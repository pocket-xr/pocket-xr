package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.3 (issue 52): Pocket mode is the lock overlay, black and dim until held; the lock itself looks as before. */
class PocketMode123Test {
    @Test
    fun theLockLooksAsBefore() {
        assertEquals(0.6f, PocketMode.iconAlpha(pocket = false, progress = 0f, pressed = false), 1e-6f)
        assertEquals(1f, PocketMode.iconAlpha(pocket = false, progress = 1f, pressed = true), 1e-6f)
        assertTrue(PocketMode.showsHint(pocket = false, pressed = false))
        assertEquals("Hold to unlock", PocketMode.hint(pocket = false))
    }

    @Test
    fun pocketModeIsBlackUntilTheLockIsHeld() {
        assertEquals(PocketMode.IDLE_ICON_ALPHA, PocketMode.iconAlpha(pocket = true, progress = 0f, pressed = false), 1e-6f)
        assertFalse(PocketMode.showsHint(pocket = true, pressed = false))
        assertTrue(PocketMode.showsHint(pocket = true, pressed = true))
        assertTrue(PocketMode.iconAlpha(pocket = true, progress = 0.5f, pressed = true) > 0.5f)
        assertEquals(1f, PocketMode.iconAlpha(pocket = true, progress = 1f, pressed = true), 1e-6f)
    }

    @Test
    fun theScreenGoesToItsLowestAndBack() {
        assertEquals(0f, PocketMode.BRIGHTNESS, 0f)
        assertEquals(-1f, PocketMode.NO_OVERRIDE, 0f)
    }
}
