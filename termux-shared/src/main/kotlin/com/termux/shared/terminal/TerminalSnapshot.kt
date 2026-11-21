package com.termux.shared.terminal

/**
 * An immutable, structured view of what a terminal session currently shows.
 *
 * A snapshot contains facts only. It never carries a verdict such as "the command finished" or
 * "this looks like a confirmation prompt" — callers decide what the facts mean.
 *
 * @property state the derived session state at capture time.
 * @property visibleText the portion of the screen the user is actually looking at.
 * @property scrollbackTail the tail of the scrollback buffer, for context that has scrolled off screen.
 * @property cursorRow cursor row, in the emulator's external coordinate system (0 is the top visible row).
 * @property cursorCol cursor column.
 * @property rows number of visible screen rows.
 * @property cols number of visible screen columns.
 * @property title the terminal title as set by the shell via OSC 0/1/2, if any.
 * @property cwd the shell process' working directory, if it could be resolved.
 * @property isAltBuffer whether the alternate screen buffer is active (vim, less, top, ...).
 * @property isTruncated whether any of the text fields had to be truncated to honour the size budget.
 */
data class TerminalSnapshot(
    val state: TerminalState,
    val visibleText: String,
    val scrollbackTail: String,
    val cursorRow: Int,
    val cursorCol: Int,
    val rows: Int,
    val cols: Int,
    val title: String? = null,
    val cwd: String? = null,
    val isAltBuffer: Boolean = false,
    val isTruncated: Boolean = false,
) {
    /** True when the session is showing a full-screen alternate-buffer application. */
    val isFullScreenApp: Boolean get() = isAltBuffer

    /** The last non-blank line of the visible screen, or an empty string. */
    val lastLine: String
        get() = visibleText.lineSequence().lastOrNull { it.isNotBlank() }?.trimEnd().orEmpty()

    /** The last line of the whole session, preferring scrollback when the screen itself is blank. */
    val lastSessionLine: String
        get() = lastLine.ifEmpty {
            scrollbackTail.lineSequence().lastOrNull { it.isNotBlank() }?.trimEnd().orEmpty()
        }

    /** Exit status when the session is dead, otherwise null. */
    val exitStatus: Int?
        get() = (state as? TerminalState.Dead)?.exitStatus
}
