package com.termux.app.ai

/**
 * How wide the assistant side panel should be.
 *
 * Pure arithmetic so the rule is testable without a real screen, and so the tests pin the shipped
 * constants instead of a copy of them that can drift.
 */
object PanelMetrics {

    /** Share of the screen width the panel targets. */
    const val WIDTH_FRACTION = 0.32f

    /**
     * Upper bound on the panel's floor, as a share of the screen width.
     *
     * The floor exists so the panel is never a sliver on a narrow phone, but on a high-density device a
     * fixed dp floor would resolve to more than half the width and turn the side panel back into a
     * full-screen cover. Clamping it keeps the terminal readable on every screen.
     */
    const val FLOOR_FRACTION = 0.30f

    /** The floor in dp, before it is clamped by [FLOOR_FRACTION]. */
    const val FLOOR_DP = 200

    /** The ceiling in dp, so the panel stays a side sheet on tablets. */
    const val CEILING_DP = 400

    /**
     * @param screenWidthPx the display width in pixels.
     * @param density the display density, used to convert the dp bounds.
     */
    fun widthPx(screenWidthPx: Int, density: Float): Int {
        if (screenWidthPx <= 0) return 0
        val target = (screenWidthPx * WIDTH_FRACTION).toInt()
        val floor = minOf((FLOOR_DP * density).toInt(), (screenWidthPx * FLOOR_FRACTION).toInt())
        val ceiling = minOf((CEILING_DP * density).toInt(), screenWidthPx)
        return maxOf(target, floor).coerceAtMost(ceiling)
    }
}
