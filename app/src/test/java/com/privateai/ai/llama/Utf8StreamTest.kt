package com.privateai.ai.llama

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamTest {
    @Test fun plainAsciiPassesThrough() {
        assertEquals("abc", Utf8Stream().push("abc".toByteArray()))
    }

    @Test fun splitMultiByteCharacterIsHeldBackUntilComplete() {
        val bytes = "नमस्ते".toByteArray(Charsets.UTF_8)
        val s = Utf8Stream()
        val sb = StringBuilder()
        for (b in bytes) sb.append(s.push(byteArrayOf(b)))   // feed one byte at a time
        assertEquals("नमस्ते", sb.toString())
    }

    @Test fun emojiSplitAcrossPieces() {
        val bytes = "a😀b".toByteArray(Charsets.UTF_8)
        val s = Utf8Stream()
        val out = s.push(bytes.copyOfRange(0, 3)) + s.push(bytes.copyOfRange(3, bytes.size))
        assertEquals("a😀b", out)
    }
}
