package dev.remotegpt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import dev.remotegpt.client.TimelineItem

internal enum class TaskStatus { RUNNING, COMPLETED, FAILED, INTERRUPTED, APPROVAL, QUESTION, UNKNOWN }

internal fun taskStatus(status: String?, active: Boolean = false, approval: Boolean = false, question: Boolean = false): TaskStatus? = when {
    approval -> TaskStatus.APPROVAL
    question -> TaskStatus.QUESTION
    active || status in setOf("inProgress", "active") -> TaskStatus.RUNNING
    status == "completed" -> TaskStatus.COMPLETED
    status in setOf("failed", "declined") -> TaskStatus.FAILED
    status == "interrupted" -> TaskStatus.INTERRUPTED
    else -> null // Idle, offline and missing metadata are not successful executions.
}

internal fun itemTaskStatus(item: TimelineItem): TaskStatus = when {
    item.failed -> TaskStatus.FAILED
    item.complete && item.executionStatus == "inProgress" -> TaskStatus.UNKNOWN
    item.executionStatus != null -> taskStatus(item.executionStatus) ?: TaskStatus.UNKNOWN
    !item.complete -> TaskStatus.RUNNING
    item.kind in setOf("agentMessage", "reasoning", "plan", "imageView", "contextCompaction") -> TaskStatus.COMPLETED
    else -> TaskStatus.UNKNOWN
}

internal fun taskStatusLabel(status: TaskStatus): String = tr(when (status) {
    TaskStatus.RUNNING -> R.string.task_running
    TaskStatus.COMPLETED -> R.string.task_completed
    TaskStatus.FAILED -> R.string.task_failed
    TaskStatus.INTERRUPTED -> R.string.task_interrupted
    TaskStatus.APPROVAL -> R.string.ui_waiting_for_approval
    TaskStatus.QUESTION -> R.string.ui_question_waiting
    TaskStatus.UNKNOWN -> R.string.task_unknown
})

/**
 * Task state and transport state are deliberately separate. A completed turn
 * may still be reconciling its authoritative history, while a live turn can
 * already be receiving deltas. Keeping this badge next to the task summary
 * makes that distinction visible without adding a second progress spinner.
 */
@Composable internal fun OutputSyncBadge(
    active: Boolean,
    syncing: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val (icon, label, tint) = when {
        syncing -> Triple(WorkbenchIcons.Refresh, tr(R.string.ui_output_reconciling), accentColor(WorkbenchAccent.BLUE))
        !connected -> Triple(WorkbenchIcons.OutputPending, tr(R.string.ui_output_waiting_to_sync), MaterialTheme.colorScheme.onSurfaceVariant)
        active -> Triple(WorkbenchIcons.OutputLive, tr(R.string.ui_output_live), MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(WorkbenchIcons.OutputSynced, tr(R.string.ui_output_synced), MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StatusIndicator(icon, label, tint, modifier, showLabel = syncing || !connected)
}

@Composable internal fun taskStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.APPROVAL, TaskStatus.QUESTION -> accentColor(WorkbenchAccent.AMBER)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A fixed footprint avoids row movement when the outcome changes. */
@Composable internal fun TaskStatusIcon(status: TaskStatus, modifier: Modifier = Modifier, label: String? = taskStatusLabel(status)) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val tint = taskStatusColor(status)
    Box(modifier.size(16.dp).clearAndSetSemantics { if (label != null) contentDescription = label }, contentAlignment = Alignment.Center) {
        if (status == TaskStatus.RUNNING && motionScale > 0f) {
            CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 1.5.dp)
        } else Icon(when (status) {
            TaskStatus.RUNNING, TaskStatus.UNKNOWN -> WorkbenchIcons.TaskPending
            TaskStatus.COMPLETED -> WorkbenchIcons.TaskDone
            TaskStatus.FAILED -> WorkbenchIcons.TaskError
            TaskStatus.INTERRUPTED -> WorkbenchIcons.StopCircle
            TaskStatus.APPROVAL -> WorkbenchIcons.FrontHand
            TaskStatus.QUESTION -> WorkbenchIcons.ChatBubbleOutline
        }, null, Modifier.size(16.dp), tint = tint)
    }
}

@Composable internal fun TaskStatusLabel(status: TaskStatus, modifier: Modifier = Modifier, text: String = taskStatusLabel(status), showLabel: Boolean = status != TaskStatus.COMPLETED) {
    if (status == TaskStatus.COMPLETED && !showLabel) {
        StatusIndicator(WorkbenchIcons.TaskDone, text, taskStatusColor(status), modifier)
        return
    }
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskStatusIcon(status, label = null)
        Text(text, style = MaterialTheme.typography.bodySmall, color = taskStatusColor(status))
    }
}
