package com.privateai.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.privateai.data.model.FileTools
import com.privateai.data.model.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class FatalDownloadException(message: String) : Exception(message)

/**
 * Resumable model download. Writes to "<name>.part" and uses HTTP Range, so pause / network loss /
 * app restarts continue from the same byte. The finished file is checked (GGUF header + SHA-256
 * published by Hugging Face when available) before it becomes visible as installed.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "model-download"
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_TITLE = "title"
        const val KEY_APPROX = "approx"
        const val KEY_MAGIC = "magic"
        const val KEY_ERROR = "error"
        const val P_DONE = "done"
        const val P_TOTAL = "total"
        const val P_VERIFY = "verify"
        private const val CHANNEL = "downloads"
        private const val UA = "PrivateAI-Android"
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: fileName
        val approx = inputData.getLong(KEY_APPROX, 0L)
        val magic = inputData.getString(KEY_MAGIC) ?: "GGUF"
        return try {
            withContext(Dispatchers.IO) { download(url, fileName, title, approx, magic) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalDownloadException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        } catch (e: IOException) {
            if (runAttemptCount < 6) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "Network error: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private suspend fun download(url: String, fileName: String, title: String, approx: Long, magic: String) {
        val mm = ModelManager(applicationContext)
        val part = mm.partFile(fileName)
        val finalFile = mm.file(fileName)
        tryForeground(title, 0, 0)

        // Best effort: Hugging Face publishes the file's SHA-256 and size on the redirect response.
        var expectedSha: String? = null
        var knownSize: Long? = null
        try {
            val h = URL(url).openConnection() as HttpURLConnection
            h.requestMethod = "HEAD"
            h.instanceFollowRedirects = false
            h.connectTimeout = 20_000; h.readTimeout = 20_000
            h.setRequestProperty("User-Agent", UA)
            h.responseCode
            expectedSha = h.getHeaderField("X-Linked-ETag")?.trim('"')?.lowercase()
                ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
            knownSize = h.getHeaderField("X-Linked-Size")?.toLongOrNull()
            h.disconnect()
        } catch (_: IOException) { }

        var existing = if (part.exists()) part.length() else 0L
        if (knownSize != null && existing > knownSize) { part.delete(); existing = 0 }

        val expectedTotal = knownSize ?: approx
        val need = (expectedTotal - existing).coerceAtLeast(0) + 50_000_000
        if (mm.freeStorage() < need) throw FatalDownloadException("Not enough free storage for this model")

        val alreadyComplete = knownSize != null && existing == knownSize
        if (!alreadyComplete) {
            val conn = connect(url, existing)
            try {
                val code = conn.responseCode
                if (code == 416) { part.delete(); throw IOException("Restarting download") }
                if (code != 200 && code != 206) {
                    if (code in 400..499) throw FatalDownloadException("Server returned HTTP $code for this model link")
                    throw IOException("HTTP $code")
                }
                val append = code == 206 && existing > 0
                var done = if (append) existing else 0L
                val total = if (code == 206)
                    conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: knownSize ?: 0L
                else conn.contentLengthLong.takeIf { it > 0 } ?: knownSize ?: 0L

                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        var lastNotif = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport > 700) {
                                lastReport = now
                                setProgress(workDataOf(P_DONE to done, P_TOTAL to total))
                                if (now - lastNotif > 2_000) { lastNotif = now; tryForeground(title, done, total) }
                            }
                        }
                    }
                }
                if (total > 0 && part.length() != total) throw IOException("Incomplete download")
            } finally {
                conn.disconnect()
            }
        }

        // Integrity checks before the file becomes "installed".
        setProgress(workDataOf(P_DONE to part.length(), P_TOTAL to part.length(), P_VERIFY to true))
        if (!FileTools.hasMagic(part, magic)) {
            part.delete()
            throw FatalDownloadException("Downloaded file is not a valid model file")
        }
        val sum = FileTools.sha256(part)
        if (expectedSha != null && !sum.equals(expectedSha, ignoreCase = true)) {
            part.delete()
            throw FatalDownloadException("Integrity check failed (checksum mismatch). Please download again.")
        }
        finalFile.delete()
        if (!part.renameTo(finalFile)) throw FatalDownloadException("Could not save the model file")
        mm.writeChecksum(fileName, sum)
    }

    /** Follows redirects manually so the Range header survives the hop to the CDN. */
    private fun connect(startUrl: String, rangeStart: Long): HttpURLConnection {
        var u = startUrl
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                continue
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    private suspend fun tryForeground(title: String, done: Long, total: Long) {
        try {
            setForeground(foregroundInfo(title, done, total))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { /* not allowed right now (background start) — keep downloading */ }
    }

    private fun foregroundInfo(title: String, done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title")
            .setContentText(if (total > 0) "$pct%" else "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total <= 0)
            .build()
        val id = title.hashCode()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)
    }
}
