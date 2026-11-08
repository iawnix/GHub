@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import androidx.core.content.ContextCompat
import dev.remotegpt.app.*
import dev.remotegpt.app.data.CrashScreen
import dev.remotegpt.client.*
import java.util.UUID

@Composable fun Workbench(vm: WorkbenchViewModel, state: WorkbenchState) {
    var showConversationOptions by rememberSaveable { mutableStateOf(false) }
    var menuProject by rememberSaveable { mutableStateOf<String?>(null) }
    var renameProject by rememberSaveable { mutableStateOf<String?>(null) }
    var showHosts by rememberSaveable { mutableStateOf(false) }
    var showHostSwitcher by rememberSaveable { mutableStateOf(false) }
    val hostEditor: HostEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val editProfile = hostEditor.profile
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var hostEditorMode by rememberSaveable { mutableStateOf("full") }
    var settingsHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsHost = state.profiles.firstOrNull { it.id == settingsHostId }
    val settingsWorkspace = state.workspaces[settingsHostId] ?: HostWorkspace()
    val showHostDetail = settingsHost != null
    val showHostList = showHosts || settingsHostId != null && settingsHost == null
    var showLegal by rememberSaveable { mutableStateOf(false) }
    var showProjectEditor by rememberSaveable { mutableStateOf(false) }
    var showWorkspace by rememberSaveable { mutableStateOf(false) }
    var bootstrapHost by rememberSaveable { mutableStateOf<String?>(null) }
    var handledNotification by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.notificationNavigationId) {
        if (state.notificationNavigationId != null && state.notificationNavigationId != handledNotification) {
            showConversationOptions = false
            menuProject = null
            renameProject = null
            showHosts = false
            showHostSwitcher = false
            hostEditor.close()
            showSettings = false
            settingsHostId = null
            showLegal = false
            showProjectEditor = false
            showWorkspace = false
            bootstrapHost = null
            handledNotification = state.notificationNavigationId
        }
    }
    var pendingDownload by rememberSaveable { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var bottomControls by remember(state.activeHost, state.workspace.selectedThread, state.tab) { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    var speechTarget by remember { mutableStateOf<Triple<String?, String?, String?>?>(null) }
    val speech = remember { SpeechInput(context, { result ->
        val current = vm.state.value
        if (speechTarget == Triple(current.activeHost, current.workspace.selectedThread, current.draft.edit?.id)) {
            val text = current.draft.edit?.input?.text ?: current.draft.text
            vm.draftText(listOf(text, result).filter(String::isNotBlank).joinToString(" "))
        }
    }, vm::message) }
    LaunchedEffect(state.activeHost, state.workspace.selectedThread, state.tab, state.draft.edit?.id,
        state.draft.edit?.delivery ?: state.draft.delivery) { speech.cancel() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) speech.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val listening by speech.listening.collectAsState()
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val current = vm.state.value
            speechTarget = Triple(current.activeHost, current.workspace.selectedThread, current.draft.edit?.id)
            speech.start()
        } else vm.message(tr(R.string.ui_microphone_permission_was_denied_you_can_continue))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runCatching { RemoteConnectionService.start(context) }
            .onSuccess { if (!granted) vm.message(tr(R.string.ui_background_without_notifications)) }
            .onFailure { vm.message(tr(R.string.ui_cannot_keep_the_connection_active_check_notification)) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.upload(uri, true) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.upload(uri, false) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> if (success) cameraUri?.let { vm.upload(Uri.parse(it), true) } }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> if (uri != null && pendingDownload.isNotBlank()) vm.download(pendingDownload, uri) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::exportDiagnostics) }

    LaunchedEffect(state.message) { state.message?.let { snackbars.showSnackbar(it, withDismissAction = true); if (vm.state.value.message == it) vm.message(null) } }
    LaunchedEffect(state.archiveUndo?.token) {
        state.archiveUndo?.let { undo ->
            val result = snackbars.showSnackbar(tr(R.string.ui_archived_353 , undo.title.take(40)), actionLabel = tr(R.string.ui_undo),
                withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) vm.undoArchive(undo)
            else vm.dismissArchiveUndo(undo.token)
        }
    }
    val showConversation = state.tab == WorkspaceTab.SESSIONS && state.workspace.selectedThread != null
    val showHome = !showConversation && state.tab == WorkspaceTab.SESSIONS
    val crashScreen = when {
        editProfile != null -> CrashScreen.HOST_EDITOR
        showProjectEditor -> CrashScreen.PROJECT_EDITOR
        showWorkspace -> CrashScreen.WORKSPACE
        showSettings -> CrashScreen.SETTINGS
        showHostList -> CrashScreen.HOSTS
        showHostDetail -> CrashScreen.HOST_DETAILS
        showHome -> CrashScreen.PROJECTS
        showConversation -> CrashScreen.CHAT
        state.tab == WorkspaceTab.FILES -> CrashScreen.FILES
        state.tab == WorkspaceTab.TERMINAL -> CrashScreen.TERMINAL
        else -> CrashScreen.SESSIONS
    }
    SideEffect { (context.applicationContext as RemoteApplication).crashes.screen(crashScreen) }
    val largeHeader = false
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(state.activeHost, state.workspace.selectedProject, showConversation) {
        scrollBehavior.state.heightOffset = 0f
        scrollBehavior.state.contentOffset = 0f
    }
    BackHandler(!showHome && editProfile == null && !showSettings && !showHostList && !showWorkspace && !showProjectEditor && !showHostDetail) { vm.navigateBack() }

    // Pages are keyed by identity only, so data updates never restart a transition. A page
    // that is leaving renders only while its data still exists; otherwise it leaves blank.
    val page = when {
        editProfile != null -> "editor:${editProfile.id}"
        showSettings -> "settings"
        showHostList -> "hosts"
        settingsHost != null -> "host:${settingsHost.id}"
        else -> "main"
    }
    AnimatedContent(
        page,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = { sharedAxisX(forward = pageDepth(targetState) > pageDepth(initialState)) },
        label = "page",
    ) { shown ->
    if (shown.startsWith("editor:")) {
        if (editProfile != null) HostEditor(hostEditor, vm, snackbar = snackbars,
            backLabel = tr(if (settingsHostId == editProfile.id) R.string.ui_back_to_server_settings else R.string.ui_back_to_servers),
            overviewOnly = hostEditorMode == "overview", onClose = hostEditor::close)
    }
    else if (shown == "settings") SettingsScreen(state.theme, state.profiles.size, state.backgroundKeepAlive, onBack = { showSettings = false },
        onTheme = vm::theme, onHosts = { showSettings = false; showHosts = true },
        onKeepAlive = { enabled -> runCatching {
            if (!enabled) RemoteConnectionService.stop(context)
            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else RemoteConnectionService.start(context)
        }
            .onFailure { vm.message(tr(R.string.ui_cannot_keep_the_connection_active_check_notification)) } },
        onClearCache = { vm.clearCache() }, onExport = { export.launch("ghub-diagnostics.txt") }, onLegal = { showLegal = true }, snackbar = snackbars)
    else if (shown == "hosts") HostsScreen(state.profiles, state.workspaces,
        onBack = { showHosts = false; settingsHostId = null; showSettings = true },
        onOpen = { settingsHostId = it.id; showHosts = false },
        onAdd = { hostEditorMode = "full"; hostEditor.open(HostProfile()) }, snackbar = snackbars)
    else if (shown.startsWith("host:")) { if (settingsHost != null) HostDetailsScreen(settingsHost, settingsWorkspace,
        onBack = { settingsHostId = null; showHosts = true }, onEdit = { hostEditorMode = "overview"; hostEditor.open(settingsHost) },
        onConnect = { if (settingsWorkspace.needsBootstrap) bootstrapHost = settingsHost.id else vm.connect(settingsHost.id) },
        onDisconnect = { vm.disconnect(settingsHost.id) }, snackbar = snackbars,
        onEditSection = { section -> hostEditorMode = "section"; hostEditor.open(settingsHost, section) }) }
    else GlassWorkbenchScaffold(
        glassEnabled = (showHome || showConversation) && state.workspace.connection == ConnectionStatus.ONLINE,
        snackbarHost = {
            if (state.tab != WorkspaceTab.FILES || state.workspace.editor == null) {
                SnackbarHost(snackbars, if (bottomControls > 0) Modifier.padding(bottom = with(LocalDensity.current) { bottomControls.toDp() }) else Modifier.imePadding())
            }
        },
        topBar = {
            if (state.tab != WorkspaceTab.TERMINAL || state.profile == null) WorkbenchHeader(
                title = when {
                    showConversation -> state.thread?.title ?: tr(R.string.ui_sessions)
                    state.tab == WorkspaceTab.FILES -> tr(R.string.ui_files)
                    state.tab == WorkspaceTab.TERMINAL -> tr(R.string.ui_terminal_290)
                    else -> state.profile?.name ?: tr(R.string.app_name)
                },
                status = state.workspace.connection,
                conversation = !showHome,
                onNavigate = { vm.navigateBack() },
                large = largeHeader, scrollBehavior = if (largeHeader) scrollBehavior else null,
                onHosts = if (!showConversation) ({ showHostSwitcher = true }) else null,
                onMenu = { if (showHome) showSettings = true else if (showConversation) showConversationOptions = true else showWorkspace = true },
                menuIcon = if (showHome) WorkbenchIcons.Settings else WorkbenchIcons.MoreHoriz,
                menuLabel = if (showHome) tr(R.string.ui_settings) else tr(R.string.ui_more_actions),
                onAdd = if (showHome && state.profile != null) ({ showProjectEditor = true }) else null,
                onArchive = if (showHome && state.profile != null) ({ vm.showArchived(!state.workspace.archived) }) else null,
                archiveLabel = tr(if (state.workspace.archived) R.string.ui_back_to_recent else R.string.ui_archived),
                backLabel = if (state.tab != WorkspaceTab.SESSIONS) tr(R.string.ui_back_to_session) else tr(R.string.ui_back_to_projects),
            )
        },
        modifier = Modifier.then(if (largeHeader) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (state.profile == null) {
                Box(Modifier.weight(1f)) { EmptyPanel(WorkbenchIcons.Lan, tr(R.string.ui_connect_your_development_machine), tr(R.string.ui_continue_codex_sessions_and_browse_files_and), tr(R.string.ui_open_settings), { showSettings = true }) }
            } else {
                if (state.tab != WorkspaceTab.TERMINAL && state.workspace.connection.recovering) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.workspace.reconnectAttempt >= 3) state.workspace.error ?: tr(R.string.ui_still_reconnecting_remote_tasks_are_not_interrupted)
                            else connectionLabel(state.workspace.connection) + "…", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.workspace.reconnectAttempt >= 3 || state.workspace.connection == ConnectionStatus.WAITING_NETWORK)
                            TextButton(onClick = { vm.connect() }) { Text(tr(R.string.ui_retry_now)) }
                    }
                } else if (state.tab != WorkspaceTab.TERMINAL && state.workspace.connection != ConnectionStatus.ONLINE) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        InfoCard(state.workspace.error ?: if (state.workspace.connection == ConnectionStatus.CONNECTING) tr(R.string.ui_establishing_a_secure_connection) else tr(R.string.ui_connect_to_continue_local_drafts_are_preserved),
                            if (state.workspace.connection == ConnectionStatus.CONNECTING) tr(R.string.ui_cancel_connection) else if (state.workspace.needsBootstrap) tr(R.string.ui_set_up_background_service) else tr(R.string.ui_connect)) {
                            if (state.workspace.connection == ConnectionStatus.CONNECTING) vm.disconnect()
                            else if (state.workspace.needsBootstrap) bootstrapHost = state.activeHost else vm.connect()
                        }
                    }
                }
                val contentKey = if (showHome) "${state.activeHost}:home" else "${state.activeHost}:${state.workspace.selectedProject}:${state.tab}:${state.workspace.selectedThread}"
                AnimatedContent(contentKey, Modifier.weight(1f).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
                    transitionSpec = { workspaceTransition(initialState, targetState) }, label = "workspace-content") { view ->
                // Only the current view and a leaving home list render: other views read the
                // current selection, so a leaving copy would show the new one twice.
                val leavingHome = view != contentKey && view == "${state.activeHost}:home"
                if (view == contentKey || leavingHome) holder.SaveableStateProvider(view) {
                    if (view.endsWith(":home")) ProjectsScreen(state.workspace, onAdd = { showProjectEditor = true },
                        onRefresh = { vm.refreshThreads() }, onMore = { vm.refreshThreads(more = true) }, onContinue = vm::openThread,
                        onCreate = { vm.createThread(it) }, onProjectMenu = { menuProject = it },
                        onArchive = { vm.archive(it, true, host = state.activeHost, allowUndo = true) })
                    else when (state.tab) {
                        WorkspaceTab.SESSIONS -> ChatScreen(state, vm, listening,
                            onImage = { imagePicker.launch(arrayOf("image/*")) }, onFile = { filePicker.launch(arrayOf("*/*")) },
                            onCamera = {
                                val file = File(context.cacheDir, "shared/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                cameraUri = uri.toString()
                                runCatching { camera.launch(uri) }.onFailure { vm.message(tr(R.string.ui_no_camera_app_found_choose_an_image)) }
                            }, onVoice = { if (listening) speech.cancel() else microphone.launch(Manifest.permission.RECORD_AUDIO) }, optionsOpen = showConversationOptions, onOptionsChange = { showConversationOptions = it }, onBottomControls = { bottomControls = it })
                        WorkspaceTab.FILES -> FilesScreen(state, vm, onWorkspace = { showWorkspace = true }, snackbar = snackbars) { file -> pendingDownload = file.path; saveFile.launch(file.name) }
                        WorkspaceTab.TERMINAL -> TerminalsScreen(state, vm, onWorkspace = { showWorkspace = true }, onBottomControls = { bottomControls = it })
                    }
                }
                }
            }
        }
    }
    Unit // The page chain is a statement, not the lambda's value.
    }

    if (showHostSwitcher) HostSwitcherSheet(
        profiles = state.profiles, activeId = state.activeHost, workspaces = state.workspaces,
        onSelect = { profile ->
            showHostSwitcher = false
            vm.selectHost(profile.id)
            if (state.workspaces[profile.id]?.connection != ConnectionStatus.ONLINE) vm.connect(profile.id)
        },
        onDismiss = { showHostSwitcher = false },
    )
    menuProject?.let { path ->
        val project = state.workspace.projects.firstOrNull { it.path == path }
        ModalBottomSheet(onDismissRequest = { menuProject = null }) {
            Column(Modifier.padding(16.dp)) {
                Text(project?.name ?: tr(R.string.ui_projects), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                Text(path, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SheetAction(if (state.workspace.creatingThreadCwd == path) tr(R.string.ui_creating) else tr(R.string.ui_create_session), WorkbenchIcons.NewSession, enabled = state.workspace.creatingThreadCwd == null && path.startsWith('/')) { menuProject = null; vm.createThread(path) }
                SheetAction(tr(R.string.ui_files), WorkbenchIcons.FolderOpen, enabled = path.startsWith('/')) { menuProject = null; vm.openProject(path); vm.selectTab(WorkspaceTab.FILES) }
                SheetAction(tr(R.string.ui_terminal_290), WorkbenchIcons.Terminal, enabled = path.startsWith('/')) { menuProject = null; vm.openProject(path); vm.selectTab(WorkspaceTab.TERMINAL) }
                SheetAction(tr(R.string.ui_rename_project), WorkbenchIcons.Edit) { menuProject = null; renameProject = path }
                SheetAction(tr(R.string.ui_hide_project), WorkbenchIcons.Close, supporting = tr(R.string.ui_hide_project_hint)) { menuProject = null; vm.hideProject(path) }
            }
        }
    }
    renameProject?.let { path -> TextPrompt(tr(R.string.ui_rename_project), tr(R.string.ui_project_name), state.workspace.projects.firstOrNull { it.path == path }?.name.orEmpty(),
        onDismiss = { renameProject = null }, onConfirm = { vm.saveProject(it, path); renameProject = null }) }

    if (showProjectEditor) ProjectEditor(state.profile?.defaultCwd.orEmpty(), onDismiss = { showProjectEditor = false },
        busy = state.workspace.creatingProjectPath != null, error = state.workspace.projectCreationError,
        onSave = { name, path -> vm.createProject(name, path) { saved -> if (saved) showProjectEditor = false } })
    if (showWorkspace) ModalBottomSheet(onDismissRequest = { showWorkspace = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ProjectWorkspaceMenu(state.project, state.tab, state.workspace.selectedThread != null,
            onSelect = { if (it == WorkspaceTab.SESSIONS && state.workspace.selectedThread == null) vm.backToProjects() else vm.selectTab(it); showWorkspace = false },
            onProjects = { vm.backToProjects(); showWorkspace = false },
            onRefresh = {
                showWorkspace = false
                when {
                    state.tab == WorkspaceTab.FILES -> vm.listFiles()
                    showConversation -> vm.refreshConversation()
                    else -> vm.refreshThreads()
                }
            })
    }
    if (showLegal) LegalNotices { showLegal = false }
    bootstrapHost?.let { host -> AlertDialog(onDismissRequest = { bootstrapHost = null }, title = { Text(tr(R.string.ui_set_up_the_official_daemon)) },
        text = { Text(tr(R.string.ui_this_runs_codex_app_server_daemon_bootstrap)) },
        confirmButton = { TextButton(onClick = { bootstrapHost = null; vm.connect(host, bootstrap = true) }) { Text(tr(R.string.ui_set_up_and_connect)) } }, dismissButton = { TextButton(onClick = { bootstrapHost = null }) { Text(tr(R.string.ui_cancel)) } }) }
    ConnectionPromptDialog(state.prompt)
    state.previewImage?.let { path -> Dialog(onDismissRequest = vm::dismissImage, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RemoteImageViewer(path, vm::dismissImage)
    } }
    state.clipboardRequest?.let { req -> AlertDialog(onDismissRequest = vm::dismissClipboard,
        title = { Text(if (req.pasteInto != null) tr(R.string.ui_paste_into_the_remote_terminal) else tr(R.string.ui_copy_terminal_content)) },
        text = { Text(req.text?.take(300) ?: tr(R.string.ui_clipboard_content_will_be_sent_to_the)) },
        confirmButton = { TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (req.pasteInto != null) clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { req.pasteInto.getEmulator()?.paste(it) }
            else clipboard.setPrimaryClip(ClipData.newPlainText(tr(R.string.ui_terminal_290), req.text))
            vm.dismissClipboard()
        }) { Text(tr(R.string.ui_confirm)) } }, dismissButton = { TextButton(onClick = vm::dismissClipboard) { Text(tr(R.string.ui_cancel)) } }) }
}

@Composable fun TextPrompt(title: String, label: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, validate: (String) -> Boolean = { it.isNotBlank() }) {
    var value by rememberSaveable { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onConfirm(value) }, enabled = validate(value), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(R.string.ui_ok)) }
        }
    }
}

private fun pageDepth(page: String): Int = when {
    page == "main" -> 0
    page == "settings" -> 1
    page == "hosts" -> 2
    page.startsWith("host:") -> 3
    else -> 4 // editor
}

/**
 * Home ↔ conversation is a step down the hierarchy; sessions, files and terminal are
 * siblings; another server is a different place. The terminal only fades: it is a
 * native view whose content should not be scaled or slid.
 */
private fun workspaceTransition(from: String, to: String): ContentTransform {
    val sameHost = from.substringBefore(':') == to.substringBefore(':')
    val fromHome = from.endsWith(":home")
    val toHome = to.endsWith(":home")
    val terminal = ":${WorkspaceTab.TERMINAL}:" in from || ":${WorkspaceTab.TERMINAL}:" in to
    return when {
        terminal -> ContentTransform(fadeIn(tween(Motion.CONTENT, delayMillis = 40)), fadeOut(tween(Motion.SHORT - 40)), sizeTransform = null)
        sameHost && fromHome != toHome -> sharedAxisX(forward = fromHome)
        else -> fadeThrough()
    }
}
