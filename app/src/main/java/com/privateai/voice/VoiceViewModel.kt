package com.privateai.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privateai.ai.EngineHolder
import com.privateai.data.model.ModelCatalog
import com.privateai.data.model.ModelManager
import com.privateai.whisper.WhisperBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VoiceUi(
    val recording: Boolean = false,
    val seconds: Int = 0,
    val transcribing: Boolean = false,
    val error: String? = null,
    val text: String? = null,   // finished transcription, waiting to be inserted into the chat box
)

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("privateai", 0)
    private val models = ModelManager(app)
    private val recorder = VoiceRecorder()
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui
    private var ticker: Job? = null

    /** The voice model the user picked, or the first installed one. */
    private fun whisperFile(): File? {
        val chosen = prefs.getString("whisper_file", null)?.let { models.file(it) }?.takeIf { it.exists() }
        return chosen ?: ModelCatalog.whisper.map { models.file(it.fileName) }.firstOrNull { it.exists() }
    }

    private fun fail(msg: String) = _ui.update { VoiceUi(error = msg) }

    fun toggle() { if (_ui.value.recording) finish() else begin() }
    fun consumeText() = _ui.update { it.copy(text = null) }
    fun clearError() = _ui.update { it.copy(error = null) }

    private fun begin() {
        if (_ui.value.transcribing) return
        if (EngineHolder.busy.value) { fail("Wait for the AI to finish, then use the microphone."); return }
        if (whisperFile() == null) { fail("Download a voice model first: Models → Voice models."); return }
        if (!recorder.start()) { fail("Could not start the microphone."); return }
        _ui.value = VoiceUi(recording = true)
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val sec = recorder.seconds.toInt()
                _ui.update { it.copy(seconds = sec) }
                if (!recorder.isRecording) { finish(); break }   // one-minute cap reached
            }
        }
    }

    private fun finish() {
        ticker?.cancel()
        val audio = recorder.stop()
        _ui.update { it.copy(recording = false, transcribing = true) }
        viewModelScope.launch {
            val file = whisperFile()
            if (file == null) { fail("Voice model not found."); return@launch }
            if (!EngineHolder.tryAcquire()) { fail("The AI is busy. Try again in a moment."); return@launch }
            try {
                val text = withContext(Dispatchers.Default) { transcribe(file, audio) }
                if (text.isBlank()) fail("No speech detected. Try again, closer to the microphone.")
                else _ui.value = VoiceUi(text = text)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                fail("Transcription failed: ${t.message}")
            } finally {
                EngineHolder.release()
            }
        }
    }

    /** Loads the voice model only for this one transcription, so it never competes with the chat model for RAM. */
    private fun transcribe(file: File, audio: FloatArray): String {
        val minLen = RATE * 12 / 10                       // Whisper needs at least ~1 second
        val pcm = if (audio.size < minLen) audio.copyOf(minLen) else audio
        val h = WhisperBridge.nativeInit(file.absolutePath)
        if (h == 0L) throw IllegalStateException("Could not load the voice model (the file may be corrupted).")
        try {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
            val lang = if (file.name.contains(".en")) "en" else "auto"
            val bytes = WhisperBridge.nativeTranscribe(h, pcm, threads, lang)
                ?: throw IllegalStateException("The speech engine returned an error.")
            return String(bytes, Charsets.UTF_8)
                .replace(Regex("\\[[A-Za-z_ ]+\\]"), " ")   // tags like [BLANK_AUDIO]
                .replace(Regex("\\s+"), " ")
                .trim()
        } finally {
            WhisperBridge.nativeFree(h)
        }
    }

    override fun onCleared() { ticker?.cancel(); recorder.stop() }

    private companion object { const val RATE = 16_000 }
}
