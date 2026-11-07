package com.privateai.ai.llama

import com.privateai.ai.ChatTurn
import com.privateai.ai.GenerationConfig
import com.privateai.ai.LlmEngine
import com.privateai.ai.ModelConfiguration
import com.privateai.ai.ThermalAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamaCppEngine(private val governor: ((Int) -> ThermalAdvice)? = null) : LlmEngine {
    @Volatile private var handle = 0L
    @Volatile private var modelConfig: ModelConfiguration? = null   // kept after releaseMemory() so we can reload
    @Volatile private var threadsInUse = -1
    @Volatile private var lastCount = 0
    private val lock = Mutex() // held while native code is running

    override val isLoaded: Boolean get() = handle != 0L || modelConfig != null
    override val inMemory: Boolean get() = handle != 0L
    override val lastTokenCount: Int get() = lastCount

    override suspend fun load(config: ModelConfiguration) {
        unload()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val h = LlamaBridge.nativeLoad(config.path, config.contextLength, config.threads)
                if (h == 0L) error("Could not load model. The file may be corrupted, unsupported, or too large for this device's RAM.")
                handle = h
                modelConfig = config
                threadsInUse = config.threads
            }
        }
    }

    override fun generate(history: List<ChatTurn>, config: GenerationConfig): Flow<String> = callbackFlow {
        if (!isLoaded) { close(IllegalStateException("No model loaded")); return@callbackFlow }
        var finished = false
        launch(Dispatchers.Default) {
            try {
                lock.withLock {
                    val cfg = modelConfig
                    if (handle == 0L) {   // model was released to save RAM: bring it back transparently
                        val c = cfg ?: throw IllegalStateException("No model loaded")
                        val nh = LlamaBridge.nativeLoad(c.path, c.contextLength, c.threads)
                        if (nh == 0L) throw IllegalStateException("Could not reload the model (not enough free RAM?). Close other apps and try again.")
                        handle = nh
                        threadsInUse = c.threads
                    }
                    val h = handle
                    var maxTokens = config.maxTokens
                    val advice = governor?.invoke(cfg?.threads ?: 4)
                    if (advice != null) {
                        advice.blockReason?.let { throw IllegalStateException(it) }
                        if (advice.threads != threadsInUse) {
                            LlamaBridge.nativeSetThreads(h, advice.threads)
                            threadsInUse = advice.threads
                        }
                        advice.maxTokensCap?.let { maxTokens = minOf(maxTokens, it) }
                    }
                    val utf8 = Utf8Stream()
                    val rc = LlamaBridge.nativeGenerate(
                        h,
                        history.map { it.role }.toTypedArray(),
                        history.map { it.content }.toTypedArray(),
                        maxTokens, config.temperature,
                        TokenCallback { bytes ->
                            val s = utf8.push(bytes)
                            if (s.isNotEmpty()) trySend(s)
                        }
                    )
                    finished = true
                    lastCount = if (rc >= 0) rc else 0
                    if (rc < 0) close(IllegalStateException(errorText(rc))) else close()
                }
            } catch (t: Throwable) {
                finished = true
                close(t)
            }
        }
        awaitClose { if (!finished) LlamaBridge.nativeStop(handle) }
    }

    override fun stop() { val h = handle; if (h != 0L) LlamaBridge.nativeStop(h) }

    override suspend fun releaseMemory() {
        if (handle == 0L) return
        withContext(Dispatchers.IO) {
            if (lock.tryLock()) {           // never free while a generation is running
                try {
                    val h = handle
                    if (h != 0L) { handle = 0L; LlamaBridge.nativeFree(h) }
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    override suspend fun unload() {
        val h = handle
        modelConfig = null
        if (h == 0L) return
        LlamaBridge.nativeStop(h)
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (handle == h) { handle = 0L; LlamaBridge.nativeFree(h) }
            }
        }
    }

    private fun errorText(rc: Int) = when (rc) {
        -3 -> "This model's chat template could not be applied."
        -4 -> "Could not tokenize the prompt."
        -5 -> "The conversation is too long for the context window. Start a new chat or raise the context length in Settings."
        -6 -> "Native decode error."
        else -> "Generation failed ($rc)."
    }
}
