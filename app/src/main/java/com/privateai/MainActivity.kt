package com.privateai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privateai.ai.EngineHolder
import com.privateai.benchmark.BenchmarkViewModel
import com.privateai.data.Personas
import com.privateai.notes.NotesViewModel
import com.privateai.rag.PdfViewModel
import com.privateai.settings.Settings
import com.privateai.settings.SettingsViewModel
import com.privateai.ui.AboutScreen
import com.privateai.ui.BenchmarkScreen
import com.privateai.ui.ChatScreen
import com.privateai.ui.HistoryScreen
import com.privateai.ui.HomeScreen
import com.privateai.ui.ModelsScreen
import com.privateai.ui.NotesScreen
import com.privateai.ui.PdfScreen
import com.privateai.ui.PersonasScreen
import com.privateai.ui.SettingsScreen
import com.privateai.voice.VoiceViewModel

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val notesVm: NotesViewModel by viewModels()
    private val pdfVm: PdfViewModel by viewModels()
    private val voiceVm: VoiceViewModel by viewModels()
    private val benchVm: BenchmarkViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by Settings.flow.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val colors = if (dark)
                darkColorScheme(primary = Color(0xFF38BDF8), background = Color(0xFF0B1020), surface = Color(0xFF121A33))
            else lightColorScheme(primary = Color(0xFF0369A1))

            MaterialTheme(colorScheme = colors) {
                var screen by rememberSaveable { mutableStateOf("home") }
                val home = { screen = "home" }
                val state by vm.state.collectAsStateWithLifecycle()
                val personas by vm.personas.collectAsStateWithLifecycle()
                val selectedPersona by vm.selectedPersonaId.collectAsStateWithLifecycle()
                val voice by voiceVm.ui.collectAsStateWithLifecycle()
                val personaName = personas.firstOrNull { it.id == selectedPersona }?.name ?: Personas.DEFAULT_NAME

                when (screen) {
                    "home" -> HomeScreen(
                        state = state,
                        personaName = personaName,
                        onNewChat = { vm.newChat(); screen = "chat" },
                        onOpenChat = { vm.openChat(it); screen = "chat" },
                        onQuick = { id ->
                            when (id) {
                                "ask" -> { vm.selectPersonaByName(Personas.DEFAULT_NAME); vm.newChat(); screen = "chat" }
                                "code" -> { vm.selectPersonaByName("Python Coding Assistant"); vm.newChat(); screen = "chat" }
                                "translate" -> { vm.selectPersonaByName("Translator"); vm.newChat(); screen = "chat" }
                                "notes" -> screen = "notes"
                                "pdf" -> screen = "pdf"
                            }
                        },
                        onNav = { screen = it },
                    )
                    "settings" -> {
                        val info by settingsVm.info.collectAsStateWithLifecycle()
                        SettingsScreen(
                            s = settings,
                            info = info,
                            vm = settingsVm,
                            onFreeMemory = vm::freeMemoryNow,
                            onClearChats = vm::deleteAllChats,
                            onClearNotes = notesVm::deleteAll,
                            onClearDocs = pdfVm::deleteAllDocs,
                            onDeleteModels = vm::deleteAllModels,
                            onAbout = { screen = "about" },
                            onBack = home,
                        )
                    }
                    "about" -> AboutScreen(onBack = home)
                    "personas" -> PersonasScreen(
                        personas = personas,
                        selectedId = selectedPersona,
                        onSelect = vm::selectPersona,
                        onSave = vm::savePersona,
                        onDelete = vm::deletePersona,
                        onBack = home,
                    )
                    "benchmark" -> {
                        val benchUi by benchVm.ui.collectAsStateWithLifecycle()
                        BenchmarkScreen(ui = benchUi, vm = benchVm, onBack = home)
                    }
                    "pdf" -> {
                        val pdfUi by pdfVm.ui.collectAsStateWithLifecycle()
                        val busy by EngineHolder.busy.collectAsStateWithLifecycle()
                        PdfScreen(ui = pdfUi, vm = pdfVm, modelReady = state.modelName != null, engineBusy = busy, onBack = home)
                    }
                    "notes" -> {
                        val notesUi by notesVm.ui.collectAsStateWithLifecycle()
                        val busy by EngineHolder.busy.collectAsStateWithLifecycle()
                        NotesScreen(ui = notesUi, vm = notesVm, modelReady = state.modelName != null, engineBusy = busy, onBack = home)
                    }
                    "models" -> {
                        val modelsUi by vm.modelsUi.collectAsStateWithLifecycle()
                        ModelsScreen(
                            ui = modelsUi,
                            notice = state.notice ?: state.error,
                            busy = state.busyLoading,
                            onBack = home,
                            onImport = vm::importModel,
                            onLoad = vm::loadInstalled,
                            onUseWhisper = vm::useWhisper,
                            onDownload = vm::download,
                            onPause = vm::pauseDownload,
                            onCancel = vm::cancelDownload,
                            onDelete = vm::deleteModel,
                            onVerify = vm::verifyModel,
                            onDismiss = vm::dismissNotice,
                        )
                    }
                    "history" -> HistoryScreen(
                        chats = state.chats,
                        currentId = state.currentChatId,
                        onBack = home,
                        onOpen = { vm.openChat(it); screen = "chat" },
                        onRename = vm::renameChat,
                        onDelete = vm::deleteChat,
                        onDeleteAll = vm::deleteAllChats,
                    )
                    else -> ChatScreen(
                        state = state,
                        onSend = vm::send,
                        onEdit = vm::editAndResend,
                        onRegenerate = vm::regenerate,
                        onStop = vm::stop,
                        onNewChat = vm::newChat,
                        onModels = { screen = "models" },
                        onNotes = { screen = "notes" },
                        onPdf = { screen = "pdf" },
                        onHome = home,
                        onPersonas = { screen = "personas" },
                        onBenchmark = { screen = "benchmark" },
                        personaName = personaName,
                        voice = voice,
                        onMic = voiceVm::toggle,
                        onVoiceConsumed = voiceVm::consumeText,
                        onHistory = { screen = "history" },
                        onAbout = { screen = "about" },
                    )
                }
            }
        }
    }
}
