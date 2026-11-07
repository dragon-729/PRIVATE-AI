package com.privateai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagChunkerTest {
    // Every word is unknown to this vocabulary → exactly 1 token each, which makes the maths easy.
    private val tok = WordPieceTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]"))
    private val words = (1..500).map { "word$it" }
    private val page = words.joinToString(" ")

    @Test fun chunksRespectTokenTargetAndOverlap() {
        val chunks = RagChunker.chunk(page, tok, target = 100, overlapTokens = 20)
        assertTrue(chunks.size > 4)
        assertTrue(chunks.all { it.text.split(" ").size <= 100 })
        assertEquals(0, chunks.first().overlapWords)
        assertTrue(chunks.drop(1).all { it.overlapWords in 1..20 })
    }

    @Test fun droppingOverlapRebuildsTheOriginalPage() {
        val chunks = RagChunker.chunk(page, tok, target = 100, overlapTokens = 20)
        val rebuilt = chunks.flatMapIndexed { i, c ->
            val w = c.text.split(" ")
            if (i == 0) w else w.drop(c.overlapWords)
        }
        assertEquals(words, rebuilt)
    }

    @Test fun tinyPageStillProducesOneChunk() {
        val chunks = RagChunker.chunk("This page has just a few words on it.", tok)
        assertEquals(1, chunks.size)
    }

    @Test fun emptyPageProducesNothing() {
        assertTrue(RagChunker.chunk("   \n  ", tok).isEmpty())
    }
}
