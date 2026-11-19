package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.TrackpadMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.6 (issue 119): Scroll by swiping, the trackpad's scrolls as a finger dragged across the app. */
class SwipeScroll156Test {
    private val youtube = SpacePanel(1, "YouTube", "com.google.android.youtube", "Main", yawDeg = 0f, pitchDeg = 0f).withAppSize()
    private val layout = SpaceLayout(listOf(youtube))
    private val px: (Int) -> Pair<Int, Int> = { 1760 to 990 }

    private fun pointer(swipe: Boolean): SpatialPointer {
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        if (swipe) p.swipePanels = setOf(youtube.id)
        p.frame(Quat.IDENTITY, layout, 16L)
        return p
    }

    private fun touches(out: List<SpaceAction>) = out.filterIsInstance<SpaceAction.Touch>()

    @Test
    fun aTwoFingerScrollDragsAFingerDownTheAppWhenSwipingIsOn() {
        val p = pointer(swipe = true)
        val begin = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, px)
        assertEquals(TouchPhase.DOWN, touches(begin).single().phase)
        assertTrue("no wheel", begin.none { it is SpaceAction.Wheel })
        val down0 = touches(begin).single()
        // The fingers go down 0.2 pad units: the finger on the app goes down too (YouTube's swipe to its mini player).
        val move = touches(p.handle(PadSignal.Scroll(0f, 0f, 0f, -0.2f, Phase.UPDATE), Quat.IDENTITY, layout, px)).single()
        assertEquals(TouchPhase.MOVE, move.phase)
        assertEquals(down0.v + 0.2f * SCROLL_DRAG, move.v, 1e-4f)
        assertEquals(down0.u, move.u, 1e-4f)
        val end = touches(p.handle(PadSignal.Scroll(0f, 0f, 0f, -0.2f, Phase.END), Quat.IDENTITY, layout, px)).single()
        assertEquals(TouchPhase.UP, end.phase)
    }

    @Test
    fun sidewaysGoesSidewaysAndStaysOnTheWindow() {
        val p = pointer(swipe = true)
        p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, px)
        val move = touches(p.handle(PadSignal.Scroll(0f, 0f, -3f, 0f, Phase.UPDATE), Quat.IDENTITY, layout, px)).single()
        assertEquals(0f, move.u, 1e-6f)
    }

    @Test
    fun aDragTooShortToBeASwipeIsCancelledNotTapped() {
        val p = pointer(swipe = true)
        p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, px)
        val end = touches(p.handle(PadSignal.Scroll(0f, 0f, 0f, 0.005f, Phase.END), Quat.IDENTITY, layout, px)).single()
        assertEquals(TouchPhase.CANCEL, end.phase)
    }

    @Test
    fun theStripSwipesToo() {
        val p = pointer(swipe = true)
        val begin = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true, wheel = true), Quat.IDENTITY, layout, px)
        assertEquals(TouchPhase.DOWN, touches(begin).single().phase)
        val move = touches(p.handle(PadSignal.Scroll(0f, 0f, 0f, 0.1f, Phase.UPDATE, strip = true, wheel = true), Quat.IDENTITY, layout, px)).single()
        assertEquals(touches(begin).single().v - 0.1f * SCROLL_DRAG, move.v, 1e-4f)
        val end = touches(p.handle(PadSignal.Scroll(0f, 0f, 0f, 0.1f, Phase.END, strip = true, wheel = true), Quat.IDENTITY, layout, px)).single()
        assertEquals(TouchPhase.UP, end.phase)
    }

    @Test
    fun withSwipingOffItIsStillTheWheel() {
        val p = pointer(swipe = false)
        val begin = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, px)
        assertTrue(begin.any { it is SpaceAction.Wheel })
        assertTrue(touches(begin).isEmpty())
    }

    @Test
    fun theStoreRemembersAppsAndTheMenuOffersItForAFocusedWindow() {
        val store = SwipeScrollStore()
        assertFalse(store.isOn("com.google.android.youtube"))
        store.set("com.google.android.youtube", true)
        store.set("pl.solidexplorer2", true)
        store.set("", true)
        val back = SwipeScrollStore(store.encode())
        assertTrue(back.isOn("com.google.android.youtube"))
        assertTrue(back.isOn("pl.solidexplorer2"))
        assertFalse(back.isOn(""))
        back.set("pl.solidexplorer2", false)
        assertFalse(SwipeScrollStore(back.encode()).isOn("pl.solidexplorer2"))
        assertTrue(TrackpadMenu.Item.SWIPE_SCROLL in TrackpadMenu.items(TrackpadMenu.State(swipeScrollShown = true)))
        assertFalse(TrackpadMenu.Item.SWIPE_SCROLL in TrackpadMenu.items(TrackpadMenu.State()))
    }
}
