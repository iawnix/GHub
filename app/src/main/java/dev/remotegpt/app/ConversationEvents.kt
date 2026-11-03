package dev.remotegpt.app

import dev.remotegpt.client.Conversation
import dev.remotegpt.client.Timeline
import kotlinx.serialization.json.JsonObject

/** The ordered consumer buffers later events while restore reads the baseline.
 * Re-read current after suspension: another reader may have installed history. */
internal suspend fun applyConversationNotification(
    thread: String,
    method: String,
    params: JsonObject,
    current: () -> Conversation?,
    restore: suspend () -> Unit,
    isCurrent: () -> Boolean,
): Conversation? {
    if (Timeline.needsBaseline(method) && current()?.baselineLoaded != true) restore()
    if (!isCurrent()) return null
    val baseline = current() ?: return null // Metadata alone must never create a body.
    if (Timeline.needsBaseline(method) && !baseline.baselineLoaded) {
        // Reconnect/history repair can retry; never append onto an unknown disk base.
        throw java.io.IOException("Conversation body baseline is unavailable")
    }
    require(baseline.threadId == thread)
    return Timeline.notification(baseline, method, params)
}
