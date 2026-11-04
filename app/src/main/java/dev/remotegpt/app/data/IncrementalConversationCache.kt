package dev.remotegpt.app.data

import dev.remotegpt.client.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val ITEM_CACHE_PREFIX = "remote-gpt-cache-v4\n"
@Serializable private data class Manifest(val conversation: Conversation, val items: List<String>)

/** A compact immutable baseline, held only for bodies currently resident in memory. */
internal data class ConversationCachePatch(
    val snapshot: Conversation,
    val header: String?,
    val reset: Boolean,
    val removed: List<String>,
    val changed: Map<String, List<String>>,
) {
    val empty get() = header == null && removed.isEmpty() && changed.isEmpty()
    val characters get() = (header?.length ?: 0).toLong() + changed.values.sumOf { chunks -> chunks.sumOf { it.length.toLong() } }
}

internal fun planConversationCache(previous: Conversation?, current: Conversation): ConversationCachePatch {
    val compact = compactConversation(current)
    val before = previous?.items?.associateBy { it.identityKey }.orEmpty()
    val after = compact.items.associateBy { it.identityKey }
    require(after.size == compact.items.size)
    fun manifest(c: Conversation) = Manifest(c.copy(items = emptyList(), stateRevision = 0), c.items.map { it.identityKey })
    val header = if (previous == null || manifest(previous) != manifest(compact))
        ITEM_CACHE_PREFIX + wireJson.encodeToString(manifest(compact)) else null
    val changed = after.filter { (key, item) -> before[key] != item }
        .mapValues { (_, item) -> splitCacheText(wireJson.encodeToString(item)) }
    return ConversationCachePatch(compact, header, previous == null, (before.keys - after.keys).toList(), changed)
}

/** Validate the entire manifest before establishing a writable disk baseline. */
internal fun decodeStoredConversation(rows: ConversationCacheRows): Conversation? {
    val header = rows.header.singleOrNull()?.json
    if (header?.startsWith(ITEM_CACHE_PREFIX) != true) return decodeConversationCache(rows.header.map { it.json })
    return runCatching {
        val manifest = wireJson.decodeFromString<Manifest>(header.removePrefix(ITEM_CACHE_PREFIX))
        require(manifest.items.distinct().size == manifest.items.size && manifest.items.size <= 64)
        val byItem = rows.items.groupBy { it.itemKey }
        require(byItem.keys == manifest.items.toSet())
        val items = manifest.items.map { key ->
            val chunks = byItem.getValue(key).sortedBy { it.chunkIndex }
            require(chunks.map { it.chunkIndex } == chunks.indices.toList())
            wireJson.decodeFromString<TimelineItem>(chunks.joinToString("") { it.json }).also { require(it.identityKey == key) }
        }
        manifest.conversation.copy(items = items,
            nextCursor = manifest.conversation.nextCursor.takeUnless { manifest.conversation.historyTruncated })
    }.getOrNull()
}

internal fun ConversationCacheRows.isIncremental() = header.singleOrNull()?.json?.startsWith(ITEM_CACHE_PREFIX) == true
