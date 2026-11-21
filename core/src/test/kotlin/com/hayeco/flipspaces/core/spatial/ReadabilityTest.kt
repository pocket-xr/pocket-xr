package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readability: every window at 4/3 its density, its size and pixels unchanged, within the floors that already exist, and
 * off the same display as before, exactly.
 */
class ReadabilityTest {
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)

    @Test
    fun aDefaultWindowGoesFrom330To440Dpi() {
        val usual = PanelDisplaySize.of(panel)
        val readable = PanelDisplaySize.of(panel, readable = true)
        assertEquals(330, usual.dpi)
        assertEquals(PanelDisplaySpec(usual.widthPx, usual.heightPx, 440), readable)
        // Three quarters of the dp each way, so text is a third bigger.
        assertEquals(usual.widthDp * 0.75f, readable.widthDp, 1f)
        assertEquals(usual.heightDp * 0.75f, readable.heightDp, 1f)
    }

    @Test
    fun theWindowKeepsItsPixels() {
        for (w in listOf(20f, 30f, 40f, 60f, 90f)) {
            val p = panel.copy(widthDeg = w, heightDeg = w * 9f / 16f)
            val usual = PanelDisplaySize.of(p)
            val readable = PanelDisplaySize.of(p, readable = true)
            assertEquals(usual.widthPx, readable.widthPx)
            assertEquals(usual.heightPx, readable.heightPx)
            assertTrue("$w degrees: ${readable.dpi} against ${usual.dpi}", readable.dpi >= usual.dpi)
        }
    }

    @Test
    fun offIsExactlyTheDisplayItHadBefore() {
        val shapes = listOf(panel, panel.copy(widthDeg = 25f, heightDeg = 30f), panel.copy(widthDeg = 80f, heightDeg = 45f))
        for (p in shapes) for (broad in listOf(false, true)) for (percent in AppTextSize.MIN..AppTextSize.MAX step AppTextSize.STEP) {
            assertEquals(PanelDisplaySize.of(p, broad, textPercent = percent), PanelDisplaySize.of(p, broad, textPercent = percent, readable = false))
        }
        assertEquals(330, Readability.density(330, false, 1760, 990))
    }

    @Test
    fun neverUnderAPhonesWidthNorOutOfRange() {
        val shapes = listOf(panel, panel.copy(widthDeg = 12f, heightDeg = 12f), panel.copy(widthDeg = 30f, heightDeg = 16.875f), panel.copy(widthDeg = 100f, heightDeg = 70f))
        for (p in shapes) for (percent in AppTextSize.MIN..AppTextSize.MAX step AppTextSize.STEP) {
            val usual = PanelDisplaySize.of(p, textPercent = percent)
            val s = PanelDisplaySize.of(p, textPercent = percent, readable = true)
            assertTrue(s.dpi in PanelDisplaySize.LOWEST_DPI..PanelDisplaySize.HIGHEST_DPI)
            // Under 320 dp only where the usual rule already was.
            if (usual.smallestDp >= PanelDisplaySize.MIN_SMALLEST_DP - 1f) {
                assertTrue("${p.widthDeg} degrees, $percent %: ${s.smallestDp} dp", s.smallestDp >= PanelDisplaySize.MIN_SMALLEST_DP - 1f)
            }
        }
    }

    @Test
    fun aThirtyDegreeWindowStopsAtThePhoneFloor() {
        val p = panel.copy(widthDeg = 30f, heightDeg = 16.875f)
        val s = PanelDisplaySize.of(p, readable = true)
        assertEquals(PanelDisplaySize.MIN_SMALLEST_DP, s.smallestDp, 1f)
        assertTrue(s.dpi < 440)
    }

    @Test
    fun wideViewKeepsItsOwnFloor() {
        val s = PanelDisplaySize.of(panel, broad = true, readable = true)
        assertTrue(s.smallestDp >= WideView.MIN_SMALLEST_DP - 1f)
    }

    @Test
    fun fitVideoStillWins() {
        val video = PanelDisplaySize.of(panel, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        assertEquals(video, PanelDisplaySize.of(panel, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP, readable = true))
    }

    @Test
    fun theLogSaysBeforeAndAfter() {
        val before = PanelDisplaySpec(1760, 990, 330)
        val after = PanelDisplaySpec(1760, 990, 440)
        assertEquals(
            "readability on: panel 3 Chrome 1760x990 at 330 dpi (853x480 dp, smallest width 480 dp) to 1760x990 at 440 dpi (640x360 dp, smallest width 360 dp)",
            Readability.line(true, 3, "Chrome", before, after),
        )
        assertTrue(Readability.line(false, 3, "Chrome", after, before).startsWith("readability off: "))
    }
}
