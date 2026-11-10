package com.hayeco.flipspaces.spatial

import android.util.Log
import com.hayeco.flipspaces.core.spatial.PositionSample
import com.hayeco.flipspaces.core.spatial.PositionTrack
import com.hayeco.flipspaces.core.spatial.SixDof
import com.hayeco.flipspaces.core.spatial.Vec3

/**
 * 1.4.0 (docs/6DOF-PLAN.md, step 1): the head's position, beside [HeadTracker]'s turn. A 6DoF source (a glasses SDK, our
 * own tracking, or the debug build's scripted replay) pushes samples on System.nanoTime; the Space and OpenXR games read
 * the position for a frame's time. 3DoF glasses never push, so nothing changes for them: no position, and Space locked
 * stays greyed out.
 *
 * The Space uses the position only in Space locked (6DoF). Body Anchor and Smooth Follow stay rotation only.
 */
object HeadPositionFeed {
    private const val TAG = "HeadPosition"

    private val track = PositionTrack()

    /** The source pushing now, for the logs and the Glasses report; null with none. */
    @Volatile var source: String? = null
        private set

    private var samples = 0L

    /** One sample from [from]. A different source than the last one starts a new origin. */
    fun push(sample: PositionSample, from: String) {
        val wasLive: Boolean
        synchronized(track) {
            if (from != source) {
                track.reset()
                Log.i(TAG, "6DoF source: $from (was ${source ?: "none"})")
                source = from
                samples = 0
            }
            track.push(sample)
            samples++
            wasLive = SixDof.live
            SixDof.live = track.live(sample.tNs)
        }
        if (!wasLive && SixDof.live) Log.i(TAG, "6DoF live from $from: Space locked is available")
    }

    /** The position for a frame shown at [presentNs] (System.nanoTime), or null with no live or held position. */
    fun positionAt(presentNs: Long): Vec3? {
        val (p, live) = synchronized(track) { track.positionAt(presentNs) to track.live(System.nanoTime()) }
        if (SixDof.live && !live) {
            SixDof.live = false
            Log.i(TAG, "6DoF from ${source ?: "none"} not live (no stable sample for ${track.staleMs} ms): held, then rotation only")
        }
        return p
    }

    /** With the head tracker's recentre: the current position becomes the origin. */
    fun recentre() {
        synchronized(track) { track.recentre() }
    }

    /** [from] stopped: no position until a source pushes again. */
    fun stop(from: String) {
        synchronized(track) {
            if (from != source) return
            Log.i(TAG, "6DoF source $from stopped after $samples samples, ${track.unsure} unsure, ${track.resumes} eased back")
            track.reset()
            source = null
            SixDof.live = false
        }
    }

    /** One line for the Glasses report and the logs. */
    fun summary(): String = synchronized(track) {
        val s = source ?: return@synchronized "6DoF: none (3DoF only)"
        "6DoF: $s, ${if (SixDof.live) "live" else "not live"}, $samples samples, ${track.unsure} unsure, ${track.resumes} eased back"
    }
}
