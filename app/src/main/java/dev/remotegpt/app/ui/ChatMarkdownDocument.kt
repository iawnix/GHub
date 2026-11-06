package dev.remotegpt.app.ui

import androidx.compose.runtime.Immutable
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.flow.first
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.impl.MathParser

/** Immutable, block-local source/offsets: retaining a paragraph must not retain an
 * entire earlier version of a growing answer. Created only on the parser worker. */
@Immutable
internal class ChatMarkdownBlock(val offset: Int, val source: String, val node: ASTNode)

internal class ChatMarkdownRoot(node: ASTNode, val blocks: List<ChatMarkdownBlock>) : ASTNode by node

private fun blockNode(node: ASTNode, base: Int): ASTNode =
    if (node.children.isEmpty()) LeafASTNode(node.type, node.startOffset - base, node.endOffset - base)
    else CompositeASTNode(node.type, node.children.map { blockNode(it, base) })

private class DocumentLinks(links: Map<String, String?>) : ReferenceLinkHandler {
    private val delegate = ReferenceLinkHandlerImpl().apply { links.forEach { (label, target) -> store(label, target) } }
    override fun find(label: String) = delegate.find(label)
    // The renderer also registers inline-link aliases during annotation.
    override fun store(label: String, destination: String?) = delegate.store(label, destination)
}

/** Full GFM parsing preserves tables, fences, lists and late reference definitions.
 * Rendering reuses unchanged blocks. Do not share an instance between parse jobs. */
internal class ChatMarkdownParser {
    private val flavour = object : GFMFlavourDescriptor() {
        private val standard = super.sequentialParserManager
        // Its dollar parser also consumes prices like "$5 and $10". Our scanner
        // owns math; unmatched delimiters must stay ordinary Markdown text.
        override val sequentialParserManager = object : SequentialParserManager() {
            override fun getParserSequence() = standard.getParserSequence().filterNot { it is MathParser }
        }
    }
    private val parser = MarkdownParser(flavour)
    private var previous = emptyMap<Int, ChatMarkdownBlock>()
    private var links: Map<String, String?> = emptyMap()
    private var handler: ReferenceLinkHandler = DocumentLinks(links)

    suspend fun parse(text: String): State.Success {
        val found = mutableMapOf<String, String?>()
        val collector = object : ReferenceLinkHandler {
            override fun store(label: String, destination: String?) { found[label.lowercase()] = destination }
            override fun find(label: String) = found[label.lowercase()].orEmpty()
        }
        val ranges = if ('$' in text || "\\(" in text || "\\[" in text)
            mathRanges(text, parser.buildMarkdownTreeFromString(text)) else emptyList()
        val parsed = parseMarkdownFlow(if (ranges.isEmpty()) text else maskMath(text, ranges), flavour = flavour, parser = parser, referenceLinkHandler = collector)
            .first { it !is State.Loading }
        if (parsed is State.Error) throw parsed.result
        val parsedRoot = (parsed as State.Success).node
        val root = if (ranges.isEmpty()) parsedRoot else restoreMath(parsedRoot, ranges)
        val normalized = found.mapKeys { it.key.lowercase() }
        if (normalized != links) {
            links = normalized
            handler = DocumentLinks(links)
        }
        val blocks = root.children.filterNot {
            it.type == MarkdownTokenTypes.EOL || it.type == MarkdownElementTypes.LINK_DEFINITION
        }.map { node ->
            val source = text.substring(node.startOffset, node.endOffset)
            val old = previous[node.startOffset]
            if (old != null && old.source == source && sameTree(old.node, node, node.startOffset)) old
            else ChatMarkdownBlock(node.startOffset, source, blockNode(node, node.startOffset))
        }
        previous = blocks.associateBy { it.offset }
        return State.Success(ChatMarkdownRoot(root, blocks), text, true, handler)
    }

    private fun sameTree(local: ASTNode, full: ASTNode, base: Int): Boolean =
        local.type == full.type && local.startOffset == full.startOffset - base &&
            local.endOffset == full.endOffset - base && local.children.size == full.children.size &&
            local.children.indices.all { sameTree(local.children[it], full.children[it], base) }
}
