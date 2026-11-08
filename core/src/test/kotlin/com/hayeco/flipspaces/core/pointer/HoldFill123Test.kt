package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.spatial.HoldFill
import com.hayeco.flipspaces.core.spatial.PanelZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.3 (issue 53: the resize handle "seems to do nothing" when pressed; it has to be held): the trackpad says how far a
 * still finger is toward its long press, and over a resize handle or a grab band the cursor fills with it.
 */
class HoldFill123Test {
    private fun touch(x: Float = 0f, y: Float = 0f) = PadTouch(0, x, y)

    @Test
    fun aStillFingerFillsTowardTheLongPress() {
        val pad = TrackpadInterpreter(mode = PadMode.VERTICAL)
        assertEquals(0f, pad.holdProgress(0L), 0f)
        pad.down(touch(), 1000L)
        assertEquals(0f, pad.holdProgress(1000L), 1e-6f)
        assertEquals(0.5f, pad.holdProgress(1000L + TrackpadInterpreter.LONG_PRESS_MS / 2), 1e-3f)
        assertEquals(1f, pad.holdProgress(1000L + TrackpadInterpreter.LONG_PRESS_MS * 2), 0f)
        // The long press fires: the hold is done, so the fill goes.
        assertTrue(pad.tick(1000L + TrackpadInterpreter.LONG_PRESS_MS).isNotEmpty())
        assertEquals(0f, pad.holdProgress(1000L + TrackpadInterpreter.LONG_PRESS_MS), 0f)
    }

    @Test
    fun aSwipeOrASecondFingerIsNoHold() {
        val swiped = TrackpadInterpreter(mode = PadMode.VERTICAL)
        swiped.down(touch(), 0L)
        swiped.move(listOf(touch(0.5f, 0.5f)), 50L)
        assertEquals(0f, swiped.holdProgress(300L), 0f)
        val two = TrackpadInterpreter(mode = PadMode.VERTICAL)
        two.down(touch(), 0L)
        two.down(PadTouch(1, 0.2f, 0.2f), 10L)
        assertEquals(0f, two.holdProgress(300L), 0f)
        val lifted = TrackpadInterpreter(mode = PadMode.VERTICAL)
        lifted.down(touch(), 0L)
        lifted.up(0, 100L)
        assertEquals(0f, lifted.holdProgress(300L), 0f)
    }

    @Test
    fun theCursorFillsOnlyOverTheHandleOrTheGrabBandAndNotForATap() {
        assertEquals(0f, HoldFill.of(1f, PanelZone.BODY, frame = false), 0f)
        assertEquals(0f, HoldFill.of(1f, null, frame = false), 0f)
        assertEquals(1f, HoldFill.of(1f, PanelZone.CORNER, frame = false), 0f)
        assertEquals(1f, HoldFill.of(1f, PanelZone.TITLE_BAR, frame = true), 0f)
        // A tap lifts well inside the first fifth of the hold: nothing shows.
        assertEquals(0f, HoldFill.of(HoldFill.START, PanelZone.CORNER, frame = false), 0f)
        assertEquals(0.5f, HoldFill.of(HoldFill.START + (1f - HoldFill.START) / 2f, PanelZone.CORNER, frame = false), 1e-4f)
    }

    @Test
    fun theTutorialSaysHowToResize() {
        val first = IntroPages.trackpad(landscapeHandheld = false, spatialMouse = true).first()
        assertEquals(IntroPages.RESIZE_LINE, first.note)
        assertTrue(first.note!!.contains("corner handle"))
    }
}
