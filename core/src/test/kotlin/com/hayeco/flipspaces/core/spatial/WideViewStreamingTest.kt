package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.13: Wide view starts on for streaming apps whose phone layout is locked upright (Crr66's Netflix). */
class WideViewStreamingTest {
    private val netflix = "com.netflix.mediaclient"

    @Test fun netflixStartsWide() {
        assertTrue(WideViewStore().isOn(netflix))
        assertFalse(WideViewStore().isOn("com.example.notes"))
    }

    @Test fun turningItOffIsRememberedAndOnAgainToo() {
        val s = WideViewStore()
        val off = s.toggle(netflix, null, 10_000L)
        assertEquals(false, off.on)
        val saved = WideViewStore(s.encode())
        assertFalse(saved.isOn(netflix))
        val on = saved.toggle(netflix, null, 20_000L)
        assertEquals(true, on.on)
        assertTrue(WideViewStore(saved.encode()).isOn(netflix))
    }

    @Test fun otherAppsWorkAsBefore() {
        val s = WideViewStore()
        assertTrue(s.toggle("com.example.notes", null, 10_000L).on)
        val saved = WideViewStore(s.encode())
        assertTrue(saved.isOn("com.example.notes"))
        assertFalse(saved.toggle("com.example.notes", null, 20_000L).on)
        assertFalse(WideViewStore(saved.encode()).isOn("com.example.notes"))
    }

    @Test fun anOldSaveStillReads() {
        val s = WideViewStore("tip\ncom.example.reader\n")
        assertTrue(s.tipShown)
        assertTrue(s.isOn("com.example.reader"))
        assertTrue(s.isOn(netflix))
    }
}
