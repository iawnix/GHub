package dev.remotegpt.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import dev.remotegpt.client.TimelineItem
import dev.remotegpt.client.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val toolJson = Json { prettyPrint = true }

@Composable internal fun DynamicToolCallContent(item: TimelineItem) {
    val details = item.dynamicToolCall
    if (details == null) {
        Text(tr(R.string.ui_tool_legacy_record), style = MaterialTheme.typography.bodySmall)
        return
    }
    val arguments = remember(details.arguments) { toolJson.encodeToString(JsonElement.serializer(), details.arguments) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskStatusLabel(itemTaskStatus(item))
        details.durationMs?.let { ms ->
            val duration = durationText(ms)
            Text(tr(duration.id, *duration.args.toTypedArray()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CodeBlock(arguments, "json", tr(R.string.ui_tool_arguments), tr(R.string.ui_copy_tool_arguments))
        if (details.contentItems.isNullOrEmpty()) {
            Text(tr(if (item.complete) R.string.ui_tool_no_result else R.string.ui_tool_waiting_result),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        details.contentItems.orEmpty().forEach { content ->
            val type = content.string("type")
            val value = when (type) {
                "inputText" -> content.string("text")
                "inputImage" -> content.string("imageUrl")
                "inputAudio" -> content.string("audioUrl")
                else -> null
            }
            val title = when (type) {
                "inputText" -> R.string.ui_tool_result
                "inputImage" -> R.string.ui_tool_image_result
                "inputAudio" -> R.string.ui_tool_audio_result
                else -> R.string.ui_tool_other_result
            }
            // Media URLs are not remote filesystem paths. Keep them readable/copyable;
            // preserve future content types as JSON rather than silently losing their payload.
            val source = remember(content) { value ?: toolJson.encodeToString(JsonElement.serializer(), content) }
            CodeBlock(source, if (value == null) "json" else "text", tr(title), tr(R.string.ui_copy_output))
        }
    }
}
