package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.Phase

/**
 * One input event for a panel's app, as the shell injects it on the panel's display (0.4.1).
 * The values are Android's own `MotionEvent` and `InputDevice` constants, written out so the
 * mapping stays plain Kotlin; the app's PanelHostService builds the real event from them.
 */
data class PanelMotion(
    /** `MotionEvent` action, with the pointer index already in bits 8 to 15 for pointer up and down. */
    val action: Int,
    /** Pointer positions in display pixels, pointer id = index. */
    val xs: List<Float>,
    val ys: List<Float>,
    /** `InputDevice.SOURCE_TOUCHSCREEN` or `SOURCE_MOUSE`. */
    val source: Int,
    /** Mouse wheel notches for ACTION_SCROLL: positive [vScroll] scrolls up, positive [hScroll] right. */
    val vScroll: Float = 0f,
    val hScroll: Float = 0f,
    /** Inject this long after the event before it (a long press's lift); 0 for now. */
    val delayMs: Long = 0,
    /** True for the first event of a gesture: the shell starts a new down time. */
    val startsGesture: Boolean = false,
) {
    val pointerCount: Int get() = xs.size

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_SCROLL = 8
        const val ACTION_HOVER_ENTER = 9
        const val ACTION_HOVER_EXIT = 10
        const val POINTER_INDEX_SHIFT = 8

        const val SOURCE_TOUCHSCREEN = 0x00001002
        const val SOURCE_MOUSE = 0x00002002
    }
}

/**
 * Turns the Space's panel input ([SpaceAction]) into the events each app gets: touches, mouse
 * hover, mouse wheel scrolling, a two finger pinch and a long press. Stateful per Space (the
 * wheel's remainder and the pinch's start), GL thread only.
 */
class PanelInputMapper(
    /** Glasses pixels of finger movement per wheel notch; Android scrolls about 64 dp a notch. */
    val pxPerNotch: Float = 40f,
    /** Half the distance between the two fingers when an app pinch starts, over the panel's shorter side. */
    val pinchSpread: Float = 0.12f,
    /** How long a two finger tap holds its long press; Android's own timeout is 400 to 500 ms. */
    val longPressMs: Long = 650,
) {
    private var wheelX = 0f
    private var wheelY = 0f
    private var sentX = 0f
    private var sentY = 0f

    /**
     * The events for [action] on a panel of [width] by [height] pixels; empty when it is not panel input. [fullArea]: the
     * window fills its whole display with no status strip (a PC screen), so hover and wheel reach its top rows too.
     */
    fun map(action: SpaceAction, width: Int, height: Int, fullArea: Boolean = false): List<PanelMotion> = when (action) {
        is SpaceAction.Touch -> listOf(touch(action, width, height))
        is SpaceAction.Hover -> listOf(hover(action, width, height, fullArea))
        is SpaceAction.Wheel -> wheel(action, width, height, fullArea)
        is SpaceAction.Zoom -> zoom(action, width, height)
        is SpaceAction.LongPress -> {
            val (x, y) = pixel(action.u, action.v, width, height)
            listOf(
                PanelMotion(PanelMotion.ACTION_DOWN, listOf(x), listOf(y), PanelMotion.SOURCE_TOUCHSCREEN, startsGesture = true),
                PanelMotion(PanelMotion.ACTION_UP, listOf(x), listOf(y), PanelMotion.SOURCE_TOUCHSCREEN, delayMs = longPressMs),
            )
        }
        else -> emptyList()
    }

    private fun touch(t: SpaceAction.Touch, width: Int, height: Int): PanelMotion {
        val (x, y) = pixel(t.u, t.v, width, height)
        val a = when (t.phase) {
            TouchPhase.DOWN -> PanelMotion.ACTION_DOWN
            TouchPhase.MOVE -> PanelMotion.ACTION_MOVE
            TouchPhase.UP -> PanelMotion.ACTION_UP
            TouchPhase.CANCEL -> PanelMotion.ACTION_CANCEL
        }
        return PanelMotion(a, listOf(x), listOf(y), PanelMotion.SOURCE_TOUCHSCREEN, startsGesture = t.phase == TouchPhase.DOWN)
    }

    private fun hover(h: SpaceAction.Hover, width: Int, height: Int, fullArea: Boolean): PanelMotion {
        // A PC screen's whole window: the content area kept the pointer 48 px under its top (Mike, 20 Nov 2026: the PC's
        // cursor never reached the top row; the phone log showed 48 as the highest it got).
        val (x, y) = if (fullArea) pixel(h.u, h.v, width, height) else contentPixel(h.u, h.v, width, height)
        val a = when (h.phase) {
            HoverPhase.ENTER -> PanelMotion.ACTION_HOVER_ENTER
            HoverPhase.MOVE -> PanelMotion.ACTION_HOVER_MOVE
            HoverPhase.EXIT -> PanelMotion.ACTION_HOVER_EXIT
        }
        return PanelMotion(a, listOf(x), listOf(y), PanelMotion.SOURCE_MOUSE)
    }

    /**
     * Natural scrolling, as on the trackpad's DeX side: fingers moving down show what is above,
     * which is a wheel turned up (positive vertical); fingers moving left show what is to the
     * right (positive horizontal). Whole notches only, the remainder carries to the next update.
     */
    private fun wheel(w: SpaceAction.Wheel, width: Int, height: Int, fullArea: Boolean = false): List<PanelMotion> {
        if (w.phase == Phase.BEGIN) {
            wheelX = 0f
            wheelY = 0f
            sentX = 0f
            sentY = 0f
            return emptyList()
        }
        wheelX = -w.dx / pxPerNotch
        wheelY = w.dy / pxPerNotch
        // 0.5.21: the thin scroll strip sends what moved since the last update, fractions included, so
        // the page follows the finger and its coast every frame rather than a notch at a time.
        val h = if (w.smooth) fine(wheelX - sentX) else notches(wheelX - sentX)
        val v = if (w.smooth) fine(wheelY - sentY) else notches(wheelY - sentY)
        if (h == 0f && v == 0f) return emptyList()
        sentX += h
        sentY += v
        val (x, y) = if (fullArea) pixel(w.u, w.v, width, height) else contentPixel(w.u, w.v, width, height)
        return listOf(PanelMotion(PanelMotion.ACTION_SCROLL, listOf(x), listOf(y), PanelMotion.SOURCE_MOUSE, vScroll = v, hScroll = h))
    }

    /** Whole notches in [n], toward zero; fractions of a notch wait for more movement. */
    private fun notches(n: Float): Float = if (n >= 0f) kotlin.math.floor(n) else kotlin.math.ceil(n)

    /** A smooth wheel's step: [n] notches, held back only under [MIN_FINE_NOTCH] (well under a pixel of a list). */
    private fun fine(n: Float): Float = if (kotlin.math.abs(n) < MIN_FINE_NOTCH) 0f else n

    /**
     * Two fingers either side of [SpaceAction.Zoom.u], [SpaceAction.Zoom.v], [pinchSpread] of the
     * panel's shorter side apart at the start, spread by the pinch's factor: down, second down,
     * moves, second up, up.
     */
    private fun zoom(z: SpaceAction.Zoom, width: Int, height: Int): List<PanelMotion> {
        val (cx, cy) = pixel(z.u, z.v, width, height)
        val half = pinchSpread * minOf(width, height) * z.factor.coerceIn(0.1f, 10f)
        val x0 = (cx - half).coerceIn(0f, width - 1f)
        val x1 = (cx + half).coerceIn(0f, width - 1f)
        val xs = listOf(x0, x1)
        val ys = listOf(cy, cy)
        val src = PanelMotion.SOURCE_TOUCHSCREEN
        return when (z.phase) {
            Phase.BEGIN -> listOf(
                PanelMotion(PanelMotion.ACTION_DOWN, listOf(x0), listOf(cy), src, startsGesture = true),
                PanelMotion(PanelMotion.ACTION_POINTER_DOWN or (1 shl PanelMotion.POINTER_INDEX_SHIFT), xs, ys, src),
            )
            Phase.UPDATE -> listOf(PanelMotion(PanelMotion.ACTION_MOVE, xs, ys, src))
            Phase.END -> listOf(
                PanelMotion(PanelMotion.ACTION_MOVE, xs, ys, src),
                PanelMotion(PanelMotion.ACTION_POINTER_UP or (1 shl PanelMotion.POINTER_INDEX_SHIFT), xs, ys, src),
                PanelMotion(PanelMotion.ACTION_UP, listOf(x0), listOf(cy), src),
            )
        }
    }

    companion object {
        const val MIN_FINE_NOTCH = 0.02f

        /** Panel u, v to a display pixel, clamped to the display. */
        fun pixel(u: Float, v: Float, width: Int, height: Int): Pair<Float, Float> =
            Pair((u * width).coerceIn(0f, width - 1f), (v * height).coerceIn(0f, height - 1f))

        /**
         * Panel u, v to a display pixel inside the app's content area ([ContentInsets.forPanel]),
         * for mouse hover and wheel events (0.4.2): never on the display's top strip, where
         * Android finds no window and drops them.
         */
        fun contentPixel(u: Float, v: Float, width: Int, height: Int): Pair<Float, Float> {
            val (x, y) = pixel(u, v, width, height)
            return ContentInsets.forPanel(width, height).clamp(x, y, width, height)
        }
    }
}

/** Where a key from a keyboard reaching the Space goes. */
sealed interface KeyTarget {
    /** The launcher's search box, while the launcher is open. */
    data object Launcher : KeyTarget

    /** The focused panel's app, as a key event on its display. */
    data class Panel(val panelId: Int) : KeyTarget

    /** The Space itself: the glasses' own button, whose long press recentres. */
    data object Space : KeyTarget
}

/**
 * Keys in the Space (0.4.1). A hardware keyboard types into the panel the pointer last pressed
 * ([focusedPanelId]); while the launcher is open it types into the launcher's search instead.
 * Keys from anything that is not a full keyboard (the glasses' button) stay with the Space.
 */
object KeyRouting {
    fun target(launcherOpen: Boolean, fullKeyboard: Boolean, focusedPanelId: Int?, panelIds: Collection<Int>): KeyTarget = when {
        launcherOpen -> KeyTarget.Launcher
        !fullKeyboard -> KeyTarget.Space
        focusedPanelId != null && focusedPanelId in panelIds -> KeyTarget.Panel(focusedPanelId)
        else -> KeyTarget.Space
    }

    /** The panel a Back goes to: the one named, else the focused one, else the topmost. */
    fun backTarget(named: Int?, focusedPanelId: Int?, panelIdsTopLast: List<Int>): Int? = when {
        named != null && named in panelIdsTopLast -> named
        focusedPanelId != null && focusedPanelId in panelIdsTopLast -> focusedPanelId
        else -> panelIdsTopLast.lastOrNull()
    }

    /** `KeyEvent.KEYCODE_BACK`. */
    const val KEYCODE_BACK = 4
}

/**
 * Keeps one panel's injected mouse hover a stream Android accepts (0.4.5). Android's
 * InputDispatcher aborts the whole system (system_server, "Expected ACTION_HOVER_MOVE instead
 * of MotionEvent") when a hover enter arrives for a pointer it still has hovering a window.
 * The 0.4.4 retest hit that: while the Back question was up every event was held back, the
 * exit from the press on "Back anyway" with them, and the enter that followed the answer
 * restarted Android. So an exit always goes through when an enter did, an enter on a display
 * that is still hovered goes as a move, and a card only holds back enters and moves.
 */
class HoverGate {
    /** The display an enter went to with no exit since; null when nothing is hovered. */
    private var openOn: Int? = null

    /**
     * The phase to inject for [phase] on [displayId], or null to send nothing; [blocked] is
     * true while a card (App closed, the Back question) keeps input from the app.
     */
    fun pass(phase: HoverPhase, displayId: Int, blocked: Boolean): HoverPhase? {
        val open = openOn == displayId
        return when (phase) {
            HoverPhase.EXIT -> if (open) {
                openOn = null
                HoverPhase.EXIT
            } else {
                null
            }
            HoverPhase.ENTER, HoverPhase.MOVE -> when {
                blocked -> null
                open -> HoverPhase.MOVE
                else -> {
                    openOn = displayId
                    HoverPhase.ENTER
                }
            }
        }
    }
}

/** One edit of the launcher's search: a character typed, or one taken off the end. */
sealed interface SearchEdit {
    data class Type(val char: Char) : SearchEdit
    data object Backspace : SearchEdit
}

/**
 * Turns the keys that reach the Space into edits of the open launcher's search (0.4.6). The
 * 0.4.5 retest saw the first letter typed twice. Every key down was taken, repeats included,
 * and the first key after the launcher opens is the one that waits while the Space takes top
 * focus back from a panel: a press held past Android's key repeat delay that way, or the same
 * press delivered again, typed its letter twice.
 *
 * Now a press types its character once: only its first down (repeat 0), and a down already
 * taken (same key and down time) is dropped. Backspace still repeats while held, so holding it
 * clears the search.
 */
class LauncherTyping {
    private var heldKeyCode = -1
    private var heldDownTime = Long.MIN_VALUE

    /**
     * The edit for a key down, or null. [unicodeChar] is the character the key makes (0 for
     * none); [repeatCount] and [downTime] are the key event's own.
     *
     * 0.4.7: a down is a duplicate only while its press is still held (no [up] yet). The 0.4.6
     * retest typed "ss" with `adb shell input text`, whose two presses share one down time, and
     * got one letter.
     */
    fun edit(keyCode: Int, unicodeChar: Int, repeatCount: Int, downTime: Long): SearchEdit? {
        if (keyCode == KEYCODE_DEL) return SearchEdit.Backspace
        if (unicodeChar == 0 || Character.isISOControl(unicodeChar)) return null
        if (repeatCount > 0) return null
        if (keyCode == heldKeyCode && downTime == heldDownTime) return null
        heldKeyCode = keyCode
        heldDownTime = downTime
        return SearchEdit.Type(unicodeChar.toChar())
    }

    /** A key up: that press is over, so its key's next down types again. */
    fun up(keyCode: Int) {
        if (keyCode == heldKeyCode) {
            heldKeyCode = -1
            heldDownTime = Long.MIN_VALUE
        }
    }

    companion object {
        /** `KeyEvent.KEYCODE_DEL`, Backspace. */
        const val KEYCODE_DEL = 67
    }
}
