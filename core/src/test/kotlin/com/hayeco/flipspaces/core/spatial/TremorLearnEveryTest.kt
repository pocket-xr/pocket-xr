package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 1.2.3: the worn tremor fit learns from every 4th sample ([TremorPredictor.learnEvery]) to cut the head thread's
 * CPU. On a worn head (sway, an 8 Hz tremor, gyro noise) its predictions stay close to learning from every sample.
 */
class TremorLearnEveryTest {
    private fun run(learnEvery: Int): List<Double> {
        val p = TremorPredictor(blend = true, learnEvery = learnEvery)
        val r = Random(7)
        val out = mutableListOf<Double>()
        var t = 1_000_000_000L
        for (i in 0 until 8000) {
            val s = i / 1000.0
            val w = 0.02 * sin(2 * PI * 0.5 * s) + 0.03 * sin(2 * PI * 8.0 * s) + 0.0016 * r.nextGaussian()
            p.add(t, Vec3(w.toFloat(), (0.5 * w).toFloat(), 0f))
            if (i >= 4000 && i % 50 == 0) p.turn(36.0)?.let { out += it.x.toDouble() }
            t += 1_000_000L
        }
        return out
    }

    @Test
    fun everyFourthSampleKeepsThePrediction() {
        val every = run(1)
        val fourth = run(4)
        val scale = every.maxOf { abs(it) }
        val worst = every.indices.maxOf { abs(every[it] - fourth[it]) }
        assertTrue("worst difference ${worst / scale} of the largest turn", worst < 0.05 * scale)
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }
}
