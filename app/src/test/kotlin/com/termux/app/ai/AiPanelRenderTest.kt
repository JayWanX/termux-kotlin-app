package com.termux.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.termux.shared.terminal.TerminalState
import com.termux.shared.theme.NightMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the assistant panel to a PNG.
 *
 * There is no way to look at the panel from a build machine otherwise, and every visual complaint so far
 * (too bright, crowded steps, text buttons where icons were expected) had to be reported by hand. These
 * tests render the real composable with representative state and write the result to a file, and they
 * also assert the panel draws something at all: a Compose tree that composes but never paints is exactly
 * the class of bug that produced a blank panel on the device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AiPanelRenderTest {

    class HostActivity : AppCompatActivity() {
        lateinit var host: FrameLayout

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            host = FrameLayout(this)
            setContentView(host)
        }
    }

    /** A transcript that exercises every row type the panel can draw. */
    private fun sampleState(expanded: Boolean): SessionState = SessionState(
        messages = listOf(
            TurnMessage(
                role = ChatMsg.ROLE_USER,
                text = "看看当前目录，然后把 build.gradle 里的版本号改成 1.3.0",
            ),
            TurnMessage(
                role = ChatMsg.ROLE_ASSISTANT,
                text = "目录里有 12 个条目：\n\n- `app/`\n- `build.gradle`\n- `settings.gradle`\n\n" +
                    "已把 `versionName` 改为 **1.3.0**。",
                steps = listOf(
                    TurnStep("read_terminal", "24 rows · idle", finished = true),
                    TurnStep("send_command", "ls -la → exit 0", finished = true),
                    TurnStep("read_file", "build.gradle:33 `versionName \"1.2.3\"`", finished = true),
                    TurnStep("edit_file", "versionName \"1.3.0\"", finished = true),
                ),
                stepsExpanded = expanded,
            ),
            TurnMessage(
                role = ChatMsg.ROLE_USER,
                text = "再跑一下测试",
            ),
            TurnMessage(
                role = ChatMsg.ROLE_ASSISTANT,
                text = "测试没能跑起来：Gradle 需要一个可用的 JDK。",
                steps = listOf(
                    TurnStep("send_command", "./gradlew test", finished = true, isError = true),
                ),
            ),
        ),
        isRunning = false,
    )

    private fun render(
        name: String,
        widthPx: Int,
        heightPx: Int,
        state: SessionState,
        nightMode: NightMode = NightMode.TRUE,
    ): Bitmap {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        NightMode.setAppNightMode(nightMode.modeName)

        val view = ComposeView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setContent {
                AiPanel(
                    state = state,
                    terminalState = TerminalState.Idle(silenceMs = 1200L),
                    onSend = {},
                    onStop = {},
                    onConfirm = {},
                    onReset = {},
                    onClose = {},
                    compact = widthPx < 700,
                )
            }
        }
        activity.host.addView(view)

        // Compose needs a measure/layout pass before it produces any pixels, and Robolectric does not
        // run the Choreographer the way a device does, so both steps are driven by hand.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        val out = File("build/panel-render").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** Guards against the blank-panel failure: something must actually be painted. */
    private fun assertPainted(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val distinct = pixels.toHashSet()
        assertTrue(
            "panel drew only ${distinct.size} distinct colour(s); it is probably blank",
            distinct.size > 8,
        )
    }

    @Test
    fun `renders a collapsed transcript`() {
        val bitmap = render("collapsed", 380, 780, sampleState(expanded = false))
        assertPainted(bitmap)
    }

    @Test
    fun `renders an expanded transcript`() {
        val bitmap = render("expanded", 380, 780, sampleState(expanded = true))
        assertPainted(bitmap)
    }

    @Test
    fun `renders the light palette when the app is pinned to light`() {
        // The app theme is independent of the system theme, so this is the case that used to render a
        // light panel over a dark terminal (and vice versa).
        val bitmap = render("light", 380, 780, sampleState(expanded = true), nightMode = NightMode.FALSE)
        assertPainted(bitmap)
    }
}
