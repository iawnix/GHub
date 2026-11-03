package dev.remotegpt.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Call on the state dispatcher. New deltas reschedule a pending write, never
 * cancel a write already running. Continuous streams still get checkpoints. */
internal class ConversationCheckpoints<K>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val write: suspend (K) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val quietMs: Long = 350,
    private val maximumMs: Long = 2_000,
) {
    private class Pending(time: Long) {
        var firstDirty = time
        var changedAt = time
        var revision = 0L
        var immediate = false
        val wake = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job
    }
    private val pending = mutableMapOf<K, Pending>()

    fun changed(key: K, immediate: Boolean = false) {
        val time = now()
        val entry = pending[key] ?: Pending(time).also { fresh ->
            pending[key] = fresh
            fresh.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val wait = if (fresh.immediate) 0 else minOf(
                            fresh.changedAt + quietMs, fresh.firstDirty + maximumMs) - now()
                        if (wait > 0) {
                            withTimeoutOrNull(wait) { fresh.wake.receive() }
                            continue
                        }
                        val revision = fresh.revision
                        fresh.immediate = false
                        fresh.firstDirty = now()
                        write(key)
                        if (revision == fresh.revision) break
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { onFailure(e) }
                finally {
                    if (pending[key] === fresh) pending.remove(key)
                    fresh.wake.close()
                }
            }
        }
        entry.changedAt = time
        entry.revision++
        entry.immediate = entry.immediate || immediate
        entry.wake.trySend(Unit)
        entry.job.start()
    }

    fun cancel(key: K) { pending.remove(key)?.job?.cancel() }
    fun cancelMatching(predicate: (K) -> Boolean) { pending.keys.filter(predicate).forEach(::cancel) }
    fun clear() { pending.keys.toList().forEach(::cancel) }
}
