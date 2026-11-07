package com.privateai.ai.llama

fun interface TokenCallback {
    fun onPiece(bytes: ByteArray)
}

object LlamaBridge {
    init { System.loadLibrary("privateai_native") }

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>,
        maxTokens: Int, temperature: Float, callback: TokenCallback
    ): Int
    external fun nativeStop(handle: Long)
    external fun nativeSetThreads(handle: Long, n: Int)
    external fun nativeFree(handle: Long)
}
