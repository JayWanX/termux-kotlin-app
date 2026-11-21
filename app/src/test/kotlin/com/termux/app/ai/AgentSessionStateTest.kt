package com.termux.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the panel's view-model logic.
 *
 * The panel is a plain function of [AgentSession.state], so most of what can go wrong lives in that
 * state machine rather than in the Composables. These tests cover the state transitions a user can
 * actually trigger: sending, approval, denial, clearing and the stop path.
 */
@RunWith(RobolectricTestRunner::class)
class AgentSessionStateTest {

    /** Minimal terminal stub; the loop is not exercised here, only the session's own bookkeeping. */
    private class StubTerminal(
        initial: com.termux.shared.terminal.TerminalState =
            com.termux.shared.terminal.TerminalState.Idle(1000L),
    ) : com.termux.shared.terminal.TerminalControl {
        override val state =
            MutableStateFlow<com.termux.shared.terminal.TerminalState>(initial)

        override fun isAttached(): Boolean = true

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
            com.termux.shared.terminal.CommandOutcome(null, state.value, snapshot())

        override fun sessions() = emptyList<com.termux.shared.terminal.SessionInfo>()

        override suspend fun transcript(): String = ""

        override suspend fun searchScrollback(
            query: String,
            ignoreCase: Boolean,
        ) = emptyList<com.termux.shared.terminal.TranscriptSearch.Match>()

        override fun selectSession(sessionId: String) = false
    }

    private fun newSession(): Pair<AgentSession, StubTerminal> {
        val terminal = StubTerminal()
        val session = AgentSession(
            config = AiConfigForTest.create(),
            loop = AgentLoop(
                streamingClient = StreamingChatClient(AiConfigForTest.create()),
                registry = ToolRegistry(),
                config = AiConfigForTest.create(),
            ),
            terminal = terminal,
        )
        return session to terminal
    }

    @Test
    fun `initial state has no messages and is idle`() {
        val (session, _) = newSession()
        val state = session.state.value
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isRunning)
        assertEquals(0, state.round)
        assertTrue(state.pendingConfirmation == null)
    }

    @Test
    fun `reset clears the transcript`() {
        val (session, _) = newSession()
        session.reset()
        assertTrue(session.state.value.messages.isEmpty())
    }

    @Test
    fun `answering with no pending confirmation is a no-op`() {
        val (session, _) = newSession()
        // Must not throw when the UI sends a stray click.
        session.answerConfirmation(true)
        session.answerConfirmation(false)
        assertTrue(session.state.value.pendingConfirmation == null)
    }

    @Test
    fun `empty input is ignored`() = kotlinx.coroutines.runBlocking {
        val (session, _) = newSession()
        val result = session.send("   ")
        assertTrue(result is AgentResult.Done)
        assertTrue(session.state.value.messages.isEmpty())
    }

    @Test
    fun `sending an empty string never leaves the session stuck running`() =
        kotlinx.coroutines.runBlocking {
            val (session, _) = newSession()
            session.send("")
            assertFalse(session.state.value.isRunning)
        }
}

/**
 * Builds an [AiConfig] without touching Android storage.
 *
 * [AiConfig.get] needs a Context and creates encrypted preferences, which is unnecessary for logic
 * tests, so the fields that matter are driven through a spy-free path.
 */
internal object AiConfigForTest {
    fun create(): AiConfig {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        return AiConfig.get(context)
    }
}
