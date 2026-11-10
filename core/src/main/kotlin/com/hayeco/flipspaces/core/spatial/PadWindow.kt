package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.SwipeDirection
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.5.17: the window controls from the trackpad (move, push and pull, resize, drop, reset), as
 * plain maths on [SpacePanel]s.
 *
 * Pocket XR's panels are sized in degrees and always face the user from the sphere they sit on.
 * A window pushed away should keep its size in the room and look smaller, as a real object does,
 * so push and pull here keep [SpacePanel.widthM] and [SpacePanel.heightM] and recompute the angles
 * ([atDistance]). Scale 1 is Pocket XR's default panel, 40 degrees wide at 1.4 m
 * ([BASE_WIDTH_M], 1.02 m), so a scale range of 0.5 to 3.0 is 0.51 m to 3.06 m wide.
 */
object PadWindowMath {
    /** A held window orbits one radian (57.2958 degrees) a pad unit. */
    const val ORBIT_DEG_PER_UNIT = 57.2958f

    /** Two fingers "the same way": unit directions' dot over this (within 45 degrees), each finger at least [MIN_FINGER] units. */
    const val SAME_WAY_DOT = 0.707f
    const val MIN_FINGER = 0.01f

    /** Push and pull's gain: metres a pad unit, before the distance squared and frame time factors. */
    const val INCREMENT = 3.0f

    /**
     * The frame time in dz = dy x increment x d^2 x dt x 10: a fixed 60 Hz frame. Pocket XR
     * applies it per move rather than per frame, so push and pull do not change speed with the
     * Flip's 120 Hz touch rate or the glasses' rate.
     */
    const val DT = 1f / 60f

    /**
     * 0.5.17: limits of 1.3 to 3.8 m for every push and pull from the trackpad. A panel already outside them (hands bring panels to
     * 0.6 m and send them to 4 m, [PanelLimits]) moves only back towards them, never jumps.
     *
     * 0.5.26: the wider range of [PanelLimits], 0.3 to 15 m, on every path. The d^2 step stays, so far moves stay
     * quick and near ones fine; one move steps at most [MAX_STEP_SHARE] of the distance, so a fast swipe at 15 m
     * cannot jump past the window's whole range in one touch event.
     */
    const val APP_MIN_M = PanelLimits.MIN_DISTANCE
    const val APP_MAX_M = PanelLimits.MAX_DISTANCE

    /**
     * 0.5.26: the most one depth move changes the distance, as a share of it. 0.5.27: 10 % (0.5.26: half), so one fast touch event far out cannot swing a window by metres.
     */
    const val MAX_STEP_SHARE = 0.1f

    /** 0.5.17's scale range, and its cap at this share of the view's width. */
    const val SCALE_MIN = 0.5f
    const val SCALE_MAX = 3.0f
    const val FOV_CAP = 0.8f

    /** Facing the user while dragged: eased by dt x 12 a frame, a time constant of about 83 ms. */
    const val FACE_RATE = 12f

    /** Dropping a held window: a tap released within 0.3 s drops it unless it moved over 0.15 units, at least 2 frames after the pick up. */
    const val DROP_TAP_MS = 300L
    const val DROP_MOVE = 0.15f
    const val DROP_MIN_FRAMES = 2

    /** Reset: in front, 0.2 m below eye level at its distance, upright, a 0.5 s tween. */
    const val RESET_BELOW_M = 0.2f
    const val TWEEN_MS = 500L

    /** Spatial Mouse: swipes ignored 0.3 s after the grab, so the grab's own touch never moves it; depth dy x 3.6 x dt x 50, scale dx x 52 x dt at 45 fps. */
    const val MOUSE_IGNORE_MS = 300L
    const val MOUSE_INCREMENT = 3.6f
    const val MOUSE_DT = 1f / 45f
    const val MOUSE_SCALE_SENSITIVITY = 52f

    /** The held window's guide shows this long after a change. */
    const val GUIDE_MS = 500L

    /** Scale 1: the default panel's width in metres. */
    val BASE_WIDTH_M: Float = 2f * PanelLimits.DEFAULT_DISTANCE * tan(PanelLimits.DEFAULT_WIDTH_DEG * DEG / 2f)

    /** A held window's centre after a one finger move of [dx], [dy] pad units: one radian a unit, up is up. */
    fun orbit(yawDeg: Float, pitchDeg: Float, dx: Float, dy: Float): Pair<Float, Float> =
        Pair(wrapDegrees(yawDeg + dx * ORBIT_DEG_PER_UNIT), (pitchDeg + dy * ORBIT_DEG_PER_UNIT).coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH))

    /**
     * Push and pull or scale, for two fingers' travel ([ax], [ay]) and ([bx], [by]): true when they
     * move the same way, false when they do not, null until each has moved [MIN_FINGER].
     */
    fun sameWay(ax: Float, ay: Float, bx: Float, by: Float): Boolean? {
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < MIN_FINGER || lb < MIN_FINGER) return null
        return (ax * bx + ay * by) / (la * lb) > SAME_WAY_DOT
    }

    /** distance += dy x increment x d^2 x dt x 10: up is further, and far windows move faster than near ones. */
    fun depthStep(dy: Float, increment: Float, distanceM: Float, dt: Float): Float = dy * increment * distanceM * distanceM * dt * 10f

    /** 0.5.26: a step of [step] metres at [distanceM], at most [MAX_STEP_SHARE] of the distance either way. */
    fun limitStep(step: Float, distanceM: Float): Float {
        val most = MAX_STEP_SHARE * distanceM.coerceAtLeast(APP_MIN_M)
        return step.coerceIn(-most, most)
    }

    /** The distance after a step, inside the app window's limits (or back towards them). */
    fun clampDistance(current: Float, next: Float): Float {
        val lo = min(APP_MIN_M, current)
        val hi = maxOf(APP_MAX_M, current)
        return next.coerceIn(lo, hi)
    }

    /** Push and pull by a two finger (or scroll strip) move of [dy] pad units. */
    fun pushPull(panel: SpacePanel, dy: Float, increment: Float = INCREMENT, dt: Float = DT): SpacePanel {
        val d = panel.distanceM
        return atDistance(panel, clampDistance(d, d + limitStep(depthStep(dy, increment, d, dt) * farDamping(d), d)))
    }

    /**
     * [panel] at [distanceM].
     *
     * 0.5.27 (Closer and Further "no longer work" in 0.5.26): 0.5.26 kept the angles, so a window grew
     * in metres exactly as fast as it moved away and looked the same at 0.3 m and 15 m; in 2D and in "2D in 3D" nothing
     * in the picture changed. Now a depth move keeps the window's size in the room (its metres along its surface, the
     * arc when curved, and its height), as 0.5.25 did, so it shrinks as it goes away and grows as it comes
     * in. Its shape is kept: both sides scale together to stay within [PanelLimits.DEPTH_MIN_SIZE_DEG] (3 degrees, so a
     * window really shrinks at 15 m) and the 100 by 70 degree maximum.
     *
     * Its display keeps its pixels and density ([SpacePanel.displayAreaDeg2], [PanelDisplaySize.panelPx]): only a
     * resize lays the app out again, so nothing reflows and the app fills the window at every distance.
     */
    fun atDistance(panel0: SpacePanel, distanceM: Float): SpacePanel {
        if (!distanceM.isFinite() || distanceM <= 0f) return panel0
        if (kotlin.math.abs(distanceM - panel0.distanceM) < 1e-6f) return panel0
        // The app's size is fixed from the angles it had before the move (a window with none yet would take it from the new ones).
        val panel = panel0.withAppSize()
        val area = panel.displayAreaDeg2.takeIf { it > 0f } ?: (panel.widthDeg * panel.heightDeg)
        val arcM = PanelCurve.arcWidthM(panel)
        val hM = panel.heightM
        fun metresFor(deg: Float) = 2f * distanceM * tan(deg * DEG / 2f)
        // The scale on both sides that keeps the shape within the limits at the new distance (1 when it fits).
        val wAt = widthForArc(panel, arcM, distanceM)
        val hAt = angleFor(hM, distanceM)
        var k = 1f
        if (wAt > PanelLimits.MAX_WIDTH_DEG || hAt > PanelLimits.MAX_HEIGHT_DEG) {
            k = minOf(arcFor(panel, PanelLimits.MAX_WIDTH_DEG, distanceM) / arcM, metresFor(PanelLimits.MAX_HEIGHT_DEG) / hM)
        } else if (wAt < PanelLimits.DEPTH_MIN_SIZE_DEG || hAt < PanelLimits.DEPTH_MIN_SIZE_DEG) {
            k = maxOf(arcFor(panel, PanelLimits.DEPTH_MIN_SIZE_DEG, distanceM) / arcM, metresFor(PanelLimits.DEPTH_MIN_SIZE_DEG) / hM)
        }
        val w = widthForArc(panel, arcM * k, distanceM).coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG)
        val h = angleFor(hM * k, distanceM).coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG)
        return panel.copy(distanceM = distanceM, widthDeg = w, heightDeg = h, displayAreaDeg2 = area)
    }

    /** The arc (or flat width) of [panel] at [distanceM] and [widthDeg], metres. */
    private fun arcFor(panel: SpacePanel, widthDeg: Float, distanceM: Float): Float =
        PanelCurve.arcWidthM(panel.copy(distanceM = distanceM, widthDeg = widthDeg))

    /** The angular width that gives [panel] an arc (or flat width) of [arcM] at [distanceM]. */
    private fun widthForArc(panel: SpacePanel, arcM: Float, distanceM: Float): Float {
        if (!PanelCurve.isCurved(panel)) return angleFor(arcM, distanceM)
        // The arc grows with the angle; 40 halvings land well under a thousandth of a degree.
        var lo = 0.01f
        var hi = 179f
        if (arcFor(panel, hi, distanceM) <= arcM) return hi
        repeat(40) {
            val m = (lo + hi) / 2f
            if (arcFor(panel, m, distanceM) < arcM) lo = m else hi = m
        }
        return (lo + hi) / 2f
    }

    /**
     * 0.5.27 (in 0.5.26, at 3.5 m one swipe crossed most of the range, at 9 m one touch event hit the cap): the d^2
     * feel up to [FAR_KNEE_M], then a step growing only with the distance, so a 1 pad
     * unit swipe moves about 1 m at 1.4 m, 2.5 m at 3.5 m and 6 m at 9 m; each swipe takes the window about twice as
     * far (or half as near) out there, never across the whole range.
     */
    fun farDamping(distanceM: Float): Float = if (distanceM <= FAR_KNEE_M) 1f else FAR_KNEE_M / distanceM

    /** 0.5.27: where the d^2 rule gives way to [farDamping], the default window distance. */
    const val FAR_KNEE_M = PanelLimits.DEFAULT_DISTANCE

    /** 0.5.27: the depth line for the log, once a gesture. */
    fun depthLine(panel: SpacePanel, how: String): String =
        "window ${panel.id} ${panel.label} pushed to %.2f m ($how), %.1f by %.1f degrees in view".format(java.util.Locale.ROOT, panel.distanceM, panel.widthDeg, panel.heightDeg)

    /**
     * 0.5.21: the angles of a [wM] by [hM] metre window at [distanceM], within the window limits with its
     * shape kept: both sides scaled together until the short one is at least the 12 degree minimum and
     * neither is over its maximum. 0.5.20 clamped each side alone, so Arrange's far, small windows came
     * out square and their apps would now be laid out square ([PanelDisplaySize] follows the window's
     * shape in metres, the surface its picture is drawn on).
     */
    fun anglesKeepingShape(wM: Float, hM: Float, distanceM: Float): Pair<Float, Float> {
        val d = distanceM.coerceAtLeast(1e-3f)
        if (!wM.isFinite() || !hM.isFinite() || wM <= 0f || hM <= 0f) {
            return angleFor(wM, d).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG) to angleFor(hM, d).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG)
        }
        fun metresFor(deg: Float) = 2f * d * tan(deg * DEG / 2f)
        val minM = metresFor(PanelLimits.MIN_SIZE_DEG)
        var k = maxOf(1f, minM / wM, minM / hM)
        k = minOf(k, metresFor(PanelLimits.MAX_WIDTH_DEG) / wM, metresFor(PanelLimits.MAX_HEIGHT_DEG) / hM)
        return angleFor(wM * k, d).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG) to angleFor(hM * k, d).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG)
    }

    /** The angle (degrees) a [sizeM] wide thing fills at [distanceM]. */
    fun angleFor(sizeM: Float, distanceM: Float): Float = 2f * atan(sizeM / (2f * distanceM)) * RAD

    /** The scale of [panel]: its width over [BASE_WIDTH_M]. */
    fun scaleOf(panel: SpacePanel): Float = panel.widthM / BASE_WIDTH_M

    /**
     * Two finger pinch on a held window, and the cover's Resize: [start] (the window when the pinch began) times
     * [factor], aspect kept. 0.5.41 (Mike: "resizing windows with the trackpad ... as if I'm limited to how much bigger or
     * smaller", while the corner handle "works great"): the corner handle's limits, [PanelLimits.MIN_SIZE_DEG] up to
     * [PanelLimits.MAX_WIDTH_DEG] by [PanelLimits.MAX_HEIGHT_DEG], instead of 0.5.17's scale 0.5 to 3 times the
     * base width and its stop at [FOV_CAP] of the view (about 42 degrees wide on the Aura). The window's shape is kept at
     * the limits too (a side is never clamped alone). [frustum] is no longer used.
     */
    @Suppress("UNUSED_PARAMETER")
    fun scaled(start: SpacePanel, factor: Float, frustum: ViewFrustum): SpacePanel {
        if (!factor.isFinite() || factor <= 0f) return start
        val d = start.distanceM
        fun metres(deg: Float) = 2f * d * tan(deg * DEG / 2f)
        val lo = maxOf(metres(PanelLimits.MIN_SIZE_DEG) / start.widthM, metres(PanelLimits.MIN_SIZE_DEG) / start.heightM)
        val hi = minOf(metres(PanelLimits.MAX_WIDTH_DEG) / start.widthM, metres(PanelLimits.MAX_HEIGHT_DEG) / start.heightM)
        val k = factor.coerceIn(minOf(lo, 1f), maxOf(hi, 1f))
        if (k == 1f) return start
        val w = angleFor(start.widthM * k, d)
        val h = angleFor(start.heightM * k, d)
        // 0.5.21: Mike's own resize: the app follows it ([PanelDisplaySize]).
        return start.copy(widthDeg = w, heightDeg = h).sizedByUser()
    }

    /** The share of the way to the target a frame of [dtS] seconds eases: dt x 12, at most all of it. */
    fun faceFraction(dtS: Float): Float = (dtS * FACE_RATE).coerceIn(0f, 1f)

    /** One frame of the eased drag from ([yaw], [pitch]) towards ([targetYaw], [targetPitch]). */
    fun faceStep(yaw: Float, pitch: Float, targetYaw: Float, targetPitch: Float, dtS: Float): Pair<Float, Float> {
        val t = faceFraction(dtS)
        return Pair(wrapDegrees(yaw + wrapDegrees(targetYaw - yaw) * t), pitch + (targetPitch - pitch) * t)
    }

    /** Whether a press of [pressMs] that moved [moved] pad units, [frames] frames after the pick up, drops the window. */
    fun dropsOnTap(pressMs: Long, moved: Float, frames: Int): Boolean =
        frames >= DROP_MIN_FRAMES && pressMs < DROP_TAP_MS && moved <= DROP_MOVE

    /** Where Reset puts [panel]: straight ahead of [headYawDeg], [RESET_BELOW_M] below eye level at its distance, upright. */
    fun resetPose(panel: SpacePanel, headYawDeg: Float): SpacePanel {
        val d = panel.distanceM.coerceAtLeast(RESET_BELOW_M * 1.01f)
        return panel.copy(yawDeg = wrapDegrees(headYawDeg), pitchDeg = -asin(RESET_BELOW_M / d) * RAD)
    }

    /** Spatial Mouse's swipe: vertical when 2 |dy| > |dx| (within about 63 degrees of vertical). */
    fun mouseSwipeIsDepth(dx: Float, dy: Float): Boolean = 2f * abs(dy) > abs(dx)

    /** Spatial Mouse's depth by a vertical swipe: dy x 3.6 x dt x 50, about 4.0 m a pad unit at 45 fps. */
    fun mouseDepthStep(dy: Float): Float = dy * MOUSE_INCREMENT * MOUSE_DT * 50f

    /** Spatial Mouse's scale by a sideways swipe: rate += dx x 52 x dt, right is larger. */
    fun mouseScaleStep(dx: Float): Float = dx * MOUSE_SCALE_SENSITIVITY * MOUSE_DT
}

/**
 * 0.5.17: a panel's move from [from] to [to] over [durationMs] (an OutQuad ease: quick, then settling),
 * for Reset (0.5 s) and the arrange actions.
 */
class PanelTween(val from: SpacePanel, val to: SpacePanel, val startMs: Long, val durationMs: Long = PadWindowMath.TWEEN_MS) {
    fun done(nowMs: Long): Boolean = nowMs - startMs >= durationMs

    fun at(nowMs: Long): SpacePanel {
        val t = ((nowMs - startMs).toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val e = ease(t)
        return to.copy(
            yawDeg = wrapDegrees(from.yawDeg + wrapDegrees(to.yawDeg - from.yawDeg) * e),
            pitchDeg = from.pitchDeg + (to.pitchDeg - from.pitchDeg) * e,
            distanceM = from.distanceM + (to.distanceM - from.distanceM) * e,
            widthDeg = from.widthDeg + (to.widthDeg - from.widthDeg) * e,
            heightDeg = from.heightDeg + (to.heightDeg - from.heightDeg) * e,
        )
    }

    companion object {
        /** OutQuad. */
        fun ease(t: Float): Float = 1f - (1f - t) * (1f - t)
    }
}

/** The arrange actions, used instead of snapping: Collect (side by side) and a circle layout. */
enum class ArrangeKind(val label: String) {
    COLLECT("Arrange"),
    CIRCLE("Circle"),

    // 0.5.32: the Menu's layouts by window count ([ArrangeLayouts.forCount]).
    CENTER("Center"),
    THEATER("Theater"),
    SIDE_BY_SIDE("Side by side"),
    MAIN_SIDE("Main + side"),
    STACKED("Stacked"),
    ACROSS("Across"),
    GRID("Grid"),
    MAIN_STRIP("Main + strip"),
    WRAPAROUND("All around"),

    /** 0.5.88: not a layout: every window held together, to push, pull, resize and turn as one ([SpatialPointer.holdAll]). */
    ADJUST_ALL("Adjust all"),
}

/**
 * 0.5.17: where the arrange actions put the panels. There is no snap; there are layouts.
 * - Collect: every window at [COLLECT_RADIUS_M] 3.78 m, [COLLECT_SCALE] 0.72 times
 *   its size, side by side on the arc with [PADDING_M] 0.3 m between, up to
 *   [PER_ROW] a row, rows [ROW_GAP_M] 0.37 m apart, centred on the gaze at eye level.
 * - Circle: the main window (the focused one, else the front one) ahead at [CIRCLE_RADIUS_M] 3 m,
 *   scale [CIRCLE_MAIN_SCALE] 1.4 (inside the scale range 0.5 to 3.0),
 *   the rest either side on the same circle at their own size.
 */
object Arrange {
    const val COLLECT_RADIUS_M = 3.78f
    const val COLLECT_SCALE = 0.72f
    const val ROW_GAP_M = 0.37f
    const val PADDING_M = 0.3f
    const val PER_ROW = 3
    const val CIRCLE_RADIUS_M = 3f
    const val CIRCLE_MAIN_SCALE = 1.4f

    fun collect(panels: List<SpacePanel>, headYawDeg: Float): List<SpacePanel> {
        if (panels.isEmpty()) return emptyList()
        // Sizes after Pocket XR's angular limits (12 degrees at least), so rows never overlap; 0.5.21: each
        // window's shape kept ([PadWindowMath.anglesKeepingShape]).
        fun metres(deg: Float): Float = 2f * COLLECT_RADIUS_M * tan(deg * DEG / 2f)
        val sized = panels.map { p ->
            val (w, h) = PadWindowMath.anglesKeepingShape(p.widthM * COLLECT_SCALE, p.heightM * COLLECT_SCALE, COLLECT_RADIUS_M)
            Triple(p, metres(w), metres(h))
        }
        val rows = sized.chunked(PER_ROW)
        val rowHeights = rows.map { r -> r.maxOf { it.third } }
        val total = rowHeights.sum() + ROW_GAP_M * (rows.size - 1)
        var top = total / 2f
        val out = mutableListOf<SpacePanel>()
        rows.forEachIndexed { i, row ->
            val centreY = top - rowHeights[i] / 2f
            top -= rowHeights[i] + ROW_GAP_M
            val arc = row.sumOf { it.second.toDouble() }.toFloat() + PADDING_M * (row.size - 1)
            var left = -arc / 2f
            for ((p, w, h) in row) {
                val centreX = left + w / 2f
                left += w + PADDING_M
                val yaw = headYawDeg + centreX / COLLECT_RADIUS_M * RAD
                val pitch = asin((centreY / COLLECT_RADIUS_M).coerceIn(-0.99f, 0.99f)) * RAD
                val (wDeg, hDeg) = PadWindowMath.anglesKeepingShape(w, h, COLLECT_RADIUS_M)
                out += p.copy(
                    yawDeg = wrapDegrees(yaw),
                    pitchDeg = pitch.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
                    distanceM = COLLECT_RADIUS_M,
                    widthDeg = wDeg,
                    heightDeg = hDeg,
                )
            }
        }
        return out
    }

    fun circle(panels: List<SpacePanel>, headYawDeg: Float, mainId: Int?): List<SpacePanel> {
        if (panels.isEmpty()) return emptyList()
        val main = panels.firstOrNull { it.id == mainId } ?: panels.last()
        val r = CIRCLE_RADIUS_M
        val aspect = main.heightM / main.widthM.coerceAtLeast(1e-3f)
        val mainW = PadWindowMath.BASE_WIDTH_M * CIRCLE_MAIN_SCALE
        val (mainWDeg, mainHDeg) = PadWindowMath.anglesKeepingShape(mainW, mainW * aspect, r)
        val placedMain = main.copy(
            yawDeg = wrapDegrees(headYawDeg),
            pitchDeg = 0f,
            distanceM = r,
            widthDeg = mainWDeg,
            heightDeg = mainHDeg,
        )
        val out = mutableListOf(placedMain)
        var right = mainW / 2f + PADDING_M
        var left = -mainW / 2f - PADDING_M
        panels.filter { it.id != main.id }.forEachIndexed { i, p ->
            val w = p.widthM
            val x = if (i % 2 == 0) (right + w / 2f).also { right += w + PADDING_M } else (left - w / 2f).also { left -= w + PADDING_M }
            val (wDeg, hDeg) = PadWindowMath.anglesKeepingShape(w, p.heightM, r)
            out += p.copy(
                yawDeg = wrapDegrees(headYawDeg + x / r * RAD),
                pitchDeg = 0f,
                distanceM = r,
                widthDeg = wDeg,
                heightDeg = hDeg,
            )
        }
        return out
    }
}

/**
 * 0.5.17: the title bars show only while a pointer is on their window, so they never clutter the
 * view: fully shown while pointed at and for
 * [STAY_MS] 1.8 s after, then fading out over [FADE_MS] 0.7 s. A bar never pointed at is hidden.
 */
class BarFade {
    private val lastPointed = HashMap<Int, Long>()

    fun update(pointed: Collection<Int>, alive: Collection<Int>, nowMs: Long) {
        for (id in pointed) lastPointed[id] = nowMs
        lastPointed.keys.retainAll(alive.toSet())
    }

    fun alpha(id: Int, nowMs: Long): Float {
        val last = lastPointed[id] ?: return 0f
        val since = nowMs - last
        if (since <= STAY_MS) return 1f
        return (1f - (since - STAY_MS).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    companion object {
        const val STAY_MS = 1800L
        const val FADE_MS = 700L
    }
}

/** 0.5.17: the held window's eight corner brackets fade in over 0.6 s and out over 0.3 s, in [Look.HELD]. */
class HoldFrameFade {
    var alpha = 0f
        private set
    private var lastMs = Long.MIN_VALUE

    fun update(held: Boolean, nowMs: Long): Float {
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceIn(0L, 200L)
        lastMs = nowMs
        alpha = if (held) (alpha + dt / IN_MS).coerceAtMost(1f) else (alpha - dt / OUT_MS).coerceAtLeast(0f)
        return alpha
    }

    companion object {
        const val IN_MS = 600f
        const val OUT_MS = 300f
        const val COLOR = com.hayeco.flipspaces.core.Look.HELD
    }
}

/**
 * 0.5.17: the trackpad cursor's reticle: [DEFAULT_DISTANCE_M] 2.5 m out
 * when over nothing; the hover ring tweens in and out over [HOVER_MS] 0.15 s from [HOVER_MIN_SCALE]
 * 0.6 to full size; a press shrinks it to [PRESS_SCALE] 0.7.
 */
class ReticleAnim {
    private var hover = 0f
    private var lastMs = Long.MIN_VALUE

    data class Look(val dotScale: Float, val ringScale: Float, val ringAlpha: Float)

    fun at(hovering: Boolean, pressed: Boolean, nowMs: Long): Look {
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceIn(0L, 200L)
        lastMs = nowMs
        val step = dt / HOVER_MS
        hover = if (hovering) (hover + step).coerceAtMost(1f) else (hover - step).coerceAtLeast(0f)
        val press = if (pressed) PRESS_SCALE else 1f
        return Look(press, (HOVER_MIN_SCALE + (1f - HOVER_MIN_SCALE) * hover) * press, hover)
    }

    companion object {
        const val DEFAULT_DISTANCE_M = 2.5f
        const val HOVER_MS = 150f
        const val HOVER_MIN_SCALE = 0.6f
        const val PRESS_SCALE = 0.7f
    }
}

/**
 * 0.5.17: the Space's tracking modes: follow the head smoothly, stay put around the body, or be
 * locked to the room. Most glasses give 3 degrees of freedom only, so Space locked (6DoF) is shown disabled.
 * 1.4.0: Space locked is available while a 6DoF source is live ([SixDof]); it draws with the head's position as well
 * as its turn. Otherwise it is Body Anchor.
 */
enum class FollowMode(val label: String, val dof: String, private val always: Boolean) {
    SMOOTH_FOLLOW("Smooth Follow", "0DoF", true),
    BODY_ANCHOR("Body Anchor", "3DoF", true),
    SPACE_LOCKED("Space locked", "6DoF", false);

    val available: Boolean get() = always || SixDof.live
}

/**
 * 0.5.17: the view's rotation for a [FollowMode]. Body Anchor draws with the head pose as it is
 * (Pocket XR's head tracked Space). Smooth Follow keeps the Space in front: an anchor eases
 * towards the head ([FOLLOW_RATE] a second, a 0.5 s time constant, chosen by feel) and
 * the Space is drawn with the head relative to it.
 */
class FollowView {
    var mode: FollowMode = FollowMode.BODY_ANCHOR
    private var anchor: Quat = Quat.IDENTITY
    private var lastMs = Long.MIN_VALUE

    /**
     * 0.5.38 (Mike: "as soon as I activate it, it's no longer centred and moves everything far to the right"): the head
     * pose when Smooth Follow went on. The anchor follows the head's turn since then (q times start's inverse), not the
     * head itself, so the view at the switch is kept: 0.5.17 to 0.5.37 eased it to the room's straight ahead, sliding every
     * window by the head's yaw at the switch.
     */
    private var start: Quat? = null

    fun update(tracked: Quat?, nowMs: Long) {
        val dt = if (lastMs == Long.MIN_VALUE) 0f else ((nowMs - lastMs).coerceIn(0L, 200L) / 1000f)
        lastMs = nowMs
        val q = HeadView.usable(tracked)
        if (mode != FollowMode.SMOOTH_FOLLOW || q == null) {
            anchor = Quat.IDENTITY
            if (mode != FollowMode.SMOOTH_FOLLOW) start = null
            return
        }
        val s = start ?: q.also {
            start = it
            anchor = Quat.IDENTITY
        }
        anchor = anchor.slerp((q * s.conjugate()).normalized(), 1f - exp(-dt * FOLLOW_RATE)).normalized()
    }

    /** 0.5.40: Recentre: Smooth Follow starts again from the next pose (the head tracker's new straight ahead). */
    fun recentre() {
        start = null
        anchor = Quat.IDENTITY
    }

    /** The pose to draw and aim with for the tracked pose [tracked]. */
    fun relative(tracked: Quat?): Quat? {
        if (mode != FollowMode.SMOOTH_FOLLOW) return tracked
        val q = HeadView.usable(tracked) ?: return tracked
        return (anchor.conjugate() * q).normalized()
    }

    companion object {
        const val FOLLOW_RATE = 2f
    }
}

/**
 * 0.5.17: Spatial Mouse's ray from the phone's rotation vector (`SensorManager.getRotationMatrixFromVector`,
 * row major, device to world with x east, y north, z up). The phone points with its top edge
 * (device +y), like a laser pointer: yaw right positive, pitch up positive.
 *
 * 1.1.9 (issue 33, a Retroid Nova held landscape: "The up and down mouse is tied to lifting and lowering the left side of
 * the device"): the sensor's axes are the screen's natural way up, and the Nova's is portrait, run landscape at a quarter
 * turn. The aim now takes the screen's top edge as it is drawn at [angles]' rotation (Display.getRotation), the same
 * axes as `SensorManager.remapCoordinateSystem` gives ([toScreen]), whoever set the orientation.
 */
object PhoneAim {
    /**
     * [r] with its columns the screen's right, top edge and face at [rotation] quarter turns (Surface.ROTATION_0..270),
     * as `remapCoordinateSystem` with (X, Y), (Y, -X), (-X, -Y) and (-Y, X).
     */
    fun toScreen(r: FloatArray, rotation: Int): FloatArray {
        val out = r.copyOf()
        if (r.size < 9) return out
        for (row in 0 until 3) {
            val x = r[row * 3]
            val y = r[row * 3 + 1]
            when (rotation and 3) {
                1 -> { out[row * 3] = -y; out[row * 3 + 1] = x }
                2 -> { out[row * 3] = -x; out[row * 3 + 1] = -y }
                3 -> { out[row * 3] = y; out[row * 3 + 1] = -x }
            }
        }
        return out
    }

    fun angles(r: FloatArray, rotation: Int = 0): Pair<Float, Float>? {
        if (r.size < 9) return null
        // The screen's top edge: the device's +y, +x, -y or -x by the rotation.
        val (col, sign) = when (rotation and 3) {
            1 -> 0 to 1f
            2 -> 1 to -1f
            3 -> 0 to -1f
            else -> 1 to 1f
        }
        val x = sign * r[col]
        val y = sign * r[3 + col]
        val z = sign * r[6 + col]
        val l = sqrt(x * x + y * y + z * z)
        if (!l.isFinite() || l < 1e-6f) return null
        return Pair(atan2(x / l, y / l) * RAD, asin((z / l).coerceIn(-1f, 1f)) * RAD)
    }
}

/** What a three finger swipe does in the Space: each opens its screen and the other swipe hides it. */
enum class SwipeRoute { OPEN_LAUNCHER, CLOSE_LAUNCHER, OPEN_CONTROLS, CLOSE_CONTROLS, BACK, NOTHING }

object ThreeFingerRoute {
    /** Up: Recent apps (the launcher, with its strip of open panels and recent apps); down: the Control panel. */
    fun route(direction: SwipeDirection, launcherOpen: Boolean, controlsOpen: Boolean): SwipeRoute = when (direction) {
        SwipeDirection.UP -> when {
            controlsOpen -> SwipeRoute.CLOSE_CONTROLS
            launcherOpen -> SwipeRoute.NOTHING
            else -> SwipeRoute.OPEN_LAUNCHER
        }
        SwipeDirection.DOWN -> when {
            launcherOpen -> SwipeRoute.CLOSE_LAUNCHER
            controlsOpen -> SwipeRoute.NOTHING
            else -> SwipeRoute.OPEN_CONTROLS
        }
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> SwipeRoute.BACK
    }
}

/** A Control panel button. */
sealed interface ControlKind {
    data class Follow(val mode: FollowMode) : ControlKind
    data class Layout(val kind: ArrangeKind) : ControlKind
    data object Recentre : ControlKind
    data object Close : ControlKind

    /** 0.5.21: the "Glasses warp (beta)" switch, off by default. */
    data object GlassesWarp : ControlKind
}

/** One Control panel button, a tile facing the user like the launcher's. */
data class ControlTile(val kind: ControlKind, val yawDeg: Float, val pitchDeg: Float, val widthDeg: Float, val heightDeg: Float, val enabled: Boolean = true) {
    fun asPanel(): SpacePanel = SpacePanel(-2, "", "", "", yawDeg, pitchDeg, SpaceControlPanel.DISTANCE_M, widthDeg, heightDeg)

    fun hit(ray: Ray): Boolean {
        val a = Angles.of(ray.dir)
        return abs(wrapDegrees(a.yawDeg - yawDeg)) <= widthDeg / 2f && abs(a.pitchDeg - pitchDeg) <= heightDeg / 2f
    }
}

/** What a press on the Control panel asks for. */
sealed interface ControlAction {
    data class SetFollow(val mode: FollowMode) : ControlAction
    data class ArrangeAll(val kind: ArrangeKind) : ControlAction
    data object Recentre : ControlAction
    data object Closed : ControlAction
    data object Disabled : ControlAction

    /** 0.5.21: turn the Glasses warp switch [on] or off (remembered). */
    data class SetGlassesWarp(val on: Boolean) : ControlAction
}

/**
 * 0.5.17: the Space's Control panel, opened by a three finger swipe down: the
 * tracking modes (Smooth Follow, Body Anchor, Space locked, the last disabled on these glasses),
 * then its layouts (Arrange, Circle), Recentre and Close. A pure model like [SpaceLauncher].
 * 0.5.21: a third row, the "Glasses warp (beta)" switch, pressed like any button
 * from the head pointer, a hand or either trackpad (the phone's and the cover screen's); the panel
 * stays open so its new state shows.
 */
class SpaceControlPanel {
    var isOpen = false
        private set
    private var yaw = 0f
    private var pitch = 0f

    fun open(gazeYaw: Float, gazePitch: Float) {
        isOpen = true
        yaw = wrapDegrees(gazeYaw)
        pitch = gazePitch.coerceIn(-SpaceLauncher.MAX_ANCHOR_PITCH, SpaceLauncher.MAX_ANCHOR_PITCH)
    }

    fun close() {
        isOpen = false
    }

    fun tiles(@Suppress("UNUSED_PARAMETER") current: FollowMode): List<ControlTile> {
        if (!isOpen) return emptyList()
        val step = TILE_W + GAP
        val rowTop = pitch + (TILE_H + GAP)
        val rowMiddle = pitch
        val rowBottom = pitch - (TILE_H + GAP)
        val out = mutableListOf<ControlTile>()
        FollowMode.entries.forEachIndexed { i, m ->
            out += ControlTile(ControlKind.Follow(m), yaw + (i - 1) * step, rowTop, TILE_W, TILE_H, enabled = m.available)
        }
        val second = listOf(ControlKind.Layout(ArrangeKind.COLLECT), ControlKind.Layout(ArrangeKind.CIRCLE), ControlKind.Recentre, ControlKind.Close)
        second.forEachIndexed { i, k ->
            out += ControlTile(k, yaw + (i - 1.5f) * (SMALL_W + GAP), rowMiddle, SMALL_W, TILE_H)
        }
        // 0.5.36: the Glasses warp tile is gone (the Aura leaves its warp within a second when a phone drives it).
        return out
    }

    /** The backdrop behind the buttons, as a panel. */
    fun backdrop(): SpacePanel? = if (!isOpen) null else SpacePanel(-3, "", "", "", yaw, pitch, DISTANCE_M + 0.02f, 3 * TILE_W + 4 * GAP, 3 * TILE_H + 4 * GAP)

    fun covers(ray: Ray): Boolean {
        val b = backdrop() ?: return false
        val a = Angles.of(ray.dir)
        return abs(wrapDegrees(a.yawDeg - b.yawDeg)) <= b.widthDeg / 2f && abs(a.pitchDeg - b.pitchDeg) <= b.heightDeg / 2f
    }

    /** A press along [ray]: a button's action, or Closed when it missed every button (the panel closes). */
    fun press(ray: Ray, current: FollowMode, glassesWarp: Boolean = false): ControlAction {
        val tile = tiles(current).firstOrNull { it.hit(ray) }
        if (tile == null) {
            close()
            return ControlAction.Closed
        }
        if (!tile.enabled) return ControlAction.Disabled
        return when (val k = tile.kind) {
            is ControlKind.Follow -> ControlAction.SetFollow(k.mode)
            is ControlKind.Layout -> {
                close()
                ControlAction.ArrangeAll(k.kind)
            }
            ControlKind.Recentre -> {
                close()
                ControlAction.Recentre
            }
            ControlKind.Close -> {
                close()
                ControlAction.Closed
            }
            ControlKind.GlassesWarp -> ControlAction.SetGlassesWarp(!glassesWarp)
        }
    }

    companion object {
        const val DISTANCE_M = 1.0f
        const val TILE_W = 9f
        const val SMALL_W = 6.5f
        const val TILE_H = 5f
        const val GAP = 1.2f
    }
}

/**
 * 1.1.10 (issue 35): Spatial Mouse's aim held still while one finger scrolls. The dot is the phone's turn since the
 * reference ([SpatialPointer]'s recentre), so moving the reference with the phone keeps the dot where it is: a hand
 * that sways as a thumb slides along the pad no longer carries the dot off what it scrolls. After the scroll the phone
 * aims on from where the dot was left, with no jump.
 */
object AimHold {
    /**
     * The reference's phone yaw and pitch ([refYaw], [refPitch]) slid by the phone's turn from ([lastYaw], [lastPitch]) to
     * ([yaw], [pitch]), so the aim (phone minus reference) stays the same. Yaw wraps at 180 degrees.
     */
    fun slide(refYaw: Float, refPitch: Float, lastYaw: Float, lastPitch: Float, yaw: Float, pitch: Float): Pair<Float, Float> =
        Pair(wrapDegrees(refYaw + wrapDegrees(yaw - lastYaw)), refPitch + (pitch - lastPitch))
}
