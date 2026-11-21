package com.termux.app.ai

import com.termux.shared.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
 * A minimal OpenAI-compatible chat-completions client with function-calling support.
 *
 * Only one protocol is implemented (OpenAI chat completions) because every mainstream provider either
 * speaks it natively or offers a compatibility endpoint. That keeps this file small and the behaviour
 * predictable across providers.
 */
class OpenAiClient(
    private val config: AiConfig,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val LOG_TAG = "OpenAiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Normalises message roles for providers that reject mid-conversation system messages.
         *
         * vLLM and several Qwen deployments require that `system` appear only at the start; any later
         * system message must be demoted to `user`. Leading system messages are merged into one.
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
    }

    /**
     * Performs one non-streaming completion.
     *
     * Non-streaming is deliberate: tool calls arrive as a complete object, so the loop can act on them
     * immediately. Streaming would require accumulating partial `tool_calls` deltas, which is a
     * meaningful amount of extra state for little benefit in this context.
     */
    suspend fun complete(
        messages: List<ChatMsg>,
        tools: List<ToolSpec>,
    ): Result<Completion> = withContext(Dispatchers.IO) {
        val apiUrl = config.apiUrl
        val model = config.model
        if (apiUrl.isBlank() || model.isBlank()) {
            return@withContext Result.failure(IllegalStateException("assistant is not configured"))
        }

        val body = buildRequestBody(messages, model, tools).toString()
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))

        val key = config.apiKey
        if (key.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: ${text.take(500)}"),
                    )
                }
                Result.success(parseCompletion(text))
            }
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "completion failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun buildRequestBody(
        messages: List<ChatMsg>,
        model: String,
        tools: List<ToolSpec>,
    ): JSONObject {
        val array = JSONArray()
        normalizeMessages(messages).forEach { array.put(messageToJson(it)) }

        return JSONObject()
            .put("model", model)
            .put("messages", array)
            .put("stream", false)
            .apply {
                if (tools.isNotEmpty()) {
                    val toolArray = JSONArray()
                    tools.forEach { toolArray.put(it.toJson()) }
                    put("tools", toolArray)
                    put("tool_choice", "auto")
                }
            }
    }

    private fun messageToJson(msg: ChatMsg): JSONObject {
        val obj = JSONObject().put("role", msg.role)
        when (msg.role) {
            ChatMsg.ROLE_ASSISTANT -> obj.put("content", msg.content ?: JSONObject.NULL)
            else -> obj.put("content", msg.content.orEmpty())
        }
        if (!msg.toolCalls.isNullOrEmpty()) {
            val calls = JSONArray()
            msg.toolCalls.forEach { call ->
                calls.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", call.name)
                                .put("arguments", call.arguments),
                        ),
                )
            }
            obj.put("tool_calls", calls)
        }
        if (!msg.toolCallId.isNullOrBlank()) {
            obj.put("tool_call_id", msg.toolCallId)
        }
        return obj
    }

    private fun parseCompletion(text: String): Completion {
        val message = JSONObject(text)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        val content = if (message.isNull("content")) null else message.optString("content").takeIf {
            it.isNotEmpty()
        }

        val toolCalls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val function = item.getJSONObject("function")
                toolCalls += ToolCall(
                    id = item.optString("id", "call_$i"),
                    name = function.optString("name"),
                    arguments = function.optString("arguments", "{}"),
                )
            }
        }
        return Completion(content = content, toolCalls = toolCalls)
    }
}
