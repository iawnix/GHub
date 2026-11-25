@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ConnectionStatus
import dev.remotegpt.app.WorkspaceTab

@Composable fun WorkbenchHeader(
    title: String, status: ConnectionStatus, conversation: Boolean,
    onNavigate: () -> Unit, backLabel: String = tr(R.string.ui_back),
    onMenu: (() -> Unit)? = null, onHosts: (() -> Unit)? = null, onAdd: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null, archiveLabel: String = tr(R.string.ui_archived),
    archived: Boolean = false,
    workspaceTab: WorkspaceTab? = null, hasConversation: Boolean = false,
    onSelectTab: ((WorkspaceTab) -> Unit)? = null,
    menuIcon: ImageVector = WorkbenchIcons.MoreHoriz, menuLabel: String = tr(R.string.ui_more_actions),
) {
    val heading: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f, fill = false).semantics { contentDescription = title }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ConnectionIndicator(status, showLabel = false)
        }
    }
    val back: @Composable () -> Unit = {
        if (conversation) IconButton(onClick = onNavigate) { Icon(WorkbenchIcons.ArrowBack, backLabel) }
        else onHosts?.let { IconButton(onClick = it) { Icon(WorkbenchIcons.Dns, tr(R.string.ui_switch_server), Modifier.size(20.dp), tint = functionalIconColor(WorkbenchIcons.Dns)) } }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    )
    val density = LocalDensity.current
    val wideBar = with(density) { LocalWindowInfo.current.containerSize.width.toDp() >= 360.dp } && density.fontScale <= 1.35f
    TopAppBar(modifier = Modifier.workbenchGlass(), title = { ProvideTextStyle(MaterialTheme.typography.titleMedium, heading) }, navigationIcon = back, actions = {
        onAdd?.let { IconButton(onClick = it) { Icon(WorkbenchIcons.Add, tr(R.string.ui_add_project), Modifier.size(20.dp)) } }
        onArchive?.let { HeaderToggleAction(archived, it, WorkbenchIcons.Archive, archiveLabel) }
        if (onSelectTab != null && workspaceTab != null) {
            if (wideBar) {
                if (workspaceTab != WorkspaceTab.SESSIONS) {
                    IconButton(onClick = { onSelectTab(WorkspaceTab.SESSIONS) }) {
                        Icon(WorkbenchIcons.ChatBubbleOutline, tr(if (hasConversation) R.string.ui_return_to_current_session else R.string.ui_sessions), Modifier.size(20.dp))
                    }
                }
                if (workspaceTab != WorkspaceTab.FILES) {
                    IconButton(onClick = { onSelectTab(WorkspaceTab.FILES) }) {
                        Icon(WorkbenchIcons.FolderOpen, tr(R.string.ui_files), Modifier.size(20.dp))
                    }
                }
                if (workspaceTab != WorkspaceTab.TERMINAL) {
                    IconButton(onClick = { onSelectTab(WorkspaceTab.TERMINAL) }) {
                        Icon(WorkbenchIcons.Terminal, tr(R.string.ui_terminal_290), Modifier.size(20.dp))
                    }
                }
            }
        }
        onMenu?.let { IconButton(onClick = it) { Icon(menuIcon, menuLabel, Modifier.size(20.dp)) } }
    }, colors = colors)
}

@Composable private fun HeaderToggleAction(checked: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Icon(icon, label, Modifier.size(20.dp))
    }
}

internal fun connectionLabel(status: ConnectionStatus) = when (status) {
    ConnectionStatus.ONLINE -> tr(R.string.ui_connected)
    ConnectionStatus.CONNECTING -> tr(R.string.ui_connecting)
    ConnectionStatus.CHECKING -> tr(R.string.ui_checking_connection)
    ConnectionStatus.WAITING_NETWORK -> tr(R.string.ui_waiting_for_network)
    ConnectionStatus.RECONNECTING -> tr(R.string.ui_reconnecting)
    ConnectionStatus.SYNCING -> tr(R.string.ui_syncing_sessions)
    ConnectionStatus.ERROR -> tr(R.string.ui_needs_attention)
    ConnectionStatus.OFFLINE -> tr(R.string.ui_disconnected)
}
