package com.termux.app.ai

import org.json.JSONObject

/**
 * How dangerous a tool is.
 *
 * The assistant is only useful if it can also be stopped from doing damage, so every tool declares its
 * risk up front and the loop consults it before invoking anything.
 */
enum class RiskLevel {
    /** Read-only and side-effect free. */
    SAFE,

    /** Changes state (writes a file, runs a command) but is reversible or expected. */
    MUTATING,

    /** Can destroy data or interrupt work. Always requires confirmation. */
    DESTRUCTIVE,
}

/**
 * The declaration of a tool, in the shape the chat-completions API expects.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val risk: RiskLevel,
) {
    /** Converts to the `tools` entry of an OpenAI request. */
    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters),
        )
}

/** The result of invoking a tool. Always ends up as text the model can read. */
sealed interface ToolResult {
    data class Ok(val text: String) : ToolResult
    data class Failed(val message: String) : ToolResult
    data class Denied(val reason: String) : ToolResult

    /** Renders the result as the `content` of a `role: "tool"` message. */
    fun asToolText(): String = when (this) {
        is Ok -> text
        is Failed -> "Error: $message"
        is Denied -> "Refused: $reason"
    }
}

/** Everything a tool needs in order to act. */
interface ToolContext {
    /** The terminal the assistant operates on. */
    val terminal: com.termux.shared.terminal.TerminalControl

    /**
     * Roots the filesystem tools may touch, most specific first.
     *
     * The first entry is treated as the user's home directory and is what `~` expands to. Anything
     * outside every root is refused outright rather than silently clamped.
     */
    val fileRoots: List<String>

    /** A stable identifier for the current session, used to keep backups tidy. */
    val sessionId: String

    /** Asks the user to approve an action. Returns false when the user declines. */
    suspend fun requestConfirmation(summary: String): Boolean

    /** Convenience accessor kept for tools that only care about the primary root. */
    val homeDir: String get() = fileRoots.firstOrNull() ?: ""
}

/** A single callable tool. */
interface AgentTool {
    val spec: ToolSpec
    suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult
}

/**
 * The set of tools exposed to the model.
 *
 * Kept intentionally tiny: an assistant that can read the screen and run commands covers almost every
 * real use, and every extra tool is another way to do damage.
 */
class ToolRegistry(private val tools: List<AgentTool>) {

    constructor(vararg tools: AgentTool) : this(tools.toList())

    /** Specs for the request payload. */
    fun specs(): List<ToolSpec> = tools.map { it.spec }

    fun riskOf(name: String): RiskLevel =
        tools.firstOrNull { it.spec.name == name }?.spec?.risk ?: RiskLevel.DESTRUCTIVE

    fun names(): Set<String> = tools.map { it.spec.name }.toSet()

    /**
     * Invokes a tool by name, converting any failure into a [ToolResult].
     *
     * A path-confinement violation is reported as a **refusal**, not an error: it is a deliberate
     * policy decision, and telling the model "refused" stops it from retrying the same path. Handling
     * it here rather than in each tool means no tool can accidentally leak the exception.
     */
    suspend fun invoke(
        name: String,
        arguments: JSONObject,
        context: ToolContext,
    ): ToolResult {
        val tool = tools.firstOrNull { it.spec.name == name }
            ?: return ToolResult.Failed("unknown tool `$name`")
        return try {
            tool.invoke(arguments, context)
        } catch (e: FileSandbox.OutsideRootsException) {
            ToolResult.Denied(
                "`${e.requested}` is outside the permitted roots (${e.roots.joinToString()})",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.Failed(e.message ?: "invalid arguments")
        } catch (e: Exception) {
            ToolResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }
}
