package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unreleased (John Davis: no Text size in the Menu after clicking a window): the focused window, kept across Space starts. */
class FocusedWindowTest {
    private val chrome = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val youTube = SpacePanel(2, "YouTube", "com.google.android.youtube", "Main", yawDeg = 40f, pitchDeg = 0f)
    private val info = SpacePanel(0, "Pocket XR", "", "", yawDeg = 0f, pitchDeg = -30f)
    private val byId = listOf(chrome, youTube, info).associateBy { it.id }

    @Test
    fun theFocusedAppWindowIsTheOneTheMenuActsOn() {
        assertEquals(chrome, FocusedWindow.of(1) { byId[it] })
        assertEquals(youTube, FocusedWindow.of(2) { byId[it] })
    }

    @Test
    fun noneWithNothingFocusedAGoneWindowOrNoApp() {
        assertNull(FocusedWindow.of(null) { byId[it] })
        assertNull(FocusedWindow.of(7) { byId[it] })
        assertNull(FocusedWindow.of(0) { byId[it] })
    }

    @Test
    fun theFocusOutlivesASpaceRestart() {
        // A window clicked, then the glasses' 3D re-plug starts a new Space with the same saved windows.
        val kept = FocusedWindow.keep(chrome)
        val restarted = SpaceLayout(listOf(chrome, youTube))
        assertEquals(1, FocusedWindow.restored(kept, restarted.panels))
    }

    @Test
    fun aClosedOrReusedWindowIsNotRestored() {
        val kept = FocusedWindow.keep(chrome)
        assertNull(FocusedWindow.restored(kept, listOf(youTube)))
        assertNull(FocusedWindow.restored(kept, listOf(chrome.copy(label = "Maps", packageName = "com.google.android.apps.maps"))))
        assertNull(FocusedWindow.restored(null, listOf(chrome)))
        assertNull(FocusedWindow.keep(info))
        assertNull(FocusedWindow.keep(null))
    }

    @Test
    fun aTrackpadTapAndAHandPinchOnAWindowFocusIt() {
        val layout = SpaceLayout(listOf(chrome))
        val pixels = { _: Int -> Pair(1280, 720) }
        val tap = SpatialPointer(ViewFrustum(1920, 1080, 70f)).handle(PointerSignal.Down(pad = true), Quat.IDENTITY, layout, pixels)
        assertTrue(SpaceAction.Focus(1) in tap)
        val pinch = SpatialPointer(ViewFrustum(1920, 1080, 70f), PointerSettings(handsSteer = false))
            .handle(PointerSignal.ForHand(com.hayeco.flipspaces.core.pointer.HandSide.RIGHT, PointerSignal.Down(pinch = true)), Quat.IDENTITY, layout, pixels)
        assertTrue(SpaceAction.Focus(1) in pinch)
    }
}
