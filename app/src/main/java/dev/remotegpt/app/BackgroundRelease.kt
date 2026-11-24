package dev.remotegpt.app

import kotlinx.coroutines.*

/** A short Activity recreation or picker visit must not close the user's terminal. */
internal class BackgroundRelease(private val scope: CoroutineScope, private val release: () -> Unit) {
    private var pending: Job? = null
    fun update(active: Boolean) {
        if (active) {
            pending?.cancel()
            pending = null
        } else if (pending?.isActive != true) {
            lateinit var launched: Job
            launched = scope.launch {
                try {
                    delay(30_000)
                    release()
                } finally {
                    if (pending === launched) pending = null
                }
            }
            pending = launched
        }
    }
}
