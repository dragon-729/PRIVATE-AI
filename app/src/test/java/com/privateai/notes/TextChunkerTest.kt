package com.privateai.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("Hello world."), TextChunker.split("Hello world.", 2000))
    }

    @Test fun longTextIsSplitWithinLimit() {
        val text = (1..400).joinToString("\n\n") { "Paragraph number $it has some words in it." }
        val parts = TextChunker.split(text, 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 500 })
    }

    @Test fun oneHugeParagraphIsSplitOnSentences() {
        val text = (1..300).joinToString(" ") { "Sentence $it ends here." }
        val parts = TextChunker.split(text, 400)
        assertTrue(parts.all { it.length <= 400 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }
}
