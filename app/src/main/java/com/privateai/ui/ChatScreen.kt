package com.privateai.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.privateai.voice.VoiceUi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.UiState
import com.privateai.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onEdit: (Long, String) -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onModels: () -> Unit,
    onNotes: () -> Unit,
    onPdf: () -> Unit,
    onHome: () -> Unit,
    onPersonas: () -> Unit,
    onBenchmark: () -> Unit,
    personaName: String,
    voice: VoiceUi,
    onMic: () -> Unit,
    onVoiceConsumed: () -> Unit,
    onHistory: () -> Unit,
    onAbout: () -> Unit,
) {
    BackHandler(onBack = onHome)
    var input by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.messages.size, state.streamingText.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    // switching chats cancels any edit in progress
    LaunchedEffect(state.currentChatId) { editingId = null; input = "" }

    // voice input: finished transcription is inserted into the message box
    val ctx = LocalContext.current
    var micDenied by remember { mutableStateOf(false) }
    LaunchedEffect(voice.text) {
        val t = voice.text
        if (!t.isNullOrBlank()) {
            input = if (input.isBlank()) t else input.trimEnd() + " " + t
            onVoiceConsumed()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) onMic()
    }
    fun micClick() {
        if (voice.recording || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micDenied = false
            onMic()
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Private AI") },
                navigationIcon = { TextButton(onClick = onHome) { Text("Home") } },
                actions = {
                    TextButton(onClick = onNewChat) { Text("New") }
                    TextButton(onClick = onHistory) { Text("History") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.modelName != null) "100% ON-DEVICE AI · ${state.modelName}" else "No model loaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                TextButton(onClick = onPersonas) { Text("Persona: $personaName", maxLines = 1) }
                TextButton(onClick = onModels) { Text("Models") }
                TextButton(onClick = onNotes) { Text("Notes") }
                TextButton(onClick = onPdf) { Text("PDF") }
                TextButton(onClick = onBenchmark) { Text("Performance") }
            }
            if (state.busyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            voice.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (micDenied) {
                Text("Microphone permission is needed for voice input. Typing still works.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }

            if (state.modelName == null && !state.busyLoading) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Your AI. Your Device. Your Privacy.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Download or import a model to start chatting. After that, everything works offline.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onModels) { Text("Open Model Manager") }
                }
            } else if (state.messages.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Everything you type stays on this phone.")
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
                    items(state.messages, key = { it.id }) { m ->
                        val streaming = m.id == state.streamingId
                        MessageItem(
                            m = m,
                            text = if (streaming) state.streamingText else m.text,
                            streaming = streaming,
                            isLast = m.id == state.messages.last().id,
                            busy = state.generating,
                            onCopy = { clipboard.setText(AnnotatedString(m.text.ifEmpty { state.streamingText })) },
                            onEdit = { editingId = m.id; input = m.text },
                            onRegenerate = onRegenerate,
                        )
                    }
                }
            }

            if (editingId != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing message", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingId = null; input = "" }) { Text("Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    enabled = state.modelName != null,
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { micClick() },
                    enabled = state.modelName != null && !state.generating && !voice.transcribing,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text(
                        when {
                            voice.recording -> "■ ${voice.seconds}s"
                            voice.transcribing -> "…"
                            else -> "Mic"
                        }
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (state.generating) {
                    Button(onClick = onStop) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            val id = editingId
                            if (id != null) onEdit(id, input) else onSend(input)
                            editingId = null; input = ""
                        },
                        enabled = state.modelName != null && input.isNotBlank(),
                    ) { Text(if (editingId != null) "Resend" else "Send") }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun MessageItem(
    m: MessageEntity,
    text: String,
    streaming: Boolean,
    isLast: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val mine = m.role == "user"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                if (mine || text.isEmpty()) {
                    Text(if (text.isEmpty() && streaming) "Thinking…" else text, modifier = Modifier.padding(12.dp))
                } else {
                    MarkdownText(text, Modifier.padding(12.dp))   // headings, lists, bold, code blocks with highlighting
                }
            }
        }
        if (!streaming) {
            val small = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFmt.format(Date(m.time)), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp))
                TextButton(onClick = onCopy, contentPadding = small, modifier = Modifier.height(28.dp)) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
                if (mine) {
                    TextButton(onClick = onEdit, enabled = !busy, contentPadding = small, modifier = Modifier.height(28.dp)) {
                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (isLast) {
                    TextButton(onClick = onRegenerate, enabled = !busy, contentPadding = small, modifier = Modifier.height(28.dp)) {
                        Text("Regenerate", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
