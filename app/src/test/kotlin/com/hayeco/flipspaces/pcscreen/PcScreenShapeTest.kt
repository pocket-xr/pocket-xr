package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcScreenShapeTest {
    @Test
    fun theFlip7PanelIsStreamedAtItsOwnShape() {
        // Mike's panel, 1786 x 976: multiples of 8 just under it.
        assertEquals(1784 to 976, PcScreenShape.forWindow(1786, 976))
    }

    @Test
    fun anUltrawideWindowStaysUltrawideWithinTheLimits() {
        val (w, h) = PcScreenShape.forWindow(5120, 1440)
        assertTrue(w <= PcScreenShape.MAX_SIDE)
        assertEquals(5120.0 / 1440, w.toDouble() / h, 0.02)
        assertEquals(0, w % 8)
        assertEquals(0, h % 8)
    }

    @Test
    fun aTinyWindowIsScaledUpWhole() {
        val (w, h) = PcScreenShape.forWindow(320, 180)
        assertTrue(w.toLong() * h >= PcScreenShape.MIN_AREA - 8 * 640)
        assertEquals(16.0 / 9, w.toDouble() / h, 0.03)
    }

    @Test
    fun onlyARealResizeRestartsTheStream() {
        assertFalse(PcScreenShape.reshapeNeeded(1786, 976, 1784, 976))
        assertTrue(PcScreenShape.reshapeNeeded(1200, 1600, 1784, 976))
    }

    @Test
    fun screenSizeChoices() {
        // 1.6.14: Window follows the window; the fixed sizes are what the computer makes, whatever the window
        assertEquals(PcScreenShape.forWindow(1784, 976), PcScreenShape.forChoice("window", 1784, 976))
        assertEquals(1920 to 1200, PcScreenShape.forChoice("1920x1200", 1784, 976))
        assertEquals(1920 to 1080, PcScreenShape.forChoice("1920x1080", 1000, 1000))
        assertEquals(2560 to 1600, PcScreenShape.forChoice("2560x1600", 1784, 976))
        // an unknown saved value falls back to the window's shape
        assertEquals(PcScreenShape.forWindow(1784, 976), PcScreenShape.forChoice("bogus", 1784, 976))
        assertEquals("window", PcScreenShape.SIZES.first())
    }
}
