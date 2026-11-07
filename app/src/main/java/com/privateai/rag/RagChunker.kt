package com.privateai.rag

data class RagChunk(val text: String, val overlapWords: Int)

object RagChunker {
    /**
     * Splits ONE page into overlapping chunks measured in real WordPiece tokens. Chunks never cross pages,
     * so every chunk has an exact page number for citations.
     * ~220 tokens (not 500): the embedder reads at most 256 tokens, and five chunks must fit a 2K-token LLM context.
     */
    fun chunk(pageText: String, tok: WordPieceTokenizer, target: Int = 220, overlapTokens: Int = 40): List<RagChunk> {
        val words = pageText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val counts = IntArray(words.size) { tok.countTokens(words[it]).coerceAtLeast(1) }
        val out = ArrayList<RagChunk>()
        var start = 0
        var overlap = 0
        val n = words.size
        while (start < n) {
            var end = start
            var used = 0
            while (end < n && used + counts[end] <= target) { used += counts[end]; end++ }
            if (end == start) end = start + 1
            val text = words.subList(start, end).joinToString(" ")
            if (text.length >= 20) out += RagChunk(text, overlap)
            if (end >= n) break
            var back = end
            var ot = 0
            while (back > start + 1 && ot + counts[back - 1] <= overlapTokens) { back--; ot += counts[back] }
            overlap = end - back
            start = back
        }
        return out
    }
}
