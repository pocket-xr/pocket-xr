package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.6 (John Davis): Head scroll, a small head turn slides the row of screens that way. */
class HeadScroll146Test {
    private fun panel(id: Int, yaw: Float, w: Float = 30f) = SpacePanel(id, "$id", "$id", "$id", yawDeg = yaw, pitchDeg = 0f, widthDeg = w, heightDeg = 20f)

    private fun run(s: HeadScroll, offset: Float, seconds: Float, pitch: Float = 0f): Float {
        var total = 0f
        repeat((seconds * 90).toInt()) { total += s.step(offset, pitch, 1f / 90f) }
        return total
    }

    @Test
    fun readingTheWindowInFrontNeverScrolls() {
        val s = HeadScroll()
        assertEquals(0f, run(s, 8f, 2f), 0f)
        assertEquals(0f, run(s, -9.9f, 2f), 0f)
    }

    @Test
    fun turningRightBringsTheScreensOnTheRightToTheMiddle() {
        val s = HeadScroll()
        val moved = run(s, 20f, 1f)
        // 10 deg past the dead zone: 30 deg/s once eased in.
        assertTrue("moved $moved", moved in 22f..30f)
        assertEquals(30f, s.speedDegS, 0.5f)
        // The view turns the way the head did, so what was to the right is now in front.
        val view = HeadView.pose(Quat.IDENTITY, moved)
        assertTrue(Angles.of(view.forward).yawDeg > 0f)
    }

    @Test
    fun turningLeftScrollsLeft() {
        assertTrue(run(HeadScroll(), -20f, 1f) < -20f)
    }

    @Test
    fun aBigTurnIsCappedAndEasesIn() {
        val s = HeadScroll()
        val first = s.step(60f, 0f, 1f / 90f)
        assertTrue("no jump on the first frame: $first", first < 0.1f)
        run(s, 60f, 2f)
        assertEquals(HeadScroll.MAX_DEG_S, s.speedDegS, 0.5f)
    }

    @Test
    fun lookingBackToTheMiddleStopsSoonWithoutAJolt() {
        val s = HeadScroll()
        run(s, 25f, 1f)
        val after = run(s, 0f, 1f)
        assertTrue("coasts a little: $after", after in 0f..12f)
        assertEquals(0f, s.speedDegS, 0f)
    }

    @Test
    fun lookingDownAtThePhoneDoesNotScroll() {
        assertEquals(0f, run(HeadScroll(), 25f, 1f, pitch = -45f), 0f)
    }

    @Test
    fun badNumbersDoNothing() {
        val s = HeadScroll()
        assertEquals(0f, s.step(Float.NaN, 0f, 0.01f), 0f)
        assertEquals(0f, s.step(20f, 0f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, s.step(20f, 0f, -1f), 0f)
    }

    @Test
    fun theRowStopsWithTheLastWindowInTheMiddle() {
        val row = listOf(panel(1, -40f), panel(2, 0f), panel(3, 40f))
        assertEquals(40f, HeadScroll.clamp(30f, 25f, row), 0.01f)
        assertEquals(-40f, HeadScroll.clamp(-30f, -25f, row), 0.01f)
        assertEquals(10f, HeadScroll.clamp(0f, 10f, row), 0.01f)
    }

    @Test
    fun aViewAlreadyPastTheEndIsNotPulledBackButGoesNoFurther() {
        val row = listOf(panel(1, -40f), panel(2, 40f))
        assertEquals(60f, HeadScroll.clamp(60f, 5f, row), 0.01f)
        assertEquals(55f, HeadScroll.clamp(60f, -5f, row), 0.01f)
    }

    @Test
    fun windowsAllAroundScrollFreely() {
        val ring = (0 until 6).map { panel(it + 1, it * 60f - 150f, w = 50f) }
        assertEquals(-175f, HeadScroll.clamp(175f, 10f, ring), 0.01f)
    }

    /** Smooth Follow: the head turned [yaw] and held for [seconds], the scroll it gives with the anchor at [rate]. */
    private fun followScroll(rate: Float, yaw: Float, seconds: Float): Float {
        val follow = FollowView().apply { mode = FollowMode.SMOOTH_FOLLOW; this.rate = rate }
        val s = HeadScroll()
        var ms = 0L
        follow.update(Quat.IDENTITY, ms)
        val turned = Quat.fromYawPitch(yaw, 0f)
        var total = 0f
        repeat((seconds * 90).toInt()) {
            ms += 11
            follow.update(turned, ms)
            val a = Angles.of(follow.relative(turned)!!.forward)
            total += s.step(a.yawDeg, a.pitchDeg, 0.011f)
        }
        return total
    }

    @Test
    fun inSmoothFollowATurnScrollsThenTheScreensCatchUp() {
        val withHeadScroll = followScroll(HeadScroll.FOLLOW_RATE, 25f, 6f)
        assertTrue("scrolled $withHeadScroll", withHeadScroll in 15f..60f)
        // It stops by itself: the screens come round to the head and the turn is used up.
        assertEquals(withHeadScroll, followScroll(HeadScroll.FOLLOW_RATE, 25f, 9f), 0.5f)
        // At Smooth Follow's own rate the screens catch up too fast for a turn to scroll much: why it is slower here.
        assertTrue(followScroll(FollowView.FOLLOW_RATE, 25f, 6f) < withHeadScroll / 2f)
    }

    @Test
    fun aRowAcrossTheBackStillHasEnds() {
        val row = listOf(panel(1, 160f), panel(2, -160f))
        assertEquals(-160f, HeadScroll.clamp(-170f, 30f, row), 0.01f)
    }
}
