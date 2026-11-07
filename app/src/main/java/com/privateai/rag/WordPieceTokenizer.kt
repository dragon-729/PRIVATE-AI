package com.privateai.rag

import java.text.Normalizer

/** BERT-uncased WordPiece tokenizer (matches all-MiniLM-L6-v2's vocab.txt). English works best. */
class WordPieceTokenizer(vocabLines: List<String>) {
    private val vocab = HashMap<String, Int>(vocabLines.size * 2)
    init { vocabLines.forEachIndexed { i, t -> vocab[t.trim()] = i } }

    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102
    private val unk = vocab["[UNK]"] ?: 100

    private val punctTypes = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    ).map { it.toInt() }.toSet()

    private fun isPunct(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return Character.getType(c) in punctTypes
    }

    private fun isCjk(cp: Int) =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x3040..0x30FF

    fun basic(text: String): List<String> {
        val norm = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) } }
        for (ch in norm) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.code == 0 || ch.code == 0xFFFD) continue
            when {
                ch.isWhitespace() -> flush()
                Character.isISOControl(ch) -> {}
                isPunct(ch) || isCjk(ch.code) -> { flush(); out += ch.toString() }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    private fun pieces(token: String): List<Int> {
        if (token.length > 100) return listOf(unk)
        val ids = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found = -1
            while (end > start) {
                val sub = (if (start > 0) "##" else "") + token.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) return listOf(unk)
            ids += found
            start = end
        }
        return ids
    }

    fun countTokens(word: String): Int = basic(word).sumOf { pieces(it).size }

    fun encode(text: String, maxLen: Int = 256): IntArray {
        val ids = ArrayList<Int>()
        ids += cls
        loop@ for (tok in basic(text)) {
            for (id in pieces(tok)) {
                if (ids.size >= maxLen - 1) break@loop
                ids += id
            }
        }
        ids += sep
        return ids.toIntArray()
    }
}
