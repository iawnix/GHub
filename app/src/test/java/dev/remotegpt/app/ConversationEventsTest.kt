package dev.remotegpt.app

import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEventsTest {
    private val original = Conversation("thread", listOf(TimelineItem("answer", "turn", "agentMessage", "Saved answer", true)))
    private fun status() = buildJsonObject { put("threadId", "thread"); putJsonObject("status") { put("type", "notLoaded") } }
    private fun delta() = buildJsonObject {
        put("threadId", "thread"); put("turnId", "new"); put("itemId", "next"); put("delta", "Next answer")
    }

    @Test fun statusAfterEvictionDoesNotCreateOrOverwriteBodyCache() = runTest {
        var disk = encodeConversationCache(original)
        val result = applyConversationNotification("thread", "thread/status/changed", status(),
            current = { null }, restore = { error("A status notification must not load or create a body") }, isCurrent = { true })
        if (result != null) disk = encodeConversationCache(result)
        assertNull(result)
        assertEquals(original, decodeConversationCache(disk))
    }

    @Test fun firstDeltaAfterEvictionWaitsForCacheThenRetainsBothBodies() = runTest {
        var disk = encodeConversationCache(original)
        var memory: Conversation? = null
        val read = CompletableDeferred<Unit>()
        val applied = async {
            applyConversationNotification("thread", "item/agentMessage/delta", delta(), current = { memory },
                restore = { read.await(); memory = decodeConversationCache(disk) }, isCurrent = { true })
        }
        runCurrent()
        assertFalse(applied.isCompleted)
        assertEquals(original, decodeConversationCache(disk))
        read.complete(Unit)
        memory = applied.await()
        disk = encodeConversationCache(memory!!)
        assertEquals(listOf("Saved answer", "Next answer"), decodeConversationCache(disk)!!.items.map { it.text })
    }

    @Test fun diskReadFailureAndIncompleteBaselineCannotOverwriteDurableMessages() = runTest {
        val disk = encodeConversationCache(original)
        try {
            applyConversationNotification("thread", "item/agentMessage/delta", delta(), current = { null },
                restore = { throw java.io.IOException("fixture") }, isCurrent = { true })
            fail("Expected failed hydration")
        } catch (_: java.io.IOException) { }
        assertEquals(original, decodeConversationCache(disk))
        try { encodeConversationCache(Conversation("thread", baselineLoaded = false)); fail("Must reject event-only bodies") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun reconnectDuringCacheReadDiscardsOldConnectionDelta() = runTest {
        var memory: Conversation? = null
        var connected = true
        val read = CompletableDeferred<Unit>()
        val applied = async {
            applyConversationNotification("thread", "item/agentMessage/delta", delta(), current = { memory },
                restore = { read.await(); memory = original }, isCurrent = { connected })
        }
        runCurrent()
        connected = false
        read.complete(Unit)
        assertNull(applied.await())
        assertEquals(original, memory)
    }
}
