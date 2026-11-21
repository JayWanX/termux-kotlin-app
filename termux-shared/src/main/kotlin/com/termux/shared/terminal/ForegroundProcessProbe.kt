package com.termux.shared.terminal

/**
 * Answers "is the shell itself in the foreground, or is a child command running?" by reading the
 * terminal's foreground process group.
 *
 * This matters because output-based detection cannot tell `sleep 30` apart from an idle prompt: both
 * produce no output. The foreground process group can: while `sleep` runs, the terminal's foreground
 * group is `sleep`'s group, not the shell's.
 *
 * `tcgetpgrp(3)` is not exposed through `android.system.Os`, so the value is read from `ps`. That costs
 * a process spawn, so callers should treat this as a confirmation step rather than a per-tick probe.
 */
object ForegroundProcessProbe {

    /**
     * Parses `ps -o tpgid= -p <pid>` output into the foreground process group id.
     *
     * Split out from [probeForegroundPgid] so the parsing can be unit-tested without spawning anything.
     */
    fun parseTpgid(psOutput: String?): Int? {
        val trimmed = psOutput?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Header-less `ps -o tpgid=` prints a single column; be tolerant of surrounding whitespace.
        val firstField = trimmed.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?: return null
        val value = firstField.toIntOrNull() ?: return null
        // -1 means the process has no controlling terminal.
        return if (value > 0) value else null
    }

    /**
     * Decides whether the shell is idle based on the foreground group.
     *
     * @param shellPid the shell's pid, which is also its process group id for an interactive shell.
     * @param tpgid the terminal's foreground process group id.
     * @return true when the shell itself is in the foreground, meaning no child command is running;
     *   null when the answer is unknown (missing data), so callers can fall back to output-based logic.
     */
    fun isShellInForeground(shellPid: Int, tpgid: Int?): Boolean? {
        if (shellPid <= 0 || tpgid == null) return null
        return tpgid == shellPid
    }
}
