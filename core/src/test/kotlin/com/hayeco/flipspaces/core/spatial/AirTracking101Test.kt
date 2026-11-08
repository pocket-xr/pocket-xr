package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * 1.1.12 (an XREAL Air 2 Pro user: "3dof, head tracking is not working even though it shows 3dof. Body anchor on"): the
 * Air family's HID protocol ([AirImuProtocol]), its reports through the fusion with Body Anchor on, and the
 * PocketXR-Tracking line ([TrackingBeat]) that says what went wrong when there is no pose.
 */
class AirTracking101Test {
    private val rate = (Math.PI / 2).toFloat() // 90 degrees a second

    /** Head axes (x right, y up, z back) back to the Air sensor's (x left, y back, z up): the inverse of [AirImuProtocol.toHead]. */
    private fun toSensor(h: Vec3) = Vec3(-h.x, h.z, h.y)

    /**
     * [seconds] of Air 2 Pro reports at 1 kHz of a head turning at [omega] (head axes, rad/s), through the framer and the
     * fusion as the head thread does. Returns the true head orientation and the next timestamp.
     */
    private fun feed(
        framer: AirImuFramer, fusion: HeadFusion, seconds: Float, omega: Vec3, start: Quat, startT: Long, step: Long,
    ): Pair<Quat, Long> {
        var q = start
        var t = startT
        val out = ArrayList<ImuReport>()
        repeat((seconds * 1000).toInt()) {
            q = (q * Quat.fromGyro(omega, 0.001f)).normalized()
            val upG = q.conjugate().rotate(Vec3.UP)
            val gyroDegS = toSensor(omega) * RAD
            val packet = AirImuEncoder.report(t, gyroDegS, toSensor(upG))
            out.clear()
            assertTrue(framer.append(packet, packet.size, out))
            out.forEach { fusion.update(it) }
            t += step
        }
        return q to t
    }

    @Test
    fun theStartCommandIsFramedAsTheAirExpects() {
        val p = AirImuProtocol.streamCommand()
        assertEquals(64, p.size)
        assertEquals(0xAA, p[0].toInt() and 0xFF)
        assertEquals(4, (p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8)) // one byte of payload plus 3
        assertEquals(0x19, p[7].toInt())
        assertEquals(1, p[8].toInt())
        val crc = CRC32().apply { update(p, 5, 4) }.value
        val got = (0..3).fold(0L) { acc, i -> acc or ((p[1 + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(crc, got)
        assertEquals(0, AirImuProtocol.streamCommand(on = false)[8].toInt())
    }

    @Test
    fun theAir2ProIsAnAirOnHidInterface3AndTheOneFamilyIsNot() {
        assertTrue(AirImuProtocol.isAir(0x3318, 0x0432))
        assertEquals("XREAL Air 2 Pro", AirImuProtocol.modelName(0x0432))
        assertEquals(3, AirImuProtocol.imuInterface(0x0432))
        assertEquals(3, AirImuProtocol.imuInterface(0x0424))
        assertEquals(2, AirImuProtocol.imuInterface(0x0426))
        // The Aura and the One keep their network stream.
        assertFalse(AirImuProtocol.isAir(0x3318, GlassesModel.USB_AURA))
        assertFalse(AirImuProtocol.isAir(0x3318, GlassesModel.USB_ONE))
        assertFalse(AirImuProtocol.isAir(0x1234, 0x0432))
    }

    @Test
    fun aReportIsReadInHeadAxesRadiansAndMetresPerSecondSquared() {
        val p = AirImuEncoder.report(123_456_789L, gyroDegS = Vec3(10f, -20f, 30f), accelG = Vec3(0.1f, -0.2f, 0.97f))
        val r = AirImuProtocol.parse(p)!!
        assertEquals(123_456_789L, r.timestampNs)
        assertEquals(ImuReportType.IMU, r.type)
        // Head x = -sensor x, y = sensor z, z = sensor y.
        assertEquals(-10f * DEG, r.gyro.x, 1e-4f)
        assertEquals(30f * DEG, r.gyro.y, 1e-4f)
        assertEquals(-20f * DEG, r.gyro.z, 1e-4f)
        // The report is in g; the fusion works in m/s2 (issue 49).
        assertEquals(-0.1f * 9.81f, r.accel.x, 1e-2f)
        assertEquals(0.97f * 9.81f, r.accel.y, 1e-2f)
        assertEquals(-0.2f * 9.81f, r.accel.z, 1e-2f)
    }

    /**
     * Issue 49 (an Air 2 user: "the horizon was somewhere in the floor", and Recentre "didn't level it"): the accelerometer
     * reached the fusion in g, so its start gate (9.81 within 2 m/s2) never passed on an Air and gravity was never used.
     * At rest an Air's reports must pass the gate, after which gravity levels the view, and Save level gets a reading.
     */
    @Test
    fun airReportsAtRestPassTheStartGateAndGravityLevelsTheView() {
        val framer = AirImuFramer()
        val model = GlassesModel.detect(GlassesId("XREAL Air 2", ""), AirImuProtocol.PID_AIR_2)
        val fusion = HeadFusion(FusionSettings(axes = model.defaultAxes))
        var t = feed(framer, fusion, 2f, Vec3.ZERO, Quat.IDENTITY, 0L, 1_000_000L).second
        assertTrue("the start gate passes on Air reports at rest", fusion.startAligned)
        assertEquals(9.81f, fusion.lastRawAccel.length, 0.05f)
        assertTrue("Save level gets a reading", fusion.levelReading() != null)
        assertEquals(0f, fusion.pose().orientation.angleTo(Quat.IDENTITY), 1f)
        // The glasses now sit pitched down 10 degrees with no turn on the gyro, as after a gyro error built up: gravity
        // alone must bring the view toward the true tilt. Before the fix it stayed level forever.
        val tilted = Quat.fromGyro(Vec3(-1f, 0f, 0f), (10f * DEG))
        val out = ArrayList<ImuReport>()
        repeat(30_000) {
            val packet = AirImuEncoder.report(t, Vec3.ZERO, toSensor(tilted.conjugate().rotate(Vec3.UP)))
            out.clear()
            framer.append(packet, packet.size, out)
            out.forEach { fusion.update(it) }
            t += 1_000_000L
        }
        assertTrue("gravity pulled the view toward the true tilt", fusion.pose().orientation.angleTo(tilted) < 5f)
    }

    @Test
    fun aCommandsAnswerIsNotAReportAndIsCounted() {
        val framer = AirImuFramer()
        val out = ArrayList<ImuReport>()
        assertFalse(framer.append(AirImuProtocol.streamCommand(), 64, out))
        assertTrue(out.isEmpty())
        assertNull(AirImuProtocol.parse(ByteArray(64)))
        // A divisor of 0 is not a report either (no division by zero into the fusion).
        val zero = AirImuEncoder.report(1L, Vec3.ZERO, Vec3.ZERO, gyroDiv = 0)
        assertNull(AirImuProtocol.parse(zero))
        assertEquals(1L, framer.counts.unknownType)
        assertEquals(64L, framer.counts.bytes)
    }

    @Test
    fun microsecondTimestampsAreFoundSoTheTurnIsNotAThousandTimesTooSmall() {
        val ts = AirTimestamp()
        assertEquals(5_000L, ts.toNs(5_000L))
        assertEquals(6_000_000L, ts.toNs(6_000L))
        assertTrue(ts.decided)
        assertEquals(1000L, ts.scale)
        val ns = AirTimestamp()
        ns.toNs(1_000_000L)
        assertEquals(2_000_000L, ns.toNs(2_000_000L))
        assertEquals(1L, ns.scale)
    }

    /** The reported case: Air 2 Pro reports, Body Anchor on: the drawn view turns with the head, so windows stay put. */
    @Test
    fun air2ProWithBodyAnchorFollowsAHeadTurn() {
        for (step in listOf(1_000_000L, 1_000L)) { // ns, and µs should a firmware send that
            val framer = AirImuFramer()
            // An Air shows as "XREAL glasses" by its display name: its default axes, as the app would pick.
            val model = GlassesModel.detect(GlassesId("XREAL Air 2 Pro", ""), 0x0432)
            val fusion = HeadFusion(FusionSettings(axes = model.defaultAxes))
            val view = FollowView().apply { mode = FollowMode.BODY_ANCHOR }
            var (q, t) = feed(framer, fusion, 0.3f, Vec3.ZERO, Quat.IDENTITY, 0L, step)
            view.update(fusion.pose().orientation, 0L)
            val before = view.relative(fusion.pose().orientation)!!
            assertEquals(0f, before.yawPitchRoll().first, 1f)
            // Turn right 90 degrees in one second (negative about up), as in HeadFusionTest.
            feed(framer, fusion, 1f, Vec3(0f, -rate, 0f), q, t, step).also { q = it.first; t = it.second }
            val tracked = fusion.pose().orientation
            view.update(tracked, 1300L)
            val drawn = view.relative(tracked)!!
            val (yaw, pitch, roll) = drawn.yawPitchRoll()
            assertEquals("step $step", 90f, yaw, 2f)
            assertEquals(0f, pitch, 2f)
            assertEquals(0f, roll, 2f)
            // Body Anchor draws the head pose as it is: no re-centring, no cancelling.
            assertEquals(0f, drawn.angleTo(tracked), 0.01f)
            assertTrue(drawn.angleTo(Quat.IDENTITY) > 80f)
            assertEquals(1300L, framer.counts.imu)
        }
    }

    @Test
    fun air2ProLooksUpAndDownTheRightWay() {
        val framer = AirImuFramer()
        val fusion = HeadFusion()
        var (q, t) = feed(framer, fusion, 0.3f, Vec3.ZERO, Quat.IDENTITY, 0L, 1_000_000L)
        // Look up 30 degrees (positive about right).
        feed(framer, fusion, 1f / 3f, Vec3(rate, 0f, 0f), q, t, 1_000_000L).also { q = it.first; t = it.second }
        val (_, pitch, _) = fusion.pose().orientation.yawPitchRoll()
        assertEquals(30f, pitch, 2f)
    }

    @Test
    fun bodyAnchorWithoutAPoseSaysWhyInTheLog() {
        val status = HeadTrackingStatus.UsbPermission("XREAL Air 2 Pro (USB 3318:0432)")
        val v = TrackingVerdict.of(FollowMode.BODY_ANCHOR, status, 0.0, null)
        assertTrue(v, v.startsWith("NO HEAD POSE: Body Anchor (3DoF)"))
        assertTrue(v, v.contains("USB access"))
        // What 1.0.0 said on an Air: still looking for a network the Air does not have.
        assertTrue(TrackingVerdict.of(FollowMode.BODY_ANCHOR, HeadTrackingStatus.Searching, 0.0, null).contains("Looking for the glasses' USB network"))
        // Streaming, but the samples stopped.
        assertTrue(TrackingVerdict.of(FollowMode.BODY_ANCHOR, HeadTrackingStatus.Streaming("usb", 1000.0), 0.0, Quat.IDENTITY).startsWith("NO IMU SAMPLES"))
        assertTrue(TrackingVerdict.of(FollowMode.BODY_ANCHOR, HeadTrackingStatus.Streaming("usb", 1000.0), 998.0, Quat.IDENTITY).startsWith("ok: Body Anchor"))
        assertTrue(TrackingVerdict.of(FollowMode.SMOOTH_FOLLOW, HeadTrackingStatus.Streaming("usb", 1000.0), 998.0, Quat.IDENTITY).contains("by design"))
    }

    @Test
    fun theTrackingLineCarriesTheGlassesModeRateAndPose() {
        val beat = TrackingBeat()
        val status = HeadTrackingStatus.Streaming("USB HID interface 3", 1000.0)
        fun facts(samples: Long, pose: Quat?) = TrackingFacts(
            glasses = "display says XREAL glasses; XREAL Air 2 Pro USB 3318:0432, USB access yes", source = "USB HID interface 3",
            follow = FollowMode.BODY_ANCHOR, status = status, samples = samples, pose = pose, accel = Vec3(0f, 1f, 0f),
        )
        beat.line(facts(0, null), 0L)
        val line = beat.line(facts(1000, Quat.fromYawPitch(45f, 10f)), 1_000_000_000L)
        assertEquals(1000.0, beat.rateHz, 0.5)
        assertTrue(line, line.contains("XREAL Air 2 Pro USB 3318:0432"))
        assertTrue(line, line.contains("source=USB HID interface 3"))
        assertTrue(line, line.contains("mode=Body Anchor (3DoF)"))
        assertTrue(line, line.contains("imu=1000 Hz, 1000 samples"))
        assertTrue(line, line.contains("yaw 45.0 pitch 10.0"))
        assertTrue(line, line.contains("ok: Body Anchor"))
        assertFalse(line, line.contains("gravity says"))
        // Gravity on the wrong axis for the chosen mapping: the line names the ones that fit.
        val tilted = beat.line(facts(2000, Quat.IDENTITY).copy(accel = Vec3(0f, 0f, 1f)), 2_000_000_000L)
        assertTrue(tilted, tilted.contains("gravity says axes"))
    }

    @Test
    fun anAirThatNeverReportsSaysSoNotTheOneFamilyHint() {
        val s = HeadSilence.status("USB HID interface 3", everStreamed = false, silentS = 5, sinceStartS = 5, bytes = 64, counts = FramerCounts(), hint = HeadTrackingStatus.AIR_FAMILY_HINT)
        assertTrue(s is HeadTrackingStatus.NoReports)
        assertTrue(s.summary, s.summary.contains("start command over USB HID"))
        assertFalse(s.summary.contains("One family"))
        assertTrue(HeadSilence.status("eth0", false, 5, 5, 0, FramerCounts()).summary.contains("One family"))
    }
}
