package com.privateai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privateai.ai.ChatTurn
import com.privateai.ai.EngineHolder
import com.privateai.ai.GenerationConfig
import com.privateai.settings.Settings
import com.privateai.ai.LlmEngine
import com.privateai.ai.ModelConfiguration
import com.privateai.data.AppDatabase
import com.privateai.data.ChatEntity
import com.privateai.data.ChatRepository
import com.privateai.data.MessageEntity
import com.privateai.data.PersonaEntity
import com.privateai.data.Personas
import com.privateai.data.model.Compat
import com.privateai.data.model.ModelCatalog
import com.privateai.data.model.ModelManager
import com.privateai.data.model.ModelsUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class UiState(
    val chats: List<ChatEntity> = emptyList(),
    val currentChatId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streamingId: Long? = null,
    val streamingText: String = "",
    val modelName: String? = null,
    val busyLoading: Boolean = false,
    val generating: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val engine: LlmEngine = EngineHolder.engine
    private val repo = ChatRepository(AppDatabase.get(app))
    private val models = ModelManager(app)
    private val personaDao = AppDatabase.get(app).personaDao()
    private val prefs = app.getSharedPreferences("privateai", 0)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val chatId = MutableStateFlow<Long?>(null)
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    private val tick = MutableStateFlow(0)
    private val activeFile = MutableStateFlow<String?>(null)
    private val whisperFile = MutableStateFlow<String?>(prefs.getString("whisper_file", null))
    val modelsUi: StateFlow<ModelsUi> =
        combine(models.workInfos(), tick, activeFile, whisperFile) { infos, _, act, wh ->
            ModelsUi(models.buildItems(infos, act), models.totalRam(), models.freeStorage(), models.whisperItems(infos, wh))
        }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUi())

    // ---------- personas ----------
    val personas: StateFlow<List<PersonaEntity>> =
        personaDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedPersonaId = MutableStateFlow(prefs.getLong("persona_id", -1L))

    fun selectPersona(id: Long) {
        prefs.edit().putLong("persona_id", id).apply()
        selectedPersonaId.value = id
    }

    fun savePersona(id: Long?, name: String, prompt: String) {
        viewModelScope.launch {
            val n = name.trim().take(40).ifEmpty { "My persona" }
            val p = prompt.trim().take(Personas.MAX_PROMPT)
            if (id == null) {
                val newId = personaDao.insert(PersonaEntity(name = n, systemPrompt = p, builtIn = false, createdAt = System.currentTimeMillis()))
                selectPersona(newId)
            } else {
                personaDao.update(id, n, p)
            }
        }
    }

    fun deletePersona(id: Long) {
        viewModelScope.launch {
            personaDao.delete(id)
            if (selectedPersonaId.value == id) personaDao.byName(Personas.DEFAULT_NAME)?.let { selectPersona(it.id) }
        }
    }

    init {
        viewModelScope.launch {
            if (personaDao.count() == 0) {
                Personas.defaults.forEach {
                    personaDao.insert(PersonaEntity(name = it.first, systemPrompt = it.second, builtIn = true, createdAt = System.currentTimeMillis()))
                }
            }
            if (personaDao.get(selectedPersonaId.value) == null) {
                personaDao.byName(Personas.DEFAULT_NAME)?.let { selectPersona(it.id) }
            }
        }
        viewModelScope.launch { repo.deleteEmptyAssistant() } // leftovers from a killed generation
        viewModelScope.launch {
            repo.observeChats().collect { list -> _state.update { it.copy(chats = list) } }
        }
        viewModelScope.launch {
            chatId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeMessages(id) }
                .collect { list -> _state.update { it.copy(messages = list) } }
        }
        prefs.getString("model_path", null)?.let { p -> if (File(p).exists()) loadModel(p) }
        // Context length / thread changes in Settings reload the selected model (when the AI is idle).
        viewModelScope.launch {
            Settings.flow.filter { it.loaded }
                .map { it.contextLength to Settings.effectiveThreads(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    val path = prefs.getString("model_path", null)
                    if (path != null && File(path).exists() && engine.isLoaded && !EngineHolder.busy.value) loadModel(path)
                }
        }
    }

    // ---------- chats ----------
    private fun setChat(id: Long?) {
        chatId.value = id
        _state.update { it.copy(currentChatId = id, error = null) }
    }

    fun newChat() { stop(); setChat(null) }
    fun openChat(id: Long) { stop(); setChat(id) }
    fun renameChat(id: Long, title: String) { viewModelScope.launch { repo.rename(id, title) } }

    fun deleteChat(id: Long) {
        if (chatId.value == id) { stop(); setChat(null) }
        viewModelScope.launch { repo.deleteChat(id) }
    }

    fun deleteAllChats() {
        stop(); setChat(null)
        viewModelScope.launch { repo.deleteAll() }
    }

    // ---------- sending ----------
    fun send(text: String) {
        if (text.isBlank()) return
        launchGeneration {
            val id = chatId.value ?: repo.newChat(titleFrom(text)).also { setChat(it) }
            repo.addMessage(id, "user", text.trim())
            id
        }
    }

    /** Edit an earlier user prompt: everything after it is discarded and the answer is regenerated. */
    fun editAndResend(messageId: Long, newText: String) {
        val id = chatId.value ?: return
        if (newText.isBlank()) return
        launchGeneration {
            repo.deleteFrom(id, messageId)
            repo.addMessage(id, "user", newText.trim())
            id
        }
    }

    fun regenerate() {
        val id = chatId.value ?: return
        val last = _state.value.messages.lastOrNull() ?: return
        if (last.role != "assistant") return
        launchGeneration { repo.deleteMessage(last.id); id }
    }

    /** Only stops chat generation (notes have their own Stop). */
    fun stop() { if (_state.value.generating) engine.stop() }

    private fun launchGeneration(prepare: suspend () -> Long) {
        if (!engine.isLoaded) { _state.update { it.copy(error = "Load a model first (Models).") }; return }
        if (!EngineHolder.tryAcquire()) { _state.update { it.copy(error = "The AI is busy with another task.") }; return }
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            var assistantId: Long? = null
            val sb = StringBuilder()
            try {
                val id = prepare()
                val history = repo.messages(id)
                val system = personaDao.get(selectedPersonaId.value)?.systemPrompt
                    ?: "You are Private AI, a helpful assistant running fully on the user's phone."
                assistantId = repo.addMessage(id, "assistant", "")
                _state.update { it.copy(streamingId = assistantId, streamingText = "") }
                engine.generate(
                    buildTurns(history, system),
                    GenerationConfig(maxTokens = Settings.value.maxReplyTokens, temperature = Settings.value.temperature),
                ).collect { piece ->
                    sb.append(piece)
                    _state.update { it.copy(streamingText = sb.toString()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Generation failed") }
            } finally {
                withContext(NonCancellable) {
                    assistantId?.let { if (sb.isEmpty()) repo.deleteMessage(it) else repo.setText(it, sb.toString()) }
                    _state.update { it.copy(generating = false, streamingId = null, streamingText = "") }
                    EngineHolder.release()
                }
            }
        }
    }

    /** Keep the newest messages that fit a conservative character budget for a 2K-token context. */
    private fun buildTurns(all: List<MessageEntity>, system: String): List<ChatTurn> {
        val st = Settings.value
        // ~2.5 characters per token is a safe average; leave room for the reply, the persona and chat-template overhead
        val budget = (((st.contextLength - st.maxReplyTokens) * 2.5).toInt() - system.length - 200).coerceAtLeast(800)
        var used = 0
        val kept = mutableListOf<MessageEntity>()
        for (m in all.asReversed()) {
            if (kept.isNotEmpty() && used + m.text.length > budget) break
            used += m.text.length
            kept.add(0, m)
        }
        while (kept.isNotEmpty() && kept.first().role != "user") kept.removeAt(0)
        return listOf(ChatTurn("system", system)) +
            kept.map { ChatTurn(it.role, it.text) }
    }

    private fun titleFrom(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(40).ifEmpty { "New chat" }

    // ---------- model manager ----------
    private fun say(msg: String?) = _state.update { it.copy(notice = msg) }
    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    private fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1_000_000_000.0)

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busyLoading = true, error = null) }
            try {
                val dest = models.importFrom(uri)
                tick.update { it + 1 }
                _state.update { it.copy(busyLoading = false) }
                loadInstalled(dest.name)
            } catch (t: Throwable) {
                _state.update { it.copy(busyLoading = false, notice = "Import failed: ${t.message}") }
            }
        }
    }

    fun download(id: String) {
        val c = ModelCatalog.everything.firstOrNull { it.id == id } ?: return
        val part = models.partFile(c.fileName)
        val have = if (part.exists()) part.length() else 0L
        val need = (c.approxBytes - have).coerceAtLeast(0) + 100_000_000L
        if (models.freeStorage() < need) {
            say("Not enough storage: about ${gb(need)} needed, ${gb(models.freeStorage())} free.")
            return
        }
        say(null)
        models.start(c)
    }

    fun pauseDownload(id: String) { ModelCatalog.everything.firstOrNull { it.id == id }?.let(models::pause) }

    fun cancelDownload(id: String) {
        ModelCatalog.everything.firstOrNull { it.id == id }?.let(models::cancel)
        tick.update { it + 1 }
    }

    fun loadInstalled(fileName: String) {
        val f = models.file(fileName)
        if (!f.exists()) { say("Model file not found."); return }
        if (EngineHolder.busy.value) { say("The AI is busy. Wait or press Stop, then switch models."); return }
        val min = ModelCatalog.all.firstOrNull { it.fileName == fileName }?.minRamGb
        if (models.compat(f.length(), min) == Compat.TOO_LARGE) {
            say("This model is too large for this phone's RAM, so it was not loaded.")
            return
        }
        say(null)
        loadModel(f.absolutePath)
    }

    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            if (whisperFile.value == fileName) {
                prefs.edit().remove("whisper_file").apply()
                whisperFile.value = null
            }
            if (activeFile.value == fileName) {
                engine.stop()
                engine.unload()
                prefs.edit().remove("model_path").apply()
                activeFile.value = null
                _state.update { it.copy(modelName = null) }
            }
            withContext(Dispatchers.IO) { models.delete(fileName) }
            tick.update { it + 1 }
        }
    }

    fun useWhisper(fileName: String) {
        if (!models.file(fileName).exists()) { say("Voice model file not found."); return }
        prefs.edit().putString("whisper_file", fileName).apply()
        whisperFile.value = fileName
        say("Voice model selected: $fileName")
    }

    fun verifyModel(fileName: String) {
        viewModelScope.launch {
            say("Verifying… this can take a few seconds.")
            say("$fileName: ${models.verify(fileName)}")
        }
    }

    private fun loadModel(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyLoading = true, error = null) }
            try {
                Settings.awaitLoaded()
                val st = Settings.value
                engine.load(ModelConfiguration(path = path, contextLength = st.contextLength, threads = Settings.effectiveThreads(st)))
                prefs.edit().putString("model_path", path).apply()
                activeFile.value = File(path).name
                _state.update { it.copy(busyLoading = false, modelName = File(path).name) }
            } catch (t: Throwable) {
                prefs.edit().remove("model_path").apply()
                activeFile.value = null
                _state.update { it.copy(busyLoading = false, modelName = null, error = t.message) }
            }
        }
    }

    fun selectPersonaByName(name: String) {
        viewModelScope.launch { personaDao.byName(name)?.let { selectPersona(it.id) } }
    }

    /** Frees the model's RAM now; it comes back automatically the next time the AI is used. */
    fun freeMemoryNow() {
        viewModelScope.launch {
            if (EngineHolder.busy.value) say("The AI is busy right now.") else engine.releaseMemory()
        }
    }

    fun deleteAllModels() {
        viewModelScope.launch {
            if (EngineHolder.busy.value) { say("Wait for the AI to finish first."); return@launch }
            engine.unload()
            prefs.edit().remove("model_path").remove("whisper_file").apply()
            activeFile.value = null
            whisperFile.value = null
            _state.update { it.copy(modelName = null) }
            withContext(Dispatchers.IO) { models.deleteAll() }
            tick.update { it + 1 }
            say("All downloaded models were deleted.")
        }
    }

    override fun onCleared() { engine.stop() }
}
