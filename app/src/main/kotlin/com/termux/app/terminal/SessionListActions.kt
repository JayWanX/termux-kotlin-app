package com.termux.app.terminal

import android.content.Context
import com.termux.R

/** What the session list can ask the caller to do. */
enum class SessionListAction {
    /** Switch to the session at this position. */
    OPEN,

    /** Create a new session, leaving the list open. */
    NEW,

    /** Open the "More" popup anchored to the given view. */
    MORE,

    /** Dismiss the list. */
    CLOSE,
}

/**
 * A button in the session list's action bar.
 *
 * Declaring the buttons explicitly is what lets the bar be laid out as a row of equal targets, instead
 * of inheriting Stock AlertDialog's three-button bar (which caps the labels, shares the width between
 * exactly three items, and always closes the dialog when pressed).
 */
data class SessionListButton(
    val label: String,
    val action: SessionListAction,
    val emphasized: Boolean = false,
)

/**
 * The action bar for the session list.
 *
 * Kept as pure data so the set of actions and their order can be asserted without inflating a dialog:
 * the previous version had a duplicate action that only a careful reading of two call sites revealed.
 */
object SessionListActions {

    /**
     * The buttons shown under the list, resolved from resources so they stay localised.
     *
     * Note what is absent: "New system session", which used to live in the More menu and called exactly
     * the same `addNewSession(false, null)` as [NEW]. Two entry points to one action in a six-item menu
     * reads as two different things that happen to look alike, which is worse than one clear button.
     */
    fun bar(context: Context): List<SessionListButton> = listOf(
        SessionListButton(context.getString(R.string.action_session_list_more), SessionListAction.MORE),
        SessionListButton(context.getString(R.string.action_new_session), SessionListAction.NEW),
        SessionListButton(
            context.getString(R.string.action_session_list_close),
            SessionListAction.CLOSE,
            emphasized = true,
        ),
    )
}
