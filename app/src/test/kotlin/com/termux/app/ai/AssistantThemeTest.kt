package com.termux.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.termux.shared.theme.NightMode

/**
 * Tests for how the assistant picks its palette.
 *
 * Termux lets the user pin the app theme independently of the system. The panel previously followed
 * only the system, so a dark app on a light-system phone rendered a bright panel over a dark terminal.
 */
class AssistantThemeTest {

    @Test
    fun `app pinned to dark renders dark even on a light system`() {
        assertTrue(AssistantTheme.isDark(NightMode.TRUE, systemIsDark = false))
    }

    @Test
    fun `app pinned to light renders light even on a dark system`() {
        assertFalse(AssistantTheme.isDark(NightMode.FALSE, systemIsDark = true))
    }

    @Test
    fun `following the system uses the system value`() {
        assertTrue(AssistantTheme.isDark(NightMode.SYSTEM, systemIsDark = true))
        assertFalse(AssistantTheme.isDark(NightMode.SYSTEM, systemIsDark = false))
    }
}
