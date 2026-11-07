package dev.remotegpt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ui.connectionLabel
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.remotegpt.app.ui.HostDetailsScreen
import dev.remotegpt.app.ui.WorkbenchTheme
import dev.remotegpt.client.HostProfile
import dev.remotegpt.client.SshEndpoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostDetailsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionActionsStayInSettingsAndFollowTheConnectionState() {
        var workspace by mutableStateOf(HostWorkspace())
        var connections = 0
        var disconnections = 0
        var edits = 0
        val profile = HostProfile(name = "Development", endpoint = SshEndpoint(host = "dev.example", username = "dev"))
        compose.setContent { WorkbenchTheme {
            HostDetailsScreen(profile, workspace, {}, { edits++ },
                onConnect = { connections++; workspace = workspace.copy(connection = ConnectionStatus.CONNECTING) },
                onDisconnect = { disconnections++; workspace = workspace.copy(connection = ConnectionStatus.OFFLINE) })
        } }
        compose.onNodeWithText(tr(R.string.ui_connect)).assertDoesNotExist()
        compose.onNodeWithContentDescription(tr(R.string.ui_connect)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, connectionLabel(ConnectionStatus.OFFLINE))).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_cancel_connection)).assertIsDisplayed().performClick()
        assertEquals(1, connections)
        assertEquals(1, disconnections)
        compose.onNodeWithContentDescription(tr(R.string.ui_connect)).assertIsDisplayed()

        compose.runOnIdle { workspace = workspace.copy(connection = ConnectionStatus.ONLINE, serverVersion = "0.165.0") }
        compose.onNodeWithText(tr(R.string.ui_open_projects)).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(tr(R.string.ui_disconnect)).assertCountEquals(1)
        compose.onNodeWithContentDescription(tr(R.string.ui_disconnect))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, connectionLabel(ConnectionStatus.ONLINE)))
        compose.onNodeWithText(tr(R.string.ui_remote_environment)).performClick()
        compose.onNodeWithText(tr(R.string.ui_app_server) + " · 0.165.0").assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.ui_close)).performClick()
        compose.onNodeWithText(tr(R.string.ui_edit_connection)).performClick()
        assertEquals(1, edits)
        assertEquals(ConnectionStatus.ONLINE, workspace.connection)
        compose.onNodeWithContentDescription(tr(R.string.ui_disconnect)).performClick()
        compose.onNodeWithText(tr(R.string.ui_remote_environment)).assertDoesNotExist()
        assertEquals(2, disconnections)

        compose.runOnIdle { workspace = workspace.copy(connection = ConnectionStatus.WAITING_NETWORK) }
        compose.onNodeWithContentDescription(tr(R.string.ui_stop_reconnecting)).performClick()
        assertEquals(ConnectionStatus.OFFLINE, workspace.connection)
        assertEquals(3, disconnections)

        compose.runOnIdle { workspace = workspace.copy(connection = ConnectionStatus.ERROR, needsBootstrap = true) }
        compose.onNodeWithContentDescription(tr(R.string.ui_set_up_and_connect)).performClick()
        assertEquals(2, connections)
        compose.onNodeWithContentDescription(tr(R.string.ui_cancel_connection)).assertIsDisplayed()
    }
}
