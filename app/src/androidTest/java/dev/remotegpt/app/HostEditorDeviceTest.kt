package dev.remotegpt.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.remotegpt.app.ui.HostEditorForm
import dev.remotegpt.app.ui.WorkbenchTheme
import dev.remotegpt.client.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostEditorDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun editor(auth: AuthKind = AuthKind.PASSWORD) = HostEditorViewModel(SavedStateHandle()).apply {
        open(HostProfile(name = "Development", endpoint = SshEndpoint(host = "dev.example", username = "dev", auth = auth)))
        runBlocking { loadCredentials { Credential() } }
    }

    @Test fun missingKeyOpensAuthenticationAndAllSectionsSaveOneDraft() {
        val editor = editor(AuthKind.PRIVATE_KEY)
        var saved: HostProfile? = null
        var secrets: Map<String, Credential>? = null
        var closed = false
        compose.setContent { WorkbenchTheme { HostEditorForm(editor, onClose = { closed = true }, onSave = { p, c, done ->
            saved = p; secrets = c; done(true)
        }) } }
        compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
        compose.onNodeWithText(tr(R.string.ui_ssh_password)).assertDoesNotExist()
        compose.onNodeWithText(tr(R.string.ui_password)).performClick()
        compose.onNodeWithText(tr(R.string.ui_ssh_password)).performScrollTo().performTextInput("draft-password")
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithText(tr(R.string.ui_remote_codex)).performScrollTo().performClick()
        compose.onNodeWithText(tr(R.string.ui_default_working_directory)).performTextReplacement("/work/project")
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        assertNull(saved)
        compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
        compose.runOnIdle {
            assertEquals("/work/project", saved?.defaultCwd)
            assertEquals(AuthKind.PASSWORD, saved?.endpoint?.auth)
            assertEquals("draft-password", secrets?.get(editor.profile!!.endpoint.credentialId)?.password)
            assertTrue(closed)
        }
    }

    @Test fun incompleteHopIsRevealedAndCanBeRemovedWithoutLosingOtherChanges() {
        val editor = editor()
        var saves = 0
        compose.setContent { WorkbenchTheme { HostEditorForm(editor, onClose = {}, onSave = { _, _, done -> saves++; done(true) }) } }
        compose.onNodeWithText(tr(R.string.ui_connection_name)).performTextReplacement("Renamed")
        compose.onNodeWithText(tr(R.string.ui_jump_hosts)).performScrollTo().performClick()
        compose.onNodeWithText(tr(R.string.ui_add_jump_host)).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
        compose.onNodeWithText(tr(R.string.ui_host_address)).assertIsFocused()
        assertEquals(0, saves)
        compose.onNodeWithText(tr(R.string.ui_remove_jump_host)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithText("Renamed").assertExists()
        compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
        assertEquals(1, saves)
        assertTrue(editor.profile!!.jumps.isEmpty())
    }

    @Test fun backingOutOffersToKeepOrDiscardUnsavedCredentials() {
        val editor = editor()
        var closed = false
        compose.setContent { WorkbenchTheme { HostEditorForm(editor, onClose = { closed = true }, onSave = { _, _, _ -> }) } }
        compose.onNodeWithText(tr(R.string.ui_authentication)).performScrollTo().performClick()
        compose.onNodeWithText(tr(R.string.ui_ssh_password)).performTextInput("new-secret")
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back_to_servers)).performClick()
        compose.onNodeWithText(tr(R.string.ui_discard_unsaved_changes)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.ui_cancel)).performClick()
        assertFalse(closed)
        assertEquals("new-secret", editor.credentials[editor.profile!!.endpoint.credentialId]?.password)
        compose.onNodeWithContentDescription(tr(R.string.ui_back_to_servers)).performClick()
        compose.onNodeWithText(tr(R.string.ui_discard_changes)).performClick()
        assertTrue(closed)
    }

    @Test fun pendingSavePreventsFurtherEditsAndNavigation() {
        val editor = editor()
        var finish: ((Boolean) -> Unit)? = null
        var closed = false
        compose.setContent { WorkbenchTheme { HostEditorForm(editor, onClose = { closed = true }, onSave = { _, _, done -> finish = done }) } }
        compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
        compose.onNodeWithText(tr(R.string.ui_connection_name)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(tr(R.string.ui_back_to_servers)).assertIsNotEnabled()
        compose.runOnIdle { finish!!(false) }
        compose.onNodeWithText(tr(R.string.ui_save_connection)).assertIsEnabled()
        assertFalse(closed)
    }
}
