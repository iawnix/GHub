package dev.remotegpt.app

import dev.remotegpt.client.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class InterruptedInputTest {
    private val prompt = TimelineItem("prompt", "turn", "userMessage", "original", true)
    private val photo = Attachment("photo", "photo.png", image = true, remotePath = "/tmp/photo.png", state = "ready")
    private val file = Attachment("file", "note.txt", remotePath = "/tmp/note.txt", state = "ready")
    private val original = MessageInput("original", listOf(photo, file), "model-a", "high")
    private fun workspace() = HostWorkspace(selectedThread = "thread",
        threads = listOf(ThreadSummary("thread", "Test", "/tmp")),
        conversations = mapOf("thread" to Conversation("thread", listOf(prompt,
            TimelineItem("answer", "turn", "agentMessage", "partial", true)), status = "interrupted")),
        results = mapOf("thread" to ResultReceipt("turn", "interrupted")))

    @Test fun onlyConfirmedLatestInterruptedPromptIsEditable() {
        val ws = workspace()
        assertEquals(prompt, editableInterruptedMessage(ws, "thread"))
        for (status in listOf("inProgress", "completed", "failed", ""))
            assertNull(editableInterruptedMessage(ws.copy(results = mapOf("thread" to ResultReceipt("turn", status))), "thread"))
        assertNull(editableInterruptedMessage(ws.copy(stoppingTurns = mapOf("thread" to "turn")), "thread"))
        assertNull(editableInterruptedMessage(ws.copy(loadingHistory = true), "thread"))
        assertNull(editableInterruptedMessage(ws.copy(historySyncingThreads = setOf("thread")), "thread"))
        assertNull(editableInterruptedMessage(ws.copy(threads = ws.threads.map { it.copy(archived = true) }), "thread"))
        val next = ws.conversations.getValue("thread").copy(items = listOf(prompt, prompt.copy(id = "next", turnId = "new")))
        assertNull(editableInterruptedMessage(ws.copy(conversations = mapOf("thread" to next)), "thread"))
        assertNull(editableInterruptedMessage(ws.copy(conversations = mapOf("thread" to next.copy(activeTurn = "new"))), "thread"))
    }

    @Test fun submittedSnapshotRestoresOriginalAttachmentsAndSettingsWithoutParsingWireText() {
        val draft = Draft(text = "next task", lastSubmission = SubmittedInput("turn", "client-id", original))
        val ws = workspace().copy(drafts = mapOf("thread" to draft))
        assertEquals(original, inputForInterruptedMessage(ws, "thread", prompt.copy(text = "original\n\nAttachment: /tmp/note.txt")))
        val fallback = inputForInterruptedMessage(ws, "thread", prompt.copy(turnId = "other", text = "literal attachment reference", imagePaths = listOf("/tmp/old.png")))
        assertEquals("literal attachment reference", fallback.text)
        assertEquals(listOf("/tmp/old.png"), fallback.attachments.map { it.remotePath })
        assertTrue(fallback.attachments.single().image)
    }

    @Test fun successfulRevisionKeepsOrdinaryDraftAndCapturesTheNewSubmission() {
        val edit = InterruptedInputEdit("edit", "turn", "prompt", original.copy(text = "revised"), delivery = "sending", pendingId = "send-2")
        val draft = Draft(text = "next task", attachments = listOf(file), model = "next-model", effort = "low",
            edit = edit, lastSubmission = SubmittedInput("turn", "send-1", original))
        val after = draft.acceptedSubmission(edit.input, "turn-2", "send-2", edited = true)
        assertEquals(draft.input(), after.input())
        assertNull(after.edit)
        assertEquals(SubmittedInput("turn-2", "send-2", edit.input), after.lastSubmission)
        assertEquals(draft.input(), draft.copy(edit = null).input())
    }

    @Test fun ordinarySendClearsOnlyItsComposerAndPersistsRecoverableInput() {
        val draft = Draft(text = original.text, attachments = original.attachments, model = original.model, effort = original.effort,
            delivery = "sending", pendingId = "send")
        val after = draft.acceptedSubmission(original, "turn", "send", edited = false)
        assertEquals("", after.text)
        assertTrue(after.attachments.isEmpty())
        assertEquals("draft", after.delivery)
        assertNull(after.pendingId)
        assertEquals(original, after.lastSubmission!!.input)
    }

    @Test fun processDeathRestoresSendingRevisionAsUncertainWithoutChangingOtherDraft() {
        val draft = Draft(text = "ordinary", lastSubmission = SubmittedInput("turn", "send-1", original),
            edit = InterruptedInputEdit("edit", "turn", "prompt", original.copy(text = "edited 中文"), "sending", "send-2"))
        val restored = wireJson.decodeFromString<Draft>(wireJson.encodeToString(draft)).restored()
        assertEquals("uncertain", restored.edit!!.delivery)
        assertEquals("send-2", restored.edit!!.pendingId)
        assertEquals(draft.edit!!.input, restored.edit!!.input)
        assertEquals(draft.input(), restored.input())
        assertEquals(draft.lastSubmission, restored.lastSubmission)
        val old = wireJson.decodeFromString<Draft>("""{"text":"old draft"}""").restored()
        assertEquals("old draft", old.text)
        assertNull(old.edit)
        assertNull(old.lastSubmission)
    }
}
