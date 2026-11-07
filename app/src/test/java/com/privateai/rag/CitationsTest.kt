package com.privateai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {
    private val allowed = setOf(4, 7)

    @Test fun validCitationIsKept() {
        assertEquals("Plants make sugar. [Page 4]", Citations.sanitize("Plants make sugar. [Page 4]", allowed, listOf(4)))
    }

    @Test fun inventedPageIsRemovedAndFallbackAdded() {
        val out = Citations.sanitize("Plants make sugar. [Page 99]", allowed, listOf(7, 4))
        assertFalse(out.contains("99"))
        assertTrue(out.contains("[Page 7], [Page 4]"))
    }

    @Test fun differentFormatsAreNormalised() {
        val out = Citations.sanitize("A (Page 4) and B (p. 7).", allowed, listOf(4))
        assertTrue(out.contains("[Page 4]"))
        assertTrue(out.contains("[Page 7]"))
        assertFalse(out.contains("Most relevant pages"))
    }

    @Test fun listOfPagesWithOneInvalidIsDropped() {
        val out = Citations.sanitize("Fact [Page 4, 12].", allowed, listOf(4))
        assertFalse(out.contains("12"))
    }
}
