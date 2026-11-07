package dev.remotegpt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.TurnContent
import dev.remotegpt.app.ui.WorkbenchTheme
import dev.remotegpt.client.TimelineItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The output overview must expose transport and task state separately. */
@RunWith(AndroidJUnit4::class)
class OutputVisualizationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun liveOutputShowsReceivingStateAndRecentEvents() {
        val items = listOf(
            TimelineItem("command", "turn", "commandExecution", "pwd", true, executionStatus = "completed"),
            TimelineItem("answer", "turn", "agentMessage", "正在生成", false),
        )
        compose.setContent { WorkbenchTheme { OutputSample(items, active = true) } }
        compose.onNodeWithText(context.getString(R.string.ui_output_receiving)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.ui_output_live)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ui_output_command_short)).assertIsDisplayed()
    }

    @Test fun completedOutputCanShowReconciliationSeparately() {
        val items = listOf(
            TimelineItem("command", "turn", "commandExecution", "pwd", true, executionStatus = "completed"),
            TimelineItem("answer", "turn", "agentMessage", "完成", true),
        )
        compose.setContent { WorkbenchTheme { OutputSample(items, active = false, syncing = true) } }
        compose.onNodeWithText(context.getString(R.string.ui_output_reconciling)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ui_commands_run_view_details, 1)).assertIsDisplayed()
    }
}

@Composable private fun OutputSample(items: List<TimelineItem>, active: Boolean, syncing: Boolean = false) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        TurnContent(items, active, {}, syncing = syncing, connected = true)
    }
}
