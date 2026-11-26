package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A RayNeo GT's sensor axes, from its Send logs (XREAL Beam Pro, 25 Nov 2026). 1.6.25 took z for up; 1.6.30 (the same
 * GT: "moving left to right swings the image like a pendulum"): x is up, y left, z back.
 */
class RayNeoAxes1625Test {
    private val axes = GlassesModel.RAYNEO.defaultAxes

    @Test fun lookingUpAtThePictureIsUpright() {
        assertEquals(AxisMap.XUP_YLEFT_ZBACK, axes)
        // Looking up from the phone: 9.79, -0.71, 0.44 m/s2
        val up = axes.apply(Vec3(9.79f, -0.71f, 0.44f))
        assertTrue("$up", up.y / up.length > 0.99f)
    }

    @Test fun lookingDownAtThePhoneIsAHeadLookingDown() {
        // The reading 1.6.25 took for level, -0.009, -0.853, 9.733 m/s2: the head's back points up, so world up leans
        // to +z in head axes (back), a steep pitch down, not a roll.
        val g = axes.apply(Vec3(-0.009f, -0.853f, 9.733f))
        val n = g.length
        assertTrue("$g", g.z / n > 0.95f)
        assertTrue("$g", abs(g.x) / n < 0.1f)
    }

    @Test fun aTurnIsAboutTheSensorsXAndANodAboutY() {
        // A turn: rotation about the head's up axis (head y); a nod about its right axis (head x), as 1.6.25 had it.
        val turn = axes.apply(Vec3(1f, 0f, 0f))
        assertEquals(1f, turn.y, 1e-6f)
        val nod = axes.apply(Vec3(0f, -1f, 0f))
        assertEquals(1f, nod.x, 1e-6f)
        assertEquals(AxisMap.XFRONT_YLEFT_ZUP.apply(Vec3(0f, -1f, 0f)).x, nod.x, 1e-6f)
    }

    @Test fun gravityNeverTurnsTheKnownLayout() {
        assertTrue(GlassesModel.RAYNEO.layoutKnown)
        // Looking down at the phone as the Space starts: 90 degrees from up, kept.
        val v = GravityAxes.check(GravityAxes.Rest(Vec3(3.55f, -0.17f, 9.12f), "start"), axes, axes, knownLayout = true)
        assertEquals(null, v.axes)
    }
}
