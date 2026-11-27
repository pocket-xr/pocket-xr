package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HintPlaceTest {
    private fun belowDeg(fov: Float) = Math.toDegrees(kotlin.math.atan(HintPlace.belowTan(fov).toDouble())).toFloat()

    @Test fun theLumaSeesTheNotice() {
        // 25.8 degrees tall: 12.9 below the middle, the line 3 above that edge.
        assertEquals(9.9f, belowDeg(25.8f), 0.01f)
        assertTrue(belowDeg(25.8f) < 25.8f / 2f)
    }

    @Test fun aTallViewKeepsTheOldPlace() {
        assertEquals(HintPlace.BELOW_TAN, HintPlace.belowTan(50f), 0f)
        // The Aura's 32.3 degrees: 16.15 below the middle, so a little higher than before, 13.15.
        assertEquals(13.15f, belowDeg(32.3f), 0.01f)
    }

    @Test fun noViewYetKeepsTheOldPlace() {
        assertEquals(HintPlace.BELOW_TAN, HintPlace.belowTan(0f), 0f)
    }
}
