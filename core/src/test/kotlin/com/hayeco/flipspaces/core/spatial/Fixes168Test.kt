package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.SpaceSurface
import com.hayeco.flipspaces.core.SpaceSurfacePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.8: the DeX caption drawn inside the Space's own window (Crr66, Galaxy S20, Android 13). */
class Fixes168Test {
    @Test fun theS20sSurfaceReadsAsACaptionInTheWindow() {
        // Crr66's 1.6.2 log: surface 1920x1028 and 3840x1028 on displays of 1080 rows
        assertTrue(SpaceSurfacePlan.captionInWindow(1920, 1028, 1920, 1080))
        assertTrue(SpaceSurfacePlan.captionInWindow(3840, 1028, 3840, 1080))
        // the 40 rows another phone shows for a moment while DeX settles the window still count; the activity waits
        // for them to stay before acting
        assertTrue(SpaceSurfacePlan.captionInWindow(1920, 1040, 1920, 1080))
    }

    @Test fun aFullOrOddSurfaceIsNoCaption() {
        assertFalse(SpaceSurfacePlan.captionInWindow(1920, 1080, 1920, 1080))
        assertFalse(SpaceSurfacePlan.captionInWindow(1920, 1076, 1920, 1080)) // 4 rows: too few (1.6.28: was 21)
        // 1.6.28: Crr66's slimmed DeX title bar, 12 rows (surface 1920x1068)
        assertTrue(SpaceSurfacePlan.captionInWindow(1920, 1068, 1920, 1080))
        assertFalse(SpaceSurfacePlan.captionInWindow(960, 1028, 1920, 1080)) // a narrower window
        assertFalse(SpaceSurfacePlan.captionInWindow(1920, 700, 1920, 1080)) // far more than a caption
        assertFalse(SpaceSurfacePlan.captionInWindow(0, 0, 1920, 1080))
    }

    @Test fun aCaptionInTheWindowWinsOverComposedAndTheHold() {
        // as on the S20: the hold moved it and SurfaceFlinger said composed, yet the caption stayed
        assertEquals(
            SpaceSurface.PRESENTATION,
            SpaceSurfacePlan.choose(57, freeform = true, holdSettled = true, holdMovedIt = true, composed = true, captionInWindow = true),
        )
        // without it, as before
        assertEquals(
            SpaceSurface.WINDOW,
            SpaceSurfacePlan.choose(57, freeform = true, holdSettled = true, holdMovedIt = true, composed = true),
        )
    }

    @Test fun aCaptionNeverTriesTwiceOrWhereAPresentationCannotGo() {
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(57, true, true, composed = true, triedHere = true, captionInWindow = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(57, true, true, takesPresentation = false, captionInWindow = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(57, true, true, windowOnly = true, captionInWindow = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(0, true, true, captionInWindow = true))
        // before the hold has had its say
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(57, true, holdSettled = false, captionInWindow = true))
    }
}
