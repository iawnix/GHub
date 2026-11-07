@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import kotlinx.coroutines.delay

/** Lightweight content actions share size, tint, tooltip and accessible names. */
@Composable internal fun ConversationActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    feedback: String? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(feedback ?: label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = modifier.size(48.dp).semantics {
                contentDescription = label
                if (feedback != null) {
                    stateDescription = feedback
                    liveRegion = LiveRegionMode.Polite
                }
            },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
            ),
        ) {
            // Compose respects the system animator duration scale, including zero.
            Crossfade(icon, Modifier.size(20.dp), animationSpec = tween(300), label = "contentAction") {
                Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Copy the original source; repeated clicks restart feedback and source changes
 * clear it, so streaming updates never claim that newer text was already copied. */
@Composable internal fun ConversationCopyButton(
    text: String,
    label: String,
    beforeCopy: () -> Boolean = { true },
) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    var copied by remember(text) { mutableStateOf(false) }
    var request by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text, request) {
        if (copied) { delay(1800); copied = false }
    }
    ConversationActionButton(
        icon = if (copied) WorkbenchIcons.Check else WorkbenchIcons.Copy,
        label = label,
        feedback = if (copied) tr(R.string.ui_copied) else null,
        onClick = {
            if (beforeCopy()) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(label, text))
                copied = true
                request++
                haptic(false)
            }
        },
    )
}
