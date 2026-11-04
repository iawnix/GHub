package dev.remotegpt.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import dev.remotegpt.app.ui.*

class MainActivity : AppCompatActivity() {
    // The connection owner is process scoped so the foreground service and
    // Activity always observe the same SSH/RPC state.
    private val vm: WorkbenchViewModel by lazy { (application as RemoteApplication).workbench() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            val dark = state.theme == "dark" || (state.theme == "system" && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            WorkbenchTheme(state.theme) { Workbench(vm, state) }
        }
        // Recreation (rotation, theme) must not reopen a conversation the user has since left.
        if (savedInstanceState == null) openFromNotification(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromNotification(intent)
    }
    private fun openFromNotification(intent: Intent?) {
        if (intent == null) return
        val host = intent.getStringExtra(TaskNotifier.EXTRA_HOST) ?: return
        val thread = intent.getStringExtra(TaskNotifier.EXTRA_THREAD) ?: return
        vm.openFromNotification(host, thread)
        // Consume once so a later recreation from history does not repeat the navigation.
        intent.removeExtra(TaskNotifier.EXTRA_HOST)
    }
    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { vm.onBackground(); super.onStop() }
}
