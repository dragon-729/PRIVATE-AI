package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateai.data.ChatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    chats: List<ChatEntity>,
    currentId: Long?,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteAll: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { if (chats.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all") } },
        )
    }) { pad ->
        if (chats.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet")
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(chats, key = { it.id }) { chat ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(chat.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            chat.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (chat.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(dateFmt.format(Date(chat.updatedAt)), style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(onClick = { renaming = chat }) { Text("Rename") }
                            TextButton(onClick = { deleting = chat }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    renaming?.let { chat ->
        var text by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename conversation") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(chat.id, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${chat.title}\" and all its messages will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(chat.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all conversations?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { onDeleteAll(); confirmAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}
