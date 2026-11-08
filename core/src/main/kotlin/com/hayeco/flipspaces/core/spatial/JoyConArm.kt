package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where a Joy-Con's grip is, from its orientation alone: there is no position sensor.
 *
 * The arm of Google's Daydream controller (GvrArmModel, Apache 2.0), in OpenXR axes (x right, y up, z back), in metres
 * from the eyes. A turn is shared between elbow and wrist, so a flick of the wrist moves the hand a little and a swing
 * of the arm a lot, and the arm reaches forward as it is raised. The body turns toward where the head looks only while
 * the arm is moving, so turning the head alone leaves both hands where they are (before, each hand swung with the head).
 *
 * [velocity] is the grip's speed, measured across samples and smoothed, for Monado to predict the hand with.
 */
class JoyConArm(right: Boolean) {
    private val side = if (right) 1f else -1f
    private var torso: Vec3? = null
    private var last: Vec3? = null

    /**
     * Moves both hands together, in the body's frame (x right, y up, z back), metres. Settable while playing, from the
     * "joycons" preferences; the default is [DEFAULT_OFFSET].
     */
    var offset = DEFAULT_OFFSET

    /** How far apart the hands sit, as a share of Daydream's shoulder width (Mike: they sat too wide). */
    var spread = DEFAULT_SPREAD

    /** The grip's position, metres. */
    var position = Vec3(0f, 0f, 0f)
        private set

    /** The grip's velocity, metres per second. */
    var velocity = Vec3(0f, 0f, 0f)
        private set

    /** The body's facing, a level direction. */
    val torsoForward: Vec3 get() = torso ?: FORWARD

    /**
     * One sample: the Joy-Con's [orientation] (world), its turn rate [rateRadS], the head's orientation, and the time
     * since the last sample in seconds.
     */
    fun update(orientation: Quat, rateRadS: Float, head: Quat, dt: Float): Vec3 {
        val gaze = level(head.rotate(FORWARD)) ?: torsoForward
        val body = torso
        torso = if (body == null || dt <= 0f) {
            gaze
        } else {
            // Daydream: up to a tenth of the way each 60 Hz frame, and only with the arm moving.
            val strength = ((rateRadS - 0.2f) / 45f).coerceIn(0f, 0.1f)
            val k = 1f - (1f - strength).pow(dt * 60f)
            level(body + (gaze - body) * k) ?: body
        }
        val torsoRotation = yawRotation(torsoForward)
        position = place(torsoRotation, orientation) + torsoRotation.rotate(offset)

        val before = last
        if (before != null && dt > 0f) {
            val measured = (position - before) * (1f / dt)
            velocity = velocity + (measured - velocity) * (1f - exp(-dt / VELOCITY_TAU_S))
        }
        last = position
        return position
    }

    private fun place(torsoRotation: Quat, orientation: Quat): Vec3 {
        // The Joy-Con's turn as the body sees it.
        val local = (torsoRotation.conjugate() * orientation).normalized()
        val forward = local.rotate(FORWARD)
        val pitchDeg = 90f - acos(forward.y.coerceIn(-1f, 1f)) * RAD
        val extension = ((pitchDeg - MIN_EXTENSION_DEG) / (MAX_EXTENSION_DEG - MIN_EXTENSION_DEG)).coerceIn(0f, 1f)

        // The turn from straight ahead to where it points, without roll.
        val pointing = fromTo(FORWARD, forward)
        val total = pointing.angleTo(Quat.IDENTITY)
        val suppression = 1f - (total / 180f).pow(6)
        val share = (1f - ELBOW_BEND + ELBOW_BEND * extension * EXTENSION_WEIGHT) * suppression
        val lerp = Quat.IDENTITY.slerp(pointing, share)

        val elbowRotation = (torsoRotation * lerp.conjugate() * pointing).normalized()
        val wristRotation = (torsoRotation * local).normalized()

        val rest = handed(ELBOW_REST)
        val elbowRest = Vec3(rest.x * spread, rest.y, rest.z) + handed(EXTENSION_OFFSET) * extension
        val elbow = NECK + torsoRotation.rotate(elbowRest)
        val wrist = elbow + elbowRotation.rotate(handed(WRIST))
        return wrist + wristRotation.rotate(handed(GRIP))
    }

    private fun handed(v: Vec3) = Vec3(v.x * side, v.y, v.z)

    private fun level(v: Vec3): Vec3? {
        val n = sqrt(v.x * v.x + v.z * v.z)
        return if (n < 1e-3f) null else Vec3(v.x / n, 0f, v.z / n)
    }

    private fun yawRotation(forward: Vec3): Quat = fromTo(FORWARD, forward)

    private fun fromTo(a: Vec3, b: Vec3): Quat {
        val d = (a dot b).coerceIn(-1f, 1f)
        if (d < -0.9999f) return Quat.axisAngle(Vec3.UP, PI.toFloat())
        val c = a cross b
        return Quat(1f + d, c.x, c.y, c.z).normalized()
    }

    companion object {
        /**
         * Tuned live with Mike in Beat Saber, 7 November 2026, on his XREAL glasses: Daydream's hands sat low, wide
         * and far out, so they go 0.15 m up and 0.4 m back, at 60 % of its shoulder width.
         */
        val DEFAULT_OFFSET = Vec3(0f, 0.15f, 0.4f)
        const val DEFAULT_SPREAD = 0.6f

        private val FORWARD = Vec3(0f, 0f, -1f)
        private const val RAD = (180.0 / PI).toFloat()

        // Daydream's right arm, z turned to OpenXR's.
        private val NECK = Vec3(0f, 0f, 0.08f)
        private val ELBOW_REST = Vec3(0.195f, -0.5f, -0.005f)
        private val WRIST = Vec3(0f, 0f, -0.25f)
        private val GRIP = Vec3(0f, 0f, -0.05f)
        private val EXTENSION_OFFSET = Vec3(-0.13f, 0.14f, -0.08f)
        private const val ELBOW_BEND = 0.6f
        private const val EXTENSION_WEIGHT = 0.4f
        private const val MIN_EXTENSION_DEG = 7f
        private const val MAX_EXTENSION_DEG = 60f
        private const val VELOCITY_TAU_S = 0.03f
    }
}
