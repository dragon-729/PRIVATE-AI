package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.privateai.settings.AppSettings
import com.privateai.settings.SettingsInfo
import com.privateai.settings.SettingsViewModel
import java.util.Locale

private fun mb(b: Long) = if (b >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", b / 1e9) else String.format(Locale.US, "%.0f MB", b / 1e6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    s: AppSettings,
    info: SettingsInfo,
    vm: SettingsViewModel,
    onFreeMemory: () -> Unit,
    onClearChats: () -> Unit,
    onClearNotes: () -> Unit,
    onClearDocs: () -> Unit,
    onDeleteModels: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.monitor() }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val gb = vm.totalRamGb
    val cores = Runtime.getRuntime().availableProcessors()

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Section("Appearance") {
                    Choice("Theme", listOf("system" to "System", "light" to "Light", "dark" to "Dark"), s.theme) { vm.setTheme(it) }
                }
            }
            item {
                Section("AI performance") {
                    Choice(
                        "Context length", listOf("2048" to "2K", "3072" to "3K", "4096" to "4K"), s.contextLength.toString(),
                        enabled = { it == "2048" || (it == "3072" && gb >= 3.5) || (it == "4096" && gb >= 5.0) },
                    ) { vm.setContext(it.toInt()) }
                    Hint("Longer context remembers more but uses more RAM and is slower. 3K needs about 4 GB of RAM, 4K about 6 GB. The model reloads when you change it.")
                    Choice(
                        "CPU threads (this phone has $cores cores)",
                        listOf("0" to "Auto", "2" to "2", "3" to "3", "4" to "4", "6" to "6"), s.threads.toString(),
                        enabled = { it == "0" || it.toInt() <= cores },
                    ) { vm.setThreads(it.toInt()) }
                    Choice("Reply length", listOf("256" to "Short", "512" to "Medium", "1024" to "Long"), s.maxReplyTokens.toString()) { vm.setReplyTokens(it.toInt()) }
                    Choice("Style", listOf("0.3" to "Precise", "0.7" to "Balanced", "1.0" to "Creative"), String.format(Locale.US, "%.1f", s.temperature)) { vm.setTemperature(it.toFloat()) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Thermal protection")
                            Text("Uses fewer CPU threads when the phone gets warm and refuses to run when it overheats.", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = s.thermalProtection, onCheckedChange = vm::setThermal)
                    }
                }
            }
            item {
                Section("Memory") {
                    Text(
                        when {
                            !info.modelSelected -> "No model selected."
                            info.inMemory -> "The model is in RAM."
                            else -> "The model is out of RAM to save memory. It reloads automatically when you use the AI."
                        },
                    )
                    Choice("Free the model's RAM after", listOf("0" to "Never", "5" to "5 min", "10" to "10 min", "30" to "30 min"), s.idleUnloadMin.toString()) { vm.setIdle(it.toInt()) }
                    OutlinedButton(onClick = onFreeMemory, enabled = info.inMemory && !info.busy) { Text("Free memory now") }
                }
            }
            item {
                Section("Privacy center") {
                    Text("Your chats, notes, PDFs and voice recordings are processed only on this phone. There is no account, no analytics and no ads.")
                    Text("Internet is used only to download the models you choose. Right now: ${if (info.online) "connected" else "offline"}. " +
                        "Airplane mode never affects chatting.", style = MaterialTheme.typography.bodySmall)
                    Text("Permissions used", style = MaterialTheme.typography.labelLarge)
                    Hint("• Internet: model downloads only\n• Notifications: download progress\n• Microphone: only while you press Mic; audio is kept in memory and never saved\n• Foreground service: keeps a model download running")
                    Hint("The “100% ON-DEVICE AI” badge appears only while a model is loaded on this phone.")
                }
            }
            item {
                Section("Storage") {
                    Text("Models: ${mb(info.modelsBytes)}")
                    Text("Chats, notes and PDF indexes: ${mb(info.dataBytes)}")
                    Text("Free space: ${mb(info.freeBytes)}")
                    FlowRowButtons {
                        OutlinedButton(onClick = { confirm = "Delete all chats?" to onClearChats }) { Text("Clear chat history") }
                        OutlinedButton(onClick = { confirm = "Delete all notes?" to onClearNotes }) { Text("Delete notes") }
                        OutlinedButton(onClick = { confirm = "Delete all PDFs and their search index?" to onClearDocs }) { Text("Delete PDFs") }
                        OutlinedButton(onClick = { confirm = "Delete all downloaded models (chat and voice)?" to onDeleteModels }, enabled = !info.busy) { Text("Delete models") }
                    }
                }
            }
            item {
                TextButton(onClick = onAbout) { Text("About Private AI") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    confirm?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.labelSmall)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Choice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: (String) -> Boolean = { true },
    onPick: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(text) }, enabled = enabled(value))
            }
        }
    }
}
