package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.5: hand tracking's camera service starts only while a Pocket XR screen is showing. */
class ScreenCountTest {
    @Test
    fun nothingShowsUntilAScreenStarts() {
        assertFalse(ScreenCount().showing)
    }

    @Test
    fun twoScreensAndOneStops() {
        val s = ScreenCount()
        s.onStart() // the phone screen
        s.onStart() // the Space on the glasses
        s.onStop()
        assertTrue(s.showing)
        s.onStop()
        assertFalse(s.showing)
    }

    @Test
    fun aStopWithoutItsStartCannotHideALaterScreen() {
        val s = ScreenCount()
        s.onStop()
        assertEquals(0, s.count)
        s.onStart()
        assertTrue(s.showing)
    }
}
