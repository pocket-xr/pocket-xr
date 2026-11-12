package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 0.5.27: whether the side by side late warp runs, with a hold. After the
 * glasses dropped out of their warp, 0.5.26's late warp flipped on and off 10 times in 0.17 s: each change reset the
 * late draw, which made it unusable for the next frame, which turned the warp off again. A change that comes only from
 * the late draw ([flakyWhy], turning off because of it or on again after it) must hold for [holdFrames] frames in a
 * row; every other reason (2D, the setting, no head tracking, the glasses' own warp) changes it at once.
 */
class LateWarpGate(val flakyWhy: String, val holdFrames: Int = HOLD_FRAMES) {
    /** Whether the late warp runs. */
    var on = false
        private set

    /** Why it is off (null while on, or before the first frame). */
    var why: String? = null
        private set

    private var pending = 0

    /** This frame wants the warp off for [wantWhy] (null: on). True when [on] or [why] changed, for the log. */
    fun update(wantWhy: String?): Boolean {
        val wantOn = wantWhy == null
        if (wantOn == on) {
            pending = 0
            if (!on && wantWhy != why) {
                why = wantWhy
                return true
            }
            return false
        }
        val flaky = wantWhy == flakyWhy || (wantOn && why == flakyWhy)
        if (flaky && ++pending < holdFrames) return false
        pending = 0
        on = wantOn
        why = wantWhy
        return true
    }

    companion object {
        const val HOLD_FRAMES = 5
    }
}

/**
 * 0.5.27 (the SBS left side looked cut off, while the phone sends both halves full and
 * symmetric to the pixel): the edge check, a Menu test that sends nothing. On each eye's picture it draws a 1 px white
 * frame on the four edges, a cross at the centre and a tick every [TICK_DEG] degrees out from the centre along all four
 * edges, longer every [LONG_EVERY]th (each 10 degrees). With one eye closed at a time, Mike counts the last tick he
 * sees on each side: if the left eye's left ticks are missing and the right eye's right ones are there, the glasses
 * place the left eye's screen off centre, and by how many degrees.
 */
object EdgeCheck {
    const val TICK_DEG = 2f
    const val LONG_EVERY = 5
    const val TICK_PX = 14
    const val LONG_TICK_PX = 36
    const val CROSS_PX = 40

    /** A white rectangle, pixels from the eye picture's top left. */
    data class Mark(val x: Int, val y: Int, val w: Int, val h: Int)

    /** The ticks' angles out from the centre that fit in a half of [tanHalf] (0 first, then 2, 4, ... degrees). */
    fun tickAngles(tanHalf: Float): List<Float> {
        if (!(tanHalf > 0f)) return emptyList()
        val most = atan(tanHalf) * RAD
        return (0..floor(most / TICK_DEG + 1e-4f).toInt()).map { it * TICK_DEG }
    }

    /** Where an angle [deg] out from the centre lands across [sizePx] pixels spanning [tanHalf] each side. */
    fun pixelAt(deg: Float, sizePx: Int, tanHalf: Float): Int =
        (sizePx / 2f + tan(deg * DEG) / tanHalf * sizePx / 2f).roundToInt().coerceIn(0, sizePx - 1)

    /** Every mark for one eye's [widthPx] by [heightPx] picture, which shows [tanHalfX] by [tanHalfY] each side. */
    fun marks(widthPx: Int, heightPx: Int, tanHalfX: Float, tanHalfY: Float): List<Mark> {
        if (widthPx <= 1 || heightPx <= 1) return emptyList()
        val out = ArrayList<Mark>()
        // The frame.
        out += Mark(0, 0, widthPx, 1)
        out += Mark(0, heightPx - 1, widthPx, 1)
        out += Mark(0, 0, 1, heightPx)
        out += Mark(widthPx - 1, 0, 1, heightPx)
        // The centre cross.
        out += Mark(widthPx / 2 - CROSS_PX / 2, heightPx / 2, CROSS_PX, 1)
        out += Mark(widthPx / 2, heightPx / 2 - CROSS_PX / 2, 1, CROSS_PX)
        // Ticks along the top and bottom edges (across), and the left and right edges (up and down).
        for ((i, a) in tickAngles(tanHalfX).withIndex()) {
            val len = if (i % LONG_EVERY == 0) LONG_TICK_PX else TICK_PX
            for (side in if (a == 0f) listOf(1f) else listOf(-1f, 1f)) {
                val x = pixelAt(side * a, widthPx, tanHalfX)
                out += Mark(x, 0, 1, len)
                out += Mark(x, heightPx - len, 1, len)
            }
        }
        for ((i, a) in tickAngles(tanHalfY).withIndex()) {
            val len = if (i % LONG_EVERY == 0) LONG_TICK_PX else TICK_PX
            for (side in if (a == 0f) listOf(1f) else listOf(-1f, 1f)) {
                val y = pixelAt(side * a, heightPx, tanHalfY)
                out += Mark(0, y, len, 1)
                out += Mark(widthPx - len, y, len, 1)
            }
        }
        return out
    }

    /** The log line: what each eye shows and what to look for. */
    fun line(widthPx: Int, heightPx: Int, tanHalfX: Float, tanHalfY: Float, eyes: Int): String {
        val ax = tickAngles(tanHalfX)
        val ay = tickAngles(tanHalfY)
        return "edge check on: on each of $eyes eye picture${if (eyes == 1) "" else "s"} (${widthPx}x$heightPx), a 1 px frame, a centre cross and a tick every %.0f degrees (long every %.0f), out to %.0f degrees left and right and %.0f up and down (the edge itself at %.1f and %.1f); with one eye closed, the last tick seen on each side says how much of that eye's picture the glasses show".format(
            java.util.Locale.ROOT, TICK_DEG, TICK_DEG * LONG_EVERY, ax.lastOrNull() ?: 0f, ay.lastOrNull() ?: 0f, atan(tanHalfX) * RAD, atan(tanHalfY) * RAD,
        ) + (if (eyes > 1) "; plus a big L and R in each eye's half and numbered pixel ticks every $PX_STEP px from each edge in to $PX_RANGE px (the first number seen is the pixels that eye loses on that side)" else "")
    }

    // ---- 0.5.27, Mike: read exactly how many pixels each eye loses on each side ----

    /** Pixel ticks every [PX_STEP] from each edge inwards, out to [PX_RANGE], longer every 100. */
    const val PX_STEP = 20
    const val PX_RANGE = 200
    const val PX_TICK = 20
    const val PX_LONG_TICK = 40

    /** The rulers sit this far from the centre: the across one below the cross, the up and down one right of it. */
    const val RULER_GAP = 80

    /** The numbers' and the eye letter's block font: 3 by 5 cells, each [DIGIT_SCALE] or [LABEL_SCALE] pixels. */
    const val DIGIT_SCALE = 3
    const val LABEL_SCALE = 24

    private val GLYPHS = mapOf(
        '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
        '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001001001001",
        '8' to "111101111101111", '9' to "111101111001111", 'L' to "100100100100111", 'R' to "110101110101101",
    )

    /** How wide [s] is in the block font at [scale]. */
    fun textWidth(s: String, scale: Int): Int = if (s.isEmpty()) 0 else s.length * 3 * scale + (s.length - 1) * scale

    /**
     * [s] in the block font, its top left at [x], [y], as white rectangles (each cell row's runs, merged downwards).
     * 0.5.60: [glyphs] for another set of 3 by 5 glyphs (the test card's letters).
     */
    fun text(s: String, x: Int, y: Int, scale: Int, glyphs: Map<Char, String> = GLYPHS): List<Mark> {
        val out = ArrayList<Mark>()
        for ((i, ch) in s.withIndex()) {
            val g = glyphs[ch] ?: continue
            val gx = x + i * 4 * scale
            // Runs per cell row (start, length), then a run repeated on the rows below grows down.
            val open = HashMap<Pair<Int, Int>, IntArray>()
            for (row in 0..4) {
                val runs = ArrayList<Pair<Int, Int>>()
                var col = 0
                while (col < 3) {
                    if (g[row * 3 + col] == '1') {
                        val start = col
                        while (col < 3 && g[row * 3 + col] == '1') col++
                        runs += start to col - start
                    } else col++
                }
                for (k in open.keys.toList()) if (k !in runs) open.remove(k)?.let { out += Mark(gx + k.first * scale, y + it[0] * scale, k.second * scale, it[1] * scale) }
                for (r in runs) open.getOrPut(r) { intArrayOf(row, 0) }[1]++
            }
            for ((k, v) in open) out += Mark(gx + k.first * scale, y + v[0] * scale, k.second * scale, v[1] * scale)
        }
        return out
    }

    /**
     * The edge check's pixel marks on one eye's [widthPx] by [heightPx] half, in the half's own pixels whatever the
     * picture adjust: its 1 px border, the eye's letter [label] (L or R) big above the centre, and numbered ticks every
     * [PX_STEP] px in from each of the four edges (the across ruler below the centre, the up and down one right of it).
     * The first number Mike sees on a side is how many pixels that eye loses there.
     */
    fun pixelMarks(widthPx: Int, heightPx: Int, label: Char?): List<Mark> {
        if (widthPx <= 1 || heightPx <= 1) return emptyList()
        val out = ArrayList<Mark>()
        out += Mark(0, 0, widthPx, 1)
        out += Mark(0, heightPx - 1, widthPx, 1)
        out += Mark(0, 0, 1, heightPx)
        out += Mark(widthPx - 1, 0, 1, heightPx)
        if (label != null) {
            val s = label.toString()
            out += text(s, widthPx / 2 - textWidth(s, LABEL_SCALE) / 2, heightPx / 2 - RULER_GAP - 5 * LABEL_SCALE, LABEL_SCALE)
        }
        val digitH = 5 * DIGIT_SCALE
        // Across: ticks hang down from the ruler's line; numbers under them, every other one a row lower so they fit.
        val ry = (heightPx / 2 + RULER_GAP).coerceAtMost(heightPx - PX_LONG_TICK - 2 * digitH - 12)
        for ((i, k) in (0..PX_RANGE step PX_STEP).withIndex()) {
            if (k >= widthPx / 2) break
            val len = if (k % 100 == 0) PX_LONG_TICK else PX_TICK
            val ny = ry + PX_LONG_TICK + 4 + (i % 2) * (digitH + 4)
            val n = k.toString()
            out += Mark(k, ry, 1, len)
            out += text(n, k + 3, ny, DIGIT_SCALE)
            val xr = widthPx - 1 - k
            out += Mark(xr, ry, 1, len)
            out += text(n, xr - 2 - textWidth(n, DIGIT_SCALE), ny, DIGIT_SCALE)
        }
        // Up and down: ticks reach right from the ruler's line; numbers beside them, under the top ones, over the bottom ones.
        val rx = (widthPx / 2 + RULER_GAP).coerceAtMost(widthPx - PX_LONG_TICK - textWidth("000", DIGIT_SCALE) - 8)
        for (k in 0..PX_RANGE step PX_STEP) {
            if (k >= heightPx / 2) break
            val len = if (k % 100 == 0) PX_LONG_TICK else PX_TICK
            val n = k.toString()
            out += Mark(rx, k, len, 1)
            out += text(n, rx + PX_LONG_TICK + 4, k + 2, DIGIT_SCALE)
            val yb = heightPx - 1 - k
            out += Mark(rx, yb, len, 1)
            out += text(n, rx + PX_LONG_TICK + 4, yb - 2 - digitH, DIGIT_SCALE)
        }
        return out
    }

    /** [m] cut to a [widthPx] by [heightPx] half; null when nothing of it is inside. */
    fun clip(m: Mark, widthPx: Int, heightPx: Int): Mark? {
        val x0 = maxOf(m.x, 0)
        val y0 = maxOf(m.y, 0)
        val x1 = minOf(m.x + m.w, widthPx)
        val y1 = minOf(m.y + m.h, heightPx)
        return if (x1 > x0 && y1 > y0) Mark(x0, y0, x1 - x0, y1 - y0) else null
    }

    /**
     * The degree marks ([marks]) on the adjusted picture: drawn on the whole moved and scaled picture ([EyePlacement]'s
     * full rectangle, [left], [top], [w] by [h] in the half), then cut to the [halfW] by [halfH] half, so they keep
     * marking the angles the picture really shows.
     */
    fun adjustedMarks(halfW: Int, halfH: Int, left: Int, top: Int, w: Int, h: Int, tanHalfX: Float, tanHalfY: Float): List<Mark> =
        marks(w, h, tanHalfX, tanHalfY).mapNotNull { clip(Mark(it.x + left, it.y + top, it.w, it.h), halfW, halfH) }
}

/**
 * 0.5.27 (Mike: "full SBS gives the full field of view top, bottom and right, 2D doesn't"):
 * what to say on the glasses about their field of view, once a session and mode. Nothing is sent to the glasses; each
 * line points at their own menu.
 *
 * - In their 2D mode: the picture is the glasses' 2D screen (80 % on Mike's Aura). The full view and the steadiest
 *   picture are their 3D mode ("2D in 3D", the same picture in both eyes at 54.5 by 32.3 degrees with the phone's late
 *   warp) at Display size 100 %.
 * - In their 3D mode: under 100 %, or 100 % only as the default (not yet set by Mike or read from their log), the
 *   hint to set Display size 100 % on them, so the phone's projection and their screen agree.
 */
object FullViewHint {
    val TWO_D: String get() = UiText.get("spatial_full_view_two_d", "Full view: glasses 3D mode, Display size 100 %")

    /** The hint for the glasses' mode ([sbs]) and the screen in use, or null when there is nothing to say. */
    fun hint(sbs: Boolean, screen: GlassesScreen, confirmed: Boolean): String? = when {
        !sbs -> TWO_D
        screen.sizeFactor < GlassesScreen.FULL - 1e-3f -> UiText.format("spatial_full_view_size", "Glasses screen %1\$.0f %%: set Display size 100 %% on them", screen.sizeFactor * 100)
        !confirmed -> UiText.get("spatial_full_view_unconfirmed", "For the full view: Display size 100 % on the glasses")
        else -> null
    }
}
