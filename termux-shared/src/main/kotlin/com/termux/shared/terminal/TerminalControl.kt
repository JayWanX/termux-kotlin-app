package com.termux.shared.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * The single entry point for observing and driving a terminal session.
 *
 * Everything that needs to know "what is the terminal doing" or "make the terminal do something"
 * goes through this interface instead of reaching into `TerminalSession` / `TerminalEmulator` /
 * `TerminalView` directly. That keeps the knowledge in one place and lets optional features (command
 * completion notifications, an assistant panel, accessibility helpers) share the same machinery.
 *
 * Implementations must be safe to call from any thread. Blocking work is done off the caller's thread.
 */
interface TerminalControl {

    /** The current session state. Emits on every state transition. */
    val state: StateFlow<TerminalState>

    /** True when there is a session that can accept input. */
    fun isAttached(): Boolean

    /**
     * Captures a structured view of the current session.
     *
     * This is suspending because the terminal screen buffer may only be read from the main thread;
     * implementations marshal accordingly.
     *
     * @param maxChars total budget for the text fields. Implementations clamp this to a sane range.
     */
    suspend fun snapshot(maxChars: Int = DEFAULT_MAX_CHARS): TerminalSnapshot

    /**
     * Writes [text] to the session.
     *
     * When [enter] is true a carriage return is appended, because a terminal expects `\r` rather than
     * `\n` to submit a line. Newlines inside [text] are normalised by the implementation.
     */
    suspend fun send(text: String, enter: Boolean = true)

    /** Sends a single control key. */
    suspend fun sendKey(key: TerminalKey)

    /**
     * Suspends until the session becomes quiet or terminated, or [timeoutMs] elapses.
     *
     * Reaching the timeout is a normal outcome, not an error: the returned state will simply still be
     * [TerminalState.Busy]. Callers that need an exact verdict should inspect [TerminalSnapshot] or use
     * [sendCommandWithSentinel].
     */
    suspend fun awaitIdle(timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS): TerminalState

    /**
     * Runs [command] and waits for it to finish using a sentinel marker, which yields the real exit
     * status even for commands that produce no output (`sleep 30`) or end without a newline.
     *
     * The command runs in the current shell (not a subshell) so that `cd`, exported variables and other
     * shell state persist exactly as if the user had typed it.
     */
    suspend fun sendCommandWithSentinel(
        command: String,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): CommandOutcome

    /** The sessions currently known to the app. */
    fun sessions(): List<SessionInfo>

    /**
     * Searches the current session's scrollback.
     *
     * Returns an empty list when there is no session or the query is blank, rather than throwing, so a
     * search box can call it on every keystroke.
     */
    suspend fun searchScrollback(query: String, ignoreCase: Boolean = true): List<TranscriptSearch.Match>

    /** The full transcript of the current session, or an empty string when unavailable. */
    suspend fun transcript(): String

    /** Switches the visible session. Returns false when the id is unknown. */
    fun selectSession(sessionId: String): Boolean

    companion object {
        const val DEFAULT_MAX_CHARS: Int = 8000
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * The result of [TerminalControl.sendCommandWithSentinel].
 *
 * @property exitStatus the command's exit status, or null when the sentinel was never observed
 *   (for example because the command was interrupted or the timeout expired).
 * @property state the session state when the call returned.
 * @property snapshot the terminal contents at the moment the sentinel was seen, minus the sentinel line.
 */
data class CommandOutcome(
    val exitStatus: Int?,
    val state: TerminalState,
    val snapshot: TerminalSnapshot,
) {
    /** True when the sentinel was observed, so [exitStatus] is meaningful. */
    val completed: Boolean get() = exitStatus != null

    /** True when the command ran to completion and reported success. */
    val succeeded: Boolean get() = exitStatus == 0
}

/**
 * A snapshot of one session, cheap enough to build for every session on each UI refresh.
 */
data class SessionInfo(
    val id: String,
    val title: String,
    val pid: Int,
    val isRunning: Boolean,
    val exitStatus: Int?,
    val cwd: String?,
    val isAltBuffer: Boolean,
    val state: TerminalState,
)
