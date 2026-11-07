package dev.remotegpt.app.ui

import android.util.LruCache
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import ru.noties.jlatexmath.JLatexMathDrawable

private val LocalMathDrawables = staticCompositionLocalOf<Map<String, JLatexMathDrawable?>> { emptyMap() }

/** JLaTeXMath has shared font/parser state. Serialize work off the UI thread and
 * bound the cache; streaming text never rebuilds already completed equations. */
internal object ChatMathRenderer {
    private data class Key(val raw: String, val size: Float, val color: Int)
    private data class Result(val drawable: JLatexMathDrawable?)
    private val mutex = Mutex()
    private val cache = LruCache<Key, Result>(128)
    // Definitions mutate global TeX state; external images are not chat math.
    private val unsupported = Regex("""\\(?:newcommand|renewcommand|newenvironment|renewenvironment|def|gdef|edef|xdef|includegraphics|input|include|DeclareMathOperator|definecolor|usepackage)\b""")

    suspend fun render(raw: String, size: Float, color: Int): JLatexMathDrawable? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val key = Key(raw, size, color)
            cache.get(key)?.let { return@withLock it.drawable }
            val latex = mathSource(raw)
            var depth = 0
            var excessiveDepth = false
            latex.forEach { if (it == '{') { depth++; if (depth > 64) excessiveDepth = true } else if (it == '}') depth-- }
            val drawable = if (latex.length > 4096 || excessiveDepth || unsupported.containsMatchIn(latex)) null else try {
                JLatexMathDrawable.builder(if (raw.startsWith("$$") || raw.startsWith("\\[")) latex else "\\textstyle $latex")
                    .textSize(size).color(color).padding(0).build()
                    .takeIf { it.intrinsicWidth in 1..16384 && it.intrinsicHeight in 1..4096 }
            } catch (_: Exception) { null }
            catch (_: StackOverflowError) { null }
            cache.put(key, Result(drawable))
            drawable
        }
    }
}

@Composable internal fun MathMarkdownContent(block: ChatMarkdownBlock, content: @Composable () -> Unit) {
    val mathNodes = remember(block) {
        buildList {
            fun visit(node: ASTNode) {
                if (node.type == MATH_INLINE || node.type == MATH_DISPLAY || node.type == MATH_SOURCE) add(node)
                node.children.forEach(::visit)
            }
            visit(block.node)
        }
    }
    if (mathNodes.isEmpty()) { content(); return }
    val formulas = remember(block) {
        mathNodes.filter { it.type != MATH_SOURCE }
            .map { block.source.substring(it.startOffset, it.endOffset) }.toSet()
    }
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize
    val size = with(density) { fontSize.toPx() }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    // Key the producer so a theme/font change cannot briefly reuse old colors/sizes.
    key(size, color) {
        val drawables by produceState<Map<String, JLatexMathDrawable?>>(emptyMap(), formulas) {
            value = formulas.associateWith { ChatMathRenderer.render(it, size, color) }
        }
        val annotator = remember(drawables) {
            markdownAnnotator { source, node ->
                if (node.type == MATH_INLINE || node.type == MATH_DISPLAY || node.type == MATH_SOURCE) {
                    val raw = source.substring(node.startOffset, node.endOffset)
                    if (drawables[raw] != null) appendInlineContent(raw, raw) else append(raw)
                    true
                } else false
            }
        }
        CompositionLocalProvider(
            LocalMathDrawables provides drawables,
            LocalMarkdownAnnotator provides annotator,
            content = content,
        )
    }
}

/** Measure at the paragraph/cell width, after list indentation. An oversized
 * inline equation scrolls within that width instead of vanishing off-screen. */
@Composable internal fun MathInlineWidth(content: @Composable () -> Unit) {
    val drawables = LocalMathDrawables.current
    if (drawables.isEmpty()) { content(); return }
    val density = LocalDensity.current
    BoxWithConstraints {
        val width = maxWidth
        val inline = remember(drawables, density, width) {
            drawables.mapNotNull { (raw, drawable) ->
                drawable ?: return@mapNotNull null
                val naturalWidth = with(density) { drawable.intrinsicWidth.toDp() }
                val height = with(density) { drawable.intrinsicHeight.toDp() }
                raw to InlineTextContent(Placeholder(
                    with(density) { min(naturalWidth, width).toSp() },
                    with(density) { height.toSp() },
                    PlaceholderVerticalAlign.TextCenter,
                )) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        MathCanvas(raw, drawable, Modifier.size(naturalWidth, height))
                    }
                }
            }.toMap()
        }
        CompositionLocalProvider(LocalMarkdownInlineContent provides DefaultMarkdownInlineContent(inline), content = content)
    }
}

@Composable internal fun DisplayMath(raw: String) {
    val drawable = LocalMathDrawables.current[raw]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Reserve an action slot outside the scroll area; never cover the formula.
        Box(Modifier.weight(1f).heightIn(min = 48.dp).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterStart) {
            if (drawable == null) Text(raw, style = MaterialTheme.typography.bodyLarge)
            else {
                val density = LocalDensity.current
                MathCanvas(raw, drawable, Modifier.size(
                    with(density) { drawable.intrinsicWidth.toDp() },
                    with(density) { drawable.intrinsicHeight.toDp() },
                ))
            }
        }
        ConversationCopyButton(mathSource(raw), tr(R.string.ui_copy_formula))
    }
}

@Composable private fun MathCanvas(raw: String, drawable: JLatexMathDrawable, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = raw }) {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
