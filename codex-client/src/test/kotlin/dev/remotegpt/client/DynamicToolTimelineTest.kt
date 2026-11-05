package dev.remotegpt.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class DynamicToolTimelineTest {
    private val arguments = buildJsonObject { put("query", "中文\n\tvalue"); put("limit", 2) }
    private val content = buildJsonArray {
        add(buildJsonObject { put("type", "inputText"); put("text", "first\n\t中文") })
        add(buildJsonObject { put("type", "inputImage"); put("imageUrl", "https://example.com/image.png") })
        add(buildJsonObject { put("type", "inputAudio"); put("audioUrl", "data:audio/wav;base64,AA==") })
        add(buildJsonObject { put("type", "futureResult"); put("payload", "preserved") })
    }
    private fun raw(status: String, success: Boolean? = null) = buildJsonObject {
        put("id", "call"); put("type", "dynamicToolCall"); put("tool", "search")
        put("namespace", "tools"); put("arguments", arguments); put("status", status)
        put("success", success?.let(::JsonPrimitive) ?: JsonNull)
        put("contentItems", if (status == "inProgress") JsonNull else content)
        put("durationMs", if (status == "inProgress") JsonNull else JsonPrimitive(1234))
    }
    private fun event(raw: JsonObject) = buildJsonObject {
        put("threadId", "thread"); put("turnId", "turn"); put("item", raw)
    }

    @Test fun `live start and completion match history including structured output`() {
        val started = Timeline.notification(Conversation("thread"), "item/started", event(raw("inProgress")))
        assertFalse(started.items.single().complete)
        assertNull(started.items.single().dynamicToolCall!!.contentItems)
        val completed = raw("completed", true)
        val live = Timeline.notification(started, "item/completed", event(completed))
        val history = Timeline.history("thread", buildJsonObject {
            putJsonArray("data") { add(buildJsonObject {
                put("id", "turn"); put("status", "completed"); putJsonArray("items") { add(completed) }
            }) }
        })
        assertEquals(history.items, live.items)
        val item = live.items.single()
        assertEquals("", item.text)
        assertFalse(item.failed)
        val details = item.dynamicToolCall!!
        assertEquals("tools.search", details.displayName)
        assertEquals(arguments, details.arguments)
        assertEquals(content.map { it.jsonObject }, details.contentItems)
        assertEquals(1234L, details.durationMs)
        assertEquals(live, Timeline.notification(live, "item/started", event(raw("inProgress"))))
        assertEquals(live, wireJson.decodeFromString<Conversation>(wireJson.encodeToString(live)))
    }

    @Test fun `tool failure is retained even when call status says completed`() {
        assertTrue(Timeline.parseItem(raw("completed", false), "turn", true).failed)
        assertTrue(Timeline.parseItem(raw("failed"), "turn", true).failed)
        assertFalse(Timeline.parseItem(raw("inProgress"), "turn", false).failed)
    }

    @Test fun `optional metadata and non-object arguments remain valid`() {
        val item = Timeline.parseItem(buildJsonObject {
            put("id", "i"); put("type", "dynamicToolCall"); put("tool", "test")
            put("arguments", JsonPrimitive("literal input")); put("status", "completed")
        }, "turn", true)
        assertEquals("test", item.dynamicToolCall!!.displayName)
        assertEquals(JsonPrimitive("literal input"), item.dynamicToolCall!!.arguments)
        assertNull(item.dynamicToolCall!!.contentItems)
        assertNull(item.dynamicToolCall!!.success)
        val old = wireJson.decodeFromString<TimelineItem>("""{"id":"i","turnId":"t","kind":"dynamicToolCall","text":"此类型暂未完整显示：dynamicToolCall","complete":true}""")
        assertNull(old.dynamicToolCall)
    }
}
