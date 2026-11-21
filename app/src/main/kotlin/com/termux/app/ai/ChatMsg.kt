package com.termux.app.ai

/** A chat message in the OpenAI wire format. */
data class ChatMsg(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(text: String) = ChatMsg(ROLE_SYSTEM, text)
        fun user(text: String) = ChatMsg(ROLE_USER, text)
        fun assistant(text: String?, toolCalls: List<ToolCall>? = null) =
            ChatMsg(ROLE_ASSISTANT, text, toolCalls)
        fun tool(callId: String, text: String) = ChatMsg(ROLE_TOOL, text, toolCallId = callId)
    }
}

/** A tool invocation requested by the model. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** One completion response. */
data class Completion(
    val content: String?,
    val toolCalls: List<ToolCall>,
)

/**
 * Normalises message roles for providers that reject mid-conversation system messages.
 *
 * vLLM and several Qwen deployments require that `system` appear only at the start; any later system
 * message must be demoted to `user`. Leading system messages are merged into one.
 *
 * Kept as a top-level function because both the streaming client and the tests need it, and neither
 * should have to instantiate a client to reach it.
 */
fun normalizeMessages(messages: List<ChatMsg>): List<ChatMsg> {
    if (messages.isEmpty()) return messages
    val out = mutableListOf<ChatMsg>()
    val leadingSystem = mutableListOf<String>()
    var index = 0
    while (index < messages.size && messages[index].role == ChatMsg.ROLE_SYSTEM) {
        messages[index].content?.trim()?.takeIf { it.isNotEmpty() }?.let { leadingSystem += it }
        index++
    }
    if (leadingSystem.isNotEmpty()) {
        out += ChatMsg.system(leadingSystem.joinToString("\n\n"))
    }
    while (index < messages.size) {
        val msg = messages[index]
        out += if (msg.role == ChatMsg.ROLE_SYSTEM) {
            ChatMsg(role = ChatMsg.ROLE_USER, content = msg.content.orEmpty())
        } else {
            msg
        }
        index++
    }
    return out
}
