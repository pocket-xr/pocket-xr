package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.spatial.SwipeRoute
import com.hayeco.flipspaces.core.spatial.ThreeFingerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.3 (issue 50: three finger gestures don't work on phones that keep them for screenshots): the tutorial says what does
 * the same without them, and the Menu's row sends what three fingers down send.
 */
class NoThreeFingers123Test {
    private fun threeFingerPage(spatialMouse: Boolean) =
        IntroPages.trackpad(landscapeHandheld = false, spatialMouse = spatialMouse).single { it.art == IntroArt.THREE_FINGERS }

    @Test
    fun theThreeFingerPageSaysWhatToUseInstead() {
        val note = threeFingerPage(spatialMouse = true).note!!
        assertEquals(IntroPages.NO_THREE_FINGERS_LINE, note)
        assertTrue(note.contains(IntroPages.CONTROL_PANEL_ROW))
    }

    @Test
    fun asTheLastPageItStillEndsWithTheExitLineAndNoDoubleStop() {
        val note = threeFingerPage(spatialMouse = false).note!!
        assertTrue(note.startsWith(IntroPages.NO_THREE_FINGERS_LINE))
        assertTrue(note.contains(IntroPages.EXIT_LABEL))
        assertFalse(note.contains(".."))
    }

    @Test
    fun theMenuRowOpensTheControlPanelAsThreeFingersDownDo() {
        assertEquals(SwipeRoute.OPEN_CONTROLS, ThreeFingerRoute.route(SwipeDirection.DOWN, launcherOpen = false, controlsOpen = false))
    }
}
