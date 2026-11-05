package dev.remotegpt.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterruptedInputDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Composable private fun Composer(draft: Draft, actions: MessageRevisionActions, onText: (String) -> Unit = {}, onSend: () -> Unit = {}) {
        val input = draft.composerDraft()
        MessageComposer(draft, emptyList(), input.model, true, false, false, onText, {}, {}, {}, {}, {}, {}, onSend,
            effort = input.effort, revision = actions, modifier = Modifier.testTag("composer"))
    }

    private fun capture(name: String) {
        compose.onNodeWithTag("composer").captureToImage().asAndroidBitmap().let { bitmap ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun pencilReusesComposerAndPreservesOriginalMessageAndOrdinaryDraft() {
        val item = TimelineItem("prompt", "turn", "userMessage", "Original input", true)
        val ordinary = Draft(text = "Unsent next task", model = "next-model", effort = "low")
        var draft by mutableStateOf(ordinary)
        var sent: MessageInput? = null
        compose.setContent { WorkbenchTheme { Column(Modifier.fillMaxSize()) {
            val actions = MessageRevisionActions(item.id, draft.edit, true,
                onBegin = { draft = draft.copy(edit = InterruptedInputEdit("edit", item.turnId, item.id, MessageInput(item.text, model = "original-model", effort = "high"))) },
                onCancel = { draft = draft.copy(edit = null) }, onRefresh = {}, onResolveUncertain = {})
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { TurnContent(listOf(item), false, {}, revision = actions) }
            Composer(draft, actions, onText = { value -> draft = draft.copy(edit = draft.edit!!.copy(input = draft.edit!!.input.copy(text = value))) },
                onSend = { sent = draft.edit!!.input; draft = draft.acceptedSubmission(sent!!, "new-turn", "new-message", true) })
        } } }
        compose.onNodeWithContentDescription(label(R.string.ui_message_input)).assertTextEquals(ordinary.text)
        val normalSend = compose.onNodeWithContentDescription(label(R.string.ui_send_message)).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_input)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_text)).assertIsFocused().assertTextEquals(item.text)
        compose.onNodeWithText("original-model", substring = true).assertExists()
        val editSend = compose.onNodeWithContentDescription(label(R.string.ui_edit_and_resend)).fetchSemanticsNode().boundsInRoot
        assertEquals(normalSend.width, editSend.width)
        assertEquals(normalSend.height, editSend.height)
        assertEquals(normalSend.left, editSend.left)
        compose.onNodeWithText(label(R.string.ui_edit_and_resend)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_text)).performTextReplacement("Revised 中文")
        compose.onNodeWithText(item.text).assertExists()
        capture("interrupted-input-composer.png")
        compose.onNodeWithText(label(R.string.ui_cancel)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription(label(R.string.ui_message_input)).assertTextEquals(ordinary.text)
        compose.runOnIdle { assertEquals(ordinary, draft) }
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_input)).performClick()
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_text)).performTextReplacement("Second revision")
        compose.onNodeWithContentDescription(label(R.string.ui_edit_and_resend)).performClick()
        compose.onNodeWithContentDescription(label(R.string.ui_message_input)).assertTextEquals(ordinary.text)
        compose.runOnIdle { assertEquals("Second revision", sent!!.text); assertEquals(ordinary.input(), draft.input()) }
    }

    @Test fun narrowComposerWithLargeTextKeepsRevisionAndAttachmentActionsVisible() {
        val edit = InterruptedInputEdit("edit", "turn", "prompt", MessageInput("Revised input", model = "gpt-5.4", effort = "high"))
        compose.setContent { WorkbenchTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Column(Modifier.width(320.dp)) {
                    Composer(Draft(edit = edit), MessageRevisionActions("prompt", edit, true, {}, {}, {}, {}))
                }
            }
        } }
        compose.onNodeWithContentDescription(label(R.string.ui_edit_and_resend)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(label(R.string.ui_cancel)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("gpt-5.4", substring = true).assertIsDisplayed()
        capture("interrupted-input-composer-large-text.png")
        compose.onNodeWithContentDescription(label(R.string.ui_add_attachment)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.ui_choose_file)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_choose_image)).assertIsDisplayed()
    }

    @Test fun uncertainSubmissionCannotBeChangedOrSentAgain() {
        val edit = InterruptedInputEdit("edit", "turn", "prompt", MessageInput("Unconfirmed input",
            listOf(Attachment("file", "note.txt", remotePath = "/tmp/note.txt", state = "ready"))), "uncertain", "pending")
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            Composer(Draft(text = "Ordinary draft", edit = edit), MessageRevisionActions("prompt", edit, true, {}, {}, {}, {}))
        } } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription(label(R.string.ui_edit_interrupted_text)).assertIsNotEnabled()
        compose.onNodeWithText("note.txt").assertExists()
        compose.onNodeWithContentDescription(label(R.string.ui_edit_and_resend)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.ui_cancel)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.ui_refresh_edit_history)).assertExists()
        compose.onNodeWithText(label(R.string.ui_history_checked_unlock_draft)).assertExists()
    }
}
