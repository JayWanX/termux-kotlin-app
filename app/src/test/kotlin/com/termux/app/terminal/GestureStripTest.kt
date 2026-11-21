package com.termux.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the right-edge gesture exclusion geometry.
 *
 * These rules matter more than they look: exceeding either platform limit does not throw, it makes the
 * system silently ignore the exclusion, and the edge swipe becomes a Back gesture again. That failure is
 * invisible from code and was the reason the gesture did nothing on a device.
 */
class GestureStripTest {

    /** A typical phone: 1080x2340 at density 3, with a 60px (20dp) strip. */
    private val screenWidth = 1080
    private val screenHeight = 2340
    private val density = 3f
    private val stripWidth = 60

    @Test
    fun `band is centred on the right edge`() {
        val rect = GestureStrip.exclusionRect(screenWidth, screenHeight, stripWidth, density)
        assertNotNull(rect)
        rect!!
        assertEquals(screenWidth, rect.right)
        assertEquals(screenWidth - stripWidth, rect.left)
        // Centred: the gap above and below the band is equal.
        assertEquals(screenHeight - rect.bottom, rect.top)
    }

    @Test
    fun `band never spans the full edge`() {
        // A full-height exclusion is rejected by the platform, so this would silently do nothing.
        val rect = GestureStrip.exclusionRect(screenWidth, screenHeight, stripWidth, density)!!
        assertTrue("band height ${rect.height} must be under the screen height", rect.height < screenHeight)
    }

    @Test
    fun `band stays within the platform height cap`() {
        val rect = GestureStrip.exclusionRect(screenWidth, screenHeight, stripWidth, density)!!
        assertTrue(
            "band height ${rect.height} exceeds 200dp",
            rect.height <= GestureStrip.MAX_BAND_HEIGHT_DP * density,
        )
    }

    @Test
    fun `band stays within the platform area cap`() {
        val rect = GestureStrip.exclusionRect(screenWidth, screenHeight, stripWidth, density)!!
        val screenArea = screenWidth.toLong() * screenHeight
        assertTrue(
            "claimed ${rect.area} of $screenArea exceeds the area cap",
            rect.area <= screenArea * GestureStrip.MAX_TOTAL_AREA_FRACTION,
        )
    }

    @Test
    fun `a very wide strip is shrunk rather than rejected`() {
        // 200px of edge on a short screen would breach the area cap at full height.
        val rect = GestureStrip.exclusionRect(1080, 900, 200, 3f)
        assertNotNull("a wide strip must still yield a legal band", rect)
        val screenArea = 1080L * 900
        assertTrue(rect!!.area <= screenArea * GestureStrip.MAX_TOTAL_AREA_FRACTION)
        assertFalse(rect.height <= 0)
    }

    @Test
    fun `no rectangle when nothing is claimed`() {
        assertNull(GestureStrip.exclusionRect(screenWidth, screenHeight, 0, density))
    }

    @Test
    fun `no rectangle for an unmeasured or degenerate view`() {
        assertNull(GestureStrip.exclusionRect(0, screenHeight, stripWidth, density))
        assertNull(GestureStrip.exclusionRect(screenWidth, 0, stripWidth, density))
        assertNull(GestureStrip.exclusionRect(screenWidth, screenHeight, stripWidth, 0f))
    }

    @Test
    fun `a strip wider than the screen is refused`() {
        // There is no rectangle inside a 100px view that is 200px wide; returning one would put the
        // exclusion outside the window, where it is ignored.
        assertNull(GestureStrip.exclusionRect(100, 400, 200, 2f))
    }
}
