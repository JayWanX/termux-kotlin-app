package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the panel's step collapsing.
 *
 * Steps are collapsed by default because they are diagnostic detail; a dozen expanded command lines
 * bury the reply the user asked for. The summary line therefore has to carry enough information on its
 * own, which is what these cases pin down.
 */
@RunWith(RobolectricTestRunner::class)
class TurnMessageStepsTest {

    private fun steps(vararg specs: Pair<String, Boolean>): List<TurnStep> =
        specs.mapIndexed { i, (label, isError) ->
            TurnStep(label = label, detail = if (isError) "Error: x" else "ok", finished = true, isError = isError)
        }

    private fun message(steps: List<TurnStep> = emptyList()) = TurnMessage(
        role = ChatMsg.ROLE_ASSISTANT,
        text = "done",
        steps = steps,
    )

    @Test
    fun `steps are collapsed by default`() {
        val msg = message(steps("read_terminal" to false))
        assertFalse("steps must start collapsed", msg.stepsExpanded)
    }

    @Test
    fun `summary is empty when there are no steps`() {
        assertEquals("", message().stepsSummary)
    }

    @Test
    fun `summary counts steps`() {
        val msg = message(steps("a" to false, "b" to false))
        assertTrue(msg.stepsSummary.contains("2 steps"))
    }

    @Test
    fun `summary uses singular for one step`() {
        assertTrue(message(steps("a" to false)).stepsSummary.contains("1 step"))
        assertFalse(message(steps("a" to false)).stepsSummary.contains("1 steps"))
    }

    @Test
    fun `summary reports completion`() {
        assertTrue(message(steps("a" to false)).stepsSummary.startsWith("Done"))
    }

    @Test
    fun `summary flags failures`() {
        val msg = message(steps("ok" to false, "bad" to true))
        assertTrue(msg.stepsSummary.contains("1 failed"))
    }

    @Test
    fun `no failure flag when all succeed`() {
        assertFalse(message(steps("a" to false)).stepsSummary.contains("failed"))
    }

    @Test
    fun `expanded flag is independent of content`() {
        val msg = message(steps("a" to false)).copy(stepsExpanded = true)
        assertTrue(msg.stepsExpanded)
        // The summary is still meaningful while expanded.
        assertTrue(msg.stepsSummary.contains("1 step"))
    }
}

/** Tests for the assistant session's step toggling. */
@RunWith(RobolectricTestRunner::class)
class AgentSessionStepToggleTest {

    private class StubTerminal : com.termux.shared.terminal.TerminalControl {
        override val state = kotlinx.coroutines.flow.MutableStateFlow<
            com.termux.shared.terminal.TerminalState,
            >(com.termux.shared.terminal.TerminalState.Idle(1L))

        override fun isAttached() = true

        override suspend fun snapshot(maxChars: Int) = com.termux.shared.terminal.TerminalSnapshot(
            state = state.value,
            visibleText = "",
            scrollbackTail = "",
            cursorRow = 0,
            cursorCol = 0,
            rows = 24,
            cols = 80,
        )

        override suspend fun send(text: String, enter: Boolean) = Unit
        override suspend fun sendKey(key: com.termux.shared.terminal.TerminalKey) = Unit
        override suspend fun awaitIdle(timeoutMs: Long) = state.value

        override suspend fun sendCommandWithSentinel(command: String, timeoutMs: Long) =
            com.termux.shared.terminal.CommandOutcome(0, state.value, snapshot())

        override fun sessions() = emptyList<com.termux.shared.terminal.SessionInfo>()
        override suspend fun transcript() = ""
        override suspend fun searchScrollback(query: String, ignoreCase: Boolean) =
            emptyList<com.termux.shared.terminal.TranscriptSearch.Match>()

        override fun selectSession(sessionId: String) = false
    }

    private fun newSession(): AgentSession {
        val config = AiConfig.get(org.robolectric.RuntimeEnvironment.getApplication())
        return AgentSession(
            config = config,
            loop = AgentLoop(
                streamingClient = StreamingChatClient(config),
                registry = ToolRegistry(),
                config = config,
            ),
            terminal = StubTerminal(),
        )
    }

    @Test
    fun `toggling a message with no steps is a no-op`() {
        val session = newSession()
        // Must not throw when the id is unknown.
        session.toggleSteps("missing-id")
        session.setStepsExpanded("missing-id", true)
        assertTrue(session.state.value.messages.isEmpty())
    }

    @Test
    fun `initial state has no messages and no pending confirmation`() {
        val session = newSession()
        assertTrue(session.state.value.messages.isEmpty())
        assertFalse(session.state.value.isRunning)
        assertTrue(session.state.value.pendingConfirmation == null)
    }
}

/**
 * Tests for the swipe threshold logic behind the edge gesture.
 *
 * Extracted as a pure decision so the behaviour is pinned without synthesising touch events.
 */
@RunWith(RobolectricTestRunner::class)
class EdgeGestureDecisionTest {

    /** Mirrors the production decision in installAssistantEdgeGesture. */
    private fun decide(
        panelShown: Boolean,
        deltaX: Float,
        threshold: Float,
    ): String = when {
        !panelShown && deltaX < -threshold -> "open"
        panelShown && deltaX > threshold -> "close"
        else -> "none"
    }

    @Test
    fun `swiping left when closed opens the panel`() {
        assertEquals("open", decide(panelShown = false, deltaX = -80f, threshold = 40f))
    }

    @Test
    fun `swiping right when open closes the panel`() {
        assertEquals("close", decide(panelShown = true, deltaX = 80f, threshold = 40f))
    }

    @Test
    fun `short swipes are ignored`() {
        assertEquals("none", decide(panelShown = false, deltaX = -10f, threshold = 40f))
        assertEquals("none", decide(panelShown = true, deltaX = 10f, threshold = 40f))
    }

    @Test
    fun `swiping the wrong direction does nothing`() {
        assertEquals("none", decide(panelShown = false, deltaX = 100f, threshold = 40f))
        assertEquals("none", decide(panelShown = true, deltaX = -100f, threshold = 40f))
    }

    @Test
    fun `exactly at the threshold does not trigger`() {
        assertEquals("none", decide(panelShown = false, deltaX = -40f, threshold = 40f))
    }

    @Test
    fun `just past the threshold triggers`() {
        assertEquals("open", decide(panelShown = false, deltaX = -41f, threshold = 40f))
    }
}

/** Tests for the narrower side-panel width rule. */
@RunWith(RobolectricTestRunner::class)
class AssistantPanelNarrowWidthTest {

    /** Mirrors the production calculation. */
    private fun widthFor(
        screenWidth: Int,
        density: Float,
        fraction: Float = 0.34f,
        maxFraction: Float = 0.36f,
    ): Int {
        fun dp(v: Int) = (v * density).toInt()
        val target = (screenWidth * fraction).toInt()
        val minWidth = minOf(dp(240), (screenWidth * maxFraction).toInt())
        val maxWidth = minOf(dp(400), screenWidth)
        return target.coerceIn(minWidth, maxWidth)
    }

    @Test
    fun `panel leaves most of the screen to the terminal`() {
        listOf(720, 1080, 1440, 2560).forEach { screen ->
            val panel = widthFor(screen, 3f)
            assertTrue(
                "screen=$screen panel=$panel must leave more than half",
                panel < screen * 0.4,
            )
        }
    }

    @Test
    fun `panel stays wide enough to be usable`() {
        val panel = widthFor(1080, 3f)
        assertTrue("panel=$panel should be at least a third", panel >= 1080 * 0.3)
    }

    @Test
    fun `tablet panel is capped`() {
        val panel = widthFor(2560, 2f)
        assertEquals(800, panel)
    }
}
