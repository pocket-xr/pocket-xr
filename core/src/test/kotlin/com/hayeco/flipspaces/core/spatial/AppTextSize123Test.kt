package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.3 (issue 57: "Let me set a window's density (DPI)"): each app's own text size, a share of the usual density,
 * remembered per app and kept off the smallest width rule's bad side.
 */
class AppTextSize123Test {
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)

    @Test
    fun atOneHundredPercentNothingChanges() {
        val usual = PanelDisplaySize.of(panel)
        assertEquals(usual, PanelDisplaySize.of(panel, textPercent = AppTextSize.DEFAULT))
        assertEquals(PanelDisplays.DENSITY_DPI, usual.dpi)
    }

    @Test
    fun smallerTextLaysMoreOutOnTheSameWindow() {
        val usual = PanelDisplaySize.of(panel)
        val small = PanelDisplaySize.of(panel, textPercent = 60)
        // Same pixels, so the window keeps its size; a lower density, so the app has more dp.
        assertEquals(usual.widthPx, small.widthPx)
        assertEquals(usual.heightPx, small.heightPx)
        assertEquals(PanelDisplays.DENSITY_DPI * 0.6, small.dpi.toDouble(), 1.0)
        assertTrue(small.widthPx * 160f / small.dpi > usual.widthPx * 160f / usual.dpi * 1.6f)
    }

    @Test
    fun largerTextNeverSqueezesTheAppUnderAPhonesWidth() {
        for (percent in AppTextSize.MIN..AppTextSize.MAX step AppTextSize.STEP) {
            val s = PanelDisplaySize.of(panel, textPercent = percent)
            val smallestDp = minOf(s.widthPx, s.heightPx) * 160f / s.dpi
            assertTrue("$percent %: $smallestDp dp", smallestDp >= PanelDisplaySize.MIN_SMALLEST_DP - 1f)
            assertTrue(s.dpi in PanelDisplaySize.LOWEST_DPI..PanelDisplaySize.HIGHEST_DPI)
        }
        assertTrue(PanelDisplaySize.of(panel, textPercent = 120).dpi > PanelDisplays.DENSITY_DPI)
    }

    @Test
    fun fitVideoStillWins() {
        val video = PanelDisplaySize.of(panel, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        assertEquals(video, PanelDisplaySize.of(panel, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP, textPercent = 60))
    }

    @Test
    fun stepsStayInRange() {
        assertEquals(90, AppTextSize.step(100, -1))
        assertEquals(AppTextSize.MIN, AppTextSize.step(50, -5))
        assertEquals(AppTextSize.MAX, AppTextSize.step(120, 3))
    }

    @Test
    fun theStoreRemembersEachAppAndForgetsOneHundred() {
        val store = AppTextSizeStore()
        store.set("com.android.chrome", 70)
        store.set("com.google.android.gm", 100)
        store.set("org.mozilla.firefox", 999)
        val back = AppTextSizeStore(store.encode())
        assertEquals(70, back.percent("com.android.chrome"))
        assertEquals(100, back.percent("com.google.android.gm"))
        assertEquals(AppTextSize.MAX, back.percent("org.mozilla.firefox"))
        assertEquals(100, back.percent("never.set"))
        assertTrue(!store.encode().contains("com.google.android.gm"))
    }
}
