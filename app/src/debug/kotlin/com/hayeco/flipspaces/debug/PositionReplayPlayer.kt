package com.hayeco.flipspaces.debug

import android.util.Log
import com.hayeco.flipspaces.core.spatial.PositionScenario
import com.hayeco.flipspaces.spatial.HeadPositionFeed

/**
 * Debug builds only (1.4.0, docs/6DOF-PLAN.md step 1): plays a scripted head path ([PositionScenario]) into
 * [HeadPositionFeed] at 120 samples a second, as a 6DoF source would. With it, Space locked (6DoF) becomes available and
 * the Space and OpenXR games move with the path, on an emulator or on any glasses. The head's turn still comes from the
 * glasses (or none on an emulator).
 */
object PositionReplayPlayer {
    const val SOURCE = "debug replay"
    private const val PERIOD_MS = 8L

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun play(scenario: PositionScenario, repeat: Int) {
        stop()
        val t = Thread({ run(scenario, repeat.coerceIn(1, 1000)) }, "position-replay").apply { isDaemon = true }
        thread = t
        t.start()
        Log.i(HandReplayPlayer.TAG, "position replay: ${scenario.name.lowercase()}, ${scenario.seconds} s, $repeat times")
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
    }

    private fun run(scenario: PositionScenario, repeat: Int) {
        val me = Thread.currentThread()
        val startNs = System.nanoTime()
        val totalS = scenario.seconds * repeat
        try {
            while (thread === me) {
                val now = System.nanoTime()
                val s = (now - startNs) / 1e9f
                if (s >= totalS) break
                HeadPositionFeed.push(scenario.at(s % scenario.seconds).copy(tNs = now), SOURCE)
                Thread.sleep(PERIOD_MS)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        } finally {
            HeadPositionFeed.stop(SOURCE)
            Log.i(HandReplayPlayer.TAG, "position replay ended: ${HeadPositionFeed.summary()}")
        }
    }
}
