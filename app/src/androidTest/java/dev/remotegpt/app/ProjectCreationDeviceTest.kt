package dev.remotegpt.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectCreationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addProjectCreatesRemoteDirectoryAndFailureKeepsFormWithoutSavingPhantomProject() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("fixtureRoot"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        val vm = app.workbench()
        val endpoint = SshEndpoint(args.getString("sshHost", "10.0.2.2"), args.getString("sshPort")!!.toInt(), args.getString("sshUser")!!)
        val profile = HostProfile(name = "Project creation fixture", endpoint = endpoint, codexPath = args.getString("codexPath")!!)
        val credential = Credential(privateKey = File(app.filesDir, "device-test-key").readText(), passphrase = args.getString("keyPassphrase").orEmpty())
        val prompts = object : SshPrompts {
            override suspend fun credential(id: String) = credential
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == args.getString("fingerprint")
            override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected prompt")
        }
        app.settings.trust(endpoint.host, endpoint.port, args.getString("fingerprint")!!)
        withContext(Dispatchers.Main) { vm.saveProfile(profile, mapOf(endpoint.credentialId to credential)) }.join()
        compose.waitUntil(10_000) { profile.id in vm.state.value.workspaces }
        withContext(Dispatchers.Main) { vm.selectHost(profile.id); vm.backToProjects() }
        try {
            compose.onNodeWithContentDescription(tr(R.string.ui_add_project)).performClick()
            val occupied = args.getString("fixtureRoot")!! + "/device-note.txt"
            compose.onNodeWithText(tr(R.string.ui_project_name_optional)).performTextReplacement("New workspace")
            compose.onNodeWithText(tr(R.string.ui_remote_working_directory)).performTextReplacement(occupied)
            compose.onAllNodes(hasText(tr(R.string.ui_add_project)) and hasClickAction()).onLast().performScrollTo().performClick()
            compose.waitUntil(60_000) { vm.state.value.workspace.creatingProjectPath == null && vm.state.value.workspace.projectCreationError != null }
            assertFalse(vm.state.value.workspace.projects.any { it.path == occupied })
            assertFalse(app.settings.projects(profile.id).any { it.path == occupied })
            compose.onNodeWithText(tr(R.string.ui_remote_working_directory)).assertTextContains(occupied)
            compose.onNodeWithText(vm.state.value.workspace.projectCreationError!!).assertExists()

            val root = args.getString("fixtureRoot")!! + "/new ' 中文 \$(literal)/nested"
            compose.onNodeWithText(tr(R.string.ui_remote_working_directory)).performTextReplacement(root)
            compose.onAllNodes(hasText(tr(R.string.ui_add_project)) and hasClickAction()).onLast().performScrollTo().performClick()
            compose.waitUntil(20_000) { vm.state.value.workspace.projects.any { it.path == root } && vm.state.value.workspace.creatingProjectPath == null }
            assertEquals(root, vm.state.value.workspace.selectedProject)
            assertTrue(app.settings.projects(profile.id).any { it.path == root })
            compose.onNodeWithText(tr(R.string.ui_remote_working_directory)).assertDoesNotExist()
            SshConnection.connect(profile, prompts).use { ssh ->
                val files = RemoteFiles(ssh)
                assertTrue(files.list(root).isEmpty())
                assertEquals("device edit fixture", files.readText(occupied).text)
                val sentinel = files.upload(root, "keep.txt", "keep".byteInputStream())
                withContext(Dispatchers.Main) { vm.createProject("Existing workspace", root) }.join()
                assertEquals("keep", files.readText(sentinel).text)
                assertEquals(1, vm.state.value.workspace.projects.count { it.path == root })
            }
            // Renaming a saved workspace remains available without a connection.
            withContext(Dispatchers.Main) { vm.disconnect(profile.id); vm.saveProject("Renamed", root) }
            assertEquals("Renamed", vm.state.value.workspace.projects.first { it.path == root }.name)
        } finally { withContext(Dispatchers.Main) { vm.deleteProfile(profile) }.join() }
    }
}
