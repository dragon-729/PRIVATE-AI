package com.privateai.data.model

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.privateai.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object FileTools {
    fun hasMagic(f: File, magic: String): Boolean = try {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(4)
            r.read(b) == 4 && String(b, Charsets.US_ASCII) == magic
        }
    } catch (_: IOException) { false }

    fun hasGgufMagic(f: File): Boolean = hasMagic(f, "GGUF")

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class ModelManager(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val work get() = WorkManager.getInstance(context)

    fun file(name: String) = File(dir, name)
    fun partFile(name: String) = File(dir, "$name.part")
    private fun sumFile(name: String) = File(dir, "$name.sha256")
    fun writeChecksum(name: String, hex: String) = sumFile(name).writeText(hex)

    fun totalRam(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }

    fun freeStorage(): Long = dir.usableSpace

    /** Rough estimate only: weights + ~25% overhead + KV cache at 2K context, compared with total RAM. */
    fun compat(sizeBytes: Long, minRamGb: Int?): Compat {
        val total = totalRam().toDouble()
        val gb = total / 1_073_741_824.0
        val need = sizeBytes * 1.25 + 300e6
        val ratio = need / total
        var c = when {
            ratio <= 0.55 -> Compat.OK
            ratio <= 0.75 -> Compat.TIGHT
            else -> Compat.TOO_LARGE
        }
        if (minRamGb != null) {
            val m = when {
                gb >= minRamGb - 1.0 -> Compat.OK
                gb >= minRamGb - 2.5 -> Compat.TIGHT
                else -> Compat.TOO_LARGE
            }
            if (m.ordinal > c.ordinal) c = m
        }
        return c
    }

    fun quantOf(name: String): String {
        val m = Regex("(IQ\\d_[A-Z]+|Q\\d_K_[A-Z]|Q\\d_[01]|Q\\d_K|BF16|F16|F32)").find(name.uppercase())
        return m?.value ?: "unknown"
    }

    // ---------- downloads (WorkManager) ----------
    fun workInfos(): Flow<List<WorkInfo>> = work.getWorkInfosByTagFlow(DownloadWorker.TAG)

    fun start(c: CatalogModel) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(
                DownloadWorker.KEY_URL to c.url,
                DownloadWorker.KEY_FILE to c.fileName,
                DownloadWorker.KEY_TITLE to c.title,
                DownloadWorker.KEY_APPROX to c.approxBytes,
                DownloadWorker.KEY_MAGIC to c.magic,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag("m:${c.id}")
            .build()
        work.enqueueUniqueWork("dl-${c.id}", ExistingWorkPolicy.KEEP, req)
    }

    /** Pause keeps the partial file, so Resume continues from the same byte. */
    fun pause(c: CatalogModel) {
        work.cancelUniqueWork("dl-${c.id}")
        work.pruneWork()
    }

    fun cancel(c: CatalogModel) {
        work.cancelUniqueWork("dl-${c.id}")
        work.pruneWork()
        partFile(c.fileName).delete()
    }

    // ---------- list ----------
    private fun stateFor(c: CatalogModel, infos: List<WorkInfo>): ModelState {
        val finalFile = file(c.fileName)
        val part = partFile(c.fileName)
        val mine = infos.filter { "m:${c.id}" in it.tags }
        val live = mine.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }
        val failed = mine.firstOrNull { it.state == WorkInfo.State.FAILED }
        val partLen = if (part.exists()) part.length() else 0L
        return when {
            finalFile.exists() -> ModelState.Installed
            live != null && live.state == WorkInfo.State.RUNNING -> {
                val p = live.progress
                ModelState.Downloading(
                    done = p.getLong(DownloadWorker.P_DONE, partLen),
                    total = p.getLong(DownloadWorker.P_TOTAL, c.approxBytes).takeIf { it > 0 } ?: c.approxBytes,
                    verifying = p.getBoolean(DownloadWorker.P_VERIFY, false),
                )
            }
            live != null -> ModelState.Queued(partLen)
            failed != null -> ModelState.Failed(failed.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed", partLen)
            partLen > 0 -> ModelState.Paused(partLen)
            else -> ModelState.NotInstalled
        }
    }

    private fun catalogItems(list: List<CatalogModel>, infos: List<WorkInfo>, activeFile: String?): List<ModelItem> =
        list.map { c ->
            val finalFile = file(c.fileName)
            val size = if (finalFile.exists()) finalFile.length() else c.approxBytes
            ModelItem(
                key = c.id, title = c.title, fileName = c.fileName,
                sizeBytes = size, sizeApprox = !finalFile.exists(),
                quant = quantOf(c.fileName),
                compat = compat(size, c.minRamGb),
                state = stateFor(c, infos), license = c.license, catalogId = c.id,
                imported = false, active = activeFile == c.fileName, kind = c.kind,
            )
        }

    fun buildItems(infos: List<WorkInfo>, activeFile: String?): List<ModelItem> {
        val items = catalogItems(ModelCatalog.all, infos, activeFile).toMutableList()
        val known = ModelCatalog.all.map { it.fileName }.toSet()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) && f.name !in known }
            ?.sortedBy { it.name }?.forEach { f ->
                items += ModelItem(
                    key = "file:${f.name}", title = f.name.removeSuffix(".gguf"), fileName = f.name,
                    sizeBytes = f.length(), sizeApprox = false, quant = quantOf(f.name),
                    compat = compat(f.length(), null), state = ModelState.Installed,
                    license = "Imported file — check the model's own license",
                    catalogId = null, imported = true, active = activeFile == f.name,
                )
            }
        return items
    }

    fun whisperItems(infos: List<WorkInfo>, activeFile: String?): List<ModelItem> =
        catalogItems(ModelCatalog.whisper, infos, activeFile)

    // ---------- import / delete / verify ----------
    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }

    suspend fun importFrom(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "model.gguf"
        require(name.endsWith(".gguf", ignoreCase = true)) { "Please choose a .gguf file" }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(dir, "import.tmp")
        val dest = File(dir, safe)
        val md = MessageDigest.getInstance("SHA-256")
        val usable = dir.usableSpace
        try {
            context.contentResolver.openInputStream(uri)!!.use { raw ->
                DigestInputStream(raw, md).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied > usable) throw IOException("Not enough free storage")
                        }
                    }
                }
            }
            if (!FileTools.hasGgufMagic(tmp)) throw IllegalArgumentException("This file is not a valid GGUF model")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model file")
            writeChecksum(safe, with(FileTools) { md.digest().toHex() })
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        dest
    }

    fun storageUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Cancels downloads and removes every model file (LLM and voice). */
    fun deleteAll() {
        work.cancelAllWorkByTag(DownloadWorker.TAG)
        work.pruneWork()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun delete(name: String) {
        file(name).delete()
        sumFile(name).delete()
        partFile(name).delete()
    }

    suspend fun verify(name: String): String = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.exists()) return@withContext "File not found."
        val magic = if (name.endsWith(".bin", true)) "lmgg" else "GGUF"
        if (!FileTools.hasMagic(f, magic)) return@withContext "FAILED — this is not a valid model file. Delete it."
        val sum = FileTools.sha256(f)
        val saved = sumFile(name).takeIf { it.exists() }?.readText()?.trim()
        when {
            saved == null -> "Header OK. SHA-256 starts with ${sum.take(16)}… (no saved checksum to compare)."
            saved.equals(sum, true) -> "OK — SHA-256 matches the checksum recorded when this file was saved."
            else -> "FAILED — the file has changed or is corrupted. Delete it and download or import again."
        }
    }
}
