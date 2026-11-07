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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ConnectionStatus

@Composable fun WorkbenchHeader(
    title: String, @Suppress("UNUSED_PARAMETER") host: String? = null, status: ConnectionStatus, conversation: Boolean,
    onNavigate: () -> Unit, backLabel: String = tr(R.string.ui_back), large: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onMenu: (() -> Unit)? = null, onHosts: (() -> Unit)? = null, onAdd: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null, archiveLabel: String = tr(R.string.ui_archived),
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
    if (large) MediumTopAppBar(modifier = Modifier.workbenchGlass(), title = { ProvideTextStyle(MaterialTheme.typography.headlineMedium, heading) }, expandedHeight = 128.dp, navigationIcon = back, colors = colors, scrollBehavior = scrollBehavior)
    else TopAppBar(modifier = Modifier.workbenchGlass(), title = { ProvideTextStyle(MaterialTheme.typography.titleMedium, heading) }, navigationIcon = back, actions = {
        onAdd?.let { IconButton(onClick = it) { Icon(WorkbenchIcons.Add, tr(R.string.ui_add_project), Modifier.size(20.dp)) } }
        onArchive?.let { IconButton(onClick = it) { Icon(WorkbenchIcons.Archive, archiveLabel, Modifier.size(20.dp)) } }
        onMenu?.let { IconButton(onClick = it) { Icon(menuIcon, menuLabel, Modifier.size(20.dp)) } }
    }, colors = colors)
}

/** Contextual actions, not navigation tabs. Kept below content and above system/IME insets. */
@Composable fun ReachableActions(
    onWorkspace: () -> Unit, primaryLabel: String, onPrimary: () -> Unit,
    primaryIcon: ImageVector = WorkbenchIcons.Add, enabled: Boolean = true,
    secondaryIcon: ImageVector? = null, secondaryLabel: String = "", secondaryEnabled: Boolean = true, onSecondary: () -> Unit = {},
) {
    WorkbenchSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    FlowRow(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onWorkspace, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(WorkbenchIcons.Menu, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_workspace))
            }
            if (secondaryIcon != null) IconButton(onClick = onSecondary, enabled = secondaryEnabled, modifier = Modifier.size(48.dp)) {
                Icon(secondaryIcon, secondaryLabel)
            }
        }
        Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(primaryIcon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(primaryLabel)
        }
    }
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
