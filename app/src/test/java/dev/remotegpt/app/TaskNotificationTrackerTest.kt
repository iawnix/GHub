package dev.remotegpt.app

import dev.remotegpt.client.HostProfile
import dev.remotegpt.client.ServerRequest
import dev.remotegpt.client.ThreadSummary
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationTrackerTest {
    private val host = "h1"
    private val thread = ThreadSummary("t1", "Fix reconnect", "/home/dev/app")

    private fun state(
        results: Map<String, ResultReceipt> = emptyMap(),
        approvals: List<PendingApproval> = emptyList(),
        questions: List<PendingQuestions> = emptyList(),
        connection: ConnectionStatus = ConnectionStatus.ONLINE,
        selected: String? = null,
    ) = WorkbenchState(
        profiles = listOf(HostProfile(id = host, name = "dev box")), activeHost = host,
        workspaces = mapOf(host to HostWorkspace(connection = connection, threads = listOf(thread), results = results,
            approvals = approvals, pendingQuestions = questions, selectedThread = selected)),
    )

    private fun approval(epoch: String, id: Int = 7, item: String = "item-1", submitted: Boolean = false) = PendingApproval(
        ServerRequest(epoch, JsonPrimitive(id), "item/commandExecution/requestApproval", buildJsonObject {
            put("threadId", "t1"); put("turnId", "turn-1"); put("itemId", item); put("command", "./gradlew test")
        }), submitted = submitted)

    private val running = ResultReceipt(turnId = "turn-1", status = "inProgress")
    private val completedUnread = ResultReceipt(turnId = "turn-1", status = "completed", completedTurnId = "turn-1")
    private val completedRead = completedUnread.copy(seenTurnId = "turn-1")

    @Test fun restoredUnreadResultsAreABaselineNotABacklog() {
        val tracker = TaskNotificationTracker()
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(), foreground = false))
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(mapOf("t1" to completedUnread)), foreground = false))
    }

    @Test fun completionInTheBackgroundIsAnnouncedOnceAndDismissedWhenRead() {
        val tracker = TaskNotificationTracker()
        tracker.update(state(mapOf("t1" to running)), foreground = false)
        val notices = tracker.update(state(mapOf("t1" to completedUnread)), foreground = false)
        assertEquals(listOf(TaskNotice.Result(host, "t1", "Fix reconnect", "dev box", failed = false)), notices)
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(mapOf("t1" to completedUnread)), foreground = false))
        assertEquals(listOf(TaskNotice.Dismiss(TaskNotice.resultTag(host, "t1"))),
            tracker.update(state(mapOf("t1" to completedRead)), foreground = true))
    }

    @Test fun foregroundCompletionStaysInTheApp() {
        val tracker = TaskNotificationTracker()
        tracker.update(state(mapOf("t1" to running)), foreground = true)
        assertTrue(tracker.update(state(mapOf("t1" to completedUnread)), foreground = true).none { it is TaskNotice.Result })
    }

    @Test fun failureIsAnnounced() {
        val tracker = TaskNotificationTracker()
        tracker.update(state(mapOf("t1" to running)), foreground = false)
        val notices = tracker.update(state(mapOf("t1" to running.copy(status = "failed"))), foreground = false)
        assertEquals(listOf(TaskNotice.Result(host, "t1", "Fix reconnect", "dev box", failed = true)), notices)
    }

    @Test fun approvalAlertsOnceSurvivesReconnectAndClosesWhenResolved() {
        val tracker = TaskNotificationTracker()
        tracker.update(state(), foreground = false)
        val first = tracker.update(state(approvals = listOf(approval("e1"))), foreground = false).single() as TaskNotice.Attention
        assertEquals("e1:7", first.requestKey)
        assertEquals("./gradlew test", first.detail)
        assertEquals(false, first.silent)

        // Disconnected: the request is still pending on the daemon, so the notification stays.
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(connection = ConnectionStatus.RECONNECTING), foreground = false))

        // Replayed on a new connection: same notification, silently retargeted to the new request key.
        val replay = tracker.update(state(approvals = listOf(approval("e2", id = 3))), foreground = false).single() as TaskNotice.Attention
        assertEquals(first.tag, replay.tag)
        assertEquals("e2:3", replay.requestKey)
        assertEquals(true, replay.silent)

        // Submitted, then resolved while online: dismissed.
        assertEquals(listOf(TaskNotice.Dismiss(first.tag)),
            tracker.update(state(approvals = listOf(approval("e2", id = 3, submitted = true))), foreground = false))
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(), foreground = false))
    }

    @Test fun approvalForTheOpenConversationIsNotPosted() {
        val tracker = TaskNotificationTracker()
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(approvals = listOf(approval("e1")), selected = "t1"), foreground = true))
        // Leaving the app afterwards does not re-alert a request the user has already seen.
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(approvals = listOf(approval("e1")), selected = "t1"), foreground = false))
    }

    @Test fun nonBlockingQuestionsAreIgnoredAndSummaryCountsWork() {
        val question = PendingQuestions(ServerRequest("e1", JsonPrimitive(9), "item/tool/requestUserInput", buildJsonObject {
            put("threadId", "t1"); put("turnId", "turn-1"); put("itemId", "q-1"); put("isBlocking", false)
        }))
        val tracker = TaskNotificationTracker()
        tracker.update(state(), foreground = false)
        assertEquals(emptyList<TaskNotice>(), tracker.update(state(questions = listOf(question)), foreground = false))
        assertEquals(TaskSummary(running = 1, approvals = 1, questions = 0),
            taskSummary(state(mapOf("t1" to running), approvals = listOf(approval("e1")), questions = listOf(question))))
    }
}
