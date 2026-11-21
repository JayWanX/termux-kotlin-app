package com.termux.app.ui.compose.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.termux.app.ui.compose.theme.TermuxTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders a documentation code block to a PNG.
 *
 * The defect this guards is purely visual: the copy button used to sit in a row of its own above the
 * code, which added 32dp of empty space to every snippet on a page full of them. Nothing about that is
 * expressible as a string assertion, so the block is drawn instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class CodeBlockRenderTest {

    class HostActivity : AppCompatActivity() {
        lateinit var host: FrameLayout

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            host = FrameLayout(this)
            setContentView(host)
        }
    }

    private fun render(name: String, code: String): Bitmap {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()

        val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setContent {
                TermuxTheme(darkTheme = true) {
                    CodeBlockView(code)
                }
            }
        }
        activity.host.addView(
            view,
            FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        val width = 720
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val height = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, width, height)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val out = File("build/code-block").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun `a single line block is compact`() {
        val bitmap = render("single", "termux-change-repo")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("block did not draw", pixels.toHashSet().size > 3)

        // One line of monospace text plus padding is roughly 40-70dp. The old layout reserved an extra
        // 32dp row for the button, which pushed a single-line block past this bound.
        val density = 1f
        assertTrue(
            "a one-line block is ${bitmap.height}px tall; the copy button should not add a row",
            bitmap.height < 90 * density,
        )
    }

    @Test
    fun `a multi line block keeps its lines`() {
        val bitmap = render(
            "multi",
            "pkg update\npkg upgrade -y\npkg install python",
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("block did not draw", pixels.toHashSet().size > 3)
        assertTrue("three lines need more height than one", bitmap.height > 60)
    }
}
