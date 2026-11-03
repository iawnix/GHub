package dev.remotegpt.client

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TimelineSyncTest {
    private fun delta(value: String) = buildJsonObject {
        put("threadId", "thread"); put("turnId", "turn"); put("itemId", "answer"); put("delta", value)
    }
    private fun lifecycle(id: String, status: String = "inProgress") = buildJsonObject {
        put("threadId", "thread"); putJsonObject("turn") { put("id", id); put("status", status) }
    }

    @Test fun `active history omitting unfinished message preserves stream before and during read`() {
        val prompt = TimelineItem("user", "turn", "userMessage", "Stream output.", true)
        val before = Conversation("thread", listOf(prompt, TimelineItem("answer", "turn", "agentMessage", "hello ")),
            "turn", status = "active")
        val page = Conversation("thread", listOf(prompt.copy(complete = false)), "turn", status = "active")
        assertEquals("hello ", Timeline.refresh(before, before, page).items.last().text)
        val live = Timeline.notification(before, "item/agentMessage/delta", delta("world"))
        val merged = Timeline.refresh(before, live, page)
        assertEquals("hello world", merged.items.last().text)
        assertEquals(2, merged.items.size)
        assertEquals("hello world!", Timeline.notification(merged, "item/agentMessage/delta", delta("!")).items.last().text)
    }

    @Test fun `completion during a read cannot be resurrected even when active turn returns to null`() {
        val before = Conversation("thread")
        val started = Timeline.notification(before, "turn/started", lifecycle("turn"))
        val streamed = Timeline.notification(started, "item/agentMessage/delta", delta("complete answer"))
        val finished = Timeline.notification(streamed, "turn/completed", lifecycle("turn", "completed"))
        val stalePage = Conversation("thread", listOf(TimelineItem("answer", "turn", "agentMessage", "complete")), "turn", status = "active")
        val merged = Timeline.refresh(before, finished, stalePage)
        assertNull(merged.activeTurn)
        assertEquals("completed", merged.status)
        assertEquals("complete answer", merged.items.single().text)
        assertTrue(merged.items.single().complete)
        assertEquals(finished.stateRevision, merged.stateRevision)
    }

    @Test fun `omitted live messages keep their position before later snapshot items`() {
        val prompt = TimelineItem("user", "turn", "userMessage", "Run checks.", true)
        val commentary = TimelineItem("answer", "turn", "agentMessage", "Checking now.")
        val command = TimelineItem("command", "turn", "commandExecution", "running")
        val before = Conversation("thread", listOf(prompt, commentary, command), "turn", status = "active")
        val page = before.copy(items = listOf(prompt, command.copy(text = "running checks")))
        val merged = Timeline.refresh(before, before, page)
        assertEquals(listOf("user", "answer", "command"), merged.items.map { it.id })
        assertEquals("Checking now.", merged.items[1].text)
        assertEquals("running checks", merged.items[2].text)
    }

    @Test fun `old turn completion cannot stop a newer turn`() {
        val before = Conversation("thread", activeTurn = "old", status = "active")
        val newer = Timeline.notification(before, "turn/started", lifecycle("new"))
        val after = Timeline.notification(newer, "turn/completed", lifecycle("old", "completed"))
        assertEquals("new", after.activeTurn)
        assertEquals("active", after.status)
    }

    @Test fun `final item replaces partial text without duplication and late history keeps final item`() {
        val before = Timeline.notification(Conversation("thread", activeTurn = "turn"), "item/agentMessage/delta", delta("partial"))
        val completed = Timeline.notification(before, "item/completed", buildJsonObject {
            put("threadId", "thread"); put("turnId", "turn")
            putJsonObject("item") { put("id", "answer"); put("type", "agentMessage"); put("text", "final answer") }
        })
        val result = Timeline.refresh(before, completed, before)
        assertEquals("final answer", result.items.single().text)
        assertTrue(result.items.single().complete)
        assertEquals(result, Timeline.notification(result, "item/agentMessage/delta", delta("late")))
    }

    @Test fun `snapshot that extends a partial stream does not regress to the shorter live prefix`() {
        val before = Conversation("thread", listOf(TimelineItem("answer", "turn", "agentMessage", "hello")), "turn", status = "active")
        val live = Timeline.notification(before, "item/agentMessage/delta", delta(" world"))
        val page = before.copy(items = listOf(before.items.single().copy(text = "hello world!")))
        assertEquals("hello world!", Timeline.refresh(before, live, page).items.single().text)
    }

    @Test fun `known historical snapshot still replaces stale completed cache`() {
        val cached = Conversation("thread", listOf(TimelineItem("answer", "old", "agentMessage", "stale", true)))
        val page = cached.copy(items = listOf(cached.items.single().copy(text = "authoritative")))
        assertEquals("authoritative", Timeline.refresh(cached, cached, page).items.single().text)
    }

    @Test fun `active history restores a truncated completed command from disk`() {
        val cachedItem = TimelineItem("command", "turn", "commandExecution", "tail", true,
            executionStatus = "completed", commandExecution = CommandExecutionDetails(outputTruncated = true))
        val cached = Conversation("thread", listOf(cachedItem), activeTurn = "turn")
        val page = cached.copy(items = listOf(cachedItem.copy(text = "prefix tail", complete = false,
            commandExecution = CommandExecutionDetails(outputTruncated = false))))
        val result = Timeline.refresh(cached, cached, page).items.single()
        assertEquals("prefix tail", result.text)
        assertFalse(result.commandExecution!!.outputTruncated)
    }
}
