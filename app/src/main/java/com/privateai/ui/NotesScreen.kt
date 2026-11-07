package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.data.NoteEntity
import com.privateai.notes.NoteAction
import com.privateai.notes.NotesUi
import com.privateai.notes.NotesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

@Composable
fun NotesScreen(
    ui: NotesUi,
    vm: NotesViewModel,
    modelReady: Boolean,
    engineBusy: Boolean,
    onBack: () -> Unit,
) {
    BackHandler { if (ui.isEditing) vm.closeEditor() else onBack() }
    if (ui.isEditing) NoteEditor(ui, vm, modelReady, engineBusy) else NoteList(ui, vm, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteList(ui: NotesUi, vm: NotesViewModel, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTextFile(uri)
    }
    var toDelete by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Notes") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = {
                TextButton(onClick = {
                    val t = clipboard.getText()?.text
                    if (t.isNullOrBlank()) vm.say("Clipboard has no text.") else vm.createFromText(t)
                }) { Text("Paste") }
                TextButton(onClick = { picker.launch(arrayOf("text/*", "application/json", "application/xml")) }) { Text("Import") }
                TextButton(onClick = vm::newNote) { Text("New") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ui.message?.let {
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f))
                        TextButton(onClick = { vm.say(null) }) { Text("OK") }
                    }
                }
            }
            if (ui.notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No notes yet. Tap New, paste text, or import a .txt file.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.notes, key = { it.id }) { n ->
                        Column(Modifier.fillMaxWidth().clickable { vm.openNote(n) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(n.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(n.content.trim().replace('\n', ' ').take(120), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(dateFmt.format(Date(n.updatedAt)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { toDelete = n }) { Text("Delete") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    toDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete note?") },
            text = { Text("\"${n.displayTitle()}\" will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteNote(n.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(ui: NotesUi, vm: NotesViewModel, modelReady: Boolean, engineBusy: Boolean) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canRun = modelReady && !engineBusy

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Note") },
            navigationIcon = { TextButton(onClick = vm::closeEditor) { Text("Back") } },
            actions = {
                Box {
                    TextButton(onClick = { menu = true }) { Text("AI tools") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        NoteAction.values().forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                enabled = canRun,
                                onClick = {
                                    menu = false
                                    if (a == NoteAction.TRANSLATE) langDialog = true else vm.runAction(a)
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = ui.title, onValueChange = vm::onTitle,
                modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Title") },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ui.content, onValueChange = vm::onContent,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("Write or paste your note…") },
            )
            Text(
                when {
                    !modelReady -> "${ui.content.length} characters · Load a model in Models to use AI tools"
                    engineBusy && !ui.aiRunning -> "${ui.content.length} characters · AI is busy with another task"
                    else -> "${ui.content.length} characters · saved automatically"
                },
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp),
            )

            val showPanel = ui.aiRunning || ui.aiResult.isNotEmpty() || ui.aiError != null
            if (showPanel) {
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ui.aiLabel ?: "AI", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (!ui.aiRunning) TextButton(onClick = vm::clearAi) { Text("Close") }
                        }
                        if (ui.aiRunning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            ui.aiProgress?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        ui.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (ui.aiResult.isNotEmpty()) {
                            Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                SelectionContainer { Text(ui.aiResult) }
                            }
                        }
                        if (ui.aiRunning) {
                            Button(onClick = vm::stopAi) { Text("Stop") }
                        } else if (ui.aiResult.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(ui.aiResult)) }) { Text("Copy") }
                                TextButton(onClick = vm::appendResultToNote) { Text("Add to note") }
                                TextButton(onClick = vm::saveResultAsNewNote) { Text("New note") }
                                TextButton(onClick = { confirmReplace = true }) { Text("Replace") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (langDialog) {
        var lang by remember { mutableStateOf("English") }
        AlertDialog(
            onDismissRequest = { langDialog = false },
            title = { Text("Translate to") },
            text = {
                Column {
                    OutlinedTextField(value = lang, onValueChange = { lang = it }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("English", "Hindi", "Spanish", "French", "German", "Arabic").forEach { l ->
                            TextButton(onClick = { lang = l }) { Text(l) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langDialog = false; vm.runAction(NoteAction.TRANSLATE, lang) }) { Text("Translate") } },
            dismissButton = { TextButton(onClick = { langDialog = false }) { Text("Cancel") } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace note text?") },
            text = { Text("The note will be replaced by the AI result.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; vm.replaceNoteWithResult() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCurrent() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
