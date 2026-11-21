package com.termux.shared.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSnapshotTest {

    private fun snapshot(
        state: TerminalState = TerminalState.Idle(600L),
        visible: String = "",
        scrollback: String = "",
        isAlt: Boolean = false,
    ) = TerminalSnapshot(
        state = state,
        visibleText = visible,
        scrollbackTail = scrollback,
        cursorRow = 3,
        cursorCol = 7,
        rows = 24,
        cols = 80,
        isAltBuffer = isAlt,
    )

    @Test
    fun `lastLine ignores trailing blank lines`() {
        val s = snapshot(visible = "user@host:~$ ls\nfile1\nfile2\n\n\n")
        assertEquals("file2", s.lastLine)
    }

    @Test
    fun `lastLine is empty when the screen is blank`() {
        assertEquals("", snapshot(visible = "\n\n  \n").lastLine)
    }

    @Test
    fun `lastSessionLine falls back to scrollback when screen is blank`() {
        val s = snapshot(visible = "", scrollback = "old output\nfinal line")
        assertEquals("final line", s.lastSessionLine)
    }

    @Test
    fun `lastSessionLine prefers the visible screen`() {
        val s = snapshot(visible = "visible tail", scrollback = "older line")
        assertEquals("visible tail", s.lastSessionLine)
    }

    @Test
    fun `exitStatus is exposed only when dead`() {
        assertEquals(2, snapshot(state = TerminalState.Dead(2)).exitStatus)
        assertNull(snapshot(state = TerminalState.Idle(1L)).exitStatus)
        assertNull(snapshot(state = TerminalState.Busy(0L, 0L)).exitStatus)
        assertNull(snapshot(state = TerminalState.NoSession).exitStatus)
    }

    @Test
    fun `exitStatus survives a null code`() {
        val s = snapshot(state = TerminalState.Dead(null))
        assertNull(s.exitStatus)
        assertTrue(s.state is TerminalState.Dead)
    }

    @Test
    fun `isFullScreenApp follows the alternate buffer flag`() {
        assertTrue(snapshot(isAlt = true).isFullScreenApp)
        assertFalse(snapshot(isAlt = false).isFullScreenApp)
    }

    @Test
    fun `snapshot is a value type`() {
        assertEquals(snapshot(), snapshot())
        assertFalse(snapshot() == snapshot(visible = "different"))
    }

    @Test
    fun `lastLine strips carriage returns at the line end`() {
        val s = snapshot(visible = "progress 50%\r\nprogress 100%\r\n")
        assertEquals("progress 100%", s.lastLine)
    }
}
