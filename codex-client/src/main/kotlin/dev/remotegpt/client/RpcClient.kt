package dev.remotegpt.client

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

interface MessageTransport : Closeable {
    suspend fun connect(onMessage: (String) -> Unit, onClosed: (Throwable?) -> Unit)
    fun send(text: String)
}

/** One instance per connection. IDs and approvals never survive a reconnect. */
class RpcClient(private val transport: MessageTransport, private val timeoutMs: Long = 30_000,
    private val onTiming: (String, Long, String) -> Unit = { _, _, _ -> },
) : Closeable {
    val epoch = UUID.randomUUID().toString()
    private val eventBuffer = OrderedRpcEvents { disconnected(EventBacklog()); transport.close() }
    val events get() = eventBuffer.output
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val approvals = ConcurrentHashMap<String, ServerRequest>()
    private val submittedApprovals = ConcurrentHashMap.newKeySet<String>()
    private val barriers = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()
    private val closed = AtomicBoolean(false)
    val isConnected get() = !closed.get()
    @Volatile var disconnectReason: Throwable? = null
        private set

    suspend fun connect(): JsonObject {
        transport.connect(::receive, ::disconnected)
        val initialized = request("initialize", buildJsonObject {
            putJsonObject("clientInfo") { put("name", "remote_gpt_android"); put("title", "GHub"); put("version", "0.1.0") }
            putJsonObject("capabilities") { put("experimentalApi", false) }
        })
        transport.send(buildJsonObject { put("method", "initialized"); putJsonObject("params") {} }.toString())
        return initialized
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), mutation: Boolean = false): JsonObject {
        check(!closed.get()) { "连接已关闭" }
        val id = sequence.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        val started = System.nanoTime()
        var outcome = "failed"
        pending[id.toString()] = deferred
        try {
            check(!closed.get()) { "连接已关闭" }
            transport.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
            return withTimeout(timeoutMs) { deferred.await() }.also { outcome = "ok" }
        } catch (e: RpcException) { throw RpcException(e.code, e.message.orEmpty(), method)
        } catch (e: Throwable) {
            if (mutation) throw UncertainDelivery(method, e)
            throw e
        } finally {
            pending.remove(id.toString())
            // Diagnostics cannot affect delivery semantics or leak request arguments.
            runCatching { onTiming(method, (System.nanoTime() - started) / 1_000_000, outcome) }
        }
    }

    fun approve(request: ServerRequest, decision: String) {
        check(!closed.get()) { "连接已关闭，审批已失效。" }
        require(request.epoch == epoch && approvals[request.id.toString()] == request) { "审批已过期，请刷新会话。" }
        require(request.supportedApproval) { "此审批类型尚不支持。" }
        require(decision in setOf("accept", "acceptForSession", "decline", "cancel"))
        check(submittedApprovals.add(request.id.toString())) { "审批已提交，正在等待服务端确认，请勿重复操作。" }
        transport.send(buildJsonObject {
            put("id", request.id); putJsonObject("result") { put("decision", decision) }
        }.toString())
        // Keep pending until serverRequest/resolved: send() is not an acknowledgement.
    }

    fun questionsSubmitted(request: ServerRequest): Boolean = request.epoch == epoch && request.id.toString() in submittedApprovals

    fun answerQuestions(request: ServerRequest, answers: Map<String, List<String>>) {
        check(!closed.get()) { "连接已关闭，问题已失效。" }
        require(request.epoch == epoch && approvals[request.id.toString()] == request) { "问题已过期，请刷新会话。" }
        val questions = requireNotNull(request.userQuestions) { "此提问类型尚不支持。" }
        require(answers.keys == questions.map { it.id }.toSet()) { "请回答每个问题。" }
        questions.forEach { question ->
            val answer = answers.getValue(question.id)
            require(answer.size == 1 && answer.single().isNotBlank()) { "请回答每个问题。" }
            require(question.options.isEmpty() || question.isOther || question.options.any { it.label == answer.single() }) {
                "请选择提供的选项。"
            }
        }
        check(submittedApprovals.add(request.id.toString())) { "回答已提交，正在等待服务端确认，请勿重复操作。" }
        // A send failure can have uncertain delivery. Keep the submission fenced
        // until resolution or reconnect, just like permission responses.
        transport.send(buildJsonObject {
            put("id", request.id)
            putJsonObject("result") {
                putJsonObject("answers") {
                    answers.forEach { (id, values) -> putJsonObject(id) {
                        put("answers", JsonArray(values.map(::JsonPrimitive)))
                    } }
                }
            }
        }.toString())
    }

    fun rejectUnsupported(request: ServerRequest) {
        if (request.epoch != epoch || !approvals.containsKey(request.id.toString())) return
        transport.send(buildJsonObject {
            put("id", request.id)
            putJsonObject("error") { put("code", -32601); put("message", "This client cannot handle ${request.method}; no permission was granted.") }
        }.toString())
        approvals.remove(request.id.toString())
    }

    /** Apply preceding notifications before merging a history snapshot, avoiding doubled deltas. */
    suspend fun awaitEventsApplied() {
        val applied = CompletableDeferred<Unit>()
        barriers += applied
        try {
            check(!closed.get()) { "连接已关闭" }
            eventBuffer.offer(RpcEvent.Barrier(applied), 0)
            withTimeout(timeoutMs) { applied.await() }
        } finally { barriers -= applied }
    }

    private fun receive(text: String) {
        if (closed.get()) return
        try {
            val msg = wireJson.parseToJsonElement(text).jsonObject
            val method = msg.string("method")
            val id = msg["id"]
            if (method != null) {
                val params = msg.obj("params")
                if (id != null) {
                    val req = ServerRequest(epoch, id, method, params)
                    approvals[id.toString()] = req
                    eventBuffer.offer(RpcEvent.Request(req), text.length)
                } else {
                    if (method == "serverRequest/resolved") {
                        approvals.remove(params["requestId"].toString())
                        submittedApprovals.remove(params["requestId"].toString())
                    }
                    eventBuffer.offer(RpcEvent.Notification(method, params), text.length)
                }
            } else if (id != null) {
                pending.remove(id.jsonPrimitive.content)?.let { waiter ->
                    if (msg["error"] is JsonObject) {
                        val error = msg.obj("error")
                        waiter.completeExceptionally(RpcException(error["code"]?.jsonPrimitive?.intOrNull ?: -1, error.string("message") ?: "请求失败"))
                    } else waiter.complete(msg["result"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            }
        } catch (e: Exception) { disconnected(e); transport.close() }
    }

    private fun disconnected(reason: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        disconnectReason = reason
        approvals.clear()
        submittedApprovals.clear()
        pending.values.forEach { it.completeExceptionally(reason ?: IllegalStateException("连接已断开")) }
        pending.clear()
        barriers.forEach { it.completeExceptionally(reason ?: IllegalStateException("连接已断开")) }
        barriers.clear()
        eventBuffer.close(reason)
    }

    /** Invalidate requests immediately; callers may dispose blocking SSH resources on IO. */
    fun invalidate() = disconnected(null)
    override fun close() { invalidate(); transport.close() }
}
