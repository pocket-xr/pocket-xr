package com.hayeco.flipspaces.core.pointer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One finger on the phone screen, in phone pixels (the old trackpad's unit, kept for callers that have pixels). */
data class Touch(val id: Int, val x: Float, val y: Float)

/**
 * 0.5.17: one finger on the phone trackpad, in pad units: x = 2 px / width - 1 and
 * y = 1 - 2 py / height, each clamped to -1..1, so y is +1 at the top. Pad units make the
 * gestures below independent of the screen's size and orientation.
 */
data class PadTouch(val id: Int, val x: Float, val y: Float) {
    companion object {
        fun of(id: Int, px: Float, py: Float, width: Float, height: Float): PadTouch {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)
            return PadTouch(id, (2f * px / w - 1f).coerceIn(-1f, 1f), (1f - 2f * py / h).coerceIn(-1f, 1f))
        }
    }
}

/** Which edge the Vertical Trackpad's scroll strip is on (default right). */
enum class StripSide { LEFT, RIGHT }

/**
 * 0.5.17: the trackpad's cursor curve. A finger's move in pad units becomes a cursor turn in
 * degrees:
 *
 * 1. aspect: portrait (Vertical Trackpad, Spatial Mouse) x times [PORTRAIT_ASPECT] 0.63; landscape
 *    y divided by [LANDSCAPE_ASPECT] 2.235, so a swipe's turn matches the pad's shape;
 * 2. times [GAIN] 18, the same in both orientations;
 * 3. acceleration from m = vx^2 + vy^2 of that frame's move: above [ACCEL_FLOOR] 0.2,
 *    1 + 2.5 sin(min((m - 0.2) 0.5 90, 90) degrees), else 1, so slow moves stay precise and
 *    quick flicks cross the view;
 * 4. times the base and acceleration factors from the Cursor Speed slider ([speedFactors]).
 *
 * Android sends one move per display frame at whatever rate the Flip runs (120 Hz), so m is taken
 * on the move scaled to a 60 Hz frame ([FRAME_MS]): the curve then feels the same at any touch
 * rate. Slow swipes do not depend on it: a full portrait width is 2 x 0.63 x 18 x 1.2 = 27.2
 * degrees.
 */
object PadCurve {
    const val PORTRAIT_ASPECT = 0.63f
    const val LANDSCAPE_ASPECT = 2.235f
    const val GAIN = 18f
    const val ACCEL_FLOOR = 0.2f
    const val ACCEL_GAIN = 2.5f
    const val FRAME_MS = 1000f / 60f
    const val DEFAULT_SPEED = 50

    /**
     * The Cursor Speed slider s in 0..100 as (base factor, acceleration factor): the lower half
     * scales the base speed from 0.2 to 1.2, the upper half adds acceleration from 1 to 2.
     */
    fun speedFactors(speed: Int): Pair<Float, Float> {
        val s = speed.coerceIn(0, 100)
        return if (s <= 50) Pair(s / 50f + 0.2f, 1f) else Pair(1.2f, (s - 50) / 50f + 1f)
    }

    /** The acceleration for a frame move whose gained square length is [m]. */
    fun accel(m: Float): Float {
        if (m <= ACCEL_FLOOR) return 1f
        val deg = min((m - ACCEL_FLOOR) * 0.5f * 90f, 90f)
        return 1f + ACCEL_GAIN * sin(Math.toRadians(deg.toDouble())).toFloat()
    }

    /**
     * The cursor turn (yaw right positive, pitch up positive, degrees) for a move of [dx], [dy] pad
     * units over [dtMs] on page [mode] at Cursor Speed [speed].
     */
    fun turn(dx: Float, dy: Float, dtMs: Float, mode: PadMode, speed: Int = DEFAULT_SPEED): Pair<Float, Float> {
        val landscape = mode == PadMode.HORIZONTAL
        val ax = if (landscape) dx else dx * PORTRAIT_ASPECT
        val ay = if (landscape) dy / LANDSCAPE_ASPECT else dy
        val vx = ax * GAIN
        val vy = ay * GAIN
        val perFrame = FRAME_MS / dtMs.coerceIn(2f, 100f)
        val m = (vx * vx + vy * vy) * perFrame * perFrame
        val (k, a) = speedFactors(speed)
        val f = k * a * accel(m)
        return Pair(vx * f, vy * f)
    }

    /** One slow full swipe's turn in degrees, with no acceleration: (width, height). */
    fun fullSwipeDeg(mode: PadMode, speed: Int = DEFAULT_SPEED): Pair<Float, Float> {
        val (k, a) = speedFactors(speed)
        val landscape = mode == PadMode.HORIZONTAL
        val w = 2f * (if (landscape) 1f else PORTRAIT_ASPECT) * GAIN * k * a
        val h = 2f * (if (landscape) 1f / LANDSCAPE_ASPECT else 1f) * GAIN * k * a
        return Pair(w, h)
    }
}

/**
 * 0.5.17: the phone trackpad's gestures, from normalised touches ([PadTouch]) to
 * [PointerSignal]s. Pure, no clock of its own: every call takes the touch's time.
 *
 * - One finger: every move turns the cursor (no slop, no dead zone, no inertia, [PadCurve]).
 *   A tap clicks: one finger, lifted with no move flag (it never moved [MOVE_LIMIT] 0.15 units
 *   from where it went down) and before the long press, sends the press and release together.
 *   Held still for [LONG_PRESS_MS] 500 ms it presses and keeps the press down, so moving then
 *   drags. The safe-down filter: a finger that moves more than [SAFE_DOWN_MOVE] 0.03 units within
 *   [SAFE_DOWN_MS] 0.15 s of going down is a swipe, never a tap or a long press.
 *   0.5.107 (Mike: in the Control Center "its really easy to accidentally select control panel toggles ... as if its
 *   registering my pinching even though im not"): a tap is also quick and nearly still: lifted within [TAP_MAX_MS]
 *   300 ms, having moved at most [TAP_TRAVEL_SHARE] of [MOVE_LIMIT] (about 2 mm). A small slow nudge of the cursor,
 *   lifted, clicked before; now it only moves the cursor.
 * - Two fingers: a tap is a right click ([PointerSignal.SecondaryTap]). Scroll or pinch is decided
 *   once a gesture, when both fingers have moved (squared over 1e-5): |distance change| over the
 *   midpoint's move is a pinch, else a scroll. The scroll axis is 4 units a pad unit (midpoint),
 *   clamped to -1..1 ([SCROLL_SPEED]). Every two finger move is also sent as
 *   [PadSignal.TwoFinger] for a held window.
 * - Three or more fingers: the centroid's shift from the third finger's landing to the first lift;
 *   over [SWIPE_MIN] 0.04 units, vertical first, it is a swipe; less, within [THREE_TAP_MS], a tap
 *   (the dock, a Pocket XR extra).
 * - The scroll strip (Vertical Trackpad only): a touch landing in the edge strip [STRIP_WIDTH] 0.35
 *   units wide ([STRIP_WIDEN] 1.35 times wider within [STRIP_WIDEN_MS] 1000 ms of the last strip
 *   touch) buzzes 10 ms; its first move decides: |dx|^2 over |dy|^2 is the cursor, else a one
 *   finger scroll, 4 axis units a pad unit.
 *
 * - 0.5.21, the thin scroll strip ([scrollStrip], [ScrollStrip]): a finger landing in the band only
 *   scrolls, at once and never the cursor; the page's travel follows the finger's times
 *   [StripSpeed.gain], coasts after a flick ([coast]) and ticks every [StripSpeed.TICK_UNITS]. While it
 *   is set, the wide strip above is off.
 *
 * Scrolls and pinches into apps start ([Phase.BEGIN]) only after [START_TRAVEL] 0.03 units of
 * travel, so a two finger tap never injects a drag before its right click.
 *
 * - 1.1.10 (issue 35: "scroll on the trackpad with a single finger on the Spatial Mouse"), with [oneFingerScroll] on
 *   Spatial Mouse: the phone aims, so one finger had nothing to move. Once it is past the tap's slop (the move flag:
 *   [MOVE_LIMIT], or the safe-down filter's quick [SAFE_DOWN_MOVE]) and not held by the long press, it scrolls
 *   ([PadSignal.Scroll] with oneFinger) the same way, at the same speed ([scrollFactor]) and by the same path as two
 *   fingers, measured from where the scroll was decided so the page does not jump by the slop. A tap still clicks and a
 *   still hold still presses and drags: neither ever passes the slop.
 */
class TrackpadInterpreter(
    var mode: PadMode = PadMode.VERTICAL,
    var cursorSpeed: Int = PadCurve.DEFAULT_SPEED,
    var stripSide: StripSide = StripSide.RIGHT,
) {
    /** 0.5.83: the two finger scroll's and the thin strip's travel factors ([TrackpadSettings.scrollFactor]); 1 is 0.5.82's. */
    var scrollFactor = 1f
    var stripFactor = 1f

    /** 1.1.10 (issue 35): one finger scrolls on Spatial Mouse (the Menu's "One-finger scroll on Spatial Mouse", on by default). */
    var oneFingerScroll = false

    private enum class One { UNDECIDED_STRIP, STRIP, CURSOR, THIN, MOUSE_SCROLL }
    private enum class Two { UNDECIDED, SCROLL, PINCH }

    private val touches = linkedMapOf<Int, PadTouch>()
    private val starts = HashMap<Int, PadTouch>()
    private val travel = HashMap<Int, Float>()
    private var fingers = 0
    private var startMs = 0L
    private var lastMoveMs = 0L
    private var done = false

    // One finger.
    private var one = One.CURSOR
    private var swiped = false
    private var held = false
    private var stripStartY = 0f
    private var mouseScrollFrom = Pair(0f, 0f)
    private var lastStripMs = Long.MIN_VALUE / 2

    // Two fingers.
    private var two = Two.UNDECIDED
    private var twoActive = false
    private var twoBegun = false
    private var twoStartDist = 0f
    private var twoStartMid = Pair(0f, 0f)
    private var twoIds: Pair<Int, Int>? = null

    // Three fingers.
    private var threeActive = false
    private var threeStart = Pair(0f, 0f)
    private var threeStartMs = 0L

    private val haptics = mutableListOf<PadHaptic>()

    /** False on the Flip's cover screen layout, which has no scroll strip. */
    var stripAllowed = true

    /**
     * 0.5.21: the thin scroll strip's band ([ScrollStrip]), or null for none. While it is set it takes
     * the place of the wide strip: a finger landing in it only scrolls, never the cursor.
     */
    var scrollStrip: StripZone? = null

    // The thin strip: the page's travel, the finger's smoothed speed, the last tick, and the coast.
    private var thinTravel = 0f
    private var thinSpeed = 0f
    private var thinLastMs = 0L
    private var thinTickAt = 0f
    private var thinTickMs = Long.MIN_VALUE / 2
    private var fling: StripFling? = null
    private var flingMs = 0L

    /** True while the page coasts after a flick on the thin strip: call [coast] every frame meanwhile. */
    val coasting: Boolean get() = fling != null

    /** 0.5.17 cover screen: the cover pad's own curve in millimetres ([CoverCurve]); null for the phone pad's [PadCurve]. */
    var cover: CoverCurve? = null

    /** The tap's move limit and the safe down's, in pad units: the phone pad's, or a thumb's on the cover ([ThumbFeel]). */
    var moveLimit = MOVE_LIMIT
    var safeDownMove = SAFE_DOWN_MOVE

    /** True while the long press holds the press down. */
    val holding: Boolean get() = held

    /** The impulses the phone should play since the last call ([HapticGate] applies the cooldown). */
    fun takeHaptics(): List<PadHaptic> = haptics.toList().also { haptics.clear() }

    /** 0.5.22: why the last three finger gesture did nothing, once, for the log; null when none was refused. */
    private var threeRefusal: String? = null

    fun takeThreeRefusal(): String? = threeRefusal.also { threeRefusal = null }

    /** The scroll strip's zone test for a touch at [x] at [nowMs]; the strip widens for a while after it was used. */
    fun inStrip(x: Float, nowMs: Long): Boolean {
        if (mode != PadMode.VERTICAL || !stripAllowed || scrollStrip != null) return false
        val k = if (nowMs - lastStripMs < STRIP_WIDEN_MS) STRIP_WIDEN else 1f
        return when (stripSide) {
            StripSide.RIGHT -> x > 1f - STRIP_WIDTH * k
            StripSide.LEFT -> x < STRIP_WIDTH * k - 1f
        }
    }

    fun down(t: PadTouch, nowMs: Long): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        val first = touches.isEmpty()
        // A touch anywhere stops a coasting page, as a finger stops a fling.
        if (first) stopCoast(out)
        touches[t.id] = t
        starts[t.id] = t
        travel[t.id] = 0f
        if (first) {
            reset()
            fingers = 1
            startMs = nowMs
            lastMoveMs = nowMs
            cover?.reset()
            if (scrollStrip?.contains(t.x) == true) {
                one = One.THIN
                thinTravel = 0f
                thinSpeed = 0f
                thinLastMs = nowMs
                thinTickAt = 0f
                haptics += PadHaptic.STRIP
                out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true, wheel = true)
            } else if (inStrip(t.x, nowMs)) {
                one = One.UNDECIDED_STRIP
                lastStripMs = nowMs
                stripStartY = t.y
                haptics += PadHaptic.STRIP
            }
            return out
        }
        fingers = maxOf(fingers, touches.size)
        if (done) return out
        when (touches.size) {
            2 -> {
                endOne(out, nowMs)
                startTwo(out)
            }
            3 -> {
                endTwo(out)
                threeActive = true
                threeStart = centroid()
                threeStartMs = nowMs
            }
        }
        return out
    }

    fun move(moved: List<PadTouch>, nowMs: Long): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        val before = HashMap(touches)
        moved.forEach { m ->
            if (m.id !in touches) return@forEach
            touches[m.id] = m
            val s = starts[m.id] ?: m
            travel[m.id] = maxOf(travel[m.id] ?: 0f, hypot(m.x - s.x, m.y - s.y))
        }
        val dtMs = (nowMs - lastMoveMs).toFloat()
        lastMoveMs = nowMs
        if (done) return out
        when {
            touches.size == 1 && fingers == 1 -> {
                val t = touches.values.first()
                val b = before[t.id] ?: return out
                val dx = t.x - b.x
                val dy = t.y - b.y
                if (dx == 0f && dy == 0f) return out
                val moved0 = travel[t.id] ?: 0f
                if (!swiped && nowMs - startMs < SAFE_DOWN_MS && moved0 > safeDownMove) swiped = true
                if (moved0 > moveLimit) swiped = true
                if (one == One.UNDECIDED_STRIP) {
                    one = if (dx * dx > dy * dy) One.CURSOR else One.STRIP
                    if (one == One.STRIP) out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true)
                }
                // 1.1.10 (issue 35): on Spatial Mouse a finger past the tap's slop, not held, scrolls from here on.
                if (one == One.CURSOR && swiped && !held && mouseScrolls()) {
                    one = One.MOUSE_SCROLL
                    mouseScrollFrom = Pair(t.x, t.y)
                    out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, oneFinger = true)
                    return out
                }
                when (one) {
                    One.MOUSE_SCROLL -> out += mouseScroll(t, Phase.UPDATE)
                    One.THIN -> thinMove(dy, nowMs, out)
                    One.STRIP -> {
                        lastStripMs = nowMs
                        val travelY = t.y - stripStartY
                        out += PadSignal.Scroll(0f, (-SCROLL_SPEED * travelY).coerceIn(-1f, 1f), 0f, travelY, Phase.UPDATE, strip = true)
                    }
                    One.CURSOR -> {
                        val (yaw, pitch) = cover?.turn(dx, dy, dtMs, cursorSpeed) ?: PadCurve.turn(dx, dy, dtMs, mode, cursorSpeed)
                        out += PadSignal.Move(dx, dy, yaw, pitch)
                    }
                    One.UNDECIDED_STRIP -> Unit
                }
            }
            touches.size == 2 && twoActive -> moveTwo(before, out)
            else -> Unit
        }
        return out
    }

    fun up(id: Int, nowMs: Long): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        if (id !in touches) return out
        if (!done) {
            when {
                threeActive -> {
                    val (cx, cy) = centroid()
                    val sx = cx - threeStart.first
                    val sy = cy - threeStart.second
                    val signal: PointerSignal? = when {
                        abs(sy) > SWIPE_MIN && abs(sy) >= abs(sx) -> PadSignal.Swipe(if (sy > 0f) SwipeDirection.UP else SwipeDirection.DOWN)
                        abs(sx) > SWIPE_MIN -> PadSignal.Swipe(if (sx > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT)
                        nowMs - threeStartMs <= THREE_TAP_MS -> PointerSignal.ShowDock
                        else -> null
                    }
                    signal?.let { out += it }
                    // 0.5.22: on the phone no three finger swipe reached Pocket XR, and a refused one left no log line; now it does.
                    if (signal == null) {
                        threeRefusal = "three fingers lifted with no swipe or tap: the centroid moved %.3f, %.3f pad units (a swipe needs over %.2f) in %d ms (a tap is within %d ms)".format(
                            java.util.Locale.ROOT, sx, sy, SWIPE_MIN, nowMs - threeStartMs, THREE_TAP_MS,
                        )
                    }
                    done = true
                }
                twoActive -> {
                    val tap = fingers == 2 && !twoBegun && touches.keys.all { (travel[it] ?: 0f) < moveLimit }
                    endTwo(out)
                    if (tap) out += PointerSignal.SecondaryTap
                    done = true
                }
                touches.size == 1 -> {
                    when {
                        held -> out += PointerSignal.Up()
                        one == One.THIN -> thinLift(nowMs, out)
                        one == One.STRIP -> out += PadSignal.Scroll(0f, 0f, 0f, touches.values.first().y - stripStartY, Phase.END, strip = true)
                        one == One.MOUSE_SCROLL -> out += mouseScroll(touches.values.first(), Phase.END)
                        one == One.CURSOR && fingers == 1 && !swiped && nowMs - startMs <= TAP_MAX_MS && (travel[id] ?: 0f) <= moveLimit * TAP_TRAVEL_SHARE -> {
                            out += PointerSignal.Down(pad = true)
                            out += PointerSignal.Up()
                        }
                    }
                    if (one == One.STRIP || one == One.UNDECIDED_STRIP) lastStripMs = nowMs
                    held = false
                }
            }
        }
        return finishUp(id, out)
    }

    private fun finishUp(id: Int, out: MutableList<PointerSignal>): List<PointerSignal> {
        touches.remove(id)
        if (touches.isNotEmpty()) {
            done = true
            return out
        }
        if (held) {
            out += PointerSignal.Up()
            held = false
        }
        reset()
        fingers = 0
        return out
    }

    fun cancel(): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        if (twoActive) endTwo(out)
        if (one == One.STRIP && touches.isNotEmpty()) out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.END, strip = true)
        if (one == One.THIN && touches.isNotEmpty()) out += thinScroll(Phase.END)
        if (one == One.MOUSE_SCROLL && touches.isNotEmpty()) out += mouseScroll(touches.values.first(), Phase.END)
        stopCoast(out)
        val wasHeld = held
        touches.clear()
        reset()
        fingers = 0
        if (wasHeld) out += PointerSignal.Cancel
        return out
    }

    /** Call on a timer while a finger is down: a still finger held for [LONG_PRESS_MS] presses and holds. */
    fun tick(nowMs: Long): List<PointerSignal> {
        if (done || held || touches.size != 1 || fingers != 1 || one != One.CURSOR || swiped) return emptyList()
        val id = touches.keys.first()
        if ((travel[id] ?: 0f) > moveLimit) return emptyList()
        if (nowMs - startMs < LONG_PRESS_MS) return emptyList()
        held = true
        return listOf(PointerSignal.Down(pad = true))
    }

    /**
     * 1.2.3 (issue 53: the resize handle "seems to do nothing" when pressed, it has to be held): how far a still finger is
     * toward the long press ([tick]), 0 to 1, so the cursor can fill as the hold builds. 0 when no long press is coming:
     * no finger, more than one, a swipe, the strip, or already held.
     */
    fun holdProgress(nowMs: Long): Float {
        if (done || held || touches.size != 1 || fingers != 1 || one != One.CURSOR || swiped) return 0f
        val id = touches.keys.first()
        if ((travel[id] ?: 0f) > moveLimit) return 0f
        return ((nowMs - startMs).toFloat() / LONG_PRESS_MS).coerceIn(0f, 1f)
    }

    /** The phone's Back button was clicked: Back, with a 10 ms buzz. */
    fun back(): List<PointerSignal> {
        haptics += PadHaptic.BACK
        return listOf(PointerSignal.Back)
    }

    /** The page changed (a 10 ms buzz); the Space is told. */
    fun changeMode(next: PadMode): List<PointerSignal> {
        val changed = next != mode
        mode = next
        if (changed) haptics += PadHaptic.MODE
        return listOf(PadSignal.Mode(next))
    }

    private fun reset() {
        one = One.CURSOR
        swiped = false
        held = false
        two = Two.UNDECIDED
        twoActive = false
        twoBegun = false
        twoIds = null
        threeActive = false
        done = false
    }

    /** A second finger landed: the one finger press (a long press held) ends. */
    private fun endOne(out: MutableList<PointerSignal>, @Suppress("UNUSED_PARAMETER") nowMs: Long) {
        if (held) {
            out += PointerSignal.Up()
            held = false
        }
        if (one == One.STRIP) out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.END, strip = true)
        if (one == One.THIN) out += thinScroll(Phase.END)
        // The first finger is the one that was scrolling.
        if (one == One.MOUSE_SCROLL) touches.values.firstOrNull()?.let { out += mouseScroll(it, Phase.END) }
        one = One.CURSOR
    }

    // ---- 1.1.10 (issue 35): Spatial Mouse's one finger scroll ----

    /** True while one finger may scroll: [oneFingerScroll] on Spatial Mouse. */
    private fun mouseScrolls(): Boolean = oneFingerScroll && mode == PadMode.SPATIAL_MOUSE

    /** The finger's travel since the scroll was decided, as two fingers' midpoint ([scroll]). */
    private fun mouseScroll(t: PadTouch, phase: Phase): PadSignal.Scroll =
        scroll(t.x - mouseScrollFrom.first, t.y - mouseScrollFrom.second, phase).copy(oneFinger = true)

    // ---- 0.5.21: the thin scroll strip ----

    private fun thinScroll(phase: Phase) =
        PadSignal.Scroll(0f, (-SCROLL_SPEED * thinTravel * stripFactor).coerceIn(-1f, 1f), 0f, thinTravel * stripFactor, phase, strip = true, wheel = true)

    /** A finger move of [dy] pad units along the strip: the page moves it times the speed curve; never the cursor. */
    private fun thinMove(dy: Float, nowMs: Long, out: MutableList<PointerSignal>) {
        val dt = (nowMs - thinLastMs).coerceAtLeast(1L).toFloat()
        thinLastMs = nowMs
        val v = dy * 1000f / dt
        val k = 1f - exp(-dt / StripSpeed.SPEED_MS)
        thinSpeed += (v - thinSpeed) * k
        thinTravel += dy * StripSpeed.gain(thinSpeed)
        tickFor(nowMs)
        out += thinScroll(Phase.UPDATE)
    }

    /** The finger lifted: a flick coasts on ([coast]); a slow or stopped finger ends the scroll here. */
    private fun thinLift(nowMs: Long, out: MutableList<PointerSignal>) {
        val stopped = nowMs - thinLastMs > StripSpeed.STOPPED_MS
        val speed = if (stopped) 0f else thinSpeed * StripSpeed.gain(thinSpeed)
        if (kotlin.math.abs(thinSpeed) >= StripSpeed.MIN_FLING && !stopped) {
            fling = StripFling(speed)
            flingMs = nowMs
        } else {
            out += thinScroll(Phase.END)
        }
    }

    /** One frame of the coast at [nowMs]: the page's travel so far, and END when it stops. */
    fun coast(nowMs: Long): List<PointerSignal> {
        val f = fling ?: return emptyList()
        val dt = (nowMs - flingMs).toFloat()
        if (dt <= 0f) return emptyList()
        flingMs = nowMs
        thinTravel += f.step(dt)
        tickFor(nowMs)
        val out = mutableListOf<PointerSignal>(thinScroll(Phase.UPDATE))
        if (f.done) {
            fling = null
            out += thinScroll(Phase.END)
        }
        return out
    }

    private fun stopCoast(out: MutableList<PointerSignal>) {
        if (fling == null) return
        fling = null
        out += thinScroll(Phase.END)
    }

    private fun tickFor(nowMs: Long) {
        if (kotlin.math.abs(thinTravel - thinTickAt) < StripSpeed.TICK_UNITS) return
        if (nowMs - thinTickMs < StripSpeed.TICK_MIN_MS) return
        thinTickAt = thinTravel
        thinTickMs = nowMs
        haptics += PadHaptic.SCROLL_TICK
    }

    private fun startTwo(out: MutableList<PointerSignal>) {
        val (a, b) = touches.values.take(2)
        twoIds = a.id to b.id
        twoActive = true
        twoBegun = false
        two = Two.UNDECIDED
        twoStartDist = hypot(a.x - b.x, a.y - b.y)
        twoStartMid = Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        out += PadSignal.TwoFinger(0f, 0f, 0f, 0f, twoStartDist, Phase.BEGIN)
    }

    private fun moveTwo(before: Map<Int, PadTouch>, out: MutableList<PointerSignal>) {
        val (ia, ib) = twoIds ?: return
        val a = touches[ia] ?: return
        val b = touches[ib] ?: return
        val pa = before[ia] ?: a
        val pb = before[ib] ?: b
        val dist = hypot(a.x - b.x, a.y - b.y)
        out += PadSignal.TwoFinger(a.x - pa.x, a.y - pa.y, b.x - pb.x, b.y - pb.y, dist, Phase.UPDATE)
        val midX = (a.x + b.x) / 2f - twoStartMid.first
        val midY = (a.y + b.y) / 2f - twoStartMid.second
        val sa = starts[ia] ?: a
        val sb = starts[ib] ?: b
        if (two == Two.UNDECIDED) {
            val ma = sq(a.x - sa.x) + sq(a.y - sa.y)
            val mb = sq(b.x - sb.x) + sq(b.y - sb.y)
            if (ma >= DECIDE_SQ && mb >= DECIDE_SQ) two = decide(twoStartDist, dist, midX, midY)
        }
        when (two) {
            Two.SCROLL -> {
                if (!twoBegun && hypot(midX, midY) > START_TRAVEL) {
                    twoBegun = true
                    out += PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN)
                }
                if (twoBegun) out += scroll(midX, midY, Phase.UPDATE)
            }
            Two.PINCH -> {
                if (!twoBegun && abs(dist - twoStartDist) > START_TRAVEL) {
                    twoBegun = true
                    out += PadSignal.Pinch(1f, Phase.BEGIN)
                }
                if (twoBegun) out += PadSignal.Pinch(dist / twoStartDist.coerceAtLeast(1e-3f), Phase.UPDATE)
            }
            Two.UNDECIDED -> Unit
        }
    }

    private fun endTwo(out: MutableList<PointerSignal>) {
        if (!twoActive) return
        val ids = twoIds
        val a = ids?.let { touches[it.first] }
        val b = ids?.let { touches[it.second] }
        if (twoBegun && a != null && b != null) {
            val midX = (a.x + b.x) / 2f - twoStartMid.first
            val midY = (a.y + b.y) / 2f - twoStartMid.second
            val dist = hypot(a.x - b.x, a.y - b.y)
            when (two) {
                Two.SCROLL -> out += scroll(midX, midY, Phase.END)
                Two.PINCH -> out += PadSignal.Pinch(dist / twoStartDist.coerceAtLeast(1e-3f), Phase.END)
                Two.UNDECIDED -> Unit
            }
        }
        out += PadSignal.TwoFinger(0f, 0f, 0f, 0f, a?.let { p -> b?.let { hypot(p.x - it.x, p.y - it.y) } } ?: twoStartDist, Phase.END)
        twoActive = false
        twoBegun = false
    }

    private fun scroll(midX: Float, midY: Float, phase: Phase): PadSignal.Scroll {
        val x = midX * scrollFactor
        val y = midY * scrollFactor
        return PadSignal.Scroll((SCROLL_SPEED * x).coerceIn(-1f, 1f), (-SCROLL_SPEED * y).coerceIn(-1f, 1f), x, y, phase)
    }

    private fun centroid(): Pair<Float, Float> {
        val all = touches.values
        return Pair(all.sumOf { it.x.toDouble() }.toFloat() / all.size, all.sumOf { it.y.toDouble() }.toFloat() / all.size)
    }

    companion object {
        /** A still finger held this long presses and holds (a long press). */
        const val LONG_PRESS_MS = 500L

        /** Past this a press is moving, never a tap. */
        const val MOVE_LIMIT = 0.15f

        /** 0.5.107: a tap is lifted within this long... */
        const val TAP_MAX_MS = 300L

        /** ...having moved at most this share of [MOVE_LIMIT] (the long press keeps the whole of it). */
        const val TAP_TRAVEL_SHARE = 0.5f

        /** The safe-down filter: a finger that moves this far this soon after landing is a swipe. */
        const val SAFE_DOWN_MS = 150L
        const val SAFE_DOWN_MOVE = 0.03f

        /** Scroll or pinch stays undecided while either finger has moved less than this, squared. */
        const val DECIDE_SQ = 1e-5f

        /** Axis units a pad unit (two fingers: 4 x 0.5 for each of the two). */
        const val SCROLL_SPEED = 4f

        /** A scroll or pinch reaches apps after this much travel, so a two finger tap stays a tap. */
        const val START_TRAVEL = 0.03f

        /** Three fingers: the centroid's shift that makes a swipe, and the time within which a still lift is a tap. */
        const val SWIPE_MIN = 0.04f
        const val THREE_TAP_MS = 600L

        /** The wide scroll strip's width, and its widening for a while after it was used. */
        const val STRIP_WIDTH = 0.35f
        const val STRIP_WIDEN = 1.35f
        const val STRIP_WIDEN_MS = 1000L

        /** Scroll or pinch: |distance change| over the midpoint's move is a pinch. */
        fun decideIsPinch(startDist: Float, dist: Float, midDx: Float, midDy: Float): Boolean =
            abs(dist - startDist) > sqrt(midDx * midDx + midDy * midDy)

        private fun decide(startDist: Float, dist: Float, midDx: Float, midDy: Float): Two =
            if (decideIsPinch(startDist, dist, midDx, midDy)) Two.PINCH else Two.SCROLL

        private fun sq(v: Float) = v * v
    }
}

/**
 * 0.5.17: the phone's Home button: it clicks when released within [CLICK_MS] 0.3 s and long
 * presses at [LONG_PRESS_MS] 1.0 s, which recentres the cursor; its press and its release each
 * drop a held window. 0.5.96: an unused 2 s long press constant is gone.
 */
class HomeButton {
    private var downMs: Long? = null
    private var longFired = false

    fun down(nowMs: Long): List<PointerSignal> {
        downMs = nowMs
        longFired = false
        return listOf(PadSignal.Home(HomeEvent.PRESS))
    }

    fun up(nowMs: Long): List<PointerSignal> {
        val d = downMs ?: return emptyList()
        downMs = null
        val out = mutableListOf<PointerSignal>(PadSignal.Home(HomeEvent.RELEASE))
        if (!longFired && nowMs - d < CLICK_MS) out += PadSignal.Home(HomeEvent.CLICK)
        return out
    }

    fun cancel() {
        downMs = null
    }

    fun tick(nowMs: Long): List<PointerSignal> {
        val d = downMs ?: return emptyList()
        if (longFired || nowMs - d < LONG_PRESS_MS) return emptyList()
        longFired = true
        return listOf(PadSignal.Home(HomeEvent.LONG_PRESS))
    }

    val pressed: Boolean get() = downMs != null

    companion object {
        const val CLICK_MS = 300L
        const val LONG_PRESS_MS = 1000L
    }
}

/**
 * 0.5.17: the screen-lock overlay's hold to unlock: one finger held [HOLD_MS] 1.0 s unlocks; a
 * second finger, or a finger moving faster than [MAX_SPEED_PX_S] 50 px/s, cancels the hold, so a
 * pocket brush or a swipe never unlocks it.
 */
class UnlockHold {
    private var downMs: Long? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastMs = 0L

    val holding: Boolean get() = downMs != null

    /** How far the hold has got, 0..1, for drawing. */
    fun progress(nowMs: Long): Float = downMs?.let { ((nowMs - it).toFloat() / HOLD_MS).coerceIn(0f, 1f) } ?: 0f

    fun down(x: Float, y: Float, pointers: Int, nowMs: Long) {
        if (pointers > 1) {
            downMs = null
            return
        }
        downMs = nowMs
        lastX = x
        lastY = y
        lastMs = nowMs
    }

    fun move(x: Float, y: Float, pointers: Int, nowMs: Long) {
        if (downMs == null) return
        if (pointers > 1) {
            downMs = null
            return
        }
        val dt = (nowMs - lastMs).coerceAtLeast(1L)
        val speed = hypot(x - lastX, y - lastY) * 1000f / dt
        lastX = x
        lastY = y
        lastMs = nowMs
        if (speed > MAX_SPEED_PX_S) downMs = null
    }

    fun up() {
        downMs = null
    }

    /** True once, when the hold reaches [HOLD_MS]. */
    fun tick(nowMs: Long): Boolean {
        val d = downMs ?: return false
        if (nowMs - d < HOLD_MS) return false
        downMs = null
        return true
    }

    companion object {
        const val HOLD_MS = 1000L
        const val MAX_SPEED_PX_S = 50f
    }
}
