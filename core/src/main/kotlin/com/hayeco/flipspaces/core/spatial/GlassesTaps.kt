package com.hayeco.flipspaces.core.spatial

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 1.6.12: taps on the glasses' frame, read from their own motion sensor: two taps recentre, three open or close the
 * launcher. Ported from Pocket XR for Steam Deck's taps.py (0.7.5, asked for there by Pierre Zammit, 23 Nov 2026), with
 * the same values.
 *
 * A tap is a short, sharp jolt: the acceleration jumps at least [TAP_G] away from its 30 ms average and is back under
 * [QUIET_G] within [SPIKE_MAX_S], after [QUIET_BEFORE_S] of quiet, while the head turned slower than [TURN_MAX] on
 * average over the last 150 ms. Head turns and walking move the glasses more slowly. Taps [TAP_GAP_MIN_S] to
 * [TAP_GAP_MAX_S] apart make a run; once [RUN_END_S] passes without another, a run of two or three is reported.
 *
 * Not yet tried with real taps on glasses (nor is the Deck's), so the switch is off at first.
 */
class GlassesTaps {
    private var t0 = 0.0
    private var lastT = Double.NaN
    private val avg = FloatArray(3)
    private var turn = 0.0
    private var loudAt = -1e9
    private var spikeStart = Double.NaN
    private var spikeTurn = 0.0
    private val taps = ArrayList<Double>()

    fun reset() {
        lastT = Double.NaN
        turn = 0.0
        loudAt = -1e9
        spikeStart = Double.NaN
        taps.clear()
    }

    /**
     * One motion report: [t] in seconds, [gyro] in rad/s, [accel] in m/s2. Returns 2 or 3 when a run of that many taps
     * just ended, else 0.
     */
    fun feed(t: Double, gyro: Vec3, accel: Vec3): Int {
        val dt = t - lastT
        if (lastT.isNaN() || !(dt > 0.0 && dt < 0.1)) {
            reset()
            t0 = t
            lastT = t
            avg[0] = accel.x; avg[1] = accel.y; avg[2] = accel.z
            return 0
        }
        lastT = t
        val dx = accel.x - avg[0]
        val dy = accel.y - avg[1]
        val dz = accel.z - avg[2]
        val d = sqrt((dx * dx + dy * dy + dz * dz).toDouble()) / G
        val k = (1.0 - exp(-dt / AVG_S)).toFloat()
        avg[0] += dx * k; avg[1] += dy * k; avg[2] += dz * k
        val w = sqrt((gyro.x * gyro.x + gyro.y * gyro.y + gyro.z * gyro.z).toDouble())
        turn += (w - turn) * (1.0 - exp(-dt / TURN_AVG_S))
        if (t - t0 < WARMUP_S) {
            loudAt = t
            return 0
        }
        if (!spikeStart.isNaN()) {
            if (d < QUIET_G) {
                val start = spikeStart
                spikeStart = Double.NaN
                if (t - start <= SPIKE_MAX_S && spikeTurn < TURN_MAX) tap(start)
                loudAt = t
            } else if (t - spikeStart > SPIKE_MAX_S) {
                loudAt = t // too long for a tap: a push or a knock, wait for it to settle
            }
        } else if (d >= TAP_G && t - loudAt >= QUIET_BEFORE_S) {
            spikeStart = t
            spikeTurn = turn
        } else if (d >= QUIET_G) {
            loudAt = t
        }
        if (taps.isNotEmpty() && spikeStart.isNaN() && t - taps.last() > RUN_END_S) {
            val n = taps.size
            taps.clear()
            if (n == 2 || n == 3) return n
        }
        return 0
    }

    private fun tap(start: Double) {
        if (taps.isNotEmpty()) {
            val gap = start - taps.last()
            if (gap < TAP_GAP_MIN_S) return // the same tap ringing on
            if (gap > TAP_GAP_MAX_S) taps.clear()
        }
        taps += start
    }

    companion object {
        const val G = 9.81
        const val TAP_G = 0.8
        const val QUIET_G = 0.3
        const val SPIKE_MAX_S = 0.04
        const val QUIET_BEFORE_S = 0.06
        val TURN_MAX = Math.toRadians(60.0)
        const val TAP_GAP_MIN_S = 0.08
        const val TAP_GAP_MAX_S = 0.45
        const val RUN_END_S = 0.5
        const val WARMUP_S = 0.5
        const val AVG_S = 0.03
        const val TURN_AVG_S = 0.15
    }
}
