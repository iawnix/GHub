package dev.remotegpt.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicToolCallDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun clipboard() = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip!!.getItemAt(0).text.toString()

    @Test fun toolRecordsExpandAndCopyArgumentsAndFailureOutput() {
        val args = buildJsonObject { put("query", "中文\n\tvalue") }
        val output = "tool error\n\tdetail 中文"
        val item = Timeline.parseItem(buildJsonObject {
            put("id", "call"); put("type", "dynamicToolCall"); put("tool", "search"); put("namespace", "tools")
            put("arguments", args); put("status", "completed"); put("success", false)
            putJsonArray("contentItems") { add(buildJsonObject { put("type", "inputText"); put("text", output) }) }
        }, "turn", true)
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TurnContent(listOf(item), false, {})
        } } }
        compose.onNodeWithText(label(R.string.ui_execution_details_includes_unsupported_types)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_failed_operations_view_details, 1)).performClick()
        compose.onNodeWithText("tools.search").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.task_failed)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.ui_copy_tool_arguments)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(args, wireJson.parseToJsonElement(clipboard())) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(output).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label(R.string.ui_copy_output)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(output, clipboard()) }
    }

    @Test fun mediaAndFutureResultsAreVisibleAndLegacyCacheExplainsRefresh() {
        val item = Timeline.parseItem(buildJsonObject {
            put("id", "call"); put("type", "dynamicToolCall"); put("tool", "render"); put("arguments", JsonNull)
            put("status", "completed"); put("success", true)
            putJsonArray("contentItems") {
                add(buildJsonObject { put("type", "inputImage"); put("imageUrl", "https://example.com/result.png") })
                add(buildJsonObject { put("type", "inputAudio"); put("audioUrl", "https://example.com/result.wav") })
                add(buildJsonObject { put("type", "future"); put("value", "kept") })
            }
        }, "turn", true)
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DynamicToolCallContent(item)
            DynamicToolCallContent(item.copy(dynamicToolCall = null))
        } } }
        compose.onNodeWithText(label(R.string.ui_tool_image_result)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_tool_audio_result)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_tool_other_result)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_tool_legacy_record)).performScrollTo().assertIsDisplayed()
    }
}
