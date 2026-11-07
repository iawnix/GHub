package dev.remotegpt.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hostFormAndUnsavedSecretSurviveActivityRecreation() {
        val profile = HostProfile(name = "Unsaved server", endpoint = SshEndpoint("example.test", username = "developer", auth = AuthKind.PASSWORD))
        lateinit var before: HostEditorViewModel
        compose.runOnIdle {
            before = ViewModelProvider(compose.activity)[HostEditorViewModel::class.java]
            before.open(profile)
            before.updateCredential(profile.endpoint.credentialId, Credential(password = "unsaved-test-secret"))
        }
        compose.onNodeWithText("Unsaved server").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Unsaved server").assertExists()
        compose.runOnIdle {
            val after = ViewModelProvider(compose.activity)[HostEditorViewModel::class.java]
            assertSame(before, after)
            assertEquals("unsaved-test-secret", after.credentials[profile.endpoint.credentialId]?.password)
            after.close()
        }
    }

    @Test fun savedHostStateRestoresMetadataWithoutSerializingCredentials() = runBlocking {
        val saved = SavedStateHandle()
        val editor = HostEditorViewModel(saved)
        val profile = HostProfile(name = "Restored", endpoint = SshEndpoint("example.test", username = "developer"))
        editor.open(profile)
        editor.updateCredential(profile.endpoint.credentialId, Credential(password = "never-save", privateKey = "private-test-key"))
        editor.navigate(HostEditorSection.AUTH)
        editor.updateProfile(profile.copy(name = "Changed metadata"))
        val restored = HostEditorViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(profile.copy(name = "Changed metadata"), restored.profile)
        assertEquals(HostEditorSection.AUTH, restored.section)
        assertTrue(restored.hasChanges)
        assertTrue(restored.credentials.isEmpty())
        assertFalse(saved.keys().any { saved.get<Any?>(it).toString().contains("never-save") || saved.get<Any?>(it).toString().contains("private-test-key") })
        restored.loadCredentials { Credential(password = "encrypted-store-value") }
        assertEquals("encrypted-store-value", restored.credentials[profile.endpoint.credentialId]?.password)
        editor.close()
        assertTrue(saved.keys().isEmpty())
    }

    @Test fun fileDraftRestoresContentAndConflictHashFromPrivateStorage() = runBlocking {
        val directory = File(compose.activity.filesDir, "editor-draft-test")
        try {
            val draft = EditorDraft("/remote/file.txt", "original", "old-sha256", "未保存的编辑😀")
            EditorDraftStore(directory).write("host", draft)
            assertEquals(draft, EditorDraftStore(directory).read("host"))
            EditorDraftStore(directory).write("host", null)
            assertNull(EditorDraftStore(directory).read("host"))
        } finally { directory.deleteRecursively() }
    }
}
