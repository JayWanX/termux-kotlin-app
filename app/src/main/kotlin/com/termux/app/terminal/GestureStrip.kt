package com.termux.app.terminal

/**
 * Geometry for the right-edge gesture strip.
 *
 * Pure arithmetic, so the platform's rules about gesture exclusions can be pinned by tests instead of
 * being discovered on a device. Two of those rules are easy to get wrong and silent when wrong:
 * an exclusion may not cover a whole screen edge, and only a limited area in total may be claimed per
 * screen. Violating either does not raise an error — the system simply ignores the rectangle, and the
 * edge swipe quietly turns into Back.
 */
object GestureStrip {

    /**
     * The tallest band the platform accepts on a single edge, in dp.
     *
     * Documented ceiling; exceeding it makes the whole exclusion be dropped.
     */
    const val MAX_BAND_HEIGHT_DP = 200

    /**
     * Total exclusion area an app may claim per screen, as a fraction of the display area.
     *
     * The platform allows 200dp on one edge, and in aggregate only a small share of the screen. Both
     * limits are checked here so a caller cannot accidentally exceed one while satisfying the other.
     */
    const val MAX_TOTAL_AREA_FRACTION = 0.05f

    /**
     * The band to claim on the right edge.
     *
     * @param widthPx the view width.
     * @param heightPx the view height.
     * @param stripWidthPx how far from the edge the app wants to keep.
     * @param density display density, to convert [MAX_BAND_HEIGHT_DP].
     * @return the rectangle to exclude, or null when no legal rectangle exists.
     */
    fun exclusionRect(
        widthPx: Int,
        heightPx: Int,
        stripWidthPx: Int,
        density: Float,
    ): Rect? {
        if (widthPx <= 0 || heightPx <= 0 || stripWidthPx <= 0 || density <= 0f) return null
        // A strip wider than the view cannot be expressed as a rectangle inside it.
        if (stripWidthPx >= widthPx) return null
        // An exclusion spanning the full edge is rejected, so the strip must stop short of the edge
        // length even when the band would otherwise be tall enough.
        val maxHeight = minOf(heightPx - 1, (MAX_BAND_HEIGHT_DP * density).toInt())
        if (maxHeight <= 0) return null

        // Grow the area limit into a height, then take whichever is smaller. A very wide strip on a
        // short screen can be area-limited even when its height is legal.
        val maxArea = (widthPx.toLong() * heightPx * MAX_TOTAL_AREA_FRACTION).toLong()
        val areaLimitedHeight = (maxArea / stripWidthPx).toInt()
        val bandHeight = minOf(maxHeight, areaLimitedHeight)
        if (bandHeight <= 0) return null

        val top = (heightPx - bandHeight) / 2
        return Rect(widthPx - stripWidthPx, top, widthPx, top + bandHeight)
    }

    /** A plain rectangle, kept here so this file stays free of Android types. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height
    }
}
