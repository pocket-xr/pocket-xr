package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.5.16, the targets the vc36 test showed 0.5.15 missed on the glasses:
 * yaw drift (item 1), the level calibration refused at 14.5 degrees (item 2), the recentre that
 * relevelled twice (item 3) and the start gate's log (item 4).
 */
class Fixes0516Test {
    /** The desk bias 0.5.14 measured (x right, y up, z back), which this session's x and z confirmed. */
    private val deskBias = Vec3(-0.00030f, -0.00376f, -0.00390f)

    /** 0.5.14's saved key on the Flip: -0.00028, -0.00372, -0.00388. */
    private val oldKey = Vec3(-0.00028f, -0.00372f, -0.00388f)

    private fun quat(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Quat =
        (Quat.axisAngle(Vec3.UP, (-yawDeg * PI / 180).toFloat()) * Quat.axisAngle(Vec3.RIGHT, (pitchDeg * PI / 180).toFloat()) *
            Quat.axisAngle(Vec3(0f, 0f, -1f), (rollDeg * PI / 180).toFloat())).normalized()

    data class Run(val yawDriftDegPerS: Double, val maxTiltDeg: Double, val fusion: HeadFusion, val lines: List<String>)

    /**
     * A head (truth yaw, pitch, roll in degrees at t) worn for [seconds], the IMU at 1 kHz with
     * [bias], 0.003 rad/s gyro noise and 0.05 m/s2 accelerometer noise. Yaw drift: the least squares
     * slope of the yaw error over the last minute.
     */
    private fun run(
        settings: FusionSettings,
        seconds: Double,
        angles: (Double) -> DoubleArray,
        bias: Vec3 = deskBias,
        seed: Long = 3,
        prepare: (HeadFusion) -> Unit = {},
    ): Run {
        val rnd = java.util.Random(seed)
        val f = HeadFusion(settings)
        prepare(f)
        val n = (seconds * 1000).toInt()
        val yawErrs = ArrayList<Pair<Double, Double>>()
        var maxTilt = 0.0
        val lines = ArrayList<String>()
        for (i in 0 until n) {
            val t = i / 1000.0
            val q = quat(angles(t)[0], angles(t)[1], angles(t)[2])
            val a1 = angles(t + 0.001)
            val q1 = quat(a1[0], a1[1], a1[2])
            val dq = (q.conjugate() * q1).normalized()
            val s = if (dq.w < 0) -1f else 1f
            val omega = Vec3(dq.x * s, dq.y * s, dq.z * s) * (2f / 0.001f)
            fun nz(sd: Double) = (rnd.nextGaussian() * sd).toFloat()
            val accel = q.conjugate().rotate(Vec3(0f, 9.81f, 0f)) + Vec3(nz(0.05), nz(0.05), nz(0.05))
            val pose = f.update(omega + bias + Vec3(nz(0.003), nz(0.003), nz(0.003)), accel, i * 1_000_000L)
            if (i % 10_000 == 0 && i > 0) lines += f.yawLine()
            if (t < 6.0) continue
            if (i % 100 == 0) {
                val e = pose.orientation.conjugate().rotate(Vec3.UP)
                val b = q.conjugate().rotate(Vec3.UP)
                maxTilt = maxOf(maxTilt, acos((e dot b).toDouble().coerceIn(-1.0, 1.0)) * 180 / PI)
                yawErrs += t to wrapDegrees(Angles.of(pose.orientation.forward).yawDeg - Angles.of(q.forward).yawDeg).toDouble()
            }
        }
        val last = yawErrs.filter { it.first >= seconds - 60 }
        val tm = last.sumOf { it.first } / last.size
        val em = last.sumOf { it.second } / last.size
        val slope = last.sumOf { (it.first - tm) * (it.second - em) } / last.sumOf { (it.first - tm) * (it.first - tm) }
        return Run(slope, maxTilt, f, lines)
    }

    /**
     * A worn head in use, as Mike's: it rests on a panel between glances. Every [periodS] s it
     * glances 15 to 30 degrees aside (out in 0.35 s, 2 s there, back in 0.35 s), with a 0.3 degree
     * sway at 0.25 Hz and a 0.05 degree tremor at 8 Hz between. It never holds every axis still for
     * 30 s (0.5.15's rule), but most 10 s windows on the yaw axis are still apart from one glance.
     */
    private fun restingHead(periodS: Double = 11.0): (Double) -> DoubleArray = { t ->
        val k = kotlin.math.floor(t / periodS)
        val u = t - k * periodS - 3.0
        val amp = (if (k.toLong() % 2 == 0L) 1 else -1) * (15.0 + 15.0 * ((k * 0.618) % 1.0))
        fun ease(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))
        val glance = when {
            u < 0 -> 0.0
            u < 0.35 -> amp * ease(u / 0.35)
            u < 2.35 -> amp
            u < 2.7 -> amp * (1 - ease((u - 2.35) / 0.35))
            else -> 0.0
        }
        doubleArrayOf(
            glance + 0.3 * sin(2 * PI * 0.25 * t) + 0.05 * sin(2 * PI * 8 * t),
            -3.0 + 0.3 * sin(2 * PI * 0.2 * t + 1.0) + 0.04 * sin(2 * PI * 7 * t),
            0.2 * sin(2 * PI * 0.15 * t + 2.0),
        )
    }

    @Test
    fun yawWindowsAWornHeadCanPassLearnTheYawBias() {
        val now = run(FusionSettings(), 420.0, restingHead())
        val old = run(FusionSettings.v0515(), 420.0, restingHead())
        println("worn head resting between glances, 7 min, nothing saved:")
        println("  0.5.15: yaw windows ${old.fusion.wornYawUpdates} used of ${old.fusion.wornWindows}, yaw drift %.4f deg/s, bias y %.5f".format(java.util.Locale.ROOT, old.yawDriftDegPerS, old.fusion.gyroBias.y))
        println("  0.5.16: yaw windows ${now.fusion.wornYawUpdates} used of ${now.fusion.wornWindows}, yaw drift %.4f deg/s, bias y %.5f, tilt max %.2f deg".format(java.util.Locale.ROOT, now.yawDriftDegPerS, now.fusion.gyroBias.y, now.maxTiltDeg))
        println("  " + now.lines.last())
        assertEquals(0, old.fusion.wornYawUpdates)
        assertTrue("0.5.15 drift ${old.yawDriftDegPerS}", abs(old.yawDriftDegPerS) > 0.15)
        assertTrue("windows ${now.fusion.wornYawUpdates}", now.fusion.wornYawUpdates >= 5)
        assertTrue("drift ${now.yawDriftDegPerS}", abs(now.yawDriftDegPerS) < 0.05)
    }

    @Test
    fun aSteadySlowTurnIsNotTakenForBias() {
        // A swivel chair turning 0.4 deg/s for 5 min, the head otherwise as above: every pair of
        // windows agrees, but each window's net turn is real. With the bias seeded right, the windows
        // must not drag it toward the turn by more than the anchor allows.
        val turning: (Double) -> DoubleArray = { t -> restingHead()(t).also { it[0] += 0.4 * t } }
        val r = run(FusionSettings(), 300.0, turning) { it.seedBias(deskBias) }
        val off = (r.fusion.gyroBias.y - deskBias.y).toDouble()
        println("steady 0.4 deg/s turn, seeded: bias y off %.5f rad/s (yaw anchor 0.0008), %d windows used, yaw drift %.4f deg/s".format(java.util.Locale.ROOT, off, r.fusion.wornYawUpdates, r.yawDriftDegPerS))
        assertTrue("off $off", abs(off) <= 0.00085)
        assertTrue("drift ${r.yawDriftDegPerS}", abs(r.yawDriftDegPerS) < 0.05)
    }

    /** WornDriftTest's head: a 1 Hz sway of about a degree on every axis, never still. */
    private fun swayingHead(): (Double) -> DoubleArray = { t ->
        doubleArrayOf(
            1.0 * sin(2 * PI * 1.0 * t) + 3.0 * sin(2 * PI * 0.2 * t + 1.0) + 4.0 * sin(2 * PI * 0.037 * t + 2.0),
            0.8 * sin(2 * PI * 1.0 * t + 1.3) + 2.0 * sin(2 * PI * 0.13 * t + 1.5),
            0.6 * sin(2 * PI * 1.0 * t + 2.1),
        )
    }

    @Test
    fun theOldSavedBiasIsTakenOnceThisSessionsXAndZConfirmIt() {
        val now = run(FusionSettings(), 300.0, swayingHead()) { it.offerOldBias(oldKey) }
        val unseeded = run(FusionSettings(), 300.0, swayingHead())
        println("never-still head, 5 min, the old key offered: %s".format(now.fusion.oldBiasConfirmedLine))
        println("  yaw drift %.4f deg/s (not offered: %.4f); %s".format(java.util.Locale.ROOT, now.yawDriftDegPerS, unseeded.yawDriftDegPerS, now.lines.last()))
        assertNotNull(now.fusion.oldBiasConfirmedLine)
        assertEquals(oldKey.y, now.fusion.gyroBias.y, 0.0003f)
        assertTrue("drift ${now.yawDriftDegPerS}", abs(now.yawDriftDegPerS) < 0.05)
        assertTrue("unseeded drift ${unseeded.yawDriftDegPerS}", abs(unseeded.yawDriftDegPerS) > 0.15)
        assertTrue(now.lines.last(), now.lines.last().contains("the old saved bias, confirmed"))
    }

    @Test
    fun aWalkedOldBiasIsNeverTaken() {
        // Mike's worn bias, walked: y -0.0134, x and z 0.001 and 0.003 off the truth.
        val walked = Vec3(0.0007f, -0.0134f, -0.007f)
        val r = run(FusionSettings(), 240.0, swayingHead()) { it.offerOldBias(walked) }
        assertNull(r.fusion.oldBiasConfirmedLine)
        assertEquals(0f, r.fusion.gyroBias.y, 0.0005f)
        assertTrue(r.lines.last(), r.lines.last().contains("not yet confirmed"))
    }

    @Test
    fun theYawLineMeasuresTheDriftLeft() {
        // Unseeded, a head swaying 0.5 degrees at 1 Hz (whole cycles in each 10 s line, never still for
        // the static check): the yaw bias is not known and the net yaw rate is its drift.
        val r = run(FusionSettings(), 60.0, { t -> doubleArrayOf(0.5 * sin(2 * PI * t), 0.3 * sin(2 * PI * t + 1.0), 0.0) })
        val line = r.lines.last()
        println(line)
        assertTrue(line, line.startsWith("head yaw: bias along up"))
        // The desk bias along up is about -0.0038 rad/s: -0.22 deg/s of drift left (the line gives
        // the rate about world up, so a drift to the right is negative).
        val m = Regex("""yaw drift ([-+0-9.]+) deg/s net""").find(line)
        assertNotNull(line, m)
        assertEquals(-0.215, m!!.groupValues[1].toDouble(), 0.02)
        // Seeded right, the same head reads no drift.
        val seeded = run(FusionSettings(), 60.0, { t -> doubleArrayOf(0.5 * sin(2 * PI * t), 0.3 * sin(2 * PI * t + 1.0), 0.0) }) { it.seedBias(deskBias) }
        val s = Regex("""yaw drift ([-+0-9.]+) deg/s net""").find(seeded.lines.last())!!.groupValues[1].toDouble()
        assertEquals(0.0, s, 0.02)
    }

    @Test
    fun twoRecentresInARowLeaveTheAttitudeWhereTheFirstPutIt() {
        // Mike's press recentred twice, 12 ms apart: 0.5.15's second relevelled by the same delta
        // again (roll 1.10 to 0.30, then 0.31 to -0.45). Now the second changes nothing.
        fun session(s: FusionSettings): Triple<Float, Float, Float> {
            val f = HeadFusion(s.copy(kp = 0f, kpMoving = 0f, kpStatic = 0f, settleKp = 0f, kpWornFast = 0f, startGate = false))
            val tilt = (Quat.axisAngle(Vec3(0f, 0f, -1f), 0.8f * DEG) * Quat.axisAngle(Vec3.RIGHT, 0.5f * DEG)).normalized()
            val g = tilt.conjugate().rotate(Vec3(0f, 9.81f, 0f))
            f.update(Vec3.ZERO, Vec3(0f, 9.81f, 0f), 0L)
            for (i in 1..2000) f.update(Vec3.ZERO, if (i < 30) Vec3(0f, 9.81f, 0f) else g, i * 1_000_000L)
            val before = f.pose().orientation.yawPitchRoll().third
            println("  " + f.recentre())
            val first = f.pose().orientation.yawPitchRoll()
            // 12 ms later, before the next gravity mean has caught up.
            for (i in 2001..2012) f.update(Vec3.ZERO, g, i * 1_000_000L)
            println("  " + f.recentre())
            val second = f.pose().orientation.yawPitchRoll()
            return Triple(first.third - before, second.third - first.third, second.second - first.second)
        }
        val (firstRoll, dRoll, dPitch) = session(FusionSettings())
        val (_, oldRoll, oldPitch) = session(FusionSettings.v0515())
        println("two recentres: the first moved roll %.3f deg, the second roll %.3f, pitch %.3f deg (0.5.15: %.3f, %.3f)".format(java.util.Locale.ROOT, firstRoll, dRoll, dPitch, oldRoll, oldPitch))
        // The first levels the 0.8 degree roll error.
        assertEquals(0.8f, abs(firstRoll), 0.05f)
        assertEquals(0f, dRoll, 0.01f)
        assertEquals(0f, dPitch, 0.01f)
        assertTrue("0.5.15 roll $oldRoll", abs(oldRoll) > 0.5f)
    }

    @Test
    fun recentreRequestsFromOnePressAreOne() {
        // The phone's button sends the command to a running Space, which recentres; only with no
        // Space does it call the tracker itself. 0.5.15 did both.
        assertEquals(listOf(RecentreRoute.Path.SPACE_COMMAND), RecentreRoute.paths(spaceRunning = true))
        assertEquals(listOf(RecentreRoute.Path.TRACKER), RecentreRoute.paths(spaceRunning = false))
        val gate = RecentreRoute.Debounce(windowMs = 300)
        assertTrue(gate.accept(1000))
        assertTrue(!gate.accept(1012))
        assertTrue(gate.accept(1400))
    }

    @Test
    fun theAurasFourteenDegreeMountIsSavedAndApplied() {
        // Mike's three tries: roll -0.2 / 0.4 / 0.5, pitch 14.5 / 14.5 / 14.3 degrees.
        assertTrue(LevelCalibration.plausible(-0.2f, 14.5f))
        assertTrue(LevelCalibration.plausible(0.5f, 14.3f))
        assertTrue(LevelCalibration.plausible(0f, -24f))
        assertTrue(!LevelCalibration.plausible(12f, 0f))
        // 1.2.13 (Discussion #81): the One Pro+'s 40-odd degrees fit; past 50 is still a head bent, not the glasses.
        assertTrue(LevelCalibration.plausible(0f, 46f))
        assertTrue(!LevelCalibration.plausible(0f, 51f))
        assertEquals("pitch 51.0 degrees is more than 50", LevelCalibration.refusal(0f, 51f))
        // 1.1.7 (issue 30): a One Pro's steeper frame, either way up.
        assertEquals(null, LevelCalibration.refusal(0f, -30f))
        assertEquals(null, LevelCalibration.refusal(0f, 30f))
        assertEquals("roll 12.0 degrees is more than 10", LevelCalibration.refusal(12f, 3f))
        // The IMU pitched 14.5 degrees in the frame, the display held level.
        val imuUp = LevelCalibration.mount(0.2f, 14.5f).rotate(Vec3.UP)
        val f = HeadFusion()
        repeat(3000) { f.update(Vec3.ZERO, imuUp * 9.81f, it * 1_000_000L) }
        val (roll, pitch) = f.levelReading()!!
        assertEquals(14.5f, pitch, 0.05f)
        val unmounted = f.pose().orientation.yawPitchRoll().second
        f.settings = f.settings.copy(mountRollDeg = roll, mountPitchDeg = pitch)
        val (_, p, r) = f.pose().orientation.yawPitchRoll()
        println("14.5 degree mount: display pitch %.2f deg without it, %.3f with it (roll %.3f)".format(java.util.Locale.ROOT, unmounted, p, r))
        assertTrue("unmounted $unmounted", abs(abs(unmounted) - 14.5f) < 0.2f)
        assertEquals(0f, p, 0.05f)
        assertEquals(0f, r, 0.05f)
        // Looking 15 degrees down with the display: reads 15 down, not 29.5 or 0.5.
        val display = Quat.axisAngle(Vec3.RIGHT, -15f * DEG)
        val imu = (display * LevelCalibration.mount(0.2f, 14.5f).conjugate()).normalized()
        val g = imu.conjugate().rotate(Vec3(0f, 9.81f, 0f))
        val f2 = HeadFusion(FusionSettings(mountRollDeg = roll, mountPitchDeg = pitch))
        repeat(3000) { f2.update(Vec3.ZERO, g, it * 1_000_000L) }
        assertEquals(-15f, f2.pose().orientation.yawPitchRoll().second, 0.2f)
    }

    @Test
    fun theStartGateSaysOnceWhichWayItPassed() {
        val still = HeadFusion()
        var event: String? = null
        repeat(1000) { still.update(Vec3.ZERO, Vec3(0f, 9.81f, 0f), it * 1_000_000L); still.takeStartGateEvent()?.let { e -> event = e } }
        println(event)
        assertTrue(event!!, event!!.contains("on a still window") && event!!.contains("passed at sample 320"))
        assertEquals(false, still.startGateByFallback)
        assertNull(still.takeStartGateEvent())
        // A head that never holds 0.3 s still (Mike's 0.079 rad/s window): the fallback, said so.
        val worn = HeadFusion()
        var e2: String? = null
        repeat(3500) { i ->
            val t = i / 1000.0
            worn.update(Vec3((0.03 * sin(2 * PI * 2 * t)).toFloat(), 0f, 0f), Vec3(0f, 9.81f, 0f), i * 1_000_000L)
            worn.takeStartGateEvent()?.let { e -> e2 = e }
        }
        println(e2)
        assertTrue(e2!!, e2!!.contains("by the fallback"))
        assertEquals(true, worn.startGateByFallback)
    }

    @Test
    fun theCapLiftSaysWhy() {
        val b = DrawBudget()
        val period = 16_666_667L
        repeat(240) { b.add(14_000_000L); b.budgetNs(period); b.outcome(it % 5 == 0) }
        val e = b.takeCapEvent()
        println(e)
        assertTrue(e!!, e!!.startsWith("late draw: half frame cap lifted (1 this mode)") && "draw p50 14.0" in e!!)
        assertNull(b.takeCapEvent())
    }

    @Test
    fun theGpuPassLineSeparatesOwnWorkFromWaiting() {
        val g = GpuPassTimes(listOf("prepare", "left eye", "right eye"))
        repeat(100) { g.addFrame(longArrayOf(400_000L, 1_200_000L, 1_100_000L)) }
        g.addDisjoint()
        val line = g.line(9.2, 21.3)
        println(line)
        assertTrue(line, line.contains("left eye p50 1.20") && line.contains("all p50 2.70") && line.contains("about 6.5 ms p50") && line.contains("1 disjoint"))
        g.unavailable = "no GL_EXT_disjoint_timer_query"
        assertTrue(g.line(null, null).contains("no timer figures"))
    }

    @Test
    fun theCompositionTableIsReadAndTheCursorWindowHides() {
        val dump = """
Display 4630947043778501762 (active) HWC layers:
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#1234(BLAST Consumer)1234
  rel      0 |            0 |     CLIENT |          0 |    0    0 3840 1080 |    0.0    0.0 3840.0 1080.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 DexPrivacyDotOverlay#88
  rel      0 |         2024 |     CLIENT |          0 |    0    0 3840   56 |    0.0    0.0 3840.0   56.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Flip Spaces cursor#90
  rel      0 |         2032 |     DEVICE |          0 |    0    0 3840 1080 |    0.0    0.0 3840.0 1080.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------

Display 4619827259835644672 (active) HWC layers:
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Wallpaper#1
  rel      0 |         2013 |     DEVICE |          0 |    0    0 1080 2640 |    0.0    0.0 1080.0 2640.0 |                                              [ ]
""".trimIndent()
        val displays = HwcComposition.parse(dump)
        assertEquals(2, displays.size)
        assertEquals(3, displays[0].layers.size)
        val line = HwcComposition.line(displays)
        println(line)
        assertTrue(line, line.contains("the Space 3840x1080 is composed on the GPU (CLIENT)") && line.contains("DexPrivacyDotOverlay#88 CLIENT 3840x56") && line.contains("Flip Spaces cursor#90 DEVICE 3840x1080"))
        assertTrue(HwcComposition.line(HwcComposition.parse(dump.replace("CLIENT |          0 |    0    0 3840 1080", "DEVICE |          0 |    0    0 3840 1080"))).contains("on a hardware overlay"))
        // The cursor window is gone while the Space is up, unless it has a banner, the dock or a preview.
        assertTrue(!CursorWindow.shown(spaceUp = true, banner = false, dock = false, preview = false))
        assertTrue(CursorWindow.shown(spaceUp = true, banner = true, dock = false, preview = false))
        assertTrue(CursorWindow.shown(spaceUp = false, banner = false, dock = false, preview = false))
    }
}
