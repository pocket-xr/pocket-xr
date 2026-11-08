package com.hayeco.flipspaces.core.pointer

import kotlin.math.hypot

/**
 * 1.2.3 (issue 59: "the phone as a gamepad"): the phone's game controller on the open trackpad, two clusters like Game keys
 * ([GameKeyLayout]): the left thumb's stick with L2, L1 and Select over it, the right thumb's A B X Y and a smaller right
 * stick with Start, R1 and R2 over them. It drives either a virtual gamepad that games see as a real one
 * ([UhidGamepad]), or touches on spots of the game's own on-screen controls ([TouchSpots]).
 *
 * A control's place is a share of its cluster (x, y, width, height, 0 to 1); a stick is a circle in its rectangle.
 */
enum class PadControl(val label: String, val stick: Boolean = false) {
    LEFT_STICK("L", stick = true),
    RIGHT_STICK("R", stick = true),
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    L1("L1"),
    R1("R1"),
    L2("L2"),
    R2("R2"),
    SELECT("Select"),
    START("Start"),
}

data class PadControlPlace(val control: PadControl, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h
    val centreX: Float get() = x + w / 2f
    val centreY: Float get() = y + h / 2f
}

object GameControllerLayout {
    private const val TOP = 0.24f

    private fun top(vararg c: PadControl): List<PadControlPlace> {
        val w = 1f / c.size
        return c.mapIndexed { i, k -> PadControlPlace(k, i * w, 0f, w, TOP) }
    }

    /** L2 L1 Select over the left stick. */
    val LEFT: List<PadControlPlace> = top(PadControl.L2, PadControl.L1, PadControl.SELECT) +
        PadControlPlace(PadControl.LEFT_STICK, 0f, TOP, 1f, 1f - TOP)

    /** Start R1 R2 over the right stick (left part) and A B X Y as a diamond (right part). */
    val RIGHT: List<PadControlPlace> = run {
        val body = 1f - TOP
        val dx = 0.6f
        val cw = (1f - dx) / 3f
        val ch = body / 3f
        top(PadControl.START, PadControl.R1, PadControl.R2) + listOf(
            PadControlPlace(PadControl.RIGHT_STICK, 0f, TOP + body * 0.15f, dx, body * 0.7f),
            PadControlPlace(PadControl.Y, dx + cw, TOP, cw, ch),
            PadControlPlace(PadControl.X, dx, TOP + ch, cw, ch),
            PadControlPlace(PadControl.B, dx + 2 * cw, TOP + ch, cw, ch),
            PadControlPlace(PadControl.A, dx + cw, TOP + 2 * ch, cw, ch),
        )
    }
}

/**
 * The controller as games read it: the buttons held, each stick's -1 to 1 x (right) and y (down), and the sticks a finger
 * holds ([sticksHeld]: a stick held at its centre still touches its spot in Touch mode).
 */
data class ControllerState(
    val buttons: Set<PadControl> = emptySet(),
    val sticksHeld: Set<PadControl> = emptySet(),
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
)

/**
 * The fingers on one cluster's [places] (sized [aspect], width over height, so a stick's circle is round). A finger that
 * lands on a stick holds it and steers it from the stick's centre, to its rim at full tilt and held there beyond it; a
 * finger on a button holds that button until it lifts or slides off.
 */
class GameControllerPresses(private val places: List<PadControlPlace>, var aspect: Float = 1f) {
    private val fingers = HashMap<Int, PadControlPlace>()
    private val tilt = HashMap<PadControl, Pair<Float, Float>>()

    fun placeAt(x: Float, y: Float): PadControlPlace? = places.firstOrNull { it.contains(x, y) }

    val held: Set<PadControl> get() = fingers.values.map { it.control }.toSet()

    /** A stick's tilt now, (0, 0) when no finger holds it. */
    fun tilt(c: PadControl): Pair<Float, Float> = tilt[c] ?: (0f to 0f)

    /** Returns true when anything a game reads changed. */
    fun down(pointer: Int, x: Float, y: Float): Boolean {
        val p = placeAt(x, y) ?: return false
        fingers[pointer] = p
        if (p.control.stick) steer(p, x, y)
        return true
    }

    fun move(pointer: Int, x: Float, y: Float): Boolean {
        val p = fingers[pointer]
        if (p == null) return down(pointer, x, y)
        if (p.control.stick) return steer(p, x, y)
        if (p.contains(x, y)) return false
        // A finger slid off a button lets it go, and presses the next one it is on.
        fingers.remove(pointer)
        placeAt(x, y)?.takeIf { !it.control.stick }?.let { fingers[pointer] = it }
        return true
    }

    fun up(pointer: Int): Boolean {
        val p = fingers.remove(pointer) ?: return false
        if (p.control.stick && fingers.values.none { it == p }) tilt.remove(p.control)
        return true
    }

    fun cancel(): Boolean {
        val any = fingers.isNotEmpty()
        fingers.clear()
        tilt.clear()
        return any
    }

    private fun steer(p: PadControlPlace, x: Float, y: Float): Boolean {
        // The stick's radius: half its rectangle's shorter side, measured with the cluster's shape.
        val r = minOf(p.w * aspect, p.h) / 2f
        var dx = (x - p.centreX) * aspect / r
        var dy = (y - p.centreY) / r
        val len = hypot(dx, dy)
        if (len > 1f) {
            dx /= len
            dy /= len
        }
        val before = tilt[p.control]
        tilt[p.control] = dx to dy
        return before != (dx to dy)
    }

    /** What these fingers hold, as a game reads it. */
    fun state(): ControllerState = ControllerState(
        buttons = held.filter { !it.stick }.toSet(),
        sticksHeld = held.filter { it.stick }.toSet(),
        leftX = tilt(PadControl.LEFT_STICK).first,
        leftY = tilt(PadControl.LEFT_STICK).second,
        rightX = tilt(PadControl.RIGHT_STICK).first,
        rightY = tilt(PadControl.RIGHT_STICK).second,
    )

    companion object {
        /** Both clusters' states as one controller. */
        fun merge(a: ControllerState, b: ControllerState): ControllerState = ControllerState(
            buttons = a.buttons + b.buttons,
            sticksHeld = a.sticksHeld + b.sticksHeld,
            leftX = if (a.leftX != 0f || a.leftY != 0f) a.leftX else b.leftX,
            leftY = if (a.leftX != 0f || a.leftY != 0f) a.leftY else b.leftY,
            rightX = if (a.rightX != 0f || a.rightY != 0f) a.rightX else b.rightX,
            rightY = if (a.rightX != 0f || a.rightY != 0f) a.rightY else b.rightY,
        )
    }
}

/** What the Menu's Game controller row cycles through. */
enum class GameControllerMode(val label: String) {
    OFF("Off"),
    GAMEPAD("Gamepad"),
    TOUCH("Touch"),
    ;

    fun next(): GameControllerMode = entries[(ordinal + 1) % entries.size]

    companion object {
        const val MENU_ROW = "Game controller"
        const val GAMEPAD_SUBTITLE = "A gamepad games see as real (needs Shizuku)"
        const val TOUCH_SUBTITLE = "Presses the game's own on-screen controls"
        const val OFF_SUBTITLE = "A stick and buttons on the phone, as a gamepad or on the game's on-screen controls"
    }
}
