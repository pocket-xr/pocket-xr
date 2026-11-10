package com.hayeco.flipspaces.openxr

import android.util.Log
import com.hayeco.flipspaces.core.spatial.DrawSmoothing
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.spatial.HeadPositionFeed
import com.hayeco.flipspaces.spatial.HeadTracker
import kotlin.math.atan2
import kotlin.math.sqrt
import org.freedesktop.monado.ipc.PocketFeed

/**
 * Streams the glasses' head pose to Monado while a game is up. Monado keeps the poses and works out the pose for each
 * frame's own time, so each one carries an angular velocity for it to look ahead with. That velocity comes from two
 * predicted poses a short step apart, which uses the predictor's smoothed gyro rate.
 *
 * Both sides use the same axes (y up, -z forward) and the same clock (System.nanoTime is CLOCK_MONOTONIC), so nothing
 * is converted.
 */
object OpenXrHeadFeed {
    private const val TAG = "PocketXrOpenXr"
    private const val PERIOD_MS = 3L
    private const val STEP_NS = 8_000_000L

    @Volatile private var thread: Thread? = null

    /**
     * Mike, 7 November 2026, Beat Saber: "a bit shaky ... like a small earthquake". The game got the raw predicted
     * head, while the Space draws through a One Euro filter (0.5.64) for the same tremor. Games get one too, opening
     * faster with a turn than the Space's so a turn trails by under 2 degrees, and the turn rate Monado looks ahead
     * with fades out below [REST_RATE_RAD_S], where it is mostly tremor.
     */
    @Volatile var smooth = true
    private val smoothing = DrawSmoothing(minCutoffHz = 1.0f, beta = 0.08f)
    private const val REST_RATE_RAD_S = 0.26f

    /** A head for testing with nobody on the glasses, in place of [HeadTracker]. */
    @Volatile var testPose: ((Long) -> Quat?)? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "openxr-head-feed").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "head feed on")
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        Log.i(TAG, "head feed off")
    }

    private fun run() {
        val me = Thread.currentThread()
        try {
            while (thread === me) {
                val now = System.nanoTime()
                val test = testPose
                val pose = test?.invoke(now) ?: HeadTracker.gamePose(now)
                val ahead = test?.invoke(now + STEP_NS) ?: HeadTracker.gamePose(now + STEP_NS)
                if (pose == null) {
                    smoothing.free()
                } else if (smooth && test == null) {
                    val steady = smoothing.update(pose, now)
                    feed(now, steady, ahead?.let { smoothing.apply(it) }, gate = true)
                } else {
                    smoothing.free()
                    feed(now, pose, ahead, gate = false)
                }
                Thread.sleep(PERIOD_MS)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        }
    }

    private fun feed(now: Long, pose: Quat, ahead: Quat?, gate: Boolean) {
        var dx = 0f
        var dy = 0f
        var dz = 0f
        if (ahead != null) {
            var turn = ahead * pose.conjugate()
            if (turn.w < 0f) turn = Quat(-turn.w, -turn.x, -turn.y, -turn.z)
            val sinHalf = sqrt(turn.x * turn.x + turn.y * turn.y + turn.z * turn.z)
            if (sinHalf > 1e-6f) {
                val speed = 2f * atan2(sinHalf, turn.w) / (STEP_NS / 1e9f)
                val keep = if (gate) speed * speed / (speed * speed + REST_RATE_RAD_S * REST_RATE_RAD_S) else 1f
                val rate = speed * keep / sinHalf
                dx = turn.x * rate
                dy = turn.y * rate
                dz = turn.z * rate
            }
        }
        // 1.4.0 (docs/6DOF-PLAN.md): with a 6DoF source the game gets the head's position too, with its velocity from two
        // positions a step apart. A runtime library from before 1.4.0 has no nativePose6: then rotation only, as before.
        val at = if (pose6) HeadPositionFeed.positionAt(now) else null
        if (at != null) {
            val ahead = HeadPositionFeed.positionAt(now + STEP_NS) ?: at
            val v = (ahead - at) * (1e9f / STEP_NS)
            val tracked = com.hayeco.flipspaces.core.spatial.SixDof.live
            try {
                PocketFeed.nativePose6(now, at.x, at.y, at.z, pose.x, pose.y, pose.z, pose.w, dx, dy, dz,
                    if (tracked) v.x else 0f, if (tracked) v.y else 0f, if (tracked) v.z else 0f, tracked)
                return
            } catch (e: UnsatisfiedLinkError) {
                pose6 = false
                Log.w(TAG, "the OpenXR runtime library has no 6DoF head pose (built before 1.4.0): games get rotation only")
            }
        }
        PocketFeed.nativePose(now, pose.x, pose.y, pose.z, pose.w, dx, dy, dz)
    }

    /** False once the runtime library turned out to have no [PocketFeed.nativePose6]. */
    @Volatile private var pose6 = true
}
