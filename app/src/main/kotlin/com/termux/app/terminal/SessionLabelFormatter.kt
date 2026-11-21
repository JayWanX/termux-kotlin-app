package com.termux.app.terminal

import com.termux.shared.terminal.TerminalState

/**
 * Text helpers for the session list.
 *
 * Kept separate from the row builder so the wording can be unit-tested independently of layout: what a
 * row *says* about a session is the part users rely on, and a dead session that reads as healthy is worse
 * than no indicator at all.
 *
 * This previously also built a single-line label (`<glyph> <name> [<index>] <title> · <cwd>`) for a plain
 * text adapter. The list now renders structured rows, so that formatter was deleted rather than left
 * behind with tests covering a function nothing called.
 *
 * The glyph table went with it. The first version drew a `│` for idle sessions, which read as a divider
 * glued to the front of every name; replacing it with circles fixed the appearance but left the row
 * saying the same thing twice — a `●` glyph and the word "running" directly under it. The word is the
 * better half (it survives colour blindness, and `exit 127` is more precise than any symbol), so the
 * glyph column is gone and the row keeps colour plus text.
 */
object SessionLabelFormatter {

    /** A short word for the state, shown as the row's status line. */
    fun statusLabel(state: TerminalState): String = when (state) {
        TerminalState.NoSession -> "no session"
        is TerminalState.Busy -> "running"
        is TerminalState.Idle -> "idle"
        is TerminalState.Dead -> state.exitStatus?.let { "exit $it" } ?: "exited"
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
