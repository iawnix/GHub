@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotegpt.app.*
import dev.remotegpt.client.*

@Composable fun FilesScreen(state: WorkbenchState, vm: WorkbenchViewModel, onWorkspace: () -> Unit, snackbar: SnackbarHostState? = null, onDownload: (RemoteFile) -> Unit) {
    val ws = state.workspace
    var goTo by rememberSaveable { mutableStateOf(false) }
    var directoryActions by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<RemoteFile?>(null) }
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // One line; the middle of a deep path matters least.
        Text(ws.filePath.ifBlank { tr(R.string.ui_project_directory) }, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        if (ws.loadingFiles) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ws.files.isEmpty() && ws.loadingFiles) Box(Modifier.weight(1f)) {
            EmptyPanel(WorkbenchIcons.CloudDone, tr(R.string.ui_connecting_to_remote_files), tr(R.string.ui_this_directory_will_load_automatically_when_the))
        }
        if (ws.files.isEmpty() && !ws.loadingFiles) Box(Modifier.weight(1f)) { EmptyPanel(WorkbenchIcons.FolderOpen, tr(R.string.ui_this_directory_is_empty), tr(R.string.ui_connect_to_browse_files_edit_text_or)) }
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ws.files, key = { it.path }) { file ->
                // The icon already says "folder"; the second line carries what it cannot: size and age.
                val details = fileDetails(file)
                ListItem(headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = supportingLine(details),
                    leadingContent = {
                        Icon(if (file.directory) WorkbenchIcons.Folder else if (imageName(file.name)) WorkbenchIcons.Image else fileTypeIcon(file.name),
                            null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = { IconButton(onClick = { options = file }) { Icon(WorkbenchIcons.MoreVert, tr(R.string.ui_actions_for , file.name)) } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    // Hidden entries stay reachable but recede behind the project's own files.
                    modifier = Modifier.fillMaxWidth().alpha(if (file.name.startsWith('.')) .6f else 1f).then(Modifier.clickableFile {
                        if (file.directory) vm.listFiles(file.path)
                        else if (imageName(file.name)) vm.previewImage(file.path)
                        else if (textFileName(file.name)) vm.editFile(file.path)
                        else options = file
                    }))
            }
        }
        ReachableActions(onWorkspace, tr(R.string.ui_directory), { directoryActions = true }, primaryIcon = WorkbenchIcons.FolderOpen,
            secondaryIcon = WorkbenchIcons.ArrowUpward, secondaryLabel = tr(R.string.ui_parent_directory), secondaryEnabled = ws.filePath != "/",
            onSecondary = { vm.listFiles(ws.filePath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }) })
    }
    if (directoryActions) ModalBottomSheet(onDismissRequest = { directoryActions = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(ws.filePath, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            SheetAction(tr(R.string.ui_open_directory), WorkbenchIcons.FolderOpen) { directoryActions = false; goTo = true }
            SheetAction(tr(R.string.ui_project_root), WorkbenchIcons.Home) { directoryActions = false; vm.listFiles(ws.selectedProject) }
            SheetAction(tr(R.string.ui_refresh_directory), WorkbenchIcons.Refresh) { directoryActions = false; vm.listFiles() }
        }
    }
    if (goTo) TextPrompt(tr(R.string.ui_open_remote_directory), tr(R.string.ui_absolute_path), ws.filePath, onDismiss = { goTo = false }, onConfirm = { vm.listFiles(it); goTo = false }, validate = { it.startsWith('/') })
    options?.let { file -> ModalBottomSheet(onDismissRequest = { options = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleLarge)
            if (!file.directory) Text(if (imageName(file.name)) tr(R.string.ui_image_119 , formatSize(file.size)) else formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (file.directory) SheetAction(tr(R.string.ui_open_directory), WorkbenchIcons.FolderOpen) { vm.listFiles(file.path); options = null }
            else {
                if (imageName(file.name)) SheetAction(tr(R.string.ui_preview_image), WorkbenchIcons.Image) { vm.previewImage(file.path); options = null }
                if (textFileName(file.name)) SheetAction(tr(R.string.ui_edit_text), WorkbenchIcons.Edit) { vm.editFile(file.path); options = null }
                SheetAction(tr(R.string.ui_attach_to_this_session), WorkbenchIcons.AttachFile) { vm.attachRemote(file); options = null }
                SheetAction(tr(R.string.ui_download_to_device), WorkbenchIcons.Download) { onDownload(file); options = null }
            }
        }
    } }
    ws.editor?.let { file ->
        val close = { if (ws.editorText != file.text) discard = true else vm.closeEditor() }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(snackbarHost = { snackbar?.let { SnackbarHost(it) } }, topBar = { TopAppBar(title = { Text(file.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = close) { Icon(WorkbenchIcons.ArrowBack, tr(R.string.ui_back_to_files)) } }) },
                modifier = Modifier.imePadding(), bottomBar = {
                    Surface { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = close, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(R.string.ui_back_to_files)) }
                        Button(onClick = { vm.saveFile() }, enabled = ws.editorText != file.text && ws.connection == ConnectionStatus.ONLINE, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(WorkbenchIcons.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_save)) }
                    } }
                }) { padding ->
                OutlinedTextField(ws.editorText, vm::editorText, modifier = Modifier.fillMaxSize().padding(padding), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
        if (ws.fileConflict) AlertDialog(onDismissRequest = {}, title = { Text(tr(R.string.ui_remote_file_changed)) }, text = { Text(tr(R.string.ui_the_file_changed_after_you_opened_it)) },
            confirmButton = { TextButton(onClick = { vm.saveFile(overwrite = true) }) { Text(tr(R.string.ui_overwrite_remote_file)) } }, dismissButton = { TextButton(onClick = { vm.editFile(file.path) }) { Text(tr(R.string.ui_reload)) } })
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr(R.string.ui_discard_unsaved_changes)) },
            confirmButton = { TextButton(onClick = { discard = false; vm.closeEditor() }) { Text(tr(R.string.ui_discard_changes)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(tr(R.string.ui_keep_editing)) } })
    }
}

private fun Modifier.clickableFile(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))
@Composable private fun fileDetails(file: RemoteFile): String? {
    val age = threadUpdatedMillis(file.modified)?.let {
        android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    return if (file.directory) age else listOfNotNull(formatSize(file.size), age).joinToString(" · ")
}

/** A declared return type makes the lambda composable; inline `if`/`let` forms do not. */
private fun supportingLine(text: String?): (@Composable () -> Unit)? {
    if (text == null) return null
    return { Text(text, maxLines = 1) }
}

private fun formatSize(size: Long) = when { size >= 1_048_576 -> "%.1f MiB".format(size / 1_048_576.0); size >= 1024 -> "%.1f KiB".format(size / 1024.0); else -> "${size} B" }

private fun textFileName(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "markdown", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bash", "zsh",
    "html", "css", "scss", "json", "yaml", "yml", "toml", "xml", "ini", "conf", "properties", "gradle", "sql", "log" -> true
    else -> false
}

private fun fileTypeIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go", "c", "cpp", "h", "sh", "html", "css" -> WorkbenchIcons.FileCode
    "json", "yaml", "yml", "toml", "xml", "ini", "conf", "properties" -> WorkbenchIcons.FileConfig
    "zip", "gz", "tar", "7z", "xz" -> WorkbenchIcons.FileArchive
    else -> WorkbenchIcons.Description
}
