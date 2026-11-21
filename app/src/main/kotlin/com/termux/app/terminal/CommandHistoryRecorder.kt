package com.termux.app.terminal

import com.termux.shared.logger.Logger
import com.termux.shared.terminal.CommandOutcome
import com.termux.shared.terminal.TerminalControl
import com.termux.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the commands a user runs, with their exit status and duration.
 *
 * The shell already keeps its own `HISTFILE`, and this deliberately does not try to replace or parse it.
 * What it adds is the two facts a history file cannot know: **how long the command ran** and **whether
 * it succeeded**, plus a stable place to read them from without depending on the user's shell config.
 *
 * Recording is driven by the terminal's own state transitions, so it works for any shell and does not
 * require the user to set up hooks.
 *
 * Commands are appended to a JSON-lines file so history survives process death. The file is written off
 * the main thread and is truncated to a bounded number of entries so it cannot grow without limit.
 */
class CommandHistoryRecorder(
    private val historyFile: File,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val LOG_TAG = "CommandHistoryRecorder"

        /** How many entries to keep on disk. */
        const val MAX_ENTRIES: Int = 2_000

        /** How long to let a shell finish echoing a submitted command before treating it as such. */
        private const val COMMAND_ECHO_GRACE_MS: Long = 250L

        /** A line shorter than this is unlikely to be a real command worth recording. */
        private const val MIN_COMMAND_LENGTH: Int = 2

        /**
         * Lines that look like a prompt rather than a command.
         *
         * A prompt is shown before the command is typed, so recording it would attribute the *previous*
         * command's duration to whatever the prompt happens to say.
         */
        private val PROMPT_TRAILERS = listOf('$', '#', '%', '>', '❯', 'λ')

        /** Builds the default history location inside app-private storage. */
        fun defaultFile(homeDir: String): File = File(homeDir, ".termux-assistant/history.jsonl")
    }

    /** One recorded command. */
    data class Entry(
        val command: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val exitStatus: Int?,
        val sessionId: String?,
        val completedAtMs: Long,
    )

    private var lastObservedLine: String? = null
    private var pendingCommand: String? = null
    private var pendingStartedAt: Long = 0L
    private var pendingDetectedAt: Long = 0L
    private var watchJob: Job? = null

    private val recording = AtomicBoolean(false)

    /** Starts watching the terminal. Safe to call repeatedly. */
    fun start(control: TerminalControl) {
        if (!recording.compareAndSet(false, true)) return
        watchJob = scope.launch {
            var previous: TerminalState = control.state.value
            control.state.collect { state ->
                handleTransition(control, previous, state)
                previous = state
            }
        }
    }

    /** Stops watching. */
    fun stop() {
        recording.set(false)
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * Decides what a state transition means for history.
     *
     * The interesting transitions are busy → idle (a command finished) and anything → dead.
     */
    private suspend fun handleTransition(
        control: TerminalControl,
        previous: TerminalState,
        current: TerminalState,
    ) {
        when {
            current is TerminalState.Busy && previous !is TerminalState.Busy -> {
                // A command just started producing output. Remember the line that was submitted.
                val line = runCatching { control.snapshot().lastSessionLine }.getOrNull()
                if (isRecordableCommand(line)) {
                    pendingCommand = line!!.trim()
                    pendingStartedAt = System.currentTimeMillis()
                    pendingDetectedAt = pendingStartedAt
                    lastObservedLine = pendingCommand
                }
            }

            current is TerminalState.Idle && previous is TerminalState.Busy -> {
                // Quiet again: attribute the elapsed time to the command we saw start.
                val command = pendingCommand ?: return
                val completedAt = System.currentTimeMillis()
                val duration = (completedAt - pendingStartedAt).coerceAtLeast(0L)
                // Skip only the case where the "command" was in fact the shell echoing our own probe.
                if (duration >= COMMAND_ECHO_GRACE_MS) {
                    append(
                        Entry(
                            command = command,
                            startedAtMs = pendingStartedAt,
                            durationMs = duration,
                            exitStatus = null,
                            sessionId = null,
                            completedAtMs = completedAt,
                        ),
                    )
                }
                clearPending()
            }

            current is TerminalState.Dead -> {
                pendingCommand?.let { command ->
                    val completedAt = System.currentTimeMillis()
                    append(
                        Entry(
                            command = command,
                            startedAtMs = pendingStartedAt,
                            durationMs = (completedAt - pendingStartedAt).coerceAtLeast(0L),
                            exitStatus = current.exitStatus,
                            sessionId = null,
                            completedAtMs = completedAt,
                        ),
                    )
                }
                clearPending()
                lastObservedLine = null
            }

            current is TerminalState.NoSession -> {
                clearPending()
                lastObservedLine = null
            }
        }
    }

    /**
     * Records a command whose exit status is known, for use when the assistant or another integration
     * ran something through [TerminalControl.sendCommandWithSentinel].
     */
    fun record(explicit: Entry) {
        append(explicit)
    }

    /** Writes one entry to disk, trimming the file when it grows too large. */
    private fun append(entry: Entry) {
        scope.launch {
            try {
                historyFile.parentFile?.mkdirs()
                historyFile.appendText(encode(entry) + "\n")
                trimIfNeeded()
            } catch (e: Exception) {
                Logger.logVerbose(LOG_TAG, "failed to append history: ${e.message}")
            }
        }
    }

    private fun trimIfNeeded() {
        if (!historyFile.isFile) return
        val lines = runCatching { historyFile.readLines() }.getOrNull() ?: return
        if (lines.size <= MAX_ENTRIES) return
        val kept = lines.takeLast(MAX_ENTRIES)
        runCatching {
            val temp = File(historyFile.parentFile, historyFile.name + ".tmp")
            temp.writeText(kept.joinToString("\n") + "\n")
            if (!temp.renameTo(historyFile)) {
                historyFile.writeText(kept.joinToString("\n") + "\n")
                temp.delete()
            }
        }
    }

    private fun clearPending() {
        pendingCommand = null
        pendingStartedAt = 0L
        pendingDetectedAt = 0L
    }

    /**
     * Whether [line] looks like a command the user typed.
     *
     * This is deliberately conservative: false negatives only lose a history entry, while false
     * positives would pollute history with prompts and output.
     */
    fun isRecordableCommand(line: String?): Boolean {
        val text = line?.trim() ?: return false
        if (text.length < MIN_COMMAND_LENGTH) return false
        if (text == lastObservedLine) return false
        // A bare prompt, optionally with a path before it, is not a command.
        if (text.last() in PROMPT_TRAILERS && text.split(' ').size <= 2) return false
        return true
    }

    /** Reads all entries, oldest first. */
    fun readAll(): List<Entry> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            historyFile.readLines().mapNotNull { decode(it) }
        }.getOrDefault(emptyList())
    }

    /** Encodes an entry as a single JSON line. */
    fun encode(entry: Entry): String = buildString {
        append('{')
        append("\"c\":").append(quote(entry.command)).append(',')
        append("\"s\":").append(entry.startedAtMs).append(',')
        append("\"d\":").append(entry.durationMs).append(',')
        append("\"e\":").append(entry.exitStatus ?: "null").append(',')
        entry.sessionId?.let { append("\"sid\":").append(quote(it)).append(',') }
        append("\"t\":").append(entry.completedAtMs)
        append('}')
    }

    /** Parses one JSON line, returning null when it is malformed. */
    fun decode(line: String): Entry? {
        val text = line.trim()
        if (text.isEmpty() || !text.startsWith("{")) return null
        return runCatching {
            val command = extractString(text, "c") ?: return null
            Entry(
                command = command,
                startedAtMs = extractLong(text, "s") ?: 0L,
                durationMs = extractLong(text, "d") ?: 0L,
                exitStatus = extractLong(text, "e")?.toInt(),
                sessionId = extractString(text, "sid"),
                completedAtMs = extractLong(text, "t") ?: 0L,
            )
        }.getOrNull()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun extractString(json: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val builder = StringBuilder()
        var i = valueStart
        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '\\' && i + 1 < json.length -> {
                    when (val escaped = json[i + 1]) {
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        'u' -> {
                            val hex = json.substring(i + 2, minOf(i + 6, json.length))
                            hex.toIntOrNull(16)?.let { builder.append(it.toChar()) }
                            i += 4
                        }
                        else -> builder.append(escaped)
                    }
                    i += 2
                }
                ch == '"' -> return builder.toString()
                else -> {
                    builder.append(ch)
                    i++
                }
            }
        }
        return null
    }

    private fun extractLong(json: String, key: String): Long? {
        val marker = "\"$key\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        if (json.startsWith("null", valueStart)) return null
        var i = valueStart
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
        return json.substring(valueStart, i).toLongOrNull()
    }
}
