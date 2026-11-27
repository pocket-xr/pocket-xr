package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 1.6.32 (Zen's Afterglow, RayNeo GT, head on a pillow: "turns everything 180 degrees unless I'm looking slight down"):
 * his recentres with the head tipped back past straight up took roll -165 deg and the windows showed upside down; those
 * with the chin a little down (roll -10 deg) were fine. A recentre past straight up now keeps the windows' top at the
 * crown.
 */
class RecentreLyingBack1632Test {
    private val g = 9.81f
    private val axes = AxisMap.XRIGHT_YDOWN_ZFRONT

    private fun feed(fusion: HeadFusion, raw: Vec3, seconds: Float = 1.5f, fromNs: Long = 0L): Long {
        var t = fromNs
        for (i in 0 until (seconds * 1000).toInt()) {
            t += 1_000_000L
            val tremor = 0.012f * sin(2f * Math.PI.toFloat() * 8f * i / 1000f)
            fusion.update(Vec3(tremor, 0f, 0.5f * tremor), raw, t)
        }
        return t
    }

    /** Gravity's reaction in the sensor's axes for a display tipped back by [pitchDeg] from level (past 90 is past straight up). */
    private fun headAt(pitchDeg: Float): Vec3 = axes.unapply(Quat.fromYawPitch(0f, pitchDeg).conjugate().rotate(Vec3.UP) * g)

    /** After a recentre at [pitchDeg]: the front window's place in the view and which way its top points there. */
    private fun recentredAt(pitchDeg: Float): Triple<Angles, Float, HeadFusion> {
        val f = HeadFusion(FusionSettings(axes = axes))
        val raw = headAt(pitchDeg)
        val t = feed(f, raw)
        feed(f, raw, 1f, t)
        println("  $pitchDeg deg: ${f.recentre()}")
        val head = HeadView.pose(f.pose().orientation, 0f)
        val front = Angles.of(head.conjugate().rotate(Vec3.direction(0f, 0f)))
        // The world's up at the front window, seen in the view: positive y is the window's top at the view's top.
        val topInView = head.conjugate().rotate(Quat.fromYawPitch(0f, f.recentrePitchDeg).rotate(Vec3.UP)).y
        return Triple(front, topInView, f)
    }

    @Test
    fun tippedBackPastStraightUpTheWindowsStayUpright() {
        for (p in listOf(100f, 106f, 115f)) {
            val (front, top, f) = recentredAt(p)
            assertEquals(0f, front.yawDeg, 1f)
            assertEquals(0f, front.pitchDeg, 1f)
            // Within 10 deg of straight up the heading comes from the ears and the pitch is over 90; further back, a half turn.
            if (p > 110f) assertTrue("$p deg: flipped", f.recentreFlipped)
            // Before 1.6.32 the reference's up pointed away from the crown: the windows were upside down.
            val viewUp = f.pose().orientation.rotate(Vec3.UP)
            assertTrue("$p deg: view up $viewUp", viewUp.y > 0.95f)
        }
    }

    @Test
    fun chinALittleDownOrLookingUpIsAsBefore() {
        for (p in listOf(0f, 40f, 75f, 85f)) {
            val (front, _, f) = recentredAt(p)
            assertEquals(0f, front.pitchDeg, 1f)
            assertFalse("$p deg", f.recentreFlipped)
            val viewUp = f.pose().orientation.rotate(Vec3.UP)
            assertTrue("$p deg: view up $viewUp", viewUp.y > 0.95f)
        }
    }
}
