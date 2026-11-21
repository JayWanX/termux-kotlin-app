package com.termux.shared.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundProcessProbeTest {

    @Test
    fun `parses a plain tpgid value`() {
        assertEquals(1234, ForegroundProcessProbe.parseTpgid("1234\n"))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(5678, ForegroundProcessProbe.parseTpgid("   5678   \n"))
    }

    @Test
    fun `rejects a negative group meaning no controlling terminal`() {
        assertNull(ForegroundProcessProbe.parseTpgid("-1\n"))
    }

    @Test
    fun `returns null for empty or missing output`() {
        assertNull(ForegroundProcessProbe.parseTpgid(null))
        assertNull(ForegroundProcessProbe.parseTpgid(""))
        assertNull(ForegroundProcessProbe.parseTpgid("   \n"))
    }

    @Test
    fun `returns null for non numeric output`() {
        assertNull(ForegroundProcessProbe.parseTpgid("not-a-pid\n"))
    }

    @Test
    fun `takes the first field when ps prints extra columns`() {
        assertEquals(42, ForegroundProcessProbe.parseTpgid("42 extra junk\n"))
    }

    @Test
    fun `shell in foreground means no child command is running`() {
        assertEquals(true, ForegroundProcessProbe.isShellInForeground(1000, 1000))
    }

    @Test
    fun `different foreground group means a child command is running`() {
        assertEquals(false, ForegroundProcessProbe.isShellInForeground(1000, 2000))
    }

    @Test
    fun `unknown tpgid yields null so callers can fall back`() {
        assertNull(ForegroundProcessProbe.isShellInForeground(1000, null))
    }

    @Test
    fun `invalid shell pid yields null`() {
        assertNull(ForegroundProcessProbe.isShellInForeground(0, 1000))
        assertNull(ForegroundProcessProbe.isShellInForeground(-1, 1000))
    }

    @Test
    fun `sleep style silent command is detected as not shell foreground`() {
        // shell pid 900, tpgid belongs to the spawned sleep
        assertFalse(ForegroundProcessProbe.isShellInForeground(900, 901) == true)
    }
}
