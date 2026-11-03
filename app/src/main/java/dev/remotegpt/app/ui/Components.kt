@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import dev.remotegpt.app.*
import dev.remotegpt.client.*
import java.io.File

@Composable fun EmptyPanel(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Icon(icon, null, Modifier.padding(18.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp)); Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp)); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(24.dp)); Button(onClick = onAction) { Text(action); Spacer(Modifier.width(8.dp)); Icon(WorkbenchIcons.ArrowForward, null, Modifier.size(18.dp)) } }
    }
}

@Composable fun StatusPill(status: ConnectionStatus) {
    val label = connectionLabel(status)
    val tint = when (status) {
        ConnectionStatus.ONLINE -> accentColor(WorkbenchAccent.TEAL)
        ConnectionStatus.CONNECTING, ConnectionStatus.CHECKING, ConnectionStatus.WAITING_NETWORK, ConnectionStatus.RECONNECTING, ConnectionStatus.SYNCING -> accentColor(WorkbenchAccent.BLUE)
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = tint.copy(alpha = 0.09f), contentColor = tint, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (status == ConnectionStatus.ONLINE) WorkbenchIcons.CloudDone else WorkbenchIcons.CloudOff, null, Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable fun InfoCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(top = 8.dp)) { Text(action) }
        }
    }
}

@Composable fun TimelineCard(
    item: TimelineItem,
    preview: (String) -> Unit,
    assistantOutput: Boolean = false,
    assistantSyncing: Boolean = false,
    assistantConnected: Boolean = true,
    assistantShowSync: Boolean = true,
    imagePreviews: Map<String, String> = emptyMap(),
    imageLoading: Set<String> = emptySet(),
    onLoadImage: (String) -> Unit = {},
) {
    val user = item.kind == "userMessage"
    val questionReply = remember(item.kind, item.text) { if (user) UserQuestions.parseReply(item.text) else null }
    val tool = item.kind !in setOf("userMessage", "agentMessage")
    // Tool output is secondary context. Keep command results, plans, reasoning and
    // failures compact by default; the final assistant answer stays open.
    var expanded by rememberSaveable(item.identityKey) { mutableStateOf(!tool || item.kind == "imageView") }
    Column(Modifier.fillMaxWidth().padding(start = if (user) 32.dp else 0.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        val content: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = if (user) 14.dp else 0.dp, vertical = if (user) 12.dp else 0.dp)) {
                if (assistantOutput) Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(WorkbenchIcons.AutoAwesome, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(tr(R.string.ui_output_assistant), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TaskStatusIcon(itemTaskStatus(item))
                    if (assistantShowSync) OutputSyncBadge(
                        active = !item.complete,
                        syncing = assistantSyncing,
                        connected = assistantConnected,
                    )
                }
                if (tool) Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) tr(R.string.ui_collapse_details) else tr(R.string.ui_expand_details)) { expanded = !expanded }.heightIn(min = 48.dp).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (item.kind) { "fileChange" -> WorkbenchIcons.Difference; "commandExecution" -> WorkbenchIcons.Terminal; "imageView" -> WorkbenchIcons.Image; else -> WorkbenchIcons.Checklist }, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when (item.kind) { "fileChange" -> tr(R.string.ui_file_changes); "commandExecution" -> tr(R.string.ui_command_execution); "imageView" -> tr(R.string.ui_image_view); "plan" -> tr(R.string.ui_execution_plan); "reasoning" -> tr(R.string.ui_reasoning_summary); else -> item.kind }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TaskStatusIcon(itemTaskStatus(item))

                    Icon(if (expanded) WorkbenchIcons.ExpandLess else WorkbenchIcons.ExpandMore, if (expanded) tr(R.string.ui_collapse) else tr(R.string.ui_expand), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    if (item.kind == "commandExecution") CommandExecutionContent(item)
                    else if (questionReply != null) QuestionReplyContent(questionReply)
                    else SelectionContainer {
                        if (item.kind == "agentMessage") key(item.identityKey) { StyledMarkdown(item.text.ifBlank { "…" }, item.complete) }
                        else Text(item.text.ifBlank { if (item.imagePaths.isEmpty()) tr(R.string.ui_running) else tr(R.string.ui_image_attachment) }, style = if (tool) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default,
                            modifier = Modifier.padding(bottom = if (tool) 14.dp else 0.dp))
                    }
                    item.questions.forEach { question ->
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(question.title, style = MaterialTheme.typography.bodyLarge)
                            question.options.forEach { option -> Text("• $option", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item.imagePaths.forEachIndexed { index, path ->
                        val imageName = path.substringAfterLast('/').ifBlank { tr(R.string.ui_image) }
                        val local = imagePreviews[path]
                        if (local != null) {
                            ImageThumbnail(
                                File(local), imageName,
                                Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 260.dp)
                                    .clickable(role = Role.Button, onClickLabel = tr(R.string.ui_view_image, index + 1)) { preview(path) },
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            )
                        } else {
                            Surface(
                                onClick = { if (path !in imageLoading) onLoadImage(path) },
                                enabled = path !in imageLoading,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (path in imageLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(WorkbenchIcons.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (path in imageLoading) tr(R.string.ui_image_preview_loading) else tr(R.string.ui_load_image_preview),
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(imageName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (user) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp),
            contentColor = MaterialTheme.colorScheme.onSurface, content = content)
        else content()
    }
}

/** Compact, neutral Markdown styling for the conversation stream.
 * The library defaults are tuned for documents and use display-sized headings;
 * chat answers need a smaller hierarchy and tighter blocks. */
@Composable private fun StyledMarkdown(content: String, complete: Boolean) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    val compact = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    Markdown(
        markdownState = rememberChatMarkdown(content, complete),
        components = remember { chatMarkdownComponents() },
        imageTransformer = remember { NoOpImageTransformerImpl() },
        loading = { Text(content, style = body) },
        error = { Text(content, style = body) },
        success = { parsed, components, modifier -> StableMarkdownContent(parsed, components, modifier) },
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(
            text = colors.onSurface,
            codeBackground = colors.surfaceContainerLow,
            inlineCodeBackground = colors.surfaceContainerLow,
            dividerColor = colors.outlineVariant,
            tableBackground = colors.surfaceContainerLow,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
            h2 = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
            h3 = body.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
            h4 = body.copy(fontWeight = FontWeight.SemiBold),
            h5 = body.copy(fontWeight = FontWeight.Medium),
            h6 = body.copy(fontWeight = FontWeight.Medium),
            text = body,
            code = compact.copy(fontFamily = FontFamily.Monospace, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight),
            inlineCode = compact.copy(fontFamily = FontFamily.Monospace),
            quote = compact.copy(color = colors.onSurfaceVariant),
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            table = compact,
        ),
        extendedSpans = markdownExtendedSpans {
            ExtendedSpans(
                RoundedCornerSpanPainter(
                    cornerRadius = 6.sp,
                    padding = RoundedCornerSpanPainter.TextPaddingValues(horizontal = 2.sp, vertical = 1.sp),
                    topMargin = 1.sp,
                    bottomMargin = 1.sp,
                )
            )
        },
        padding = markdownPadding(
            block = 8.dp,
            list = 3.dp,
            listItemTop = 2.dp,
            listItemBottom = 2.dp,
            listIndent = 16.dp,
            codeBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            blockQuote = PaddingValues(horizontal = 12.dp),
            blockQuoteText = PaddingValues(vertical = 4.dp),
            blockQuoteBar = PaddingValues.Absolute(left = 3.dp, top = 2.dp, right = 5.dp, bottom = 2.dp),
        ),
        dimens = markdownDimens(
            codeBackgroundCornerSize = 10.dp,
            blockQuoteThickness = 2.dp,
            tableCellPadding = 8.dp,
            tableCornerSize = 10.dp,
        ),
    )
}


@Composable fun ApprovalCard(pending: PendingApproval, enabled: Boolean = true, onDecision: (String) -> Unit) {
    val req = pending.request
    val haptic = rememberHaptic()
    WorkbenchSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(WorkbenchIcons.FrontHand, null, Modifier.size(20.dp), tint = accentColor(WorkbenchAccent.AMBER)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_approval_required), style = MaterialTheme.typography.titleMedium) }
            if (req.command != null) CodeBlock(req.command!!, "sh", tr(R.string.ui_output_command_short), tr(R.string.ui_copy_command))
            else SelectionContainer { Text(req.reason ?: tr(R.string.ui_codex_is_requesting_permission_to_modify_files), style = MaterialTheme.typography.bodyMedium) }
            req.workingDirectory?.let { Text(tr(R.string.ui_working_directory , it), style = MaterialTheme.typography.bodySmall) }
            req.reason?.takeIf { req.command != null }?.let { Text(it) }
            req.writeRoot?.let { Text(tr(R.string.ui_requested_write_scope , it)) }
            req.networkHost?.let { Text(tr(R.string.ui_network_access , it)) }
            Text(tr(R.string.ui_permission_applies_to_this_operation_only_no), style = MaterialTheme.typography.bodySmall)
            if (pending.submitted) Text(tr(R.string.ui_submitted_waiting_for_server_confirmation), style = MaterialTheme.typography.bodySmall)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { haptic(true); onDecision("accept") }, enabled = enabled) { Text(tr(R.string.ui_allow_once)) }
                OutlinedButton(onClick = { haptic(true); onDecision("decline") }, enabled = enabled) { Text(tr(R.string.ui_reject)) }
                TextButton(onClick = { haptic(false); onDecision("cancel") }, enabled = enabled) { Text(tr(R.string.ui_cancel)) }
            }
        }
    }
}

@Preview(showBackground = true) @Composable private fun MessagePreview() = WorkbenchTheme {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TimelineCard(TimelineItem("1", "t", "userMessage", tr(R.string.ui_check_connection_recovery_and_avoid_duplicate_messages), true), {})
        TimelineCard(TimelineItem("2", "t", "agentMessage", tr(R.string.ui_found_the_connection_recovery_logic_n_ni), true), {})
        TimelineCard(TimelineItem("3", "t", "commandExecution", "git status --short\nWorking tree clean", true), {})
    }
}
