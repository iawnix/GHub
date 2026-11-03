@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.trq
import dev.remotegpt.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.client.*

/** One compact turn: prompt, process disclosure, final response, file summary. */
@Composable fun TurnContent(
    items: List<TimelineItem>,
    active: Boolean,
    preview: (String) -> Unit,
    turnStatus: String? = null,
    syncing: Boolean = false,
    connected: Boolean = true,
    imagePreviews: Map<String, String> = emptyMap(),
    imageLoading: Set<String> = emptySet(),
    onLoadImage: (String) -> Unit = {},
) {
    val answer = items.lastOrNull { it.kind == "agentMessage" }
    val files = items.filter { it.kind == "fileChange" }
    val images = items.filter { it.kind == "imageView" }
    val questions = items.filter { it.questions.isNotEmpty() && it !== answer }
    val steps = items.filter { it.kind != "userMessage" && it.kind != "fileChange" && it.kind != "imageView" && it.questions.isEmpty() && it !== answer }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.filter { it.kind == "userMessage" }.forEach { item ->
            key(item.identityKey) {
                TimelineCard(item, preview, imagePreviews = imagePreviews, imageLoading = imageLoading, onLoadImage = onLoadImage)
            }
        }
        questions.forEach { question ->
            key(question.identityKey) { TimelineCard(question, preview) }
        }
        if (steps.isNotEmpty() || active) ExecutionSummary(steps, active, files.size, images.size, syncing, connected, preview, turnStatus,
            imagePreviews, imageLoading, onLoadImage)
        images.forEach { image ->
            key(image.identityKey) {
                TimelineCard(image, preview, imagePreviews = imagePreviews, imageLoading = imageLoading, onLoadImage = onLoadImage)
            }
        }
        answer?.let {
            TimelineCard(
                it,
                preview,
                assistantOutput = true,
                assistantSyncing = syncing,
                assistantConnected = connected,
                assistantShowSync = !active && steps.isEmpty(),
                imagePreviews = imagePreviews,
                imageLoading = imageLoading,
                onLoadImage = onLoadImage,
            )
        }
        if (files.isNotEmpty()) FileSummary(files, preview)
        if (answer?.complete == true && !active) ReplyActions(answer.text, completed = turnStatus == "completed")
    }
}

@Composable private fun ExecutionSummary(
    steps: List<TimelineItem>,
    active: Boolean,
    fileCount: Int,
    imageCount: Int,
    syncing: Boolean,
    connected: Boolean,
    preview: (String) -> Unit,
    turnStatus: String?,
    imagePreviews: Map<String, String>,
    imageLoading: Set<String>,
    onLoadImage: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val commands = steps.count { it.kind == "commandExecution" }
    val unknown = steps.any { it.kind !in setOf("agentMessage", "commandExecution", "reasoning", "plan") }
    val failures = steps.filter { it.failed }
    val status = when {
        active -> TaskStatus.RUNNING
        failures.isNotEmpty() -> TaskStatus.FAILED
        turnStatus == "interrupted" -> TaskStatus.INTERRUPTED
        steps.all { itemTaskStatus(it) == TaskStatus.COMPLETED } -> TaskStatus.COMPLETED
        else -> TaskStatus.UNKNOWN
    }
    val summary = when {
        failures.isNotEmpty() -> tr(R.string.ui_failed_operations_view_details, failures.size)
        unknown -> tr(R.string.ui_execution_details_includes_unsupported_types)
        active -> tr(R.string.ui_output_receiving)
        commands > 0 -> tr(R.string.ui_commands_run_view_details, commands)
        else -> tr(R.string.ui_events_view_details, steps.size)
    }
    val meta = buildList {
        if (steps.isNotEmpty()) add(trq(R.plurals.ui_output_events, steps.size))
        if (commands > 0) add(trq(R.plurals.ui_output_commands, commands))
        if (fileCount > 0) add(trq(R.plurals.ui_output_files, fileCount))
        if (imageCount > 0) add(trq(R.plurals.ui_output_images, imageCount))
    }.joinToString(" · ")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) tr(R.string.ui_collapse_execution_details) else tr(R.string.ui_view_execution_details)) { expanded = !expanded }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskStatusIcon(status)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = if (failures.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutputSyncBadge(active = active, syncing = syncing, connected = connected)
                Icon(if (expanded) WorkbenchIcons.ExpandLess else WorkbenchIcons.ChevronRight,
                    if (expanded) tr(R.string.ui_collapse) else tr(R.string.ui_expand), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (steps.isNotEmpty()) OutputEventRail(steps)
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { item ->
                    key(item.identityKey) {
                        TimelineCard(item, preview, imagePreviews = imagePreviews, imageLoading = imageLoading, onLoadImage = onLoadImage)
                    }
                }
            }
        }
    }
}

/** A compact visual trace of the last remote events. It mirrors event ordering
 * without exposing transport internals or forcing the user to open raw logs. */
@Composable private fun OutputEventRail(items: List<TimelineItem>) {
    val visible = items.takeLast(4)
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { item ->
            val status = itemTaskStatus(item)
            val tint = taskStatusColor(status)
            Surface(color = tint.copy(alpha = .08f), shape = RoundedCornerShape(50)) {
                Row(
                    Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TaskStatusIcon(status, Modifier.size(13.dp))
                    Text(eventShortLabel(item.kind), style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
        if (items.size > visible.size) {
            Text(trq(R.plurals.ui_output_more_events, items.size - visible.size),
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun eventShortLabel(kind: String): String = tr(when (kind) {
    "commandExecution" -> R.string.ui_output_command_short
    "fileChange" -> R.string.ui_output_files_short
    "reasoning" -> R.string.ui_output_reasoning_short
    "plan" -> R.string.ui_output_plan_short
    "agentMessage" -> R.string.ui_output_reply_short
    else -> R.string.ui_output_event_short
})

@Composable internal fun FileSummary(items: List<TimelineItem>, preview: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val edits = remember(items) { items.flatMap { it.fileEdits } }
    val grouped = remember(edits) { edits.groupBy { it.path }.entries.toList() }
    val repeatedNames = remember(grouped) { grouped.groupingBy { it.key.substringAfterLast('/') }.eachCount().filterValues { it > 1 }.keys }
    val counts = remember(edits) { edits.map { it.lineCounts } }
    val failed = items.any { it.failed }
    val known = !failed && edits.isNotEmpty() && items.all { it.fileEdits.isNotEmpty() } && counts.all { it != null }
    val total = if (known) counts.sumOf { it!!.first } to counts.sumOf { it!!.second } else null
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(role = Role.Button) { haptic(false); expanded = !expanded }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed || items.any { !it.complete }) TaskStatusIcon(if (failed) TaskStatus.FAILED else TaskStatus.RUNNING)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (failed) tr(R.string.ui_file_changes_failed) else if (grouped.isNotEmpty()) trq(R.plurals.chat_turn_files, grouped.size) else tr(R.string.ui_file_change_details),
                        style = MaterialTheme.typography.labelMedium)
                    ChangeCounts(total)
                }
                Icon(if (expanded) WorkbenchIcons.ExpandLess else WorkbenchIcons.ExpandMore, if (expanded) tr(R.string.ui_collapse) else tr(R.string.ui_expand),
                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                (if (showAll) grouped else grouped.take(3)).forEach { (path, changes) -> key(path) { FileDiffRow(path, changes, path.substringAfterLast('/') in repeatedNames) } }
                if (grouped.size > 3) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    TextButton(onClick = { haptic(false); showAll = !showAll }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (showAll) tr(R.string.chat_fewer_files) else trq(R.plurals.chat_more_files, grouped.size - 3), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Icon(if (showAll) WorkbenchIcons.ExpandLess else WorkbenchIcons.ChevronRight, null, Modifier.size(16.dp))
                    }
                }
                items.filter { it.fileEdits.isEmpty() }.forEach { key(it.identityKey) { Box(Modifier.padding(horizontal = 12.dp)) { TimelineCard(it, preview) } } }
            }
        }
    }
}

@Composable private fun ChangeCounts(counts: Pair<Int, Int>?) {
    if (counts == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("+${counts.first}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = accentColor(WorkbenchAccent.TEAL))
        Text("−${counts.second}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun FileDiffRow(path: String, edits: List<FileEdit>, showParent: Boolean) {
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val largeText = LocalDensity.current.fontScale > 1.3f
    val counts = remember(edits) {
        val values = edits.map { it.lineCounts }
        if (values.any { it == null }) null else values.sumOf { it!!.first } to values.sumOf { it!!.second }
    }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr(R.string.chat_show_diff, path)) { haptic(false); expanded = !expanded }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(WorkbenchIcons.FileCode, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (showParent) path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (largeText) ChangeCounts(counts)
            }
            if (!largeText) ChangeCounts(counts)
            Icon(if (expanded) WorkbenchIcons.ExpandLess else WorkbenchIcons.ChevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            SelectionContainer { Text(path, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            DiffContent(edits.joinToString("\n\n") { it.diff.ifBlank { tr(R.string.ui_the_server_did_not_provide_a_diff) } })
        }
    }
}

@Composable private fun DiffContent(text: String) {
    CodeBlock(text, "diff", tr(R.string.ui_file_change_details), tr(R.string.ui_copy_diff), framed = false, initiallyWrapped = false)
}
