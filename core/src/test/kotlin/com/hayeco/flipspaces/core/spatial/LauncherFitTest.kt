package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.34 (Mike, for Crr66's VITURE Luma: "fit every glasses"): the launcher opens far enough away to fit the view. */
class LauncherFitTest {
    private fun opened(wDeg: Float?, hDeg: Float) = XrLauncher().apply {
        fitViewWidthDeg = wDeg
        fitViewHeightDeg = hDeg
        open(10f, 4f, 0L)
    }

    @Test fun theLumaSeesAllOfIt() {
        val l = opened(44.2f, 25.8f)
        val (w, h) = LauncherGrid.angularDeg(l.placement.distanceM)
        assertEquals("the height limits it", 25.8f * XrLauncher.FIT_VIEW, h, 0.05f)
        assertTrue(w <= 44.2f * XrLauncher.FIT_VIEW + 0.05f)
        assertEquals("centred on the gaze", 4f, l.placement.pitchDeg, 0.01f)
        assertEquals(10f, l.placement.yawDeg, 0.01f)
    }

    @Test fun theAuraIsAbout80PercentOfItsOldSize() {
        val l = opened(54.5f, 32.3f)
        assertEquals(1.25f, l.placement.distanceM, 0.02f)
        assertTrue(LauncherGrid.angularDeg(l.placement.distanceM).second <= 32.3f * XrLauncher.FIT_VIEW + 0.05f)
    }

    @Test fun aNarrowViewIsLimitedByItsWidth() {
        val l = opened(30f, 40f)
        val (w, _) = LauncherGrid.angularDeg(l.placement.distanceM)
        assertEquals(30f * XrLauncher.FIT_VIEW, w, 0.05f)
    }

    @Test fun aViewBigEnoughKeepsOneMetre() {
        assertEquals(XrLauncher.DISTANCE_M, opened(90f, 60f).placement.distanceM, 0.001f)
    }

    @Test fun pointingFollowsTheFartherPanel() {
        val l = opened(44.2f, 25.8f)
        val corner = l.placement.toWorld(0f, 0f, l.scale(10_000))
        val (x, y) = l.pointOf(Ray(Vec3(0f, 0f, 0f), corner.normalized()), 10_000)!!
        assertEquals(0f, x, 0.5f)
        assertEquals(0f, y, 0.5f)
    }
}
