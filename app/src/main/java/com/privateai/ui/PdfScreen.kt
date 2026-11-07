package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.data.DocumentEntity
import com.privateai.rag.PdfUi
import com.privateai.rag.PdfViewModel

@Composable
fun PdfScreen(ui: PdfUi, vm: PdfViewModel, modelReady: Boolean, engineBusy: Boolean, onBack: () -> Unit) {
    BackHandler { if (ui.openDoc != null) vm.closeDoc() else onBack() }
    val doc = ui.openDoc
    if (doc != null) DocChat(ui, vm, doc, modelReady, engineBusy) else DocList(ui, vm, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocList(ui: PdfUi, vm: PdfViewModel, onBack: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPdf(uri)
    }
    var toDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Chat with PDF") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = {
                TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !ui.importing) { Text("Import PDF") }
            },
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Everything stays on this phone: text extraction, OCR, indexing and answers.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("OCR for scanned pages", Modifier.weight(1f))
                            Switch(checked = ui.ocrEnabled, onCheckedChange = vm::setOcr, enabled = !ui.importing)
                        }
                        Text("Works best with English documents. OCR reads Latin script and is slower.", style = MaterialTheme.typography.labelSmall)
                        if (ui.importing) {
                            if (ui.importProgress in 0f..1f) LinearProgressIndicator(progress = { ui.importProgress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(ui.importPhase, style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = vm::cancelImport) { Text("Cancel") }
                        }
                    }
                }
            }
            ui.message?.let {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f))
                            TextButton(onClick = { vm.say(null) }) { Text("OK") }
                        }
                    }
                }
            }
            if (ui.docs.isEmpty() && !ui.importing) {
                item { Text("No documents yet. Tap Import PDF.", modifier = Modifier.padding(16.dp)) }
            }
            items(ui.docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${d.pageCount} pages · ${d.chunkCount} sections" + if (d.ocrPages > 0) " · ${d.ocrPages} OCR pages" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (d.title.isNotBlank() || d.author.isNotBlank()) {
                            Text(listOf(d.title, d.author).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row {
                            Button(onClick = { vm.openDoc(d) }) { Text("Open") }
                            TextButton(onClick = { toDelete = d }) { Text("Delete") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete document?") },
            text = { Text("“${d.name}” and its search index will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteDoc(d.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocChat(ui: PdfUi, vm: PdfViewModel, doc: DocumentEntity, modelReady: Boolean, engineBusy: Boolean) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var pagesDialog by remember { mutableStateOf(false) }
    var findDialog by remember { mutableStateOf(false) }
    val canRun = modelReady && !engineBusy
    val lastLen = ui.msgs.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(ui.msgs.size, lastLen) {
        if (ui.msgs.isNotEmpty()) listState.scrollToItem(ui.msgs.lastIndex)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = vm::closeDoc) { Text("Back") } },
            actions = {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Tools") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Summarize document") }, enabled = canRun && !ui.running,
                            onClick = { menu = false; vm.summarizeDocument() })
                        DropdownMenuItem(text = { Text("Summarize pages…") }, enabled = canRun && !ui.running,
                            onClick = { menu = false; pagesDialog = true })
                        DropdownMenuItem(text = { Text("Extract important info") }, enabled = canRun && !ui.running,
                            onClick = { menu = false; vm.extractInfo() })
                        DropdownMenuItem(text = { Text("Find relevant sections…") }, enabled = !ui.running,
                            onClick = { menu = false; findDialog = true })
                    }
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Text(
                "${doc.pageCount} pages · ${doc.chunkCount} sections" + if (!modelReady) " · Load a model in Models to ask questions" else "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (ui.msgs.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask anything about this PDF", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Answers cite the pages they come from, like [Page 4]. If the PDF doesn't contain the answer, I'll say so.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
                    items(ui.msgs, key = { it.id }) { m ->
                        val mine = m.role == "user"
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.widthIn(max = 340.dp),
                            ) {
                                SelectionContainer {
                                    Text(if (m.text.isEmpty() && !mine) "Thinking…" else m.text, modifier = Modifier.padding(12.dp))
                                }
                            }
                            if (!mine && m.text.isNotEmpty() && !(ui.running && m.id == ui.msgs.last().id)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(m.text)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            ui.progress?.let {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Ask about this PDF") },
                    maxLines = 3, enabled = canRun,
                )
                Spacer(Modifier.width(8.dp))
                if (ui.running) Button(onClick = vm::stopAi) { Text("Stop") }
                else Button(onClick = { vm.ask(input); input = "" }, enabled = canRun && input.isNotBlank()) { Text("Ask") }
            }
        }
    }

    if (pagesDialog) {
        var from by remember { mutableStateOf("1") }
        var to by remember { mutableStateOf(doc.pageCount.toString()) }
        AlertDialog(
            onDismissRequest = { pagesDialog = false },
            title = { Text("Summarize pages") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, label = { Text("From") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, label = { Text("To") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pagesDialog = false
                    vm.summarizePages(from.toIntOrNull() ?: 0, to.toIntOrNull() ?: 0)
                }) { Text("Summarize") }
            },
            dismissButton = { TextButton(onClick = { pagesDialog = false }) { Text("Cancel") } },
        )
    }
    if (findDialog) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { findDialog = false },
            title = { Text("Find relevant sections") },
            text = { OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("What are you looking for?") }) },
            confirmButton = { TextButton(onClick = { findDialog = false; vm.findSections(q) }, enabled = q.isNotBlank()) { Text("Search") } },
            dismissButton = { TextButton(onClick = { findDialog = false }) { Text("Cancel") } },
        )
    }
}
