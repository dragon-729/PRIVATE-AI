package com.privateai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.privateai.benchmark.BenchUi
import com.privateai.benchmark.BenchmarkViewModel
import com.privateai.benchmark.DeviceMetrics
import com.privateai.data.BenchmarkEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
private fun gb(b: Long) = String.format(Locale.US, "%.2f GB", b / 1e9)

private fun thermalName(s: Int) = when (s) {
    0 -> "Normal"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"
    else -> "Unknown"
}

private fun tempText(c: Double) = if (c < 0) "n/a" else "${f1(c)} °C"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(ui: BenchUi, vm: BenchmarkViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.monitor() }   // live numbers only while this screen is open

    Scaffold(topBar = {
        TopAppBar(title = { Text("Performance") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { DeviceCard(ui.metrics) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Model", style = MaterialTheme.typography.titleSmall)
                        if (ui.modelName == null) Text("No model loaded. Open Models and load one.")
                        else {
                            Text(ui.modelName)
                            Text("Size ${gb(ui.modelBytes)} · quantization ${ui.quant.ifEmpty { "unknown" }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Run a benchmark", style = MaterialTheme.typography.titleSmall)
                        ChipRow("Prompt", listOf("Short" to !ui.longPrompt, "Long" to ui.longPrompt), !ui.running) { vm.setPrompt(it == "Long") }
                        ChipRow("Tokens", listOf(64, 128, 256).map { "$it" to (ui.maxTokens == it) }, !ui.running) { vm.setTokens(it.toInt()) }
                        ChipRow("Runs", listOf(1, 2, 3).map { "$it" to (ui.runs == it) }, !ui.running) { vm.setRuns(it.toInt()) }
                        Text("Stops automatically if the phone gets too hot. Results below are measured on this phone with the loaded model.",
                            style = MaterialTheme.typography.labelSmall)
                        if (ui.running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            ui.progress?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                            Button(onClick = vm::stop) { Text("Stop") }
                        } else {
                            Button(onClick = vm::run, enabled = ui.modelName != null) { Text("Start benchmark") }
                        }
                        ui.error?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                TextButton(onClick = vm::dismissError) { Text("OK") }
                            }
                        }
                    }
                }
            }
            ui.latest?.let { r ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Result", style = MaterialTheme.typography.titleSmall)
                            ResultLines(r)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (ui.history.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("Clear") }
                }
            }
            if (ui.history.isEmpty()) item { Text("No benchmarks yet.", style = MaterialTheme.typography.bodySmall) }
            items(ui.history, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${dateFmt.format(Date(r.createdAt))} · ${r.model}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${f1(r.tokensPerSec)} tok/s · first token ${r.firstTokenMs} ms · ${r.promptKind} prompt, ${r.maxTokens} tokens, ${r.runs} run(s)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ResultLines(r: BenchmarkEntity) {
    val lines = listOf(
        "Model" to "${r.model} (${gb(r.modelBytes)}, ${r.quant})",
        "First-token latency" to "${r.firstTokenMs} ms (includes reading the prompt)",
        "Tokens per second" to "${f1(r.tokensPerSec)} (generation speed after the first token)",
        "Generation duration" to "${f1(r.durationMs / 1000.0)} s for ${r.tokens} tokens (average of ${r.runs} run${if (r.runs > 1) "s" else ""})",
        "Free RAM at the end" to "${r.availRamMb} MB",
        "App memory (estimate)" to if (r.appMemMb >= 0) "${r.appMemMb} MB — whole app incl. the model, not AI-only" else "n/a",
        "Battery temperature" to "${tempText(r.tempStartC)} → ${tempText(r.tempEndC)}",
        "Thermal status" to thermalName(r.thermal),
        "Battery" to "${r.batteryPct}%",
    )
    lines.forEach { (k, v) ->
        Column { Text(k, style = MaterialTheme.typography.labelSmall); Text(v, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun DeviceCard(m: DeviceMetrics?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("This phone, right now", style = MaterialTheme.typography.titleSmall)
            if (m == null) Text("Reading…")
            else {
                Text("Free RAM: ${m.ramAvailMb} MB of ${m.ramTotalMb} MB")
                Text("App memory (estimate): ${if (m.appMemMb >= 0) "${m.appMemMb} MB" else "n/a"}")
                Text("Battery: ${if (m.batteryPct >= 0) "${m.batteryPct}%" else "n/a"}${if (m.charging) " · charging" else ""}")
                Text("Battery temperature: ${m.tempC?.let { "${f1(it)} °C" } ?: "n/a"}")
                Text("Thermal status: ${thermalName(m.thermal)}")
                Text("Temperature is the battery sensor, used as a stand-in for the phone's temperature. Memory is for the whole app, not only the AI.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(label: String, options: List<Pair<String, Boolean>>, enabled: Boolean, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium)
        options.forEach { (text, selected) ->
            FilterChip(selected = selected, onClick = { onPick(text) }, label = { Text(text) }, enabled = enabled)
        }
    }
}
