package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Steady mode's Screen gap ([SteadyArrange.arrange]'s gapShare): 0 to 0.10 of each cell, 0.04 unless changed. */
class SteadyScreenGapTest {
    private fun windows(n: Int) = SpaceLayout((1..n).map { SpacePanel(id = it, label = "App $it", packageName = "app.$it", activityName = "Main", yawDeg = it * 50f, pitchDeg = 20f, widthDeg = 50f, heightDeg = 30f) })

    /** The space between neighbours in a row, degrees: centres apart less half of each width. */
    private fun gaps(l: SpaceLayout): List<Float> = l.panels.zipWithNext { a, b ->
        abs(wrapDegrees(b.yawDeg - a.yawDeg)) - (a.widthDeg + b.widthDeg) / 2f
    }

    @Test fun theDefaultIsTheGapSteadyModeAlwaysHad() {
        assertEquals(0.04f, SteadyArrange.GAP_SHARE, 0f)
        for (n in 1..PanelLimits.MAX_PANELS) {
            assertEquals(SteadyArrange.arrange(windows(n), 0f, 0f, 79f, 26f), SteadyArrange.arrange(windows(n), 0f, 0f, 79f, 26f, SteadyArrange.GAP_SHARE))
        }
    }

    @Test fun gapZeroPutsScreensSideBySideTouching() {
        // Three on the Aura's canvas, one row, none narrowed by the widest shape: neighbours meet.
        val l = SteadyArrange.arrange(windows(3), 0f, 0f, 79f, 26f, 0f)
        assertEquals(3, l.panels.size)
        for (g in gaps(l)) assertEquals("touching: $g", 0f, g, 0.01f)
    }

    @Test fun aWiderGapLeavesMoreSpaceAndSmallerScreens() {
        val none = SteadyArrange.arrange(windows(3), 0f, 0f, 79f, 26f, 0f)
        val usual = SteadyArrange.arrange(windows(3), 0f, 0f, 79f, 26f)
        val wide = SteadyArrange.arrange(windows(3), 0f, 0f, 79f, 26f, 0.1f)
        for (i in 0 until 3) {
            assertTrue("$i: narrower with a wider gap", wide.panels[i].widthDeg < usual.panels[i].widthDeg && usual.panels[i].widthDeg < none.panels[i].widthDeg)
            assertTrue("$i: shorter with a wider gap", wide.panels[i].heightDeg < usual.panels[i].heightDeg && usual.panels[i].heightDeg < none.panels[i].heightDeg)
            // Each window keeps its place: only the space around it changes.
            assertEquals(none.panels[i].yawDeg, wide.panels[i].yawDeg, 0.001f)
        }
        val g = gaps(wide)
        assertTrue("0.1 leaves a gap: $g", g.all { it > 1f })
        assertTrue("0.1 leaves more than 0.04: $g", g.zip(gaps(usual)).all { (a, b) -> a > b })
    }

    @Test fun gapZeroAndTenthKeepEveryWindowAndItsButtonsOnTheCanvasWithoutOverlap() {
        for (gap in listOf(0f, 0.1f)) for ((viewW, viewH) in listOf(79f to 26f, 79f to 60f)) for (n in 1..PanelLimits.MAX_PANELS) {
            val l = SteadyArrange.arrange(windows(n), 30f, 4f, viewW, viewH, gap)
            assertEquals(n, l.panels.size)
            for (p in l.panels) {
                val above = p.heightDeg * WindowControls.aboveShare(p)
                val below = p.heightDeg * WindowControls.belowShare(p)
                assertTrue("gap $gap, $n: top", p.pitchDeg + p.heightDeg / 2f + above <= 4f + viewH / 2f + 0.001f)
                assertTrue("gap $gap, $n: bottom", p.pitchDeg - p.heightDeg / 2f - below >= 4f - viewH / 2f - 0.001f)
                assertTrue("gap $gap, $n: sides", abs(wrapDegrees(p.yawDeg - 30f)) + p.widthDeg / 2f <= viewW / 2f + 0.001f)
            }
            for (a in l.panels) for (b in l.panels) if (a.id < b.id) {
                val apartX = abs(wrapDegrees(a.yawDeg - b.yawDeg)) >= (a.widthDeg + b.widthDeg) / 2f - 0.01f
                val apartY = abs(a.pitchDeg - b.pitchDeg) >= (a.heightDeg + b.heightDeg) / 2f - 0.01f
                assertTrue("gap $gap, $n: ${a.id} and ${b.id} overlap", apartX || apartY)
            }
        }
    }

    @Test fun aNewGapKeepsTheRowWhereItIsNow() {
        val arranged = SteadyArrange.arrange(windows(3), 10f, 2f, 79f, 26f)
        // Head scroll or a Recentre moved the row 15 degrees right and 3 up since.
        val moved = arranged.copy(panels = arranged.panels.map { it.copy(yawDeg = wrapDegrees(it.yawDeg + 15f), pitchDeg = it.pitchDeg + 3f) })
        val (again, centre) = SteadyArrange.regapped(moved, 10f, 2f, 79f, 26f, SteadyArrange.GAP_SHARE, 0f)
        assertEquals(25f, centre.first, 0.01f)
        assertEquals(5f, centre.second, 0.01f)
        assertSamePlaces(SteadyArrange.arrange(windows(3), 25f, 5f, 79f, 26f, 0f), again)
        // The same gap again changes nothing.
        assertSamePlaces(moved, SteadyArrange.regapped(moved, 10f, 2f, 79f, 26f, SteadyArrange.GAP_SHARE, SteadyArrange.GAP_SHARE).first)
    }

    private fun assertSamePlaces(want: SpaceLayout, got: SpaceLayout) {
        assertEquals(want.panels.map { it.id }, got.panels.map { it.id })
        want.panels.zip(got.panels).forEach { (w, g) ->
            assertEquals(0f, wrapDegrees(w.yawDeg - g.yawDeg), 0.01f)
            assertEquals(w.pitchDeg, g.pitchDeg, 0.01f)
            assertEquals(w.widthDeg, g.widthDeg, 0.01f)
            assertEquals(w.heightDeg, g.heightDeg, 0.01f)
        }
    }

    @Test fun aGapOutsideTheRangeIsKeptWithinIt() {
        assertEquals(0f, SteadyArrange.clampGap(-0.5f), 0f)
        assertEquals(0.1f, SteadyArrange.clampGap(0.7f), 0f)
        assertEquals(SteadyArrange.GAP_SHARE, SteadyArrange.clampGap(Float.NaN), 0f)
        assertEquals(SteadyArrange.arrange(windows(2), 0f, 0f, 79f, 26f, 0.1f), SteadyArrange.arrange(windows(2), 0f, 0f, 79f, 26f, 3f))
    }
}
