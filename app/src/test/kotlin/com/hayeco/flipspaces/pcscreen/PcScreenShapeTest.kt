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
}
