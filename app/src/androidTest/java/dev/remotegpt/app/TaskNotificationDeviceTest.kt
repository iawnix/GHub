package dev.remotegpt.app

import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import dev.remotegpt.app.ui.Workbench
import dev.remotegpt.app.ui.WorkbenchTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskNotificationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication

    @Test fun approvalActionsRequireUnlock() {
        val notice = TaskNotice.Attention("review-host", "review-thread", "Review", "Fixture", "epoch:1", "review-item", false, "echo review", false)
        val notification = TaskNotifier(app).attention(notice)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(2, notification.actions.size)
            assertTrue("Remote command approval must require unlocking the device", notification.actions.all { it.isAuthenticationRequired })
            assertTrue(notification.actions.all { it.actionIntent.isBroadcast })
        } else {
            assertEquals(1, notification.actions.size)
            assertEquals(tr(R.string.notify_review_approval), notification.actions.single().title)
            assertTrue("Older Android must open the conversation for confirmation", notification.actions.single().actionIntent.isActivity)
            assertEquals(notification.contentIntent, notification.actions.single().actionIntent)
        }
        assertNull(TaskNotifier(app).attention(notice.copy(question = true)).actions)
    }

    @Test fun notificationFromSettingsMustShowConversation() = runBlocking {
        val vm = withContext(Dispatchers.Main) { app.workbench() }
        val profile = HostProfile(id = "notification-review-host", name = "Notification review", endpoint = SshEndpoint("localhost", 1, "fixture"))
        try {
            withContext(Dispatchers.Main) { vm.saveProfile(profile, emptyMap()) }.join()
            compose.waitUntil(10000) { profile.id in vm.state.value.workspaces }
            compose.setContent { WorkbenchTheme { Workbench(vm, vm.uiState.collectAsState().value) } }
            compose.runOnIdle { vm.selectHost(profile.id); vm.backToProjects(); vm.message(null) }
            compose.onNodeWithContentDescription(app.getString(R.string.ui_settings)).performClick()
            compose.onNodeWithText(app.getString(R.string.ui_settings)).assertIsDisplayed()
            compose.runOnIdle {
                vm.openFromNotification(profile.id, "notification-review-thread")
            }
            compose.waitUntil(10000) { vm.state.value.workspace.selectedThread == "notification-review-thread" }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(app.getString(R.string.ui_message_input)).assertIsDisplayed()
            // The same conversation must also reopen when its options sheet obscures it.
            compose.onNodeWithContentDescription(app.getString(R.string.ui_more_actions)).performClick()
            compose.runOnIdle { vm.openFromNotification(profile.id, "notification-review-thread") }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(app.getString(R.string.ui_message_input)).assertIsDisplayed()
            Unit
        } finally {
            withContext(Dispatchers.Main) { vm.deleteProfile(profile) }.join()
        }
    }
}
