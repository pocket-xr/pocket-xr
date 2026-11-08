package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.3 (issue 58): 2D while moving: flat at once on a turn or the cursor, 3D again after a still spell. */
class SideBySideMotion123Test {
    @Test
    fun stillAndHiddenIs3d() {
        val m = SideBySideMotion()
        assertFalse(m.update(2f, cursorShown = false, nowMs = 0L))
        assertFalse(m.update(2f, cursorShown = false, nowMs = 1000L))
    }

    @Test
    fun aTurnOrTheCursorIsFlatAtOnce() {
        val turn = SideBySideMotion()
        assertTrue(turn.update(40f, cursorShown = false, nowMs = 0L))
        val cursor = SideBySideMotion()
        assertTrue(cursor.update(0f, cursorShown = true, nowMs = 0L))
    }

    @Test
    fun threeDComesBackOnlyAfterAStillSpell() {
        val m = SideBySideMotion()
        m.update(40f, false, 0L)
        assertTrue(m.update(2f, false, 100L))
        assertTrue(m.update(2f, false, 100L + SideBySideMotion.STILL_MS - 1))
        assertFalse(m.update(2f, false, 100L + SideBySideMotion.STILL_MS))
    }

    @Test
    fun aSmallMoveInTheStillSpellStartsItAgain() {
        val m = SideBySideMotion()
        m.update(40f, false, 0L)
        m.update(2f, false, 100L)
        m.update(30f, false, 500L)
        assertTrue(m.update(2f, false, 600L))
        assertTrue(m.update(2f, false, 600L + SideBySideMotion.STILL_MS - 1))
        assertFalse(m.update(2f, false, 600L + SideBySideMotion.STILL_MS))
    }

    @Test
    fun aBadTurnSpeedCountsAsMoving() {
        assertTrue(SideBySideMotion().update(Float.NaN, false, 0L))
    }

    @Test
    fun flatShowsTheLeftHalfToBothEyes() {
        val whole = floatArrayOf(0f, 0f, 1f, 1f)
        // The renderer passes eye 0 for both passes while flat.
        assertArrayEquals(SideBySide.eyeUv(whole, 0, 2), SideBySide.eyeUv(whole, 0, 2), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), SideBySide.eyeUv(whole, 0, 2), 1e-6f)
    }
}
