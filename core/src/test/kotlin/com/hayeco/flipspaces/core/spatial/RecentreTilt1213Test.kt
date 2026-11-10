package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 1.2.13 (Discussion #81): the tester's head was really about 40 degrees up for 20 minutes of videos (accelerometer
 * about -0.1, -7.6, 6.2), and each of 24 recentres kept the windows on the horizon, 40 degrees below the view. A
 * recentre now takes the head's pitch as straight ahead when it is more than 15 degrees from level (Recenter follows
 * head tilt, on by default), never its roll; nearer level, and with the switch off, the horizon stays straight ahead.
 */
class RecentreTilt1213Test {
    private val g = 9.81f
    private val axes = AxisMap.XRIGHT_YDOWN_ZFRONT

    /** The tester's accelerometer while watching a video, in the sensor's axes. */
    private val logWatching = Vec3(-0.11f, -7.65f, 6.24f)

    /** [seconds] of a worn head held still at 1 kHz with gravity's reaction [raw] (sensor axes) and an 8 Hz tremor. */
    private fun feed(fusion: HeadFusion, raw: Vec3, seconds: Float = 1.5f, fromNs: Long = 0L): Long {
        var t = fromNs
        for (i in 0 until (seconds * 1000).toInt()) {
            t += 1_000_000L
            val tremor = 0.012f * sin(2f * Math.PI.toFloat() * 8f * i / 1000f)
            fusion.update(Vec3(tremor, 0f, 0.5f * tremor), raw, t)
        }
        return t
    }

    /** Gravity's reaction in the sensor's axes for a display at [pitchDeg] (up positive), no roll. */
    private fun headAt(pitchDeg: Float): Vec3 = axes.unapply(Quat.fromYawPitch(0f, pitchDeg).conjugate().rotate(Vec3.UP) * g)

    /** Where the front window (the layout's 0, 0) shows in the view drawn with [tracked]: degrees right and up of the middle. */
    private fun frontInView(tracked: Quat, manualYawDeg: Float = 0f): Angles {
        val head = HeadView.pose(tracked, manualYawDeg)
        val v = head.conjugate().rotate(Vec3.direction(0f, 0f))
        return Angles.of(v)
    }

    private fun fusion(follows: Boolean = true) = HeadFusion(FusionSettings(axes = axes, recentreFollowsTilt = follows))

    @Test
    fun aRecentre40DegreesUpPutsTheFrontWindowInView() {
        val f = fusion()
        val t = feed(f, logWatching)
        // Before: the windows sit on the horizon, 39 degrees below the middle of the view.
        assertEquals(-39.2f, frontInView(f.pose().orientation).pitchDeg, 1.5f)
        feed(f, logWatching, 1f, t)
        val line = f.recentre()
        println("  40 up: $line")
        assertTrue(line, line.contains("recenter follows head tilt"))
        assertEquals(39.2f, f.recentrePitchDeg, 1.5f)
        val q = f.pose().orientation
        val front = frontInView(q)
        assertEquals(0f, front.yawDeg, 0.5f)
        assertEquals(0f, front.pitchDeg, 0.5f)
        // Level to that view: the recentre adds no roll; the log's head itself leans 0.8 degrees.
        assertEquals(0.82f, f.levelPitchRoll().second, 0.1f)
        assertEquals(f.levelPitchRoll().second, q.yawPitchRoll().third, 0.2f)
        // The gravity check's rest is still the sensor's own reading, and kept on the One's layout.
        val rest = f.takeRestGravity()
        assertNotNull(rest)
        assertEquals(0f, (rest!!.raw - logWatching).length, 0.1f)
        assertNull(GravityAxes.check(rest, axes, axes).axes)
        // Pitch and roll against gravity (the worn check) still read the real tilt.
        assertEquals(39.2f, f.levelPitchRoll().first, 1.5f)
        assertTrue(f.attitudeLine(), f.attitudeLine().contains("straight ahead 39."))
    }

    @Test
    fun theWindowsStayInTheRoomAfterATiltedRecentre() {
        // Body Anchor: after recentring 40 up, a head back to level sees the front window 40 above the middle.
        val head = GyroHead(HeadFusion(FusionSettings()))
        head.run(0.5f, Vec3.ZERO)
        head.run(1f, Vec3(40f * DEG, 0f, 0f))
        head.run(0.5f, Vec3.ZERO)
        head.f.recentre()
        assertEquals(0f, frontInView(head.f.pose().orientation).pitchDeg, 0.5f)
        head.run(1f, Vec3(-40f * DEG, 0f, 0f))
        head.run(0.5f, Vec3.ZERO)
        assertEquals(40f, frontInView(head.f.pose().orientation).pitchDeg, 1f)
        // A head turned 20 degrees right sees the front window 20 to its left.
        head.run(1f, head.q.conjugate().rotate(Vec3.UP) * (-20f * DEG))
        assertEquals(-20f, frontInView(head.f.pose().orientation).yawDeg, 2f)
    }

    /** A head turned on the gyro at 1 kHz from level, with gravity to match: [q] is the truth. */
    private class GyroHead(val f: HeadFusion) {
        var q = Quat.IDENTITY
        private var t = 0L

        fun run(seconds: Float, omega: Vec3) {
            repeat((seconds * 1000).toInt()) {
                q = (q * Quat.fromGyro(omega, 0.001f)).normalized()
                f.update(omega, q.conjugate().rotate(Vec3.UP) * 9.81f, t)
                t += 1_000_000L
            }
        }
    }

    @Test
    fun smoothFollowStartsFromTheTiltedStraightAhead() {
        val f = fusion()
        feed(f, headAt(-35f))
        f.recentre()
        val follow = FollowView().apply { mode = FollowMode.SMOOTH_FOLLOW }
        follow.recentre()
        val q = f.pose().orientation
        follow.update(q, 0L)
        follow.update(q, 500L)
        val front = frontInView(follow.relative(q)!!)
        assertEquals(0f, front.yawDeg, 0.5f)
        assertEquals(0f, front.pitchDeg, 0.5f)
    }

    @Test
    fun aRecentre5DegreesUpStaysOnTheHorizon() {
        val f = fusion()
        val t = feed(f, headAt(5f))
        feed(f, headAt(5f), 1f, t)
        val line = f.recentre()
        println("  5 up: $line")
        assertTrue(line, line.contains("the horizon") && line.contains("within 15 deg of level"))
        assertEquals(0f, f.recentrePitchDeg, 0f)
        assertEquals(-5f, frontInView(f.pose().orientation).pitchDeg, 0.5f)
        // And 14 degrees down, still within the threshold.
        val down = fusion()
        feed(down, headAt(-14f))
        down.recentre()
        assertEquals(14f, frontInView(down.pose().orientation).pitchDeg, 0.5f)
    }

    @Test
    fun withTheSwitchOffARecentreKeepsTheHorizon() {
        val f = fusion(follows = false)
        val t = feed(f, logWatching)
        feed(f, logWatching, 1f, t)
        val line = f.recentre()
        println("  switch off: $line")
        assertTrue(line, line.contains("recenter follows head tilt off"))
        assertEquals(0f, f.recentrePitchDeg, 0f)
        assertEquals(-39.2f, frontInView(f.pose().orientation).pitchDeg, 1.5f)
        assertEquals(0f, f.recentrePitch(40f), 0f)
        // On, the threshold is 15 degrees either way.
        val on = fusion()
        assertEquals(0f, on.recentrePitch(15f), 0f)
        assertEquals(16f, on.recentrePitch(16f), 0f)
        assertEquals(-40f, on.recentrePitch(-40f), 0f)
        assertEquals(0f, on.recentrePitch(Float.NaN), 0f)
    }

    @Test
    fun lyingFlatLookingUpRecentresWithAFiniteHeading() {
        val f = fusion()
        feed(f, headAt(88f))
        f.recentre()
        val q = f.pose().orientation
        assertTrue(HeadView.isUsable(q))
        val front = frontInView(q)
        assertEquals(0f, front.yawDeg, 1f)
        assertEquals(0f, front.pitchDeg, 1f)
    }

    @Test
    fun aRecentreAfterATurnAndATiltCentresTheFrontWindow() {
        // Turn 30 degrees right while level, then look 40 up, both on the gyro, and recentre.
        val head = GyroHead(HeadFusion(FusionSettings()))
        val f = head.f
        head.run(0.5f, Vec3.ZERO)
        head.run(1f, Vec3(0f, -30f * DEG, 0f))
        head.run(1f, Vec3(40f * DEG, 0f, 0f))
        head.run(0.5f, Vec3.ZERO)
        val (yaw, pitch, _) = f.pose().orientation.yawPitchRoll()
        assertEquals(30f, yaw, 2f)
        assertEquals(40f, pitch, 2f)
        f.recentre()
        val front = frontInView(f.pose().orientation)
        assertEquals(0f, front.yawDeg, 0.5f)
        assertEquals(0f, front.pitchDeg, 0.5f)
        assertEquals(0f, f.pose().orientation.yawPitchRoll().third, 0.5f)
    }
}
