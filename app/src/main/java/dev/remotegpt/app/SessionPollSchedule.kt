package dev.remotegpt.app

/** Connection-local deadlines. Push supplies state; reads are bounded reconciliation. */
internal class SessionPollSchedule {
    data class Target(val id: String, val selected: Boolean = false, val active: Boolean = false, val subscribed: Boolean = false)
    private data class Entry(var checked: Long? = null, var idleChecks: Int = 0, var failures: Int = 0,
        var retryAt: Long? = null, var hintAt: Long? = null)
    private var idleBatchAt = Long.MIN_VALUE
    private var idleBudget = 8
    private val entries = mutableMapOf<String, Entry>()
    private fun entry(id: String) = entries.getOrPut(id) { Entry() }

    fun hint(id: String, now: Long, immediate: Boolean = false) {
        val e = entry(id)
        val due = now + if (immediate) 0 else 750
        e.hintAt = minOf(e.hintAt ?: due, due)
    }
    fun pushed(id: String, now: Long) {
        entry(id).apply { checked = now; idleChecks = 0; failures = 0; retryAt = null; hintAt = null }
    }
    fun attempted(id: String, now: Long) { entry(id).apply { checked = now; hintAt = null } }
    fun confirmed(id: String, changed: Boolean = false) {
        entry(id).apply { retryAt = null; failures = 0; idleChecks = if (changed) 0 else (idleChecks + 1).coerceAtMost(2) }
    }
    fun retry(id: String, now: Long) {
        entry(id).apply {
            failures = (failures + 1).coerceAtMost(5)
            retryAt = now + minOf(5_000L shl (failures - 1), 60_000L)
        }
    }
    private fun due(target: Target, foreground: Boolean): Long {
        val e = entry(target.id)
        // Discovery hints cannot bypass failure backoff on a broken/empty thread.
        e.retryAt?.let { return it }
        e.hintAt?.let { return it }
        val interval = when {
            !foreground -> if (target.active) 30_000L else 60_000L
            target.active -> if (target.subscribed) 15_000L else 5_000L
            e.idleChecks >= 2 -> 60_000L
            else -> 30_000L
        }
        return e.checked?.plus(interval) ?: 0L
    }
    fun targets(candidates: List<Target>, now: Long, foreground: Boolean): List<Target> = candidates
        .filter { due(it, foreground) <= now }
        .sortedWith(compareBy<Target> { if (it.selected) 0 else if (it.active) 1 else 2 }
            .thenByDescending { entries[it.id]?.hintAt ?: Long.MIN_VALUE }
            .thenBy { entries[it.id]?.checked ?: Long.MIN_VALUE })

    private fun idleWindow(foreground: Boolean) = if (foreground) 15_000L else 30_000L
    private fun resetBudget(now: Long, foreground: Boolean) {
        if (idleBatchAt == Long.MIN_VALUE || now >= idleBatchAt + idleWindow(foreground)) {
            idleBatchAt = now; idleBudget = 8
        }
    }
    fun reserve(candidates: List<Target>, busy: Set<String>, now: Long, foreground: Boolean): List<Target> {
        resetBudget(now, foreground)
        val due = targets(candidates.filterNot { it.id in busy }, now, foreground)
        val activeSlots = (2 - candidates.count { !it.selected && it.active && it.id in busy }).coerceAtLeast(0)
        val idleSlots = (2 - candidates.count { !it.selected && !it.active && it.id in busy }).coerceAtLeast(0)
        val idle = due.filter { !it.selected && !it.active }.take(minOf(idleSlots, idleBudget))
        idleBudget -= idle.size
        return due.filter { it.selected }.take(1) + due.filter { !it.selected && it.active }.take(activeSlots) + idle
    }
    fun delay(candidates: List<Target>, now: Long, foreground: Boolean): Long {
        resetBudget(now, foreground)
        return candidates.minOfOrNull { target ->
            val deadline = if (!target.selected && !target.active && idleBudget == 0)
                maxOf(due(target, foreground), idleBatchAt + idleWindow(foreground)) else due(target, foreground)
            (deadline - now).coerceAtLeast(0)
        } ?: 60_000L
    }
    fun pendingIds(): Set<String> = entries.filterValues { it.hintAt != null || it.retryAt != null }.keys
    fun retain(ids: Set<String>) { entries.keys.retainAll(ids) }
}
