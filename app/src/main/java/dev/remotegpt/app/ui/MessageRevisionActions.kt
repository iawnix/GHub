package dev.remotegpt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.remotegpt.app.InterruptedInputEdit
import dev.remotegpt.app.R
import dev.remotegpt.app.tr

class MessageRevisionActions(
    val editableItemId: String?, val edit: InterruptedInputEdit?, val canResend: Boolean,
    val onBegin: (String) -> Unit, val onCancel: () -> Unit,
    val onRefresh: () -> Unit, val onResolveUncertain: () -> Unit,
)

@Composable internal fun UserMessageActions(text: String, onEdit: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ConversationCopyButton(text, tr(R.string.ui_copy_message))
        if (onEdit != null) ConversationActionButton(WorkbenchIcons.Edit, tr(R.string.ui_edit_interrupted_input), onEdit)
    }
}
