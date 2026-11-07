package com.privateai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.UiState
import com.privateai.util.isOnline
import kotlinx.coroutines.delay
import java.util.Calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    personaName: String,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onQuick: (String) -> Unit,
    onNav: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val online by produceState(false) {
        while (true) { value = isOnline(ctx); delay(3000) }
    }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }

    Scaffold { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Private AI", style = MaterialTheme.typography.headlineMedium)
                    Text("Your AI. Your Device. Your Privacy.", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(greeting, style = MaterialTheme.typography.titleMedium)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.modelName != null) {
                            Text("100% ON-DEVICE AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Model: ${state.modelName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text("No model loaded", style = MaterialTheme.typography.titleSmall)
                            Text("Download or import a model to start.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onNav("models") }) { Text("Open Model Manager") }
                        }
                        Text("Persona: $personaName", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (online) "Network: connected (chats never use it)" else "Offline: everything here works without internet",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item { Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) { Text("New chat") } }
            item {
                Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            }
            item {
                val actions = listOf(
                    "ask" to "Ask AI", "notes" to "Summarize Notes", "pdf" to "Chat with PDF",
                    "code" to "Coding Assistant", "translate" to "Translate Text",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (id, label) ->
                                Card(Modifier.weight(1f).clickable { onQuick(id) }) {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                                        Text(label, style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (state.chats.isNotEmpty()) {
                item { Text("Recent conversations", style = MaterialTheme.typography.titleMedium) }
                items(state.chats.take(4), key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }) {
                        Text(c.title, Modifier.padding(14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { TextButton(onClick = { onNav("history") }) { Text("All conversations") } }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onNav("models") }) { Text("Models") }
                    TextButton(onClick = { onNav("personas") }) { Text("Personas") }
                    TextButton(onClick = { onNav("benchmark") }) { Text("Performance") }
                    TextButton(onClick = { onNav("settings") }) { Text("Settings") }
                    TextButton(onClick = { onNav("about") }) { Text("About") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
