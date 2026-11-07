package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun LegalNotices(onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    val text by produceState("", selected) {
        value = selected?.let { name -> withContext(Dispatchers.IO) { try { context.assets.open(name).bufferedReader().use { it.readText() } } catch (_: java.io.IOException) { tr(R.string.ui_cannot_read_the_license_try_again_or) } } }.orEmpty()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
                Row {
                    TextButton(onClick = { if (selected == null) onClose() else selected = null }) { Text(if (selected == null) tr(R.string.ui_close) else tr(R.string.ui_back)) }
                }
                Text(selected ?: tr(R.string.ui_open_source_licenses), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                if (selected == null) {
                    listOf("THIRD_PARTY_NOTICES.md", "GPL-3.0-only.txt", "Termux-LICENSE.md", "Apache-2.0.txt", "Java-WebSocket-MIT.txt", "Bouncy-Castle.txt", "SLF4J-MIT.txt", "Phosphor-LICENSE.txt").forEach { name ->
                        TextButton(onClick = { selected = name }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                    }
                } else SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
