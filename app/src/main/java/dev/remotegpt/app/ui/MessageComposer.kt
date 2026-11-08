@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.remotegpt.app.composerDraft
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
    running: Boolean = false, stopping: Boolean = false, onStop: () -> Unit = {}, modifier: Modifier = Modifier,
    modelOptions: List<ModelOption> = emptyList(), effort: String? = null,
    onEffort: (String?) -> Unit = {}, onReloadModels: () -> Unit = {},
    revision: MessageRevisionActions? = null,
) {
    val edit = draft.edit
    val inputDraft = draft.composerDraft()
    val textFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(edit?.id) {
        if (edit?.delivery == "draft" && !archived) {
            textFocus.requestFocus()
            keyboard?.show()
        }
    }
    var attachmentsMenu by remember { mutableStateOf(false) }
    var modelsMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    val currentModel = if (model == null) modelOptions.firstOrNull { it.isDefault } else modelOptions.firstOrNull { it.id == model }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val haptic = rememberHaptic()
    val editable = !archived && inputDraft.delivery == "draft"
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
            if (edit != null) Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(WorkbenchIcons.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr(R.string.ui_editing_interrupted_input), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { revision?.onCancel?.invoke() }, enabled = edit.delivery == "draft") {
                    Text(tr(R.string.ui_cancel))
                }
            }
            if (inputDraft.attachments.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            ) {
                items(inputDraft.attachments, key = { it.id }) { attachment ->
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
                            IconButton(onClick = { haptic(false); onRemoveAttachment(attachment.id) }, enabled = editable, modifier = Modifier.size(48.dp)) {
                                Icon(WorkbenchIcons.Close, tr(R.string.ui_remove_attachment, attachment.name), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            val hasContent = inputDraft.text.isNotBlank() || inputDraft.attachments.isNotEmpty()
            BasicTextField(
                value = inputDraft.text, onValueChange = onText, enabled = editable, minLines = 1, maxLines = 5,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 28.dp, max = LocalComposerTextHeight.current)
                    .focusRequester(textFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = tr(if (edit != null) R.string.ui_edit_interrupted_text else R.string.ui_message_input) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (inputDraft.text.isEmpty()) Text(if (listening) tr(R.string.ui_listening) else tr(R.string.chat_message_placeholder),
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
                    IconButton(onClick = { haptic(false); focus.clearFocus(); attachmentsMenu = true }, enabled = editable, modifier = Modifier.size(48.dp)) {
                        Icon(WorkbenchIcons.Add, tr(R.string.ui_add_attachment), Modifier.size(22.dp))
                    }
                    if (compact) {
                        TextButton(onClick = { haptic(false); focus.clearFocus(); optionsMenu = true }, enabled = editable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = tr(R.string.ui_message_options_current, modelLabel, effortLabel(effort)) },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text("$modelLabel · ${effortLabel(effort)}", Modifier.weight(1f, fill = false),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                    } else {
                        TextButton(onClick = { haptic(false); focus.clearFocus(); modelsMenu = true }, enabled = editable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = tr(R.string.ui_choose_model_current, modelLabel) },
                            contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(modelLabel, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                        TextButton(onClick = { haptic(false); focus.clearFocus(); effortMenu = true }, enabled = editable,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = tr(R.string.ui_reasoning_level_current, effortLabel(effort)) },
                            contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(effortLabel(effort), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(12.dp))
                        }
                    }
                    IconButton(onClick = { haptic(false); onVoice() }, enabled = editable, modifier = Modifier.size(48.dp)) {
                        Icon(if (listening) WorkbenchIcons.StopCircle else WorkbenchIcons.MicNone,
                            tr(if (listening) R.string.ui_stop_voice_input else R.string.ui_voice_input), Modifier.size(20.dp),
                            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val enabled = if (running) online && !stopping else editable && online && inputDraft.attachments.all { it.state == "ready" } && hasContent && (edit == null || revision?.canResend == true)
                    val sendLabel = tr(when {
                        stopping -> R.string.ui_stopping_task
                        running -> R.string.ui_stop_task
                        inputDraft.delivery == "sending" -> R.string.ui_resending_input
                        edit != null -> R.string.ui_edit_and_resend
                        else -> R.string.ui_send_message
                    })
                    val sendInteractionSource = remember { MutableInteractionSource() }
                    IconButton(onClick = { haptic(running); if (running) onStop() else onSend() }, enabled = enabled,
                        interactionSource = sendInteractionSource,
                        modifier = Modifier.size(48.dp).workbenchPressScale(enabled, sendInteractionSource)
                            .semantics { contentDescription = sendLabel }) {
                        // Stop is destructive for the running turn, so it must not look like Send.
                        val container by animateColorAsState(when {
                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
                            running -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }, Motion.short(), label = "send-container")
                        Box(Modifier.size(34.dp).background(container, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            val tint by animateColorAsState(when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                running -> MaterialTheme.colorScheme.onError
                                else -> MaterialTheme.colorScheme.surface
                            }, Motion.short(), label = "send-tint")
                            val glyph = when {
                                inputDraft.delivery == "sending" && !running -> 2
                                running -> 1
                                else -> 0
                            }
                            AnimatedContent(glyph, transitionSpec = { swap() }, contentAlignment = Alignment.Center, label = "send-glyph") { target ->
                                when (target) {
                                    2 -> CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
                                    1 -> Icon(WorkbenchIcons.Stop, null, Modifier.size(20.dp), tint = tint)
                                    else -> Icon(WorkbenchIcons.ArrowUpward, null, Modifier.size(20.dp), tint = tint)
                                }
                            }
                        }
                    }
                }
            }
            if (edit != null && (edit.error != null || edit.delivery == "uncertain" || (edit.delivery == "draft" && revision?.canResend != true))) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    edit.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (edit.delivery == "uncertain") {
                        Text(tr(R.string.ui_your_last_message_has_an_unconfirmed_delivery), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { revision?.onRefresh?.invoke() }, enabled = online) { Text(tr(R.string.ui_refresh_edit_history)) }
                        TextButton(onClick = { revision?.onResolveUncertain?.invoke() }) { Text(tr(R.string.ui_history_checked_unlock_draft)) }
                    } else if (edit.delivery == "draft" && revision?.canResend != true) {
                        Text(tr(R.string.ui_edit_wait_for_sync), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (modelsMenu) ModelPickerSheet(models, inputDraft.model, running, online, onReloadModels,
        onSelect = { onModel(it); modelsMenu = false }, onDismiss = { modelsMenu = false })
    if (effortMenu) EffortPickerSheet(currentModel?.efforts.orEmpty(), inputDraft.effort, inputDraft.model == null, running,
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
