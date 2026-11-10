package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.SteadyMode.Glasses
import com.hayeco.flipspaces.core.spatial.SteadyMode.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.0 (docs/6DOF-PLAN.md): Steady mode in 6DoF keeps the Aura's Spatial Anchor on, set last, put off first. */
class SteadySixDofTest {
    private fun g(stereo: Int = 0, ultrawide: Int = 0, anchor: Int = 0, size: Float = 1.2f, max: Float = 1.2f) =
        Glasses(stereo, ultrawide, anchor, size, max)

    @Test
    fun onGoesTo2dThenUltrawideThenSizeThenAnchorLast() {
        assertEquals(Step.LEAVE_3D, SteadyMode.next(true, g(stereo = 1), sixDof = true))
        assertEquals(Step.ULTRAWIDE_ON, SteadyMode.next(true, g(), sixDof = true))
        assertEquals(Step.SCREEN_MAX, SteadyMode.next(true, g(ultrawide = 1, size = 1.0f), sixDof = true))
        assertEquals(Step.ANCHOR_ON, SteadyMode.next(true, g(ultrawide = 1), sixDof = true))
        assertEquals(Step.DONE, SteadyMode.next(true, g(ultrawide = 1, anchor = 1), sixDof = true))
    }

    @Test
    fun anAnchorAlreadyOnIsKept() {
        // Plain Steady mode turns it off first; the 6DoF kind leaves it on and goes on with the rest.
        assertEquals(Step.ANCHOR_OFF, SteadyMode.next(true, g(stereo = 1, anchor = 1)))
        assertEquals(Step.LEAVE_3D, SteadyMode.next(true, g(stereo = 1, anchor = 1), sixDof = true))
    }

    @Test
    fun offPutsTheAnchorOffBeforeUltrawide() {
        assertEquals(Step.ANCHOR_OFF, SteadyMode.next(false, g(ultrawide = 1, anchor = 1), sixDof = true))
        assertEquals(Step.ULTRAWIDE_OFF, SteadyMode.next(false, g(ultrawide = 1), sixDof = true))
        assertEquals(Step.DONE, SteadyMode.next(false, g(), sixDof = true))
    }

    @Test
    fun plainSteadyModeOffLeavesTheAnchorAlone() {
        assertEquals(Step.ULTRAWIDE_OFF, SteadyMode.next(false, g(ultrawide = 1, anchor = 1)))
    }

    @Test
    fun waitsForTheAnchorRead() {
        assertEquals(Step.WAIT, SteadyMode.next(true, Glasses(0, 0, null, 1.2f, 1.2f), sixDof = true))
    }

    @Test
    fun onlyTheAuraHoldsIn6Dof() {
        assertTrue(SteadyMode.sixDofOn(GlassesModel.AURA))
        assertFalse(SteadyMode.sixDofOn(GlassesModel.ONE))
        assertFalse(SteadyMode.sixDofOn(null))
    }

    @Test
    fun theCommandsSendAnchorOnAndReadItBack() {
        val sent = ArrayList<ByteArray>()
        val c = GlassesDisplayCommands(object : GlassesDisplayCommands.Io {
            override fun send(bytes: ByteArray): Boolean { sent += bytes; return true }
            override fun log(line: String) {}
        })
        // Nothing read yet: it waits and only reads.
        assertEquals(Step.WAIT, c.steady(true, 0L, sixDof = true))
        assertTrue(sent.isNotEmpty())
        assertFalse("no Spatial Anchor set before the glasses answer", sent.any { type(it) == GlassesControl.SPATIAL_ANCHOR_SET })
    }

    private fun type(b: ByteArray): Int = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
}
