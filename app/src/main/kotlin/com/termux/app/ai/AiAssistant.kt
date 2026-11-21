package com.termux.app.ai

import com.termux.shared.terminal.TerminalControl

/**
 * The single seam between the app and the optional assistant.
 *
 * The assistant is an aid, not a feature of the terminal. To keep it that way:
 *
 * - This object is the only thing outside the `ai` package that app code may touch.
 * - It holds no references of its own: [attach] receives what it needs and [detach] drops it.
 * - Deleting the whole `ai` package plus this one call site must leave a fully working terminal,
 *   which is enforced by a grep check in CI.
 */
object AiAssistant {

    @Volatile
    private var attachment: Attachment? = null

    /**
     * Binds the assistant to a terminal.
     *
     * Safe to call repeatedly; the most recent attachment wins. Call from the UI layer only when the
     * assistant is actually going to be shown.
     */
    fun attach(control: TerminalControl, config: AiConfig) {
        attachment = Attachment(control, config)
    }

    /** Releases the binding. The assistant becomes inert until [attach] is called again. */
    fun detach() {
        attachment = null
    }

    /** True when the assistant is bound and the user has enabled and configured it. */
    fun isAvailable(): Boolean = attachment?.config?.isUsable() == true

    /**
     * Creates a runner for one conversation.
     *
     * Returns null when the assistant is unavailable, so callers do not need to duplicate the
     * availability check.
     */
    fun createSession(): AgentSession? {
        val current = attachment ?: return null
        if (!current.config.isUsable()) return null
        return AgentSession(
            config = current.config,
            loop = AgentLoop(
                streamingClient = StreamingChatClient(current.config),
                // Terminal tools first: they are the primary interface. File tools follow, because
                // editing files is a common follow-up to reading terminal output.
                registry = ToolRegistry(TerminalTools.all() + FileTools.all()),
                config = current.config,
            ),
            terminal = current.control,
        )
    }

    private data class Attachment(
        val control: TerminalControl,
        val config: AiConfig,
    )
}
