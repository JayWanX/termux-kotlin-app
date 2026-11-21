package com.termux.app.terminal.io

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.termux.extrakeys.ExtraKeysView
import kotlin.math.abs

/**
 * A [ViewPager] that also steals clearly horizontal drags away from a focused
 * child such as an [EditText].
 *
 * Normally a focused EditText consumes touch events for text selection, which
 * makes swiping back to the keys page very difficult. This pager intercepts a
 * gesture as soon as it is unambiguously horizontal, so swiping works anywhere
 * on the toolbar, while vertical drags still reach the EditText for scrolling.
 */
class SwipeableToolbarViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dx = abs(ev.x - initialX)
                    val dy = abs(ev.y - initialY)
                    // Only steal the gesture once the horizontal movement is
                    // dominant, so vertical scrolling keeps working.
                    if (dx > touchSlop && dx > dy * 1.5f) {
                        isDragging = true
                        // Replay the gesture to this pager from the start.
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}

object TerminalToolbarViewPager {

    class PageAdapter(
        private val mActivity: TermuxActivity,
        private var mSavedTextInput: String?
    ) : PagerAdapter() {

        override fun getCount(): Int = 2

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }

        override fun instantiateItem(collection: ViewGroup, position: Int): Any {
            val inflater = LayoutInflater.from(mActivity)
            val layout: View
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false)
                val extraKeysView = layout as ExtraKeysView
                extraKeysView.extraKeysViewClient = mActivity.termuxTerminalExtraKeys
                extraKeysView.setButtonTextAllCaps(mActivity.properties?.shouldExtraKeysTextBeAllCaps() == true)
                mActivity.setExtraKeysView(extraKeysView)
                extraKeysView.reload(
                    mActivity.termuxTerminalExtraKeys?.getExtraKeysInfo(),
                    mActivity.terminalToolbarDefaultHeight
                )

                // apply extra keys fix if enabled in prefs
                if (mActivity.properties?.isUsingFullScreen() == true && mActivity.properties?.isUsingFullScreenWorkAround() == true) {
                    FullScreenWorkAround.apply(mActivity)
                }
            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false)
                val editText = layout.findViewById<EditText>(R.id.terminal_toolbar_text_input)
                val sendButton = layout.findViewById<ImageButton>(R.id.terminal_toolbar_text_input_send)

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput)
                    mSavedTextInput = null
                }

                // Multi-line editing: Enter inserts a newline instead of sending.
                editText.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_FULLSCREEN

                // Grow/shrink the toolbar as the content wraps to more or fewer
                // lines, clamped between the configured minimum and maximum.
                editText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        editText.post { applyMeasuredHeight(editText) }
                    }
                })

                // Also re-measure once the view is laid out, because automatic
                // wrapping depends on the final width.
                editText.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft) {
                        editText.post { applyMeasuredHeight(editText) }
                    }
                }

                sendButton.setOnClickListener {
                    sendTextInput(editText)
                }
            }
            collection.addView(layout)
            return layout
        }

        /**
         * Measure the input and apply the resulting toolbar height.
         */
        private fun applyMeasuredHeight(editText: EditText) {
            if (!editText.isAttachedToWindow) return
            val desired = measureDesiredHeight(editText)
            mActivity.updateTerminalToolbarHeightForPage(1, desired)
        }

        /**
         * Measure how tall the input needs to be for its current content,
         * accounting for automatic soft wrapping and the surrounding padding.
         *
         * The send button is never taller than the input, so the input height is
         * the binding constraint for the row.
         */
        private fun measureDesiredHeight(editText: EditText): Int {
            val textLayout = editText.layout ?: return 0
            val lineHeight = editText.paint.fontMetricsInt.let { it.descent - it.ascent }
            val wrappedLines = textLayout.lineCount
            val inputHeight = wrappedLines * lineHeight +
                editText.paddingTop + editText.paddingBottom
            // Respect the minimum height declared in the layout so a single line
            // stays aligned with the 40dp send button.
            val contentHeight = if (inputHeight < editText.minimumHeight) {
                editText.minimumHeight
            } else {
                inputHeight
            }
            // Add the container's own vertical padding.
            val parent = editText.parent as? View
            val containerPadding = if (parent != null) {
                parent.paddingTop + parent.paddingBottom
            } else {
                0
            }
            return contentHeight + containerPadding
        }
        /**
         * Send the current text input content to the running session.
         *
         * The whole buffer is written at once and terminated with a newline so
         * the shell actually executes it.
         */
        private fun sendTextInput(editText: EditText) {
            val session = mActivity.currentSession

            if (session == null) {
                editText.setText("")
                return
            }

            if (!session.isRunning) {
                mActivity.termuxTerminalSessionClient?.removeFinishedSession(session)
                editText.setText("")
                return
            }

            val raw = editText.text.toString()
            if (raw.isEmpty()) {
                mActivity.showToast(mActivity.getString(R.string.msg_terminal_toolbar_text_input_empty), false)
                return
            }

            // Append a newline so the command is executed, not merely typed.
            val payload = if (raw.endsWith("\n")) raw else "$raw\n"
            session.write(payload)
            editText.setText("")
        }

        override fun destroyItem(collection: ViewGroup, position: Int, view: Any) {
            collection.removeView(view as View)
        }
    }

    class OnPageChangeListener(
        private val mActivity: TermuxActivity,
        private val mTerminalToolbarViewPager: ViewPager
    ) : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            if (position == 0) {
                mActivity.terminalView.requestFocus()
                mActivity.updateTerminalToolbarHeightForPage(position, 0)
            } else {
                val editText = mTerminalToolbarViewPager.findViewById<EditText>(R.id.terminal_toolbar_text_input)
                editText?.requestFocus()
                // Re-measure the existing content so returning to this page
                // restores the height instead of collapsing to a single line.
                editText?.post {
                    mActivity.updateTerminalToolbarHeightForPage(
                        position, measureDesiredHeightFor(editText)
                    )
                }
            }
        }
    }

    /**
     * Measure the toolbar height needed to fully display [editText]'s content.
     */
    private fun measureDesiredHeightFor(editText: EditText): Int {
        val layout = editText.layout ?: return 0
        val lineHeight = editText.paint.fontMetricsInt.let { it.descent - it.ascent }
        val inputHeight = layout.lineCount * lineHeight +
            editText.paddingTop + editText.paddingBottom
        val contentHeight = maxOf(inputHeight, editText.minimumHeight)
        val parent = editText.parent as? View
        val containerPadding = if (parent != null) {
            parent.paddingTop + parent.paddingBottom
        } else {
            0
        }
        return contentHeight + containerPadding
    }
}
