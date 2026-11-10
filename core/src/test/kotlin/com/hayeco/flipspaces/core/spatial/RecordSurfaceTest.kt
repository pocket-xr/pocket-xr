package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.11: Record on the Flip 7 (Xclipse 950) failed every time with EGL 0x3009, the encoder's config unlike the Space's. */
class RecordSurfaceTest {
    /** GLSurfaceView's default config, the Space's own: RGB888, no alpha, 16 bit depth, not recordable. */
    private val space = EglConfigSizes(8, 8, 8, 0, 16, 0, 0, recordable = false)
    private val rgba = EglConfigSizes(8, 8, 8, 8, 0, 0, 0, recordable = true)
    private val rgbaDepth24 = EglConfigSizes(8, 8, 8, 8, 24, 8, 0, recordable = true)
    private val twin = space.copy(recordable = true)

    @Test
    fun `the Flip 7's case - no recordable twin - tries the Space's own config before an unlike recordable one`() {
        // 1.1.5 used recordable 0 (RGBA8888, no depth) straight away; the Xclipse driver refused it with the context.
        val order = RecordSurface.order(space, listOf(rgba, rgbaDepth24))
        assertEquals(listOf(RecordSurface.OWN, 0, 1), order)
    }

    @Test
    fun `a recordable twin of the Space's config goes first, then the own one`() {
        val order = RecordSurface.order(space, listOf(rgba, twin, rgbaDepth24))
        assertEquals(listOf(1, RecordSurface.OWN, 0, 2), order)
    }

    @Test
    fun `an own config that is recordable is the only one needed`() {
        assertEquals(listOf(RecordSurface.OWN), RecordSurface.order(twin, listOf(rgba, twin)))
    }

    @Test
    fun `no recordable configs at all still tries the own one`() {
        assertEquals(listOf(RecordSurface.OWN), RecordSurface.order(space, emptyList()))
    }

    @Test
    fun `alpha, depth, stencil or samples that differ are not the same buffers`() {
        assertFalse(space.sameBuffers(rgba))
        assertFalse(space.sameBuffers(space.copy(depth = 24)))
        assertFalse(space.sameBuffers(space.copy(stencil = 8)))
        assertFalse(space.sameBuffers(space.copy(samples = 4)))
        assertTrue(space.sameBuffers(twin))
    }

    @Test
    fun `a recording that never started says so instead of nothing`() {
        assertEquals("The recording couldn't start on this phone. Nothing was saved.", RecordSurface.notice(false, 0, 0.0))
    }

    @Test
    fun `saved and empty recordings keep their lines`() {
        assertEquals("Saved to Movies/Pocket XR (12 s)", RecordSurface.notice(true, 360, 12.2))
        assertEquals("The recording didn't work on this phone. Nothing was saved.", RecordSurface.notice(false, 30, 1.0))
    }
}
