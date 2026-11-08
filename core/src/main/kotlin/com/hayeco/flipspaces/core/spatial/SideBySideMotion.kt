package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.3 (issue 58: "while I move my head or the cursor, the picture is 2D, and when I'm still and the cursor is hidden, it
 * goes back to 3D"): the optional 2D while moving for [SideBySide] windows, off by default (some like it, some find the
 * change distracting). While it is on, a side by side window shows its left half to both eyes, a plain 2D picture, from
 * the moment the head turns or the cursor shows; it goes back to 3D once the head has been still for [STILL_MS] with the
 * cursor hidden. Flat at once and 3D only after a still spell, so the picture does not flicker on small movements.
 */
class SideBySideMotion {
    private var stillSinceMs = Long.MIN_VALUE
    var flat = false
        private set

    /** The head turning at [turnDegS] and the cursor [cursorShown] at [nowMs]; whether side by side windows draw flat. */
    fun update(turnDegS: Float, cursorShown: Boolean, nowMs: Long): Boolean {
        val moving = cursorShown || !(turnDegS < MOVING_DEG_S)
        if (moving) {
            flat = true
            stillSinceMs = Long.MIN_VALUE
        } else {
            if (stillSinceMs == Long.MIN_VALUE) stillSinceMs = nowMs
            if (nowMs - stillSinceMs >= STILL_MS) flat = false
        }
        return flat
    }

    companion object {
        /** A head turning this fast or faster is moving; slower is the small sway of a head held still. */
        const val MOVING_DEG_S = 12f

        /** How long the head must be still, the cursor hidden, before the 3D comes back. */
        const val STILL_MS = 600L

        const val MENU_ROW = "2D while moving"
        const val MENU_SUBTITLE = "Side by side videos show flat while your head or the cursor moves, then 3D again"
    }
}
