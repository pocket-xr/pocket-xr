package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowShapeTest {
    private val window = SpacePanel(1, "Notes", "notes", "", yawDeg = 0f, pitchDeg = 0f, distanceM = PanelLimits.DEFAULT_DISTANCE, widthDeg = 60f, heightDeg = 20f)

    @Test fun eachPresetGivesItsShape() {
        for (preset in WindowShape.Preset.entries) {
            val p = WindowShape.shaped(window, preset.ratio)
            assertEquals(preset.name, preset.ratio, PanelDisplaySize.aspect(p), preset.ratio * 0.02f)
            assertEquals(preset, WindowShape.current(PanelDisplaySize.aspect(p)))
        }
    }

    @Test fun aShapeKeepsAboutTheSameSize() {
        val before = window.widthDeg * window.heightDeg
        val p = WindowShape.shaped(window, 4f / 3f)
        assertEquals(before, p.widthDeg * p.heightDeg, before * 0.15f)
    }

    @Test fun aShapeStaysInsideTheLimits() {
        val huge = window.copy(widthDeg = 99f, heightDeg = 60f)
        for (preset in WindowShape.Preset.entries) {
            val p = WindowShape.shaped(huge, preset.ratio)
            assertTrue(p.widthDeg <= PanelLimits.MAX_WIDTH_DEG + 0.01f && p.heightDeg <= PanelLimits.MAX_HEIGHT_DEG + 0.01f)
            assertTrue(p.widthDeg >= PanelLimits.MIN_SIZE_DEG - 0.01f && p.heightDeg >= PanelLimits.MIN_SIZE_DEG - 0.01f)
        }
    }

    @Test fun anOddShapeIsNoPreset() = assertNull(WindowShape.current(2.4f))
}
