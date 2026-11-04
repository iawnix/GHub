@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.Draft
import dev.remotegpt.client.ModelOption

@Composable fun MessageComposer(
    draft: Draft, models: List<Pair<String, String>>, model: String?, online: Boolean, archived: Boolean,
    listening: Boolean, onText: (String) -> Unit, onRemoveAttachment: (String) -> Unit,
    onModel: (String?) -> Unit, onImage: () -> Unit, onFile: () -> Unit,
    onCamera: () -> Unit, onVoice: () -> Unit, onSend: () -> Unit,
    running: Boolean = false, onStop: () -> Unit = {}, modifier: Modifier = Modifier,
    modelOptions: List<ModelOption> = emptyList(), effort: String? = null,
    onEffort: (String?) -> Unit = {}, onReloadModels: () -> Unit = {},
) {
    var attachmentsMenu by remember { mutableStateOf(false) }
    var modelsMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    val currentModel = if (model == null) modelOptions.firstOrNull { it.isDefault } else modelOptions.firstOrNull { it.id == model }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val haptic = rememberHaptic()
    val editable = !archived && draft.delivery == "draft"
    val composerShape = RoundedCornerShape(22.dp)
    Surface(
        // ConversationViewport owns IME insets; typing never starts a separate layout animation.
        modifier = modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(6.dp, composerShape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .06f),
                spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .08f)),
        color = Color.Transparent,
        shape = composerShape,
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else interactiveOutlineColor()),
        tonalElevation = 1.dp,
    ) {
        Column {
            if (draft.attachments.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            ) {
                items(draft.attachments, key = { it.id }) { attachment ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(248.dp)) {
                        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (attachment.image && attachment.localUri.isNotBlank()) ImageThumbnail(
                                attachment.localUri, attachment.name, Modifier.size(48.dp),
                            ) else Icon(WorkbenchIcons.AttachFile, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                Text(attachment.error ?: when (attachment.state) {
                                    "uploading" -> tr(R.string.ui_uploading)
                                    "ready" -> tr(R.string.ui_ready)
                                    else -> tr(R.string.ui_upload_failed)
                                }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = if (attachment.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = editable, modifier = Modifier.size(48.dp)) {
                                Icon(WorkbenchIcons.Close, tr(R.string.ui_remove_attachment, attachment.name), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            val hasContent = draft.text.isNotBlank() || draft.attachments.isNotEmpty()
            BasicTextField(
                value = draft.text, onValueChange = onText, enabled = editable, minLines = 1, maxLines = 5,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 28.dp, max = LocalComposerTextHeight.current)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = tr(R.string.ui_message_input) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (draft.text.isEmpty()) Text(if (listening) tr(R.string.ui_listening) else tr(R.string.chat_message_placeholder),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            // Same solid surface as the text area: one capsule, no seam while content scrolls behind it.
            BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 2.dp)) {
                val compact = maxWidth < 344.dp || LocalDensity.current.fontScale > 1.3f
                val modelLabel = currentModel?.name ?: model ?: tr(R.string.ui_choose_model)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { focus.clearFocus(); attachmentsMenu = true }, enabled = editable, modifier = Modifier.size(48.dp)) {
                        Icon(WorkbenchIcons.Add, tr(R.string.ui_add_attachment), Modifier.size(22.dp))
                    }
                    if (compact) {
                        TextButton(onClick = { focus.clearFocus(); optionsMenu = true }, enabled = editable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = tr(R.string.ui_message_options_current, modelLabel, effortLabel(effort)) },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(modelLabel, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text(" · ${effortLabel(effort)}", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                    } else {
                        TextButton(onClick = { focus.clearFocus(); modelsMenu = true }, enabled = editable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = tr(R.string.ui_choose_model_current, modelLabel) },
                            contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(modelLabel, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                        TextButton(onClick = { focus.clearFocus(); effortMenu = true }, enabled = editable,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = tr(R.string.ui_reasoning_level_current, effortLabel(effort)) },
                            contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(effortLabel(effort), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                    }
                    IconButton(onClick = onVoice, enabled = editable, modifier = Modifier.size(48.dp)) {
                        Icon(if (listening) WorkbenchIcons.StopCircle else WorkbenchIcons.MicNone,
                            tr(if (listening) R.string.ui_stop_voice_input else R.string.ui_voice_input), Modifier.size(20.dp),
                            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val enabled = if (running) online else editable && online && draft.attachments.all { it.state == "ready" } && hasContent
                    IconButton(onClick = { haptic(running); if (running) onStop() else onSend() }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        // Stop is destructive for the running turn, so it must not look like Send.
                        val container = when {
                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
                            running -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Box(Modifier.size(34.dp).background(container, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            val tint = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                running -> MaterialTheme.colorScheme.onError
                                else -> MaterialTheme.colorScheme.surface
                            }
                            if (draft.delivery == "sending" && !running) CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
                            else Icon(if (running) WorkbenchIcons.Stop else WorkbenchIcons.ArrowUpward,
                                tr(if (running) R.string.ui_stop_task else R.string.ui_send_message), Modifier.size(20.dp), tint = tint)
                        }
                    }
                }
            }
        }
    }
    if (optionsMenu) ModalBottomSheet(onDismissRequest = { optionsMenu = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr(R.string.ui_message_options), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            SheetAction(tr(R.string.ui_choose_model), WorkbenchIcons.AutoAwesome,
                supporting = currentModel?.name ?: model) { optionsMenu = false; modelsMenu = true }
            SheetAction(tr(R.string.ui_reasoning_level), WorkbenchIcons.Checklist,
                supporting = effortLabel(effort)) { optionsMenu = false; effortMenu = true }
        }
    }
    if (attachmentsMenu) ModalBottomSheet(onDismissRequest = { attachmentsMenu = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr(R.string.ui_input_options), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            SheetAction(tr(R.string.ui_choose_image), WorkbenchIcons.Image, enabled = editable && online) { attachmentsMenu = false; onImage() }
            SheetAction(tr(R.string.ui_take_photo), WorkbenchIcons.PhotoCamera, enabled = editable && online) { attachmentsMenu = false; onCamera() }
            SheetAction(tr(R.string.ui_choose_file), WorkbenchIcons.AttachFile, enabled = editable && online) { attachmentsMenu = false; onFile() }
        }
    }
    if (modelsMenu) ModelPickerSheet(models, draft.model, running, online, onReloadModels,
        onSelect = { onModel(it); modelsMenu = false }, onDismiss = { modelsMenu = false })
    if (effortMenu) EffortPickerSheet(currentModel?.efforts.orEmpty(), draft.effort, draft.model == null, running,
        onSelect = { onEffort(it); effortMenu = false }, onDismiss = { effortMenu = false })


}

fun effortLabel(value: String?): String = when (value) {
    "none" -> tr(R.string.ui_off); "minimal" -> tr(R.string.ui_minimal); "low" -> tr(R.string.ui_low); "medium" -> tr(R.string.ui_medium); "high" -> tr(R.string.ui_high); "xhigh" -> tr(R.string.ui_extra_high); "max" -> tr(R.string.ui_maximum); "ultra" -> tr(R.string.ui_ultra)
    null -> tr(R.string.ui_default); else -> value
}

@Composable internal fun ModelPickerSheet(models: List<Pair<String, String>>, selected: String?, running: Boolean,
    online: Boolean, onReload: () -> Unit, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    SelectionSheet(tr(R.string.ui_choose_model),
        listOf(SelectionOption("inherit", tr(R.string.ui_use_session_settings))) +
            models.map { (id, label) -> SelectionOption("model:$id", label) },
        selected?.let { "model:$it" } ?: "inherit",
        onSelect = { onSelect(if (it == "inherit") null else it.removePrefix("model:")) }, onDismiss = onDismiss,
        hint = if (running) tr(R.string.ui_next_message_only) else null,
        emptyText = if (models.isEmpty()) tr(R.string.ui_no_models_loaded_connect_to_the_server) else null,
        onRefresh = onReload, refreshEnabled = online)
}

@Composable internal fun EffortPickerSheet(efforts: List<String>, selected: String?, followsSession: Boolean,
    running: Boolean, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    SelectionSheet(tr(R.string.ui_reasoning_level),
        listOf(SelectionOption("inherit", tr(if (followsSession) R.string.ui_use_session_settings else R.string.ui_model_default))) +
            efforts.map { SelectionOption("effort:$it", effortLabel(it)) },
        selected?.let { "effort:$it" } ?: "inherit",
        onSelect = { onSelect(if (it == "inherit") null else it.removePrefix("effort:")) }, onDismiss = onDismiss,
        hint = if (running) tr(R.string.ui_next_message_only) else null,
        emptyText = if (efforts.isEmpty()) tr(R.string.ui_this_model_does_not_offer_adjustable_reasoning) else null)
}
