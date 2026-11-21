package com.termux.app.ai

/**
 * The swipe and tap rules behind the panel's edge gesture.
 *
 * Kept as pure functions so the thresholds are pinned by tests without synthesising touch events, and
 * so the screen and its tests share one definition instead of two that can drift apart.
 */
object EdgeSwipe {

    /**
     * Whether a drag began close enough to the right edge to belong to the panel.
     *
     * Two zones, and the reason for the second one is the whole point:
     *
     * - The outermost [edgeWidthPx] is the system's gesture zone. Under gesture navigation the system
     *   claims a swipe here as Back, and an app only sees it at all if it has declared a gesture
     *   exclusion — which the platform caps at 200dp of height per edge. Swipes near the top or bottom
     *   of the screen are therefore lost no matter what.
     * - The band just inside it, up to [innerWidthPx] from the edge, is entirely the app's: the system
     *   does not intercept there, so a swipe is delivered reliably.
     *
     * Accepting the second zone is what makes the gesture dependable. It costs nothing in practice —
     * a horizontal drag starting 40dp from the right edge has no other purpose in a terminal — and it
     * removes the dependence on an exclusion the platform only partially honours.
     *
     * @param startX x coordinate where the drag began.
     * @param rootWidthPx width of the container the gesture is measured in.
     */
    fun startsInEdgeZone(startX: Float, rootWidthPx: Int, innerWidthPx: Float): Boolean {
        if (rootWidthPx <= 0 || innerWidthPx <= 0f) return false
        return startX >= rootWidthPx - innerWidthPx
    }

    /**
     * Whether a drag that started on the right edge should open the panel.
     *
     * Only horizontal travel is required and the bar is deliberately low. The earlier rule demanded a
     * 40dp swipe inside a 20dp strip, which meant the gesture only fired when a drag began *and* stayed
     * almost perfectly horizontal for a long distance — reasonable-looking numbers that felt unreliable
     * in the hand. A short flick from the edge is what people actually do.
     *
     * @param deltaX horizontal travel since the drag began; negative is towards the left.
     * @param thresholdPx distance that counts as deliberate.
     * @param dyDriftPx vertical travel, used to reject vertical scrolls that begin near the edge.
     * @param maxDriftPx how much vertical movement still reads as a horizontal swipe.
     */
    fun shouldOpen(
        deltaX: Float,
        thresholdPx: Float,
        dyDriftPx: Float = 0f,
        maxDriftPx: Float = Float.MAX_VALUE,
    ): Boolean {
        if (thresholdPx <= 0f) return false
        if (deltaX >= -thresholdPx) return false
        // A finger that has travelled mostly vertically is scrolling the terminal, not reaching for the
        // panel; opening in that case steals the scroll mid-gesture.
        return kotlin.math.abs(dyDriftPx) <= maxDriftPx
    }

    /**
     * Whether a drag inside the open panel should dismiss it.
     *
     * Requires more than a twitch so the panel does not close while the user is scrolling the transcript
     * sideways by accident, but well under the old 48dp — dismissing was reported as harder than opening.
     *
     * @param accumulatedDx rightward travel accumulated since the drag began.
     * @param thresholdPx distance that counts as deliberate.
     */
    fun shouldClose(accumulatedDx: Float, thresholdPx: Float): Boolean =
        thresholdPx > 0f && accumulatedDx > thresholdPx

    /** A tap that should be recognised as part of a double tap. */
    data class Tap(val x: Float, val y: Float, val atMs: Long)

    /**
     * Whether [second] completes a double tap that began with [first].
     *
     * @param edgeLeftX x coordinate where the edge strip starts.
     * @param slopPx how far apart the two taps may land.
     * @param timeoutMs maximum gap between them.
     */
    fun isEdgeDoubleTap(
        first: Tap?,
        second: Tap,
        edgeLeftX: Float,
        slopPx: Float,
        timeoutMs: Long,
    ): Boolean {
        if (first == null) return false
        // Both taps must be inside the strip. A first tap elsewhere followed by a tap on the edge is two
        // unrelated actions, and treating it as one would open the panel on an ordinary tap.
        if (first.x < edgeLeftX || second.x < edgeLeftX) return false
        val elapsed = second.atMs - first.atMs
        if (elapsed < 0L || elapsed > timeoutMs) return false
        val dx = second.x - first.x
        val dy = second.y - first.y
        return dx * dx + dy * dy <= slopPx * slopPx
    }
}
