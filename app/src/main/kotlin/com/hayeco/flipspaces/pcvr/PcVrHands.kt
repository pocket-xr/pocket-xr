package com.hayeco.flipspaces.pcvr

import com.hayeco.flipspaces.core.hands.CameraModel
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandSkeleton

/**
 * The glasses' tracked hands for SteamVR: each hand's 26 joints in the head frame at the moment the camera saw it
 * ([HandSkeleton]), kept until the renderer turns them into the room with the head pose of that moment. A hand holding a
 * Joy-Con is sent as the Joy-Con instead; a free hand is sent as a hand.
 */
object PcVrHands {
    private val camera = CameraModel()
    private val lock = Any()
    private val joints = Array(2) { FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE) }
    private val seenAtMs = LongArray(2)
    private val scratch = FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE)

    /** From the hand feed, while PC VR is open: [capturedAtMs] on the uptime clock. Left is 0, right 1. */
    fun store(frames: List<HandFrame>, capturedAtMs: Long) {
        for (f in frames) {
            val side = if (f.rightHand == false) 0 else 1
            if (!HandSkeleton.fill(f, camera, scratch)) continue
            synchronized(lock) {
                scratch.copyInto(joints[side])
                seenAtMs[side] = capturedAtMs
            }
        }
    }

    /** Hand [side]'s head-frame joints into [out] and when they were seen (uptime ms), or 0 if none yet. */
    fun latest(side: Int, out: FloatArray): Long = synchronized(lock) {
        joints[side].copyInto(out)
        seenAtMs[side]
    }
}
