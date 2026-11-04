package dev.remotegpt.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodePresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun clipboard() = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip!!.getItemAt(0).text.toString()
    private fun awaitText(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun commandAndStreamingOutputCopyIndependentlyWithoutChangingWhitespace() {
        val command = "printf '%s\\n' \"\$HOME\"\n\tgit status --short"
        var item by mutableStateOf(TimelineItem("cmd", "turn", "commandExecution", "first\n", false,
            executionStatus = "inProgress", commandExecution = CommandExecutionDetails(command)))
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { CommandExecutionContent(item) } } }
        awaitText(command)
        compose.waitUntil(5000) { compose.onNodeWithText(command).fetchSemanticsNode().config[SemanticsProperties.Text].first().spanStyles.isNotEmpty() }
        compose.onAllNodesWithContentDescription(label(R.string.ui_wrap_lines))[0].performClick()
        compose.onNodeWithContentDescription(label(R.string.ui_copy_command)).performClick()
        compose.runOnIdle { assertEquals(command, clipboard()); item = item.copy(text = "first\n\tsecond 中文\n", complete = true,
            executionStatus = "completed", commandExecution = item.commandExecution!!.copy(exitCode = 0)) }
        awaitText("first\n\tsecond 中文\n")
        // A zero exit code restates the completed status, so only non-zero codes are shown.
        compose.onNodeWithText(context.getString(R.string.ui_exit_code, 0)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.ui_copy_output)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("first\n\tsecond 中文\n", clipboard()) }
    }

    @Test fun failedCommandsStillCollapseWithTheExecutionSummary() {
        val command = "./gradlew test"
        val item = TimelineItem("cmd", "turn", "commandExecution", "BUILD FAILED", true, failed = true,
            executionStatus = "failed", commandExecution = CommandExecutionDetails(command, exitCode = 1))
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { TurnContent(listOf(item), false, {}) } } }
        compose.onNodeWithText("BUILD FAILED").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_failed_operations_view_details, 1)).performClick()
        compose.onNodeWithText(label(R.string.ui_command_execution)).performClick()
        awaitText("BUILD FAILED")
        compose.onNodeWithText("BUILD FAILED").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ui_failed_operations_view_details, 1)).performScrollTo().performClick()
        compose.onNodeWithText("BUILD FAILED").assertDoesNotExist()
    }

    @Test fun hugeSingleLineHasAFullReaderAndCopiesTheUnabridgedSourceAtLargeFont() {
        val source = "中文😀 ".repeat(3000) + "END_OF_OUTPUT"
        compose.setContent { WorkbenchTheme("dark") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    CodeBlock(source, "text", "Log", tr(R.string.ui_copy_output))
                }
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.ui_view_full_content)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.ui_view_full_content)).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(label(R.string.ui_wrap_lines)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(codePages(source).lastIndex)
        compose.onNodeWithText("END_OF_OUTPUT", substring = true).assertExists()
        compose.onNode(hasContentDescription(label(R.string.ui_copy_output)) and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(source, clipboard()) }
        compose.onNodeWithContentDescription(label(R.string.ui_back_to_session)).performClick()
        compose.onNodeWithText(label(R.string.ui_view_full_content)).assertExists()
    }

    @Test fun legacyCommandRecordsStayWholeAndNeverOfferAnInferredCommand() {
        val source = "echo x\nx\ncompleted"
        compose.setContent { WorkbenchTheme { CommandExecutionContent(TimelineItem("old", "t", "commandExecution", source, true)) } }
        awaitText(source)
        compose.onNodeWithText(label(R.string.ui_original_record)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.ui_copy_command)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.ui_copy_record)).performClick()
        compose.runOnIdle { assertEquals(source, clipboard()) }
    }

    @Test fun markdownUsesHighlightedCodeWithExactCopy() {
        val source = "def hello():\n\treturn \"中文\"\n"
        compose.setContent { WorkbenchTheme { TimelineCard(TimelineItem("reply", "t", "agentMessage", "```python\n${source}```", true), {}) } }
        awaitText(source)
        compose.waitUntil(5000) { compose.onNodeWithText(source).fetchSemanticsNode().config[SemanticsProperties.Text].first().spanStyles.isNotEmpty() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(source).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Horizontal scrolling must preserve explicit newlines", 3, layouts.single().lineCount)
        assertFalse(layouts.single().isLineEllipsized(0))
        compose.onNodeWithContentDescription(label(R.string.ui_copy_code)).performClick()
        compose.runOnIdle { assertEquals(source, clipboard()) }
    }
}
