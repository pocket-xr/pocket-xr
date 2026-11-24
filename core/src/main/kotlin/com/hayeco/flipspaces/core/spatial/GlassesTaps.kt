package com.hayeco.flipspaces.core.spatial

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Taps on the glasses' frame, read from their own motion sensor: two taps recentre, three open or close the launcher.
 *
 * 1.6.17: ported from Pocket XR for Steam Deck's taps.py as of 0.7.11, which a tester (Pierre Zammit, XREAL XBX A01+)
 * tuned with his recordings; 1.6.12's first port needed firm taps ("they still need a lot of force to register").
 * What sets a tap apart is how suddenly it starts: his taps jumped 0.23 to 0.42 g within 3 ms, plain head movement
 * never over 0.10 g. So a jolt starts at [LOG_G] away from the 30 ms average and ends once it has stayed under it for
 * [SETTLE_S] (the swing after a tap is part of the same jolt). It counts as a tap when it peaked at [TAP_G] or more,
 * jumped [JERK_G] within 3 ms, lasted no longer than [SPIKE_MAX_S], came [QUIET_BEFORE_S] after the last jolt, while
 * the head turned slower than [TURN_MAX]. Taps [TAP_GAP_MIN_S] to [TAP_GAP_MAX_S] apart make a run; once [RUN_END_S]
 * passes, a run of two or three is reported. [sensitivity] (25 to 300 %) divides the jolt and jump a tap needs.
 *
 * Every jolt goes to [onLog] with why it did or didn't count, at most [LOG_MAX] lines a minute.
 */
class GlassesTaps(private val onLog: ((String) -> Unit)? = null) {
    var sensitivity: Int = 100
        set(value) {
            field = value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
            val k = 100.0 / field
            tapG = TAP_G * k
            jerkG = JERK_G * k
            logG = minOf(LOG_G, tapG * 0.6)
        }

    private var tapG = TAP_G
    private var jerkG = JERK_G
    private var logG = LOG_G

    private var t0 = 0.0
    private var lastT = Double.NaN
    private val avg = DoubleArray(3)
    private var turn = 0.0
    private val recentT = ArrayDeque<Double>()
    private val recentA = ArrayDeque<DoubleArray>()
    private var quietFrom = -1e9
    private var jolt: Jolt? = null
    private val taps = ArrayList<Double>()
    private val logged = ArrayDeque<Double>()

    private class Jolt(val start: Double, var peak: Double, var jerk: Double, val turn: Double, val quiet: Double, var under: Double?)

    fun reset() {
        lastT = Double.NaN
        turn = 0.0
        recentT.clear(); recentA.clear()
        quietFrom = -1e9
        jolt = null
        taps.clear()
    }

    /** One report: [t] seconds, [gyro] rad/s, [accel] m/s2. Returns 2 or 3 when a run of that many just ended, else 0. */
    fun feed(t: Double, gyro: Vec3, accel: Vec3): Int {
        val a = doubleArrayOf(accel.x.toDouble(), accel.y.toDouble(), accel.z.toDouble())
        val dt = t - lastT
        if (lastT.isNaN() || !(dt > 0.0 && dt < 0.1)) {
            reset()
            t0 = t
            lastT = t
            a.copyInto(avg)
            recentT.addLast(t); recentA.addLast(a)
            return 0
        }
        lastT = t
        val d = dist(a, avg) / G
        val k = 1.0 - exp(-dt / AVG_S)
        for (i in 0..2) avg[i] += (a[i] - avg[i]) * k
        var old: DoubleArray? = null
        for (i in recentT.indices.reversed()) if (t - recentT[i] >= JERK_S) { old = recentA[i]; break }
        val jump = old?.let { dist(a, it) / G } ?: 0.0
        recentT.addLast(t); recentA.addLast(a)
        while (recentT.size > 2 && t - recentT[1] >= JERK_S) { recentT.removeFirst(); recentA.removeFirst() }
        val w = sqrt((gyro.x * gyro.x + gyro.y * gyro.y + gyro.z * gyro.z).toDouble())
        val turnBefore = turn
        turn += (w - turn) * (1.0 - exp(-dt / TURN_AVG_S))
        if (t - t0 < WARMUP_S) {
            quietFrom = t
            return 0
        }
        val j = jolt
        if (j == null) {
            if (d >= logG) jolt = Jolt(t, d, jump, turnBefore, t - quietFrom, null)
        } else {
            j.peak = maxOf(j.peak, d)
            j.jerk = maxOf(j.jerk, jump)
            if (d >= logG) j.under = null else if (j.under == null) j.under = t
            val under = j.under
            if ((under != null && t - under >= SETTLE_S) || t - j.start > 0.3) {
                jolt = null
                quietFrom = t
                judge(j, (under ?: t) - j.start)
            }
        }
        if (taps.isNotEmpty() && jolt == null && t - taps.last() > RUN_END_S) {
            val n = taps.size
            taps.clear()
            if (n == 2 || n == 3) return n
            log(t, "taps: a run of $n ended: only two or three do something")
        }
        return 0
    }

    private fun judge(j: Jolt, lasted: Double) {
        val gap = if (taps.isEmpty()) null else j.start - taps.last()
        val why = when {
            j.peak < tapG -> "too soft (under %.2f g)".format(java.util.Locale.ROOT, tapG)
            j.jerk < jerkG -> "not sharp (a tap jumps %.2f g within 3 ms)".format(java.util.Locale.ROOT, jerkG)
            lasted > SPIKE_MAX_S -> "too long (a tap is under ${(SPIKE_MAX_S * 1000).toInt()} ms)"
            j.quiet < QUIET_BEFORE_S -> "too soon after other movement"
            j.turn >= TURN_MAX -> "the head was turning"
            gap != null && gap < TAP_GAP_MIN_S -> "too soon after the last tap"
            else -> null
        }
        val verdict = if (why == null) {
            if (gap != null && gap > TAP_GAP_MAX_S) taps.clear()
            taps += j.start
            "a tap, ${taps.size} in this run"
        } else {
            "not a tap: $why"
        }
        log(j.start, "taps: jolt %.2f g (jump %.2f g in 3 ms), %.0f ms, still %.0f ms before, head turning %.0f deg/s, last tap %s before: %s".format(
            java.util.Locale.ROOT, j.peak, j.jerk, lasted * 1000, minOf(j.quiet, 9.99) * 1000, Math.toDegrees(j.turn),
            gap?.let { "%.0f ms".format(java.util.Locale.ROOT, it * 1000) } ?: "none", verdict,
        ))
    }

    private fun log(t: Double, line: String) {
        val l = onLog ?: return
        while (logged.isNotEmpty() && t - logged.first() >= 60.0) logged.removeFirst()
        if (logged.size >= LOG_MAX) return
        logged.addLast(t)
        l(line)
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    companion object {
        const val G = 9.81
        const val LOG_G = 0.15
        const val TAP_G = 0.25
        const val JERK_G = 0.15
        const val JERK_S = 0.003
        const val SETTLE_S = 0.008
        const val SPIKE_MAX_S = 0.15
        const val QUIET_BEFORE_S = 0.06
        val TURN_MAX = Math.toRadians(60.0)
        const val TAP_GAP_MIN_S = 0.2
        const val TAP_GAP_MAX_S = 0.7
        const val RUN_END_S = 0.75
        const val WARMUP_S = 0.5
        const val AVG_S = 0.03
        const val TURN_AVG_S = 0.15
        const val SENSITIVITY_MIN = 25
        const val SENSITIVITY_MAX = 300
        const val LOG_MAX = 120
    }
}
