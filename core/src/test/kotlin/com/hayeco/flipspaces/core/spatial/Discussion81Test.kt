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
 * 1.2.13 (Discussion #81, gegonut's XREAL One Pro+ on a Galaxy Z Fold 6: "When looking straight ahead it's as if I'm
 * looking straight up. When I recenter, all of the windows appear straight down. Home always shows properly though."):
 * the One Pro+ is told from its name, its layout puts a level head level, and the gravity check mends a layout that
 * puts gravity a quarter turn off, at the start and on a recentre.
 */
class Discussion81Test {
    private val g = 9.81f

    /** The One Pro+'s sensor at rest on a level head, as the tester's words say it reads: gravity's reaction along +z. */
    private val onePlusAtRest = Vec3(0f, 0f, g)

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
    fun theOneProPlusIsToldFromItsName() {
        for (name in listOf("XREAL One Pro+", "XREAL 1 Pro+", "XREAL One Pro Plus", "xreal 1pro+")) {
            assertEquals(name, GlassesModel.ONE_PRO_PLUS, GlassesModel.detect(GlassesId(name, "")))
        }
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
        assertTrue(SteadyMode.offeredOn(m))
    }

    @Test
    fun aOneProPlusAtRestIsLevelOnItsOwnLayout() {
        val fusion = HeadFusion(FusionSettings(axes = GlassesModel.ONE_PRO_PLUS.defaultAxes))
        feed(fusion, onePlusAtRest)
        val q = fusion.pose().orientation
        assertEquals("pitch", 0f, pitchDeg(q), 1f)
        assertTrue("upright", q.up.y > 0.99f)
    }

    @Test
    fun onTheOnesLayoutTheSameRestReadsAsLookingStraightUp() {
        // What the tester saw: a level head drawn as one looking straight up, so the windows ahead sit straight down.
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        feed(fusion, onePlusAtRest)
        assertEquals(90f, pitchDeg(fusion.pose().orientation), 2f)
        assertEquals(90f, GravityAxes.angleFromUp(onePlusAtRest, AxisMap.XRIGHT_YDOWN_ZFRONT), 0.01f)
    }

    @Test
    fun theGravityCheckMendsAQuarterTurnKeepingRightAsRight() {
        // Up along +z: the One Pro+'s layout; along -z: the other quarter turn. Right stays right in both.
        val v = GravityAxes.check(GravityAxes.Rest(onePlusAtRest, "start"), AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, v.axes)
        assertEquals(GlassesModel.ONE_PRO_PLUS.defaultAxes, v.axes)
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
        val v = GravityAxes.check(GravityAxes.Rest(onePlusAtRest, "start", tableStill = true), AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT)
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
        feed(fusion, onePlusAtRest)
        val rest = fusion.takeRestGravity()
        assertNotNull(rest)
        assertEquals("start", rest!!.why)
        assertFalse("a worn head's tremor is not a table", rest.tableStill)
        assertEquals(0f, (rest.raw - onePlusAtRest).length, 0.05f)
        assertNull("taken once", fusion.takeRestGravity())
        val v = GravityAxes.check(rest, wrong, GlassesModel.ONE_PRO.defaultAxes)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, v.axes)
        // The fusion again on the mended layout, as the head tracker does: level.
        fusion.settings = fusion.settings.copy(axes = v.axes!!)
        fusion.reset()
        feed(fusion, onePlusAtRest)
        assertEquals(0f, pitchDeg(fusion.pose().orientation), 1f)
    }

    @Test
    fun aStillTableAtTheStartIsMarkedSo() {
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        feed(fusion, onePlusAtRest, onTable = true)
        assertTrue(fusion.takeRestGravity()!!.tableStill)
    }

    @Test
    fun aSteadyRecentreHandsItsRestToTheCheck() {
        val fusion = HeadFusion(FusionSettings(axes = AxisMap.XRIGHT_YDOWN_ZFRONT))
        val t = feed(fusion, onePlusAtRest)
        fusion.takeRestGravity()
        feed(fusion, onePlusAtRest, seconds = 1f, fromNs = t)
        fusion.recentre()
        val rest = fusion.takeRestGravity()
        assertNotNull(rest)
        assertEquals("recentre", rest!!.why)
        assertEquals(0f, (rest.raw - onePlusAtRest).length, 0.1f)
        assertEquals(AxisMap.XRIGHT_ZUP_YFRONT, GravityAxes.check(rest, AxisMap.XRIGHT_YDOWN_ZFRONT, AxisMap.XRIGHT_YDOWN_ZFRONT).axes)
    }

    @Test
    fun theReportSaysTheModelIdsLayoutAndGravity() {
        val axes = GravityAxes.reportLine(AxisMap.XRIGHT_ZUP_YFRONT, "the XREAL One Pro+'s default", onePlusAtRest, "gravity check (start): kept")
        val r = GlassesReport(
            appVersion = "1.2.13 (100)", phone = "samsung SM-F956U", android = "16 (API 36)",
            displays = listOf(GlassesReport.Display("XREAL One Pro+", "16645", listOf("1920x1080@120"), "1920x1080@120")),
            usb = listOf(GlassesReport.Usb(0x3318, 0x0436, "XREAL One Pro+", listOf("CDC Network Control Model (NCM)"))),
            controlLink = "open", serial = null, stereoState = 0, edidIndex = null,
            headTracking = "Streaming", headTrackingWorks = true, calibration = "none", hands = "not available", timeUtc = "2026-11-10 12:00",
            headAxes = axes,
        )
        val t = r.text()
        assertTrue(t, t.contains("Model: XREAL One Pro+ (oneproplus)"))
        assertTrue(t, t.contains("Detected from: display \"XREAL One Pro+\" (EDID product 16645); USB 3318:0436"))
        assertTrue(t, t.contains("Head axes: x right, y front, z up (the XREAL One Pro+'s default); gravity at the last rest, sensor axes 0.00, 0.00, 9.81 m/s2, 0 deg from this layout's up"))
        // Before any motion was read.
        assertTrue(r.copy(headAxes = null).text().contains("Head axes: not known yet"))
    }
}
