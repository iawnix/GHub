package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LEGAL_NOTICE_ASSETS = listOf(
    "THIRD_PARTY_NOTICES.md",
    "GPL-3.0-only.txt",
    "Termux-LICENSE.md",
    "JLaTeXMath-LICENSE.txt",
    "JLaTeXMath-COPYING.txt",
    "Apache-2.0.txt",
    "Java-WebSocket-MIT.txt",
    "Bouncy-Castle.txt",
    "SLF4J-MIT.txt",
    "Phosphor-LICENSE.txt",
)

@Composable fun LegalNotices(onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val text by produceState("", selected) {
        value = selected?.let { name ->
            withContext(Dispatchers.IO) {
                try {
                    context.assets.open(name).bufferedReader().use { it.readText() }
                } catch (_: java.io.IOException) {
                    tr(R.string.ui_cannot_read_the_license_try_again_or)
                }
            }
        }.orEmpty()
    }
    val back = { if (selected == null) onClose() else selected = null }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ManagementPage(title = selected ?: tr(R.string.ui_open_source_licenses), onBack = back) { padding ->
            if (selected == null) {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item {
                        WorkbenchSurface(Modifier.padding(horizontal = 20.dp)) {
                            LEGAL_NOTICE_ASSETS.forEachIndexed { index, name ->
                                if (index > 0) GroupDivider()
                                PreferenceRow(
                                    title = name,
                                    icon = if (name.endsWith(".md")) WorkbenchIcons.Description else WorkbenchIcons.FileCode,
                                    onClick = { selected = name },
                                )
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    WorkbenchSurface {
                        SelectionContainer {
                            Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
    }
}
