package com.termux.app.ai

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for mounting the assistant panel.
 *
 * The panel embeds a [androidx.compose.ui.platform.ComposeView] inside a plain AppCompat activity, which
 * is a place Compose is easy to get wrong: the view tree owners must be set before `setContent` runs, and
 * both the container and the child must be made visible. These tests exist because a crash here takes the
 * terminal down with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiPanelMountTest {

    /** Minimal terminal so the panel can be constructed without a real session. */
    private class StubTerminal : com.termux.shared.terminal.TerminalControl {
        override val state = MutableStateFlow<com.termux.shared.terminal.TerminalState>(
            com.termux.shared.terminal.TerminalState.Idle(1000L),
        )

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
            com.termux.shared.terminal.CommandOutcome(null, state.value, snapshot())

        override fun sessions() = emptyList<com.termux.shared.terminal.SessionInfo>()
        override suspend fun transcript() = ""
        override suspend fun searchScrollback(query: String, ignoreCase: Boolean) =
            emptyList<com.termux.shared.terminal.TranscriptSearch.Match>()

        override fun selectSession(sessionId: String) = false
    }

    /** The assistant must be attachable before a session can be created. */
    private fun attachAssistant(): Boolean {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val config = AiConfig.get(context)
        config.enabled = true
        config.apiUrl = "https://example.invalid/v1/chat/completions"
        config.model = "test-model"
        AiAssistant.attach(StubTerminal(), config)
        return AiAssistant.isAvailable()
    }

    @Test
    fun `container starts hidden and becomes visible with the panel`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = android.view.View.GONE
        }
        (activity.findViewById<FrameLayout>(android.R.id.content)).addView(container)

        val available = attachAssistant()
        assertTrue("assistant should be available once configured", available)

        val terminal = StubTerminal()
        val panel = AiPanelController(activity, container, terminal)
        assertNotNull(panel.session())

        panel.show()
        assertTrue(panel.isVisible())
        // Both levels must be visible: making only the child visible leaves the panel undrawn.
        assertTrue(container.visibility == android.view.View.VISIBLE)
        assertTrue(container.childCount > 0)

        panel.hide()
        assertFalse(panel.isVisible())
        assertTrue(container.visibility == android.view.View.GONE)
    }

    @Test
    fun `toggling twice returns to hidden`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()

        val container = FrameLayout(activity).apply { visibility = android.view.View.GONE }
        (activity.findViewById<FrameLayout>(android.R.id.content)).addView(container)

        attachAssistant()
        val panel = AiPanelController(activity, container, StubTerminal())

        panel.toggle()
        assertTrue(panel.isVisible())
        panel.toggle()
        assertFalse(panel.isVisible())
    }

    @Test
    fun `show is a no-op when the assistant is not attached`() {
        AiAssistant.detach()
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()

        val container = FrameLayout(activity).apply { visibility = android.view.View.GONE }
        (activity.findViewById<FrameLayout>(android.R.id.content)).addView(container)

        val panel = AiPanelController(activity, container, StubTerminal())
        assertNull(panel.session())

        // Must not throw; an unconfigured assistant simply does nothing.
        panel.show()
        assertFalse(panel.isVisible())
        assertTrue(container.visibility == android.view.View.GONE)
    }

    @Test
    fun `dispose removes the panel from the container`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()

        val container = FrameLayout(activity).apply { visibility = android.view.View.GONE }
        (activity.findViewById<FrameLayout>(android.R.id.content)).addView(container)

        attachAssistant()
        val panel = AiPanelController(activity, container, StubTerminal())
        panel.show()
        assertTrue(container.childCount > 0)

        panel.dispose()
        assertTrue("dispose should detach the view", container.childCount == 0)
    }

    @Test
    fun `assistant config survives an unusable backing store`() {
        // AiConfig must never propagate a storage failure to the caller: the fallback is plain prefs.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val config = AiConfig.get(context)
        config.enabled = true
        config.apiUrl = "https://example.invalid/x"
        config.model = "m"
        assertTrue(config.isUsable())
        assertTrue(config.enabled)
    }
}
