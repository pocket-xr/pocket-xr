package com.hayeco.flipspaces.core.spatial

import kotlin.math.sqrt

/**
 * A Joy-Con stick for a game. Joy-Con sticks rest a little off centre, which a game read as a slow walk or turn. A round
 * dead zone takes that out, and the rest of the travel is stretched back to the full range, so the edge still reads 1
 * and a small push still starts small.
 */
object JoyConStick {
    const val DEAD_ZONE = 0.12f
    const val FULL = 0.92f

    /** The stick's [x] and [y] (each -1 to 1) shaped for a game. */
    fun shape(x: Float, y: Float): Pair<Float, Float> {
        val r = sqrt(x * x + y * y)
        if (r <= DEAD_ZONE) return 0f to 0f
        val scaled = ((r - DEAD_ZONE) / (FULL - DEAD_ZONE)).coerceAtMost(1f)
        return (x / r * scaled) to (y / r * scaled)
    }

    /** The thumb is on the stick: pushed past the dead zone. */
    fun touched(x: Float, y: Float): Boolean = x * x + y * y > DEAD_ZONE * DEAD_ZONE
}
