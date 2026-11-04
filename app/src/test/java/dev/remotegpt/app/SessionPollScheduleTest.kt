package dev.remotegpt.app

import org.junit.Assert.*
import org.junit.Test

class SessionPollScheduleTest {
    @Test fun subscribedStreamsUseWatchdogAndUnsubscribedActiveThreadsStayPrompt() {
        val schedule = SessionPollSchedule()
        val pushed = SessionPollSchedule.Target("push", selected = true, active = true, subscribed = true)
        val other = SessionPollSchedule.Target("cli", active = true)
        listOf(pushed, other).forEach { schedule.pushed(it.id, 0) }
        assertTrue(schedule.targets(listOf(pushed, other), 4_999, true).isEmpty())
        assertEquals(listOf(other), schedule.targets(listOf(pushed, other), 5_000, true))
        assertEquals(listOf(pushed, other), schedule.targets(listOf(pushed, other), 15_000, true))
        assertTrue(schedule.targets(listOf(pushed, other), 29_999, false).isEmpty())
        assertEquals(2, schedule.targets(listOf(pushed, other), 30_000, false).size)
    }

    @Test fun idleMinuteUsesTwoReadsInsteadOfTwentyAndBackgroundWaitsLonger() {
        val schedule = SessionPollSchedule()
        val idle = listOf(SessionPollSchedule.Target("idle", selected = true, subscribed = true))
        var reads = 0
        for (now in 0L until 60_000 step 250) {
            schedule.targets(idle, now, true).forEach {
                reads++; schedule.attempted(it.id, now); schedule.confirmed(it.id)
            }
        }
        assertEquals(2, reads)
        assertTrue(schedule.targets(idle, 89_999, true).isEmpty())
        assertEquals(idle, schedule.targets(idle, 90_000, true))
        schedule.hint("idle", 61_000, immediate = true)
        assertEquals(idle, schedule.targets(idle, 61_000, true))
    }

    @Test fun eventBurstsCoalesceButExplicitForegroundCheckIsImmediate() {
        val schedule = SessionPollSchedule()
        val target = SessionPollSchedule.Target("t", subscribed = true)
        schedule.pushed("t", 0)
        repeat(50) { schedule.hint("t", 1_000 + it * 10L) }
        assertTrue(schedule.targets(listOf(target), 1_749, true).isEmpty())
        assertEquals(listOf(target), schedule.targets(listOf(target), 1_750, true))
        schedule.pushed("t", 2_000)
        assertTrue(schedule.targets(listOf(target), 2_001, true).isEmpty())
        schedule.hint("t", 2_002, immediate = true)
        assertEquals(listOf(target), schedule.targets(listOf(target), 2_002, true))
    }

    @Test fun failuresBackOffEvenIfDiscoveryKeepsHinting() {
        val schedule = SessionPollSchedule()
        val target = listOf(SessionPollSchedule.Target("empty", active = true))
        schedule.attempted("empty", 0); schedule.retry("empty", 0)
        schedule.hint("empty", 100, immediate = true)
        assertTrue(schedule.targets(target, 4_999, true).isEmpty())
        assertEquals(target, schedule.targets(target, 5_000, true))
        schedule.attempted("empty", 5_000); schedule.retry("empty", 5_000)
        assertEquals(10_000L, schedule.delay(target, 5_000, true))
        schedule.pushed("empty", 6_000)
        assertEquals(5_000L, schedule.delay(target, 6_000, true))
    }

    @Test fun unloadedThreadsLeavePollingAfterTheirDiscoveryCheck() {
        val schedule = SessionPollSchedule()
        schedule.hint("unloaded", 0)
        assertEquals(setOf("unloaded"), schedule.pendingIds())
        schedule.attempted("unloaded", 750)
        schedule.retry("unloaded", 750)
        assertEquals(setOf("unloaded"), schedule.pendingIds())
        schedule.attempted("unloaded", 5_750)
        schedule.confirmed("unloaded")
        assertTrue(schedule.pendingIds().isEmpty())
        schedule.retain(emptySet())
        // A later summary change must still be discoverable.
        schedule.hint("unloaded", 10_000)
        assertEquals(setOf("unloaded"), schedule.pendingIds())
    }

    @Test fun backlogIsBoundedAndNeverBlocksSelectedOrNewlyDiscoveredThread() {
        val schedule = SessionPollSchedule()
        val old = (1..100).map { SessionPollSchedule.Target("old-$it") }
        val seen = mutableSetOf<String>()
        repeat(4) { tick ->
            val reads = schedule.reserve(old, emptySet(), tick.toLong(), true)
            assertEquals(2, reads.size)
            reads.forEach { schedule.attempted(it.id, tick.toLong()); schedule.confirmed(it.id); seen += it.id }
        }
        assertEquals(8, seen.size)
        assertTrue(schedule.reserve(old, emptySet(), 1_000, true).isEmpty())
        assertEquals(14_000L, schedule.delay(old, 1_000, true))
        val current = SessionPollSchedule.Target("selected", selected = true)
        assertEquals(listOf(current), schedule.reserve(old + current, emptySet(), 1_000, true))
        val fresh = SessionPollSchedule.Target("new-cli")
        schedule.hint(fresh.id, 15_000, immediate = true)
        assertEquals(fresh, schedule.reserve(old + fresh, emptySet(), 15_000, true).first())
        val busy = setOf("old-9", "old-10")
        assertTrue(schedule.reserve(old, busy, 15_001, true).isEmpty())
    }
}
