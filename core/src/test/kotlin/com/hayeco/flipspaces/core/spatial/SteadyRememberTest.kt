package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.SteadyRemember.Now
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Steady mode remembered across launches ([SteadyRemember]). */
class SteadyRememberTest {
    /** An Aura plugged in with Steady mode left on, its link open and its Ultrawide answer in (off). */
    private val ready = Now(
        saved = true, offered = true, tried = false, spaceUp = true, busy = false, steadyOn = false,
        readsSettings = true, ultrawide = 0, sinceLinkMs = 500L,
    )

    @Test fun leftOnItComesBackOnceTheLinkIsReady() {
        assertTrue(SteadyRemember.turnOn(ready))
        assertTrue(SteadyRemember.settled(ready))
    }

    @Test fun leftOffNothingHappens() {
        assertFalse(SteadyRemember.turnOn(ready.copy(saved = false)))
        assertTrue(SteadyRemember.settled(ready.copy(saved = false)))
    }

    @Test fun glassesThatCannotUseSteadyModeIgnoreIt() {
        assertFalse(SteadyRemember.turnOn(ready.copy(offered = false)))
        assertTrue(SteadyRemember.settled(ready.copy(offered = false)))
    }

    @Test fun onlyOnceAPlugIn() = assertFalse(SteadyRemember.turnOn(ready.copy(tried = true)))

    @Test fun theAuraWaitsForItsUltrawideAnswer() {
        val waiting = ready.copy(ultrawide = null, sinceLinkMs = 10_000L)
        assertFalse(SteadyRemember.turnOn(waiting))
        assertFalse("still to be done", SteadyRemember.settled(waiting))
    }

    @Test fun otherGlassesWaitAFewSecondsAfterTheLinkOpens() {
        val one = ready.copy(readsSettings = false, ultrawide = null, sinceLinkMs = SteadyRemember.SETTLE_MS - 1L)
        assertFalse(SteadyRemember.turnOn(one))
        assertFalse(SteadyRemember.settled(one))
        assertTrue(SteadyRemember.turnOn(one.copy(sinceLinkMs = SteadyRemember.SETTLE_MS)))
    }

    @Test fun notWithoutASpace() {
        assertFalse(SteadyRemember.turnOn(ready.copy(spaceUp = false)))
        assertFalse(SteadyRemember.settled(ready.copy(spaceUp = false)))
    }

    @Test fun alreadyOnIsLeftAsItIs() {
        assertFalse(SteadyRemember.turnOn(ready.copy(steadyOn = true)))
        assertFalse(SteadyRemember.turnOn(ready.copy(ultrawide = 1)))
        assertTrue("dealt with: not turned on later behind the user", SteadyRemember.settled(ready.copy(ultrawide = 1)))
    }

    @Test fun aChangeUnderWayOrPcVrComesFirst() {
        assertFalse(SteadyRemember.turnOn(ready.copy(busy = true)))
        assertTrue("not tried again later on this plug-in", SteadyRemember.settled(ready.copy(busy = true)))
    }
}
