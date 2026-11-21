package com.termux.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the composer's send-button alignment.
 *
 * The rule is a measurement, so the cases that matter are the boundaries: a single line must centre, a
 * wrapped line must drop to the bottom, and an unmeasured field must not make the button jump once.
 */
class ComposerLayoutTest {

    private val density = 3f

    @Test
    fun `a single line field centres the button`() {
        // Material's outlined field is 56dp tall with one line of text.
        assertTrue(ComposerLayout.sendButtonCentered((56 * density).toInt(), density))
    }

    @Test
    fun `a wrapped field drops the button to the bottom`() {
        // Two lines: 56dp minimum plus roughly a line of text.
        assertFalse(ComposerLayout.sendButtonCentered((84 * density).toInt(), density))
    }

    @Test
    fun `an unmeasured field is treated as one line`() {
        // Otherwise the button would render bottom-aligned and jump on the first layout pass.
        assertTrue(ComposerLayout.sendButtonCentered(0, density))
    }

    @Test
    fun `the rule scales with density`() {
        // The same physical size must give the same answer at any density.
        assertTrue(ComposerLayout.sendButtonCentered((56 * 1f).toInt(), 1f))
        assertTrue(ComposerLayout.sendButtonCentered((56 * 4f).toInt(), 4f))
        assertFalse(ComposerLayout.sendButtonCentered((100 * 1f).toInt(), 1f))
    }

    @Test
    fun `a degenerate density does not crash or flip the default`() {
        assertTrue(ComposerLayout.sendButtonCentered(500, 0f))
    }
}
