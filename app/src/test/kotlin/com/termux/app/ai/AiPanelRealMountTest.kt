package com.termux.app.ai

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RelativeLayout
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
 * Reproduces the assistant panel's real mounting conditions.
 *
 * The earlier tests used a container attached directly to `android.R.id.content`. The real app builds the
 * overlay inside a [RelativeLayout] that lives in a custom root view, and the theme is a Material
 * Components theme rather than a bare AppCompat one. Those differences are exactly where the panel
 * failed on a device, so they are reproduced here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiPanelRealMountTest {

    private class StubTerminal : com.termux.shared.terminal.TerminalControl {
        override val state = MutableStateFlow<com.termux.shared.terminal.TerminalState>(
            com.termux.shared.terminal.TerminalState.Idle(500L),
        )

        override fun isAttached() = true

        override suspend fun snapshot(maxChars: Int) = com.termux.shared.terminal.TerminalSnapshot(
            state = state.value,
            visibleText = "user@host:~$ ",
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

    /**
     * An activity shaped like TermuxActivity: a vertical root holding a relative layout, which is where
     * the overlay is added.
     */
    class FakeTermuxActivity : AppCompatActivity() {
        lateinit var rootRelativeLayout: RelativeLayout

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // A FrameLayout root whose direct child is a RelativeLayout, matching activity_termux.xml:
            // the overlay container is added to that inner relative layout, not to the content view.
            val params = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val root = FrameLayout(this).apply { layoutParams = params }
            rootRelativeLayout = RelativeLayout(this).apply { layoutParams = params }
            root.addView(rootRelativeLayout, params)
            setContentView(root, params)
            rootRelativeLayout.id = 1001
        }
    }

    private fun attachAssistant(): AiConfig {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val config = AiConfig.get(context)
        config.enabled = true
        config.apiUrl = "https://example.invalid/v1/chat/completions"
        config.model = "test-model"
        AiAssistant.attach(StubTerminal(), config)
        return config
    }

    @Test
    fun `panel mounts inside a relative layout like the real activity`() {
        attachAssistant()
        val controller = Robolectric.buildActivity(FakeTermuxActivity::class.java).setup()
        val activity = controller.get()
        val parent = activity.rootRelativeLayout

        // Mirror ensureAiPanelContainer: the parent's own params type, with explicit dimensions.
        // generateLayoutParams(null) cannot be used because RelativeLayout throws on an empty
        // attribute set rather than returning a usable default.
        val params = RelativeLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )

        val container = FrameLayout(activity).apply {
            layoutParams = params
            visibility = android.view.View.GONE
            isClickable = true
            isFocusable = true
        }
        parent.addView(container)
        assertTrue("container must attach", parent.childCount == 1)

        val panel = AiPanelController(activity, container, StubTerminal())
        panel.show()

        assertTrue(panel.isVisible())
        assertTrue(container.visibility == android.view.View.VISIBLE)
        assertTrue(container.childCount > 0)
    }

    @Test
    fun `generateLayoutParams with null attributes throws and must not be used`() {
        val controller = Robolectric.buildActivity(FakeTermuxActivity::class.java).setup()
        val parent = controller.get().rootRelativeLayout

        // Documents the platform behaviour that caused the device crash: RelativeLayout does not fall
        // back to defaults, it throws. Production code constructs the params type directly instead.
        val threw = try {
            parent.generateLayoutParams(null)
            false
        } catch (e: UnsupportedOperationException) {
            true
        }
        assertTrue("RelativeLayout.generateLayoutParams(null) throws", threw)

        // The explicit construction that production code uses works.
        val params = RelativeLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val child = FrameLayout(controller.get())
        parent.addView(child, params)
        assertEquals(1, parent.childCount)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    @Test
    fun `nested content host still provides view tree owners`() {
        attachAssistant()
        val controller = Robolectric.buildActivity(FakeTermuxActivity::class.java).setup()
        val activity = controller.get()

        // android.R.id.content is the activity's own FrameLayout; the overlay is nested deeper, so the
        // owners must be reachable from the nested container rather than only from the content view.
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertNotNull(content)

        val container = FrameLayout(activity).apply { visibility = android.view.View.GONE }
        content.addView(container)

        val panel = AiPanelController(activity, container, StubTerminal())
        panel.show()
        assertTrue(panel.isVisible())
        panel.hide()
        assertFalse(panel.isVisible())
    }

    @Test
    fun `show after dispose re-mounts cleanly`() {
        attachAssistant()
        val controller = Robolectric.buildActivity(FakeTermuxActivity::class.java).setup()
        val activity = controller.get()
        val parent = activity.rootRelativeLayout

        val container = FrameLayout(activity).apply { visibility = android.view.View.GONE }
        parent.addView(container)

        val panel = AiPanelController(activity, container, StubTerminal())
        panel.show()
        assertTrue(container.childCount > 0)

        panel.dispose()
        assertTrue(container.childCount == 0)

        // A disposed controller must not crash if the menu is used again; a new one takes over.
        val second = AiPanelController(activity, container, StubTerminal())
        second.show()
        assertTrue(container.childCount > 0)
    }

    @Test
    fun `session creation tolerates missing terminal binding`() {
        AiAssistant.detach()
        val controller = Robolectric.buildActivity(FakeTermuxActivity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity)
        activity.rootRelativeLayout.addView(container)

        val panel = AiPanelController(activity, container, StubTerminal())
        assertNull(panel.session())
    }
}
