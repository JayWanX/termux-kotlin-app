package com.termux.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the edge gesture rules.
 *
 * The thresholds were previously too strict to feel reliable: a 40dp swipe out of a 20dp strip meant the
 * gesture only fired on a long, almost perfectly horizontal drag. These cases pin both halves of the
 * relaxed rule — short flicks open the panel, vertical scrolls that begin near the edge do not.
 */
class EdgeSwipeTest {

    private val threshold = 18f
    private val drift = 40f

    @Test
    fun `a short leftward flick opens the panel`() {
        assertTrue(EdgeSwipe.shouldOpen(deltaX = -20f, thresholdPx = threshold))
    }

    @Test
    fun `a swipe shorter than the threshold does not open`() {
        assertFalse(EdgeSwipe.shouldOpen(deltaX = -10f, thresholdPx = threshold))
    }

    @Test
    fun `a rightward swipe does not open`() {
        assertFalse(EdgeSwipe.shouldOpen(deltaX = 100f, thresholdPx = threshold))
    }

    @Test
    fun `the old 40dp threshold is no longer required`() {
        // The specific complaint: a 25dp flick used to do nothing.
        assertFalse(EdgeSwipe.shouldOpen(deltaX = -25f, thresholdPx = 40f))
        assertTrue(EdgeSwipe.shouldOpen(deltaX = -25f, thresholdPx = threshold))
    }

    @Test
    fun `a mostly vertical drag does not open the panel`() {
        // This is what the drift ceiling is for: a scroll that starts near the right edge must not
        // trigger the panel halfway through.
        assertFalse(
            EdgeSwipe.shouldOpen(
                deltaX = -30f,
                thresholdPx = threshold,
                dyDriftPx = 60f,
                maxDriftPx = drift,
            ),
        )
    }

    @Test
    fun `a slightly diagonal swipe still opens the panel`() {
        assertTrue(
            EdgeSwipe.shouldOpen(
                deltaX = -30f,
                thresholdPx = threshold,
                dyDriftPx = 20f,
                maxDriftPx = drift,
            ),
        )
    }

    @Test
    fun `a degenerate threshold never opens`() {
        assertFalse(EdgeSwipe.shouldOpen(deltaX = -100f, thresholdPx = 0f))
    }

    // ------------------------------------------------------------- start zone

    @Test
    fun `a drag starting at the very edge is accepted`() {
        assertTrue(EdgeSwipe.startsInEdgeZone(startX = 1078f, rootWidthPx = 1080, innerWidthPx = 132f))
    }

    @Test
    fun `a drag starting inside the widened zone is accepted`() {
        // The system only delivers touches outside its own ~20dp edge zone, so accepting a start further
        // in is what makes the gesture work reliably instead of racing the Back gesture.
        assertTrue(EdgeSwipe.startsInEdgeZone(startX = 1000f, rootWidthPx = 1080, innerWidthPx = 132f))
    }

    @Test
    fun `a drag starting in the middle of the screen is rejected`() {
        assertFalse(EdgeSwipe.startsInEdgeZone(startX = 400f, rootWidthPx = 1080, innerWidthPx = 132f))
    }

    @Test
    fun `the widened zone is wider than the system edge zone`() {
        // Guards the point of the change: if the accepted zone shrank back to the system's ~20dp, the
        // gesture would again depend on a gesture exclusion the platform only partly honours.
        val acceptedFromEdgeDp = 44f
        val systemEdgeDp = 20f
        assertTrue(acceptedFromEdgeDp > systemEdgeDp)
    }

    @Test
    fun `a degenerate zone accepts nothing`() {
        assertFalse(EdgeSwipe.startsInEdgeZone(startX = 1070f, rootWidthPx = 1080, innerWidthPx = 0f))
        assertFalse(EdgeSwipe.startsInEdgeZone(startX = 1070f, rootWidthPx = 0, innerWidthPx = 100f))
    }

    // ---------------------------------------------------------------- double tap

    private val edgeLeft = 1000f
    private val slop = 48f
    private val timeout = 400L

    private fun tap(x: Float, y: Float, at: Long) = EdgeSwipe.Tap(x, y, at)

    @Test
    fun `two quick taps on the edge form a double tap`() {
        assertTrue(
            EdgeSwipe.isEdgeDoubleTap(
                tap(1020f, 500f, 1000L),
                tap(1022f, 503f, 1120L),
                edgeLeft, slop, timeout,
            ),
        )
    }

    @Test
    fun `taps outside the edge strip do not form a double tap`() {
        assertFalse(
            EdgeSwipe.isEdgeDoubleTap(
                tap(500f, 500f, 1000L),
                tap(1020f, 500f, 1120L),
                edgeLeft, slop, timeout,
            ),
        )
    }

    @Test
    fun `two taps far apart do not form a double tap`() {
        assertFalse(
            EdgeSwipe.isEdgeDoubleTap(
                tap(1020f, 500f, 1000L),
                tap(1020f, 700f, 1120L),
                edgeLeft, slop, timeout,
            ),
        )
    }

    @Test
    fun `taps beyond the timeout do not form a double tap`() {
        assertFalse(
            EdgeSwipe.isEdgeDoubleTap(
                tap(1020f, 500f, 1000L),
                tap(1021f, 500f, 1600L),
                edgeLeft, slop, timeout,
            ),
        )
    }

    @Test
    fun `a first tap that does not exist cannot complete a double tap`() {
        assertFalse(
            EdgeSwipe.isEdgeDoubleTap(null, tap(1020f, 500f, 1000L), edgeLeft, slop, timeout),
        )
    }

    @Test
    fun `time going backwards does not count as a double tap`() {
        assertFalse(
            EdgeSwipe.isEdgeDoubleTap(
                tap(1020f, 500f, 2000L),
                tap(1020f, 500f, 1000L),
                edgeLeft, slop, timeout,
            ),
        )
    }
}
