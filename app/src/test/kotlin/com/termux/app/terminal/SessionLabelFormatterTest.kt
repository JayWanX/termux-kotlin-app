package com.termux.app.terminal

import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the session row label.
 *
 * The row is what tells a user whether a session is still working or has died, so the cases that
 * matter are the ones where a session is *not* healthy.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLabelFormatterTest {

    private fun info(
        state: TerminalState = TerminalState.Idle(1000L),
        isRunning: Boolean = true,
        exitStatus: Int? = null,
        cwd: String? = null,
        isAltBuffer: Boolean = false,
        title: String = "",
        pid: Int = 100,
    ) = SessionInfo(
        id = "s$pid",
        title = title,
        pid = pid,
        isRunning = isRunning,
        exitStatus = exitStatus,
        cwd = cwd,
        isAltBuffer = isAltBuffer,
        state = state,
    )

    @Test
    fun `idle running session shows glyph name and index`() {
        val label = SessionLabelFormatter.format(1, info(), "main", null)
        assertTrue(label.startsWith("│ "))
        assertTrue(label.contains("main"))
        assertTrue(label.contains("[1]"))
    }

    @Test
    fun `busy session uses a filled glyph`() {
        val label = SessionLabelFormatter.format(
            2,
            info(state = TerminalState.Busy(0L, 0L)),
            "build",
            null,
        )
        assertTrue(label.startsWith("● "))
        assertTrue(label.contains("[2]"))
    }

    @Test
    fun `dead session shows glyph and exit status`() {
        val label = SessionLabelFormatter.format(
            1,
            info(
                state = TerminalState.Dead(130),
                isRunning = false,
                exitStatus = 130,
            ),
            "gone",
            null,
        )
        assertTrue(label.startsWith("× "))
        assertTrue(label.contains("exit 130"))
    }

    @Test
    fun `dead session without a status still reads as exited`() {
        val label = SessionLabelFormatter.format(
            1,
            info(state = TerminalState.Dead(null), isRunning = false, exitStatus = null),
            "gone",
            null,
        )
        assertTrue(label.contains("exited"))
        assertFalse(label.contains("exit "))
    }

    @Test
    fun `blank name falls back to a generic label`() {
        val label = SessionLabelFormatter.format(3, info(), null, null)
        assertTrue(label.contains("Session"))
        assertTrue(label.contains("[3]"))
    }

    @Test
    fun `title is appended when distinct from the name`() {
        val label = SessionLabelFormatter.format(1, info(title = "vim"), "main", "vim")
        assertTrue(label.contains("vim"))
        assertEquals(1, label.split("vim").size - 1)
    }

    @Test
    fun `title equal to the name is not duplicated`() {
        val label = SessionLabelFormatter.format(1, info(title = "main"), "main", "main")
        assertEquals(1, label.split("main").size - 1)
    }

    @Test
    fun `cwd is appended when known`() {
        val label = SessionLabelFormatter.format(1, info(cwd = "/data/data/com.termux/files/home"), "main", null)
        assertTrue(label.contains("·"))
        assertTrue(label.contains("home"))
    }

    @Test
    fun `fullscreen sessions are marked`() {
        val label = SessionLabelFormatter.format(1, info(isAltBuffer = true), "vim", null)
        assertTrue(label.contains("fullscreen"))
    }

    @Test
    fun `inconsistent running plus dead state is flagged`() {
        // Should never happen, but a row that silently looks healthy would be worse.
        val label = SessionLabelFormatter.format(
            1,
            info(state = TerminalState.Dead(1), isRunning = true, exitStatus = 1),
            "odd",
            null,
        )
        assertTrue(label.contains("(?)"))
    }

    @Test
    fun `elide keeps the tail which carries the meaning`() {
        val path = "/data/data/com.termux/files/home/projects/my-app"
        val elided = SessionLabelFormatter.elideMiddle(path, 30)
        assertTrue(elided.length <= 30)
        assertTrue(elided.contains("…"))
        assertTrue(elided.endsWith("my-app"))
    }

    @Test
    fun `short paths are not elided`() {
        assertEquals("/home", SessionLabelFormatter.elideMiddle("/home", 30))
    }

    @Test
    fun `elide handles a tiny budget without throwing`() {
        // Below four characters there is no room for an ellipsis, so it is a plain prefix.
        assertEquals("/v", SessionLabelFormatter.elideMiddle("/very/long/path", 2))
        assertEquals("/ve", SessionLabelFormatter.elideMiddle("/very/long/path", 3))
    }

    @Test
    fun `glyphs differ per state`() {
        val glyphs = setOf(
            SessionLabelFormatter.statusGlyph(TerminalState.NoSession),
            SessionLabelFormatter.statusGlyph(TerminalState.Busy(0, 0)),
            SessionLabelFormatter.statusGlyph(TerminalState.Idle(0)),
            SessionLabelFormatter.statusGlyph(TerminalState.Dead(0)),
        )
        assertEquals("each state needs a distinct glyph", 4, glyphs.size)
    }
}

/** Tests for the status strip's pure formatting/model layer. */
@RunWith(RobolectricTestRunner::class)
class TerminalStatusViewTest {

    private val context = RuntimeEnvironment.getApplication()
    private val palette = TerminalStatusView.Palette(1, 2, 3, 4)

    @Test
    fun `idle hides the strip`() {
        assertNull(
            TerminalStatusView.describe(TerminalState.Idle(500L), "main", null, context, palette),
        )
    }

    @Test
    fun `no session hides the strip`() {
        assertNull(
            TerminalStatusView.describe(TerminalState.NoSession, null, null, context, palette),
        )
    }

    @Test
    fun `busy shows running text`() {
        val model = TerminalStatusView.describe(
            TerminalState.Busy(0L, 0L),
            "build",
            12_000L,
            context,
            palette,
        )
        assertTrue(model!!.text.contains("Running"))
        assertTrue(model.text.contains("build"))
        assertTrue(model.text.contains("12s"))
        assertEquals(1, model.textColor)
        assertEquals(2, model.backgroundColor)
    }

    @Test
    fun `busy without elapsed time omits the hint`() {
        val model = TerminalStatusView.describe(
            TerminalState.Busy(0L, 0L),
            "build",
            null,
            context,
            palette,
        )
        assertFalse(model!!.text.contains("s ·"))
    }

    @Test
    fun `dead shows exit status`() {
        val model = TerminalStatusView.describe(
            TerminalState.Dead(130),
            "gone",
            null,
            context,
            palette,
        )
        assertTrue(model!!.text.contains("Exited"))
        assertTrue(model.text.contains("130"))
        assertEquals(3, model.textColor)
        assertEquals(4, model.backgroundColor)
    }

    @Test
    fun `dead without status omits the parenthesis`() {
        val model = TerminalStatusView.describe(
            TerminalState.Dead(null),
            null,
            null,
            context,
            palette,
        )
        assertFalse(model!!.text.contains("("))
    }

    @Test
    fun `elapsed formatting scales with magnitude`() {
        assertEquals("500ms", TerminalStatusView.formatElapsed(500))
        assertEquals("5s", TerminalStatusView.formatElapsed(5_000))
        assertEquals("2m 5s", TerminalStatusView.formatElapsed(125_000))
        assertEquals("1h 1m", TerminalStatusView.formatElapsed(3_660_000))
    }
}
