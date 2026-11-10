package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.3: the Steady mode canvas is set level, so it is never upside down. */
class SteadyCanvasLevelTest {
    private fun roll(q: Quat) = q.yawPitchRoll().third

    @Test
    fun aLevelHeadKeepsItsHeadingAndLook() {
        val head = Quat.fromYawPitch(30f, 10f)
        val c = SteadyMode.levelCanvas(head)
        val a = Angles.of(c.forward)
        assertEquals(30f, a.yawDeg, 0.1f)
        assertEquals(10f, a.pitchDeg, 0.1f)
        assertEquals(0f, roll(c), 0.1f)
    }

    @Test
    fun anUpsideDownHeadGivesAnUprightCanvas() {
        // Glasses lying upside down: rolled over, facing 40 degrees right.
        val head = (Quat.fromYawPitch(40f, 0f) * Quat.axisAngle(Vec3.FORWARD, Math.PI.toFloat())).normalized()
        assertTrue("the head is upside down", kotlin.math.abs(roll(head)) > 170f)
        val c = SteadyMode.levelCanvas(head)
        assertEquals(0f, roll(c), 0.1f)
        assertEquals(40f, Angles.of(c.forward).yawDeg, 0.5f)
    }

    @Test
    fun aSteepLookIsLeftOut() {
        val c = SteadyMode.levelCanvas(Quat.fromYawPitch(-20f, 70f))
        assertEquals(0f, Angles.of(c.forward).pitchDeg, 0.1f)
        assertEquals(-20f, Angles.of(c.forward).yawDeg, 0.5f)
    }
}
