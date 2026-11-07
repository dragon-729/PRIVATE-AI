package com.privateai.notes

object TextChunker {
    /** Splits on paragraph boundaries into pieces of at most [max] characters (small enough for a 2K-token context). */
    fun split(text: String, max: Int = 2000): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out += cur.toString().trim()
            cur.clear()
        }
        for (para in text.trim().split(Regex("\n\\s*\n"))) {
            if (para.length > max) {
                flush()
                out += hardSplit(para, max)
                continue
            }
            if (cur.length + para.length + 2 > max) flush()
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(para)
        }
        flush()
        return out.ifEmpty { listOf(text.trim()) }
    }

    private fun hardSplit(p: String, max: Int): List<String> {
        val res = mutableListOf<String>()
        var start = 0
        while (start < p.length) {
            var end = minOf(start + max, p.length)
            if (end < p.length) {
                val cut = p.lastIndexOfAny(charArrayOf('.', '।', '!', '?', '\n', ' '), end - 1)
                if (cut > start + max / 2) end = cut + 1
            }
            val piece = p.substring(start, end).trim()
            if (piece.isNotEmpty()) res += piece
            start = end
        }
        return res
    }
}
