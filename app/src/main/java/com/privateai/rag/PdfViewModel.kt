package com.privateai.rag

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.privateai.ai.ChatTurn
import com.privateai.ai.EngineHolder
import com.privateai.ai.GenerationConfig
import com.privateai.data.AppDatabase
import com.privateai.data.DocumentChunkEntity
import com.privateai.data.DocumentEntity
import com.privateai.notes.TextChunker
import com.privateai.rag.Embedder.toBytes
import com.privateai.rag.Embedder.toFloats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

data class DocMsg(val id: Long, val role: String, val text: String)

data class PdfUi(
    val docs: List<DocumentEntity> = emptyList(),
    val importing: Boolean = false,
    val importPhase: String = "",
    val importProgress: Float = -1f,
    val ocrEnabled: Boolean = true,
    val message: String? = null,
    val openDoc: DocumentEntity? = null,
    val msgs: List<DocMsg> = emptyList(),
    val running: Boolean = false,
    val progress: String? = null,
)

private class Draft(val page: Int, val idx: Int, val overlap: Int, val text: String)
private class Segment(val pageFrom: Int, val pageTo: Int, val text: String)
private class Hit(val chunk: DocumentChunkEntity, val score: Float)

class PdfViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val db = AppDatabase.get(app)
    private val dao = db.documentDao()
    private val engine = EngineHolder.engine
    private val prefs = app.getSharedPreferences("privateai", 0)
    private val _ui = MutableStateFlow(PdfUi(ocrEnabled = prefs.getBoolean("ocr", true)))
    val ui: StateFlow<PdfUi> = _ui

    private var importJob: Job? = null
    private var aiJob: Job? = null
    @Volatile private var stopRequested = false
    private var nextId = 1L

    private companion object {
        const val TOP_K = 5
        const val CONTEXT_CHARS = 3600
        const val MIN_SCORE = 0.20f      // cosine similarity below this = "not in the document"
        const val SEG = 2000
        const val MAX_SEG = 30
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_FOUND_MSG = "I couldn't find that in this document."
        const val QA_SYSTEM = "You answer questions using ONLY the document excerpts provided. " +
            "Each excerpt starts with its page, like [Page 4]. After each fact you use, write its page in exactly that form, e.g. [Page 4]. " +
            "Never invent page numbers. If the excerpts do not contain the answer, reply with exactly NOT_FOUND and nothing else."
        const val TASK_SYSTEM = "You are a careful assistant that works only with the text the user provides. " +
            "Do not invent facts. Follow the instruction exactly and output only the result."
    }

    init {
        viewModelScope.launch { dao.observeAll().collect { l -> _ui.update { it.copy(docs = l) } } }
    }

    fun say(msg: String?) = _ui.update { it.copy(message = msg) }
    fun setOcr(on: Boolean) { prefs.edit().putBoolean("ocr", on).apply(); _ui.update { it.copy(ocrEnabled = on) } }

    // ---------------- import & index ----------------
    fun importPdf(uri: Uri) {
        if (_ui.value.importing) return
        _ui.update { it.copy(importing = true, importPhase = "Copying PDF…", importProgress = -1f, message = null) }
        importJob = viewModelScope.launch {
            val tmp = File(ctx.cacheDir, "import.pdf")
            try {
                val name = withContext(Dispatchers.IO) { copyPdf(uri, tmp) }
                val ocr = _ui.value.ocrEnabled
                withContext(Dispatchers.Default) {
                    Embedder.ensure(ctx)
                    val tok = Embedder.tokenizer()
                    val ex = PdfExtractor(ctx).extract(tmp, ocr) { page, total, isOcr ->
                        phase(if (isOcr) "OCR page $page of $total" else "Reading page $page of $total", 0.4f * page / total)
                    }
                    val drafts = ArrayList<Draft>()
                    var idx = 0
                    for ((page, text) in ex.pages) {
                        for (c in RagChunker.chunk(text, tok)) drafts += Draft(page, idx++, c.overlapWords, c.text)
                    }
                    if (drafts.isEmpty()) {
                        throw IOException("No readable text found in this PDF." + if (!ocr) " Try turning OCR on." else "")
                    }
                    val vectors = ArrayList<ByteArray>(drafts.size)
                    for ((i, d) in drafts.withIndex()) {
                        ensureActive()
                        vectors += Embedder.embed(d.text).toBytes()
                        if (i % 5 == 0 || i == drafts.size - 1) {
                            phase("Indexing section ${i + 1} of ${drafts.size}", 0.4f + 0.6f * (i + 1) / drafts.size)
                        }
                    }
                    db.withTransaction {
                        val docId = dao.insert(
                            DocumentEntity(
                                name = name, title = ex.title, author = ex.author, pageCount = ex.pageCount,
                                chunkCount = drafts.size, ocrPages = ex.ocrPages, createdAt = System.currentTimeMillis(),
                            )
                        )
                        dao.insertChunks(drafts.mapIndexed { i, d ->
                            DocumentChunkEntity(documentId = docId, page = d.page, idx = d.idx,
                                overlapWords = d.overlap, text = d.text, embedding = vectors[i])
                        })
                    }
                    say("Imported “$name”: ${ex.pageCount} pages, ${drafts.size} sections" +
                        if (ex.ocrPages > 0) ", ${ex.ocrPages} pages read with OCR." else ".")
                }
            } catch (e: CancellationException) {
                say("Import cancelled.")
                throw e
            } catch (t: Throwable) {
                say("Import failed: ${t.message}")
            } finally {
                withContext(NonCancellable) {
                    tmp.delete()
                    _ui.update { it.copy(importing = false, importProgress = -1f) }
                }
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    private fun phase(text: String, progress: Float) =
        _ui.update { it.copy(importPhase = text, importProgress = progress) }

    private fun copyPdf(uri: Uri, dest: File): String {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "document.pdf"
        val limit = 150L * 1024 * 1024
        val free = ctx.cacheDir.usableSpace
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > limit) throw IOException("PDF is larger than 150 MB")
                    if (total > free) throw IOException("Not enough free storage")
                }
            }
        }
        val magic = ByteArray(5)
        dest.inputStream().use { it.read(magic) }
        if (String(magic, Charsets.US_ASCII) != "%PDF-") throw IOException("This file is not a PDF")
        return name.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    fun deleteDoc(id: Long) {
        viewModelScope.launch {
            if (_ui.value.openDoc?.id == id) closeDoc()
            dao.delete(id)
        }
    }

    fun deleteAllDocs() {
        closeDoc()
        viewModelScope.launch { dao.deleteAll() }
    }

    // ---------------- open document ----------------
    fun openDoc(d: DocumentEntity) = _ui.update { it.copy(openDoc = d, msgs = emptyList(), progress = null) }

    fun closeDoc() {
        stopAi()
        _ui.update { it.copy(openDoc = null, msgs = emptyList(), progress = null) }
    }

    private fun addMsg(role: String, text: String) =
        _ui.update { it.copy(msgs = it.msgs + DocMsg(nextId++, role, text)) }

    private fun setLast(text: String) = _ui.update { st ->
        val m = st.msgs.toMutableList()
        if (m.isNotEmpty()) m[m.lastIndex] = m.last().copy(text = text)
        st.copy(msgs = m)
    }

    private fun progress(s: String?) = _ui.update { it.copy(progress = s) }

    fun stopAi() { if (_ui.value.running) { stopRequested = true; engine.stop() } }

    private fun begin(userText: String): DocumentEntity? {
        val doc = _ui.value.openDoc ?: return null
        if (!engine.isLoaded) { addMsg("assistant", "Load a model first (Models screen)."); return null }
        if (!EngineHolder.tryAcquire()) { addMsg("assistant", "The AI is busy with another task."); return null }
        stopRequested = false
        addMsg("user", userText)
        addMsg("assistant", "")
        _ui.update { it.copy(running = true) }
        return doc
    }

    private suspend fun end() = withContext(NonCancellable) {
        _ui.update { it.copy(running = false, progress = null) }
        EngineHolder.release()
    }

    // ---------------- retrieval ----------------
    private suspend fun retrieve(docId: Long, query: String, k: Int): List<Hit> = withContext(Dispatchers.Default) {
        Embedder.ensure(ctx)
        val q = Embedder.embed(query)
        dao.chunks(docId)
            .map { Hit(it, Embedder.cosine(q, it.embedding.toFloats())) }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Find relevant sections: pure semantic search, no language model needed. */
    fun findSections(query: String) {
        val doc = _ui.value.openDoc ?: return
        if (query.isBlank()) return
        addMsg("user", "Find sections: $query")
        addMsg("assistant", "")
        viewModelScope.launch {
            try {
                val hits = retrieve(doc.id, query, 6)
                if (hits.isEmpty() || hits.first().score < 0.12f) {
                    setLast("No section of this document seems related to that.")
                } else {
                    setLast(hits.joinToString("\n\n") { h ->
                        val snippet = h.chunk.text.take(220).trim() + if (h.chunk.text.length > 220) "…" else ""
                        "[Page ${h.chunk.page}] · similarity ${String.format(Locale.US, "%.2f", h.score)}\n$snippet"
                    })
                }
            } catch (t: Throwable) {
                setLast("Search failed: ${t.message}")
            }
        }
    }

    // ---------------- question answering ----------------
    fun ask(question: String) {
        if (question.isBlank()) return
        val doc = begin(question.trim()) ?: return
        aiJob = viewModelScope.launch {
            try {
                progress("Searching the document…")
                val hits = retrieve(doc.id, question.trim(), TOP_K)
                if (hits.isEmpty() || hits.first().score < MIN_SCORE) {
                    setLast(NOT_FOUND_MSG)
                    return@launch
                }
                // keep the best chunks that fit the context budget, then read them in page order
                val picked = ArrayList<Hit>()
                var chars = 0
                for (h in hits) {
                    if (picked.isNotEmpty() && chars + h.chunk.text.length > CONTEXT_CHARS) break
                    picked += h
                    chars += h.chunk.text.length
                }
                val ordered = picked.sortedWith(compareBy({ it.chunk.page }, { it.chunk.idx }))
                val allowed = ordered.map { it.chunk.page }.toSet()
                val byScore = picked.map { it.chunk.page }.distinct()
                val excerpts = ordered.joinToString("\n\n") { "[Page ${it.chunk.page}]\n${it.chunk.text}" }

                progress("Writing the answer…")
                val acc = StringBuilder()
                val turns = listOf(
                    ChatTurn("system", QA_SYSTEM),
                    ChatTurn("user", "Document excerpts:\n\n$excerpts\n\nQuestion: ${question.trim()}"),
                )
                engine.generate(turns, GenerationConfig(maxTokens = 400, temperature = 0.2f)).collect { piece ->
                    acc.append(piece)
                    val t = acc.toString().trimStart()
                    when {
                        t.length <= NOT_FOUND.length && NOT_FOUND.startsWith(t) -> {}          // hold back while it might be NOT_FOUND
                        t.startsWith(NOT_FOUND) -> setLast(NOT_FOUND_MSG)
                        else -> setLast(t)
                    }
                }
                val finalText = acc.toString().trim()
                when {
                    stopRequested && finalText.isEmpty() -> setLast("Stopped.")
                    finalText.isEmpty() || finalText.startsWith(NOT_FOUND) || NOT_FOUND.startsWith(finalText) -> setLast(NOT_FOUND_MSG)
                    else -> setLast(Citations.sanitize(finalText, allowed, byScore.take(3)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                setLast("Error: ${t.message}")
            } finally {
                end()
            }
        }
    }

    // ---------------- summaries & extraction ----------------
    fun summarizeDocument() {
        val d = _ui.value.openDoc ?: return
        runDocTask("Summarize the document", SUMMARY, 1, d.pageCount)
    }

    fun summarizePages(from: Int, to: Int) {
        val d = _ui.value.openDoc ?: return
        if (from < 1 || to < from || to > d.pageCount) {
            addMsg("assistant", "Enter a page range between 1 and ${d.pageCount}.")
            return
        }
        runDocTask("Summarize pages $from–$to", SUMMARY, from, to)
    }

    fun extractInfo() {
        val d = _ui.value.openDoc ?: return
        runDocTask(
            "Extract important information",
            "Extract the important information from this text: names, dates, numbers, amounts, deadlines, decisions and requirements. " +
                "Output a bulleted list, one '- ' line per item. Use only the text.",
            1, d.pageCount,
        )
    }

    private val SUMMARY = "Summarize the following text in a short, clear paragraph. Use only information from the text."

    private fun runDocTask(label: String, instruction: String, from: Int, to: Int) {
        val doc = begin(label) ?: return
        aiJob = viewModelScope.launch {
            try {
                progress("Loading the document…")
                val pages = pagesFrom(dao.chunks(doc.id))
                var segs = segments(pages, from, to)
                if (segs.isEmpty()) { setLast("No text found on those pages."); return@launch }
                val totalSegs = segs.size
                val sampled = totalSegs > MAX_SEG
                if (sampled) segs = sample(segs)

                val acc = StringBuilder()
                fun show(s: String) { acc.clear(); acc.append(s); setLast(s) }

                if (segs.size == 1) {
                    runPrompt(instruction, segs[0].text) { acc.append(it); setLast(acc.toString()) }
                } else {
                    val partials = ArrayList<String>()
                    for ((i, s) in segs.withIndex()) {
                        if (stopRequested) break
                        progress("Reading part ${i + 1} of ${segs.size} (pages ${s.pageFrom}–${s.pageTo})")
                        partials += runPrompt(instruction, s.text)
                    }
                    var combined = partials.joinToString("\n\n")
                    var round = 0
                    while (!stopRequested && combined.length > SEG && round < 3) {
                        val parts = TextChunker.split(combined, SEG)
                        val condensed = ArrayList<String>()
                        for ((i, p) in parts.withIndex()) {
                            if (stopRequested) break
                            progress("Condensing ${i + 1} of ${parts.size}")
                            condensed += runPrompt("Condense the following text, keeping every important fact. Output only the condensed text.", p)
                        }
                        combined = condensed.joinToString("\n\n")
                        round++
                    }
                    if (!stopRequested) {
                        progress("Writing the final result")
                        val merged = "$instruction The text below combines results from several parts of one document; merge them into a single result without repeating yourself."
                        runPrompt(merged, combined.take(SEG * 2)) { acc.append(it); setLast(acc.toString()) }
                    }
                }
                val result = acc.toString().trim()
                if (result.isEmpty()) {
                    setLast(if (stopRequested) "Stopped." else "The model returned no text. Try again.")
                } else {
                    val a = segs.minOf { it.pageFrom }
                    val b = segs.maxOf { it.pageTo }
                    val footer = "Pages covered: ${if (a == b) "$a" else "$a–$b"}" +
                        if (sampled) " (long document: ${segs.size} of $totalSegs parts, spread evenly)" else ""
                    show(result + "\n\n" + footer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                setLast("Error: ${t.message}")
            } finally {
                end()
            }
        }
    }

    private suspend fun runPrompt(instruction: String, text: String, onPiece: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        val turns = listOf(ChatTurn("system", TASK_SYSTEM), ChatTurn("user", "$instruction\n\n---\n$text\n---"))
        engine.generate(turns, GenerationConfig(maxTokens = 600, temperature = 0.3f)).collect { sb.append(it); onPiece(it) }
        return sb.toString().trim()
    }

    /** Rebuilds page text from stored chunks, dropping the words repeated by the overlap. */
    private fun pagesFrom(chunks: List<DocumentChunkEntity>): List<Pair<Int, String>> {
        val out = LinkedHashMap<Int, StringBuilder>()
        var prevPage = -1
        for (c in chunks) {
            val words = c.text.split(' ')
            val body = if (c.page == prevPage && c.overlapWords > 0) words.drop(c.overlapWords) else words
            val sb = out.getOrPut(c.page) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(body.joinToString(" "))
            prevPage = c.page
        }
        return out.map { it.key to it.value.toString() }
    }

    private fun segments(pages: List<Pair<Int, String>>, from: Int, to: Int): List<Segment> {
        val segs = ArrayList<Segment>()
        var cur = StringBuilder()
        var pf = -1
        var pt = -1
        fun flush() {
            if (cur.isNotBlank()) segs += Segment(pf, pt, cur.toString().trim())
            cur = StringBuilder()
            pf = -1
        }
        for ((p, t) in pages) {
            if (p < from || p > to) continue
            if (t.length > SEG) {
                flush()
                TextChunker.split(t, SEG).forEach { segs += Segment(p, p, it) }
                continue
            }
            if (cur.length + t.length + 1 > SEG) flush()
            if (pf == -1) pf = p
            pt = p
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(t)
        }
        flush()
        return segs
    }

    private fun sample(segs: List<Segment>): List<Segment> {
        val n = segs.size
        return (0 until MAX_SEG).map { (it * (n - 1).toDouble() / (MAX_SEG - 1)).roundToInt() }.distinct().map { segs[it] }
    }
}
