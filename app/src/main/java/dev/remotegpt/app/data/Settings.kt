package dev.remotegpt.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.remotegpt.app.ProjectProfile
import dev.remotegpt.app.ResultReceipt
import dev.remotegpt.app.TerminalPreferences
import dev.remotegpt.client.HostProfile
import dev.remotegpt.client.hopRoutePrefix
import dev.remotegpt.client.wireJson
import kotlinx.serialization.encodeToString

internal fun hostKeyPreferenceName(host: String, port: Int, route: String = ""): String =
    if (route.isBlank()) "host_key:${host}:${port}" else "host_key:${route}->${host}:${port}"

internal fun profileHostKeyNames(profile: HostProfile): Set<String> {
    val endpoints = profile.jumps + profile.endpoint
    return endpoints.mapIndexed { index, endpoint ->
        hostKeyPreferenceName(endpoint.host, endpoint.port, hopRoutePrefix(profile.jumps, index))
    }.toSet()
}

private val Context.settingsStore by preferencesDataStore("settings")
class Settings(private val context: Context) {
    suspend fun results(host: String): Map<String, ResultReceipt> = context.settingsStore.data.first()[stringPreferencesKey("results:${host}")]
        ?.let { runCatching { wireJson.decodeFromString<Map<String, ResultReceipt>>(it) }.getOrNull() }.orEmpty()
    suspend fun results(host: String, value: Map<String, ResultReceipt>) {
        context.settingsStore.edit { it[stringPreferencesKey("results:${host}")] = wireJson.encodeToString(value) }
    }
    val theme = context.settingsStore.data.map { it[stringPreferencesKey("theme")] ?: "system" }
    val terminal = context.settingsStore.data.map {
        TerminalPreferences(
            (it[floatPreferencesKey("terminal_font_size")] ?: 14f).let { size -> if (size.isFinite()) size.coerceIn(10f, 28f) else 14f },
            it[booleanPreferencesKey("terminal_extra_keys")] ?: false,
        )
    }
    suspend fun terminalFontSize(value: Float) { if (value.isFinite()) context.settingsStore.edit { it[floatPreferencesKey("terminal_font_size")] = value.coerceIn(10f, 28f) } }
    suspend fun terminalExtraKeys(value: Boolean) { context.settingsStore.edit { it[booleanPreferencesKey("terminal_extra_keys")] = value } }
    val activeHost = context.settingsStore.data.map { it[stringPreferencesKey("active_host")] }
    suspend fun theme(value: String) { context.settingsStore.edit { it[stringPreferencesKey("theme")] = value } }
    suspend fun activeHost(value: String) { context.settingsStore.edit { it[stringPreferencesKey("active_host")] = value } }
    suspend fun lastThread(host: String): String? = context.settingsStore.data.first()[stringPreferencesKey("thread:${host}")]
    suspend fun lastThread(host: String, thread: String?) { context.settingsStore.edit { if (thread == null) it.remove(stringPreferencesKey("thread:${host}")) else it[stringPreferencesKey("thread:${host}")] = thread } }
    suspend fun clearLastThreadIf(host: String, thread: String) { context.settingsStore.edit {
        val key = stringPreferencesKey("thread:${host}")
        if (it[key] == thread) it.remove(key)
    } }
    suspend fun projects(host: String): List<ProjectProfile> = context.settingsStore.data.first()[stringPreferencesKey("projects:${host}")]
        ?.let { runCatching { wireJson.decodeFromString<List<ProjectProfile>>(it) }.getOrNull() }.orEmpty()
    suspend fun projects(host: String, projects: List<ProjectProfile>) { context.settingsStore.edit { it[stringPreferencesKey("projects:${host}")] = wireJson.encodeToString(projects) } }
    suspend fun hiddenProjects(host: String): Set<String> =
        context.settingsStore.data.first()[stringSetPreferencesKey("hidden_projects:${host}")] ?: emptySet()
    suspend fun projectVisibility(host: String, path: String, hidden: Boolean) { context.settingsStore.edit {
        val key = stringSetPreferencesKey("hidden_projects:${host}")
        val paths = it[key] ?: emptySet()
        it[key] = if (hidden) paths + path else paths - path
    } }
    suspend fun lastProject(host: String): String? = context.settingsStore.data.first()[stringPreferencesKey("project:${host}")]
    suspend fun lastProject(host: String, path: String?) { context.settingsStore.edit {
        val key = stringPreferencesKey("project:${host}")
        if (path == null) it.remove(key) else it[key] = path
    } }
    suspend fun location(host: String, project: String?, thread: String?) { context.settingsStore.edit {
        val projectKey = stringPreferencesKey("project:${host}")
        val threadKey = stringPreferencesKey("thread:${host}")
        if (project == null) it.remove(projectKey) else it[projectKey] = project
        if (thread == null) it.remove(threadKey) else it[threadKey] = thread
    } }
    suspend fun reconnectHosts(): Set<String> = context.settingsStore.data.first()[stringSetPreferencesKey("reconnect_hosts")] ?: emptySet()
    suspend fun reconnect(host: String, enabled: Boolean) { context.settingsStore.edit {
        val key = stringSetPreferencesKey("reconnect_hosts")
        val current = it[key] ?: emptySet()
        it[key] = if (enabled) current + host else current - host
    } }
    private fun hostKey(host: String, port: Int, route: String = "") =
        stringPreferencesKey(hostKeyPreferenceName(host, port, route))
    suspend fun fingerprint(host: String, port: Int, route: String = ""): String? =
        context.settingsStore.data.first()[hostKey(host, port, route)]
    suspend fun trust(host: String, port: Int, fingerprint: String, route: String = "") {
        context.settingsStore.edit { it[hostKey(host, port, route)] = fingerprint }
    }
    suspend fun removeHost(host: String, unusedHostKeys: Set<String> = emptySet()) {
        context.settingsStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("results:${host}"))
            prefs.remove(stringPreferencesKey("thread:${host}"))
            prefs.remove(stringPreferencesKey("projects:${host}"))
            prefs.remove(stringPreferencesKey("hidden_projects:${host}"))
            prefs.remove(stringPreferencesKey("project:${host}"))
            if (prefs[stringPreferencesKey("active_host")] == host) {
                prefs.remove(stringPreferencesKey("active_host"))
            }
            val reconnectKey = stringSetPreferencesKey("reconnect_hosts")
            prefs[reconnectKey]?.let { current -> prefs[reconnectKey] = current - host }
            unusedHostKeys.forEach { name -> prefs.remove(stringPreferencesKey(name)) }
        }
    }
}
