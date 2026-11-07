package dev.remotegpt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dev.remotegpt.client.Credential
import dev.remotegpt.client.HostProfile
import dev.remotegpt.client.wireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

@Serializable
enum class HostEditorSection { BASIC, AUTH, PORT, JUMPS, JUMP, JUMP_AUTH, REMOTE }

@Serializable
private data class HostEditorPage(val section: HostEditorSection, val jumpId: String)

/** Only connection metadata enters saved state. Unsaved credentials stay in memory. */
class HostEditorViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var profile by mutableStateOf(saved.get<String>(PROFILE)?.let {
        runCatching { wireJson.decodeFromString<HostProfile>(it) }.getOrNull()
    })
        private set
    var credentials by mutableStateOf<Map<String, Credential>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
    private var loaded = false
    private var originalProfile: HostProfile? = saved.get<String>(ORIGINAL)?.let {
        runCatching { wireJson.decodeFromString<HostProfile>(it) }.getOrNull()
    } ?: profile
    private var originalCredentials: Map<String, Credential> = emptyMap()
    var section by mutableStateOf(runCatching { HostEditorSection.valueOf(saved[SECTION] ?: "BASIC") }.getOrDefault(HostEditorSection.BASIC))
        private set
    var jumpId by mutableStateOf(saved.get<String>(JUMP_ID).orEmpty())
        private set
    private var history by mutableStateOf(saved.get<String>(HISTORY)?.let {
        runCatching { wireJson.decodeFromString<List<HostEditorPage>>(it) }.getOrNull()
    }.orEmpty())
    val atEntryPage: Boolean get() = history.isEmpty()
    val hasChanges: Boolean get() = profile != originalProfile ||
        profile?.let { value -> (value.jumps + value.endpoint).any {
            (credentials[it.credentialId] ?: Credential()) != (originalCredentials[it.credentialId] ?: Credential())
        } } == true

    fun navigate(value: HostEditorSection, endpointId: String = jumpId) {
        if (value == section && endpointId == jumpId) return
        updateHistory(history + HostEditorPage(section, jumpId))
        show(value, endpointId)
    }

    /** False means the editor was opened here, so Back should leave the draft. */
    fun goBack(): Boolean {
        val previous = history.lastOrNull() ?: return false
        updateHistory(history.dropLast(1))
        show(previous.section, previous.jumpId)
        return true
    }

    private fun updateHistory(value: List<HostEditorPage>) {
        history = value
        saved[HISTORY] = wireJson.encodeToString(value)
    }

    private fun show(value: HostEditorSection, endpointId: String) {
        section = value
        jumpId = endpointId
        saved[SECTION] = value.name
        saved[JUMP_ID] = endpointId
    }
    var importTarget: String
        get() = saved[IMPORT_TARGET] ?: ""
        set(value) { saved[IMPORT_TARGET] = value }

    fun open(value: HostProfile, section: HostEditorSection = HostEditorSection.BASIC) {
        originalProfile = value
        saved[ORIGINAL] = wireJson.encodeToString(value)
        originalCredentials = emptyMap()
        updateHistory(emptyList())
        show(section, "")
        saving = false
        credentials = emptyMap()
        loaded = false
        loading = true
        importTarget = ""
        updateProfile(value)
    }

    fun updateProfile(value: HostProfile) {
        profile = value
        saved[PROFILE] = wireJson.encodeToString(value)
    }

    suspend fun loadCredentials(read: suspend (String) -> Credential) {
        if (loaded) return
        val current = profile ?: return
        try {
            val restored = (current.jumps + current.endpoint).associate { it.credentialId to read(it.credentialId) }
            if (profile?.id == current.id) { originalCredentials = restored; credentials = restored + credentials; loaded = true }
        } finally { if (profile?.id == current.id) loading = false }
    }

    fun updateCredential(id: String, value: Credential) { credentials = credentials + (id to value) }

    fun close() {
        profile = null
        saving = false
        credentials = emptyMap()
        loaded = false
        loading = true
        originalProfile = null
        originalCredentials = emptyMap()
        history = emptyList()
        saved.remove<String>(HISTORY)
        saved.remove<String>(ORIGINAL)
        saved.remove<String>(SECTION)
        saved.remove<String>(JUMP_ID)
        saved.remove<String>(PROFILE)
        saved.remove<String>(IMPORT_TARGET)
    }

    companion object {
        private const val HISTORY = "host_editor_history"
        private const val ORIGINAL = "host_editor_original"
        private const val SECTION = "host_editor_section"
        private const val JUMP_ID = "host_editor_jump_id"
        private const val PROFILE = "host_editor_profile"
        private const val IMPORT_TARGET = "host_editor_import_target"
    }
}
