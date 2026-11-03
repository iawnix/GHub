package dev.remotegpt.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import dev.remotegpt.app.data.decodeConversationCache
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Uses an isolated SSH host and a local delayed Responses fixture, never a paid model. */
@RunWith(AndroidJUnit4::class)
class SessionStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unopenedSessionUpdatesWhileArchivedAndInBackground() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("statusFixturePort"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        val endpoint = SshEndpoint("10.0.2.2", args.getString("sshPort")!!.toInt(), args.getString("sshUser")!!)
        val profile = HostProfile(name = "Status sync fixture", endpoint = endpoint, codexPath = args.getString("codexPath")!!)
        val credential = Credential(privateKey = File(app.filesDir, "device-test-key").readText(), passphrase = "fixture-only-passphrase")
        val prompts = object : SshPrompts {
            override suspend fun credential(id: String) = credential
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == args.getString("fingerprint")
            override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected keyboard prompt")
        }
        val vm = app.workbench()
        app.settings.trust(endpoint.host, endpoint.port, args.getString("fingerprint")!!)
        val save = withContext(Dispatchers.Main) { vm.saveProfile(profile, mapOf(endpoint.credentialId to credential)) }
        save.join()
        compose.waitUntil(10_000) { vm.state.value.profiles.any { it.id == profile.id } }
        withContext(Dispatchers.Main) { vm.connect(profile.id) }
        compose.waitUntil(60_000) { vm.state.value.workspace.connection in setOf(ConnectionStatus.ONLINE, ConnectionStatus.ERROR) }
        assertEquals(ConnectionStatus.ONLINE, vm.state.value.workspace.connection)
        withContext(Dispatchers.Main) { vm.showArchived(true) }
        compose.waitUntil(15_000) { !vm.state.value.workspace.loadingThreads }

        SshConnection.connect(profile, prompts).use { ssh ->
            RpcClient(ProxyTransport(ssh)).use { rpc ->
                rpc.connect()
                val provider = buildJsonObject {
                    put("name", "Status fixture"); put("base_url", "http://127.0.0.1:${args.getString("statusFixturePort")}/v1")
                    put("wire_api", "responses"); put("requires_openai_auth", false); put("supports_websockets", false)
                }
                val thread = rpc.request("thread/start", buildJsonObject {
                    put("cwd", args.getString("fixtureRoot")!!); put("modelProvider", "remote_gpt_status_fixture"); put("model", "gpt-5.4")
                    put("approvalPolicy", "never"); put("sandbox", "read-only")
                    putJsonObject("config") { put("model_providers.remote_gpt_status_fixture", provider) }
                }).obj("thread").string("id")!!
                var turn: String? = null
                try {
                    val response = rpc.request("turn/start", buildJsonObject {
                        put("threadId", thread)
                        putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", "Return the local status fixture response.") }) }
                    })
                    turn = response.obj("turn").string("id")
                    try {
                        compose.waitUntil(15_000) { vm.state.value.workspace.results[thread]?.status == "inProgress" }
                    } catch (error: Throwable) {
                        val file = File(app.cacheDir, "status-test-diagnostics.txt")
                        withContext(Dispatchers.Main) { vm.exportDiagnostics(android.net.Uri.fromFile(file)) }.join()
                        println(file.readText())
                        println("Fixture observation: connection=${vm.state.value.workspace.connection}, receipt=${vm.state.value.workspace.results[thread]?.status}, remote=${CodexApi(rpc).latestTurnStatus(thread)?.string("status")}")
                        throw error
                    }
                    assertNull(vm.state.value.workspace.selectedThread)
                    assertTrue(vm.state.value.workspace.archived)
                    assertFalse(vm.state.value.workspace.threads.any { it.id == thread })
                    assertTrue(vm.state.value.workspace.conversations[thread]?.items.isNullOrEmpty())
                    withContext(Dispatchers.Main) { vm.setBackgroundKeepAlive(true); vm.onBackground() }
                    compose.waitUntil(45_000) { vm.state.value.workspace.results[thread]?.status == "completed" }
                    val result = vm.state.value.workspace.results.getValue(thread)
                    assertEquals(turn, result.completedTurnId)
                    assertTrue(result.unread)
                    assertNull(vm.state.value.workspace.selectedThread)
                    assertTrue(vm.state.value.workspace.archived)
                    assertFalse(vm.state.value.workspace.threads.any { it.id == thread })
                    assertTrue(vm.state.value.workspace.conversations[thread]?.items.isNullOrEmpty())
                    withContext(Dispatchers.Main) { vm.onForeground(); vm.setBackgroundKeepAlive(false); vm.showArchived(false) }
                    compose.waitUntil(15_000) { vm.state.value.workspace.threads.any { it.id == thread } }
                    assertEquals("completed", vm.state.value.workspace.results[thread]?.status)
                    assertTrue(vm.state.value.workspace.results.getValue(thread).unread)

                    // First open attaches once. Navigation thereafter reuses the same live subscription.
                    withContext(Dispatchers.Main) { vm.openThread(thread) }
                    compose.waitUntil(20_000) { vm.state.value.workspace.selectedThread == thread &&
                        !vm.state.value.workspace.loadingHistory && vm.state.value.workspace.conversations[thread]?.items?.isNotEmpty() == true }
                    val resumed = vm.syncTimings.count("rpc.thread/resume.ok")
                    val histories = vm.syncTimings.count("history_latest")
                    repeat(3) {
                        val opened = vm.syncTimings.count("open_ready")
                        withContext(Dispatchers.Main) { vm.backToThreads(); vm.openThread(thread) }
                        compose.waitUntil(10_000) { vm.syncTimings.count("open_ready") > opened }
                    }
                    assertEquals("Navigation must not resubscribe", resumed, vm.syncTimings.count("rpc.thread/resume.ok"))
                    assertEquals("Navigation must not reload 20 turns", histories, vm.syncTimings.count("history_latest"))

                    // An explicit sync still repairs history without reattaching an existing subscription.
                    withContext(Dispatchers.Main) { vm.refreshConversation() }
                    compose.waitUntil(15_000) { vm.syncTimings.count("history_latest") > histories && !vm.state.value.workspace.loadingHistory }
                    assertEquals(resumed, vm.syncTimings.count("rpc.thread/resume.ok"))

                    // Another client starts a turn while the phone is on the project list.
                    withContext(Dispatchers.Main) { vm.backToThreads() }
                    val start = android.os.SystemClock.elapsedRealtime()
                    val second = rpc.request("turn/start", buildJsonObject {
                        put("threadId", thread)
                        putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", "Second isolated fixture response.") }) }
                    })
                    turn = second.obj("turn").string("id")
                    compose.waitUntil(10_000) { vm.state.value.workspace.results[thread]?.turnId == turn &&
                        vm.state.value.workspace.results[thread]?.status == "inProgress" }
                    println("Subscribed cross-client start observed after ${android.os.SystemClock.elapsedRealtime() - start} ms")
                    compose.waitUntil(45_000) { vm.state.value.workspace.results[thread]?.status == "completed" }
                    withContext(Dispatchers.Main) { vm.openThread(thread) }
                    compose.waitUntil(15_000) { !vm.state.value.workspace.loadingHistory &&
                        vm.state.value.workspace.conversations[thread]?.items?.any { it.turnId == turn && it.kind == "agentMessage" && it.complete } == true }

                    // A new SSH/RPC connection must subscribe again and reconcile the original thread.
                    val restores = vm.syncTimings.count("connection_restore")
                    val subscriptionsBeforeReconnect = vm.syncTimings.count("rpc.thread/resume.ok")
                    withContext(Dispatchers.Main) { vm.disconnect(profile.id); vm.connect(profile.id) }
                    compose.waitUntil(60_000) { vm.state.value.workspace.connection == ConnectionStatus.ONLINE &&
                        vm.syncTimings.count("connection_restore") > restores }
                    assertTrue(vm.syncTimings.count("rpc.thread/resume.ok") > subscriptionsBeforeReconnect)
                    val items = vm.state.value.workspace.conversations.getValue(thread).items
                    assertEquals(items.size, items.map { it.identityKey }.distinct().size)
                    assertEquals(2, items.count { it.kind == "userMessage" })
                    assertEquals(2, items.count { it.kind == "agentMessage" })

                    // Evict a subscribed body, then receive a new turn. Its deltas
                    // must hydrate the two durable turns before any checkpoint.
                    withContext(Dispatchers.Main) { vm.backToThreads(); vm.trimMemory() }
                    compose.waitUntil(10_000) { vm.state.value.workspace.conversations[thread] == null }
                    val streaming = rpc.request("turn/start", buildJsonObject {
                        put("threadId", thread)
                        putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", "Stream sync fixture") }) }
                    }).obj("turn").string("id")!!
                    turn = streaming
                    fun partial() = vm.state.value.workspace.conversations[thread]?.items
                        ?.firstOrNull { it.turnId == streaming && it.kind == "agentMessage" }?.text
                    compose.waitUntil(10_000) { (partial()?.length ?: 0) >= 18 }
                    assertEquals(2, vm.state.value.workspace.conversations.getValue(thread).items.count { it.kind == "agentMessage" && it.complete })
                    withContext(Dispatchers.Main) { vm.openThread(thread) }
                    compose.waitUntil(10_000) { !vm.state.value.workspace.loadingHistory }
                    val prefixLength = partial()!!.length
                    val historyReads = vm.syncTimings.count("history_latest")
                    withContext(Dispatchers.Main) { vm.refreshConversation() }
                    compose.waitUntil(10_000) { vm.syncTimings.count("history_latest") > historyReads && !vm.state.value.workspace.loadingHistory }
                    assertTrue("Active history must keep the stream prefix", partial()!!.startsWith("SYNC_000\n"))
                    assertTrue(partial()!!.length >= prefixLength)

                    // No 350ms quiet period exists during this stream. Its body
                    // must nevertheless reach Room before the turn completes.
                    withTimeout(8_000) {
                        while (true) {
                            val saved = decodeConversationCache(app.database.workbench().conversationChunks(profile.id, thread).map { it.json })
                            val checkpoint = saved?.items?.firstOrNull { it.turnId == streaming && it.kind == "agentMessage" }
                            if (checkpoint != null && checkpoint.text.length >= prefixLength) {
                                assertTrue(checkpoint.text.startsWith("SYNC_000\n"))
                                assertFalse(checkpoint.complete)
                                break
                            }
                            delay(100)
                        }
                    }
                    withContext(Dispatchers.Main) { vm.setBackgroundKeepAlive(true); vm.onBackground() }
                    delay(500)
                    withContext(Dispatchers.Main) { vm.onForeground(); vm.setBackgroundKeepAlive(false) }
                    compose.waitUntil(30_000) { vm.state.value.workspace.results[thread]?.status == "completed" }
                    val expected = (0 until 80).joinToString("") { "SYNC_${it.toString().padStart(3, '0')}\n" }
                    assertEquals(expected, partial())
                    withContext(Dispatchers.Main) { vm.flush() }.join()
                    val cached = decodeConversationCache(app.database.workbench().conversationChunks(profile.id, thread).map { it.json })!!
                    assertEquals(expected, cached.items.first { it.turnId == streaming && it.kind == "agentMessage" }.text)
                    assertEquals(3, cached.items.count { it.kind == "agentMessage" })

                    // Clearing local history must remove pending checkpoints too;
                    // reconnect then reconstructs the same complete conversation.
                    withContext(Dispatchers.Main) { vm.disconnect(profile.id) }
                    withContext(Dispatchers.Main) { vm.clearCache() }.join()
                    assertNull(vm.state.value.workspace.conversations[thread])
                    assertTrue(app.database.workbench().conversationChunks(profile.id, thread).isEmpty())
                    val restoredAfterClear = vm.syncTimings.count("connection_restore")
                    withContext(Dispatchers.Main) { vm.connect(profile.id) }
                    compose.waitUntil(60_000) { vm.state.value.workspace.connection == ConnectionStatus.ONLINE &&
                        vm.syncTimings.count("connection_restore") > restoredAfterClear }
                    val restoredItems = vm.state.value.workspace.conversations.getValue(thread).items
                    assertEquals(3, restoredItems.count { it.kind == "agentMessage" })
                    assertEquals(expected, restoredItems.first { it.turnId == streaming && it.kind == "agentMessage" }.text)
                    println("Sync timing aggregates:\n${vm.syncTimings.report()}")
                } finally {
                    turn?.let { runCatching { CodexApi(rpc).interrupt(thread, it) } }
                    runCatching { CodexApi(rpc).archive(thread, true) }
                    withContext(Dispatchers.Main) { vm.onForeground(); vm.setBackgroundKeepAlive(false); vm.disconnect(profile.id) }
                }
            }
        }
        Unit
    }
}
