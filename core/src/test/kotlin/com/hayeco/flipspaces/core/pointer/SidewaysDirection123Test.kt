package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.3 (issue 56: "The Default / Inverted choice in the Menu shows no effect"): the Menu shows Sideways trackpad direction
 * only for the page it turns.
 */
class SidewaysDirection123Test {
    @Test
    fun theChoiceShowsOnlyOnTheHorizontalTrackpad() {
        assertTrue(TrackpadSettings.directionApplies(PadMode.HORIZONTAL))
        for (m in PadMode.entries.filter { it != PadMode.HORIZONTAL }) assertFalse(m.label, TrackpadSettings.directionApplies(m))
    }

    /** Where the choice shows it changes the page's orientation, and where it is hidden it would change nothing. */
    @Test
    fun theChoiceIsShownExactlyWhereItChangesTheOrientation() {
        for (m in PadMode.entries) for (device in listOf(false, true)) {
            val changes = TrackpadSettings.screenOrientation(m, PadDirection.DEFAULT, device) !=
                TrackpadSettings.screenOrientation(m, PadDirection.INVERTED, device)
            assertEquals("${m.label}, landscape device $device", changes, TrackpadSettings.directionApplies(m))
        }
    }
}
