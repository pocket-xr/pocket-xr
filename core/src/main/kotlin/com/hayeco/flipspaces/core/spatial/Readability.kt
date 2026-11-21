package com.hayeco.flipspaces.core.spatial

import kotlin.math.roundToInt

/**
 * Readability (a Mac tester on an XREAL Air found text too small, and the other Pocket XR versions add the same switch):
 * larger text in every window, for 1080p glasses. One switch in the Space settings, off by default.
 *
 * On the Mac it is a 1440 x 810 screen in place of 1920 x 1080, so everything on it is 1920 / 1440 = 4/3 the size. Here
 * each window is a virtual display, already drawn at about one window pixel per glasses pixel
 * ([PanelLimits.PIXELS_PER_DEGREE], 44 a degree; a 1080p pair at about 46 degrees has about 42), so the same step is the
 * density: [FACTOR] times what the window would otherwise have (after the app's own [AppTextSize]). A default window goes
 * from 330 to 440 dpi, 853 x 480 to 640 x 360 dp. The window's size, place and pixels do not change. A narrower window
 * was not taken: on Android it makes text smaller, or runs into the 320 dp floor.
 *
 * The switch writes nothing but itself: no window, layout or per-app text size, and the rule keeps no state, so off gives
 * every display back exactly the size and density it had.
 */
object Readability {
    const val FACTOR = 4f / 3f

    /**
     * The density for [dpi] on a [wp] by [hp] display with Readability [on]. Never squeezes the display under
     * [minSmallestDp] across its smallest side (unless [dpi] already did), and stays within [PanelDisplaySize]'s range.
     */
    fun density(dpi: Int, on: Boolean, wp: Int, hp: Int, minSmallestDp: Float = PanelDisplaySize.MIN_SMALLEST_DP): Int {
        if (!on) return dpi
        val scaled = (dpi * FACTOR).roundToInt()
        val floor = maxOf(dpi, (minOf(wp, hp) * 160f / minSmallestDp).toInt())
        return minOf(scaled, floor).coerceIn(PanelDisplaySize.LOWEST_DPI, PanelDisplaySize.HIGHEST_DPI)
    }

    /** The log line for one window when the switch changes. */
    fun line(on: Boolean, panelId: Int, label: String, before: PanelDisplaySpec, after: PanelDisplaySpec): String =
        "readability ${if (on) "on" else "off"}: panel $panelId $label ${before.line()} to ${after.line()}"
}
