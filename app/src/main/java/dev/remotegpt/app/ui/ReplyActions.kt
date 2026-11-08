package dev.remotegpt.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

/** Actions belong to the finished answer, after its file summary. */
@Composable internal fun ReplyActions(text: String, completed: Boolean = false) {
    val context = LocalContext.current
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ConversationCopyButton(text, tr(R.string.chat_copy_reply), beforeCopy = {
            if (text.length > 100_000) {
                Toast.makeText(context, tr(R.string.chat_copy_long_reply), Toast.LENGTH_SHORT).show()
                false
            } else true
        })
        ConversationActionButton(WorkbenchIcons.Share, tr(R.string.chat_share_reply), enabled = !preparing, onClick = {
            preparing = true
            scope.launch {
                try {
                    context.startActivity(Intent.createChooser(replyShareIntent(context, text), tr(R.string.chat_share_reply)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    Toast.makeText(context, tr(R.string.chat_share_unavailable), Toast.LENGTH_SHORT).show()
                } finally { preparing = false }
            }
        })
        Spacer(Modifier.weight(1f))
        if (completed) TaskStatusLabel(TaskStatus.COMPLETED)
    }
}

/** Keep large replies out of Binder extras; share the complete UTF-8 file instead. */
internal suspend fun replyShareIntent(context: Context, text: String): Intent {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
    if (text.length <= 64_000) return intent.putExtra(Intent.EXTRA_TEXT, text)
    return withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "shared/reply-${UUID.randomUUID()}.md")
        file.parentFile?.mkdirs()
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
            clipData = ClipData.newRawUri("Reply", uri)
        }
    }
}
