package com.privateai.settings

import android.app.Application
import androidx.datastore.preferences.core.set
import androidx.lifecycle.AndroidViewModel
import com.privateai.ai.EngineHolder
import com.privateai.data.model.ModelManager
import com.privateai.util.isOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class SettingsInfo(
    val modelsBytes: Long = 0,
    val dataBytes: Long = 0,
    val freeBytes: Long = 0,
    val inMemory: Boolean = false,
    val modelSelected: Boolean = false,
    val busy: Boolean = false,
    val online: Boolean = false,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val models = ModelManager(app)
    private val _info = MutableStateFlow(SettingsInfo())
    val info: StateFlow<SettingsInfo> = _info
    val totalRamGb: Double = models.totalRam() / 1_073_741_824.0

    /** Refreshes the numbers every 2 seconds while the Settings screen is open. */
    suspend fun monitor() {
        while (currentCoroutineContext().isActive) {
            val i = withContext(Dispatchers.IO) {
                val dbBase = ctx.getDatabasePath("privateai.db")
                val data = listOf(dbBase, java.io.File(dbBase.path + "-wal"), java.io.File(dbBase.path + "-shm"))
                    .sumOf { if (it.exists()) it.length() else 0L }
                SettingsInfo(
                    modelsBytes = models.storageUsed(),
                    dataBytes = data,
                    freeBytes = ctx.filesDir.usableSpace,
                    inMemory = EngineHolder.engine.inMemory,
                    modelSelected = EngineHolder.engine.isLoaded,
                    busy = EngineHolder.busy.value,
                    online = isOnline(ctx),
                )
            }
            _info.update { i }
            delay(2000)
        }
    }

    fun setTheme(v: String) = Settings.update { this[Settings.Keys.THEME] = v }
    fun setContext(v: Int) = Settings.update { this[Settings.Keys.CTX] = v }
    fun setThreads(v: Int) = Settings.update { this[Settings.Keys.THREADS] = v }
    fun setThermal(v: Boolean) = Settings.update { this[Settings.Keys.THERMAL] = v }
    fun setIdle(v: Int) = Settings.update { this[Settings.Keys.IDLE] = v }
    fun setReplyTokens(v: Int) = Settings.update { this[Settings.Keys.MAX_TOKENS] = v }
    fun setTemperature(v: Float) = Settings.update { this[Settings.Keys.TEMP] = v }
}
