package dev.remotegpt.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.InterruptedInputEdit
import dev.remotegpt.app.R
import dev.remotegpt.app.tr

class MessageRevisionActions(
    val editableItemId: String?, val edit: InterruptedInputEdit?, val canResend: Boolean,
    val onBegin: (String) -> Unit, val onCancel: () -> Unit,
    val onRefresh: () -> Unit, val onResolveUncertain: () -> Unit,
)

@Composable internal fun UserMessageActions(text: String, onEdit: (() -> Unit)?) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", text))
        }, modifier = Modifier.size(48.dp)) {
            Icon(WorkbenchIcons.Copy, tr(R.string.ui_copy_message), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onEdit != null) IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(WorkbenchIcons.Edit, tr(R.string.ui_edit_interrupted_input), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
