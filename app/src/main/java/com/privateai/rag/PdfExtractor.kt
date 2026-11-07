package com.privateai.rag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

data class ExtractedPdf(
    val title: String,
    val author: String,
    val pageCount: Int,
    val pages: List<Pair<Int, String>>, // (1-based page number, text)
    val ocrPages: Int,
)

class PdfExtractor(private val ctx: Context) {
    private companion object {
        const val MIN_TEXT = 25
        private var pdfBoxReady = false
    }

    /** Text pages come from PdfBox. Pages with almost no text are OCR'd on-device (ML Kit) when [ocr] is true. */
    suspend fun extract(file: File, ocr: Boolean, onProgress: (page: Int, total: Int, ocr: Boolean) -> Unit): ExtractedPdf {
        if (!pdfBoxReady) { PDFBoxResourceLoader.init(ctx); pdfBoxReady = true }
        val doc = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        var recognizerUsed = false
        try {
            if (doc.isEncrypted) throw IOException("This PDF is password-protected")
            val total = doc.numberOfPages
            val info = doc.documentInformation
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = ArrayList<Pair<Int, String>>()
            var ocrCount = 0
            for (p in 1..total) {
                currentCoroutineContext().ensureActive()
                onProgress(p, total, false)
                stripper.startPage = p
                stripper.endPage = p
                var text = clean(stripper.getText(doc))
                if (text.length < MIN_TEXT && ocr) {
                    onProgress(p, total, true)
                    if (renderer == null) {
                        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(pfd!!)
                    }
                    val bmp = renderPage(renderer!!, p - 1)
                    try {
                        recognizerUsed = true
                        val ocrText = clean(recognize(recognizer, bmp))
                        if (ocrText.length > text.length) { text = ocrText; ocrCount++ }
                    } finally {
                        bmp.recycle()
                    }
                }
                if (text.isNotBlank()) pages += p to text
            }
            return ExtractedPdf(info?.title.orEmpty().trim(), info?.author.orEmpty().trim(), total, pages, ocrCount)
        } finally {
            doc.close()
            renderer?.close()
            pfd?.close()
            if (recognizerUsed) recognizer.close()
        }
    }

    private fun clean(s: String): String =
        s.replace("\u0000", "").replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun renderPage(r: PdfRenderer, index: Int): Bitmap {
        val page = r.openPage(index)
        try {
            val w = minOf(page.width * 2, 1800)
            val h = (w.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private suspend fun recognize(rec: com.google.mlkit.vision.text.TextRecognizer, bmp: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { e ->
                    Log.w("PrivateAI", "OCR failed", e)
                    if (cont.isActive) cont.resume("")
                }
        }
}
