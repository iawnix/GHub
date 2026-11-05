package dev.remotegpt.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real SSH/RPC against an isolated delayed local model fixture. */
@RunWith(AndroidJUnit4::class)
class InterruptedInputSyncDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun stopEditAndResendPreservesDraftAndHistoryAndChecksAttachments() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("statusFixturePort"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        val endpoint = SshEndpoint(args.getString("sshHost") ?: "10.0.2.2", args.getString("sshPort")!!.toInt(), args.getString("sshUser")!!)
        val profile = HostProfile(name = "Interrupted input fixture", endpoint = endpoint, codexPath = args.getString("codexPath")!!)
        val credential = Credential(privateKey = File(app.filesDir, "device-test-key").readText(), passphrase = "fixture-only-passphrase")
        val prompts = object : SshPrompts {
            override suspend fun credential(id: String) = credential
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == args.getString("fingerprint")
            override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected keyboard prompt")
        }
        val vm = withContext(Dispatchers.Main) { app.workbench() }
        app.settings.trust(endpoint.host, endpoint.port, args.getString("fingerprint")!!)
        withContext(Dispatchers.Main) { vm.saveProfile(profile, mapOf(endpoint.credentialId to credential)) }.join()
        try {
            compose.waitUntil(10_000) { profile.id in vm.state.value.workspaces }
            withContext(Dispatchers.Main) { vm.selectHost(profile.id); vm.connect(profile.id) }
            compose.waitUntil(60_000) { vm.state.value.workspace.connection in setOf(ConnectionStatus.ONLINE, ConnectionStatus.ERROR) }
            assertEquals(vm.state.value.workspace.error, ConnectionStatus.ONLINE, vm.state.value.workspace.connection)
            SshConnection.connect(profile, prompts).use { ssh ->
                RpcClient(ProxyTransport(ssh)).use { rpc ->
                    rpc.connect()
                    val provider = buildJsonObject {
                        put("name", "Edit input fixture"); put("base_url", "http://127.0.0.1:${args.getString("statusFixturePort")}/v1")
                        put("wire_api", "responses"); put("requires_openai_auth", false); put("supports_websockets", false)
                    }
                    val thread = rpc.request("thread/start", buildJsonObject {
                        put("cwd", args.getString("fixtureRoot")!!); put("modelProvider", "remote_gpt_status_fixture"); put("model", "gpt-5.4")
                        put("approvalPolicy", "never"); put("sandbox", "read-only")
                        putJsonObject("config") { put("model_providers.remote_gpt_status_fixture", provider) }
                    }).obj("thread").string("id")!!
                    try {
                        val path = args.getString("fixtureRoot") + "/revision-note.txt"
                        ssh.command("printf fixture > " + shellQuote(path))
                        withContext(Dispatchers.Main) { vm.openThread(thread) }
                        compose.waitUntil(20_000) { vm.state.value.workspace.selectedThread == thread && !vm.state.value.workspace.loadingHistory }
                        withContext(Dispatchers.Main) {
                            vm.draftText("Original input")
                            vm.attachRemote(RemoteFile("revision-note.txt", path, false, 7, 0))
                            vm.send()
                        }
                        compose.waitUntil(15_000) { vm.state.value.draft.lastSubmission?.turnId?.isNotBlank() == true && vm.state.value.conversation?.activeTurn != null }
                        val original = vm.state.value.draft.lastSubmission!!
                        assertEquals("Original input", original.input.text)
                        assertEquals(listOf(path), original.input.attachments.map { it.remotePath })
                        withContext(Dispatchers.Main) { vm.draftText("Unsent next task"); vm.interrupt(); vm.interrupt() }
                        compose.waitUntil(20_000) { editableInterruptedMessage(vm.state.value.workspace, thread) != null }
                        assertEquals("interrupted", vm.state.value.workspace.results[thread]?.status)
                        assertEquals(1L, vm.syncTimings.count("rpc.turn/interrupt.ok"))
                        val prompt = editableInterruptedMessage(vm.state.value.workspace, thread)!!
                        withContext(Dispatchers.Main) { vm.editInterruptedInput(prompt.id); vm.draftText("Edited input") }
                        val model = vm.state.value.workspace.modelOptions.first()
                        withContext(Dispatchers.Main) { vm.selectModel(model.id); vm.selectEffort(model.efforts.firstOrNull()) }
                        assertEquals(model.id, vm.state.value.draft.edit!!.input.model)
                        assertEquals(model.efforts.firstOrNull(), vm.state.value.draft.edit!!.input.effort)
                        assertEquals("Unsent next task", vm.state.value.draft.text)
                        assertNull(vm.state.value.draft.model)
                        assertNull(vm.state.value.draft.effort)
                        withContext(Dispatchers.Main) { vm.flush() }.join()
                        val persisted = wireJson.decodeFromString<Draft>(app.database.workbench().draft(profile.id, thread)!!.json)
                        assertEquals("Unsent next task", persisted.text)
                        assertEquals("Edited input", persisted.edit!!.input.text)
                        assertEquals(original, persisted.lastSubmission)
                        withContext(Dispatchers.Main) { vm.cancelInterruptedEdit(); vm.editInterruptedInput(prompt.id) }
                        assertEquals("Original input", vm.state.value.draft.edit!!.input.text)
                        assertEquals("Unsent next task", vm.state.value.draft.text)
                        withContext(Dispatchers.Main) { vm.draftText("Revised input") }
                        val sends = vm.syncTimings.count("rpc.turn/start.ok")
                        ssh.command("rm -- " + shellQuote(path))
                        withContext(Dispatchers.Main) { vm.resendInterruptedInput() }.join()
                        assertEquals(sends, vm.syncTimings.count("rpc.turn/start.ok"))
                        assertNotNull(vm.state.value.draft.edit!!.error)
                        assertEquals("draft", vm.state.value.draft.edit!!.delivery)
                        ssh.command("printf fixture > " + shellQuote(path))
                        withContext(Dispatchers.Main) { vm.resendInterruptedInput(); vm.resendInterruptedInput() }
                        compose.waitUntil(20_000) { vm.state.value.draft.edit == null && vm.state.value.draft.lastSubmission?.turnId != original.turnId }
                        assertEquals(sends + 1, vm.syncTimings.count("rpc.turn/start.ok"))
                        assertEquals("Unsent next task", vm.state.value.draft.text)
                        assertEquals("Revised input", vm.state.value.draft.lastSubmission!!.input.text)
                        withContext(Dispatchers.Main) { vm.interrupt() }
                        compose.waitUntil(20_000) { vm.state.value.conversation?.activeTurn == null && !vm.state.value.workspace.loadingHistory }
                        val history = Timeline.history(thread, CodexApi(rpc).history(thread))
                        val messages = history.items.filter { it.kind == "userMessage" }
                        assertEquals(2, messages.size)
                        assertTrue(messages[0].text.startsWith("Original input"))
                        assertTrue(messages[1].text.startsWith("Revised input"))
                        assertEquals(2, messages[1].text.split(path).size)
                        compose.waitUntil(20_000) { editableInterruptedMessage(vm.state.value.workspace, thread) != null }
                        val latestPrompt = editableInterruptedMessage(vm.state.value.workspace, thread)!!
                        withContext(Dispatchers.Main) { vm.editInterruptedInput(latestPrompt.id); vm.draftText("Keep this revision") }
                        val concurrent = rpc.request("turn/start", buildJsonObject {
                            put("threadId", thread)
                            putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", "Other client task") }) }
                        }).obj("turn").string("id")!!
                        compose.waitUntil(10_000) { vm.state.value.conversation?.activeTurn == concurrent }
                        withContext(Dispatchers.Main) { vm.resendInterruptedInput() }.join()
                        assertEquals(sends + 1, vm.syncTimings.count("rpc.turn/start.ok"))
                        assertEquals("Keep this revision", vm.state.value.draft.edit!!.input.text)
                        CodexApi(rpc).interrupt(thread, concurrent)
                        compose.waitUntil(20_000) { vm.state.value.workspace.results[thread]?.turnId == concurrent &&
                            vm.state.value.workspace.results[thread]?.status == "interrupted" && vm.state.value.conversation?.activeTurn == null }
                        withContext(Dispatchers.Main) { vm.resendInterruptedInput() }.join()
                        assertEquals(sends + 1, vm.syncTimings.count("rpc.turn/start.ok"))
                        withContext(Dispatchers.Main) { vm.cancelInterruptedEdit() }
                        assertEquals("Unsent next task", vm.state.value.draft.text)
                    } finally {
                        runCatching { CodexApi(rpc).latestTurnStatus(thread)?.takeIf { it.string("status") == "inProgress" }?.string("id")?.let { CodexApi(rpc).interrupt(thread, it) } }
                        runCatching { CodexApi(rpc).archive(thread, true) }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { vm.deleteProfile(profile) }.join()
        }
        Unit
    }
}
