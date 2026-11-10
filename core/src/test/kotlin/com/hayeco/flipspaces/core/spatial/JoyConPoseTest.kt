package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoyConPoseTest {
    private val deg = (PI / 180).toFloat()

    private fun settled(): JoyConPose = JoyConPose(right = true).apply {
        // Held like a remote: z out of the face reads up.
        repeat(400) { update(0f, 0f, 0f, 0f, 0f, 1f, 0.005f) }
    }

    private fun tiltDeg(pose: JoyConPose): Float {
        val up = pose.orientation.rotate(Vec3(0f, 1f, 0f))
        return kotlin.math.acos(up.y.coerceIn(-1f, 1f)) / deg
    }

    @Test
    fun `held flat it points forward with its face up`() {
        val forward = settled().orientation.rotate(Vec3(0f, 0f, -1f))
        val up = settled().orientation.rotate(Vec3(0f, 1f, 0f))
        assertTrue("forward $forward", abs(forward.z + 1f) < 0.02f)
        assertTrue("up $up", abs(up.y - 1f) < 0.02f)
    }

    @Test
    fun `a clockwise roll about the tip takes its right side down`() {
        val pose = settled()
        // x points at the tip, so a positive turn about x is clockwise seen from behind. The accelerometer follows it.
        val steps = 200
        val rate = (PI / 2).toFloat() / (steps * 0.005f)
        for (i in 1..steps) {
            val a = (PI / 2 * i / steps).toFloat()
            pose.update(rate, 0f, 0f, 0f, sin(a), cos(a), 0.005f)
        }
        val right = pose.orientation.rotate(Vec3(1f, 0f, 0f))
        assertTrue("right $right", right.y < -0.9f)
    }

    @Test
    fun `turning left about the face takes the tip left, as measured held upright`() {
        val pose = JoyConPose(right = true)
        // Upright with the face toward the user, x reads up, as in the capture.
        repeat(400) { pose.update(0f, 0f, 0f, 1f, 0f, 0f, 0.005f) }
        val tip = pose.orientation.rotate(Vec3(0f, 0f, -1f))
        assertTrue("upright tip $tip", tip.y > 0.9f)
        val steps = 200
        val rate = (PI / 2).toFloat() / (steps * 0.005f)
        for (i in 1..steps) {
            val a = (PI / 2 * i / steps).toFloat()
            // Measured: a turn to the left reads positive about z, and y falls toward -1.
            pose.update(0f, 0f, rate, cos(a), -sin(a), 0f, 0.005f)
        }
        val leaned = pose.orientation.rotate(Vec3(0f, 0f, -1f))
        assertTrue("leaned tip $leaned", abs(leaned.y) < 0.2f)
    }

    @Test
    fun `recentre lines it up with the head`() {
        val pose = settled()
        val head = Quat.fromYawPitch(90f, 0f)
        pose.recentre(head)
        val forward = pose.orientation.rotate(Vec3(0f, 0f, -1f))
        val headForward = head.rotate(Vec3(0f, 0f, -1f))
        assertTrue("$forward against $headForward", abs(forward.x - headForward.x) < 0.02f && abs(forward.z - headForward.z) < 0.02f)
    }

    @Test
    fun `a rest learns the gyro drift, and then it stops turning`() {
        val pose = JoyConPose(right = true)
        // 2 degrees a second of drift about the face (world up when flat).
        val drift = 2f * deg
        repeat(800) { pose.update(0f, 0f, drift, 0f, 0f, 1f, 0.005f) }
        assertTrue("at rest", pose.atRest)
        assertTrue("learned", pose.biasLearned)
        assertEquals(drift, pose.biasRadS.y, 0.2f * deg)
        val before = pose.orientation
        repeat(2000) { pose.update(0f, 0f, drift, 0f, 0f, 1f, 0.005f) }
        assertTrue("turned ${before.angleTo(pose.orientation)} degrees in 10 s", before.angleTo(pose.orientation) < 1f)
    }

    @Test
    fun `a saved drift is used from the start`() {
        val pose = JoyConPose(right = true)
        val drift = 2f * deg
        pose.restoreBias(Vec3(0f, drift, 0f))
        repeat(100) { pose.update(0f, 0f, drift, 0f, 0f, 1f, 0.005f) }
        val before = pose.orientation
        // Held in a hand (a little shake), so no rest: the saved drift alone keeps it still.
        for (i in 0 until 1000) pose.update(0f, 0f, drift, 0f, 0.02f * sin(i * 0.7f), 1f, 0.005f)
        assertTrue("turned ${before.angleTo(pose.orientation)} degrees in 5 s", before.angleTo(pose.orientation) < 1.5f)
    }

    @Test
    fun `a fast spin with its push sideways does not tip it over`() {
        val pose = settled()
        // 2 s turning about world up at 6 rad/s, the swing pushing 0.6 g along the length the whole time.
        repeat(400) { pose.update(0f, 0f, 6f, 0.6f, 0f, 1f, 0.005f) }
        assertTrue("tilted ${tiltDeg(pose)} degrees", tiltDeg(pose) < 3f)
    }

    @Test
    fun `it lines up with the head by itself when first held level and still`() {
        val pose = JoyConPose(right = true)
        val head = Quat.fromYawPitch(-60f, 0f)
        repeat(200) {
            pose.update(0f, 0f, 0f, 0f, 0f, 1f, 0.005f)
            pose.align(head, 0.005f)
        }
        assertTrue(pose.isAligned)
        val forward = pose.orientation.rotate(Vec3(0f, 0f, -1f))
        val headForward = head.rotate(Vec3(0f, 0f, -1f))
        assertTrue("$forward against $headForward", (forward - headForward).length < 0.05f)
    }

    @Test
    fun `small yaw differences are left alone, large ones come back slowly`() {
        val pose = settled()
        pose.recentre(Quat.IDENTITY)
        // Ten degrees off the head: inside the dead band.
        val head10 = Quat.fromYawPitch(10f, 0f)
        repeat(20_000) { pose.update(0f, 0f, 0f, 0f, 0f, 1f, 0.005f); pose.align(head10, 0.005f) }
        val yaw10 = pose.orientation.rotate(Vec3(0f, 0f, -1f))
        assertTrue("left alone $yaw10", abs(yaw10.x) < 0.02f)
        // Forty degrees: pulled back toward the band at half a degree a second at most.
        val head40 = Quat.fromYawPitch(40f, 0f)
        val before = pose.orientation
        repeat(2_000) { pose.update(0f, 0f, 0f, 0f, 0f, 1f, 0.005f); pose.align(head40, 0.005f) }
        val moved = before.angleTo(pose.orientation)
        assertTrue("moved $moved degrees in 10 s", moved <= 5.1f)
    }
}

class JoyConClockTest {
    @Test
    fun `three samples in one read are spread a third of a report apart and set back by the link`() {
        val clock = JoyConClock()
        var t = 1_000_000_000L
        var stamps = emptyList<JoyConClock.Stamp>()
        repeat(50) {
            stamps = List(3) { clock.sample(t) }
            t += 15_000_000L
        }
        val read = t - 15_000_000L
        assertEquals(read - JoyConClock.LINK_DELAY_NS, stamps[2].sampleNs)
        assertEquals(5f, (stamps[1].sampleNs - stamps[0].sampleNs) / 1e6f, 0.1f)
        stamps.forEach { assertEquals(0.005f, it.dt, 0.0002f) }
    }

    @Test
    fun `late arrivals barely move the clock and time never goes back`() {
        val clock = JoyConClock()
        var t = 0L
        var last = Long.MIN_VALUE
        val random = java.util.Random(4)
        val dts = ArrayList<Float>()
        repeat(400) { n ->
            val jitter = (random.nextDouble() * 4_000_000).toLong()
            repeat(3) {
                val s = clock.sample(t + jitter)
                assertTrue(s.sampleNs > last)
                last = s.sampleNs
                if (n > 50) dts += s.dt
            }
            t += 15_000_000L
        }
        val sorted = dts.sorted()
        assertEquals(0.005f, sorted[sorted.size / 2], 0.0005f)
        assertTrue("p99 ${sorted[(sorted.size * 0.99).toInt()]}", sorted[(sorted.size * 0.99).toInt()] < 0.0075f)
    }

    @Test
    fun `the period is learned and a lost report is counted`() {
        val clock = JoyConClock()
        var t = 0L
        repeat(300) {
            repeat(3) { clock.sample(t) }
            t += 11_250_000L
        }
        assertEquals(11.25f, clock.periodNs / 1e6f, 0.2f)
        t += 3 * 11_250_000L
        repeat(3) { clock.sample(t) }
        assertEquals(1L, clock.gaps)
    }
}

class JoyConArmTest {
    private val level = Quat.IDENTITY

    @Test
    fun `turning the head alone leaves the hand where it is`() {
        val arm = JoyConArm(right = true)
        val start = arm.update(level, 0f, Quat.IDENTITY, 0.005f)
        var p = start
        for (yaw in 0..60) p = arm.update(level, 0f, Quat.fromYawPitch(yaw.toFloat(), 0f), 0.005f)
        assertTrue("moved ${(p - start).length} m", (p - start).length < 0.001f)
    }

    @Test
    fun `the body follows the head while the arm swings`() {
        val arm = JoyConArm(right = true)
        arm.update(level, 0f, Quat.IDENTITY, 0.005f)
        val head = Quat.fromYawPitch(60f, 0f)
        repeat(400) { arm.update(level, 5f, head, 0.005f) }
        val gaze = head.rotate(Vec3(0f, 0f, -1f))
        assertTrue("torso ${arm.torsoForward}", (arm.torsoForward - Vec3(gaze.x, 0f, gaze.z).normalized()).length < 0.05f)
    }

    /** Daydream's own arm, with no offset and its full shoulder width. */
    private fun daydream(right: Boolean) = JoyConArm(right).apply {
        offset = Vec3(0f, 0f, 0f)
        spread = 1f
    }

    @Test
    fun `right hand right, left hand left, both low and in front`() {
        val r = daydream(right = true).update(level, 0f, Quat.IDENTITY, 0.005f)
        val l = daydream(right = false).update(level, 0f, Quat.IDENTITY, 0.005f)
        assertTrue("$r", r.x > 0.1f && r.y < -0.3f && r.z < -0.2f)
        assertTrue("$l", l.x < -0.1f && abs(l.x + r.x) < 1e-4f)
    }

    @Test
    fun `the tuned default moves both hands by the offset and narrows them`() {
        val plain = daydream(right = true).update(level, 0f, Quat.IDENTITY, 0.005f)
        val tuned = JoyConArm(right = true).update(level, 0f, Quat.IDENTITY, 0.005f)
        val d = tuned - plain
        assertTrue("$d", abs(d.y - JoyConArm.DEFAULT_OFFSET.y) < 1e-4f && abs(d.z - JoyConArm.DEFAULT_OFFSET.z) < 1e-4f)
        assertTrue("$plain to $tuned", tuned.x > 0f && tuned.x < plain.x)
    }

    @Test
    fun `raising the Joy-Con raises the hand, and a wrist flick moves it less than the arm`() {
        val arm = JoyConArm(right = true)
        val low = arm.update(level, 0f, Quat.IDENTITY, 0.005f)
        val up = JoyConArm(right = true).update(Quat.fromYawPitch(0f, 60f), 0f, Quat.IDENTITY, 0.005f)
        assertTrue("$low to $up", up.y > low.y + 0.2f)
        val side = JoyConArm(right = true).update(Quat.fromYawPitch(30f, 0f), 0f, Quat.IDENTITY, 0.005f)
        // A 30 degree turn moves the grip less than a stiff 0.3 m arm from the shoulder would (0.15 m).
        assertTrue("moved ${(side - low).length}", (side - low).length in 0.02f..0.15f)
    }

    @Test
    fun `velocity follows the hand`() {
        val arm = JoyConArm(right = true)
        for (i in 0..100) arm.update(Quat.fromYawPitch(0f, i * 0.5f), 1.7f, Quat.IDENTITY, 0.005f)
        assertTrue("${arm.velocity}", arm.velocity.y > 0.1f)
    }
}

class JoyConStickTest {
    @Test
    fun `a stick resting off centre reads zero, the edge reads one`() {
        assertEquals(0f to 0f, JoyConStick.shape(0.08f, -0.05f))
        assertFalse(JoyConStick.touched(0.08f, -0.05f))
        val (x, _) = JoyConStick.shape(0.95f, 0f)
        assertEquals(1f, x, 1e-4f)
        val (sx, _) = JoyConStick.shape(0.2f, 0f)
        assertTrue("$sx", sx > 0f && sx < 0.15f)
    }
}
