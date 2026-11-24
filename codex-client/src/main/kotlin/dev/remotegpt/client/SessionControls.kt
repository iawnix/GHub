package dev.remotegpt.client

import kotlinx.serialization.json.*

/** Only exact, supported command names are interpreted; paths and code remain text. */
data class SessionCommand(val name: String, val argument: String)

/** Commands handled by the client before a message is sent to the App Server. */
val supportedSessionCommands = listOf("/plan", "/goal")

fun sessionCommand(text: String): SessionCommand? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith('/')) return null
    val match = Regex("^(/\\S+)(?:\\s+(.*))?$", setOf(RegexOption.DOT_MATCHES_ALL)).matchEntire(trimmed) ?: return null
    val name = match.groupValues[1].lowercase()
    return if (name in supportedSessionCommands) SessionCommand(name, match.groupValues.getOrElse(2) { "" }.trim()) else null
}

/** Returns command completions only while the first token is being typed. */
fun sessionCommandSuggestions(text: String): List<String> {
    val input = text.trimStart()
    if (!input.startsWith('/') || input.contains(Regex("\\s"))) return emptyList()
    return supportedSessionCommands.filter { it.startsWith(input.lowercase()) }
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
