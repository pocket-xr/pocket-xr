package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.sqrt

/**
 * 0.5.10: the glasses' IMU timestamps on the phone's clock.
 *
 * Up to 0.5.9 a pose was taken to be as old as the time since the last read that brought reports
 * (`ageS`), and every report in a read was placed back from that read's arrival. The shell
 * helper's pipe delivers the 1 kHz stream in bursts, so the pose's apparent age, and with it the
 * prediction, jumped by up to a burst's length from one display frame to the next: the drawn view
 * shook with the bursts while the head moved, which is one part of Mike's "all the windows are
 * flickering" when he moved his head.
 *
 * The device clock runs at a steady rate, so host time = device time + offset, where the offset
 * is the smallest (arrival - device time) seen: the sample that waited least in the pipe. It may
 * creep up by [driftNsPerSample] a sample (clock rate differences, about 1 ms a second at 1 kHz) and
 * starts again when the device clock jumps (a reconnect).
 */
class ImuClock(
    /**
     * 0.5.11: 100 ns (was 1000). The offset crept up 1 ms a second toward later arrivals, so between
     * fast samples every report was placed up to a burst's length too late, and the pose was
     * predicted that much too little. Clock rates differ by well under 100 ppm.
     */
    val driftNsPerSample: Long = 100,
    val jumpNs: Long = 500_000_000,
) {
    private var offset = Long.MIN_VALUE

    /** The host time (ns) of a sample stamped [deviceNs] that arrived at [arrivedNs]. */
    fun toHost(deviceNs: Long, arrivedNs: Long): Long {
        val candidate = arrivedNs - deviceNs
        offset = when {
            offset == Long.MIN_VALUE -> candidate
            candidate < offset - jumpNs || candidate > offset + jumpNs -> candidate
            candidate < offset -> candidate
            else -> offset + minOf(driftNsPerSample, candidate - offset)
        }
        return deviceNs + offset
    }

    /** How long the newest sample waited from its (mapped) time to its arrival, ns. */
    fun waited(deviceNs: Long, arrivedNs: Long): Long = if (offset == Long.MIN_VALUE) 0 else arrivedNs - (deviceNs + offset)

    fun reset() {
        offset = Long.MIN_VALUE
    }
}

/**
 * 0.5.10: the head pose at a display frame's present time.
 *
 * Each fused sample comes with its host time ([ImuClock]); the angular velocity is smoothed over
 * [rateTauMs] (raw 1 kHz gyro samples carry a few degrees a second of noise, which a 40 ms
 * prediction turns into visible shake) and an angular acceleration over [accelTauMs]. [predict]
 * integrates the rate from the sample's time to the target time, plus [accelWeight] of the
 * acceleration term, never further than [maxAheadMs]: a late latched, predicted pose, as headsets use,
 * without the timewarp.
 */
class HeadPredictor(
    val rateTauMs: Float = 3f,
    val accelTauMs: Float = 10f,
    val accelWeight: Float = 1f,
    val maxAheadMs: Float = 80f,
    /** Angular acceleration above this (rad/s^2) is noise or a knock, not a head. */
    val maxAccel: Float = 400f,
    /**
     * 0.5.10 had a rest gate here: no prediction while the head had not turned faster than about
     * 2 deg/s, ramping in by 5 deg/s. That is a dead zone exactly where head fidgets live, so every
     * small motion showed the whole pipeline as lag.
     * 0.5.11: 0, every motion is predicted ([v0510] keeps the old values for the tests). Gyro noise
     * is kept down by [rateTauMs] (a 50 Hz low pass), which smooths
     * only the rate the prediction extrapolates, never the measured orientation.
     */
    val restRate: Float = 0f,
    val restRamp: Float = 0f,
    val activityMs: Float = 300f,
    /**
     * 0.5.11: the acceleration term counts fully once the head's recent peak rate (decaying over
     * [activityMs], so a shake's turning points keep it) reaches this (rad/s, about 17 deg/s);
     * below it scales down linearly, so the term's noise is not pushed out on a nearly still head.
     * The rate term is always in full. (0.5.18 tried 0.05: the still head replay gained 0.001 deg at
     * 19 ms, but HeadFidgetTest's sub-degree fidget at 41 ms went from 0.144 to 0.174 deg, so it stays.)
     */
    val accelFullRate: Float = 0.3f,
    /**
     * 0.5.12: an adaptive rate low pass instead of the fixed [rateTauMs]: a
     * cutoff of [restHz] while the head is steady, opening linearly with the angular acceleration
     * to [openHz] at [openAccel] rad/s^2, with no dead zone. Off by default: the horizon sweep
     * (HorizonSweepTest.adaptiveSmoothingIsKeptOnlyIfItCutsTheWobble) did not show the 10 % gain
     * it needs to be adopted.
     */
    val adaptiveRate: Boolean = false,
    val restHz: Float = 20f,
    val openHz: Float = 500f,
    val openAccel: Float = 25f,
    /**
     * 0.5.18 (Mike's "a tiny bit fidgety in full SBS with my head still"): a One Euro style filter on
     * the rate the prediction extrapolates. Its cut off is [euroMinHz] at rest and opens with the
     * head's speed ([euroBetaRate] Hz per rad/s above [euroRateFloor]) and its angular acceleration
     * ([euroBetaAccel] Hz per rad/s^2 above [euroAccelFloor]), never above [rateTauMs]'s 53 Hz: a
     * moving head gets exactly 0.5.17's rate, a still one a 6 Hz low pass that leaves the gyro's
     * noise out of the prediction. The speed is read from the 3 ms rate, so a turn opens the filter
     * in its first millisecond; it is fully open by 0.0032 rad/s (0.18 deg/s). Tuned on the still
     * head replay (StillHeadReplayTest): at 6000 Hz per rad/s a worn still head's tremor, at 0.002 to
     * 0.01 rad/s, was 2 to 4 % worse than 0.5.17 for a gain only the still head mode needs. False is
     * 0.5.17.
     */
    val oneEuro: Boolean = true,
    val euroMinHz: Float = 6f,
    val euroBetaRate: Float = 40000f,
    val euroRateFloor: Float = 0.002f,
    val euroBetaAccel: Float = 40f,
    val euroAccelFloor: Float = 2f,
    /**
     * 0.5.18: the still head mode. While the head is still (the filtered rate under [stillEnter]
     * rad/s, about 0.14 deg/s), prediction fades over [stillRiseMs] to [stillShare] of itself: at
     * rest it only pushed noise and the bias's last error ahead. It comes back in full by
     * [stillExit] (0.29 deg/s), smoothly with the rate, so a head that starts to turn is predicted
     * in full from its first degree a second and nothing jumps. Between the two the mode keeps what
     * it was (hysteresis); its weight never switches, it slides. False is 0.5.17.
     */
    val stillHead: Boolean = true,
    val stillEnter: Float = 0.0025f,
    val stillExit: Float = 0.005f,
    val stillShare: Float = 0.05f,
    val stillRiseMs: Float = 300f,
    /**
     * 0.5.20: the turn to the light time from the raw gyro, by a
     * predictor that learns this head's motion ([TremorPredictor]): no rate or acceleration filter,
     * so the 4 to 12 Hz tremor is carried to the light on time instead of overshot. The filtered
     * rate and acceleration above are only the fallback while the stream has a gap. Null is 0.5.19.
     */
    val tremor: TremorPredictor? = TremorPredictor.worn(),
    /**
     * 0.5.20: with [tremor], the still head mode follows only glasses lying still: the gyro under
     * 15 Hz, the RMS of its three axes about their own 0.1 s mean, under [lyingRmsEnter] rad/s (what
     * the sensor's white noise leaves under 15 Hz: 0.0006 on Mike's table, 0.0011 for a gyro twice
     * as noisy), back off above [lyingRmsExit]. The faintest worn tremor modelled (0.01 deg at 4 Hz
     * on one axis, 0.003 rad/s RMS; Mike's stillest worn second reads about 0.009) is over the exit,
     * so the mode never holds back a worn head's correction, however still it is held. 0.5.18's rate
     * rule ([stillEnter]) is kept without [tremor].
     */
    val lyingRmsEnter: Float = 0.0018f,
    val lyingRmsExit: Float = 0.0024f,
    /**
     * 0.5.20: [tremor]'s learned weights predict the head in full while its speed (the gyro's peak
     * over the last 0.1 s) is under [tremorFullBelow] rad/s, and hand over smoothly to its prior (a
     * lag free parabola through the last 24 ms of rate) by [tremorNoneAbove]: a held head's tremor,
     * sway and fidget (Mike's stillest worn stretch peaks near 0.03 rad/s) are learned and carried,
     * while a deliberate turn is predicted by the prior, whatever was learned while the head was
     * still. The prior turns a 40 degree head turn with no more lag than 0.5.19 and less error.
     */
    val tremorFullBelow: Float = 0.08f,
    val tremorNoneAbove: Float = 0.3f,
    /**
     * 0.5.30 (Mike: with the head still the windows are almost stable, "but still a
     * little shaky"): the still blend. [tremor] keeps the moments of a turn exact, so on a held head
     * the gyro's noise is still pushed ahead in full with the prediction. Here the prediction is
     * scaled by the share of it that came true: every sample the turn over [blendRefMs] is predicted,
     * and once that time has passed it is set against the turn the gyro then made (the gyro's own
     * noise after the sample cannot be known, so it does not bias the fit). The gain, sum(made x
     * predicted) / sum(predicted^2) over the last [blendTauMs], held to 0..1, is the least squares
     * share: near 1 for a turn or a tremor that repeats, toward 0 (the horizon toward 0) when what is
     * predicted is mostly noise. It is let go at once, with no jump, by a move: the prediction
     * [blendReleaseLow] to [blendReleaseHigh] times its own recent RMS, or the gyro's peak from
     * [blendSpeedLow] to [blendSpeedHigh] rad/s, slides the share to 1, so a head turn is predicted
     * in full from its first moment. False is 0.5.29.
     */
    val stillBlend: Boolean = true,
    val blendRefMs: Float = 30f,
    val blendTauMs: Float = 160f,
    val blendReleaseLow: Float = 3f,
    val blendReleaseHigh: Float = 6f,
    val blendSpeedLow: Float = 0.1f,
    val blendSpeedHigh: Float = 0.25f,
) {
    companion object {
        /** 0.5.10's predictor, for the before and after comparisons. */
        fun v0510() = HeadPredictor(restRate = 0.03f, restRamp = 0.06f, accelFullRate = 0f, oneEuro = false, stillHead = false, tremor = null)

        /** 0.5.12: the adaptive 20 to 500 Hz rate smoothing ([adaptiveRate]). */
        fun adaptive(openAccel: Float = 25f) = HeadPredictor(adaptiveRate = true, openAccel = openAccel, accelFullRate = 0.3f, oneEuro = false, stillHead = false, tremor = null)

        /** 0.5.17's predictor (a fixed 3 ms rate filter, prediction always in full), for the tests of 0.5.18. */
        fun v0517() = HeadPredictor(accelFullRate = 0.3f, oneEuro = false, stillHead = false, tremor = null)

        /** 0.5.19's predictor (0.5.18's One Euro rate and still head mode, no [TremorPredictor]), for the tests of 0.5.20. */
        fun v0519() = HeadPredictor(tremor = null)

        /** 0.5.29's predictor (no still blend), for the tests of 0.5.30. */
        fun v0529() = HeadPredictor(stillBlend = false)
    }

    /** 0.5.30: the still blend's ring of predictions waiting for their turn to finish, and the gyro's integral. */
    private val blendT = LongArray(BLEND_CAP)
    private val blendP = Array(BLEND_CAP) { DoubleArray(3) }
    private val blendI = Array(BLEND_CAP) { DoubleArray(3) }
    private var blendHead = 0
    private var blendTail = 0
    private val integral = DoubleArray(3)
    private var blendMade = 0.0
    private var blendPred = 0.0
    private var blendWeight = 0.0
    private var blendNow = 0.0
    private var blendCount = 0

    /** 0.5.55: the still blend's turn of this sample, kept (it was a new Vec3 a sample). */
    private val blendTurn = FloatArray(3)

    /** 0.5.30: the still blend's fitted gain (0..1) and the share of the prediction it gives now. */
    var blendGain = 1f
        private set
    var blendShare = 1f
        private set

    private fun blendClear() {
        blendHead = 0
        blendTail = 0
        integral.fill(0.0)
        blendMade = 0.0
        blendPred = 0.0
        blendWeight = 0.0
        blendNow = 0.0
        blendCount = 0
        blendGain = 1f
        blendShare = 1f
    }

    /** 0.5.30: one sample of the still blend, after [tremor] has the sample. */
    private fun blendStep(omega: Vec3, hostNs: Long, dtMs: Float) {
        val t = tremor ?: return
        val dt = dtMs / 1000.0
        integral[0] += omega.x * dt
        integral[1] += omega.y * dt
        integral[2] += omega.z * dt
        val refNs = (blendRefMs * 1e6f).toLong()
        val k = dtMs / (blendTauMs + dtMs).toDouble()
        while (blendTail != blendHead && blendT[blendTail] + refNs <= hostNs) {
            val p = blendP[blendTail]
            val i0 = blendI[blendTail]
            var made = 0.0
            var pp = 0.0
            for (a in 0 until 3) {
                made += (integral[a] - i0[a]) * p[a]
                pp += p[a] * p[a]
            }
            blendMade += (made - blendMade) * k
            blendPred += (pp - blendPred) * k
            blendWeight += (1.0 - blendWeight) * k
            blendCount++
            blendTail = (blendTail + 1) % BLEND_CAP
        }
        // Before 0.2 s of finished turns the gain is not trusted: 1, as 0.5.29.
        blendGain = if (blendCount < BLEND_WARMUP || blendPred <= 1e-18) 1f else (blendMade / blendPred).coerceIn(0.0, 1.0).toFloat()
        // 0.5.55: into [blendTurn] ([TremorPredictor.turnInto]), the same floats [TremorPredictor.turn] makes a Vec3 of.
        if (!t.turnInto(blendRefMs.toDouble(), tremorShare.toDouble(), blendTurn)) {
            blendNow = 0.0
            return
        }
        val px = blendTurn[0]
        val py = blendTurn[1]
        val pz = blendTurn[2]
        val slot = blendHead
        blendT[slot] = hostNs
        blendP[slot][0] = px.toDouble()
        blendP[slot][1] = py.toDouble()
        blendP[slot][2] = pz.toDouble()
        integral.copyInto(blendI[slot])
        blendHead = (blendHead + 1) % BLEND_CAP
        if (blendHead == blendTail) blendTail = (blendTail + 1) % BLEND_CAP
        blendNow = (px * px + py * py + pz * pz).toDouble()
        blendShare = blendShareNow()
    }

    /** 0.5.30: the fitted gain, let go to 1 by a move (the prediction against its RMS, or the gyro's peak). */
    private fun blendShareNow(): Float {
        val rms = if (blendWeight > 0.0) sqrt((blendPred / blendWeight).toFloat()) else 0f
        val z = if (rms > 0f) sqrt(blendNow.toFloat()) / rms else Float.MAX_VALUE
        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }
        val release = maxOf(
            smooth((z - blendReleaseLow) / (blendReleaseHigh - blendReleaseLow)),
            smooth((turnSpeed - blendSpeedLow) / (blendSpeedHigh - blendSpeedLow)),
        )
        return blendGain + (1f - blendGain) * release
    }

    /** 0.5.20: frames predicted by [tremor] and by the fallback since the last [takeTremorCounts]. */
    var tremorFrames = 0L
        private set
    var fallbackFrames = 0L
        private set

    /** 0.5.20: the gyro's mean (0.1 s) and its RMS about it, rad/s, for the lying still rule. */
    private var lyingMean = Vec3.ZERO
    private var lyingRate = Vec3.ZERO
    private var lyingVar = LYING_VAR_START
    val lyingRms: Float get() = sqrt(lyingVar)

    /** 0.5.20: the gyro's peak speed, decaying over 0.1 s, rad/s: which predictor carries the head. */
    var turnSpeed = 0f
        private set

    /**
     * Off while a game has the glasses: the tremor fit's learning was a quarter of the head thread at 1000 samples a
     * second (7 November 2026), and a game asks only a few milliseconds ahead. The weights already learned are kept
     * and still used; the steady, speeding and bending turn rules hold as always.
     */
    @Volatile var learning = true

    /** 0.5.20: ms of steady stream since the last restart, for [LEARN_AFTER_MS]. */
    private var streamMs = 0f

    /** 0.5.20: the share of the prediction [tremor] gives now (1 a held head, 0 a deliberate turn). */
    val tremorShare: Float get() {
        if (tremor == null) return 0f
        val x = ((turnSpeed - tremorFullBelow) / (tremorNoneAbove - tremorFullBelow)).coerceIn(0f, 1f)
        return 1f - x * x * (3f - 2f * x)
    }

    /** The rate filter's time constant for this step, ms. */
    private fun rateTau(): Float {
        if (oneEuro && !adaptiveRate) {
            val speed = (euroBetaRate * (fastRate.length - euroRateFloor).coerceAtLeast(0f)) +
                (euroBetaAccel * (fastAccel - euroAccelFloor).coerceAtLeast(0f))
            val openHz = 1000f / (2f * Math.PI.toFloat() * rateTauMs)
            val hz = (euroMinHz + speed).coerceIn(euroMinHz, openHz)
            return 1000f / (2f * Math.PI.toFloat() * hz)
        }
        if (!adaptiveRate) return rateTauMs
        val hz = (restHz + (openHz - restHz) * (accel.length / openAccel)).coerceIn(restHz, openHz)
        return 1000f / (2f * Math.PI.toFloat() * hz)
    }

    /** 0.5.18: the rate through 0.5.17's 3 ms filter and its change (rad/s^2), which open the One Euro filter. */
    private var fastRate = Vec3.ZERO
    private var fastAccel = 0f

    /** 0.5.18: the still head mode's weight (0 moving, 1 still) and its hysteresis state. */
    var stillWeight = 0f
        private set
    private var stillSlide = 0f
    private var stillWanted = false

    /** 0.5.18: the share of the prediction given now (1 moving, [stillShare] still). */
    val predictionShare: Float get() = 1f - stillWeight * (1f - stillShare)

    var orientation: Quat? = null
        private set
    var sampleNs = 0L
        private set
    private var rate = Vec3.ZERO
    private var accel = Vec3.ZERO
    private var lastRateNs = 0L
    private var activity = 0f

    /** The smoothed body frame angular velocity, rad/s. */
    val angularVelocity: Vec3 get() = rate

    fun add(q: Quat, omega: Vec3, hostNs: Long) {
        val prev = orientation
        val dtMs = if (prev == null) 0f else (hostNs - sampleNs) / 1e6f
        if (prev == null || dtMs <= 0f || dtMs > 100f) {
            rate = omega
            accel = Vec3.ZERO
            activity = omega.length
            fastRate = omega
            fastAccel = 0f
            stillSlide = 0f
            stillWanted = false
            stillWeight = 0f
            lyingMean = omega
            lyingRate = omega
            turnSpeed = omega.length
            lyingVar = LYING_VAR_START
            streamMs = 0f
            tremor?.clear()
            blendClear()
        } else {
            // 0.5.55: each filter step below in one Vec3 ([HeadMath], the same sums to the bit); they made about twenty
            // Vec3s a sample.
            if (oneEuro || stillHead) {
                val kf = dtMs / (rateTauMs + dtMs)
                val nextFast = HeadMath.toward(fastRate, omega, kf)
                val a = HeadMath.distance(nextFast, fastRate) * (1000f / dtMs)
                // The change of the 3 ms rate is itself smoothed over 10 ms, as the acceleration term.
                fastAccel += (a - fastAccel) * (dtMs / (accelTauMs + dtMs))
                fastRate = nextFast
            }
            val k = dtMs / (rateTau() + dtMs)
            val next = HeadMath.toward(rate, omega, k)
            val a = HeadMath.diffScaled(next, rate, 1000f / dtMs)
            val ka = dtMs / (accelTauMs + dtMs)
            accel = HeadMath.toward(accel, a, ka)
            if (accel.length > maxAccel) accel = accel * (maxAccel / accel.length)
            rate = next
            activity = maxOf(rate.length, activity * kotlin.math.exp(-dtMs / activityMs))
            if (tremor != null) {
                streamMs += dtMs
                turnSpeed = maxOf(omega.length, turnSpeed * kotlin.math.exp(-dtMs / TURN_SPEED_MS))
                // The gyro under 15 Hz, where a head moves and only a share of the sensor's white noise is.
                lyingRate = HeadMath.toward(lyingRate, omega, dtMs / (LYING_LOW_PASS_MS + dtMs))
                val kl = dtMs / (LYING_TAU_MS + dtMs)
                lyingMean = HeadMath.toward(lyingMean, lyingRate, kl)
                lyingVar += (minOf(HeadMath.distanceSq(lyingRate, lyingMean), 16f * lyingRmsExit * lyingRmsExit) - lyingVar) * kl
                if (stillHead) lyingStep(dtMs)
            } else if (stillHead) {
                stillStep(dtMs)
            }
        }
        // 0.5.20: nothing is learned while the glasses lie still (the gyro is only noise then, and
        // weights fitted to noise, a long average, would serve the next head turn badly), nor in a
        // stream's first half second, before the lying still rule has measured it.
        tremor?.add(hostNs, omega, learnNow = learning && stillWeight < 0.5f && streamMs >= LEARN_AFTER_MS)
        if (stillBlend && tremor != null && prev != null && dtMs > 0f && dtMs <= 100f) blendStep(omega, hostNs, dtMs)
        orientation = q
        sampleNs = hostNs
        lastRateNs = hostNs
    }

    /**
     * 0.5.18: the still head weight. The filtered rate decides with hysteresis whether the head is
     * still; the weight slides toward that over [stillRiseMs] (in) or 20 ms (out), and a move on the
     * 3 ms rate takes it down at once, smoothly from [stillEnter] to none left at [stillExit].
     */
    private fun stillStep(dtMs: Float) {
        val slow = rate.length
        if (slow <= stillEnter) stillWanted = true else if (slow >= stillExit) stillWanted = false
        stillSlide = if (stillWanted) {
            stillSlide + (1f - stillSlide) * (dtMs / (stillRiseMs + dtMs))
        } else {
            stillSlide * (1f - dtMs / (STILL_FALL_MS + dtMs))
        }
        val x = ((fastRate.length - stillEnter) / (stillExit - stillEnter)).coerceIn(0f, 1f)
        val gate = 1f - x * x * (3f - 2f * x)
        stillWeight = minOf(stillSlide, gate)
    }

    /**
     * 0.5.20: the still head weight for glasses lying still ([lyingRmsEnter]); it slides in over
     * [stillRiseMs] and out over 20 ms, and any move of the 3 ms rate beyond the exit takes it out at
     * once, so a head that starts to turn is predicted in full from its first moment.
     */
    private fun lyingStep(dtMs: Float) {
        val rms = lyingRms
        if (rms <= lyingRmsEnter) stillWanted = true else if (rms >= lyingRmsExit) stillWanted = false
        stillSlide = if (stillWanted) {
            stillSlide + (1f - stillSlide) * (dtMs / (stillRiseMs + dtMs))
        } else {
            stillSlide * (1f - dtMs / (STILL_FALL_MS + dtMs))
        }
        // The 3 ms rate (no 15 Hz lag) off the lying mean by 0.005 rad/s starts the release, by 0.01
        // it is complete: a turn is predicted in full within its first 3 ms.
        val x = ((HeadMath.distance(fastRate, lyingMean) - LYING_RELEASE) / LYING_RELEASE).coerceIn(0f, 1f)
        stillWeight = minOf(stillSlide, 1f - x * x * (3f - 2f * x))
    }

    /** 0.5.55: [predict]'s turn, kept (the caller holds this predictor's lock, as for everything here). */
    private val predictTurn = FloatArray(3)

    /** The orientation at [targetNs] (host clock), or null before the first sample. */
    fun predict(targetNs: Long): Quat? {
        val q = orientation ?: return null
        val dt = ((targetNs - sampleNs) / 1e9f).coerceIn(0f, maxAheadMs / 1000f)
        if (dt <= 0f) return q
        val share = if (stillHead) predictionShare else 1f
        if (tremor != null) {
            // 0.5.55: the turn into a kept array and the product in one step ([HeadMath.turnedBy]): this runs several
            // times a frame on the render thread, and made a Vec3, a scaled Vec3 and four Quats each time.
            if (tremor.turnInto(dt * 1000.0, tremorShare.toDouble(), predictTurn)) {
                tremorFrames++
                // 0.5.30: times the still blend's share (1 moving, toward 0 when the prediction is mostly noise).
                val blend = if (stillBlend) blendShare else 1f
                val s = share * blend
                return HeadMath.turnedBy(q, predictTurn[0] * s, predictTurn[1] * s, predictTurn[2] * s, 1f)
            }
            fallbackFrames++
        }
        val ramp = if (restRamp <= 0f) (if (activity >= restRate) 1f else 0f) else ((activity - restRate) / restRamp).coerceIn(0f, 1f)
        if (ramp <= 0f) return q
        // 0.5.18: times the still head mode's share ([predictionShare]).
        val step = classicStep(dt) * (ramp * share)
        return (q * Quat.fromGyro(step, 1f)).normalized()
    }

    /** Rate integration with the acceleration's share: angle = w t + a w' t^2 / 2 (0.5.10 to 0.5.19's prediction). */
    private fun classicStep(dt: Float): Vec3 {
        val accelShare = if (accelFullRate <= 0f) 1f else (activity / accelFullRate).coerceIn(0f, 1f)
        return rate * dt + accel * (accelWeight * accelShare * dt * dt / 2f)
    }

    fun reset() {
        orientation = null
        rate = Vec3.ZERO
        accel = Vec3.ZERO
        activity = 0f
        fastRate = Vec3.ZERO
        fastAccel = 0f
        stillSlide = 0f
        stillWanted = false
        stillWeight = 0f
        lyingMean = Vec3.ZERO
        lyingRate = Vec3.ZERO
        lyingVar = LYING_VAR_START
        turnSpeed = 0f
        streamMs = 0f
        tremor?.clear()
        blendClear()
    }

    /** 0.5.20: (tremor predictor frames, fallback frames) since the last call, for the head chain line. */
    fun takeTremorCounts(): Pair<Long, Long> = (tremorFrames to fallbackFrames).also { tremorFrames = 0; fallbackFrames = 0 }
}

/** 0.5.30: the still blend's ring, samples (at 1 kHz it holds the [HeadPredictor.blendRefMs] in flight several times over). */
private const val BLEND_CAP = 256

/** 0.5.30: finished turns before the still blend's gain is used. */
private const val BLEND_WARMUP = 200

/** 0.5.18: how fast the still head mode lets go when the head stops being still, ms. */
private const val STILL_FALL_MS = 20f

/** 0.5.20: the lying still rule's mean and RMS time constant, ms. */
private const val LYING_TAU_MS = 100f

/** 0.5.20: the lying still RMS starts at 0.01 rad/s (not still), so a new stream is not taken for lying still before it is measured. */
private const val LYING_VAR_START = 1e-4f

/** 0.5.20: the lying still mode's quick release on the 3 ms rate, rad/s (its noise on a table: 0.001). */
private const val LYING_RELEASE = 0.005f

/** 0.5.20: no learning in a stream's first half second ([HeadPredictor.tremor]), ms. */
private const val LEARN_AFTER_MS = 500f

/** 0.5.20: how fast [HeadPredictor.turnSpeed]'s peak decays, ms. */
private const val TURN_SPEED_MS = 100f

/** 0.5.20: the lying still rule's low pass, ms (15 Hz). */
private const val LYING_LOW_PASS_MS = 10.6f

/**
 * 0.5.10: when each display frame reaches the eyes, from the frames' own cadence.
 *
 * The render loop is held back by the display (GLSurfaceView blocks when every buffer is queued),
 * so the time between frame starts is the display's real period: 16.7 ms at 60 Hz, 33.3 ms if
 * SurfaceFlinger composes the glasses at 30 (Mike's 3840x1080 log showed renderFrameRate 30.0 for
 * three entries). A frame started now is shown on the phone's display output [pipelineFrames]
 * periods later (one in the queue, one composing), and on the glasses' panel [extraMs] after that.
 *
 * 0.5.11: two times, not one. [frame] returns the time the frame is stamped with
 * (eglPresentationTimeANDROID), and [targetNs] the time the pose is predicted to. 0.5.10 stamped the
 * frame with start + 2 periods + the glasses' 8 ms. SurfaceFlinger does not show a buffer before its
 * stamp, so a stamp past the vsync it was drawn for (the 8 ms always, and the frame's own start
 * offset after the vsync) held it to the next vsync: every frame was seen one period (16.7 ms at
 * 60 Hz, 33.3 ms at 30) later than its pose was predicted for. That lag is what a world locked
 * window shows as following the head. Now the stamp is [stampLeadFrames] of a period before the
 * vsync it is meant for, so it is never held back, and the glasses' delay only moves the
 * prediction, never the stamp.
 */
class FramePacer(
    val pipelineFrames: Float = 2f,
    var extraMs: Float = 8f,
    val minPeriodMs: Float = 4f,
    val maxPeriodMs: Float = 50f,
    /** The display's own refresh, when known; the measured period snaps to a whole multiple of it. */
    var refreshHz: Float = 0f,
    /** 0.5.11: the stamp sits this share of a period before the vsync the frame is meant for. */
    val stampLeadFrames: Float = 0.5f,
    /** 0.5.11: true stamps the way 0.5.10 did (start + 2 periods + the glasses' delay), for the tests. */
    val legacyStamp: Boolean = false,
) {
    private var lastStartNs = 0L
    private var period = 1000f / 60f
    private var periods = 0

    /** The measured display period, ms. */
    val periodMs: Float get() = period

    /** 0.5.11: what the pose of the frame last started is predicted to: its photons on the glasses, ns. */
    var targetNs: Long = 0L
        private set

    /** A frame starts at [nowNs]: the time to stamp it with (eglPresentationTimeANDROID), ns. See [targetNs]. */
    fun frame(nowNs: Long): Long {
        if (lastStartNs != 0L) {
            val gap = (nowNs - lastStartNs) / 1e6f
            if (gap in minPeriodMs..maxPeriodMs) {
                period += (gap - period) * if (periods < 10) 0.3f else 0.05f
                periods++
            }
        }
        lastStartNs = nowNs
        targetNs = nowNs + (aheadMs() * 1e6f).toLong()
        return if (legacyStamp) targetNs else nowNs + (stampMs() * 1e6f).toLong()
    }

    /** How far ahead of a frame's start its photons leave the glasses, ms: what the pose is predicted for. */
    fun aheadMs(): Float = snapped() * pipelineFrames + extraMs

    /** 0.5.11: how far ahead of a frame's start it is stamped, ms: before the vsync it is meant for. */
    fun stampMs(): Float = snapped() * (pipelineFrames - stampLeadFrames).coerceAtLeast(0f)

    /** The period, on a whole number of the display's refresh periods when the refresh is known. */
    fun snapped(): Float {
        if (refreshHz <= 0f) return period
        val vsync = 1000f / refreshHz
        val n = kotlin.math.round(period / vsync).coerceAtLeast(1f)
        return n * vsync
    }

    fun line(): String = String.format(
        Locale.ROOT, "frame %.1f ms (%.0f fps)%s, shown %.0f ms after it starts (%.0f frames queued + %.0f ms glasses), stamped %.0f ms ahead",
        snapped(), 1000f / snapped(), if (refreshHz > 0f) ", display %.0f Hz".format(Locale.ROOT, refreshHz) else "", aheadMs(), pipelineFrames, extraMs,
        if (legacyStamp) aheadMs() else stampMs(),
    )
}

/**
 * 0.5.10: what the view does when head tracking pauses. 0.5.6 to 0.5.9 drew head locked the moment
 * the stream was not "Streaming", and every reconnect reset the fusion, which made forward wherever
 * the head pointed then: the room snapped to the head and back on each of Mike's drops (about every
 * 10 s). Now a pause of up to [holdMs] keeps the last pose, unpredicted, and the stream
 * carries on from it when it comes back; only after that does the view go head locked.
 */
object HeadHold {
    const val HOLD_MS = 5_000L

    /** Predict from fresh samples within [freshMs]; else hold the last pose until [holdMs]; else none. */
    enum class Use { PREDICT, HOLD, NONE }

    fun use(sinceSampleMs: Long, hasPose: Boolean, freshMs: Long = 100, holdMs: Long = HOLD_MS): Use = when {
        !hasPose -> Use.NONE
        sinceSampleMs <= freshMs -> Use.PREDICT
        sinceSampleMs <= holdMs -> Use.HOLD
        else -> Use.NONE
    }
}

/** 0.5.10: one line on the head tracking chain, for the log every few seconds. */
data class HeadChain(
    val imuHz: Double,
    val readsPerS: Double,
    val waitedMs: Float,
    val predictMs: Float,
    val pacer: String,
    /** 0.5.20: the horizon's p95 over the last frames; [predictMs] is then their p50. */
    val predictP95Ms: Float? = null,
) {
    fun line(): String = String.format(
        Locale.ROOT,
        "head chain: IMU %.0f Hz in %.0f reads a second (about %.0f reports a read), newest report %.1f ms in the pipe, pose predicted %s ahead; %s",
        imuHz, readsPerS, if (readsPerS > 0) imuHz / readsPerS else 0.0, waitedMs,
        if (predictP95Ms != null) "p50 %.1f ms p95 %.1f ms".format(Locale.ROOT, predictMs, predictP95Ms) else "%.0f ms".format(Locale.ROOT, predictMs),
        pacer,
    )
}

/**
 * 0.5.10: the frame rate the Space's surface asks for: the refresh the glasses run at now, so
 * SurfaceFlinger composes every frame at it instead of a lower render rate. Never another mode's
 * rate: a mode switch blanks the glasses for a moment (asked only as seamless).
 */
object FrameRateVote {
    fun rateFor(currentHz: Float): Float = currentHz.takeIf { it.isFinite() && it >= 10f } ?: 60f
}
