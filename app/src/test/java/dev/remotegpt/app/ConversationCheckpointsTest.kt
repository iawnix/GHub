package dev.remotegpt.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCheckpointsTest {
    @Test fun continuousStreamIsSavedAtLeastEveryTwoSecondsAndQuietTailIsSaved() = runTest {
        var text = ""
        val writes = mutableListOf<Pair<Long, String>>()
        val checkpoints = ConversationCheckpoints<String>(this, { testScheduler.currentTime },
            { writes += testScheduler.currentTime to text }, { throw it })
        repeat(100) {
            text += "x"
            checkpoints.changed("thread")
            advanceTimeBy(75); runCurrent()
        }
        assertTrue(writes.size >= 3)
        assertTrue(writes.first().first <= 2_000)
        assertTrue(writes.zipWithNext().all { (a, b) -> b.first - a.first <= 2_075 })
        advanceUntilIdle()
        assertEquals(text, writes.last().second)
        assertEquals(7_775L, writes.last().first)
    }

    @Test fun completionWritesImmediatelyAndDoesNotWaitForDebounce() = runTest {
        val writes = mutableListOf<Long>()
        val checkpoints = ConversationCheckpoints<String>(this, { testScheduler.currentTime },
            { writes += testScheduler.currentTime }, { throw it })
        checkpoints.changed("thread")
        advanceTimeBy(100)
        checkpoints.changed("thread", immediate = true)
        runCurrent()
        assertEquals(listOf(100L), writes)
        advanceUntilIdle()
        assertEquals(1, writes.size)
    }

    @Test fun deltasDoNotCancelSlowWriteAndCompletionQueuesLatestSnapshotAfterIt() = runTest {
        var text = "first"
        val writes = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val checkpoints = ConversationCheckpoints<String>(this, { testScheduler.currentTime }, { _ ->
            val snapshot = text
            if (++calls == 1) gate.await()
            writes += snapshot
        }, { throw it })
        checkpoints.changed("thread", immediate = true)
        runCurrent()
        repeat(30) { text += "x"; checkpoints.changed("thread"); advanceTimeBy(75) }
        checkpoints.changed("thread", immediate = true)
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", text), writes)
    }

    @Test fun cacheClearCancelsOldPendingWritesWithoutAffectingNewGeneration() = runTest {
        val writes = mutableListOf<String>()
        var text = "old"
        val checkpoints = ConversationCheckpoints<String>(this, { testScheduler.currentTime }, { writes += text }, { throw it })
        checkpoints.changed("thread")
        advanceTimeBy(200)
        checkpoints.clear()
        text = "new"
        checkpoints.changed("thread", immediate = true)
        advanceUntilIdle()
        assertEquals(listOf("new"), writes)
    }
}
