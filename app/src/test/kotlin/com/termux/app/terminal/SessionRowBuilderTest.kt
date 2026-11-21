package com.termux.app.terminal

import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the session list's row model.
 *
 * The list is the only place a user sees every session at once, so what a row says — and what it leaves
 * out — is worth pinning: a label that runs everything into one sentence was the reason it became hard
 * to scan.
 */
class SessionRowBuilderTest {

    private fun info(
        id: String = "s1",
        title: String = "",
        isRunning: Boolean = true,
        exitStatus: Int? = null,
        cwd: String? = "/data/data/com.termux/files/home",
        state: TerminalState = TerminalState.Idle(1000L),
    ) = SessionInfo(
        id = id,
        title = title,
        pid = 42,
        isRunning = isRunning,
        exitStatus = exitStatus,
        cwd = cwd,
        isAltBuffer = false,
        state = state,
    )

    @Test
    fun `rows are numbered from one`() {
        val rows = SessionRowBuilder.build(
            listOf(info(), info(id = "s2")),
            names = listOf("main", "build"),
            currentIndex = 0,
        )
        assertEquals(listOf(1, 2), rows.map { it.index })
    }

    @Test
    fun `a session without a name gets a readable placeholder`() {
        val rows = SessionRowBuilder.build(
            listOf(info()),
            names = listOf(null),
            currentIndex = -1,
        )
        assertEquals("Session 1", rows[0].name)
    }

    @Test
    fun `a blank name is treated as unset`() {
        val rows = SessionRowBuilder.build(
            listOf(info()),
            names = listOf("   "),
            currentIndex = -1,
        )
        assertEquals("Session 1", rows[0].name)
    }

    @Test
    fun `the assigned name wins over the shell title`() {
        val rows = SessionRowBuilder.build(
            listOf(info(title = "~/project")),
            names = listOf("main"),
            currentIndex = -1,
        )
        assertEquals("main", rows[0].name)
        // The title moves to the subtitle instead of being dropped.
        assertEquals("~/project", rows[0].title)
    }

    @Test
    fun `a title equal to the name is not repeated`() {
        val rows = SessionRowBuilder.build(
            listOf(info(title = "main")),
            names = listOf("main"),
            currentIndex = -1,
        )
        assertEquals(null, rows[0].title)
    }

    @Test
    fun `the current session is marked`() {
        val rows = SessionRowBuilder.build(
            listOf(info(), info(id = "s2")),
            names = listOf("main", "build"),
            currentIndex = 1,
        )
        assertFalse(rows[0].isCurrent)
        assertTrue(rows[1].isCurrent)
    }

    @Test
    fun `a dead session is flagged and loses its running status`() {
        val rows = SessionRowBuilder.build(
            listOf(info(isRunning = false, exitStatus = 127, state = TerminalState.Dead(127))),
            names = listOf("gone"),
            currentIndex = -1,
        )
        assertTrue(rows[0].isError)
        assertFalse(rows[0].isRunning)
        assertTrue("exit code should be visible", rows[0].statusLabel.contains("127"))
    }

    @Test
    fun `a busy session reports running`() {
        val rows = SessionRowBuilder.build(
            listOf(info(state = TerminalState.Busy(0L, 0L))),
            names = listOf("build"),
            currentIndex = -1,
        )
        assertEquals("running", rows[0].statusLabel)
        assertTrue(rows[0].isRunning)
    }

    @Test
    fun `an idle session is not marked as running`() {
        // The distinction that a rendered screenshot caught: a live shell sitting at a prompt is Idle,
        // not running. Treating the two the same drew an active status rule beside every row.
        val rows = SessionRowBuilder.build(
            listOf(info(isRunning = true, state = TerminalState.Idle(1000L))),
            names = listOf("main"),
            currentIndex = -1,
        )
        assertFalse("an idle prompt must not look active", rows[0].isRunning)
        assertEquals("idle", rows[0].statusLabel)
    }

    @Test
    fun `an idle session does not show a vertical bar`() {
        val rows = SessionRowBuilder.build(
            listOf(info()),
            names = listOf("main"),
            currentIndex = -1,
        )
        assertFalse("idle row must not contain a divider: ${rows[0].statusText}", rows[0].statusLabel.contains("│"))
        assertEquals("idle", rows[0].statusLabel)
    }

    @Test
    fun `a fullscreen session is marked`() {
        val rows = SessionRowBuilder.build(
            listOf(
                SessionInfo(
                    id = "s1",
                    title = "vim",
                    pid = 1,
                    isRunning = true,
                    exitStatus = null,
                    cwd = null,
                    isAltBuffer = true,
                    state = TerminalState.Idle(500L),
                ),
            ),
            names = listOf("edit"),
            currentIndex = -1,
        )
        assertTrue("a full-screen program should be flagged", rows[0].statusText.contains("fullscreen"))
    }

    @Test
    fun `subtitle joins title and directory`() {
        val rows = SessionRowBuilder.build(
            listOf(info(title = "vim", cwd = "~/src")),
            names = listOf("edit"),
            currentIndex = -1,
        )
        assertEquals("vim  ·  ~/src", rows[0].subtitle)
        assertTrue(rows[0].hasSubtitle)
    }

    @Test
    fun `subtitle is hidden when there is nothing to show`() {
        val rows = SessionRowBuilder.build(
            listOf(info(title = "", cwd = null)),
            names = listOf("main"),
            currentIndex = -1,
        )
        assertFalse(rows[0].hasSubtitle)
        assertEquals("", rows[0].subtitle)
    }

    @Test
    fun `a long directory is elided in the middle`() {
        val long = "/data/data/com.termux/files/home/some/deeply/nested/project"
        val rows = SessionRowBuilder.build(
            listOf(info(cwd = long)),
            names = listOf("main"),
            currentIndex = -1,
            maxCwdChars = 20,
        )
        assertTrue("should be shortened", rows[0].cwd!!.length <= 20)
        assertTrue("should keep the tail", rows[0].cwd!!.endsWith("project"))
    }

    @Test
    fun `fewer names than sessions does not throw`() {
        val rows = SessionRowBuilder.build(
            listOf(info(), info(id = "s2")),
            names = listOf("only-one"),
            currentIndex = -1,
        )
        assertEquals(2, rows.size)
        assertEquals("Session 2", rows[1].name)
    }
}
