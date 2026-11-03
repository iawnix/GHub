package dev.remotegpt.app

import dev.remotegpt.client.Conversation

/** Budgets inactive history only; active work and unsent drafts must never be discarded. */
internal class ConversationMemoryBudget {
    private val recency = linkedSetOf<String>()
    fun touch(thread: String) { recency.remove(thread); recency.add(thread) }
    fun evictions(conversations: Map<String, Conversation>, pinned: Set<String>, aggressive: Boolean = false): List<String> {
        recency.retainAll(conversations.keys)
        conversations.keys.filterNot { it in recency }.forEach { recency.add(it) }
        val inactive = recency.filter { it !in pinned && conversations[it]?.activeTurn == null }
        var count = inactive.size
        var characters = inactive.sumOf { size(conversations.getValue(it)) }
        return inactive.filter { thread ->
            if (aggressive || count > 8 || characters > 4_000_000L) {
                count--; characters -= size(conversations.getValue(thread)); true
            } else false
        }
    }
    private fun size(c: Conversation): Long = c.items.sumOf { item ->
        128L + item.text.length + item.imagePaths.sumOf { it.length.toLong() } +
            (item.commandExecution?.command?.length ?: 0) + (item.commandExecution?.workingDirectory?.length ?: 0) +
            item.fileEdits.sumOf { it.path.length.toLong() + it.diff.length } +
            item.questions.sumOf { it.title.length.toLong() + it.options.sumOf { option -> option.length.toLong() } }
    }
}
