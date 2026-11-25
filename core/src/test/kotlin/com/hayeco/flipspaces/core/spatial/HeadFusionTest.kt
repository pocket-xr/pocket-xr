package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeadFusionTest {
    private val g = 9.81f
    private val stepNs = 1_000_000L // 1 kHz, like the One family stream

    /** Feeds [seconds] of samples of a head turning at [omega] (head frame, rad/s) from [q0]. */
    private fun simulate(
        fusion: HeadFusion,
        seconds: Float,
        omega: Vec3,
        start: Quat,
        startNs: Long = 0L,
        gyroBias: Vec3 = Vec3.ZERO,
        noise: java.util.Random? = null,
    ): Pair<Quat, Long> {
        var q = start
        var t = startNs
        val n = (seconds * 1000).toInt()
        repeat(n) {
            q = (q * Quat.fromGyro(omega, 0.001f)).normalized()
            val up = q.conjugate().rotate(Vec3.UP) * g
            fun jitter() = noise?.let { (it.nextGaussian() * 0.01).toFloat() } ?: 0f
            fusion.update(omega + gyroBias + Vec3(jitter(), jitter(), jitter()), up + Vec3(jitter(), jitter(), jitter()), t)
            t += stepNs
        }
        return Pair(q, t)
    }

    @Test
    fun startsLevelFromGravity() {
        val f = HeadFusion()
        val tilted = Quat.fromYawPitch(0f, 20f)
        f.update(Vec3.ZERO, tilted.conjugate().rotate(Vec3.UP) * g, 0)
        val (_, pitch, roll) = f.pose().orientation.yawPitchRoll()
        assertEquals(20f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun followsAYawTurnAndBack() {
        val f = HeadFusion()
        var (q, t) = simulate(f, 0.2f, Vec3.ZERO, Quat.IDENTITY)
        // Turn right 90 degrees in one second (negative rotation about up), then back.
        val rate = (Math.PI / 2).toFloat()
        simulate(f, 1f, Vec3(0f, -rate, 0f), q, t).also { q = it.first; t = it.second }
        val (yaw, _, _) = f.pose().orientation.yawPitchRoll()
        assertEquals(90f, yaw, 1f)
        simulate(f, 1f, Vec3(0f, rate, 0f), q, t).also { q = it.first; t = it.second }
        val (yawBack, pitch, roll) = f.pose().orientation.yawPitchRoll()
        assertEquals(0f, yawBack, 1f)
        assertEquals(0f, pitch, 1f)
        assertEquals(0f, roll, 1f)
    }

    @Test
    fun accelerometerPullsBackTiltError() {
        val f = HeadFusion()
        // A wrong start: initialise from a reading tilted 15 degrees, then hold level. 0.5.11: 8 s
        // (was 5): after 2 s of settling, gravity pulls at the static tier's 0.3, not at 1.0.
        f.update(Vec3.ZERO, Quat.fromYawPitch(0f, 15f).conjugate().rotate(Vec3.UP) * g, 0)
        simulate(f, 8f, Vec3.ZERO, Quat.IDENTITY, startNs = stepNs)
        val (_, pitch, _) = f.pose().orientation.yawPitchRoll()
        assertEquals(0f, pitch, 0.5f)
    }

    @Test
    fun learnsGyroBiasWhileStill() {
        val f = HeadFusion()
        val bias = Vec3(0.01f, 0.02f, -0.015f)
        simulate(f, 10f, Vec3.ZERO, Quat.IDENTITY, gyroBias = bias)
        val learned = f.pose().gyroBias
        assertEquals(bias.y, learned.y, 0.002f)
        val yawAfterLearning = f.pose().orientation.yawPitchRoll().first
        // After bias is learned, a further minute of stillness drifts less than a degree.
        simulate(f, 60f, Vec3.ZERO, Quat.IDENTITY, startNs = 10_000_000_000L, gyroBias = bias)
        val drift = angleDiff(f.pose().orientation.yawPitchRoll().first, yawAfterLearning)
        assertTrue("drift $drift", drift < 1f)
    }

    @Test
    fun noisySensorsStayWithinADegreeOverATurn() {
        val f = HeadFusion()
        val rnd = java.util.Random(7)
        var (q, t) = simulate(f, 1f, Vec3.ZERO, Quat.IDENTITY, noise = rnd)
        simulate(f, 2f, Vec3(0.2f, -0.8f, 0.1f), q, t, noise = rnd).also { q = it.first; t = it.second }
        simulate(f, 2f, Vec3(-0.2f, 0.8f, -0.1f), q, t, noise = rnd).also { q = it.first; t = it.second }
        assertTrue(f.pose().orientation.angleTo(q) < 2f)
    }

    @Test
    fun recentreZeroesYawOnly() {
        // 1.6.28 follows every tilt on a recentre (RecentreTilt1213Test); this keeps the 15 degree horizon snap it was written for.
        val f = HeadFusion(FusionSettings(recentreTiltMinDeg = 15f))
        val start = Quat.fromYawPitch(40f, 10f)
        simulate(f, 0.5f, Vec3.ZERO, start)
        f.recentre()
        val (yaw, pitch, _) = f.pose().orientation.yawPitchRoll()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(10f, pitch, 0.5f)
    }

    @Test
    fun predictionExtrapolatesTheTurn() {
        val f = HeadFusion()
        val (q, t) = simulate(f, 0.2f, Vec3.ZERO, Quat.IDENTITY)
        simulate(f, 0.2f, Vec3(0f, -1f, 0f), q, t)
        val pose = f.pose()
        val now = pose.orientation.yawPitchRoll().first
        val ahead = pose.predicted(0.02f).yawPitchRoll().first
        assertEquals(0.02f * RAD, ahead - now, 0.2f)
    }

    @Test
    fun ignoresTimestampJumpsAndMagnetometerReports() {
        val f = HeadFusion()
        f.update(Vec3.ZERO, Vec3(0f, g, 0f), 0)
        f.update(Vec3(0f, 50f, 0f), Vec3(0f, g, 0f), 5_000_000_000L) // 5 s gap: not integrated
        assertTrue(abs(f.pose().orientation.yawPitchRoll().first) < 0.01f)
        val mag = ImuReport(1, 1, ImuReportType.MAGNETOMETER, Vec3.ZERO, Vec3.ZERO, Vec3(1f, 1f, 1f), 0f)
        assertEquals(null, f.update(mag))
    }

    @Test
    fun axisMapsSuggestPresetsFromGravity() {
        assertTrue(AxisMap.XRIGHT_YUP_ZBACK in AxisMap.suggest(Vec3(0f, 9.8f, 0f)))
        assertTrue(AxisMap.XRIGHT_YDOWN_ZFRONT in AxisMap.suggest(Vec3(0f, -9.8f, 0f)))
        assertTrue(AxisMap.XRIGHT_ZUP_YFRONT in AxisMap.suggest(Vec3(0f, 0f, 1f)))
        assertTrue(AxisMap.XRIGHT_YUP_ZBACK !in AxisMap.suggest(Vec3(0f, 0f, 1f)))
        assertTrue(AxisMap.suggest(Vec3(1f, 1f, 1f)).isEmpty())
        // Every preset is a proper rotation: right x up = back.
        AxisMap.entries.forEach { m ->
            val x = m.apply(Vec3(1f, 0f, 0f))
            val y = m.apply(Vec3(0f, 1f, 0f))
            val z = m.apply(Vec3(0f, 0f, 1f))
            assertEquals(m.label, 1f, (x cross y) dot z, 1e-6f)
        }
    }

    @Test
    fun rateMeterCountsPerSecond() {
        val m = RateMeter()
        var t = 0L
        repeat(2001) { m.tick(t); t += 1_000_000L }
        assertEquals(1000.0, m.rate, 5.0)
    }
}
