package dev.remotegpt.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class Publication { IMMEDIATE, STREAM, HIDDEN }

/** Authoritative state never waits for rendering. A timer publishes the latest
 * value, not a captured delta; urgent changes cancel it and cannot be reverted. */
internal class StatePublication<S>(
    private val source: StateFlow<S>, private val scope: CoroutineScope,
    private val classify: (S, S) -> Publication,
    private val onPublish: () -> Unit = {}, private val intervalMs: Long = 150,
) {
    private val published = MutableStateFlow(source.value)
    val state = published.asStateFlow()
    private var pending: Job? = null
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = source.value
            source.collect { next ->
                val kind = classify(previous, next)
                previous = next
                if (published.value == next) return@collect
                when (kind) {
                    Publication.IMMEDIATE -> flush()
                    Publication.HIDDEN -> Unit // Another thread must not cancel a visible stream's timer.
                    Publication.STREAM -> if (pending == null) {
                        pending = scope.launch {
                            delay(intervalMs)
                            pending = null
                            publish()
                        }
                    }
                }
            }
        }
    }
    private fun publish() {
        if (published.value != source.value) { published.value = source.value; onPublish() }
    }
    fun flush() { pending?.cancel(); pending = null; publish() }
}

internal fun publicationFor(before: WorkbenchState, after: WorkbenchState, foreground: Boolean): Publication {
    // Compare all control state while ignoring body maps. No text serialization or
    // scanning of unchanged message contents is needed for this classification.
    if (before.copy(workspaces = after.workspaces) != after || before.workspaces.keys != after.workspaces.keys)
        return Publication.IMMEDIATE
    for ((host, current) in after.workspaces) {
        val old = before.workspaces.getValue(host)
        if (old.copy(conversations = current.conversations) != current || old.conversations.keys != current.conversations.keys)
            return Publication.IMMEDIATE
    }
    return if (foreground && after.tab == WorkspaceTab.SESSIONS && after.workspace.selectedThread != null &&
        before.conversation !== after.conversation) Publication.STREAM else Publication.HIDDEN
}
