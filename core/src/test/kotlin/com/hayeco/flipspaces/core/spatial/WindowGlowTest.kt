package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.2: Window glow scales the focused window's glow from off to the look it always had. */
class WindowGlowTest {
    @Test
    fun theDefaultIsTheFullLook() {
        assertEquals(100, WindowGlow.DEFAULT_PERCENT)
        assertEquals(1f, WindowGlow.scale(WindowGlow.DEFAULT_PERCENT), 0f)
        // 0.32 is the focused glow's strength (HALO_ALPHA): unchanged at the default.
        assertEquals(0.32f, WindowGlow.alpha(0.32f, WindowGlow.DEFAULT_PERCENT), 0f)
        assertTrue(WindowGlow.shown(WindowGlow.DEFAULT_PERCENT))
    }

    @Test
    fun offDrawsNothing() {
        assertEquals(0f, WindowGlow.scale(0), 0f)
        assertEquals(0f, WindowGlow.alpha(0.32f, 0), 0f)
        assertFalse(WindowGlow.shown(0))
    }

    @Test
    fun theOpacityScalesInProportion() {
        assertEquals(0.5f, WindowGlow.scale(50), 1e-6f)
        assertEquals(0.16f, WindowGlow.alpha(0.32f, 50), 1e-6f)
        assertEquals(0.054f, WindowGlow.alpha(0.54f, 10), 1e-6f)
        assertTrue(WindowGlow.shown(1))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(0, WindowGlow.clamp(-20))
        assertEquals(100, WindowGlow.clamp(250))
        assertEquals(1f, WindowGlow.scale(250), 0f)
        assertEquals(0f, WindowGlow.scale(-5), 0f)
        assertFalse(WindowGlow.shown(-5))
        // The glow plus the grab band's added strength never goes past fully opaque.
        assertEquals(1f, WindowGlow.alpha(1.4f, 100), 0f)
    }
}
