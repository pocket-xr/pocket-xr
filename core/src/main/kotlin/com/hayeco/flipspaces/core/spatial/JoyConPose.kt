package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One Joy-Con's orientation from its gyro and accelerometer.
 *
 * The sensors as Linux reports them, measured on both Joy-Cons: x along the length toward the shoulder button end,
 * y to the left, z out of the face, right handed, the accelerometer reading up as positive. Pointed forward with the
 * face up like a remote, that is the OpenXR frame (x right, y up, z back) with x' = -y, y' = z and z' = -x.
 *
 * - **Gyro drift** is learned whenever the Joy-Con lies still for a second, and can be handed back next time
 *   ([biasRadS], [restoreBias]), so a Joy-Con starts true.
 * - **Gravity** keeps pitch and roll true, but a swing's own push looks like gravity, so the pull fades away as the
 *   Joy-Con turns faster or reads far from 1 g, and stops for a moment after a reading past the sensors' range.
 * - **Yaw** has nothing to keep it true. [align] lines the Joy-Con up with the head the first time it is held level
 *   and still, and afterwards takes out large slow drift, half a degree a second at most. [recentre] lines it up at once.
 */
class JoyConPose(@Suppress("unused") private val right: Boolean) {
    private var attitude = Quat.IDENTITY
    private var yawFix = Quat.IDENTITY
    private var bias = Vec3(0f, 0f, 0f)
    private var settleS = SETTLE_S
    private var clipHoldS = 0f

    // Rest: low passed readings and how long they have stayed still.
    private var lpGyro = Vec3(0f, 0f, 0f)
    private var lpAccel = Vec3(0f, 1f, 0f)
    private var restS = 0f
    private var lpStarted = false

    // Yaw lining up.
    private var aligned = false
    private var steadyS = 0f
    private var aliveS = 0f
    private var yawErrorAvg = 0f

    /** The Joy-Con's orientation, Joy-Con to world. */
    val orientation: Quat get() = (yawFix * attitude).normalized()

    /** The last turn rate in the world frame, radians per second, drift taken out. */
    var angularVelocity = Vec3(0f, 0f, 0f)
        private set

    /** The gyro's drift in the Joy-Con's frame (OpenXR axes), radians per second. */
    val biasRadS: Vec3 get() = bias

    /** True once a rest has measured the drift on this Joy-Con, so it is worth keeping. */
    var biasLearned = false
        private set

    /** Lying still now (a second or more). */
    val atRest: Boolean get() = restS >= REST_MIN_S

    /** Lined up with the head at least once. */
    val isAligned: Boolean get() = aligned

    /** The drift found last time. A rest measures it again; until then it stands. */
    fun restoreBias(saved: Vec3) {
        bias = clampBias(saved)
    }

    /**
     * One sample, in the sensors' own axes: [gx], [gy], [gz] in radians per second, [ax], [ay], [az] in g. [dt] is the
     * time since the last sample, in seconds.
     */
    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float) {
        if (dt <= 0f || dt > MAX_DT_S) return
        val gyro = Vec3(-gy, gz, -gx)
        val accel = Vec3(-ay, az, -ax)
        val length = accel.length
        aliveS += dt

        if (abs(gx) > CLIP_RAD_S || abs(gy) > CLIP_RAD_S || abs(gz) > CLIP_RAD_S || length > CLIP_G) clipHoldS = CLIP_HOLD_S
        else if (clipHoldS > 0f) clipHoldS -= dt

        rest(gyro, accel, length, dt)

        val turning = gyro - bias
        val rate = turning.length
        var gain = 0f
        if (length > 0.5f && clipHoldS <= 0f) {
            gain = when {
                settleS > 0f -> SETTLE_GAIN
                atRest -> REST_GAIN
                else -> {
                    val motion = (1f - (rate - CALM_RAD_S) / FADE_RAD_S).coerceIn(0f, 1f)
                    val trust = (1f - abs(length - 1f) / ACCEL_WINDOW_G).coerceIn(0f, 1f)
                    GAIN * motion * trust
                }
            }
        }
        var correction = Vec3(0f, 0f, 0f)
        if (gain > 0f) {
            val measured = accel * (1f / length)
            val expected = attitude.conjugate().rotate(Vec3.UP)
            val error = measured cross expected
            correction = error * gain
            // A slow pull on the drift from gravity too, for hands that never rest. It cannot see the axis along gravity.
            if (settleS <= 0f && !atRest && gain >= GAIN * 0.5f) bias = clampBias(bias - error * (BIAS_GAIN * dt))
        }
        if (settleS > 0f) settleS -= dt
        attitude = (attitude * Quat.fromGyro(turning + correction, dt)).normalized()
        angularVelocity = orientation.rotate(turning)
    }

    /** Still for [REST_MIN_S]: the gyro's average then is its drift. */
    private fun rest(gyro: Vec3, accel: Vec3, length: Float, dt: Float) {
        if (!lpStarted) {
            lpGyro = gyro
            lpAccel = accel
            lpStarted = true
        }
        val k = 1f - exp(-dt / REST_FILTER_S)
        lpGyro = lpGyro + (gyro - lpGyro) * k
        lpAccel = lpAccel + (accel - lpAccel) * k
        val still = (gyro - lpGyro).length < REST_GYRO_RAD_S &&
            lpGyro.length < REST_MAX_DRIFT_RAD_S &&
            (accel - lpAccel).length < REST_ACCEL_G &&
            abs(length - 1f) < REST_LEVEL_G
        if (!still) {
            restS = 0f
            return
        }
        restS += dt
        if (restS >= REST_MIN_S) {
            bias = clampBias(bias + (lpGyro - bias) * (1f - exp(-dt / REST_BIAS_S)))
            if (restS >= REST_MIN_S + REST_BIAS_S) biasLearned = true
        }
    }

    /** Points the Joy-Con's forward at the head's forward, leaving its pitch and roll alone. */
    fun recentre(head: Quat) {
        val f = attitude.rotate(FORWARD)
        if (horizontal(f) < MIN_LEVEL) return
        val turn = yawOf(head.rotate(FORWARD)) - yawOf(f)
        yawFix = Quat.axisAngle(Vec3.UP, turn)
        aligned = true
        yawErrorAvg = 0f
    }

    /**
     * Each sample, with the head as it is: lines the Joy-Con up the first time it is held level and still (or after
     * [ALIGN_ANYWAY_S] whatever it does), then takes out drift that has built up past [DEAD_BAND_RAD] on average.
     * Hands point roughly where the head points over a minute, so that average is the drift.
     */
    fun align(head: Quat, dt: Float) {
        if (dt <= 0f || dt > MAX_DT_S) return
        val f = orientation.rotate(FORWARD)
        val level = horizontal(f)
        val slow = angularVelocity.length < ALIGN_RATE_RAD_S
        if (!aligned) {
            steadyS = if (level > LEVEL_FOR_START && slow) steadyS + dt else 0f
            if (steadyS >= ALIGN_STEADY_S || aliveS >= ALIGN_ANYWAY_S) recentre(head)
            return
        }
        if (level < LEVEL_FOR_PRIOR || angularVelocity.length > PRIOR_RATE_RAD_S) return
        val h = head.rotate(FORWARD)
        if (horizontal(h) < MIN_LEVEL) return
        val error = wrap(yawOf(h) - yawOf(f))
        yawErrorAvg += (error - yawErrorAvg) * (1f - exp(-dt / PRIOR_TAU_S))
        val excess = abs(yawErrorAvg) - DEAD_BAND_RAD
        if (excess > 0f) {
            val step = min(excess, PRIOR_SLEW_RAD_S * dt) * if (yawErrorAvg > 0f) 1f else -1f
            yawFix = (Quat.axisAngle(Vec3.UP, step) * yawFix).normalized()
            yawErrorAvg -= step
        }
    }

    private fun clampBias(b: Vec3): Vec3 {
        val n = b.length
        return if (n > MAX_BIAS_RAD_S) b * (MAX_BIAS_RAD_S / n) else b
    }

    private fun horizontal(v: Vec3) = sqrt(v.x * v.x + v.z * v.z)

    /** Yaw of a direction about world up, radians, counterclockwise seen from above. */
    private fun yawOf(v: Vec3): Float = atan2(-v.x, -v.z)

    private fun wrap(a: Float): Float {
        var r = a
        while (r > PI) r -= (2 * PI).toFloat()
        while (r < -PI) r += (2 * PI).toFloat()
        return r
    }

    private companion object {
        val FORWARD = Vec3(0f, 0f, -1f)
        const val DEG = (PI / 180.0).toFloat()
        const val MAX_DT_S = 0.1f

        const val GAIN = 0.5f
        const val REST_GAIN = 2f
        const val SETTLE_S = 0.5f
        const val SETTLE_GAIN = 20f
        const val CALM_RAD_S = 0.3f
        const val FADE_RAD_S = 2f
        const val ACCEL_WINDOW_G = 0.25f
        const val BIAS_GAIN = 0.02f

        /** Past these the sensors clip (about 2300 degrees a second, 8 g): no gravity pull for a moment. */
        const val CLIP_RAD_S = 38f
        const val CLIP_G = 7.5f
        const val CLIP_HOLD_S = 0.2f

        const val REST_FILTER_S = 0.5f
        const val REST_MIN_S = 1f
        const val REST_BIAS_S = 1f
        val REST_GYRO_RAD_S = 3f * DEG
        val REST_MAX_DRIFT_RAD_S = 6f * DEG
        const val REST_ACCEL_G = 0.06f
        const val REST_LEVEL_G = 0.1f
        val MAX_BIAS_RAD_S = 5f * DEG

        const val MIN_LEVEL = 0.5f
        const val LEVEL_FOR_START = 0.85f
        const val ALIGN_RATE_RAD_S = 0.5f
        const val ALIGN_STEADY_S = 0.3f
        const val ALIGN_ANYWAY_S = 3f
        const val LEVEL_FOR_PRIOR = 0.7f
        const val PRIOR_RATE_RAD_S = 1f
        const val PRIOR_TAU_S = 30f
        val DEAD_BAND_RAD = 15f * DEG
        val PRIOR_SLEW_RAD_S = 0.5f * DEG
    }
}
