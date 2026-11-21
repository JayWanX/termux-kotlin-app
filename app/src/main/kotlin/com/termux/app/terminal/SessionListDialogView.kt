package com.termux.app.terminal

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.R

/**
 * Builds the session list dialog's content view.
 *
 * Replaces the stock AlertDialog button bar. That bar had three problems that a custom view solves
 * outright:
 *
 * - The buttons were rendered in caps by the platform theme's `textAllCaps`, so "More", "New" and "Close"
 *   appeared as "MORE", "NEW", "CLOSE" next to sentence-case session rows.
 * - The bar shares its width between exactly three buttons, so labels either truncate or force the dialog
 *   wider than the list needs.
 * - A button that creates a session could only dismiss the dialog afterwards, because AlertDialog's
 *   button callbacks always close it.
 *
 * The replacement is a row of equally weighted, sentence-case buttons under the list, wired to plain
 * click listeners so a single callback decides whether the dialog stays open.
 */
object SessionListDialogView {

    /**
     * Builds the content view.
     *
     * @param context themed context, so the dialog's own theme decides the colours.
     * @param onAction invoked with the button's action; the caller owns dialog lifetime.
     */
    fun build(
        context: Context,
        onAction: (SessionListAction, View) -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val list = ListView(context).apply {
            id = android.R.id.list
            // Height is decided after the adapter is attached (see [applyListHeight]): a fixed height
            // leaves a large empty area when there are only two sessions, while `wrap_content` lets a
            // long list push the action bar off screen. Neither can be expressed in layout params
            // alone, so it is measured against the content and clamped.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(list)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * context.resources.displayMetrics.density).toInt(),
            )
            setBackgroundColor(ruleColor(context))
        }
        root.addView(divider)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Generous vertical padding so each target is comfortably tappable.
            val v = dp(context, 6)
            setPadding(dp(context, 6), v, dp(context, 6), v)
        }

        val actions = SessionListActions.bar(context)
        actions.forEach { button ->
            bar.addView(
                actionButton(context, button) { onAction(button.action, it) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(bar)

        return root
    }

    /** True when the list view inside [content] is the one to bind. */
    fun listOf(content: View): ListView? = content.findViewById(android.R.id.list)

    /**
     * Sizes [list] to its content, clamped to a share of the screen.
     *
     * A dialog's content view is measured with an unbounded height, so a `wrap_content` ListView would
     * ask for the full height of every row and the buttons below would be pushed out of the window. This
     * measures the rows that were actually bound and caps the result, which also avoids the opposite
     * failure: a fixed height leaving half an empty dialog for a one-session list.
     *
     * @param maxHeightPx the largest the list may become.
     */
    fun applyListHeight(list: ListView, maxHeightPx: Int) {
        val adapter = list.adapter ?: return
        if (adapter.isEmpty) {
            list.layoutParams = list.layoutParams.apply { height = 0 }
            return
        }

        var total = list.dividerHeight * (adapter.count - 1)
        val rowWidth = list.width.coerceAtLeast(1)

        // Measure every row individually: ListView only creates views for the visible window, so asking
        // it for its own wrapped height returns a number that depends on the current scroll position.
        for (position in 0 until adapter.count) {
            val row = adapter.getView(position, null, list)
            row.measure(
                View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            total += row.measuredHeight
        }

        list.layoutParams = list.layoutParams.apply {
            height = total.coerceIn(1, maxHeightPx)
        }
    }

    /** The largest height a list may take in a dialog, as a share of the screen. */
    fun maxListHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        return minOf(screenHeight / 2, (360 * density).toInt())
    }

    /**
     * A single action button.
     *
     * Text rather than an icon: the actions are not all self-evident as shapes ("New from template" has
     * no unambiguous glyph), and the bar has room for short words once the platform's cap style is gone.
     */
    private fun actionButton(
        context: Context,
        spec: SessionListButton,
        onClick: (View) -> Unit,
    ): TextView = TextView(context).apply {
        text = spec.label
        // Explicitly sentence case: this view is the reason the caps are gone, so do not inherit them.
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val v = dp(context, 10)
        setPadding(dp(context, 8), v, dp(context, 8), v)
        setTextColor(
            ContextCompat.getColor(
                context,
                if (spec.emphasized) R.color.status_accent else R.color.status_muted,
            ),
        )
        if (spec.emphasized) setTypeface(null, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        // A ripple would need a MaterialButton; a plain selector keeps the dependency surface small.
        setOnClickListener(onClick)
    }

    private fun ruleColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.status_row_current)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
