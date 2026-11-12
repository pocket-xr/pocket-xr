package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.roundToInt

/**
 * 1.2.3 (issue 57: "Text in windows is quite small for me ... To get the desktop layout in a browser (with tabs), I have
 * to make the window very large and then push it far away"): each app's own text size, a share of the density the usual
 * rule gives ([PanelDisplaySize.of]). Lower lays more content out on the same window (a browser's desktop layout), higher
 * makes text bigger. Set from the trackpad's Menu for the focused window, remembered per app ([AppTextSizeStore]), and
 * kept through resizes and restarts, as Fit video's density is.
 */
object AppTextSize {
    const val DEFAULT = 100
    const val MIN = 40
    const val MAX = 130
    const val STEP = 10

    /** [percent] moved [steps] steps of [STEP], within [MIN] to [MAX]. */
    fun step(percent: Int, steps: Int): Int = (percent + steps * STEP).coerceIn(MIN, MAX)

    /**
     * The density for [percent] of [dpi] on a [wp] by [hp] display. Larger text never squeezes the display under
     * [minSmallestDp] across its smallest side (where apps lay out badly), unless the usual rule already did; the result
     * stays within [PanelDisplaySize]'s range.
     */
    fun density(dpi: Int, percent: Int, wp: Int, hp: Int, minSmallestDp: Float = PanelDisplaySize.MIN_SMALLEST_DP): Int {
        if (percent == DEFAULT) return dpi
        var scaled = (dpi * percent.coerceIn(MIN, MAX) / 100f).roundToInt()
        if (percent > DEFAULT) scaled = minOf(scaled, maxOf(dpi, (minOf(wp, hp) * 160f / minSmallestDp).toInt()))
        return scaled.coerceIn(PanelDisplaySize.LOWEST_DPI, PanelDisplaySize.HIGHEST_DPI)
    }

    /** How the Menu and the window's notice say it. */
    fun label(percent: Int): String = UiText.format("spatial_text_size_percent", "Text size %1\$d %%", percent)
}

/** 1.2.3 (issue 57): each app's [AppTextSize], one `package=percent` a line; an app at 100 % is not stored. */
class AppTextSizeStore(text: String? = null) {
    private val percent = LinkedHashMap<String, Int>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
            val pkg = line.substringBefore('=').trim()
            val v = line.substringAfter('=', "").trim().toIntOrNull()
            if (pkg.isNotEmpty() && v != null) set(pkg, v)
        }
    }

    fun percent(packageName: String): Int = percent[packageName] ?: AppTextSize.DEFAULT

    fun set(packageName: String, value: Int) {
        val v = value.coerceIn(AppTextSize.MIN, AppTextSize.MAX)
        if (v == AppTextSize.DEFAULT) percent.remove(packageName) else percent[packageName] = v
    }

    fun encode(): String = buildString { percent.forEach { (k, v) -> append(k).append('=').append(v).append('\n') } }
}
