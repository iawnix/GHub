@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.rememberScrollState
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.conflate
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val turnStatus = ws.results[thread]?.status?.takeIf { it.isNotBlank() } ?: state.conversation?.status
    // A live event is immediately useful, but a completed turn is only shown
    // as authoritative after its history has been reconciled with the daemon.
    val outputSyncing = thread in ws.historySyncingThreads || ws.loadingHistory || ws.connection in setOf(
        ConnectionStatus.CHECKING, ConnectionStatus.RECONNECTING, ConnectionStatus.SYNCING,
    )
    val outputConnected = ws.connection == ConnectionStatus.ONLINE
    val draft = state.draft
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
    val completedTurn = ws.results[thread]?.completedTurnId
    val completedContentLoaded = timeline.any { it.turnId == completedTurn && it.complete }
    val haptic = rememberHaptic()
    var seenCompletion by rememberSaveable(thread) { mutableStateOf(completedTurn) }
    val latestVersion = timeline.lastOrNull()?.let { "${it.identityKey}:${it.text.hashCode()}:${it.complete}" }
    var seenVersion by rememberSaveable(thread) { mutableStateOf(latestVersion) }
    val bottomTolerance = with(LocalDensity.current) { 32.dp.roundToPx() }
    val nearBottom by remember(listState, bottomTolerance) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null && last.key == CHAT_END_KEY &&
                last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset <= bottomTolerance
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
        // keeps up with continuous output without debounce starvation. The tiny
        // end item makes long turns land at their bottom, not their beginning.
        snapshotFlow {
            val layout = listState.layoutInfo
            if (followingLatest && !userScrolling && !listState.isScrollInProgress && listState.canScrollForward) {
                Triple(layout.totalItemsCount, layout.viewportEndOffset to layout.afterContentPadding,
                    layout.visibleItemsInfo.lastOrNull()?.let { Triple(it.key, it.offset, it.size) })
            } else null
        }.conflate().collect {
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
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (problem != null || ws.loadingHistory) Spacer(Modifier.height(LocalWorkbenchTopInset.current))
        if (ws.loadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
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
            topInset = if (problem != null || ws.loadingHistory) 0.dp else LocalWorkbenchTopInset.current,
            dock = {
                if (archived) InfoCard(tr(R.string.ui_this_session_is_archived_restore_it_to), tr(R.string.ui_restore_session)) { vm.archive(thread, false) }
                if (draft.delivery == "uncertain") {
                    InfoCard(tr(R.string.ui_your_last_message_has_an_unconfirmed_delivery), tr(R.string.ui_history_checked_unlock_draft)) { confirmRetry = true }
                }
                MessageComposer(
                    draft = draft, models = ws.models, model = draft.model ?: state.thread?.model,
                    modelOptions = ws.modelOptions, effort = draft.effort ?: if (draft.model != null) ws.modelOptions.firstOrNull { it.id == draft.model }?.defaultEffort else state.thread?.reasoningEffort,
                    onEffort = vm::selectEffort, onReloadModels = { vm.reloadModels() },
                    online = ws.connection == ConnectionStatus.ONLINE && problem == null && !ws.loadingHistory, archived = archived, listening = listening,
                    onText = vm::draftText, onRemoveAttachment = vm::removeAttachment, onModel = vm::selectModel,
                    onImage = onImage, onFile = onFile, onCamera = onCamera, onVoice = onVoice, onSend = { userScrolling = false; followingLatest = true; vm.send() },
                    running = state.conversation?.activeTurn != null, onStop = { vm.interrupt() }, modifier = Modifier,
                )
            }) { listPadding, dockHeight ->
            LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 760.dp).fillMaxSize().workbenchBackdropSource().nestedScroll(scrollIntent), state = listState, contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state.conversation?.nextCursor != null) item(key = "control:older") { TextButton(onClick = { followingLatest = false; vm.loadOlder() }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.ui_load_earlier_messages)) } }
                if (problem == null && timeline.isEmpty() && !ws.loadingHistory && ws.connection == ConnectionStatus.ONLINE) item(key = "control:empty") { Column(Modifier.padding(vertical = 38.dp)) {
                    Icon(WorkbenchIcons.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp)); Text(tr(R.string.ui_ready_to_start), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp)); Text(tr(R.string.ui_describe_your_task_or_attach_relevant_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                items(turns, key = { "turn:${it.key}" }) { turn ->
                    TurnContent(
                        turn.value,
                        state.conversation?.activeTurn == turn.key,
                        vm::previewImage,
                        turnStatus = turnStatus.takeIf { ws.results[thread]?.turnId == turn.key },
                        syncing = outputSyncing,
                        connected = outputConnected,
                        imagePreviews = ws.imagePreviews,
                        imageLoading = ws.imageLoading,
                        onLoadImage = vm::loadImagePreview,
                    )
                }
                items(approvals, key = { "approval:${it.request.key}" }) { pending -> ApprovalCard(pending, enabled = ws.connection == ConnectionStatus.ONLINE) { vm.approve(pending.request, it) } }
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
                item(key = CHAT_END_KEY, contentType = "end") { Spacer(Modifier.height(1.dp)) }
            }
            if (!followingLatest && !nearBottom && timeline.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { haptic(false); userScrolling = false; followingLatest = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = dockHeight).padding(12.dp),
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
    if (optionsOpen) ModalBottomSheet(onDismissRequest = { onOptionsChange(false) }) {
        ConversationActions(
            cwd = state.thread?.cwd.orEmpty(), archived = archived,
            projectAvailable = ws.selectedProject?.startsWith('/') == true,
            onFiles = { onOptionsChange(false); vm.selectTab(WorkspaceTab.FILES) },
            onTerminal = { onOptionsChange(false); vm.selectTab(WorkspaceTab.TERMINAL) },
            onRefresh = { onOptionsChange(false); vm.refreshConversation() },
            onRename = { onOptionsChange(false); rename = true },
            onArchive = { onOptionsChange(false); vm.archive(thread, !archived) },
        )
    }

    if (rename) TextPrompt(tr(R.string.ui_rename_session), tr(R.string.ui_name), state.thread?.title.orEmpty(), onDismiss = { rename = false }, onConfirm = { vm.rename(thread, it); rename = false })
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false }, title = { Text(tr(R.string.ui_have_you_checked_the_remote_history)) }, text = { Text(tr(R.string.ui_unlocking_only_restores_draft_editing_it_does)) }, confirmButton = { TextButton(onClick = { vm.resolveUncertain(); confirmRetry = false }) { Text(tr(R.string.ui_unlock_draft)) } }, dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text(tr(R.string.ui_cancel)) } })
}

/** Shared by the actual conversation and the offline design gallery. */
@Composable fun ConversationActions(
    cwd: String, archived: Boolean, projectAvailable: Boolean,
    onFiles: () -> Unit, onTerminal: () -> Unit,
    onRefresh: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr(R.string.ui_session_options), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        if (cwd.isNotBlank()) Text(cwd, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SheetAction(tr(R.string.ui_project_files), WorkbenchIcons.FolderOpen, enabled = projectAvailable, onClick = onFiles)
        SheetAction(tr(R.string.ui_terminal_290), WorkbenchIcons.Terminal, enabled = projectAvailable, onClick = onTerminal)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        SheetAction(tr(R.string.ui_sync_session), WorkbenchIcons.Refresh, onClick = onRefresh)
        SheetAction(tr(R.string.ui_rename), WorkbenchIcons.Edit, onClick = onRename)
        SheetAction(if (archived) tr(R.string.ui_restore_session) else tr(R.string.ui_archive_session), WorkbenchIcons.Archive, onClick = onArchive)
    }
}

private const val CHAT_END_KEY = "control:conversation-end"
