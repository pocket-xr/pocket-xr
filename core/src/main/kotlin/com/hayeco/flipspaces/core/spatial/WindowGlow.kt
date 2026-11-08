package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.Look
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1.2.2 (a user: "maybe the ability to reduce opacity or intensity of the blue drop shadow around the windows"): Spatial
 * windows' Window glow setting, the strength of the accent glow round the focused window, from off (0 %) to the look it
 * always had (100 %, the default). It scales the glow's opacity only; its width stays. Mike also wanted the glow's colour
 * to be a choice: a preset swatch ([PRESETS], the accent blue first and the default) or a custom colour ([custom]).
 */
object WindowGlow {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = MAX_PERCENT

    /** The glow's colour unless changed: the accent blue it always had. */
    const val DEFAULT_COLOR = Look.ACCENT

    /** A named glow colour, opaque ARGB. */
    data class Swatch(val name: String, val argb: Int)

    /** The preset glow colours, the default blue first. Bright, since the glasses show only light. */
    val PRESETS = listOf(
        Swatch("Blue", DEFAULT_COLOR),
        Swatch("White", 0xFFFFFFFF.toInt()),
        Swatch("Purple", 0xFFA67CFF.toInt()),
        Swatch("Pink", 0xFFFF6FB7.toInt()),
        Swatch("Green", 0xFF4FE08A.toInt()),
        Swatch("Amber", 0xFFFFB238.toInt()),
        Swatch("Red", 0xFFFF5C5C.toInt()),
    )

    /** [percent] kept within off and full. */
    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** The factor the glow's opacity is multiplied by at [percent]: 0 off, 1 the full look. */
    fun scale(percent: Int): Float = clamp(percent) / MAX_PERCENT.toFloat()

    /** The glow's opacity [alpha] at [percent], within 0 and 1. */
    fun alpha(alpha: Float, percent: Int): Float = (alpha * scale(percent)).coerceIn(0f, 1f)

    /** Whether the glow is drawn at all at [percent]. */
    fun shown(percent: Int): Boolean = clamp(percent) > MIN_PERCENT

    /** [argb] as a glow colour: always opaque, since the intensity alone sets how strong the glow is. */
    fun color(argb: Int): Int = argb or OPAQUE

    /** The preset [argb] is, or null for a custom colour. */
    fun preset(argb: Int): Swatch? = PRESETS.firstOrNull { it.argb == color(argb) }

    /** The colour's name in Settings: the preset's, or Custom. */
    fun name(argb: Int): String = preset(argb)?.name ?: "Custom"

    /**
     * The glow's straight RGBA for the renderer: [argb]'s colour (made opaque) at opacity [alpha] scaled by [percent].
     * At the defaults this is exactly the accent at [alpha], the look the glow always had.
     */
    fun rgba(argb: Int, alpha: Float, percent: Int): FloatArray = Look.rgba(color(argb), alpha(alpha, percent))

    /**
     * A custom glow colour at [hue] (0 to 360 degrees) and [saturation] (0 white to 1 the full hue), always at full
     * brightness: the glasses show only light, so a dark glow would not show.
     */
    fun custom(hue: Float, saturation: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val c = s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = 1f - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return OPAQUE or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /** [argb]'s hue (0 to 360 degrees) and saturation (0 to 1), so the custom sliders start at the saved colour. */
    fun hueSaturation(argb: Int): Pair<Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (max <= 0f || d <= 0f) return 0f to 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return ((hue + 360f) % 360f) to d / max
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
