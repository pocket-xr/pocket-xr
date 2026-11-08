package com.hayeco.flipspaces.core.pointer

/**
 * 1.2.3 (issue 59: "The keyboard doesn't work for games, because it sends finished text, not key presses, so holding W or
 * Space does nothing in a game like Terraria"): Game keys, two clusters of keys on the open phone's trackpad, beside the
 * pad that still moves the mouse. Each key sends a real key down when a finger lands on it and a key up when it lifts, so
 * a held key stays held in the game. Several fingers work at once, and a finger slid from one key to the next lets go of
 * the first and presses the second, as on a game pad's buttons.
 *
 * A key's place is a share of its cluster (x, y, width, height from 0 to 1). The key codes are Android's KeyEvent codes,
 * written as numbers so core needs no Android.
 */
data class GameKey(val label: String, val keyCode: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h
}

/** A key going down or up. */
data class GameKeyChange(val keyCode: Int, val down: Boolean)

object GameKeyLayout {
    const val Q = 45
    const val W = 51
    const val E = 33
    const val R = 46
    const val A = 29
    const val S = 47
    const val D = 32
    const val F = 34
    const val SHIFT = 59
    const val CTRL = 113
    const val TAB = 61
    const val SPACE = 62
    const val ESCAPE = 111
    const val ONE = 8
    const val TWO = 9
    const val THREE = 10
    const val FOUR = 11

    /** Not a key: the cluster's own key that turns Game keys off. */
    const val EXIT = -1

    private fun row(y: Float, h: Float, vararg keys: Pair<String, Int>): List<GameKey> {
        val w = 1f / keys.size
        return keys.mapIndexed { i, (label, code) -> GameKey(label, code, i * w, y, w, h) }
    }

    /** The left thumb's keys: Q W E over A S D, and Shift, Ctrl and Tab under them. */
    val LEFT: List<GameKey> =
        row(0f, 1f / 3f, "Q" to Q, "W" to W, "E" to E) +
            row(1f / 3f, 1f / 3f, "A" to A, "S" to S, "D" to D) +
            row(2f / 3f, 1f / 3f, "Shift" to SHIFT, "Ctrl" to CTRL, "Tab" to TAB)

    /** The right thumb's keys: 1 to 4, then R, F, Esc and the way out, then a wide Space. */
    val RIGHT: List<GameKey> =
        row(0f, 1f / 3f, "1" to ONE, "2" to TWO, "3" to THREE, "4" to FOUR) +
            row(1f / 3f, 1f / 3f, "R" to R, "F" to F, "Esc" to ESCAPE, "Done" to EXIT) +
            row(2f / 3f, 1f / 3f, "Space" to SPACE)

    const val MENU_ROW = "Game keys"
    const val MENU_SUBTITLE = "Keys that games feel held down, beside the trackpad"
}

/**
 * The fingers on one cluster of [keys] and the keys they hold. A key is down while any finger is on it, so two fingers
 * on one key send one down and one up. [cancel] lets go of everything (the cluster hidden, the trackpad paused).
 */
class GameKeyPresses(private val keys: List<GameKey>) {
    private val fingers = HashMap<Int, GameKey>()

    /** The key under [x], [y] (shares of the cluster), or null between keys or outside. */
    fun keyAt(x: Float, y: Float): GameKey? = keys.firstOrNull { it.contains(x, y) }

    /** The keys held now, for drawing them lit. */
    val held: Set<GameKey> get() = fingers.values.toSet()

    fun down(pointer: Int, x: Float, y: Float): List<GameKeyChange> = to(pointer, keyAt(x, y))

    fun move(pointer: Int, x: Float, y: Float): List<GameKeyChange> = if (pointer in fingers || keyAt(x, y) != null) to(pointer, keyAt(x, y)) else emptyList()

    fun up(pointer: Int): List<GameKeyChange> = to(pointer, null)

    fun cancel(): List<GameKeyChange> {
        val out = held.filter { it.keyCode != GameKeyLayout.EXIT }.map { GameKeyChange(it.keyCode, false) }
        fingers.clear()
        return out
    }

    private fun to(pointer: Int, key: GameKey?): List<GameKeyChange> {
        val before = fingers[pointer]
        if (before == key) return emptyList()
        val out = ArrayList<GameKeyChange>(2)
        if (key == null) fingers.remove(pointer) else fingers[pointer] = key
        if (before != null && before !in fingers.values) out += GameKeyChange(before.keyCode, false)
        if (key != null && fingers.values.count { it == key } == 1) out += GameKeyChange(key.keyCode, true)
        return out
    }
}
