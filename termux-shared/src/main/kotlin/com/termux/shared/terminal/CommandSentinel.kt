package com.termux.shared.terminal

/**
 * Builds and recognises the sentinel marker used to detect exactly when a command finished and with
 * what exit status.
 *
 * The command is wrapped as:
 *
 * ```
 * { <command>
 * }; printf '\n<SENTINEL>%s<SENTINEL>\n' $?
 * ```
 *
 * Design notes:
 *
 * - `{ ...; }` is used instead of `( ... )` on purpose: running in the current shell means `cd`,
 *   `export` and other state changes persist, exactly as if the user had typed the command.
 * - The failure of the user command must not prevent the sentinel from printing, so the group is not
 *   followed by `&&`.
 * - A leading `\n` is printed so the marker never glue onto output that ended without a newline.
 * - `printf` is used rather than `echo` because `echo` implementations differ in escape handling.
 * - The exit status is captured with `$?` immediately after the group, before anything else runs.
 */
object CommandSentinel {

    /** Marker prefix. Kept ASCII-only so it survives any locale. */
    const val PREFIX: String = "__TERMUX_CMD_DONE_"

    /** Marker suffix. */
    const val SUFFIX: String = "__"

    /**
     * Builds the shell snippet for [command].
     *
     * A unique [token] is embedded so that a stale marker from an earlier run cannot be mistaken for
     * the current one.
     */
    fun build(command: String, token: String): String {
        val marker = markerFor(token)
        // Split so that commands not ending in a newline still place the marker on its own line.
        return buildString {
            append("{ ")
            append(command)
            append("\n}; printf '\\n")
            append(marker)
            append("%s")
            append(marker)
            append("\\n' $?")
        }
    }

    /** The literal marker text for [token]. */
    fun markerFor(token: String): String = "$PREFIX${sanitizeToken(token)}$SUFFIX"

    /**
     * Finds the exit status of the most recent sentinel for [token] in [text].
     *
     * Returns null when the marker is absent, which is how callers distinguish "still running" from
     * "finished with status N".
     */
    fun parseExitStatus(text: String, token: String): Int? {
        val marker = markerFor(token)
        // The marker appears twice around the status: <marker><digits><marker>.
        // Locate the closing occurrence first, then search backwards for the opening one.
        val closing = text.lastIndexOf(marker)
        if (closing < 0) return null
        val opening = text.lastIndexOf(marker, closing - 1)
        if (opening < 0) return null
        val digits = text.substring(opening + marker.length, closing).trim()
        return digits.toIntOrNull()
    }

    /**
     * Removes every sentinel line from [text] so that the marker does not pollute what the user (or an
     * assistant) reads.
     */
    fun strip(text: String, token: String): String {
        val marker = markerFor(token)
        if (!text.contains(marker)) return text
        return text.lineSequence()
            .filterNot { line -> line.contains(marker) }
            .joinToString("\n")
    }

    /** Rejects anything that would break out of the marker, keeping tokens to a safe alphabet. */
    private fun sanitizeToken(token: String): String =
        token.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "0" }
}
