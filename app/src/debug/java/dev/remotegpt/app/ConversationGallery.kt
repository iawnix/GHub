@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*

/** Actual production components, offline content, no remote sessions or model calls. */
@Composable internal fun ConversationGallery() {
    var text by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf<String?>("high") }
    val items = remember { referenceConversation() }
    GlassWorkbenchScaffold(topBar = { WorkbenchHeader("优化会话体验", ConnectionStatus.ONLINE, true, {}, onMenu = {}) }) { padding ->
        ConversationViewport(Modifier.padding(padding).consumeWindowInsets(padding), dock = {
            MessageComposer(Draft(text = text, model = model, effort = effort), listOf("gpt-5.4" to "GPT-5.4"), model,
                true, false, false, onText = { text = it }, onRemoveAttachment = {}, onModel = { model = it },
                onImage = {}, onFile = {}, onCamera = {}, onVoice = {}, onSend = {},
                modelOptions = listOf(ModelOption("gpt-5.4", "GPT-5.4", listOf("low", "medium", "high"), "medium", true)),
                effort = effort, onEffort = { effort = it })
        }) { listPadding, _ ->
            LazyColumn(Modifier.fillMaxSize().workbenchBackdropSource(), contentPadding = listPadding) {
                item { TurnContent(items, false, {}, "completed") }
            }
        }
    }
}

internal fun referenceConversation() = listOf(
    TimelineItem("question", "reference", "userMessage", "让会话页面更简洁，文件改动也清晰一些。", true),
    TimelineItem("command", "reference", "commandExecution", "./gradlew :app:assembleDebug\nBUILD SUCCESSFUL", true, executionStatus = "completed"),
    TimelineItem("answer", "reference", "agentMessage", """
        已调整好会话页面。

        - **正文更清晰**：统一段落、列表和代码的排版。
        - **过程更紧凑**：执行输出默认收起，按需展开。
        - **改动更直观**：先看文件摘要，再查看具体 diff。

        输入框保留模型和思考强度选择，设置应用于下一条消息。

        完整说明见 [项目文档](https://example.com/docs)，本地代码位于 `app/src/main`。
    """.trimIndent(), true),
    TimelineItem("files", "reference", "fileChange", "", true, fileEdits = listOf(
        FileEdit("app/src/main/java/dev/remotegpt/app/ui/Components.kt", "@@ -1,2 +1,2 @@\n-Surface {\n+Column {\n content()"),
        FileEdit("app/src/main/java/dev/remotegpt/app/ui/TurnContent.kt", "@@ -1,1 +1,2 @@\n-FileList()\n+FileSummary()\n+ReplyActions()"),
        FileEdit("app/src/main/java/dev/remotegpt/app/ui/MessageComposer.kt", "@@ -1,1 +1,1 @@\n-oldInput()\n+compactInput()"),
        FileEdit("app/src/main/res/values-zh/strings.xml", "@@ -1,1 +1,2 @@\n <resources>\n+  <!-- 本轮改动 -->"),
        FileEdit("docs/ui-design.md", "@@ -1,1 +1,2 @@\n # Design\n+Conversation layout"),
    ), executionStatus = "completed"),
)
