package com.termux.app.terminal

import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState

/**
 * Formats a session row for the session list.
 *
 * Kept separate from the dialog so the formatting can be unit-tested: what a row says about a session
 * is the part users actually rely on, and it is easy to get subtly wrong (a dead session that looks
 * alive is worse than no indicator at all).
 */
object SessionLabelFormatter {

    /** Glyph shown before the name, chosen so it reads at a glance. */
    fun statusGlyph(state: TerminalState): String = when (state) {
        TerminalState.NoSession -> "○"
        is TerminalState.Busy -> "●"
        is TerminalState.Idle -> "│"
        is TerminalState.Dead -> "×"
    }

    /**
     * Builds the label for one session.
     *
     * Format: `<glyph> <name> [<index>] <title> · <cwd>`
     *
     * The index is included because the "More" menu and keyboard shortcuts refer to sessions by number.
     *
     * @param index one-based position in the session list.
     * @param info the session snapshot.
     * @param name the user-assigned name, which may be blank.
     * @param title the terminal title reported by the shell, which may be blank.
     * @param maxCwdChars how much of the working directory to show before eliding the middle.
     */
    fun format(
        index: Int,
        info: SessionInfo,
        name: String?,
        title: String?,
        maxCwdChars: Int = 40,
    ): String {
        val displayName = name?.takeIf { it.isNotBlank() } ?: "Session"
        val builder = StringBuilder()
        builder.append(statusGlyph(info.state))
        builder.append(' ')
        builder.append(displayName)
        builder.append(" [").append(index).append(']')

        if (info.isRunning && info.state is TerminalState.Dead) {
            // Inconsistent inputs should not produce a row that looks healthy.
            builder.append(" (?)")
        }
        if (!info.isRunning) {
            val status = info.exitStatus
            builder.append(if (status != null) " (exit $status)" else " (exited)")
        }

        title?.takeIf { it.isNotBlank() && it != name }?.let {
            builder.append(' ').append(it)
        }

        info.cwd?.takeIf { it.isNotBlank() }?.let { cwd ->
            builder.append(" · ").append(elideMiddle(cwd, maxCwdChars))
        }

        if (info.isAltBuffer) {
            builder.append(" [fullscreen]")
        }

        return builder.toString()
    }

    /**
     * Shortens [path] by replacing its middle with an ellipsis.
     *
     * The tail is kept because the last path segments carry the most meaning in practice
     * (`…/projects/app` says more than `/data/data/com.termux…`).
     */
    fun elideMiddle(path: String, maxChars: Int): String {
        if (maxChars <= 3) return path.take(maxChars)
        if (path.length <= maxChars) return path

        val keep = maxChars - 1
        val tailLength = keep - keep / 3
        val headLength = keep - tailLength
        return path.take(headLength) + "…" + path.takeLast(tailLength)
    }
}
