package dev.remotegpt.app

import dev.remotegpt.client.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatePublicationTest {
    @Test fun streamIsThrottledWithoutDebounceStarvationAndUrgentStateCannotBeReverted() = runTest {
        val source = MutableStateFlow(0)
        var renders = 0
        val ui = StatePublication(source, backgroundScope, { _, new -> if (new < 0) Publication.IMMEDIATE else Publication.STREAM }, { renders++ })
        repeat(30) { source.value++; runCurrent(); advanceTimeBy(10); runCurrent() }
        assertEquals(2, renders)
        assertEquals(source.value, ui.state.value)
        source.value = 31; runCurrent(); advanceTimeBy(50)
        source.value = -1; runCurrent()
        assertEquals(-1, ui.state.value)
        advanceTimeBy(200); runCurrent()
        assertEquals(-1, ui.state.value)
        assertEquals(3, renders)
    }

    @Test fun hiddenStreamDoesNotPublishUntilNavigationOrForegroundFlush() = runTest {
        val source = MutableStateFlow(0)
        val ui = StatePublication(source, backgroundScope, { _, _ -> Publication.HIDDEN })
        repeat(100) { source.value++; runCurrent(); advanceTimeBy(100) }
        assertEquals(0, ui.state.value)
        ui.flush()
        assertEquals(100, ui.state.value)
    }

    @Test fun hiddenUpdatesCannotStarveAnAlreadyScheduledVisibleUpdate() = runTest {
        val source = MutableStateFlow(0)
        val ui = StatePublication(source, backgroundScope, { _, new -> if (new == 1) Publication.STREAM else Publication.HIDDEN })
        source.value = 1; runCurrent()
        repeat(10) { advanceTimeBy(10); source.value++; runCurrent() }
        advanceTimeBy(50); runCurrent()
        assertEquals(source.value, ui.state.value)
    }

    @Test fun bodyClassificationIgnoresOtherThreadsButNeverDelaysNavigationOrRequests() {
        val body = Conversation("t", listOf(TimelineItem("a", "turn", "agentMessage", "a")))
        val before = WorkbenchState(activeHost = "host", workspaces = mapOf("host" to HostWorkspace(
            selectedThread = "t", conversations = mapOf("t" to body, "other" to body.copy(threadId = "other")))))
        fun changed(thread: String) = before.copy(workspaces = mapOf("host" to before.workspace.copy(
            conversations = before.workspace.conversations + (thread to body.copy(items = listOf(body.items.single().copy(text = "ab")))))))
        assertEquals(Publication.STREAM, publicationFor(before, changed("t"), true))
        assertEquals(Publication.HIDDEN, publicationFor(before, changed("t"), false))
        assertEquals(Publication.HIDDEN, publicationFor(before, changed("other"), true))
        assertEquals(Publication.IMMEDIATE, publicationFor(before, changed("t").copy(message = "Approval"), true))
        assertEquals(Publication.IMMEDIATE, publicationFor(before, before.copy(tab = WorkspaceTab.FILES), true))
    }
}
