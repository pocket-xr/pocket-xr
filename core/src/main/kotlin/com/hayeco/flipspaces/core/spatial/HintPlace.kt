package com.hayeco.flipspaces.core.spatial

/**
 * 1.6.35: where the Space's one line notices go (the 3D button's "Switch between 2D and 3D on the glasses themselves",
 * the Glasses warp switch, setup hints): head locked below the middle of the view, 0.3 down for 1 ahead (about
 * 16.7 degrees) as since 0.5.8, but never lower than [EDGE_MARGIN_DEG] above the view's bottom edge. On the VITURE Luma
 * (a view 25.8 degrees tall, 12.9 below the middle) the old place was out of sight.
 */
object HintPlace {
    const val BELOW_TAN = 0.3f
    const val EDGE_MARGIN_DEG = 3f

    /** The notice's drop below the middle, as the tangent of its angle, in a view [verticalFovDeg] tall. */
    fun belowTan(verticalFovDeg: Float): Float {
        if (verticalFovDeg <= 0f) return BELOW_TAN
        val most = Math.toRadians((verticalFovDeg / 2f - EDGE_MARGIN_DEG).toDouble()).coerceAtLeast(0.0)
        return minOf(BELOW_TAN, kotlin.math.tan(most).toFloat())
    }
}
