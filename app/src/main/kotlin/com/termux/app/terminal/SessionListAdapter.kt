package com.termux.app.terminal

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState

/**
 * One row of the session list.
 *
 * Everything the row needs, resolved and formatted, so the adapter does no work beyond binding strings
 * to views. That keeps the formatting unit-testable: [SessionRow] is plain data and can be asserted
 * without inflating a view.
 */
data class SessionRow(
    /** One-based index, matching the numbers used by the "More" menu and keyboard shortcuts. */
    val index: Int,
    val name: String,
    val statusLabel: String,
    val title: String?,
    val cwd: String?,
    val isCurrent: Boolean,
    /**
     * True when the session is actively executing something, i.e. [statusLabel] is "running".
     *
     * Not the same as the shell process being alive: an idle prompt is a live process that is doing
     * nothing, and marking it as active made every row look busy.
     */
    val isRunning: Boolean,
    val isError: Boolean,
    /**
     * Whether a full-screen program (vim, less, top) owns the terminal.
     *
     * Worth surfacing: switching to such a session shows a screen with no shell prompt, which otherwise
     * looks like a hung terminal.
     */
    val isAltBuffer: Boolean = false,
) {
    /** True when there is a shell-reported title or directory worth a second line. */
    val hasSubtitle: Boolean
        get() = !title.isNullOrBlank() || !cwd.isNullOrBlank()

    /** The second line: title and directory, whichever are present. */
    val subtitle: String
        get() = buildList {
            title?.takeIf { it.isNotBlank() }?.let { add(it) }
            cwd?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")

    /** The status line, including an explicit marker for a full-screen program. */
    val statusText: String
        get() = if (isAltBuffer) "$statusLabel   fullscreen" else statusLabel
}

/**
 * Builds the rows for the session list.
 *
 * Pure, so the list's content is testable without a dialog or a live service.
 */
object SessionRowBuilder {

    /**
     * @param sessions the sessions to render, in display order.
     * @param names the user-assigned name of each session, aligned with [sessions]; blank when unset.
     * @param currentIndex the position of the active session, or -1.
     * @param maxCwdChars how much of the working directory to keep.
     */
    fun build(
        sessions: List<SessionInfo>,
        names: List<String?>,
        currentIndex: Int,
        maxCwdChars: Int = 32,
    ): List<SessionRow> = sessions.mapIndexed { position, info ->
        val dead = info.state is TerminalState.Dead || !info.isRunning
        val assigned = names.getOrNull(position)?.takeIf { it.isNotBlank() }
        SessionRow(
            index = position + 1,
            name = assigned ?: "Session ${position + 1}",
            statusLabel = SessionLabelFormatter.statusLabel(info.state),
            title = info.title?.takeIf { it.isNotBlank() && it != assigned },
            cwd = info.cwd
                ?.takeIf { it.isNotBlank() }
                ?.let { SessionLabelFormatter.elideMiddle(it, maxCwdChars) },
            isCurrent = position == currentIndex,
            // "Running" here means executing a command, not merely holding a live shell. Sessions whose
            // shell is alive but sitting at a prompt are Idle, and must not be drawn as active.
            isRunning = info.state is TerminalState.Busy,
            isError = dead,
            isAltBuffer = info.isAltBuffer,
        )
    }
}

/**
 * Binds [SessionRow]s to the session list dialog.
 *
 * A custom adapter replaces the previous `simple_list_item_single_choice` rows, which were a single line
 * of joined text: the status glyph, the name, the index, the shell title, the working directory and any
 * exit code all ran together, so nothing could be scanned and the part that matters most — which session
 * is this, and is it alive — was buried in the middle of a sentence.
 *
 * Each row now has a status dot, a bold name, the index as a quiet suffix, a status line, and a dimmed
 * second line for the shell title and directory. The current session is marked, and a dead one is shown
 * in the error colour with its exit code.
 */
class SessionListAdapter(private val context: Context) : BaseAdapter() {

    private var rows: List<SessionRow> = emptyList()

    /** Replaces the contents. Kept separate from the constructor so the dialog can refresh in place. */
    fun submit(newRows: List<SessionRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): SessionRow = rows[position]

    override fun getItemId(position: Int): Long = rows[position].index.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? RowView ?: RowView(context)
        view.bind(rows[position])
        return view
    }

    /** A row's view, built in code so the dialog stays a plain AlertDialog. */
    private inner class RowView(context: Context) : LinearLayout(context) {

        private val nameView: TextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val indexView: TextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, 0, 0)
        }
        private val statusView: TextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        private val subtitleView: TextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = VERTICAL
            // The left inset is wider than the right to leave room for the status rule drawn in bind().
            setPadding(dp(18), dp(9), dp(14), dp(9))
            setWillNotDraw(false)

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(nameView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )

            addView(
                statusView,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                },
            )

            addView(
                subtitleView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(1)
                },
            )
        }

        fun bind(row: SessionRow) {
            val muted = ContextCompat.getColor(context, R.color.status_muted)
            val running = ContextCompat.getColor(context, R.color.status_running)
            val exited = ContextCompat.getColor(context, R.color.status_exited)
            val accent = ContextCompat.getColor(context, R.color.status_accent)

            nameView.text = row.name
            nameView.setTypeface(null, if (row.isCurrent) Typeface.BOLD else Typeface.NORMAL)
            // Resolved from the theme the dialog is actually using, not from the system's night mode.
            // Termux lets the app theme be pinned independently of the system, so a dark app on a
            // light-system phone would otherwise draw primary_text_light (near-black) on a dark dialog.
            nameView.setTextColor(themeTextColor())

            indexView.text = row.index.toString()
            indexView.setTextColor(muted)

            statusView.text = buildString {
                append(row.statusText)
                if (row.isCurrent) append("   current")
            }
            statusView.setTextColor(
                when {
                    row.isError -> exited
                    row.isCurrent -> accent
                    row.isRunning -> running
                    else -> muted
                },
            )

            if (row.hasSubtitle) {
                subtitleView.text = row.subtitle
                subtitleView.visibility = View.VISIBLE
                subtitleView.setTextColor(muted)
            } else {
                subtitleView.visibility = View.GONE
            }

            rulerColor = when {
                row.isError -> exited
                row.isRunning -> running
                row.isCurrent -> accent
                else -> 0
            }
            setBackgroundColor(
                if (row.isCurrent) ContextCompat.getColor(context, R.color.status_row_current) else 0x00000000,
            )
            invalidate()
        }

        /** Colour of the vertical rule drawn at the left of the row; 0 draws nothing. */
        private var rulerColor: Int = 0

        /**
         * Draws the status rule.
         *
         * A coloured bar instead of a glyph character: the previous `●`/`○`/`×` column repeated what the
         * status word underneath already said, and characters render inconsistently across fonts. A bar
         * carries the same at-a-glance cue, takes no text width, and cannot be confused with content.
         */
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (rulerColor == 0) return
            val inset = dp(6)
            val width = dp(3)
            canvas.drawRoundRect(
                inset.toFloat(),
                inset.toFloat(),
                (inset + width).toFloat(),
                (height - inset).toFloat(),
                width / 2f,
                width / 2f,
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = rulerColor
                },
            )
        }

        private fun isNightMode(): Boolean {
            val mask = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        /**
         * The dialog's primary text colour.
         *
         * Read from the theme the row is actually inflated with rather than from the system's night mode
         * or a hardcoded light/dark pair. Termux lets the app theme be pinned independently of the
         * system, so resolving `textColorPrimary` from the row's own context is the only answer that
         * matches what the rest of the dialog draws.
         */
        private fun themeTextColor(): Int {
            val attrs = intArrayOf(android.R.attr.textColorPrimary)
            val typed = context.obtainStyledAttributes(attrs)
            return try {
                typed.getColor(
                    0,
                    ContextCompat.getColor(
                        context,
                        if (isNightMode()) android.R.color.primary_text_dark
                        else android.R.color.primary_text_light,
                    ),
                )
            } finally {
                typed.recycle()
            }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }
}
