package dev.remotegpt.app

import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ConversationCacheTest {
    @Test fun truncatedCacheRebuildsPaginationAndCanReachEveryEarlierTurn() {
        fun items(start: Int, end: Int) = (start..end).map { TimelineItem("i$it", "t$it", "agentMessage", "body$it", true) }
        val original = Conversation("thread", items(1, 100), nextCursor = "before-t1")
        val cached = decodeConversationCache(encodeConversationCache(original))!!
        assertEquals("t37", cached.items.first().turnId)
        assertTrue(cached.historyTruncated)
        assertNull(cached.nextCursor)
        val latest = Conversation("thread", items(81, 100), nextCursor = "before-t81")
        var restored = Timeline.refresh(cached, cached, latest)
        assertFalse(restored.historyTruncated)
        assertEquals("before-t81", restored.nextCursor)
        assertEquals(latest.items, restored.items)
        for (start in listOf(61, 41, 21, 1, 0)) {
            val end = if (start == 0) 0 else start + 19
            restored = Timeline.older(restored, Conversation("thread", items(start, end), nextCursor = if (start == 0) null else "before-t$start"))
        }
        assertEquals(items(0, 100), restored.items)
        assertNull(restored.nextCursor)
    }

    @Test fun oldChunkedCacheCannotReuseCursorEvenWhenOriginalTruncationWasNotRecorded() {
        val original = Conversation("thread", listOf(TimelineItem("i", "t", "agentMessage", "old", true)), nextCursor = "unsafe")
        val old = listOf("remote-gpt-cache-v2\n" + wireJson.encodeToString(original))
        val restored = decodeConversationCache(old)!!
        assertEquals(original.items, restored.items)
        assertTrue(restored.historyTruncated)
        assertNull(restored.nextCursor)
        val savedAgain = decodeConversationCache(encodeConversationCache(restored))!!
        assertTrue(savedAgain.historyTruncated)
        assertNull(savedAgain.nextCursor)
    }

    @Test fun completeWindowKeepsItsCursorAndTruncatedWindowCannotBePagedDirectly() {
        val original = Conversation("thread", listOf(TimelineItem("i", "t", "agentMessage", "body", true)), nextCursor = "valid")
        assertEquals(original, decodeConversationCache(encodeConversationCache(original)))
        try {
            Timeline.older(original.copy(historyTruncated = true), original)
            fail("A truncated window must fetch a new first page before loading older pages")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun commandCacheKeepsExactCommandAndMarksShortenedOutput() {
        val command = "printf '%s' \"\$HOME\"\n\t" + "中文".repeat(5_000)
        val output = "line\n".repeat(5_000)
        val item = TimelineItem("cmd", "turn", "commandExecution", output, true,
            commandExecution = CommandExecutionDetails(command, "/tmp/a b", 0, 123))
        val cached = decodeConversationCache(encodeConversationCache(Conversation("t", listOf(item))))!!.items.single()
        assertEquals(command, cached.commandExecution?.command)
        assertEquals(output.takeLast(8192), cached.text)
        assertTrue(cached.commandExecution!!.outputTruncated)
        assertEquals(0, cached.commandExecution?.exitCode)
    }

    @Test fun longMarkdownSurvivesDiskChunksWithFencesAndUnicodeIntact() {
        val text = "# Long reply\n\n```text\n" + "中文 😀 `code`\n".repeat(20_000) + "```\n\n[link][ref]\n\n[ref]: https://example.com"
        val original = Conversation("thread", listOf(TimelineItem("reply", "turn", "agentMessage", text, true)))
        val rows = encodeConversationCache(original)
        assertTrue(rows.size > 2)
        // Exercise the UTF-8 round-trip performed by SQLite, not only Kotlin strings.
        val stored = rows.map { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) }
        assertEquals(original, decodeConversationCache(stored))
        assertTrue(rows.all { it.toByteArray(Charsets.UTF_8).size < 131_072 })
        assertNull(decodeConversationCache(rows.dropLast(1)))
    }

    @Test fun existingInstalledCachesStillLoad() {
        val one = Conversation("t", listOf(TimelineItem("one", "turn", "userMessage", "Hello", true)))
        val two = Conversation("t", listOf(TimelineItem("two", "turn", "agentMessage", "**Answer**", true)))
        assertEquals(one.items + two.items, decodeConversationCache(listOf(wireJson.encodeToString(one), wireJson.encodeToString(two)))?.items)
    }
}
