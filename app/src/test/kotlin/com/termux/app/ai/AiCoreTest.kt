package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject



@RunWith(RobolectricTestRunner::class)
class ToolRegistryTest {

    private class FakeTool(
        override val spec: ToolSpec,
        private val behaviour: suspend () -> ToolResult,
    ) : AgentTool {
        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            behaviour()
    }

    private fun spec(name: String, risk: RiskLevel) = ToolSpec(
        name = name,
        description = "test",
        parameters = JSONObject().put("type", "object"),
        risk = risk,
    )

    private val context = object : ToolContext {
        override val terminal: com.termux.shared.terminal.TerminalControl
            get() = throw UnsupportedOperationException("not used")
        override val fileRoots: List<String> = listOf("/tmp")
        override val sessionId: String = "test"
        override suspend fun requestConfirmation(summary: String): Boolean = true
    }

    @Test
    fun `unknown tool is reported as destructive so it can never run silently`() {
        val registry = ToolRegistry(FakeTool(spec("known", RiskLevel.SAFE)) { ToolResult.Ok("ok") })
        assertEquals(RiskLevel.DESTRUCTIVE, registry.riskOf("missing"))
    }

    @Test
    fun `declared risk is reported`() {
        val registry = ToolRegistry(
            FakeTool(spec("a", RiskLevel.SAFE)) { ToolResult.Ok("") },
            FakeTool(spec("b", RiskLevel.MUTATING)) { ToolResult.Ok("") },
        )
        assertEquals(RiskLevel.SAFE, registry.riskOf("a"))
        assertEquals(RiskLevel.MUTATING, registry.riskOf("b"))
    }

    @Test
    fun `invoking an unknown tool yields a failure rather than throwing`() = kotlinx.coroutines.runBlocking {
        val registry = ToolRegistry()
        val result = registry.invoke("nope", JSONObject(), context)
        assertTrue(result is ToolResult.Failed)
    }

    @Test
    fun `an exception inside a tool becomes a failure result`() = kotlinx.coroutines.runBlocking {
        val registry = ToolRegistry(
            FakeTool(spec("boom", RiskLevel.SAFE)) { throw IllegalStateException("bang") },
        )
        val result = registry.invoke("boom", JSONObject(), context)
        assertTrue(result is ToolResult.Failed)
        assertEquals("Error: bang", result.asToolText())
    }

    @Test
    fun `specs expose the declared names`() {
        val registry = ToolRegistry(
            FakeTool(spec("x", RiskLevel.SAFE)) { ToolResult.Ok("") },
            FakeTool(spec("y", RiskLevel.SAFE)) { ToolResult.Ok("") },
        )
        assertEquals(setOf("x", "y"), registry.names())
        assertEquals(2, registry.specs().size)
    }

    @Test
    fun `denied results render distinctly from failures`() {
        assertTrue(ToolResult.Denied("because").asToolText().startsWith("Refused:"))
        assertTrue(ToolResult.Failed("because").asToolText().startsWith("Error:"))
        assertEquals("fine", ToolResult.Ok("fine").asToolText())
    }
}

class OpenAiNormalizeTest {

    @Test
    fun `leading system messages are merged`() {
        val input = listOf(
            ChatMsg.system("first"),
            ChatMsg.system("second"),
            ChatMsg.user("hi"),
        )
        val output = normalizeMessages(input)
        assertEquals(2, output.size)
        assertEquals(ChatMsg.ROLE_SYSTEM, output[0].role)
        assertEquals("first\n\nsecond", output[0].content)
        assertEquals(ChatMsg.ROLE_USER, output[1].role)
    }

    @Test
    fun `mid conversation system is demoted to user`() {
        val input = listOf(
            ChatMsg.system("rules"),
            ChatMsg.user("hello"),
            ChatMsg.system("mid-conversation note"),
        )
        val output = normalizeMessages(input)
        assertEquals(ChatMsg.ROLE_SYSTEM, output[0].role)
        assertEquals(ChatMsg.ROLE_USER, output[1].role)
        assertEquals(ChatMsg.ROLE_USER, output[2].role)
        assertEquals("mid-conversation note", output[2].content)
    }

    @Test
    fun `empty history is returned unchanged`() {
        assertTrue(normalizeMessages(emptyList()).isEmpty())
    }

    @Test
    fun `blank leading system messages are dropped`() {
        val input = listOf(
            ChatMsg.system("   "),
            ChatMsg.user("hi"),
        )
        val output = normalizeMessages(input)
        assertEquals(1, output.size)
        assertEquals(ChatMsg.ROLE_USER, output[0].role)
    }

    @Test
    fun `tool call ids survive normalisation`() {
        val input = listOf(
            ChatMsg.system("rules"),
            ChatMsg.tool("call_1", "result"),
        )
        val output = normalizeMessages(input)
        assertEquals("call_1", output[1].toolCallId)
    }
}
