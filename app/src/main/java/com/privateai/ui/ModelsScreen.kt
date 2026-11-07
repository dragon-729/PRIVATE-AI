package com.privateai.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.privateai.data.model.Compat
import com.privateai.data.model.ModelItem
import com.privateai.data.model.ModelState
import com.privateai.data.model.ModelsUi
import java.util.Locale

private fun fmt(b: Long): String =
    if (b >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", b / 1e9)
    else String.format(Locale.US, "%.0f MB", b / 1e6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    ui: ModelsUi,
    notice: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onImport: (Uri) -> Unit,
    onLoad: (String) -> Unit,
    onUseWhisper: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf<ModelItem?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startDownload(id: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) // optional: only for the progress notification
        onDownload(id)
    }

    val installed = ui.items.filter { it.state is ModelState.Installed }
    val available = ui.items.filter { it.state !is ModelState.Installed }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Models") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("This phone", style = MaterialTheme.typography.titleSmall)
                        Text("RAM: ${fmt(ui.totalRamBytes)} · Free storage: ${fmt(ui.freeStorageBytes)}")
                        Text("RAM fit is an estimate, not a guarantee. Downloads need internet; chatting never does.",
                            style = MaterialTheme.typography.labelSmall)
                        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import GGUF file") }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (notice != null) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
                            Text(notice, Modifier.weight(1f).align(androidx.compose.ui.Alignment.CenterVertically))
                            TextButton(onClick = onDismiss) { Text("OK") }
                        }
                    }
                }
            }
            if (installed.isNotEmpty()) {
                item { Text("Installed", style = MaterialTheme.typography.titleMedium) }
                items(installed, key = { "i-" + it.key }) { m ->
                    ModelCard(m, onLoad, ::startDownload, onPause, onCancel, { confirmDelete = m }, onVerify)
                }
            }
            item { Text("Available to download", style = MaterialTheme.typography.titleMedium) }
            items(available, key = { "a-" + it.key }) { m ->
                ModelCard(m, onLoad, ::startDownload, onPause, onCancel, { confirmDelete = m }, onVerify)
            }
            item { Text("Voice models (Whisper)", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "For the Mic button in chat. Tiny is fastest. Base is more accurate and understands many languages, including Hindi. " +
                        "Choose an English-only model for English. Tap Use to pick one.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            items(ui.whisperItems, key = { "w-" + it.key }) { m ->
                ModelCard(m, onUseWhisper, ::startDownload, onPause, onCancel, { confirmDelete = m }, onVerify, loadLabel = "Use")
            }
            item {
                Text("Models keep their own licenses. Sizes marked ~ are approximate until downloaded.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model?") },
            text = { Text("${m.title} (${fmt(m.sizeBytes)}) will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(m.fileName); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelCard(
    m: ModelItem,
    onLoad: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: () -> Unit,
    onVerify: (String) -> Unit,
    loadLabel: String = "Load",
) {
    val id = m.catalogId
    val compatText = when (m.compat) {
        Compat.OK -> "Should run well on this phone"
        Compat.TIGHT -> "Tight on RAM — may be slow or close other apps"
        Compat.TOO_LARGE -> "Too large for this phone's RAM"
    }
    val compatColor = when (m.compat) {
        Compat.OK -> MaterialTheme.colorScheme.primary
        Compat.TIGHT -> MaterialTheme.colorScheme.tertiary
        Compat.TOO_LARGE -> MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium)
            Text("${m.quant} · ${if (m.sizeApprox) "~" else ""}${fmt(m.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(compatText, style = MaterialTheme.typography.labelMedium, color = compatColor)
            m.license?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(4.dp))

            when (val s = m.state) {
                is ModelState.Installed -> {
                    if (m.active) Text("● Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!m.active) Button(onClick = { onLoad(m.fileName) }, enabled = m.compat != Compat.TOO_LARGE) { Text(loadLabel) }
                        TextButton(onClick = { onVerify(m.fileName) }) { Text("Verify") }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
                is ModelState.NotInstalled -> {
                    Button(onClick = { id?.let(onDownload) }, enabled = m.compat != Compat.TOO_LARGE) { Text("Download") }
                }
                is ModelState.Queued -> {
                    Text("Waiting for network… (${fmt(s.done)} saved)", style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { id?.let(onPause) }) { Text("Pause") }
                          TextButton(onClick = { id?.let(onCancel) }) { Text("Cancel") } }
                }
                is ModelState.Downloading -> {
                    if (s.verifying) {
                        Text("Verifying file integrity…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        Text("${(frac * 100).toInt()}% · ${fmt(s.done)} / ${fmt(s.total)}", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { id?.let(onPause) }) { Text("Pause") }
                              TextButton(onClick = { id?.let(onCancel) }) { Text("Cancel") } }
                    }
                }
                is ModelState.Paused -> {
                    Text("Paused · ${fmt(s.done)} saved", style = MaterialTheme.typography.bodySmall)
                    Row { Button(onClick = { id?.let(onDownload) }) { Text("Resume") }
                          TextButton(onClick = { id?.let(onCancel) }) { Text("Cancel") } }
                }
                is ModelState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row { Button(onClick = { id?.let(onDownload) }) { Text(if (s.done > 0) "Resume" else "Retry") }
                          if (s.done > 0) TextButton(onClick = { id?.let(onCancel) }) { Text("Discard") } }
                }
            }
        }
    }
}
