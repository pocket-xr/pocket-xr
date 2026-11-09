package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import kotlin.math.sqrt

/**
 * A tracked hand as OpenXR's 26 joints (XR_EXT_hand_tracking's order and axes), for SteamVR through ALVR: each joint's
 * position in the head frame (x right, y up, z back, metres) and its orientation, -z along the bone toward the
 * fingertip and +y out of the back of the hand.
 *
 * From MediaPipe's 21 points: the image points place each joint on its line of sight from the camera ([CameraModel]),
 * at the hand's distance ([CameraModel.depthOf]) plus each point's own depth from the world landmarks, so the skeleton
 * sits where the camera saw it. MediaPipe has no metacarpal bases for the four fingers; they are put a fifth of the
 * way from the wrist to each knuckle. The palm joint sits between the wrist and the middle knuckle.
 */
object HandSkeleton {
    const val JOINTS = 26

    /** Floats per joint in [fill]'s output: orientation x, y, z, w, then position x, y, z. */
    const val STRIDE = 7

    // OpenXR joint indices.
    private const val PALM = 0
    private const val WRIST = 1

    /** OpenXR joint to MediaPipe point, for joints that have one (-1: placed between others). */
    private val FROM_MEDIAPIPE = intArrayOf(
        -1, 0, // palm, wrist
        1, 2, 3, 4, // thumb metacarpal (MediaPipe's CMC), proximal (MCP), distal (IP), tip
        -1, 5, 6, 7, 8, // index metacarpal, proximal (MCP), intermediate (PIP), distal (DIP), tip
        -1, 9, 10, 11, 12,
        -1, 13, 14, 15, 16,
        -1, 17, 18, 19, 20,
    )

    /** Each finger's OpenXR joints from base to tip. */
    private val FINGERS = arrayOf(
        intArrayOf(2, 3, 4, 5),
        intArrayOf(6, 7, 8, 9, 10),
        intArrayOf(11, 12, 13, 14, 15),
        intArrayOf(16, 17, 18, 19, 20),
        intArrayOf(21, 22, 23, 24, 25),
    )

    private const val METACARPAL_AT = 0.2f

    /**
     * The hand's 26 joints into [out] ([JOINTS] x [STRIDE] floats, head frame). False when the frame can't give a
     * skeleton (no world landmarks).
     */
    fun fill(hand: HandFrame, camera: CameraModel, out: FloatArray): Boolean {
        val world = hand.world ?: return false
        require(out.size >= JOINTS * STRIDE)
        val depth = camera.depthOf(hand)
        // MediaPipe's world z grows away from the camera; each point's own depth around the hand's.
        val z0 = world[HandFrame.WRIST].z
        val mp = Array(HandFrame.COUNT) { i -> camera.toHead(hand[i], depth + (world[i].z - z0)) }

        val p = arrayOfNulls<Vec3>(JOINTS)
        for (j in 0 until JOINTS) FROM_MEDIAPIPE[j].takeIf { it >= 0 }?.let { p[j] = mp[it] }
        val wrist = mp[HandFrame.WRIST]
        for (f in 1..4) {
            val knuckle = p[FINGERS[f][1]]!!
            p[FINGERS[f][0]] = wrist + (knuckle - wrist) * METACARPAL_AT
        }
        p[PALM] = (wrist + mp[HandFrame.MIDDLE_MCP]) * 0.5f

        // The back of the hand: up the palm crossed with across the knuckles (index to little finger) points out of the
        // left hand's back and the right hand's palm, so the right hand's is flipped. (A right hand seen from its back,
        // fingers up: index on the left, so across is +x, up is +y, and y cross x is -z, away from the back.)
        val across = mp[HandFrame.PINKY_MCP] - mp[HandFrame.INDEX_MCP]
        val along = mp[HandFrame.MIDDLE_MCP] - wrist
        val right = hand.rightHand ?: true
        var back = (along cross across).normalized()
        if (right) back = -back

        put(out, PALM, p[PALM]!!, frame(along, back))
        put(out, WRIST, wrist, frame(along, back))
        for (finger in FINGERS) {
            for (k in finger.indices) {
                val j = finger[k]
                // Each joint points at the next; a tip keeps its last bone's direction.
                val dir = if (k + 1 < finger.size) p[finger[k + 1]]!! - p[j]!! else p[j]!! - p[finger[k - 1]]!!
                put(out, j, p[j]!!, frame(dir, back))
            }
        }
        return true
    }

    /** -z along [forward], +y as near [up] as is square to it. */
    private fun frame(forward: Vec3, up: Vec3): Quat {
        val z = (-forward).normalized()
        var x = (up cross z)
        if (x.length < 1e-5f) x = (Vec3(0f, 1f, 0f) cross z)
        x = x.normalized()
        val y = z cross x
        return fromBasis(x, y, z)
    }

    /** The rotation whose columns are [x], [y], [z]. */
    fun fromBasis(x: Vec3, y: Vec3, z: Vec3): Quat {
        val trace = x.x + y.y + z.z
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            Quat(0.25f * s, (y.z - z.y) / s, (z.x - x.z) / s, (x.y - y.x) / s)
        } else if (x.x > y.y && x.x > z.z) {
            val s = sqrt(1f + x.x - y.y - z.z) * 2f
            Quat((y.z - z.y) / s, 0.25f * s, (y.x + x.y) / s, (z.x + x.z) / s)
        } else if (y.y > z.z) {
            val s = sqrt(1f + y.y - x.x - z.z) * 2f
            Quat((z.x - x.z) / s, (y.x + x.y) / s, 0.25f * s, (z.y + y.z) / s)
        } else {
            val s = sqrt(1f + z.z - x.x - y.y) * 2f
            Quat((x.y - y.x) / s, (z.x + x.z) / s, (z.y + y.z) / s, 0.25f * s)
        }.normalized()
    }

    private fun put(out: FloatArray, joint: Int, at: Vec3, q: Quat) {
        val o = joint * STRIDE
        out[o] = q.x; out[o + 1] = q.y; out[o + 2] = q.z; out[o + 3] = q.w
        out[o + 4] = at.x; out[o + 5] = at.y; out[o + 6] = at.z
    }
}
