package dev.remotegpt.app.ui

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode

internal val MATH_INLINE = IElementType("CHAT_MATH_INLINE")
internal val MATH_DISPLAY = IElementType("CHAT_MATH_DISPLAY")
internal val MATH_SOURCE = IElementType("CHAT_MATH_SOURCE")

internal data class MathRange(val start: Int, val end: Int, val display: Boolean, val complete: Boolean)

/** Scan before Markdown interprets TeX underscores, pipes, backslashes and brackets.
 * Code and link targets are identified by the existing GFM parser, not regexes. */
internal fun mathRanges(text: String, markdown: ASTNode): List<MathRange> {
    val protected = BooleanArray(text.length)
    val excludedTypes = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.LINK_TITLE,
        MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.IMAGE)
    fun exclude(node: ASTNode) {
        if (node.type in excludedTypes) {
            for (i in node.startOffset until node.endOffset) protected[i] = true
        } else node.children.forEach(::exclude)
    }
    exclude(markdown)
    fun escaped(offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] == '\\') i--
        return (offset - i - 1) % 2 != 0
    }
    val result = mutableListOf<MathRange>()
    var i = 0
    while (i < text.length) {
        if (protected[i] || escaped(i)) { i++; continue }
        val opener = when {
            text.startsWith("$$", i) -> "$$"
            text.startsWith("\\[", i) -> "\\["
            text.startsWith("\\(", i) -> "\\("
            text[i] == '$' && text.getOrNull(i + 1)?.let { !it.isWhitespace() } == true -> "$"
            else -> { i++; continue }
        }
        val closer = when (opener) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> opener }
        val display = opener == "$$" || opener == "\\["
        var end = i + opener.length
        var closed = false
        while (end < text.length && !protected[end]) {
            if (!display && text[end] == '\n') break
            if (text.startsWith(closer, end) && !escaped(end) &&
                (opener != "$" || (!text[end - 1].isWhitespace() && text.getOrNull(end + 1)?.isDigit() != true))) {
                closed = true
                break
            }
            end++
        }
        if (closed && text.substring(i + opener.length, end).isNotBlank()) {
            end += closer.length
            result += MathRange(i, end, display, true)
            i = end
        } else if (opener != "$") {
            // Preserve unfinished explicit math verbatim while tokens arrive.
            result += MathRange(i, end, display, false)
            i = end.coerceAtLeast(i + opener.length)
        } else i++
    }
    return result
}

internal fun maskMath(text: String, ranges: List<MathRange>): String {
    val chars = text.toCharArray()
    ranges.forEach { range -> for (i in range.start until range.end) chars[i] = 'q' }
    return String(chars)
}

/** Masking preserves offsets. Replace the masked text leaves with math nodes and
 * split display equations into real blocks, including those inside lists/quotes. */
internal fun restoreMath(root: ASTNode, ranges: List<MathRange>): ASTNode {
    fun restore(node: ASTNode): List<ASTNode> {
        if (node.children.isEmpty()) {
            val inside = ranges.filter { it.start >= node.startOffset && it.end <= node.endOffset }
            if (inside.isEmpty()) return listOf(LeafASTNode(node.type, node.startOffset, node.endOffset))
            val nodes = mutableListOf<ASTNode>()
            var offset = node.startOffset
            inside.forEach {
                if (offset < it.start) nodes += LeafASTNode(node.type, offset, it.start)
                nodes += LeafASTNode(if (!it.complete) MATH_SOURCE else if (it.display) MATH_DISPLAY else MATH_INLINE, it.start, it.end)
                offset = it.end
            }
            if (offset < node.endOffset) nodes += LeafASTNode(node.type, offset, node.endOffset)
            return nodes
        }
        val children = node.children.flatMap(::restore)
        if (node.type != MarkdownElementTypes.PARAGRAPH || children.none { it.type == MATH_DISPLAY }) {
            return listOf(CompositeASTNode(node.type, children))
        }
        val blocks = mutableListOf<ASTNode>()
        val paragraph = mutableListOf<ASTNode>()
        fun flush() {
            if (paragraph.any { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }) {
                blocks += CompositeASTNode(MarkdownElementTypes.PARAGRAPH, paragraph.toList())
            }
            paragraph.clear()
        }
        children.forEach { if (it.type == MATH_DISPLAY) { flush(); blocks += it } else paragraph += it }
        flush()
        return blocks
    }
    return restore(root).single()
}

internal fun mathSource(raw: String): String = when {
    raw.startsWith("$$") -> raw.drop(2).dropLast(2)
    raw.startsWith("\\(") || raw.startsWith("\\[") -> raw.drop(2).dropLast(2)
    else -> raw.drop(1).dropLast(1)
}.trim()
