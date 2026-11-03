package dev.remotegpt.app.data

import dev.remotegpt.client.Conversation
import dev.remotegpt.client.wireJson
import kotlinx.serialization.encodeToString

private const val CHUNK_PREFIX = "remote-gpt-cache-v3\n"
private const val OLD_CHUNK_PREFIX = "remote-gpt-cache-v2\n"

/** Split serialized data, not Markdown. Cutting the start of a reply can remove
 * fences/table headers and makes the cached answer render differently after reopening. */
internal fun encodeConversationCache(conversation: Conversation): List<String> {
    require(conversation.baselineLoaded) { "Cannot persist a conversation without its body baseline" }
    val truncated = conversation.historyTruncated || conversation.items.size > 64
    val compact = conversation.copy(items = conversation.items.takeLast(64).map { item ->
        item.copy(
            text = if (item.kind in setOf("agentMessage", "userMessage")) item.text else item.text.takeLast(8_192),
            imagePaths = item.imagePaths.take(8),
            fileEdits = item.fileEdits.take(8).map { it.copy(diff = it.diff.takeLast(8_192)) },
            commandExecution = item.commandExecution?.let {
                it.copy(outputTruncated = it.outputTruncated || item.text.length > 8_192)
            },
        )
    }, historyTruncated = truncated, nextCursor = conversation.nextCursor.takeUnless { truncated })
    val json = wireJson.encodeToString(compact)
    return buildList {
        var start = 0
        while (start < json.length) {
            var end = minOf(start + 32_768, json.length)
            // SQLite stores UTF-8: never split a UTF-16 surrogate pair between rows.
            if (end < json.length && json[end - 1].isHighSurrogate()) end--
            add(CHUNK_PREFIX + json.substring(start, end))
            start = end
        }
    }
}

internal fun decodeConversationCache(chunks: List<String>): Conversation? {
    if (chunks.isEmpty()) return null
    return runCatching {
        if (chunks.first().startsWith(CHUNK_PREFIX) || chunks.first().startsWith(OLD_CHUNK_PREFIX)) {
            val prefix = if (chunks.first().startsWith(CHUNK_PREFIX)) CHUNK_PREFIX else OLD_CHUNK_PREFIX
            require(chunks.all { it.startsWith(prefix) })
            val restored = wireJson.decodeFromString<Conversation>(chunks.joinToString("") { it.removePrefix(prefix) })
            // Old versions did not record whether the 64-item window was truncated.
            if (prefix == OLD_CHUNK_PREFIX || restored.historyTruncated) restored.copy(historyTruncated = true, nextCursor = null) else restored
        } else {
            // Existing installed versions stored one complete Conversation per row.
            chunks.map { wireJson.decodeFromString<Conversation>(it) }.reduce { first, next ->
                first.copy(items = first.items + next.items, activeTurn = next.activeTurn,
                    nextCursor = next.nextCursor, status = next.status)
            }.copy(historyTruncated = true, nextCursor = null)
        }
    }.getOrNull()
}
