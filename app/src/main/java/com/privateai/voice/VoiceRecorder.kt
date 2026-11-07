package com.privateai.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records 16 kHz mono audio (what Whisper expects) into memory. Nothing is written to disk or sent anywhere. */
class VoiceRecorder {
    private companion object {
        const val RATE = 16_000
        const val MAX_SAMPLES = RATE * 60   // one minute cap
    }

    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    private val chunks = ArrayList<ShortArray>()
    private var total = 0

    val isRecording: Boolean get() = recording
    val seconds: Float get() = synchronized(chunks) { total } / RATE.toFloat()

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling this
    fun start(): Boolean {
        if (recording) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        } catch (_: Throwable) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        synchronized(chunks) { chunks.clear(); total = 0 }
        rec = r
        recording = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(4096)
            while (recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(buf.copyOf(n))
                        total += n
                        if (total >= MAX_SAMPLES) recording = false
                    }
                } else if (n < 0) {
                    recording = false
                }
            }
        }.also { it.start() }
        return true
    }

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        recording = false
        thread?.join(1000)
        thread = null
        rec?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        rec = null
        synchronized(chunks) {
            val out = FloatArray(total)
            var i = 0
            for (c in chunks) for (s in c) out[i++] = s / 32768f
            chunks.clear()
            total = 0
            return out
        }
    }
}
