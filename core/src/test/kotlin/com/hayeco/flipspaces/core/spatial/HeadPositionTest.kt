package com.hayeco.flipspaces.core.spatial

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.0 (docs/6DOF-PLAN.md): the head position from a 6DoF source never jumps. */
class HeadPositionTest {
    private val ms = 1_000_000L

    private fun near(expected: Vec3, actual: Vec3?, tol: Float = 1e-3f) {
        assertNotNull(actual)
        assertEquals("x", expected.x, actual!!.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
        assertEquals("z", expected.z, actual.z, tol)
    }

    @After
    fun clear() {
        SixDof.live = false
    }

    @Test
    fun noSourceNoPosition() {
        assertNull(PositionTrack().positionAt(0L))
    }

    @Test
    fun firstStableSampleIsTheOrigin() {
        val t = PositionTrack()
        t.push(PositionSample(1000 * ms, Vec3(1f, 1.6f, 2f)))
        near(Vec3.ZERO, t.positionAt(1000 * ms))
        t.push(PositionSample(1010 * ms, Vec3(1.1f, 1.6f, 2f)))
        near(Vec3(0.1f, 0f, 0f), t.positionAt(1010 * ms))
    }

    @Test
    fun predictsAShortStepAheadOnly() {
        val t = PositionTrack(maxPredictMs = 30L)
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3(0.01f, 0f, 0f))) // 1 m/s
        near(Vec3(0.03f, 0f, 0f), t.positionAt(30 * ms))
        // Never more than 30 ms ahead of the newest sample.
        near(Vec3(0.04f, 0f, 0f), t.positionAt(200 * ms))
    }

    @Test
    fun unsureSamplesHoldThePosition() {
        val t = PositionTrack()
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3(0.2f, 0f, 0f)))
        t.push(PositionSample(20 * ms, Vec3(0.9f, 0f, 0f), stable = false))
        t.push(PositionSample(30 * ms, Vec3(1.5f, 0f, 0f), stable = false))
        val held = t.positionAt(30 * ms)
        assertNotNull(held)
        assertEquals(0.2f, held!!.x, 0.05f)
        assertEquals(2, t.unsure)
    }

    @Test
    fun aResumeCarriesOnFromTheHoldThenEasesBack() {
        val t = PositionTrack(easeMs = 1_000L)
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3.ZERO, stable = false))
        // The source comes back 40 cm away: the picture must not jump.
        t.push(PositionSample(20 * ms, Vec3(0.4f, 0f, 0f)))
        near(Vec3.ZERO, t.positionAt(20 * ms), 0.01f)
        assertEquals(1, t.resumes)
        // A second later it has slid into place (more than 95 %).
        t.push(PositionSample(1020 * ms, Vec3(0.4f, 0f, 0f)))
        assertEquals(0.4f, t.positionAt(1020 * ms)!!.x, 0.02f)
    }

    @Test
    fun aWildOrBrokenSampleCountsAsUnsure() {
        val t = PositionTrack(maxM = 5f)
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3(Float.NaN, 0f, 0f)))
        t.push(PositionSample(20 * ms, Vec3(40f, 0f, 0f)))
        near(Vec3.ZERO, t.positionAt(20 * ms))
        assertEquals(2, t.unsure)
    }

    @Test
    fun recentreMakesHereTheOrigin() {
        val t = PositionTrack()
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3(0.5f, 0f, -0.3f)))
        t.recentre()
        near(Vec3.ZERO, t.positionAt(10 * ms))
        t.push(PositionSample(20 * ms, Vec3(0.6f, 0f, -0.3f)))
        assertEquals(0.1f, t.positionAt(20 * ms)!!.x, 0.02f)
    }

    @Test
    fun resetStartsTheCountsAgain() {
        val t = PositionTrack()
        t.push(PositionSample(0L, Vec3.ZERO))
        t.push(PositionSample(10 * ms, Vec3.ZERO, stable = false))
        t.push(PositionSample(20 * ms, Vec3.ZERO))
        t.reset()
        assertEquals(0, t.unsure)
        assertEquals(0, t.resumes)
        assertNull(t.positionAt(20 * ms))
    }

    @Test
    fun liveThenStaleThenGone() {
        val t = PositionTrack(staleMs = 500L, dropMs = 2_000L)
        t.push(PositionSample(0L, Vec3.ZERO))
        assertTrue(t.live(100 * ms))
        assertFalse(t.live(600 * ms))
        assertNotNull(t.positionAt(1_500 * ms))
        assertNull(t.positionAt(2_500 * ms))
    }

    @Test
    fun spaceLockedIsAvailableOnlyWhileASourceIsLive() {
        SixDof.live = false
        assertFalse(FollowMode.SPACE_LOCKED.available)
        assertTrue(FollowMode.BODY_ANCHOR.available)
        SixDof.live = true
        assertTrue(FollowMode.SPACE_LOCKED.available)
    }

    @Test
    fun scenariosStartAtTheOriginAndLoseTrackingWhereSaid() {
        for (s in PositionScenario.entries) near(Vec3.ZERO, s.at(0f).position)
        assertFalse(PositionScenario.LOST.at(3.5f).stable)
        assertTrue(PositionScenario.LOST.at(5f).stable)
        near(Vec3(1f, 0f, -1f), PositionScenario.SQUARE.at(8f).position)
        assertEquals(PositionScenario.LEAN, PositionScenario.named("lean"))
    }

    @Test
    fun theLostScenarioNeverJumps() {
        val t = PositionTrack()
        var last: Vec3? = null
        var s = 0f
        while (s < PositionScenario.LOST.seconds) {
            val ns = (s * 1e9f).toLong()
            t.push(PositionScenario.LOST.at(s).copy(tNs = ns))
            val p = t.positionAt(ns)!!
            last?.let { assertTrue("step at $s s", (p - it).length < 0.02f) }
            last = p
            s += 0.008f
        }
    }
}
