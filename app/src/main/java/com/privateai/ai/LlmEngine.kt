package com.privateai.ai

import kotlinx.coroutines.flow.Flow

data class ChatTurn(val role: String, val content: String) // role: system | user | assistant

data class GenerationConfig(val maxTokens: Int = 512, val temperature: Float = 0.7f)

data class ModelConfiguration(val path: String, val contextLength: Int = 2048, val threads: Int = 4)

/** What the thermal governor wants for the next generation. */
data class ThermalAdvice(val threads: Int, val maxTokensCap: Int?, val blockReason: String?)

/** UI talks only to this interface, never to a specific backend. */
interface LlmEngine {
    /** A model is selected. It may be temporarily out of RAM (see [inMemory]) and reloads automatically on use. */
    val isLoaded: Boolean
    /** The model weights are currently held in RAM. */
    val inMemory: Boolean
    /** Tokens produced by the most recent generate() call (used by the benchmark). */
    val lastTokenCount: Int
    suspend fun load(config: ModelConfiguration)
    fun generate(history: List<ChatTurn>, config: GenerationConfig = GenerationConfig()): Flow<String>
    fun stop()
    /** Frees the model's RAM but remembers it; the next generate() reloads it. Does nothing while generating. */
    suspend fun releaseMemory()
    /** Forgets the model completely. */
    suspend fun unload()
}
