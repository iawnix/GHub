package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A gesture is transient; only the server acknowledgement removes the row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeArchiveRow(enabled: Boolean, onArchive: () -> Unit, content: @Composable () -> Unit) {
    // Do not restore a dismissed gesture after rotation and issue the request again.
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { distance -> distance * 0.4f } }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    var committing by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = swipe,
        gesturesEnabled = enabled && !committing,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        modifier = Modifier.clip(MaterialTheme.shapes.small).semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(tr(R.string.ui_archive_session)) {
                haptic(false)
                onArchive()
                true
            })
        },
        onDismiss = { direction ->
            if (direction != SwipeToDismissBoxValue.Settled && !committing) {
                committing = true
                if (enabled) { haptic(false); onArchive() }
                scope.launch {
                    try { swipe.reset() } finally { committing = false }
                }
            }
        },
        backgroundContent = {
            if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 20.dp),
                    contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                        Alignment.CenterStart else Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(WorkbenchIcons.Archive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tr(R.string.ui_archive), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) { content() }
    }
}
