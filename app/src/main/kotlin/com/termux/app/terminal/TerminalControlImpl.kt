package com.termux.app.terminal

import android.os.SystemClock
import com.termux.shared.logger.Logger
import com.termux.shared.terminal.CommandOutcome
import com.termux.shared.terminal.CommandSentinel
import com.termux.shared.terminal.ForegroundProcessProbe
import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalControl
import com.termux.shared.terminal.TerminalKey
import com.termux.shared.terminal.TerminalSnapshot
import com.termux.shared.terminal.TerminalState
import com.termux.shared.terminal.TerminalStateTracker
import com.termux.shared.terminal.TranscriptSearch
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The app-side [TerminalControl] implementation.
 *
 * It needs a way to reach the current [TerminalSession], which is supplied as a lambda so that this
 * class does not hold an Activity reference and therefore cannot leak one.
 *
 * Sampling happens on a fixed interval driven by [start] / [stop]. The interval is intentionally short
 * (120 ms) so that state transitions feel immediate; sampling only reads two cheap values.
 *
 * Text reads and writes must run on the main thread because the emulator's screen buffer is not
 * thread safe. [snapshot] therefore marshals to the main thread; the polling loop reuses the same
 * path.
 */
class TerminalControlImpl(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> TerminalSession?,
    private val sessionsProvider: () -> List<TerminalSession> = { emptyList() },
    private val selectSessionAction: (TerminalSession) -> Unit = {},
    private val mainThread: suspend (() -> Unit) -> Unit = { block ->
        withContext(Dispatchers.Main.immediate) { block() }
    },
) : TerminalControl {

    companion object {
        private const val LOG_TAG = "TerminalControlImpl"

        /** How often the session is sampled. */
        const val POLL_INTERVAL_MS: Long = 120L

        /** Upper bound on the scrollback tail included in a snapshot. */
        private const val SCROLLBACK_TAIL_CHARS: Int = 1800

        /** Lower bound accepted by [snapshot]. */
        private const val MIN_MAX_CHARS: Int = 500

        /** Upper bound accepted by [snapshot]. */
        private const val MAX_MAX_CHARS: Int = 20000

        /** How often the sentinel is looked for while waiting for a command. */
        private const val SENTINEL_POLL_INTERVAL_MS: Long = 150L
    }

    private val tracker = TerminalStateTracker()
    private val _state = MutableStateFlow<TerminalState>(TerminalState.NoSession)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    /**
     * Optional listener informed on every state transition, with a snapshot for context.
     *
     * The app uses this to raise completion notifications; keeping it a callback (rather than having
     * this class know about notifications) keeps the core free of UI concerns.
     */
    var stateListener: ((String, TerminalState, TerminalSnapshot?) -> Unit)? = null

    private var pollJob: Job? = null
    private val started = AtomicBoolean(false)

    /** Last session observed, used to detect a switch and reset the tracker. */
    private var lastSession: TerminalSession? = null

    /** Cached scroll counter, read on the main thread and consumed by the polling loop. */
    @Volatile
    private var cachedScrollCounter: Int = 0

    /** Cached foreground-group answer; refreshed lazily by [refreshForegroundInfo]. */
    @Volatile
    private var shellInForeground: Boolean? = null

    private var scrollCounterReadJob: Job? = null

    override fun isAttached(): Boolean = sessionProvider()?.isRunning == true

    /** Starts periodic sampling. Safe to call repeatedly. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        pollJob = scope.launch {
            while (started.get()) {
                sampleOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
        scrollCounterReadJob = scope.launch {
            while (started.get()) {
                refreshScrollCounter()
                refreshForegroundInfo()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops periodic sampling and releases the session reference. */
    fun stop() {
        started.set(false)
        pollJob?.cancel()
        pollJob = null
        scrollCounterReadJob?.cancel()
        scrollCounterReadJob = null
        lastSession = null
        tracker.reset()
        _state.value = TerminalState.NoSession
    }

    // ---------------------------------------------------------------- sampling

    private suspend fun sampleOnce() {
        val session = sessionProvider()
        if (session !== lastSession) {
            lastSession = session
            tracker.reset()
        }

        val now = SystemClock.elapsedRealtime()
        val isRunning = session?.isRunning == true
        val exitStatus = if (session != null && !isRunning) session.exitStatus else null

        var state = tracker.sample(
            isRunning = isRunning,
            scrollCounter = cachedScrollCounter,
            nowMs = now,
            exitStatus = exitStatus,
        )

        // When the output says "idle" but a child process is still in the foreground, the command is
        // merely silent (for example `sleep 30`). Report Busy so callers do not act prematurely.
        if (state is TerminalState.Idle && shellInForeground == false) {
            state = TerminalState.Busy(now, now)
        }

        val previous = _state.value
        _state.value = state

        if (previous != state) {
            notifyStateListener(session, state)
        }
    }

    /**
     * Notifies the optional listener of a transition, capturing a snapshot lazily.
     *
     * A snapshot is only taken for terminal outcomes, where it is useful for the notification body;
     * sampling it on every Busy transition would be wasteful.
     */
    private suspend fun notifyStateListener(session: TerminalSession?, state: TerminalState) {
        val listener = stateListener ?: return
        val id = session?.mSessionName ?: session?.getPid()?.toString() ?: return
        val snapshot = if (state is TerminalState.Idle || state is TerminalState.Dead) {
            runCatching { snapshot() }.getOrNull()
        } else {
            null
        }
        runCatching { listener(id, state, snapshot) }
            .onFailure { Logger.logVerbose(LOG_TAG, "stateListener failed: ${it.message}") }
    }

    /** Reads the scroll counter on the main thread, where touching the emulator is safe. */
    private suspend fun refreshScrollCounter() {
        val session = sessionProvider() ?: return
        try {
            mainThread {
                cachedScrollCounter = session.emulator?.getScrollCounter() ?: 0
            }
        } catch (e: Exception) {
            Logger.logVerbose(LOG_TAG, "refreshScrollCounter failed: ${e.message}")
        }
    }

    /**
     * Asks the OS which process group owns the terminal.
     *
     * This spawns a process, so it runs off the main thread and only every poll interval; on a modern
     * device `ps -o tpgid= -p <pid>` costs a few milliseconds.
     */
    private suspend fun refreshForegroundInfo() {
        val session = sessionProvider() ?: run {
            shellInForeground = null
            return
        }
        val pid = session.getPid()
        if (pid <= 0) {
            shellInForeground = null
            return
        }
        shellInForeground = withContext(Dispatchers.IO) {
            readForegroundState(pid)
        }
    }

    private fun readForegroundState(shellPid: Int): Boolean? {
        return try {
            val process = ProcessBuilder("ps", "-o", "tpgid=", "-p", shellPid.toString())
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            ForegroundProcessProbe.isShellInForeground(
                shellPid = shellPid,
                tpgid = ForegroundProcessProbe.parseTpgid(output),
            )
        } catch (e: Exception) {
            Logger.logVerbose(LOG_TAG, "readForegroundState failed: ${e.message}")
            null
        }
    }

    // --------------------------------------------------------------- snapshots

    override suspend fun snapshot(maxChars: Int): TerminalSnapshot {
        val limit = maxChars.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
        val session = sessionProvider()
            ?: return emptySnapshot(TerminalState.NoSession)

        if (!session.isRunning) {
            return emptySnapshot(TerminalState.Dead(session.exitStatus), session, limit)
        }

        var visible = ""
        var full = ""
        var rows = 0
        var cols = 0
        var cursorRow = 0
        var cursorCol = 0
        var isAlt = false

        mainThread {
            val emulator = session.emulator
            if (emulator != null) {
                rows = emulator.mRows
                cols = emulator.mColumns
                cursorRow = emulator.getCursorRow()
                cursorCol = emulator.getCursorCol()
                isAlt = emulator.isAlternateBufferActive()
                val screen = emulator.getScreen()
                visible = screen.getSelectedText(0, 0, cols, rows)
                full = screen.getTranscriptText()
            }
        }

        val visibleBlock = trimToTail(visible, limit / 2)
        val scrollTail = trimToTail(full, minOf(SCROLLBACK_TAIL_CHARS, limit / 2))
        val truncated = visibleBlock.length < visible.trim().length ||
            scrollTail.length < full.trim().length

        return TerminalSnapshot(
            state = _state.value,
            visibleText = visibleBlock,
            scrollbackTail = scrollTail,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            rows = rows,
            cols = cols,
            title = session.title?.takeIf { it.isNotBlank() },
            cwd = readCwd(session),
            isAltBuffer = isAlt,
            isTruncated = truncated,
        )
    }

    private fun emptySnapshot(
        state: TerminalState,
        session: TerminalSession? = null,
        limit: Int = TerminalControl.DEFAULT_MAX_CHARS,
    ): TerminalSnapshot = TerminalSnapshot(
        state = state,
        visibleText = "",
        scrollbackTail = "",
        cursorRow = 0,
        cursorCol = 0,
        rows = 0,
        cols = 0,
        title = session?.title?.takeIf { it.isNotBlank() },
        cwd = session?.let { readCwd(it) },
        isAltBuffer = false,
        isTruncated = false,
    )

    private fun readCwd(session: TerminalSession): String? =
        try {
            session.getCwd()
        } catch (e: Exception) {
            null
        }

    private fun trimToTail(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.substring(trimmed.length - maxChars)
    }

    // ----------------------------------------------------------------- writing

    override suspend fun send(text: String, enter: Boolean) {
        val session = sessionProvider() ?: return
        if (!session.isRunning) return
        mainThread {
            val emulator = session.emulator
            if (emulator != null) {
                // paste() converts \n to \r and honours bracketed paste mode, which is exactly the
                // semantics a terminal expects from typed text.
                if (enter) {
                    emulator.paste(text)
                    session.write("\r")
                } else {
                    // Without enter, deliver the payload but do not submit the line.
                    emulator.paste(text)
                }
            } else {
                session.write(if (enter) "$text\r" else text)
            }
        }
    }

    override suspend fun sendKey(key: TerminalKey) {
        val session = sessionProvider() ?: return
        if (!session.isRunning) return
        mainThread {
            val emulator = session.emulator
            val control = key.controlChar
            if (control != null) {
                // Writing the control byte to the pty is exactly what pressing the key does; it lets
                // the terminal driver deliver SIGINT to the foreground group without us having to
                // signal processes directly.
                session.write(control.toString())
                return@mainThread
            }
            val code = when (key) {
                TerminalKey.ENTER -> "\r"
                TerminalKey.TAB -> "\t"
                TerminalKey.ESC -> "\u001B"
                TerminalKey.BACKSPACE -> "\u007F"
                TerminalKey.UP -> cursorKey('A', emulator?.isCursorKeysApplicationMode() == true)
                TerminalKey.DOWN -> cursorKey('B', emulator?.isCursorKeysApplicationMode() == true)
                TerminalKey.RIGHT -> cursorKey('C', emulator?.isCursorKeysApplicationMode() == true)
                TerminalKey.LEFT -> cursorKey('D', emulator?.isCursorKeysApplicationMode() == true)
                else -> return@mainThread
            }
            session.write(code)
        }
    }

    /**
     * Builds a cursor-key escape sequence.
     *
     * In normal mode arrows are `CSI A..D`; applications that enable cursor-key application mode
     * (vim, less) expect `SS3 A..D` instead. Sending the wrong one leaves the key apparently dead.
     */
    private fun cursorKey(letter: Char, applicationMode: Boolean): String =
        if (applicationMode) "\u001B O$letter" else "\u001B[$letter"

    // ------------------------------------------------------------------ waiting

    override suspend fun awaitIdle(timeoutMs: Long): TerminalState {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = _state.value
            if (current is TerminalState.Idle || current is TerminalState.Dead) return current
            delay(POLL_INTERVAL_MS)
        }
        return _state.value
    }

    override suspend fun sendCommandWithSentinel(command: String, timeoutMs: Long): CommandOutcome {
        val token = "t" + SystemClock.elapsedRealtime() + "_" + Random.nextInt(1000, 9999)
        val script = CommandSentinel.build(command, token)

        send(script, enter = true)

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastSnapshot = snapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (_state.value is TerminalState.Dead) break
            delay(SENTINEL_POLL_INTERVAL_MS)
            lastSnapshot = snapshot()
            val combined = lastSnapshot.scrollbackTail + "\n" + lastSnapshot.visibleText
            val status = CommandSentinel.parseExitStatus(combined, token)
            if (status != null) {
                val cleaned = lastSnapshot.copy(
                    visibleText = CommandSentinel.strip(lastSnapshot.visibleText, token),
                    scrollbackTail = CommandSentinel.strip(lastSnapshot.scrollbackTail, token),
                )
                return CommandOutcome(exitStatus = status, state = _state.value, snapshot = cleaned)
            }
        }

        // Timed out or the shell died before reporting: return what we know.
        val exitStatus = (lastSnapshot.state as? TerminalState.Dead)?.exitStatus
            ?: (if (_state.value is TerminalState.Dead) sessionProvider()?.exitStatus else null)
        return CommandOutcome(exitStatus = exitStatus, state = _state.value, snapshot = lastSnapshot)
    }

    // ---------------------------------------------------------------- sessions

    override fun sessions(): List<SessionInfo> = sessionsProvider().map { session ->
        SessionInfo(
            id = session.mSessionName ?: session.getPid().toString(),
            title = session.title.orEmpty(),
            pid = session.getPid(),
            isRunning = session.isRunning,
            exitStatus = if (session.isRunning) null else session.exitStatus,
            cwd = readCwd(session),
            isAltBuffer = session.emulator?.isAlternateBufferActive() == true,
            state = if (session === sessionProvider()) _state.value
            else if (session.isRunning) TerminalState.Idle(0L)
            else TerminalState.Dead(session.exitStatus),
        )
    }

    override suspend fun transcript(): String {
        val session = sessionProvider() ?: return ""
        return try {
            com.termux.shared.shell.ShellUtils.getTerminalSessionTranscriptText(
                session,
                linesJoined = false,
                trim = true,
            ).orEmpty()
        } catch (e: Exception) {
            Logger.logVerbose(LOG_TAG, "transcript failed: ${e.message}")
            ""
        }
    }

    override suspend fun searchScrollback(
        query: String,
        ignoreCase: Boolean,
    ): List<TranscriptSearch.Match> {
        if (query.isBlank()) return emptyList()
        return TranscriptSearch.find(transcript(), query.trim(), ignoreCase)
    }

    override fun selectSession(sessionId: String): Boolean {
        val target = sessionsProvider().firstOrNull {
            (it.mSessionName ?: it.getPid().toString()) == sessionId
        } ?: return false
        selectSessionAction(target)
        return true
    }
}

