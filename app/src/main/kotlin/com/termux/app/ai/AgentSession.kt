package com.termux.app.ai

import com.termux.shared.terminal.TerminalControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One rendered message in the panel. */
data class TurnMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val steps: List<TurnStep> = emptyList(),
)

/** A tool step shown under a message. */
data class TurnStep(val label: String, val detail: String, val finished: Boolean = false)

/** The panel's observable state. */
data class SessionState(
    val messages: List<TurnMessage> = emptyList(),
    val isRunning: Boolean = false,
    val round: Int = 0,
    val maxRounds: Int = AiConfig.DEFAULT_MAX_ROUNDS,
    val pendingConfirmation: String? = null,
)

/**
 * One conversation with the assistant.
 *
 * Owns the transcript and adapts the generic [AgentLoop] to something a screen can render. Confirmation
 * requests are surfaced as state and answered through [answerConfirmation], rather than blocking on a
 * dialog inside the loop, so the UI stays a plain function of [state].
 */
class AgentSession(
    private val config: AiConfig,
    private val loop: AgentLoop,
    private val terminal: TerminalControl,
) {

    private val _state = MutableStateFlow(
        SessionState(maxRounds = config.maxRounds),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Conversation history in wire format, replayed to the model on each turn. */
    private val history = mutableListOf<ChatMsg>()

    /** Non-null while the loop waits for the user to approve something. */
    @Volatile
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    /** Directories the filesystem tools may touch. The first is treated as `~`. */
    var fileRoots: List<String> = listOf("/data/data/com.termux/files/home")

    /** Stable id for this session, used to keep edit backups separated. */
    val sessionId: String = UUID.randomUUID().toString().take(8)

    /** Clears the conversation. */
    fun reset() {
        history.clear()
        _state.value = SessionState(maxRounds = config.maxRounds)
    }

    /** Answers a pending confirmation. No-op when nothing is pending. */
    fun answerConfirmation(approved: Boolean) {
        pendingConfirmation?.complete(approved)
        pendingConfirmation = null
        _state.value = _state.value.copy(pendingConfirmation = null)
    }

    /**
     * Sends a user message and runs a turn.
     *
     * The caller is expected to run this inside a lifecycle-aware coroutine; cancellation propagates
     * into the loop and is reported as [AgentResult.Cancelled].
     */
    suspend fun send(text: String): AgentResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AgentResult.Done("")

        history += ChatMsg.user(trimmed)
        appendMessage(TurnMessage(role = ChatMsg.ROLE_USER, text = trimmed))
        _state.value = _state.value.copy(isRunning = true, round = 0)

        val assistantMessage = TurnMessage(role = ChatMsg.ROLE_ASSISTANT, text = "")
        appendMessage(assistantMessage)

        val context = object : ToolContext {
            override val terminal: TerminalControl = this@AgentSession.terminal
            override val fileRoots: List<String> = this@AgentSession.fileRoots
            override val sessionId: String = this@AgentSession.sessionId

            override suspend fun requestConfirmation(summary: String): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                pendingConfirmation = deferred
                _state.value = _state.value.copy(pendingConfirmation = summary)
                return try {
                    deferred.await()
                } finally {
                    pendingConfirmation = null
                    _state.value = _state.value.copy(pendingConfirmation = null)
                }
            }
        }

        val result = try {
            loop.run(history, context) { event -> handleEvent(assistantMessage.id, event) }
        } finally {
            _state.value = _state.value.copy(isRunning = false)
        }

        when (result) {
            is AgentResult.Done -> history += ChatMsg.assistant(result.text)
            is AgentResult.RoundLimitReached,
            is AgentResult.Failed,
            AgentResult.Cancelled,
            -> Unit
        }
        return result
    }

    private fun handleEvent(assistantId: String, event: AgentEvent) {
        val current = _state.value
        when (event) {
            is AgentEvent.RoundProgress -> {
                _state.value = current.copy(round = event.current, maxRounds = event.max)
            }

            is AgentEvent.ToolStep -> {
                val message = current.messages.find { it.id == assistantId } ?: return
                val updated = message.copy(
                    steps = message.steps + TurnStep(event.label, event.detail),
                )
                replaceMessage(updated)
            }

            is AgentEvent.ToolFinished -> {
                val message = current.messages.find { it.id == assistantId } ?: return
                val steps = message.steps.toMutableList()
                val index = steps.indexOfLast { it.label == event.label && !it.finished }
                if (index >= 0) {
                    steps[index] = steps[index].copy(detail = event.summary, finished = true)
                } else {
                    steps += TurnStep(event.label, event.summary, finished = true)
                }
                replaceMessage(message.copy(steps = steps))
            }

            is AgentEvent.ConfirmationRequired -> {
                _state.value = current.copy(pendingConfirmation = event.summary)
            }

            is AgentEvent.Final -> {
                val message = current.messages.find { it.id == assistantId } ?: return
                replaceMessage(message.copy(text = event.text))
            }
        }
    }

    private fun appendMessage(message: TurnMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + message)
    }

    private fun replaceMessage(message: TurnMessage) {
        val messages = _state.value.messages.map { if (it.id == message.id) message else it }
        _state.value = _state.value.copy(messages = messages)
    }
}
