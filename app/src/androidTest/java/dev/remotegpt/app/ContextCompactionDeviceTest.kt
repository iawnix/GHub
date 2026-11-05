package dev.remotegpt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextCompactionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun liveCompactionChangesFromRunningToCompletedWithoutExpandableDetails() {
        var item by mutableStateOf(TimelineItem("compact", "turn", "contextCompaction", ""))
        compose.setContent { WorkbenchTheme { TimelineCard(item, {}) } }
        compose.onNodeWithText(label(R.string.ui_context_compaction_running)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(TaskStatus.RUNNING, itemTaskStatus(item))
            item = item.copy(complete = true)
        }
        compose.onNodeWithText(label(R.string.ui_context_compaction_completed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_context_compaction_running)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.ui_expand)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(TaskStatus.COMPLETED, itemTaskStatus(item)) }
    }

    @Test fun oldCachedPlaceholderIsRecognizedInExecutionSummaryWithoutRefresh() {
        val placeholder = "此类型暂未完整显示：contextCompaction"
        val item = TimelineItem("compact", "turn", "contextCompaction", placeholder, complete = true)
        compose.setContent { WorkbenchTheme { Column { TurnContent(listOf(item), false, {}) } } }
        compose.onNodeWithText(label(R.string.ui_execution_details_includes_unsupported_types)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.ui_context_compaction)).assertIsDisplayed()
        val summary = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_events_view_details, 1)
        compose.onNodeWithText(summary).performClick()
        compose.onNodeWithText(label(R.string.ui_context_compaction_completed)).assertIsDisplayed()
        compose.onNodeWithText(placeholder).assertDoesNotExist()
    }
}
