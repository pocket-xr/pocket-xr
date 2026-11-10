package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.4.2: Steady mode comes back after the glasses are unplugged and plugged in again (Mike, 10 November 2026). */
class SteadyReplugTest {
    @Test
    fun ultrawideOffJustAfterANewLinkIsAReplug() {
        assertEquals(SteadyMode.Dropped.REPLUG, SteadyMode.dropped(100_000L, null, linkOpenedMs = 98_000L))
        // Even with a drop moments ago: the re-plug that Ultrawide itself causes is not the user's second press.
        assertEquals(SteadyMode.Dropped.REPLUG, SteadyMode.dropped(100_000L, 95_000L, linkOpenedMs = 99_000L))
    }

    @Test
    fun onAnOldLinkItIsTheButtonAsBefore() {
        val long = 100_000L - SteadyMode.REPLUG_MS - 1L
        assertEquals(SteadyMode.Dropped.BACK_IN, SteadyMode.dropped(100_000L, null, linkOpenedMs = long))
        assertEquals(SteadyMode.Dropped.LEAVE, SteadyMode.dropped(100_000L, 90_000L, linkOpenedMs = long))
    }
}
