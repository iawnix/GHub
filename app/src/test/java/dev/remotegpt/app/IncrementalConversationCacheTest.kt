package dev.remotegpt.app

import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import org.junit.Assert.*
import org.junit.Test

class IncrementalConversationCacheTest {
    private fun rows(patch: ConversationCachePatch) = ConversationCacheRows(
        listOf(ConversationChunkRow("host", "thread", 0, patch.header!!)),
        patch.changed.flatMap { (id, chunks) -> chunks.mapIndexed { index, json -> ConversationItemRow("host", "thread", id, index, json) } })

    @Test fun onlyGrowingItemIsEncodedWhileLongCompletedHistoryIsReused() {
        val old = (1..63).map { TimelineItem("i$it", "t$it", "agentMessage", "中文😀".repeat(10_000), true) }
        val current = Conversation("thread", old + TimelineItem("live", "turn", "agentMessage", "first"), activeTurn = "turn")
        val first = planConversationCache(null, current)
        val next = current.copy(items = old + current.items.last().copy(text = "first second"))
        val patch = planConversationCache(first.snapshot, next)
        assertEquals(setOf(next.items.last().identityKey), patch.changed.keys)
        assertNull(patch.header)
        assertFalse(patch.reset)
        assertTrue(patch.characters * 1_000 < first.characters)
        assertTrue(planConversationCache(patch.snapshot, next).empty)
        val disk = rows(first)
        val changed = patch.changed.map { (id, chunks) -> ConversationItemRow("host", "thread", id, 0, chunks.single()) }
        assertEquals(next, decodeStoredConversation(disk.copy(items = disk.items.filterNot { it.itemKey in patch.changed } + changed)))
    }

    @Test fun rollingWindowDeletesOnlyEvictedItemAndKeepsCursorRepairFlag() {
        val original = Conversation("thread", (1..64).map { TimelineItem("i$it", "t$it", "agentMessage", "$it", true) }, nextCursor = "before1")
        val first = planConversationCache(null, original)
        val next = planConversationCache(first.snapshot, original.copy(items = original.items + TimelineItem("i65", "t65", "agentMessage", "65")))
        assertEquals(listOf(original.items.first().identityKey), next.removed)
        assertEquals(1, next.changed.size)
        assertTrue(next.snapshot.historyTruncated)
        assertNull(next.snapshot.nextCursor)
        assertNotNull(next.header)
    }

    @Test fun largeUnicodeItemsRoundTripAndMissingChunksCannotBecomeWritableBaseline() {
        val original = Conversation("thread", listOf(TimelineItem("a", "t", "agentMessage", "中文😀".repeat(30_000), true)))
        val disk = rows(planConversationCache(null, original))
        assertTrue(disk.items.size > 2)
        val sqlite = disk.copy(items = disk.items.map { it.copy(json = it.json.toByteArray().toString(Charsets.UTF_8)) })
        assertEquals(original, decodeStoredConversation(sqlite))
        assertNull(decodeStoredConversation(sqlite.copy(items = sqlite.items.drop(1))))
        assertNull(decodeStoredConversation(sqlite.copy(items = sqlite.items.dropLast(1))))
    }

    @Test fun v3CacheMigratesLazilyWithoutChangingCompleteHistory() {
        val original = Conversation("thread", listOf(TimelineItem("a", "t", "agentMessage", "saved", true)), nextCursor = "older")
        val old = ConversationCacheRows(encodeConversationCache(original).mapIndexed { index, json ->
            ConversationChunkRow("host", "thread", index, json) }, emptyList())
        assertFalse(old.isIncremental())
        val restored = decodeStoredConversation(old)!!
        assertEquals(original, restored)
        val patch = planConversationCache(null, restored)
        assertTrue(patch.reset)
        assertEquals(original, decodeStoredConversation(rows(patch)))
    }
}
