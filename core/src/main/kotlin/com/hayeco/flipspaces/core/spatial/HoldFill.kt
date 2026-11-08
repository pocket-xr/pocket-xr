package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.3 (issue 53: "It wasn't clear how the bottom-right handle works. Pressing it seems to do nothing"): a window's resize
 * handle and its grab band act on a held press, which on the trackpad is a still finger held half a second. Over either of
 * them the cursor fills from its middle while the finger is held, so the wait shows. A tap is over long before the fill
 * starts ([START]), so plain clicks do not flash it.
 */
object HoldFill {
    /** The share of the hold before the fill starts to show. */
    const val START = 0.2f

    /** Whether a hold on [zone] (or on the grab band, [frame]) does something the cursor should show. */
    fun applies(zone: PanelZone?, frame: Boolean): Boolean = frame || zone == PanelZone.CORNER

    /** The fill, 0 to 1, for the trackpad's hold [progress] (0 to 1) over [zone] / [frame]; 0 while nothing shows. */
    fun of(progress: Float, zone: PanelZone?, frame: Boolean): Float {
        if (!applies(zone, frame) || progress <= START) return 0f
        return ((progress - START) / (1f - START)).coerceIn(0f, 1f)
    }
}
