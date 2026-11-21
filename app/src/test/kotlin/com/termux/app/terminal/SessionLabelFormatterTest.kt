package com.termux.app.terminal

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
 * Tests for the session list's wording.
 *
 * The row is what tells a user whether a session is still working or has died, so the cases that matter
 * are the ones where a session is *not* healthy. Row composition itself is covered by
 * [SessionRowBuilderTest].
 */
@RunWith(RobolectricTestRunner::class)
class SessionLabelFormatterTest {

    @Test
    fun `idle reads as idle`() {
        assertEquals("idle", SessionLabelFormatter.statusLabel(TerminalState.Idle(1000L)))
    }

    @Test
    fun `busy reads as running`() {
        assertEquals("running", SessionLabelFormatter.statusLabel(TerminalState.Busy(0L, 0L)))
    }

    @Test
    fun `a dead session reports its exit code`() {
        assertEquals("exit 127", SessionLabelFormatter.statusLabel(TerminalState.Dead(127)))
    }

    @Test
    fun `a dead session without an exit code still says something`() {
        assertEquals("exited", SessionLabelFormatter.statusLabel(TerminalState.Dead(null)))
    }

    @Test
    fun `no status glyph table remains`() {
        // The glyphs were removed along with the single-line formatter: the row states the status in
        // words, and a symbol beside it said the same thing twice. This guards against reintroducing a
        // second, competing representation.
        val methods = SessionLabelFormatter::class.java.declaredMethods.map { it.name }
        assertFalse(
            "statusGlyph should no longer exist: $methods",
            methods.contains("statusGlyph"),
        )
    }

    // ------------------------------------------------------------------ elide

    @Test
    fun `short paths are returned unchanged`() {
        assertEquals("/home", SessionLabelFormatter.elideMiddle("/home", 30))
    }

    @Test
    fun `long paths keep their tail`() {
        val path = "/data/data/com.termux/files/home/projects/app"
        val elided = SessionLabelFormatter.elideMiddle(path, 30)
        assertTrue("should be shortened", elided.length <= 30)
        assertTrue("should end with the project directory", elided.endsWith("app"))
        assertTrue("should mark the omission", elided.contains("…"))
    }

    @Test
    fun `elide keeps the leading directory`() {
        val elided = SessionLabelFormatter.elideMiddle("/data/data/com.termux/files/home/x", 20)
        assertTrue(elided.startsWith("/"))
    }

    @Test
    fun `elide handles a tiny budget without throwing`() {
        assertEquals("/v", SessionLabelFormatter.elideMiddle("/very/long/path", 2))
        assertEquals("/ve", SessionLabelFormatter.elideMiddle("/very/long/path", 3))
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
            TerminalState.Busy(0L, 0L), "main", 3_000L, context, palette,
        )
        assertTrue(model!!.text.contains("main"))
        assertEquals(palette.runningText, model.textColor)
        assertEquals(palette.runningBg, model.backgroundColor)
    }

    @Test
    fun `busy without elapsed time omits the hint`() {
        val model = TerminalStatusView.describe(
            TerminalState.Busy(0L, 0L), "main", null, context, palette,
        )
        // No elapsed figure should appear when the caller did not supply one.
        assertTrue(model!!.text.contains("main"))
        assertFalse(model.text.contains("ms"))
    }

    @Test
    fun `dead shows exit status`() {
        val model = TerminalStatusView.describe(
            TerminalState.Dead(1), "gone", null, context, palette,
        )
        assertTrue(model!!.text.contains("1"))
        assertEquals(palette.exitedText, model.textColor)
        assertEquals(palette.exitedBg, model.backgroundColor)
    }

    @Test
    fun `dead without status omits the parenthesis`() {
        val model = TerminalStatusView.describe(
            TerminalState.Dead(null), "gone", null, context, palette,
        )
        assertFalse(model!!.text.contains("("))
    }

    @Test
    fun `elapsed formatting scales with magnitude`() {
        assertEquals("500ms", TerminalStatusView.formatElapsed(500))
        assertEquals("3s", TerminalStatusView.formatElapsed(3_000))
        assertTrue(TerminalStatusView.formatElapsed(90_000).contains("m"))
        assertTrue(TerminalStatusView.formatElapsed(7_200_000).contains("h"))
    }
}
