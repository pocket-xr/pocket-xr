package com.hayeco.flipspaces.pcvr

import com.hayeco.flipspaces.core.hands.CameraModel
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandSkeleton
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.HandSide

/**
 * The glasses' tracked hands for SteamVR, as the Space uses them:
 * - the aim is the Space's own hand ray (filtered, calibrated, the same that moves its cursor), so pointing works as it
 *   does in the Space (8 November 2026: the raw wrist pointed "very off");
 * - a click is the Space's own pinch, sent as the trigger (ALVR's guess from finger distances did nothing);
 * - the 26 joints ([HandSkeleton]) are smoothed, and only give the fingers' shape.
 * All in the head frame of the moment the camera saw the hand; the renderer turns them into the room.
 */
object PcVrHands {
    private val camera = CameraModel()
    private val lock = Any()
    private val joints = Array(2) { FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE) }
    private val smoothed = BooleanArray(2)
    private val ray = Array(2) { FloatArray(7) }
    private val pinched = BooleanArray(2)
    private val seenAtMs = LongArray(2)
    private val scratch = FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE)

    /** Set by the renderer while hand [side] is sent as a tracked hand: its Joy-Con's buttons then stay out. */
    val active = BooleanArray(2)

    /** Weight of a new frame's joints; the rest is the last smoothed frame. */
    private const val SMOOTH = 0.45f

    /** From the hand feed, while PC VR is open: [capturedAtMs] on the uptime clock. */
    fun store(frames: List<HandFrame>, capturedAtMs: Long, statuses: Map<HandSide, HandStatus>) {
        for ((handSide, status) in statuses) {
            if (!status.present) continue
            val r = status.ray ?: continue
            val side = if (handSide == HandSide.LEFT) 0 else 1
            val frame = frames.firstOrNull { (it.rightHand == true) == (side == 1) } ?: frames.singleOrNull()
            val hasSkeleton = frame != null && HandSkeleton.fill(frame, camera, scratch)
            synchronized(lock) {
                val a = ray[side]
                a[0] = r.origin.x; a[1] = r.origin.y; a[2] = r.origin.z
                a[3] = r.dir.x; a[4] = r.dir.y; a[5] = r.dir.z; a[6] = r.depthM
                pinched[side] = status.pinched
                if (hasSkeleton) {
                    val j = joints[side]
                    if (!smoothed[side] || capturedAtMs - seenAtMs[side] > 300L) scratch.copyInto(j)
                    else for (i in j.indices) j[i] += (scratch[i] - j[i]) * SMOOTH
                    smoothed[side] = true
                }
                seenAtMs[side] = capturedAtMs
            }
        }
    }

    /**
     * Hand [side] into [skeleton] (head-frame joints) and [aim] (ray origin x y z, direction x y z, hand distance m);
     * returns when it was seen (uptime ms, 0 if never) and [pinchOut] its pinch.
     */
    fun latest(side: Int, skeleton: FloatArray, aim: FloatArray, pinchOut: BooleanArray): Long = synchronized(lock) {
        joints[side].copyInto(skeleton)
        ray[side].copyInto(aim)
        pinchOut[side] = pinched[side]
        seenAtMs[side]
    }
}
