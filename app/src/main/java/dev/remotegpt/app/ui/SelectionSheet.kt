@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.remotegpt.app.R
import dev.remotegpt.app.tr

internal data class SelectionOption(val id: String, val label: String, val detail: String? = null, val status: String? = null)

/** A value picker, separate from the icon-led action menus. Material owns gestures/insets. */
@Composable internal fun SelectionSheet(
    title: String, options: List<SelectionOption>, selected: String?, onSelect: (String) -> Unit,
    onDismiss: () -> Unit, hint: String? = null, emptyText: String? = null,
    onRefresh: (() -> Unit)? = null, refreshEnabled: Boolean = true,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Keep the sheet one surface level above the dark canvas. Using the
        // page surface for both the sheet and every row made the switcher read
        // as one flat black block in dark mode.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp) {
        SelectionList(title, options, selected, onSelect, hint, emptyText, onRefresh, refreshEnabled)
    }
}

@Composable internal fun SelectionList(
    title: String, options: List<SelectionOption>, selected: String?, onSelect: (String) -> Unit,
    hint: String? = null, emptyText: String? = null,
    onRefresh: (() -> Unit)? = null, refreshEnabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    val selectedIndex = options.indexOfFirst { it.id == selected }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    var positioned by remember { mutableStateOf(selectedIndex >= 0) }
    LaunchedEffect(selectedIndex) {
        if (!positioned && selectedIndex >= 0) {
            list.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
            positioned = true
        }
    }
    val haptic = rememberHaptic()
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .75f }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 24.dp, end = if (onRefresh == null) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            if (onRefresh != null) IconButton(onClick = { haptic(false); onRefresh() }, enabled = refreshEnabled) {
                Icon(WorkbenchIcons.Refresh, tr(R.string.ui_reload_models), Modifier.size(20.dp))
            }
        }
        if (hint != null) Text(hint, Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().selectableGroup(), state = list,
            contentPadding = PaddingValues(bottom = 12.dp)) {
            items(options, key = { it.id }) { option ->
                SelectionRow(option, option.id == selected) { onSelect(option.id) }
            }
            if (emptyText != null) item { Text(emptyText, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (footer != null) item { footer() }
        }
    }
}

@Composable private fun SelectionRow(option: SelectionOption, selected: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val stackedStatus = LocalDensity.current.fontScale > 1.3f || with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 360.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .86f) else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = { haptic(false); onClick() })
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            option.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stackedStatus) option.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!stackedStatus) option.status?.let {
            Text(it, Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Reserve the same trailing space in every row. Selection is exposed by selectable().
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(WorkbenchIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
