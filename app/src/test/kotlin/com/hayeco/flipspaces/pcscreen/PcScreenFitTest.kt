package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class PcScreenFitTest {
    @Test
    fun theFlip7PanelGetsBarsAtTheSides() {
        // The phone test's panel, 1786 x 976, is wider than 16:9.
        assertEquals(1735 to 976, PcScreenFit.fit(1786, 976, 1920, 1080))
    }

    @Test
    fun aTallBoxGetsBarsAboveAndBelow() {
        assertEquals(1080 to 607, PcScreenFit.fit(1080, 2520, 1920, 1080))
    }

    @Test
    fun anExactFitIsUnchanged() {
        assertEquals(1920 to 1080, PcScreenFit.fit(1920, 1080, 1920, 1080))
    }

    @Test
    fun theSpaceDrawsTheSameBoxThePcScreenWindowUses() {
        // 1735 of 1786 wide: 25.5 px of bar each side, as the window's own layout.
        val uv = PcScreenFit.uv(1786, 976, 1920, 1080)
        assertEquals(25.5f / 1786f, uv[0], 1e-4f)
        assertEquals(0f, uv[1], 1e-6f)
        assertEquals(1f - 25.5f / 1786f, uv[2], 1e-4f)
        assertEquals(1f, uv[3], 1e-6f)
    }
}
