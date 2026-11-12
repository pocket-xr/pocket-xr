package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * 1.1.15 (tester John Davis: "Is there a way to add a "lock screens" option? Sometimes the cursor being pressed too long
 * resizes the screens"): Lock screens. While on, no window moves or resizes from the pointer: no Move pill or grab band
 * pick up, no resize handle, no two hand stretch or scale, no window buttons (Move, Resize, Further, Closer, Curve), no
 * Reset, Arrange or Adjust all. Clicks, scrolls, zooms and typing still reach the apps, and Close, Back, Refresh, Switch
 * app, Wide view, Fit video and Immersive still work. The setting is saved, so it is still on after a restart.
 */
object WindowLock {
    /** The Menu row's and the quick toggle's name. */
    val LABEL: String get() = UiText.get("spatial_lock_label", "Lock screens")

    /** The quick toggle's short label under its icon. */
    val SHORT_LABEL: String get() = UiText.get("spatial_lock_short", "Lock")
    val SHORT_LABEL_ON: String get() = UiText.get("spatial_lock_short_on", "Locked")

    /** Said on the glasses when a move or resize is tried while locked. */
    val NOTICE: String get() = UiText.get("spatial_lock_notice", "Screens are locked. Unlock them from the Lock button or the Menu.")

    /** The Menu row's subtitle. */
    val SUBTITLE: String get() = UiText.get("spatial_lock_subtitle", "Windows stay where they are. Clicks, scrolls and typing still work.")

    /** The saved preference's key (SharedPreferences "space"). */
    const val KEY = "windows_locked_1115"

    fun rowLabel(locked: Boolean): String = if (locked) UiText.get("spatial_lock_row_on", "Lock screens: ON") else UiText.get("spatial_lock_row_off", "Lock screens: OFF")

    /** What a screen reader says for the quick toggle. */
    fun description(locked: Boolean): String = if (locked) UiText.get("spatial_lock_description_on", "Lock screens, on. Windows can't be moved or resized. Tap to unlock.") else UiText.get("spatial_lock_description_off", "Lock screens, off. Tap to lock every window in place.")

    /**
     * What a hit is while [locked]: the resize handle's part inside the window is the window's content, its part outside
     * and the grab band round the window are nothing, so a press there reaches the app or nothing, never a resize or move.
     */
    fun view(hit: PanelHit?, locked: Boolean): PanelHit? {
        if (!locked || hit == null) return hit
        return when {
            hit.zone == PanelZone.CORNER -> if (hit.u in 0f..1f && hit.v in 0f..1f) hit.copy(zone = PanelZone.BODY) else null
            hit.zone == PanelZone.TITLE_BAR && hit.frame -> null
            else -> hit
        }
    }
}

/**
 * 1.1.15 (John Davis: "the bottom right blue window pull is sorta impacting page elements ( buttons ). If there is a
 * button on bottom right hovering over it triggers the window resize"): the resize handle starts a resize only after a
 * clear drag: held at least [HOLD_MS] and moved at least [MOVE_DEG] from where it was pressed. A hover does nothing, and
 * a press let go before that is a plain click on the app at that spot when it is on the window ([CornerDrag.clickAt]).
 * The handle's hit area is outside the window, plus only [PanelHitTester.CORNER_EDGE_DEG] inside its very edge.
 */
object CornerDrag {
    const val HOLD_MS = 150L
    const val MOVE_DEG = 1.0f

    /** True once a press on the handle, held [heldMs] and moved [movedDeg], is a resize. */
    fun starts(heldMs: Long, movedDeg: Float): Boolean = heldMs >= HOLD_MS && movedDeg >= MOVE_DEG

    /**
     * Where a press on the handle at [u], [v] clicks the app: on the window or within [marginU], [marginV] of its edge
     * (clamped onto it), else null (a press out past the window clicks nothing).
     */
    fun clickAt(u: Float, v: Float, marginU: Float, marginV: Float): Pair<Float, Float>? {
        if (u < -marginU || u > 1f + marginU || v < -marginV || v > 1f + marginV) return null
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }
}
