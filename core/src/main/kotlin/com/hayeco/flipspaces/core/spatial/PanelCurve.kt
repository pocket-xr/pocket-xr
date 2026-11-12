package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import com.hayeco.flipspaces.core.pointer.PadHaptic
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 0.5.19 (Mike: "a way to curve the windows, like an ultrawide curved monitor"). A Pocket XR design.
 *
 * A window's [SpacePanel.curve] runs from 0 (flat, 0.5.18's quad) to 1. It bends the window round a
 * vertical cylinder (the axis along the window's own up) of radius distance / curve, whose axis is
 * on Mike's side of the window. At 1 the axis passes through his head, so every column of the window
 * is the same distance from his eyes: the ultrawide monitor curve. Its angular width stays
 * [SpacePanel.widthDeg] at any curve: the edges are bent forward until each is seen half that angle
 * from the centre, so a curve never makes a window look wider or narrower, only rounder.
 */

/** 0.5.19: the curved surface of a window, its hit test and the rectangles drawn on it. */
object PanelCurve {
    /** At or under this a curve is drawn, hit tested and saved flat. */
    const val FLAT_EPS = 0.005f

    /**
     * 0.5.36 (Mike: "I barely notice a difference between 0 % and 100 %"): the strongest curve, 2.5 (was 1). At 1 the
     * axis is at the head and every column is as far from the eye, so from the head the outline is the flat one's and only
     * the edges' depth changes. Past 1 the axis is in front of the head and the edges come clearly forward; 100 % on the
     * Curve key and the Menu's slider is this.
     */
    const val MAX = 2.5f

    /** 0.5.36: the furthest an edge bends round its axis (a wide window is held to it), so no edge turns past side on. */
    private const val MAX_HALF_ARC_DEG = 85f

    /** 0.5.36: a curve as the share of [MAX] shown to Mike, 0 to 100 %. */
    fun percent(curve: Float): Int = kotlin.math.round(curve.coerceIn(0f, MAX) / MAX * 100f).toInt()

    /**
     * 0.5.36: the curve a window is drawn with: [SpacePanel.curve], held so its half arc stays under [MAX_HALF_ARC_DEG]
     * (phi = a + asin(sin(a)(c - 1)) reaches 85 deg at c = 1 + sin(85 deg - a) / sin(a)).
     */
    fun effective(p: SpacePanel): Float {
        val c = p.curve.coerceIn(0f, MAX)
        if (c <= 1f) return c
        val a = p.widthDeg.coerceIn(1f, 179f) * DEG / 2f
        val limit = MAX_HALF_ARC_DEG * DEG
        if (a >= limit) return 1f
        return minOf(c, 1f + sin(limit - a) / sin(a))
    }

    fun isCurved(p: SpacePanel): Boolean = p.curve > FLAT_EPS

    /** The cylinder's radius, metres (infinite when flat). */
    fun radius(p: SpacePanel): Float = if (isCurved(p)) p.distanceM / effective(p) else Float.POSITIVE_INFINITY

    /**
     * Half the body's arc around the axis, radians. With half the window's angular width a, an edge at
     * arc angle phi is at R sin(phi) across and R (1 - cos(phi)) nearer, and is seen at a from the head
     * when sin(phi - a) = -sin(a)(1 - c): phi = a - asin(sin(a)(1 - c)). At c = 1 phi = a.
     */
    fun halfArc(p: SpacePanel): Float {
        if (!isCurved(p)) return 0f
        val a = p.widthDeg.coerceIn(1f, 179f) * DEG / 2f
        return a - asin((sin(a) * (1f - effective(p))).coerceIn(-1f, 1f))
    }

    /** The axis's point level with the window's centre: [SpacePanel.centre] plus the normal times the radius. */
    fun axis(p: SpacePanel): Vec3 = p.centre + p.normal * radius(p)

    /** The body's width along its surface, metres (the flat width when flat). */
    fun arcWidthM(p: SpacePanel): Float = if (isCurved(p)) radius(p) * 2f * halfArc(p) else p.widthM

    /**
     * The surface point at [x] (-0.5 at the left edge to 0.5 at the right, along the arc; beyond for the
     * title bar's reach) and [y] (-0.5 bottom to 0.5 top, straight up the cylinder).
     */
    fun point(p: SpacePanel, x: Float, y: Float): Vec3 {
        if (!isCurved(p)) return p.centre + p.right * (x * p.widthM) + p.up * (y * p.heightM)
        val r = radius(p)
        val th = x * 2f * halfArc(p)
        return axis(p) + p.right * (r * sin(th)) - p.normal * (r * cos(th)) + p.up * (y * p.heightM)
    }

    /** Where a ray meets a window's surface: distance along the ray and the body fractions there. */
    data class SurfaceHit(val t: Float, val x: Float, val y: Float)

    /**
     * The ray against the window's surface (0.5.19: a cylinder when curved, else its plane, as 0.5.18):
     * the nearest crossing in front of the ray's origin on the window's own (front) half. The fractions
     * run past the body's -0.5..0.5 so the title bar, the corner and the press margin keep working.
     */
    fun intersect(ray: Ray, p: SpacePanel): SurfaceHit? {
        if (!isCurved(p)) {
            val t = ray.planeDistance(p) ?: return null
            val local = ray.at(t) - p.centre
            return SurfaceHit(t, (local dot p.right) / p.widthM, (local dot p.up) / p.heightM)
        }
        val r = radius(p)
        val q = ray.origin - axis(p)
        val qx = q dot p.right
        val qy = q dot p.up
        val qz = q dot p.normal
        val dx = ray.dir dot p.right
        val dy = ray.dir dot p.up
        val dz = ray.dir dot p.normal
        // Doubles: at a gentle curve the radius is many metres and the roots nearly cancel.
        val a = dx.toDouble() * dx + dz.toDouble() * dz
        if (a < 1e-12) return null
        val b = 2.0 * (qx.toDouble() * dx + qz.toDouble() * dz)
        val c = qx.toDouble() * qx + qz.toDouble() * qz - r.toDouble() * r
        val disc = b * b - 4.0 * a * c
        if (disc < 0.0) return null
        val s = sqrt(disc)
        // The stable form of the two roots.
        val k = -0.5 * (b + if (b >= 0.0) s else -s)
        val roots = listOfNotNull(k / a, if (k != 0.0) c / k else null).sorted()
        val half = halfArc(p)
        for (t in roots) {
            if (t <= 1e-6) continue
            val px = qx + t * dx
            val pz = qz + t * dz
            // The window's half of the cylinder is the far one (cos of the arc angle positive).
            if (pz >= 0.0) continue
            val th = atan2(px, -pz)
            val y = (qy + t * dy) / p.heightM
            return SurfaceHit(t.toFloat(), (th / (2.0 * half)).toFloat(), y.toFloat())
        }
        return null
    }

    /**
     * A rectangle of the window: [u0]..[u1] across (0 left to 1 right, as [PanelHit.u]) and [v0]..[v1]
     * down (0 top to 1 bottom; the title bar is above 0), drawn on the window's surface. [model] maps
     * the drawing mesh's local (x across in metres, y -0.5..0.5, z in metres) to the room; the vertex
     * shader puts each vertex at (R sin(th), y, -R cos(th)) with th from [theta0] to [theta1] across.
     * Flat ([radius] 0): [model] is the rectangle's own quad, 0.5.18's.
     */
    data class Rect(val model: Mat4, val radius: Float, val theta0: Float, val theta1: Float) {
        val curved: Boolean get() = radius > 0f
    }

    fun rect(p: SpacePanel, u0: Float, v0: Float, u1: Float, v1: Float): Rect {
        val h = (v1 - v0) * p.heightM
        val yc = 0.5f - (v0 + v1) / 2f
        if (!isCurved(p)) {
            val w = (u1 - u0) * p.widthM
            val at = p.centre + p.right * (((u0 + u1) / 2f - 0.5f) * p.widthM) + p.up * (yc * p.heightM)
            return Rect(Mat4.basis(at, p.right, p.up, p.normal, w, h), 0f, 0f, 0f)
        }
        val half = halfArc(p)
        val model = Mat4.basis(axis(p) + p.up * (yc * p.heightM), p.right, p.up, p.normal, 1f, h)
        return Rect(model, radius(p), (u0 - 0.5f) * 2f * half, (u1 - 0.5f) * 2f * half)
    }

    /** The mesh vertex ([ax], [ay] in -0.5..0.5) of [r] in the room, as the vertex shader places it. */
    fun vertex(r: Rect, ax: Float, ay: Float): Vec3 {
        val local = if (!r.curved) {
            Vec3(ax, ay, 0f)
        } else {
            val th = r.theta0 + (r.theta1 - r.theta0) * (ax + 0.5f)
            Vec3(r.radius * sin(th), ay, -r.radius * cos(th))
        }
        val o = r.model.transform(local)
        return Vec3(o[0], o[1], o[2])
    }

    // ---- the window's parts, as rectangles of its body (u across, v down) ----

    fun body(p: SpacePanel): Rect = rect(p, 0f, 0f, 1f, 1f)

    /** 0.5.20: one of the window's floating controls ([WindowControls]), on its surface. */
    fun control(p: SpacePanel, c: WindowControls.Control, layout: WindowControls.Layout = WindowControls.layout(p)): Rect =
        layout.of(c).let { rect(p, it.u0, it.v0, it.u1, it.v1) }

    /** 0.5.20: the Move pill (0.5.19 and before: the title bar across the window's top). */
    fun titleBar(p: SpacePanel): Rect = control(p, WindowControls.Control.MOVE)

    fun backButton(p: SpacePanel): Rect = control(p, WindowControls.Control.BACK)

    /** The [fromRight]th button from the right end (1 Close, 2 Reset). */
    fun barButton(p: SpacePanel, fromRight: Int): Rect = control(p, if (fromRight == 1) WindowControls.Control.CLOSE else WindowControls.Control.RESET)

    /** The resize handle's catch area, centred on the bottom right corner (its hit zone; see [cornerBracket] for what is drawn). */
    fun corner(p: SpacePanel): Rect {
        val s = PanelLimits.CORNER_FRACTION * minOf(arcWidthM(p), p.heightM)
        val du = s / (2f * arcWidthM(p))
        val dv = s / (2f * p.heightM)
        return rect(p, 1f - du, 1f - dv, 1f + du, 1f + dv)
    }

    /**
     * 0.5.20: the resize handle as drawn: an L of two short bars just outside the bottom right
     * corner (apart from the window, like its other controls), each [WindowControls] control's height
     * long and a fifth of it thick, a quarter of it off the edge.
     */
    fun cornerBracket(p: SpacePanel): List<Rect> {
        val l = WindowControls.layout(p)
        val w = arcWidthM(p).coerceAtLeast(1e-4f)
        val h = p.heightM.coerceAtLeast(1e-4f)
        val len = l.heightM
        val thick = l.heightM * 0.2f
        val off = l.heightM * 0.25f
        val u0 = 1f + off / w
        val v0 = 1f + off / h
        return listOf(
            // Along the bottom, ending at the corner's outside.
            rect(p, u0 + thick / w - len / w, v0, u0 + thick / w, v0 + thick / h),
            // Up the right side.
            rect(p, u0, v0 + thick / h - len / h, u0 + thick / w, v0 + thick / h),
        )
    }
}

/**
 * 0.5.19: the drawing mesh for a curved rectangle: [cols] by [rows] cells over -0.5..0.5, one shared
 * vertex per grid point, indexed triangles, so neighbouring cells share their edge's vertices exactly
 * (no seam can open between them). [rows] matches [RowScan]'s strips for the per-row pose.
 */
class CurveMesh(val cols: Int = COLS, val rows: Int = RowScan.ROWS) {
    /** x, y per vertex, row by row from the bottom. */
    val positions: FloatArray = FloatArray((cols + 1) * (rows + 1) * 2).also { a ->
        var i = 0
        for (r in 0..rows) for (c in 0..cols) {
            a[i++] = -0.5f + c.toFloat() / cols
            a[i++] = -0.5f + r.toFloat() / rows
        }
    }

    /** Two triangles per cell, counter clockwise seen from the front (the window faces the user). */
    val indices: ShortArray = ShortArray(cols * rows * 6).also { a ->
        var i = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val v00 = (r * (cols + 1) + c).toShort()
            val v10 = (r * (cols + 1) + c + 1).toShort()
            val v01 = ((r + 1) * (cols + 1) + c).toShort()
            val v11 = ((r + 1) * (cols + 1) + c + 1).toShort()
            a[i++] = v00; a[i++] = v10; a[i++] = v11
            a[i++] = v00; a[i++] = v11; a[i++] = v01
        }
    }

    val vertexCount: Int get() = (cols + 1) * (rows + 1)
    val triangleCount: Int get() = cols * rows * 2

    companion object {
        /**
         * 64 columns: the widest window (100 degrees) at full curve is 1.6 degrees of arc a column, whose
         * chord sits at most 0.15 glasses pixels off the arc as Mike sees it ([CurveDrawCost.chordErrorPx]).
         */
        const val COLS = 64
    }
}

/**
 * 0.5.19: what curving costs the eye passes. 0.5.18's passes were 0.3 ms (p50) for everything, the
 * panels' fragments being nearly all of it; a curved window has the same angular size, so the same
 * fragments, plus its mesh's vertices. The per-vertex and per-fragment figures are the Adreno 830's
 * order of magnitude (about 2 G vertices and 8 G four-tap texels a second at the Space's clocks),
 * taken high, so the budget check errs towards failing.
 */
object CurveDrawCost {
    const val NS_PER_VERTEX = 1.0
    const val NS_PER_FRAGMENT = 0.5

    /** 0.5.18's target for both eye passes together (the late draw's budget for under 1 % misses). */
    const val EYE_PASSES_BUDGET_MS = 6.6

    /**
     * The rectangles drawn per window: body, Back, Move, Reset, Close and the corner's two bars
     * (0.5.20: no title bar; 0.5.19 counted 6 with it and one corner square).
     */
    const val RECTS_PER_WINDOW = 7

    /** The extra time both eye passes take with [windows] curved windows ([mesh] each rectangle). */
    fun extraMs(windows: Int, eyes: Int = 2, mesh: CurveMesh = CurveMesh()): Double =
        windows * RECTS_PER_WINDOW * eyes * (mesh.vertexCount - 4) * NS_PER_VERTEX / 1e6

    /**
     * The glasses pixels [p]'s body covers seen from the head through [frustum] (its surface sampled on
     * a 32 by 8 grid, each cell's projected area summed), for the fragment cost of flat against curved.
     */
    fun coveredPx(p: SpacePanel, frustum: ViewFrustum, head: Quat = Quat.fromYawPitch(p.yawDeg, p.pitchDeg)): Double {
        val n = 32
        val m = 8
        val inv = head.conjugate()
        fun px(x: Float, y: Float): Pair<Float, Float>? = frustum.pixelOf(inv.rotate(PanelCurve.point(p, x, y).normalized()))
        var area = 0.0
        for (j in 0 until m) for (i in 0 until n) {
            val x0 = -0.5f + i.toFloat() / n
            val x1 = -0.5f + (i + 1).toFloat() / n
            val y0 = -0.5f + j.toFloat() / m
            val y1 = -0.5f + (j + 1).toFloat() / m
            val a = px(x0, y0) ?: continue
            val b = px(x1, y0) ?: continue
            val c = px(x1, y1) ?: continue
            val d = px(x0, y1) ?: continue
            // The shoelace area of the projected cell.
            area += abs((a.first * b.second - b.first * a.second) + (b.first * c.second - c.first * b.second) + (c.first * d.second - d.first * c.second) + (d.first * a.second - a.first * d.second)) / 2.0
        }
        return area
    }

    /**
     * The largest angle, in glasses pixels seen from the head, between [p]'s true surface and its mesh's
     * flat cells (each column's chord), over its body: how far the tessellation can put a texel.
     */
    fun chordErrorPx(p: SpacePanel, frustum: ViewFrustum, cols: Int = CurveMesh.COLS): Float {
        if (!PanelCurve.isCurved(p)) return 0f
        val r = PanelCurve.body(p)
        var worst = 0f
        for (c in 0 until cols) for (k in 1 until 8) {
            val f = k / 8f
            val ax0 = -0.5f + c.toFloat() / cols
            val ax1 = -0.5f + (c + 1).toFloat() / cols
            val a = PanelCurve.vertex(r, ax0, 0f)
            val b = PanelCurve.vertex(r, ax1, 0f)
            val onChord = a + (b - a) * f
            val onArc = PanelCurve.vertex(r, ax0 + (ax1 - ax0) * f, 0f)
            worst = maxOf(worst, angleDeg(onChord, onArc))
        }
        return worst * frustum.pixelsPerDegree
    }

    /** The angle between two directions, degrees, in doubles from the cross product (acos of a float near 1 cannot see a hundredth of a degree). */
    private fun angleDeg(a: Vec3, b: Vec3): Float {
        val ax = a.x.toDouble(); val ay = a.y.toDouble(); val az = a.z.toDouble()
        val bx = b.x.toDouble(); val by = b.y.toDouble(); val bz = b.z.toDouble()
        val cx = ay * bz - az * by
        val cy = az * bx - ax * bz
        val cz = ax * by - ay * bx
        return Math.toDegrees(kotlin.math.atan2(sqrt(cx * cx + cy * cy + cz * cz), ax * bx + ay * by + az * bz)).toFloat()
    }

    /**
     * Both eye passes' extra time for [windows] curved windows: their mesh vertices, and the fragments a
     * curved window covers beyond its flat self ([curvedPx] against [flatPx] each, from [coveredPx]).
     */
    fun extraMs(windows: Int, flatPx: Double, curvedPx: Double, eyes: Int = 2): Double =
        extraMs(windows, eyes) + windows * eyes * (curvedPx - flatPx).coerceAtLeast(0.0) * NS_PER_FRAGMENT / 1e6
}

/**
 * 0.5.19: the Curve control's feel, from either trackpad. A sideways swipe while the window is held
 * to curve ([CurveControl.PER_PAD_UNIT]: the whole pad's width is flat to full), or a slide along the
 * cover's Curve button ([PER_MM]). Each [STEP] (10 %) crossed buzzes; under [SNAP] it snaps flat with
 * its own buzz, and leaving flat needs [SNAP] again, so flat is easy to hit and to keep.
 */
object CurveControl {
    // 0.5.36: scaled with [PanelCurve.MAX] (was 1), so the same swipe or slide still covers flat to full and buzzes every 10 %.
    const val STEP = 0.1f * PanelCurve.MAX
    const val SNAP = 0.04f * PanelCurve.MAX
    const val PER_PAD_UNIT = 0.5f * PanelCurve.MAX
    const val PER_MM = PanelCurve.MAX / 30f

    /** The curve shown for a raw (unsnapped) value. */
    fun snapped(raw: Float): Float = if (raw < SNAP) 0f else raw.coerceIn(0f, PanelCurve.MAX)

    data class Change(val raw: Float, val curve: Float, val haptic: PadHaptic?)

    /** [raw] moved by [delta]; the buzz for the step or the snap it crossed, if any. */
    fun move(raw: Float, delta: Float): Change {
        val before = snapped(raw)
        val next = (raw + delta).coerceIn(-SNAP, PanelCurve.MAX)
        val after = snapped(next)
        val haptic = when {
            after == 0f && before > 0f -> PadHaptic.CURVE_FLAT
            after == PanelCurve.MAX && before < PanelCurve.MAX -> PadHaptic.CURVE_STEP
            floor(after / STEP + 1e-4f) != floor(before / STEP + 1e-4f) -> PadHaptic.CURVE_STEP
            else -> null
        }
        return Change(next, after, haptic)
    }

    /** "FLAT" or "CURVE 40 %", for the held window's guide. */
    fun guide(curve: Float): String = if (curve <= PanelCurve.FLAT_EPS) UiText.get("spatial_guide_flat", "FLAT") else UiText.format("spatial_guide_curve", "CURVE %1\$d %%", PanelCurve.percent(curve))
}

/**
 * 0.5.19: the whole Space as one wraparound. With it on, every window is fully curved ([SpacePanel.curve]
 * 1) at one shared distance, [radiusM], so windows side by side are pieces of one cylinder round Mike's
 * head; each window's own distance and curve are kept in [saved] and come back when it goes off. A
 * window pushed or pulled while wrapped moves the whole wrap; a window opened while wrapped joins it.
 *
 * 0.5.28 (every Curve swipe on a wrapped Space was refused with only a log
 * line): the wrap has its own [curve], which the Curve key bends for every window at once ([curveTo]).
 */
// 0.5.36: retired from the Menu (a saved wrap is undone at load); its curve stays the head-centred 1, not the new [PanelCurve.MAX].
data class SpaceWrap(val radiusM: Float, val saved: Map<Int, Pair<Float, Float>> = emptyMap(), val curve: Float = 1f) {
    companion object {
        /** 0.5.28: [layout]'s wrap bent to [curve], every window with it; unchanged when not wrapped. */
        fun curveTo(layout: SpaceLayout, curve: Float): SpaceLayout {
            val w = layout.wrap ?: return layout
            val c = curve.coerceIn(0f, PanelCurve.MAX)
            return SpaceLayout(layout.panels.map { it.copy(curve = c) }, w.copy(curve = c))
        }

        /** Wraps [layout] at [radiusM] (default: the median window distance, else the default distance). */
        fun on(layout: SpaceLayout, radiusM: Float? = null): SpaceLayout {
            if (layout.wrap != null) return layout
            val r = (radiusM ?: layout.panels.map { it.distanceM }.sorted().let { if (it.isEmpty()) PanelLimits.DEFAULT_DISTANCE else it[it.size / 2] })
                .coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE)
            val saved = layout.panels.associate { it.id to (it.distanceM to it.curve) }
            return SpaceLayout(layout.panels.map { it.copy(distanceM = r, curve = 1f) }, SpaceWrap(r, saved))
        }

        /** Unwraps [layout]: each window back at its own distance and curve (a window opened while wrapped: [defaultCurve] at the wrap's distance). */
        fun off(layout: SpaceLayout, defaultCurve: Float = 0f): SpaceLayout {
            val w = layout.wrap ?: return layout
            return SpaceLayout(
                layout.panels.map { p -> w.saved[p.id]?.let { (d, c) -> p.copy(distanceM = d, curve = c) } ?: p.copy(curve = defaultCurve) },
                null,
            )
        }

        /**
         * [next] after a change of [prev] while wrapped: a window whose distance changed moves the wrap
         * to that distance; a new window joins it; every window stays fully curved.
         */
        fun keep(prev: SpaceLayout, next: SpaceLayout): SpaceLayout {
            val w = next.wrap ?: prev.wrap ?: return next
            val old = prev.panels.associateBy { it.id }
            val moved = next.panels.lastOrNull { p -> old[p.id]?.let { abs(it.distanceM - p.distanceM) > 1e-4f } == true }
            val r = moved?.distanceM ?: w.radiusM
            val kept = w.saved.filterKeys { id -> next.panels.any { it.id == id } }
            // 0.5.27: the other windows go with it as a depth move does (their size in the room and their displays' pixels kept).
            return SpaceLayout(next.panels.map { PadWindowMath.atDistance(it.copy(curve = w.curve), r) }, SpaceWrap(r, kept, w.curve))
        }
    }
}
