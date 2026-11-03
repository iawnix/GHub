package dev.remotegpt.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class UserQuestionsTest {
    private val id = "[\"request_user_input_async\",\"call_fixture\",0]"
    private val reply = QuestionReply(id, "你希望把这些 POM 结构处理到哪一步？", "整理成后续计算描述符可用的结构数据（推荐）")

    @Test fun `wrapped reply retains opaque identity unicode and trailing ordinary text`() {
        val raw = UserQuestions.encodeReply(listOf(reply)) + "\n\n这种消息，我们是不是没有支持？"
        val parsed = assertNotNull(UserQuestions.parseReply(raw))
        assertEquals(listOf(reply), parsed.replies)
        assertEquals("\n\n这种消息，我们是不是没有支持？", parsed.remainingText)
    }

    @Test fun `malformed missing fields wrong types and quoted examples remain raw text`() {
        listOf("not json", "[]", "{}", "[null]", "[{\"questionItemId\":\"q\",\"question\":\"Q\"}]",
            "[{\"questionItemId\":1,\"question\":\"Q\",\"answer\":\"A\"}]",
            "[{\"questionItemId\":\"q\",\"question\":\"Q\",\"answer\":null}]").forEach { body ->
            assertNull(UserQuestions.parseReply("<send_user_message_question_reply>$body</send_user_message_question_reply>"))
        }
        val raw = UserQuestions.encodeReply(listOf(reply))
        assertNull(UserQuestions.parseReply("Example:\n$raw"))
        assertNull(UserQuestions.parseReply("```xml\n$raw\n```"))
        assertNull(UserQuestions.parseReply(raw.substringBefore("</send_user_message_question_reply>")))
    }

    @Test fun `multiple answers unknown fields and embedded closing tags are safe`() {
        val second = reply.copy(questionItemId = "other-id", answer = "literal </send_user_message_question_reply> and \"quoted\"\nnext line")
        val encoded = UserQuestions.encodeReply(listOf(reply, second))
        assertEquals(listOf(reply, second), UserQuestions.parseReply(encoded)?.replies)
        val literal = "<send_user_message_question_reply>" + wireJson.encodeToString(listOf(second)) + "</send_user_message_question_reply>"
        assertEquals(listOf(second), UserQuestions.parseReply(literal)?.replies)
        val extra = "<send_user_message_question_reply>[{\"questionItemId\":\"x\",\"question\":\"Q\",\"answer\":\"\",\"future\":true}]</send_user_message_question_reply>"
        assertEquals("", UserQuestions.parseReply(extra)?.replies?.single()?.answer)
    }

    @Test fun `live history and cache keep original reply text and question metadata`() {
        val rawText = UserQuestions.encodeReply(listOf(reply))
        val raw = buildJsonObject {
            put("id", "reply"); put("type", "userMessage")
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", rawText) }) }
        }
        val params = buildJsonObject { put("threadId", "t"); put("turnId", "turn"); put("item", raw) }
        val live = Timeline.notification(Conversation("t"), "item/completed", params)
        val page = buildJsonObject {
            putJsonArray("data") { add(buildJsonObject {
                put("id", "turn"); put("status", "completed"); put("items", JsonArray(listOf(raw)))
            }) }
        }
        val history = Timeline.history("t", page)
        assertEquals(live.items, history.items)
        val cached = wireJson.decodeFromString<Conversation>(wireJson.encodeToString(history))
        assertEquals(rawText, cached.items.single().text)
        assertEquals(reply, UserQuestions.parseReply(cached.items.single().text)?.replies?.single())
        val question = Timeline.parseItem(wireJson.parseToJsonElement("""{"id":"a","type":"agentMessage","text":"Choose","questions":[{"title":"Scope?","options":["A","B"]}]}""").jsonObject, "turn", true)
        assertEquals(listOf(AsyncUserQuestion("Scope?", listOf("A", "B"))), question.questions)
        assertEquals(question, wireJson.decodeFromString<TimelineItem>(wireJson.encodeToString(question)))
        assertTrue(wireJson.decodeFromString<TimelineItem>("""{"id":"a","turnId":"t","kind":"agentMessage","text":"old"}""").questions.isEmpty())
    }
}
