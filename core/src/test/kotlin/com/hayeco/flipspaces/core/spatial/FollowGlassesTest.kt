package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.6.29 (Crr66, VITURE Luma XR): the view follows the glasses' own 3D button; 640x480 is never taken as their 2D. */
class FollowGlassesTest {
    private val luma3d = listOf(Triple(3840, 1080, 60f), Triple(640, 480, 60f))
    private val luma2d = listOf(Triple(1920, 1080, 60f), Triple(640, 480, 60f))

    @Test
    fun theFallbackVgaModeIsNotAFlatMode() {
        assertNull(ViewModeChoice.pickFlatMode(luma3d))
        assertEquals(0, ViewModeChoice.pickFlatMode(luma2d))
    }

    @Test
    fun theGlassesOwn3dButtonSwitchesTheView() {
        assertEquals(GlassesView.SBS_3D, ViewModeChoice.followGlasses(GlassesView.FLAT_2D, luma3d, WideKind.BY_VIEW, glassesMenuOnly = true))
        assertEquals(GlassesView.FLAT_2D, ViewModeChoice.followGlasses(GlassesView.SBS_3D, luma2d, WideKind.BY_VIEW, glassesMenuOnly = true))
        // Already matching: nothing to do.
        assertNull(ViewModeChoice.followGlasses(GlassesView.SBS_3D, luma3d, WideKind.BY_VIEW, glassesMenuOnly = true))
    }

    @Test
    fun ultrawideAndXrealAreLeftAlone() {
        assertNull(ViewModeChoice.followGlasses(GlassesView.FLAT_2D, luma3d, WideKind.CANVAS, glassesMenuOnly = true))
        assertNull(ViewModeChoice.followGlasses(GlassesView.FLAT_2D, luma3d, WideKind.SIDE_BY_SIDE, glassesMenuOnly = false))
    }
}
