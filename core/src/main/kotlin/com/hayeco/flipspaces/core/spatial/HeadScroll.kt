package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

/**
 * 1.4.6 (John Davis: "use head tracking in steady / anchor mode to move the screens left or right, basically mimicking
 * scroll left or right", and the same in follow mode): Head scroll, off by default.
 *
 * Where the screens are held still in front (Steady mode, where the glasses hold the canvas, and Smooth Follow, where
 * they move with the head), a small turn of the head past a dead zone slides the row of screens that way, like a
 * joystick: turn a little right and the screens on the right come towards the middle. The further past the dead zone,
 * the faster, up to a limit; looking back to the middle stops it. The speed eases in and out so the row never jumps,
 * and the row stops at its ends: the last window on either side comes to the middle and no further.
 *
 * Reading a window near the middle stays still: the dead zone is wider than a glance across the window in front.
 * Looking down (at the phone) or up past [MAX_PITCH_DEG] does not scroll.
 *
 * [offsetYawDeg] is the head's turn from where the screens are held (right positive, as [Angles]); the result is added
 * to the view's turn ([HeadView]'s yaw), the same turn the trackpad's scroll over empty space changes.
 */
class HeadScroll(
    val deadZoneDeg: Float = DEAD_ZONE_DEG,
    val gainPerS: Float = GAIN_PER_S,
    val maxDegS: Float = MAX_DEG_S,
    val easeS: Float = EASE_S,
) {
    /** The row's current speed, degrees a second (right positive). */
    var speedDegS = 0f
        private set

    /** The turn to add to the view's yaw for a frame [dtS] seconds long, the head [offsetYawDeg] off and [pitchDeg] up. */
    fun step(offsetYawDeg: Float, pitchDeg: Float, dtS: Float): Float {
        if (!offsetYawDeg.isFinite() || !pitchDeg.isFinite() || !dtS.isFinite() || dtS <= 0f) return 0f
        val dt = dtS.coerceAtMost(MAX_STEP_S)
        val target = targetSpeed(offsetYawDeg, pitchDeg)
        val k = 1f - exp(-dt / easeS)
        speedDegS += (target - speedDegS) * k
        if (abs(speedDegS) < STOP_DEG_S && target == 0f) speedDegS = 0f
        return speedDegS * dt
    }

    /** The speed wanted with the head [offsetYawDeg] off and [pitchDeg] up, before easing. */
    fun targetSpeed(offsetYawDeg: Float, pitchDeg: Float): Float {
        if (abs(pitchDeg) > MAX_PITCH_DEG) return 0f
        val past = abs(offsetYawDeg) - deadZoneDeg
        if (past <= 0f) return 0f
        return sign(offsetYawDeg) * (past * gainPerS).coerceAtMost(maxDegS)
    }

    /** Still at once (a recentre, a window grabbed, the mode left). */
    fun stop() {
        speedDegS = 0f
    }

    companion object {
        const val DEAD_ZONE_DEG = 10f
        const val GAIN_PER_S = 3f
        const val MAX_DEG_S = 45f
        const val EASE_S = 0.18f
        const val MAX_PITCH_DEG = 30f

        /**
         * Smooth Follow's anchor rate while Head scroll is on (a 2 s time constant, not 0.5 s): the screens stay in front
         * long enough for a small turn to scroll them, then come round to where the head settles, as before.
         */
        const val FOLLOW_RATE = 0.5f
        private const val MAX_STEP_S = 0.1f
        private const val STOP_DEG_S = 0.5f

        /**
         * The view's yaw after adding [deltaDeg] to [viewYawDeg], kept between the first and last of [panels] (their
         * middles, around their mean) so the row stops at its ends. A view already outside them (turned there by other
         * means) is only kept from going further out. Windows that go round the head ([RecentreContent.SPREAD_MAX_DEG])
         * have no ends: the turn is free.
         */
        fun clamp(viewYawDeg: Float, deltaDeg: Float, panels: List<SpacePanel>): Float {
            val next = wrapDegrees(viewYawDeg + deltaDeg)
            if (panels.isEmpty()) return next
            val sx = panels.sumOf { cos(it.yawDeg * DEG).toDouble() }
            val sz = panels.sumOf { sin(it.yawDeg * DEG).toDouble() }
            val mean = if (sx * sx + sz * sz < 1e-6) panels.first().yawDeg else (atan2(sz, sx) / DEG).toFloat()
            val lo = panels.minOf { wrapDegrees(it.yawDeg - mean) }
            val hi = panels.maxOf { wrapDegrees(it.yawDeg - mean) }
            val spread = panels.maxOf { wrapDegrees(it.yawDeg - mean) + it.widthDeg / 2f } -
                panels.minOf { wrapDegrees(it.yawDeg - mean) - it.widthDeg / 2f }
            if (spread > RecentreContent.SPREAD_MAX_DEG) return next
            val now = wrapDegrees(viewYawDeg - mean)
            val then = now + deltaDeg
            val kept = when {
                deltaDeg > 0f -> if (then > hi) maxOf(now, hi) else then
                deltaDeg < 0f -> if (then < lo) minOf(now, lo) else then
                else -> now
            }
            return wrapDegrees(mean + kept)
        }
    }
}
