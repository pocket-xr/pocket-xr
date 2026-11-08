package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The glasses' real refresh, from the times frames were actually shown on them, for Monado's pacer while a game has the
 * glasses (7 November 2026).
 *
 * On Android Monado's pacer gets no feedback: it keeps the phase it started with and a nominal 16.667 ms, while
 * SurfaceFlinger measures XREAL glasses at 16.66 ms. The two drift a frame apart every 30 to 40 s, and a stream ran at
 * 57 or at 37 to 46 new frames a second depending on where the phase sat. The phone's Vulkan layer reports one frozen
 * present time for Monado's own swapchain, so the times come from the Space's EGL surface on the same display instead:
 * every layer of a display is shown at the display's own refresh.
 *
 * Each shown time is a refresh. The period is the span of the kept times over the number of refreshes in it, so a
 * refresh with no frame shown costs nothing. The newest time is the phase.
 */
class VblankLock(private val nominalPeriodNs: Long, private val keep: Int = 240) {
    private val times = ArrayDeque<Long>()

    /** The newest shown time, CLOCK_MONOTONIC; 0 before any. */
    var lastNs = 0L
        private set

    /** The measured period, ns; 0 until enough refreshes were seen. */
    var periodNs = 0L
        private set

    /** Times that did not sit on a refresh, for the log. */
    var rejected = 0L
        private set

    fun add(shownNs: Long) {
        val last = times.lastOrNull()
        if (last != null) {
            val gap = shownNs - last
            if (gap <= 0L) return
            // A shown time must sit on a whole number of refreshes after the last one.
            val reference = if (periodNs > 0L) periodNs else nominalPeriodNs
            val steps = (gap.toDouble() / reference).roundToLong()
            if (steps < 1L || abs(gap - steps * reference) > reference / 4) {
                rejected++
                times.clear()
                periodNs = 0L
            } else if (steps > MAX_STEPS) {
                times.clear()
                periodNs = 0L
            }
        }
        times.addLast(shownNs)
        while (times.size > keep) times.removeFirst()
        lastNs = shownNs
        if (times.size >= MIN_TIMES) {
            val span = times.last() - times.first()
            val reference = if (periodNs > 0L) periodNs else nominalPeriodNs
            val refreshes = (span.toDouble() / reference).roundToLong()
            if (refreshes > 0L) periodNs = span / refreshes
        }
    }

    fun reset() {
        times.clear()
        lastNs = 0L
        periodNs = 0L
    }

    private companion object {
        /** Enough for a period to a few microseconds at 60 Hz. */
        const val MIN_TIMES = 30

        /** A gap of more refreshes than this is a pause: start again. */
        const val MAX_STEPS = 30L
    }
}
