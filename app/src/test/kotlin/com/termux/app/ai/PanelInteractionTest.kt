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
 * Tests for dismissing the open panel.
 *
 * The open gesture and its thresholds live in [EdgeSwipeTest]; this covers only the dismiss direction,
 * which is driven by the Compose drag inside the panel rather than by the root view.
 */
@RunWith(RobolectricTestRunner::class)
class EdgeDismissTest {

    private val threshold = 40f

    @Test
    fun `swiping right inside the panel dismisses it`() {
        assertTrue(EdgeSwipe.shouldClose(accumulatedDx = 80f, thresholdPx = threshold))
    }

    @Test
    fun `swiping left inside the panel does not dismiss it`() {
        assertFalse(EdgeSwipe.shouldClose(accumulatedDx = -100f, thresholdPx = threshold))
    }

    @Test
    fun `a short swipe inside the panel does not dismiss it`() {
        assertFalse(EdgeSwipe.shouldClose(accumulatedDx = 10f, thresholdPx = threshold))
    }

    @Test
    fun `a degenerate threshold never dismisses`() {
        // A zero threshold would close the panel on any incidental rightward movement.
        assertFalse(EdgeSwipe.shouldClose(accumulatedDx = 100f, thresholdPx = 0f))
    }
}

/** Tests for the side-panel width rule. */
@RunWith(RobolectricTestRunner::class)
class AssistantPanelWidthTest {

    @Test
    fun `panel leaves most of the screen to the terminal`() {
        listOf(720, 1080, 1440, 2560).forEach { screen ->
            listOf(1f, 2f, 2.75f, 3f, 4f).forEach { density ->
                val panel = PanelMetrics.widthPx(screen, density)
                assertTrue(
                    "screen=$screen density=$density panel=$panel must stay well under half",
                    panel < screen * 0.45,
                )
            }
        }
    }

    @Test
    fun `panel follows the target fraction when the floor does not apply`() {
        // 1080px at density 3: the 200dp floor is clamped to 30% = 324px, below the 346px target.
        val panel = PanelMetrics.widthPx(1080, 3f)
        assertEquals((1080 * PanelMetrics.WIDTH_FRACTION).toInt(), panel)
    }

    @Test
    fun `floor keeps the panel usable on a low density screen`() {
        // 720px at density 1: 32% would be 230px, but the 200dp floor and the 30% cap agree on 216px.
        val panel = PanelMetrics.widthPx(720, 1f)
        assertTrue("panel=$panel must not be a sliver", panel >= 200)
    }

    @Test
    fun `tablet panel is capped at the ceiling`() {
        val panel = PanelMetrics.widthPx(2560, 2f)
        assertEquals(PanelMetrics.CEILING_DP * 2, panel)
    }

    @Test
    fun `degenerate screen width does not produce a negative panel`() {
        assertEquals(0, PanelMetrics.widthPx(0, 3f))
    }
}
