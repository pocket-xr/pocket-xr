package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.UiText

/**
 * 1.2.3 (issue 52: "I'd like to put the phone in my pocket and walk around with the glasses on. Turning the phone's screen
 * off also turns off the glasses. Leaving it on means accidental touches, battery drain and burn-in"): Pocket mode, from
 * the trackpad's Menu. The trackpad's lock overlay comes up at once, all black, at the screen's lowest brightness, with
 * the screen kept on so the glasses keep their picture. Touches are blocked; the same hold as the lock's leaves it. On a
 * phone with an OLED panel black pixels are off, so it draws next to no power and nothing burns in.
 *
 * Only the lock spot shows, faintly, so it can be found; the hint and the glow come up while it is held.
 */
object PocketMode {
    /** The window's brightness in Pocket mode: the lowest the screen goes (Android's 0). */
    const val BRIGHTNESS = 0f

    /** The window's brightness otherwise: the phone's own setting (Android's "no override"). */
    const val NO_OVERRIDE = -1f

    /** The lock spot while nothing touches it: just enough to find in a dark room. */
    const val IDLE_ICON_ALPHA = 0.08f

    /** The lock icon's opacity for the hold's [progress] (0 to 1); in [pocket] mode faint until pressed. */
    fun iconAlpha(pocket: Boolean, progress: Float, pressed: Boolean): Float = when {
        !pocket -> 0.6f + 0.4f * progress
        !pressed && progress <= 0f -> IDLE_ICON_ALPHA
        else -> 0.3f + 0.7f * progress
    }

    /** Whether the hint and the glow show: always on the lock, only while held in [pocket] mode. */
    fun showsHint(pocket: Boolean, pressed: Boolean): Boolean = !pocket || pressed

    /** The hint under the lock spot. */
    fun hint(pocket: Boolean): String =
        if (pocket) UiText.get("core_pocket_hold_to_leave", "Hold to leave Pocket mode") else UiText.get("core_pocket_hold_to_unlock", "Hold to unlock")

    val MENU_ROW: String get() = UiText.get("core_pocket_mode", "Pocket mode")
    val MENU_SUBTITLE: String get() = UiText.get("core_pocket_mode_subtitle", "A black, locked phone screen. The glasses keep running. Hold the lock to leave.")
}
