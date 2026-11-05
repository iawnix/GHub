package dev.remotegpt.app

import android.app.Application
import android.os.SystemClock
import android.content.Context
import android.net.*
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.*
import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import dev.remotegpt.terminal.SessionCallbacks
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.random.Random

class WorkbenchViewModel(application: Application) : AndroidViewModel(application), SshPrompts {
    private val app = application as RemoteApplication
    private val dao = app.database.workbench()
    private val mutable = MutableStateFlow(WorkbenchState())
    val state = mutable.asStateFlow()
    private data class FileRuntime(val ssh: SshConnection, val files: RemoteFiles)
    private data class Runtime(val ssh: SshConnection, val rpc: RpcClient, val api: CodexApi, val files: RemoteFiles) {
        val observedThreads = mutableMapOf<String, ThreadSummary>()
        val persistedThreads = mutableMapOf<String, ThreadSummary>()
        val polls = SessionPollSchedule()
        val sessions = SessionSync()
        val currentReads = Semaphore(1)
        val activeReads = Semaphore(2)
        val idleReads = Semaphore(2)
        val readingResults = mutableSetOf<String>()
        var ancillary: Job? = null
        var loadedThreads: Set<String> = emptySet()
        val statusVersions = mutableMapOf<String, Long>()
        var overviewVersion = 0L
        val syncWake = Channel<Unit>(Channel.CONFLATED)
        var overviewAt = 0L
        var loadedAt = 0L
        var overviewReading = false
        var loadedReading = false
    }
    private val runtimes = mutableMapOf<String, Runtime>()
    /** SSH/SFTP remains usable while the optional Codex proxy is restarting. */
    private val fileRuntimes = mutableMapOf<String, FileRuntime>()
    private val connections = mutableMapOf<String, Job>()
    private val resultMonitors = mutableMapOf<String, Job>()
    private val creatingThreads = mutableSetOf<String>()
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val desiredConnections = mutableSetOf<String>()
    private val connectionEpochs = mutableMapOf<String, String>()
    private val retryBlocked = mutableSetOf<String>()
    private val healthChecks = mutableMapOf<String, Job>()
    private val checkAgain = mutableSetOf<String>()
    private var foreground = false
    private var backgroundKeepAlive = false
    private val connectionActive get() = foreground || backgroundKeepAlive
    private val uploads = mutableMapOf<String, Job>()
    private val persistence = mutableMapOf<String, Job>()
    private val imageLoads = mutableMapOf<String, Job>()
    private val terminalOpenings = mutableMapOf<Pair<String, String>, Job>()
    private val editorDrafts = EditorDraftStore(File(app.filesDir, "editor-drafts"))
    private val imageCache = RemoteImageCache(File(app.cacheDir, "remote-images"))
    private var imageGeneration = 0
    private var clearingImages = false
    private val memoryBudgets = mutableMapOf<String, ConversationMemoryBudget>()
    private val trims = mutableMapOf<String, Job>()
    private val cacheWrites = Mutex()
    private val draftWrites = Mutex()
    private var conversationCacheGeneration = 0L
    private val savedConversations = mutableMapOf<Pair<String, String>, Conversation>()
    private val conversationCheckpoints = ConversationCheckpoints<Pair<String, String>>(
        viewModelScope, SystemClock::elapsedRealtime,
        { (host, thread) -> writeConversation(host, thread) }, ::storageFailed,
    )
    private val backgroundRelease = BackgroundRelease(viewModelScope) {
        if (!connectionActive) {
            (fileRuntimes.keys + runtimes.keys + connections.keys).toSet().forEach { releaseConnection(it) }
            trimMemory()
        }
    }
    private val promptMutex = Mutex()
    private val diagnostic = ArrayDeque<String>()
    internal val syncTimings = SyncTimings()
    private val publication = StatePublication(state, viewModelScope,
        { before, after -> publicationFor(before, after, foreground) },
        { syncTimings.increment("ui_publications") })
    val uiState = publication.state
    /** Mirrors [foreground] so tray notifications react to app visibility as well as state. */
    private val visible = MutableStateFlow(false)
    private val taskTracker = TaskNotificationTracker()
    private val taskNotifier = TaskNotifier(app)
    init {
        TaskNotifier.createChannels(app)
        viewModelScope.launch(Dispatchers.Default) {
            // Conflated: streaming text emits often, but only transitions matter here.
            combine(state, visible) { s, v -> s to v }.conflate().collect { (s, v) ->
                taskNotifier.apply(taskTracker.update(s, v))
            }
        }
    }
    private val renderPending = mutableMapOf<String, Pair<Conversation, Long>>()
    private fun timing(operation: String, started: Long) = syncTimings.add(operation, SystemClock.elapsedRealtime() - started)
    fun conversationComposed(host: String, conversation: Conversation?) {
        val pending = renderPending[host] ?: return
        if (pending.first !== conversation) return
        timing("state_to_compose", pending.second)
        renderPending.remove(host)
    }
    private val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { networkChanged("available") } }
        override fun onLost(network: Network) { viewModelScope.launch { networkChanged("lost") } }
    }

    init {
        network.registerDefaultNetworkCallback(networkCallback)
        launchStorage { app.settings.theme.collect { value -> mutable.update { it.copy(theme = value) } } }
        launchStorage { app.settings.terminal.collect { value -> mutable.update { it.copy(terminalPreferences = value) } } }
        launchStorage {
            desiredConnections += app.settings.reconnectHosts()
            dao.hosts().collect { rows ->
                val profiles = rows.mapNotNull { runCatching { wireJson.decodeFromString<HostProfile>(it.json) }.getOrNull() }
                val storedActive = app.settings.activeHost.first()
                mutable.update { it.copy(profiles = profiles, activeHost = it.activeHost?.takeIf { id -> profiles.any { p -> p.id == id } }
                    ?: storedActive?.takeIf { id -> profiles.any { p -> p.id == id } } ?: profiles.firstOrNull()?.id) }
                for (profile in profiles) if (profile.id !in mutable.value.workspaces) {
                    val summaries = dao.threads(profile.id).mapNotNull { runCatching { wireJson.decodeFromString<ThreadSummary>(it.json) }.getOrNull() }.distinctBy { it.id }
                    val receipts = app.settings.results(profile.id)
                    val hidden = app.settings.hiddenProjects(profile.id)
                    val last = app.settings.lastThread(profile.id)?.takeUnless { id ->
                        summaries.firstOrNull { it.id == id }?.cwd?.let(::projectPath) in hidden
                    }
                    val selected = summaries.firstOrNull { it.id == last }
                    val projects = (app.settings.projects(profile.id) + summaries.filterNot { it.archived }.map { ProjectProfile(projectPath(it.cwd)) }
                        + listOfNotNull(profile.defaultCwd.takeIf { it.startsWith('/') }?.let { ProjectProfile(projectPath(it)) }))
                        .distinctBy { it.path }.filterNot { it.path in hidden }
                    val project = (app.settings.lastProject(profile.id) ?: selected?.cwd?.let(::projectPath))?.takeUnless { it in hidden }
                    update(profile.id) { it.copy(results = receipts, threads = summaries.filterNot { t -> t.archived }, selectedThread = last, selectedSummary = selected,
                        projects = projects, hiddenProjects = hidden, selectedProject = project, filePath = project.orEmpty()) }
                    editorDrafts.read(profile.id)?.let { draft -> update(profile.id) {
                        it.copy(editor = TextFile(draft.path, draft.original, draft.sha256), editorText = draft.text)
                    } }
                    if (last != null) loadCached(profile.id, last)
                    if (connectionActive && profile.id in desiredConnections) connect(profile.id)
                }
            }
        }
    }

    private fun launchStorage(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit) = viewModelScope.launch(start = start) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: java.io.IOException) { storageFailed(e) }
        catch (e: android.database.SQLException) { storageFailed(e) }
        catch (e: java.security.GeneralSecurityException) { storageFailed(e) }
    }
    private fun storageFailed(e: Exception) {
        record("storage_failed", e.javaClass.simpleName)
        message(tr(R.string.ui_cannot_read_or_write_local_data_check))
    }

    private fun update(host: String, block: (HostWorkspace) -> HostWorkspace) {
        val previous = ui(host)
        mutable.update { all ->
            val before = all.workspaces[host]
            val candidate = block(before ?: HostWorkspace())
            val stops = candidate.stoppingTurns.filterNot { (thread, turn) ->
                candidate.results[thread]?.let { it.turnId == turn && it.status in setOf("interrupted", "completed", "failed") } == true
            }
            val after = if (stops == candidate.stoppingTurns) candidate else candidate.copy(stoppingTurns = stops)
            if (after === before || after == before) all else all.copy(workspaces = all.workspaces + (host to after))
        }
        val current = ui(host)
        val budget = memoryBudgets.getOrPut(host) { ConversationMemoryBudget() }
        (current.conversations.keys - previous.conversations.keys).forEach(budget::touch)
        if (current.selectedThread != previous.selectedThread) current.selectedThread?.let(budget::touch)
        val budgetChanged = current.conversations !== previous.conversations &&
            (current.conversations.keys != previous.conversations.keys || current.conversations.any { (thread, conversation) ->
                conversation !== previous.conversations[thread] && conversation.activeTurn == null
            })
        if (budgetChanged || current.selectedThread != previous.selectedThread ||
            current.historySyncingThreads != previous.historySyncingThreads || current.approvals != previous.approvals || current.pendingQuestions != previous.pendingQuestions) scheduleTrim(host)
    }
    private fun pinned(ws: HostWorkspace) = setOfNotNull(ws.selectedThread) +
        ws.approvals.map { it.request.threadId } + ws.pendingQuestions.map { it.request.threadId } + ws.historySyncingThreads + ws.attentionSyncingThreads

    private fun scheduleTrim(host: String, aggressive: Boolean = false) {
        if (trims[host]?.isActive == true) return
        val job = launchStorage(start = CoroutineStart.LAZY) {
            try {
                delay(750)
                val budget = memoryBudgets.getOrPut(host) { ConversationMemoryBudget() }
                for (thread in budget.evictions(ui(host).conversations, pinned(ui(host)), aggressive)) {
                    val snapshot = ui(host).conversations[thread]?.takeIf { it.baselineLoaded } ?: continue
                    cacheWrites.withLock {
                        val ws = ui(host)
                        if (ws.conversations[thread] === snapshot && thread !in pinned(ws) && snapshot.activeTurn == null) {
                            saveConversation(host, thread, snapshot)
                            if (ui(host).conversations[thread] === snapshot && thread !in pinned(ui(host))) {
                                conversationCheckpoints.cancel(host to thread)
                                savedConversations.remove(host to thread)
                                runtimes[host]?.sessions?.dirty(thread)
                                update(host) { it.copy(conversations = it.conversations - thread) }
                            }
                        }
                    }
                }
            } finally { if (trims[host] === currentCoroutineContext()[Job]) trims.remove(host) }
        }
        trims[host] = job
        job.start()
    }
    fun trimMemory() { state.value.workspaces.keys.forEach { host -> trims.remove(host)?.cancel(); scheduleTrim(host, aggressive = true) } }

    private fun ui(host: String) = mutable.value.workspaces[host] ?: HostWorkspace()
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun selectTab(tab: WorkspaceTab) {
        if (state.value.workspace.selectedProject == null) return
        mutable.update { it.copy(tab = tab) }
        if (tab == WorkspaceTab.FILES && state.value.workspace.files.isEmpty()) listFiles()
    }

    private fun rememberProjects(host: String, paths: List<String>) {
        val previous = ui(host).projects
        val projects = (previous + paths.map { ProjectProfile(projectPath(it)) }).distinctBy { it.path }
            .filterNot { it.path in ui(host).hiddenProjects }
        if (previous == projects) return
        update(host) { it.copy(projects = projects) }
        launchStorage { app.settings.projects(host, projects) }
    }

    /** Local labels/visibility only. New projects go through createProject first. */
    internal fun saveProject(name: String, path: String) {
        val host = state.value.activeHost ?: return
        val root = projectPath(path.trim())
        if (!root.startsWith('/') || '\n' in root || '\u0000' in root) { message(tr(R.string.ui_enter_an_absolute_remote_directory)); return }
        update(host) { it.copy(projects = it.projects.filterNot { project -> project.path == root } + ProjectProfile(root, name.trim().ifBlank { projectName(root) }),
            hiddenProjects = it.hiddenProjects - root) }
        launchStorage { app.settings.projectVisibility(host, root, false); app.settings.projects(host, ui(host).projects) }
        openProject(root)
    }

    fun createProject(name: String, path: String, onSaved: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val host = state.value.activeHost ?: return@launch
        if (ui(host).creatingProjectPath != null) return@launch
        val root = projectPath(path.trim())
        if (!root.startsWith('/') || '\n' in root || '\u0000' in root) {
            update(host) { it.copy(projectCreationError = tr(R.string.ui_enter_an_absolute_remote_directory)) }
            onSaved(false)
            return@launch
        }
        val origin = ui(host)
        val originTab = state.value.tab
        var saved = false
        update(host) { it.copy(creatingProjectPath = root, projectCreationError = null) }
        try {
            val rt = waitForFileRuntime(host) ?: error(tr(R.string.ui_remote_files_are_temporarily_unavailable_please_try))
            rt.files.ensureDirectory(root)
            if (host !in state.value.workspaces) return@launch
            // A reconnect/profile change must not attach the old server's result
            // to a new connection. Retrying mkdirs after an uncertain result is safe.
            if (fileRuntimes[host]?.ssh !== rt.ssh && runtimes[host]?.ssh !== rt.ssh)
                error(tr(R.string.ui_remote_files_are_temporarily_unavailable_please_try))
            val projects = ui(host).projects.filterNot { it.path == root } +
                ProjectProfile(root, name.trim().ifBlank { projectName(root) })
            app.settings.projectVisibility(host, root, false)
            app.settings.projects(host, projects)
            if (host !in state.value.workspaces) return@launch
            update(host) { it.copy(projects = projects, hiddenProjects = it.hiddenProjects - root) }
            saved = true
            if (state.value.activeHost == host && ui(host).selectedProject == origin.selectedProject &&
                ui(host).selectedThread == origin.selectedThread && state.value.tab == originTab) openProject(root)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (host in state.value.workspaces) update(host) { it.copy(projectCreationError =
                tr(R.string.ui_project_directory_failed, errorText(e))) }
        } finally {
            if (host in state.value.workspaces) update(host) { it.copy(creatingProjectPath = null) }
            onSaved(saved)
        }
    }

    /** Local navigation preference only. Remote sessions, files and drafts survive. */
    fun hideProject(path: String) {
        val host = state.value.activeHost ?: return
        val root = projectPath(path)
        if (ui(host).selectedProject == root) {
            switchProject(host, null)
            mutable.update { it.copy(tab = WorkspaceTab.SESSIONS) }
        }
        update(host) { it.copy(projects = it.projects.filterNot { project -> project.path == root }, hiddenProjects = it.hiddenProjects + root) }
        launchStorage { app.settings.projectVisibility(host, root, true); app.settings.projects(host, ui(host).projects) }
        message(tr(R.string.ui_project_hidden))
    }

    fun openProject(path: String) {
        val host = state.value.activeHost ?: return
        switchProject(host, projectPath(path))
        mutable.update { it.copy(tab = WorkspaceTab.SESSIONS) }
    }

    private fun switchProject(host: String, root: String?) {
        update(host) { ws ->
            val files = ws.projectFiles + listOfNotNull(ws.selectedProject?.let { it to ProjectFiles(ws.filePath, ws.files) })
            val remembered = files[root]
            ws.copy(selectedProject = root, selectedThread = null, selectedSummary = null,
                filePath = remembered?.path ?: root.orEmpty(), files = remembered?.entries.orEmpty(), projectFiles = files,
                selectedTerminal = ws.projectTerminals[root] ?: ws.terminals.firstOrNull { it.projectPath == root }?.id,
                loadingFiles = false, loadingHistory = false)
        }
        launchStorage { app.settings.location(host, root, null) }
    }

    fun backToProjects() {
        state.value.activeHost?.let { switchProject(it, null) }
        mutable.update { it.copy(tab = WorkspaceTab.SESSIONS) }
        state.value.activeHost?.let { host ->
            val ws = ui(host)
            if (ws.search.isNotBlank() || ws.archived) {
                update(host) { it.copy(search = "", archived = false) }
                refreshThreads(host)
            }
        }
    }

    fun navigateBack() {
        when {
            state.value.tab != WorkspaceTab.SESSIONS -> selectTab(WorkspaceTab.SESSIONS)
            state.value.workspace.selectedThread != null -> backToProjects()
            state.value.workspace.selectedProject != null -> backToProjects()
        }
    }
    fun theme(value: String) { launchStorage { app.settings.theme(value) } }
    fun terminalFontSize(value: Float) { launchStorage { app.settings.terminalFontSize(value) } }
    fun terminalExtraKeys(value: Boolean) { launchStorage { app.settings.terminalExtraKeys(value) } }
    fun selectHost(id: String) { mutable.update { it.copy(activeHost = id, tab = WorkspaceTab.SESSIONS) }; launchStorage { app.settings.activeHost(id) } }

    fun saveProfile(profile: HostProfile, credentials: Map<String, Credential>, onSaved: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            require(profile.name.isNotBlank()) { tr(R.string.ui_enter_a_connection_name) }
            require(profile.codexPath.isNotBlank() && '\n' !in profile.codexPath) { tr(R.string.ui_enter_the_codex_executable_path) }
            for (endpoint in profile.jumps + profile.endpoint) {
                require(endpoint.host.isNotBlank() && endpoint.username.isNotBlank() && endpoint.port in 1..65535) { tr(R.string.ui_enter_a_host_username_and_valid_port) }
            }
            val used = (profile.jumps + profile.endpoint).map { it.credentialId }.toSet()
            val previous = state.value.profiles.firstOrNull { it.id == profile.id }
            val credentialsChanged = withContext(Dispatchers.IO) {
                val changed = credentials.filterKeys { it in used }.filter { (id, secret) -> runCatching { app.secrets.read(id) }.getOrNull() != secret }
                changed.forEach { (id, secret) -> app.secrets.save(id, secret) }
                changed.isNotEmpty()
            }
            val connectionChanged = previous != null && (previous.endpoint != profile.endpoint || previous.jumps != profile.jumps ||
                previous.codexPath != profile.codexPath || previous.socketPath != profile.socketPath || credentialsChanged)
            val needsReconnect = connectionChanged && (profile.id in desiredConnections || ui(profile.id).connection != ConnectionStatus.OFFLINE)
            if (connectionChanged) disconnect(profile.id)
            dao.putHost(HostRow(profile.id, wireJson.encodeToString(profile)))
            withContext(Dispatchers.IO) { previous?.let { old -> (old.jumps + old.endpoint).filter { it.credentialId !in used }.forEach { app.secrets.remove(it.credentialId) } } }
            if (previous == null && state.value.profiles.none { it.id != profile.id }) selectHost(profile.id)
            message(tr(if (needsReconnect) R.string.ui_connection_saved_reconnect else R.string.ui_connection_saved))
            onSaved(true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message(e.message ?: tr(R.string.ui_save_failed)); onSaved(false) }
    }
    fun deleteProfile(profile: HostProfile) = launchStorage {
        disconnect(profile.id)
        conversationCacheGeneration++
        conversationCheckpoints.cancelMatching { it.first == profile.id }
        trims.remove(profile.id)?.cancel()
        memoryBudgets.remove(profile.id)
        editorDrafts.write(profile.id, null)
        persistence.keys.filter { it.contains(":${profile.id}:") }.forEach { persistence.remove(it)?.cancel() }
        ui(profile.id).drafts.values.flatMap { it.attachments }.forEach { uploads.remove(it.id)?.cancel() }
        mutable.update { it.copy(workspaces = it.workspaces - profile.id) }
        cacheWrites.withLock {
            savedConversations.keys.removeAll { it.first == profile.id }
            dao.deleteWorkspace(profile.id)
        }
        withContext(Dispatchers.IO) { (profile.jumps + profile.endpoint).forEach { app.secrets.remove(it.credentialId) } }
    }

    override suspend fun credential(id: String) = withContext(Dispatchers.IO) { app.secrets.read(id) }
    override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String): Boolean = promptMutex.withLock {
        val previous = app.settings.fingerprint(host, port)
        if (previous == fingerprint) return@withLock true
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { mutable.update { it.copy(prompt = ConnectionPrompt.HostKey(HostKeyChallenge(host, port, algorithm, fingerprint, previous), answer)) } }
        try {
            val trusted = withTimeout(110_000) { answer.await() }
            if (trusted) app.settings.trust(host, port, fingerprint)
            trusted
        } finally { withContext(NonCancellable + Dispatchers.Main) { mutable.update { it.copy(prompt = null) } } }
    }
    override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = promptMutex.withLock {
        val answer = CompletableDeferred<List<String>>()
        withContext(Dispatchers.Main) { mutable.update { it.copy(prompt = ConnectionPrompt.Keyboard(prompt, answer)) } }
        try { withTimeout(110_000) { answer.await() } }
        finally { withContext(NonCancellable + Dispatchers.Main) { mutable.update { it.copy(prompt = null) } } }
    }

    fun connect(host: String? = state.value.activeHost, bootstrap: Boolean = false) {
        if (host == null) return
        retryBlocked.remove(host)
        desiredConnections += host
        if (runtimes[host] != null) { checkConnection(host); return }
        reconnectJobs.remove(host)?.cancel()
        startConnection(host, bootstrap, recovering = false)
    }

    private fun startConnection(host: String, bootstrap: Boolean = false, recovering: Boolean = true) {
        if (connections[host]?.isActive == true || runtimes[host] != null || host in retryBlocked) return
        val profile = state.value.profiles.firstOrNull { it.id == host } ?: return
        if (!hasNetwork()) {
            update(host) { it.copy(connection = ConnectionStatus.WAITING_NETWORK) }
            scheduleReconnect(host)
            return
        }
        val epoch = UUID.randomUUID().toString()
        connectionEpochs[host] = epoch
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            update(host) { it.copy(connection = if (recovering) ConnectionStatus.RECONNECTING else ConnectionStatus.CONNECTING,
                error = null, needsBootstrap = false, attentionSyncingThreads = attentionThreads(it),
                approvals = emptyList(), pendingQuestions = emptyList()) }
            var ssh: SshConnection? = null
            var rpc: RpcClient? = null
            var stage = "ssh"
            try {
                record("connect_start", "epoch=${epoch} attempt=${ui(host).reconnectAttempt}")
                ssh = SshConnection.connect(profile, this@WorkbenchViewModel)
                fileRuntimes.remove(host)?.let { previous -> if (previous.ssh !== ssh) withContext(NonCancellable + Dispatchers.IO) { runCatching { previous.ssh.close() } } }
                fileRuntimes[host] = FileRuntime(ssh, RemoteFiles(ssh))
                stage = "daemon"
                val cliVersion = ssh.command("${shellQuote(profile.codexPath)} --version").trim()
                if (!CodexCompatibility.isSupported(cliVersion)) throw UnsupportedServer(tr(R.string.ui_unsupported_cli_version_the_minimum_is_the , cliVersion.ifBlank { tr(R.string.ui_unknown) }, CodexCompatibility.MINIMUM_VERSION))
                if (bootstrap) ssh.bootstrap()
                val versions = wireJson.parseToJsonElement(ssh.version()).jsonObject
                if (versions.string("status") != "running") {
                    update(host) { it.copy(needsBootstrap = true) }
                    throw DaemonNotRunning()
                }
                val version = versions.string("appServerVersion")
                    ?: throw UnsupportedServer(tr(R.string.ui_app_server_did_not_report_a_version , CodexCompatibility.MINIMUM_VERSION))
                if (!CodexCompatibility.isSupported(version)) throw UnsupportedServer(tr(R.string.ui_unsupported_app_server_version_the_minimum_is , version, CodexCompatibility.MINIMUM_VERSION))
                stage = "proxy_initialize"
                rpc = RpcClient(ProxyTransport(ssh), onTiming = { method, milliseconds, outcome ->
                    syncTimings.add("rpc.$method.$outcome", milliseconds)
                }, onTraffic = { direction, bytes ->
                    syncTimings.increment("rpc_${direction}_messages")
                    syncTimings.increment("rpc_${direction}_bytes", bytes)
                })
                val initialized = rpc.connect()
                val userAgent = initialized.string("userAgent")
                if (!CodexCompatibility.isSupported(userAgent)) throw UnsupportedServer(tr(R.string.ui_the_socket_did_not_report_a_supported , userAgent ?: tr(R.string.ui_unknown), CodexCompatibility.MINIMUM_VERSION))
                val rt = Runtime(ssh, rpc, CodexApi(rpc), RemoteFiles(ssh))
                ensureActive()
                if (connectionEpochs[host] != epoch) return@launch
                runtimes[host] = rt
                record("proxy_ready", "epoch=${epoch} rpc=${rpc.epoch} cli=${CodexCompatibility.parse(cliVersion)} daemon=${version} protocol=${CodexCompatibility.parse(userAgent)}")
                // The protocol is ready. Metadata and discovery must not gate the selected conversation.
                update(host) { it.copy(connection = ConnectionStatus.ONLINE, serverVersion = version,
                    error = null, reconnectAttempt = 0, loadingHistory = it.selectedThread != null) }
                viewModelScope.launch { consume(host, rt) }
                launchStorage { app.settings.reconnect(host, true) }
                stage = "synchronize"
                val restoreStarted = SystemClock.elapsedRealtime()
                coroutineScope {
                    val restore = async(start = CoroutineStart.UNDISPATCHED) { restoreSelectedThread(host, rt) }
                    startAncillarySync(host, rt)
                    monitorResults(host, rt)
                    restore.await()
                    restorePendingAttention(host, rt)
                }
                ensureActive()
                if (runtimes[host] !== rt || connectionEpochs[host] != epoch) return@launch
                timing("connection_restore", restoreStarted)
                record("connected", "epoch=${epoch} version=${version}")
            } catch (e: Exception) {
                val current = connectionEpochs[host] == epoch
                if (current) {
                    runtimes[host]?.takeIf { it.rpc === rpc }?.let { retireRuntime(host, it) }
                    if (e !is CancellationException || e is TimeoutCancellationException) {
                        val permanent = requiresAttention(e, stage)
                        if (permanent) retryBlocked += host
                        update(host) { it.copy(connection = if (permanent) ConnectionStatus.ERROR else recoveryStatus(),
                            error = errorText(e), approvals = emptyList(), pendingQuestions = emptyList()) }
                        record("connect_failed", "epoch=${epoch} stage=${stage} reason=${connectionDiagnostic(e)} retry=${!permanent}")
                    }
                }
                val closeSsh = ssh == null || fileRuntimes[host]?.ssh !== ssh
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { rpc?.close() }
                    if (closeSsh) runCatching { ssh?.close() }
                }
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            } finally {
                if (connectionEpochs[host] == epoch) {
                    connections.remove(host)
                    if (runtimes[host] == null) scheduleReconnect(host)
                    else if (host in checkAgain) checkConnection(host)
                }
            }
        }
        connections[host] = job
        job.start()
    }

    private fun requiresAttention(error: Throwable, stage: String): Boolean {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        if (causes.any { it is HostKeyRejected || it is UnsupportedServer || it is DaemonNotRunning }) return true
        if (causes.any { it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.io.EOFException }) return false
        return causes.any { it is RpcException || it.javaClass.simpleName == "UserAuthException" ||
            it is IllegalArgumentException || it is java.security.GeneralSecurityException } ||
            (stage == "ssh" && error is TimeoutCancellationException) ||
            (stage == "daemon" && error is RemoteCommandFailed)
    }

    private fun hasNetwork() = network.activeNetwork != null
    private fun recoveryStatus() = if (hasNetwork()) ConnectionStatus.RECONNECTING else ConnectionStatus.WAITING_NETWORK

    /** Remove ownership before asynchronous close: late callbacks cannot change a new connection. */
    private fun retireRuntime(host: String, rt: Runtime) {
        if (runtimes[host] !== rt) return
        runtimes.remove(host)
        rt.ancillary?.cancel()
        renderPending.remove(host)
        resultMonitors.remove(host)?.cancel()
        rt.rpc.invalidate()
        update(host) { it.copy(attentionSyncingThreads = attentionThreads(it), stoppingTurns = emptyMap(),
            approvals = emptyList(), pendingQuestions = emptyList(), loadingHistory = false, loadingThreads = false) }
        // Keep the SSH transport for SFTP/terminal while only the Codex proxy
        // is being rebuilt. Full disconnect closes it below.
        viewModelScope.launch(Dispatchers.IO) { rt.rpc.close() }
    }

    fun disconnect(host: String? = state.value.activeHost) {
        if (host == null) return
        desiredConnections.remove(host)
        retryBlocked.remove(host)
        launchStorage { app.settings.reconnect(host, false) }
        releaseConnection(host)
    }

    private fun releaseConnection(host: String) {
        terminalOpenings.keys.filter { it.first == host }.toList().forEach { terminalOpenings.remove(it)?.cancel() }
        ui(host).terminals.forEach { it.session.finishIfRunning() }
        imageLoads.keys.filter { it.startsWith("$host:") }.toList().forEach { imageLoads.remove(it)?.cancel() }
        connectionEpochs[host] = UUID.randomUUID().toString()
        reconnectJobs.remove(host)?.cancel()
        healthChecks.remove(host)?.cancel()
        checkAgain.remove(host)
        connections.remove(host)?.cancel()
        runtimes[host]?.let { retireRuntime(host, it) }
        fileRuntimes.remove(host)?.let { runtime -> viewModelScope.launch(Dispatchers.IO) { runCatching { runtime.ssh.close() } } }
        update(host) { it.copy(connection = ConnectionStatus.OFFLINE, approvals = emptyList(), pendingQuestions = emptyList(), error = null, reconnectAttempt = 0,
            terminals = emptyList(), selectedTerminal = null, projectTerminals = emptyMap(), openingTerminalProjects = emptySet(), imageLoading = emptySet()) }
    }

    private fun reconnectOffline(immediate: Boolean = false) {
        if (!connectionActive) return
        desiredConnections.toList().forEach { host ->
            if (runtimes[host] == null && connections[host]?.isActive != true) scheduleReconnect(host, immediate)
        }
    }
    private fun scheduleReconnect(host: String, immediate: Boolean = false) {
        if (!connectionActive || host !in desiredConnections || host in retryBlocked || runtimes[host] != null) return
        if (immediate) reconnectJobs.remove(host)?.cancel()
        if (reconnectJobs[host]?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0
            while (isActive && connectionActive && host in desiredConnections && host !in retryBlocked) {
                if (runtimes[host] != null) break
                if (!hasNetwork()) {
                    update(host) { it.copy(connection = ConnectionStatus.WAITING_NETWORK) }
                    // A network callback wakes the next attempt; no connection attempts while offline.
                    break
                }
                update(host) { it.copy(connection = ConnectionStatus.RECONNECTING) }
                val base = listOf(1000L, 2000L, 4000L, 8000L, 15000L, 30000L)[attempt.coerceAtMost(5)]
                if (!(immediate && attempt == 0)) delay((base * Random.nextDouble(0.8, 1.2)).toLong().coerceAtMost(30_000))
                if (!connectionActive || !hasNetwork()) continue
                update(host) { it.copy(reconnectAttempt = it.reconnectAttempt + 1) }
                record("reconnect_attempt", "attempt=${ui(host).reconnectAttempt}")
                startConnection(host)
                connections[host]?.join()
                attempt++
            }
        }
        reconnectJobs[host] = job
        job.start()
    }

    private fun networkChanged(event: String) {
        val caps = network.activeNetwork?.let { network.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        record("network", "${event} transport=${type} foreground=${foreground} keepAlive=${backgroundKeepAlive}")
        if (!connectionActive) return
        (runtimes.keys + connections.keys).toSet().forEach { checkConnection(it) }
        reconnectOffline(immediate = true)
    }

    private fun checkConnection(host: String) {
        if (!connectionActive) return
        if (connections[host]?.isActive == true) { checkAgain += host; return }
        val rt = runtimes[host] ?: return
        if (healthChecks[host]?.isActive == true) { checkAgain += host; return }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    checkAgain.remove(host)
                    if (runtimes[host] !== rt) return@launch
                    record("health_check", "epoch=${rt.rpc.epoch}")
                    try {
                        // Stable, read-only API; a response proves both proxy and daemon are reachable.
                        withTimeout(8_000) { rt.rpc.request("thread/loaded/list", buildJsonObject { put("limit", 1) }) }
                    } catch (e: RpcException) {
                        // A protocol error is also proof of liveness; never tear down SSH for it.
                        record("health_response", "rpc_error code=${e.code}")
                    }
                    if (runtimes[host] !== rt) return@launch
                    // A healthy transport stays usable while the selected conversation catches up.
                    ui(host).selectedThread?.let { thread ->
                        if (ui(host).conversations[thread]?.activeTurn != null) rt.sessions.dirty(thread)
                    }
                    restoreSelectedThread(host, rt)
                    restorePendingAttention(host, rt)
                } while (host in checkAgain && connectionActive)
                if (runtimes[host] === rt) {
                    update(host) { it.copy(connection = ConnectionStatus.ONLINE, error = null) }
                    monitorResults(host, rt)
                    record("health_ok", "epoch=${rt.rpc.epoch}")
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (runtimes[host] === rt) {
                    record("health_failed", "epoch=${rt.rpc.epoch} reason=${connectionDiagnostic(e)}")
                    if (e is RpcException && rt.rpc.isConnected) {
                        // A rejected thread resume/read does not make an otherwise healthy SSH
                        // connection unusable. Keep independent terminals and file transfers alive.
                        retryBlocked += host
                        update(host) { it.copy(connection = ConnectionStatus.ERROR, approvals = emptyList(), pendingQuestions = emptyList(),
                            error = tr(R.string.ui_the_server_is_connected_but_session_sync)) }
                        return@launch
                    }
                    retireRuntime(host, rt)
                    if (requiresAttention(e, "health")) retryBlocked += host
                    update(host) { it.copy(connection = if (host in retryBlocked) ConnectionStatus.ERROR else recoveryStatus(), error = errorText(e)) }
                    scheduleReconnect(host, immediate = true)
                }
            } finally {
                if (healthChecks[host] === currentCoroutineContext()[Job]) {
                    healthChecks.remove(host)
                    checkAgain.remove(host)
                }
            }
        }
        healthChecks[host] = job
        job.start()
    }

    fun onForeground() {
        foreground = true
        visible.value = true
        publication.flush()
        backgroundRelease.update(connectionActive)
        record("lifecycle", "foreground")
        (runtimes.keys + connections.keys).toSet().forEach { checkConnection(it) }
        reconnectOffline(immediate = true)
    }
    fun onBackground() {
        foreground = false
        visible.value = false
        backgroundRelease.update(connectionActive)
        runtimes.values.forEach { it.syncWake.trySend(Unit) }
        record("lifecycle", "background")
        if (backgroundKeepAlive) {
            reconnectOffline(immediate = true)
            flush()
            return
        }
        resultMonitors.values.forEach { it.cancel() }
        reconnectJobs.values.forEach { it.cancel() }; reconnectJobs.clear()
        healthChecks.values.forEach { it.cancel() }; healthChecks.clear(); checkAgain.clear()
        flush()
    }

    /** Called by the foreground service; the remote daemon remains authoritative. */
    fun setBackgroundKeepAlive(enabled: Boolean) {
        if (backgroundKeepAlive == enabled) return
        backgroundKeepAlive = enabled
        backgroundRelease.update(connectionActive)
        mutable.update { it.copy(backgroundKeepAlive = enabled) }
        record("keep_alive", if (enabled) "enabled" else "disabled")
        if (enabled) {
            reconnectOffline(immediate = true)
            runtimes.forEach { (host, runtime) -> monitorResults(host, runtime) }
        } else if (!foreground) {
            resultMonitors.values.forEach { it.cancel() }
            reconnectJobs.values.forEach { it.cancel() }; reconnectJobs.clear()
            healthChecks.values.forEach { it.cancel() }; healthChecks.clear(); checkAgain.clear()
        }
    }

    private suspend fun consume(host: String, rt: Runtime) {
        var failure: Throwable? = null
        try { for (event in rt.rpc.events) {
            if (runtimes[host] !== rt || !rt.rpc.isConnected) break
            val applyStarted = SystemClock.elapsedRealtime()
            val received = when (event) {
                is RpcEvent.Notification -> event.receivedNanos
                is RpcEvent.Request -> event.receivedNanos
                else -> null
            }
            received?.let { syncTimings.add("event_queue", (System.nanoTime() - it) / 1_000_000) }
            when (event) {
                is RpcEvent.Barrier -> event.applied.complete(Unit)
                is RpcEvent.Disconnected -> { failure = event.reason; break }
                is RpcEvent.Request -> {
                    if (event.request.supportedApproval) update(host) { ws ->
                        val previous = ws.approvals.firstOrNull { it.request.key == event.request.key }
                        ws.copy(approvals = ws.approvals.filterNot { it.request.key == event.request.key } +
                            (previous?.copy(request = event.request) ?: PendingApproval(event.request)))
                    }
                    else if (event.request.userQuestions != null) update(host) { ws ->
                        val previous = ws.pendingQuestions.firstOrNull { it.request.key == event.request.key }
                        ws.copy(pendingQuestions = ws.pendingQuestions.filterNot { it.request.key == event.request.key } +
                            (previous?.copy(request = event.request) ?: PendingQuestions(event.request)))
                    }
                    else {
                        rt.rpc.rejectUnsupported(event.request)
                        message(tr(R.string.ui_this_client_cannot_handle_no_permission_was , event.request.method))
                    }
                }
                is RpcEvent.Notification -> {
                    val thread = event.params.string("threadId")
                    if (event.method in setOf("turn/started", "turn/completed", "thread/status/changed", "thread/started", "thread/archived", "thread/unarchived", "thread/name/updated")) {
                        val changed = thread ?: event.params.obj("thread").string("id")
                        changed?.let { rt.statusVersions[it] = (rt.statusVersions[it] ?: 0) + 1 }
                        rt.overviewVersion++
                        val now = SystemClock.elapsedRealtime()
                        changed?.let { rt.polls.pushed(it, now) }
                        if (event.method in setOf("thread/started", "thread/archived", "thread/unarchived", "thread/name/updated")) {
                            rt.overviewAt = minOf(rt.overviewAt, now + 750)
                            if (event.method == "thread/started") rt.loadedAt = minOf(rt.loadedAt, now + 750)
                        }
                        rt.syncWake.trySend(Unit)
                    }
                    if (event.method == "serverRequest/resolved") update(host) { ws -> ws.copy(approvals = ws.approvals.filterNot { it.request.id == event.params["requestId"] },
                        pendingQuestions = ws.pendingQuestions.filterNot { it.request.id == event.params["requestId"] }) }
                    if (event.method == "thread/started") {
                        val summary = threadSummary(event.params.obj("thread"))
                        rt.observedThreads[summary.id] = summary
                        update(host) { ws ->
                            if (ws.archived) return@update ws
                            val existing = ws.threads.firstOrNull { it.id == summary.id }
                            val merged = summary.copy(awaitingFirstMessage = existing?.awaitingFirstMessage == true)
                            ws.copy(threads = listOf(merged) + ws.threads.filterNot { it.id == summary.id })
                        }
                    }
                    if (thread != null) {
                        if (event.method == "turn/completed") rt.sessions.dirty(thread)
                        if (event.method in setOf("thread/closed", "thread/archived", "thread/unarchived") ||
                            (event.method == "thread/status/changed" && event.params.obj("status").string("type") == "notLoaded")) {
                            rt.sessions.closed(thread)
                        }
                        if (event.method == "turn/started" || event.method == "turn/completed") {
                            val turn = event.params.obj("turn")
                            turn.string("id")?.let { turnId ->
                                update(host) { ws -> ws.copy(results = ws.results + (thread to observeResult(ws.results[thread], turnId,
                                    if (event.method == "turn/started") "inProgress" else turn.string("status").orEmpty(), 0, liveEvent = true)),
                                    threads = ws.threads.map { if (it.id == thread) it.copy(status = if (event.method == "turn/started") "active" else "idle") else it }) }
                                persistResults(host)
                            }
                        }
                        // Hydrate the durable body before applying a delta. The ordered
                        // event consumer holds subsequent events while this read completes.
                        val after = applyConversationNotification(thread, event.method, event.params,
                            current = { ui(host).conversations[thread] },
                            restore = { loadConversationCache(host, thread) },
                            isCurrent = { runtimes[host] === rt && rt.rpc.isConnected },
                        )
                        if (runtimes[host] !== rt || !rt.rpc.isConnected) break
                        var conversationChanged = false
                        update(host) { ws ->
                            val before = ws.conversations[thread]
                            val threads = when (event.method) {
                                "thread/name/updated" -> ws.threads.map { if (it.id == thread) it.copy(title = event.params.string("name") ?: it.title) else it }
                                "thread/status/changed" -> ws.threads.map { if (it.id == thread) it.copy(status = event.params.obj("status").string("type") ?: it.status) else it }
                                "thread/archived", "thread/unarchived" -> ws.threads.filterNot {
                                    it.id == thread && it.archived != (event.method == "thread/archived")
                                }
                                else -> ws.threads
                            }
                            conversationChanged = after != null && after != before
                            ws.copy(conversations = if (conversationChanged && after != null) ws.conversations + (thread to after) else ws.conversations, threads = threads,
                                threadListRevision = ws.threadListRevision +
                                    if (event.method in setOf("thread/archived", "thread/unarchived")) 1 else 0)
                        }
                        if (conversationChanged) {
                            if (Timeline.needsBaseline(event.method)) persistConversation(host, thread, immediate = event.method == "turn/completed")
                            if (ui(host).selectedThread == thread) ui(host).conversations[thread]?.let {
                                renderPending[host] = it to SystemClock.elapsedRealtime()
                            }
                        }
                        if (event.method == "turn/completed" && ui(host).selectedThread == thread)
                            viewModelScope.launch {
                                try { synchronizeHistory(host, thread, rt) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { record("history_sync_failed", e.javaClass.simpleName) }
                            }
                    }
                }
            }
            // Critical protocol events bypass visual throttling; message order and
            // persistence are already applied to authoritative state above.
            when (event) {
                is RpcEvent.Request -> publication.flush()
                is RpcEvent.Notification -> when (event.method) {
                    "item/started", "item/completed", "turn/started", "turn/completed",
                    "thread/status/changed", "serverRequest/resolved", "error" -> publication.flush()
                }
                else -> Unit
            }
            if (received != null) timing("event_apply", applyStarted)
        }
        } catch (e: Exception) {
            failure = e
            if (e is CancellationException) throw e
        } finally {
            if (runtimes[host] === rt) {
                val reason = failure ?: rt.rpc.disconnectReason
                record("disconnected", "epoch=${rt.rpc.epoch} reason=${connectionDiagnostic(reason)} foreground=${foreground}")
                retireRuntime(host, rt)
                update(host) { it.copy(connection = recoveryStatus(), error = when (reason) {
                    is EventBacklog -> tr(R.string.ui_output_processing_is_behind_restoring_the_session)
                    is ProxyClosed -> if (reason.heartbeat) tr(R.string.ui_connection_heartbeat_timed_out_reconnecting) else tr(R.string.ui_the_remote_proxy_closed_reconnecting)
                    else -> tr(R.string.ui_connection_interrupted_restoring_the_original_session)
                }) }
                scheduleReconnect(host)
            }
        }
    }

    private fun record(event: String, detail: String) {
        if (diagnostic.size >= 200) diagnostic.removeFirst()
        diagnostic.addLast("${System.currentTimeMillis()} ${event} ${detail}")
    }
    private fun errorText(e: Throwable): String = when (e) {
        is UncertainDelivery -> tr(R.string.ui_uncertain_delivery)
        is ThreadUnavailable -> tr(R.string.ui_thread_unavailable_detail)
        is FileConflict -> tr(R.string.ui_the_file_changed_after_you_opened_it)
        is UnsupportedServer, is RpcException -> e.message.orEmpty()
        else -> when {
            isOversizedCache(e) -> tr(R.string.ui_local_session_cache_was_too_large_and)
            e.javaClass.simpleName.contains("UserAuth") -> tr(R.string.ui_ssh_authentication_failed_check_the_username_key)
            e.javaClass.simpleName.contains("Transport") -> tr(R.string.ui_ssh_connection_failed_check_the_host_fingerprint)
            else -> e.message?.take(240) ?: tr(R.string.ui_operation_failed_check_the_connection_and_try)
        }
    }

    /** File and terminal actions use SSH independently from the Codex proxy. */
    private suspend fun waitForFileRuntime(host: String): FileRuntime? {
        fileRuntimes[host]?.let { return it }
        runtimes[host]?.let { runtime -> return FileRuntime(runtime.ssh, runtime.files) }
        if (!foreground) return null
        desiredConnections += host
        connect(host)
        withTimeoutOrNull(45_000) {
            while (currentCoroutineContext().isActive) {
                fileRuntimes[host]?.let { return@withTimeoutOrNull it }
                if (host in retryBlocked && connections[host]?.isActive != true) break
                delay(200)
            }
            null
        }?.let { return it }
        return fileRuntimes[host]
    }

    private fun perform(host: String? = state.value.activeHost, block: suspend (String, Runtime) -> Unit) = viewModelScope.launch {
        if (host == null) return@launch
        val rt = runtimes[host] ?: run { message(tr(R.string.ui_you_can_continue_after_reconnecting_your_draft)); return@launch }
        if (ui(host).connection != ConnectionStatus.ONLINE || !rt.rpc.isConnected) return@launch
        try { block(host, rt) } catch (e: Exception) {
            if (runtimes[host] === rt && generateSequence<Throwable>(e) { it.cause }.take(8).any { it is TimeoutCancellationException }) {
                record("request_failed", "timeout"); checkConnection(host)
            }
            if (e !is CancellationException && runtimes[host] === rt) message(errorText(e))
        }
    }

    private fun performFile(host: String? = state.value.activeHost, block: suspend (String, FileRuntime) -> Unit) = viewModelScope.launch {
        if (host == null) return@launch
        val rt = waitForFileRuntime(host)
        if (rt == null) {
            update(host) { it.copy(loadingFiles = false) }
            message(tr(R.string.ui_remote_files_are_temporarily_unavailable_please_try))
            return@launch
        }
        try { block(host, rt) } catch (e: Exception) {
            if (e !is CancellationException) message(errorText(e))
        }
    }

    fun refreshThreads(host: String? = state.value.activeHost, more: Boolean = false, background: Boolean = false) = perform(host) { id, rt ->
        refreshThreadsNow(id, rt, more, background)
    }
    private suspend fun refreshThreadsNow(id: String, rt: Runtime, more: Boolean = false, background: Boolean = false, observe: Boolean = true) {
        if (!background) update(id) { it.copy(loadingThreads = true) }
        try {
            val ws = ui(id)
            val page = rt.api.threads(if (more) ws.nextThreads else null, ws.archived, ws.search)
            if (runtimes[id] !== rt || ui(id).archived != ws.archived || ui(id).search != ws.search ||
                ui(id).threadListRevision != ws.threadListRevision) return
            // Empty threads are not listed by the daemon until the first message.
            // Preserve them locally, including across explicit refresh and filters.
            val pending = ui(id).threads.filter { it.awaitingFirstMessage }
            val threads = (page.items + pending + if (background || more) ui(id).threads else emptyList()).distinctBy { it.id }
            update(id) { it.copy(threads = threads, nextThreads = if (background && ws.threads.isNotEmpty()) it.nextThreads else page.nextCursor) }
            rememberProjects(id, page.items.map { it.cwd })
            if (!background && !more && !ws.archived && ws.search.isBlank()) {
                dao.deleteThreads(id)
                rt.persistedThreads.clear()
            }
            dao.putThreads((page.items + pending).distinctBy { it.id }.map { ThreadRow(id, it.id, wireJson.encodeToString(it)) })
            if (observe) observeResults(id, rt, if (background) (page.items + ws.threads.filter { ws.results[it.id]?.status == "inProgress" }).distinctBy { it.id } else page.items)
        } finally { if (!background && runtimes[id] === rt) update(id) { it.copy(loadingThreads = false) } }
    }
    private fun startAncillarySync(host: String, rt: Runtime) {
        rt.ancillary = viewModelScope.launch {
            supervisorScope {
                suspend fun optional(name: String, work: suspend () -> Unit) {
                    try { work() }
                    catch (e: TimeoutCancellationException) { record(name, "timeout") }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { record(name, e.javaClass.simpleName) }
                }
                if (ui(host).archived || ui(host).search.isNotBlank()) launch {
                    optional("filtered_list_sync_failed") { refreshThreadsNow(host, rt, background = true, observe = false) }
                }
                launch { optional("account_sync_failed") {
                    val account = rt.api.account()
                    val label = account.obj("account").string("email") ?: account.obj("account").string("type")
                        ?: if (account["requiresOpenaiAuth"]?.jsonPrimitive?.booleanOrNull == false) tr(R.string.ui_custom_model_provider)
                        else tr(R.string.ui_run_codex_login_on_the_server_first)
                    if (runtimes[host] === rt) update(host) { it.copy(account = label) }
                } }
                launch { optional("models_sync_failed") {
                    val models = rt.api.models()
                    if (runtimes[host] === rt) update(host) { it.copy(models = models.map { model -> model.id to model.name }, modelOptions = models) }
                } }
            }
        }
    }

    private fun urgentThreads(host: String): Set<String> {
        val ws = ui(host)
        return listOfNotNull(ws.selectedThread).toSet() + ws.results.filterValues { it.status == "inProgress" }.keys +
            ws.threads.filter { it.status == "active" }.map { it.id }
    }

    private fun pollTargets(host: String, rt: Runtime): List<SessionPollSchedule.Target> {
        val ws = ui(host)
        val ids = rt.loadedThreads + urgentThreads(host) + rt.polls.pendingIds() + rt.readingResults
        rt.polls.retain(ids)
        return ids.map { id -> SessionPollSchedule.Target(id, ws.selectedThread == id,
            ws.results[id]?.status == "inProgress" || summary(host, id)?.status == "active",
            rt.sessions.isSubscribed(id)) }
    }

    private fun monitorResults(host: String, rt: Runtime) {
        if (!connectionActive || runtimes[host] !== rt || ui(host).connection != ConnectionStatus.ONLINE) return
        // Startup / successful foreground health check requests one reconciliation.
        urgentThreads(host).forEach { rt.polls.hint(it, SystemClock.elapsedRealtime(), immediate = true) }
        rt.overviewAt = 0; rt.loadedAt = 0
        rt.syncWake.trySend(Unit)
        val previousMonitor = resultMonitors[host]
        if (previousMonitor?.isActive == true) return
        resultMonitors[host] = viewModelScope.launch {
            // Reusing the same connection after a quick background/foreground
            // transition must wait for canceled workers to release their slots.
            previousMonitor?.join()
            coroutineScope {
                while (isActive && connectionActive && runtimes[host] === rt) {
                    val now = SystemClock.elapsedRealtime()
                    if (ui(host).connection == ConnectionStatus.ONLINE) {
                        if (!rt.overviewReading && now >= rt.overviewAt) {
                            rt.overviewReading = true
                            rt.overviewAt = Long.MAX_VALUE
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try { discoverRecentResults(host, rt) }
                                catch (e: TimeoutCancellationException) { record("overview_sync_failed", "timeout") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { record("overview_sync_failed", e.javaClass.simpleName) }
                                finally {
                                    rt.overviewReading = false
                                    rt.overviewAt = minOf(rt.overviewAt, SystemClock.elapsedRealtime() + if (foreground) 15_000 else 30_000)
                                    rt.syncWake.trySend(Unit)
                                }
                            }
                        }
                        if (!rt.loadedReading && now >= rt.loadedAt) {
                            rt.loadedReading = true
                            rt.loadedAt = Long.MAX_VALUE
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    val loaded = withTimeout(8_000) { rt.api.loadedThreads().toSet() }
                                    (loaded - rt.loadedThreads).forEach { rt.polls.hint(it, SystemClock.elapsedRealtime(), immediate = true) }
                                    rt.loadedThreads = loaded
                                }
                                catch (e: TimeoutCancellationException) { record("loaded_sync_failed", "timeout") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { record("loaded_sync_failed", e.javaClass.simpleName) }
                                finally {
                                    rt.loadedReading = false
                                    rt.loadedAt = minOf(rt.loadedAt, SystemClock.elapsedRealtime() + if (foreground) 15_000 else 30_000)
                                    rt.syncWake.trySend(Unit)
                                }
                            }
                        }
                        val candidates = pollTargets(host, rt)
                        val reads = rt.polls.reserve(candidates, rt.readingResults, now, foreground)
                        reads.forEach { target ->
                            rt.readingResults += target.id
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try { observeThreadResult(host, rt, target.id) }
                                finally { rt.readingResults.remove(target.id); rt.syncWake.trySend(Unit) }
                            }
                        }
                    }
                    val at = SystemClock.elapsedRealtime()
                    val pollDelay = rt.polls.delay(pollTargets(host, rt).filterNot { it.id in rt.readingResults }, at, foreground)
                    val discoveryDelay = minOf(if (rt.overviewReading) Long.MAX_VALUE else rt.overviewAt,
                        if (rt.loadedReading) Long.MAX_VALUE else rt.loadedAt) - at
                    // Under backlog, pace batches instead of spinning on due work.
                    val wait = minOf(pollDelay, discoveryDelay, 60_000L).coerceAtLeast(250L)
                    withTimeoutOrNull(wait) { rt.syncWake.receive() }
                }
            }
        }
    }

    private suspend fun discoverRecentResults(host: String, rt: Runtime) {
        val revision = ui(host).threadListRevision
        val version = rt.overviewVersion
        // Explicitly query non-archived sessions, regardless of the displayed page.
        val page = withTimeout(8_000) { rt.api.threads(archived = false) }
        if (runtimes[host] !== rt || ui(host).threadListRevision != revision || rt.overviewVersion != version) return
        page.items.forEach { rt.observedThreads[it.id] = it }
        update(host) { ws ->
            if (ws.archived || ws.search.isNotBlank()) ws
            else ws.copy(threads = (page.items + ws.threads).distinctBy { it.id },
                nextThreads = if (ws.threads.isEmpty()) page.nextCursor else ws.nextThreads)
        }
        rememberProjects(host, page.items.map { it.cwd })
        // Never delete the paged cache or overwrite the archive list from a monitor.
        val changedRows = page.items.filter { rt.persistedThreads[it.id] != it }
        if (changedRows.isNotEmpty()) {
            val rows = withContext(Dispatchers.Default) { changedRows.map { ThreadRow(host, it.id, wireJson.encodeToString(it)) } }
            dao.putThreads(rows)
            if (runtimes[host] === rt) changedRows.forEach { rt.persistedThreads[it.id] = it }
        }
        observeResults(host, rt, page.items)
    }

    private suspend fun observeResults(host: String, rt: Runtime, summaries: List<ThreadSummary>) {
        val changed = summaries.filter { summary ->
            rt.observedThreads[summary.id] = summary
            val before = ui(host).results[summary.id]
            !summary.awaitingFirstMessage && (before == null || before.updatedAt != summary.updatedAt ||
                (summary.status == "active" && before.status != "inProgress"))
        }
        changed.forEach { rt.polls.hint(it.id, SystemClock.elapsedRealtime()) }
        if (changed.isNotEmpty()) rt.syncWake.trySend(Unit)
    }

    private suspend fun observeThreadResult(host: String, rt: Runtime, id: String) {
        val ws = ui(host)
        val permits = when {
            ws.selectedThread == id -> rt.currentReads
            ws.results[id]?.status == "inProgress" || summary(host, id)?.status == "active" -> rt.activeReads
            else -> rt.idleReads
        }
        val queued = SystemClock.elapsedRealtime()
        permits.withPermit {
            if (runtimes[host] !== rt || !connectionActive) return@withPermit
            timing(if (permits === rt.idleReads) "status_queue_idle" else "status_queue_priority", queued)
            rt.polls.attempted(id, SystemClock.elapsedRealtime())
            val before = ui(host).results[id]
            val version = rt.statusVersions[id] ?: 0
            val revision = ui(host).threadListRevision
            var confirmed = false
            var changedStatus = false
            var canceled = false
            try {
                // Loaded threads can include a CLI session outside the first
                // list page, and a brand-new thread with no persisted rollout.
                val summary = rt.observedThreads[id] ?: summary(host, id) ?: withTimeout(8_000) {
                    threadSummary(rt.api.read(id).obj("thread"))
                }
                val turn = withTimeout(8_000) { rt.api.latestTurnStatus(id) } ?: return@withPermit
                val turnId = turn.string("id")?.takeIf { it.isNotBlank() } ?: return@withPermit
                val status = turn.string("status") ?: return@withPermit
                if (status !in setOf("inProgress", "completed", "failed", "interrupted")) return@withPermit
                if (runtimes[host] !== rt || ui(host).results[id] != before ||
                    (rt.statusVersions[id] ?: 0) != version || ui(host).threadListRevision != revision) return@withPermit
                confirmed = true
                val observed = summary.copy(status = if (status == "inProgress") "active" else "idle", awaitingFirstMessage = false)
                rt.observedThreads[id] = observed
                val receipt = observeResult(before, turnId, status, summary.updatedAt)
                val changed = before?.turnId != turnId || before?.status != status
                changedStatus = changed
                if (changed) { rt.overviewVersion++; rt.sessions.dirty(id) }
                update(host) { ws ->
                    val row = ws.threads.firstOrNull { it.id == id }
                    val rowChanged = row != null && (row.status != observed.status || row.awaitingFirstMessage)
                    if (receipt == before && !changed && !rowChanged) return@update ws
                    // Only update existing rows here. A metadata read does not
                    // establish whether an unknown thread belongs in archive view.
                    val conversation = ws.conversations[id]
                    ws.copy(results = ws.results + (id to receipt),
                        threads = ws.threads.map { if (it.id == id) it.copy(status = observed.status, awaitingFirstMessage = false) else it },
                        conversations = if (conversation != null && changed) ws.conversations + (id to conversation.copy(
                            activeTurn = if (status == "inProgress") turnId else null, status = status, stateRevision = conversation.stateRevision + 1)) else ws.conversations)
                }
                if (receipt != before) persistResults(host)
                // Full message history is fetched only for the selected session,
                // including when polling repairs a missed completion notification.
                if (ui(host).selectedThread == id && !ui(host).loadingHistory && !rt.sessions.isCurrent(id))
                    resumeThread(host, id, rt)
            } catch (e: TimeoutCancellationException) { record("result_sync_failed", "timeout") }
            catch (e: CancellationException) { canceled = true; throw e }
            catch (e: Exception) { record("result_sync_failed", e.javaClass.simpleName) }
            finally {
                // A newer push already supplied the result; do not replace its
                // fresh deadline with failure backoff from an obsolete read.
                if (!canceled && (rt.statusVersions[id] ?: 0) == version) {
                    if (confirmed) rt.polls.confirmed(id, changedStatus) else rt.polls.retry(id, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun persistResults(host: String) {
        val key = "results:${host}:state"
        persistLater(key, 200) { app.settings.results(host, ui(host).results) }
    }

    fun markResultRead(thread: String) {
        val host = state.value.activeHost ?: return
        if (!foreground || ui(host).selectedThread != thread || state.value.tab != WorkspaceTab.SESSIONS) return
        val receipt = ui(host).results[thread] ?: return
        if (!receipt.unread || ui(host).conversations[thread]?.items?.any { it.turnId == receipt.completedTurnId } != true) return
        update(host) { ws -> ws.copy(results = ws.results + (thread to receipt.copy(seenTurnId = receipt.completedTurnId))) }
        persistResults(host)
    }

    fun showArchived(value: Boolean) { state.value.activeHost?.let { id -> update(id) { it.copy(archived = value, search = "") }; refreshThreads(id) } }
    fun createThread(cwd: String) = viewModelScope.launch {
        val host = state.value.activeHost ?: return@launch
        val root = projectPath(cwd.trim())
        if (!root.startsWith('/') || '\n' in root || '\u0000' in root) {
            message(tr(R.string.ui_choose_a_valid_remote_project_directory_first))
            return@launch
        }
        val rt = runtimes[host]
        if (rt == null || ui(host).connection != ConnectionStatus.ONLINE || !rt.rpc.isConnected) {
            message(tr(R.string.ui_the_server_is_not_connected_reconnect_before))
            return@launch
        }
        if (!creatingThreads.add(host)) return@launch
        val originProject = ui(host).selectedProject
        val originThread = ui(host).selectedThread
        val originTab = state.value.tab
        update(host) { it.copy(creatingThreadCwd = root) }
        try {
            val summary = rt.api.create(root).copy(awaitingFirstMessage = true)
            if (runtimes[host] !== rt) {
                message(tr(R.string.ui_session_created_open_it_from_the_project))
                return@launch
            }
            rt.sessions.created(summary.id)
            val createdRoot = projectPath(summary.cwd.ifBlank { root })
            rememberProjects(host, listOf(createdRoot))
            update(host) { ws -> ws.copy(
                threads = listOf(summary) + ws.threads.filterNot { it.id == summary.id },
                conversations = if (summary.id in ws.conversations) ws.conversations
                    else ws.conversations + (summary.id to Conversation(summary.id)),
            ) }
            // thread/start already attaches this client. A fresh thread may not have
            // persisted history yet; do not immediately resume it or read its turns.
            if (state.value.activeHost == host && state.value.tab == originTab &&
                ui(host).selectedProject == originProject && ui(host).selectedThread == originThread) {
                switchProject(host, createdRoot)
                update(host) { it.copy(selectedThread = summary.id, selectedSummary = summary,
                    archived = false, search = "", loadingHistory = false) }
                mutable.update { it.copy(tab = WorkspaceTab.SESSIONS) }
                launchStorage { app.settings.location(host, createdRoot, summary.id) }
            }
            launchStorage { dao.putThreads(listOf(ThreadRow(host, summary.id, wireJson.encodeToString(summary)))) }
        } catch (e: UncertainDelivery) {
            message(tr(R.string.ui_session_creation_is_unconfirmed_reconnect_and_check))
            if (runtimes[host] === rt) checkConnection(host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(tr(R.string.ui_could_not_create_session , errorText(e)))
        } finally {
            creatingThreads.remove(host)
            update(host) { it.copy(creatingThreadCwd = null) }
        }
    }
    fun openThread(thread: String) {
        val host = state.value.activeHost ?: return
        mutable.update { it.copy(tab = WorkspaceTab.SESSIONS) }
        openThread(host, thread)
    }
    private fun openThread(host: String, thread: String, force: Boolean = false) = viewModelScope.launch {
        val opened = SystemClock.elapsedRealtime()
        if (force) runtimes[host]?.sessions?.dirty(thread)
        val summary = ui(host).threads.firstOrNull { it.id == thread } ?: ui(host).selectedSummary?.takeIf { it.id == thread }
        val root = summary?.cwd?.let(::projectPath) ?: ui(host).selectedProject
        if (root != ui(host).selectedProject) switchProject(host, root)
        update(host) { it.copy(selectedThread = thread, loadingHistory = runtimes[host]?.sessions?.isCurrent(thread) != true, selectedSummary = summary) }
        try {
            launchStorage { app.settings.location(host, root, thread) }
            loadCached(host, thread)
            timing("open_cache", opened)
            val rt = runtimes[host]
            if (rt != null && rt.rpc.isConnected && ui(host).connection in setOf(ConnectionStatus.ONLINE, ConnectionStatus.ERROR)) {
                resumeThread(host, thread, rt)
                if (runtimes[host] !== rt) return@launch
                if (ui(host).connection == ConnectionStatus.ERROR && ui(host).selectedThread == thread) {
                    retryBlocked.remove(host)
                    update(host) { it.copy(connection = ConnectionStatus.ONLINE, error = null) }
                    monitorResults(host, rt)
                }
                val cwd = ui(host).selectedSummary?.takeIf { it.id == thread }?.cwd.orEmpty()
                if (cwd.isNotBlank() && ui(host).selectedThread == thread) {
                    rememberProjects(host, listOf(cwd))
                    update(host) { it.copy(selectedProject = projectPath(cwd), filePath = it.filePath.ifBlank { cwd }) }
                    app.settings.lastProject(host, projectPath(cwd))
                }
            }
        } catch (e: Exception) { if (e !is CancellationException) threadProblem(host, thread, e) }
        finally {
            timing("open_ready", opened)
            update(host) { if (it.selectedThread == thread) it.copy(loadingHistory = false) else it }
        }
    }
    fun backToThreads() { state.value.activeHost?.let { id -> update(id) { it.copy(selectedThread = null, selectedSummary = null) }; launchStorage { app.settings.lastThread(id, null) } } }

    private fun attentionThreads(ws: HostWorkspace) = ws.attentionSyncingThreads +
        ws.approvals.map { it.request.threadId } + ws.pendingQuestions.map { it.request.threadId }

    private suspend fun restorePendingAttention(host: String, rt: Runtime) {
        // Requests may belong to conversations other than the one currently selected.
        for (thread in ui(host).attentionSyncingThreads) {
            if (runtimes[host] !== rt) return
            resumeThread(host, thread, rt)
        }
    }

    private suspend fun attentionRestored(host: String, thread: String, rt: Runtime) {
        if (thread !in ui(host).attentionSyncingThreads) return
        rt.rpc.awaitEventsApplied()
        if (runtimes[host] === rt) update(host) { it.copy(attentionSyncingThreads = it.attentionSyncingThreads - thread) }
    }

    private suspend fun restoreSelectedThread(host: String, rt: Runtime) {
        do {
            val thread = ui(host).selectedThread ?: return
            update(host) { it.copy(loadingHistory = !rt.sessions.isCurrent(thread)) }
            try { resumeThread(host, thread, rt) }
            finally {
                if (runtimes[host] === rt) update(host) {
                    if (it.selectedThread == thread) it.copy(loadingHistory = false) else it
                }
            }
            if (runtimes[host] !== rt) return
        } while (ui(host).selectedThread != thread)
    }

    private fun summary(host: String, thread: String): ThreadSummary? =
        ui(host).threads.firstOrNull { it.id == thread } ?: ui(host).selectedSummary?.takeIf { it.id == thread }

    private fun rememberSummary(host: String, summary: ThreadSummary) {
        update(host) { ws -> ws.copy(threads = ws.threads.filterNot { it.id == summary.id } + summary,
            selectedSummary = if (ws.selectedThread == summary.id) summary else ws.selectedSummary) }
        launchStorage { dao.putThreads(listOf(ThreadRow(host, summary.id, wireJson.encodeToString(summary)))) }
    }

    private fun threadProblem(host: String, thread: String, error: Throwable) {
        val unavailable = error is ThreadUnavailable
        val detail = if (unavailable) errorText(error)
            else tr(R.string.ui_could_not_restore_this_session , errorText(error))
        update(host) { it.copy(threadProblems = it.threadProblems + (thread to ThreadProblem(detail, unavailable))) }
        val rpc = generateSequence(error) { it.cause }.filterIsInstance<RpcException>().firstOrNull()
        record("thread_restore_failed", "kind=${if (unavailable) "unavailable" else "request"} method=${rpc?.method ?: "unknown"} code=${rpc?.code ?: 0}")
    }

    private suspend fun resumeThread(host: String, thread: String, rt: Runtime) {
        try {
            if (ui(host).conversations[thread] == null) rt.sessions.dirty(thread)
            val current = summary(host, thread)
            if (current?.archived != true) {
                rt.sessions.subscribe(thread) {
                    val resumed = rt.api.resumeOrReadEmpty(thread)
                    if (runtimes[host] !== rt) return@subscribe false
                    rememberSummary(host, resumed)
                    !resumed.awaitingFirstMessage
                }
                if (runtimes[host] !== rt) return
                if (summary(host, thread)?.awaitingFirstMessage == true) {
                    update(host) { it.copy(threadProblems = it.threadProblems - thread) }
                    attentionRestored(host, thread, rt)
                    return
                }
            } else rt.sessions.dirty(thread) // Archived threads have no live subscription.
            synchronizeHistory(host, thread, rt)
            attentionRestored(host, thread, rt)
            if (runtimes[host] === rt) update(host) { it.copy(threadProblems = it.threadProblems - thread) }
        } catch (e: ThreadUnavailable) {
            attentionRestored(host, thread, rt)
            if (runtimes[host] === rt) threadProblem(host, thread, e)
        } catch (e: RpcException) {
            // A rejected thread lookup does not invalidate the healthy server
            // connection. Keep the failure scoped to this conversation.
            if (runtimes[host] === rt) threadProblem(host, thread, e)
        }
    }

    fun removeEmptyRecord(thread: String) = launchStorage {
        val host = state.value.activeHost ?: return@launchStorage
        if (!canRemoveEmptyRecord(ui(host), thread)) return@launchStorage
        val original = summary(host, thread)
        // Read persisted data conservatively. A failed read/parse is not evidence
        // that a record is empty; never delete message or draft rows here.
        val empty = runCatching {
            val rows = dao.conversationSnapshot(host, thread)
            val cached = if (rows.header.isEmpty()) null else withContext(Dispatchers.Default) {
                requireNotNull(decodeStoredConversation(rows))
            }
            val saved = listOfNotNull(cached, dao.conversation(host, thread)?.let { wireJson.decodeFromString<Conversation>(it.json) })
            val draft = dao.draft(host, thread)?.let { wireJson.decodeFromString<Draft>(it.json) } ?: Draft()
            saved.all { it.items.isEmpty() && it.activeTurn == null && it.nextCursor == null } &&
                draft.text.isEmpty() && draft.attachments.isEmpty() && draft.delivery == "draft" && draft.pendingId == null
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            message(tr(R.string.ui_cannot_verify_that_the_local_record_is))
            return@launchStorage
        }
        if (!empty || !canRemoveEmptyRecord(ui(host), thread)) {
            message(tr(R.string.ui_this_session_still_has_local_content_its))
            return@launchStorage
        }
        dao.deleteThread(host, thread)
        if (!canRemoveEmptyRecord(ui(host), thread)) {
            original?.let { dao.putThreads(listOf(ThreadRow(host, thread, wireJson.encodeToString(it)))) }
            return@launchStorage
        }
        val selected = ui(host).selectedThread == thread
        update(host) { ws -> ws.copy(threads = ws.threads.filterNot { it.id == thread },
            selectedThread = if (selected) null else ws.selectedThread,
            selectedSummary = if (selected) null else ws.selectedSummary,
            threadProblems = ws.threadProblems - thread, threadListRevision = ws.threadListRevision + 1) }
        app.settings.clearLastThreadIf(host, thread)
        message(tr(R.string.ui_empty_local_record_removed))
    }

    /** Serialize cache hydration with writes and eviction. A failed read must not
     * be treated as an empty baseline that is then allowed to overwrite disk. */
    private suspend fun loadConversationCache(host: String, thread: String) = cacheWrites.withLock {
        if (ui(host).conversations[thread]?.baselineLoaded == true) return@withLock
        val generation = conversationCacheGeneration
        val rows = dao.conversationSnapshot(host, thread)
        val restored = if (rows.header.isNotEmpty()) {
            withContext(Dispatchers.Default) { decodeStoredConversation(rows) }
        } else {
            // Legacy single-row caches use the same conservative pagination migration.
            val legacy = try { dao.conversation(host, thread) }
            catch (e: Exception) {
                if (!isOversizedCache(e)) throw e
                record("cache_read_failed", "cursor_window")
                dao.deleteConversation(host, thread)
                message(tr(R.string.ui_local_session_cache_was_too_large_and))
                null
            }
            legacy?.let { withContext(Dispatchers.Default) { decodeConversationCache(listOf(it.json)) } }
        }
        if (generation != conversationCacheGeneration || host !in state.value.workspaces) return@withLock
        // A successful read establishes what is recoverable locally. Missing or
        // malformed serialized data requires a fresh server page and a new cursor.
        // IO/SQLite failures propagate above; those never establish a baseline.
        if (rows.header.isNotEmpty() && restored == null) record("cache_read_failed", "invalid_serialization")
        if (rows.isIncremental() && restored?.threadId == thread) savedConversations[host to thread] = restored
        val baseline = restored?.takeIf { it.threadId == thread }?.let(Timeline::normalized)
            ?: Conversation(thread, historyTruncated = true)
        update(host) { ws ->
            if (ws.conversations[thread]?.baselineLoaded == true) ws
            else ws.copy(conversations = ws.conversations + (thread to baseline))
        }
    }

    private suspend fun loadCached(host: String, thread: String) {
        loadConversationCache(host, thread)
        if (ui(host).drafts[thread] == null) {
            val draft = dao.draft(host, thread)?.let { runCatching { wireJson.decodeFromString<Draft>(it.json) }.getOrNull() } ?: Draft()
            val restored = draft.restored()
            update(host) { it.copy(drafts = it.drafts + (thread to restored)) }
        }
    }
    private suspend fun synchronizeHistory(host: String, thread: String, rt: Runtime, more: Boolean = false) {
        if (summary(host, thread)?.awaitingFirstMessage == true) return
        update(host) { it.copy(historySyncingThreads = it.historySyncingThreads + thread) }
        try {
            rt.sessions.history(thread, more) {
                if (runtimes[host] !== rt) return@history false
                val started = SystemClock.elapsedRealtime()
                loadConversationCache(host, thread)
                if (runtimes[host] !== rt) return@history false
                val generation = conversationCacheGeneration
                val current = ui(host).conversations[thread] ?: Conversation(thread, baselineLoaded = false)
                val append = more && current.baselineLoaded && !current.historyTruncated
                if (append && current.nextCursor == null) return@history true
                val beforeReceipt = ui(host).results[thread]
                val response = rt.api.history(thread, if (append) current.nextCursor else null)
                rt.rpc.awaitEventsApplied()
                val page = withContext(Dispatchers.Default) { Timeline.history(thread, response) }
                if (runtimes[host] !== rt || generation != conversationCacheGeneration) return@history false
                update(host) { ws ->
                    val live = ws.conversations[thread] ?: current
                    val merged = if (append) Timeline.older(live, page) else Timeline.refresh(current, live, page)
                    val latest = response.array("data").firstOrNull() as? JsonObject
                    val results = if (!append && ws.results[thread] == beforeReceipt) ws.results + (thread to observeResult(beforeReceipt,
                        latest?.string("id").orEmpty(), latest?.string("status").orEmpty(), ws.threads.firstOrNull { it.id == thread }?.updatedAt ?: 0)) else ws.results
                    ws.copy(conversations = ws.conversations + (thread to merged), results = results)
                }
                if (!append) {
                    rt.polls.pushed(thread, SystemClock.elapsedRealtime())
                    persistResults(host)
                }
                persistConversation(host, thread, immediate = page.activeTurn == null)
                timing(if (append) "history_older" else "history_latest", started)
                true
            }
        } finally {
            update(host) { it.copy(historySyncingThreads = it.historySyncingThreads - thread) }
        }
    }
    fun loadOlder() = perform { id, rt -> ui(id).selectedThread?.let { synchronizeHistory(id, it, rt, more = true) } }
    fun refreshConversation() {
        val host = state.value.activeHost ?: return
        ui(host).selectedThread?.let { openThread(host, it, force = true) }
    }
    fun rename(thread: String, name: String) = perform { host, rt -> rt.api.rename(thread, name); refreshThreads(host) }
    fun archive(thread: String, value: Boolean, host: String? = state.value.activeHost, allowUndo: Boolean = false,
        restoreSummary: ThreadSummary? = null) = perform(host) { id, rt ->
        if (thread in ui(id).archivingThreads) return@perform
        val original = summary(id, thread) ?: restoreSummary
        if (value && (original?.status == "active" || ui(id).results[thread]?.status == "inProgress" ||
                ui(id).conversations[thread]?.activeTurn != null || ui(id).approvals.any { it.request.threadId == thread } ||
                ui(id).pendingQuestions.any { it.request.threadId == thread })) {
            message(tr(R.string.ui_this_session_is_running_or_waiting_for))
            return@perform
        }
        update(id) { it.copy(archivingThreads = it.archivingThreads + thread) }
        try {
            rt.api.archive(thread, value)
            rt.sessions.closed(thread)
            // Only remove after acknowledgement; invalidate older list snapshots.
            update(id) { ws -> ws.copy(threads = ws.threads.filterNot { it.id == thread } +
                if (ws.archived == value) listOfNotNull(original?.copy(archived = value)) else emptyList(),
                threadListRevision = ws.threadListRevision + 1,
                selectedThread = if (ws.selectedThread == thread) null else ws.selectedThread,
                selectedSummary = if (ws.selectedThread == thread) null else ws.selectedSummary) }
            if (value && allowUndo && original != null) mutable.update {
                it.copy(archiveUndo = ArchiveUndo(id, original))
            }
            original?.let { saved -> launchStorage {
                dao.putThreads(listOf(ThreadRow(id, thread, wireJson.encodeToString(saved.copy(archived = value)))))
            } }
            launchStorage { app.settings.clearLastThreadIf(id, thread) }
            refreshThreads(id, background = true)
        } finally {
            update(id) { it.copy(archivingThreads = it.archivingThreads - thread) }
        }
    }
    fun dismissArchiveUndo(token: String) {
        mutable.update { if (it.archiveUndo?.token == token) it.copy(archiveUndo = null) else it }
    }
    fun undoArchive(undo: ArchiveUndo) {
        dismissArchiveUndo(undo.token)
        if (ui(undo.host).connection != ConnectionStatus.ONLINE) {
            message(tr(R.string.ui_the_original_server_is_disconnected_reconnect_and))
            return
        }
        archive(undo.thread, false, host = undo.host, restoreSummary = undo.summary)
    }
    fun selectModel(model: String?) {
        val host = state.value.activeHost ?: return
        val thread = ui(host).selectedThread ?: return
        if (model != null && ui(host).modelOptions.none { it.id == model }) return
        updateMessageInput(host, thread, ui(host).drafts[thread]?.edit?.id) { it.copy(model = model, effort = null) }
    }
    fun selectEffort(effort: String?) {
        val host = state.value.activeHost ?: return
        val thread = ui(host).selectedThread ?: return
        val draft = ui(host).drafts[thread] ?: Draft()
        val input = draft.edit?.input ?: draft.input()
        val model = input.model ?: summary(host, thread)?.model ?: ui(host).modelOptions.firstOrNull { it.isDefault }?.id
        if (effort != null && ui(host).modelOptions.firstOrNull { it.id == model }?.efforts?.contains(effort) != true) return
        updateMessageInput(host, thread, draft.edit?.id) { it.copy(effort = effort) }
    }
    fun reloadModels() = perform { host, rt ->
        val models = rt.api.models()
        if (runtimes[host] === rt) update(host) { it.copy(modelOptions = models, models = models.map { model -> model.id to model.name }) }
    }

    private fun updateDraft(host: String, thread: String, block: (Draft) -> Draft) {
        update(host) { it.copy(drafts = it.drafts + (thread to block(it.drafts[thread] ?: Draft()))) }
        persistDraft(host, thread)
    }
    fun draftText(value: String) { val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: return
        updateMessageInput(host, thread, s.draft.edit?.id) { it.copy(text = value) }
    }
    fun editInterruptedInput(itemId: String) {
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: return
        val item = editableInterruptedMessage(s.workspace, thread)?.takeIf { it.id == itemId } ?: return
        val draft = s.workspace.drafts[thread] ?: Draft()
        if (draft.delivery != "draft" || draft.edit != null) return
        val input = inputForInterruptedMessage(s.workspace, thread, item)
        updateDraft(host, thread) { it.copy(edit = InterruptedInputEdit(UUID.randomUUID().toString(), item.turnId, item.id, input)) }
    }

    fun cancelInterruptedEdit() {
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: return
        val edit = s.draft.edit?.takeIf { it.delivery == "draft" } ?: return
        edit.input.attachments.forEach { uploads.remove(it.id)?.cancel() }
        updateDraft(host, thread) { if (it.edit?.id == edit.id) it.copy(edit = null) else it }
    }

    /** Capture the edit id before asynchronous uploads so late results cannot enter another draft. */
    private fun updateMessageInput(host: String, thread: String, editId: String?, block: (MessageInput) -> MessageInput) {
        updateDraft(host, thread) { draft ->
            if (editId == null) {
                if (draft.delivery == "draft") draft.withInput(block(draft.input())) else draft
            } else draft.edit?.takeIf { it.id == editId && it.delivery == "draft" }?.let { edit ->
                draft.copy(edit = edit.copy(input = block(edit.input), error = null))
            } ?: draft
        }
    }

    fun resolveUncertain() {
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: return
        updateDraft(host, thread) { draft ->
            if (draft.edit?.delivery == "uncertain") draft.copy(edit = draft.edit.copy(delivery = "draft", pendingId = null, error = null))
            else if (draft.delivery == "uncertain") draft.copy(delivery = "draft", pendingId = null) else draft
        }
    }
    fun send() = perform { host, rt -> sendInput(host, rt, edited = false) }
    fun resendInterruptedInput() = perform { host, rt -> sendInput(host, rt, edited = true) }

    private suspend fun sendInput(host: String, rt: Runtime, edited: Boolean) {
        val thread = ui(host).selectedThread ?: return
        val ws = ui(host)
        if (thread in ws.threadProblems || ws.loadingHistory || thread in ws.historySyncingThreads) {
            message(tr(R.string.ui_restore_this_session_before_sending_your_draft)); return
        }
        if (summary(host, thread)?.archived == true || ws.conversations[thread]?.activeTurn != null || thread in ws.stoppingTurns) return
        val draft = ws.drafts[thread] ?: return
        val edit = draft.edit
        if (draft.delivery != "draft" || (edited && edit?.delivery != "draft") || (!edited && edit != null)) return
        if (edited && editableInterruptedMessage(ws, thread)?.let { it.id == edit!!.itemId && it.turnId == edit.turnId } != true) {
            message(tr(R.string.ui_edit_task_changed)); return
        }
        val input = if (edited) edit!!.input else draft.input()
        if (input.attachments.any { it.state != "ready" } || (input.text.isBlank() && input.attachments.isEmpty())) return
        val firstMessage = summary(host, thread)?.awaitingFirstMessage == true
        val messageId = UUID.randomUUID().toString()
        val option = ws.modelOptions.firstOrNull { it.id == (input.model ?: summary(host, thread)?.model ?: ws.modelOptions.firstOrNull { it.isDefault }?.id) }
        val effort = input.effort ?: if (input.model != null) option?.defaultEffort else null
        require(effort == null || option?.efforts?.contains(effort) == true) { tr(R.string.ui_the_current_model_does_not_support_this) }
        val submitted = input.copy(model = input.model ?: summary(host, thread)?.model ?: option?.id,
            effort = effort ?: summary(host, thread)?.reasoningEffort)
        updateDraft(host, thread) {
            if (edited) it.copy(edit = edit!!.copy(delivery = "sending", pendingId = messageId, error = null))
            else it.copy(delivery = "sending", pendingId = messageId)
        }
        val response = try {
            // The original/edit input and its delivery fence reach disk before any mutation.
            flushDraft(host, thread)
            if (edited) {
                for (attachment in input.attachments) {
                    require(rt.files.readableFile(attachment.remotePath)) { tr(R.string.ui_edit_attachment_unavailable, attachment.name) }
                }
                val latest = rt.api.latestTurnStatus(thread)
                rt.rpc.awaitEventsApplied()
                if (latest?.string("id") != edit!!.turnId || latest.string("status") != "interrupted") {
                    rt.sessions.dirty(thread)
                    synchronizeHistory(host, thread, rt)
                    error(tr(R.string.ui_edit_task_changed))
                }
                check(editableInterruptedMessage(ui(host), thread)?.let { it.id == edit.itemId && it.turnId == edit.turnId } == true) {
                    tr(R.string.ui_edit_task_changed)
                }
            }
            check(runtimes[host] === rt && rt.rpc.isConnected && ui(host).conversations[thread]?.activeTurn == null) {
                tr(R.string.ui_restore_this_session_before_sending_your_draft)
            }
            val text = (listOf(input.text) + input.attachments.filterNot { it.image }.map { tr(R.string.ui_attachment, it.remotePath) })
                .filter { it.isNotBlank() }.joinToString("\n\n")
            rt.api.send(thread, text, input.attachments.filter { it.image }.map { it.remotePath }, input.model, messageId, effort)
        } catch (e: Exception) {
            updateDraft(host, thread) {
                val delivery = if (e is UncertainDelivery) "uncertain" else "draft"
                if (edited) it.copy(edit = it.edit?.let { current ->
                    if (current.id == edit!!.id) current.copy(delivery = delivery, error = errorText(e),
                        pendingId = messageId.takeIf { delivery == "uncertain" }) else current
                }) else it.copy(delivery = delivery, pendingId = messageId.takeIf { delivery == "uncertain" })
            }
            withContext(NonCancellable) { flushDraft(host, thread) }
            throw e
        }
        // An accepted turn must never become sendable again because a later disk/sync operation fails.
        val turn = response.obj("turn").string("id").orEmpty()
        summary(host, thread)?.let { rememberSummary(host, it.copy(model = submitted.model, reasoningEffort = submitted.effort)) }
        updateDraft(host, thread) { it.acceptedSubmission(submitted, turn, messageId, edited) }
        flushDraft(host, thread)
        if (firstMessage && runtimes[host] === rt) {
            summary(host, thread)?.let { rememberSummary(host, it.copy(awaitingFirstMessage = false)) }
            rt.sessions.dirty(thread)
            try { resumeThread(host, thread, rt) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message(tr(R.string.ui_message_sent_restoring_session_state_please_do))
                record("first_turn_sync_failed", e.javaClass.simpleName); checkConnection(host)
            }
        }
    }

    fun interrupt() = perform { host, rt ->
        val thread = ui(host).selectedThread ?: return@perform
        val turn = ui(host).conversations[thread]?.activeTurn ?: return@perform
        if (thread in ui(host).stoppingTurns) return@perform
        update(host) { it.copy(stoppingTurns = it.stoppingTurns + (thread to turn)) }
        try { rt.api.interrupt(thread, turn) }
        catch (e: Exception) {
            update(host) { it.copy(stoppingTurns = it.stoppingTurns - thread) }
            throw e
        }
    }
    /** Notification action: only for a still-pending request on a live connection. */
    fun approveFromNotification(host: String, requestKey: String, decision: String): Boolean {
        val pending = ui(host).approvals.firstOrNull { it.request.key == requestKey && !it.submitted } ?: return false
        val rt = runtimes[host] ?: return false
        if (ui(host).connection != ConnectionStatus.ONLINE || !rt.rpc.isConnected) return false
        approve(pending.request, decision)
        return ui(host).approvals.any { it.request.key == requestKey && it.submitted }
    }

    /** Opens the conversation a notification refers to, switching server if needed. */
    fun openFromNotification(host: String, thread: String) = viewModelScope.launch {
        // A cold start from the tray arrives before saved servers are loaded from disk.
        withTimeoutOrNull(10_000) { state.first { s -> s.profiles.any { it.id == host } && host in s.workspaces } } ?: return@launch
        if (state.value.activeHost != host) selectHost(host)
        mutable.update { it.copy(tab = WorkspaceTab.SESSIONS, previewImage = null,
            notificationNavigationId = UUID.randomUUID().toString()) }
        openThread(host, thread)
    }

    fun approve(request: ServerRequest, decision: String) {
        val host = state.value.workspaces.entries.firstOrNull { (_, ws) -> ws.approvals.any { it.request.key == request.key } }?.key ?: return
        val rt = runtimes[host] ?: return
        if (ui(host).connection != ConnectionStatus.ONLINE || !rt.rpc.isConnected) return
        try {
            rt.rpc.approve(request, decision)
            update(host) { it.copy(approvals = it.approvals.map { p -> if (p.request.key == request.key) p.copy(submitted = true) else p }) }
        } catch (e: Exception) { message(errorText(e)) }
    }

    fun answerQuestions(request: ServerRequest, answers: Map<String, List<String>>) {
        val host = state.value.workspaces.entries.firstOrNull { (_, ws) -> ws.pendingQuestions.any { it.request.key == request.key } }?.key ?: return
        val rt = runtimes[host] ?: return
        if (ui(host).connection != ConnectionStatus.ONLINE || !rt.rpc.isConnected) return
        try {
            rt.rpc.answerQuestions(request, answers)
        } catch (e: Exception) { message(errorText(e)) }
        finally {
            if (rt.rpc.questionsSubmitted(request)) update(host) {
                it.copy(pendingQuestions = it.pendingQuestions.map { p -> if (p.request.key == request.key) p.copy(submitted = true) else p })
            }
        }
    }

    fun listFiles(path: String? = null) {
        state.value.activeHost?.let { host -> update(host) { it.copy(loadingFiles = true) } }
        performFile { host, rt ->
        val project = ui(host).selectedProject
        if (project == null) {
            update(host) { it.copy(loadingFiles = false) }
            return@performFile
        }
        try {
            val cwd = path ?: ui(host).filePath.takeIf { it.isNotBlank() } ?: rt.files.home()
            val entries = rt.files.list(cwd)
            update(host) { ws ->
                val cache = ws.projectFiles + (project to ProjectFiles(cwd, entries))
                if (ws.selectedProject == project) ws.copy(filePath = cwd, files = entries, projectFiles = cache)
                else ws.copy(projectFiles = cache)
            }
        } finally { update(host) { it.copy(loadingFiles = false) } }
        }
    }
    fun editFile(path: String) = performFile { host, rt -> val text = rt.files.readText(path); update(host) { it.copy(editor = text, editorText = text.text, fileConflict = false) } }
    fun editorText(text: String) { state.value.activeHost?.let { id -> update(id) { it.copy(editorText = text) }; persistLater("editor:$id:draft", 200) { flushEditor(id) } } }
    fun closeEditor() { state.value.activeHost?.let { id -> update(id) { it.copy(editor = null, editorText = "", fileConflict = false) }; persistLater("editor:$id:draft", 0) { flushEditor(id) } } }
    fun saveFile(overwrite: Boolean = false) = performFile { host, rt ->
        val ws = ui(host); val file = ws.editor ?: return@performFile
        try {
            val saved = rt.files.saveText(file, ws.editorText, overwrite)
            update(host) { current -> if (current.editor != file) current else current.copy(editor = saved,
                editorText = if (current.editorText == ws.editorText) saved.text else current.editorText, fileConflict = false) }
            flushEditor(host)
            message(tr(R.string.ui_file_saved))
        }
        catch (e: FileConflict) { update(host) { it.copy(fileConflict = true) } }
    }
    private suspend fun flushEditor(host: String) {
        val ws = ui(host)
        editorDrafts.write(host, ws.editor?.takeIf { it.text != ws.editorText }?.let {
            EditorDraft(it.path, it.text, it.sha256, ws.editorText)
        })
    }
    fun attachRemote(file: RemoteFile) {
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: run { message(tr(R.string.ui_choose_a_session_first)); return }
        val editId = s.draft.edit?.id
        updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments + Attachment(UUID.randomUUID().toString(), file.name, image = imageName(file.name), remotePath = file.path, state = "ready")) }
        selectTab(WorkspaceTab.SESSIONS)
    }

    fun upload(uri: Uri, image: Boolean) {
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: run { message(tr(R.string.ui_select_a_session_first)); return }
        val editId = s.draft.edit?.id
        val delivery = s.draft.edit?.delivery ?: s.draft.delivery
        if (delivery != "draft") return
        val id = UUID.randomUUID().toString()
        updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments + Attachment(id, if (image) tr(R.string.ui_image) else tr(R.string.ui_attachment_57), uri.toString(), image)) }
        val job = viewModelScope.launch {
            try {
                val rt = waitForFileRuntime(host) ?: error(tr(R.string.ui_remote_files_are_temporarily_unavailable))
                val name = withContext(Dispatchers.IO) {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } ?: if (image) "image.jpg" else "attachment"
                updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(name = name) else a }) }
                val cwd = (ui(host).threads.firstOrNull { it.id == thread } ?: ui(host).selectedSummary?.takeIf { it.id == thread })?.cwd ?: error(tr(R.string.ui_cannot_determine_the_session_working_directory))
                val path = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { rt.files.upload(cwd, name, it) } ?: error(tr(R.string.ui_cannot_open_file)) }
                updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(remotePath = path, state = "ready") else a }) }
            } catch (e: Exception) {
                if (e !is CancellationException) updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(state = "failed", error = errorText(e)) else a }) }
            } finally { uploads.remove(id) }
        }
        uploads[id] = job
    }
    fun removeAttachment(id: String) {
        uploads.remove(id)?.cancel()
        val s = state.value; val host = s.activeHost ?: return; val thread = s.workspace.selectedThread ?: return
        val editId = s.draft.edit?.id
        updateMessageInput(host, thread, editId) { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }
    fun download(path: String, uri: Uri) = performFile { _, rt -> withContext(Dispatchers.IO) {
        app.contentResolver.openOutputStream(uri, "w")?.use { rt.files.download(path, it) } ?: error(tr(R.string.ui_cannot_open_the_save_location))
    }; message(tr(R.string.ui_download_complete)) }
    private fun requestImage(path: String, show: Boolean) {
        if (clearingImages) return
        val host = state.value.activeHost ?: return
        val key = "$host:$path"
        if (imageLoads[key]?.isActive == true) {
            if (show) {
                val generation = imageGeneration
                viewModelScope.launch { imageLoads[key]?.join(); if (generation == imageGeneration) ui(host).imagePreviews[path]?.let { file -> mutable.update { it.copy(previewImage = file) } } }
            }
            return
        }
        val generation = imageGeneration
        update(host) { it.copy(imageLoading = it.imageLoading + path) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val entry = imageCache.get(key, { state.value.previewImage }) { output ->
                    val rt = withContext(Dispatchers.Main) { waitForFileRuntime(host) }
                        ?: error(tr(R.string.ui_remote_files_are_temporarily_unavailable))
                    rt.files.download(path, output, maxBytes = 20L * 1024 * 1024)
                }
                ensureActive()
                if (generation != imageGeneration) return@launch
                mutable.update { all -> all.copy(workspaces = all.workspaces.mapValues { (id, ws) ->
                    ws.copy(imagePreviews = ws.imagePreviews.filterValues { it in entry.retained } +
                        if (id == host) mapOf(path to entry.file.absolutePath) else emptyMap())
                }, previewImage = if (show) entry.file.absolutePath else all.previewImage) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(errorText(e)) }
            finally {
                if (imageLoads[key] === currentCoroutineContext()[Job]) {
                    imageLoads.remove(key)
                    update(host) { it.copy(imageLoading = it.imageLoading - path) }
                }
            }
        }
        imageLoads[key] = job
        job.start()
    }
    fun loadImagePreview(path: String) = requestImage(path, show = false)
    fun previewImage(path: String) = requestImage(path, show = true)
    fun dismissImage() { mutable.update { it.copy(previewImage = null) } }

    fun ensureTerminal() {
        val ws = state.value.workspace
        if (ws.connection == ConnectionStatus.ONLINE && ws.terminals.none { it.projectPath == ws.selectedProject }) newTerminal()
    }

    fun newTerminal(): Job? {
        val host = state.value.activeHost ?: return null
        val project = ui(host).selectedProject ?: return null
        val key = host to project
        terminalOpenings[key]?.let { return it }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val rt = waitForFileRuntime(host) ?: error(tr(R.string.ui_terminal_connection_unavailable))
                openTerminal(host, project, rt)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(errorText(e)) }
            finally {
                if (terminalOpenings[key] === currentCoroutineContext()[Job]) {
                    terminalOpenings.remove(key)
                    update(host) { it.copy(openingTerminalProjects = it.openingTerminalProjects - project) }
                }
            }
        }
        terminalOpenings[key] = job
        update(host) { it.copy(openingTerminalProjects = it.openingTerminalProjects + project) }
        job.start()
        return job
    }

    private suspend fun openTerminal(host: String, project: String, rt: FileRuntime) {
        val remote = rt.ssh.terminal(cwd = project.ifBlank { ui(host).filePath })
        if (fileRuntimes[host] !== rt) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { remote.close() } }
            return
        }
        val writes = Channel<ByteArray>(128)
        val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = object : TerminalBackend {
            override fun write(bytes: ByteArray) { if (!writes.trySend(bytes).isSuccess) message(tr(R.string.ui_terminal_input_is_too_fast_please_try)) }
            override fun resize(columns: Int, rows: Int) { sizes.trySend(columns to rows) }
            override fun close() { io.launch { runCatching { remote.close() } }; writes.close(); sizes.close() }
        }
        val session = TerminalSession(backend, object : SessionCallbacks() {
            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { mutable.update { it.copy(clipboardRequest = ClipboardRequest(text.orEmpty())) } }
            override fun onPasteTextFromClipboard(session: TerminalSession?) { mutable.update { it.copy(clipboardRequest = ClipboardRequest(null, session)) } }
        })
        val tab = TerminalTab(UUID.randomUUID().toString(), tr(R.string.ui_terminal , ui(host).terminals.size + 1), session, project)
        update(host) { it.copy(terminals = it.terminals + tab,
            selectedTerminal = if (it.selectedProject == project) tab.id else it.selectedTerminal,
            projectTerminals = it.projectTerminals + (project to tab.id)) }
        io.launch { try { for (bytes in writes) remote.write(bytes) } catch (_: Exception) { session.markFinished() } }
        io.launch { try { for ((cols, rows) in sizes) remote.resize(cols, rows) } catch (_: Exception) { session.markFinished() } }
        io.launch {
            try { val buffer = ByteArray(16384); while (isActive) { val n = remote.input.read(buffer); if (n < 0) break; session.append(buffer.copyOf(n)) } }
            catch (_: Exception) { /* EOF and SSH disconnect both end this terminal only. */ }
            finally { session.markFinished(); runCatching { remote.close() }; io.cancel() }
        }
    }
    fun selectTerminal(id: String) { state.value.activeHost?.let { host -> update(host) { ws ->
        val tab = ws.terminals.firstOrNull { it.id == id && it.projectPath == ws.selectedProject }
        if (tab == null) ws else ws.copy(selectedTerminal = id, projectTerminals = ws.projectTerminals + (tab.projectPath to id))
    } } }
    fun closeTerminal(id: String) {
        state.value.activeHost?.let { host ->
            ui(host).terminals.firstOrNull { it.id == id }?.session?.finishIfRunning()
            update(host) { ws ->
                val remaining = ws.terminals.filterNot { it.id == id }
                val selected = if (ws.selectedTerminal == id) remaining.firstOrNull { it.projectPath == ws.selectedProject }?.id else ws.selectedTerminal
                ws.copy(terminals = remaining, selectedTerminal = selected, projectTerminals = ws.projectTerminals.filterValues { it != id })
            }
        }
    }
    fun dismissClipboard() { mutable.update { it.copy(clipboardRequest = null) } }

    private fun persistConversation(host: String, thread: String, immediate: Boolean = false) {
        if (ui(host).conversations[thread]?.baselineLoaded == true)
            conversationCheckpoints.changed(host to thread, immediate)
    }

    private suspend fun writeConversation(host: String, thread: String) = cacheWrites.withLock {
        val conversation = ui(host).conversations[thread]?.takeIf { it.baselineLoaded } ?: return@withLock
        saveConversation(host, thread, conversation)
    }

    /** Caller owns cacheWrites. Commit the baseline only after the atomic Room patch succeeds. */
    private suspend fun saveConversation(host: String, thread: String, conversation: Conversation) {
        val generation = conversationCacheGeneration
        val started = SystemClock.elapsedRealtime()
        val previous = savedConversations[host to thread]
        val patch = withContext(Dispatchers.Default) { planConversationCache(previous, conversation) }
        timing("cache_prepare", started)
        if (generation != conversationCacheGeneration || host !in state.value.workspaces) return
        if (patch.empty) { syncTimings.increment("cache_unchanged"); return }
        val rows = patch.changed.flatMap { (key, chunks) -> chunks.mapIndexed { index, json ->
            ConversationItemRow(host, thread, key, index, json)
        } }
        val writing = SystemClock.elapsedRealtime()
        dao.patchConversation(host, thread, patch.header, patch.reset, patch.removed, rows)
        timing("cache_write", writing)
        syncTimings.increment("cache_items_written", patch.changed.size.toLong())
        syncTimings.increment("cache_characters_written", patch.characters)
        if (generation == conversationCacheGeneration && host in state.value.workspaces)
            savedConversations[host to thread] = patch.snapshot
    }
    private fun persistLater(key: String, milliseconds: Long, block: suspend () -> Unit) {
        persistence.remove(key)?.cancel()
        val job = launchStorage(start = CoroutineStart.LAZY) {
            try { delay(milliseconds); block() }
            finally { if (persistence[key] === currentCoroutineContext()[Job]) persistence.remove(key) }
        }
        persistence[key] = job
        job.start()
    }
    private fun persistDraft(host: String, thread: String) {
        val key = "draft:${host}:${thread}"
        persistLater(key, 200) { flushDraft(host, thread) }
    }
    private suspend fun flushDraft(host: String, thread: String) = draftWrites.withLock {
        ui(host).drafts[thread]?.let { dao.putDraft(DraftRow(host, thread, wireJson.encodeToString(it))) }
    }
    fun flush() = launchStorage {
        state.value.workspaces.forEach { (host, ws) ->
            flushEditor(host)
            app.settings.results(host, ws.results)
            ws.drafts.forEach { (thread, _) -> flushDraft(host, thread) }
            ws.conversations.keys.forEach { thread -> writeConversation(host, thread) }
        }
    }

    private fun isOversizedCache(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            cause.javaClass.simpleName in setOf("CursorWindowAllocationException", "SQLiteBlobTooBigException") ||
                cause.message?.contains("CursorWindow", ignoreCase = true) == true ||
                cause.message?.contains("Row too big", ignoreCase = true) == true
        }
    }
    fun clearCache() = launchStorage {
        imageGeneration++
        imageLoads.values.forEach { it.cancel() }; imageLoads.clear()
        mutable.update { all -> all.copy(previewImage = null, workspaces = all.workspaces.mapValues { (_, ws) ->
            ws.copy(imagePreviews = emptyMap(), imageLoading = emptySet())
        }) }
        clearingImages = true
        try { imageCache.clear() } finally { clearingImages = false }
        cacheWrites.withLock {
            // Invalidate at the actual clear boundary, after any suspended image
            // cleanup and previous body IO, so neither can reinstall an old base.
            conversationCacheGeneration++
            conversationCheckpoints.clear()
            trims.values.forEach { it.cancel() }; trims.clear()
            memoryBudgets.clear()
            savedConversations.clear()
            runtimes.values.forEach { it.sessions.clearHistory() }
            mutable.update { all -> all.copy(workspaces = all.workspaces.mapValues { (_, ws) -> ws.copy(conversations = emptyMap(), threads = emptyList(), nextThreads = null) }) }
            dao.clearConversations(); dao.clearConversationChunks(); dao.clearConversationItems(); dao.clearThreads()
            runtimes.values.forEach { it.persistedThreads.clear() }
        }
        message(tr(R.string.ui_local_session_cache_cleared_drafts_are_preserved))
    }
    fun exportDiagnostics(uri: Uri) = viewModelScope.launch {
        try {
            val content = "${app.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}\nProtocol compatibility ${CodexCompatibility.POLICY_LABEL}\n" + diagnostic.joinToString("\n") +
                "\n\nSync timings (local aggregates, milliseconds)\n" + syncTimings.report() +
                "\n\nLast crash (safe category, screen, class names and stack frames only)\n" + withContext(Dispatchers.IO) { app.crashes.read() }
            withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: error(tr(R.string.ui_cannot_open_the_save_location)) }
            message(tr(R.string.ui_diagnostics_exported_without_credentials_or_message_content))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { record("export_failed", e.javaClass.simpleName); message(tr(R.string.ui_could_not_export_diagnostics_choose_a_writable)) }
    }

    override fun onCleared() {
        network.unregisterNetworkCallback(networkCallback)
        state.value.workspaces.values.flatMap { it.terminals }.forEach { it.session.finishIfRunning() }
        val rpc = runtimes.values.map { it.rpc }
        val ssh = fileRuntimes.values.map { it.ssh }
        CoroutineScope(Dispatchers.IO).launch {
            rpc.forEach { runCatching { it.close() } }
            ssh.forEach { runCatching { it.close() } }
        }
        super.onCleared()
    }
}

fun imageName(name: String) = name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
