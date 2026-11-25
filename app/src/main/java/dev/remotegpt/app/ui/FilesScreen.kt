@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotegpt.app.*
import dev.remotegpt.client.*

@Composable fun FilesScreen(state: WorkbenchState, vm: WorkbenchViewModel, snackbar: SnackbarHostState? = null, onDownload: (RemoteFile) -> Unit) {
    val ws = state.workspace
    var goTo by rememberSaveable { mutableStateOf(false) }
    var directoryActions by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<RemoteFile?>(null) }
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // Directory tools sit with the path they act on; the app bar keeps workspace navigation.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.listFiles(ws.filePath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }) },
                enabled = ws.filePath.startsWith('/') && ws.filePath != "/") {
                Icon(WorkbenchIcons.ArrowUpward, tr(R.string.ui_parent_directory), Modifier.size(20.dp))
            }
            PathBreadcrumbs(ws.filePath, onNavigate = vm::listFiles, modifier = Modifier.weight(1f))
            IconButton(onClick = { directoryActions = true }) {
                Icon(WorkbenchIcons.FolderOpen, tr(R.string.ui_directory), Modifier.size(20.dp))
            }
        }
        PullToRefreshBox(
            isRefreshing = ws.loadingFiles && ws.files.isNotEmpty(),
            onRefresh = { vm.listFiles() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                ws.files.isEmpty() && ws.loadingFiles -> EmptyPanel(
                    WorkbenchIcons.Refresh,
                    tr(R.string.ui_connecting_to_remote_files),
                    tr(R.string.ui_this_directory_will_load_automatically_when_the),
                )
                ws.files.isEmpty() -> EmptyPanel(
                    WorkbenchIcons.FolderOpen,
                    tr(R.string.ui_this_directory_is_empty),
                    tr(if (ws.connection == ConnectionStatus.ONLINE) R.string.ui_empty_directory_hint else R.string.ui_connect_to_browse_files_edit_text_or),
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(ws.files, key = { it.path }) { file ->
                        // The icon already says "folder"; the second line carries what it cannot: size and age.
                        val details = fileDetails(file)
                        ListItem(headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = supportingLine(details),
                            leadingContent = {
                                Icon(if (file.directory) WorkbenchIcons.Folder else if (imageName(file.name)) WorkbenchIcons.Image else fileTypeIcon(file.name),
                                    null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = { IconButton(onClick = { options = file }) { Icon(WorkbenchIcons.MoreVert, tr(R.string.ui_actions_for, file.name)) } },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                            // Hidden entries stay reachable but recede behind the project's own files.
                            modifier = Modifier.fillMaxWidth().alpha(if (file.name.startsWith('.')) .6f else 1f).then(Modifier.clickableFile {
                                if (file.directory) vm.listFiles(file.path)
                                else if (imageName(file.name)) vm.previewImage(file.path)
                                else if (textFileName(file.name) && file.size <= MAX_EDITABLE_FILE_BYTES) vm.editFile(file.path)
                                else options = file
                            }))
                    }
                }
            }
        }
    }
    if (directoryActions) ModalBottomSheet(onDismissRequest = { directoryActions = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(ws.filePath, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            SheetAction(tr(R.string.ui_open_directory), WorkbenchIcons.FolderOpen) { directoryActions = false; goTo = true }
            SheetAction(tr(R.string.ui_project_root), WorkbenchIcons.Home) { directoryActions = false; vm.listFiles(ws.selectedProject) }
        }
    }
    if (goTo) TextPrompt(tr(R.string.ui_open_remote_directory), tr(R.string.ui_absolute_path), ws.filePath, onDismiss = { goTo = false }, onConfirm = { vm.listFiles(it); goTo = false }, validate = { it.startsWith('/') })
    options?.let { file -> ModalBottomSheet(onDismissRequest = { options = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleLarge)
            if (!file.directory) Text(if (imageName(file.name)) tr(R.string.ui_image_119, formatSize(file.size)) else formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (file.directory) SheetAction(tr(R.string.ui_open_directory), WorkbenchIcons.FolderOpen) { vm.listFiles(file.path); options = null }
            else {
                if (imageName(file.name)) SheetAction(tr(R.string.ui_preview_image), WorkbenchIcons.Image) { vm.previewImage(file.path); options = null }
                if (editableTextCandidate(file)) SheetAction(tr(R.string.ui_edit_text), WorkbenchIcons.Edit) { vm.editFile(file.path); options = null }
                SheetAction(tr(R.string.ui_attach_to_this_session), WorkbenchIcons.AttachFile) { vm.attachRemote(file); options = null }
                SheetAction(tr(R.string.ui_download_to_device), WorkbenchIcons.Download) { onDownload(file); options = null }
            }
        }
    } }
    ws.editor?.let { file ->
        val dirty = ws.editorText != file.text
        val lineCount = remember(ws.editorText) { ws.editorText.count { it == '\n' } + 1 }
        val close = { if (dirty) discard = true else vm.closeEditor() }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                snackbarHost = { snackbar?.let { WorkbenchSnackbarHost(it) } },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(file.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                val linesLabel = tr(R.string.ui_editor_line_count, lineCount)
                                Text(
                                    if (dirty) "${tr(R.string.ui_unsaved_changes)} · $linesLabel" else linesLabel,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = if (dirty) LocalWorkbenchSemanticColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = { IconButton(onClick = close) { Icon(WorkbenchIcons.ArrowBack, tr(R.string.ui_back_to_files)) } },
                        actions = {
                            Button(
                                onClick = { vm.saveFile() },
                                enabled = dirty && ws.connection == ConnectionStatus.ONLINE,
                                modifier = Modifier.padding(end = 12.dp).heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(WorkbenchIcons.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.ui_save))
                            }
                        },
                    )
                },
                modifier = Modifier.imePadding(),
            ) { padding ->
                OutlinedTextField(
                    value = ws.editorText,
                    onValueChange = vm::editorText,
                    modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (ws.fileConflict) AlertDialog(onDismissRequest = {}, title = { Text(tr(R.string.ui_remote_file_changed)) }, text = { Text(tr(R.string.ui_the_file_changed_after_you_opened_it)) },
            confirmButton = { TextButton(onClick = { vm.saveFile(overwrite = true) }) { Text(tr(R.string.ui_overwrite_remote_file)) } }, dismissButton = { TextButton(onClick = { vm.editFile(file.path) }) { Text(tr(R.string.ui_reload)) } })
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr(R.string.ui_discard_unsaved_changes)) },
            confirmButton = { TextButton(onClick = { discard = false; vm.closeEditor() }) { Text(tr(R.string.ui_discard_changes)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(tr(R.string.ui_keep_editing)) } })
    }
}

@Composable private fun PathBreadcrumbs(path: String, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!path.startsWith('/')) {
        Text(path.ifBlank { tr(R.string.ui_project_directory) }, modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        return
    }
    val segments = remember(path) {
        val parts = path.trim('/').split('/').filter(String::isNotEmpty)
        buildList {
            add("/" to "/")
            var current = ""
            for (part in parts) {
                current = "$current/$part"
                add(part to current)
            }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    val haptic = rememberHaptic()
    Row(
        modifier = modifier.horizontalScroll(scroll).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, targetPath) ->
            val current = index == segments.lastIndex
            if (index > 0) {
                Icon(WorkbenchIcons.ChevronRight, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !current, role = Role.Button) { haptic(false); onNavigate(targetPath) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val MAX_EDITABLE_FILE_BYTES = 1_048_576L

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

private val knownTextBaseNames = setOf(
    "dockerfile", "makefile", "gnumakefile", "cmakelists.txt", "justfile", "procfile",
    "gemfile", "rakefile", "podfile", "vagrantfile", "license", "copying", "readme",
    "changelog", "authors", ".gitignore", ".gitattributes", ".gitmodules", ".editorconfig",
    ".npmrc", ".yarnrc", ".env", ".bashrc", ".zshrc", ".profile", ".clang-format",
)

private val knownBinaryExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif",
    "mp3", "wav", "ogg", "flac", "m4a", "aac", "mp4", "mkv", "mov", "avi", "webm",
    "zip", "gz", "tgz", "tar", "7z", "xz", "bz2", "zst", "rar", "jar", "apk", "aab", "whl",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "so", "dylib", "dll", "exe", "bin", "o", "a", "class", "dex", "wasm", "pyc",
    "ttf", "otf", "woff", "woff2", "sqlite", "db",
)

internal fun editableTextCandidate(file: RemoteFile): Boolean {
    if (file.directory || file.size > MAX_EDITABLE_FILE_BYTES) return false
    val ext = file.name.substringAfterLast('.', "").lowercase()
    return ext !in knownBinaryExtensions
}

internal fun textFileName(name: String): Boolean {
    val lower = name.lowercase()
    if (lower in knownTextBaseNames || lower.startsWith(".env.") || lower.startsWith("dockerfile.")) return true
    return when (lower.substringAfterLast('.', "")) {
        "txt", "md", "markdown", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "html", "css", "scss", "json", "yaml", "yml", "toml", "xml", "ini", "conf", "properties", "gradle", "sql", "log",
        "lock", "cmake", "mk", "proto", "graphql", "vue", "svelte", "dart", "swift", "rb", "php", "lua", "pl", "r", "scala", "ex", "exs", "zig", "csv", "tsv", "svg" -> true
        else -> false
    }
}

private fun fileTypeIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go", "c", "cpp", "h", "sh", "html", "css", "dart", "swift", "rb", "php", "lua", "zig" -> WorkbenchIcons.FileCode
    "json", "yaml", "yml", "toml", "xml", "ini", "conf", "properties", "lock", "gradle" -> WorkbenchIcons.FileConfig
    "zip", "gz", "tar", "7z", "xz", "tgz", "bz2", "zst", "rar" -> WorkbenchIcons.FileArchive
    else -> if (name.lowercase() in knownTextBaseNames) WorkbenchIcons.FileConfig else WorkbenchIcons.Description
}
