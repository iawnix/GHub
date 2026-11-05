package dev.remotegpt.client

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ContextCompactionTimelineTest {
    @Test fun `active history preserves known completion and replaces old placeholder text`() {
        val cached = Conversation("thread", listOf(TimelineItem("compact", "turn", "contextCompaction",
            "此类型暂未完整显示：contextCompaction", complete = true)), activeTurn = "turn")
        val page = cached.copy(items = listOf(Timeline.parseItem(buildJsonObject {
            put("id", "compact"); put("type", "contextCompaction")
        }, "turn", complete = false)))
        val refreshed = Timeline.refresh(cached, cached, page).items.single()
        assertTrue(refreshed.complete)
        assertEquals("", refreshed.text)
    }

    @Test fun `compaction lifecycle and history use supported markers without placeholder text`() {
        val raw = buildJsonObject { put("id", "compact"); put("type", "contextCompaction") }
        val event = buildJsonObject {
            put("threadId", "thread"); put("turnId", "turn"); put("item", raw)
        }
        val started = Timeline.notification(Conversation("thread"), "item/started", event)
        assertFalse(started.items.single().complete)
        assertFalse(started.items.single().failed)
        assertEquals("", started.items.single().text)
        val finished = Timeline.notification(started, "item/completed", event)
        assertTrue(finished.items.single().complete)
        assertFalse(finished.items.single().failed)
        assertEquals(finished, Timeline.notification(finished, "item/started", event))
        val history = Timeline.history("thread", buildJsonObject {
            putJsonArray("data") { add(buildJsonObject {
                put("id", "turn"); put("status", "completed"); putJsonArray("items") { add(raw) }
            }) }
        })
        assertEquals(finished.items, history.items)
    }
}
