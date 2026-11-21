package com.termux.shared.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [TranscriptSearch], the text search behind the scrollback finder. */
class TranscriptSearchTest {

    private val transcript = """
        line one
        ERROR something failed
        line three
        another error here
        tail
    """.trimIndent()

    @Test
    fun `finds all occurrences case insensitively`() {
        val matches = TranscriptSearch.find(transcript, "error")
        assertEquals(2, matches.size)
        assertEquals(1, matches[0].lineIndex)
        assertEquals(3, matches[1].lineIndex)
    }

    @Test
    fun `respects case when asked`() {
        val matches = TranscriptSearch.find(transcript, "ERROR", ignoreCase = false)
        assertEquals(1, matches.size)
        assertEquals(1, matches[0].lineIndex)
    }

    @Test
    fun `reports column and length`() {
        val matches = TranscriptSearch.find(transcript, "three")
        assertEquals(1, matches.size)
        assertTrue(matches[0].column > 0)
        assertEquals(5, matches[0].length)
        assertEquals(matches[0].column until matches[0].column + 5, matches[0].range)
    }

    @Test
    fun `multiple matches on one line are all reported`() {
        val matches = TranscriptSearch.find("aXbXc", "X")
        assertEquals(2, matches.size)
        assertEquals(1, matches[0].column)
        assertEquals(3, matches[1].column)
    }

    @Test
    fun `blank query yields nothing`() {
        assertTrue(TranscriptSearch.find(transcript, "").isEmpty())
    }

    @Test
    fun `empty transcript yields nothing`() {
        assertTrue(TranscriptSearch.find("", "x").isEmpty())
    }

    @Test
    fun `no match yields nothing`() {
        assertTrue(TranscriptSearch.find(transcript, "zzzz").isEmpty())
    }

    @Test
    fun `results are capped`() {
        val many = (1..1000).joinToString("\n") { "hit" }
        val matches = TranscriptSearch.find(many, "hit", maxMatches = 10)
        assertEquals(10, matches.size)
    }

    @Test
    fun `next moves forward and wraps`() {
        val matches = TranscriptSearch.find(transcript, "error")
        assertEquals(1, TranscriptSearch.next(matches, currentLine = 0, forward = true)?.lineIndex)
        assertEquals(3, TranscriptSearch.next(matches, currentLine = 1, forward = true)?.lineIndex)
        // Past the last match it wraps to the first.
        assertEquals(1, TranscriptSearch.next(matches, currentLine = 3, forward = true)?.lineIndex)
    }

    @Test
    fun `previous moves backward and wraps`() {
        val matches = TranscriptSearch.find(transcript, "error")
        assertEquals(1, TranscriptSearch.next(matches, currentLine = 2, forward = false)?.lineIndex)
        // Before the first match it wraps to the last.
        assertEquals(3, TranscriptSearch.next(matches, currentLine = 0, forward = false)?.lineIndex)
    }

    @Test
    fun `next on empty results is null`() {
        assertNull(TranscriptSearch.next(emptyList(), 0, true))
    }

    @Test
    fun `summary text reflects the result count`() {
        assertEquals("", TranscriptSearch.summarize(emptyList(), ""))
        assertEquals("No matches", TranscriptSearch.summarize(emptyList(), "x"))
        assertEquals("1 match", TranscriptSearch.summarize(TranscriptSearch.find(transcript, "tail"), "tail"))
        assertEquals("2 matches", TranscriptSearch.summarize(TranscriptSearch.find(transcript, "error"), "error"))
    }

    @Test
    fun `trailing padding is trimmed from matched lines`() {
        val matches = TranscriptSearch.find("needle        \n", "needle")
        assertEquals(1, matches.size)
        assertEquals("needle", matches[0].line)
    }
}
