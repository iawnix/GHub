package dev.remotegpt.app.ui

import dev.remotegpt.client.TimelineItem

/** A conversation row is the unit measured by the outer LazyColumn. */
internal sealed interface ConversationRow {
    val key: String
    val contentType: String
    val turnId: String

    data class User(val item: TimelineItem) : ConversationRow {
        override val key = "user:${item.identityKey}"
        override val contentType = "user"
        override val turnId = item.turnId
    }

    data class AssistantBlock(val item: TimelineItem, val source: String, val index: Int, val showSync: Boolean) : ConversationRow {
        override val key = "assistant:${item.identityKey}:$index"
        override val contentType = "assistant-markdown"
        override val turnId = item.turnId
    }

    data class AssistantFallback(val item: TimelineItem, val showSync: Boolean) : ConversationRow {
        override val key = "assistant:${item.identityKey}:fallback"
        override val contentType = "assistant"
        override val turnId = item.turnId
    }

    data class Question(val item: TimelineItem) : ConversationRow {
        override val key = "question:${item.identityKey}"
        override val contentType = "question"
        override val turnId = item.turnId
    }

    data class Execution(
        override val turnId: String,
        val items: List<TimelineItem>,
        val active: Boolean,
        val fileCount: Int,
        val imageCount: Int,
    ) : ConversationRow {
        override val key = "execution:$turnId"
        override val contentType = "execution"
    }

    data class Image(val item: TimelineItem) : ConversationRow {
        override val key = "image:${item.identityKey}"
        override val contentType = "image"
        override val turnId = item.turnId
    }

    data class Files(override val turnId: String, val items: List<TimelineItem>) : ConversationRow {
        override val key = "files:$turnId"
        override val contentType = "files"
    }

    data class Reply(override val turnId: String, val item: TimelineItem, val completed: Boolean) : ConversationRow {
        override val key = "reply:$turnId"
        override val contentType = "reply-actions"
    }
}

/**
 * Turns remain the protocol grouping, while rows become the rendering grouping.
 * Assistant messages are deliberately emitted in their original order. Tool
 * events stay in one compact execution row, so progress text never disappears
 * when a later assistant message arrives.
 */
internal fun buildConversationRows(
    turns: List<Map.Entry<String, List<TimelineItem>>>,
    markdownBlocks: Map<String, List<String>>,
    activeTurn: String?,
    completedTurn: String?,
    turnStatus: String?,
): List<ConversationRow> = buildList {
    turns.forEach { (turnId, items) ->
        items.filter { it.kind == "userMessage" }.forEach { add(ConversationRow.User(it)) }

        items.filter { it.kind == "agentMessage" && it.questions.isNotEmpty() }
            .forEach { add(ConversationRow.Question(it)) }

        val assistantItems = items.filter { it.kind == "agentMessage" && it.questions.isEmpty() }
        assistantItems.forEachIndexed { assistantIndex, item ->
            val showSync = assistantIndex == assistantItems.lastIndex
            val blocks = markdownBlocks[item.identityKey]
            if (blocks.isNullOrEmpty()) add(ConversationRow.AssistantFallback(item, showSync))
            else blocks.forEachIndexed { index, source -> add(ConversationRow.AssistantBlock(item, source, index, showSync)) }
        }

        val steps = items.filter {
            it.kind != "userMessage" && it.kind != "agentMessage" &&
                it.kind != "fileChange" && it.kind != "imageView" && it.questions.isEmpty()
        }
        if (steps.isNotEmpty() || activeTurn == turnId) {
            add(ConversationRow.Execution(turnId, steps, activeTurn == turnId,
                items.count { it.kind == "fileChange" }, items.count { it.kind == "imageView" }))
        }
        items.filter { it.kind == "imageView" }.forEach { add(ConversationRow.Image(it)) }
        val files = items.filter { it.kind == "fileChange" }
        if (files.isNotEmpty()) add(ConversationRow.Files(turnId, files))

        // Reply actions belong to the final complete assistant message only.
        val answer = items.lastOrNull { it.kind == "agentMessage" && it.questions.isEmpty() }
        if (answer?.complete == true && activeTurn != turnId) {
            add(ConversationRow.Reply(turnId, answer, completed = turnStatus == "completed" && completedTurn == turnId))
        }
    }
}
