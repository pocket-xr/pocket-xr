package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.Look
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.2.2: Window glow's colour, a preset or a custom colour, and how the intensity scales it. */
class WindowGlowColorTest {
    @Test
    fun theDefaultIsTheBlueItAlwaysWas() {
        assertEquals(Look.ACCENT, WindowGlow.DEFAULT_COLOR)
        assertEquals("Blue", WindowGlow.PRESETS.first().name)
        assertEquals(WindowGlow.DEFAULT_COLOR, WindowGlow.PRESETS.first().argb)
        assertEquals("Blue", WindowGlow.name(WindowGlow.DEFAULT_COLOR))
        // At the default colour and intensity the glow is exactly what the renderer drew before (look(ACCENT, alpha)).
        assertArrayEquals(Look.rgba(Look.ACCENT, 0.32f), WindowGlow.rgba(WindowGlow.DEFAULT_COLOR, 0.32f, WindowGlow.DEFAULT_PERCENT), 0f)
    }

    @Test
    fun thePresetsAreDistinctAndOpaque() {
        assertEquals(listOf("Blue", "White", "Purple", "Pink", "Green", "Amber", "Red"), WindowGlow.PRESETS.map { it.name })
        assertEquals(WindowGlow.PRESETS.size, WindowGlow.PRESETS.map { it.argb }.toSet().size)
        WindowGlow.PRESETS.forEach {
            assertEquals(0xFF, (it.argb ushr 24) and 0xFF)
            assertEquals(it, WindowGlow.preset(it.argb))
        }
    }

    @Test
    fun anyOtherColourIsCustom() {
        assertNull(WindowGlow.preset(0xFF123456.toInt()))
        assertEquals("Custom", WindowGlow.name(0xFF123456.toInt()))
    }

    @Test
    fun aColourIsAlwaysOpaque() {
        // A see-through colour saved some other way does not dim the glow behind the intensity's back.
        assertEquals(0xFF5A93FF.toInt(), WindowGlow.color(0x005A93FF))
        assertEquals(WindowGlow.PRESETS[0], WindowGlow.preset(0x105A93FF))
        assertEquals(1f, WindowGlow.rgba(0x00FFFFFF, 1f, 100)[3], 0f)
    }

    @Test
    fun theIntensityScalesOnlyTheOpacity() {
        val red = 0xFFFF5C5C.toInt()
        val half = WindowGlow.rgba(red, 0.54f, 50)
        assertEquals(1f, half[0], 1e-6f)
        assertEquals(0x5C / 255f, half[1], 1e-6f)
        assertEquals(0x5C / 255f, half[2], 1e-6f)
        assertEquals(0.27f, half[3], 1e-6f)
        val off = WindowGlow.rgba(red, 0.54f, 0)
        assertEquals(0f, off[3], 0f)
        assertEquals(half[0], off[0], 0f)
        // The glow plus the grab band never goes past opaque, whatever the colour.
        assertEquals(1f, WindowGlow.rgba(red, 1.4f, 100)[3], 0f)
    }

    @Test
    fun customColoursAreBrightHues() {
        assertEquals(0xFFFF0000.toInt(), WindowGlow.custom(0f, 1f))
        assertEquals(0xFF00FF00.toInt(), WindowGlow.custom(120f, 1f))
        assertEquals(0xFF0000FF.toInt(), WindowGlow.custom(240f, 1f))
        assertEquals(0xFFFF0000.toInt(), WindowGlow.custom(360f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), WindowGlow.custom(200f, 0f))
        assertEquals(0xFFFF8080.toInt(), WindowGlow.custom(0f, 0.5f))
        // Out of range values wrap (hue) or clamp (colour).
        assertEquals(WindowGlow.custom(300f, 1f), WindowGlow.custom(-60f, 2f))
    }

    @Test
    fun theCustomSlidersStartAtTheSavedColour() {
        val (h, s) = WindowGlow.hueSaturation(0xFF00FF00.toInt())
        assertEquals(120f, h, 1e-3f)
        assertEquals(1f, s, 1e-6f)
        assertEquals(0f, WindowGlow.hueSaturation(0xFFFFFFFF.toInt()).second, 0f)
        // Round trip: the default blue's hue and colour give the default blue back.
        val (bh, bs) = WindowGlow.hueSaturation(WindowGlow.DEFAULT_COLOR)
        assertEquals(WindowGlow.DEFAULT_COLOR, WindowGlow.custom(bh, bs))
        val (ph, ps) = WindowGlow.hueSaturation(0xFFFF6FB7.toInt())
        assertEquals(0xFFFF6FB7.toInt(), WindowGlow.custom(ph, ps))
    }
}
