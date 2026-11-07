package com.privateai.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** On-device sentence embeddings: all-MiniLM-L6-v2 (int8) run with ONNX Runtime. 384 dimensions, unit length. */
object Embedder {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tok: WordPieceTokenizer? = null

    @Synchronized
    fun ensure(ctx: Context) {
        if (session != null) return
        try {
            val modelBytes = ctx.assets.open("embedding/model.onnx").use { it.readBytes() }
            val vocab = ctx.assets.open("embedding/vocab.txt").bufferedReader().use { it.readLines() }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = e.createSession(modelBytes, opts)
            env = e
            tok = WordPieceTokenizer(vocab)
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("The embedding model is missing from this build. Build the APK with the GitHub workflow, which downloads it.")
        }
    }

    fun tokenizer(): WordPieceTokenizer = tok ?: error("Embedder not initialised")

    fun embed(text: String): FloatArray {
        val t = tok ?: error("Embedder not initialised")
        val s = session ?: error("Embedder not initialised")
        val e = env ?: error("Embedder not initialised")
        val ids = t.encode(text, 256)
        val n = ids.size
        val names = s.inputNames
        val tensors = ArrayList<OnnxTensor>()
        val feeds = HashMap<String, OnnxTensor>()
        try {
            fun add(name: String, row: LongArray) {
                if (name in names) {
                    val tt = OnnxTensor.createTensor(e, arrayOf(row))
                    tensors += tt
                    feeds[name] = tt
                }
            }
            add("input_ids", LongArray(n) { ids[it].toLong() })
            add("attention_mask", LongArray(n) { 1L })
            add("token_type_ids", LongArray(n))
            s.run(feeds).use { res ->
                @Suppress("UNCHECKED_CAST")
                val seq = (res[0].value as Array<Array<FloatArray>>)[0]
                val dim = seq[0].size
                val v = FloatArray(dim)
                for (row in seq) for (d in 0 until dim) v[d] += row[d]   // mean pooling (all tokens attended)
                var norm = 0f
                for (d in 0 until dim) { v[d] /= seq.size; norm += v[d] * v[d] }
                val inv = 1f / sqrt(norm).coerceAtLeast(1e-9f)
                for (d in 0 until dim) v[d] *= inv                        // L2 normalise → dot product = cosine
                return v
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun FloatArray.toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in this) bb.putFloat(f)
        return bb.array()
    }

    fun ByteArray.toFloats(): FloatArray {
        val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { bb.getFloat() }
    }
}
