package dev.remotegpt.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
fun jsonObject(vararg pairs: Pair<String, JsonElement?>) = JsonObject(pairs.filter { it.second != null }.associate { it.first to it.second!! })
fun str(value: String?) = value?.let(::JsonPrimitive)

@Serializable
enum class AuthKind { PASSWORD, PRIVATE_KEY, INTERACTIVE }

/** Credentials themselves never belong in a connection profile. */
@Serializable
data class SshEndpoint(
    val host: String = "", val port: Int = 22, val username: String = "",
    val credentialId: String = UUID.randomUUID().toString(), val auth: AuthKind = AuthKind.PRIVATE_KEY,
)

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(), val name: String = "",
    val endpoint: SshEndpoint = SshEndpoint(), val jumps: List<SshEndpoint> = emptyList(),
    val codexPath: String = "codex", val socketPath: String = "", val defaultCwd: String = "",
)

data class Credential(val privateKey: String = "", val password: String = "", val passphrase: String = "")
data class HostKeyChallenge(val host: String, val port: Int, val algorithm: String, val fingerprint: String, val previous: String?)
data class KeyboardPrompt(val name: String, val instruction: String, val prompts: List<Pair<String, Boolean>>)

@Serializable
data class ThreadSummary(
    val id: String, val title: String, val cwd: String, val status: String = "notLoaded",
    val updatedAt: Long = 0, val archived: Boolean = false,
    // Local lifecycle marker; thread/start has not yet materialized history.
    val awaitingFirstMessage: Boolean = false,
    val model: String? = null, val reasoningEffort: String? = null,
)

data class ModelOption(val id: String, val name: String, val efforts: List<String>,
    val defaultEffort: String?, val isDefault: Boolean)

fun threadSummary(raw: JsonObject, archived: Boolean = false): ThreadSummary = ThreadSummary(
    id = raw.string("id") ?: error("Missing thread ID"),
    title = raw.string("name")?.takeIf { it.isNotBlank() } ?: raw.string("preview")?.take(100).orEmpty().ifBlank { "新会话" },
    cwd = raw.string("cwd").orEmpty(), status = raw.obj("status").string("type") ?: "notLoaded",
    updatedAt = (raw["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0, archived = archived,
    model = raw.string("model"), reasoningEffort = raw.string("reasoningEffort"),
)

@Serializable
data class FileEdit(val path: String, val diff: String) {
    // Only count unified-diff hunk lines; headers and arbitrary output are not edits.
    val lineCounts: Pair<Int, Int>? get() {
        var hunk = false
        var recognized = false
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("@@ ") -> { hunk = true; recognized = true }
                line.startsWith("diff --git ") -> hunk = false
                hunk && line.startsWith('+') -> added++
                hunk && line.startsWith('-') -> removed++
            }
        }
        return if (recognized) added to removed else null
    }
}

@Serializable
data class CommandExecutionDetails(
    val command: String? = null,
    val workingDirectory: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val outputTruncated: Boolean = false,
)

@Serializable
data class DynamicToolCallDetails(
    val tool: String,
    val namespace: String? = null,
    val arguments: JsonElement = JsonNull,
    val contentItems: List<JsonObject>? = null,
    val success: Boolean? = null,
    val durationMs: Long? = null,
) {
    val displayName get() = listOfNotNull(namespace?.takeIf { it.isNotBlank() }, tool).joinToString(".")
}

@Serializable
data class TimelineItem(
    val id: String, val turnId: String, val kind: String, val text: String,
    val complete: Boolean = false, val imagePaths: List<String> = emptyList(),
    val fileEdits: List<FileEdit> = emptyList(), val failed: Boolean = false,
    val executionStatus: String? = null,
    // When present, text contains only the output. Older caches retain their
    // original combined text and must never be split by guessing at newlines.
    val commandExecution: CommandExecutionDetails? = null,
    val questions: List<AsyncUserQuestion> = emptyList(),
    val dynamicToolCall: DynamicToolCallDetails? = null,
) {
    // Identity within a conversation. Length-prefixing avoids delimiter collisions.
    // Computed, so existing serialized caches remain compatible.
    val identityKey: String get() = "${turnId.length}:$turnId$id"
}

@Serializable
data class Conversation(
    val threadId: String, val items: List<TimelineItem> = emptyList(), val activeTurn: String? = null,
    val nextCursor: String? = null, val status: String = "idle",
    // A truncated cache is readable, but has no trustworthy backwards cursor.
    val historyTruncated: Boolean = false,
    // Runtime provenance: incomplete event-only objects must never replace disk history.
    @kotlinx.serialization.Transient val baselineLoaded: Boolean = true,
    // Detect even idle -> active -> idle transitions while a history read is in flight.
    @kotlinx.serialization.Transient val stateRevision: Long = 0,
)

data class ServerRequest(val epoch: String, val id: JsonElement, val method: String, val params: JsonObject) {
    val key get() = "$epoch:$id"
    val threadId get() = params.string("threadId").orEmpty()
    val supportedApproval get() = method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")
    val userQuestions get() = if (method == "item/tool/requestUserInput") UserQuestions.parseRequest(params) else null
    val isBlocking get() = (params["isBlocking"] as? JsonPrimitive)?.booleanOrNull ?: true
    val command get() = params.string("command")
    val reason get() = params.string("reason")
    val workingDirectory get() = params.string("cwd")
    val writeRoot get() = params.string("grantRoot")
    val networkHost get() = params.obj("networkApprovalContext").string("host")
}

sealed interface RpcEvent {
    /** Local ordering fence, never transmitted over the protocol. */
    data class Barrier(val applied: kotlinx.coroutines.CompletableDeferred<Unit>) : RpcEvent
    data class Notification(val method: String, val params: JsonObject, val receivedNanos: Long = System.nanoTime()) : RpcEvent
    data class Request(val request: ServerRequest, val receivedNanos: Long = System.nanoTime()) : RpcEvent
    data class Disconnected(val reason: Throwable?) : RpcEvent
}

data class Page<T>(val items: List<T>, val nextCursor: String?)
data class RemoteFile(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: Long)
data class TextFile(val path: String, val text: String, val sha256: String)
class FileConflict : Exception("远端文件已修改，请重新加载，或确认覆盖。")
class UnsupportedServer(message: String) : Exception(message)
class RpcException(val code: Int, message: String, val method: String? = null) : Exception(message)
class ThreadUnavailable(val threadId: String, cause: Throwable) : Exception(
    "远端找不到此会话，可能创建未完成或记录已失效。手机上的草稿和缓存仍保留。", cause)
class UncertainDelivery(val method: String, cause: Throwable) : Exception("发送状态待确认：请刷新会话核对结果，不要重复发送。", cause)
