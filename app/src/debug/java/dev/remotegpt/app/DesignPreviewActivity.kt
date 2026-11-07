@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import kotlinx.serialization.json.*
import dev.remotegpt.client.ThreadSummary
import dev.remotegpt.client.TimelineItem

/** Debug-only offline gallery. Sample content, no SSH connections or protocol calls. */
class DesignPreviewActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getStringExtra("theme") == "dark"
        delegate.localNightMode = if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent { ProjectDesignPreview(if (dark) "dark" else "light", intent.getStringExtra("screen") ?: "projects") }
    }
}

@Preview(name = "Projects", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun ProjectsPreview() = ProjectDesignPreview("light", "projects")
@Preview(name = "Project sessions", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun SessionsPreview() = ProjectDesignPreview("light", "sessions")
@Preview(name = "Conversation", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun ConversationPreview() = ProjectDesignPreview("light", "chat")
@Preview(name = "Workspace · dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun WorkspacePreview() = ProjectDesignPreview("dark", "workspace")

@Composable private fun ProjectDesignPreview(theme: String, initialScreen: String) = WorkbenchTheme(theme) {
    if (initialScreen.startsWith("picker-")) { PickerGallery(initialScreen); return@WorkbenchTheme }
    if (initialScreen == "reference") { ConversationGallery(); return@WorkbenchTheme }
    if (initialScreen == "status") { TaskStatusGallery(); return@WorkbenchTheme }
    if (initialScreen.startsWith("host-editor")) {
        val editor = remember { HostEditorViewModel(androidx.lifecycle.SavedStateHandle()).apply {
            open(HostProfile(name = "开发机", endpoint = SshEndpoint(host = "dev.example.com", username = "developer", auth = AuthKind.PASSWORD)),
                when (initialScreen.substringAfter("host-editor-", "")) {
                    "auth" -> HostEditorSection.AUTH
                    "jumps" -> HostEditorSection.JUMPS
                    "remote" -> HostEditorSection.REMOTE
                    else -> HostEditorSection.BASIC
                })
        } }
        LaunchedEffect(Unit) { editor.loadCredentials { Credential() } }
        HostEditorForm(editor, existing = true, onClose = {}, onSave = { _, _, done -> done(true) })
        return@WorkbenchTheme
    }
    if (initialScreen == "icons") { IconGallery(); return@WorkbenchTheme }
    if (initialScreen == "math") { MathGallery(); return@WorkbenchTheme }
    if (initialScreen == "markdown") { MarkdownGallery(); return@WorkbenchTheme }
    if (initialScreen == "code") { CodeGallery(); return@WorkbenchTheme }
    if (initialScreen.startsWith("terminal")) { TerminalGallery(initialScreen == "terminal-multi"); return@WorkbenchTheme }
    val projects = listOf(ProjectProfile("/home/dev/ghub", "GHub"), ProjectProfile("/home/dev/website", "个人网站"), ProjectProfile("/home/dev/api-service", "API 服务"))
    val threads = listOf(
        ThreadSummary("reconnect", "优化会话恢复体验", projects[0].path, "idle"),
        ThreadSummary("uploads", "处理图片上传失败", projects[0].path, "active"),
        ThreadSummary("theme", "调整深色主题与排版", projects[0].path),
        ThreadSummary("website", "完善文章列表", projects[1].path),
    )
    var screen by rememberSaveable { mutableStateOf(if (initialScreen == "workspace" || initialScreen == "picker") "chat" else initialScreen) }
    var menu by rememberSaveable { mutableStateOf(initialScreen == "workspace") }
    var homeMenu by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf(threads.first().title) }
    var text by rememberSaveable { mutableStateOf("") }
    var unread by rememberSaveable { mutableStateOf(true) }
    var projectRoot by rememberSaveable { mutableStateOf(projects.first().path) }
    val project = projects.first { it.path == projectRoot }
    val back = { screen = when (screen) { "host" -> "hosts"; else -> "projects" } }
    BackHandler(screen != "projects" && !menu, back)
    val sampleHost = remember { HostProfile(id = "demo", name = "开发机", endpoint = SshEndpoint(host = "dev.example.com", username = "dev")) }
    val sampleWorkspace = HostWorkspace(connection = ConnectionStatus.ONLINE, serverVersion = "0.165.0", account = "已登录")
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (screen == "settings") { SettingsScreen(theme, 1, false, back, {}, { screen = "hosts" }, {}, {}, {}, {}); return@WorkbenchTheme }
    if (screen == "hosts") { HostsScreen(listOf(sampleHost), mapOf("demo" to sampleWorkspace), back, { screen = "host" }, {}); return@WorkbenchTheme }
    if (screen == "host") { HostDetailsScreen(sampleHost, sampleWorkspace, back, {}, {}, {}); return@WorkbenchTheme }
    val ws = HostWorkspace(connection = ConnectionStatus.ONLINE, projects = projects, selectedProject = projectRoot, threads = threads,
        results = mapOf("reconnect" to ResultReceipt("turn", "completed", completedTurnId = "turn", seenTurnId = if (unread) null else "turn")))
    val sampleItems = listOf(
        TimelineItem("user", "turn", "userMessage", "帮我优化网络切换后的会话恢复，避免消息重复发送。", true),
        TimelineItem("reply", "turn", "agentMessage", "我会先检查连接与消息状态，再调整恢复流程。", true),
        TimelineItem("tool", "turn", "commandExecution", "rg -n reconnect app/src", true),
        TimelineItem("tool2", "turn", "commandExecution", "git diff --stat", true),
        TimelineItem("answer", "turn", "agentMessage", "已调整恢复流程：\n\n- 重连后先同步远端状态\n- 保留未发送的本地草稿\n- 待确认的消息不会自动重发\n\n改动集中在连接管理与草稿状态处理。", true),
        TimelineItem("diff", "turn", "fileChange", "WorkbenchViewModel.kt", true, fileEdits = listOf(
            FileEdit("app/WorkbenchViewModel.kt", "@@ -1,1 +1,2 @@\n-resume()\n+syncState()\n+resumeDraft()"),
            FileEdit("app/Draft.kt", "@@ -1,1 +1,1 @@\n-val pending = false\n+val pending = delivery == Uncertain"))),
    )
    GlassWorkbenchScaffold(
        glassEnabled = screen == "projects",
        topBar = { WorkbenchHeader(if (screen == "projects") "开发机" else title,
            if (screen == "projects") null else project.name, ConnectionStatus.ONLINE, screen != "projects", back,
            backLabel = "返回项目列表", onMenu = { if (screen == "projects") homeMenu = true else menu = true }, onAdd = if (screen == "projects") ({}) else null) },
        modifier = Modifier.imePadding(),
    ) { padding ->
        if (screen == "projects" || screen == "sessions") holder.SaveableStateProvider("projects") {
            Box(Modifier.fillMaxSize().padding(padding)) {
                ProjectsScreen(ws, onAdd = {}, onRefresh = {}, onMore = {},
                    onContinue = { id -> title = threads.first { it.id == id }.title; unread = false; screen = "chat" },
                    onCreate = {}, onProjectMenu = { projectRoot = it; menu = true })
            }
        } else Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { TurnContent(if (screen == "approval") sampleItems.take(4) else sampleItems, screen == "approval", {}) }
                if (screen == "approval") item { ApprovalCard(PendingApproval(ServerRequest("preview", JsonPrimitive(1), "item/commandExecution/requestApproval",
                    buildJsonObject { put("threadId", "reconnect"); put("command", "./gradlew :app:assembleDebug"); put("cwd", "/home/dev/ghub"); put("reason", "构建 APK，确认改动能正常编译。") }))) {} }
            }
            MessageComposer(Draft(text), listOf("默认模型" to "默认模型"), null, true, false, false,
                onText = { text = it }, onRemoveAttachment = {}, onModel = {}, onImage = {}, onFile = {}, onCamera = {}, onVoice = {}, onSend = {})
        }
    }
    if (homeMenu) ModalBottomSheet(onDismissRequest = { homeMenu = false }) {
        Column(Modifier.padding(16.dp)) {
            SheetAction("切换或管理主机", WorkbenchIcons.Dns) { homeMenu = false; screen = "hosts" }
            SheetAction("同步项目与会话", WorkbenchIcons.Refresh) { homeMenu = false }
            SheetAction("设置", WorkbenchIcons.Settings) { homeMenu = false; screen = "settings" }
        }
    }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (screen == "projects") ProjectWorkspaceMenu(project, WorkspaceTab.SESSIONS, false, onSelect = { menu = false },
            onProjects = { menu = false; screen = "projects" }, onRefresh = { menu = false })
        else ConversationActions(project.path, false, true,
            onFiles = { menu = false }, onTerminal = { menu = false },
            onRefresh = { menu = false }, onRename = { menu = false }, onArchive = { menu = false })
    }
}
