package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.25: a RayNeo GT's sensor axes, from its Send logs (XREAL Beam Pro, 25 Nov 2026). */
class RayNeoAxes1625Test {
    private val axes = GlassesModel.RAYNEO.defaultAxes

    @Test fun upIsTheSensorsZ() {
        assertEquals(AxisMap.XFRONT_YLEFT_ZUP, axes)
        // The head level: -0.009, -0.853, 9.733 m/s2
        val level = axes.apply(Vec3(-0.009f, -0.853f, 9.733f))
        assertTrue("$level", level.y / level.length > 0.99f)
    }

    @Test fun lookingDownAtThePhoneLeansUpTowardsTheFront() {
        // Worn, gravity 4.95, -0.51, 8.47 m/s2: about 30 degrees of a head looking down, read as pitch, not roll
        val g = axes.apply(Vec3(4.95f, -0.51f, 8.47f))
        val n = g.length
        // The head's front is -z in head axes: world up leans that way when looking down
        assertTrue("$g", -g.z / n > 0.45f)
        assertTrue("$g", kotlin.math.abs(g.x) / n < 0.1f)
    }

    @Test fun aPickThatOnlyFoundUpGivesWayToTheModelsOwn() {
        // The GT's phone saved "x right, y front, z up" from the gravity check
        assertTrue(GravityAxes.sameUp(AxisMap.XRIGHT_ZUP_YFRONT, AxisMap.XFRONT_YLEFT_ZUP))
        assertFalse(GravityAxes.sameUp(AxisMap.XRIGHT_YUP_ZBACK, AxisMap.XFRONT_YLEFT_ZUP))
        // The check keeps the model's own when it fits
        val v = GravityAxes.check(GravityAxes.Rest(Vec3(4.95f, -0.51f, 8.47f), "test"), AxisMap.XFRONT_YLEFT_ZUP, AxisMap.XFRONT_YLEFT_ZUP)
        assertEquals(null, v.axes)
    }
}
