package dev.remotegpt.app

import org.junit.Assert.*
import org.junit.Test

class SyncTimingsTest {
    @Test fun rollingCountersExpireWhileLifetimeTotalsRemain() {
        var now = 0L
        val metrics = SyncTimings { now }
        metrics.increment("rpc_rx_bytes", 100)
        metrics.add("history_latest", 12)
        now = 59_000
        metrics.increment("rpc_rx_bytes", 20)
        assertTrue(metrics.report().contains("rpc_rx_bytes total=120 last_60s=120"))
        now = 60_000
        assertTrue(metrics.report().contains("rpc_rx_bytes total=120 last_60s=20"))
        now = 120_000
        assertTrue(metrics.report().contains("rpc_rx_bytes total=120 last_60s=0"))
        assertEquals(1L, metrics.count("history_latest"))
    }
}
