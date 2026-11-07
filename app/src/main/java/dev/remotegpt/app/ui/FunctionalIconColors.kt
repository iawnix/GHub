package dev.remotegpt.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** Used by feature entry points, not generic message actions or status indicators. */
@Composable internal fun functionalIconColor(icon: ImageVector): Color {
    return when (icon) {
        WorkbenchIcons.Dns, WorkbenchIcons.Key, WorkbenchIcons.Route,
        WorkbenchIcons.Terminal -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
