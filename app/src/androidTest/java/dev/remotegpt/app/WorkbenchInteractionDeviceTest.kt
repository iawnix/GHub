@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.ThreadSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbenchInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun expandingBottomProjectRevealsFirstSessionAndQuickToggleCanReverse() {
        val projects = (1..9).map { ProjectProfile("/project/$it", "Project $it") }
        val threads = projects.map { ThreadSummary(it.path, "Session ${it.name}", it.path) }
        val ws = HostWorkspace(connection = ConnectionStatus.ONLINE, projects = projects, threads = threads)
        compose.setContent { WorkbenchTheme {
            GlassWorkbenchScaffold(topBar = { WorkbenchHeader("Host", ConnectionStatus.ONLINE, false, {}) }) { padding ->
                Box(Modifier.padding(padding).height(360.dp)) { ProjectsScreen(ws, {}, {}, {}) }
            }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("project:/project/5")
        compose.onNodeWithText("Project 5").performClick()
        compose.onNodeWithText("Session Project 5").assertIsDisplayed()
        val header = compose.onNodeWithText("Project 5").fetchSemanticsNode().boundsInRoot
        val session = compose.onNodeWithText("Session Project 5").fetchSemanticsNode().boundsInRoot
        assertTrue(header.top > compose.onNodeWithText("Host").fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue("Session must follow the project row: header=$header, session=$session", session.top >= header.bottom)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Project 5").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Project 5").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Session Project 5").assertIsDisplayed()
    }

    @Test fun multilineInputLeavesAllActionsBelowTextAtLargeFont() {
        var draft by mutableStateOf(Draft())
        compose.setContent { WorkbenchTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    ConversationViewport(dock = {
                        MessageComposer(draft, listOf("m" to "Long model name"), "m", true, false,
                            { draft = draft.copy(text = it) }, {}, {}, {}, {}, {}, {})
                    }) { _, _ -> }
                }
            }
        } }
        val input = compose.onNodeWithContentDescription(context.getString(R.string.ui_message_input))
        input.performTextInput("Long message 中文测试\n".repeat(30))
        val inputNode = input.fetchSemanticsNode()
        val optionsDescription = context.getString(R.string.ui_message_options_current, "m", context.getString(R.string.ui_default))
        val actionNodes = listOf(context.getString(R.string.ui_add_attachment), optionsDescription,
            context.getString(R.string.ui_send_message))
            .map { compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode() }
        // Read all coordinates in one UI frame; IME insets can move the dock between test queries.
        compose.runOnIdle {
            val inputRect = inputNode.boundsInRoot
            val actions = actionNodes.map { it.boundsInRoot }
            assertTrue("Actions must stay below input: input=$inputRect, actions=$actions", actions.all { it.top >= inputRect.bottom })
            assertTrue("Actions must share a row: $actions", actions.all { kotlin.math.abs(it.center.y - actions.first().center.y) < 2f })
            assertTrue("Actions must not overlap: $actions", actions.zipWithNext().all { (left, right) -> left.right <= right.left + 1f })
        }
        compose.onNodeWithContentDescription(optionsDescription).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_choose_model)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ui_reasoning_level)).assertIsDisplayed()
    }

    @Test fun glassActuallyBlursTheBackgroundWithReadableOlderAndroidFallback() {
        compose.setContent { WorkbenchTheme("light") {
            val backdrop = rememberHazeState(blurEnabled = Build.VERSION.SDK_INT >= 31)
            CompositionLocalProvider(LocalWorkbenchBackdrop provides backdrop) {
                Box(Modifier.size(160.dp).testTag("glass-sample")) {
                    Canvas(Modifier.fillMaxSize().workbenchBackdropSource()) {
                        val stripe = 4.dp.toPx()
                        repeat((size.width / stripe).toInt() + 1) {
                            drawRect(if (it % 2 == 0) Color.Black else Color.White, Offset(it * stripe, 0f), Size(stripe, size.height))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter).workbenchGlass())
                }
            }
        } }
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("glass-sample").captureToImage().toPixelMap()
        val values = (pixels.width / 4 until pixels.width * 3 / 4).map { pixels[it, pixels.height * 3 / 4].red }
        val spread = values.max() - values.min()
        assertTrue("Background stripes must not remain sharp: $spread", spread < .08f)
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue("Blur must retain background tone, not paint an opaque surface: ${values.average()}", values.average() in .72.. .94)
        } else assertTrue("Fallback must keep controls readable", values.average() > .95)
    }
}
