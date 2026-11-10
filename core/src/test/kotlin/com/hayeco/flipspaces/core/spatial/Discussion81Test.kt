package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 1.2.13 (Discussion #81, an XREAL One Pro+ on a Galaxy Z Fold 6: "When looking straight ahead it's as if I'm looking
 * straight up. When I recenter, all of the windows appear straight down. Home always shows properly though."): the One
 * Pro+ is told from its display's name, its EDID product and its USB product; the tester's Send logs (1.2.10) showed the
 * One's axes are its axes and the pitch was 30 to 45 degrees off, a display mount, not a quarter turn; the gravity check
 * mends a layout that puts gravity a quarter turn off, and leaves the One Pro+'s alone.
 */
class Discussion81Test {
    private val g = 9.81f

    /** The tester's accelerometer (sensor axes, m/s2) at the report, a head about level: gravity on the sensor's -y. */
    private val logLevelish = Vec3(0.51f, -9.77f, 1.45f)

    /** The tester's accelerometer while watching a video, a head looking ahead: the app read it as about 39 degrees up. */
    private val logWatching = Vec3(-0.11f, -7.65f, 6.24f)

    /** A sensor a quarter turn from the One's about the side axis, gravity's reaction along +z: what the branch guessed. */
    private val quarterTurn = Vec3(0f, 0f, g)

    private fun pitchDeg(q: Quat): Float {
        val f = q.forward
        return atan2(f.y, hypot(f.x, f.z)) * RAD
    }

    /**
     * [seconds] of a head held still at 1 kHz with [raw] on the accelerometer (sensor axes), and a small 8 Hz tremor on
     * the gyro unless [onTable], so the start gate passes as on a worn head.
     */
    private fun feed(fusion: HeadFusion, raw: Vec3, seconds: Float = 1.5f, onTable: Boolean = false, fromNs: Long = 0L): Long {
        val n = (seconds * 1000).toInt()
        var t = fromNs
        for (i in 0 until n) {
            t += 1_000_000L
            val s = i / 1000f
            val tremor = if (onTable) 0f else 0.012f * sin(2f * Math.PI.toFloat() * 8f * s)
            fusion.update(Vec3(tremor, 0f, 0.5f * tremor), raw, t)
        }
        return t
    }

    @Test
    fun theOneProPlusIsToldFromItsNameItsEdidAndItsUsbProduct() {
        for (name in listOf("XREAL One Pro+", "XREAL 1 Pro+", "XREAL One Pro Plus", "xreal 1pro+")) {
            assertEquals(name, GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(GlassesId(name, "")))
        }
        // What its Glasses report showed: display "XREAL 1 Pro+", EDID product 16657, USB 3318:0444.
        assertEquals(GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(GlassesId("XREAL 1 Pro+", "16657"), 0x0444))
        assertEquals(GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(GlassesId("", "16657")))
        assertEquals(GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(null, GlassesModel.USB_ONE_PRO_PLUS))
        assertEquals(GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(GlassesId("Display", ""), 0x0444))
        // The One Pro, and the 1 Pro by XREAL's newer naming, are still the One Pro.
        assertEquals(GlassesModel.ONE_PRO, GlassesModel.detect(GlassesId("XREAL One Pro", "")))
        assertEquals(GlassesModel.ONE_PRO, GlassesModel.detect(GlassesId("XREAL 1 Pro", "")))
        val m = GlassesModel.ONE_PRO_PLUS
        assertEquals(57f, m.diagonalFovDeg!!, 0f)
        assertFalse(m.tested)
        assertFalse(m.camera)
        assertTrue(m.isXreal)
        assertEquals(ONE_STABILIZER, m.setupHint)
        assertEquals(SetupFamily.ONE_SERIES, GlassesSetup.family(m))
        assertEquals(SetupFamily.ONE_SERIES, GlassesSetup.family(null, 0x0444))
        assertTrue(SteadyMode.offeredOn(m))
        assertEquals(ImuTransport.USB_NETWORK, HeadImuTransport.choose(GlassesReport.XREAL_VENDOR, 0x0444, emptyList()))
    }

    @Test
    fun theLogPutsGravityOnTheOnesAxesNotAQuarterTurn() {
        val axes = GlassesModel.ONE_PRO_PLUS.defaultAxes
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, axes)
        // Its level-ish rest is within 10 degrees of the One's up; the branch's guess put it 81 off, near looking down.
        assertEquals(9.0f, GravityAxes.angleFromUp(logLevelish, axes), 0.5f)
        assertEquals(81.6f, GravityAxes.angleFromUp(logLevelish, AxisMap.XRIGHT_ZUP_YFRONT), 0.5f)
        // Of every layout, the One's is the nearest to gravity in both of the log's readings.
        for (raw in listOf(logLevelish, logWatching)) {
            assertEquals("$raw", axes, AxisMap.entries.minBy { GravityAxes.angleFromUp(raw, it) })
        }
        // While watching, the One's layout reads 39 degrees up, as the log's head attitude said: a tilt, not the axes.
        val fusion = HeadFusion(FusionSettings(axes = axes))
        feed(fusion, logWatching)
        assertEquals(39.2f, pitchDeg(fusion.pose().orientation), 1.5f)
    }

    @Test
    fun theGravityCheckLeavesTheOneProPlusLayoutAlone() {
        val axes = GlassesModel.ONE_PRO_PLUS.defaultAxes
        for (raw in listOf(logLevelish, logWatching)) {
            val v = GravityAxes.check(GravityAxes.Rest(raw, "recentre"), axes, axes)
            assertNull(v.line, v.axes)
            assertTrue(v.line, v.line.contains(": kept"))
        }
    }

    @Test
    fun onTheBranchsGuessTheCheckCatchesOnlyALevelRest() {
        // Had the One Pro+ started on "x right, y front, z up": a level-ish recentre puts the One's back...
        val guess = AxisMap.XRIGHT_ZUP_YFRONT
        val v = GravityAxes.check(GravityAxes.Rest(logLevelish, "recentre"), guess, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, v.axes)
        // ...but the 39-degree rests of the session are 51 degrees from that layout's up, inside the 60 kept,
        assertNull(GravityAxes.check(GravityAxes.Rest(logWatching, "recentre"), guess, AxisMap.XRIGHT_YDOWN_ZFRONT).axes)
        // and the start's rest was a still one (gyro peak to peak 0.004 rad/s, under the static 0.02), left alone.
        assertNull(GravityAxes.check(GravityAxes.Rest(logLevelish, "start", tableStill = true), guess, AxisMap.XRIGHT_YDOWN_ZFRONT).axes)
    }

    @Test
    fun aLevelCalibrationOfTheTiltPutsTheWatchingHeadLevel() {
        val axes = GlassesModel.ONE_PRO_PLUS.defaultAxes
        val (roll, pitch) = LevelCalibration.fromGravity(axes.apply(logWatching))
        assertEquals(39.2f, kotlin.math.abs(pitch), 1.5f)
        assertNull("40-odd degrees is taken now", LevelCalibration.refusal(roll, pitch))
        val fusion = HeadFusion(FusionSettings(axes = axes, mountRollDeg = roll, mountPitchDeg = pitch))
        feed(fusion, logWatching)
        assertEquals(0f, pitchDeg(fusion.pose().orientation), 1f)
    }

    @Test
    fun aFactoryMountTooSteepToTakeSaysWhy() {
        // The tester's calibration (version 8) gave a gyro matrix and "no display mount": the line now says which way.
        val gyro = "\"IMU\":{\"device_1\":{\"imu_intrinsics\":{\"gyro_calib_mat\":[1,0,0,0,1,0,0,0,1]}}}"
        val none = FactoryCalibration.parse("{$gyro}")!!
        assertNull(none.displayMount)
        assertTrue(none.line(), none.line().contains("no display mount (the calibration has no display.target_q_left_display)"))
        // A display 40 degrees about the side axis from the IMU, in the JSON's camera axes (x, y, z, w).
        val q = Quat.axisAngle(Vec3.RIGHT, 40f * DEG) * Quat.axisAngle(Vec3.RIGHT, Math.PI.toFloat())
        val steep = FactoryCalibration.parse("{$gyro,\"display\":{\"target_q_left_display\":[${q.x},${q.y},${q.z},${q.w}]}}")!!
        assertNull(steep.displayMount)
        assertTrue(steep.line(), steep.line().contains("display.target_q_left_display refused: 40.0 deg from level, more than 30"))
        // One within the limit is used and needs no note.
        val q10 = Quat.axisAngle(Vec3.RIGHT, 10f * DEG) * Quat.axisAngle(Vec3.RIGHT, Math.PI.toFloat())
        val fine = FactoryCalibration.parse("{$gyro,\"display\":{\"target_q_left_display\":[${q10.x},${q10.y},${q10.z},${q10.w}]}}")!!
        assertNotNull(fine.displayMount)
        assertNull(fine.mountNote)
    }

    @Test
    fun theGravityCheckMendsAQuarterTurnKeepingRightAsRight() {
        // Up along +z: "x right, y front, z up"; along -z: the other quarter turn. Right stays right in both.
        val v = GravityAxes.check(GravityAxes.Rest(quarterTurn, "start"), AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, v.axes)
        assertTrue(v.line, v.line.contains("axes now x right, y front, z up"))
        assertTrue(v.line, v.line.contains("0.00, 0.00, 9.81"))
        val down = GravityAxes.check(GravityAxes.Rest(Vec3(0f, 0f, -g), "recentre"), AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertEquals(AxisMap.XRIGHT_ZDOWN_YBACK, down.axes)
        for (a in listOf(v.axes!!, down.axes!!)) {
            val right = a.apply(Vec3(1f, 0f, 0f))
            assertEquals(a.label, 1f, right.x, 0f)
            assertEquals(a.label, 0f, kotlin.math.abs(right.y) + kotlin.math.abs(right.z), 0f)
        }
    }

    @Test
    fun onTheWrongLayoutAQuarterTurnReadsAsLookingStraightUp() {
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        feed(fusion, quarterTurn)
        assertEquals(90f, pitchDeg(fusion.pose().orientation), 2f)
        assertEquals(90f, GravityAxes.angleFromUp(quarterTurn, AxisMap.XRIGHT_YDOWN_ZFRONT), 0.01f)
    }

    @Test
    fun theGravityCheckLeavesARightLayoutAndAHeadThatOnlyLooksDown() {
        val one = AxisMap.XRIGHT_YDOWN_ZFRONT
        assertNull(GravityAxes.check(GravityAxes.Rest(Vec3(0f, -g, 0f), "start"), one, one).axes)
        // Looking 40 degrees down on the right layout: kept.
        val a = 40f * DEG
        val down40 = one.unapply(Vec3(0f, g * cos(a), -g * sin(a)))
        val kept = GravityAxes.check(GravityAxes.Rest(down40, "recentre"), one, one)
        assertNull(kept.axes)
        assertTrue(kept.line, kept.line.contains(": kept"))
        // Not 1 g (the head moving): not judged.
        assertNull(GravityAxes.check(GravityAxes.Rest(Vec3(0f, 0f, -2f * g), "recentre"), one, one).axes)
        // Gravity between axes (lying at 45 degrees): on no layout's axis, kept.
        assertNull(GravityAxes.check(GravityAxes.Rest(Vec3(0f, -g * 0.7071f, -g * 0.7071f), "recentre"), one, one).axes)
    }

    @Test
    fun glassesLyingStillAtTheStartAreLeftAlone() {
        val v = GravityAxes.check(GravityAxes.Rest(quarterTurn, "start", tableStill = true), AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertNull(v.axes)
        assertTrue(v.line, v.line.contains("lie still"))
    }

    @Test
    fun theModelsOwnLayoutWinsWhenItFits() {
        // A layout picked by hand a quarter turn off: gravity on the One's own axis puts the One's layout back.
        val v = GravityAxes.check(GravityAxes.Rest(Vec3(0f, -g, 0f), "recentre"), AxisMap.XRIGHT_ZUP_YFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, v.axes)
    }

    @Test
    fun everyWayGravityCanReadHasALayoutAndEachUndoesItself() {
        for (d in listOf(Vec3(g, 0f, 0f), Vec3(-g, 0f, 0f), Vec3(0f, g, 0f), Vec3(0f, -g, 0f), Vec3(0f, 0f, g), Vec3(0f, 0f, -g))) {
            assertTrue("$d", AxisMap.suggest(d).isNotEmpty())
        }
        val v = Vec3(0.1f, -0.2f, 0.3f)
        for (m in AxisMap.entries) {
            assertEquals(m.label, v, m.unapply(m.apply(v)))
            assertEquals(m.label, v, m.apply(m.unapply(v)))
            assertEquals(m.label, 3f, GravityAxes.likeness(m, m), 1e-6f)
        }
        // A bias carried across a layout change is the same turn in the sensor's axes.
        val bias = Vec3(0.001f, 0.002f, -0.003f)
        val carried = GravityAxes.carry(bias, AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_ZDOWN_YBACK)
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT.unapply(bias), AxisMap.XRIGHT_ZDOWN_YBACK.unapply(carried))
    }

    @Test
    fun theStartHandsItsRestToTheCheckAndTheMendedLayoutIsLevel() {
        val wrong = AxisMap.XRIGHT_YDOWN_ZFRONT
        val fusion = HeadFusion(FusionSettings(axes = wrong))
        feed(fusion, quarterTurn)
        val rest = fusion.takeRestGravity()
        assertNotNull(rest)
        assertEquals("start", rest!!.why)
        assertFalse("a worn head's tremor is not a table", rest.tableStill)
        assertEquals(0f, (rest.raw - quarterTurn).length, 0.05f)
        assertNull("taken once", fusion.takeRestGravity())
        val v = GravityAxes.check(rest, wrong, GlassesModel.ONE_PRO.defaultAxes)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, v.axes)
        // The fusion again on the mended layout, as the head tracker does: level.
        fusion.settings = fusion.settings.copy(axes = v.axes!!)
        fusion.reset()
        feed(fusion, quarterTurn)
        assertEquals(0f, pitchDeg(fusion.pose().orientation), 1f)
    }

    @Test
    fun aStillTableAtTheStartIsMarkedSo() {
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        feed(fusion, quarterTurn, onTable = true)
        assertTrue(fusion.takeRestGravity()!!.tableStill)
    }

    @Test
    fun aSteadyRecentreHandsItsRestToTheCheck() {
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        val t = feed(fusion, quarterTurn)
        fusion.takeRestGravity()
        feed(fusion, quarterTurn, seconds = 1f, fromNs = t)
        fusion.recentre()
        val rest = fusion.takeRestGravity()
        assertNotNull(rest)
        assertEquals("recentre", rest!!.why)
        assertEquals(0f, (rest.raw - quarterTurn).length, 0.1f)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, GravityAxes.check(rest, AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT).axes)
    }

    @Test
    fun theReportSaysTheModelIdsLayoutAndGravity() {
        val axes = GravityAxes.reportLine(AxisMap.XRIGHT_YDOWN_ZFRONT, "the XREAL One Pro+'s default", logLevelish, "gravity check (recentre): kept")
        val r = GlassesReport(
            appVersion = "1.2.13 (100)", phone = "samsung SM-F956U1", android = "17 (API 37)",
            displays = listOf(GlassesReport.Display("XREAL 1 Pro+", "16657", listOf("1920x1080@60"), "1920x1080@60")),
            usb = listOf(GlassesReport.Usb(0x3318, 0x0444, "XREAL 1 Pro+", listOf("CDC Network Control Model (NCM)"))),
            controlLink = "open", serial = null, stereoState = 0, edidIndex = null,
            headTracking = "Streaming", headTrackingWorks = true, calibration = "none", hands = "not available", timeUtc = "2026-11-10 12:00",
            headAxes = axes,
        )
        val t = r.text()
        assertTrue(t, t.contains("Model: XREAL One Pro+ (oneproplus)"))
        assertTrue(t, t.contains("Detected from: display \"XREAL 1 Pro+\" (EDID product 16657); USB 3318:0444"))
        assertTrue(t, t.contains("Head axes: x right, y down, z front (the XREAL One Pro+'s default); gravity at the last rest, sensor axes 0.51, -9.77, 1.45 m/s2, 9 deg from this layout's up"))
        // Before any motion was read.
        assertTrue(r.copy(headAxes = null).text().contains("Head axes: not known yet"))
    }
}
