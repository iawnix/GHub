@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import dev.remotegpt.client.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

private data class CodeDocument(val code: HighlightedCode, val pages: List<CodeSlice>, val shortened: Boolean)

/** One worker per visible block, conflating streaming updates without reparsing
 * on the UI thread. Disposing/collapsing the block also disposes its worker. */
@Composable private fun rememberCodeDocument(source: String, language: String, full: Boolean): CodeDocument? {
    val latest by rememberUpdatedState(Triple(source, language, full))
    var document by remember { mutableStateOf<CodeDocument?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { (raw, lang, all) ->
            document = withContext(Dispatchers.Default) {
                val end = if (all) raw.length else codeSlice(raw).end
                val highlighted = highlightCode(raw.substring(0, end), lang)
                CodeDocument(highlighted, if (all) codePages(highlighted.text) else emptyList(), end < raw.length)
            }
            delay(120)
        }
    }
    return document
}

@Composable internal fun CodeBlock(
    source: String,
    language: String = "text",
    title: String = language,
    copyLabel: String = tr(R.string.ui_copy_code),
    framed: Boolean = true,
    initiallyWrapped: Boolean = language in setOf("sh", "bash", "zsh", "shell", "shellscript", "output", "text"),
    /** Short, always-wrapped snippets (a single command) keep copy beside the text instead of a header row. */
    inlineControls: Boolean = false,
) {
    var wrap by rememberSaveable { mutableStateOf(initiallyWrapped || inlineControls) }
    var full by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val document = rememberCodeDocument(source, language, full = false)
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            val code: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    SelectionContainer {
                        CodeText(document?.code ?: HighlightedCode("", emptyList()), wrap, language !in setOf("output", "text", "diff", "patch"),
                            maxLines = 12, onOverflow = { overflow = it })
                    }
                }
            }
            if (inlineControls) Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.Top) {
                // 6dp + the text's 8dp inset centers the first 20sp line on the 48dp button.
                code(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp))
                CodeCopyControl(source, copyLabel)
            } else {
                CodeToolbar(title, source, copyLabel, wrap) { wrap = !wrap }
                code(Modifier.fillMaxWidth())
            }
            if (document?.shortened == true || overflow) TextButton(onClick = { full = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr(R.string.ui_view_full_content))
            }
        }
    }
    if (framed) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)), content = content)
    else content()
    if (full) FullCodeDialog(source, language, title, copyLabel, wrap, { wrap = !wrap }) { full = false }
}

@Composable private fun CodeToolbar(title: String, source: String, copyLabel: String, wrap: Boolean, onWrap: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        WrapControl(wrap, onWrap)
        CodeCopyControl(source, copyLabel)
    }
}

/** An icon toggle reads as a state at a glance; the tinted container marks "on". */
@Composable private fun WrapControl(wrap: Boolean, onWrap: () -> Unit) {
    IconToggleButton(checked = wrap, onCheckedChange = { onWrap() },
        modifier = Modifier.semantics {
            stateDescription = if (wrap) tr(R.string.ui_wrap_enabled) else tr(R.string.ui_wrap_disabled)
        },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        )) {
        Icon(WorkbenchIcons.WrapText, tr(R.string.ui_wrap_lines), Modifier.size(18.dp))
    }
}

@Composable private fun CodeCopyControl(source: String, label: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    IconButton(onClick = {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, source))
        haptic(false)
        copied = true
    }) { Icon(if (copied) WorkbenchIcons.Check else WorkbenchIcons.Copy,
        if (copied) tr(R.string.ui_copied) else label, Modifier.size(18.dp)) }
}

@Composable private fun codeColors(): Map<CodeTone, Color> {
    val colors = MaterialTheme.colorScheme
    val semantic = LocalWorkbenchSemanticColors.current
    return remember(colors, semantic) { mapOf(
        CodeTone.KEYWORD to colors.primary, CodeTone.COMMAND to colors.primary,
        CodeTone.STRING to semantic.success, CodeTone.ADD to semantic.success,
        CodeTone.NUMBER to semantic.warning, CodeTone.VARIABLE to semantic.warning,
        CodeTone.OPTION to colors.primary, CodeTone.COMMENT to colors.onSurfaceVariant,
        CodeTone.OPERATOR to colors.onSurface, CodeTone.REMOVE to colors.error,
        CodeTone.META to colors.primary,
    ) }
}

@Composable private fun CodeText(code: HighlightedCode, wrap: Boolean, indentWrappedLines: Boolean, slice: CodeSlice? = null,
    maxLines: Int = Int.MAX_VALUE, onOverflow: (Boolean) -> Unit = {}) {
    val colors = codeColors()
    val annotated = remember(code, colors, slice, wrap, indentWrappedLines) {
        val start = slice?.start ?: 0
        val end = slice?.end ?: code.text.length
        buildAnnotatedString {
            val visible = code.text.substring(start, end)
            append(visible)
            code.tokens.forEach { token ->
                val from = maxOf(start, token.start)
                val to = minOf(end, token.end)
                if (from < to) addStyle(SpanStyle(color = colors.getValue(token.tone)), from - start, to - start)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (slice == null) 8.dp else 0.dp)) {
        Text(annotated,
            modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Normal, letterSpacing = 0.sp, textDirection = TextDirection.Ltr,
                textIndent = if (wrap && indentWrappedLines) TextIndent(restLine = 1.2.em) else TextIndent.None),
            color = MaterialTheme.colorScheme.onSurface, softWrap = wrap, maxLines = maxLines,
            // Compose treats softWrap=false + Ellipsis as a single line, even
            // when the source contains newlines. Scrolling code must use Clip.
            overflow = if (wrap) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { onOverflow(it.didOverflowHeight) })
    }
}

@Composable private fun FullCodeDialog(source: String, language: String, title: String, copyLabel: String,
    wrap: Boolean, onWrap: () -> Unit, onClose: () -> Unit) {
    val document = rememberCodeDocument(source, language, full = true)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = onClose) { Icon(WorkbenchIcons.ArrowBack, tr(R.string.ui_back_to_session)) }
            }, actions = { WrapControl(wrap, onWrap); CodeCopyControl(source, copyLabel) })
        }) { padding ->
            val current = document
            if (current == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(current.pages, key = { it.start }) { page ->
                        CodeText(current.code, wrap, language !in setOf("output", "text", "diff", "patch"), page)
                    }
                }
            }
        }
    }
}

@Composable internal fun CommandExecutionContent(item: TimelineItem) {
    val details = item.commandExecution
    if (details == null) {
        CodeBlock(item.text, title = tr(R.string.ui_original_record), copyLabel = tr(R.string.ui_copy_record))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TaskStatusLabel(itemTaskStatus(item))
            // Exit code 0 restates "completed"; only a non-zero code adds information.
            details.exitCode?.takeIf { it != 0 }?.let { Text(tr(R.string.ui_exit_code, it), style = MaterialTheme.typography.bodySmall) }
            details.durationMs?.let { ms ->
                val duration = durationText(ms)
                Text(tr(duration.id, *duration.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        details.workingDirectory?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
            Column {
                details.command?.let { command ->
                    CodeBlock(command, "sh", tr(R.string.ui_output_command_short), tr(R.string.ui_copy_command), framed = false,
                        inlineControls = true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                }
                if (details.outputTruncated) Text(tr(R.string.ui_cached_output_tail), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.text.isNotEmpty()) CodeBlock(item.text, "output", tr(R.string.ui_command_output), tr(R.string.ui_copy_output), framed = false)
                else Text(tr(if (item.complete) R.string.ui_no_command_output else R.string.ui_waiting_command_output),
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal data class DurationText(@androidx.annotation.StringRes val id: Int, val args: List<Any>)

/** 850 ms · 2.1 s · 1 min 12 s; tenths are truncated so 59 999 ms never reads as 60.0 s. */
internal fun durationText(ms: Long): DurationText {
    val value = ms.coerceAtLeast(0)
    return when {
        value < 1_000 -> DurationText(R.string.ui_duration_ms, listOf(value))
        value < 60_000 -> DurationText(R.string.ui_duration_seconds,
            listOf("${value / 1_000}.${value % 1_000 / 100}"))
        else -> DurationText(R.string.ui_duration_minutes, listOf(value / 60_000, value % 60_000 / 1_000))
    }
}
