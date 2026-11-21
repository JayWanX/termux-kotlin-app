package com.termux.app.terminal

import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the session list's actions and content view.
 *
 * The list previously used AlertDialog's stock button bar, whose labels the platform theme rendered in
 * caps and whose callbacks always closed the dialog. Both are asserted here, because both are properties
 * of the layout rather than of any logic a reviewer would naturally re-check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionListDialogTest {

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

    @Test
    fun `the bar has exactly one action per intent`() {
        val actions = SessionListActions.bar(activity())
        val kinds = actions.map { it.action }
        assertEquals("duplicate actions are what caused the earlier confusion", kinds.size, kinds.toSet().size)
        assertTrue(kinds.containsAll(listOf(SessionListAction.MORE, SessionListAction.NEW, SessionListAction.CLOSE)))
    }

    @Test
    fun `labels are not upper case`() {
        // The platform dialog theme set textAllCaps, so these read as "MORE", "NEW", "CLOSE".
        SessionListActions.bar(activity()).forEach { button ->
            assertFalse(
                "`${button.label}` must be sentence case",
                button.label == button.label.uppercase(),
            )
        }
    }

    @Test
    fun `close is the emphasized action`() {
        val close = SessionListActions.bar(activity()).first { it.action == SessionListAction.CLOSE }
        assertTrue(close.emphasized)
    }

    @Test
    fun `there is no duplicate new-session entry`() {
        // "New system session" in the More menu called the same thing as the New button.
        val labels = SessionListActions.bar(activity()).map { it.label.lowercase() }
        assertEquals("only one way to create a session", 1, labels.count { it.contains("new") })
    }

    @Test
    fun `the content view exposes a list and a button bar`() {
        val context = activity()
        val content = SessionListDialogView.build(context) { _, _ -> }
        assertNotNull("the list must be findable for binding", SessionListDialogView.listOf(content))

        // Three buttons are added after the divider; assert them rather than a hardcoded child count so
        // the test survives a decorative view being added.
        val bar = (content as LinearLayout).children()
            .filterIsInstance<LinearLayout>()
            .firstOrNull { it.childCount == 3 }
        assertNotNull("expected a three-button bar", bar)
    }

    @Test
    fun `clicking a button reports its action without dismissing anything`() {
        val context = activity()
        val seen = mutableListOf<SessionListAction>()
        val content = SessionListDialogView.build(context) { action, _ -> seen.add(action) }

        val bar = (content as LinearLayout).children()
            .filterIsInstance<LinearLayout>()
            .first { it.childCount == 3 }
        bar.children().forEach { it.performClick() }

        assertEquals(
            listOf(SessionListAction.MORE, SessionListAction.NEW, SessionListAction.CLOSE).toSet(),
            seen.toSet(),
        )
    }

    @Test
    fun `the list is sized to its content and clamped`() {
        val context = activity()
        val content = SessionListDialogView.build(context) { _, _ -> }
        val list = SessionListDialogView.listOf(content) as ListView

        val adapter = SessionListAdapter(context)
        adapter.submit(sampleRows(1))
        list.adapter = adapter
        list.layout(0, 0, 720, 0)

        val max = SessionListDialogView.maxListHeightPx(context)
        SessionListDialogView.applyListHeight(list, max)
        val oneRow = list.layoutParams.height
        assertTrue("one row should take some height", oneRow > 0)
        assertTrue("one row must fit within the cap", oneRow <= max)

        // More rows mean more height, up to the cap — not a fixed block with empty space.
        adapter.submit(sampleRows(4))
        SessionListDialogView.applyListHeight(list, max)
        val fourRows = list.layoutParams.height
        assertTrue("four rows should be taller than one", fourRows > oneRow)
        assertTrue("the cap must hold", fourRows <= max)
    }

    @Test
    fun `an empty list takes no height`() {
        val context = activity()
        val content = SessionListDialogView.build(context) { _, _ -> }
        val list = SessionListDialogView.listOf(content) as ListView

        val adapter = SessionListAdapter(context)
        adapter.submit(emptyList())
        list.adapter = adapter
        list.layout(0, 0, 720, 0)

        SessionListDialogView.applyListHeight(list, SessionListDialogView.maxListHeightPx(context))
        assertEquals(0, list.layoutParams.height)
    }

    @Test
    fun `buttons are laid out as equal shares`() {
        val context = activity()
        val content = SessionListDialogView.build(context) { _, _ -> }
        val bar = (content as LinearLayout).children()
            .filterIsInstance<LinearLayout>()
            .first { it.childCount == 3 }

        bar.children().forEach { child ->
            val params = child.layoutParams as LinearLayout.LayoutParams
            assertEquals("each button should share the width equally", 1f, params.weight, 0.001f)
        }
    }

    private fun sampleRows(count: Int): List<SessionRow> =
        (1..count).map { index ->
            SessionRow(
                index = index,
                name = "session $index",
                statusLabel = "idle",
                title = null,
                cwd = null,
                isCurrent = index == 1,
                isRunning = false,
                isError = false,
            )
        }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }
}
