package com.privateai.rag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "##s", ",", ".", "play", "##ing")
    private val tok = WordPieceTokenizer(vocab)

    @Test fun splitsPunctuationAndLowercases() {
        // [CLS] hello , world ##s . [SEP]
        assertArrayEquals(intArrayOf(2, 4, 7, 5, 6, 8, 3), tok.encode("Hello, worlds."))
    }

    @Test fun unknownWordBecomesUnk() {
        assertArrayEquals(intArrayOf(2, 1, 3), tok.encode("zzz"))
    }

    @Test fun wordPieceSplitsKnownSuffix() {
        assertArrayEquals(intArrayOf(2, 9, 10, 3), tok.encode("playing"))
    }

    @Test fun countTokensMatchesEncodeWithoutSpecials() {
        assertEquals(2, tok.countTokens("playing"))
        assertEquals(1, tok.countTokens("hello"))
    }

    @Test fun encodeRespectsMaxLength() {
        val ids = tok.encode("hello ".repeat(100), maxLen = 10)
        assertEquals(10, ids.size)
        assertEquals(3, ids.last())   // still ends with [SEP]
    }
}
