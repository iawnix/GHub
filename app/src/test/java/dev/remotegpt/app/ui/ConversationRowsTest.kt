package dev.remotegpt.app.ui

import dev.remotegpt.client.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRowsTest {
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
}
