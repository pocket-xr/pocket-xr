package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs

/**
 * How the glasses' sensor axes map onto the head frame (x right, y up, z back). The One family
 * axes are not documented, so this is a setting; [suggest] reads gravity while the glasses sit
 * still to tell which preset puts it on the up axis.
 */
enum class AxisMap(val label: String, private val ix: Int, private val sx: Float, private val iy: Int, private val sy: Float, private val iz: Int, private val sz: Float) {
    XRIGHT_YUP_ZBACK("x right, y up, z back", 0, 1f, 1, 1f, 2, 1f),
    XRIGHT_YDOWN_ZFRONT("x right, y down, z front", 0, 1f, 1, -1f, 2, -1f),
    XRIGHT_ZUP_YFRONT("x right, y front, z up", 0, 1f, 2, 1f, 1, -1f),
    XFRONT_YLEFT_ZUP("x front, y left, z up", 1, -1f, 2, 1f, 0, -1f),
    XLEFT_YUP_ZFRONT("x left, y up, z front", 0, -1f, 1, 1f, 2, -1f),
    XDOWN_YRIGHT_ZBACK("x down, y right, z back", 1, 1f, 0, -1f, 2, 1f),

    /**
     * 0.5.92 (Mike on the XREAL One: "everything is upside down", "windows arent staying in place"): its gravity read
     * along -y, so the default put the head rolled 177 to 178 degrees. 0.5.92 took this half turn about the forward axis
     * for the One; it turned left and right the right way and up and down the wrong way. 0.5.94: the One's is
     * [XRIGHT_YDOWN_ZFRONT]; this one stays in the list.
     */
    XLEFT_YDOWN_ZBACK("x left, y down, z back", 0, -1f, 1, -1f, 2, 1f),

    /**
     * 1.2.13 (Discussion #81): the One's layout turned a quarter about the side axis, gravity's reaction along the
     * sensor's -z ([XRIGHT_ZUP_YFRONT] is the other quarter). With [XUP_YLEFT_ZBACK] every direction gravity can read
     * along has a layout, so the gravity check ([GravityAxes]) always has one to pick.
     */
    XRIGHT_ZDOWN_YBACK("x right, y back, z down", 0, 1f, 2, -1f, 1, 1f),
    XUP_YLEFT_ZBACK("x up, y left, z back", 1, -1f, 0, 1f, 2, 1f),
    ;

    fun apply(v: Vec3): Vec3 = Vec3(pick(v, ix) * sx, pick(v, iy) * sy, pick(v, iz) * sz)

    /** 1.2.13: head axes back to the sensor's, the inverse of [apply]. */
    fun unapply(h: Vec3): Vec3 {
        val a = FloatArray(3)
        a[ix] = h.x * sx
        a[iy] = h.y * sy
        a[iz] = h.z * sz
        return Vec3(a[0], a[1], a[2])
    }

    /** 0.5.55: the axis by index, without the FloatArray each call made (two a sample, the gyro and the accelerometer). */
    private fun pick(v: Vec3, i: Int): Float = when (i) {
        0 -> v.x
        1 -> v.y
        else -> v.z
    }

    /** The axes as shown on the head tracking screen, in the phone's language; [label] stays the English for the log. */
    val text: String get() = com.hayeco.flipspaces.core.UiText.get("spatial_axes_" + name.lowercase(), label)

    companion object {
        val DEFAULT = XRIGHT_YUP_ZBACK

        /**
         * Presets that put a still reading of [rawAccel] on +y (an accelerometer at rest reports
         * the reaction to gravity, pointing up). Empty when gravity is not clearly on one axis.
         */
        fun suggest(rawAccel: Vec3): List<AxisMap> {
            val n = rawAccel.length
            if (n < 1e-3f) return emptyList()
            return entries.filter { it.apply(rawAccel).y / n > 0.9f }
        }
    }
}

data class FusionSettings(
    val axes: AxisMap = AxisMap.DEFAULT,
    /**
     * Mahony proportional gain: how fast gravity pulls roll and pitch back, rad/s per radian.
     * 0.5.11: 0.05 (was 1.0, doubled while still). Shifting in a seat is mostly translation, whose
     * 0.1 to 0.5 m/s2 tips the measured gravity by 0.5 to 3 degrees; at 1.0 to 2.0 the room tilted
     * toward that false up at 1 to 2 degrees a second.
     */
    val kp: Float = 0.05f,
    /** 0.5.11: the pull while the head turns faster than [quietGyro] (gravity is trusted fully only when quiet). */
    val kpMoving: Float = 0.005f,
    /** 0.5.11: below this rate (rad/s, about 3 deg/s) the head counts as quiet for gravity. */
    val quietGyro: Float = 0.05f,
    /**
     * 0.5.11: gravity is skipped while the accelerometer's size differs from the learned gravity by
     * more than this share (the head accelerates).
     */
    val gravityGate: Float = 0.01f,
    /** Mahony integral gain. 0.5.11: 0 (was 0.02); the bias comes from the static detector instead. */
    val ki: Float = 0f,
    /** How far ahead to predict the pose: roughly motion to photon latency. */
    val predictSeconds: Float = 0.020f,
    /** 0.5.10 bias rule (used only with [legacyBias]): below this rate (rad/s) the head counts as still. */
    val stillGyro: Float = 0.04f,
    /** 0.5.10 bias rule: seconds of stillness before gyro bias is learned. */
    val stillSeconds: Float = 0.8f,
    /** 0.5.10 bias rule: bias learning rate per second while still. */
    val biasRate: Float = 0.5f,
    /**
     * 0.5.10: above this rate (rad/s, about 23 deg/s) the head is turning and gravity's pull on
     * roll and pitch drops to [turningWeight]. Used only with [legacyBias].
     */
    val turningGyro: Float = 0.4f,
    val turningWeight: Float = 0.15f,
    /**
     * 0.5.11: the 0.5.10 bias and gravity rules, for the before and after tests only. They made
     * windows follow Mike's head fidgets: anything under 2.3 deg/s for 0.8 s counted as still, and
     * the bias then chased the head's own slow motion with a 2 s time constant.
     */
    val legacyBias: Boolean = false,
    /** 0.5.11 static detector ([StaticDetector]): the window judged, s. */
    val staticWindowS: Float = 4f,
    /**
     * 0.5.11: peak to peak of each (50 Hz low passed) gyro axis over the window under this (rad/s,
     * 1.1 deg/s) is static. A head held "still" (sub-degree sway and tremor) is above it, so its
     * motion never feeds the bias; glasses on a table or a truly still head are below it.
     */
    val staticPeakToPeak: Float = 0.02f,
    /**
     * 0.5.11: and each accelerometer axis within this share of gravity, peak to peak (about
     * 0.1 m/s2 or 0.6 degrees of tilt): no translation, so gravity is all it feels.
     */
    val staticAccel: Float = 0.01f,
    /** 0.5.11: the most the bias moves per second of stillness once learned (rad/s per s). */
    val biasStepPerS: Float = 0.0005f,
    /**
     * 0.5.11: once learned, the bias follows static windows with this time constant (s): a bias
     * random walk that is small, yet still tracks a warming gyro.
     */
    val biasTauS: Float = 60f,
    /** 0.5.11: a window mean further than this from the bias is a slow turn, not drift: the bias stays. */
    val biasTrust: Float = 0.002f,
    /** 0.5.11: the bias is never larger than this, rad/s per axis. */
    val maxBias: Float = 0.05f,
    /** 0.5.11: for this long after start the start pose (from one accelerometer sample) levels at [settleKp]. */
    val settleS: Float = 2f,
    val settleKp: Float = 1f,
    /**
     * 0.5.11: the pull while the [StaticDetector] says static: then the accelerometer feels only
     * gravity (no translation for seconds), so it is trusted most.
     */
    val kpStatic: Float = 0.3f,
    /** 0.5.11: the two halves of a static window must agree on the mean rate within this (rad/s): a constant bias, not a slow sway. */
    val staticHalves: Float = 0.0003f,
    /**
     * 0.5.12: if the glasses lie still (the accelerometer steady) but the gyro's own
     * noise keeps its peak to peak over [staticPeakToPeak], the gate is set from that measurement:
     * 1.5 x the p99 of [staticGateWindows] such windows, never above [staticGateMax]
     * (0.03 rad/s). Otherwise a noisy gyro never learns a bias after the first session.
     */
    val adaptiveStaticGate: Boolean = true,
    val staticGateMax: Float = 0.03f,
    val staticGateWindows: Int = 10,
    /**
     * 0.5.13, the start gate: after a start or reconnect the first
     * [startSkip] samples are skipped, then roll and pitch are aligned from the mean of a
     * [startWindow]-sample window only once its mean accelerometer is within [startAccelTol] m/s2 of
     * 9.81 and its mean gyro rate under [startGyroMax] rad/s; otherwise the next window is tried.
     * Until then the pose turns with the gyro from the first sample's level, as before. 0.5.12
     * aligned on the first sample, so glasses picked up while moving started tilted.
     * Added to those two tests: each accelerometer axis must also stay within [startAccelSpread]
     * m/s2 peak to peak over the window. Without it a 100-sample window that fell on the still
     * moment of a nod passed while the glasses were still swinging (0.42 s into a 1 s pick-up).
     */
    val startGate: Boolean = true,
    val startSkip: Int = 20,
    val startWindow: Int = 200,
    val startAccelTol: Float = 2.0f,
    val startGyroMax: Float = 0.2f,
    val startAccelSpread: Float = 1.0f,
    /**
     * 0.5.13: gravity pulls toward the mean of the accelerometer over this long (s;
     * 0 is 0.5.12's raw sample). The 1 % size gate stays on the raw
     * value, and gravity also waits while the raw value is more than [gravityGate] of g from the
     * mean: the accelerometer is changing, so the head is accelerating, as when it shifts in a seat.
     */
    val accelMeanS: Float = 0.3f,
    /**
     * 0.5.14: the reference size of gravity is 9.81 until the start gate
     * passes, then the size of the gate window's mean, then it follows the accelerometer mean with a
     * [gravityRefTauS] time constant while gravity is in use. 0.5.13 took it from the very first raw
     * sample and moved it only while static, so glasses put on while moving (3 % off g) skipped
     * gravity on 100 % of samples for the whole session.
     */
    val gravityRefFromGate: Boolean = true,
    val gravityRefTauS: Float = 30f,
    /**
     * 0.5.14: gravity's size and change checks run on averaged values, in tiers. The size of the
     * [accelMeanS] mean against the reference, and the change between a [accelShortS] mean and the
     * [accelMeanS] mean, both as a share of the reference: within [gravityFull] the full pull, within
     * [gravityWide] at most [kpMoving], beyond that none. 0.5.13 judged each raw sample at 1 % twice,
     * which a head's own accelerometer noise fails most of the time.
     */
    val gravityTiers: Boolean = true,
    val gravityFull: Float = 0.02f,
    val gravityWide: Float = 0.05f,
    val accelShortS: Float = 0.05f,
    /**
     * 0.5.14: the change check's full tier is tighter than the size check's: averaged in the room's
     * axes the change is the head's own acceleration only (0.05 m/s2 of noise per axis is 0.07 % after
     * a 50 ms mean), and a 0.3 m/s2 seat shift (3 %) must not get the full pull.
     */
    val gravityChangeFull: Float = 0.01f,
    /**
     * 0.5.14: the bias also learns while worn. Roll and pitch: gravity's tilt error feeds the bias
     * (a Mahony integral, rad/s of bias per radian of error per second), in the full tier only and
     * never while settling or static (the static detector owns that). 0.5.13 learned the bias only on
     * a table, so on a head it stayed 0 and roll drifted 0.23 deg/s.
     */
    val kiWorn: Float = 0.01f,
    /**
     * 0.5.14: while no bias is known yet (none seeded, none learned static), the first [wornFastS] s
     * of worn learning pull at least [kpWornFast] with an integral of [kiWornFast]: an unknown roll
     * bias b leaves a tilt of b / kp until the integral has it, 4.6 degrees at 0.05 for the measured
     * 0.004 rad/s, 0.8 degrees at 0.3. Yaw meanwhile uses [wornFastTauS] for its first
     * [wornFastWindows] windows.
     */
    val wornFastS: Float = 90f,
    val kpWornFast: Float = 0.3f,
    val kiWornFast: Float = 0.03f,
    val wornFastTauS: Float = 60f,
    val wornFastWindows: Int = 4,
    /** 0.5.14: for this long after the static detector measured the bias, nothing is learned while worn. */
    val wornAfterStaticS: Float = 60f,
    /**
     * 0.5.14: yaw, which gravity cannot see: over each [wornWindowS] window while worn, the gyro's mean
     * along the head's up is the bias plus the head's net turn over the window. A window whose mean is
     * under [wornTrust] rad/s (bias and net turn together under 0.7 deg/s, 21 degrees in 30 s) moves the
     * bias [wornWindowS] / [wornTauS] of the way; a larger one is a turn, and the bias stays. Head
     * motion that comes back averages out over the windows; a steady turn under the gate does not.
     */
    val wornYawBias: Boolean = true,
    val wornWindowS: Float = 30f,
    val wornTauS: Float = 180f,
    val wornTrust: Float = 0.012f,
    /**
     * 0.5.15: the worn bias loop is damped. 0.5.14 kept [kiWorn] 0.01 while
     * gravity's pull fell to [kpMoving] 0.005, a loop with a damping ratio of about 0.025, so the
     * learned bias swung +-0.9 deg/s with a 1.5 to 2 minute period. Now:
     * - the integral is at most kp squared over 4 for the pull in use (critically damped);
     * - it learns only while the unbiased rate is under [wornLearnQuietGyro] (or, with no bias
     *   known at all, in the fast start, when gravity must hold roll against the whole bias) and the
     *   full tier has held for [wornLearnHoldS];
     * - it learns only while the head is within about 25 degrees of level ([wornLevelUp]) and never
     *   moves the bias along the head's yaw axis (y), which gravity cannot see: the yaw part is the
     *   still yaw windows' alone, so a learned tilt bias cannot leak into yaw when the head pitches;
     * - each step moves at most [wornStepPerS] rad/s a second, and the bias never goes further than
     *   [wornAnchorMax] per axis from the last static measurement (seeded or learned this session);
     * - the fast damped start runs for its first [wornFastS] s even when a bias was seeded
     *   ([wornFastWhenSeeded]), and only while the head is quiet.
     */
    val wornBiasDamped: Boolean = true,
    val wornLearnQuietGyro: Float = 0.05f,
    val wornLearnHoldS: Float = 1f,
    val wornLevelUp: Float = 0.9f,
    val wornStepPerS: Float = 0.0005f,
    val wornAnchorMax: Float = 0.003f,
    val wornFastWhenSeeded: Boolean = true,
    /**
     * 0.5.15: a 30 s yaw window counts only when the head was really still in it: at least
     * [wornYawStillShare] of its 0.1 s means of the unbiased rate under [wornYawStillGyro] rad/s.
     * 0.5.14 took any window whose net turn was under 0.7 deg/s.
     */
    val wornYawStillOnly: Boolean = true,
    val wornYawStillGyro: Float = 0.02f,
    val wornYawStillShare: Float = 0.95f,
    /**
     * 0.5.15: the
     * start gate demands real stillness over a [startStrictWindow]-sample window (0.3 s at 1 kHz),
     * on the gyro through a 50 Hz and the accelerometer through a 10 Hz low pass as the still check
     * has them: each gyro axis within [startGyroPeakToPeak] rad/s peak to peak and a standard
     * deviation under [startGyroStd]; each accelerometer axis within [startAccelShare] of g peak to
     * peak and a standard deviation under [startAccelStdShare] of g. If no window passes in
     * [startFallbackS] s, the stillest window seen within [startFallbackGyro] rad/s and
     * [startFallbackAccelShare] of g aligns instead, logged as such.
     */
    val startStrict: Boolean = true,
    val startStrictWindow: Int = 300,
    val startGyroPeakToPeak: Float = 0.05f,
    val startGyroStd: Float = 0.01f,
    val startAccelShare: Float = 0.02f,
    val startAccelStdShare: Float = 0.005f,
    val startFallbackS: Float = 3f,
    val startFallbackGyro: Float = 0.2f,
    val startFallbackAccelShare: Float = 0.05f,
    /**
     * 0.5.15: until the start gate passes, gravity is not used at all: no pull, no reference size
     * taken and no bias learned. 0.5.14 pulled toward a 9.81 reference from the first samples.
     */
    val gravityAfterGate: Boolean = true,
    /**
     * 0.5.15: the IMU to display mounting rotation, as the roll and pitch
     * (degrees) the IMU's gravity reads while the display is level ([LevelCalibration]). 0 is the
     * identity, all 0.5.14 had.
     */
    val mountRollDeg: Float = 0f,
    val mountPitchDeg: Float = 0f,
    /**
     * 0.5.65: the glasses' factory display mount ([FactoryCalibration.displayMount]), used instead of
     * [mountRollDeg] and [mountPitchDeg] when set; it also holds the twist about up that a level calibration cannot measure.
     */
    val factoryMount: Quat? = null,
    /** 0.5.65: the glasses' factory gyro matrix (3x3 row-major), applied to the raw gyro when set. */
    val gyroMatrix: FloatArray? = null,
    /** 0.5.15: a recentre also relevels roll and pitch from the averaged gravity, when the head is steady enough. */
    val recentreLevels: Boolean = true,
    val recentreGyro: Float = 0.2f,
    /**
     * 1.2.13 (Discussion #81: a head 40 degrees up for 20 minutes of videos, so every recentre left the windows 40
     * degrees below the view): a recentre takes the head's pitch as straight ahead too, when it is more than
     * [recentreTiltMinDeg] from level, so the windows land where the head looks, lying back or looking down. Within it,
     * the horizon stays straight ahead, as before. Never roll: the windows stay level to that view.
     */
    val recentreFollowsTilt: Boolean = true,
    val recentreTiltMinDeg: Float = 15f,
    /**
     * 0.5.15: the [settleS] fast level after the start gate counts only samples in the full tier
     * with the head quiet, for at most [settleMaxS]; while the head moves it pulls at [kpMoving].
     */
    val settleOnQuiet: Boolean = true,
    val settleMaxS: Float = 10f,
    /**
     * 0.5.16: yaw windows a
     * worn head can pass. Each lasts [wornYawWornWindowS] s; its 0.1 s means are judged on the yaw
     * axis only (the rate along world up), under [wornYawAxisGyro] rad/s for at least
     * [wornYawAxisShare] of them; and it counts only when its mean along up agrees with the previous
     * such window's within [wornYawAgree] rad/s (two windows that turned by different amounts on net
     * rarely agree; a head that comes back to its panel does). The bias then moves by the two
     * windows' mean. While no bias is known the first [wornYawFastS] s of counted windows use
     * [wornFastTauS]. False is 0.5.15's 30 s windows with every axis held still.
     */
    val wornYawWorn: Boolean = true,
    val wornYawWornWindowS: Float = 10f,
    val wornYawAxisGyro: Float = 0.03f,
    /**
     * 0.5.17 (Mike's rests reached 87 to 89 % against the 90 % needed, so 0
     * of 34 windows were used): 75 % (0.5.16: 90 %).
     */
    val wornYawAxisShare: Float = 0.75f,
    val wornYawAgree: Float = 0.002f,
    val wornYawFastS: Float = 120f,
    /**
     * 0.5.16: with a static bias known (seeded, measured or the old one confirmed), the worn yaw
     * windows move its part along up by at most this (rad/s; 0.046 deg/s of drift): a chair turning
     * slowly and steadily passes the pair rule, and must not drag the yaw bias with it.
     */
    val wornYawAnchorMax: Float = 0.0008f,
    /**
     * 0.5.17: a window's estimate is the gyro's mean along up over its
     * rested 0.1 s segments only (those under [wornYawAxisGyro] on the yaw axis, as the yaw line
     * measures the drift), so a glance in the window adds nothing to it. 0.5.16 took the whole
     * window's mean, glances included.
     */
    val wornYawRested: Boolean = true,
    /**
     * 0.5.17 (the saved static bias was 0.0018 rad/s off along up on the worn,
     * warm glasses, and the 0.0008 guard could not fix that): once [wornYawAgreeCount] rested windows
     * agree, each within [wornYawAgreeWithin] rad/s of their mean, and that mean is within
     * [wornYawAnchorGrown] of the static bias along up, the guard grows to [wornYawAnchorGrown] for
     * the rest of the session and the windows move the bias with [wornFastTauS]. A slow steady turn
     * agrees with itself too, but one faster than [wornYawAnchorGrown] (0.17 deg/s) along up is no
     * bias a static measurement could be off by, and never grows the guard.
     */
    val wornYawGuardGrows: Boolean = true,
    val wornYawAnchorGrown: Float = 0.003f,
    val wornYawAgreeCount: Int = 3,
    val wornYawAgreeWithin: Float = 0.0005f,
    /**
     * 0.5.16: an older saved bias (0.5.14's key, [HeadFusion.offerOldBias])
     * is taken as the static bias, its yaw part included, once at least [oldBiasLearnS] s of worn
     * gravity learning have put this session's x and z within [oldBiasConfirm] rad/s of it.
     */
    val oldBiasConfirm: Float = 0.001f,
    val oldBiasLearnS: Float = 10f,
    /** 0.5.16: a recentre's relevel turns the averaged gravity with it, so a second one at once changes nothing. False is 0.5.15. */
    val recentreTurnsGravity: Boolean = true,
    /**
     * 0.5.18 (Mike: "in full SBS, with my head still, the windows are a tiny bit fidgety"): gravity's
     * pull no longer switches from sample to sample. 0.5.17 picked 0.3, 0.05 or 0.005 for each 1 kHz
     * sample from the static flag and that one sample's rate against [quietGyro], so a worn head's
     * gyro noise flicked the pull between 0.05 and 0.005 many times a second. Now the quiet test reads
     * the rate's [quietMeanS] mean, and the pull slides toward its tier's value: up over [kpRiseS],
     * down over [kpFallS] (a turn or a skipped tier lowers it at once, so a turn's centripetal pull is
     * still not taken for gravity). The fast level after the start gate is not slid. False is 0.5.17.
     */
    val smoothWeights: Boolean = true,
    val kpRiseS: Float = 1f,
    val kpFallS: Float = 0.02f,
    val quietMeanS: Float = 0.05f,
    /**
     * 0.5.18: corrections ride on motion. While the head is still, gravity turns the view by at most
     * [restCorrection] rad/s (0.003 deg/s), plus [correctionPerRate] of the head's own rate as it
     * moves (2 % of a turn, which nobody sees): a tilt error is levelled in slow ramps while the head
     * rests and quickly while it moves, never as a creep the eye can follow. The fast level after the
     * start gate is not limited. 0 turns the limit off (0.5.17).
     */
    val restCorrection: Float = 0.00005f,
    val correctionPerRate: Float = 0.02f,
    /**
     * 0.5.18: a change of the bias (a static measurement, a worn yaw window, the worn gravity
     * integral) reaches the integration as a ramp with this time constant (s), not as a step in the
     * rate. 0 is 0.5.17.
     */
    val biasRampS: Float = 0.5f,
    /**
     * 0.5.18: hysteresis on the static flag: between the window checks it ends on the gyro only once
     * a chunk's spread reaches this share of the gate (0.5.17: the gate itself). The accelerometer
     * still ends it at once (a seat shift). 1 is 0.5.17.
     */
    val staticExitShare: Float = 1.25f,
    /**
     * 0.5.18 (1 of 31 yaw windows used, rested drift +0.157 deg/s): rested
     * moments are pooled across windows. Every rested run of at least [poolRunS] s (0.1 s means under
     * [wornYawAxisGyro] along up of the pool's own last estimate, a mean shift, so a slow turn is not
     * read as slower than it is) is kept with its time; each [poolNeedS] s of them gathered within
     * [poolSpanS] s join a long run mean of the rested rate along up ([poolLongS] s time constant),
     * whatever share of each window was still. A head that drifts slowly while it reads (0.08 deg/s
     * over a 30 s sway) and comes back averages out of that mean; a bias error does not. Once
     * [poolMinS] s of rest are in it, the mean moves the bias as a used window does, under the same
     * guard; the guard grows ([wornYawGuardGrows]) once [poolGrowS] s of rest are in and its last
     * three values agree within [poolAgree]. A slow steady turn still never grows the guard (its mean
     * is further than the grown guard from the static bias). False is 0.5.17 (only whole windows
     * with a 75 % still share count).
     */
    val yawPool: Boolean = true,
    val poolRunS: Float = 0.3f,
    val poolNeedS: Float = 3f,
    val poolSpanS: Float = 30f,
    val poolLongS: Float = 180f,
    val poolMinS: Float = 20f,
    val poolGrowS: Float = 45f,
    val poolAgree: Float = 0.0005f,
    /**
     * 0.5.19 (the glasses lay upside down off the head and an
     * upside down batch, +0.0049 rad/s along up, joined the upright pool 8 s later): yaw is learned
     * only while the glasses are worn upright, their up within [uprightDeg] of the room's. False is 0.5.18.
     */
    val yawUprightOnly: Boolean = true,
    val uprightDeg: Float = 60f,
    /**
     * 0.5.19 ("fix the sign"): the pool keeps the rested rate as a vector in the
     * glasses' own axes (each rested run's rate along up, times up in those axes), not as a number
     * along up: the bias is fixed in the glasses' axes, and turned upside down its part along up
     * changes sign, which the 0.5.18 number added in with the sign the wrong way. And the pool starts
     * over when up turns more than [poolClearDeg] in the glasses' axes. False is 0.5.18.
     */
    val poolVector: Boolean = true,
    val poolClearDeg: Float = 30f,
    /**
     * 0.5.19: how far a pool entry's 0.1 s means may be from the pool's estimate, rad/s (0.5.18:
     * [wornYawAxisGyro]). A stricter one was tried at 0.015 on the restless head of
     * [Fixes0517YawTest], it cut the 8 Hz tremor's chunks unevenly around a centre that starts at the
     * static bias and held the pool 0.0008 rad/s short of the truth, so 0.5.18's 0.03 stays.
     */
    val poolRestGyro: Float = 0.03f,
    /**
     * 0.5.19 (the "rested" runs held slow head yaw; their mean, -0.0022 to -0.0027 rad/s
     * along up, pulled the bias 0.0007 rad/s away from the static truth under the 0.0008 guard): with a
     * static measurement known, the pool moves the bias at most [poolCap] rad/s along up from it, until a
     * static measurement agrees with the pool (within [poolAgree]); a static measurement that does not
     * agree starts the pool over. A pool that only agrees with itself does not lift it: a slow turn of
     * the head while it rests agrees with itself too, and nothing on a worn head tells the two apart.
     * [poolCapLiftS] 0 keeps it so; above 0, that many seconds of self agreeing rest lift it as well.
     * 0 [poolCap] is 0.5.18.
     */
    val poolCap: Float = 0.0003f,
    val poolCapLiftS: Float = 0f,
) {
    companion object {
        /** 0.5.18's fusion (the yaw pool as a number along up, mixed orientations, 0.03 rest gate, no cap, any orientation), for the tests of 0.5.19. */
        fun v0518() = FusionSettings(yawUprightOnly = false, poolVector = false, poolClearDeg = 180f, poolCap = 0f)

        /** 0.5.17's fusion (switching gravity weights, unlimited corrections, bias steps, whole yaw windows only), for the tests of 0.5.18. */
        fun v0517() = v0518().copy(smoothWeights = false, restCorrection = 0f, biasRampS = 0f, staticExitShare = 1f, yawPool = false)

        /** 0.5.16's fusion (whole window yaw means, 90 % still share, the 0.0008 guard fixed), for the tests of 0.5.17. */
        fun v0516() = v0517().copy(wornYawAxisShare = 0.9f, wornYawRested = false, wornYawGuardGrows = false)

        /** 0.5.15's fusion (30 s still-only yaw windows, recentre gravity not turned), for the tests of 0.5.16. */
        fun v0515() = v0517().copy(wornYawWorn = false, recentreTurnsGravity = false)

        /** 0.5.14's fusion (undamped worn bias, the loose start gate, recentre on yaw only), for the tests of 0.5.15. */
        fun v0514() = v0517().copy(
            wornBiasDamped = false, wornFastWhenSeeded = false, wornYawStillOnly = false, startStrict = false,
            gravityAfterGate = false, recentreLevels = false, settleOnQuiet = false,
        )

        /** 0.5.10's fusion, for the before and after comparisons. */
        fun v0510() = v0517().copy(kp = 1.0f, ki = 0.02f, legacyBias = true)

        /** 0.5.12's fusion (no start gate, gravity from the raw sample), for the before and after tests of 0.5.13. */
        fun v0512() = v0513().copy(startGate = false, accelMeanS = 0f)

        /** 0.5.13's fusion (gravity size from the first raw sample, 1 % raw gates, bias only on a table), for the tests of 0.5.14. */
        fun v0513() = v0514().copy(gravityRefFromGate = false, gravityTiers = false, kiWorn = 0f, wornYawBias = false)
    }
}

/**
 * 0.5.13: the start gate ([FusionSettings.startGate]). Feed it every sample after
 * a start; [add] returns the window's mean accelerometer (head axes) once a window passes.
 */
class StartGate {
    private var seen = 0
    private var n = 0
    private var accSum = Vec3.ZERO
    private var gyroSum = 0f
    private var accMin = Vec3.ZERO
    private var accMax = Vec3.ZERO

    /** 0.5.15: the low passed values, their extremes and their sums of squares over the window. */
    private var lpGyro: Vec3? = null
    private var lpAccel: Vec3? = null
    private var gMin = Vec3.ZERO
    private var gMax = Vec3.ZERO
    private var gSum = Vec3.ZERO
    private var gSq = Vec3.ZERO
    private var aMin = Vec3.ZERO
    private var aMax = Vec3.ZERO
    private var aSum = Vec3.ZERO
    private var aSq = Vec3.ZERO
    private var gRef = Vec3.ZERO
    private var aRef = Vec3.ZERO

    var passed = false
        private set
    var windowsTried = 0
        private set
    var passedAtSample = -1
        private set

    /** 0.5.14: the passing window's mean accelerometer, and why the last window was refused (for the log). */
    var passedMean = Vec3.ZERO
        private set
    var lastRefusal: String? = null
        private set

    /** 0.5.15: true when no strict window passed in time and the stillest one aligned instead. */
    var passedByFallback = false
        private set

    /** 0.5.15: the passing window's gyro peak to peak (rad/s) and accelerometer spread (share of g), for the log. */
    var passedGyroPeakToPeak = 0f
        private set
    var passedAccelSpread = 0f
        private set

    /** 0.5.15: one judged window: its mean accelerometer (head axes), figures, and whether it passed. */
    class Window(val mean: Vec3, val gyroPeakToPeak: Float, val accelSpread: Float, val passed: Boolean, val fallbackOk: Boolean)

    fun reset() {
        seen = 0
        n = 0
        accSum = Vec3.ZERO
        gyroSum = 0f
        passed = false
        windowsTried = 0
        passedAtSample = -1
        passedMean = Vec3.ZERO
        lastRefusal = null
        lpGyro = null
        lpAccel = null
        passedByFallback = false
        passedGyroPeakToPeak = 0f
        passedAccelSpread = 0f
    }

    /** 0.5.15: the fusion aligned from the stillest window after [FusionSettings.startFallbackS]. */
    fun passFallback(mean: Vec3, gyroPeakToPeak: Float, accelSpread: Float) {
        passed = true
        passedByFallback = true
        passedAtSample = seen
        passedMean = mean
        passedGyroPeakToPeak = gyroPeakToPeak
        passedAccelSpread = accelSpread
    }

    /** 0.5.13 to 0.5.14: returns the passing window's mean accelerometer, or null. */
    fun add(gyro: Vec3, accel: Vec3, s: FusionSettings): Vec3? = addWindow(gyro, accel, s, 0.001f)?.takeIf { it.passed }?.mean

    /** Feeds one sample; at the end of each window returns how it was judged. */
    fun addWindow(gyro: Vec3, accel: Vec3, s: FusionSettings, dt: Float): Window? {
        if (passed) return null
        seen++
        // The low passes run from the first sample, so the window starts settled.
        val kg = (dt / (0.0032f + dt)).coerceIn(0f, 1f)
        val fg = lpGyro?.let { it + (gyro - it) * kg } ?: gyro
        lpGyro = fg
        val ka = (dt / (0.016f + dt)).coerceIn(0f, 1f)
        val fa = lpAccel?.let { it + (accel - it) * ka } ?: accel
        lpAccel = fa
        if (seen <= s.startSkip) return null
        accSum = accSum + accel
        gyroSum += gyro.length
        if (n == 0) {
            accMin = accel
            accMax = accel
            gMin = fg; gMax = fg; aMin = fa; aMax = fa
            gSum = Vec3.ZERO; gSq = Vec3.ZERO; aSum = Vec3.ZERO; aSq = Vec3.ZERO
            gRef = fg; aRef = fa
        } else {
            accMin = Vec3(minOf(accMin.x, accel.x), minOf(accMin.y, accel.y), minOf(accMin.z, accel.z))
            accMax = Vec3(maxOf(accMax.x, accel.x), maxOf(accMax.y, accel.y), maxOf(accMax.z, accel.z))
            gMin = Vec3(minOf(gMin.x, fg.x), minOf(gMin.y, fg.y), minOf(gMin.z, fg.z))
            gMax = Vec3(maxOf(gMax.x, fg.x), maxOf(gMax.y, fg.y), maxOf(gMax.z, fg.z))
            aMin = Vec3(minOf(aMin.x, fa.x), minOf(aMin.y, fa.y), minOf(aMin.z, fa.z))
            aMax = Vec3(maxOf(aMax.x, fa.x), maxOf(aMax.y, fa.y), maxOf(aMax.z, fa.z))
        }
        // Around the window's first value, so a float keeps the variance of a 9.81 signal.
        val dg = fg - gRef
        val da = fa - aRef
        gSum = gSum + dg
        gSq = gSq + Vec3(dg.x * dg.x, dg.y * dg.y, dg.z * dg.z)
        aSum = aSum + da
        aSq = aSq + Vec3(da.x * da.x, da.y * da.y, da.z * da.z)
        n++
        val size = if (s.startStrict) s.startStrictWindow else s.startWindow
        if (n < size) return null
        val mean = accSum * (1f / n)
        val gyroMean = gyroSum / n
        val spread = accMax - accMin
        val gP2p = maxOf(gMax.x - gMin.x, gMax.y - gMin.y, gMax.z - gMin.z)
        val aP2p = maxOf(aMax.x - aMin.x, aMax.y - aMin.y, aMax.z - aMin.z) / GRAVITY
        val gStd = maxStd(gSum, gSq, n)
        val aStd = maxStd(aSum, aSq, n) / GRAVITY
        windowsTried++
        n = 0
        accSum = Vec3.ZERO
        gyroSum = 0f
        val sizeOk = abs(mean.length - GRAVITY) <= s.startAccelTol
        val ok = if (s.startStrict) {
            sizeOk && gP2p <= s.startGyroPeakToPeak && gStd <= s.startGyroStd && aP2p <= s.startAccelShare && aStd <= s.startAccelStdShare
        } else {
            sizeOk && gyroMean < s.startGyroMax && maxOf(spread.x, spread.y, spread.z) <= s.startAccelSpread
        }
        val fallbackOk = sizeOk && gP2p <= s.startFallbackGyro && aP2p <= s.startFallbackAccelShare
        if (ok) {
            passed = true
            passedAtSample = seen
            passedMean = mean
            passedGyroPeakToPeak = gP2p
            passedAccelSpread = aP2p
            return Window(mean, gP2p, aP2p, true, fallbackOk)
        }
        lastRefusal = if (s.startStrict) String.format(
            java.util.Locale.ROOT,
            "last window: mean accel %.2f m/s2 (gate 9.81 +- %.1f), gyro peak to peak %.4f rad/s (gate %.3f), sd %.4f (gate %.3f), accel spread %.2f %% of g (gate %.0f %%), sd %.2f %% (gate %.1f %%)",
            mean.length, s.startAccelTol, gP2p, s.startGyroPeakToPeak, gStd, s.startGyroStd, aP2p * 100, s.startAccelShare * 100, aStd * 100, s.startAccelStdShare * 100,
        ) else String.format(
            java.util.Locale.ROOT,
            "last window: mean accel %.2f m/s2 (gate 9.81 +- %.1f), mean gyro %.3f rad/s (gate %.2f), accel spread %.2f m/s2 (gate %.1f)",
            mean.length, s.startAccelTol, gyroMean, s.startGyroMax, maxOf(spread.x, spread.y, spread.z), s.startAccelSpread,
        )
        return Window(mean, gP2p, aP2p, false, fallbackOk)
    }

    private fun maxStd(sum: Vec3, sq: Vec3, n: Int): Float {
        fun sd(s: Float, q: Float): Float {
            val m = s / n
            return kotlin.math.sqrt((q / n - m * m).coerceAtLeast(0f))
        }
        return maxOf(sd(sum.x, sq.x), sd(sum.y, sq.y), sd(sum.z, sq.z))
    }

    companion object {
        const val GRAVITY = 9.81f
    }
}

/** 0.5.13: a running mean of the accelerometer over a fixed number of samples. */
class AccelMean {
    private var buf = arrayOfNulls<Vec3>(0)
    private var next = 0
    private var count = 0
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0

    /** Samples in the window, set from the first step's dt; 0 until then. */
    val size: Int get() = buf.size
    val full: Boolean get() = buf.isNotEmpty() && count == buf.size

    fun add(a: Vec3, dt: Float, seconds: Float): Vec3 {
        if (buf.isEmpty()) {
            val n = kotlin.math.round(seconds / dt.coerceAtLeast(1e-4f)).toInt().coerceIn(1, 2000)
            buf = arrayOfNulls(n)
        }
        buf[next]?.let { old ->
            sx -= old.x; sy -= old.y; sz -= old.z
        }
        buf[next] = a
        sx += a.x; sy += a.y; sz += a.z
        next = (next + 1) % buf.size
        if (count < buf.size) count++
        return Vec3((sx / count).toFloat(), (sy / count).toFloat(), (sz / count).toFloat())
    }

    /** 0.5.16: every kept sample turned by [r] (a recentre's relevel turned the room's axes), the sums again. */
    fun rotate(r: Quat) {
        sx = 0.0; sy = 0.0; sz = 0.0
        for (i in buf.indices) {
            val v = buf[i] ?: continue
            val w = r.rotate(v)
            buf[i] = w
            sx += w.x; sy += w.y; sz += w.z
        }
    }

    fun reset() {
        buf = arrayOfNulls(0)
        next = 0
        count = 0
        sx = 0.0; sy = 0.0; sz = 0.0
    }
}

/** A fused head pose and what it took to get there. */
data class HeadPose(
    /** Recentred orientation: forward is where the user last recentred. */
    val orientation: Quat,
    /** Body-frame angular velocity, rad/s (bias removed). */
    val angularVelocity: Vec3,
    val timestampNs: Long,
    val still: Boolean,
    val gyroBias: Vec3,
) {
    /** Orientation [seconds] ahead, assuming the head keeps turning at the same rate. */
    fun predicted(seconds: Float): Quat = (orientation * Quat.fromGyro(angularVelocity, seconds)).normalized()
}

/**
 * 3DoF orientation from gyro and accelerometer: a Mahony complementary filter on a quaternion,
 * with gyro bias learned while the glasses are static (0.5.11: [StaticDetector]) and a recentre
 * that zeroes yaw. Yaw has no
 * absolute reference (the magnetometer is ignored: it is poor indoors), so it drifts slowly;
 * recentre fixes it. Not thread safe: feed it from the reader thread and publish [pose].
 */
class HeadFusion(var settings: FusionSettings = FusionSettings()) {
    private var q = Quat.IDENTITY
    private var integral = Vec3.ZERO
    private var bias = Vec3.ZERO
    private var lastNs = Long.MIN_VALUE
    private var stillFor = 0f
    private var initialised = false
    private var reference = Quat.IDENTITY
    private var lastOmega = Vec3.ZERO
    private var gravityNorm = 0f

    /** Latest raw accelerometer (sensor axes), for the axis check on the diagnostics screen. */
    var lastRawAccel: Vec3 = Vec3.ZERO
        private set

    var samples: Long = 0
        private set

    /** Feeds one IMU report. Magnetometer reports are ignored. Returns the new pose or null. */
    fun update(report: ImuReport): HeadPose? {
        if (report.type != ImuReportType.IMU) return null
        return update(report.gyro, report.accel, report.timestampNs)
    }

    fun update(rawGyro: Vec3, rawAccel: Vec3, timestampNs: Long): HeadPose {
        lastRawAccel = rawAccel
        samples++
        val gyro = settings.axes.apply(settings.gyroMatrix?.let { applyMatrix(it, rawGyro) } ?: rawGyro)
        val accel = settings.axes.apply(rawAccel)

        if (!initialised) {
            q = levelFrom(accel)
            initialised = true
            lastNs = timestampNs
            startGate.reset()
            startGate.add(gyro, accel, settings)
            return pose(timestampNs)
        }
        val dtRaw = (timestampNs - lastNs) / 1e9f
        lastNs = timestampNs
        // Device timestamps can jump on reconnect; ignore steps that are not plausible.
        if (dtRaw <= 0f || dtRaw > 0.1f) return pose(timestampNs)
        val dt = dtRaw

        if (settings.legacyBias) {
            legacyStep(gyro, accel, dt)
        } else {
            staticStep(gyro, accel, dt)
        }
        lastOmega = gyro - if (rampsBias()) appliedBias else bias
        // 0.5.55: (q * Quat.fromGyro(corrected, dt)).normalized() in one step ([HeadMath]), the same to the bit.
        q = HeadMath.turnedBy(q, corrected, dt)
        // 0.5.13: roll and pitch from the first window the start gate passes, yaw kept.
        if (settings.startGate && !settings.legacyBias && !startGate.passed) {
            val w = startGate.addWindow(gyro - bias, accel, settings, dt)
            if (w != null && w.passed) {
                restGravity = GravityAxes.Rest(settings.axes.unapply(w.mean), "start", tableStill = w.gyroPeakToPeak < settings.staticPeakToPeak)
                align(w.mean)
                startGateEvent = startGateLine()
            } else if (w != null && settings.startStrict && w.fallbackOk && w.gyroPeakToPeak < fallbackScore) {
                // 0.5.15: the stillest window so far, kept in the room's axes so it can align later.
                fallbackScore = w.gyroPeakToPeak
                fallbackWorld = q.rotate(w.mean)
                fallbackSpread = w.accelSpread
            }
            val fb = fallbackWorld
            if (!startGate.passed && settings.startStrict && fb != null && runS >= settings.startFallbackS) {
                val mean = q.conjugate().rotate(fb)
                startGate.passFallback(mean, fallbackScore, fallbackSpread)
                restGravity = GravityAxes.Rest(settings.axes.unapply(mean), "start", tableStill = fallbackScore < settings.staticPeakToPeak)
                align(mean)
                startGateEvent = startGateLine()
            }
        }
        yawDriftStep(dt)
        return pose(timestampNs)
    }

    /**
     * 0.5.16 (the gate passed by its fallback and the log said so only in
     * the 10 s line): the start gate's result once, at the moment it passes, on a still window or by
     * the fallback. Taken (and cleared) by the caller's log.
     */
    private var startGateEvent: String? = null

    /**
     * 1.2.13 (Discussion #81): the averaged accelerometer, in the sensor's own axes, when the start gate passed or a steady
     * recentre relevelled, for the gravity check of the axis layout ([GravityAxes]). Taken (and cleared) by the caller.
     */
    private var restGravity: GravityAxes.Rest? = null

    fun takeRestGravity(): GravityAxes.Rest? = restGravity.also { restGravity = null }

    fun takeStartGateEvent(): String? = startGateEvent?.let { "head gravity: $it (${if (startGate.passedByFallback) "by the fallback" else "on a still window"})" }.also { startGateEvent = null }

    /** 0.5.16: whether the start gate passed by its fallback (null before it passed). */
    val startGateByFallback: Boolean? get() = if (startGate.passed) startGate.passedByFallback else null

    /** 0.5.13: roll and pitch from the start gate's mean, yaw kept; 0.5.14: the mean's size is gravity's from now on. */
    private fun align(mean: Vec3) {
        q = relevel(q, mean)
        settleFromS = runS
        accelMean.reset()
        accelShort.reset()
        fullForS = 0f
        settledS = 0f
        // 0.5.14: the gate's mean is gravity's size from now on (0.5.13: the first raw sample).
        if (settings.gravityRefFromGate) gravityNorm = mean.length
    }

    /** 0.5.15: the stillest start window within the fallback limits, in the room's axes. */
    private var fallbackWorld: Vec3? = null
    private var fallbackScore = Float.MAX_VALUE
    private var fallbackSpread = 0f

    /** 0.5.13: the start gate, and when its alignment was (the settle time counts from there). */
    private val startGate = StartGate()
    private var settleFromS = 0f
    private val accelMean = AccelMean()

    /** 0.5.13: whether the start gate has aligned roll and pitch yet, and how many windows it tried. */
    val startAligned: Boolean get() = !settings.startGate || settings.legacyBias || startGate.passed
    val startWindowsTried: Int get() = startGate.windowsTried

    /** [current] with its roll and pitch replaced by a level whose up is [accel] (head axes), its yaw kept. */
    private fun relevel(current: Quat, accel: Vec3): Quat {
        val level = levelFrom(accel)
        val want = Angles.of(current.forward).yawDeg
        val have = Angles.of(level.forward).yawDeg
        // A turn about world up by -d moves the yaw (right positive) by +d.
        return (Quat.axisAngle(Vec3.UP, -wrapDegrees(want - have) * DEG) * level).normalized()
    }

    /** The rate this step turns [q] by: the unbiased gyro plus gravity's correction. */
    private var corrected = Vec3.ZERO

    /** 0.5.10's rules, kept for the before and after tests ([FusionSettings.legacyBias]). */
    private fun legacyStep(gyro: Vec3, accel: Vec3, dt: Float) {
        // Bias: while the head is still, the gyro should read zero.
        val unbiased = gyro - bias
        val still = unbiased.length < settings.stillGyro
        stillFor = if (still) stillFor + dt else 0f
        if (stillFor >= settings.stillSeconds) {
            val k = (settings.biasRate * dt).coerceAtMost(1f)
            bias = bias + (gyro - bias) * k
        }
        var omega = gyro - bias
        val an = accel.length
        if (an > 1e-6f) {
            val error = gravityError(accel, an)
            if (gravityNorm <= 0f) gravityNorm = an
            if (still) gravityNorm += (an - gravityNorm) * (dt * 2f).coerceAtMost(1f)
            val off = abs(an - gravityNorm) / gravityNorm
            val weight = when {
                off > 0.15f -> 0.1f
                unbiased.length > settings.turningGyro -> settings.turningWeight
                stillFor > 0f -> 2f
                else -> 1f
            }
            integral = integral + error * (settings.ki * dt * weight.coerceAtMost(1f))
            omega = omega + error * (settings.kp * weight) + integral
        }
        corrected = omega
    }

    /**
     * 0.5.11, an error state filter: every rotation the gyro measures turns the pose, in full and at once. Nothing here
     * filters, dead zones or eases the rotation. Only two slow corrections touch it:
     *
     * - The gyro bias moves only while the [StaticDetector] says the glasses are truly static (the
     *   peak to peak of each axis over 4 s under 0.02 rad/s, both halves of the window agreeing,
     *   and the accelerometer steady), by at most 0.0005 rad/s a second, and never toward a window
     *   mean more than 0.002 rad/s from the bias (that is a slow turn, not drift). The first static
     *   window sets it outright (start up); after that each static chunk moves it 1/600 of the way.
     * - Gravity pulls roll and pitch at 0.05 rad/s per radian while the head is quiet, 0.005 while it
     *   turns, and not at all while the accelerometer's size is off by more than 1 %.
     *
     * 0.5.14 ([FusionSettings.gravityTiers]): the gravity checks run on means in the room's axes,
     * against a reference from the start gate, in tiers, and the bias also learns while worn
     * ([tieredGravity], [wornYaw]).
     */
    private fun staticStep(gyro: Vec3, accel: Vec3, dt: Float) {
        runS += dt
        val an = accel.length
        val verdict = detector.add(gyro, accel, dt, settings)
        if (verdict != null) {
            val mean = verdict
            // 0.5.14: a bias learned while worn (or seeded) is an estimate; the session's first
            // static window is the measurement, and replaces it outright.
            val replace = !biasLearned || (settings.wornYawBias && !staticLearned)
            staticLearned = true
            staticLearnedAtS = runS
            if (replace) {
                bias = clampBias(mean)
                biasLearned = true
                biasLearnedAtS = runS
                biasEvents++
                noteLearn(first = true)
                // 0.5.15: what worn learning is held to, and what may be saved as the static bias.
                staticBias = bias
                // 0.5.19: the pool against the measurement.
                if (settings.yawPool) poolStaticMeasured()
            } else if ((mean - bias).length <= settings.biasTrust) {
                // A Kalman style gain: each static chunk moves the bias 1/[biasTauS] s of the way.
                val step = (mean - bias) * (detector.chunkS / settings.biasTauS).coerceAtMost(1f)
                val maxStep = settings.biasStepPerS * detector.chunkS
                bias = clampBias(bias + if (step.length > maxStep) step * (maxStep / step.length) else step)
                biasEvents++
                noteLearn(first = false)
                staticBias = bias
            }
        }
        stillFor = if (detector.static) stillFor + dt else 0f
        val unbiased = gyro - bias
        // 0.5.18: the bias reaches the integration as a ramp ([FusionSettings.biasRampS]).
        // 0.5.55: this and the rate's mean below as one Vec3 each ([HeadMath.toward]), not three.
        if (rampsBias()) appliedBias = HeadMath.toward(appliedBias, bias, (dt / settings.biasRampS).coerceAtMost(1f))
        var omega = if (rampsBias()) gyro - appliedBias else unbiased
        // 0.5.18: the rate's short mean, for the quiet test of gravity's pull ([FusionSettings.smoothWeights]).
        if (settings.smoothWeights) rateMean = HeadMath.toward(rateMean, unbiased, dt / (settings.quietMeanS + dt))
        // 0.5.13: gravity from the accelerometer's short mean, while the raw value
        // stays near it (a changing accelerometer is a head accelerating, not gravity).
        val meanS = settings.accelMeanS
        if (settings.gravityTiers) {
            corrected = omega + tieredGravity(accel, unbiased, dt)
            wornYaw(gyro, dt)
            checkOldBias()
            return
        }
        val g = if (meanS > 0f) accelMean.add(accel, dt, meanS) else accel
        val gn = g.length
        val changing = meanS > 0f && an > 1e-6f && HeadMath.distance(accel, g) > settings.gravityGate * an
        if (an > 1e-6f && gn > 1e-6f) {
            if (gravityNorm <= 0f) gravityNorm = if (settings.gravityRefFromGate) StartGate.GRAVITY else an
            if (detector.static) gravityNorm += (an - gravityNorm) * (dt * 0.5f).coerceAtMost(1f)
            val off = abs(an - gravityNorm) / gravityNorm
            if (off <= settings.gravityGate && !changing) {
                // The first seconds level the start pose quickly (it came from one accelerometer sample).
                // and again for a moment once the bias is first known: the tilt the unknown bias
                // left would otherwise fade over 20 s, which a turning head sees as the room moving.
                // 0.5.13: with the start gate, the first seconds count from its alignment.
                val settling = runS - settleFromS < settings.settleS || (biasLearnedAtS >= 0f && runS - biasLearnedAtS < settings.settleS)
                val kp = when {
                    settling -> settings.settleKp
                    detector.static -> settings.kpStatic
                    unbiased.length < settings.quietGyro -> settings.kp
                    else -> settings.kpMoving
                }
                val error = gravityError(g, gn)
                integral = integral + error * (settings.ki * dt)
                omega = omega + error * kp + integral
            } else {
                gravitySkipped++
            }
        }
        corrected = omega
    }

    /** 0.5.14: the [FusionSettings.accelShortS] mean, for the change check on averaged values. */
    private val accelShort = AccelMean()

    /** 0.5.14: samples by gravity tier since the last [gravityShareLine]: full, wide, skipped. */
    private val tierCounts = LongArray(4)

    /**
     * 0.5.14: gravity's pull, in tiers, and the worn bias from its tilt error. Returns the
     * correction to add to the unbiased rate. The accelerometer is averaged in the room's axes
     * (turned by the current pose), so a head that turns does not blur the mean or trip the change
     * check; only the head's own acceleration does.
     */
    private fun tieredGravity(accel: Vec3, unbiased: Vec3, dt: Float): Vec3 {
        val s = settings
        if (accel.length < 1e-6f) return Vec3.ZERO
        // 0.5.55: the turns and differences here in one Vec3 each ([HeadMath]; a Quat.rotate made seven).
        val world = HeadMath.rotate(q, accel)
        val gw = if (s.accelMeanS > 0f) accelMean.add(world, dt, s.accelMeanS) else world
        val shortW = if (s.accelShortS > 0f) accelShort.add(world, dt, s.accelShortS) else world
        val gn = gw.length
        if (gn < 1e-6f) return Vec3.ZERO
        lastGravityWorld = gw
        // 0.5.15: nothing from gravity until the start gate has passed (0.5.14 pulled toward 9.81 from the first sample).
        if (s.gravityAfterGate && !startAligned) {
            gravityHeld++
            tierCounts[3]++
            fullForS = 0f
            slideKp(0f, dt)
            return Vec3.ZERO
        }
        // Back in the head's axes for the tilt error.
        val g = HeadMath.rotateByConjugate(q, gw)
        if (gravityNorm <= 0f) gravityNorm = if (s.gravityRefFromGate) StartGate.GRAVITY else gn
        val ref = gravityNorm
        val off = abs(gn - ref) / ref
        val change = HeadMath.distance(shortW, gw) / ref
        lastGravityOff = off
        lastGravityChange = change
        if (off > s.gravityWide || change > s.gravityWide) {
            gravitySkipped++
            tierCounts[2]++
            fullForS = 0f
            slideKp(0f, dt)
            return Vec3.ZERO
        }
        val full = off <= s.gravityFull && change <= s.gravityChangeFull
        tierCounts[if (full) 0 else 1]++
        fullForS = if (full) fullForS + dt else 0f
        val settling = settlingNow()
        // 0.5.15: the settle's fast pull only while the head is quiet, and its time counts only then.
        val settleQuiet = !s.settleOnQuiet || unbiased.length < s.quietGyro
        if (settling && full && settleQuiet) settledS += dt
        // While the head moves during the settle, the pull is the usual one for the moment.
        val settleKpNow = settling && settleQuiet
        // 0.5.18: quiet on the rate's short mean, not on one 1 kHz sample ([FusionSettings.smoothWeights]).
        val quietRate = if (s.smoothWeights) rateMean.length else unbiased.length
        var kp = when {
            settleKpNow -> s.settleKp
            detector.static -> s.kpStatic
            quietRate < s.quietGyro -> s.kp
            else -> s.kpMoving
        }
        val fast = (!biasLearned || (s.wornFastWhenSeeded && !staticLearned)) && wornLearnS < s.wornFastS && s.kiWorn > 0f
        // 0.5.15: with a bias known (seeded), the fast pull and the learning only while the head is
        // quiet: a turn's centripetal pull is not gravity. With none known, gravity must hold roll
        // against the whole bias while the head moves too, so the fast mode pulls then as in 0.5.14.
        val quiet = unbiased.length < s.wornLearnQuietGyro
        val unknown = !biasLearned
        if (full && fast && !(if (s.settleOnQuiet) settleKpNow else settling) && !detector.static && (!s.wornBiasDamped || quiet || unknown)) kp = maxOf(kp, s.kpWornFast)
        if (!full) kp = minOf(kp, s.kpMoving)
        val error = gravityError(g, gn)
        if (full) {
            // The reference follows the mean slowly, and at once while static (as 0.5.13).
            val tau = if (detector.static) 2f else s.gravityRefTauS
            gravityNorm += (gn - gravityNorm) * (dt / tau).coerceAtMost(1f)
            // Worn: the tilt error the bias leaves feeds the bias (roll and pitch).
            if (!settling && !detector.static && s.kiWorn > 0f && !staticFresh()) {
                if (!s.wornBiasDamped) {
                    bias = clampBias(bias - error * ((if (fast) s.kiWornFast else s.kiWorn) * dt))
                    wornLearnS += dt
                } else if ((quiet || (fast && unknown)) && fullForS >= s.wornLearnHoldS && headUp().y >= s.wornLevelUp) {
                    // Critically damped for the pull in use: ki <= kp^2 / 4.
                    val ki = minOf(if (fast) s.kiWornFast else s.kiWorn, kp * kp / 4f)
                    // Never along the head's yaw axis: that part is the still yaw windows' alone.
                    val step = Vec3(error.x, 0f, error.z) * (-ki * dt)
                    val most = s.wornStepPerS * dt
                    bias = clampWorn(bias + Vec3(step.x.coerceIn(-most, most), 0f, step.z.coerceIn(-most, most)))
                    wornLearnS += dt
                }
            }
        }
        if (!s.smoothWeights && s.restCorrection <= 0f) return error * kp
        // 0.5.18: the pull slides to its tier's value, and while the head is still it turns the view
        // only in a slow ramp ([FusionSettings.restCorrection]). Not while gravity must level fast:
        // the settle after the start gate, the worn fast start, or with no bias known at all (gravity
        // then holds roll against the whole bias).
        val exempt = settling || fast || !biasLearned
        if (exempt || !s.smoothWeights) kpApplied = kp else slideKp(kp, dt)
        var pull = error * kpApplied
        if (s.restCorrection > 0f && !exempt) {
            val most = s.restCorrection + s.correctionPerRate * (if (s.smoothWeights) rateMean.length else unbiased.length)
            val size = pull.length
            if (size > most) {
                pull = pull * (most / size)
                correctionsLimited++
            }
        }
        return pull
    }

    /** 0.5.18: the gravity pull in use, sliding toward its tier's value ([FusionSettings.smoothWeights]). */
    private var kpApplied = 0f

    /** 0.5.18: the unbiased rate's [FusionSettings.quietMeanS] mean. */
    private var rateMean = Vec3.ZERO

    /** 0.5.18: the bias the integration uses, ramping toward [bias] ([FusionSettings.biasRampS]). */
    private var appliedBias = Vec3.ZERO

    private fun rampsBias(): Boolean = settings.biasRampS > 0f && !settings.legacyBias

    /** 0.5.18: samples whose gravity pull was held to the still head's slow ramp, for the tests and the log. */
    var correctionsLimited = 0L
        private set

    private fun slideKp(target: Float, dt: Float) {
        if (!settings.smoothWeights) {
            kpApplied = target
            return
        }
        val tau = if (target < kpApplied) settings.kpFallS else settings.kpRiseS
        kpApplied += (target - kpApplied) * (dt / (tau + dt))
    }

    /** 0.5.15: seconds the full gravity tier has held without a break. */
    private var fullForS = 0f

    /** 0.5.15: quiet full tier seconds of settle since the start gate aligned ([FusionSettings.settleOnQuiet]). */
    private var settledS = 0f

    /**
     * The fast level after the start gate's alignment, and for a moment once the bias is first
     * known. 0.5.15: the first is counted in quiet full tier seconds, at most [FusionSettings.settleMaxS]:
     * 0.5.14 spent its 2 s while Mike was still putting the glasses on, gravity gated out.
     */
    private fun settlingNow(): Boolean {
        val s = settings
        val start = if (s.settleOnQuiet) settledS < s.settleS && runS - settleFromS < s.settleMaxS else runS - settleFromS < s.settleS
        return start || (biasLearnedAtS >= 0f && runS - biasLearnedAtS < s.settleS)
    }

    /** 0.5.15: the newest averaged gravity in the room's axes, for the recentre's relevel, the level calibration and the attitude line. */
    private var lastGravityWorld: Vec3? = null

    /** 0.5.15: samples whose gravity waited for the start gate. */
    var gravityHeld = 0L
        private set

    /**
     * 0.5.15: the last static measurement of the bias (seeded or learned this session); a bias
     * learned while worn stays within [FusionSettings.wornAnchorMax] of it per axis.
     */
    var staticBias: Vec3? = null
        private set

    private fun clampWorn(b: Vec3): Vec3 {
        val a = staticBias
        if (!settings.wornBiasDamped || a == null) return clampBias(b)
        val m = settings.wornAnchorMax
        return clampBias(Vec3(b.x.coerceIn(a.x - m, a.x + m), b.y.coerceIn(a.y - m, a.y + m), b.z.coerceIn(a.z - m, a.z + m)))
    }

    /**
     * 0.5.14: the yaw part of the bias while worn ([FusionSettings.wornYawBias]): the gyro's mean
     * along the head's up over a window, against the bias.
     */
    private fun wornYaw(gyro: Vec3, dt: Float) {
        val s = settings
        if (!s.wornYawBias) return
        // 0.5.15: a "static" verdict that learned nothing (its halves disagreed: a worn head's sway)
        // no longer stops the still windows; one that learned stops them for a while ([staticFresh]).
        if ((detector.static && !s.wornYawStillOnly) || settlingNow() || staticFresh() || (s.gravityAfterGate && !startAligned)) {
            wornSum = Vec3.ZERO
            wornUp = Vec3.ZERO
            wornS = 0f
            chunkSum = Vec3.ZERO
            chunkS = 0f
            chunks = 0
            stillChunks = 0
            prevYawMean = null
            chunkRawSum = Vec3.ZERO
            restAlongSum = 0.0
            restS = 0.0
            yawAgreeRing.clear()
            clearPool()
            return
        }
        // 0.5.55: kept from the gravity error of this sample ([headUp]); the sums below in one Vec3 each ([HeadMath]).
        val upNow = headUp()
        // 0.5.19: only while worn upright; glasses on their side or upside down
        // (off the head) break the window and the pool's run, as a static spell does.
        if (s.yawUprightOnly && upNow.y < kotlin.math.cos(s.uprightDeg * DEG)) {
            if (!notUpright) {
                notUpright = true
                lastYawWindow = "not learned: the glasses are not upright (up %.0f deg from the room's), so not worn".format(java.util.Locale.ROOT, kotlin.math.acos(upNow.y.coerceIn(-1f, 1f)) * RAD)
                lastPool = lastYawWindow
            }
            wornSum = Vec3.ZERO
            wornUp = Vec3.ZERO
            wornS = 0f
            chunkSum = Vec3.ZERO
            chunkS = 0f
            chunks = 0
            stillChunks = 0
            prevYawMean = null
            chunkRawSum = Vec3.ZERO
            restAlongSum = 0.0
            restS = 0.0
            poolRunAlong = 0.0
            poolRunDur = 0.0
            poolRunVec = DoubleArray(3)
            pool.clear()
            return
        }
        if (notUpright) {
            // 0.5.21 ("not upright" stayed the yaw line's reason for minutes after
            // the glasses were worn again): the reason says so as soon as they are upright again.
            notUpright = false
            lastYawWindow = "upright again (worn): a new still yaw window is being gathered"
            lastPool = lastYawWindow
        }
        wornSum = HeadMath.addScaled(wornSum, gyro, dt)
        wornUp = HeadMath.addScaled(wornUp, upNow, dt)
        wornS += dt
        // 0.5.15: how still the head was, in 0.1 s means of the unbiased rate. 0.5.16: on the yaw
        // axis only (the rate along world up), which is all a yaw window needs held still.
        chunkSum = HeadMath.addDiffScaled(chunkSum, gyro, bias, dt)
        chunkRawSum = HeadMath.addScaled(chunkRawSum, gyro, dt)
        chunkS += dt
        if (chunkS >= 0.1f) {
            chunks++
            val m = chunkSum * (1f / chunkS)
            val rested = if (s.wornYawWorn) abs(m dot upNow) < s.wornYawAxisGyro else m.length < s.wornYawStillGyro
            if (rested) {
                stillChunks++
                // 0.5.17: the raw gyro along up over this rested segment (bias plus what is left of
                // the head's turn while it rests), for the window's rested estimate.
                restAlongSum += (chunkRawSum dot upNow).toDouble()
                restS += chunkS.toDouble()
            }
            // 0.5.18: and its rested runs, pooled across windows ([FusionSettings.yawPool]). The pool
            // judges rest around its own last estimate, not the bias: a window around the bias keeps
            // the chunks whose head motion cancels a slow turn, so a steady 0.2 deg/s chair turn read
            // as 0.0024 rad/s along up (under the grown guard's 0.003) instead of its 0.0035.
            if (s.yawPool) {
                val along = chunkRawSum dot upNow
                val centre = poolCentre ?: (bias dot upNow)
                // 0.5.19: a stricter rest for the pool ([FusionSettings.poolRestGyro]).
                poolChunk(abs(along / chunkS - centre) < s.poolRestGyro, along, chunkS, upNow, s)
            }
            chunkSum = Vec3.ZERO
            chunkRawSum = Vec3.ZERO
            chunkS = 0f
        }
        if (s.wornYawWorn) {
            wornYawWindow(s)
            return
        }
        if (wornS < s.wornWindowS) return
        val up = wornUp * (1f / wornS)
        val mean = wornSum * (1f / wornS)
        val stillShare = if (chunks > 0) stillChunks.toFloat() / chunks else 0f
        wornSum = Vec3.ZERO
        wornUp = Vec3.ZERO
        val window = wornS
        wornS = 0f
        chunks = 0
        stillChunks = 0
        restAlongSum = 0.0
        restS = 0.0
        // Only a window spent mostly level says much about yaw.
        if (up.length < 0.8f) return
        val u = up.normalized()
        val d = (mean - bias) dot u
        wornWindows++
        // 0.5.15: a head that moved can turn slowly on net without anything showing it; only a still one counts.
        if (s.wornYawStillOnly && stillShare < s.wornYawStillShare) {
            wornRefused++
            return
        }
        // Judged on the window's own mean, not its distance from the bias: a gate around the bias
        // refuses the windows that carry most of an unknown bias, and the learning stalls short of it.
        if (abs(mean dot u) > s.wornTrust) {
            wornRefused++
            return
        }
        val tau = if (!biasLearned && wornYawUpdates < s.wornFastWindows) s.wornFastTauS else s.wornTauS
        bias = clampWorn(bias + u * (d * (window / tau).coerceAtMost(1f)))
        wornYawUpdates++
    }

    /**
     * 0.5.16: one [FusionSettings.wornYawWornWindowS] window ([FusionSettings.wornYawWorn]): still
     * enough on the yaw axis, under [FusionSettings.wornTrust] on net, and agreeing with the window
     * before it. The verdict is kept for the yaw line.
     */
    private fun wornYawWindow(s: FusionSettings) {
        if (wornS < s.wornYawWornWindowS) return
        val up = wornUp * (1f / wornS)
        val mean = wornSum * (1f / wornS)
        val stillShare = if (chunks > 0) stillChunks.toFloat() / chunks else 0f
        val window = wornS
        val rested = if (restS > 0.0) (restAlongSum / restS).toFloat() else null
        wornSum = Vec3.ZERO
        wornUp = Vec3.ZERO
        wornS = 0f
        chunks = 0
        stillChunks = 0
        restAlongSum = 0.0
        restS = 0.0
        if (up.length < 0.8f) {
            lastYawWindow = "not level enough"
            prevYawMean = null
            return
        }
        val u = up.normalized()
        // The gyro's own mean along up (bias and net turn together), and against the bias now.
        // 0.5.17: over the window's rested 0.1 s segments only, so its glances add nothing.
        val along = if (s.wornYawRested && rested != null) rested else mean dot u
        wornWindows++
        yawWindowShares.addLast(stillShare)
        while (yawWindowShares.size > 200) yawWindowShares.removeFirst()
        val prev = prevYawMean
        val what = if (s.wornYawRested) "rested mean" else "mean"
        val verdict = when {
            stillShare < s.wornYawAxisShare -> "the head turned: %.0f %% of 0.1 s still on yaw (needs %.0f %%)".format(java.util.Locale.ROOT, stillShare * 100, s.wornYawAxisShare * 100)
            abs(along) > s.wornTrust -> "net turn: $what %.4f rad/s along up (gate %.3f)".format(java.util.Locale.ROOT, along, s.wornTrust)
            prev == null -> "first of a pair ($what %.4f rad/s along up, %.0f %% rested)".format(java.util.Locale.ROOT, along, stillShare * 100)
            abs(along - prev) > s.wornYawAgree -> "disagrees with the window before: %.4f against %.4f rad/s (needs %.3f)".format(java.util.Locale.ROOT, along, prev, s.wornYawAgree)
            else -> null
        }
        // A window that was still enough and turned little starts or continues a pair.
        val candidate = stillShare >= s.wornYawAxisShare && abs(along) <= s.wornTrust
        prevYawMean = if (candidate) along else null
        if (candidate) checkYawGuard(s, along, u)
        if (verdict != null) {
            wornRefused++
            lastYawWindow = "refused, $verdict"
            return
        }
        val target = (along + prev!!) / 2f
        val (d, tau) = moveYawBias(target, u, window, s)
        lastYawWindow = "used ($what %.4f, the pair %.4f rad/s along up; bias moved %.5f, tau %.0f s)".format(java.util.Locale.ROOT, along, target, d * (window / tau).coerceAtMost(1f), tau)
    }

    /**
     * Moves the bias's part along [u] toward [target] by [windowS] / tau of the way, under the yaw
     * guard of a known static bias; returns how far it was from the target and the tau used.
     */
    private fun moveYawBias(target: Float, u: Vec3, windowS: Float, s: FusionSettings): Pair<Float, Float> {
        val window = windowS
        val d = target - (bias dot u)
        val fast = !biasLearned && wornYawLearnedS < s.wornYawFastS
        val tau = if (fast || yawGuardGrown) s.wornFastTauS else s.wornTauS
        var next = clampWorn(bias + u * (d * (window / tau).coerceAtMost(1f)))
        // With a static measurement known, its yaw part is trusted over any worn window: they may move
        // it only [FusionSettings.wornYawAnchorMax] along up (a slow steady turn agrees with itself).
        // 0.5.17: [FusionSettings.wornYawAnchorGrown] once rested windows agreed ([checkYawGuard]); and
        // the band also holds a worn bias seeded from the last session, so its correction is not undone.
        staticBias?.let { a ->
            val off = (next - a) dot u
            val m = yawGuard()
            val seedOff = seededWorn?.takeIf { s.wornYawGuardGrows }?.let { (it - a) dot u } ?: 0f
            val lo = minOf(0f, seedOff) - m
            val hi = maxOf(0f, seedOff) + m
            if (off > hi) next = clampBias(next - u * (off - hi)) else if (off < lo) next = clampBias(next - u * (off - lo))
        }
        bias = next
        wornYawUpdates++
        wornYawLearnedS += window
        return d to tau
    }

    // ---- 0.5.18: rested moments pooled across windows ----

    private var poolRunAlong = 0.0
    private var poolRunDur = 0.0
    private val pool = ArrayDeque<DoubleArray>()
    private var lastPoolAtS = -1f

    /** 0.5.18: the long run mean of the rested rate along up: its weighted sum and rested seconds. */
    private var longAlong = 0.0
    private var longRested = 0.0

    /** 0.5.18: the last long run means, for the guard's agreement check. */
    private val poolRing = ArrayDeque<Float>()

    /** 0.5.18: pooled batches taken and used, and the last one's verdict, for the yaw line and the tests. */
    var poolEstimates = 0
        private set
    var poolUsed = 0
        private set
    private var lastPool: String? = null

    /** 0.5.18: rested seconds now in the long run mean. */
    val pooledRestS: Double get() = longRested

    /** One 0.1 s chunk for the pool ([FusionSettings.yawPool]): [along] is its raw gyro along up times its length. */
    private fun poolChunk(rested: Boolean, along: Float, dur: Float, up: Vec3, s: FusionSettings) {
        if (rested) {
            poolRunAlong += along
            poolRunDur += dur
            // 0.5.19: the same in the glasses' own axes ([FusionSettings.poolVector]).
            poolRunVec[0] += (along * up.x).toDouble()
            poolRunVec[1] += (along * up.y).toDouble()
            poolRunVec[2] += (along * up.z).toDouble()
            // A long rest gives a batch every [FusionSettings.poolNeedS] s, not only at its end.
            if (poolRunDur < s.poolNeedS) return
        }
        if (poolRunDur >= s.poolRunS) pool.addLast(doubleArrayOf(runS.toDouble(), poolRunAlong, poolRunDur, poolRunVec[0], poolRunVec[1], poolRunVec[2]))
        poolRunAlong = 0.0
        poolRunDur = 0.0
        poolRunVec = DoubleArray(3)
        while (pool.isNotEmpty() && pool.first()[0] < runS - s.poolSpanS) pool.removeFirst()
        val total = pool.sumOf { it[2] }
        if (total < s.poolNeedS) return
        val sum = pool.sumOf { it[1] }
        val vec = Vec3(pool.sumOf { it[3] }.toFloat(), pool.sumOf { it[4] }.toFloat(), pool.sumOf { it[5] }.toFloat())
        pool.clear()
        poolBatch(sum, vec, total, up, s)
    }

    /**
     * A batch of [restedS] rested seconds whose raw gyro along up summed to [sum] ([vec]: the same
     * in the glasses' axes) joins the long run mean.
     */
    private fun poolBatch(sum: Double, vec: Vec3, restedS: Double, up: Vec3, s: FusionSettings) {
        poolEstimates++
        if (up.length < 0.8f) {
            lastPool = "not level enough"
            return
        }
        val u = up.normalized()
        val batch = (sum / restedS).toFloat()
        // A batch that turned on net (a glance it caught) is left out.
        if (abs(batch) > s.wornTrust) {
            lastPool = "batch left out, net turn %.4f rad/s along up over %.1f s rested".format(java.util.Locale.ROOT, batch, restedS)
            return
        }
        // 0.5.19: up turned in the glasses' axes since the pool began: it starts over in this orientation.
        val ref = poolUp
        if (ref != null && (ref dot u).coerceIn(-1f, 1f) < kotlin.math.cos(s.poolClearDeg * DEG)) {
            lastPool = "up turned %.0f deg in the glasses' axes since the pool began: it starts over".format(java.util.Locale.ROOT, kotlin.math.acos((ref dot u).coerceIn(-1f, 1f)) * RAD)
            clearLong()
        }
        if (poolUp == null) poolUp = u
        val since = if (lastPoolAtS < 0f) 0f else runS - lastPoolAtS
        val decay = kotlin.math.exp(-since / s.poolLongS).toDouble()
        longAlong = longAlong * decay + sum
        longVec = longVec * decay.toFloat() + vec
        longRested = longRested * decay + restedS
        lastPoolAtS = runS
        val mean = if (s.poolVector) ((longVec dot u) / longRested).toFloat() else (longAlong / longRested).toFloat()
        // The next batch's rest is judged around this mean (a mean shift toward the rested rate).
        poolCentre = mean
        if (longRested < s.poolMinS) {
            lastPool = "%.0f s of %.0f s rested in the long run mean (%.4f rad/s along up)".format(java.util.Locale.ROOT, longRested, s.poolMinS, mean)
            return
        }
        poolRing.addLast(mean)
        while (poolRing.size > 3) poolRing.removeFirst()
        val spread = if (poolRing.size < 3) Float.MAX_VALUE else poolRing.max() - poolRing.min()
        val a = staticBias
        if (a != null && s.wornYawGuardGrows && !yawGuardGrown && longRested >= s.poolGrowS && spread <= s.poolAgree && abs(mean - (a dot u)) <= s.wornYawAnchorGrown) {
            yawGuardGrown = true
            yawGuardLine = String.format(
                java.util.Locale.ROOT,
                "gyro bias: the pooled rested mean over %.0f s of rest holds (%.5f rad/s along up, its last three within %.5f, needs %.4f), %.5f from the static bias: the yaw guard grows from %.4f to %.4f rad/s",
                longRested, mean, spread, s.poolAgree, mean - (a dot u), s.wornYawAnchorMax, s.wornYawAnchorGrown,
            )
        }
        // 0.5.19: near a known static bias until the pool has earned more ([FusionSettings.poolCap]).
        var target = mean
        var capped = ""
        if (a != null && s.poolCap > 0f && !poolCapLifted) {
            if (s.poolCapLiftS > 0f && longRested >= s.poolCapLiftS && spread <= s.poolAgree) {
                poolCapLifted = true
                poolCapLine = "gyro bias: %.0f s of rest pooled upright in one orientation, its last three means within %.5f rad/s: the pool may move the bias past %.4f rad/s of the static measurement now".format(
                    java.util.Locale.ROOT, longRested, spread, s.poolCap,
                )
            } else {
                // The band round the static bias, widened to a worn bias seeded from the last session as
                // the yaw guard's is, so a correction already made is kept.
                val along = a dot u
                val seedOff = seededWorn?.takeIf { s.wornYawGuardGrows }?.let { (it - a) dot u } ?: 0f
                val lo = along + minOf(0f, seedOff) - s.poolCap
                val hi = along + maxOf(0f, seedOff) + s.poolCap
                val held = target.coerceIn(lo, hi)
                if (held != target) {
                    val now = bias dot u
                    // Already as far as the cap lets the pool go (the yaw windows may have moved it): left there.
                    if ((target > hi && now >= hi) || (target < lo && now <= lo)) {
                        poolHeld++
                        lastPool = "held: the mean of %.0f s rested %.4f rad/s along up is past the %.4f rad/s the pool may move from the static bias until a static measurement agrees; the bias stays".format(
                            java.util.Locale.ROOT, longRested, mean, s.poolCap,
                        )
                        return
                    }
                    capped = "; held to %.4f rad/s of the static bias until a static measurement agrees".format(java.util.Locale.ROOT, s.poolCap)
                }
                target = held
            }
        }
        val (d, tau) = moveYawBias(target, u, since.coerceAtLeast(s.poolNeedS), s)
        poolUsed++
        lastPool = "used: the mean of %.0f s rested %.4f rad/s along up (batch %.4f over %.1f s); bias moved %.5f (tau %.0f s)%s".format(
            java.util.Locale.ROOT, longRested, mean, batch, restedS, d * (since.coerceAtLeast(s.poolNeedS) / tau).coerceAtMost(1f), tau, capped,
        )
    }

    private fun clearPool() {
        poolRunAlong = 0.0
        poolRunDur = 0.0
        poolRunVec = DoubleArray(3)
        pool.clear()
        poolRing.clear()
        poolCentre = null
    }

    /** 0.5.19: the long run mean starts over (another orientation, or a static measurement that disagreed with it). */
    private fun clearLong() {
        longAlong = 0.0
        longVec = Vec3.ZERO
        longRested = 0.0
        lastPoolAtS = -1f
        poolRing.clear()
        poolCentre = null
        poolUp = null
    }

    /**
     * 0.5.19: a static measurement was just taken ([staticStep]): a pool that agrees with it (within
     * [FusionSettings.poolAgree] along its up) lifts the cap; one that does not starts over, the
     * measurement being the truth the pool was meant to find.
     */
    private fun poolStaticMeasured() {
        val s = settings
        val up = poolUp ?: return
        if (longRested < s.poolMinS) return clearLong()
        val mean = if (s.poolVector) ((longVec dot up) / longRested).toFloat() else (longAlong / longRested).toFloat()
        val off = mean - (bias dot up)
        if (abs(off) <= s.poolAgree) {
            if (!poolCapLifted && s.poolCap > 0f) {
                poolCapLifted = true
                poolCapLine = "gyro bias: a static measurement agrees with the pooled rested mean (%.5f rad/s apart along up): the pool may move the bias past %.4f rad/s now".format(java.util.Locale.ROOT, off, s.poolCap)
            }
        } else {
            lastPool = "a static measurement %.5f rad/s from the pooled mean along up: the pool starts over".format(java.util.Locale.ROOT, -off)
            clearLong()
        }
    }

    /** 0.5.19: the pool's run in the glasses' axes, its long run sum, and the up it began in. */
    private var poolRunVec = DoubleArray(3)
    private var longVec = Vec3.ZERO
    private var poolUp: Vec3? = null

    /** 0.5.19: the glasses were not upright at the last sample (yaw not learned). */
    private var notUpright = false

    /** 0.5.19: pooled batches that left the bias where it was, the cap reached ([FusionSettings.poolCap]). */
    var poolHeld = 0
        private set

    /** 0.5.19: the pool's cap near the static bias was lifted ([FusionSettings.poolCap]), and the line saying so (once). */
    var poolCapLifted = false
        private set
    var poolCapLine: String? = null
        private set

    /** 0.5.18: the rate along up the pool judges rest around: its long run mean (null: the bias). */
    private var poolCentre: Float? = null

    /** 0.5.17: the guard on the yaw part of a known static bias now, rad/s along up. */
    private fun yawGuard(): Float = if (yawGuardGrown) settings.wornYawAnchorGrown else settings.wornYawAnchorMax

    /**
     * 0.5.17: one more rested window that was still enough and turned little; grows the guard once
     * the last [FusionSettings.wornYawAgreeCount] such windows agree with each other and sit within
     * [FusionSettings.wornYawAnchorGrown] of the static bias along up.
     */
    private fun checkYawGuard(s: FusionSettings, along: Float, u: Vec3) {
        if (!s.wornYawGuardGrows || yawGuardGrown) return
        yawAgreeRing.addLast(along)
        while (yawAgreeRing.size > s.wornYawAgreeCount) yawAgreeRing.removeFirst()
        val a = staticBias ?: return
        if (yawAgreeRing.size < s.wornYawAgreeCount) return
        val mean = yawAgreeRing.sum() / yawAgreeRing.size
        val spread = yawAgreeRing.maxOf { abs(it - mean) }
        val off = mean - (a dot u)
        if (spread > s.wornYawAgreeWithin || abs(off) > s.wornYawAnchorGrown) return
        yawGuardGrown = true
        yawGuardLine = String.format(
            java.util.Locale.ROOT,
            "gyro bias: %d rested yaw windows agree (mean %.5f rad/s along up, each within %.5f of it, needs %.4f), %.5f from the static bias: the yaw guard grows from %.4f to %.4f rad/s",
            yawAgreeRing.size, mean, spread, s.wornYawAgreeWithin, off, s.wornYawAnchorMax, s.wornYawAnchorGrown,
        )
    }

    /** 0.5.16: the last still enough yaw window's mean along up, for the pair rule; null after a break. */
    private var prevYawMean: Float? = null
    private var wornYawLearnedS = 0f
    private var lastYawWindow: String? = null

    /** 0.5.17: the rested segments of the window so far: the raw gyro along up summed over them, and their time. */
    private var chunkRawSum = Vec3.ZERO
    private var restAlongSum = 0.0
    private var restS = 0.0

    /** 0.5.17: the last rested windows' estimates for the guard ([checkYawGuard]), and whether it grew. */
    private val yawAgreeRing = ArrayDeque<Float>()
    var yawGuardGrown = false
        private set

    /** 0.5.17: set once, when the guard grew: the log line. */
    var yawGuardLine: String? = null
        private set

    /** 0.5.17: the still share of each yaw window judged (the last 200), for the tests and the yaw line. */
    val yawWindowShares = ArrayDeque<Float>()

    /** 0.5.17: a worn bias seeded from the last session (near the static one), which the yaw guard's band holds. */
    private var seededWorn: Vec3? = null

    // ---- 0.5.16: the yaw line, measured every 10 s ----

    private var driftChunkSum = 0f
    private var driftChunkS = 0f
    private var driftStillSum = 0.0
    private var driftStillS = 0.0
    private var driftAllSum = 0.0
    private var driftAllS = 0.0

    /**
     * The rate along world up with the bias removed, in 0.1 s means: those under
     * [FusionSettings.wornYawAxisGyro] are a head held still, whose true yaw rate is about 0, so
     * their mean is the yaw drift left. Only after the start gate.
     */
    private fun yawDriftStep(dt: Float) {
        if (!startAligned) return
        // 0.5.55: one Vec3 ([HeadMath.rotate]), not seven.
        val yawRate = HeadMath.rotate(q, lastOmega) dot Vec3.UP
        driftChunkSum += yawRate * dt
        driftChunkS += dt
        if (driftChunkS < 0.1f) return
        val m = driftChunkSum / driftChunkS
        driftAllSum += driftChunkSum
        driftAllS += driftChunkS
        if (abs(m) < settings.wornYawAxisGyro) {
            driftStillSum += driftChunkSum
            driftStillS += driftChunkS
        }
        driftChunkSum = 0f
        driftChunkS = 0f
    }

    /**
     * 0.5.22 ("not upright" was the yaw line's reason for 50 s after the glasses were
     * back on, pitch -19.7 and roll 1.9, because the reason changed only when a yaw window ran, and the
     * still and settling spells after putting them on skip the windows): at each line the reason is made
     * from the up vector now. Upright again: the "not upright" reason goes at once; still not upright: the
     * angle is today's, not the one at the moment they were taken off.
     */
    private fun currentUpright(upImu: Vec3) {
        val s = settings
        if (!s.yawUprightOnly) return
        val upright = upImu.y >= kotlin.math.cos(s.uprightDeg * DEG)
        if (upright && notUpright) {
            notUpright = false
            lastYawWindow = "upright again (worn): a new still yaw window is being gathered"
            lastPool = lastYawWindow
        } else if (!upright && notUpright) {
            lastYawWindow = "not learned: the glasses are not upright (up %.0f deg from the room's), so not worn".format(java.util.Locale.ROOT, kotlin.math.acos(upImu.y.coerceIn(-1f, 1f)) * RAD)
            lastPool = lastYawWindow
        }
    }

    /**
     * 0.5.16: the yaw bias (the bias along world up now, and its y part), where it came from, the
     * yaw drift measured in the still 0.1 s means since the last line, the last yaw window and the
     * old bias's check. The measurements start over.
     */
    fun yawLine(): String {
        val upImu = q.conjugate().rotate(Vec3.UP)
        val alongUp = bias dot upImu
        currentUpright(upImu)
        val source = when {
            staticLearned -> "measured static this session"
            oldBiasTaken -> "the old saved bias, confirmed by this session's x and z"
            biasLearned && wornYawUpdates == 0 -> "seeded from the last session"
            wornYawUpdates > 0 -> "learned worn"
            else -> "not known"
        }
        // The net yaw rate over the line's 10 s: the drift plus the head's net turn, which comes back
        // over a few lines (the median of these over a session is the drift). The still spells' mean
        // reads the drift alone while the head truly rests, and toward 0 while it sways.
        val net = if (driftAllS >= 0.5) "%+.3f deg/s net over %.1f s".format(java.util.Locale.ROOT, driftAllSum / driftAllS * RAD, driftAllS) else "not measured yet"
        // Only a line the head mostly rested through: a swaying head's still spells are the sway's turning points.
        val still = if (driftStillS >= 0.5 && driftStillS >= 0.8 * driftAllS) {
            "%+.3f deg/s while the head rested (%.1f s)".format(java.util.Locale.ROOT, driftStillSum / driftStillS * RAD, driftStillS)
        } else {
            "the head rested %.1f s of %.1f".format(java.util.Locale.ROOT, driftStillS, driftAllS)
        }
        val drift = "yaw drift $net, $still"
        driftStillSum = 0.0; driftStillS = 0.0; driftAllSum = 0.0; driftAllS = 0.0
        // 0.5.17: the guard on a known static bias's yaw part, and the windows' still shares.
        val guard = when {
            staticBias == null -> ""
            yawGuardGrown -> "; yaw guard %.4f rad/s (grown: rested windows agreed), %.5f from the static bias".format(java.util.Locale.ROOT, settings.wornYawAnchorGrown, alongUp - (staticBias!! dot upImu))
            else -> "; yaw guard %.4f rad/s, %.5f from the static bias".format(java.util.Locale.ROOT, settings.wornYawAnchorMax, alongUp - (staticBias!! dot upImu))
        }
        return String.format(
            java.util.Locale.ROOT,
            "head yaw: bias along up %.5f rad/s (%.3f deg/s), y %.5f, %s; %s; yaw windows %d used of %d%s%s%s%s",
            alongUp, alongUp * RAD, bias.y, source, drift, wornYawUpdates - poolUsed, wornWindows,
            lastYawWindow?.let { ", last $it" } ?: "",
            // 0.5.18: the pooled rested moments ([FusionSettings.yawPool]).
            if (settings.yawPool) "; rested moments pooled: %d estimates, %d used, %d held at the cap%s".format(java.util.Locale.ROOT, poolEstimates, poolUsed, poolHeld, lastPool?.let { ", last $it" } ?: "") else "",
            guard,
            oldBiasLine(),
        )
    }

    // ---- 0.5.16: the old saved bias, confirmed by this session ----

    private var oldBias: Vec3? = null
    private var oldBiasTaken = false

    /** Set once [oldBias] was taken: the static bias to save for the next session, and its log line. */
    var oldBiasConfirmedLine: String? = null
        private set

    /**
     * An older saved bias (0.5.14's key), which may be a worn one that walked: it is not used until
     * the x and z learned worn this session agree with it ([FusionSettings.oldBiasConfirm]).
     */
    fun offerOldBias(b: Vec3): Boolean {
        // 0.5.17 ("the old bias path is unreachable once a static bias is
        // saved"): by design. A saved static bias is a measurement (on a table, or this very key
        // confirmed in an earlier session, as Mike's: its y -0.00373 is the key's -0.00372), so the
        // key has nothing to add; offering it again would confirm the same y that was 0.0018 rad/s off
        // on the worn, warm glasses. That error is the rested yaw windows' to fix (the guard that
        // grows, [FusionSettings.wornYawGuardGrows]). The caller logs why the key was not taken.
        if (biasLearned || staticBias != null) return false
        oldBias = clampBias(b)
        return true
    }

    private fun checkOldBias() {
        val o = oldBias ?: return
        val s = settings
        if (oldBiasTaken || biasLearned || staticBias != null || wornLearnS < s.oldBiasLearnS) return
        if (abs(bias.x - o.x) > s.oldBiasConfirm || abs(bias.z - o.z) > s.oldBiasConfirm) return
        // Its yaw part is taken; x and z stay as learned (they are this session's own).
        bias = clampBias(Vec3(bias.x, o.y, bias.z))
        staticBias = o
        biasLearned = true
        oldBiasTaken = true
        prevYawMean = null
        oldBiasConfirmedLine = String.format(
            java.util.Locale.ROOT,
            "gyro bias: the old saved bias %.5f, %.5f, %.5f rad/s confirmed after %.0f s of worn learning (x %.5f, z %.5f learned, within %.4f); its y taken as the yaw bias and the whole as the static bias",
            o.x, o.y, o.z, wornLearnS, bias.x, bias.z, s.oldBiasConfirm,
        )
    }

    private fun oldBiasLine(): String {
        val o = oldBias ?: return ""
        if (oldBiasTaken) return "; old saved bias taken"
        return String.format(
            java.util.Locale.ROOT,
            "; old saved bias %.5f, %.5f, %.5f not yet confirmed (x off %.5f, z off %.5f, needs %.4f after %.0f s of worn learning, %.0f s so far)",
            o.x, o.y, o.z, bias.x - o.x, bias.z - o.z, settings.oldBiasConfirm, settings.oldBiasLearnS, wornLearnS,
        )
    }

    private var chunkSum = Vec3.ZERO
    private var chunkS = 0f
    private var chunks = 0
    private var stillChunks = 0

    /**
     * 0.5.14: a static window measured the bias less than [FusionSettings.wornAfterStaticS] ago: it
     * is better than anything learned on a head, so worn learning waits.
     */
    private fun staticFresh(): Boolean = staticLearnedAtS >= 0f && runS - staticLearnedAtS < settings.wornAfterStaticS
    private var staticLearnedAtS = -1f

    private var wornSum = Vec3.ZERO
    private var wornUp = Vec3.ZERO
    private var wornS = 0f
    private var staticLearned = false

    /** 0.5.14: worn bias learning so far: seconds of gravity integral, yaw windows used and refused. */
    var wornLearnS = 0f
        private set
    var wornYawUpdates = 0
        private set
    var wornWindows = 0
        private set
    var wornRefused = 0
        private set

    /** 0.5.14: the last averaged size and change checks, as a share of the reference. */
    var lastGravityOff = 0f
        private set
    var lastGravityChange = 0f
        private set

    /** 0.5.14: the reference size of gravity, m/s2 (0 until the first sample). */
    val gravityReference: Float get() = gravityNorm

    /**
     * 0.5.14: the share of samples in each gravity tier since the last call, and the worn bias
     * learning, for the 10 s head line; the counts start over.
     */
    fun gravityShareLine(): String {
        // 0.5.15: shares of the samples since the start gate; those before it are counted apart.
        val total = (tierCounts[0] + tierCounts[1] + tierCounts[2]).coerceAtLeast(1)
        val line = String.format(
            java.util.Locale.ROOT,
            "gravity used on %.1f %% of samples (full %.1f %%, reduced %.1f %%, skipped %.1f %%; gates %.0f / %.0f %% on the %.1f s mean)%s, reference %.3f m/s2, last size off %.2f %%, change %.2f %%; worn bias: gravity %.0f s, yaw windows %d used of %d",
            (tierCounts[0] + tierCounts[1]) * 100.0 / total, tierCounts[0] * 100.0 / total, tierCounts[1] * 100.0 / total, tierCounts[2] * 100.0 / total,
            settings.gravityFull * 100, settings.gravityWide * 100, settings.accelMeanS,
            if (tierCounts[3] > 0) "; %d samples waited for the start gate".format(java.util.Locale.ROOT, tierCounts[3]) else "",
            gravityNorm, lastGravityOff * 100, lastGravityChange * 100,
            wornLearnS, wornYawUpdates, wornWindows,
        )
        tierCounts.fill(0)
        return line
    }

    /** 0.5.14: the start gate's result, once it passed or for each window it refused. */
    fun startGateLine(): String = if (!settings.startGate || settings.legacyBias) {
        "start gate: off"
    } else if (startGate.passed) {
        String.format(
            java.util.Locale.ROOT,
            "start gate: passed at sample %d after %d windows%s; roll and pitch aligned from its mean (size %.3f m/s2, the gravity reference from now on; gyro peak to peak %.4f rad/s, accel spread %.2f %% of g)",
            startGate.passedAtSample, startGate.windowsTried,
            if (startGate.passedByFallback) " by the fallback (no still window in %.0f s; the stillest one)".format(java.util.Locale.ROOT, settings.startFallbackS) else "",
            startGate.passedMean.length, startGate.passedGyroPeakToPeak, startGate.passedAccelSpread * 100,
        )
    } else {
        String.format(java.util.Locale.ROOT, "start gate: not passed yet, %d windows tried (%s)", startGate.windowsTried, startGate.lastRefusal ?: "first window not full")
    }

    /** Measured up versus the up the current estimate expects, both in the body frame. */
    private fun gravityError(accel: Vec3, an: Float): Vec3 = HeadMath.scaledCross(accel, 1f / an, headUp())

    /**
     * 0.5.55: q.conjugate().rotate(Vec3.UP), the room's up in the head's axes, kept for the [q] it was made from: the
     * gravity error and the worn yaw each made it every sample (eight objects a time). [q] is never changed in place,
     * only replaced, so the same object is the same pose.
     */
    private var headUpFor: Quat? = null
    private var headUpValue = Vec3.UP

    private fun headUp(): Vec3 {
        val now = q
        if (now !== headUpFor) {
            headUpValue = HeadMath.rotateByConjugate(now, Vec3.UP)
            headUpFor = now
        }
        return headUpValue
    }

    private fun clampBias(b: Vec3): Vec3 {
        val m = settings.maxBias
        return Vec3(b.x.coerceIn(-m, m), b.y.coerceIn(-m, m), b.z.coerceIn(-m, m))
    }

    private val detector = StaticDetector()
    private var runS = 0f
    private var biasLearnedAtS = -1f
    private var biasLearned = false

    /**
     * 0.5.12: each bias learning event since the glasses last moved, for the device
     * check ("bias learned within 10 s on a table"): how long after they were put down, and the
     * gate. [learnDelays] keeps the delay of the first event of each still spell, s.
     */
    private var learnedThisSpell = -1f
    val learnDelays = ArrayList<Float>()
    var lastLearnLine: String? = null
        private set

    private fun noteLearn(first: Boolean) {
        val since = detector.stillForS
        if (detector.stillSinceS != learnedThisSpell) {
            learnedThisSpell = detector.stillSinceS
            learnDelays += since
            lastLearnLine = String.format(
                java.util.Locale.ROOT,
                "still check: bias %s %.1f s after the glasses went still (gyro peak to peak %.4f rad/s, gate %.4f%s, accel spread %.2f %%); bias %.5f, %.5f, %.5f rad/s",
                if (first) "learned" else "updated", since, detector.lastPeakToPeak, detector.gate,
                if (detector.gate > settings.staticPeakToPeak) " measured" else "", detector.lastAccelSpread * 100, bias.x, bias.y, bias.z,
            )
        }
    }

    /** 0.5.12: the detector's view for the once a second line while the glasses lie still. */
    fun stillnessLine(): String = String.format(
        java.util.Locale.ROOT,
        "still check: 4 s gyro peak to peak %.4f rad/s (gate %.4f%s), accel spread %.2f %% (gate %.1f %%), %s, still for %.1f s, bias learned %d times",
        detector.lastPeakToPeak, detector.gate, if (detector.gate > settings.staticPeakToPeak) ", measured from this gyro" else "",
        detector.lastAccelSpread * 100, settings.staticAccel * 100, if (detector.static) "static" else "not static", detector.stillForS, biasEvents,
    )

    /** 0.5.12: true while the glasses have not moved for at least a chunk (accel steady, gyro small). */
    val lyingStill: Boolean get() = detector.stillSinceS >= 0f

    /** 0.5.21: the still check's last 4 s gyro peak to peak, rad/s (for the raw gyro ring, [GyroRing]). */
    val stillPeakToPeak: Float get() = detector.lastPeakToPeak

    /** 0.5.11: bias updates so far and IMU samples whose gravity was skipped, for the stillness line. */
    var biasEvents = 0
        private set
    var gravitySkipped = 0L
        private set

    /** 0.5.11: the static detector's verdict now. */
    val isStatic: Boolean get() = if (settings.legacyBias) stillFor >= settings.stillSeconds else detector.static

    /**
     * 0.5.11: a bias known from an earlier session, so start up does not have to learn it.
     * 0.5.15: [static] is the last bias measured on a table, which worn learning stays near
     * ([FusionSettings.wornAnchorMax]); [b] may be a worn bias saved near it.
     */
    fun seedBias(b: Vec3, static: Vec3? = b) {
        bias = clampBias(b)
        // 0.5.18: a seed is where the integration starts, not a change to ramp to.
        appliedBias = bias
        biasLearned = true
        staticBias = static?.let { clampBias(it) }
        seededWorn = if (static != null && static != b) bias else null
    }

    val gyroBias: Vec3 get() = bias

    /** 0.5.15: whether the static detector measured the bias in this session (it is then worth saving as the static one). */
    val staticThisSession: Boolean get() = staticLearned

    /**
     * Makes the current heading the new forward. 0.5.15: roll and pitch are
     * also relevelled from the averaged gravity when it is steady (the full tier) and the head turns
     * slower than [FusionSettings.recentreGyro]; 0.5.14 reset yaw only. 1.2.13: a head tilted more than
     * [FusionSettings.recentreTiltMinDeg] up or down makes its pitch straight ahead too, never its roll
     * ([FusionSettings.recentreFollowsTilt]). The gravity check's rest is in the sensor's axes, apart from this. Returns the log line.
     */
    fun recentre(): String {
        val before = displayQ().yawPitchRoll()
        val gw = lastGravityWorld
        val steady = startAligned && accelMean.full && gw != null && lastGravityOff <= settings.gravityFull &&
            lastGravityChange <= settings.gravityChangeFull && lastOmega.length < settings.recentreGyro
        val levelled = settings.recentreLevels && steady
        // 1.2.13: a steady recentre is a worn head looking ahead, so its gravity checks the axis layout ([GravityAxes]).
        if (steady) restGravity = GravityAxes.Rest(settings.axes.unapply(q.conjugate().rotate(gw)), "recentre")
        if (levelled) {
            val old = q
            q = relevel(q, q.conjugate().rotate(gw!!))
            // The integral must not take the jump for a bias.
            fullForS = 0f
            // 0.5.16: the averaged gravity is kept in the room's axes, which
            // the relevel just turned. Turned with them, it reads level now, so a second recentre
            // before the next mean (Mike's press recentred twice, 12 ms apart) changes nothing;
            // 0.5.15 applied the same correction again.
            if (settings.recentreTurnsGravity) {
                val turn = (q * old.conjugate()).normalized()
                lastGravityWorld = turn.rotate(gw)
                accelMean.rotate(turn)
                accelShort.rotate(turn)
            }
        }
        val after = displayQ().yawPitchRoll()
        val d = displayQ()
        val look = Angles.of(d.forward)
        // 1.2.13: the head's pitch is straight ahead too when tilted past the threshold ([FusionSettings.recentreFollowsTilt]).
        val pitch = recentrePitch(look.pitchDeg)
        // Looking nearly straight up or down, the forward's heading is noise: the head's right side gives it instead.
        val side = Vec3.UP cross d.rotate(Vec3.RIGHT)
        val yaw = if (kotlin.math.abs(look.pitchDeg) < NEAR_VERTICAL_DEG || side.length < 0.2f) look.yawDeg else Angles.of(side).yawDeg
        reference = Quat.fromYawPitch(yaw, pitch).conjugate().normalized()
        recentrePitchDeg = pitch
        recentres++
        return String.format(
            java.util.Locale.ROOT,
            "recentre %d: forward is now yaw %.1f deg, %s; %s",
            recentres, yaw,
            when {
                pitch != 0f -> "pitch %.1f deg (recenter follows head tilt)".format(java.util.Locale.ROOT, pitch)
                !settings.recentreFollowsTilt -> "the horizon (head pitch %.1f deg; recenter follows head tilt off)".format(java.util.Locale.ROOT, look.pitchDeg)
                else -> "the horizon (head pitch %.1f deg, within %.0f deg of level)".format(java.util.Locale.ROOT, look.pitchDeg, settings.recentreTiltMinDeg)
            },
            if (levelled) {
                "roll and pitch relevelled from the %.1f s gravity mean: roll %.2f to %.2f deg, pitch %.2f to %.2f deg".format(
                    java.util.Locale.ROOT, settings.accelMeanS, before.third, after.third, before.second, after.second,
                )
            } else if (!settings.recentreLevels) {
                "roll and pitch left to gravity (relevel off)"
            } else {
                "roll %.2f and pitch %.2f deg not relevelled (%s)".format(
                    java.util.Locale.ROOT, before.third, before.second,
                    when {
                        !startAligned -> "the start gate has not passed"
                        lastOmega.length >= settings.recentreGyro -> "the head turns at %.2f rad/s".format(java.util.Locale.ROOT, lastOmega.length)
                        else -> "gravity not steady: size off %.2f %%, change %.2f %%".format(java.util.Locale.ROOT, lastGravityOff * 100, lastGravityChange * 100)
                    },
                )
            },
        )
    }

    var recentres = 0
        private set

    /** 1.2.13: the pitch the last recentre took as straight ahead (degrees, up positive); 0 on the horizon. */
    var recentrePitchDeg = 0f
        private set

    /** 1.2.13: the pitch a recentre at head pitch [headPitchDeg] makes straight ahead: the head's, or 0 near level or with the switch off. */
    fun recentrePitch(headPitchDeg: Float): Float =
        if (settings.recentreFollowsTilt && headPitchDeg.isFinite() && kotlin.math.abs(headPitchDeg) > settings.recentreTiltMinDeg) headPitchDeg else 0f

    /**
     * 1.2.13: the display's pitch and roll against gravity (degrees), whatever the recentre took as straight ahead.
     * [pose] is relative to a tilted recentre, so a head 40 degrees up reads level there; this does not.
     */
    fun levelPitchRoll(): Pair<Float, Float> {
        val (_, p, r) = displayQ().yawPitchRoll()
        return p to r
    }

    /**
     * 0.5.15: the IMU to display mounting rotation from [FusionSettings.mountRollDeg] and
     * [FusionSettings.mountPitchDeg]: display axes to IMU axes ([LevelCalibration.mount]).
     */
    // 0.5.55: the key as two floats compared by their bits (as the Pair of boxed Floats compared them), not a Pair and two
    // boxed Floats made on every sample's pose.
    private var mountForRoll = Float.NaN
    private var mountForPitch = Float.NaN
    private var mountQ = Quat.IDENTITY

    private fun mount(): Quat {
        settings.factoryMount?.let { return it }
        val roll = settings.mountRollDeg
        val pitch = settings.mountPitchDeg
        if (roll.toBits() != mountForRoll.toBits() || pitch.toBits() != mountForPitch.toBits()) {
            mountForRoll = roll
            mountForPitch = pitch
            mountQ = LevelCalibration.mount(roll, pitch)
        }
        return mountQ
    }

    /** The display's orientation before the recentre's reference: the IMU's turned by the mount. */
    private fun displayQ(): Quat = (q * mount()).normalized()

    /**
     * 0.5.15: the level calibration's reading: the averaged gravity's roll and pitch in the IMU's
     * axes (degrees), to be saved as the mount while the display is held level; null while gravity
     * is not steady or the head turns.
     */
    fun levelReading(): Pair<Float, Float>? {
        val gw = lastGravityWorld ?: return null
        if (!startAligned || !accelMean.full || lastGravityOff > settings.gravityFull || lastGravityChange > settings.gravityChangeFull) return null
        if (lastOmega.length >= settings.wornLearnQuietGyro) return null
        return LevelCalibration.fromGravity(q.conjugate().rotate(gw))
    }

    /**
     * 0.5.15: yaw, pitch and roll of the tracking frame, what the averaged
     * gravity says roll and pitch are, and the mount, for the 10 s head line.
     */
    fun attitudeLine(): String {
        val (y, p, r) = pose().orientation.yawPitchRoll()
        val gw = lastGravityWorld
        val grav = if (gw == null || !startAligned) "gravity not in use yet" else {
            val d = displayQ()
            // The display's orientation relevelled to the gravity mean: what gravity alone says.
            val gHead = mount().conjugate().rotate(q.conjugate().rotate(gw))
            val (_, gp, gr) = relevel(d, gHead).yawPitchRoll()
            val expected = d.conjugate().rotate(Vec3.UP)
            val tilt = kotlin.math.acos((expected dot gHead.normalized()).coerceIn(-1f, 1f)) * RAD
            String.format(java.util.Locale.ROOT, "gravity mean says pitch %.2f roll %.2f deg (tilt error %.2f deg, size off %.2f %%)", gp, gr, tilt, lastGravityOff * 100)
        }
        val b = bias
        val a = staticBias
        return String.format(
            java.util.Locale.ROOT,
            "head attitude: yaw %.1f pitch %.2f roll %.2f deg (tracking frame%s); %s; mount roll %.2f pitch %.2f deg; %d recentres; bias %.5f, %.5f, %.5f rad/s, %s",
            y, p, r,
            // 1.2.13: the tracking frame's pitch is from a tilted recentre's straight ahead, so the line says by how much.
            if (recentrePitchDeg != 0f) ", straight ahead %.1f deg %s the horizon".format(java.util.Locale.ROOT, kotlin.math.abs(recentrePitchDeg), if (recentrePitchDeg > 0f) "above" else "below") else "",
            grav, settings.mountRollDeg, settings.mountPitchDeg, recentres, b.x, b.y, b.z,
            if (a == null) "no static bias to hold it to" else "%.5f, %.5f, %.5f from the static one".format(java.util.Locale.ROOT, b.x - a.x, b.y - a.y, b.z - a.z),
        )
    }

    fun reset() {
        q = Quat.IDENTITY
        integral = Vec3.ZERO
        bias = Vec3.ZERO
        appliedBias = Vec3.ZERO
        kpApplied = 0f
        rateMean = Vec3.ZERO
        initialised = false
        stillFor = 0f
        reference = Quat.IDENTITY
        recentrePitchDeg = 0f
        gravityNorm = 0f
        samples = 0
        biasLearned = false
        detector.reset()
        runS = 0f
        biasLearnedAtS = -1f
        startGate.reset()
        settleFromS = 0f
        accelMean.reset()
        accelShort.reset()
        tierCounts.fill(0)
        wornSum = Vec3.ZERO
        wornUp = Vec3.ZERO
        wornS = 0f
        staticLearned = false
        staticLearnedAtS = -1f
        staticBias = null
        fullForS = 0f
        settledS = 0f
        lastGravityWorld = null
        restGravity = null
        fallbackWorld = null
        fallbackScore = Float.MAX_VALUE
        chunkSum = Vec3.ZERO
        chunkS = 0f
        chunks = 0
        stillChunks = 0
        prevYawMean = null
        wornYawLearnedS = 0f
        lastYawWindow = null
        chunkRawSum = Vec3.ZERO
        restAlongSum = 0.0
        restS = 0.0
        yawAgreeRing.clear()
        yawGuardGrown = false
        yawGuardLine = null
        yawWindowShares.clear()
        seededWorn = null
        clearPool()
        clearLong()
        poolCapLifted = false
        poolCapLine = null
        poolHeld = 0
        notUpright = false
        poolEstimates = 0
        poolUsed = 0
        lastPool = null
        correctionsLimited = 0L
        driftChunkSum = 0f; driftChunkS = 0f
        driftStillSum = 0.0; driftStillS = 0.0; driftAllSum = 0.0; driftAllS = 0.0
        // The old saved bias is kept to be confirmed again; what it set went with the bias.
        oldBiasTaken = false
        startGateEvent = null
    }

    /** 0.5.15: in the display's axes: the IMU's orientation and rate turned by the mount ([FusionSettings.mountRollDeg]). */
    fun pose(timestampNs: Long = lastNs): HeadPose {
        val m = mount()
        // 0.5.55: the same products in one Quat and one Vec3 ([HeadMath]); they made eleven objects every sample.
        return HeadPose(HeadMath.mul3Normalized(reference, q, m), HeadMath.rotateByConjugate(m, lastOmega), timestampNs, isStatic, bias)
    }

    /** Orientation with zero yaw whose up matches the measured gravity. */
    private fun levelFrom(accel: Vec3): Quat {
        if (accel.length < 1e-6f) return Quat.IDENTITY
        val up = accel.normalized()
        // Rotation taking the body's measured up onto the world up.
        val axis = up cross Vec3.UP
        val s = axis.length
        val c = up dot Vec3.UP
        if (s < 1e-6f) return if (c > 0) Quat.IDENTITY else Quat.axisAngle(Vec3.RIGHT, Math.PI.toFloat())
        return Quat.axisAngle(axis, kotlin.math.atan2(s, c)).normalized()
    }

    private companion object {
        /** 1.2.13: past this pitch a recentre takes its heading from the head's right side, not its forward. */
        const val NEAR_VERTICAL_DEG = 80f
    }
}

/** Reports per second over a sliding window, for the diagnostics screen. */
class RateMeter(private val windowNs: Long = 1_000_000_000L) {
    private var windowStart = Long.MIN_VALUE
    private var count = 0
    var rate: Double = 0.0
        private set

    fun tick(nowNs: Long, n: Int = 1) {
        if (windowStart == Long.MIN_VALUE) windowStart = nowNs
        count += n
        val elapsed = nowNs - windowStart
        if (elapsed >= windowNs) {
            rate = count * 1e9 / elapsed
            count = 0
            windowStart = nowNs
        }
    }
}

/** Absolute difference of two angles in degrees, wrapping at 180. */
fun angleDiff(a: Float, b: Float): Float = abs(wrapDegrees(a - b))

/**
 * 0.5.11: is the head truly static? The test:
 * the peak to peak of each gyro axis over a long window, after a 50 Hz low pass, is tiny, and the
 * accelerometer's size is steady. The window is kept as [chunkS] chunks of min, max and sum so a
 * 1 kHz stream costs a few operations a sample. 0.5.10 called anything under 2.3 deg/s for 0.8 s
 * still, which natural head sway (0.5 to 2 deg/s) passes; a sway of 1 deg/s has a peak to peak of
 * 0.035 rad/s and never passes this one.
 */
class StaticDetector(val chunkS: Float = 0.1f) {
    /**
     * 0.5.55: the chunk's extremes and sums as plain floats, the very sums and comparisons the Vec3s held: each sample
     * made five new Vec3s for them (and two more for the exit check), and each window check 200 for its 40 chunks.
     */
    private class Chunk {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var accMinX = Float.MAX_VALUE
        var accMinY = Float.MAX_VALUE
        var accMinZ = Float.MAX_VALUE
        var accMaxX = -Float.MAX_VALUE
        var accMaxY = -Float.MAX_VALUE
        var accMaxZ = -Float.MAX_VALUE
        var accSum = 0.0
        var n = 0
        var s = 0f

        fun gyroSpread() = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
        fun accelSpread() = maxOf(accMaxX - accMinX, accMaxY - accMinY, accMaxZ - accMinZ)
    }

    private val chunks = ArrayDeque<Chunk>()
    private var current = Chunk()

    // 0.5.55: the two low passes as floats ([lpOn] false: no sample yet, as the null Vec3s were).
    private var lpOn = false
    private var lpX = 0f
    private var lpY = 0f
    private var lpZ = 0f
    private var lpAccelOn = false
    private var lpAX = 0f
    private var lpAY = 0f
    private var lpAZ = 0f

    /** True while the last full window was static. */
    var static = false
        private set

    /** 0.5.12: the gyro peak to peak gate in use, rad/s: the setting, or measured ([FusionSettings.adaptiveStaticGate]). */
    var gate = -1f
        private set

    /** 0.5.12: the last full window's largest gyro axis peak to peak (rad/s) and accelerometer spread (share of gravity). */
    var lastPeakToPeak = 0f
        private set
    var lastAccelSpread = 0f
        private set

    /** 0.5.12: the detector's own clock, and when the glasses last stopped moving (-1 while they move). */
    private var nowS = 0f
    var stillSinceS = -1f
        private set
    val stillForS: Float get() = if (stillSinceS < 0f) 0f else nowS - stillSinceS
    private val quietWindows = RollingStats(32)
    private var quietFailed = 0

    /**
     * Adds one sample; at the end of each chunk returns the window's mean raw gyro when the window
     * is static, else null.
     */
    fun add(gyro: Vec3, accel: Vec3, dt: Float, settings: FusionSettings): Vec3? {
        val accelNorm = accel.length
        // 50 Hz one pole low pass (3.2 ms) on each axis before the peak to peak.
        val k = (dt / (0.0032f + dt)).coerceIn(0f, 1f)
        // 0.5.55: lp + (gyro - lp) * k per axis, as the Vec3s did it.
        if (lpOn) {
            lpX += (gyro.x - lpX) * k
            lpY += (gyro.y - lpY) * k
            lpZ += (gyro.z - lpZ) * k
        } else {
            lpX = gyro.x; lpY = gyro.y; lpZ = gyro.z
            lpOn = true
        }
        // The accelerometer through 10 Hz (16 ms): its noise is larger than the tilt that matters.
        val ka = (dt / (0.016f + dt)).coerceIn(0f, 1f)
        if (lpAccelOn) {
            lpAX += (accel.x - lpAX) * ka
            lpAY += (accel.y - lpAY) * ka
            lpAZ += (accel.z - lpAZ) * ka
        } else {
            lpAX = accel.x; lpAY = accel.y; lpAZ = accel.z
            lpAccelOn = true
        }
        val c = current
        c.minX = minOf(c.minX, lpX); c.minY = minOf(c.minY, lpY); c.minZ = minOf(c.minZ, lpZ)
        c.maxX = maxOf(c.maxX, lpX); c.maxY = maxOf(c.maxY, lpY); c.maxZ = maxOf(c.maxZ, lpZ)
        c.sumX += gyro.x; c.sumY += gyro.y; c.sumZ += gyro.z
        c.accMinX = minOf(c.accMinX, lpAX); c.accMinY = minOf(c.accMinY, lpAY); c.accMinZ = minOf(c.accMinZ, lpAZ)
        c.accMaxX = maxOf(c.accMaxX, lpAX); c.accMaxY = maxOf(c.accMaxY, lpAY); c.accMaxZ = maxOf(c.accMaxZ, lpAZ)
        c.accSum += accelNorm
        c.n++
        c.s += dt
        nowS += dt
        if (gate < 0f) gate = settings.staticPeakToPeak
        // Motion ends "static" at once, not when the chunk closes: a seat shift (pure translation,
        // the gyro quiet) must not get the static tier's trust in gravity for a tenth of a second.
        // 0.5.18: on the gyro with hysteresis ([FusionSettings.staticExitShare]); the window check below still needs the gate itself.
        if (static && (c.gyroSpread() >= gate * settings.staticExitShare ||
                c.accelSpread() >= settings.staticAccel * accelNorm.coerceAtLeast(1e-6f))
        ) {
            static = false
        }
        if (c.s < chunkS) return null
        chunks.addLast(c)
        current = Chunk()
        // 0.5.12: a chunk that moved (the accelerometer swings, or the gyro well over any gate) ends a still spell.
        val chunkMoved = c.accelSpread() >= settings.staticAccel * (c.accSum / c.n).toFloat() ||
            c.gyroSpread() >= settings.staticGateMax * 2f
        if (chunkMoved) stillSinceS = -1f else if (stillSinceS < 0f) stillSinceS = nowS - c.s
        val keep = kotlin.math.ceil(settings.staticWindowS / chunkS).toInt().coerceAtLeast(1)
        while (chunks.size > keep) chunks.removeFirst()
        if (chunks.size < keep) {
            static = false
            return null
        }
        // 0.5.55: the window's extremes and sums as floats, by index (no Vec3 or iterator per chunk).
        var mnX = Float.MAX_VALUE
        var mnY = Float.MAX_VALUE
        var mnZ = Float.MAX_VALUE
        var mxX = -Float.MAX_VALUE
        var mxY = -Float.MAX_VALUE
        var mxZ = -Float.MAX_VALUE
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var n = 0
        var firstX = 0f
        var firstY = 0f
        var firstZ = 0f
        var firstN = 0
        val half = chunks.size / 2
        var aMinX = Float.MAX_VALUE
        var aMinY = Float.MAX_VALUE
        var aMinZ = Float.MAX_VALUE
        var aMaxX = -Float.MAX_VALUE
        var aMaxY = -Float.MAX_VALUE
        var aMaxZ = -Float.MAX_VALUE
        var aSum = 0.0
        for (i in 0 until chunks.size) {
            val ch = chunks[i]
            if (i < half) {
                firstX += ch.sumX; firstY += ch.sumY; firstZ += ch.sumZ
                firstN += ch.n
            }
            mnX = minOf(mnX, ch.minX); mnY = minOf(mnY, ch.minY); mnZ = minOf(mnZ, ch.minZ)
            mxX = maxOf(mxX, ch.maxX); mxY = maxOf(mxY, ch.maxY); mxZ = maxOf(mxZ, ch.maxZ)
            sumX += ch.sumX; sumY += ch.sumY; sumZ += ch.sumZ
            n += ch.n
            aMinX = minOf(aMinX, ch.accMinX); aMinY = minOf(aMinY, ch.accMinY); aMinZ = minOf(aMinZ, ch.accMinZ)
            aMaxX = maxOf(aMaxX, ch.accMaxX); aMaxY = maxOf(aMaxY, ch.accMaxY); aMaxZ = maxOf(aMaxZ, ch.accMaxZ)
            aSum += ch.accSum
        }
        val aMean = (aSum / n).toFloat()
        lastPeakToPeak = maxOf(mxX - mnX, mxY - mnY, mxZ - mnZ)
        val aSpread = maxOf(aMaxX - aMinX, aMaxY - aMinY, aMaxZ - aMinZ)
        lastAccelSpread = if (aMean > 1e-6f) aSpread / aMean else 1f
        val accelSteady = aMean > 1e-6f && aSpread < settings.staticAccel * aMean
        // 0.5.12: still by the accelerometer but over the gate by the gyro: the gyro's own noise.
        // After [FusionSettings.staticGateWindows] such windows in a row the gate is measured.
        if (settings.adaptiveStaticGate && accelSteady && lastPeakToPeak >= gate && lastPeakToPeak < settings.staticGateMax * 2f) {
            quietWindows.add(lastPeakToPeak.toDouble())
            quietFailed++
            if (quietFailed >= settings.staticGateWindows) {
                gate = (quietWindows.percentile(0.99).toFloat() * 1.5f).coerceIn(gate, settings.staticGateMax)
                quietFailed = 0
            }
        } else if (!accelSteady || lastPeakToPeak < gate) {
            quietFailed = 0
        }
        val p2p = gate
        static = mxX - mnX < p2p && mxY - mnY < p2p && mxZ - mnZ < p2p && accelSteady
        if (!static) return null
        // A constant bias reads the same in both halves of the window; a slow sway does not.
        val sa = 1f / firstN.coerceAtLeast(1)
        val sb = 1f / (n - firstN).coerceAtLeast(1)
        val dx = firstX * sa - (sumX - firstX) * sb
        val dy = firstY * sa - (sumY - firstY) * sb
        val dz = firstZ * sa - (sumZ - firstZ) * sb
        val h = settings.staticHalves
        val sn = 1f / n
        return if (abs(dx) < h && abs(dy) < h && abs(dz) < h) Vec3(sumX * sn, sumY * sn, sumZ * sn) else null
    }

    fun reset() {
        chunks.clear()
        current = Chunk()
        lpOn = false
        lpAccelOn = false
        static = false
        stillSinceS = -1f
        quietWindows.clear()
        quietFailed = 0
        // The measured gate is the gyro's, not the session's: it is kept.
    }
}
