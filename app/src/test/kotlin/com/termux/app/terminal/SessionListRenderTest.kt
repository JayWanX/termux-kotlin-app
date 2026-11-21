package com.termux.app.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the session list to a PNG.
 *
 * The list is where every session is visible at once, and the previous version put the status, name,
 * index, title, directory and exit code into one joined line — a layout problem that no string assertion
 * describes. Rendering it makes the result reviewable without a device, the same reason the assistant
 * panel is rendered.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SessionListRenderTest {

    private fun info(
        id: String,
        title: String = "",
        isRunning: Boolean = true,
        exitStatus: Int? = null,
        cwd: String? = null,
        state: TerminalState = TerminalState.Idle(1000L),
    ) = SessionInfo(
        id = id,
        title = title,
        pid = 100,
        isRunning = isRunning,
        exitStatus = exitStatus,
        cwd = cwd,
        isAltBuffer = false,
        state = state,
    )

    private fun sampleRows(): List<SessionRow> = SessionRowBuilder.build(
        sessions = listOf(
            info("s1", title = "~/projects", cwd = "/data/data/com.termux/files/home/projects"),
            info("s2", title = "", cwd = "/data/data/com.termux/files/home", state = TerminalState.Busy(0, 0)),
            info("s3", title = "vim README.md", cwd = "~/projects/app", state = TerminalState.Idle(500L)),
            info("s4", isRunning = false, exitStatus = 127, state = TerminalState.Dead(127)),
        ),
        names = listOf("main", "build", "edit", "crashed"),
        currentIndex = 0,
    )

    /** Guards the blank-list failure: a broken adapter would otherwise pass unnoticed. */
    private fun assertPainted(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("list drew only ${pixels.toHashSet().size} colours", pixels.toHashSet().size > 5)
    }

    @Test
    fun `renders the session list with its action bar`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()

        val adapter = SessionListAdapter(activity)
        adapter.submit(sampleRows())

        // The real content view, so the button bar is included in the picture.
        val content = SessionListDialogView.build(activity) { _, _ -> }
        val list = SessionListDialogView.listOf(content)
        list?.adapter = adapter

        val width = 720
        // Lay out at the target width first: the row-measuring step needs a real width, and a zero-width
        // pass under-measures every row, which shrinks the list to a single row in the picture.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        content.layout(0, 0, width, content.measuredHeight)

        if (list != null) {
            SessionListDialogView.applyListHeight(list, SessionListDialogView.maxListHeightPx(activity))
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = content.measuredHeight.coerceAtLeast(1)
        content.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))

        val out = File("build/session-list").apply { mkdirs() }.resolve("dialog.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertPainted(bitmap)
    }
}
