package dev.remotegpt.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class UserQuestionOption(val label: String, val description: String = "")

@Serializable
data class AsyncUserQuestion(val title: String, val options: List<String> = emptyList())

@Serializable
data class UserQuestion(
    val id: String, val question: String, val header: String = "",
    val options: List<UserQuestionOption> = emptyList(),
    val isOther: Boolean = false, val isSecret: Boolean = false,
)

/** A reply ID is opaque: keep it intact, including the JSON encoded tuple. */
@Serializable
data class QuestionReply(val questionItemId: String, val question: String, val answer: String)

data class QuestionReplyMessage(val replies: List<QuestionReply>, val remainingText: String)

object UserQuestions {
    private const val OPEN = "<send_user_message_question_reply>"
    private const val CLOSE = "</send_user_message_question_reply>"

    /** Only recognize an envelope at the start; quoted examples stay ordinary text.
     * Keep the original TimelineItem.text for lossless caching and fallback. */
    fun parseReply(text: String): QuestionReplyMessage? {
        val input = text.trimStart()
        if (!input.startsWith(OPEN)) return null
        var end = input.indexOf(CLOSE, OPEN.length)
        while (end >= 0) {
            // A JSON string can itself contain the closing tag.
            val replies = runCatching {
                val entries = wireJson.parseToJsonElement(input.substring(OPEN.length, end)) as? JsonArray
                    ?: return@runCatching null
                if (entries.isEmpty()) return@runCatching null
                entries.map { entry ->
                    val obj = entry as? JsonObject ?: return@runCatching null
                    QuestionReply(obj.strictString("questionItemId")?.takeIf { it.isNotBlank() } ?: return@runCatching null,
                        obj.strictString("question") ?: return@runCatching null,
                        obj.strictString("answer") ?: return@runCatching null)
                }
            }.getOrNull()
            if (replies != null) return QuestionReplyMessage(replies, input.substring(end + CLOSE.length))
            end = input.indexOf(CLOSE, end + CLOSE.length)
        }
        return null
    }

    fun encodeReply(replies: List<QuestionReply>): String {
        require(replies.isNotEmpty())
        val body = wireJson.encodeToString(replies).replace("<", "\\u003c")
        return "$OPEN\n$body\n$CLOSE"
    }

    fun parseRequest(params: JsonObject): List<UserQuestion>? = runCatching {
        if (listOf("threadId", "turnId", "itemId").any { params.strictString(it).isNullOrBlank() }) return null
        val entries = params["questions"] as? JsonArray ?: return null
        if (entries.isEmpty()) return null
        val questions = entries.map { entry ->
            val obj = entry as? JsonObject ?: return null
            val options = when (val raw = obj["options"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> raw.map {
                    val option = it as? JsonObject ?: return null
                    UserQuestionOption(option.strictString("label") ?: return null,
                        option.strictString("description") ?: return null)
                }
                else -> return null
            }
            UserQuestion(obj.strictString("id")?.takeIf { it.isNotBlank() } ?: return null,
                obj.strictString("question") ?: return null, obj.strictString("header") ?: return null, options,
                obj["isOther"]?.jsonPrimitive?.boolean ?: false,
                obj["isSecret"]?.jsonPrimitive?.boolean ?: false)
        }
        questions.takeIf { it.map(UserQuestion::id).distinct().size == it.size }
    }.getOrNull()

    private fun JsonObject.strictString(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
