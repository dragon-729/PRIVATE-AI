package com.privateai.ai

import android.content.Context
import android.os.SystemClock
import com.privateai.ai.llama.LlamaCppEngine
import kotlinx.coroutines.flow.MutableStateFlow

/** One engine for the whole app; only one task (chat, note action, PDF, voice, benchmark) may use the CPU at a time. */
object EngineHolder {
    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    val engine: LlmEngine by lazy {
        LlamaCppEngine(governor = { base -> ThermalGovernor.advise(appContext, base) })
    }
    val busy = MutableStateFlow(false)
    @Volatile var lastUsed: Long = SystemClock.elapsedRealtime()

    fun tryAcquire(): Boolean {
        val ok = busy.compareAndSet(false, true)
        if (ok) lastUsed = SystemClock.elapsedRealtime()
        return ok
    }

    fun release() {
        lastUsed = SystemClock.elapsedRealtime()
        busy.value = false
    }
}
