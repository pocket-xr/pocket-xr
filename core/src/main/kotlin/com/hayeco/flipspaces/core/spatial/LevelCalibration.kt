package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.atan2
import kotlin.math.tan

/**
 * 0.5.15: the IMU to display mounting rotation. Nothing in 0.5.14
 * applied one, so any fixed tilt between the Aura's IMU and its optics showed as a constant roll
 * or pitch from the first frame. The Aura's own calibration (eye poses, IMU to head) is read from
 * the glasses over their control channel, which Pocket XR does not use, so it is measured
 * once instead: a line is drawn level across the middle of the view, the user tilts their head
 * until it lies along a level edge at eye height and holds still, and the IMU's averaged gravity
 * at that moment is the mount ([fromGravity]).
 *
 * The mount is kept as two angles: the roll and pitch (degrees) the IMU's gravity reads while the
 * display is level, roll = atan2(up.x, up.y) and pitch = atan2(up.z, up.y) in the head axes
 * (x right, y up, z back). Yaw between the two is not observable and does not matter.
 */
object LevelCalibration {
    /** The most a mount's roll may be, degrees: anything larger is a head held crooked, not the glasses. */
    const val MAX_ROLL_DEG = 10f

    /**
     * 0.5.16: the most a mount's pitch may be. The Aura's IMU sits about
     * 14.5 degrees pitched from the display's level line (Mike's three tries read 14.5, 14.5 and
     * 14.3, all refused by 0.5.15's 10), as a glasses frame's forward tilt puts it.
     * 1.1.7 (issue 30, an XREAL One Pro: about 20 degrees off when level, and its level calibration refused): 40, for
     * glasses whose optics sit steeper than the Aura's. A head bent further than that is still refused.
     * 1.2.13 (Discussion #81, a One Pro+ whose steady recentres read 29 to 49 degrees up, about 40 in the middle): 50,
     * so its level calibration is not refused about half the time.
     */
    const val MAX_PITCH_DEG = 50f

    /** 0.5.15's single limit, for both. */
    const val MAX_DEG = MAX_ROLL_DEG

    /** The roll and pitch of [gravityImu] (the accelerometer's mean, IMU axes), degrees. */
    fun fromGravity(gravityImu: Vec3): Pair<Float, Float> =
        atan2(gravityImu.x, gravityImu.y) * RAD to atan2(gravityImu.z, gravityImu.y) * RAD

    /** True when a reading is small enough to be a mount ([MAX_ROLL_DEG], [MAX_PITCH_DEG]). */
    fun plausible(rollDeg: Float, pitchDeg: Float): Boolean = refusal(rollDeg, pitchDeg) == null

    /** 0.5.16: why a reading cannot be a mount, in plain words with the reading; null when it can. */
    fun refusal(rollDeg: Float, pitchDeg: Float): String? = when {
        !rollDeg.isFinite() || !pitchDeg.isFinite() -> UiText.get("spatial_level_not_a_number", "the reading is not a number")
        kotlin.math.abs(rollDeg) > MAX_ROLL_DEG -> UiText.format("spatial_level_roll_too_much", "roll %1\$.1f degrees is more than %2\$.0f", rollDeg, MAX_ROLL_DEG)
        kotlin.math.abs(pitchDeg) > MAX_PITCH_DEG -> UiText.format("spatial_level_pitch_too_much", "pitch %1\$.1f degrees is more than %2\$.0f", pitchDeg, MAX_PITCH_DEG)
        else -> null
    }

    /** The IMU's up while the display is level, for [rollDeg] and [pitchDeg]. */
    fun imuUp(rollDeg: Float, pitchDeg: Float): Vec3 = Vec3(tan(rollDeg * DEG), 1f, tan(pitchDeg * DEG)).normalized()

    /**
     * Display axes to IMU axes: the shortest rotation taking the display's up onto [imuUp]. The
     * fusion's display orientation is its IMU orientation times this, so a display held level reads
     * level whatever the IMU's tilt in the frame.
     */
    fun mount(rollDeg: Float, pitchDeg: Float): Quat {
        if (rollDeg == 0f && pitchDeg == 0f) return Quat.IDENTITY
        val up = imuUp(rollDeg, pitchDeg)
        val axis = Vec3.UP cross up
        val s = axis.length
        if (s < 1e-7f) return Quat.IDENTITY
        return Quat.axisAngle(axis, atan2(s, Vec3.UP dot up)).normalized()
    }

    fun line(rollDeg: Float, pitchDeg: Float, calibrated: Boolean): String = if (!calibrated) {
        "level calibration: none saved; the IMU is taken as mounted level with the display"
    } else {
        String.format(java.util.Locale.ROOT, "level calibration: the IMU reads roll %.2f pitch %.2f deg while the display is level; applied as the mount", rollDeg, pitchDeg)
    }
}
