package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for where the panel's bottom edge goes.
 *
 * The toolbar is the only way to type into the terminal, so a panel covering it would force closing the
 * assistant to run the command it just proposed.
 */
class PanelAnchorTest {

    @Test
    fun `a visible toolbar is kept clear`() {
        assertEquals(
            PanelAnchor.Bottom.ABOVE_TOOLBAR,
            PanelAnchor.bottom(toolbarVisible = true),
        )
    }

    @Test
    fun `without a toolbar the panel reaches the bottom`() {
        assertEquals(
            PanelAnchor.Bottom.SCREEN_BOTTOM,
            PanelAnchor.bottom(toolbarVisible = false),
        )
    }

    @Test
    fun `the decision is independent of how the panel was opened`() {
        // The anchor is re-evaluated on every show, because the toolbar can be toggled from settings
        // while the panel is mounted. This pins that the answer depends only on the toolbar.
        val on = (1..3).map { PanelAnchor.bottom(toolbarVisible = true) }.toSet()
        val off = (1..3).map { PanelAnchor.bottom(toolbarVisible = false) }.toSet()
        assertEquals(1, on.size)
        assertEquals(1, off.size)
    }
}
