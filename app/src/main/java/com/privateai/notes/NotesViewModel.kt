package com.privateai.notes

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privateai.ai.ChatTurn
import com.privateai.ai.EngineHolder
import com.privateai.ai.GenerationConfig
import com.privateai.data.AppDatabase
import com.privateai.data.NoteEntity
import com.privateai.data.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class NotesUi(
    val notes: List<NoteEntity> = emptyList(),
    val isEditing: Boolean = false,
    val editingId: Long? = null,
    val title: String = "",
    val content: String = "",
    val aiRunning: Boolean = false,
    val aiLabel: String? = null,
    val aiProgress: String? = null,
    val aiResult: String = "",
    val aiError: String? = null,
    val message: String? = null,
)

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NoteRepository(AppDatabase.get(app))
    private val engine = EngineHolder.engine
    private val _ui = MutableStateFlow(NotesUi())
    val ui: StateFlow<NotesUi> = _ui

    private var saveJob: Job? = null
    private var aiJob: Job? = null
    private val saveMutex = Mutex()
    @Volatile private var stopRequested = false

    private companion object {
        const val MAX_IMPORT_BYTES = 2_000_000
        const val MAX_CHARS = 200_000
        const val CHUNK = 2000
        const val SYSTEM = "You are a careful assistant that works only with the text the user provides. " +
            "Do not invent facts. Follow the instruction exactly and output only the result."
    }

    init {
        viewModelScope.launch { repo.observeAll().collect { l -> _ui.update { it.copy(notes = l) } } }
    }

    fun say(msg: String?) = _ui.update { it.copy(message = msg) }

    // ---------- notes CRUD ----------
    fun newNote() = _ui.update {
        it.copy(isEditing = true, editingId = null, title = "", content = "", aiResult = "", aiError = null, aiLabel = null)
    }

    fun openNote(n: NoteEntity) = _ui.update {
        it.copy(isEditing = true, editingId = n.id, title = n.title, content = n.content, aiResult = "", aiError = null, aiLabel = null)
    }

    fun onTitle(t: String) { _ui.update { it.copy(title = t) }; scheduleSave() }
    fun onContent(c: String) { _ui.update { it.copy(content = c.take(MAX_CHARS)) }; scheduleSave() }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(700); persist() }
    }

    private suspend fun persist() = withContext(NonCancellable) {
        saveMutex.withLock {
            val s = _ui.value
            val id = s.editingId
            if (id == null) {
                if (s.title.isBlank() && s.content.isBlank()) return@withLock
                val newId = repo.insert(s.title.trim(), s.content)
                _ui.update { it.copy(editingId = newId) }
            } else {
                repo.update(id, s.title.trim(), s.content)
            }
        }
    }

    fun closeEditor() {
        saveJob?.cancel()
        stopAi()
        viewModelScope.launch {
            persist()
            _ui.update { it.copy(isEditing = false, editingId = null, title = "", content = "", aiResult = "", aiError = null, aiLabel = null) }
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            if (_ui.value.editingId == id) {
                saveJob?.cancel()
                stopAi()
                _ui.update { it.copy(isEditing = false, editingId = null, title = "", content = "", aiResult = "", aiError = null, aiLabel = null) }
            }
        }
    }

    fun deleteAll() {
        saveJob?.cancel()
        stopAi()
        viewModelScope.launch {
            repo.deleteAll()
            _ui.update { it.copy(isEditing = false, editingId = null, title = "", content = "", aiResult = "", aiError = null, aiLabel = null) }
        }
    }

    fun deleteCurrent() {
        val id = _ui.value.editingId
        if (id != null) deleteNote(id) else closeEditor()
    }

    fun createFromText(text: String, title: String = "") {
        viewModelScope.launch {
            val id = repo.insert(title, text.take(MAX_CHARS))
            _ui.update {
                it.copy(isEditing = true, editingId = id, title = title, content = text.take(MAX_CHARS),
                    aiResult = "", aiError = null, aiLabel = null)
            }
        }
    }

    fun importTextFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, text) = withContext(Dispatchers.IO) { readText(uri) }
                createFromText(text, name)
            } catch (t: Throwable) {
                say("Import failed: ${t.message}")
            }
        }
    }

    private fun readText(uri: Uri): Pair<String, String> {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "Imported note"
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) throw IOException("File is larger than 2 MB")
            }
            out.toByteArray()
        }
        if (bytes.take(4096).any { it == 0.toByte() }) throw IOException("This does not look like a text file")
        return name.substringBeforeLast('.') to String(bytes, Charsets.UTF_8)
    }

    // ---------- AI tools ----------
    fun runAction(action: NoteAction, lang: String? = null) {
        val text = _ui.value.content.trim()
        if (text.isEmpty()) { _ui.update { it.copy(aiError = "Write or paste some text first.", aiLabel = action.label) }; return }
        if (!engine.isLoaded) { _ui.update { it.copy(aiError = "Load a model first (Models screen).", aiLabel = action.label) }; return }
        if (!EngineHolder.tryAcquire()) { _ui.update { it.copy(aiError = "The AI is busy with another task.", aiLabel = action.label) }; return }

        stopRequested = false
        val label = if (action == NoteAction.TRANSLATE) "Translate → ${lang?.ifBlank { null } ?: "English"}" else action.label
        _ui.update { it.copy(aiRunning = true, aiLabel = label, aiProgress = null, aiResult = "", aiError = null) }

        aiJob = viewModelScope.launch {
            try {
                val instruction = action.instruction(lang)
                val chunks = TextChunker.split(text, CHUNK)
                if (!action.mapReduce) {
                    for ((i, chunk) in chunks.withIndex()) {
                        if (stopRequested) break
                        if (chunks.size > 1) progress("Part ${i + 1} of ${chunks.size}")
                        if (i > 0) append("\n\n")
                        runPrompt(instruction, chunk) { append(it) }
                    }
                } else if (chunks.size == 1) {
                    runPrompt(instruction, chunks[0]) { append(it) }
                } else {
                    val partials = mutableListOf<String>()
                    for ((i, chunk) in chunks.withIndex()) {
                        if (stopRequested) break
                        progress("Reading part ${i + 1} of ${chunks.size}")
                        partials += runPrompt(instruction, chunk)
                    }
                    var combined = partials.joinToString("\n\n")
                    var round = 0
                    while (!stopRequested && combined.length > CHUNK && round < 3) {
                        val parts = TextChunker.split(combined, CHUNK)
                        val condensed = mutableListOf<String>()
                        for ((i, p) in parts.withIndex()) {
                            if (stopRequested) break
                            progress("Condensing ${i + 1} of ${parts.size}")
                            condensed += runPrompt("Condense the following text, keeping every important fact. Output only the condensed text.", p)
                        }
                        combined = condensed.joinToString("\n\n")
                        round++
                    }
                    if (!stopRequested) {
                        progress("Writing the final result")
                        val merged = instruction + " The text below combines results from several parts of one longer note; merge them into a single result without repeating yourself."
                        runPrompt(merged, combined.take(CHUNK * 2)) { append(it) }
                    }
                }
                if (!stopRequested && _ui.value.aiResult.isBlank()) {
                    _ui.update { it.copy(aiError = "The model returned no text. Try again.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.update { it.copy(aiError = t.message ?: "AI action failed") }
            } finally {
                withContext(NonCancellable) {
                    _ui.update { it.copy(aiRunning = false, aiProgress = null) }
                    EngineHolder.release()
                }
            }
        }
    }

    private fun progress(s: String) = _ui.update { it.copy(aiProgress = s) }
    private fun append(piece: String) = _ui.update { it.copy(aiResult = it.aiResult + piece) }

    private suspend fun runPrompt(instruction: String, text: String, onPiece: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        val turns = listOf(
            ChatTurn("system", SYSTEM),
            ChatTurn("user", "$instruction\n\n---\n$text\n---"),
        )
        engine.generate(turns, GenerationConfig(maxTokens = 700, temperature = 0.3f)).collect {
            sb.append(it)
            onPiece(it)
        }
        return sb.toString().trim()
    }

    fun stopAi() {
        if (_ui.value.aiRunning) { stopRequested = true; engine.stop() }
    }

    fun clearAi() = _ui.update { it.copy(aiResult = "", aiError = null, aiLabel = null) }

    // ---------- use the result ----------
    fun appendResultToNote() {
        val s = _ui.value
        if (s.aiResult.isBlank()) return
        onContent(s.content.trimEnd() + "\n\n— ${s.aiLabel ?: "AI"} —\n" + s.aiResult.trim())
        clearAi()
    }

    fun replaceNoteWithResult() {
        val s = _ui.value
        if (s.aiResult.isBlank()) return
        onContent(s.aiResult.trim())
        clearAi()
    }

    fun saveResultAsNewNote() {
        val s = _ui.value
        if (s.aiResult.isBlank()) return
        viewModelScope.launch {
            repo.insert("${s.aiLabel ?: "AI"}: ${s.title.ifBlank { "note" }}".take(60), s.aiResult.trim())
            say("Saved as a new note.")
            clearAi()
        }
    }
}
