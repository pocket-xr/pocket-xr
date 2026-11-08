package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.3 (John Davis, with a Bluetooth mouse: "would it be possible to scroll the screens using the mousewheel when focus /
 * cursor is away from the screens? right now when viewing screens linear there is no way to scroll to see them with the
 * mouse. Also maybe 2 finger trackpad to scroll the screens when cursor is underneath the screens"): a scroll over empty
 * space turns the view, which slides the row of windows past. Only sideways travel turned it, so a mouse wheel (up and
 * down only) and an up or down two finger scroll did nothing there. Up and down now turn it too, the way a scroll moves a
 * page: a scroll back (wheel away, fingers down) moves the row as a drag to the right does, a scroll on (wheel toward
 * you, fingers up) as a drag to the left.
 */
object OffWindowScroll {
    /**
     * A pointer scroll's travel ([dx], [dy] finger pixels since its start, y down: a wheel turned away is positive [dy])
     * as sideways travel for the view's turn.
     */
    fun along(dx: Float, dy: Float): Float = dx + dy

    /** The trackpad's two finger travel ([dx], [dy] pad units since the start, [dy] positive with the fingers going up). */
    fun padAlong(dx: Float, dy: Float): Float = dx - dy
}
