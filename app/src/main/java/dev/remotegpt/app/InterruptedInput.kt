package dev.remotegpt.app

import dev.remotegpt.client.TimelineItem
import kotlinx.serialization.Serializable

@Serializable data class MessageInput(
    val text: String = "", val attachments: List<Attachment> = emptyList(),
    val model: String? = null, val effort: String? = null,
)
@Serializable data class SubmittedInput(val turnId: String, val clientMessageId: String, val input: MessageInput)
@Serializable data class InterruptedInputEdit(
    val id: String, val turnId: String, val itemId: String, val input: MessageInput,
    val delivery: String = "draft", val pendingId: String? = null, val error: String? = null,
)

internal fun Draft.input() = MessageInput(text, attachments, model, effort)
internal fun Draft.withInput(input: MessageInput) = copy(text = input.text, attachments = input.attachments,
    model = input.model, effort = input.effort)

/** Display the active editor without changing the separately persisted ordinary draft. */
internal fun Draft.composerDraft(): Draft = edit?.let {
    Draft(text = it.input.text, attachments = it.input.attachments, model = it.input.model,
        effort = it.input.effort, delivery = it.delivery, pendingId = it.pendingId)
} ?: this

/** Only the original prompt of the latest confirmed interrupted turn can be revised. */
internal fun editableInterruptedMessage(ws: HostWorkspace, thread: String): TimelineItem? {
    val receipt = ws.results[thread] ?: return null
    val conversation = ws.conversations[thread] ?: return null
    val summary = ws.threads.firstOrNull { it.id == thread } ?: ws.selectedSummary?.takeIf { it.id == thread }
    if (receipt.status != "interrupted" || conversation.activeTurn != null || !conversation.baselineLoaded ||
        conversation.items.lastOrNull()?.turnId != receipt.turnId || summary?.archived == true ||
        thread in ws.stoppingTurns || thread in ws.threadProblems || thread in ws.historySyncingThreads ||
        (ws.selectedThread == thread && ws.loadingHistory)) return null
    return conversation.items.firstOrNull { it.turnId == receipt.turnId && it.kind == "userMessage" }
}

internal fun inputForInterruptedMessage(ws: HostWorkspace, thread: String, item: TimelineItem): MessageInput {
    ws.drafts[thread]?.lastSubmission?.takeIf { it.turnId == item.turnId }?.let { return it.input }
    val summary = ws.threads.firstOrNull { it.id == thread } ?: ws.selectedSummary?.takeIf { it.id == thread }
    // Older/other-client messages have no original attachment metadata. Preserve their exact
    // text (including file references), and restore only image paths explicitly supplied by history.
    return MessageInput(item.text, item.imagePaths.distinct().mapIndexed { index, path ->
        Attachment("restored:${item.identityKey}:$index", path.substringAfterLast('/'), image = true,
            remotePath = path, state = "ready")
    }, summary?.model, summary?.reasoningEffort)
}

internal fun Draft.acceptedSubmission(input: MessageInput, turnId: String, clientMessageId: String, edited: Boolean): Draft {
    val submission = SubmittedInput(turnId, clientMessageId, input)
    return if (edited) copy(edit = null, lastSubmission = submission)
    else Draft(model = model, effort = effort, lastSubmission = submission)
}

internal fun Draft.restored(): Draft {
    fun MessageInput.restoredAttachments() = copy(attachments = attachments.distinctBy { it.id }.map {
        if (it.state == "uploading") it.copy(state = "failed", error = tr(R.string.ui_upload_interrupted_select_the_file_again)) else it
    })
    return withInput(input().restoredAttachments()).copy(
        delivery = if (delivery == "sending") "uncertain" else delivery,
        edit = edit?.let { it.copy(input = it.input.restoredAttachments(), delivery = if (it.delivery == "sending") "uncertain" else it.delivery) },
    )
}
