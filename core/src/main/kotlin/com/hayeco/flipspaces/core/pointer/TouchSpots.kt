package com.hayeco.flipspaces.core.pointer

/**
 * 1.2.3 (issue 59: "for games that only have on-screen controls, a phone button that taps or drags a chosen point in the
 * game's window"): the Game controller's Touch mode. Each control is set to a spot on the game's window ([u], [v], 0 to 1
 * across and down) by aiming the dot at the game's own button and holding the control ([SET_HOLD_MS]); remembered per
 * app ([TouchSpotStore]). Then a button touches its spot while held, and a stick touches its spot and drags from it by
 * its tilt, up to [STICK_REACH] of the window's height, as a thumb on the game's on-screen stick would.
 */
data class TouchSpot(val u: Float, val v: Float)

object TouchSpots {
    /** Holding a control this long while setting spots sets it to where the dot is. */
    const val SET_HOLD_MS = 800L

    /** How far a stick's touch moves at full tilt, as a share of the window's height. */
    const val STICK_REACH = 0.08f

    /**
     * The touches [s] makes on a window [aspect] wide for 1 tall, from [spots]: one per held control that has a spot,
     * a stick's moved by its tilt. Keyed by control, so each keeps its own pointer id while held.
     */
    fun touches(s: ControllerState, spots: Map<PadControl, TouchSpot>, aspect: Float): Map<PadControl, TouchSpot> {
        val out = LinkedHashMap<PadControl, TouchSpot>()
        for (c in s.buttons) spots[c]?.let { out[c] = it }
        fun stick(c: PadControl, x: Float, y: Float, held: Boolean) {
            val spot = spots[c] ?: return
            if (!held) return
            val a = if (aspect.isFinite() && aspect > 0f) aspect else 16f / 9f
            out[c] = TouchSpot((spot.u + x * STICK_REACH / a).coerceIn(0f, 1f), (spot.v + y * STICK_REACH).coerceIn(0f, 1f))
        }
        return out.also {
            stick(PadControl.LEFT_STICK, s.leftX, s.leftY, PadControl.LEFT_STICK in s.sticksHeld)
            stick(PadControl.RIGHT_STICK, s.rightX, s.rightY, PadControl.RIGHT_STICK in s.sticksHeld)
        }
    }
}

/** Each app's touch spots, one `package control u v` a line. */
class TouchSpotStore(text: String? = null) {
    private val spots = LinkedHashMap<String, MutableMap<PadControl, TouchSpot>>()

    init {
        text?.lineSequence()?.map { it.trim().split(' ') }?.filter { it.size == 4 }?.forEach { (pkg, c, u, v) ->
            val control = PadControl.entries.firstOrNull { it.name == c } ?: return@forEach
            val uu = u.toFloatOrNull() ?: return@forEach
            val vv = v.toFloatOrNull() ?: return@forEach
            if (uu in 0f..1f && vv in 0f..1f) spots.getOrPut(pkg) { LinkedHashMap() }[control] = TouchSpot(uu, vv)
        }
    }

    fun of(packageName: String): Map<PadControl, TouchSpot> = spots[packageName] ?: emptyMap()

    fun set(packageName: String, control: PadControl, spot: TouchSpot) {
        spots.getOrPut(packageName) { LinkedHashMap() }[control] = TouchSpot(spot.u.coerceIn(0f, 1f), spot.v.coerceIn(0f, 1f))
    }

    fun clear(packageName: String) {
        spots.remove(packageName)
    }

    fun encode(): String = buildString {
        spots.forEach { (pkg, m) -> m.forEach { (c, s) -> append(pkg).append(' ').append(c.name).append(' ').append(s.u).append(' ').append(s.v).append('\n') } }
    }
}

/**
 * Multi-touch on a window from a changing set of touches, each with its own pointer id kept while it is down: what
 * Android's MotionEvent needs. [next] gives the events from the touches before to the touches now.
 */
class TouchPointers {
    /** One event: [action] (with the pointer index in bits 8 to 15 for pointer down and up), and every pointer in it. */
    data class Event(val action: Int, val ids: IntArray, val us: FloatArray, val vs: FloatArray) {
        override fun equals(other: Any?): Boolean = other is Event && action == other.action && ids.contentEquals(other.ids) && us.contentEquals(other.us) && vs.contentEquals(other.vs)
        override fun hashCode(): Int = action * 31 + ids.contentHashCode()
    }

    private val active = LinkedHashMap<PadControl, Pair<Int, TouchSpot>>()

    val down: Boolean get() = active.isNotEmpty()

    fun next(now: Map<PadControl, TouchSpot>): List<Event> {
        val out = ArrayList<Event>()
        // Lifts first, each while it is still in the event.
        for (c in active.keys.toList()) if (c !in now) {
            val index = active.keys.indexOf(c)
            out += if (active.size == 1) event(ACTION_UP, 0) else event(ACTION_POINTER_UP, index)
            active.remove(c)
        }
        // New touches, each joining the event.
        for ((c, spot) in now) if (c !in active) {
            val id = (0..31).first { i -> active.values.none { it.first == i } }
            active[c] = id to spot
            out += if (active.size == 1) event(ACTION_DOWN, 0) else event(ACTION_POINTER_DOWN, active.keys.indexOf(c))
        }
        // Moves.
        var moved = false
        for ((c, spot) in now) {
            val (id, before) = active[c] ?: continue
            if (before != spot) {
                active[c] = id to spot
                moved = true
            }
        }
        if (moved) out += event(ACTION_MOVE, 0)
        return out
    }

    /** Lifts every touch (the controller hidden, the window gone). */
    fun cancel(): List<Event> = next(emptyMap())

    private fun event(base: Int, index: Int): Event {
        val action = if (base == ACTION_POINTER_DOWN || base == ACTION_POINTER_UP) base or (index shl 8) else base
        val v = active.values.toList()
        return Event(action, IntArray(v.size) { v[it].first }, FloatArray(v.size) { v[it].second.u }, FloatArray(v.size) { v[it].second.v })
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}
