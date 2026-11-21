package com.termux.app.ai

import com.termux.shared.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A streaming OpenAI-compatible chat client.
 *
 * Streaming matters for perceived latency: without it the panel shows nothing until the whole answer
 * arrives, which for a long reply looks like a hang. With it, text appears as it is generated.
 *
 * Two protocol details drive the implementation:
 *
 * 1. The reply arrives as server-sent events; each `data:` line carries a delta.
 * 2. **Tool calls also stream**, as fragments keyed by index. They cannot be handed to the loop until
 *    complete, so they are accumulated here and emitted once the stream ends. Dropping them would make
 *    streaming and tools mutually exclusive, which is the limitation this design avoids.
 */
class StreamingChatClient(
    private val config: AiConfig,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val LOG_TAG = "StreamingChatClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Streaming responses stay open for the whole generation, so the read timeout is generous.
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Runs one streaming turn.
     *
     * Emits text deltas as they arrive. When the stream finishes, [Completion] carries the assembled
     * content and any tool calls that were requested.
     */
    fun stream(
        messages: List<ChatMsg>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
    ): Flow<Result<Completion>> = callbackFlow {
        val apiUrl = config.apiUrl
        val model = config.model
        if (apiUrl.isBlank() || model.isBlank()) {
            trySend(Result.failure(IllegalStateException("assistant is not configured")))
            close()
            return@callbackFlow
        }

        val body = buildRequestBody(messages, model, tools).toString()
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply { config.apiKey.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }
            .post(body.toRequestBody(JSON))
            .build()

        val call = client.newCall(request)

        // Cancel the HTTP call if the collector goes away mid-stream.
        trySend(Result.failure(IllegalStateException())).let { /* no-op: placeholder for symmetry */ }

        var contentBuilder = StringBuilder()
        val toolAccumulator = ToolCallAccumulator()

        val thread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string().orEmpty()
                        trySend(Result.failure(IllegalStateException("HTTP ${response.code}: ${errorBody.take(500)}")))
                        return@use
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        trySend(Result.failure(IllegalStateException("empty response")))
                        return@use
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        if (!line.startsWith("data:")) continue

                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        if (!payload.startsWith("{")) continue

                        val choice = runCatching {
                            JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                        }.getOrNull() ?: continue

                        val delta = choice.optJSONObject("delta") ?: continue

                        // Text comes as a delta; accumulate and forward it for live rendering.
                        val text = delta.optString("content", "")
                        if (text.isNotEmpty()) {
                            contentBuilder.append(text)
                            onDelta(text)
                        }

                        // Tool calls arrive as partial objects keyed by index.
                        delta.optJSONArray("tool_calls")?.let { toolAccumulator.add(it) }
                    }
                }

                val completion = Completion(
                    content = contentBuilder.toString().ifEmpty { null },
                    toolCalls = toolAccumulator.build(),
                )
                trySend(Result.success(completion))
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "stream failed: ${e.message}")
                trySend(Result.failure(e))
            } finally {
                close()
            }
        }.apply { isDaemon = true }
        thread.start()

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

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
            .put("stream", true)
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
                            JSONObject().put("name", call.name).put("arguments", call.arguments),
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
}

/**
 * Assembles tool calls from streamed fragments.
 *
 * The wire format spreads one logical call across many events: the id and name arrive once, while
 * `arguments` is delivered in arbitrary string chunks. Because the pieces are indexed rather than
 * ordered, they cannot simply be concatenated in arrival order.
 */
class ToolCallAccumulator {

    private data class Partial(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val partials = sortedMapOf<Int, Partial>()

    /** Merges one `tool_calls` delta array. */
    fun add(deltas: JSONArray) {
        for (i in 0 until deltas.length()) {
            val delta = deltas.optJSONObject(i) ?: continue
            val index = delta.optInt("index", partials.size)
            val partial = partials.getOrPut(index) { Partial() }

            delta.optString("id", "").takeIf { it.isNotEmpty() }?.let { partial.id = it }
            delta.optJSONObject("function")?.let { function ->
                function.optString("name", "").takeIf { it.isNotEmpty() }?.let { partial.name = it }
                function.optString("arguments", "").takeIf { it.isNotEmpty() }?.let {
                    partial.arguments.append(it)
                }
            }
        }
    }

    /** The completed calls, ordered by index. Incomplete entries are skipped. */
    fun build(): List<ToolCall> = partials.entries.mapNotNull { (index, partial) ->
        if (partial.name.isEmpty()) return@mapNotNull null
        val arguments = partial.arguments.toString().ifBlank { "{}" }
        ToolCall(
            id = partial.id.ifEmpty { "call_$index" },
            name = partial.name,
            arguments = arguments,
        )
    }

    /** True when at least one call was seen, used to decide whether the loop must act. */
    fun isEmpty(): Boolean = partials.isEmpty()
}
