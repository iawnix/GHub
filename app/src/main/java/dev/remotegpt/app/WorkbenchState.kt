package dev.remotegpt.app

import com.termux.terminal.TerminalSession
import dev.remotegpt.client.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable

enum class ConnectionStatus {
    OFFLINE, CONNECTING, CHECKING, WAITING_NETWORK, RECONNECTING, SYNCING, ONLINE, ERROR;
    val recovering get() = this in setOf(CHECKING, WAITING_NETWORK, RECONNECTING, SYNCING)
    val busy get() = this == CONNECTING || recovering
}
enum class WorkspaceTab { SESSIONS, FILES, TERMINAL }

@Serializable data class Attachment(val id: String, val name: String, val localUri: String = "", val image: Boolean = false,
    val remotePath: String = "", val state: String = "uploading", val error: String? = null)
@Serializable data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList(), val delivery: String = "draft", val pendingId: String? = null,
    val model: String? = null, val effort: String? = null)
data class PendingQuestions(val request: ServerRequest, val submitted: Boolean = false)
data class PendingApproval(val request: ServerRequest, val submitted: Boolean = false)
data class TerminalTab(val id: String, val name: String, val session: TerminalSession, val projectPath: String = "")
data class TerminalPreferences(val fontSize: Float = 14f, val extraKeys: Boolean = false)
@Serializable data class ProjectProfile(val path: String, val name: String = projectName(path))
fun projectPath(path: String) = path.trimEnd('/').ifEmpty { if (path.startsWith('/')) "/" else "" }
fun projectName(path: String) = projectPath(path).substringAfterLast('/').ifBlank { if (path == "/") tr(R.string.ui_root_directory) else tr(R.string.ui_ungrouped) }
data class ProjectFiles(val path: String, val entries: List<RemoteFile> = emptyList())
data class ThreadProblem(val detail: String, val unavailable: Boolean = false)
data class HostWorkspace(
    val connection: ConnectionStatus = ConnectionStatus.OFFLINE, val error: String? = null,
    val reconnectAttempt: Int = 0,
    val serverVersion: String = "", val account: String = "", val needsBootstrap: Boolean = false,
    val threads: List<ThreadSummary> = emptyList(), val nextThreads: String? = null,
    val results: Map<String, ResultReceipt> = emptyMap(),
    val archived: Boolean = false, val search: String = "", val loadingThreads: Boolean = false,
    val selectedThread: String? = null, val conversations: Map<String, Conversation> = emptyMap(),
    val selectedSummary: ThreadSummary? = null,
    val threadProblems: Map<String, ThreadProblem> = emptyMap(),
    /** Ephemeral history reads that reconcile push events with the daemon snapshot. */
    val historySyncingThreads: Set<String> = emptySet(),
    /** Previously pending requests whose thread replay has not yet been reconciled. */
    val attentionSyncingThreads: Set<String> = emptySet(),
    val creatingThreadCwd: String? = null,
    val creatingProjectPath: String? = null, val projectCreationError: String? = null,
    val archivingThreads: Set<String> = emptySet(), val threadListRevision: Long = 0,
    val projects: List<ProjectProfile> = emptyList(), val selectedProject: String? = null,
    val hiddenProjects: Set<String> = emptySet(),
    /** Process-local remote image files used by inline conversation previews. */
    val imagePreviews: Map<String, String> = emptyMap(),
    val imageLoading: Set<String> = emptySet(),
    val projectFiles: Map<String, ProjectFiles> = emptyMap(), val projectTerminals: Map<String, String> = emptyMap(),
    val drafts: Map<String, Draft> = emptyMap(), val approvals: List<PendingApproval> = emptyList(),
    val pendingQuestions: List<PendingQuestions> = emptyList(),
    val models: List<Pair<String, String>> = emptyList(), val model: String? = null,
    val modelOptions: List<ModelOption> = emptyList(),
    val filePath: String = "", val files: List<RemoteFile> = emptyList(), val loadingFiles: Boolean = false,
    val editor: TextFile? = null, val editorText: String = "", val fileConflict: Boolean = false,
    val terminals: List<TerminalTab> = emptyList(), val selectedTerminal: String? = null,
    val openingTerminalProjects: Set<String> = emptySet(),
    val loadingHistory: Boolean = false,
)

fun canRemoveEmptyRecord(ws: HostWorkspace, thread: String): Boolean {
    val draft = ws.drafts[thread] ?: return false
    val conversation = ws.conversations[thread]
    return ws.threadProblems[thread]?.unavailable == true && !ws.loadingHistory &&
        conversation?.items.isNullOrEmpty() && conversation?.activeTurn == null &&
        conversation?.nextCursor == null && ws.results[thread]?.turnId.isNullOrEmpty() &&
        ws.results[thread]?.completedTurnId == null &&
        draft.text.isEmpty() && draft.attachments.isEmpty() && draft.delivery == "draft" && draft.pendingId == null
}

sealed interface ConnectionPrompt {
    data class HostKey(val challenge: HostKeyChallenge, val answer: CompletableDeferred<Boolean>) : ConnectionPrompt
    data class Keyboard(val challenge: KeyboardPrompt, val answer: CompletableDeferred<List<String>>) : ConnectionPrompt
}

data class ClipboardRequest(val text: String?, val pasteInto: TerminalSession? = null)
data class ArchiveUndo(val host: String, val summary: ThreadSummary,
    val token: String = java.util.UUID.randomUUID().toString()) {
    val thread get() = summary.id
    val title get() = summary.title
}
data class WorkbenchState(
    val profiles: List<HostProfile> = emptyList(), val activeHost: String? = null,
    val workspaces: Map<String, HostWorkspace> = emptyMap(), val tab: WorkspaceTab = WorkspaceTab.SESSIONS,
    val theme: String = "system", val backgroundKeepAlive: Boolean = false, val prompt: ConnectionPrompt? = null, val message: String? = null,
    val previewImage: String? = null, val clipboardRequest: ClipboardRequest? = null,
    val archiveUndo: ArchiveUndo? = null,
    /** Every tray navigation, including repeated opens of the same thread. */
    val notificationNavigationId: String? = null,
    val terminalPreferences: TerminalPreferences = TerminalPreferences(),
) {
    val profile get() = profiles.firstOrNull { it.id == activeHost }
    val workspace get() = workspaces[activeHost] ?: HostWorkspace()
    val thread get() = workspace.threads.firstOrNull { it.id == workspace.selectedThread }
        ?: workspace.selectedSummary?.takeIf { it.id == workspace.selectedThread }
    val conversation get() = workspace.conversations[workspace.selectedThread]
    val draft get() = workspace.drafts[workspace.selectedThread] ?: Draft()
    val project get() = workspace.projects.firstOrNull { it.path == workspace.selectedProject }
}
