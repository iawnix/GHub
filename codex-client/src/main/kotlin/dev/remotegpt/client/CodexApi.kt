package dev.remotegpt.client

import kotlinx.serialization.json.*

class CodexApi(val rpc: RpcClient) {
    private fun configuredThread(result: JsonObject): ThreadSummary {
        val thread = threadSummary(result.obj("thread"))
        return thread.copy(model = result.string("model") ?: thread.model,
            reasoningEffort = result.string("reasoningEffort") ?: thread.reasoningEffort)
    }
    suspend fun threads(cursor: String? = null, archived: Boolean = false, search: String = ""): Page<ThreadSummary> {
        val result = rpc.request("thread/list", jsonObject("limit" to JsonPrimitive(40), "cursor" to str(cursor),
            "archived" to JsonPrimitive(archived), "searchTerm" to str(search.takeIf { it.isNotBlank() }),
            "modelProviders" to JsonArray(emptyList()), "sortKey" to JsonPrimitive("updated_at")))
        return Page(result.array("data").map { threadSummary(it.jsonObject, archived) }.distinctBy { it.id }, result.string("nextCursor"))
    }

    suspend fun resume(id: String) = rpc.request("thread/resume", jsonObject("threadId" to str(id), "excludeTurns" to JsonPrimitive(true)))
    suspend fun read(id: String) = rpc.request("thread/read", jsonObject("threadId" to str(id)))
    /** Read-only discovery across every loaded-thread page, independent of UI filters. */
    suspend fun loadedThreads(): List<String> {
        val ids = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = rpc.request("thread/loaded/list", jsonObject("limit" to JsonPrimitive(100), "cursor" to str(cursor)))
            ids += page.array("data").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            cursor = page.string("nextCursor")
            check(cursor == null || cursors.add(cursor)) { "Repeated loaded-thread cursor" }
        } while (cursor != null)
        return ids.toList()
    }

    /** Metadata only: never resume a thread or download its command/message bodies. */
    suspend fun latestTurnStatus(id: String): JsonObject? = rpc.request("thread/turns/list", jsonObject(
        "threadId" to str(id), "limit" to JsonPrimitive(1), "sortDirection" to str("desc"),
        "itemsView" to str("notLoaded"))).array("data").firstOrNull() as? JsonObject
    suspend fun history(id: String, cursor: String? = null, limit: Int = 20) = rpc.request("thread/turns/list", jsonObject(
        "threadId" to str(id), "cursor" to str(cursor), "limit" to JsonPrimitive(limit),
        "sortDirection" to str("desc"), "itemsView" to str("full")))

    /** Open the same thread, including empty threads created by older clients. */
    suspend fun resumeOrReadEmpty(id: String): ThreadSummary {
        try {
            return configuredThread(resume(id))
        } catch (e: RpcException) {
            if (e.code != -32600 || e.message != "no rollout found for thread id $id") throw e
        }
        // Missing rollout alone is not evidence of an empty thread: it could be
        // deleted or corrupt. Require live metadata AND the explicit empty-history
        // response; do not infer lifecycle from a title or an old local cache flag.
        val metadata = try {
            threadSummary(read(id).obj("thread"))
        } catch (e: RpcException) {
            if (e.code == -32600 && e.message in setOf("thread not loaded: $id", "no rollout found for thread id $id")) {
                throw ThreadUnavailable(id, e)
            }
            throw e
        }
        if (metadata.id != id) throw ClientStateException(ClientErrorCode.THREAD_ID_MISMATCH, "Server returned mismatched thread ID")
        try {
            history(id, limit = 1)
        } catch (e: RpcException) {
            if (e.code == -32600 && e.message ==
                "thread $id is not materialized yet; thread/turns/list is unavailable before first user message") {
                return metadata.copy(awaitingFirstMessage = true)
            }
            throw e
        }
        // Another client may have sent the first message during these reads.
        // Retry subscription once, never thread/start or turn/start.
        return configuredThread(resume(id))
    }

    suspend fun create(cwd: String): ThreadSummary {
        if (!cwd.startsWith('/')) throw ClientArgumentException(ClientErrorCode.INVALID_REMOTE_DIRECTORY, "Enter an absolute remote directory")
        return configuredThread(rpc.request("thread/start", buildJsonObject {
            put("cwd", cwd); put("sandbox", "workspace-write"); put("approvalPolicy", "on-request")
            put("approvalsReviewer", "user")
        }, mutation = true))
    }

    suspend fun send(thread: String, text: String, images: List<String>, model: String?, clientMessageId: String, effort: String? = null, mode: String? = null): JsonObject {
        return rpc.request("turn/start", buildJsonObject {
            put("threadId", thread); put("clientUserMessageId", clientMessageId)
            mode?.let { put("collaborationMode", collaborationSettings(it, requireNotNull(model), effort)) }
            model?.let { put("model", it) }
            effort?.let { put("effort", it) }
            putJsonArray("input") {
                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                images.forEach { path -> add(buildJsonObject { put("type", "localImage"); put("path", path) }) }
            }
        }, mutation = true)
    }

    suspend fun collaborationModes(): Set<String> = rpc.request("collaborationMode/list").array("data")
        .mapNotNull { (it as? JsonObject)?.string("mode") }.toSet()
    suspend fun goal(thread: String): ThreadGoal? = parseThreadGoal(rpc.request("thread/goal/get",
        buildJsonObject { put("threadId", thread) })["goal"])
    suspend fun setGoal(thread: String, objective: String? = null, status: String? = null, budget: Long? = null): ThreadGoal? =
        parseThreadGoal(rpc.request("thread/goal/set", buildJsonObject {
            put("threadId", thread); put("origin", "user")
            objective?.let { put("objective", it) }; status?.let { put("status", it) }
            budget?.let { require(it > 0); put("tokenBudget", it) }
        }, mutation = true)["goal"])
    suspend fun clearGoal(thread: String) { rpc.request("thread/goal/clear", buildJsonObject {
        put("threadId", thread); put("origin", "user")
    }, mutation = true) }
    suspend fun updatePermissions(thread: String, approval: String, sandbox: String) {
        require(approval in setOf("untrusted", "on-request", "never"))
        require(sandbox in setOf("read-only", "workspace-write", "danger-full-access"))
        rpc.request("thread/settings/update", buildJsonObject {
            put("threadId", thread); put("approvalPolicy", approval); put("sandboxPolicy", buildJsonObject {
                put("type", when (sandbox) { "read-only" -> "readOnly"; "danger-full-access" -> "dangerFullAccess"; else -> "workspaceWrite" })
                if (sandbox == "read-only") put("networkAccess", false)
                if (sandbox == "workspace-write") { putJsonArray("writableRoots") {}; put("networkAccess", false); put("excludeTmpdirEnvVar", false); put("excludeSlashTmp", false) }
            })
        }, mutation = true)
    }

    suspend fun interrupt(thread: String, turn: String) = rpc.request("turn/interrupt", jsonObject("threadId" to str(thread), "turnId" to str(turn)), true)
    suspend fun rename(thread: String, name: String) = rpc.request("thread/name/set", jsonObject("threadId" to str(thread), "name" to str(name)), true)
    suspend fun archive(thread: String, archived: Boolean) = rpc.request(if (archived) "thread/archive" else "thread/unarchive", jsonObject("threadId" to str(thread)), true)
    suspend fun models(): List<ModelOption> {
        val result = mutableListOf<ModelOption>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = rpc.request("model/list", jsonObject("cursor" to str(cursor), "limit" to JsonPrimitive(100)))
            page.array("data").forEach {
                val obj = it.jsonObject
                if (obj["hidden"]?.jsonPrimitive?.booleanOrNull != true) obj.string("model")?.let { id ->
                    result += ModelOption(id, obj.string("displayName") ?: id,
                        obj.array("supportedReasoningEfforts").mapNotNull { value -> (value as? JsonObject)?.string("reasoningEffort") }.distinct(),
                        obj.string("defaultReasoningEffort"), obj["isDefault"]?.jsonPrimitive?.booleanOrNull == true)
                }
            }
            cursor = page.string("nextCursor")
            if (cursor != null && !cursors.add(cursor!!)) {
                throw ClientStateException(ClientErrorCode.REPEATED_MODEL_CURSOR, "Repeated model pagination cursor")
            }
        } while (cursor != null)
        return result.distinctBy { it.id }
    }
    suspend fun account() = rpc.request("account/read", buildJsonObject { put("refreshToken", false) })
}
