@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.remotegpt.app.*
import dev.remotegpt.app.R
import dev.remotegpt.terminal.ViewCallbacks
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable fun TerminalsScreen(state: WorkbenchState, vm: WorkbenchViewModel, onWorkspace: () -> Unit = {}, onBottomControls: (Int) -> Unit = {}) {
    val ws = state.workspace
    LaunchedEffect(state.activeHost, ws.selectedProject, ws.connection) { vm.ensureTerminal() }
    TerminalPane(
        terminals = ws.terminals.filter { it.projectPath == ws.selectedProject }, selectedId = ws.selectedTerminal,
        project = state.project?.name ?: projectName(ws.selectedProject.orEmpty()), projectPath = ws.selectedProject.orEmpty(),
        connection = ws.connection, opening = ws.selectedProject in ws.openingTerminalProjects, preferences = state.terminalPreferences,
        onBack = vm::navigateBack, onWorkspace = onWorkspace, onSelect = vm::selectTerminal,
        onNew = { vm.newTerminal() }, onClose = vm::closeTerminal, onConnect = { vm.connect() },
        onFontSize = vm::terminalFontSize, onExtraKeys = vm::terminalExtraKeys, onBottomControls = onBottomControls,
    )
}

/** Uses the real terminal view in production, previews and device regressions. */
@Composable internal fun TerminalPane(
    terminals: List<TerminalTab>, selectedId: String?, project: String, projectPath: String,
    connection: ConnectionStatus, opening: Boolean, preferences: TerminalPreferences,
    onBack: () -> Unit, onWorkspace: () -> Unit, onSelect: (String) -> Unit,
    onNew: () -> Unit, onClose: (String) -> Unit, onConnect: () -> Unit,
    onFontSize: (Float) -> Unit, onExtraKeys: (Boolean) -> Unit, onBottomControls: (Int) -> Unit = {},
) {
    val selected = terminals.firstOrNull { it.id == selectedId } ?: terminals.firstOrNull()
    var switching by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var sizing by rememberSaveable { mutableStateOf(false) }
    var closing by remember { mutableStateOf<String?>(null) }
    var fontSize by rememberSaveable { mutableFloatStateOf(preferences.fontSize) }
    var extraKeys by rememberSaveable { mutableStateOf(preferences.extraKeys) }
    LaunchedEffect(preferences.fontSize) { fontSize = preferences.fontSize }
    LaunchedEffect(preferences.extraKeys) { extraKeys = preferences.extraKeys }
    LaunchedEffect(fontSize) { delay(300); if (fontSize != preferences.fontSize) onFontSize(fontSize) }
    val lastFont by rememberUpdatedState(fontSize)
    val saveFont by rememberUpdatedState(onFontSize)
    DisposableEffect(Unit) { onDispose { saveFont(lastFont) } }
    val modifiers = remember(selected?.id) { TerminalModifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var running by remember(selected?.session) { mutableStateOf(selected?.session?.isRunning() == true) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    val keyboardHeight = (WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    var controlsHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsHeight, keyboardHeight) { onBottomControls(controlsHeight + keyboardHeight) }
    val haptic = rememberHaptic()
    val canCreate = connection == ConnectionStatus.ONLINE && !opening
    val inputEnabled = selected != null && running
    fun keyboard() {
        view?.let { terminal ->
            val manager = terminal.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (imeVisible) manager.hideSoftInputFromWindow(terminal.windowToken, 0)
            else { terminal.requestFocus(); manager.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    fun sendKey(code: Int) {
        if (inputEnabled) view?.let { terminal ->
            haptic(false)
            terminal.requestFocus()
            terminal.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
            terminal.onKeyUp(code, KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // Decide from the space actually left by the IME, including split windows.
        val compactInput = imeVisible && maxHeight < 240.dp
        val minimalInput = compactInput && maxHeight < 120.dp
        Column(Modifier.fillMaxSize()) {
            if (!compactInput || selected == null) TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), expandedHeight = (40.dp * density.fontScale + 16.dp).coerceAtLeast(64.dp), title = {
                Column(Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button,
                    onClickLabel = tr(R.string.terminal_sessions), onClick = { switching = true })
                    .heightIn(min = 48.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected?.name ?: tr(R.string.ui_terminal_290), Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(WorkbenchIcons.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp))
                    }
                    Text(project + " · " + if (selected != null && !running) tr(R.string.terminal_ended) else connectionLabel(connection),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(WorkbenchIcons.ArrowBack, tr(R.string.ui_back_to_session)) }
            }, actions = {
                IconButton(onClick = ::keyboard, enabled = inputEnabled) {
                    Icon(WorkbenchIcons.Keyboard, tr(if (imeVisible) R.string.terminal_hide_keyboard else R.string.ui_show_keyboard), Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { options = true }) { Icon(WorkbenchIcons.MoreHoriz, tr(R.string.terminal_options)) }
                    DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                        DropdownMenuItem(text = { Text(tr(if (extraKeys) R.string.terminal_hide_extra_keys else R.string.terminal_show_extra_keys)) }, onClick = {
                            options = false; extraKeys = !extraKeys; onExtraKeys(extraKeys)
                        })
                        DropdownMenuItem(text = { Text(tr(R.string.terminal_font_size)) }, onClick = { options = false; sizing = true })
                        DropdownMenuItem(text = { Text(tr(R.string.ui_new_terminal)) }, enabled = canCreate, onClick = { options = false; onNew() })
                        DropdownMenuItem(text = { Text(tr(R.string.ui_close_terminal_339)) }, enabled = selected != null, onClick = { options = false; closing = selected?.id })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(tr(R.string.ui_workspace)) }, onClick = { options = false; onWorkspace() })
                    }
                }
            })
            if (selected == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (opening) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(); Text(tr(R.string.ui_opening_terminal))
                    } else EmptyPanel(WorkbenchIcons.Terminal, tr(R.string.ui_open_remote_terminal), tr(R.string.ui_supports_vim_tmux_and_common_terminal_programs))
                }
                Box(Modifier.fillMaxWidth().onSizeChanged { controlsHeight = it.height }.padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = if (connection == ConnectionStatus.ONLINE) onNew else onConnect, enabled = !opening && !connection.busy) {
                        Text(tr(if (connection == ConnectionStatus.ONLINE) R.string.ui_new_terminal else R.string.ui_connect))
                    }
                }
            } else {
                // An outer inset keeps drawing, text selection and PTY dimensions in
                // the same coordinate system; TerminalView does not support padding.
                Row(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = if (compactInput) 2.dp else 6.dp)) {
                        key(selected.id) {
                            AndroidView(factory = { ctx ->
                                TerminalView(ctx, null).apply {
                                    isFocusable = true; isFocusableInTouchMode = true
                                    setTextSize((fontSize * density.density * density.fontScale).roundToInt())
                                    setTypeface(Typeface.MONOSPACE)
                                    setTerminalViewClient(object : ViewCallbacks() {
                                        override fun readControlKey() = modifiers.ctrl.active
                                        override fun readAltKey() = modifiers.alt.active
                                        override fun readShiftKey() = modifiers.shift.active
                                        override fun onKeyInput() = modifiers.consume()
                                        override fun shouldEnforceCharBasedInput() = true
                                        override fun onScale(scale: Float): Float { fontSize = (fontSize * scale).coerceIn(10f, 28f); return 1f }
                                        override fun onSingleTapUp(e: MotionEvent?) {
                                            requestFocus()
                                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                                        }
                                    })
                                    attachSession(selected.session)
                                    contentDescription = tr(R.string.ui_ssh_terminal)
                                    view = this
                                }
                            }, modifier = Modifier.fillMaxSize(), update = { terminal ->
                                val pixels = (fontSize * density.density * density.fontScale).roundToInt()
                                if (terminal.tag != pixels) { terminal.setTextSize(pixels); terminal.tag = pixels }
                                terminal.onScreenUpdated()
                            }, onRelease = { terminal -> if (view === terminal) view = null })
                            DisposableEffect(selected.session, view) {
                                val attached = view
                                selected.session.onChanged = { running = selected.session.isRunning(); attached?.onScreenUpdated() }
                                onDispose { selected.session.onChanged = null }
                            }
                        }
                    }
                    if (minimalInput) Surface(Modifier.widthIn(min = 48.dp).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (modifiers.ctrl.active) ModifierKey("Ctrl", modifiers.ctrl, inputEnabled, Modifier.widthIn(min = 48.dp),
                                { modifiers.ctrl = modifiers.ctrl.tap() }, { modifiers.ctrl = modifiers.ctrl.lock() })
                            if (modifiers.alt.active) ModifierKey("Alt", modifiers.alt, inputEnabled, Modifier.widthIn(min = 48.dp),
                                { modifiers.alt = modifiers.alt.tap() }, { modifiers.alt = modifiers.alt.lock() })
                            if (modifiers.shift.active) ModifierKey("Shift", modifiers.shift, inputEnabled, Modifier.widthIn(min = 48.dp),
                                { modifiers.shift = modifiers.shift.tap() }, { modifiers.shift = modifiers.shift.lock() })
                            IconButton(onClick = ::keyboard) { Icon(WorkbenchIcons.Keyboard, tr(R.string.terminal_hide_keyboard), Modifier.size(20.dp)) }
                        }
                    }
                }
                if (minimalInput) SideEffect { controlsHeight = 0 }
                else Column(Modifier.fillMaxWidth().onSizeChanged { controlsHeight = it.height }.background(MaterialTheme.colorScheme.surface)) {
                    if (!running) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.terminal_ended), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = if (connection == ConnectionStatus.ONLINE) onNew else onConnect, enabled = !opening && !connection.busy) {
                            Text(tr(if (connection == ConnectionStatus.ONLINE) R.string.ui_new_terminal else R.string.ui_connect))
                        }
                    }
                    TerminalKeyBar(modifiers, extraKeys && !compactInput, inputEnabled, ::sendKey,
                        { selected.session.onPasteTextFromClipboard() }, compactInput, ::keyboard)
                }
            }
        }
    }
    if (switching) ModalBottomSheet(onDismissRequest = { switching = false }) {
        Text(tr(R.string.terminal_sessions), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        Text(project, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
        Text(projectPath, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(terminals, key = { it.id }) { tab ->
                ListItem(headlineContent = { Text(tab.name) }, supportingContent = { if (!tab.session.isRunning()) Text(tr(R.string.terminal_ended)) },
                    leadingContent = { Icon(if (tab.id == selected?.id) WorkbenchIcons.Check else WorkbenchIcons.Terminal, null) },
                    trailingContent = { IconButton(onClick = { closing = tab.id }) { Icon(WorkbenchIcons.Close, tr(R.string.ui_close_330, tab.name), Modifier.size(20.dp)) } },
                    modifier = Modifier.clickable { onSelect(tab.id); switching = false }.semantics { this.selected = tab.id == selected?.id })
            }
        }
        TextButton(onClick = { switching = false; onNew() }, enabled = canCreate, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(WorkbenchIcons.Add, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_new_terminal))
        }
    }
    if (sizing) AlertDialog(onDismissRequest = { sizing = false }, title = { Text(tr(R.string.terminal_font_size)) }, text = {
        Column {
            Text(tr(R.string.terminal_font_value, fontSize.roundToInt()), style = MaterialTheme.typography.titleMedium)
            Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 10f..28f, steps = 17,
                modifier = Modifier.semantics { contentDescription = tr(R.string.terminal_font_size) })
            Text(tr(R.string.terminal_pinch_hint), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { sizing = false }) { Text(tr(R.string.ui_ok)) } },
        dismissButton = { TextButton(onClick = { fontSize = 14f }) { Text(tr(R.string.terminal_reset_font)) } })
    closing?.let { id -> AlertDialog(onDismissRequest = { closing = null }, title = { Text(tr(R.string.ui_close_terminal)) },
        text = { Text(tr(R.string.ui_programs_in_a_regular_shell_may_stop)) },
        confirmButton = { TextButton(onClick = { onClose(id); closing = null }) { Text(tr(R.string.ui_close_terminal_339)) } },
        dismissButton = { TextButton(onClick = { closing = null }) { Text(tr(R.string.ui_cancel)) } }) }
}

@Composable internal fun TerminalKeyBar(modifiers: TerminalModifiers, expanded: Boolean, enabled: Boolean, send: (Int) -> Unit, paste: () -> Unit,
    keyboardToggle: Boolean = false, onKeyboard: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val keyWidth = (48.dp * LocalDensity.current.fontScale).coerceAtLeast(48.dp)
        val singleRow = maxWidth >= keyWidth * if (keyboardToggle) 8 else 7
        val primary: @Composable RowScope.() -> Unit = {
            TerminalKey("Esc", tr(R.string.terminal_escape), enabled, Modifier.weight(1f)) { send(KeyEvent.KEYCODE_ESCAPE) }
            TerminalKey("Tab", tr(R.string.terminal_tab), enabled, Modifier.weight(1f)) { send(KeyEvent.KEYCODE_TAB) }
            ModifierKey("Ctrl", modifiers.ctrl, enabled, Modifier.weight(1f), { modifiers.ctrl = modifiers.ctrl.tap() }, { modifiers.ctrl = modifiers.ctrl.lock() })
        }
        val arrows: @Composable RowScope.() -> Unit = {
            // Conventional ← ↑ ↓ → order; vim users still get the same keys one slot apart.
            for ((label, code, description) in listOf(Triple("←", KeyEvent.KEYCODE_DPAD_LEFT, R.string.terminal_left),
                Triple("↑", KeyEvent.KEYCODE_DPAD_UP, R.string.terminal_up), Triple("↓", KeyEvent.KEYCODE_DPAD_DOWN, R.string.terminal_down),
                Triple("→", KeyEvent.KEYCODE_DPAD_RIGHT, R.string.terminal_right))) {
                TerminalKey(label, tr(description), enabled, Modifier.weight(1f)) { send(code) }
            }
            if (keyboardToggle) IconButton(onClick = onKeyboard, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(WorkbenchIcons.Keyboard, tr(R.string.terminal_hide_keyboard), Modifier.size(20.dp))
            }
        }
        Column {
            if (singleRow) Row(Modifier.fillMaxWidth()) { primary(); arrows() }
            else { Row(Modifier.fillMaxWidth(), content = primary); Row(Modifier.fillMaxWidth(), content = arrows) }
            if (expanded || modifiers.alt.active || modifiers.shift.active) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKey("Alt", modifiers.alt, enabled, Modifier.widthIn(min = keyWidth), { modifiers.alt = modifiers.alt.tap() }, { modifiers.alt = modifiers.alt.lock() })
                ModifierKey("Shift", modifiers.shift, enabled, Modifier.widthIn(min = keyWidth), { modifiers.shift = modifiers.shift.tap() }, { modifiers.shift = modifiers.shift.lock() })
                if (expanded) {
                    TerminalKey("Home", tr(R.string.terminal_home), enabled) { send(KeyEvent.KEYCODE_MOVE_HOME) }
                    TerminalKey("End", tr(R.string.terminal_end), enabled) { send(KeyEvent.KEYCODE_MOVE_END) }
                    TerminalKey(tr(R.string.ui_paste), tr(R.string.ui_paste), enabled, onClick = paste)
                }
            }
            if (expanded) Text(tr(R.string.terminal_modifier_hint), Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TerminalKey(label: String, description: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .semantics { contentDescription = description }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun ModifierKey(label: String, state: TerminalModifier, enabled: Boolean, modifier: Modifier,
    onTap: () -> Unit, onLock: () -> Unit) {
    val haptic = rememberHaptic()
    val description = tr(when (state) { TerminalModifier.OFF -> R.string.terminal_modifier_off; TerminalModifier.ONCE -> R.string.terminal_modifier_once; TerminalModifier.LOCKED -> R.string.terminal_modifier_locked })
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .background(if (state.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = { haptic(false); onTap() },
            onLongClickLabel = tr(if (state == TerminalModifier.LOCKED) R.string.terminal_unlock_modifier else R.string.terminal_lock_modifier, label),
            onLongClick = { haptic(true); onLock() })
        .semantics { contentDescription = label; stateDescription = description; selected = state.active }
        .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                else if (state.active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
            if (state == TerminalModifier.LOCKED) Icon(WorkbenchIcons.Key, null, Modifier.size(12.dp))
        }
    }
}
