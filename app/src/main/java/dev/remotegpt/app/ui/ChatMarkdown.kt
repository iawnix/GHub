@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.components.*
import com.mikepenz.markdown.model.*
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private class ChatMarkdownState : MarkdownState {
    override val state = MutableStateFlow<State>(State.Loading())
    override val links = MutableStateFlow<Map<String, String?>>(emptyMap())
    override suspend fun parse(): State = state.value
}

@Composable internal fun rememberChatMarkdown(content: String, complete: Boolean): MarkdownState {
    val state = remember { ChatMarkdownState() }
    val parser = remember { ChatMarkdownParser() }
    val latest by rememberUpdatedState(content to complete)
    LaunchedEffect(state) {
        // Conflate intermediate deltas, but let each parse finish. Never replace
        // an existing result with a Loading/empty view while new text is parsed.
        snapshotFlow { latest }.conflate().collect { (text, done) ->
            try {
                state.state.value = withContext(Dispatchers.Default) { parser.parse(text) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.state.value = State.Error(e) }
            if (!done) delay(120)
        }
    }
    return state
}

@Composable internal fun StableMarkdownContent(state: State.Success, components: MarkdownComponents, modifier: Modifier) {
    Column(modifier) {
        val blocks = (state.node as ChatMarkdownRoot).blocks
        blocks.forEach { block ->
            key(block.offset) { StableMarkdownBlock(block, components) }
        }
    }
}

@Composable private fun StableMarkdownBlock(block: ChatMarkdownBlock, components: MarkdownComponents) {
    MathMarkdownContent(block) { MarkdownElement(block.node, components, block.source) }
}

internal fun chatMarkdownComponents(): MarkdownComponents {
    lateinit var components: MarkdownComponents
    fun mathWidth(component: MarkdownComponent): MarkdownComponent = { model -> MathInlineWidth { component(model) } }
    components = markdownComponents(
        paragraph = mathWidth(CurrentComponentsBridge.paragraph),
        heading1 = mathWidth(CurrentComponentsBridge.heading1),
        heading2 = mathWidth(CurrentComponentsBridge.heading2),
        heading3 = mathWidth(CurrentComponentsBridge.heading3),
        heading4 = mathWidth(CurrentComponentsBridge.heading4),
        heading5 = mathWidth(CurrentComponentsBridge.heading5),
        heading6 = mathWidth(CurrentComponentsBridge.heading6),
        setextHeading1 = mathWidth(CurrentComponentsBridge.setextHeading1),
        setextHeading2 = mathWidth(CurrentComponentsBridge.setextHeading2),
        table = { ChatTable(it, components) },
        codeFence = { ChatCode(it, fenced = true) },
        codeBlock = { ChatCode(it, fenced = false) },
        custom = { type, model ->
            if (type == MATH_DISPLAY) DisplayMath(model.content.substring(model.node.startOffset, model.node.endOffset))
            else model.node.children.forEach { MarkdownElement(it, components, model.content) }
        },
    )
    return components
}

@Composable internal fun CopyControl(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    TextButton(onClick = {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        haptic(false); copied = true
    }) { Text(if (copied) tr(R.string.ui_copied) else tr(R.string.ui_copy), style = MaterialTheme.typography.labelMedium) }
}

@Composable private fun ChatCode(model: MarkdownComponentModel, fenced: Boolean) {
    val node = model.node
    val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.let { model.content.substring(it.startOffset, it.endOffset) }.orEmpty()
    val code = if (fenced) {
        val raw = model.content.substring(node.startOffset, node.endOffset)
        val start = raw.indexOf('\n').let { if (it < 0) raw.length else it + 1 }
        val end = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }?.startOffset?.minus(node.startOffset) ?: raw.length
        raw.substring(start.coerceAtMost(end), end)
    } else model.content.substring(node.startOffset, node.endOffset).lines().joinToString("\n") { it.removePrefix("    ") }
    CodeBlock(code, language, language.ifBlank { tr(R.string.ui_code) })
}

@Composable private fun ChatTable(model: MarkdownComponentModel, components: MarkdownComponents, fullScreen: Boolean = false) {
    val rows = model.node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    val count = rows.firstOrNull()?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0
    if (count == 0) { Text(model.content.substring(model.node.startOffset, model.node.endOffset)); return }
    var expanded by rememberSaveable(model.node.startOffset) { mutableStateOf(false) }
    var showAll by rememberSaveable(model.node.startOffset) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val text = model.content.substring(model.node.startOffset, model.node.endOffset)
    val alignments = model.node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.let {
        model.content.substring(it.startOffset, it.endOffset).trim().trim('|').split('|').map { cell ->
            val value = cell.trim()
            when {
                value.startsWith(':') && value.endsWith(':') -> TextAlign.Center
                value.endsWith(':') -> TextAlign.End
                else -> TextAlign.Start
            }
        }
    }.orEmpty()
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!fullScreen) TextButton(onClick = { expanded = true }) { Text(tr(R.string.ui_expand_table), style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f)); CopyControl(text)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimum = (if (count == 2) 120 else 152).dp * fontScale
                val total = maxOf(maxWidth, minimum * count)
                val widths = if (count == 2) listOf(maxOf(minimum, total * .35f), total - maxOf(minimum, total * .35f)) else List(count) { total / count }
                Column(Modifier.horizontalScroll(scroll).width(total)) {
                    val visible = if (!fullScreen && !showAll && rows.size > 13) rows.take(7) else rows
                    visible.forEachIndexed { index, row ->
                        val cells = row.children.filter { it.type == GFMTokenTypes.CELL }
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            repeat(count) { column ->
                                val alignment = alignments.getOrNull(column) ?: TextAlign.Start
                                Box(Modifier.width(widths[column]).padding(10.dp), contentAlignment = when (alignment) {
                                    TextAlign.End -> Alignment.TopEnd
                                    TextAlign.Center -> Alignment.TopCenter
                                    else -> Alignment.TopStart
                                }) {
                                    cells.getOrNull(column)?.let { cell ->
                                        // A cell is an inline container, not a list of separate
                                        // blocks. Keep links, emphasis and formulas on one text run.
                                        MathInlineWidth {
                                            MarkdownText(model.content, cell,
                                                style = model.typography.table.copy(textAlign = alignment))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (scroll.maxValue > 0) {
            Text(tr(R.string.ui_swipe_sideways_to_view), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (scroll.value.toFloat() / scroll.maxValue).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        if (!fullScreen && rows.size > 13) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) tr(R.string.ui_collapse_table) else tr(R.string.ui_show_all_rows , rows.size - 1)) }
    }
    if (expanded && !fullScreen) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(tr(R.string.ui_table)) }, navigationIcon = { IconButton(onClick = { expanded = false }) { Icon(WorkbenchIcons.ArrowBack, tr(R.string.ui_back_to_session)) } }) }) { padding ->
            SelectionContainer {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ChatTable(model, components, fullScreen = true)
                }
            }
        }
    }
}
