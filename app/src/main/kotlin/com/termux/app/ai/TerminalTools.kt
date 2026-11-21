package com.termux.app.ai

import com.termux.shared.terminal.TerminalControl
import com.termux.shared.terminal.TerminalKey
import com.termux.shared.terminal.TerminalSnapshot
import com.termux.shared.terminal.TerminalState
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools exposed to the assistant.
 *
 * Deliberately limited to four: read the terminal, run a command, send a key, read a file. Anything
 * broader (writing files, editing settings, managing containers) belongs in the terminal itself, where
 * the user stays in control.
 */
object TerminalTools {

    /** Default cap on file reads, so a huge log cannot blow up the context window. */
    private const val MAX_FILE_BYTES: Long = 256 * 1024

    /**
     * Terminal tools only.
     *
     * Filesystem tools live in [FileTools]; the two sets are registered together in
     * [AiAssistant.createSession].
     */
    fun all(): List<AgentTool> = listOf(
        ReadTerminalTool(),
        SendCommandTool(),
        SendKeyTool(),
    )

    private fun stringProperty(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integerProperty(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    /**
     * Reads the current terminal contents.
     *
     * Returns a compact, factual rendering: session state, cursor, working directory, the visible
     * screen and the tail of the scrollback.
     */
    class ReadTerminalTool : AgentTool {
        override val spec = ToolSpec(
            name = "read_terminal",
            description = "Read what the terminal currently shows. Returns the session state, " +
                "working directory and the visible screen plus recent scrollback. Use this before " +
                "assuming what happened.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "max_chars",
                        integerProperty("Maximum characters of terminal text to return."),
                    ),
                )
                .put("required", JSONArray()),
            risk = RiskLevel.SAFE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult {
            val maxChars = arguments.optInt("max_chars", TerminalControl.DEFAULT_MAX_CHARS)
            val snapshot = context.terminal.snapshot(maxChars)
            return ToolResult.Ok(format(snapshot))
        }

        /** Renders a snapshot as the text the model sees. Facts only, no interpretation. */
        fun format(snapshot: TerminalSnapshot): String = buildString {
            appendLine("state: ${describe(snapshot.state)}")
            snapshot.cwd?.let { appendLine("cwd: $it") }
            snapshot.title?.let { appendLine("title: $it") }
            appendLine("cursor: row=${snapshot.cursorRow} col=${snapshot.cursorCol} " +
                "size=${snapshot.rows}x${snapshot.cols}")
            if (snapshot.isFullScreenApp) {
                appendLine("note: a full-screen application (vim/less/top) is running")
            }
            appendLine("last line: ${snapshot.lastSessionLine}")
            appendLine("--- visible screen ---")
            appendLine(snapshot.visibleText)
            if (snapshot.scrollbackTail.isNotBlank() &&
                snapshot.scrollbackTail != snapshot.visibleText
            ) {
                appendLine("--- scrollback tail ---")
                appendLine(snapshot.scrollbackTail)
            }
            if (snapshot.isTruncated) {
                appendLine("(output truncated)")
            }
        }.trim()

        private fun describe(state: TerminalState): String = when (state) {
            TerminalState.NoSession -> "no session"
            is TerminalState.Dead -> "exited" + (state.exitStatus?.let { " (status $it)" } ?: "")
            is TerminalState.Busy -> "running"
            is TerminalState.Idle -> "idle (no output for ${state.silenceMs} ms)"
        }
    }

    /**
     * Runs a shell command and waits for it to finish.
     *
     * The command is wrapped in a sentinel so the real exit status is known even when the command is
     * silent or produces no trailing newline.
     */
    class SendCommandTool : AgentTool {
        override val spec = ToolSpec(
            name = "send_command",
            description = "Run a shell command in the terminal and wait for it to finish. " +
                "Returns the exit status and the resulting terminal output. Prefer one command at a time.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("command", stringProperty("The shell command to run."))
                        .put(
                            "timeout_ms",
                            integerProperty("How long to wait before giving up, in milliseconds."),
                        ),
                )
                .put("required", JSONArray().put("command")),
            risk = RiskLevel.MUTATING,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult {
            val command = arguments.optString("command").trim()
            if (command.isEmpty()) {
                return ToolResult.Failed("command must not be empty")
            }
            val timeout = arguments.optLong("timeout_ms", TerminalControl.DEFAULT_COMMAND_TIMEOUT_MS)
                .coerceIn(1_000L, 30 * 60_000L)

            val outcome = context.terminal.sendCommandWithSentinel(command, timeout)
            return ToolResult.Ok(buildString {
                appendLine("exit_status: ${outcome.exitStatus ?: "unknown (still running or interrupted)"}")
                appendLine("state: ${outcome.state::class.simpleName}")
                appendLine("--- output ---")
                append(outcome.snapshot.visibleText.take(4000))
            }.trim())
        }
    }

    /** Sends a single control key, most usefully Ctrl+C to interrupt whatever is running. */
    class SendKeyTool : AgentTool {
        override val spec = ToolSpec(
            name = "send_key",
            description = "Send a single control key to the terminal. Use CTRL_C to interrupt a " +
                "running command, ENTER to confirm a prompt.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "key",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Which key to send.")
                            .put(
                                "enum",
                                JSONArray().apply {
                                    TerminalKey.entries.forEach { put(it.name) }
                                },
                            ),
                    ),
                )
                .put("required", JSONArray().put("key")),
            risk = RiskLevel.SAFE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult {
            val raw = arguments.optString("key").uppercase().trim()
            val key = TerminalKey.entries.firstOrNull { it.name == raw }
                ?: return ToolResult.Failed("unsupported key `$raw`")
            context.terminal.sendKey(key)
            return ToolResult.Ok("sent $key")
        }
    }

}
