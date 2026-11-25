package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 1.6.20 (Mike's Aura, 24 Nov 2026): the Space started with him lying on his back, and the start check read gravity
 * 78 degrees from up as a quarter turn layout, so his head turns were read about the wrong axis.
 */
class LyingBack1620Test {
    // His start reading, sensor axes, on the Aura's layout (x right, y up, z back): "78 deg from up"
    private val lyingBack = Vec3(1.20f, 2.13f, -9.56f)

    @Test fun aTestedModelKeepsItsOwnLayoutLyingBack() {
        val v = GravityAxes.check(GravityAxes.Rest(lyingBack, "start"), AxisMap.DEFAULT, AxisMap.DEFAULT, knownLayout = true)
        assertNull(v.line, v.axes)
    }

    @Test fun beforeThisFixItTurnedTheLayout() {
        // what 1.6.19 did with the same reading (still what untested glasses get: the safety net for a wrong layout)
        val v = GravityAxes.check(GravityAxes.Rest(lyingBack, "start"), AxisMap.DEFAULT, AxisMap.DEFAULT)
        assertEquals(AxisMap.XRIGHT_ZDOWN_YBACK, v.axes)
    }

    @Test fun aTurnedLayoutComesBackToTheKnownOne() {
        // upright again on the layout the old check picked: gravity fits the Aura's own, which is taken back
        val upright = Vec3(0.3f, 9.78f, 0.5f)
        val v = GravityAxes.check(GravityAxes.Rest(upright, "start"), AxisMap.XRIGHT_ZDOWN_YBACK, AxisMap.DEFAULT, knownLayout = true)
        assertEquals(AxisMap.DEFAULT, v.axes)
    }
}
