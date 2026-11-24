package dev.remotegpt.client

import kotlinx.serialization.json.*

/** Only exact, supported command names are interpreted; paths and code remain text. */
data class SessionCommand(val name: String, val argument: String)
fun sessionCommand(text: String): SessionCommand? {
    val parts = text.trim().split(Regex("\\s+"), limit = 2)
    val name = parts.firstOrNull() ?: return null
    return if (name in setOf("/plan", "/goal")) SessionCommand(name, parts.getOrElse(1) { "" }) else null
}
data class ThreadGoal(val objective: String, val status: String, val tokenBudget: Long?, val tokensUsed: Long)
fun parseThreadGoal(value: JsonElement?): ThreadGoal? {
    val obj = value as? JsonObject ?: return null
    return ThreadGoal(obj.string("objective") ?: return null, obj.string("status") ?: "active",
        obj["tokenBudget"]?.jsonPrimitive?.longOrNull, obj["tokensUsed"]?.jsonPrimitive?.longOrNull ?: 0)
}
fun collaborationSettings(mode: String, model: String, effort: String?): JsonObject {
    require(mode in setOf("plan", "default") && model.isNotBlank())
    return buildJsonObject {
        put("mode", mode)
        putJsonObject("settings") {
            put("model", model); put("reasoning_effort", effort?.let(::JsonPrimitive) ?: JsonNull)
            put("developer_instructions", JsonNull)
        }
    }
}
