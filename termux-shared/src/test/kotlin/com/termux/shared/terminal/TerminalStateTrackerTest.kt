package com.termux.shared.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStateTrackerTest {

    private val threshold = 500L

    @Test
    fun `dead process reports dead with exit status`() {
        val tracker = TerminalStateTracker(threshold)
        val state = tracker.sample(isRunning = false, scrollCounter = 0, nowMs = 1000L, exitStatus = 3)
        assertEquals(TerminalState.Dead(3), state)
    }

    @Test
    fun `first sample is busy because output baseline is unknown`() {
        val tracker = TerminalStateTracker(threshold)
        val state = tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1000L)
        assertTrue(state is TerminalState.Busy)
    }

    @Test
    fun `changing scroll counter keeps the session busy`() {
        val tracker = TerminalStateTracker(threshold)
        tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1000L)

        val state = tracker.sample(isRunning = true, scrollCounter = 11, nowMs = 1100L)
        assertTrue(state is TerminalState.Busy)
        assertEquals(1100L, (state as TerminalState.Busy).lastOutputAtMs)
    }

    @Test
    fun `silence past the threshold becomes idle`() {
        val tracker = TerminalStateTracker(threshold)
        tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1000L)

        // Still within the threshold: busy.
        assertTrue(tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1200L) is TerminalState.Busy)

        // Past the threshold: idle.
        val state = tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1600L)
        assertTrue(state is TerminalState.Idle)
        assertEquals(600L, (state as TerminalState.Idle).silenceMs)
    }

    @Test
    fun `output after an idle period returns to busy`() {
        val tracker = TerminalStateTracker(threshold)
        tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1000L)
        tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1600L)

        val state = tracker.sample(isRunning = true, scrollCounter = 12, nowMs = 1700L)
        assertTrue(state is TerminalState.Busy)
    }

    @Test
    fun `attaching to an already quiet session goes idle after the threshold`() {
        val tracker = TerminalStateTracker(threshold)
        tracker.sample(isRunning = true, scrollCounter = 42, nowMs = 10_000L)
        val state = tracker.sample(isRunning = true, scrollCounter = 42, nowMs = 10_000L + threshold)
        assertTrue(state is TerminalState.Idle)
    }

    @Test
    fun `process exit resets accumulated state`() {
        val tracker = TerminalStateTracker(threshold)
        tracker.sample(isRunning = true, scrollCounter = 10, nowMs = 1000L)
        tracker.sample(isRunning = false, scrollCounter = 10, nowMs = 2000L, exitStatus = 1)

        // A brand new session starts from scratch, so the first sample is busy again.
        val state = tracker.sample(isRunning = true, scrollCounter = 99, nowMs = 3000L)
        assertTrue(state is TerminalState.Busy)
    }

    @Test
    fun `idle silence is measured from when we started watching`() {
        val tracker = TerminalStateTracker(threshold)
        // Nothing was emitted for a long time before we attached; that must not count against us.
        tracker.sample(isRunning = true, scrollCounter = 5, nowMs = 100_000L)
        assertTrue(tracker.sample(isRunning = true, scrollCounter = 5, nowMs = 100_200L) is TerminalState.Busy)
        assertTrue(tracker.sample(isRunning = true, scrollCounter = 5, nowMs = 100_500L) is TerminalState.Idle)
    }

    @Test
    fun `state helpers classify quiet and terminated`() {
        assertTrue(TerminalState.Idle(600L).isQuiet)
        assertFalse(TerminalState.Busy(0L, 0L).isQuiet)
        assertTrue(TerminalState.Dead(null).isTerminated)
        assertTrue(TerminalState.NoSession.isTerminated)
        assertFalse(TerminalState.Idle(0L).isTerminated)
    }

    @Test
    fun `dead without a known exit status keeps null`() {
        val tracker = TerminalStateTracker(threshold)
        val state = tracker.sample(isRunning = false, scrollCounter = 0, nowMs = 1L, exitStatus = null)
        assertEquals(TerminalState.Dead(null), state)
        assertNull((state as TerminalState.Dead).exitStatus)
    }
}
