package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.UsbAsk
import com.hayeco.flipspaces.core.spatial.VitureReconnect.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.12, Discussion #24, third report (a VITURE Beast on 1.2.10): it worked, but the switch had to be turned on at first,
 * and after each unplug and replug it had to be flipped back on.
 */
class Discussion24ThirdReportTest {
    private val first = VitureReconnect.Device("/dev/bus/usb/002/003", permitted = true)
    private val replugged = VitureReconnect.Device("/dev/bus/usb/002/004", permitted = false)

    // ---- on by default on the Beast only; a saved choice wins ----

    @Test fun onByDefaultOnTheBeastOnly() {
        assertTrue(VitureDevices.headTrackingByDefault(VitureDevices.BEAST))
        assertFalse(VitureDevices.headTrackingByDefault(0x1201))
        assertFalse(VitureDevices.headTrackingByDefault(null))
        assertTrue(VitureDevices.headTrackingOn(null, VitureDevices.BEAST))
        assertFalse(VitureDevices.headTrackingOn(null, 0x1201))
    }

    @Test fun aSavedChoiceWins() {
        assertFalse(VitureDevices.headTrackingOn(false, VitureDevices.BEAST))
        assertTrue(VitureDevices.headTrackingOn(true, 0x1201))
        assertTrue(VitureDevices.headTrackingOn(true, null))
    }

    @Test fun theSwitchNameDropsExperimentalOnTheBeastOnly() {
        assertEquals("VITURE head tracking", VitureDevices.switchLabel(VitureDevices.BEAST))
        assertEquals("Experimental VITURE head tracking", VitureDevices.switchLabel(0x1201))
        assertEquals("Experimental VITURE head tracking", VitureDevices.switchLabel(null))
    }

    @Test fun theBeastSwitchedOffSaysSoNotUnsupported() {
        val beast = HeadTrackingStatus.VitureNotYet(VitureDevices.BEAST).summary
        assertTrue(beast, beast.contains("turned off"))
        assertFalse(beast, beast.contains("isn't supported"))
        assertTrue(HeadTrackingStatus.VitureNotYet(0x1201).summary.contains("isn't supported yet"))
        assertTrue(HeadTrackingStatus.VitureNotYet(VitureDevices.BEAST, experimentalOn = true).summary.contains("Record motion"))
    }

    // ---- a replug comes back with the switch left on ----

    /** Streaming on the first plug-in, then unplugged: the link closes. */
    private fun streamingThenUnplugged(): VitureReconnect {
        val r = VitureReconnect()
        r.setEnabled(true)
        assertEquals(Step.OPEN, r.next(first, 1_000))
        r.opened(first.name)
        r.detached()
        assertEquals(Step.CLOSE, r.next(null, 2_000))
        r.closed()
        assertEquals(Step.IDLE, r.next(null, 3_000))
        return r
    }

    @Test fun aReplugGrantedByThePlugInPromptOpensWithoutTheSwitch() {
        val r = streamingThenUnplugged()
        r.attached(10_000)
        // Android's "Open Pocket XR" prompt is up: the reader does not stack its own on top.
        assertEquals(Step.WAIT_FOR_PLUG_IN_PROMPT, r.next(replugged, 10_500))
        assertEquals(Step.WAIT_FOR_PLUG_IN_PROMPT, r.next(replugged, 15_000))
        // Its OK granted access: open, with the switch never touched.
        assertEquals(Step.OPEN, r.next(replugged.copy(permitted = true), 16_000))
        r.opened(replugged.name)
        assertEquals(Step.READ, r.next(replugged.copy(permitted = true), 17_000))
    }

    @Test fun aReplugWithThePlugInPromptUnansweredAsksItsOwnAfterTheWait() {
        val r = streamingThenUnplugged()
        r.attached(10_000)
        assertEquals(Step.WAIT_FOR_PLUG_IN_PROMPT, r.next(replugged, 10_000 + UsbAsk.ATTACH_PROMPT_MS - 1))
        assertEquals(Step.ASK, r.next(replugged, 10_000 + UsbAsk.ATTACH_PROMPT_MS))
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(replugged, 10_000 + UsbAsk.ATTACH_PROMPT_MS + 1_000))
    }

    @Test fun aMissedPromptIsAskedAgainWhenAScreenOpens() {
        val r = streamingThenUnplugged()
        r.attached(10_000)
        assertEquals(Step.ASK, r.next(replugged, 30_000))
        // 1.2.10 waited here until the switch was turned off and on.
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(replugged, 31_000))
        r.screenOpened()
        assertEquals(Step.ASK, r.next(replugged, 32_000))
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(replugged, 33_000))
        assertEquals(Step.OPEN, r.next(replugged.copy(permitted = true), 34_000))
    }

    @Test fun aScreenOpeningLeavesAnOpenLinkAlone() {
        val r = VitureReconnect()
        r.setEnabled(true)
        assertEquals(Step.OPEN, r.next(first))
        r.opened(first.name)
        r.screenOpened()
        assertEquals(Step.READ, r.next(first))
    }

    @Test fun turningTheSwitchOnAsksAtOnceEvenJustAfterAPlugIn() {
        val r = VitureReconnect()
        r.attached(10_000)
        assertEquals(Step.IDLE, r.next(replugged, 10_100))
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(replugged, 10_200))
    }

    @Test fun aPlugInWithoutATimeDoesNotWait() {
        val r = VitureReconnect()
        r.setEnabled(true)
        r.attached()
        assertEquals(Step.ASK, r.next(replugged, 0))
    }

    @Test fun anUnplugDuringThePlugInWaitClearsIt() {
        val r = VitureReconnect()
        r.setEnabled(true)
        r.attached(10_000)
        r.detached()
        assertEquals(Step.ASK, r.next(replugged, 10_100))
    }
}
