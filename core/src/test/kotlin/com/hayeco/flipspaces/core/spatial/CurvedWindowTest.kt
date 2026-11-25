package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.CoverButton
import com.hayeco.flipspaces.core.pointer.CoverLayoutMath
import com.hayeco.flipspaces.core.pointer.HapticGate
import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.StripSide
import com.hayeco.flipspaces.core.pointer.WindowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/** 0.5.19: curved windows: the cylinder, its mesh and texture mapping, hit tests on it, the controls and the draw cost. */
class CurvedWindowTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val curves = listOf(0.1f, 0.35f, 0.6f, 1f)

    private fun panel(curve: Float, yaw: Float = 0f, pitch: Float = 0f, width: Float = 40f, height: Float = 22.5f, distance: Float = 1.4f) =
        SpacePanel(1, "Chrome", "com.android.chrome", "Main", yaw, pitch, distance, width, height, curve)

    /** Seen from [origin], the yaw of a room point, degrees. */
    private fun yawOf(v: Vec3, origin: Vec3 = Vec3.ZERO): Float = (v - origin).let { atan2(it.x, -it.z) * RAD }

    // ---- the cylinder ----

    @Test
    fun aFullCurveIsCentredOnTheHeadSoEveryColumnIsTheSameDistance() {
        for (pitch in listOf(0f, 20f, -35f)) {
            val p = panel(1f, yaw = 30f, pitch = pitch)
            for (k in 0..20) {
                val x = -0.5f + k / 20f
                val d = PanelCurve.point(p, x, 0f).length
                assertEquals("column at x $x, pitch $pitch", p.distanceM, d, 1e-4f)
            }
            // The axis passes through the head.
            assertEquals(0f, PanelCurve.axis(p).length, 1e-4f)
        }
    }

    @Test
    fun aCurveKeepsTheWindowsAngularWidth() {
        for (c in curves + 0.006f) {
            val p = panel(c, width = 60f)
            assertEquals("left edge at curve $c", -30f, yawOf(PanelCurve.point(p, -0.5f, 0f)), 0.01f)
            assertEquals("right edge at curve $c", 30f, yawOf(PanelCurve.point(p, 0.5f, 0f)), 0.01f)
            // The centre does not move.
            val centre = PanelCurve.point(p, 0f, 0f)
            assertEquals(0f, (centre - p.centre).length, 1e-4f)
        }
        // From flat, a slight curve is the flat window: its arc width meets the flat width.
        val nearly = panel(0.006f, width = 60f)
        assertEquals(nearly.widthM, PanelCurve.arcWidthM(nearly), nearly.widthM * 0.005f)
    }

    // ---- the mesh and its texture mapping ----

    @Test
    fun theMeshSharesEveryInnerEdgeSoNoSeamCanOpen() {
        val mesh = CurveMesh()
        val edges = HashMap<Pair<Int, Int>, Int>()
        val idx = mesh.indices
        for (t in idx.indices step 3) {
            val a = idx[t].toInt()
            val b = idx[t + 1].toInt()
            val c = idx[t + 2].toInt()
            for ((i, j) in listOf(a to b, b to c, c to a)) edges.merge(minOf(i, j) to maxOf(i, j), 1, Int::plus)
        }
        // Each edge is in one triangle (the outline) or two (inside); never more, and no vertex is duplicated.
        assertTrue(edges.values.all { it == 1 || it == 2 })
        val outline = edges.values.count { it == 1 }
        assertEquals(2 * (mesh.cols + mesh.rows), outline)
        val points = (0 until mesh.vertexCount).map { mesh.positions[it * 2] to mesh.positions[it * 2 + 1] }
        assertEquals(points.size, points.toSet().size)
        assertEquals(mesh.cols * mesh.rows * 2, mesh.triangleCount)
        // Every triangle faces the same way (counter clockwise in the mesh's x, y).
        for (t in idx.indices step 3) {
            val (ax, ay) = points[idx[t].toInt()]
            val (bx, by) = points[idx[t + 1].toInt()]
            val (cx, cy) = points[idx[t + 2].toInt()]
            assertTrue((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0f)
        }
    }

    @Test
    fun meshVerticesLieOnTheCylinderWhereTheirTextureCoordinateSays() {
        val mesh = CurveMesh()
        for (c in curves) {
            val p = panel(c, yaw = -20f, pitch = 10f)
            val body = PanelCurve.body(p)
            val r = PanelCurve.radius(p)
            val axis = PanelCurve.axis(p)
            for (i in 0 until mesh.vertexCount) {
                val ax = mesh.positions[i * 2]
                val ay = mesh.positions[i * 2 + 1]
                val v = PanelCurve.vertex(body, ax, ay)
                // On the cylinder: its distance from the axis (along the window's up) is the radius.
                val off = v - axis
                val radial = off - p.up * (off dot p.up)
                assertEquals("radius at curve $c", r, radial.length, r * 1e-5f + 1e-5f)
                // The texture coordinate the shader gives this vertex (ax + 0.5, ay + 0.5) is the surface point there.
                val want = PanelCurve.point(p, ax, ay)
                assertEquals(0f, (v - want).length, 1e-4f)
            }
        }
    }

    @Test
    fun theTitleBarMeetsTheBodyAlongTheSameArc() {
        for (c in curves) {
            val p = panel(c)
            val body = PanelCurve.body(p)
            // 0.5.20: the Move pill (no title bar); each of its points straight above the body's top
            // at the same place along the arc, by the controls' gap.
            val l = WindowControls.layout(p)
            val move = l.of(WindowControls.Control.MOVE)
            val bar = PanelCurve.titleBar(p)
            for (k in 0..16) {
                val ax = -0.5f + k / 16f
                val u = move.u0 + (ax + 0.5f) * (move.u1 - move.u0)
                val top = PanelCurve.vertex(body, u - 0.5f, 0.5f)
                val barBottom = PanelCurve.vertex(bar, ax, -0.5f)
                val d = barBottom - top
                // Straight up the cylinder by the gap: the same arc position, no sideways step.
                assertEquals(0f, (d - p.up * (d dot p.up)).length, 1e-4f)
                assertEquals(l.gapM, d dot p.up, 1e-4f)
            }
        }
    }

    @Test
    fun theTessellationStaysUnderAQuarterPixel() {
        for (c in curves) for (w in listOf(40f, 100f)) {
            val e = CurveDrawCost.chordErrorPx(panel(c, width = w), frustum)
            println("chord error, curve $c, $w deg: %.4f glasses px".format(java.util.Locale.ROOT, e))
            assertTrue("curve $c, $w deg: $e px", e < 0.25f)
        }
        assertEquals(0f, CurveDrawCost.chordErrorPx(panel(0f), frustum), 0f)
    }

    // ---- ray to cylinder ----

    @Test
    fun aRayThroughAnyPointOfTheCurveHitsItThere() {
        val origins = listOf(Vec3.ZERO, Vec3(0.18f, -0.32f, -0.12f), Vec3(-0.2f, -0.25f, 0.05f))
        for (c in curves) for (origin in origins) {
            val p = panel(c, yaw = 25f, pitch = -10f)
            // From just inside each edge (the edge itself is a float's width from being off the body).
            for (i in 0..10) for (j in 0..10) {
                val u = 0.002f + 0.996f * i / 10f
                val v = 0.002f + 0.996f * j / 10f
                val target = PanelCurve.point(p, u - 0.5f, 0.5f - v)
                val hit = PanelHitTester.hit(Ray(origin, (target - origin).normalized()), p)
                assertNotNull("curve $c, u $u, v $v", hit)
                hit!!
                assertEquals("u at curve $c", u, hit.u, 1e-3f)
                assertEquals("v at curve $c", v, hit.v, 1e-3f)
                // The cursor sits where the ray meets the surface.
                assertEquals(0f, (hit.point - target).length, 1e-3f)
                assertEquals((target - origin).length, hit.distance, 1e-3f)
            }
        }
    }

    @Test
    fun theBarButtonsAndTheCornerAreWhereTheyAreDrawn() {
        for (c in listOf(0f) + curves) {
            val p = panel(c)
            fun zoneAt(r: PanelCurve.Rect, ax: Float = 0f, ay: Float = 0f) = PanelHitTester.hit(Ray(Vec3.ZERO, PanelCurve.vertex(r, ax, ay).normalized()), p)?.zone
            assertEquals("curve $c", PanelZone.BODY, zoneAt(PanelCurve.body(p)))
            assertEquals("curve $c", PanelZone.TITLE_BAR, zoneAt(PanelCurve.titleBar(p)))
            assertEquals("curve $c", PanelZone.BACK, zoneAt(PanelCurve.backButton(p)))
            assertEquals("curve $c", PanelZone.CLOSE, zoneAt(PanelCurve.barButton(p, 1)))
            assertEquals("curve $c", PanelZone.RESET, zoneAt(PanelCurve.barButton(p, 2)))
            assertEquals("curve $c", PanelZone.CORNER, zoneAt(PanelCurve.corner(p)))
        }
    }

    @Test
    fun flatWindowsHitExactlyAsBefore() {
        val p = panel(0f, yaw = 15f, pitch = 5f)
        for (yaw in -20..40 step 3) for (pitch in -10..20 step 3) {
            val ray = Ray(Vec3.ZERO, Vec3.direction(yaw.toFloat(), pitch.toFloat()))
            val hit = PanelHitTester.hit(ray, p, 1.5f) ?: continue
            // 0.5.18's plane maths.
            val t = ray.planeDistance(p)!!
            val local = ray.at(t) - p.centre
            assertEquals(0.5f + (local dot p.right) / p.widthM, hit.u, 1e-6f)
            assertEquals(0.5f - (local dot p.up) / p.heightM, hit.v, 1e-6f)
            assertEquals(t, hit.distance, 1e-6f)
        }
    }

    @Test
    fun aRayThatMissesTheWindowMissesItCurvedToo() {
        for (c in curves) {
            val p = panel(c)
            assertNull(PanelHitTester.hit(Ray(Vec3.ZERO, Vec3.direction(60f, 0f)), p))
            assertNull(PanelHitTester.hit(Ray(Vec3.ZERO, Vec3.direction(180f, 0f)), p))
            assertNull(PanelHitTester.hit(Ray(Vec3.ZERO, Vec3.direction(0f, 40f)), p))
        }
    }

    // ---- the draw cost ----

    @Test
    fun curvingEveryWindowFitsTheEyePassBudget() {
        // Six curved windows, both eyes: the vertices add a few hundredths of a millisecond.
        val vertices = CurveDrawCost.extraMs(PanelLimits.MAX_PANELS)
        assertTrue("vertices $vertices ms", vertices < 0.01 * CurveDrawCost.EYE_PASSES_BUDGET_MS)
        // The fragments: a curved window keeps its angular width, but its edges come nearer, so they look
        // taller than a flat window's (which recede): a few percent more glasses pixels at 40 degrees, at most
        // 15 % for an 80 degree window fully curved (whose flat edges look 23 % shorter).
        for (w in listOf(40f, 80f)) {
            val flat = CurveDrawCost.coveredPx(panel(0f, width = w), frustum)
            for (c in curves) {
                val curved = CurveDrawCost.coveredPx(panel(c, width = w), frustum)
                println("pixels covered, $w deg, curve $c: %.0f against %.0f flat (%+.1f %%)".format(java.util.Locale.ROOT, curved, flat, (curved / flat - 1) * 100))
                val limit = if (w <= 40f) 1.04 else 1.15
                assertTrue("$w deg at curve $c: $curved px against $flat", curved <= flat * limit && curved >= flat * 0.99)
            }
        }
        // All six default windows fully curved: under 5 % of the eye passes' 6.6 ms budget.
        val flat = CurveDrawCost.coveredPx(panel(0f), frustum)
        val curved = CurveDrawCost.coveredPx(panel(1f), frustum)
        val total = CurveDrawCost.extraMs(PanelLimits.MAX_PANELS, flat, curved)
        println("six fully curved default windows, both eyes: vertices %.4f ms, all %.4f ms of the %.1f ms eye pass budget".format(java.util.Locale.ROOT, vertices, total, CurveDrawCost.EYE_PASSES_BUDGET_MS))
        assertTrue("six curved windows add $total ms", total < 0.05 * CurveDrawCost.EYE_PASSES_BUDGET_MS)
    }

    // ---- saving ----

    @Test
    fun eachWindowsCurveAndTheWrapAreSaved() {
        // 0.5.21: windows carry their app size (as a save gives them back).
        val layout = SpaceLayout(listOf(panel(0.35f), panel(0f).copy(id = 2, yawDeg = 50f), panel(1f).copy(id = 3, yawDeg = -50f)).map { it.withAppSize() })
        val back = LayoutCodec.decode(LayoutCodec.encode(layout))
        assertEquals(listOf(0.35f, 0f, 1f), back.panels.map { it.curve })
        assertNull(back.wrap)
        val wrapped = SpaceWrap.on(layout, 2f)
        val again = LayoutCodec.decode(LayoutCodec.encode(wrapped))
        assertEquals(wrapped, again)
        assertEquals(layout.panels, SpaceWrap.off(again).panels)
        // A 0.5.18 save (v1, nine fields) still opens, flat.
        val v1 = "flipspaces-layout v1\n1\tChrome\tcom.android.chrome\tMain\t0.0\t0.0\t1.4\t40.0\t22.5\n"
        assertEquals(listOf(0f), LayoutCodec.decode(v1).panels.map { it.curve })
    }

    // ---- the control ----

    @Test
    fun curveStepsBuzzAndFlatSnaps() {
        var raw = 0f
        val buzzes = mutableListOf<PadHaptic>()
        repeat(25) {
            val c = CurveControl.move(raw, 0.02f * PanelCurve.MAX)
            raw = c.raw
            c.haptic?.let(buzzes::add)
        }
        // Half the range in steps of 2 %: a buzz at each tenth (0.5.36: the range is [PanelCurve.MAX]).
        assertEquals(0.5f * PanelCurve.MAX, CurveControl.snapped(raw), 1e-4f)
        assertEquals(5, buzzes.count { it == PadHaptic.CURVE_STEP })
        // Back down: under 0.04 it snaps flat once, with the flat buzz.
        buzzes.clear()
        repeat(25) {
            val c = CurveControl.move(raw, -0.02f * PanelCurve.MAX)
            raw = c.raw
            c.haptic?.let(buzzes::add)
        }
        assertEquals(0f, CurveControl.snapped(raw), 0f)
        assertEquals(1, buzzes.count { it == PadHaptic.CURVE_FLAT })
        // Leaving flat needs the snap distance again.
        assertEquals(0f, CurveControl.move(raw, 0.03f).curve, 0f)
        // Never past full.
        assertEquals(PanelCurve.MAX, CurveControl.move(0.95f * PanelCurve.MAX, 0.5f * PanelCurve.MAX).curve, 0f)
        assertEquals("CURVE 40 %", CurveControl.guide(0.4f * PanelCurve.MAX))
        assertEquals("FLAT", CurveControl.guide(0f))
        // The steps are felt one after the other: no cooldown for them.
        val gate = HapticGate()
        assertNotNull(gate.play(PadHaptic.COVER_BUTTON, 0))
        assertEquals(PadHaptic.CURVE_STEP.ms, gate.play(PadHaptic.CURVE_STEP, 50))
        assertEquals(PadHaptic.CURVE_FLAT.ms, gate.play(PadHaptic.CURVE_FLAT, 60))
        assertNull(gate.play(PadHaptic.COVER_BUTTON, 100))
    }

    private var layout = SpaceLayout(
        listOf(
            SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f),
            SpacePanel(2, "Maps", "com.google.android.apps.maps", "Main", yawDeg = 50f, pitchDeg = 0f),
        ),
    )
    private var committed: SpaceLayout? = null
    private val haptics = mutableListOf<PadHaptic>()

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, { Pair(1280, 720) }).also(::apply)

    private fun apply(out: List<SpaceAction>) {
        out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let {
            layout = it.layout
            if (it.commit) committed = it.layout
        }
        out.filterIsInstance<SpaceAction.Haptic>().forEach { haptics += it.haptic }
    }

    /** 1.6.28 (Crr66: "I like less complexity"): a tap on Curve steps the window under the cursor at once, nothing held. */
    @Test
    fun aTapOnCurveStepsTheWindowFlatHalfFullAndFlatAgain() {
        val p = SpatialPointer(frustum)
        p.frame(Quat.IDENTITY, layout, 16)
        val steps = (1..4).map {
            p.send(PadSignal.Window(WindowAction.CURVE))
            assertNull("nothing is held", p.heldPanelId)
            committed!!.panel(1)!!.curve
        }
        assertEquals(listOf(0.5f * PanelCurve.MAX, PanelCurve.MAX, 0f, 0.5f * PanelCurve.MAX), steps)
        // The other window is untouched.
        assertEquals(0f, committed!!.panel(2)!!.curve, 0f)
        // From a curve between the steps, the next step up.
        assertEquals(0.5f * PanelCurve.MAX, CurveControl.tapStep(0.3f * PanelCurve.MAX), 0f)
        assertEquals(PanelCurve.MAX, CurveControl.tapStep(0.6f * PanelCurve.MAX), 0f)
        assertEquals(0f, CurveControl.tapStep(0.99f * PanelCurve.MAX), 0f)
    }

    @Test
    fun aSlideAlongTheCurveButtonCurvesTheWindowUnderTheCursor() {
        val p = SpatialPointer(frustum)
        p.frame(Quat.IDENTITY, layout, 16)
        p.send(PadSignal.Curve(0f, Phase.BEGIN))
        // 15 mm of thumb travel is half the range.
        repeat(15) { p.send(PadSignal.Curve(CurveControl.PER_MM, Phase.UPDATE)) }
        p.send(PadSignal.Curve(0f, Phase.END))
        assertNull(p.heldPanelId)
        assertEquals(0.5f * PanelCurve.MAX, committed!!.panel(1)!!.curve, 1e-3f)
        assertEquals(5, haptics.count { it == PadHaptic.CURVE_STEP })
    }

    @org.junit.Ignore("0.5.36: the wraparound is retired (Mike: Curve and Further/Closer on the selected window only); a saved wrap is undone at load")
    @Test
    fun aWrappedSpaceIsOneCylinder() {
        val wrapped = SpaceWrap.on(layout)
        assertTrue(wrapped.panels.all { it.curve == 1f && it.distanceM == wrapped.wrap!!.radiusM })
        // Every window of it on the one head-centred cylinder.
        for (pl in wrapped.panels) for (k in 0..8) assertEquals(wrapped.wrap!!.radiusM, PanelCurve.point(pl, -0.5f + k / 8f, 0f).length, 1e-4f)
        // One window pushed further moves the wrap; a new window joins it.
        val pushed = wrapped.replace(wrapped.panel(2)!!.copy(distanceM = 2.2f))
        val kept = SpaceWrap.keep(wrapped, pushed)
        assertTrue(kept.panels.all { it.distanceM == 2.2f && it.curve == 1f })
        val added = kept.add("Clock", "com.google.android.deskclock", "Main", aroundYaw = -60f, curve = 0.2f)!!.first
        assertEquals(2.2f, added.panels.last().distanceM, 0f)
        assertEquals(1f, added.panels.last().curve, 0f)
        // Off: each back as it was; the new one at the default curve.
        val off = SpaceWrap.off(added, defaultCurve = 0.35f)
        // 0.5.27: the push moved the whole wrap as a depth move does (sizes in the room kept, so the windows look
        // smaller at 2.2 m); off puts each back at its own distance and curve, as big in view as it was in the wrap.
        for ((was, now) in layout.panels.zip(off.panels.take(2))) {
            assertEquals(was.distanceM, now.distanceM, 0f)
            assertEquals(was.curve, now.curve, 0f)
            assertEquals(kept.panel(was.id)!!.widthDeg, now.widthDeg, 0f)
        }
        // Without a push, off gives back exactly what was there.
        assertEquals(layout.panels, SpaceWrap.off(wrapped).panels)
        assertEquals(0.35f, off.panels.last().curve, 0f)
        assertNull(off.wrap)
        // While wrapped, a window is not curved on its own: 0.5.28, Curve bends the whole wrap.
        layout = wrapped
        val p = SpatialPointer(frustum)
        p.frame(Quat.IDENTITY, layout, 16)
        p.send(PadSignal.Window(WindowAction.CURVE))
        val out = p.send(PadSignal.Move(-0.4f, 0f, 0f, 0f))
        assertEquals(0.8f, layout.panel(1)!!.curve, 1e-4f)
        assertEquals(0.8f, layout.panel(2)!!.curve, 1e-4f)
        assertEquals(0.8f, layout.wrap!!.curve, 1e-4f)
        assertTrue(out.filterIsInstance<SpaceAction.Note>().any { "wrapped" in it.line })
    }

    @Test
    fun newWindowsTakeTheDefaultCurve() {
        val (l, added) = SpaceLayout().add("Clock", "com.google.android.deskclock", "Main", curve = 0.6f)!!
        assertEquals(0.6f, added.curve, 0f)
        assertEquals(0.6f, l.panels.single().curve, 0f)
    }

    @Test
    fun theCoverRailHasCurveAndKeepsEveryRule() {
        for (side in StripSide.entries) {
            val l = CoverLayoutMath.flip7(side)
            assertTrue(CoverButton.CURVE in l.buttons)
            assertEquals(emptyList<String>(), CoverLayoutMath.problems(l))
        }
    }

    @Test
    fun aCurvedWindowIsStillCulledAsItsAngleSays() {
        for (c in curves) {
            val p = panel(c, yaw = 0f)
            assertTrue(PanelCulling.visible(p, Quat.IDENTITY, frustum))
            // Its edges are no further out than the flat window's corners the cone allows for.
            val edge = abs(yawOf(PanelCurve.point(p, 0.5f, 0.5f)))
            assertTrue(edge <= PanelCulling.panelRadiusDeg(p) + 0.01f)
        }
    }
}
