package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1.6.33 (Crr66, VITURE Luma: "sometimes the launcher (or space) is rotated like 45 degree when I open it"): his Space
 * started with the head moving, the start gate and its fallback never passed for 30 s, and the view kept the first
 * sample's tilt (roll -41 to -61 deg while gravity said 0). Gravity now pulls gently meanwhile.
 */
class PreGatePull1633Test {
    private val axes = AxisMap.XRIGHT_YUP_ZBACK
    private val g = 9.81f

    /** Gravity's reaction (sensor axes) for a head rolled by [rollDeg], level otherwise. */
    private fun rolled(rollDeg: Float): Vec3 {
        val head = Quat.axisAngle(Vec3.FORWARD, rollDeg * Math.PI.toFloat() / 180f)
        return axes.unapply(head.conjugate().rotate(Vec3.UP) * g)
    }

    /** Starts level, then the head sits rolled 40 deg with a restless 0.35 rad/s jitter, so no window can align. */
    private fun run(preGateKp: Float): Pair<HeadFusion, Float> {
        val f = HeadFusion(FusionSettings(axes = axes, preGateKp = preGateKp))
        var t = 0L
        f.update(Vec3.ZERO, rolled(0f), t)
        val raw = rolled(40f)
        for (i in 0 until 10_000) {
            t += 1_000_000L
            val w = 0.35f * sin(2f * Math.PI.toFloat() * 7f * i / 1000f)
            f.update(Vec3(0.3f * w, w, 0.5f * w * cos(i * 0.01f)), raw, t)
        }
        val (_, roll) = f.levelPitchRoll()
        return f to roll
    }

    @Test
    fun aRestlessStartLevelsWithinSeconds() {
        val (f, roll) = run(FusionSettings().preGateKp)
        assertFalse("the gate stays shut", f.startAligned)
        assertTrue(f.preGatePulled > 0)
        // 40 deg of real roll, read as 0 at the start; 10 s later the view has it.
        assertEquals(40f, kotlin.math.abs(roll), 4f)
    }

    @Test
    fun withoutThePullTheFirstSamplesTiltStays() {
        val (_, roll) = run(0f)
        assertTrue("roll $roll", kotlin.math.abs(roll) < 10f)
    }
}
