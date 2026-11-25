@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import dev.remotegpt.app.*
import dev.remotegpt.client.*

@Composable fun ChatScreen(state: WorkbenchState, vm: WorkbenchViewModel, listening: Boolean, onImage: () -> Unit, onFile: () -> Unit, onCamera: () -> Unit, onVoice: () -> Unit, optionsOpen: Boolean = false, onOptionsChange: (Boolean) -> Unit = {}, onBottomControls: (Int) -> Unit = {}) {
    val ws = state.workspace
    val thread = ws.selectedThread ?: return
    SideEffect { state.activeHost?.let { vm.conversationComposed(it, state.conversation) } }
    val timeline = remember(state.conversation?.items) {
        state.conversation?.let { Timeline.normalized(it).items }.orEmpty()
    }
    val approvals = ws.approvals.filter { it.request.threadId == thread }
    val questions = ws.pendingQuestions.filter { it.request.threadId == thread }
    val turns = remember(timeline) { timeline.groupBy { it.turnId }.entries.toList() }
    val turnStatus = ws.results[thread]?.status?.takeIf { it.isNotBlank() } ?: state.conversation?.status
    val completedTurn = ws.results[thread]?.completedTurnId
    val assistantItems = remember(timeline) {
        timeline.filter { it.kind in setOf("agentMessage", "plan") && it.questions.isEmpty() }
    }
    val markdownParsers = remember { mutableMapOf<String, ChatMarkdownParser>() }
    val parsedCache = remember { mutableMapOf<String, Pair<String, List<PreparsedMarkdownBlock>>>() }
    var markdownBlocks by remember { mutableStateOf<Map<String, List<PreparsedMarkdownBlock>>>(emptyMap()) }
    LaunchedEffect(assistantItems) {
        val activeKeys = assistantItems.mapTo(HashSet()) { it.identityKey }
        markdownParsers.keys.retainAll(activeKeys)
        parsedCache.keys.retainAll(activeKeys)
        val toParse = assistantItems.mapNotNull { item ->
            if (parsedCache[item.identityKey]?.first == item.text) null
            else Triple(item.identityKey, item.text, markdownParsers.getOrPut(item.identityKey) { ChatMarkdownParser() })
        }
        if (toParse.isNotEmpty()) {
            val freshlyParsed = withContext(Dispatchers.Default) {
                toParse.map { (key, text, parser) ->
                    val blocks = try {
                        val result = parser.parse(text)
                        (result.node as ChatMarkdownRoot).blocks.map { block ->
                            PreparsedMarkdownBlock(block, result.referenceLinkHandler)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Triple(key, text, blocks)
                }
            }
            for ((key, text, blocks) in freshlyParsed) {
                parsedCache[key] = text to blocks
            }
        }
        markdownBlocks = buildMap(assistantItems.size) {
            for (item in assistantItems) {
                put(item.identityKey, parsedCache[item.identityKey]?.second.orEmpty())
            }
        }
    }
    val rows = remember(timeline, markdownBlocks, state.conversation?.activeTurn, completedTurn, turnStatus) {
        buildConversationRowsWithAst(turns, markdownBlocks, state.conversation?.activeTurn,
            completedTurn, turnStatus)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // A live event is immediately useful, but a completed turn is only shown
    // as authoritative after its history has been reconciled with the daemon.
    val outputSyncing = thread in ws.historySyncingThreads || ws.loadingHistory || ws.connection in setOf(
        ConnectionStatus.CHECKING, ConnectionStatus.RECONNECTING, ConnectionStatus.SYNCING,
    )
    val outputConnected = ws.connection == ConnectionStatus.ONLINE
    val draft = state.draft
    val composerDraft = draft.composerDraft()
    val archived = state.thread?.archived == true
    val problem = ws.threadProblems[thread]
    val listState = rememberLazyListState()
    var rename by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }
    // These values live in Workbench's per-host/per-thread SaveableStateProvider,
    // together with rememberLazyListState's saved index and pixel offset.
    var followingLatest by rememberSaveable(thread) { mutableStateOf(true) }
    var userScrolling by remember(thread) { mutableStateOf(false) }
    var newContent by rememberSaveable(thread) { mutableStateOf(false) }
    val completedContentLoaded = timeline.any { it.turnId == completedTurn && it.complete }
    val scope = rememberCoroutineScope()
    var seenCompletion by rememberSaveable(thread) { mutableStateOf(completedTurn) }
    val latestVersion = timeline.lastOrNull()?.let { "${it.identityKey}:${it.text.hashCode()}:${it.complete}" }
    var seenVersion by rememberSaveable(thread) { mutableStateOf(latestVersion) }
    val bottomTolerance = with(LocalDensity.current) { 32.dp.roundToPx() }
    val nearBottom by remember(listState, bottomTolerance) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null && (!listState.canScrollForward ||
                last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset <= bottomTolerance)
        }
    }
    val scrollIntent = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    // Stop before the user's scroll, never in response to content
                    // growth or an IME resize. Do not consume the gesture.
                    followingLatest = false
                    userScrolling = true
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(userScrolling, listState.isScrollInProgress, nearBottom) }
            .collect { (user, scrolling, bottom) ->
                if (user && !scrolling) {
                    followingLatest = bottom
                    userScrolling = false
                }
            }
    }
    LaunchedEffect(listState) {
        // Observe measured geometry instead of tokens. A conflated collector
        // keeps up with continuous output without debounce starvation. Rows are
        // already flattened, so the last real row is a reliable scroll target.
        snapshotFlow {
            val layout = listState.layoutInfo
            if (followingLatest && !userScrolling && !listState.isScrollInProgress && listState.canScrollForward) {
                Triple(layout.totalItemsCount, layout.viewportEndOffset to layout.afterContentPadding,
                    layout.visibleItemsInfo.lastOrNull()?.let { Triple(it.key, it.offset, it.size) })
            } else null
        }.conflate().collect {
            // Dock/IME resizing can publish geometry during layout. Defer the scroll
            // until the next frame so scrollToItem cannot recursively remeasure it.
            withFrameNanos { }
            val layout = listState.layoutInfo
            if (followingLatest && !userScrolling && !listState.isScrollInProgress &&
                layout.totalItemsCount > 0 && listState.canScrollForward) {
                try {
                    coroutineScope { listState.scrollToItem(layout.totalItemsCount - 1) }
                } catch (cancelled: CancellationException) {
                    // A user gesture may cancel this scroll mutation. Keep the
                    // observer alive, but propagate disposal of the screen.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    LaunchedEffect(latestVersion, completedTurn, followingLatest, nearBottom) {
        if (followingLatest && nearBottom) {
            newContent = false
            seenVersion = latestVersion
            seenCompletion = completedTurn
        } else if (!followingLatest && latestVersion != seenVersion) {
            newContent = true
        }
    }
    LaunchedEffect(thread, completedTurn, completedContentLoaded, followingLatest, nearBottom, lifecycle) {
        if (followingLatest && nearBottom && completedContentLoaded) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                vm.markResultRead(thread)
                awaitCancellation()
            }
        }
    }
    // Approvals and blocking questions follow the turns in the list (see items below).
    val editableMessage = editableInterruptedMessage(ws, thread)
    val revision = MessageRevisionActions(
        editableItemId = editableMessage?.id?.takeIf { draft.delivery == "draft" }, edit = draft.edit,
        canResend = ws.connection == ConnectionStatus.ONLINE && editableMessage?.let { it.id == draft.edit?.itemId && it.turnId == draft.edit?.turnId } == true,
        onBegin = { id -> if (listening) onVoice(); followingLatest = false; vm.editInterruptedInput(id) },
        onCancel = { if (listening) onVoice(); vm.cancelInterruptedEdit() },
        onRefresh = { vm.refreshConversation() }, onResolveUncertain = { confirmRetry = true },
    )
    if (ws.goalEditorThread == thread) GoalEditorSheet(
        goal = ws.goals[thread], initialObjective = dev.remotegpt.client.sessionCommand(draft.text)
            ?.takeIf { it.name == "/goal" }?.argument.orEmpty(),
        supported = ws.goalSupported == true, busy = thread in ws.goalBusy,
        enabled = ws.connection == ConnectionStatus.ONLINE && !archived,
        onDismiss = { vm.showGoalEditor(false) },
        onSave = { objective, budget -> vm.changeGoal(thread, objective = objective, budget = budget) },
        onStatus = { vm.changeGoal(thread, status = it) }, onClear = { vm.changeGoal(thread, clear = true) },
    )
    val showEmpty = problem == null && timeline.isEmpty() && !ws.loadingHistory && ws.connection == ConnectionStatus.ONLINE
    val firstPendingIndex = (if (state.conversation?.nextCursor != null) 1 else 0) + (if (showEmpty) 1 else 0) + rows.size
    val blockingQuestions = questions.count { it.request.isBlocking }
    val pendingInView by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // Content behind the composer dock is laid out but not readable.
            val readableEnd = layout.viewportEndOffset - layout.afterContentPadding
            layout.visibleItemsInfo.any { item ->
                val key = item.key as? String ?: return@any false
                (key.startsWith("approval:") || key.startsWith("question:")) && item.offset < readableEnd
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (problem != null) Spacer(Modifier.height(LocalWorkbenchTopInset.current))
            if (problem != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (problem.unavailable) tr(R.string.ui_session_not_found_on_server) else tr(R.string.ui_could_not_restore_session), style = MaterialTheme.typography.titleMedium)
                    Text(problem.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.refreshConversation() }, enabled = ws.connection == ConnectionStatus.ONLINE && !ws.loadingHistory) {
                            Icon(WorkbenchIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.ui_check_again))
                        }
                        TextButton(onClick = { vm.backToProjects() }) { Text(tr(R.string.ui_back_to_project)) }
                        if (canRemoveEmptyRecord(ws, thread)) TextButton(onClick = { vm.removeEmptyRecord(thread) }) { Text(tr(R.string.ui_remove_empty_local_record)) }
                    }
                }
            }
            // The list must not control the IME. Dragging the conversation should
            // only scroll messages; the keyboard is opened by input focus and
            // dismissed by the system back gesture/key.
            ConversationViewport(modifier = Modifier.weight(1f), onBottomControls = onBottomControls,
                topInset = if (problem != null) 0.dp else LocalWorkbenchTopInset.current,
                dock = {
                    if (archived) InfoCard(tr(R.string.ui_this_session_is_archived_restore_it_to), tr(R.string.ui_restore_session)) { vm.archive(thread, false) }
                    val bannerText = when {
                        approvals.isNotEmpty() -> trq(R.plurals.ui_approval_pending_banner, approvals.size)
                        blockingQuestions > 0 -> trq(R.plurals.ui_question_pending_banner, blockingQuestions)
                        else -> null
                    }
                    val shownBanner = rememberLastNonNull(bannerText)
                    RiseIn(bannerText != null && !pendingInView) {
                        PendingActionBanner(shownBanner.orEmpty(), question = approvals.isEmpty()) {
                            userScrolling = false
                            followingLatest = false
                            scope.launch { listState.animateScrollToItem(firstPendingIndex) }
                        }
                    }
                    if (draft.delivery == "uncertain") {
                        InfoCard(tr(R.string.ui_your_last_message_has_an_unconfirmed_delivery), tr(R.string.ui_history_checked_unlock_draft)) { confirmRetry = true }
                    }
                    if (draft.queued.isNotEmpty()) QueuedMessages(
                        items = draft.queued,
                        onEdit = vm::editQueuedInput,
                        onRemove = vm::removeQueuedInput,
                    )
                    MessageComposer(
                        draft = draft, models = ws.models, model = composerDraft.model ?: state.thread?.model,
                        modelOptions = ws.modelOptions, effort = composerDraft.effort ?: if (composerDraft.model != null) ws.modelOptions.firstOrNull { it.id == composerDraft.model }?.defaultEffort else state.thread?.reasoningEffort,
                        canExecutePlan = composerDraft.mode == "plan" && composerDraft.text.isBlank() && composerDraft.attachments.isEmpty() &&
                            draft.edit == null && turnStatus == "completed" && timeline.any {
                                it.kind == "plan" && it.complete && it.turnId == timeline.lastOrNull()?.turnId
                            },
                        onExecutePlan = { vm.executePlan() },
                        modes = ws.collaborationModes, onMode = vm::selectMode,
                        modeMenu = ws.modePickerThread == thread,
                        onOpenModeMenu = { vm.showModePicker() }, onDismissModeMenu = { vm.showModePicker(false) },
                        goal = ws.goals[thread], onGoal = { vm.showGoalEditor() },
                        permissions = ws.permissions[thread] ?: PermissionSettings(), onPermissions = { vm.showPermissionEditor() },
                        onEffort = vm::selectEffort, onReloadModels = { vm.reloadModels() },
                        online = ws.connection == ConnectionStatus.ONLINE && problem == null && !ws.loadingHistory, archived = archived, listening = listening,
                        onText = vm::draftText, onRemoveAttachment = vm::removeAttachment, onModel = vm::selectModel,
                        onImage = onImage, onFile = onFile, onCamera = onCamera, onVoice = onVoice, onSend = {
                            if (listening) onVoice()
                            userScrolling = false; followingLatest = true
                            if (draft.edit != null) vm.resendInterruptedInput() else vm.send()
                        },
                        onQueue = { vm.send() },
                        onSteer = { vm.steer() },
                        revision = revision,
                        running = state.conversation?.activeTurn != null, stopping = thread in ws.stoppingTurns, onStop = { vm.interrupt() }, modifier = Modifier,
                    )
                    if (ws.permissionEditorThread == thread) PermissionPickerSheet(ws.permissions[thread] ?: PermissionSettings(),
                        onApply = { approval, sandbox -> vm.applyPermissions(approval, sandbox) }, onDismiss = { vm.showPermissionEditor(false) })
                }) { listPadding, dockHeight ->
                LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 760.dp).fillMaxSize().workbenchBackdropSource().nestedScroll(scrollIntent), state = listState, contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.conversation?.nextCursor != null) item(key = "control:older") { TextButton(onClick = { followingLatest = false; vm.loadOlder() }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.ui_load_earlier_messages)) } }
                    if (showEmpty) item(key = "control:empty") { Column(Modifier.padding(vertical = 38.dp)) {
                        Icon(WorkbenchIcons.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp)); Text(tr(R.string.ui_ready_to_start), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp)); Text(tr(R.string.ui_describe_your_task_or_attach_relevant_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                        ConversationRowContent(
                            row = row,
                            preview = vm::previewImage,
                            turnStatus = turnStatus.takeIf { ws.results[thread]?.turnId == row.turnId },
                            syncing = outputSyncing,
                            connected = outputConnected,
                            imagePreviews = ws.imagePreviews,
                            imageLoading = ws.imageLoading,
                            onLoadImage = vm::loadImagePreview,
                            revision = revision.takeIf { row.turnId == editableMessage?.turnId || row.turnId == draft.edit?.turnId },
                        )
                    }
                    // Blocking cards can change height while their command/reason text is being
                    // measured. Keep them as plain LazyColumn items so a remeasure moves the
                    // following rows atomically instead of leaving an animated placement layer
                    // at the old height.
                    items(approvals, key = { "approval:${it.request.key}" }) { pending ->
                        ApprovalCard(pending, enabled = ws.connection == ConnectionStatus.ONLINE) { vm.approve(pending.request, it) }
                    }
                    items(questions, key = { "question:${it.request.key}" }) { pending ->
                        UserQuestionsCard(pending, enabled = ws.connection == ConnectionStatus.ONLINE) { vm.answerQuestions(pending.request, it) }
                    }
                    val task = taskStatus(turnStatus, active = state.conversation?.activeTurn != null, approval = approvals.isNotEmpty(),
                        question = questions.any { it.request.isBlocking })
                    if (problem == null && task != null && (task != TaskStatus.COMPLETED || timeline.none { it.kind == "agentMessage" && it.complete && it.turnId == ws.results[thread]?.turnId })) item(key = "control:task-status") {
                        TaskStatusLabel(task, text = when (task) {
                            TaskStatus.FAILED -> tr(R.string.ui_this_turn_failed_check_the_execution_details)
                            TaskStatus.INTERRUPTED -> tr(R.string.ui_this_turn_was_interrupted)
                            else -> taskStatusLabel(task)
                        })
                    }
                }
                RiseIn(!followingLatest && !nearBottom && timeline.isNotEmpty(),
                    Modifier.align(Alignment.BottomEnd).padding(bottom = dockHeight).padding(12.dp)) {
                    FilledTonalButton(
                        onClick = { userScrolling = false; followingLatest = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Icon(WorkbenchIcons.ExpandMore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(when {
                            completedTurn != null && completedTurn != seenCompletion -> tr(R.string.ui_task_completed_view_latest)
                            newContent -> tr(R.string.ui_new_messages_view_latest)
                            else -> tr(R.string.ui_latest)
                        }, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

        }
        // Overlaid under the app bar so loading history never shifts the conversation.
        if (ws.loadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = LocalWorkbenchTopInset.current))
    }
    if (optionsOpen) WorkbenchBottomSheet(onDismissRequest = { onOptionsChange(false) }) {
        ConversationActions(
            cwd = state.thread?.cwd.orEmpty(), archived = archived,
            onFiles = if (ws.selectedProject?.startsWith('/') == true) ({ onOptionsChange(false); vm.selectTab(WorkspaceTab.FILES) }) else null,
            onTerminal = if (ws.selectedProject?.startsWith('/') == true) ({ onOptionsChange(false); vm.selectTab(WorkspaceTab.TERMINAL) }) else null,
            onMode = if (!archived) ({ onOptionsChange(false); vm.showModePicker() }) else null,
            modeLabel = tr(if (composerDraft.mode == "plan") R.string.ui_mode_plan else R.string.ui_mode_execute),
            onGoal = if (ws.connection == ConnectionStatus.ONLINE && !archived) ({ onOptionsChange(false); vm.showGoalEditor() }) else null,
            onPermissions = if (ws.connection == ConnectionStatus.ONLINE && !archived) ({ onOptionsChange(false); vm.showPermissionEditor() }) else null,
            permissionLabel = permissionSummaryText(ws.permissions[thread] ?: PermissionSettings()),
            onRefresh = { onOptionsChange(false); vm.refreshConversation() },
            onRename = { onOptionsChange(false); rename = true },
            onArchive = { onOptionsChange(false); vm.archive(thread, !archived) },
        )
    }

    if (rename) TextPrompt(tr(R.string.ui_rename_session), tr(R.string.ui_name), state.thread?.title?.ifBlank { tr(R.string.ui_new_session) }.orEmpty(), onDismiss = { rename = false }, onConfirm = { vm.rename(thread, it); rename = false })
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false }, title = { Text(tr(R.string.ui_have_you_checked_the_remote_history)) }, text = { Text(tr(R.string.ui_unlocking_only_restores_draft_editing_it_does)) }, confirmButton = { TextButton(onClick = { vm.resolveUncertain(); confirmRetry = false }) { Text(tr(R.string.ui_unlock_draft)) } }, dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text(tr(R.string.ui_cancel)) } })
}

@Composable private fun QueuedMessages(items: List<QueuedInput>, onEdit: (String) -> Unit, onRemove: (String) -> Unit) {
    // A queue is secondary to the running turn: one quiet line until the user opens it.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = items.any { it.error != null }
    fun preview(item: QueuedInput) = item.input.text.ifBlank {
        item.input.attachments.joinToString { it.name }.ifBlank { tr(R.string.ui_queued_message) }
    }
    Surface(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                onClickLabel = tr(if (expanded) R.string.ui_collapse else R.string.ui_expand)) { expanded = !expanded }
                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (failed) WorkbenchIcons.TaskError else WorkbenchIcons.OutputPending, null, Modifier.size(16.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr(R.string.ui_queue_count, items.size), style = MaterialTheme.typography.labelMedium)
                Text(if (expanded) tr(R.string.ui_queue_waiting) else preview(items.first()), Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DisclosureChevron(expanded, WorkbenchIcons.ChevronRight, null, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Disclosure(expanded) {
                Column(Modifier.heightIn(max = 184.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 4.dp, bottom = 4.dp)) {
                    items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(16.dp), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(preview(item), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (item.input.attachments.isNotEmpty()) Text(tr(R.string.ui_queue_attachments, item.input.attachments.size),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                item.error?.let { error ->
                                    Text(error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            IconButton(onClick = { onEdit(item.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(WorkbenchIcons.Edit, tr(R.string.ui_edit_queued_message), Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(WorkbenchIcons.Close, tr(R.string.ui_remove_queued_message), Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared by the actual conversation and the offline design gallery. */
@Composable fun ConversationActions(
    cwd: String, archived: Boolean,
    onRefresh: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit,
    onFiles: (() -> Unit)? = null, onTerminal: (() -> Unit)? = null,
    onMode: (() -> Unit)? = null, modeLabel: String? = null,
    onGoal: (() -> Unit)? = null, onPermissions: (() -> Unit)? = null, permissionLabel: String? = null,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr(R.string.ui_session_options), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        if (cwd.isNotBlank()) Text(cwd, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Project tools live here rather than in the app bar, so the conversation header stays quiet.
        onFiles?.let { SheetAction(tr(R.string.ui_files), WorkbenchIcons.FolderOpen, onClick = it) }
        onTerminal?.let { SheetAction(tr(R.string.ui_terminal_290), WorkbenchIcons.Terminal, onClick = it) }
        onMode?.let { SheetAction(tr(R.string.ui_mode_title), WorkbenchIcons.Checklist, supporting = modeLabel, onClick = it) }
        onGoal?.let { SheetAction(tr(R.string.ui_goal_title), WorkbenchIcons.Goal, onClick = it) }
        onPermissions?.let { SheetAction(tr(R.string.ui_permissions), WorkbenchIcons.Key, supporting = permissionLabel, onClick = it) }
        SheetAction(tr(R.string.ui_sync_session), WorkbenchIcons.Refresh, onClick = onRefresh)
        SheetAction(tr(R.string.ui_rename), WorkbenchIcons.Edit, onClick = onRename)
        SheetAction(if (archived) tr(R.string.ui_restore_session) else tr(R.string.ui_archive_session), WorkbenchIcons.Archive, onClick = onArchive)
    }
}
