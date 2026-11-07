package com.privateai.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val loaded: Boolean = false,
    val theme: String = "system",        // system | light | dark
    val contextLength: Int = 2048,       // 2048..4096
    val threads: Int = 0,                // 0 = automatic
    val thermalProtection: Boolean = true,
    val idleUnloadMin: Int = 10,         // free the model's RAM after this many idle minutes; 0 = never
    val maxReplyTokens: Int = 512,
    val temperature: Float = 0.7f,
)

/** App-wide settings kept in Jetpack DataStore. Reads are instant (cached); writes are saved in the background. */
object Settings {
    object Keys {
        val THEME = stringPreferencesKey("theme")
        val CTX = intPreferencesKey("context_length")
        val THREADS = intPreferencesKey("threads")
        val THERMAL = booleanPreferencesKey("thermal_protection")
        val IDLE = intPreferencesKey("idle_unload_min")
        val MAX_TOKENS = intPreferencesKey("max_reply_tokens")
        val TEMP = floatPreferencesKey("temperature")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _flow = MutableStateFlow(AppSettings())
    private lateinit var store: DataStore<Preferences>

    val flow: StateFlow<AppSettings> = _flow
    val value: AppSettings get() = _flow.value

    fun init(ctx: Context) {
        store = ctx.applicationContext.settingsStore
        scope.launch {
            store.data.collect { p ->
                _flow.value = AppSettings(
                    loaded = true,
                    theme = p[Keys.THEME] ?: "system",
                    contextLength = p[Keys.CTX] ?: 2048,
                    threads = p[Keys.THREADS] ?: 0,
                    thermalProtection = p[Keys.THERMAL] ?: true,
                    idleUnloadMin = p[Keys.IDLE] ?: 10,
                    maxReplyTokens = p[Keys.MAX_TOKENS] ?: 512,
                    temperature = p[Keys.TEMP] ?: 0.7f,
                )
            }
        }
    }

    suspend fun awaitLoaded() { flow.first { it.loaded } }

    fun update(block: MutablePreferences.() -> Unit) {
        scope.launch { store.edit { it.block() } }
    }

    fun autoThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    fun effectiveThreads(s: AppSettings): Int = if (s.threads > 0) s.threads else autoThreads()
}
