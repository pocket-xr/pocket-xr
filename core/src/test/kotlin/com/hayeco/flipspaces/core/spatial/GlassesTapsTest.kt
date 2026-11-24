package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GlassesTapsTest {
    private val rate = 1000.0
    private val gravity = Vec3(0f, 9.81f, 0f)

    private fun run(seconds: Double, sensitivity: Int = 100, accelAt: (Double) -> Vec3, gyroAt: (Double) -> Vec3 = { Vec3.ZERO }): List<Int> {
        val taps = GlassesTaps().also { it.sensitivity = sensitivity }
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

    /** Still glasses with a sharp jolt of [g] g along z for [ms] ms at each of [at] (rising within 1 ms). */
    private fun jolts(g: Float, ms: Double, vararg at: Double): (Double) -> Vec3 = { t ->
        if (at.any { t >= it && t < it + ms / 1000 }) Vec3(0f, 9.81f, g * 9.81f) else gravity
    }

    @Test fun softTapsLikePierresCount() {
        // 0.4 g taps about 320 ms apart, as on his XBX: two recentre, three switch
        assertEquals(listOf(2), run(3.0, accelAt = jolts(0.4f, 20.0, 1.0, 1.32)))
        assertEquals(listOf(3), run(3.0, accelAt = jolts(0.4f, 20.0, 1.0, 1.32, 1.64)))
    }

    @Test fun firmLongerTapsCount() {
        // 0.7.7 left out firm taps of about 100 ms
        assertEquals(listOf(3), run(3.0, accelAt = jolts(2.5f, 100.0, 1.0, 1.45, 1.9)))
    }

    @Test fun oneOrFourDoNothingAndTooFarApartIsSingle() {
        assertEquals(emptyList<Int>(), run(3.0, accelAt = jolts(0.6f, 20.0, 1.0)))
        assertEquals(emptyList<Int>(), run(4.0, accelAt = jolts(0.6f, 20.0, 1.0, 1.4, 1.8, 2.2)))
        assertEquals(emptyList<Int>(), run(4.0, accelAt = jolts(0.6f, 20.0, 1.0, 2.0)))
    }

    @Test fun lowSensitivityNeedsFirmerTaps() {
        assertEquals(emptyList<Int>(), run(3.0, sensitivity = 25, accelAt = jolts(0.4f, 20.0, 1.0, 1.32)))
        assertEquals(listOf(2), run(3.0, sensitivity = 25, accelAt = jolts(1.5f, 20.0, 1.0, 1.32)))
    }

    @Test fun headMovementWalkingAndPushesAreNotTaps() {
        val turning: (Double) -> Vec3 = { Vec3(0f, 3f, 0f) }
        assertEquals(emptyList<Int>(), run(3.0, accelAt = jolts(0.6f, 20.0, 1.0, 1.32), gyroAt = turning))
        val walk: (Double) -> Vec3 = { t -> Vec3(0f, 9.81f + 0.5f * 9.81f * sin(2 * PI * 2 * t).toFloat(), 0f) }
        assertEquals(emptyList<Int>(), run(6.0, accelAt = walk))
        // a slow push: the same size, but rising over 50 ms, not within 3 ms
        val push: (Double) -> Vec3 = { t ->
            val ramp = { s: Double -> ((t - s) / 0.05).coerceIn(0.0, 1.0) * (if (t < s + 0.1) 1.0 else 0.0) }
            Vec3(0f, 9.81f, (0.6 * 9.81 * maxOf(ramp(1.0), ramp(1.32))).toFloat())
        }
        assertEquals(emptyList<Int>(), run(3.0, accelAt = push))
    }

    @Test fun everyJoltIsLogged() {
        val lines = ArrayList<String>()
        val taps = GlassesTaps { lines += it }
        val accel = jolts(0.4f, 20.0, 1.0)
        var i = 0
        while (i < 2000) { taps.feed(i / rate, Vec3.ZERO, accel(i / rate)); i++ }
        assertTrue(lines.any { it.startsWith("taps: jolt") })
    }
}
