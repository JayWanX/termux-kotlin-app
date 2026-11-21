package com.termux.shared.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSentinelTest {

    @Test
    fun `command runs in the current shell so state changes persist`() {
        val script = CommandSentinel.build("cd /data", "t1")
        assertTrue("must use a group, not a subshell", script.startsWith("{ cd /data"))
        assertTrue("must close the group", script.contains("}; printf"))
    }

    @Test
    fun `sentinel prints even when the command fails`() {
        val script = CommandSentinel.build("false", "t1")
        // The group must not be chained with && , otherwise a failing command would skip the marker.
        assertFalse(script.contains("} && printf"))
        assertTrue(script.contains("} ; printf") || script.contains("}; printf"))
    }

    @Test
    fun `sentinel emits a leading newline before the marker`() {
        val script = CommandSentinel.build("printf no-newline", "t1")
        assertTrue(script.contains("printf '\\n" + CommandSentinel.PREFIX))
    }

    @Test
    fun `exit status is captured immediately after the group`() {
        val script = CommandSentinel.build("ls", "t1")
        assertTrue(script.trimEnd().endsWith("$?"))
    }

    @Test
    fun `marker contains the sanitized token`() {
        val marker = CommandSentinel.markerFor("abc-123_x")
        assertEquals("__TERMUX_CMD_DONE_abc-123_x__", marker)
    }

    @Test
    fun `token is sanitized against injection`() {
        val marker = CommandSentinel.markerFor("a'; rm -rf / #")
        assertFalse(marker.contains("'"))
        assertFalse(marker.contains(";"))
        assertFalse(marker.contains(" "))
        assertFalse(marker.contains("#"))
    }

    @Test
    fun `empty token falls back to a stable marker`() {
        assertEquals("__TERMUX_CMD_DONE_0__", CommandSentinel.markerFor("!!!"))
    }

    @Test
    fun `parses zero exit status`() {
        val marker = CommandSentinel.markerFor("t1")
        val text = "output line\n$marker" + "0" + "$marker\n"
        assertEquals(0, CommandSentinel.parseExitStatus(text, "t1"))
    }

    @Test
    fun `parses non zero exit status`() {
        val marker = CommandSentinel.markerFor("t1")
        val text = "boom\n$marker" + "127" + "$marker\n"
        assertEquals(127, CommandSentinel.parseExitStatus(text, "t1"))
    }

    @Test
    fun `returns null when marker is absent`() {
        assertNull(CommandSentinel.parseExitStatus("still running...", "t1"))
    }

    @Test
    fun `uses the last marker when several are present`() {
        val marker = CommandSentinel.markerFor("t1")
        val text = "${marker}2${marker}\nthen more\n${marker}0${marker}\n"
        assertEquals(0, CommandSentinel.parseExitStatus(text, "t1"))
    }

    @Test
    fun `tokens are isolated from each other`() {
        val text = "${CommandSentinel.markerFor("a")}1${CommandSentinel.markerFor("a")}\n"
        assertNotNull(CommandSentinel.parseExitStatus(text, "a"))
        assertNull(CommandSentinel.parseExitStatus(text, "b"))
    }

    @Test
    fun `strip removes marker lines but keeps output`() {
        val marker = CommandSentinel.markerFor("t1")
        val text = "hello\n$marker" + "0" + "$marker\nworld"
        val stripped = CommandSentinel.strip(text, "t1")
        assertFalse(stripped.contains(marker))
        assertTrue(stripped.contains("hello"))
        assertTrue(stripped.contains("world"))
    }

    @Test
    fun `strip is a no op when no marker is present`() {
        val text = "just output"
        assertEquals(text, CommandSentinel.strip(text, "t1"))
    }
}
