package com.hayeco.flipspaces.core.spatial

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI

/**
 * 1.4.0 (docs/6DOF-PLAN.md, step 1): one sample from a 6DoF source. [position] is the head's position in metres in the
 * Space's axes (x right, y up, -z forward), from wherever the source's own origin is. [stable] is false while the source
 * says its tracking is unsure (just after start, after a sudden move, or lost): such a sample's position is not used.
 */
data class PositionSample(val tNs: Long, val position: Vec3, val stable: Boolean = true)

/**
 * 1.4.0: turns a 6DoF source's samples into the head position the Space draws with, so the picture never jumps.
 *
 * - The first stable sample is the Space's origin: the Space starts where the head is, as the 3DoF Space starts facing
 *   ahead.
 * - While the source is unsure, or between samples, the last position holds. The head keeps turning (3DoF), only the
 *   position waits.
 * - When stable samples come back after a hold, the position carries on from where it held, and the difference to the
 *   source's position eases out over [easeMs]. The room never jumps; it slides back into place.
 * - A sample that is not finite, or farther than [maxM] from the origin, counts as unsure.
 * - [recentre] makes the current position the origin.
 * - With no stable sample for [staleMs] the source is not [live]; after [dropMs] there is no position at all (null).
 *
 * Not thread safe: callers synchronize on it.
 */
class PositionTrack(
    val staleMs: Long = 500L,
    val dropMs: Long = 2_000L,
    val easeMs: Long = 1_000L,
    val maxM: Float = 5f,
    val maxPredictMs: Long = 30L,
) {
    private var offset = Vec3.ZERO
    private var ease = Vec3.ZERO
    private var easeFromNs = 0L
    private var lastRaw: Vec3? = null
    private var lastStableNs = Long.MIN_VALUE
    private var lastSampleNs = Long.MIN_VALUE
    private var held: Vec3? = null
    private var holding = false
    private var velocity = Vec3.ZERO
    private var prevRaw: Vec3? = null
    private var prevNs = 0L

    /** How many times a hold ended with an ease back (for the logs and the Glasses report). */
    var resumes = 0
        private set

    /** How many unsure samples came in (for the logs). */
    var unsure = 0
        private set

    fun push(s: PositionSample) {
        lastSampleNs = s.tNs
        val p = s.position
        val ok = s.stable && p.x.isFinite() && p.y.isFinite() && p.z.isFinite() &&
            (lastRaw == null || ((p + offset).length <= maxM))
        if (!ok) {
            unsure++
            if (!holding) {
                held = output(s.tNs, predict = false)
                holding = held != null
            }
            velocity = Vec3.ZERO
            prevRaw = null
            return
        }
        if (lastRaw == null) offset = -p
        if (holding) {
            val h = held ?: Vec3.ZERO
            ease = h - (p + offset)
            easeFromNs = s.tNs
            holding = false
            held = null
            resumes++
            prevRaw = null
        }
        val before = prevRaw
        if (before != null && s.tNs > prevNs) {
            val v = (p - before) * (1e9f / (s.tNs - prevNs))
            velocity = if (v.length > MAX_SPEED_M_S) v * (MAX_SPEED_M_S / v.length) else v
        } else {
            velocity = Vec3.ZERO
        }
        prevRaw = p
        prevNs = s.tNs
        lastRaw = p
        lastStableNs = s.tNs
    }

    /** Whether a stable sample came in the last [staleMs] before [nowNs]. */
    fun live(nowNs: Long): Boolean = lastStableNs != Long.MIN_VALUE && nowNs - lastStableNs <= staleMs * 1_000_000L

    /**
     * The position to draw with at [tNs]: predicted from the newest stable sample by at most [maxPredictMs] while the
     * source is live, held while it is unsure, null with no source or after [dropMs] without any sample.
     */
    fun positionAt(tNs: Long): Vec3? {
        if (lastRaw == null) return null
        val newest = maxOf(lastSampleNs, lastStableNs)
        if (tNs - newest > dropMs * 1_000_000L) return null
        if (holding) return held
        return output(tNs, predict = live(tNs))
    }

    /** The current position becomes the origin. */
    fun recentre() {
        val raw = lastRaw ?: return
        offset = -raw
        ease = Vec3.ZERO
        if (holding) held = Vec3.ZERO
    }

    /** Forget everything: the next stable sample is a new origin. */
    fun reset() {
        offset = Vec3.ZERO
        ease = Vec3.ZERO
        lastRaw = null
        lastStableNs = Long.MIN_VALUE
        lastSampleNs = Long.MIN_VALUE
        held = null
        holding = false
        velocity = Vec3.ZERO
        prevRaw = null
    }

    private fun output(tNs: Long, predict: Boolean): Vec3? {
        val raw = lastRaw ?: return null
        val aheadS = if (predict) ((tNs - lastStableNs).coerceIn(0L, maxPredictMs * 1_000_000L)) / 1e9f else 0f
        val e = if (ease == Vec3.ZERO) Vec3.ZERO else {
            val k = exp(-((tNs - easeFromNs).coerceAtLeast(0L) / 1e6f) / (easeMs / 3f))
            if (k < 1e-3f) { ease = Vec3.ZERO; Vec3.ZERO } else ease * k
        }
        return raw + offset + e + velocity * aheadS
    }

    companion object {
        /** Faster than anyone moves their head; a faster step is a glitch, not a move. */
        const val MAX_SPEED_M_S = 3f
    }
}

/**
 * 1.4.0: whether a 6DoF source is live, for [FollowMode.SPACE_LOCKED]'s [FollowMode.available]. Set by the app's head
 * tracker; false in tests and on every 3DoF glasses.
 */
object SixDof {
    @Volatile var live: Boolean = false
}

/**
 * 1.4.0: scripted head paths for testing 6DoF without 6DoF glasses (the debug build's position replay, and tests). Each
 * gives the position at a time in seconds from its start, or a sample marked unsure where the script loses tracking.
 */
enum class PositionScenario(val seconds: Float) {
    /** Side to side, 20 cm each way, every 4 seconds. */
    SWAY(8f) {
        override fun at(s: Float) = PositionSample(0L, Vec3(0.2f * sin(2f * PI.toFloat() * s / 4f), 0f, 0f))
    },
    /** Lean in 30 cm and back, every 6 seconds. */
    LEAN(12f) {
        override fun at(s: Float) = PositionSample(0L, Vec3(0f, 0f, -0.15f * (1f - kotlin.math.cos(2f * PI.toFloat() * s / 6f))))
    },
    /** A 1 m square on the floor, 4 seconds a side. */
    SQUARE(16f) {
        override fun at(s: Float): PositionSample {
            val t = (s % 16f) / 4f
            val f = t - t.toInt()
            val p = when (t.toInt()) {
                0 -> Vec3(f, 0f, 0f)
                1 -> Vec3(1f, 0f, -f)
                2 -> Vec3(1f - f, 0f, -1f)
                else -> Vec3(0f, 0f, -1f + f)
            }
            return PositionSample(0L, p)
        }
    },
    /** Sway, with tracking lost from 3 to 4.5 seconds while the head keeps moving: the hold and the ease back. */
    LOST(8f) {
        override fun at(s: Float) = SWAY.at(s).copy(stable = s < 3f || s >= 4.5f)
    };

    abstract fun at(s: Float): PositionSample

    companion object {
        fun named(name: String?): PositionScenario? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
