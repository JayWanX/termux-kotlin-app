package com.termux.shared.terminal

/**
 * Derives [TerminalState] from a small, testable set of observations.
 *
 * The tracker is deliberately free of Android types: it consumes plain values (a scroll counter and a
 * running flag) that the app layer reads from `TerminalSession` / `TerminalEmulator`. That keeps the
 * interesting logic unit-testable on the JVM.
 *
 * The algorithm is quiescence detection:
 *
 * 1. If the process is gone, the session is [TerminalState.Dead].
 * 2. If the scroll counter changed since the previous sample, output is arriving: [TerminalState.Busy].
 * 3. Otherwise the session has been silent for `now - lastOutputAt`; once that exceeds
 *    [silenceThresholdMs] it is [TerminalState.Idle].
 *
 * A newly attached session starts out [TerminalState.Idle] after the threshold, which is what we want:
 * attaching to an already-quiet shell should not report a busy session.
 *
 * Known limitation: a command that runs silently (for example `sleep 30`) is indistinguishable from an
 * idle prompt by output alone. Callers that need an exact verdict should either use a sentinel
 * ([TerminalControl.sendCommandWithSentinel]) or additionally consult [ForegroundProcessProbe].
 */
class TerminalStateTracker(
    val silenceThresholdMs: Long = DEFAULT_SILENCE_THRESHOLD_MS,
) {

    private var lastScrollCounter: Int = Int.MIN_VALUE
    private var lastOutputAtMs: Long = 0L
    private var busySinceMs: Long = 0L
    private var startedAtMs: Long = 0L
    private var hasSample: Boolean = false

    /**
     * Feeds one observation and returns the resulting state.
     *
     * @param isRunning whether the shell process is still alive.
     * @param scrollCounter a monotonically increasing counter of output, typically
     *   `TerminalEmulator.getScrollCounter()`. Any change means output happened.
     * @param nowMs a monotonic timestamp in milliseconds.
     * @param exitStatus the process exit status, used only when [isRunning] is false.
     */
    fun sample(
        isRunning: Boolean,
        scrollCounter: Int,
        nowMs: Long,
        exitStatus: Int? = null,
    ): TerminalState {
        if (!isRunning) {
            reset()
            return TerminalState.Dead(exitStatus)
        }

        if (!hasSample) {
            hasSample = true
            startedAtMs = nowMs
            lastScrollCounter = scrollCounter
            lastOutputAtMs = nowMs
            return TerminalState.Busy(nowMs, nowMs)
        }

        if (scrollCounter != lastScrollCounter) {
            lastScrollCounter = scrollCounter
            lastOutputAtMs = nowMs
            if (busySinceMs == 0L) busySinceMs = nowMs
            return TerminalState.Busy(busySinceMs, lastOutputAtMs)
        }

        // Silence is measured from the later of "last output" and "we started watching", so that
        // attaching to a quiet shell does not have to wait for a full threshold that already elapsed
        // before we were listening.
        val reference = maxOf(lastOutputAtMs, startedAtMs)
        val silence = nowMs - reference
        return if (silence >= silenceThresholdMs) {
            busySinceMs = 0L
            TerminalState.Idle(silence)
        } else {
            TerminalState.Busy(maxOf(busySinceMs, reference), lastOutputAtMs)
        }
    }

    /** Clears accumulated state, for example when the observed session changes. */
    fun reset() {
        lastScrollCounter = Int.MIN_VALUE
        lastOutputAtMs = 0L
        busySinceMs = 0L
        startedAtMs = 0L
        hasSample = false
    }

    companion object {
        /** How long the session must be silent before it counts as idle. */
        const val DEFAULT_SILENCE_THRESHOLD_MS: Long = 500L
    }
}
