@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.first
import dev.remotegpt.client.ThreadSummary

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.*

@Composable fun ProjectsScreen(
    ws: HostWorkspace, onAdd: () -> Unit, onRefresh: () -> Unit,
    onMore: () -> Unit, onContinue: (String) -> Unit = {},
    onCreate: (String) -> Unit = {}, onProjectMenu: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
) {
    val haptic = rememberHaptic()
    val list = rememberLazyListState()
    val refresh = rememberPullToRefreshState()
    val topInset = LocalWorkbenchTopInset.current
    val topPixels = with(LocalDensity.current) { topInset.toPx() }
    var expanded by rememberSaveable { mutableStateOf(listOfNotNull(ws.selectedProject)) }
    var all by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var focusedProject by rememberSaveable { mutableStateOf<String?>(null) }
    // Only direct expansion requests may reveal a project. Network updates never scroll.
    var revealProject by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ws.selectedProject) {
        ws.selectedProject?.let { if (it !in expanded) expanded = expanded + it }
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) revealProject = null }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = ws.loadingThreads, onRefresh = onRefresh,
        state = refresh, modifier = Modifier.fillMaxSize(), indicator = {
            PullToRefreshDefaults.Indicator(state = refresh, isRefreshing = ws.loadingThreads,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset))
        }) {
        LazyColumn(Modifier.fillMaxSize().workbenchBackdropSource(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = 8.dp)) {
            if (ws.projects.isEmpty() && !ws.loadingThreads) item(key = "control:empty") {
                EmptyPanel(WorkbenchIcons.FolderOpen, tr(R.string.ui_add_your_project), tr(R.string.ui_choose_a_remote_working_directory_to_start), tr(R.string.ui_add_project), onAdd)
            }
            ws.projects.forEach { project ->
                val threads = ws.threads.filter { projectPath(it.cwd) == project.path && (!it.awaitingFirstMessage || !ws.archived) }
                    .distinctBy { it.id }.sortedWith(compareByDescending<ThreadSummary> { ws.results[it.id]?.unread == true }.thenByDescending { it.updatedAt })
                val open = project.path in expanded
                val unread = threads.count { ws.results[it.id]?.unread == true }
                val running = threads.count { ws.results[it.id]?.status == "inProgress" || it.status == "active" }
                val failures = threads.count { ws.results[it.id]?.status == "failed" }
                val blockingQuestions = ws.pendingQuestions.count { it.request.isBlocking && it.request.threadId in threads.map { thread -> thread.id } }
                val approvals = ws.approvals.count { pending -> pending.request.threadId in threads.map { it.id } || pending.request.workingDirectory?.let(::projectPath) == project.path }
                item(key = "project:${project.path}") {
                    val visibility = remember { MutableTransitionState(open) }
                    visibility.targetState = open
                    val angle by animateFloatAsState(if (open) 90f else 0f, tween(if (open) 180 else 120, easing = FastOutSlowInEasing), label = "project-chevron")
                    val emphasis by animateFloatAsState(if (focusedProject == project.path && open) 1f else 0f, tween(120), label = "project-focus")
                    val reveal = remember { BringIntoViewRequester() }
                    var headerHeight by remember { mutableIntStateOf(0) }
                    var firstHeight by remember { mutableIntStateOf(0) }
                    var width by remember { mutableIntStateOf(0) }
                    LaunchedEffect(revealProject, open) {
                        if (revealProject == project.path && open) {
                            snapshotFlow { visibility.isIdle && visibility.currentState && firstHeight > 0 }.first { it }
                            // Includes the obscured area above the header, so it cannot land under the app bar.
                            reveal.bringIntoView(Rect(0f, -topPixels, width.toFloat(), (headerHeight + firstHeight).toFloat()))
                            if (revealProject == project.path) revealProject = null
                        }
                    }
                    Column(Modifier.fillMaxWidth().bringIntoViewRequester(reveal).onSizeChanged { width = it.width }) {
                        Row(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = emphasis)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).semantics { stateDescription = tr(if (open) R.string.ui_project_expanded else R.string.ui_project_collapsed) }
                                .clickable(role = Role.Button, onClickLabel = tr(if (open) R.string.ui_collapse_269 else R.string.ui_expand)) {
                                    haptic(false)
                                    focusedProject = project.path
                                    revealProject = if (open) null else project.path
                                    expanded = if (open) expanded - project.path else expanded + project.path
                                }.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(WorkbenchIcons.ChevronRight, null, Modifier.size(16.dp).rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(WorkbenchIcons.Project, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val projectStatus = when {
                                    approvals > 0 -> TaskStatus.APPROVAL
                                    blockingQuestions > 0 -> TaskStatus.QUESTION
                                    running > 0 -> TaskStatus.RUNNING
                                    failures > 0 -> TaskStatus.FAILED
                                    unread > 0 -> TaskStatus.COMPLETED
                                    else -> null
                                }
                                projectStatus?.let { TaskStatusLabel(it, text = when (it) {
                                    TaskStatus.APPROVAL -> tr(R.string.ui_approval_needed, approvals)
                                    TaskStatus.FAILED -> tr(R.string.task_failed_count, failures)
                                    TaskStatus.COMPLETED -> tr(R.string.ui_completed, unread)
                                    else -> taskStatusLabel(it)
                                }) }
                            }
                            IconButton(onClick = { onProjectMenu(project.path) }) { Icon(WorkbenchIcons.MoreHoriz, tr(R.string.ui_actions_for_project, project.name), Modifier.size(18.dp)) }
                        }
                        // Only four rows participate in size animation. Older history remains lazy.
                        AnimatedVisibility(visibleState = visibility,
                            enter = expandVertically(tween(180, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(120)),
                            exit = shrinkVertically(tween(120, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(90))) {
                            Column {
                                threads.take(4).forEachIndexed { index, thread -> key(thread.id) {
                                    ProjectThreadRow(ws, thread, onContinue, onArchive,
                                        if (index == 0) Modifier.onSizeChanged { firstHeight = it.height } else Modifier)
                                } }
                                if (threads.isEmpty()) Text(tr(if (ws.nextThreads != null) R.string.ui_you_can_load_earlier_sessions else R.string.ui_no_sessions_yet),
                                    Modifier.onSizeChanged { firstHeight = it.height }.padding(start = 68.dp, top = 12.dp, bottom = 12.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (open && project.path in all) {
                    items(threads.drop(4), key = { "thread:${it.id}" }) { ProjectThreadRow(ws, it, onContinue, onArchive) }
                }
                item(key = "project-actions:${project.path}") {
                    val visibility = remember { MutableTransitionState(open) }
                    visibility.targetState = open
                    AnimatedVisibility(visibility,
                        enter = expandVertically(tween(180), expandFrom = Alignment.Top) + fadeIn(tween(120)),
                        exit = shrinkVertically(tween(120), shrinkTowards = Alignment.Top) + fadeOut(tween(90))) {
                        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { haptic(false); onCreate(project.path) }, enabled = ws.creatingThreadCwd == null && project.path.startsWith('/')) {
                                if (ws.creatingThreadCwd == project.path) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(WorkbenchIcons.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (ws.creatingThreadCwd == project.path) tr(R.string.ui_creating) else tr(R.string.ui_new_session), style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.weight(1f))
                            if (threads.size > 4 || ws.nextThreads != null) TextButton(onClick = {
                                all = if (project.path in all && ws.nextThreads == null) all - project.path else all + project.path
                                if (ws.nextThreads != null) onMore()
                            }, enabled = !ws.loadingThreads) { Text(if (project.path !in all) tr(R.string.ui_view_all) else if (ws.nextThreads != null) tr(R.string.ui_load_earlier) else tr(R.string.ui_collapse_list), style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                item(key = "divider:${project.path}") { HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)) }
            }
            if (ws.nextThreads != null) item(key = "control:more") { TextButton(onClick = onMore, enabled = !ws.loadingThreads && ws.connection == ConnectionStatus.ONLINE, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.ui_load_more_projects_and_sessions), style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable private fun ProjectThreadRow(ws: HostWorkspace, thread: ThreadSummary, onContinue: (String) -> Unit,
    onArchive: (String) -> Unit, modifier: Modifier = Modifier) {
    val unreadResult = ws.results[thread.id]?.unread == true
    val questions = ws.pendingQuestions.filter { it.request.threadId == thread.id }
    val waiting = ws.approvals.any { it.request.threadId == thread.id }
    val active = ws.results[thread.id]?.status == "inProgress" || thread.status == "active"
    val archiving = thread.id in ws.archivingThreads
    val unavailable = ws.threadProblems[thread.id]?.unavailable == true
    val status = if (unavailable) null else taskStatus(ws.results[thread.id]?.status, active, waiting, questions.any { it.request.isBlocking })
    SwipeArchiveRow(enabled = ws.connection == ConnectionStatus.ONLINE && !ws.archived &&
        !thread.archived && !active && !waiting && questions.isEmpty() && !archiving && !unavailable,
        onArchive = { onArchive(thread.id) }) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !archiving) { onContinue(thread.id) }.then(modifier).heightIn(min = 48.dp)
            .padding(start = 68.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(thread.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Status owns one trailing slot. The icon is
            // beside its label so a running spinner never
            // competes with a second status on the left.
            when {
                archiving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tr(R.string.ui_archiving), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                unavailable -> Text(tr(R.string.ui_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (status == TaskStatus.COMPLETED && unreadResult) tr(R.string.ui_new_result) else taskStatusLabel(status),
                        style = MaterialTheme.typography.labelSmall, color = taskStatusColor(status), maxLines = 1)
                    TaskStatusIcon(status, label = null)
                }
            }
        }
    }

}

@Composable fun ProjectWorkspaceMenu(project: ProjectProfile?, selected: WorkspaceTab, hasConversation: Boolean,
    onSelect: (WorkspaceTab) -> Unit, onProjects: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(project?.name ?: tr(R.string.ui_workspace), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
        project?.let { Text(it.path, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
        if (project != null) {
            listOf(
                Triple(WorkspaceTab.SESSIONS, if (hasConversation) tr(R.string.ui_return_to_current_session) else tr(R.string.ui_back_to_projects), WorkbenchIcons.ChatBubbleOutline),
                Triple(WorkspaceTab.FILES, tr(R.string.ui_files), WorkbenchIcons.FolderOpen),
                Triple(WorkspaceTab.TERMINAL, tr(R.string.ui_terminal_290), WorkbenchIcons.Terminal),
            ).forEach { (tab, label, icon) ->
                SheetAction(label, icon, selected = selected == tab, enabled = tab == WorkspaceTab.SESSIONS || project.path.startsWith('/')) { onSelect(tab) }
            }
        }
        SheetAction(if (project == null) tr(R.string.ui_choose_project) else tr(R.string.ui_switch_project), WorkbenchIcons.FolderCopy, onClick = onProjects)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Icon(WorkbenchIcons.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.ui_refresh)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable fun SheetAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false, enabled: Boolean = true, supporting: String? = null, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Surface(onClick = { haptic(false); onClick() }, enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (selected) Icon(WorkbenchIcons.Check, tr(R.string.ui_selected), Modifier.size(18.dp))
        }
    }
}

@Composable fun ProjectEditor(initialPath: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit,
    busy: Boolean = false, error: String? = null) {
    var path by rememberSaveable { mutableStateOf(initialPath) }
    var name by rememberSaveable { mutableStateOf("") }
    val saving by rememberUpdatedState(busy)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !saving })) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr(R.string.ui_add_project), style = MaterialTheme.typography.titleLarge)
            Text(tr(R.string.ui_project_directory_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text(tr(R.string.ui_project_name_optional)) }, leadingIcon = { Icon(WorkbenchIcons.Project, null) }, singleLine = true)
            OutlinedTextField(path, { path = it }, enabled = !busy, isError = error != null, modifier = Modifier.fillMaxWidth(), label = { Text(tr(R.string.ui_remote_working_directory)) }, leadingIcon = { Icon(WorkbenchIcons.FolderOpen, null) }, placeholder = { Text("/home/user/projects/app") }, singleLine = true,
                supportingText = error?.let { { Text(it) } })
            Button(onClick = { onSave(name, path) }, enabled = !busy && path.trim().startsWith('/') && '\n' !in path && '\u0000' !in path,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(if (busy) R.string.ui_creating else R.string.ui_add_project)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable fun ProjectPicker(projects: List<ProjectProfile>, current: String?, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val duplicateNames = projects.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    SelectionList(tr(R.string.ui_choose_project), projects.map { project ->
        SelectionOption(project.path, project.name, detail = project.path.takeIf { project.name in duplicateNames })
    }, current, onSelect, footer = {
        val haptic = rememberHaptic()
        TextButton(onClick = { haptic(false); onAdd() }, modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(WorkbenchIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_add_project))
        }
    })
}
