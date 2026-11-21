package com.termux.app.terminal

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.shared.terminal.TerminalState

/**
 * A thin strip above the terminal showing what the current session is doing.
 *
 * The point is to answer "is it still running?" at a glance after returning from another app, which
 * otherwise requires reading the output and guessing.
 *
 * It stays hidden while the session is idle, so a user who never runs long commands never sees it. That
 * restraint matters: permanent chrome costs screen space on every launch for no information.
 *
 * The decision of *what* to show and *which* colours to use is expressed as [Model] and computed by
 * [describe], a pure function, so it can be tested without inflating a view.
 */
class TerminalStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val label: TextView = TextView(context).apply {
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption)
        setSingleLine(true)
        val padding = (resources.displayMetrics.density * 10).toInt()
        setPadding(padding, padding / 2, padding, padding / 2)
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Colours resolved once, from the view's own context. */
    private val palette = Palette(
        runningText = ContextCompat.getColor(context, R.color.status_running_text),
        runningBg = ContextCompat.getColor(context, R.color.status_running_bg),
        exitedText = ContextCompat.getColor(context, R.color.status_exited_text),
        exitedBg = ContextCompat.getColor(context, R.color.status_exited_bg),
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Updates the strip from a session state.
     *
     * @param state the live state of the current session.
     * @param sessionTitle a short descriptor, usually the session name or title.
     * @param busyForMillis how long the session has been busy, shown as an elapsed hint.
     */
    fun render(
        state: TerminalState,
        sessionTitle: String?,
        busyForMillis: Long? = null,
    ) {
        val model = describe(state, sessionTitle, busyForMillis, context, palette)
        if (model == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        label.text = model.text
        label.setTextColor(model.textColor)
        setBackgroundColor(model.backgroundColor)
    }

    /** Hides the strip, for example when no session exists. */
    fun hideStrip() {
        visibility = GONE
    }

    /** Colours used to render the strip, resolved from resources at construction time. */
    data class Palette(
        val runningText: Int,
        val runningBg: Int,
        val exitedText: Int,
        val exitedBg: Int,
    )

    /** The visual model for one state. */
    data class Model(
        val text: String,
        val textColor: Int,
        val backgroundColor: Int,
    )

    companion object {

        /**
         * Builds the display model, or null when nothing should be shown.
         *
         * Idle sessions hide the strip: "idle" is the default state and showing it permanently would be
         * noise. Busy and exited sessions are the ones worth interrupting for.
         */
        fun describe(
            state: TerminalState,
            sessionTitle: String?,
            busyForMillis: Long?,
            context: Context,
            palette: Palette,
        ): Model? = when (state) {
            is TerminalState.Busy -> Model(
                text = buildString {
                    append(context.getString(R.string.status_running))
                    if (!sessionTitle.isNullOrBlank()) append(" · ").append(sessionTitle)
                    busyForMillis?.let { append(" · ").append(formatElapsed(it)) }
                },
                textColor = palette.runningText,
                backgroundColor = palette.runningBg,
            )

            is TerminalState.Dead -> Model(
                text = buildString {
                    append(context.getString(R.string.status_exited))
                    state.exitStatus?.let { append(" (").append(it).append(')') }
                    if (!sessionTitle.isNullOrBlank()) append(" · ").append(sessionTitle)
                },
                textColor = palette.exitedText,
                backgroundColor = palette.exitedBg,
            )

            is TerminalState.Idle, TerminalState.NoSession -> null
        }

        /** Formats an elapsed duration compactly. */
        fun formatElapsed(millis: Long): String = when {
            millis < 1_000 -> "${millis}ms"
            millis < 60_000 -> "${millis / 1_000}s"
            millis < 3_600_000 -> "${millis / 60_000}m ${(millis % 60_000) / 1_000}s"
            else -> "${millis / 3_600_000}h ${(millis % 3_600_000) / 60_000}m"
        }
    }
}
