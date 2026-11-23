package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GlassesTapsTest {
    private val rate = 1000.0
    private val gravity = Vec3(0f, 9.81f, 0f)

    /** Runs [seconds] of reports through a detector, with [accelAt] and [gyroAt] by time; returns the runs it reported. */
    private fun run(seconds: Double, accelAt: (Double) -> Vec3, gyroAt: (Double) -> Vec3 = { Vec3.ZERO }): List<Int> {
        val taps = GlassesTaps()
        val out = ArrayList<Int>()
        var i = 0
        while (i < seconds * rate) {
            val t = i / rate
            val n = taps.feed(t, gyroAt(t), accelAt(t))
            if (n != 0) out += n
            i++
        }
        return out
    }

    /** Still glasses with a 15 ms jolt of 1.5 g along z at each of [at]. */
    private fun jolts(vararg at: Double): (Double) -> Vec3 = { t ->
        if (at.any { t >= it && t < it + 0.015 }) Vec3(0f, 9.81f, 1.5f * 9.81f) else gravity
    }

    @Test fun twoTapsAreARunOfTwo() {
        assertEquals(listOf(2), run(3.0, jolts(1.0, 1.25)))
    }

    @Test fun threeTapsAreARunOfThree() {
        assertEquals(listOf(3), run(3.0, jolts(1.0, 1.25, 1.5)))
    }

    @Test fun oneTapOrFourDoNothing() {
        assertEquals(emptyList<Int>(), run(3.0, jolts(1.0)))
        assertEquals(emptyList<Int>(), run(4.0, jolts(1.0, 1.2, 1.4, 1.6)))
    }

    @Test fun tapsTooFarApartAreSingleTaps() {
        assertEquals(emptyList<Int>(), run(4.0, jolts(1.0, 2.0)))
    }

    @Test fun nothingInTheFirstHalfSecond() {
        assertEquals(emptyList<Int>(), run(2.0, jolts(0.1, 0.3)))
    }

    @Test fun tapsDuringAFastHeadTurnAreLeftOut() {
        val turning: (Double) -> Vec3 = { Vec3(0f, 3f, 0f) } // about 170 deg/s
        assertEquals(emptyList<Int>(), run(3.0, jolts(1.0, 1.25), turning))
    }

    @Test fun walkingIsNotTaps() {
        // steps: a 2 Hz bounce of 0.5 g, slow next to a tap
        val walk: (Double) -> Vec3 = { t -> Vec3(0f, 9.81f + 0.5f * 9.81f * sin(2 * PI * 2 * t).toFloat(), 0f) }
        assertEquals(emptyList<Int>(), run(6.0, walk))
    }

    @Test fun aLongPushIsNotATap() {
        val push: (Double) -> Vec3 = { t -> if (t in 1.0..1.2 || t in 1.4..1.6) Vec3(0f, 9.81f, 1.5f * 9.81f) else gravity }
        assertEquals(emptyList<Int>(), run(3.0, push))
    }
}
