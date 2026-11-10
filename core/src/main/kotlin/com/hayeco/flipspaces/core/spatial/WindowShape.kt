package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 1.3.3 (a tester on Steady mode: "the windows are super wide now and I find the current method to resize them a bit
 * clunky... Maybe some preset aspect ratios would be good?"): one tap gives the focused window a common shape. The window
 * keeps about the same size (its area), so it changes shape without jumping bigger or smaller, within [PanelLimits].
 */
object WindowShape {
    enum class Preset(val label: String, val small: String, val ratio: Float) {
        WIDE("16:9", "Video", 16f / 9f),
        LAPTOP("3:2", "Laptop", 3f / 2f),
        TABLET("4:3", "Tablet", 4f / 3f),
        SQUARE("1:1", "Square", 1f),
        PHONE("9:16", "Phone", 9f / 16f),
    }

    /** How close a window's shape must be to a preset's to count as it. */
    const val MATCH = 0.03f

    /** The preset [aspect] (width over height) already is, or null. */
    fun current(aspect: Float): Preset? =
        Preset.entries.firstOrNull { abs(aspect / it.ratio - 1f) <= MATCH }

    /**
     * [panel] in [ratio]'s shape, measured as [PanelDisplaySize.aspect] measures it (the app's display gets this shape),
     * about as big as it was. Twice, since a curved window's width isn't quite linear in its angle.
     */
    fun shaped(panel: SpacePanel, ratio: Float): SpacePanel {
        var p = panel
        repeat(3) {
            val k = sqrt(ratio / PanelDisplaySize.aspect(p))
            var w = p.widthDeg * k
            var h = p.heightDeg / k
            // Inside the limits, keeping the shape: a side past its limit brings the other one down with it.
            val down = maxOf(w / PanelLimits.MAX_WIDTH_DEG, h / PanelLimits.MAX_HEIGHT_DEG, 1f)
            w /= down
            h /= down
            val up = maxOf(PanelLimits.MIN_SIZE_DEG / w, PanelLimits.MIN_SIZE_DEG / h, 1f)
            w *= up
            h *= up
            p = p.copy(widthDeg = w, heightDeg = h)
        }
        return p.sizedByUser()
    }
}
