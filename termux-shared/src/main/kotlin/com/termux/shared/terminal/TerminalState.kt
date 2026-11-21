package com.termux.shared.terminal

/**
 * The observable state of a terminal session.
 *
 * These states are derived purely from terminal mechanics ([com.termux.terminal.TerminalSession.isRunning],
 * the emulator's scroll counter, and optionally the foreground process group). They deliberately do **not**
 * attempt to guess what the shell prompt looks like, so they stay correct across bash/zsh/fish and any
 * custom `PS1`.
 *
 * Text-based heuristics such as "does the last line look like a `(y/n)` prompt" belong to consumers,
 * not to this type.
 */
sealed interface TerminalState {

    /** No session exists yet (still starting up, or every session was closed). */
    data object NoSession : TerminalState

    /** The shell process has exited. [exitStatus] is null when it could not be determined. */
    data class Dead(val exitStatus: Int?) : TerminalState

    /**
     * Output is still arriving, or it stopped arriving recently enough that we cannot yet call the
     * session quiet.
     *
     * @param sinceMs monotonic timestamp at which the session first became busy in this busy period.
     * @param lastOutputAtMs monotonic timestamp of the most recent observed output.
     */
    data class Busy(val sinceMs: Long, val lastOutputAtMs: Long) : TerminalState

    /**
     * No output for at least the configured silence threshold and the process is still alive.
     *
     * @param silenceMs how long the session has been quiet, in milliseconds.
     */
    data class Idle(val silenceMs: Long) : TerminalState
}

/** Whether the session is alive but quiet, i.e. likely sitting at a prompt. */
val TerminalState.isQuiet: Boolean
    get() = this is TerminalState.Idle

/** Whether the session can no longer receive input. */
val TerminalState.isTerminated: Boolean
    get() = this is TerminalState.Dead || this is TerminalState.NoSession
