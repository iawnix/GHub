package dev.remotegpt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatusIndicatorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun serverSelectionKeepsStatusAccessibleAndFailureVisible() {
        var connection by mutableStateOf(ConnectionStatus.ONLINE)
        var selected: String? = null
        val host = HostProfile(id = "development", name = "Development")
        compose.setContent { WorkbenchTheme("dark") {
            HostSwitcherSheet(listOf(host), null, mapOf(host.id to HostWorkspace(connection = connection)),
                onSelect = { selected = it.id }, onDismiss = {})
        } }
        compose.onNodeWithText(tr(R.string.ui_connected)).assertDoesNotExist()
        compose.onNodeWithContentDescription(tr(R.string.ui_connected), useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(host.id, selected) }
        compose.onNodeWithContentDescription(tr(R.string.ui_connected), useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(tr(R.string.ui_connected)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2500)
        compose.runOnIdle { connection = ConnectionStatus.ERROR }
        compose.onNodeWithText(tr(R.string.ui_needs_attention), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(tr(R.string.ui_connected), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun completedTaskAndTransportRemainDistinctWhenConnectionDrops() {
        var connected by mutableStateOf(true)
        var syncing by mutableStateOf(false)
        compose.setContent { WorkbenchTheme {
            Column {
                TaskStatusLabel(TaskStatus.COMPLETED)
                OutputSyncBadge(active = false, syncing = syncing, connected = connected)
            }
        } }
        compose.onNodeWithText(tr(R.string.task_completed)).assertDoesNotExist()
        compose.onNodeWithContentDescription(tr(R.string.task_completed)).assertIsDisplayed()
        compose.onNodeWithContentDescription(tr(R.string.ui_output_synced)).assertIsDisplayed()
        compose.runOnIdle { connected = false }
        compose.onNodeWithText(tr(R.string.ui_output_waiting_to_sync)).assertIsDisplayed()
        compose.onNodeWithContentDescription(tr(R.string.ui_output_synced)).assertDoesNotExist()
        compose.onNodeWithContentDescription(tr(R.string.task_completed)).assertIsDisplayed()
        compose.runOnIdle { connected = true; syncing = true }
        compose.onNodeWithText(tr(R.string.ui_output_reconciling)).assertIsDisplayed()
    }
}
