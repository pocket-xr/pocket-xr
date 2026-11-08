package com.hayeco.flipspaces.core.spatial

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A panel's virtual display: [widthPx] by [heightPx] at [dpi]. */
data class PanelDisplaySpec(val widthPx: Int, val heightPx: Int, val dpi: Int) {
    val widthDp: Float get() = widthPx * 160f / dpi
    val heightDp: Float get() = heightPx * 160f / dpi
    val smallestDp: Float get() = minOf(widthDp, heightDp)
    val landscape: Boolean get() = widthPx >= heightPx

    fun line(): String = "%dx%d at %d dpi (%.0fx%.0f dp, smallest width %.0f dp)".format(java.util.Locale.ROOT, widthPx, heightPx, dpi, widthDp, heightDp, smallestDp)
}

/**
 * 0.5.21 (Mike: "most apps don't resize when I change a window's size; they stay portrait"): the size of the virtual display behind a window, in width, height and density.
 *
 * What the device logs showed: Pocket XR did resize each display live, and Android gave the app every
 * new size as a landscape configuration. Apps that ask for portrait (Twitch at run time, another
 * app in its manifest) are letterboxed into a phone shaped column on a display that cannot
 * rotate, so resizing only grew or shrank the column ([WideView] is for those). Two Pocket XR
 * faults made every app worse, and this fixes both:
 *
 * 1. **Only Mike's own resizes lay the app out again.** The display followed the window's angular
 *    size, and Arrange (3.78 m, 0.72 times) shrank windows to the 12 degree minimum: 528 px, 256 dp,
 *    smaller than any phone. Now the app's size is its own ([SpacePanel.appWidthDp], set by a corner
 *    drag, a stretch or a scale, [SpacePanel.sizedByUser]); Arrange, Reset, push and pull and the wrap
 *    move and scale the picture only. The display takes the window's shape (its aspect) with the
 *    app's area, so a window squeezed by the 12 degree floor is not stretched.
 * 2. **Never under a phone.** The smallest side is kept at [MIN_SMALLEST_DP] (320 dp, Android's
 *    smallest phone class) or more: a smaller window gets more pixels and its picture is scaled down.
 *
 * Density: [PanelDisplays.DENSITY_DPI] (330), so an app lays out 1 dp to the same angle at every size.
 * A window too big for [PanelLimits.MAX_PX] a side or [PanelLimits.MAX_AREA_PX] keeps its dp size and
 * takes a lower density rather than fewer dp, so a big window gives the app the room it shows instead
 * of laying it out smaller. Each change of width, height or density reaches the app through
 * [PanelResizeGate] live during a drag (every 150 ms) and once at the release.
 *
 * 0.5.23: the display's pixels are the window's own and the density one continuous rule ([of]); [forDp]
 * is 0.5.22's rule, kept for the before and after tests.
 */
object PanelDisplaySize {
    const val MIN_SMALLEST_DP = 320f
    const val MIN_DPI = 160

    /** The app size a window's angular size gives: [PanelLimits.PIXELS_PER_DEGREE] at [PanelDisplays.DENSITY_DPI]. */
    fun naturalDp(panel: SpacePanel): Pair<Float, Float> {
        fun safe(deg: Float, fallback: Float) = if (deg.isFinite() && deg > 0f) deg else fallback
        val k = PanelLimits.PIXELS_PER_DEGREE * 160f / PanelDisplays.DENSITY_DPI
        return safe(panel.widthDeg, PanelLimits.DEFAULT_WIDTH_DEG) * k to safe(panel.heightDeg, PanelLimits.DEFAULT_HEIGHT_DEG) * k
    }

    /** The app's own size, dp: what Mike's last resize gave, else the window's angular size. */
    fun appDp(panel: SpacePanel): Pair<Float, Float> =
        if (panel.appWidthDp > 0f && panel.appHeightDp > 0f) panel.appWidthDp to panel.appHeightDp else naturalDp(panel)

    /**
     * The window's shape, width over height, in metres along its surface (the arc when curved): the
     * surface the app's picture is drawn on, so the picture is never stretched. 0.5.20 took the angles'
     * ratio, 1.78 for the default window whose surface is 1.83 wide for 1 tall, a 3 % stretch.
     */
    fun aspect(panel: SpacePanel): Float {
        val w = PanelCurve.arcWidthM(panel)
        val h = panel.heightM
        if (w.isFinite() && h.isFinite() && w > 0f && h > 0f) return w / h
        return PanelLimits.DEFAULT_WIDTH_DEG / PanelLimits.DEFAULT_HEIGHT_DEG
    }

    /**
     * The display for [panel]. 0.5.23 (Mike: "when I resize the window the app doesn't fit the resized window";
     * in vc43, within 7 s, display 89 went 2100x1058 at 260, 2560x1410 and 2370x1554 at 160, 2446x1502 at
     * 232 and 2084x1258 at 260 dpi, aspects that did not match the window): one rule at every size, Wide view on
     * or off.
     *
     * - **The pixels are the window's own** ([panelPx]): its angular area at [PanelLimits.PIXELS_PER_DEGREE] in
     *   the shape of its surface, scaled as a whole to fit the pixel limits, so the display's aspect is always
     *   the window's (to the pixel's rounding) and the picture fills it edge to edge. 0.5.22 floored the density
     *   at 160 and then clamped each side at 2560 on its own, which broke the aspect.
     * - **The density is one continuous rule** ([densityFor]): the app's size in dp ([appDp], which only Mike's
     *   resizes set) over those pixels. While Mike resizes, the dp follow the window, so the density stays at
     *   330 (Wide view off) or 260 (on), and goes down smoothly only where the pixel limits bind.
     *   Arrange, Reset and depth change the window's pixels and keep the app's dp, so they move the density
     *   smoothly too. The smallest width rules (320 dp, Wide view's 600 dp, 569 dp tall for a letterboxed
     *   portrait app) take a lower density, never more pixels. No branch, so no jump between 260, 232 and 160.
     */
    fun of(panel: SpacePanel, broad: Boolean = false, lockedPortrait: Boolean = false, videoSmallestDp: Float? = null, textPercent: Int = AppTextSize.DEFAULT): PanelDisplaySpec {
        val (wp, hp) = panelPx(panel)
        // 0.5.24: an app with its own density setting ([AppDensityStore], Fit video) is laid out at that smallest width,
        // a phone's, whatever its dp were and with Wide view on or off ([VideoFit]).
        if (videoSmallestDp != null) return PanelDisplaySpec(wp, hp, VideoFit.densityFor(wp, hp, videoSmallestDp))
        val (aw, ah) = appDp(panel)
        // Wide view: the same pixels at 260 dpi give 1.27 times the dp of 330.
        val k = if (broad) PanelDisplays.DENSITY_DPI.toFloat() / WideView.DPI else 1f
        val minSmallest = when {
            broad -> WideView.MIN_SMALLEST_DP
            // 0.5.22 (a corner drag squeezed Twitch, letterboxed, to a 204 dp column):
            // Android draws a portrait locked app in a phone shaped column as tall as the display, so the
            // display's height is kept at [LOCKED_MIN_HEIGHT_DP] or more and the column at 320 dp or more.
            lockedPortrait && wp > hp -> LOCKED_MIN_HEIGHT_DP
            else -> MIN_SMALLEST_DP
        }
        // 1.2.3 (issue 57): the app's own text size ([AppTextSize]) on top of the rule. Fit video's phone width above wins.
        return PanelDisplaySpec(wp, hp, AppTextSize.density(densityFor(wp, hp, aw * ah * k * k, minSmallest), textPercent, wp, hp, minSmallest))
    }

    /** 0.5.23: the lowest and highest density the rule gives (Android's ldpi and xxxhdpi). */
    const val LOWEST_DPI = 120
    const val HIGHEST_DPI = 640

    /**
     * 0.5.23: the window's pixels: its angular area at [PanelLimits.PIXELS_PER_DEGREE] shaped as its surface
     * ([aspect]), scaled as a whole (never one side alone) to within [PanelLimits.MAX_PX] a side and
     * [PanelLimits.MAX_AREA_PX], and to at least [PanelLimits.MIN_PX] a side; even, rounded down where a limit binds.
     *
     * 0.5.27 (Mike: Twitch no longer filled its window after 0.5.26's depth change): a window moved closer or further
     * keeps the area its pixels were sized at ([SpacePanel.displayAreaDeg2]) and takes its own shape, so its display
     * keeps its pixels and density (no relayout, no density change) and still has the window's aspect, edge to edge.
     */
    fun panelPx(panel: SpacePanel): Pair<Int, Int> {
        fun safe(deg: Float, fallback: Float) = if (deg.isFinite() && deg > 0f) deg else fallback
        val areaDeg2 = panel.displayAreaDeg2.takeIf { it.isFinite() && it > 0f }
            ?: (safe(panel.widthDeg, PanelLimits.DEFAULT_WIDTH_DEG) * safe(panel.heightDeg, PanelLimits.DEFAULT_HEIGHT_DEG))
        return pixelsFor(areaDeg2 * PanelLimits.PIXELS_PER_DEGREE * PanelLimits.PIXELS_PER_DEGREE, aspect(panel))
    }

    /** [area] pixels shaped [aspect], as [panelPx] scales them. */
    fun pixelsFor(area: Float, aspect: Float): Pair<Int, Int> {
        val r = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.1f, 10f) else 16f / 9f
        val a = if (area.isFinite() && area > 0f) area else 1760f * 990f
        val w = sqrt(a * r)
        val h = sqrt(a / r)
        val down = minOf(1f, PanelLimits.MAX_PX / maxOf(w, h), sqrt(PanelLimits.MAX_AREA_PX / (w * h)))
        val up = maxOf(1f, PanelLimits.MIN_PX / (minOf(w, h) * down))
        val s = down * up
        val capped = down < 1f
        fun even(v: Float): Int {
            val half = v * s / 2f
            return ((if (capped) floor(half).toInt() else half.roundToInt()) * 2).coerceIn(PanelLimits.MIN_PX, PanelLimits.MAX_PX)
        }
        return even(w) to even(h)
    }

    /**
     * 0.5.23: the density that lays [areaDp] square dp out on [wp] by [hp] pixels, lowered so the smallest side
     * has at least [minSmallestDp], within [LOWEST_DPI] to [HIGHEST_DPI]. Continuous in every input.
     */
    fun densityFor(wp: Int, hp: Int, areaDp: Float, minSmallestDp: Float): Int {
        val area = if (areaDp.isFinite() && areaDp > 0f) areaDp else 853f * 480f
        var dpi = 160f * sqrt(wp.toFloat() * hp / area)
        dpi = minOf(dpi, minOf(wp, hp) * 160f / minSmallestDp)
        return dpi.roundToInt().coerceIn(LOWEST_DPI, HIGHEST_DPI)
    }

    /**
     * 0.5.22: the narrowest phone column Android letterboxes a portrait app into is [MIN_SMALLEST_DP] wide at
     * 9 : 16 ([PHONE_ASPECT]; Twitch's column on Mike's panels was 568 by 996 px, 0.57), so 569 dp tall.
     */
    const val PHONE_ASPECT = 9f / 16f
    const val LOCKED_MIN_HEIGHT_DP = MIN_SMALLEST_DP / PHONE_ASPECT

    /** A display of [areaDp] square dp shaped [aspect] (width over height), at [baseDpi], its smallest side at least [minSmallestDp]. */
    fun forDp(areaDp: Float, aspect: Float, baseDpi: Int = PanelDisplays.DENSITY_DPI, minSmallestDp: Float = MIN_SMALLEST_DP): PanelDisplaySpec {
        val r = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.1f, 10f) else 16f / 9f
        val area = if (areaDp.isFinite() && areaDp > 0f) areaDp else 853f * 480f
        var w = sqrt(area * r)
        var h = sqrt(area / r)
        val smallest = minOf(w, h)
        if (smallest < minSmallestDp) {
            val k = minSmallestDp / smallest
            w *= k
            h *= k
        }
        var dpi = baseDpi.toFloat()
        val pw = w * dpi / 160f
        val ph = h * dpi / 160f
        val fit = minOf(1f, PanelLimits.MAX_PX / maxOf(pw, ph), sqrt(PanelLimits.MAX_AREA_PX / (pw * ph)))
        if (fit < 1f) dpi = maxOf(MIN_DPI.toFloat(), floor(dpi * fit))
        val dpiInt = dpi.roundToInt()
        // Even pixels, rounded down when the limits bind so rounding never goes over them.
        fun even(dp: Float): Int {
            val px = dp * dpiInt / 160f
            val half = if (fit < 1f) floor(px / 2f).toInt() else (px / 2f).roundToInt()
            return (half * 2).coerceIn(PanelLimits.MIN_PX, PanelLimits.MAX_PX)
        }
        var wp = even(w)
        var hp = even(h)
        // At the lowest density the pixels themselves are capped (the dp then shrink, as before 0.5.21).
        if (wp.toLong() * hp > PanelLimits.MAX_AREA_PX) {
            val s = sqrt(PanelLimits.MAX_AREA_PX / (wp.toFloat() * hp))
            wp = (floor(wp * s / 2f).toInt() * 2).coerceAtLeast(PanelLimits.MIN_PX)
            hp = (floor(hp * s / 2f).toInt() * 2).coerceAtLeast(PanelLimits.MIN_PX)
        }
        return PanelDisplaySpec(wp, hp, dpiInt)
    }

    /** 0.5.22's display for [panel], for the before and after tests. */
    fun v0522(panel: SpacePanel, broad: Boolean = false, lockedPortrait: Boolean = false): PanelDisplaySpec {
        val (aw, ah) = appDp(panel)
        return if (broad) {
            val k = PanelDisplays.DENSITY_DPI.toFloat() / WideView.DPI
            forDp(aw * ah * k * k, aspect(panel), WideView.DPI, WideView.MIN_SMALLEST_DP)
        } else if (lockedPortrait && aspect(panel) > 1f) {
            forDp(aw * ah, aspect(panel), minSmallestDp = LOCKED_MIN_HEIGHT_DP)
        } else {
            forDp(aw * ah, aspect(panel))
        }
    }
}

/**
 * 0.5.23 (Mike: the app must fill the resized window edge to edge): how a panel's picture maps onto its window.
 * The texture is the display's own size ([PanelDisplaySize.of]), so its UVs are the whole texture. While a resize
 * is on its way the picture drawn may still be the reader from before it (a size the window no longer has):
 * it is then cropped about its centre to the window's shape, never stretched, and still fills the window.
 */
object PanelUv {
    /** u0, v0, u1, v1 of a [srcW] by [srcH] picture for a window shaped [windowAspect] (width over height). */
    fun cover(srcW: Int, srcH: Int, windowAspect: Float): FloatArray {
        if (srcW <= 0 || srcH <= 0 || !windowAspect.isFinite() || windowAspect <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        val src = srcW.toFloat() / srcH
        // Within a pixel's rounding of the window's shape: the whole picture.
        if (kotlin.math.abs(src / windowAspect - 1f) <= 2f / minOf(srcW, srcH)) return floatArrayOf(0f, 0f, 1f, 1f)
        return if (src > windowAspect) {
            val w = windowAspect / src
            floatArrayOf((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = src / windowAspect
            floatArrayOf(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
    }

    /** The texture matrix (column major, as GL's) that samples [uv] across the window's 0 to 1. */
    fun matrix(uv: FloatArray): FloatArray {
        val su = uv[2] - uv[0]
        val sv = uv[3] - uv[1]
        return floatArrayOf(su, 0f, 0f, 0f, 0f, sv, 0f, 0f, 0f, 0f, 1f, 0f, uv[0], uv[1], 0f, 1f)
    }
}

/**
 * 0.5.23 (Mike: "a relayout on release if live resizing can't"): after a resize is released, whether the app's
 * own window took the display's size. Android reports the activity's configuration in `dumpsys activity
 * activities` (`CurrentConfiguration={... 260dpi ... mBounds=Rect(0, 0 - 2084, 1258) ...}`, read with
 * [AppShape]); an app that did not follow is nudged: its display goes 2 px shorter and back, which is two
 * configuration changes, at most [MAX_NUDGES] times a release.
 */
object PanelFit {
    const val MAX_NUDGES = 2

    /** Why the app [shape] does not fill [spec], or null when it does (or cannot be told). A letterboxed app is Android's choice, not a missed relayout. */
    fun misfit(spec: PanelDisplaySpec, shape: AppShape?): String? {
        if (shape == null || shape.letterboxed) return null
        val w = shape.boundsW ?: return null
        val h = shape.boundsH ?: return null
        val sizeOk = kotlin.math.abs(w - spec.widthPx) <= 2 && kotlin.math.abs(h - spec.heightPx) <= 2
        val dpiOk = shape.densityDpi == null || shape.densityDpi == spec.dpi
        if (sizeOk && dpiOk) return null
        return "the app is laid out at ${w}x$h${shape.densityDpi?.let { " at $it dpi" } ?: ""}, its display is ${spec.widthPx}x${spec.heightPx} at ${spec.dpi} dpi"
    }
}
