package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs

/**
 * 0.5.37 (Mike: "an Immersive button which makes it so only that window appears as a perfectly sized movie like widescreen
 * experience, with auto dimming making the other open app windows, in a cool motion designed visual way, disappear into
 * nothingness, leaving an immersive movie watching experience, kind of like Apple Vision Pro").
 *
 * The motion, in the premium manner (no overshoot, eased both ends for the hero, ease in for what leaves):
 * - **Entering**: the hero window glides from where it is to the cinema spot ([cinema]: straight ahead at [CINEMA_M], as big as fits
 *   in [FIT] of the view, its own shape kept) over [ENTER_MS]. Every other window recedes to [RECEDE] times its distance
 *   (so it shrinks as it goes) and fades to nothing, one after the other [STAGGER_MS] apart, each over [LEAVE_MS].
 * - **Exiting**: the hero glides back over [EXIT_MS]; the others come back from the distance and fade in, staggered.
 * Hidden windows take no presses ([hidden]). The end of each phase is committed (saved, the hero's display resized).
 */
class ImmersiveMode {
    enum class Phase { IDLE, ENTERING, ON, EXITING }

    var phase = Phase.IDLE
        private set
    var heroId: Int? = null
        private set
    private var startMs = 0L
    private var from: Map<Int, SpacePanel> = emptyMap()
    private var cinema: SpacePanel? = null
    private var order: List<Int> = emptyList()

    val active: Boolean get() = phase != Phase.IDLE

    /** Enters with [heroId]'s window from [layout], the cinema in front of [gazeYaw], [gazePitch]. False if it is not there. */
    fun enter(layout: SpaceLayout, heroId: Int, gazeYaw: Float, gazePitch: Float, nowMs: Long, viewWDeg: Float = VIEW_W_DEG, viewHDeg: Float = VIEW_H_DEG, fixedView: Boolean = false): Boolean {
        val hero = layout.panel(heroId) ?: return false
        this.heroId = heroId
        from = layout.panels.associateBy { it.id }
        cinema = cinema(hero, gazeYaw, gazePitch, viewWDeg, viewHDeg, fixedView)
        // The others leave nearest first from the hero outward, so the room empties away from it.
        order = layout.panels.filter { it.id != heroId }.sortedBy { abs(wrapDegrees(it.yawDeg - hero.yawDeg)) }.map { it.id }
        phase = Phase.ENTERING
        startMs = nowMs
        return true
    }

    fun exit(nowMs: Long) {
        if (phase == Phase.ENTERING || phase == Phase.ON) {
            phase = Phase.EXITING
            startMs = nowMs
        }
    }

    data class Step(val layout: SpaceLayout, val commit: Boolean)

    /**
     * The layout to show at [nowMs] from the current [layout] (windows opened or closed meanwhile are left as they are),
     * and whether this step ends a phase (to commit), or null while idle.
     */
    fun step(layout: SpaceLayout, nowMs: Long): Step? {
        val hero = heroId ?: return null
        val c = cinema ?: return null
        val home = from[hero] ?: return null
        val t = nowMs - startMs
        return when (phase) {
            Phase.IDLE, Phase.ON -> null
            Phase.ENTERING -> {
                val k = easeInOut(t.toFloat() / ENTER_MS)
                var l = layout.panel(hero)?.let { layout.replace(blend(home, c, k)) } ?: layout
                for ((i, id) in order.withIndex()) {
                    val o = from[id] ?: continue
                    if (l.panel(id) == null) continue
                    val e = easeIn(((t - i * STAGGER_MS).toFloat() / LEAVE_MS))
                    l = l.replace(PadWindowMath.atDistance(o, o.distanceM * (1f + (RECEDE - 1f) * e)))
                }
                val done = t >= enterTotalMs()
                if (done) phase = Phase.ON
                Step(l, done)
            }
            Phase.EXITING -> {
                val k = easeInOut(t.toFloat() / EXIT_MS)
                var l = layout.panel(hero)?.let { layout.replace(blend(c, home, k)) } ?: layout
                for ((i, id) in order.withIndex()) {
                    val o = from[id] ?: continue
                    if (l.panel(id) == null) continue
                    val e = easeOut(((t - RETURN_DELAY_MS - i * STAGGER_MS).toFloat() / RETURN_MS))
                    l = l.replace(PadWindowMath.atDistance(o, o.distanceM * (RECEDE - (RECEDE - 1f) * e)))
                }
                val done = t >= exitTotalMs()
                if (done) {
                    phase = Phase.IDLE
                    heroId = null
                    cinema = null
                    from = emptyMap()
                }
                Step(l, done)
            }
        }
    }

    /** How visible window [id] is at [nowMs]: 1 for the hero and while idle, fading for the others. */
    fun alpha(id: Int, nowMs: Long): Float {
        if (!active || id == heroId) return 1f
        val i = order.indexOf(id).takeIf { it >= 0 } ?: return 1f
        val t = nowMs - startMs
        return when (phase) {
            Phase.ENTERING -> 1f - easeIn((t - i * STAGGER_MS).toFloat() / LEAVE_MS)
            Phase.ON -> 0f
            Phase.EXITING -> easeOut((t - RETURN_DELAY_MS - i * STAGGER_MS).toFloat() / RETURN_MS)
            Phase.IDLE -> 1f
        }
    }

    /** A window that takes no presses now: the others while entering, on, and until they have mostly faded back in. */
    fun hidden(id: Int, nowMs: Long): Boolean = active && id != heroId && alpha(id, nowMs) < 0.5f

    private fun enterTotalMs() = maxOf(ENTER_MS, (order.size - 1).coerceAtLeast(0) * STAGGER_MS + LEAVE_MS)
    private fun exitTotalMs() = maxOf(EXIT_MS, RETURN_DELAY_MS + (order.size - 1).coerceAtLeast(0) * STAGGER_MS + RETURN_MS)

    companion object {
        // 0.5.38 (Mike: "the screen is too close. it needs to fit well within the user's FoV so the edges aren't cut off"):
        // 0.5.37's 66 deg at 3 m was wider than the glasses' 52 deg view. Now further, and inside [FIT] of the view each way.
        const val CINEMA_M = 5.0f
        const val FIT = 0.88f

        /** 1.6.0: how far a window's shape may be from the view's before Immersive gives it the view's shape. */
        const val RESHAPE_RATIO = 1.2f

        /** The Aura's picture, degrees (vc57's log: 52.2 by 30.8), when the renderer does not pass its own. */
        const val VIEW_W_DEG = 52.2f
        const val VIEW_H_DEG = 30.8f
        const val RECEDE = 1.35f
        const val ENTER_MS = 700L
        const val EXIT_MS = 550L
        const val LEAVE_MS = 450L
        const val RETURN_MS = 400L
        const val RETURN_DELAY_MS = 150L
        const val STAGGER_MS = 60L

        /**
         * The cinema screen for [p]: in front of the gaze, nearly level, as big as fits in [FIT] of a [viewWDeg] by [viewHDeg] view,
         * its shape kept. With [fixedView] (Steady mode) looking up or down shows no more, so it is centred on the view and
         * fits with the buttons above and below it.
         */
        fun cinema(p: SpacePanel, gazeYaw: Float, gazePitch: Float, viewWDeg: Float = VIEW_W_DEG, viewHDeg: Float = VIEW_H_DEG, fixedView: Boolean = false): SpacePanel {
            val shapeAspect = p.heightM / p.widthM.coerceAtLeast(1e-3f)
            val d = CINEMA_M
            // 1.6.0 (GitHub #118: Immersive left much of the view empty for a tall YouTube window): a window whose shape is
            // far from the view's (more than [RESHAPE_RATIO] either way) takes the view's shape, so the app lays itself out
            // to fill it; a window near it (a 16:9 video) keeps its own, so its picture is not letterboxed. Not in Steady
            // mode, whose shape is the ultrawide row's.
            val viewAspect = kotlin.math.tan(viewHDeg * DEG / 2f) / kotlin.math.tan(viewWDeg * DEG / 2f)
            val ratio = shapeAspect / viewAspect.coerceAtLeast(1e-3f)
            val aspect = if (!fixedView && (ratio > RESHAPE_RATIO || ratio < 1f / RESHAPE_RATIO)) viewAspect else shapeAspect
            val pitch = if (fixedView) gazePitch else (gazePitch * 0.3f).coerceIn(-6f, 6f)
            fun metres(deg: Float) = 2f * d * kotlin.math.tan(deg * DEG / 2f)
            fun fitted(roomM: Float): SpacePanel {
                var wM = metres(FIT * viewWDeg)
                if (wM * aspect > roomM) wM = roomM / aspect
                val (wDeg, hDeg) = PadWindowMath.anglesKeepingShape(wM, wM * aspect, d)
                return p.copy(yawDeg = wrapDegrees(gazeYaw), pitchDeg = pitch, distanceM = d, widthDeg = wDeg, heightDeg = hDeg)
            }
            val room = metres((if (fixedView) SteadyArrange.HEIGHT_FIT else FIT) * viewHDeg)
            var c = fitted(room)
            // Twice: the buttons' share grows a little as the window shrinks.
            if (fixedView) repeat(2) { c = fitted(room / (1f + WindowControls.aboveShare(c) + WindowControls.belowShare(c))) }
            return c
        }

        /** [a] to [b] at [k]: direction the short way round, distance and size eased together. */
        fun blend(a: SpacePanel, b: SpacePanel, k: Float): SpacePanel = a.copy(
            yawDeg = wrapDegrees(a.yawDeg + wrapDegrees(b.yawDeg - a.yawDeg) * k),
            pitchDeg = a.pitchDeg + (b.pitchDeg - a.pitchDeg) * k,
            distanceM = a.distanceM + (b.distanceM - a.distanceM) * k,
            widthDeg = a.widthDeg + (b.widthDeg - a.widthDeg) * k,
            heightDeg = a.heightDeg + (b.heightDeg - a.heightDeg) * k,
        )

        /** Cubic ease in and out (a smooth start and landing, no overshoot). */
        fun easeInOut(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        }

        fun easeIn(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * it }

        fun easeOut(x: Float): Float = 1f - (1f - x.coerceIn(0f, 1f)).let { it * it * it }
    }
}
