package dev.remotegpt.app.ui

import kotlinx.coroutines.runBlocking
import org.intellij.markdown.IElementType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.*
import org.junit.Test

class ChatMathParserTest {
    private fun ASTNode.nodes(type: IElementType): List<ASTNode> =
        (if (this.type == type) listOf(this) else emptyList()) + children.flatMap { it.nodes(type) }

    @Test fun delimitersPreserveTexAndDisplayEquationsBecomeSeparateRows() = runBlocking {
        val source = "Before ${'$'}x_i^2${'$'}, \\(\\frac{a}{b}\\).\n\n" +
            "${'$'}${'$'}\n\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}\n${'$'}${'$'}\n\n" +
            "\\[\\int_0^1 x^2 dx\\]\n\nAfter"
        val root = ChatMarkdownParser().parse(source).node as ChatMarkdownRoot
        assertEquals(2, root.nodes(MATH_INLINE).size)
        assertEquals(2, root.blocks.count { it.node.type == MATH_DISPLAY })
        assertTrue(root.blocks.any { it.source == "After" })
        root.blocks.forEach { block ->
            assertEquals(source.substring(block.offset, block.offset + block.source.length), block.source)
            (block.node.nodes(MATH_INLINE) + block.node.nodes(MATH_DISPLAY)).forEach {
                assertFalse(mathSource(block.source.substring(it.startOffset, it.endOffset)).contains('q'))
            }
        }
    }

    @Test fun codeEscapedDollarsPricesAndLinkTargetsStayLiteral() = runBlocking {
        val source = "`\$x\$` and \\\$x\\\$; prices \$5 and \$10.\n\n" +
            "```latex\n\$\$x_i\$\$\n```\n\n    \$y\$\n\n" +
            "[link](https://example.com/\$x\$)\n\n[ref]: https://example.com/\$x\$\n\n" +
            "![image](https://example.com/\$x\$)"
        val root = ChatMarkdownParser().parse(source).node
        assertTrue(root.nodes(MATH_INLINE).isEmpty())
        assertTrue(root.nodes(MATH_DISPLAY).isEmpty())
        assertTrue(root.nodes(GFMElementTypes.INLINE_MATH).isEmpty())
        assertTrue(root.nodes(GFMElementTypes.BLOCK_MATH).isEmpty())
    }

    @Test fun formulaSyntaxDoesNotBreakMarkdownEmphasisListsTablesOrReferences() = runBlocking {
        val source = "**value \$a_b*c\$**\n\n- \\(x^2\\)\n\n> \$y_1\$\n\n" +
            "| Formula | Link |\n| --- | --- |\n| \$|x|\$ | [docs][ref] |\n\n[ref]: https://example.com"
        val result = ChatMarkdownParser().parse(source)
        assertEquals(4, result.node.nodes(MATH_INLINE).size)
        assertEquals("https://example.com", result.referenceLinkHandler.find("[ref]"))
    }

    @Test fun everyStreamingPrefixIsValidAndCompletedBlocksAreReused() = runBlocking {
        val parser = ChatMarkdownParser()
        val source = "中文 \$\\frac{a}{b}\$\n\n\\[\n\\sum_{i=1}^{n} i\n\\]\n\nTail"
        for (end in 1..source.length) {
            val text = source.take(end)
            val root = parser.parse(text).node as ChatMarkdownRoot
            root.blocks.forEach { block ->
                fun check(node: ASTNode) {
                    assertTrue(node.startOffset in 0..block.source.length)
                    assertTrue(node.endOffset in node.startOffset..block.source.length)
                    node.children.forEach { assertSame(node, it.parent); check(it) }
                }
                check(block.node)
            }
        }
        val before = (parser.parse(source).node as ChatMarkdownRoot).blocks
        val after = (parser.parse(source + " changed").node as ChatMarkdownRoot).blocks
        before.dropLast(1).zip(after).forEach { (a, b) -> assertSame(a, b) }
        val unfinished = parser.parse("\\[\\frac{a}{").node
        assertEquals(1, unfinished.nodes(MATH_SOURCE).size)
        assertTrue(unfinished.nodes(MATH_DISPLAY).isEmpty())
    }
}
