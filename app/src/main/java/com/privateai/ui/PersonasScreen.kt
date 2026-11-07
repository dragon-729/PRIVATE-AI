package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.data.PersonaEntity
import com.privateai.data.Personas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    personas: List<PersonaEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onSave: (Long?, String, String) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var editing by remember { mutableStateOf<PersonaEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<PersonaEntity?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Personas") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { TextButton(onClick = { creating = true }) { Text("New") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "A persona is the instruction the AI follows in every chat. Pick one before you chat; it applies to your next message.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(personas, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = p.id == selectedId, onClick = { onSelect(p.id) })
                        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name + if (p.builtIn) "" else "  ·  custom", style = MaterialTheme.typography.titleSmall)
                            Text(p.systemPrompt, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row {
                                TextButton(onClick = { editing = p }) { Text("Edit") }
                                if (!p.builtIn) TextButton(onClick = { toDelete = p }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (creating || editing != null) {
        val p = editing
        var name by remember(p?.id, creating) { mutableStateOf(p?.name ?: "") }
        var prompt by remember(p?.id, creating) { mutableStateOf(p?.systemPrompt ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null; creating = false },
            title = { Text(if (p == null) "New persona" else "Edit persona") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        prompt, { prompt = it.take(Personas.MAX_PROMPT) }, label = { Text("System prompt") },
                        minLines = 4, maxLines = 8,
                    )
                    Text("${prompt.length}/${Personas.MAX_PROMPT} characters. Keep it short: it uses part of the AI's memory window.",
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(enabled = prompt.isNotBlank(), onClick = { onSave(p?.id, name, prompt); editing = null; creating = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null; creating = false }) { Text("Cancel") } },
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete persona?") },
            text = { Text("“${p.name}” will be removed.") },
            confirmButton = { TextButton(onClick = { onDelete(p.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
