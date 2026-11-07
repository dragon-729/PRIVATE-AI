package com.privateai

import android.app.Application
import android.os.SystemClock
import com.privateai.ai.EngineHolder
import com.privateai.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PrivateAiApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        EngineHolder.init(this)
        Settings.init(this)
        // Idle watcher: after N quiet minutes the model's RAM is freed; it reloads automatically on the next use.
        scope.launch {
            while (true) {
                delay(30_000)
                val minutes = Settings.value.idleUnloadMin
                val engine = EngineHolder.engine
                if (minutes > 0 && engine.inMemory && !EngineHolder.busy.value &&
                    SystemClock.elapsedRealtime() - EngineHolder.lastUsed > minutes * 60_000L
                ) engine.releaseMemory()
            }
        }
    }

    /** Android is short on memory: give the model's RAM back if nothing is running. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 15 = running critically low on memory, >= 40 = our process is in the background list
        if ((level == 15 || level >= 40) && !EngineHolder.busy.value) {
            scope.launch { EngineHolder.engine.releaseMemory() }
        }
    }
}
