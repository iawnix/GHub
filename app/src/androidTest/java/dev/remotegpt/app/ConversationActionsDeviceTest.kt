package dev.remotegpt.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.TimelineItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationActionsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun copyButton(id: Int) = compose.onNodeWithContentDescription(label(id))
    private fun clipboard() = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.text?.toString()
    private fun copied(id: Int, expected: Boolean) {
        val state = copyButton(id).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(if (expected) label(R.string.ui_copied) else null, state)
    }

    @Test fun copyTargetsHaveStableBoundsAndFeedbackTracksLatestContent() {
        val user = "用户原文\n\t$" + "value"
        val reply = "**完整回复**\n\n\\(x^2\\)"
        var output by mutableStateOf("line 1\n")
        compose.setContent { WorkbenchTheme("dark") {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                UserMessageActions(user, {})
                ReplyActions(reply)
                CodeBlock(output, "output", "Output", tr(R.string.ui_copy_output))
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for ((id, source) in listOf(R.string.ui_copy_message to user, R.string.chat_copy_reply to reply, R.string.ui_copy_output to output)) {
            val button = copyButton(id)
            button.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val before = button.fetchSemanticsNode().boundsInRoot
            button.performClick()
            compose.mainClock.advanceTimeBy(320)
            compose.runOnIdle { assertEquals(source, clipboard()) }
            copied(id, true)
            assertEquals(before, button.fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { output = "line 1\n\tline 2 中文\n" }
        compose.mainClock.advanceTimeBy(32)
        copied(R.string.ui_copy_output, false)
        copyButton(R.string.ui_copy_output).performClick()
        compose.mainClock.advanceTimeBy(1000)
        copyButton(R.string.ui_copy_output).performClick()
        compose.mainClock.advanceTimeBy(1000)
        copied(R.string.ui_copy_output, true)
        compose.runOnIdle { assertEquals(output, clipboard()) }
        compose.mainClock.advanceTimeBy(1000)
        copied(R.string.ui_copy_output, false)
    }

    @Test fun longPressExplainsActionAndRejectedCopyDoesNotClaimSuccess() {
        compose.setContent { WorkbenchTheme {
            Column {
                UserMessageActions("clipboard sentinel", null)
                ReplyActions("x".repeat(100_001))
            }
        } }
        copyButton(R.string.ui_copy_message).performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.ui_copy_message)).assertIsDisplayed()
        // Let the transient tooltip dismiss before performing another action.
        compose.mainClock.advanceTimeBy(2500)
        copyButton(R.string.ui_copy_message).performClick()
        copyButton(R.string.chat_copy_reply).performClick()
        compose.runOnIdle { assertEquals("clipboard sentinel", clipboard()) }
        copied(R.string.chat_copy_reply, false)
    }

    @Test fun formulaActionStaysBesideScrollableContentAndTableCopiesMarkdownInFullScreen() {
        val formula = "\\[" + (1..32).joinToString("+") { "x_{$it}" } + "\\]"
        val table = "| Name | Value |\n| --- | --- |\n| **中文** | `code` |"
        compose.setContent { WorkbenchTheme {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                TimelineCard(TimelineItem("math", "t", "agentMessage", "$formula\n\n$table", true), {})
            }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(formula, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val formulaBounds = compose.onNodeWithContentDescription(formula, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val buttonBounds = copyButton(R.string.ui_copy_formula).fetchSemanticsNode().boundsInRoot
        assertTrue("Copy and formula must share vertical space", buttonBounds.top < formulaBounds.bottom && buttonBounds.bottom > formulaBounds.top)
        assertTrue("The action must not cover the scrollable formula", formulaBounds.right <= buttonBounds.left)
        copyButton(R.string.ui_copy_formula).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(formula.drop(2).dropLast(2), clipboard()) }
        compose.onNodeWithContentDescription(label(R.string.ui_expand_table)).performScrollTo().performClick()
        compose.onNode(hasContentDescription(label(R.string.ui_copy_table)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(table, clipboard()) }
    }
}
