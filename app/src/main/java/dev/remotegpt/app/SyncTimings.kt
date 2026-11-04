package dev.remotegpt.app

/** Aggregate local operation names only. No host, path, thread ID, arguments or message bodies. */
internal class SyncTimings(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Sample(var count: Long = 0, var total: Long = 0, var max: Long = 0)
    private class Counter {
        var total = 0L
        val seconds = LongArray(60) { Long.MIN_VALUE }
        val values = LongArray(60)
        fun add(value: Long, second: Long) {
            total += value
            val index = Math.floorMod(second, 60).toInt()
            if (seconds[index] != second) { seconds[index] = second; values[index] = 0 }
            values[index] += value
        }
        fun recent(second: Long) = values.indices.sumOf { if (second - seconds[it] in 0..59) values[it] else 0L }
    }
    private val samples = sortedMapOf<String, Sample>()
    private val counters = sortedMapOf<String, Counter>()
    @Synchronized fun add(operation: String, milliseconds: Long) {
        val sample = samples.getOrPut(operation) { Sample() }
        sample.count++
        sample.total += milliseconds.coerceAtLeast(0)
        sample.max = maxOf(sample.max, milliseconds)
        increment("operations.$operation")
    }
    @Synchronized fun increment(operation: String, value: Long = 1) {
        require(value >= 0)
        counters.getOrPut(operation) { Counter() }.add(value, now() / 1_000)
    }
    @Synchronized fun count(operation: String) = samples[operation]?.count ?: 0L
    @Synchronized fun total(operation: String) = counters[operation]?.total ?: 0L
    @Synchronized fun report(): String {
        val second = now() / 1_000
        return samples.entries.joinToString("\n") { (name, s) ->
            "$name count=${s.count} mean_ms=${s.total / s.count} max_ms=${s.max}"
        } + "\n\nCounters (last_60s uses whole monotonic seconds; RPC bytes exclude SSH/WebSocket framing):\n" +
            counters.entries.joinToString("\n") { (name, c) -> "$name total=${c.total} last_60s=${c.recent(second)}" }
    }
}
