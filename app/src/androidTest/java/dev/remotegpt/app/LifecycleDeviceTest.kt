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

    @Test fun changingAppThemePreservesActivityEditorAndNativeThemeAgreement() {
        val activity = compose.activity
        val app = activity.application as RemoteApplication
        val vm = app.workbench()
        val original = vm.state.value.theme
        val profile = HostProfile(name = "Theme draft", endpoint = SshEndpoint("example.test", username = "dev"))
        lateinit var editor: HostEditorViewModel
        compose.runOnIdle {
            editor = ViewModelProvider(activity)[HostEditorViewModel::class.java]
            editor.open(profile, HostEditorSection.AUTH)
            editor.updateCredential(profile.endpoint.credentialId, Credential(password = "theme-unsaved-secret"))
        }
        try {
            for (mode in listOf("dark", "light", "system")) {
                compose.runOnIdle { vm.theme(mode) }
                compose.waitUntil(10_000) {
                    vm.uiState.value.theme == mode && activity.delegate.localNightMode == when (mode) {
                        "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                }
                compose.runOnIdle {
                    assertSame(activity, compose.activity)
                    assertSame(editor, ViewModelProvider(compose.activity)[HostEditorViewModel::class.java])
                    assertEquals(HostEditorSection.AUTH, editor.section)
                    assertTrue(editor.atEntryPage)
                    assertEquals("theme-unsaved-secret", editor.credentials[profile.endpoint.credentialId]?.password)
                    val night = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    if (mode != "system") assertEquals(if (mode == "dark") android.content.res.Configuration.UI_MODE_NIGHT_YES
                        else android.content.res.Configuration.UI_MODE_NIGHT_NO, night)
                    val nativeAccent = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.colorAccent, nativeAccent, true)
                    assertEquals(activity.getColor(R.color.workbench_accent), nativeAccent.data)
                    assertEquals(night != android.content.res.Configuration.UI_MODE_NIGHT_YES,
                        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        } finally {
            compose.runOnIdle { editor.close(); vm.theme(original) }
            compose.waitUntil(10_000) { vm.uiState.value.theme == original }
        }
    }

    @Test fun systemAppearanceChangesInPlaceAndExplicitChoiceOverridesIt() {
        val activity = compose.activity
        val app = activity.application as RemoteApplication
        val vm = app.workbench()
        val manager = app.getSystemService(android.app.UiModeManager::class.java)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalNight = manager.nightMode
        val originalTheme = vm.state.value.theme
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        fun setAppTheme(mode: String) {
            compose.runOnIdle { vm.theme(mode) }
            compose.waitUntil(10_000) { vm.uiState.value.theme == mode }
            compose.waitForIdle()
        }
        fun awaitNight(expected: Int) {
            compose.waitUntil(10_000) {
                activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == expected
            }
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertEquals(expected != android.content.res.Configuration.UI_MODE_NIGHT_YES,
                    androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
        }
        try {
            setAppTheme("system")
            manager.setNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
            awaitNight(android.content.res.Configuration.UI_MODE_NIGHT_YES)
            manager.setNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
            awaitNight(android.content.res.Configuration.UI_MODE_NIGHT_NO)
            setAppTheme("dark")
            awaitNight(android.content.res.Configuration.UI_MODE_NIGHT_YES)
            manager.setNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
            manager.setNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
            compose.waitForIdle()
            awaitNight(android.content.res.Configuration.UI_MODE_NIGHT_YES)
        } finally {
            manager.setNightMode(originalNight)
            automation.dropShellPermissionIdentity()
            setAppTheme(originalTheme)
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
        assertFalse(restored.atEntryPage)
        assertTrue(restored.goBack())
        assertEquals(HostEditorSection.BASIC, restored.section)
        assertTrue(restored.atEntryPage)
        assertFalse(restored.goBack())
        editor.close()
        assertTrue(saved.keys().isEmpty())
    }

    @Test fun directJumpEntryAndNestedReturnPathSurviveSavedStateRestoration() {
        val saved = SavedStateHandle()
        val editor = HostEditorViewModel(saved)
        val hop = SshEndpoint(host = "jump.example", username = "dev")
        editor.open(HostProfile(jumps = listOf(hop)), HostEditorSection.JUMPS)
        editor.navigate(HostEditorSection.JUMP, hop.credentialId)
        editor.navigate(HostEditorSection.JUMP_AUTH)
        val restored = HostEditorViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(HostEditorSection.JUMP_AUTH, restored.section)
        assertEquals(hop.credentialId, restored.jumpId)
        assertTrue(restored.goBack())
        assertEquals(HostEditorSection.JUMP, restored.section)
        assertEquals(hop.credentialId, restored.jumpId)
        assertTrue(restored.goBack())
        assertEquals(HostEditorSection.JUMPS, restored.section)
        assertTrue(restored.atEntryPage)
        assertFalse(restored.goBack())
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
