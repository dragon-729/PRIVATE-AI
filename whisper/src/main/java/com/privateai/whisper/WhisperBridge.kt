package com.privateai.whisper

object WhisperBridge {
    init { System.loadLibrary("whisper_jni") }

    /** Returns a native handle, or 0 if the model could not be loaded. */
    external fun nativeInit(path: String): Long

    /** [audio]: 16 kHz mono float samples in [-1, 1]. [lang]: "en", "auto", ... Returns UTF-8 text or null on error. */
    external fun nativeTranscribe(handle: Long, audio: FloatArray, threads: Int, lang: String): ByteArray?

    external fun nativeFree(handle: Long)
}
