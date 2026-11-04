package dev.remotegpt.app

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.remotegpt.client.ServerRequest
import dev.remotegpt.client.string

/** What the task notifier should change in the system tray. Pure data, so the diffing is unit-testable. */
internal sealed interface TaskNotice {
    val tag: String

    data class Result(val host: String, val thread: String, val title: String, val hostName: String,
        val failed: Boolean) : TaskNotice {
        override val tag get() = resultTag(host, thread)
    }

    data class Attention(val host: String, val thread: String, val title: String, val hostName: String,
        val requestKey: String, val stableId: String, val question: Boolean, val detail: String?,
        /** A replay of an already announced request (for example after a reconnect): refresh without alerting. */
        val silent: Boolean) : TaskNotice {
        override val tag get() = attentionTag(host, stableId)
    }

    data class Dismiss(override val tag: String) : TaskNotice

    companion object {
        fun resultTag(host: String, thread: String) = "result:$host:$thread"
        fun attentionTag(host: String, stableId: String) = "attention:$host:$stableId"
    }
}

internal data class TaskSummary(val running: Int, val approvals: Int, val questions: Int)

internal fun taskSummary(state: WorkbenchState): TaskSummary {
    var running = 0; var approvals = 0; var questions = 0
    state.workspaces.values.forEach { ws ->
        running += ws.threads.count { ws.results[it.id]?.status == "inProgress" || it.status == "active" }
        approvals += ws.approvals.count { !it.submitted }
        questions += ws.pendingQuestions.count { it.request.isBlocking && !it.submitted }
    }
    return TaskSummary(running, approvals, questions)
}

/**
 * Turns workbench state changes into tray changes. It only announces transitions it has
 * watched happen: results restored from disk or found on the first sync are a baseline,
 * not a backlog. Requests replayed after a reconnect keep their notification (keyed by
 * thread and item, not by connection epoch) and only refresh the action target.
 */
internal class TaskNotificationTracker {
    /** host + thread -> "turn:status" of the last terminal result, "" when none. */
    private val results = mutableMapOf<String, String>()
    /** Attention tags already seen (posted, or shown in-app while foreground). */
    private val knownAttention = mutableMapOf<String, String>() // tag -> thread
    /** Attention tags currently in the tray -> request key their actions target. */
    private val postedAttention = mutableMapOf<String, String>()

    fun update(state: WorkbenchState, foreground: Boolean): List<TaskNotice> {
        val notices = mutableListOf<TaskNotice>()
        state.workspaces.forEach { (host, ws) ->
            val hostName = state.profiles.firstOrNull { it.id == host }?.name.orEmpty()
            val viewing = foreground && state.activeHost == host && state.tab == WorkspaceTab.SESSIONS
            fun title(thread: String) = (ws.threads.firstOrNull { it.id == thread }
                ?: ws.selectedSummary?.takeIf { it.id == thread })?.title.orEmpty()

            ws.results.forEach { (thread, receipt) ->
                val key = "$host\u0000$thread"
                val terminal = when {
                    receipt.status == "failed" && receipt.turnId.isNotBlank() -> "${receipt.turnId}:failed"
                    receipt.status == "completed" && receipt.turnId == receipt.completedTurnId && receipt.unread -> "${receipt.completedTurnId}:completed"
                    else -> ""
                }
                val previous = results.put(key, terminal)
                val tag = TaskNotice.resultTag(host, thread)
                when {
                    previous == null -> Unit // Baseline: restored or first synchronized.
                    terminal.isNotEmpty() && terminal != previous && !foreground ->
                        notices += TaskNotice.Result(host, thread, title(thread), hostName, failed = terminal.endsWith(":failed"))
                    terminal.isEmpty() && previous?.isNotEmpty() == true -> notices += TaskNotice.Dismiss(tag)
                }
                if (viewing && ws.selectedThread == thread && terminal.isNotEmpty()) notices += TaskNotice.Dismiss(tag)
            }

            val current = buildList {
                ws.approvals.filterNot { it.submitted }.forEach { add(it.request to false) }
                ws.pendingQuestions.filter { it.request.isBlocking && !it.submitted }.forEach { add(it.request to true) }
            }
            val currentTags = mutableSetOf<String>()
            current.forEach { (request, question) ->
                val stableId = stableRequestId(request)
                val tag = TaskNotice.attentionTag(host, stableId)
                currentTags += tag
                val thread = request.threadId
                val inView = viewing && ws.selectedThread == thread
                val notice = TaskNotice.Attention(host, thread, title(thread), hostName, request.key, stableId,
                    question, requestDetail(request, question), silent = tag in knownAttention)
                when {
                    inView -> {
                        knownAttention[tag] = thread
                        if (postedAttention.remove(tag) != null) notices += TaskNotice.Dismiss(tag)
                    }
                    tag !in knownAttention -> {
                        knownAttention[tag] = thread
                        if (!foreground) { postedAttention[tag] = request.key; notices += notice }
                    }
                    postedAttention[tag]?.let { it != request.key } == true -> {
                        postedAttention[tag] = request.key
                        notices += notice
                    }
                }
            }
            // Absence is authoritative only after this thread has resubscribed and its
            // replay/history events have been applied, not merely when the transport is online.
            if (ws.connection == ConnectionStatus.ONLINE) {
                val prefix = TaskNotice.attentionTag(host, "")
                knownAttention.filter { (tag, thread) -> tag.startsWith(prefix) && tag !in currentTags &&
                    thread !in ws.attentionSyncingThreads }.keys.toList().forEach { tag ->
                    knownAttention -= tag
                    if (postedAttention.remove(tag) != null) notices += TaskNotice.Dismiss(tag)
                }
            }
        }
        return notices
    }

    companion object {
        /** Thread + item identify a request across reconnects; the JSON-RPC id and epoch do not. */
        fun stableRequestId(request: ServerRequest): String {
            val item = request.params.string("itemId")
            return if (request.threadId.isNotBlank() && !item.isNullOrBlank()) "${request.threadId}:$item" else request.key
        }

        fun requestDetail(request: ServerRequest, question: Boolean): String? =
            if (question) request.userQuestions?.firstOrNull()?.question
            else request.command ?: request.reason
    }
}

/** Posts tray changes. Channels: results (default), attention (high), background connection (low). */
internal class TaskNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun apply(notices: List<TaskNotice>) {
        if (notices.isEmpty()) return
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notices.forEach { notice ->
            when (notice) {
                is TaskNotice.Dismiss -> manager.cancel(notice.tag, NOTIFICATION_ID)
                is TaskNotice.Result -> if (allowed) post(notice.tag, result(notice))
                is TaskNotice.Attention -> if (allowed) post(notice.tag, attention(notice))
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked in apply().
    private fun post(tag: String, notification: android.app.Notification) {
        runCatching { manager.notify(tag, NOTIFICATION_ID, notification) }
    }

    private fun base(channel: String, tag: String, host: String, thread: String, hostName: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setSubText(hostName.takeIf { it.isNotBlank() })
            .setContentIntent(openThreadIntent(context, tag, host, thread))

    private fun result(notice: TaskNotice.Result) =
        base(CHANNEL_RESULTS, notice.tag, notice.host, notice.thread, notice.hostName)
            .setContentTitle(notice.title.ifBlank { tr(R.string.notify_session_fallback) })
            .setContentText(tr(if (notice.failed) R.string.notify_task_failed else R.string.notify_task_completed))
            .setCategory(if (notice.failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .build()

    internal fun attention(notice: TaskNotice.Attention): android.app.Notification {
        val title = tr(if (notice.question) R.string.notify_question_title else R.string.notify_approval_title,
            notice.title.ifBlank { tr(R.string.notify_session_fallback) })
        val text = notice.detail?.takeIf { it.isNotBlank() }
            ?: tr(if (notice.question) R.string.notify_question_fallback else R.string.notify_approval_fallback)
        return base(CHANNEL_ATTENTION, notice.tag, notice.host, notice.thread, notice.hostName)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(notice.silent)
            .apply {
                // Questions need typed or multi-choice answers; they open the conversation instead.
                if (!notice.question) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        addAction(NotificationCompat.Action.Builder(0, tr(R.string.ui_allow_once), approvalIntent(notice, "accept"))
                            .setAuthenticationRequired(true).build())
                        addAction(NotificationCompat.Action.Builder(0, tr(R.string.ui_approval_decline_continue), approvalIntent(notice, "decline"))
                            .setAuthenticationRequired(true).build())
                    } else {
                        addAction(0, tr(R.string.notify_review_approval),
                            openThreadIntent(context, notice.tag, notice.host, notice.thread))
                    }
                }
            }
            .build()
    }

    private fun approvalIntent(notice: TaskNotice.Attention, decision: String): PendingIntent {
        val intent = Intent(context, TaskNotificationReceiver::class.java)
            .setAction(ACTION_APPROVE)
            .putExtra(EXTRA_HOST, notice.host)
            .putExtra(EXTRA_REQUEST, notice.requestKey)
            .putExtra(EXTRA_DECISION, decision)
            .putExtra(EXTRA_TAG, notice.tag)
        return PendingIntent.getBroadcast(context, "${notice.tag}:$decision".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_RESULTS = "task_results"
        const val CHANNEL_ATTENTION = "task_attention"
        const val NOTIFICATION_ID = 41
        private const val GROUP = "ghub_tasks"
        const val ACTION_APPROVE = "dev.remotegpt.app.APPROVE_FROM_NOTIFICATION"
        const val EXTRA_HOST = "dev.remotegpt.app.extra.HOST"
        const val EXTRA_THREAD = "dev.remotegpt.app.extra.THREAD"
        const val EXTRA_REQUEST = "dev.remotegpt.app.extra.REQUEST"
        const val EXTRA_DECISION = "dev.remotegpt.app.extra.DECISION"
        const val EXTRA_TAG = "dev.remotegpt.app.extra.TAG"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, tr(R.string.notify_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr(R.string.notify_channel_results_description) })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ATTENTION, tr(R.string.notify_channel_attention),
                NotificationManager.IMPORTANCE_HIGH).apply { description = tr(R.string.notify_channel_attention_description) })
        }

        fun openThreadIntent(context: Context, tag: String, host: String, thread: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_THREAD, thread)
            return PendingIntent.getActivity(context, tag.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** Handles "Allow once" / "Decline" from an approval notification without opening the app. */
class TaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskNotifier.ACTION_APPROVE) return
        // Defense in depth: direct background approval is supported only with authenticated actions.
        if (Build.VERSION.SDK_INT < 31 || context.getSystemService(KeyguardManager::class.java).isDeviceLocked ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val host = intent.getStringExtra(TaskNotifier.EXTRA_HOST) ?: return
        val key = intent.getStringExtra(TaskNotifier.EXTRA_REQUEST) ?: return
        val decision = intent.getStringExtra(TaskNotifier.EXTRA_DECISION)?.takeIf { it in setOf("accept", "decline") } ?: return
        val workbench = (context.applicationContext as RemoteApplication).workbench()
        // On success the request becomes "submitted" and the tracker dismisses the notification;
        // the daemon's serverRequest/resolved confirms it. Offline, the request stays pending remotely.
        if (!workbench.approveFromNotification(host, key, decision)) {
            Toast.makeText(context, tr(R.string.notify_approval_unavailable), Toast.LENGTH_LONG).show()
        }
    }
}
