package dev.remotegpt.app.ui

import dev.remotegpt.client.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRowsTest {
    @Test fun planBlocksStayVisibleOutsideExecutionSummary() {
        val plan = TimelineItem("plan", "turn", "plan", "Step one", complete = true)
        val tool = TimelineItem("tool", "turn", "commandExecution", "ls", complete = true)
        val rows = buildConversationRows(listOf(java.util.AbstractMap.SimpleEntry("turn", listOf(plan, tool))),
            mapOf(plan.identityKey to listOf("Step one", "Step two")), null, "turn", "completed")
        assertEquals(listOf("Step one", "Step two"), rows.filterIsInstance<ConversationRow.AssistantBlock>().map { it.source })
        assertEquals(listOf(tool), rows.filterIsInstance<ConversationRow.Execution>().single().items)
    }

    @Test fun allAssistantMessagesRemainVisibleWhenLaterMessageArrives() {
        val first = TimelineItem("a1", "turn", "agentMessage", "I will inspect the project.", complete = true)
        val second = TimelineItem("a2", "turn", "agentMessage", "The issue is fixed.", complete = false)
        val command = TimelineItem("cmd", "turn", "commandExecution", "echo ok", complete = false)
        val rows = buildConversationRows(
            listOf(java.util.AbstractMap.SimpleEntry("turn", listOf(first, command, second))),
            mapOf("${first.identityKey}" to listOf(first.text), "${second.identityKey}" to listOf(second.text)),
            activeTurn = "turn", completedTurn = null, turnStatus = "inProgress",
        )
        val assistants = rows.filterIsInstance<ConversationRow.AssistantBlock>()
        assertEquals(listOf("I will inspect the project.", "The issue is fixed."), assistants.map { it.source })
        assertTrue(rows.any { it is ConversationRow.Execution && it.items.single().id == "cmd" })
        assertEquals(
            listOf("assistant:${first.identityKey}:0", "execution:turn", "assistant:${second.identityKey}:0"),
            rows.map { it.key },
        )
    }

    @Test fun executionSummaryPrecedesFinalAnswerWhenToolsRunBeforeReply() {
        val user = TimelineItem("u1", "turn", "userMessage", "Fix it", complete = true)
        val command = TimelineItem("cmd", "turn", "commandExecution", "./gradlew test", complete = true)
        val finalReply = TimelineItem("a1", "turn", "agentMessage", "All tests pass.", complete = true)
        val rows = buildConversationRows(
            listOf(java.util.AbstractMap.SimpleEntry("turn", listOf(user, command, finalReply))),
            mapOf(finalReply.identityKey to listOf(finalReply.text)),
            activeTurn = null, completedTurn = "turn", turnStatus = "completed",
        )
        assertEquals(
            listOf("user:${user.identityKey}", "execution:turn", "assistant:${finalReply.identityKey}:0", "reply:turn"),
            rows.map { it.key },
        )
    }

    @Test fun completedReplyActionsAttachOnlyToFinalAssistantMessage() {
        val first = TimelineItem("a1", "turn", "agentMessage", "first", complete = true)
        val second = TimelineItem("a2", "turn", "agentMessage", "final", complete = true)
        val rows = buildConversationRows(
            listOf(java.util.AbstractMap.SimpleEntry("turn", listOf(first, second))),
            mapOf(first.identityKey to listOf(first.text), second.identityKey to listOf(second.text)),
            activeTurn = null, completedTurn = "turn", turnStatus = "completed",
        )
        assertEquals(1, rows.filterIsInstance<ConversationRow.Reply>().size)
        assertEquals("a2", rows.filterIsInstance<ConversationRow.Reply>().single().item.id)
    }

    @Test fun preparsedAstBlocksCarryDocumentReferenceLinksAndAttachImagesOnce() = kotlinx.coroutines.runBlocking {
        val source = "First paragraph with [Guide][docs].\n\nSecond paragraph.\n\n[docs]: https://example.com/guide"
        val parsed = ChatMarkdownParser().parse(source)
        val preparsed = (parsed.node as ChatMarkdownRoot).blocks.map { block ->
            PreparsedMarkdownBlock(block, parsed.referenceLinkHandler)
        }
        val item = TimelineItem("a1", "turn", "agentMessage", source, complete = true,
            imagePaths = listOf("/tmp/diagram.png"))
        val rows = buildConversationRowsWithAst(
            listOf(java.util.AbstractMap.SimpleEntry("turn", listOf(item))),
            mapOf(item.identityKey to preparsed),
            activeTurn = null, completedTurn = "turn", turnStatus = "completed",
        )
        val blocks = rows.filterIsInstance<ConversationRow.AssistantBlock>()
        assertEquals(2, blocks.size)
        assertEquals("https://example.com/guide", blocks.first().preparsed!!.links.find("[docs]"))
        assertEquals(listOf(false, true), blocks.map { it.isLastBlock })
    }
}
