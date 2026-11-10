package com.hayeco.flipspaces.pcvr

import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3

/** The latest state of each Joy-Con, from the panel service's thread, read on the GL thread. */
class PcVrPads {
    @Volatile var streaming = false
    private val lock = Any()
    private val sampleNs = LongArray(2)
    private val state = Array(2) { FloatArray(13) }

    fun store(hand: Int, ns: Long, p: Vec3, q: Quat, w: Vec3, v: Vec3) {
        if (hand !in 0..1) return
        synchronized(lock) {
            sampleNs[hand] = ns
            val s = state[hand]
            s[0] = q.x; s[1] = q.y; s[2] = q.z; s[3] = q.w
            s[4] = p.x; s[5] = p.y; s[6] = p.z
            s[7] = w.x; s[8] = w.y; s[9] = w.z
            s[10] = v.x; s[11] = v.y; s[12] = v.z
        }
    }

    /**
     * Both hands at [targetNs] into [out] (14 floats each, as AlvrBridge.sendTracking takes): carried forward by
     * their turn and speed, at most [PcVrRenderer.MAX_HAND_AHEAD_NS] past the newest sample (a guess beyond that does more harm),
     * in the play space with the eyes at [eyeHeight]. A hand silent for [PcVrRenderer.HAND_STALE_NS] is not sent.
     */
    fun at(targetNs: Long, nowNs: Long, eyeHeight: Float, out: FloatArray) {
        synchronized(lock) {
            for (h in 0..1) {
                val o = 14 * h
                val ns = sampleNs[h]
                if (ns == 0L || nowNs - ns > PcVrRenderer.HAND_STALE_NS) {
                    out[o] = 0f
                    continue
                }
                val s = state[h]
                val dt = (minOf(targetNs - ns, PcVrRenderer.MAX_HAND_AHEAD_NS).coerceAtLeast(0L)) / 1e9f
                val q = Quat(s[3], s[0], s[1], s[2])
                val turn = Quat.fromGyro(Vec3(s[7], s[8], s[9]), dt)
                val r = (turn * q).normalized()
                out[o] = 1f
                out[o + 1] = r.x; out[o + 2] = r.y; out[o + 3] = r.z; out[o + 4] = r.w
                out[o + 5] = s[4] + s[10] * dt
                out[o + 6] = s[5] + s[11] * dt + eyeHeight
                out[o + 7] = s[6] + s[12] * dt
                for (k in 0..5) out[o + 8 + k] = s[7 + k]
            }
        }
    }
}
