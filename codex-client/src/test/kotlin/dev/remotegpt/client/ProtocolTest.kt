package dev.remotegpt.client

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

private class FixtureTransport : MessageTransport {
    val sent = mutableListOf<JsonObject>()
    lateinit var incoming: (String) -> Unit
    lateinit var disconnected: (Throwable?) -> Unit
    override suspend fun connect(onMessage: (String) -> Unit, onClosed: (Throwable?) -> Unit) { incoming = onMessage; disconnected = onClosed }
    override fun send(text: String) {
        val obj = wireJson.parseToJsonElement(text).jsonObject
        sent += obj
        if (obj.string("method") == "initialize") incoming(jsonObject("id" to obj["id"], "result" to buildJsonObject { put("userAgent", "codex/0.165.0") }).toString())
    }
    fun respond(msg: JsonObject, result: JsonObject) = incoming(jsonObject("id" to msg["id"], "result" to result).toString())
    override fun close() = Unit
}

class ProtocolTest {
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

    @Test fun `initialization uses official framing and declares stable capabilities`() = runTest {
        val t = FixtureTransport(); val rpc = RpcClient(t); rpc.connect()
        assertEquals(listOf("initialize", "initialized"), t.sent.map { it.string("method") })
        assertFalse(t.sent.first().containsKey("jsonrpc"))
        assertEquals(false, t.sent.first().obj("params").obj("capabilities")["experimentalApi"]?.jsonPrimitive?.boolean)
        rpc.close()
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
