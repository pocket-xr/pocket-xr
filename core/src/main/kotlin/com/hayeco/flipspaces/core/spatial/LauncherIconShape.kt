package com.hayeco.flipspaces.core.spatial

import kotlin.math.max
import kotlin.math.min

/**
 * 1.2.10 (Discussion #24, second report: launcher icons sharp, but "some square, some icon pack icons sitting in a white
 * circle"): every app icon in the one shape the phone gives adaptive icons (its icon mask: a circle on a Pixel, a
 * rounded square on a Galaxy).
 *
 * An adaptive icon's layers are cut by that mask. A legacy or icon pack bitmap was put at 70 % on a white disc, so a
 * square pack icon showed a square on white; now it is measured ([legacy]) and goes one of two ways, never on white:
 * - [Treatment.FILL]: an icon that is already a solid shape (a square, a rounded square, a circle: its opaque part
 *   covers at least [FILL_COVERAGE] of its own box, and the box is about square) is scaled to cover the mask, and the
 *   mask's corners it leaves (a circle in a rounded square) take the colour of its own edge, so it reads as one shape.
 * - [Treatment.PLATE]: a free-form glyph is fitted at [PLATE_GLYPH] of the mask on a plate in its own hue, dimmed
 *   ([plateColor]), not white: on see-through glasses a dark plate vanishes and a white one glares.
 * Colours are ARGB ints, so this runs without Android.
 */
object LauncherIconShape {
    enum class Treatment { FILL, PLATE }

    /** What a legacy bitmap's opaque part is: its box, how much of the box it covers, and its colours. */
    data class Legacy(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val coverage: Float,
        val treatment: Treatment,
        /** The mean colour of its outer ring, opaque; behind a [Treatment.FILL] icon. */
        val edgeColor: Int,
        /** The mean colour of its opaque part, opaque. */
        val meanColor: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** A circle covers 78.5 % of its box; a glyph much less. */
    const val FILL_COVERAGE = 0.75f

    /** How far from square a [Treatment.FILL] icon's box may be. */
    const val MAX_ASPECT = 1.2f

    /** The alpha a pixel counts as part of the icon from. */
    const val SOLID_ALPHA = 128

    /** A glyph's size on its plate, as a share of the mask. */
    const val PLATE_GLYPH = 0.66f

    /** The outer ring an edge colour is read from, as a share of the box's smaller side (at least one pixel). */
    const val EDGE_RING = 0.06f

    /** [argb] ([w] x [h], row by row) measured; null when nothing in it is opaque. */
    fun legacy(argb: IntArray, w: Int, h: Int): Legacy? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (alpha(argb[y * w + x]) >= SOLID_ALPHA) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < 0) return null
        val bw = r - l + 1
        val bh = b - t + 1
        val ring = max(1, (min(bw, bh) * EDGE_RING).toInt())
        var solid = 0
        val mean = ColorSum()
        val edge = ColorSum()
        for (y in t..b) for (x in l..r) {
            val c = argb[y * w + x]
            if (alpha(c) < SOLID_ALPHA) continue
            solid++
            mean.add(c)
            if (x - l < ring || r - x < ring || y - t < ring || b - y < ring) edge.add(c)
        }
        val coverage = solid.toFloat() / (bw * bh)
        val aspect = max(bw, bh).toFloat() / min(bw, bh)
        val treatment = if (coverage >= FILL_COVERAGE && aspect <= MAX_ASPECT) Treatment.FILL else Treatment.PLATE
        return Legacy(l, t, r + 1, b + 1, coverage, treatment, edge.color(), mean.color())
    }

    /**
     * Where [fit]'s box goes in the mask's square box ([boxLeft], [boxTop], side [side]): left, top, right, bottom.
     * [Treatment.FILL] covers the box, [Treatment.PLATE] fits [PLATE_GLYPH] of it; both centred.
     */
    fun destination(fit: Legacy, boxLeft: Float, boxTop: Float, side: Float): FloatArray {
        val k = when (fit.treatment) {
            Treatment.FILL -> max(side / fit.width, side / fit.height)
            Treatment.PLATE -> PLATE_GLYPH * side / max(fit.width, fit.height)
        }
        val w = fit.width * k
        val h = fit.height * k
        val l = boxLeft + (side - w) / 2f
        val t = boxTop + (side - h) / 2f
        return floatArrayOf(l, t, l + w, t + h)
    }

    /** The colour behind [fit] in the mask: its edge's for a FILL icon, a plate for a glyph. */
    fun backdrop(fit: Legacy): Int = when (fit.treatment) {
        Treatment.FILL -> fit.edgeColor
        Treatment.PLATE -> plateColor(fit.meanColor)
    }

    /** A plate for a glyph of mean colour [mean]: its hue, saturation at most 0.6, brightness 0.42; never white or black. */
    fun plateColor(mean: Int): Int {
        val r = red(mean) / 255f
        val g = green(mean) / 255f
        val b = blue(mean) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val s = if (mx <= 0f) 0f else min(0.6f, (mx - mn) / mx)
        val hue = when {
            mx == mn -> 0f
            mx == r -> 60f * (((g - b) / (mx - mn)).mod(6f))
            mx == g -> 60f * ((b - r) / (mx - mn) + 2f)
            else -> 60f * ((r - g) / (mx - mn) + 4f)
        }
        return hsv(hue, s, PLATE_VALUE)
    }

    const val PLATE_VALUE = 0.42f

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f).mod(2f) - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(255, ((r + m) * 255f + 0.5f).toInt(), ((g + m) * 255f + 0.5f).toInt(), ((b + m) * 255f + 0.5f).toInt())
    }

    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private class ColorSum {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        fun add(c: Int) {
            r += red(c)
            g += green(c)
            b += blue(c)
            n++
        }
        fun color(): Int = if (n == 0) argb(255, 0, 0, 0) else argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
