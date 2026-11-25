package dev.remotegpt.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.ModelOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun longReplySharesACompleteFileWithoutOversizedIntentExtras() = runBlocking {
        val text = "中文 Markdown **bold** 😀\n".repeat(5_000)
        val intent = replyShareIntent(context, text)
        assertNull(intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
        val uri = intent.clipData!!.getItemAt(0).uri
        try {
            assertEquals(text, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { context.contentResolver.delete(uri, null, null) }
        Unit
    }

    @Test fun fileSummaryExpandsAndCopiesOnlyTheAnswer() {
        val items = referenceConversation()
        compose.setContent { WorkbenchTheme { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(20.dp)) { TurnContent(items, false, {}) } } }
        compose.onNodeWithText("Components.kt").assertExists()
        compose.onNodeWithText("docs/ui-design.md").assertDoesNotExist()
        val fullPath = "app/src/main/java/dev/remotegpt/app/ui/Components.kt"
        compose.onNodeWithText(fullPath).assertDoesNotExist()
        compose.onNodeWithText("Components.kt").performScrollTo().performClick()
        compose.onNodeWithText(fullPath).assertExists()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.chat_more_files, 2, 2)).performScrollTo().performClick()
        compose.onNodeWithText("ui-design.md").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_copy_reply)).performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(items.first { it.id == "answer" }.text, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    @Test fun compactComposerKeepsModelEffortUploadAndStopControls() {
        var draft by mutableStateOf(Draft())
        var running by mutableStateOf(false)
        var sends = 0
        var stops = 0
        val options = listOf(ModelOption("main", "Main model", listOf("medium", "high"), "medium", true),
            ModelOption("fast", "Fast model", listOf("medium", "high"), "medium", false))
        compose.setContent { WorkbenchTheme {
            MessageComposer(draft, options.map { it.id to it.name }, draft.model, true, false,
                onText = { draft = draft.copy(text = it) }, onRemoveAttachment = {}, onModel = { draft = draft.copy(model = it) },
                onImage = {}, onFile = {}, onCamera = {}, onSend = { sends++ },
                running = running, onStop = { stops++ }, modifier = Modifier.width(320.dp),
                modelOptions = options, effort = draft.effort, onEffort = { draft = draft.copy(effort = it) })
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_message_options_current,
            "Main model", context.getString(R.string.ui_default))).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_choose_model)).performClick()
        compose.onNodeWithText("Fast model").performClick()
        compose.runOnIdle { assertEquals("fast", draft.model) }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_message_options_current,
            "Fast model", context.getString(R.string.ui_default))).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_reasoning_level)).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_high)).performClick()
        compose.runOnIdle { assertEquals("high", draft.effort) }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_message_input)).performTextInput("Follow up")
        compose.onNodeWithContentDescription(context.getString(R.string.ui_send_message)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends); draft = draft.copy(attachments = listOf(Attachment("a", "pending.png", state = "uploading"))) }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_send_message)).assertIsNotEnabled()
        compose.runOnIdle { running = true }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_stop_task)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
