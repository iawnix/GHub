package dev.remotegpt.client

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

private class FixtureTransport(override var isOpen: Boolean = true) : MessageTransport {
    val sent = mutableListOf<JsonObject>()
    lateinit var incoming: (String) -> Unit
    lateinit var disconnected: (Throwable?) -> Unit
    override suspend fun connect(onMessage: (String) -> Unit, onClosed: (Throwable?) -> Unit) { incoming = onMessage; disconnected = onClosed }
    override fun send(text: String) {
        check(isOpen) { "Transport is closed" }
        val obj = wireJson.parseToJsonElement(text).jsonObject
        sent += obj
        if (obj.string("method") == "initialize") incoming(jsonObject("id" to obj["id"], "result" to buildJsonObject { put("userAgent", "codex/0.165.0") }).toString())
    }
    fun respond(msg: JsonObject, result: JsonObject) = incoming(jsonObject("id" to msg["id"], "result" to result).toString())
    override fun close() = Unit
}

class ProtocolTest {
    @Test fun `plan and execute turns carry real mode settings while ordinary turns inherit`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect(); val api = CodexApi(rpc)
        for (mode in listOf(null, "plan", "default")) {
            val response = async { api.send("thread", "hello", emptyList(), "model", "id", "high", mode) }
            yield()
            val request = t.sent.last()
            assertEquals("turn/start", request.string("method"))
            val params = request.obj("params")
            if (mode == null) assertFalse(params.containsKey("collaborationMode"))
            else {
                assertEquals(mode, params.obj("collaborationMode").string("mode"))
                assertEquals("model", params.obj("collaborationMode").obj("settings").string("model"))
                assertEquals("high", params.obj("collaborationMode").obj("settings").string("reasoning_effort"))
                assertEquals(JsonNull, params.obj("collaborationMode").obj("settings")["developer_instructions"])
            }
            t.respond(request, buildJsonObject { }); response.await()
        }
        rpc.close()
    }
    @Test fun `goal mutations carry user origin and do not start a turn`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect(); val api = CodexApi(rpc)
        val setting = async { api.setGoal("thread", "Ship it", budget = 10000) }; yield()
        val set = t.sent.last()
        assertEquals("thread/goal/set", set.string("method"))
        assertEquals("user", set.obj("params").string("origin"))
        assertEquals(10000, set.obj("params")["tokenBudget"]?.jsonPrimitive?.int)
        t.respond(set, buildJsonObject { putJsonObject("goal") { put("objective", "Ship it"); put("status", "active"); put("tokensUsed", 42) } })
        assertEquals(42L, setting.await()?.tokensUsed)
        val pause = async { api.setGoal("thread", status = "paused") }; yield()
        val pauseRequest = t.sent.last()
        assertFalse(pauseRequest.obj("params").containsKey("objective"))
        assertFalse(pauseRequest.obj("params").containsKey("tokenBudget"))
        assertEquals("paused", pauseRequest.obj("params").string("status"))
        t.respond(pauseRequest, buildJsonObject { put("goal", JsonNull) }); pause.await()
        val clearing = async { api.clearGoal("thread") }; yield()
        assertEquals("thread/goal/clear", t.sent.last().string("method"))
        assertEquals("user", t.sent.last().obj("params").string("origin"))
        t.respond(t.sent.last(), buildJsonObject { }); clearing.await()
        assertTrue(t.sent.none { it.string("method") == "turn/start" })
        rpc.close()
    }

    @Test fun `traffic sizes match UTF8 without exposing message contents`() = runTest {
        val texts = listOf("plain", "中文😀", "\uD800", "\uDC00", "a\uD800b")
        texts.forEach { assertEquals(it.toByteArray(Charsets.UTF_8).size.toLong(), utf8Size(it)) }
        val t = FixtureTransport()
        val traffic = mutableListOf<Pair<String, Long>>()
        val rpc = RpcClient(t, onTraffic = { direction, bytes -> traffic += direction to bytes })
        rpc.connect()
        assertTrue(traffic.any { it.first == "rx" && it.second > 0 })
        assertEquals(t.sent.sumOf { it.toString().toByteArray().size.toLong() }, traffic.filter { it.first == "tx" }.sumOf { it.second })
        rpc.close()
        val safe = RpcClient(FixtureTransport(), onTraffic = { _, _ -> error("diagnostics failed") })
        safe.connect()
        safe.close()
    }

    private val questionParams = """{"threadId":"thread","turnId":"turn","itemId":"call-q","isBlocking":false,"questions":[{"id":"scope","header":"Scope","question":"How far?","isOther":true,"options":[{"label":"Structures","description":"Prepare structures"}]}]}"""

    @Test fun `questions use response IDs and cannot be resubmitted after submission or remote resolution`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        t.incoming("""{"id":0,"method":"item/tool/requestUserInput","params":$questionParams}""")
        val request = (rpc.events.receive() as RpcEvent.Request).request
        assertFalse(request.supportedApproval)
        assertFalse(request.isBlocking)
        assertEquals("How far?", request.userQuestions?.single()?.question)
        val before = t.sent.size
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request, mapOf("wrong" to listOf("A"))) }
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request, mapOf("scope" to listOf(""))) }
        assertEquals(before, t.sent.size)
        rpc.answerQuestions(request, mapOf("scope" to listOf("Custom answer")))
        assertEquals(JsonPrimitive(0), t.sent.last()["id"])
        assertEquals(wireJson.parseToJsonElement("""{"answers":{"scope":{"answers":["Custom answer"]}}}"""), t.sent.last()["result"])
        assertFailsWith<IllegalStateException> { rpc.answerQuestions(request, mapOf("scope" to listOf("Structures"))) }
        t.incoming("""{"method":"serverRequest/resolved","params":{"threadId":"thread","requestId":0}}""")
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request, mapOf("scope" to listOf("Structures"))) }
        rpc.close()
    }

    @Test fun `questions resolved elsewhere and old connection questions cannot be answered`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        t.incoming("""{"id":"q","method":"item/tool/requestUserInput","params":$questionParams}""")
        val request = (rpc.events.receive() as RpcEvent.Request).request
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request.copy(epoch = "old"), mapOf("scope" to listOf("A"))) }
        t.incoming("""{"method":"serverRequest/resolved","params":{"threadId":"thread","requestId":"q"}}""")
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request, mapOf("scope" to listOf("A"))) }
        rpc.close()
    }

    @Test fun `invalid question requests fail validation instead of exposing a partial form`() {
        val params = wireJson.parseToJsonElement(questionParams).jsonObject
        assertNotNull(UserQuestions.parseRequest(params))
        val question = params.array("questions").single()
        assertNull(UserQuestions.parseRequest(JsonObject(params + ("questions" to JsonArray(listOf(question, question))))))
        assertNull(UserQuestions.parseRequest(JsonObject(params + ("questions" to JsonArray(listOf(JsonNull))))))
        assertNull(UserQuestions.parseRequest(JsonObject(params + ("questions" to JsonArray(emptyList())))))
        assertNull(UserQuestions.parseRequest(JsonObject(params - "threadId")))
    }

    @Test fun `question options are enforced unless the server permits free text`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        val params = questionParams.replace("\"isOther\":true", "\"isOther\":false").replace("\"isBlocking\":false,", "")
        t.incoming("""{"id":5,"method":"item/tool/requestUserInput","params":$params}""")
        val request = (rpc.events.receive() as RpcEvent.Request).request
        assertTrue(request.isBlocking)
        assertFailsWith<IllegalArgumentException> { rpc.answerQuestions(request, mapOf("scope" to listOf("invented"))) }
        assertFalse(rpc.questionsSubmitted(request))
        rpc.answerQuestions(request, mapOf("scope" to listOf("Structures")))
        assertTrue(rpc.questionsSubmitted(request))
        rpc.close()
    }

    @Test fun `timing callback is optional and cannot change request delivery`() = runTest {
        val t = FixtureTransport()
        val timings = mutableListOf<Triple<String, Long, String>>()
        val rpc = RpcClient(t, onTiming = { method, elapsed, outcome ->
            timings += Triple(method, elapsed, outcome)
            error("diagnostic sink failure")
        })
        rpc.connect()
        val response = async { rpc.request("thread/read", buildJsonObject { put("threadId", "private-session") }) }
        testScheduler.runCurrent()
        t.respond(t.sent.last(), buildJsonObject { put("text", "private-message") })
        assertEquals("private-message", response.await().string("text"))
        assertEquals(listOf("initialize", "thread/read"), timings.map { it.first })
        assertTrue(timings.all { it.second >= 0 && it.third == "ok" })
        assertFalse(timings.toString().contains("private"))
        rpc.close()
    }

    @Test fun `initialization uses official framing and opts into collaboration mode APIs`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t, clientVersion = "0.1.59"); rpc.connect()
        assertEquals(listOf("initialize", "initialized"), t.sent.map { it.string("method") })
        assertFalse(t.sent.first().containsKey("jsonrpc"))
        assertEquals("0.1.59", t.sent.first().obj("params").obj("clientInfo").string("version"))
        assertEquals(true, t.sent.first().obj("params").obj("capabilities")["experimentalApi"]?.jsonPrimitive?.boolean)
        rpc.close()
    }

    @Test fun `closed transport rejects approval and question submission before fencing`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        t.incoming("""{"id":"a-1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread","command":"pwd"}}""")
        val approval = (rpc.events.receive() as RpcEvent.Request).request
        t.incoming("""{"id":"q-1","method":"item/tool/requestUserInput","params":$questionParams}""")
        val question = (rpc.events.receive() as RpcEvent.Request).request
        t.isOpen = false
        assertFailsWith<IllegalStateException> { rpc.approve(approval, "accept") }
        assertFalse(rpc.approvalSubmitted(approval))
        assertFailsWith<IllegalStateException> { rpc.answerQuestions(question, mapOf("scope" to listOf("Structures"))) }
        assertFalse(rpc.questionsSubmitted(question))
        t.isOpen = true
        rpc.approve(approval, "accept")
        assertTrue(rpc.approvalSubmitted(approval))
        rpc.close()
    }

    @Test fun `ssh prompt bridge cancels immediately when parent connection job is cancelled`() = runBlocking {
        val parent = Job()
        val entered = CompletableDeferred<Unit>()
        val worker = async(Dispatchers.Default) {
            runPromptInterruptibly(parent) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        parent.cancel()
        withTimeout(2_000) {
            assertFailsWith<CancellationException> { worker.await() }
        }
        val jumps = listOf(SshEndpoint("jump1.example", 22, "u"), SshEndpoint("jump2.example", 2222, "u"))
        assertEquals("", hopRoutePrefix(jumps, 0))
        assertEquals("jump1.example:22", hopRoutePrefix(jumps, 1))
        assertEquals("jump1.example:22->jump2.example:2222", hopRoutePrefix(jumps, 2))
    }

    @Test fun `out of order results are matched to their requests`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        val a = async { rpc.request("thread/list") }; val b = async { rpc.request("model/list") }
        testScheduler.runCurrent()
        t.respond(t.sent.last(), buildJsonObject { put("value", "models") })
        t.respond(t.sent[t.sent.lastIndex - 1], buildJsonObject { put("value", "threads") })
        assertEquals("models", b.await().string("value")); assertEquals("threads", a.await().string("value")); rpc.close()
    }

    @Test fun `lost response to mutation is uncertain and is never retried`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t, 100); rpc.connect()
        assertFailsWith<UncertainDelivery> { rpc.request("turn/start", mutation = true) }
        assertEquals(1, t.sent.count { it.string("method") == "turn/start" }); rpc.close()
    }

    @Test fun `server rejection is distinct from uncertain delivery`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        val result = async { runCatching { rpc.request("turn/start", mutation = true) } }
        testScheduler.runCurrent()
        t.incoming(jsonObject("id" to t.sent.last()["id"], "error" to buildJsonObject { put("code", -32602); put("message", "invalid") }).toString())
        assertIs<RpcException>(result.await().exceptionOrNull()); rpc.close()
    }

    @Test fun `approval resolved by another client cannot be answered again`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        t.incoming("""{"id":"server-1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread","command":"pwd"}}""")
        val request = (rpc.events.receive() as RpcEvent.Request).request
        rpc.approve(request, "accept")
        assertEquals("accept", t.sent.last().obj("result").string("decision"))
        t.incoming("""{"method":"serverRequest/resolved","params":{"threadId":"thread","requestId":"server-1"}}""")
        assertFailsWith<IllegalArgumentException> { rpc.approve(request, "accept") }; rpc.close()
    }

    @Test fun `old connection approval cannot be replayed into new connection`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        val old = ServerRequest("old-epoch", JsonPrimitive(1), "item/fileChange/requestApproval", JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> { rpc.approve(old, "accept") }; rpc.close()
    }

    @Test fun `unsupported server request fails closed without granting permissions`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        t.incoming("""{"id":77,"method":"new/permission/request","params":{"unknown":true}}""")
        val req = (rpc.events.receive() as RpcEvent.Request).request
        assertFalse(req.supportedApproval)
        rpc.rejectUnsupported(req)
        assertEquals(-32601, t.sent.last().obj("error")["code"]?.jsonPrimitive?.int)
        assertFalse(t.sent.last().containsKey("result")); rpc.close()
    }

    @Test fun `transport disconnection releases pending requests`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        val pending = async { runCatching { rpc.request("thread/read") } }
        testScheduler.runCurrent(); t.disconnected(IllegalStateException("offline"))
        assertTrue(pending.await().isFailure); assertIs<RpcEvent.Disconnected>(rpc.events.receive())
    }

    @Test fun `timeline final item replaces deltas and ignores late deltas`() {
        val start = Conversation("t")
        val delta = wireJson.parseToJsonElement("""{"threadId":"t","turnId":"turn","itemId":"i","delta":"hel"}""").jsonObject
        val partial = Timeline.notification(start, "item/agentMessage/delta", delta)
        val final = wireJson.parseToJsonElement("""{"threadId":"t","turnId":"turn","item":{"id":"i","type":"agentMessage","text":"hello","newField":123}}""").jsonObject
        val completed = Timeline.notification(partial, "item/completed", final)
        assertEquals("hello", Timeline.notification(completed, "item/agentMessage/delta", delta).items.single().text)
        assertEquals(completed, Timeline.notification(completed, "item/completed", final))
    }

    @Test fun `image view items expose their remote path for the client image viewer`() {
        val item = Timeline.parseItem(
            buildJsonObject {
                put("id", "image-item")
                put("type", "imageView")
                put("path", "/tmp/remote/plan.png")
            }, "turn", true,
        )
        assertEquals("imageView", item.kind)
        assertEquals(listOf("/tmp/remote/plan.png"), item.imagePaths)
        assertTrue(item.text.isEmpty())
        assertTrue(item.complete)
    }

    @Test fun `pagination retains newer live version of overlapping items`() {
        val fresh = TimelineItem("i", "turn", "agentMessage", "new", true)
        val old = fresh.copy(text = "old")
        val merged = Timeline.older(Conversation("t", listOf(fresh)), Conversation("t", listOf(old), nextCursor = "next"))
        assertEquals(listOf(fresh), merged.items); assertEquals("next", merged.nextCursor)
    }

    @Test fun `unknown item is visible and does not break history`() {
        val item = Timeline.parseItem(buildJsonObject { put("id", "x"); put("type", "futureTool") }, "turn", true)
        assertTrue(item.text.contains("futureTool"))
    }

    @Test fun `history refresh preserves older loaded pages and completed events during fetch`() {
        val old = TimelineItem("old", "turn0", "agentMessage", "older", true)
        val partial = TimelineItem("i", "turn1", "agentMessage", "hel")
        val before = Conversation("t", listOf(old, partial), "turn1", "cursor-old", "active")
        val finished = partial.copy(text = "hello", complete = true)
        val arrived = TimelineItem("new", "turn2", "agentMessage", "next", true)
        val live = before.copy(items = listOf(old, finished, arrived), activeTurn = null, status = "completed")
        val page = Conversation("t", listOf(partial), "turn1", "cursor-new", "active")
        val merged = Timeline.refresh(before, live, page)
        assertEquals(listOf(old, finished, arrived), merged.items)
        assertNull(merged.activeTurn)
        assertEquals("cursor-old", merged.nextCursor)
    }

    @Test fun `authoritative refresh replaces stale cached text`() {
        val stale = TimelineItem("i", "turn", "agentMessage", "stale", true)
        val before = Conversation("t", listOf(stale))
        val page = before.copy(items = listOf(stale.copy(text = "server")))
        assertEquals("server", Timeline.refresh(before, before, page).items.single().text)
    }

    @Test fun `shell parameters cannot break out of single quoted argument`() {
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
        assertEquals("'\$(whoami)'", shellQuote("\$(whoami)"))
        assertFailsWith<IllegalArgumentException> { shellQuote("a\u0000b") }
    }
}
