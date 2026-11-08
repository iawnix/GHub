@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.remotegpt.app.ui

import dev.remotegpt.app.tr
import dev.remotegpt.app.R

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.*
import dev.remotegpt.client.*
import kotlinx.coroutines.*

@Composable fun HostEditor(editor: HostEditorViewModel, vm: WorkbenchViewModel, snackbar: SnackbarHostState? = null,
    backLabel: String = tr(R.string.ui_back_to_servers), overviewOnly: Boolean = false, onClose: () -> Unit) {
    val profile = editor.profile ?: return
    var loadError by remember(profile.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(profile.id) {
        try { editor.loadCredentials(vm::credential) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = tr(R.string.ui_saved_credentials_cannot_be_decrypted_import_them) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    HostEditorForm(editor, existing = state.profiles.any { it.id == profile.id },
        reconnect = state.workspaces[profile.id]?.connection?.let { it != ConnectionStatus.OFFLINE } == true,
        loadError = loadError, snackbar = snackbar, backLabel = backLabel, overviewOnly = overviewOnly, onClose = onClose,
        onSave = { value, secrets, done -> vm.saveProfile(value, secrets, done); Unit },
        onDelete = { vm.deleteProfile(profile); onClose() })
}

/** All sections edit one draft. The entry page owns Save, and Back retraces the actual navigation path. */
@Composable fun HostEditorForm(editor: HostEditorViewModel, existing: Boolean = false, reconnect: Boolean = false,
    loadError: String? = null, snackbar: SnackbarHostState? = null,
    backLabel: String = tr(R.string.ui_back_to_servers), overviewOnly: Boolean = false, onClose: () -> Unit,
    onSave: (HostProfile, Map<String, Credential>, (Boolean) -> Unit) -> Unit, onDelete: () -> Unit = {}) {
    val profile = editor.profile ?: return
    val section = editor.section
    var deleting by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val saving = editor.saving
    var error by remember { mutableStateOf<String?>(null) }
    var invalidField by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile, editor.credentials) { invalidField = null; error = null }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun navigate(page: HostEditorSection, id: String = editor.jumpId) {
        invalidField = null
        error = null
        focusManager.clearFocus()
        editor.navigate(page, id)
    }
    fun back() {
        if (saving) return
        invalidField = null
        error = null
        focusManager.clearFocus()
        if (!editor.goBack()) {
            if (editor.hasChanges) discarding = true else onClose()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = editor.importTarget
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                    readLimited(stream, 65536).decodeToString()
                } ?: error(tr(R.string.ui_cannot_read_private_key)) }
                require(text.contains("PRIVATE KEY")) { tr(R.string.ui_select_a_private_key_not_a_pub) }
                // A picker result must never attach to a removed hop or a different draft.
                val current = editor.profile
                if (current?.id == profile.id && (current.jumps + current.endpoint).any { it.credentialId == target }) {
                    editor.updateCredential(target, (editor.credentials[target] ?: Credential()).copy(privateKey = text))
                    invalidField = null
                    error = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    val endpoint = if (section == HostEditorSection.JUMP || section == HostEditorSection.JUMP_AUTH)
        profile.jumps.firstOrNull { it.credentialId == editor.jumpId } else profile.endpoint
    fun changeEndpoint(value: SshEndpoint) {
        editor.updateProfile(if (value.credentialId == profile.endpoint.credentialId) profile.copy(endpoint = value)
            else profile.copy(jumps = profile.jumps.map { if (it.credentialId == value.credentialId) value else it }))
    }
    fun save() {
        val issue = validateHostDraft(profile, editor.credentials)
        if (issue != null) {
            // Build the hop hierarchy so Back and Remove both return to its list.
            if (issue.section == HostEditorSection.JUMP || issue.section == HostEditorSection.JUMP_AUTH) {
                if (section != HostEditorSection.JUMPS) navigate(HostEditorSection.JUMPS)
                navigate(HostEditorSection.JUMP, issue.endpointId)
                if (issue.section == HostEditorSection.JUMP_AUTH) navigate(HostEditorSection.JUMP_AUTH, issue.endpointId)
            } else navigate(issue.section)
            invalidField = issue.field
            error = tr(issue.message)
            return
        }
        focusManager.clearFocus()
        editor.saving = true
        onSave(profile, editor.credentials) { success ->
            editor.saving = false
            if (success) onClose() else error = tr(R.string.ui_could_not_save_check_local_storage_and)
        }
    }
    val title = tr(if (overviewOnly && section == HostEditorSection.BASIC) R.string.ui_connection_overview else when (section) {
        HostEditorSection.BASIC -> R.string.ui_server_connection
        HostEditorSection.AUTH, HostEditorSection.JUMP_AUTH -> R.string.ui_authentication
        HostEditorSection.PORT -> R.string.ui_port
        HostEditorSection.JUMPS -> R.string.ui_jump_hosts
        HostEditorSection.JUMP -> R.string.ui_jump_host
        HostEditorSection.REMOTE -> R.string.ui_remote_codex
    })
    BackHandler { back() }
    Scaffold(modifier = Modifier.imePadding(), snackbarHost = { snackbar?.let { SnackbarHost(it) } }, topBar = {
        TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
            IconButton(onClick = ::back, enabled = !saving) {
                Icon(WorkbenchIcons.ArrowBack, if (editor.atEntryPage) backLabel else tr(R.string.ui_back))
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }, bottomBar = {
        if (editor.atEntryPage) Surface {
            Button(onClick = ::save, enabled = !editor.loading && !saving,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                Text(tr(if (saving) R.string.ui_saving else R.string.ui_save_connection))
            }
        }
    }) { padding ->
        key(section, editor.jumpId) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (editor.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (reconnect && editor.atEntryPage) Text(tr(R.string.ui_connection_changes_require_reconnect),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (section) {
                    HostEditorSection.BASIC -> {
                        DraftField(profile.name, { editor.updateProfile(profile.copy(name = it)) }, R.string.ui_connection_name,
                            "name", invalidField, enabled = !saving)
                        EndpointAddressFields(profile.endpoint, ::changeEndpoint, invalidField, enabled = !saving)
                        if (overviewOnly) {
                            ServerSettingRow(tr(R.string.ui_advanced_connection), WorkbenchIcons.Settings,
                                profile.endpoint.port.takeIf { it != 22 }?.toString(), enabled = !saving) { navigate(HostEditorSection.PORT) }
                        } else {
                            Column {
                                ServerSettingRow(tr(R.string.ui_port), WorkbenchIcons.Settings, profile.endpoint.port.toString(), enabled = !saving) { navigate(HostEditorSection.PORT) }
                                ServerSettingRow(tr(R.string.ui_authentication), WorkbenchIcons.Key,
                                    authenticationSummary(profile.endpoint, editor.credentials[profile.endpoint.credentialId], editor.loading), enabled = !saving) { navigate(HostEditorSection.AUTH) }
                                ServerSettingRow(tr(R.string.ui_jump_hosts), WorkbenchIcons.Route,
                                    if (profile.jumps.isEmpty()) null else tr(R.string.ui_hops, profile.jumps.size), enabled = !saving) { navigate(HostEditorSection.JUMPS) }
                                ServerSettingRow(tr(R.string.ui_remote_codex), WorkbenchIcons.Terminal,
                                    if (profile.codexPath == "codex" && profile.defaultCwd.isBlank() && profile.socketPath.isBlank()) null else tr(R.string.ui_custom_configuration), enabled = !saving) { navigate(HostEditorSection.REMOTE) }
                            }
                        }
                        if (existing) TextButton(onClick = { deleting = true }, enabled = !saving) {
                            Icon(WorkbenchIcons.Trash, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(R.string.ui_delete_server_connection), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HostEditorSection.PORT -> DraftField(if (profile.endpoint.port == 0) "" else profile.endpoint.port.toString(),
                        { changeEndpoint(profile.endpoint.copy(port = it.toIntOrNull() ?: 0)) }, R.string.ui_port, "port", invalidField, KeyboardType.Number, enabled = !saving)
                    HostEditorSection.AUTH, HostEditorSection.JUMP_AUTH -> endpoint?.let { value ->
                        AuthenticationFields(value, editor.credentials[value.credentialId] ?: Credential(), invalidField,
                            enabled = !editor.loading && !saving, onEndpoint = ::changeEndpoint,
                            onCredential = { editor.updateCredential(value.credentialId, it) }, onImport = {
                                editor.importTarget = value.credentialId
                                picker.launch(arrayOf("*/*"))
                            })
                        Text(tr(R.string.ui_credentials_are_encrypted_on_this_device_host), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HostEditorSection.JUMPS -> {
                        Text(tr(R.string.ui_add_hosts_in_connection_order_each_host), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        profile.jumps.forEachIndexed { index, hop ->
                            ServerSettingRow(tr(R.string.ui_hop, index + 1), WorkbenchIcons.Dns,
                                hop.host.ifBlank { tr(R.string.ui_not_configured) }, enabled = !saving) { navigate(HostEditorSection.JUMP, hop.credentialId) }
                        }
                        OutlinedButton(onClick = {
                            val hop = SshEndpoint()
                            editor.updateProfile(profile.copy(jumps = profile.jumps + hop))
                            navigate(HostEditorSection.JUMP, hop.credentialId)
                        }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(WorkbenchIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.ui_add_jump_host))
                        }
                    }
                    HostEditorSection.JUMP -> endpoint?.let { hop ->
                        Text(tr(R.string.ui_hop, profile.jumps.indexOf(hop) + 1), style = MaterialTheme.typography.titleMedium)
                        EndpointAddressFields(hop, ::changeEndpoint, invalidField, enabled = !saving)
                        DraftField(if (hop.port == 0) "" else hop.port.toString(), { changeEndpoint(hop.copy(port = it.toIntOrNull() ?: 0)) },
                            R.string.ui_port, "port", invalidField, KeyboardType.Number, enabled = !saving)
                        ServerSettingRow(tr(R.string.ui_authentication), WorkbenchIcons.Key,
                            authenticationSummary(hop, editor.credentials[hop.credentialId], editor.loading), enabled = !saving) { navigate(HostEditorSection.JUMP_AUTH) }
                        TextButton(onClick = {
                            editor.updateProfile(profile.copy(jumps = profile.jumps.filterNot { it.credentialId == hop.credentialId }))
                            back()
                        }, enabled = !saving) { Text(tr(R.string.ui_remove_jump_host), color = MaterialTheme.colorScheme.error) }
                    }
                    HostEditorSection.REMOTE -> {
                        DraftField(profile.codexPath, { editor.updateProfile(profile.copy(codexPath = it)) }, R.string.ui_executable_path, "codex", invalidField, enabled = !saving)
                        DraftField(profile.defaultCwd, { editor.updateProfile(profile.copy(defaultCwd = it)) }, R.string.ui_default_working_directory, "cwd", invalidField, enabled = !saving)
                        DraftField(profile.socketPath, { editor.updateProfile(profile.copy(socketPath = it)) }, R.string.ui_service_socket_path_optional, "socket", invalidField, enabled = !saving)
                        Text(tr(R.string.ui_leave_empty_to_use_the_default_shared), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                (error ?: loadError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (discarding) AlertDialog(onDismissRequest = { discarding = false }, title = { Text(tr(R.string.ui_discard_unsaved_changes)) },
        confirmButton = { TextButton(onClick = onClose) { Text(tr(R.string.ui_discard_changes)) } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text(tr(R.string.ui_cancel)) } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(tr(R.string.ui_delete_server_connection_160)) },
        text = { Text(tr(R.string.ui_this_removes_the_connection_and_credentials_saved)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(tr(R.string.ui_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(tr(R.string.ui_cancel)) } })
}

internal data class HostDraftIssue(val section: HostEditorSection, val field: String, val message: Int, val endpointId: String = "")

internal fun validateHostDraft(profile: HostProfile, credentials: Map<String, Credential>): HostDraftIssue? {
    if (profile.name.isBlank()) return HostDraftIssue(HostEditorSection.BASIC, "name", R.string.ui_enter_a_connection_name)
    for (endpoint in listOf(profile.endpoint) + profile.jumps) {
        val hop = endpoint.credentialId != profile.endpoint.credentialId
        val page = if (hop) HostEditorSection.JUMP else HostEditorSection.BASIC
        if (endpoint.host.isBlank()) return HostDraftIssue(page, "host", R.string.ui_enter_a_host_username_and_valid_port, endpoint.credentialId)
        if (endpoint.username.isBlank()) return HostDraftIssue(page, "username", R.string.ui_enter_a_host_username_and_valid_port, endpoint.credentialId)
        if (endpoint.port !in 1..65535) return HostDraftIssue(if (hop) page else HostEditorSection.PORT, "port", R.string.ui_enter_a_host_username_and_valid_port, endpoint.credentialId)
        if (endpoint.auth == AuthKind.PRIVATE_KEY && credentials[endpoint.credentialId]?.privateKey.isNullOrBlank())
            return HostDraftIssue(if (hop) HostEditorSection.JUMP_AUTH else HostEditorSection.AUTH, "key", R.string.ui_import_private_key, endpoint.credentialId)
    }
    if (profile.codexPath.isBlank() || '\n' in profile.codexPath) return HostDraftIssue(HostEditorSection.REMOTE, "codex", R.string.ui_enter_the_codex_executable_path)
    return null
}

@Composable internal fun authenticationSummary(endpoint: SshEndpoint, secret: Credential?, loading: Boolean = false): String {
    val method = tr(when (endpoint.auth) {
        AuthKind.PRIVATE_KEY -> R.string.ui_private_key
        AuthKind.PASSWORD -> R.string.ui_password
        AuthKind.INTERACTIVE -> R.string.ui_interactive_authentication
    })
    if (loading || secret == null || endpoint.auth == AuthKind.INTERACTIVE) return method
    return method + " · " + tr(when {
        endpoint.auth == AuthKind.PRIVATE_KEY -> if (secret.privateKey.isNotBlank()) R.string.ui_configured else R.string.ui_not_configured
        secret.password.isNotBlank() -> R.string.ui_configured
        else -> R.string.ui_ask_when_connecting
    })
}

@Composable private fun EndpointAddressFields(endpoint: SshEndpoint, onEndpoint: (SshEndpoint) -> Unit, invalid: String?, enabled: Boolean = true) {
    DraftField(endpoint.host, { onEndpoint(endpoint.copy(host = it.trim())) }, R.string.ui_host_address, "host", invalid, KeyboardType.Uri, enabled)
    DraftField(endpoint.username, { onEndpoint(endpoint.copy(username = it.trim())) }, R.string.ui_username, "username", invalid, enabled = enabled)
}

@Composable private fun DraftField(value: String, onChange: (String) -> Unit, label: Int, field: String, invalid: String?,
    keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(invalid) { if (invalid == field) focus.requestFocus() }
    OutlinedTextField(value, onChange, label = { Text(tr(label)) }, singleLine = true, enabled = enabled,
        isError = invalid == field, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).then(imeAwareFieldModifier()))
}

@Composable private fun AuthenticationFields(endpoint: SshEndpoint, secret: Credential, invalid: String?, enabled: Boolean,
    onEndpoint: (SshEndpoint) -> Unit, onCredential: (Credential) -> Unit, onImport: () -> Unit) {
    Column(Modifier.selectableGroup()) {
        listOf(AuthKind.PRIVATE_KEY to R.string.ui_private_key, AuthKind.PASSWORD to R.string.ui_password,
            AuthKind.INTERACTIVE to R.string.ui_interactive_authentication).forEach { (kind, label) ->
            Row(Modifier.fillMaxWidth().selectable(selected = endpoint.auth == kind, enabled = enabled,
                role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onEndpoint(endpoint.copy(auth = kind)) })
                .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = endpoint.auth == kind, onClick = null, enabled = enabled)
                Spacer(Modifier.width(12.dp)); Text(tr(label), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    when (endpoint.auth) {
        AuthKind.PRIVATE_KEY -> {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(invalid) { if (invalid == "key") focus.requestFocus() }
            OutlinedButton(onClick = onImport, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).focusRequester(focus)) {
                Icon(WorkbenchIcons.Key, null, Modifier.size(20.dp), tint = if (enabled) functionalIconColor(WorkbenchIcons.Key) else LocalContentColor.current); Spacer(Modifier.width(8.dp))
                Text(tr(if (secret.privateKey.isBlank()) R.string.ui_import_private_key else R.string.ui_key_imported_replace))
            }
            SecretField(secret.passphrase, { onCredential(secret.copy(passphrase = it)) }, R.string.ui_key_passphrase_optional, enabled)
        }
        AuthKind.PASSWORD -> SecretField(secret.password, { onCredential(secret.copy(password = it)) }, R.string.ui_ssh_password, enabled)
        AuthKind.INTERACTIVE -> Text(tr(R.string.ui_enter_the_password_or_verification_code_requested), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SecretField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value, onChange, label = { Text(tr(label)) }, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true, modifier = Modifier.fillMaxWidth().then(imeAwareFieldModifier()))
}

/** Lightweight disclosure row; long summaries wrap instead of squeezing the label. */
@Composable internal fun ServerSettingRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = if (summary == null) 56.dp else 64.dp).padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) functionalIconColor(icon) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(WorkbenchIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun imeAwareFieldModifier(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, imeBottom) {
        if (focused) { withFrameNanos { }; requester.bringIntoView() }
    }
    return Modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}

@Composable fun ConnectionPromptDialog(prompt: ConnectionPrompt?) {
    when (prompt) {
        is ConnectionPrompt.HostKey -> {
            val c = prompt.challenge
            AlertDialog(onDismissRequest = { prompt.answer.complete(false) }, title = { Text(if (c.previous == null) tr(R.string.ui_verify_host_identity) else tr(R.string.ui_host_fingerprint_changed)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${c.host}:${c.port}\n${c.algorithm}")
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(c.fingerprint) }
                    if (c.previous != null) Text(tr(R.string.ui_previous_nverify_this_change_through_a_trusted , c.previous))
                    else Text(tr(R.string.ui_compare_this_fingerprint_with_one_provided_by))
                } }, confirmButton = { TextButton(onClick = { prompt.answer.complete(true) }) { Text(if (c.previous == null) tr(R.string.ui_trust_and_connect) else tr(R.string.ui_verified_update_trust)) } },
                dismissButton = { TextButton(onClick = { prompt.answer.complete(false) }) { Text(tr(R.string.ui_cancel)) } })
        }
        is ConnectionPrompt.Keyboard -> {
            var answers by remember(prompt) { mutableStateOf(List(prompt.challenge.prompts.size) { "" }) }
            AlertDialog(onDismissRequest = { prompt.answer.cancel() }, title = { Text(prompt.challenge.name.ifBlank { tr(R.string.ui_ssh_authentication) }) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (prompt.challenge.instruction.isNotBlank()) Text(prompt.challenge.instruction)
                    prompt.challenge.prompts.forEachIndexed { index, (label, echo) ->
                        OutlinedTextField(answers[index], { text -> answers = answers.toMutableList().also { it[index] = text } }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().then(imeAwareFieldModifier()),
                            visualTransformation = if (echo) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = if (echo) KeyboardType.Text else KeyboardType.Password, autoCorrectEnabled = false))
                    }
                } }, confirmButton = { TextButton(onClick = { prompt.answer.complete(answers) }) { Text(tr(R.string.ui_continue)) } }, dismissButton = { TextButton(onClick = { prompt.answer.cancel() }) { Text(tr(R.string.ui_cancel)) } })
        }
        null -> Unit
    }
}
