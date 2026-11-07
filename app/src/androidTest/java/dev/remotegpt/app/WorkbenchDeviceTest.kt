package dev.remotegpt.app

import android.graphics.Bitmap
import org.junit.runners.MethodSorters
import java.io.FileOutputStream
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class WorkbenchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun emptyWorkspaceOffersHostSetupAndAccessibleEditor() {
        compose.onNodeWithText(tr(R.string.ui_open_settings)).assertIsDisplayed().performClick()
        compose.onAllNodesWithText(tr(R.string.ui_servers)).onLast().assertIsDisplayed().performClick()
        compose.onNodeWithText(tr(R.string.ui_add_server)).assertIsDisplayed().performClick()
        compose.onNodeWithText(tr(R.string.ui_connection_name)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.ui_host_address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.ui_username)).performScrollTo().assertIsDisplayed()
        compose.waitUntil { compose.onAllNodes(hasText(tr(R.string.ui_save_connection)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(tr(R.string.ui_back_to_servers)).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
        compose.onNodeWithText(tr(R.string.ui_connect_your_development_machine)).assertIsDisplayed()
    }

    @Test fun serverSettingsPreserveTheActiveWorkspaceAcrossEditAndDelete() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        lateinit var vm: WorkbenchViewModel
        compose.runOnIdle { vm = app.workbench() }
        val primary = HostProfile(name = "Primary server", endpoint = SshEndpoint(host = "primary.invalid", username = "dev", auth = AuthKind.PASSWORD))
        val secondary = HostProfile(name = "Secondary server", endpoint = SshEndpoint(host = "secondary.invalid", username = "dev", auth = AuthKind.PASSWORD))
        suspend fun save(profile: HostProfile) {
            lateinit var job: Job
            compose.runOnIdle { job = vm.saveProfile(profile, emptyMap()) }
            job.join()
            compose.waitUntil { vm.state.value.profiles.any { it.id == profile.id } && vm.state.value.workspaces.containsKey(profile.id) }
        }
        try {
            save(primary)
            compose.waitUntil { vm.state.value.activeHost == primary.id }
            compose.runOnIdle { vm.openProject("/workspace/primary") }
            save(secondary)
            assertEquals(primary.id, vm.state.value.activeHost)
            compose.onNodeWithContentDescription(tr(R.string.ui_settings)).performClick()
            compose.onNodeWithText(tr(R.string.ui_servers)).performClick()
            compose.onNodeWithText(secondary.name).performClick()
            assertEquals(primary.id, vm.state.value.activeHost)
            assertEquals("/workspace/primary", vm.state.value.workspace.selectedProject)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(tr(R.string.ui_authentication)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(tr(R.string.ui_back_to_server_settings)).performClick()
            compose.onNodeWithText(tr(R.string.ui_edit_connection)).assertExists()
            compose.onNodeWithText(tr(R.string.ui_remote_codex)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(tr(R.string.ui_back_to_server_settings)).performClick()
            compose.onNodeWithText(tr(R.string.ui_edit_connection)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(tr(R.string.ui_back_to_server_settings)).assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.ui_connection_name)).performTextReplacement("Renamed secondary")
            compose.waitUntil { compose.onAllNodes(hasText(tr(R.string.ui_save_connection)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(tr(R.string.ui_save_connection)).performClick()
            compose.waitUntil { vm.state.value.profiles.any { it.id == secondary.id && it.name == "Renamed secondary" } }
            compose.onNodeWithText(tr(R.string.ui_edit_connection)).performScrollTo().assertIsDisplayed()
            assertEquals(primary.id, vm.state.value.activeHost)
            assertEquals("/workspace/primary", vm.state.value.workspace.selectedProject)

            // The save snackbar can cover the last row while this test immediately
            // opens the editor again. Clear it before testing the delete action.
            compose.runOnIdle { vm.message(null) }
            compose.onNodeWithText(tr(R.string.ui_edit_connection)).performClick()
            compose.onNodeWithText(tr(R.string.ui_delete_server_connection)).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText(tr(R.string.ui_delete)).performClick()
            compose.waitUntil { vm.state.value.profiles.none { it.id == secondary.id } }
            compose.onNodeWithText(tr(R.string.ui_add_server)).assertIsDisplayed()
            compose.onNodeWithText(primary.name).assertIsDisplayed()
            compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
            compose.onNodeWithText(tr(R.string.ui_servers)).assertIsDisplayed()
            compose.onNodeWithContentDescription(tr(R.string.ui_back)).performClick()
            assertEquals(primary.id, vm.state.value.activeHost)
            assertEquals("/workspace/primary", vm.state.value.workspace.selectedProject)
            assertEquals(ConnectionStatus.OFFLINE, vm.state.value.workspace.connection)
        } catch (error: Throwable) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            FileOutputStream(File(app.filesDir, "device-hostsettings.png")).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            throw error
        } finally {
            for (profile in listOf(secondary, primary)) {
                lateinit var job: Job
                compose.runOnIdle { job = vm.deleteProfile(profile) }
                job.join()
            }
        }
        Unit
    }

    @Test fun androidSshAndProxyReadExistingDaemon() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Pass fixture arguments to run real SSH device test", args.containsKey("sshPort"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = File(context.filesDir, "device-test-key").readText()
        val endpoint = SshEndpoint(args.getString("sshHost", "10.0.2.2"), args.getString("sshPort")!!.toInt(), args.getString("sshUser")!!)
        val jumps = args.getString("jumpPorts")?.split(',')?.mapIndexed { i, port -> SshEndpoint(if (i == 0) args.getString("sshHost", "10.0.2.2") else "127.0.0.1", port.toInt(), endpoint.username) }.orEmpty()
        val profile = HostProfile(name = "device fixture", endpoint = if (jumps.isEmpty()) endpoint else endpoint.copy(host = "127.0.0.1"), jumps = jumps, codexPath = args.getString("codexPath")!!)
        val prompts = object : SshPrompts {
            override suspend fun credential(id: String) = Credential(privateKey = key, passphrase = args.getString("keyPassphrase").orEmpty())
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == args.getString("fingerprint")
            override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected keyboard prompt")
        }
        SshConnection.connect(profile, prompts).use { ssh ->
            assertTrue(Regex("\\\"appServerVersion\\\"\\s*:\\s*\\\"0\\.\\d+\\.\\d+\\\"").containsMatchIn(ssh.version()))
            RpcClient(ProxyTransport(ssh)).use { rpc ->
                assertNotNull(rpc.connect().string("userAgent"))
                assertNotNull(rpc.request("thread/loaded/list")["data"])
            }
            val files = RemoteFiles(ssh)
            assertTrue(files.home().startsWith('/'))
        }
        Unit
    }
    @Test fun workbenchResumesPendingApprovalAndRunsRemoteTerminal() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Requires a disposable daemon fixture", args.containsKey("threadId"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        val endpoint = SshEndpoint(args.getString("sshHost", "10.0.2.2"), args.getString("sshPort")!!.toInt(), args.getString("sshUser")!!)
        val profile = HostProfile(name = "验证主机", endpoint = endpoint, codexPath = args.getString("codexPath")!!)
        val thread = args.getString("threadId")!!
        lateinit var vm: WorkbenchViewModel
        compose.runOnIdle { vm = app.workbench() }
        app.settings.trust(endpoint.host, endpoint.port, args.getString("fingerprint")!!)
        val key = File(app.filesDir, "device-test-key").readText()
        lateinit var save: kotlinx.coroutines.Job
        compose.runOnIdle { save = vm.saveProfile(profile, mapOf(endpoint.credentialId to Credential(privateKey = key, passphrase = args.getString("keyPassphrase").orEmpty()))) }
        save.join()
        compose.waitUntil(10_000) { vm.state.value.profiles.any { it.id == profile.id } }
        val notices = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val noticesJob = launch(Dispatchers.Default) { vm.state.map { it.message }.distinctUntilChanged().collect { if (it != null) notices.add(it) } }
        try {
            android.util.Log.i("WorkbenchCheck", "connecting")
            compose.runOnIdle { vm.connect(profile.id) }
            compose.waitUntil(60_000) { vm.state.value.workspace.connection in setOf(ConnectionStatus.ONLINE, ConnectionStatus.ERROR) }
            assertEquals(vm.state.value.workspace.error, ConnectionStatus.ONLINE, vm.state.value.workspace.connection)
            android.util.Log.i("WorkbenchCheck", "connected; opening fixture")
            compose.runOnIdle { vm.openThread(thread) }
            compose.waitUntil(30_000) { vm.state.value.workspace.approvals.any { it.request.threadId == thread } }
            android.util.Log.i("WorkbenchCheck", "approval received")
            val first = vm.state.value.workspace.approvals.single { it.request.threadId == thread }.request
            // Reconnect while no conversation is selected: pending requests still need restoring.
            compose.runOnIdle { vm.backToThreads(); vm.disconnect(profile.id) }
            assertTrue(thread in vm.state.value.workspace.attentionSyncingThreads)
            compose.waitUntil { vm.state.value.workspace.approvals.isEmpty() }
            compose.runOnIdle { vm.connect(profile.id) }
            compose.waitUntil(60_000) { vm.state.value.workspace.approvals.any { it.request.threadId == thread } }
            android.util.Log.i("WorkbenchCheck", "approval replayed")
            val replay = vm.state.value.workspace.approvals.single { it.request.threadId == thread }.request
            assertEquals(first.id, replay.id)
            assertNotEquals(first.epoch, replay.epoch)
            compose.waitUntil(30_000) { thread !in vm.state.value.workspace.attentionSyncingThreads }
            assertNull(vm.state.value.workspace.selectedThread)
            compose.runOnIdle { vm.openFromNotification(profile.id, thread) }
            compose.waitUntil(10_000) { vm.state.value.workspace.selectedThread == thread }
            android.util.Log.i("WorkbenchCheck", "approving via UI")
            compose.onNodeWithText(tr(R.string.ui_allow_once)).performScrollTo().performClick()
            compose.waitUntil(60_000) { vm.state.value.conversation?.items?.any { it.text.contains("Remote GPT approval probe completed.") } == true }
            compose.waitUntil { vm.state.value.workspace.approvals.isEmpty() }
            android.util.Log.i("WorkbenchCheck", "turn completed")
            compose.runOnIdle { vm.draftText("保留的中文草稿"); vm.selectTab(WorkspaceTab.FILES); vm.listFiles(args.getString("fixtureRoot")) }
            compose.waitUntil(20_000) { vm.state.value.workspace.files.any { it.name == "device-note.txt" } }
            compose.runOnIdle { vm.editFile(args.getString("fixtureRoot") + "/device-note.txt") }
            compose.waitUntil(20_000) { vm.state.value.workspace.editor != null }
            compose.runOnIdle { vm.editorText("手机编辑成功"); vm.saveFile() }
            compose.waitUntil(20_000) { vm.state.value.workspace.editor?.text == "手机编辑成功" }
            compose.runOnIdle { vm.closeEditor(); vm.selectTab(WorkspaceTab.TERMINAL) }
            compose.waitUntil(20_000) { vm.state.value.workspace.terminals.isNotEmpty() }
            assertEquals(1, vm.state.value.workspace.terminals.size)
            compose.onNodeWithText(tr(R.string.ui_workspace)).assertDoesNotExist()
            android.util.Log.i("WorkbenchCheck", "terminal opened")
            val terminal = vm.state.value.workspace.terminals.last().session
            compose.runOnIdle { vm.selectTab(WorkspaceTab.SESSIONS) }
            compose.waitForIdle()
            compose.runOnIdle { vm.selectTab(WorkspaceTab.TERMINAL) }
            compose.waitForIdle()
            assertSame("Returning to the terminal must reuse its session", terminal, vm.state.value.workspace.terminals.single().session)
            compose.waitUntil(10_000) { terminal.getEmulator() != null }
            // Isolate interactive tests from the developer's login shell customizations.
            compose.runOnIdle { terminal.write("exec env -u PROMPT_COMMAND PS1='FIXTURE> ' /bin/bash --noprofile --norc -i\r") }
            compose.waitUntil(10_000) { terminal.getEmulator()?.screen?.transcriptText?.contains("\nFIXTURE>") == true }
            compose.runOnIdle { terminal.write("printf '手机%s OK\\n' '终端'\r") }
            compose.waitUntil(15_000) { terminal.getEmulator()?.screen?.transcriptText?.contains("手机终端 OK") == true }
            compose.runOnIdle { save = vm.saveProfile(profile.copy(name = "Renamed fixture"),
                mapOf(endpoint.credentialId to Credential(privateKey = key, passphrase = args.getString("keyPassphrase").orEmpty()))) }
            save.join()
            compose.runOnIdle {
                assertEquals(ConnectionStatus.ONLINE, vm.state.value.workspace.connection)
                assertEquals(WorkspaceTab.TERMINAL, vm.state.value.tab)
                assertSame(terminal, vm.state.value.workspace.terminals.single().session)
                assertTrue(terminal.isRunning())
            }
            compose.runOnIdle { terminal.write("vim -Nu NONE -i NONE " + shellQuote(args.getString("fixtureRoot") + "/device-note.txt") + "; printf 'VIM_%s_READY\\n' EXITED\r") }
            compose.waitUntil(20_000) { terminal.getEmulator()?.isAlternateBufferActive == true }
            compose.onNodeWithContentDescription(tr(R.string.terminal_down)).performClick()
            compose.onNodeWithContentDescription(tr(R.string.terminal_escape)).performClick()
            compose.runOnIdle { terminal.write(":q!\r") }
            compose.waitUntil(15_000) { terminal.getEmulator()?.isAlternateBufferActive == false &&
                terminal.getEmulator()?.screen?.transcriptText?.contains("VIM_EXITED_READY") == true }
            assertTrue("Terminal must remain writable after Vim exits", terminal.isRunning())
            // Vim is gone, but late terminal-query replies may leave readline editing state.
            // Reset that state and wait for a fresh prompt before typing the next command.
            val promptsBefore = terminal.getEmulator()!!.screen.transcriptText.split("FIXTURE>").size
            compose.runOnIdle { terminal.write("\u0003") }
            compose.waitUntil(10_000) { terminal.getEmulator()!!.screen.transcriptText.split("FIXTURE>").size > promptsBefore }
            val socketName = "remote-gpt-device-" + args.getString("sshPort")
            compose.runOnIdle { terminal.write("tmux -L $socketName -f /dev/null new-session -s probe\r") }
            compose.waitUntil(20_000) { terminal.getEmulator()?.isAlternateBufferActive == true }
            compose.runOnIdle { terminal.write("printf 'TMUX_%s_OK\\n' DEVICE\r") }
            compose.waitUntil(15_000) { terminal.getEmulator()?.screen?.transcriptText?.contains("TMUX_DEVICE_OK") == true }
            compose.onNodeWithContentDescription("Ctrl").performClick()
            // Use the real IME path for the prefix; the next letter must be plain d.
            compose.runOnIdle {
                fun terminalView(view: android.view.View): com.termux.view.TerminalView? {
                    if (view is com.termux.view.TerminalView) return view
                    if (view is android.view.ViewGroup) repeat(view.childCount) { terminalView(view.getChildAt(it))?.let { return it } }
                    return null
                }
                terminalView(compose.activity.window.decorView)!!.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("bd", 1)
            }
            compose.waitUntil(15_000) { terminal.getEmulator()?.isAlternateBufferActive == false }
            compose.runOnIdle { vm.closeTerminal(vm.state.value.workspace.terminals.last().id) }
            compose.waitForIdle()
            assertTrue("Closing the last terminal must leave it closed", vm.state.value.workspace.terminals.isEmpty())
            assertTrue(vm.state.value.workspace.openingTerminalProjects.isEmpty())
            compose.runOnIdle {
                val opening = vm.newTerminal()
                assertNotNull(opening)
                assertSame("Repeated taps must share the pending terminal", opening, vm.newTerminal())
            }
            compose.waitUntil(20_000) { vm.state.value.workspace.terminals.isNotEmpty() }
            assertEquals(1, vm.state.value.workspace.terminals.size)
            val restoredTerminal = vm.state.value.workspace.terminals.last().session
            compose.runOnIdle { restoredTerminal.write("tmux -L $socketName attach-session -t probe\r") }
            compose.waitUntil(20_000) { restoredTerminal.getEmulator()?.screen?.transcriptText?.contains("TMUX_DEVICE_OK") == true }
            compose.runOnIdle { restoredTerminal.write("\u0002d") }
            compose.waitUntil(15_000) { restoredTerminal.getEmulator()?.isAlternateBufferActive == false }
            compose.runOnIdle { restoredTerminal.write("tmux -L $socketName kill-server\r") }
            android.util.Log.i("WorkbenchCheck", "vim and tmux reconnect passed")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(20_000) { vm.state.value.workspace.terminals.isNotEmpty() }
            compose.runOnIdle {
                assertSame(vm, app.workbench())
                assertSame(restoredTerminal, vm.state.value.workspace.terminals.single().session)
            }
            compose.runOnIdle { vm.selectTab(WorkspaceTab.SESSIONS) }
            assertEquals("保留的中文草稿", vm.state.value.draft.text)
            val image = File(app.cacheDir, "shared/device-image.png").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", image)
            compose.runOnIdle { vm.upload(uri, true) }
            compose.waitUntil(20_000) { vm.state.value.draft.attachments.singleOrNull()?.state == "ready" }
            compose.runOnIdle { vm.message(null) }
            compose.waitForIdle()
            android.util.Log.i("WorkbenchCheck", "sending image; connection=${vm.state.value.workspace.connection}; draft=${vm.state.value.draft.delivery}; archived=${vm.state.value.thread?.archived}")
            compose.onNodeWithContentDescription(tr(R.string.ui_send_message)).assertIsEnabled().performClick()
            compose.waitUntil(30_000) { vm.state.value.draft.text.isEmpty() && vm.state.value.draft.attachments.isEmpty() }
            compose.waitUntil(30_000) { vm.state.value.conversation?.items?.any { it.kind == "userMessage" && it.imagePaths.isNotEmpty() } == true }
            compose.runOnIdle { vm.message(null); vm.draftText("可继续编辑的草稿") }
            compose.waitForIdle()
            android.util.Log.i("WorkbenchCheck", "image sent; activity recreation passed")
            compose.runOnIdle { vm.setBackgroundKeepAlive(false); vm.onBackground() }
            compose.waitUntil(40_000) { vm.state.value.workspace.connection == ConnectionStatus.OFFLINE }
            assertTrue(vm.state.value.workspace.terminals.isEmpty())
            assertFalse(restoredTerminal.isRunning())
            assertEquals("可继续编辑的草稿", vm.state.value.draft.text)
            compose.runOnIdle { vm.selectTab(WorkspaceTab.TERMINAL) }
            compose.waitForIdle()
            assertTrue(vm.state.value.workspace.terminals.isEmpty())
            compose.runOnIdle { vm.onForeground() }
            compose.waitUntil(60_000) { vm.state.value.workspace.connection == ConnectionStatus.ONLINE }
            compose.waitUntil(20_000) { vm.state.value.workspace.terminals.isNotEmpty() }
            assertEquals(1, vm.state.value.workspace.terminals.size)
            compose.runOnIdle { vm.selectTab(WorkspaceTab.SESSIONS) }
            assertEquals("可继续编辑的草稿", vm.state.value.draft.text)
            android.util.Log.i("WorkbenchCheck", "background release and foreground reconnect passed")
            compose.runOnIdle { save = vm.saveProfile(profile.copy(socketPath = "/tmp/changed-fixture.sock"), emptyMap()) }
            save.join()
            compose.runOnIdle {
                assertEquals(ConnectionStatus.OFFLINE, vm.state.value.workspace.connection)
                assertTrue(vm.state.value.workspace.terminals.isEmpty())
                assertEquals("可继续编辑的草稿", vm.state.value.draft.text)
                assertEquals(tr(R.string.ui_connection_saved_reconnect), vm.state.value.message)
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            FileOutputStream(File(app.filesDir, "device-workbench.png")).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } catch (error: Throwable) {
            android.util.Log.e("WorkbenchCheck", "device workflow failed; connection=${vm.state.value.workspace.connection}; delivery=${vm.state.value.draft.delivery}; message=${vm.state.value.message}; notices=$notices", error)
            val failure = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            FileOutputStream(File(app.filesDir, "device-workbench.png")).use { failure.compress(Bitmap.CompressFormat.PNG, 100, it) }; failure.recycle()
            throw error
        } finally {
            noticesJob.cancel()
            lateinit var remove: kotlinx.coroutines.Job
            compose.runOnIdle { remove = vm.deleteProfile(profile) }
            remove.join()
        }
        Unit
    }

}
