@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ConnectionStatus

/** A passive status, without a button surface or a click action. Long press explains the glyph.
 * Glyph and color change with a short transition so a status change is noticed, not missed. */
@Composable internal fun StatusIndicator(
    icon: ImageVector, label: String, tint: Color,
    modifier: Modifier = Modifier, showLabel: Boolean = false,
) {
    val color by animateColorAsState(tint, Motion.short(), label = "status-tint")
    if (showLabel) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusGlyph(icon, null, color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            modifier = modifier,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                StatusGlyph(icon, label, color)
            }
        }
    }
}

@Composable private fun StatusGlyph(icon: ImageVector, label: String?, tint: Color) {
    AnimatedContent(icon, transitionSpec = { swap() }, contentAlignment = Alignment.Center, label = "status-glyph") { glyph ->
        Icon(glyph, label, Modifier.size(16.dp), tint = tint)
    }
}

@Composable internal fun ConnectionIndicator(
    status: ConnectionStatus, modifier: Modifier = Modifier,
    showLabel: Boolean = status != ConnectionStatus.ONLINE,
) {
    StatusIndicator(
        icon = when (status) {
            ConnectionStatus.ONLINE -> WorkbenchIcons.Connected
            ConnectionStatus.OFFLINE, ConnectionStatus.WAITING_NETWORK -> WorkbenchIcons.Disconnected
            ConnectionStatus.ERROR -> WorkbenchIcons.TaskError
            else -> WorkbenchIcons.Refresh
        },
        label = connectionLabel(status), tint = connectionColor(status),
        modifier = modifier, showLabel = showLabel,
    )
}
