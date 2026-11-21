package dev.remotegpt.app

import android.app.Application
import androidx.room.Room
import dev.remotegpt.app.data.*

class RemoteApplication : Application() {
    private val servicePrefs by lazy { getSharedPreferences("connection_service", MODE_PRIVATE) }
    private val keepAlivePreference by lazy {
        androidx.compose.runtime.mutableStateOf(servicePrefs.getBoolean("keep_alive", false))
    }
    var keepAliveRequested: Boolean
        get() = keepAlivePreference.value
        set(value) {
            servicePrefs.edit().putBoolean("keep_alive", value).apply()
            keepAlivePreference.value = value
        }

    val crashes by lazy { CrashDiagnostics(this) }
    @Volatile private var workbenchInstance: WorkbenchViewModel? = null
    override fun onCreate() { super.onCreate(); AppStrings.initialize(this); crashes.install() }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) workbenchInstance?.trimMemory()
    }
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "workbench.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
    val secrets by lazy { SecretStore(this) }
    val settings by lazy { Settings(this) }

    /** One process-wide session owner shared by the Activity and foreground service. */
    @Synchronized fun workbench(): WorkbenchViewModel = workbenchInstance ?: WorkbenchViewModel(this).also { workbenchInstance = it }
}
