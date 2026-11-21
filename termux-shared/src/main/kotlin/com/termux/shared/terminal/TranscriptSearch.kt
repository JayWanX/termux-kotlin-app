package com.termux.shared.terminal

/**
 * Searches terminal scrollback text.
 *
 * Kept as a pure function over the transcript string rather than reaching into `TerminalBuffer`: the
 * transcript is already the canonical text view of a session, and keeping the search independent makes
 * it testable and reusable by the UI and the assistant alike.
 */
object TranscriptSearch {

    /** Cap on matches returned for one query, so a pathological pattern cannot freeze the UI. */
    const val MAX_MATCHES: Int = 500

    /**
     * One match.
     *
     * @property lineIndex zero-based line number within the transcript.
     * @property line the full text of that line, trimmed of trailing padding.
     * @property column zero-based character offset of the match within [line].
     * @property length length of the matched text.
     */
    data class Match(
        val lineIndex: Int,
        val line: String,
        val column: Int,
        val length: Int,
    ) {
        /** Character range of the match within [line]. */
        val range: IntRange get() = column until (column + length)
    }

    /**
     * Finds every occurrence of [query] in [transcript].
     *
     * Matching is case-insensitive by default because terminal output is usually scanned by eye rather
     * than matched exactly. Occurrences are non-overlapping, which is what highlighting expects.
     *
     * @param maxMatches upper bound on results; defaults to [MAX_MATCHES].
     */
    fun find(
        transcript: String,
        query: String,
        ignoreCase: Boolean = true,
        maxMatches: Int = MAX_MATCHES,
    ): List<Match> {
        if (query.isEmpty() || transcript.isEmpty()) return emptyList()

        val matches = ArrayList<Match>()
        val lines = transcript.split('\n')

        for ((lineIndex, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd()
            if (line.isEmpty()) continue

            var from = 0
            while (from <= line.length - query.length) {
                val found = line.indexOf(query, from, ignoreCase)
                if (found < 0) break
                matches += Match(
                    lineIndex = lineIndex,
                    line = line,
                    column = found,
                    length = query.length,
                )
                if (matches.size >= maxMatches) return matches
                from = found + query.length
            }
        }
        return matches
    }

    /**
     * Picks the match to jump to given the current viewport position and a direction.
     *
     * @param matches ordered results from [find].
     * @param currentLine the line the user is currently looking at.
     * @param forward true to search down, false to search up.
     * @return the next match, or null when there is none in that direction.
     */
    fun next(
        matches: List<Match>,
        currentLine: Int,
        forward: Boolean,
    ): Match? {
        if (matches.isEmpty()) return null
        return if (forward) {
            matches.firstOrNull { it.lineIndex > currentLine } ?: matches.first()
        } else {
            matches.lastOrNull { it.lineIndex < currentLine } ?: matches.last()
        }
    }

    /** Human-readable summary for a result set. */
    fun summarize(matches: List<Match>, query: String): String = when {
        query.isEmpty() -> ""
        matches.isEmpty() -> "No matches"
        matches.size == 1 -> "1 match"
        matches.size >= MAX_MATCHES -> "$MAX_MATCHES+ matches"
        else -> "${matches.size} matches"
    }
}
