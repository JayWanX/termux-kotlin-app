package com.termux.app.ai

import com.termux.shared.terminal.TerminalControl
import com.termux.shared.terminal.TerminalSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Events surfaced to the UI while a turn is running. */
sealed interface AgentEvent {
    /** A tool is about to run. */
    data class ToolStep(val label: String, val detail: String) : AgentEvent

    /** A tool finished. */
    data class ToolFinished(val label: String, val summary: String) : AgentEvent

    /** Progress within the round budget. */
    data class RoundProgress(val current: Int, val max: Int) : AgentEvent

    /** The user must approve something; the UI shows a dialog. */
    data class ConfirmationRequired(val summary: String) : AgentEvent

    /** A chunk of assistant text, so the panel can render it as it arrives. */
    data class TextDelta(val text: String) : AgentEvent

    /** Final answer, or an explanation of why the turn ended. */
    data class Final(val text: String) : AgentEvent
}

/** The outcome of one user turn. */
sealed interface AgentResult {
    data class Done(val text: String) : AgentResult
    data class RoundLimitReached(val maxRounds: Int) : AgentResult
    data class Failed(val message: String) : AgentResult
    data object Cancelled : AgentResult
}

/**
 * The assistant's turn loop.
 *
 * Shape: inject a fresh terminal snapshot, ask the model, run any requested tools, feed the results
 * back, repeat until the model answers in prose or the round budget runs out.
 *
 * Three details are load-bearing and easy to get wrong:
 *
 * 1. The terminal snapshot is *replaced* each round, not appended. Appending would grow the context
 *    without bound across a dozen rounds.
 * 2. Only the recent conversation is replayed when the terminal is in play, because older turns carry
 *    stale screen contents that actively mislead the model.
 * 3. A destructive tool never runs without confirmation, and the model cannot grant that confirmation
 *    itself by passing a flag.
 */
class AgentLoop(
    private val streamingClient: StreamingChatClient,
    private val registry: ToolRegistry,
    private val config: AiConfig,
) {

    companion object {
        /** How many recent messages to replay. */
        const val HISTORY_LIMIT: Int = 6

        /** Marker used to recognise the ephemeral snapshot message so it can be replaced. */
        private const val SNAPSHOT_PREFIX = "=== Current terminal state ==="

        /** Tools that modify files, so their confirmation toggle is the file one, not the command one. */
        private val FILE_MUTATING_TOOLS = setOf("edit_file", "multi_edit", "write_file")

        /** The system prompt. Short on purpose: every token here costs on every round. */
        val SYSTEM_PROMPT: String = """
            You are a terminal assistant inside a Termux Android terminal. You operate the user's real shell and can read and edit files.

            Working with the terminal:
            - Before assuming the outcome of anything, call read_terminal.
            - Prefer one shell command at a time, and read the result before the next.
            - Never claim a command succeeded unless you saw its exit_status.
            - If a full-screen app (vim, less, top) is running, either explain how to exit or send the appropriate key.

            Working with files:
            - Read before you edit. Copy old_string verbatim from read_file output; do not retype it from memory.
            - To change part of a file use edit_file or multi_edit, never write_file. Rewriting a whole file to change a line wastes tokens and risks losing content.
            - Batch related changes to one file into a single multi_edit call.
            - For large files use offset/limit rather than reading everything.
            - If edit_file reports the string occurs more than once, add surrounding context to make it unique instead of setting replace_all blindly.
            - Files live under the Termux home directory. Paths outside it are refused.

            General:
            - If an action is destructive (deleting, moving, overwriting, changing system state, killing processes) explain what you are about to do and let the user confirm.
            - Answer in the user's language. Be concise.
        """.trimIndent()
    }

    /**
     * Runs one turn.
     *
     * @param history the conversation so far, oldest first.
     * @param context the tool context, providing the terminal and confirmation callback.
     * @param onEvent receives progress for the UI.
     */
    suspend fun run(
        history: List<ChatMsg>,
        context: ToolContext,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        val working = buildWorkingMessages(history)
        val maxRounds = config.maxRounds
        var round = 0

        try {
            onEvent(AgentEvent.RoundProgress(0, maxRounds))

            while (round < maxRounds) {
                currentCoroutineContext().ensureActive()

                // Refresh the snapshot every round so the model reasons about the screen as it is now.
                replaceEphemeralSnapshot(working, context.terminal)

                // Streaming so the panel can render text as it is generated instead of waiting for the
                // whole reply. Tool calls are still delivered whole at the end of the stream.
                //
                // The delta callback is not a suspending context, so deltas are buffered in a channel
                // and drained here, where onEvent can be awaited.
                val deltaChannel = Channel<String>(capacity = Channel.UNLIMITED)
                var completion: Completion? = null
                var streamError: Throwable? = null

                coroutineScope {
                    val drain = launch {
                        for (delta in deltaChannel) {
                            onEvent(AgentEvent.TextDelta(delta))
                        }
                    }
                    streamingClient.stream(working, registry.specs()) { delta ->
                        deltaChannel.trySend(delta)
                    }.collect { result ->
                        completion = result.getOrNull()
                        streamError = result.exceptionOrNull()
                    }
                    deltaChannel.close()
                    drain.join()
                }

                val outcome = completion
                    ?: return AgentResult.Failed(
                        streamError?.message ?: "request failed or was cancelled",
                    )

                if (outcome.toolCalls.isEmpty()) {
                    val text = outcome.content?.trim().orEmpty()
                    onEvent(AgentEvent.Final(text))
                    return AgentResult.Done(text)
                }

                working += ChatMsg.assistant(outcome.content, outcome.toolCalls)
                round++
                onEvent(AgentEvent.RoundProgress(round, maxRounds))

                for (call in outcome.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val result = executeTool(call, context, onEvent)
                    working += ChatMsg.tool(call.id, result.asToolText())
                }
            }

            val exhausted = "已达工具调用轮次上限（$maxRounds）。请回复“继续”以接着执行。"
            onEvent(AgentEvent.Final(exhausted))
            return AgentResult.RoundLimitReached(maxRounds)
        } catch (e: CancellationException) {
            return AgentResult.Cancelled
        }
    }

    /** Runs one tool call, applying the confirmation policy first. */
    private suspend fun executeTool(
        call: ToolCall,
        context: ToolContext,
        onEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        val risk = registry.riskOf(call.name)
        val arguments = parseArguments(call.arguments)
        val label = describeCall(call.name, arguments)
        onEvent(AgentEvent.ToolStep(label, call.name))

        val needsConfirmation = when (risk) {
            RiskLevel.DESTRUCTIVE -> true
            RiskLevel.MUTATING -> if (isFileTool(call.name)) {
                config.confirmFileEdits
            } else {
                config.confirmCommands
            }
            RiskLevel.SAFE -> false
        }

        if (needsConfirmation) {
            onEvent(AgentEvent.ConfirmationRequired(label))
            val approved = context.requestConfirmation(label)
            if (!approved) {
                val denied = ToolResult.Denied("the user declined this action")
                onEvent(AgentEvent.ToolFinished(label, denied.asToolText()))
                return denied
            }
        }

        val result = registry.invoke(call.name, arguments, context)
        onEvent(AgentEvent.ToolFinished(label, result.asToolText().take(500)))
        return result
    }

    private fun parseArguments(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        JSONObject()
    }

    /** True for tools that mutate the filesystem rather than the terminal. */
    private fun isFileTool(name: String): Boolean = name in FILE_MUTATING_TOOLS

    /** A short, human-readable description of a call, used for the confirmation dialog. */
    fun describeCall(name: String, arguments: JSONObject): String = when (name) {
        "send_command" -> "运行命令：${arguments.optString("command").trim()}"
        "send_key" -> "按键：${arguments.optString("key")}"
        "read_terminal" -> "读取终端内容"
        "read_file" -> "读取文件：${arguments.optString("path")}"
        "list_files" -> "列出目录：${arguments.optString("path").ifBlank { "~" }}"
        "edit_file" -> "编辑文件：${arguments.optString("path")}"
        "multi_edit" -> "批量编辑：${arguments.optString("path")}"
        "write_file" -> "写入文件：${arguments.optString("path")}"
        "move_file" -> "移动/重命名：${arguments.optString("source")} → ${arguments.optString("destination")}"
        "delete_file" -> "删除：${arguments.optString("path")}"
        else -> name
    }

    /** Builds the initial message list: system prompt plus recent conversation. */
    private fun buildWorkingMessages(history: List<ChatMsg>): MutableList<ChatMsg> {
        val list = mutableListOf<ChatMsg>()
        list += ChatMsg.system(SYSTEM_PROMPT)
        val recent = history.filter {
            it.role == ChatMsg.ROLE_USER || it.role == ChatMsg.ROLE_ASSISTANT
        }.takeLast(HISTORY_LIMIT)
        list += recent
        return list
    }

    /**
     * Replaces the ephemeral snapshot message with a fresh one.
     *
     * Removing the previous snapshot before adding the new one is what keeps the context bounded.
     */
    private suspend fun replaceEphemeralSnapshot(
        messages: MutableList<ChatMsg>,
        terminal: TerminalControl,
    ) {
        messages.removeAll { isEphemeralSnapshot(it) }
        val snapshot = runCatching { terminal.snapshot() }.getOrNull() ?: return
        messages += ChatMsg.user(formatSnapshot(snapshot))
    }

    private fun isEphemeralSnapshot(message: ChatMsg): Boolean =
        message.role == ChatMsg.ROLE_USER &&
            message.content?.startsWith(SNAPSHOT_PREFIX) == true

    private fun formatSnapshot(snapshot: TerminalSnapshot): String = buildString {
        appendLine(SNAPSHOT_PREFIX)
        appendLine("state: ${snapshot.state::class.simpleName}")
        snapshot.cwd?.let { appendLine("cwd: $it") }
        if (snapshot.isFullScreenApp) appendLine("full-screen application active")
        appendLine("last line: ${snapshot.lastSessionLine}")
        appendLine("--- visible screen ---")
        append(snapshot.visibleText.take(3000))
    }.trim()
}
