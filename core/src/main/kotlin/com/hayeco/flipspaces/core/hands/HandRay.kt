package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.DEG
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import java.util.Locale
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

/**
 * The glasses camera as a pinhole (0.5.0): a normalised
 * image point (x, y in 0..1 across the upright frame) becomes a direction in the camera's frame,
 * then in the head frame (x right, y up, -z forward, metres from the head centre) through
 * [yawDeg], [pitchDeg], [rollDeg] and [offset].
 *
 * The Aura publishes no intrinsics or extrinsics (no published camera model), so the defaults
 * are our estimates, looking straight ahead from the bridge of the glasses, 1 cm above and 1 cm
 * in front of the eyes' centre.
 *
 * 0.5.10: 68 by 41.6 degrees across the 16:9 frame (was 80 by 51). 0.5.0 to 0.5.9 took the "80
 * degrees" as the frame's width; a camera's quoted angle is its diagonal, and 80 degrees on the
 * diagonal of a 4:3 sensor whose full width the 1080p picture keeps is 68 degrees across. Too wide
 * an angle per pixel puts every hand further from the centre than it is, by more the further out
 * it is: Mike's right hand's cursor right of his aim, his left hand's left of it (10/22). Two older
 * measurements point the same way: 0.4.8's air box, which put the cursor near the hand, spread
 * the picture's middle 0.7 over the display, and 0.5.0's palm size distances with 80 degrees sat on
 * their 0.2 m clamp for hands at arm's length ([AURA_HFOV_DEG]).
 */
data class CameraModel(
    val hfovDeg: Float = AURA_HFOV_DEG,
    val vfovDeg: Float = verticalFor(AURA_HFOV_DEG, 16f / 9f),
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val offset: Vec3 = Vec3(0f, 0.01f, -0.01f),
) {
    init {
        require(hfovDeg in 10f..170f && vfovDeg in 10f..170f) { "Camera field of view must be 10 to 170 degrees" }
    }

    private val halfX = tan(hfovDeg * DEG / 2f)
    private val halfY = tan(vfovDeg * DEG / 2f)
    private val rotation: Quat =
        (Quat.fromYawPitch(yawDeg, pitchDeg) * Quat.axisAngle(Vec3.FORWARD, rollDeg * DEG)).normalized()

    /** The image point on the camera's image plane at distance 1 (x right, y up). */
    fun plane(p: Landmark): Pair<Float, Float> = (p.x - cx) * 2f * halfX to -(p.y - cy) * 2f * halfY

    /** The point [p] at [depthM] in front of the camera, in the head frame. */
    fun toHead(p: Landmark, depthM: Float): Vec3 {
        val (tx, ty) = plane(p)
        return rotation.rotate(Vec3(tx * depthM, ty * depthM, -depthM)) + offset
    }

    /** The head frame direction the camera sees [p] in. */
    fun direction(p: Landmark): Vec3 {
        val (tx, ty) = plane(p)
        return rotation.rotate(Vec3(tx, ty, -1f).normalized())
    }

    /**
     * How far the hand is from the camera, from its size (a pinhole estimate): the
     * palm's bones in metres (world landmarks, or a typical hand) over their length on the image
     * plane. Foreshortening only ever shortens a bone in the picture, so the bone that gives the
     * nearest depth is the least foreshortened, and that one is used; clamped to [minM]..[maxM].
     */
    fun depthOf(hand: HandFrame, minM: Float = 0.2f, maxM: Float = 0.8f): Float {
        var best = Float.MAX_VALUE
        for ((i, bone) in PALM_BONES.withIndex()) {
            val (a, b) = bone
            val real = hand.world?.let { it[a].distance3To(it[b]) }?.takeIf { it > 0.01f } ?: TYPICAL_BONES_M[i]
            val (ax, ay) = plane(hand[a])
            val (bx, by) = plane(hand[b])
            val seen = hypot(ax - bx, ay - by)
            if (seen > 1e-4f) best = minOf(best, real / seen)
        }
        return if (best == Float.MAX_VALUE) DEFAULT_DEPTH_M else best.coerceIn(minM, maxM)
    }

    /**
     * 0.5.10: how big the palm looks: the longest of its three bones on the image plane at
     * distance 1 (the least foreshortened). It halves when the hand is twice as far; only its
     * change during a two hand grab is used ([com.hayeco.flipspaces.core.spatial.TwoHandDepth]).
     */
    fun palmSize(hand: HandFrame): Float {
        var best = 0f
        for ((a, b) in PALM_BONES) {
            val (ax, ay) = plane(hand[a])
            val (bx, by) = plane(hand[b])
            best = maxOf(best, hypot(ax - bx, ay - by))
        }
        return best
    }

    /** One line for the log and the setup screen. */
    fun line(): String = "camera %.0f by %.0f degrees, yaw %.1f, pitch %.1f, roll %.1f, offset %.2f, %.2f, %.2f m".fmt(
        hfovDeg, vfovDeg, yawDeg, pitchDeg, rollDeg, offset.x, offset.y, offset.z,
    )

    companion object {
        const val DEFAULT_DEPTH_M = 0.45f

        /** 0.5.10: the Aura camera's estimated width, degrees (80 degrees diagonal of a 4:3 sensor). */
        const val AURA_HFOV_DEG = 68f

        /** 0.5.0 to 0.5.9's width, for comparison. */
        const val OLD_HFOV_DEG = 80f

        /** Wrist to index knuckle, wrist to pinky knuckle, index to pinky knuckle. */
        val PALM_BONES = listOf(
            HandFrame.WRIST to HandFrame.INDEX_MCP,
            HandFrame.WRIST to HandFrame.PINKY_MCP,
            HandFrame.INDEX_MCP to HandFrame.PINKY_MCP,
        )

        /** An adult hand's palm bones, metres, for frames without world landmarks. */
        val TYPICAL_BONES_M = listOf(0.085f, 0.075f, 0.065f)

        fun verticalFor(hfovDeg: Float, aspect: Float): Float =
            2f * atan(tan(hfovDeg * DEG / 2f) / aspect) / DEG
    }
}

/** Where a hand ray starts (0.5.1). */
enum class RayOrigin {
    /**
     * The glasses camera, which sits by the eyes: the ray goes from the camera through the hand's
     * pinch point, so the cursor is where the hand is seen, 1:1 in angle (0.4.8's mapping, which
     * spread about 56 by 36 camera degrees over the 61 by 34 degree display). The default.
     */
    CAMERA,

    /** 0.5.0's body anchored ray, from between the wrist and a guessed shoulder. An option, off by default. */
    SHOULDER,
}

/**
 * The hand ray (0.5.0, reworked in 0.5.1).
 *
 * [origin] CAMERA (the default since 0.5.1): from the camera through [aim] on the hand, the
 * point between the thumb and index tips, with no depth or shoulder guess in it. Mike's 0.5.0
 * log had the shoulder ray 10 to 62
 * degrees above the hand and swung inward, worst where the size based depth sat on its clamps.
 *
 * [origin] SHOULDER, a shoulder based pointer as some headsets use, kept as
 * an option: from a point between the wrist and an estimated shoulder, through [aim].
 * - The shoulder is [shoulderOut] to the hand's side of the neck, [shoulderDown] below the
 *   head centre and [shoulderBack] behind it, turned by a neck yaw that follows the head's yaw
 *   only beyond [neckDeadzoneDeg], so turning the head to look does not swing the shoulders.
 *   0.5.1: 6, 8 and 2 cm instead of 15, 20 and 5.
 * - The origin is [blend] of the way from the wrist to the shoulder: 0.5.0 used 0.53; 0.5.1
 *   starts at the shoulder itself (1), since with a fixed distance an origin near the wrist and
 *   a hand point at the same depth give a ray across the picture, not into it.
 * - The hand's distance is [fixedDepthM] (0.45 m) until the camera's field of view is measured;
 *   null uses the size estimate ([CameraModel.depthOf]), smoothed with its own One Euro filter
 *   ([depthMinCutoffHz]). The origin is smoothed per axis ([originMinCutoffHz]).
 * - [eyeViewAtM]: the cursor goes where the ray is that far out, seen from the eyes, so the
 *   shoulder's parallax does not lift it (null: the ray's own direction, as in 0.5.0).
 *
 * The direction itself is smoothed by the recognizer's cursor filter, on display pixels (about
 * 30 per degree), and frozen by its pinch lock.
 */
data class HandRaySettings(
    val origin: RayOrigin = RayOrigin.CAMERA,
    /**
     * 0.5.2: the stable pinch point (was the pinch point, which moved as the fingers closed).
     * 0.5.9: the steady pinch point, the same spot with about 0.4 of the jitter ([SteadyAnchor]).
     */
    val aim: AimPoint = AimPoint.STEADY_PINCH,
    val shoulderOut: Float = 0.06f,
    val shoulderDown: Float = 0.08f,
    val shoulderBack: Float = 0.02f,
    val blend: Float = 1f,
    val fixedDepthM: Float? = CameraModel.DEFAULT_DEPTH_M,
    val eyeViewAtM: Float? = 1.5f,
    val neckDeadzoneDeg: Float = 25f,
    val depthMinCutoffHz: Float = 0.5f,
    val originMinCutoffHz: Float = 1.0f,
) {
    init {
        require(blend in 0f..1f) { "Blend is between 0 (wrist) and 1 (shoulder)" }
        require(neckDeadzoneDeg >= 0f && depthMinCutoffHz > 0f && originMinCutoffHz > 0f) { "Settings must be positive" }
        require(fixedDepthM == null || fixedDepthM > 0f) { "A fixed hand distance must be positive" }
        require(eyeViewAtM == null || eyeViewAtM > 0f) { "The eye view distance must be positive" }
    }

    /** The shoulder of [side] in a head frame turned by the neck only, metres. */
    fun shoulder(side: HandSide): Vec3 =
        Vec3(if (side == HandSide.RIGHT) shoulderOut else -shoulderOut, -shoulderDown, shoulderBack)

    /** For the log. */
    fun line(): String = when (origin) {
        RayOrigin.CAMERA -> "camera ray through the ${aim.label}"
        RayOrigin.SHOULDER -> "shoulder ray through the ${aim.label} (shoulder %.2f, %.2f, %.2f m, blend %.2f, hand %s)".fmt(
            shoulderOut, shoulderDown, shoulderBack, blend, fixedDepthM?.let { "%.2f m".fmt(it) } ?: "distance from its size",
        )
    }

    companion object {
        /** The shoulder ray option with the 0.5.1 values. */
        val SHOULDER = HandRaySettings(origin = RayOrigin.SHOULDER)
    }
}

/** One hand ray in the head frame at the frame's capture time. */
data class HandRayResult(
    val origin: Vec3,
    val dir: Vec3,
    val depthM: Float,
    val shoulder: Vec3,
    /** 0.5.10: [CameraModel.palmSize] of the frame, 0 when not known. */
    val apparentSize: Float = 0f,
) {
    val angles: Angles get() = Angles.of(dir)

    fun line(): String = "ray %.1f, %.1f deg from %.2f, %.2f, %.2f m, hand %.2f m away".fmt(
        angles.yawDeg, angles.pitchDeg, origin.x, origin.y, origin.z, depthM,
    )
}

/** Builds one hand's ray frame by frame, with its depth and origin filters and neck yaw. */
class HandRayBuilder(
    var side: HandSide,
    var camera: CameraModel = CameraModel(),
    val settings: HandRaySettings = HandRaySettings(),
) {
    private val depth = OneEuroFilter(settings.depthMinCutoffHz, 0f, 1f)
    private val ox = OneEuroFilter(settings.originMinCutoffHz, 0f, 1f)
    private val oy = OneEuroFilter(settings.originMinCutoffHz, 0f, 1f)
    private val oz = OneEuroFilter(settings.originMinCutoffHz, 0f, 1f)
    private var neckYaw: Float? = null

    /** 0.5.9: the steady pinch point's smoothed offsets ([AimPoint.STEADY_PINCH]). */
    private val anchor = SteadyAnchor()

    /** The last ray built, or null after [reset]. */
    var last: HandRayResult? = null
        private set

    /**
     * The ray for [hand] captured at [nowMs]. [head] is the head pose at that moment when head
     * tracking is live: only its yaw is used, for the neck. Without it the shoulder moves with
     * the head, which is fine while Mike faces forward.
     */
    fun build(hand: HandFrame, nowMs: Long, head: Quat? = null): HandRayResult {
        val aimAt = if (settings.aim == AimPoint.STEADY_PINCH) anchor.point(hand, nowMs) else hand.point(settings.aim)
        if (settings.origin == RayOrigin.CAMERA) {
            // 0.5.1: no depth or shoulder in it: where the camera, by the eyes, sees the pinch.
            val dir = camera.direction(aimAt)
            return HandRayResult(camera.offset, dir, settings.fixedDepthM ?: CameraModel.DEFAULT_DEPTH_M, camera.offset, camera.palmSize(hand)).also { last = it }
        }
        val z = settings.fixedDepthM ?: depth.filter(camera.depthOf(hand), nowMs)
        val wrist = camera.toHead(hand[HandFrame.WRIST], z)
        val aim = camera.toHead(aimAt, z)
        val shoulder = shoulderInHead(head)
        val blended = wrist + (shoulder - wrist) * settings.blend
        val origin = Vec3(ox.filter(blended.x, nowMs), oy.filter(blended.y, nowMs), oz.filter(blended.z, nowMs))
        val toAim = aim - origin
        val body = if (toAim.length < 1e-4f) Vec3.FORWARD else toAim.normalized()
        val eyeAt = settings.eyeViewAtM
            ?: return HandRayResult(origin, body, z, shoulder, camera.palmSize(hand)).also { last = it }
        // 0.5.1: where the body ray reaches the panels, seen from the eyes (the camera), so the
        // shoulder's parallax does not read as aiming high.
        val seen = (origin + body * eyeAt) - camera.offset
        val dir = if (seen.length < 1e-4f) body else seen.normalized()
        return HandRayResult(camera.offset, dir, z, shoulder, camera.palmSize(hand)).also { last = it }
    }

    /** The shoulder in the head frame, turned by the neck's yaw when the head pose is known. */
    fun shoulderInHead(head: Quat?): Vec3 {
        val local = settings.shoulder(side)
        if (head == null) return local
        val yaw = Angles.of(head.forward).yawDeg
        val neck = neckYaw?.let { followNeck(it, yaw, settings.neckDeadzoneDeg) } ?: yaw
        neckYaw = neck
        val world = Quat.fromYawPitch(neck, 0f).rotate(local)
        return head.conjugate().rotate(world)
    }

    fun reset() {
        depth.reset()
        anchor.reset()
        ox.reset()
        oy.reset()
        oz.reset()
        neckYaw = null
        last = null
    }

    companion object {
        /** The neck's new yaw: it stays put while the head is within [deadzone] of it, else trails at that distance. */
        fun followNeck(neck: Float, head: Float, deadzone: Float): Float {
            val d = wrapDegrees(head - neck)
            return when {
                d > deadzone -> wrapDegrees(neck + d - deadzone)
                d < -deadzone -> wrapDegrees(neck + d + deadzone)
                else -> neck
            }
        }
    }
}

private fun String.fmt(vararg args: Any?) = String.format(Locale.ROOT, this, *args)
