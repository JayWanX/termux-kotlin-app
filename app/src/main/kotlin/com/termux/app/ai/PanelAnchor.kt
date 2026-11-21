package com.termux.app.ai

/**
 * Where the assistant panel sits relative to the terminal's quick-input toolbar.
 *
 * The panel is an overlay inside the same layout as the terminal, so it has to be told explicitly to
 * stop above the toolbar. Getting this wrong is not a cosmetic issue: the toolbar is the only way to
 * type into the terminal, so a panel that covers it forces the user to close the assistant before
 * running the command it just proposed.
 *
 * Expressed as a decision rather than as `RelativeLayout` rules so it can be asserted without inflating
 * a dialog — the rules themselves are three lines of glue in the activity.
 */
object PanelAnchor {

    /** Where the panel's bottom edge goes. */
    enum class Bottom {
        /** Stop above the toolbar so both remain usable. */
        ABOVE_TOOLBAR,

        /** Extend to the bottom of the layout, when there is no toolbar to avoid. */
        SCREEN_BOTTOM,
    }

    /**
     * @param toolbarVisible whether the quick-input toolbar is currently shown.
     */
    fun bottom(toolbarVisible: Boolean): Bottom =
        if (toolbarVisible) Bottom.ABOVE_TOOLBAR else Bottom.SCREEN_BOTTOM
}
