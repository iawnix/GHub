@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.Draft
import dev.remotegpt.client.ModelOption
import dev.remotegpt.client.sessionCommand
import dev.remotegpt.client.sessionCommandSuggestions

@Composable fun MessageComposer(
    draft: Draft, models: List<Pair<String, String>>, model: String?, online: Boolean, archived: Boolean,
    listening: Boolean, onText: (String) -> Unit, onRemoveAttachment: (String) -> Unit,
    onModel: (String?) -> Unit, onImage: () -> Unit, onFile: () -> Unit,
    onCamera: () -> Unit, onVoice: () -> Unit, onSend: () -> Unit,
    onQueue: () -> Unit = {},
    onSteer: () -> Unit = {},
    running: Boolean = false, stopping: Boolean = false, onStop: () -> Unit = {}, modifier: Modifier = Modifier,
    modelOptions: List<ModelOption> = emptyList(), effort: String? = null,
    onEffort: (String?) -> Unit = {}, onReloadModels: () -> Unit = {},
    revision: MessageRevisionActions? = null,
    modes: Set<String> = emptySet(), onMode: (String) -> Unit = {},
    modeMenu: Boolean = false, onOpenModeMenu: () -> Unit = {}, onDismissModeMenu: () -> Unit = {},
    goal: dev.remotegpt.client.ThreadGoal? = null, onGoal: () -> Unit = {},
    permissions: dev.remotegpt.app.PermissionSettings = dev.remotegpt.app.PermissionSettings(), onPermissions: () -> Unit = {},
    canExecutePlan: Boolean = false, onExecutePlan: () -> Unit = {},
    onCommand: (String) -> Unit = { command -> onText("$command ") },
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
    val riskyPermissions = permissions.sandbox == "danger-full-access" || permissions.approval == "never"
    val customPermissions = permissions.sandbox != "workspace-write" || permissions.approval != "on-request"
    val permissionSummary = when {
        permissions.sandbox == "danger-full-access" && permissions.approval == "never" ->
            "${tr(R.string.ui_permission_full)} · ${tr(R.string.ui_permission_auto)}"
        permissions.sandbox == "danger-full-access" -> tr(R.string.ui_permission_full)
        permissions.approval == "never" -> tr(R.string.ui_permission_auto)
        permissions.sandbox == "read-only" -> tr(R.string.ui_permission_read_only)
        permissions.approval == "untrusted" -> tr(R.string.ui_permission_untrusted)
        else -> tr(R.string.ui_permission_ask)
    }
    val composerShape = RoundedCornerShape(22.dp)
    Surface(
        // ConversationViewport owns IME insets; typing never starts a separate layout animation.
        modifier = modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(6.dp, composerShape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .06f),
                spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .08f)),
        color = MaterialTheme.colorScheme.surface,
        shape = composerShape,
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else interactiveOutlineColor()),
        tonalElevation = 1.dp,
    ) {
        Column {
            if (inputDraft.mode == "plan" || customPermissions || goal != null) {
                Column(Modifier.fillMaxWidth()) {
                    if (inputDraft.mode == "plan" || canExecutePlan || customPermissions) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (inputDraft.mode == "plan") TextButton(onClick = { onOpenModeMenu() }, enabled = editable) {
                                Icon(WorkbenchIcons.Checklist, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text(tr(R.string.ui_mode_plan))
                            }
                            if (canExecutePlan) TextButton(onClick = onExecutePlan, enabled = editable && online && !running) {
                                Text(tr(R.string.ui_plan_execute))
                            }
                            if (customPermissions) {
                                val badgeColor = if (riskyPermissions) LocalWorkbenchSemanticColors.current.warning
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                TextButton(
                                    onClick = onPermissions,
                                    enabled = online && !archived && edit == null,
                                    colors = ButtonDefaults.textButtonColors(contentColor = badgeColor),
                                ) {
                                    Icon(if (riskyPermissions) WorkbenchIcons.FrontHand else WorkbenchIcons.Key, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(permissionSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (goal != null) {
                        TextButton(onClick = onGoal, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text(goal.objective, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(6.dp)); Text(goalStatusLabel(goal.status), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                }
            }
            if (edit != null) Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
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
            val command = sessionCommand(inputDraft.text)
            val commandSuggestions = if (editable) sessionCommandSuggestions(inputDraft.text) else emptyList()
            if (commandSuggestions.isNotEmpty()) SlashCommandSuggestions(commandSuggestions, onCommand)
            val commandAction = command != null
            val queueable = hasContent && inputDraft.attachments.all { it.state == "ready" } && edit == null
            // While a turn runs, the round button always stops it. Anything typed meanwhile is
            // sent through these secondary actions, so a draft can never hide the stop control.
            if (running && (commandAction || queueable)) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                if (commandAction) TextButton(onClick = { haptic(false); onSend() }, enabled = online && !stopping) {
                    Text(tr(R.string.ui_run_slash_command))
                } else {
                    TextButton(onClick = { haptic(false); onSteer() }, enabled = online,
                        modifier = Modifier.semantics { contentDescription = tr(R.string.ui_steer_task) }) { Text(tr(R.string.ui_steer_short)) }
                    TextButton(onClick = { haptic(false); onQueue() }, enabled = online,
                        modifier = Modifier.semantics { contentDescription = tr(R.string.ui_queue_message) }) { Text(tr(R.string.ui_queue_short)) }
                }
            }
            // One solid outer capsule surface: no inner background seams while content scrolls behind it.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
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
                        commandAction -> R.string.ui_run_slash_command
                        inputDraft.delivery == "sending" -> R.string.ui_resending_input
                        edit != null -> R.string.ui_edit_and_resend
                        else -> R.string.ui_send_message
                    })
                    val sendInteractionSource = remember { MutableInteractionSource() }
                    IconButton(onClick = { haptic(true); if (running) onStop() else onSend() }, enabled = enabled,
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            // Keep the actions people use to add content at the top of the sheet.
            // This is also important on short or narrow displays where the sheet
            // cannot show every action without scrolling.
            SheetAction(tr(R.string.ui_choose_image), WorkbenchIcons.Image, enabled = editable && online) { attachmentsMenu = false; onImage() }
            SheetAction(tr(R.string.ui_take_photo), WorkbenchIcons.PhotoCamera, enabled = editable && online) { attachmentsMenu = false; onCamera() }
            SheetAction(tr(R.string.ui_choose_file), WorkbenchIcons.AttachFile, enabled = editable && online) { attachmentsMenu = false; onFile() }
            SheetAction(tr(R.string.ui_mode_title), WorkbenchIcons.Checklist, enabled = editable,
                supporting = tr(if (inputDraft.mode == "plan") R.string.ui_mode_plan else R.string.ui_mode_execute)) {
                attachmentsMenu = false; onOpenModeMenu()
            }
            SheetAction(tr(R.string.ui_goal_title), WorkbenchIcons.AutoAwesome, enabled = online && !archived && edit == null) {
                attachmentsMenu = false; onGoal()
            }
            SheetAction(tr(R.string.ui_permissions), WorkbenchIcons.Key, enabled = online && !archived && edit == null,
                supporting = permissionSummary) {
                attachmentsMenu = false; onPermissions()
            }
        }
    }
    if (modeMenu) SelectionSheet(tr(R.string.ui_mode_title),
        listOf(SelectionOption("default", tr(R.string.ui_mode_execute)), SelectionOption("plan", tr(R.string.ui_mode_plan))),
        inputDraft.mode ?: "default", onSelect = { onMode(it); onDismissModeMenu() }, onDismiss = onDismissModeMenu,
        hint = if (modes.isEmpty()) tr(R.string.ui_mode_unavailable) else tr(R.string.ui_mode_hint))
    if (modelsMenu) ModelPickerSheet(models, inputDraft.model, running, online, onReloadModels,
        onSelect = { onModel(it); modelsMenu = false }, onDismiss = { modelsMenu = false })
    if (effortMenu) EffortPickerSheet(currentModel?.efforts.orEmpty(), inputDraft.effort, inputDraft.model == null, running,
        onSelect = { onEffort(it); effortMenu = false }, onDismiss = { effortMenu = false })
}

@Composable private fun SlashCommandSuggestions(commands: List<String>, onCommand: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(tr(R.string.ui_slash_commands), Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            commands.forEach { command ->
                val description = tr(if (command == "/plan") R.string.ui_slash_plan_description else R.string.ui_slash_goal_description)
                Surface(
                    onClick = { onCommand(command) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(command, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(WorkbenchIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun PermissionPickerSheet(current: dev.remotegpt.app.PermissionSettings, onApply: (String, String) -> Unit, onDismiss: () -> Unit) {
    var approval by remember(current) { mutableStateOf(current.approval) }
    var sandbox by remember(current) { mutableStateOf(current.sandbox) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(tr(R.string.ui_permissions), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            Text(tr(R.string.ui_permission_scope), Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple("read-only", R.string.ui_permission_read_only, R.string.ui_permission_read_only_detail),
                    Triple("workspace-write", R.string.ui_permission_workspace, R.string.ui_permission_workspace_detail),
                    Triple("danger-full-access", R.string.ui_permission_full, R.string.ui_permission_full_detail),
                ).forEach { (value, label, detail) ->
                    PermissionOptionRow(tr(label), tr(detail), selected = sandbox == value, risky = value == "danger-full-access") {
                        sandbox = value
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(tr(R.string.ui_permission_approval), Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple("on-request", R.string.ui_permission_ask, R.string.ui_permission_ask_detail),
                    Triple("untrusted", R.string.ui_permission_untrusted, R.string.ui_permission_untrusted_detail),
                    Triple("never", R.string.ui_permission_auto, R.string.ui_permission_auto_detail),
                ).forEach { (value, label, detail) ->
                    PermissionOptionRow(tr(label), tr(detail), selected = approval == value, risky = value == "never") {
                        approval = value
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onApply(approval, sandbox); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp),
            ) {
                Text(tr(R.string.ui_save))
            }
        }
    }
}

@Composable private fun PermissionOptionRow(label: String, detail: String, selected: Boolean, risky: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val detailColor = when {
        risky && selected -> LocalWorkbenchSemanticColors.current.warning
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .86f) else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = { haptic(false); onClick() })
            .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        }
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(WorkbenchIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
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
    val values = efforts.distinct()
    val defaultOption = SelectionOption("inherit", tr(if (followsSession) R.string.ui_use_session_settings else R.string.ui_model_default))
    SelectionSheet(
        title = tr(R.string.ui_reasoning_level),
        options = if (values.isEmpty()) listOf(defaultOption) else listOf(defaultOption) + values.map { SelectionOption("effort:$it", effortLabel(it)) },
        selected = selected?.let { "effort:$it" } ?: "inherit",
        onSelect = { choice ->
            onSelect(if (choice == "inherit") null else choice.removePrefix("effort:"))
            onDismiss()
        },
        onDismiss = onDismiss,
        hint = if (running) tr(R.string.ui_next_message_only) else null,
        emptyText = if (values.isEmpty()) tr(R.string.ui_this_model_does_not_offer_adjustable_reasoning) else null,
    )
}
