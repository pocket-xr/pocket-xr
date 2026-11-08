package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.20 (Mike's "the windows still move with my head's tremor"): the
 * head's turn from its newest IMU sample to the frame's light, predicted from the raw gyro by a
 * predictor that learns, from the gyro itself, how the head's own motion goes on.
 *
 * 0.5.19 extrapolated a filtered rate and a filtered acceleration (a 3 ms rate filter, a One Euro
 * filter, a 10 ms acceleration filter). Each filter lags, and at 6 to 12 Hz the lag turned the
 * extrapolation into an overshoot: at 37 ms the window moved 2.1 times the head's own 8 Hz tremor
 * and 3.7 times its 12 Hz one. A world locked window has to take the head's whole
 * motion out, on time, so nothing here smooths or holds back the pose.
 *
 * The prediction is a weighted sum of the last [taps] gyro means ([tapMs] each, newest first):
 * turn = sum_k c_k w_k, per axis. The weights for each horizon in [horizonsMs] are a least squares
 * fit of what the head actually did: every sample, the turn over each horizon that has just
 * finished is known from the gyro, and the features it should have been predicted from are kept,
 * so the fit is the best linear predictor of this head's motion, with the gyro's noise in it, over
 * the last [memoryS] seconds. A tremor that repeats (a 6 to 12 Hz rhythm) is learned and carried to
 * the light; a random one is predicted as far as it can be; and on a table, where nothing but noise
 * moves the gyro, the fit learns that nothing is predictable and the weights spread out, which
 * keeps the noise out without any filter. Three rules are kept exactly whatever was learned: a
 * steady turn, a steadily speeding up turn and one whose rate bends as a parabola are carried to
 * the light in full (sum c = h and the next two moments), so the start and stop of a head turn are
 * never lagged, whatever the weights learned while the head was still. The fit is pulled
 * ([loading]) toward [prior], a parabola fit to the last [priorMs] of rate (a lag free prediction),
 * so with little motion seen it is that.
 */
class TremorPredictor(
    val taps: Int = 64,
    val tapMs: Double = 2.0,
    val horizonsMs: DoubleArray = doubleArrayOf(4.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0, 40.0, 46.0, 52.0, 60.0),
    val memoryS: Double = 2.0,
    val loading: Double = 0.01,
    val priorMs: Double = 24.0,
    val solveEveryMs: Double = 100.0,
    val maxGapMs: Double = 4.0,
    /** Learning on (false: the prior alone, a fixed lag free predictor). */
    val learn: Boolean = true,
    /** The rate's moments kept exact: 2 (a steady and a speeding turn) or 3 (and a parabolic one). */
    val exactOrder: Int = 2,
    /** 0.5.22: the learned weights used only as far as they beat the prior on the turns just made ([blendStep]). False is 0.5.21. */
    val blend: Boolean = false,
    /**
     * 1.2.3 (Mike's short lags and audio pops came with heat; the head thread was half a core, a quarter of it this
     * fit's per sample update at 1000 samples a second): the fit's statistics take every [learnEvery]th sample,
     * weighted [learnEvery] times. The taps are 2 ms means, so neighbouring samples carry nearly the same features;
     * the regularisation scales with the same weights, so the fit is the same in expectation. The band filters
     * still see every sample. 1 is every sample, as before.
     */
    val learnEvery: Int = 1,
) {
    private val nh = horizonsMs.size
    private val cap = 256
    private val tNs = LongArray(cap)
    private val wx = DoubleArray(cap)
    private val wy = DoubleArray(cap)
    private val wz = DoubleArray(cap)
    /** Prefix sums of rate x dt (rad) per axis, and of rate per axis (for the tap means). */
    private val ix = DoubleArray(cap)
    private val iy = DoubleArray(cap)
    private val iz = DoubleArray(cap)
    private val sx = DoubleArray(cap)
    private val sy = DoubleArray(cap)
    private val sz = DoubleArray(cap)
    private var n = 0L
    private var filled = 0
    private var stepMs = 1.0
    private var m = 2

    /** Each sample's features, per axis, kept for the horizons to finish ([cap] slots of [taps]). */
    private val feat = Array(3) { Array(cap) { DoubleArray(taps) } }
    private val featOk = BooleanArray(cap)

    private val r = Array(taps) { DoubleArray(taps) }
    private val rh = Array(nh) { DoubleArray(taps) }
    private var learned = 0.0
    private var sinceSolveMs = 0.0

    /** Current weights per horizon (s a tap). */
    private val weights = Array(nh) { DoubleArray(taps) }
    private val priors = Array(nh) { DoubleArray(taps) }

    /** Fits since the start, and whether the weights are learned or still the prior. */
    var solves = 0
        private set

    /*
     * 0.5.55 (Mike's log: a GC every 16 s, 1.5 MB/s allocated): kept work arrays. [turn] ran every sample for the still
     * blend and several times a frame for the pose, and made four or five 64-tap arrays and a dozen small ones each
     * time; [solve], ten times a second while worn, made two 64 x 64 matrices and about forty arrays (some 100 KB).
     * Each is filled by the same arithmetic in the same order as the array it replaces, so every weight and turn is
     * the same to the bit (HeadAlloc0555Test). Like the rest of this class, not for two threads at once: the head
     * tracker uses it only under its predictor's lock. Declared before init: [resetWeights] uses them.
     */
    private val priorS = DoubleArray(taps)
    private val priorB = DoubleArray(3)
    private val priorG = DoubleArray(9)
    private val priorInv = DoubleArray(9)
    private val priorT = DoubleArray(3)
    private val priorU = DoubleArray(3)
    private val wPrior = DoubleArray(taps)
    private val turnLearned = DoubleArray(taps)
    private val turnExact = DoubleArray(taps)
    private val turnInterp = DoubleArray(taps)
    private val turnC = DoubleArray(taps)
    private val turnOut = FloatArray(3)
    private val bandV = DoubleArray(3)
    private val solveA = Array(taps) { DoubleArray(taps) }
    private val solveL = Array(taps) { DoubleArray(taps) }
    private val solveCs = Array(3) { DoubleArray(taps) }
    private val solveUs = Array(3) { DoubleArray(taps) }
    private val solveG = DoubleArray(9)
    private val solveGi = DoubleArray(9)
    private val solveB = DoubleArray(taps)
    private val solveY = DoubleArray(taps)
    private val solveX = DoubleArray(taps)
    private val solveWant = DoubleArray(3)
    private val solveE = DoubleArray(3)
    private val solveYc = DoubleArray(3)
    private val solveW = Array(nh) { DoubleArray(taps) }

    init {
        resetWeights()
    }

    private fun resetWeights() {
        for (i in 0 until nh) {
            prior(horizonsMs[i] / 1000.0).copyInto(priors[i])
            priors[i].copyInto(weights[i])
        }
    }

    /** The centre of tap k, seconds back from the newest sample. */
    private fun back(k: Int): Double = (k * m + (m - 1) / 2.0) * stepMs / 1000.0

    /**
     * The lag free prediction: a parabola through the first [priorMs] of taps (the rate now, its
     * slope and its curve at the newest sample), integrated over h. Exact for a turn whose rate
     * changes as a parabola, so a turn's start and stop are carried in full.
     */
    fun prior(h: Double): DoubleArray = priorInto(h, DoubleArray(taps))

    /** 0.5.55: [prior] into [out] (every tap written), with the kept work arrays; returns [out]. */
    private fun priorInto(h: Double, out: DoubleArray): DoubleArray {
        val p = (priorMs / tapMs).roundToInt().coerceIn(4, taps)
        // Least squares on the basis 1, s (and s^2) (s the tap's time, negative): weights for a, b (and c).
        val s = priorS
        for (k in 0 until p) s[k] = -back(k)
        // g row by row (g[i * 3 + j]).
        val g = priorG
        for (i in 0 until 3) for (j in 0 until 3) g[i * 3 + j] = if (i == j && i >= exactOrder) 1.0 else 0.0
        val b = priorB
        for (k in 0 until p) {
            b[0] = 1.0
            b[1] = s[k]
            b[2] = s[k] * s[k]
            for (i in 0 until exactOrder) for (j in 0 until exactOrder) g[i * 3 + j] += b[i] * b[j]
        }
        val inv = inverse3Into(g, priorInv)
        // The turn is a h + b h^2 / 2 (+ c h^3 / 3).
        val t = priorT
        t[0] = h
        t[1] = h * h / 2
        t[2] = if (exactOrder >= 3) h * h * h / 3 else 0.0
        val u = priorU
        for (i in 0 until 3) {
            var sum = 0.0
            for (j in 0 until 3) sum += t[j] * inv[j * 3 + i]
            u[i] = sum
        }
        for (k in 0 until taps) out[k] = if (k >= p) 0.0 else u[0] + u[1] * s[k] + u[2] * s[k] * s[k]
        return out
    }

    /** The inverse of the 3 x 3 [m] (row by row, m[i * 3 + j]) into [out], the same way; returns [out]. */
    private fun inverse3Into(m: DoubleArray, out: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        out[0] = (e * i - f * h) / det; out[1] = (c * h - b * i) / det; out[2] = (b * f - c * e) / det
        out[3] = (f * g - d * i) / det; out[4] = (a * i - c * g) / det; out[5] = (c * d - a * f) / det
        out[6] = (d * h - e * g) / det; out[7] = (b * g - a * h) / det; out[8] = (a * e - b * d) / det
        return out
    }

    /** True once the stream holds enough samples to predict from. */
    val ready: Boolean get() = filled >= taps * m + 1

    fun clear() {
        filled = 0
        for (i in featOk.indices) featOk[i] = false
    }

    /** Forgets what was learned too (a new session). */
    fun reset() {
        clear()
        for (row in r) row.fill(0.0)
        for (row in rh) row.fill(0.0)
        learned = 0.0
        sinceSolveMs = 0.0
        solves = 0
        bandRatePower = 0.0
        bandPowerRad2 = 0.0
        bandMiss = 1.0
        errPower = 0.0
        turnPower = 0.0
        errBp.forEach { it.reset() }
        blendNum.fill(0.0)
        blendDen.fill(0.0)
        blendN.fill(0.0)
        for (ax in 0 until 3) {
            bpX1[ax] = 0.0; bpX2[ax] = 0.0; bpY1[ax] = 0.0; bpY2[ax] = 0.0
        }
        resetWeights()
    }

    private fun slot(j: Long) = ((j % cap + cap) % cap).toInt()

    /** One gyro sample (body rate, rad/s, bias removed) at [hostNs]. */
    fun add(hostNs: Long, omega: Vec3, learnNow: Boolean = true) {
        if (filled > 0) {
            val last = tNs[slot(n - 1)]
            val gapMs = (hostNs - last) / 1e6
            if (gapMs <= 0.0) return
            if (gapMs > maxGapMs) clear() else stepMs += (gapMs - stepMs) * 0.01
        }
        val i = slot(n)
        val prev = slot(n - 1)
        val dt = if (filled > 0) (hostNs - tNs[prev]) / 1e9 else stepMs / 1000.0
        tNs[i] = hostNs
        wx[i] = omega.x.toDouble()
        wy[i] = omega.y.toDouble()
        wz[i] = omega.z.toDouble()
        val base = filled > 0
        ix[i] = (if (base) ix[prev] else 0.0) + wx[i] * dt
        iy[i] = (if (base) iy[prev] else 0.0) + wy[i] * dt
        iz[i] = (if (base) iz[prev] else 0.0) + wz[i] * dt
        sx[i] = (if (base) sx[prev] else 0.0) + wx[i]
        sy[i] = (if (base) sy[prev] else 0.0) + wy[i]
        sz[i] = (if (base) sz[prev] else 0.0) + wz[i]
        if (filled == 0) m = (tapMs / stepMs).roundToInt().coerceAtLeast(1)
        n++
        if (filled < cap) filled++
        featOk[i] = false
        if (!ready) return
        features(i)
        if (!learn || !learnNow) {
            pendingDt = 0.0
            pendingSamples = 0
            return
        }
        trackBand(dt)
        checkStep(i, dt)
        pendingDt += dt
        pendingSamples++
        if (pendingSamples >= learnEvery) {
            learnStep(i, pendingDt, pendingSamples.toDouble())
            pendingDt = 0.0
            pendingSamples = 0
        }
    }

    private var pendingDt = 0.0
    private var pendingSamples = 0

    /** Every sample: how the current weights did at [checkIndex], for [bandMiss] (its filters need every sample). */
    private fun checkStep(i: Int, dt: Double) {
        if (bandWeight <= 0.0) return
        val h = checkIndex
        if (h < 0 || h >= nh) return
        val back = (horizonsMs[h] / stepMs).roundToInt()
        if (back >= filled - 1) return
        val j = slot(n - 1 - back)
        if (!featOk[j]) return
        val c = weights[h]
        checkBand(doubleArrayOf(ix[i] - ix[j], iy[i] - iy[j], iz[i] - iz[j]), doubleArrayOf(dot(c, feat[0][j]), dot(c, feat[1][j]), dot(c, feat[2][j])), dt)
    }

    /** Tap means ending at slot [i] into feat[*][i]. */
    private fun features(i: Int) {
        val fx = feat[0][i]
        val fy = feat[1][i]
        val fz = feat[2][i]
        val newest = n - 1
        for (k in 0 until taps) {
            val end = slot(newest - k * m)
            val start = slot(newest - (k + 1) * m)
            fx[k] = (sx[end] - sx[start]) / m
            fy[k] = (sy[end] - sy[start]) / m
            fz[k] = (sz[end] - sz[start]) / m
        }
        featOk[i] = true
    }

    /** The fit's statistics: [w] samples' worth, [dt] seconds since the last step. */
    private fun learnStep(i: Int, dt: Double, w: Double) {
        val a = exp(-dt / memoryS)
        // R: the features' second moments (the three axes share one predictor).
        for (p in 0 until taps) {
            val rp = r[p]
            val x0 = feat[0][i][p]
            val x1 = feat[1][i][p]
            val x2 = feat[2][i][p]
            val f0 = feat[0][i]
            val f1 = feat[1][i]
            val f2 = feat[2][i]
            if (w == 1.0) {
                for (q in 0..p) rp[q] = rp[q] * a + x0 * f0[q] + x1 * f1[q] + x2 * f2[q]
            } else {
                for (q in 0..p) rp[q] = rp[q] * a + w * (x0 * f0[q] + x1 * f1[q] + x2 * f2[q])
            }
        }
        learned = learned * a + w
        val newest = n - 1
        for (h in 0 until nh) {
            for (q in 0 until taps) rh[h][q] *= a
            val back = (horizonsMs[h] / stepMs).roundToInt()
            if (back >= filled - 1) continue
            val j = slot(newest - back)
            if (!featOk[j]) continue
            // The turn from sample j to now, per axis.
            val tx = ix[i] - ix[j]
            val ty = iy[i] - iy[j]
            val tz = iz[i] - iz[j]
            val fx = feat[0][j]
            val fy = feat[1][j]
            val fz = feat[2][j]
            val row = rh[h]
            if (w == 1.0) {
                for (q in 0 until taps) row[q] += fx[q] * tx + fy[q] * ty + fz[q] * tz
            } else {
                for (q in 0 until taps) row[q] += w * (fx[q] * tx + fy[q] * ty + fz[q] * tz)
            }
            if (blend) blendStep(h, a, w, fx, fy, fz, tx, ty, tz)
        }
        sinceSolveMs += dt * 1000
        if (sinceSolveMs >= solveEveryMs && learned > 200) {
            sinceSolveMs = 0.0
            solve()
        }
    }

    /**
     * 0.5.22: how much of the
     * learned weights' departure from the prior to use, fitted per horizon from the turns that have just
     * finished. With l the learned prediction, p the prior's and t the turn that came, the share s that makes
     * p + s (l - p) closest to t over the fit's memory is E[(t - p)(l - p)] / E[(l - p)^2], held to 0 to 1. A head
     * whose tremor repeats keeps the learned weights (s near 1); on Mike's three worn still heads the learned
     * weights did worse than the prior on the turns they had already made, and s takes them back toward it.
     * Both are lag free and exact for a steady and a speeding turn, so a turn's start and stop are carried in
     * full whatever s is; nothing smooths, holds or damps the pose.
     */
    private fun blendStep(h: Int, a: Double, w: Double, fx: DoubleArray, fy: DoubleArray, fz: DoubleArray, tx: Double, ty: Double, tz: Double) {
        if (solves == 0) return
        val c = weights[h]
        val p = priors[h]
        var lx = 0.0; var ly = 0.0; var lz = 0.0
        var px = 0.0; var py = 0.0; var pz = 0.0
        for (k in 0 until taps) {
            lx += c[k] * fx[k]; ly += c[k] * fy[k]; lz += c[k] * fz[k]
            px += p[k] * fx[k]; py += p[k] * fy[k]; pz += p[k] * fz[k]
        }
        val dx = lx - px; val dy = ly - py; val dz = lz - pz
        blendNum[h] = blendNum[h] * a + w * ((tx - px) * dx + (ty - py) * dy + (tz - pz) * dz)
        blendDen[h] = blendDen[h] * a + w * (dx * dx + dy * dy + dz * dz)
        blendN[h] = blendN[h] * a + w
    }

    /** 0.5.22: the fitted share of the learned weights at each horizon ([blendStep]); 1 until measured. */
    fun learnedShareAt(h: Int): Double {
        if (!blend || blendN[h] < BLEND_MIN_SAMPLES || blendDen[h] <= 1e-30) return 1.0
        return (blendNum[h] / blendDen[h]).coerceIn(0.0, 1.0)
    }

    /** 0.5.22: the fitted share at [aheadMs], between the two nearest horizons as [weightsFor] blends them. */
    fun learnedShare(aheadMs: Double): Double {
        val h = aheadMs.coerceIn(0.0, horizonsMs.last())
        if (h <= horizonsMs[0]) return learnedShareAt(0)
        var i = 0
        while (i + 1 < nh && horizonsMs[i + 1] < h) i++
        val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
        return learnedShareAt(i) * (1 - f) + learnedShareAt(i + 1) * f
    }

    private val blendNum = DoubleArray(nh)
    private val blendDen = DoubleArray(nh)
    private val blendN = DoubleArray(nh)

    /**
     * Ridge to the prior, with the steady and the ramp turn kept exactly (two equality constraints).
     *
     * 0.5.21 (on Mike's worn head the learned weights kept 172 % of an 8 Hz and
     * 578 % of a 12 Hz head motion, because the fit is ruled by the 0.1 to 0.6 degree sway and nothing
     * held its response at the tremor's frequencies, where the 2 s of data say little): the fit is
     * regularised in frequency. Beside the head's own samples it is given virtual ones of a tremor
     * whose future cannot be known, from [BAND_LOW_HZ] to [BAND_HIGH_HZ], [bandWeight] times as strong
     * as the 5 to 14 Hz motion the gyro measured over the same memory ([bandPowerRad2]). For such a
     * tremor the best prediction of the turn ahead is to take its present angle out of the pose ("drop
     * the band"), which leaves 100 % of it on screen, the least an unforeseeable tremor can be held to.
     * Where the real samples show a repeating tremor they outweigh the virtual ones and it is still
     * learned below 100 %; where they say nothing (the band's quiet edges, an incoherent tremor) the
     * virtual ones rule and the response cannot run away to 5 times. The exact moments are kept, so a
     * head turn's start and stop are carried in full, and nothing smooths, holds or damps the pose.
     */
    private fun solve() {
        // 0.5.55: into the kept matrix, every entry written as before.
        val a = solveA
        for (p in 0 until taps) {
            val row = a[p]
            for (q in 0 until taps) row[q] = if (q <= p) r[p][q] else r[q][p]
        }
        var trace = 0.0
        for (p in 0 until taps) trace += a[p][p]
        val mu = loading * trace / taps + 1e-12
        for (p in 0 until taps) a[p][p] += mu
        var extra: DoubleArray? = null
        if (bandWeight > 0.0 && bandPowerRad2 > 0.0) {
            val (re, im) = bandRows()
            // Each band frequency's share of the measured angle power, as samples over the fit's memory,
            // as strong as the band was found unpredictable ([bandMiss]).
            val k = bandWeight * bandMiss * learned * bandPowerRad2 / re.size
            for (p in 0 until taps) for (q in 0 until taps) {
                var s = 0.0
                for (f in re.indices) s += re[f][p] * re[f][q] + im[f][p] * im[f][q]
                a[p][q] += k * s
            }
            extra = DoubleArray(taps) { p -> var s = 0.0; for (f in re.indices) s -= re[f][p]; s * k }
        }
        // 0.5.55: [fit] fills [solveW]; the weights change only when it all came out finite, as before.
        if (!fit(a, mu, extra)) return
        for (h in 0 until nh) solveW[h].copyInto(weights[h])
        solves++
    }

    /**
     * 0.5.21: how strong the virtual unforeseeable tremor is against the measured band power ([solve]);
     * 0 is 0.5.20's fit.
     */
    var bandWeight: Double = BAND_WEIGHT

    /** 0.5.21: the gyro's 5 to 14 Hz motion over the fit's memory, as mean square angle (rad^2, the three axes summed). */
    var bandPowerRad2 = 0.0
        private set

    /**
     * 0.5.21: how unpredictable the band turned out, 0 to 1: the out of sample error of the weights in
     * use at [BAND_CHECK_MS] ahead, in the band, over the band's own turn there (both band passed, over
     * the fit's memory). A steady rhythm the fit has learned reads near 0 and is left to the data; a
     * tremor it cannot foresee reads 1 and gets the full virtual pull. 1 until measured.
     */
    var bandMiss = 1.0
        private set
    private val errBp = Array(2) { BandPass() }
    private var errPower = 0.0
    private var turnPower = 0.0

    /** A band pass on three axes, sharing the predictor's band. */
    private inner class BandPass {
        private val x1 = DoubleArray(3)
        private val x2 = DoubleArray(3)
        private val y1 = DoubleArray(3)
        private val y2 = DoubleArray(3)

        fun power(v: DoubleArray): Double {
            var p = 0.0
            for (ax in 0 until 3) {
                val y = bpB0 * v[ax] - bpB0 * x2[ax] - bpA1 * y1[ax] - bpA2 * y2[ax]
                x2[ax] = x1[ax]
                x1[ax] = v[ax]
                y2[ax] = y1[ax]
                y1[ax] = y
                p += y * y
            }
            return p
        }

        fun reset() {
            x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        }
    }

    /** One sample of the out of sample check: the true turn over [BAND_CHECK_MS] ending now against what the weights said then. */
    private fun checkBand(truth: DoubleArray, said: DoubleArray, dt: Double) {
        val k = 1 - exp(-dt / memoryS)
        val e = DoubleArray(3) { truth[it] - said[it] }
        errPower += (errBp[0].power(e) - errPower) * k
        turnPower += (errBp[1].power(truth) - turnPower) * k
        if (turnPower > 1e-30) bandMiss = (errPower / turnPower).coerceIn(0.0, 1.0)
    }

    // A band pass per axis on the rate for [bandPowerRad2].
    private val bpX1 = DoubleArray(3)
    private val bpX2 = DoubleArray(3)
    private val bpY1 = DoubleArray(3)
    private val bpY2 = DoubleArray(3)
    private var bpRate = 0.0
    private var bpB0 = 0.0
    private var bpA1 = 0.0
    private var bpA2 = 0.0
    private var bandRatePower = 0.0

    /** The band's rate power, followed with the fit's memory, then as an angle at the band's centre. */
    private fun trackBand(dt: Double) {
        val fs = 1000.0 / stepMs
        if (abs(fs - bpRate) > 1.0) {
            bpRate = fs
            val fc = sqrt(BAND_LOW_HZ * BAND_HIGH_HZ)
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * fc / (BAND_HIGH_HZ - BAND_LOW_HZ))
            val a0 = 1 + alpha
            bpB0 = alpha / a0
            bpA1 = -2 * cos(w0) / a0
            bpA2 = (1 - alpha) / a0
        }
        val i = slot(n - 1)
        // 0.5.55: a kept array (a new one each sample while learning).
        val v = bandV
        v[0] = wx[i]; v[1] = wy[i]; v[2] = wz[i]
        var p = 0.0
        for (ax in 0 until 3) {
            val y = bpB0 * v[ax] - bpB0 * bpX2[ax] - bpA1 * bpY1[ax] - bpA2 * bpY2[ax]
            bpX2[ax] = bpX1[ax]
            bpX1[ax] = v[ax]
            bpY2[ax] = bpY1[ax]
            bpY1[ax] = y
            p += y * y
        }
        val k = 1 - exp(-dt / memoryS)
        bandRatePower += (p - bandRatePower) * k
        val wc = 2 * PI * sqrt(BAND_LOW_HZ * BAND_HIGH_HZ)
        bandPowerRad2 = bandRatePower / (wc * wc)
    }

    /**
     * The weights for every horizon from the normal matrix [a] (ridge [mu] included), the data's
     * right hand side plus [extra] (the band penalty's), with the exact moments kept, into [solveW]; false if singular.
     * 0.5.55: every array is a kept one ([solveL], [solveCs], ...), filled as the new ones were.
     */
    private fun fit(a: Array<DoubleArray>, mu: Double, extra: DoubleArray?): Boolean {
        val l = choleskyInto(a, solveL) ?: return false
        // The constraints: a steady, a steadily speeding and a parabolic rate are carried exactly.
        val cs = solveCs
        for (k in 0 until taps) {
            cs[0][k] = 1.0
            cs[1][k] = -back(k)
            cs[2][k] = back(k) * back(k)
        }
        val us = solveUs
        for (c in 0 until 3) solveWithInto(l, cs[c], solveY, us[c])
        // C A^-1 C^T over the kept moments (the unused one is the identity, and its correction 0).
        val g = solveG
        for (i in 0 until 3) for (j in 0 until 3) g[i * 3 + j] = if (i < exactOrder && j < exactOrder) dot(cs[i], us[j]) else if (i == j) 1.0 else 0.0
        val gi = inverse3Into(g, solveGi)
        for (v in gi) if (!v.isFinite()) return false
        for (h in 0 until nh) {
            val hs = horizonsMs[h] / 1000.0
            val b = solveB
            for (q in 0 until taps) b[q] = rh[h][q] + mu * priors[h][q] + (extra?.get(q) ?: 0.0)
            val x = solveWithInto(l, b, solveY, solveX)
            val want = solveWant
            want[0] = hs
            want[1] = hs * hs / 2
            want[2] = hs * hs * hs / 3
            val e = solveE
            for (c in 0 until 3) e[c] = if (c < exactOrder) want[c] - dot(cs[c], x) else 0.0
            val y = solveYc
            for (i in 0 until 3) {
                var sum = 0.0
                for (j in 0 until 3) sum += gi[i * 3 + j] * e[j]
                y[i] = sum
            }
            val out = solveW[h]
            for (q in 0 until taps) {
                val v = x[q] + us[0][q] * y[0] + us[1][q] * y[1] + us[2][q] * y[2]
                if (!v.isFinite()) return false
                out[q] = v
            }
        }
        return true
    }

    /**
     * Per band frequency, each tap's predicted turn for a unit angle motion at the newest sample:
     * i w times the tap's mean phasor, as real ([first]) and imaginary ([second]) parts.
     */
    private fun bandRows(): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val fs = generateSequence(BAND_LOW_HZ) { it + BAND_STEP_HZ }.takeWhile { it <= BAND_HIGH_HZ + 1e-9 }.toList()
        val dt = stepMs / 1000.0
        val re = Array(fs.size) { DoubleArray(taps) }
        val im = Array(fs.size) { DoubleArray(taps) }
        for ((i, f) in fs.withIndex()) {
            val w = 2 * PI * f
            for (k in 0 until taps) {
                var sn = 0.0
                var cn = 0.0
                for (s in 0 until m) {
                    val b = (k * m + s) * dt
                    sn += sin(w * b)
                    cn += cos(w * b)
                }
                re[i][k] = w * sn / m
                im[i][k] = w * cn / m
            }
        }
        return re to im
    }


    /** The weights for a horizon of [aheadMs] (between the two nearest horizons, linearly; under the first, scaled toward 0 at 0). */
    fun weightsFor(aheadMs: Double): DoubleArray = weightsInto(aheadMs, DoubleArray(taps))

    /** 0.5.55: [weightsFor] into [out] (every tap written); returns [out]. */
    private fun weightsInto(aheadMs: Double, out: DoubleArray): DoubleArray {
        val h = aheadMs.coerceIn(0.0, horizonsMs.last())
        if (h <= horizonsMs[0]) {
            val f = h / horizonsMs[0]
            // Below the first horizon: the prior, which is exact in h, times the learned share of the first.
            val p = priorInto(h / 1000.0, wPrior)
            for (k in 0 until taps) out[k] = p[k] + (weights[0][k] - priors[0][k]) * f * f
            return out
        }
        var i = 0
        while (i + 1 < nh && horizonsMs[i + 1] < h) i++
        val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
        for (k in 0 until taps) out[k] = weights[i][k] * (1 - f) + weights[i + 1][k] * f
        return out
    }

    /** The priors as [weightsFor] blends the weights, so a share of 1 is exactly the learned weights; into [out] (0.5.55). */
    private fun priorsInto(h: Double, out: DoubleArray): DoubleArray {
        if (h <= horizonsMs[0]) return priorInto(h / 1000.0, out)
        var i = 0
        while (i + 1 < nh && horizonsMs[i + 1] < h) i++
        val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
        for (k in 0 until taps) out[k] = priors[i][k] * (1 - f) + priors[i + 1][k] * f
        return out
    }

    /**
     * The predicted turn (body axes, rad) from the newest sample over [aheadMs], or null before
     * [ready]. [learnedShare] under 1 moves the weights that far back toward the [prior]: a
     * deliberate turn is predicted by the prior's lag free parabola, not by weights learned on a
     * held head.
     */
    fun turn(aheadMs: Double, learnedShare: Double = 1.0): Vec3? {
        if (!turnInto(aheadMs, learnedShare, turnOut)) return null
        return Vec3(turnOut[0], turnOut[1], turnOut[2])
    }

    /**
     * 0.5.55: [turn] into [out] (x, y, z, the very floats [turn] makes its Vec3 of), with the kept work arrays; false
     * where [turn] gives null. The head path's way: no array or Vec3 made.
     */
    fun turnInto(aheadMs: Double, learnedShare: Double, out: FloatArray): Boolean {
        if (!ready) return false
        val i = slot(n - 1)
        if (!featOk[i]) return false
        val learned = weightsInto(aheadMs, turnLearned)
        val share = if (blend) learnedShare * this.learnedShare(aheadMs) else learnedShare
        val c = if (share >= 1.0) learned else {
            val h = aheadMs.coerceIn(0.0, horizonsMs.last())
            val exact = priorInto(h / 1000.0, turnExact)
            val interp = priorsInto(h, turnInterp)
            val mixed = turnC
            for (k in 0 until taps) mixed[k] = exact[k] + (learned[k] - interp[k]) * share
            mixed
        }
        var x = 0.0
        var y = 0.0
        var z = 0.0
        val fx = feat[0][i]
        val fy = feat[1][i]
        val fz = feat[2][i]
        for (k in 0 until taps) {
            x += c[k] * fx[k]
            y += c[k] * fy[k]
            z += c[k] * fz[k]
        }
        out[0] = x.toFloat()
        out[1] = y.toFloat()
        out[2] = z.toFloat()
        return true
    }

    /**
     * How much of a head motion at [hz] the current weights leave on screen at [aheadMs]: |predicted
     * - true turn| / the motion's own angle. 0 is a window nailed in place, 1 a head locked one.
     */
    fun leftShare(hz: Double, aheadMs: Double): Double {
        val c = weightsFor(aheadMs)
        val h = aheadMs / 1000.0
        val w = 2 * PI * hz
        val dt = stepMs / 1000.0
        var re = 0.0
        var im = 0.0
        for (k in 0 until taps) {
            // Tap k: the mean of m samples ending k m samples back.
            var tr = 0.0
            var ti = 0.0
            for (s in 0 until m) {
                val back = (k * m + s) * dt
                tr += cos(w * back)
                ti -= sin(w * back)
            }
            re += c[k] * tr / m
            im += c[k] * ti / m
        }
        val tr = sin(w * h) / w
        val ti = (1 - cos(w * h)) / w
        return sqrt((re - tr) * (re - tr) + (im - ti) * (im - ti)) * w
    }

    /** How much the weights multiply the gyro's white noise per sample into the turn at [aheadMs], s. */
    fun noiseGain(aheadMs: Double): Double = sqrt(weightsFor(aheadMs).sumOf { it * it } / m)

    fun line(aheadMs: Double): String = String.format(
        Locale.ROOT,
        "tremor prediction: %s (%d fits, %.0f ms of taps); at %.0f ms ahead it leaves %.0f %% of a 4 Hz, %.0f %% of an 8 Hz and %.0f %% of a 12 Hz head motion on screen, gyro noise x %.2f ms%s",
        if (solves > 0) "learned from the last ${memoryS.roundToInt()} s of this head" else "the prior (a line through the last ${priorMs.roundToInt()} ms of rate)",
        solves, taps * m * stepMs, aheadMs, leftShare(4.0, aheadMs) * 100, leftShare(8.0, aheadMs) * 100, leftShare(12.0, aheadMs) * 100, noiseGain(aheadMs) * 1000,
        (if (bandWeight > 0.0 && solves > 0) String.format(Locale.ROOT, "; %.0f to %.0f Hz regularised toward dropping an unforeseeable tremor (%.4f deg RMS measured there, weight %.2f)", BAND_LOW_HZ, BAND_HIGH_HZ, sqrt(bandPowerRad2) * 180 / PI, bandWeight) else "") +
            // 0.5.22: how far the learned weights are trusted on this head ([blend]); the leftShare figures above are the learned weights'.
            (if (blend && solves > 0) String.format(Locale.ROOT, "; learned weights used %.0f %% (fitted on this head's last turns; the rest is the prior)", learnedShare(aheadMs) * 100) else ""),
    )

    companion object {
        /** 0.5.21: the band the fit is regularised over ([solve]), in [BAND_STEP_HZ] steps, and the virtual tremor's weight. */
        const val BAND_LOW_HZ = 5.0
        const val BAND_HIGH_HZ = 14.0
        const val BAND_STEP_HZ = 0.5
        /**
         * Off (0), measured: on Mike's vc41 still head rebuilt from its own tremor bands ([Vc41StillHeadTest]
         * in the tests) the regularised fit cut the 12 Hz gain (291 % to 187 % at weight 0.3) but raised the
         * window's residual at every weight, timing and seed (0.0239 to 0.0247 deg at 32 ms): the plain
         * least squares fit is already the best linear prediction of that head, and its 12 Hz overshoot
         * costs less than what holding it takes from 5 to 8 Hz. Kept for the next measured head.
         */
        const val BAND_WEIGHT = 0.0

        /** The weight the replay compares against ([BAND_WEIGHT]'s decision). */
        const val BAND_WEIGHT_TRIED = 0.3

        /** 0.5.21: the horizon the band's predictability is checked at ([bandMiss]): the worn pose to photons, about. */
        const val BAND_CHECK_MS = 36.0

        /**
         * 0.5.22 (still head 0.0147 deg against 0.01): the first replay of Mike's own raw
         * gyro (the three 10 s worn still head files the 0.5.21 gyro ring wrote, `Vc42RawGyroReplayTest` in the
         * tests). On two of the three the learned weights did worse than their own fixed prior (25 ms ahead:
         * 0.0168 against 0.0150 deg, and 0.0372 against 0.0222), on the third about the same. The prior alone
         * failed the modelled heads whose tremor repeats and a still head's noise, so neither is right for
         * every head: the worn predictor fits per head how far to trust the learned weights ([blend],
         * [blendStep]), from the turns it has just seen. On Mike's three it matches or beats the better of
         * the two (0.0150, 0.0109, 0.0212 deg at 25 ms) and the modelled heads keep their learned weights.
         * [BLEND_MIN_SAMPLES] finished turns (0.3 s) before the share is used.
         */
        const val BLEND_MIN_SAMPLES = 300.0

        /** The worn head's predictor: 0.5.21's, with the learned share fitted per head ([blend]). */
        fun worn() = TremorPredictor(blend = true, learnEvery = WORN_LEARN_EVERY)

        /** 1.2.3: the worn predictor's fit learns from every 4th sample ([learnEvery]). */
        const val WORN_LEARN_EVERY = 4
    }

    /** The horizon nearest [BAND_CHECK_MS]. */
    private val checkIndex: Int = horizonsMs.indices.minByOrNull { abs(horizonsMs[it] - BAND_CHECK_MS) } ?: 0

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * 0.5.55: into the kept [l] ([solveL]). Only its lower triangle is written and read (here and in [solveWithInto]), each
     * entry before it is read, so what an earlier fit left above the diagonal changes nothing.
     */
    private fun choleskyInto(a: Array<DoubleArray>, l: Array<DoubleArray>): Array<DoubleArray>? {
        val k = a.size
        for (i in 0 until k) {
            for (j in 0..i) {
                var s = a[i][j]
                for (p in 0 until j) s -= l[i][p] * l[j][p]
                if (i == j) {
                    if (s <= 0.0 || !s.isFinite()) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }

    /** 0.5.55: into the kept [y] (the forward pass) and [x] (returned); each entry is written before it is read. */
    private fun solveWithInto(l: Array<DoubleArray>, b: DoubleArray, y: DoubleArray, x: DoubleArray): DoubleArray {
        val k = b.size
        for (i in 0 until k) {
            var s = b[i]
            for (p in 0 until i) s -= l[i][p] * y[p]
            y[i] = s / l[i][i]
        }
        for (i in k - 1 downTo 0) {
            var s = y[i]
            for (p in i + 1 until k) s -= l[p][i] * x[p]
            x[i] = s / l[i][i]
        }
        return x
    }
}

/**
 * 0.5.20 ("the still check pins the gyro's high frequency content, not the
 * angle's low frequency part", so the tremor could only be modelled): the head's own motion per
 * axis in three bands, from every raw gyro sample, so the next log measures it. Each band is a
 * second order band pass (0.3 to 3 Hz sway, 4 to 8 and 8 to 12 Hz tremor) on the bias free rate;
 * the RMS is over the last [windowS] seconds, in degrees of angle (the rate's RMS over the band's
 * centre frequency, 2 pi f), so it reads as the tremor the window has to cancel. Second order
 * filters overlap: a tremor at a band's edge shows in both neighbours (their quadrature sum is the
 * tremor), and a strong tremor leaks a little into the sway band.
 */
class TremorBands(val windowS: Double = 2.0) {
    private class Band(val lowHz: Double, val highHz: Double) {
        val centre = sqrt(lowHz * highHz)
        private var b0 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var rateHz = 0.0
        private val x1 = DoubleArray(3)
        private val x2 = DoubleArray(3)
        private val y1 = DoubleArray(3)
        private val y2 = DoubleArray(3)
        val power = DoubleArray(3)

        fun design(fs: Double) {
            if (abs(fs - rateHz) < 1.0) return
            rateHz = fs
            // RBJ band pass, 0 dB peak gain, at the band's centre with its width.
            val w0 = 2 * PI * centre / fs
            val q = centre / (highHz - lowHz)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = alpha / a0
            b2 = -alpha / a0
            a1 = -2 * cos(w0) / a0
            a2 = (1 - alpha) / a0
        }

        fun step(axis: Int, x: Double, k: Double) {
            val y = b0 * x + b2 * x2[axis] - a1 * y1[axis] - a2 * y2[axis]
            x2[axis] = x1[axis]
            x1[axis] = x
            y2[axis] = y1[axis]
            y1[axis] = y
            power[axis] += (y * y - power[axis]) * k
        }

        fun reset() {
            for (a in 0 until 3) {
                x1[a] = 0.0; x2[a] = 0.0; y1[a] = 0.0; y2[a] = 0.0; power[a] = 0.0
            }
        }

        /** The band's motion on [axis], degrees RMS of angle. */
        fun deg(axis: Int) = sqrt(power[axis]) / (2 * PI * centre) * 180 / PI
    }

    // 0.5.55: an array, walked by index (a List's iterator and a new DoubleArray each sample, every sample of the stream).
    private val bands = arrayOf(Band(0.3, 3.0), Band(4.0, 8.0), Band(8.0, 12.0))
    private val v = DoubleArray(3)
    private var lastNs = 0L
    private var stepMs = 1.0
    private var seenS = 0.0

    fun add(hostNs: Long, omega: Vec3) {
        if (lastNs != 0L) {
            val d = (hostNs - lastNs) / 1e6
            if (d <= 0.0) return
            if (d > 50.0) {
                for (b in bands) b.reset()
                seenS = 0.0
            } else {
                stepMs += (d - stepMs) * 0.01
            }
        }
        lastNs = hostNs
        val fs = 1000.0 / stepMs
        val k = (stepMs / 1000.0) / windowS
        seenS += stepMs / 1000.0
        v[0] = omega.x.toDouble()
        v[1] = omega.y.toDouble()
        v[2] = omega.z.toDouble()
        for (i in bands.indices) {
            val b = bands[i]
            b.design(fs)
            for (a in 0 until 3) b.step(a, v[a], k)
        }
    }

    /** True once a whole window is measured. */
    val ready: Boolean get() = seenS >= windowS

    /** Degrees RMS of angle per band (sway, 4 to 8 Hz, 8 to 12 Hz), per axis of the glasses (x, y, z). */
    fun degrees(): List<DoubleArray> = bands.map { b -> DoubleArray(3) { b.deg(it) } }

    fun line(): String {
        val d = degrees()
        fun f(v: DoubleArray) = "%.4f, %.4f, %.4f".format(Locale.ROOT, v[0], v[1], v[2])
        return "head tremor (the gyro's own bands over the last %.0f s, deg RMS of angle on the glasses' x, y, z): 0.3 to 3 Hz %s; 4 to 8 Hz %s; 8 to 12 Hz %s".format(
            Locale.ROOT, windowS, f(d[0]), f(d[1]), f(d[2]),
        )
    }
}
