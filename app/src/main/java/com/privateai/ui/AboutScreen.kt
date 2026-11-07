package com.privateai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

const val GITHUB_URL = "https://github.com/dragon-729"
const val TELEGRAM_URL = "https://t.me/YogeshBhai729"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Scaffold(topBar = {
        TopAppBar(title = { Text("About") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Private AI", style = MaterialTheme.typography.headlineMedium)
            val version = remember {
                try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null }
            }
            if (version != null) Text("Version $version", style = MaterialTheme.typography.labelMedium)
            Text("Your AI. Your Device. Your Privacy.", color = MaterialTheme.colorScheme.primary)
            Text("Runs GGUF language models locally with llama.cpp. Your chats, notes and documents are processed only on this phone. Internet is used only to download models you choose.")
            HorizontalDivider()
            Text("Developer", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { open(GITHUB_URL) }, modifier = Modifier.fillMaxWidth()) { Text("GitHub · dragon-729") }
            OutlinedButton(onClick = { open(TELEGRAM_URL) }, modifier = Modifier.fillMaxWidth()) { Text("Telegram · @YogeshBhai729") }
            Spacer(Modifier.weight(1f))
            Text("Powered by llama.cpp (MIT). PDF text: PdfBox-Android (Apache-2.0). OCR: Google ML Kit. Search: all-MiniLM-L6-v2 (Apache-2.0) with ONNX Runtime (MIT). Voice: whisper.cpp and OpenAI Whisper models (MIT). Chat models keep their own licenses — check any model you import.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
