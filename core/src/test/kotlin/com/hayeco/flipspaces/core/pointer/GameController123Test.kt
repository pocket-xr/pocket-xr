package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.3 (issue 59): the phone's game controller: its layout, sticks and buttons, the gamepad's reports and the touches. */
class GameController123Test {
    private fun u16(p: ByteArray, at: Int) = (p[at].toInt() and 0xFF) or ((p[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(p: ByteArray, at: Int) = u16(p, at) or (u16(p, at + 2) shl 16)

    @Test
    fun everyControlIsInsideItsClusterAndFindable() {
        for (places in listOf(GameControllerLayout.LEFT, GameControllerLayout.RIGHT)) for (p in places) {
            assertTrue(p.control.label, p.x >= 0f && p.y >= 0f && p.x + p.w <= 1.0001f && p.y + p.h <= 1.0001f)
            assertEquals(p.control.label, p, places.first { it.contains(p.centreX, p.centreY) })
        }
        val all = (GameControllerLayout.LEFT + GameControllerLayout.RIGHT).map { it.control }
        assertEquals(PadControl.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun aStickFollowsTheFingerAndStopsAtItsRim() {
        val left = GameControllerPresses(GameControllerLayout.LEFT, aspect = 1f)
        val stick = GameControllerLayout.LEFT.single { it.control == PadControl.LEFT_STICK }
        assertTrue(left.down(0, stick.centreX, stick.centreY))
        assertEquals(0f, left.state().leftX, 1e-6f)
        assertTrue(PadControl.LEFT_STICK in left.state().sticksHeld)
        val r = minOf(stick.w, stick.h) / 2f
        left.move(0, stick.centreX + r / 2f, stick.centreY)
        assertEquals(0.5f, left.state().leftX, 1e-4f)
        left.move(0, stick.centreX, stick.centreY - 5f * r)
        assertEquals(-1f, left.state().leftY, 1e-4f)
        assertEquals(0f, left.state().leftX, 1e-4f)
        left.up(0)
        assertEquals(ControllerState(), left.state())
    }

    @Test
    fun buttonsHoldAndSlide() {
        val right = GameControllerPresses(GameControllerLayout.RIGHT)
        val a = GameControllerLayout.RIGHT.single { it.control == PadControl.A }
        val b = GameControllerLayout.RIGHT.single { it.control == PadControl.B }
        right.down(1, a.centreX, a.centreY)
        assertEquals(setOf(PadControl.A), right.state().buttons)
        right.move(1, b.centreX, b.centreY)
        assertEquals(setOf(PadControl.B), right.state().buttons)
        right.up(1)
        assertTrue(right.state().buttons.isEmpty())
    }

    @Test
    fun theTwoClustersMakeOneController() {
        val merged = GameControllerPresses.merge(
            ControllerState(buttons = setOf(PadControl.L1), sticksHeld = setOf(PadControl.LEFT_STICK), leftX = 0.3f),
            ControllerState(buttons = setOf(PadControl.A), rightY = -1f),
        )
        assertEquals(setOf(PadControl.L1, PadControl.A), merged.buttons)
        assertEquals(0.3f, merged.leftX, 0f)
        assertEquals(-1f, merged.rightY, 0f)
    }

    @Test
    fun theGamepadReportCarriesSticksAndButtons() {
        val r = UhidGamepad.report(ControllerState(buttons = setOf(PadControl.A, PadControl.START), leftX = -1f, leftY = 1f))
        assertEquals(UhidGamepad.REPORT_BYTES, r.size)
        assertEquals(0, r[0].toInt() and 0xFF)
        assertEquals(255, r[1].toInt() and 0xFF)
        assertEquals(128, r[2].toInt() and 0xFF)
        assertEquals(128, r[3].toInt() and 0xFF)
        assertEquals(8, r[4].toInt())
        assertEquals((1 shl 0) or (1 shl 11), u16(r, 5))
        assertEquals(128, UhidGamepad.axis(Float.NaN).toInt() and 0xFF)
    }

    @Test
    fun theUhidEventsAreTheKernelsLayout() {
        val c = UhidGamepad.create()
        assertEquals(4 + 128 + 64 + 64 + 2 + 2 + 16 + 4096, c.size)
        assertEquals(UhidGamepad.UHID_CREATE2, u32(c, 0))
        assertEquals(UhidGamepad.NAME, String(c, 4, UhidGamepad.NAME.length))
        val at = 4 + 128 + 64 + 64
        assertEquals(UhidGamepad.DESCRIPTOR.size, u16(c, at))
        assertEquals(UhidGamepad.BUS_USB, u16(c, at + 2))
        assertEquals(UhidGamepad.VENDOR, u32(c, at + 4))
        assertEquals(UhidGamepad.PRODUCT, u32(c, at + 8))
        assertEquals(0x05, c[at + 20].toInt())
        val i = UhidGamepad.input(byteArrayOf(1, 2, 3))
        assertEquals(4 + 2 + 4096, i.size)
        assertEquals(UhidGamepad.UHID_INPUT2, u32(i, 0))
        assertEquals(3, u16(i, 4))
        assertEquals(3, i[8].toInt())
    }

    @Test
    fun theDescriptorsInputIsSevenBytes() {
        // 4 axes of 8 bits, 4 + 4 bits of hat and padding, 16 buttons of 1 bit.
        assertEquals(4 * 8 + 4 + 4 + 16, UhidGamepad.REPORT_BYTES * 8)
        assertEquals(0xC0, UhidGamepad.DESCRIPTOR.last().toInt() and 0xFF)
    }

    @Test
    fun touchesFollowTheSpotsAndSticksDragFromThem() {
        val spots = mapOf(PadControl.A to TouchSpot(0.9f, 0.8f), PadControl.LEFT_STICK to TouchSpot(0.2f, 0.7f))
        val t = TouchSpots.touches(ControllerState(buttons = setOf(PadControl.A, PadControl.B), sticksHeld = setOf(PadControl.LEFT_STICK), leftX = 1f), spots, aspect = 2f)
        assertEquals(TouchSpot(0.9f, 0.8f), t[PadControl.A])
        assertFalse(PadControl.B in t)
        assertEquals(0.2f + TouchSpots.STICK_REACH / 2f, t[PadControl.LEFT_STICK]!!.u, 1e-6f)
        assertEquals(0.7f, t[PadControl.LEFT_STICK]!!.v, 1e-6f)
        // A stick not held makes no touch, even with a spot.
        assertFalse(PadControl.LEFT_STICK in TouchSpots.touches(ControllerState(), spots, 2f))
    }

    @Test
    fun multiTouchKeepsEachTouchsPointerId() {
        val p = TouchPointers()
        val a = TouchSpot(0.1f, 0.1f)
        val b = TouchSpot(0.9f, 0.9f)
        assertEquals(listOf(TouchPointers.Event(TouchPointers.ACTION_DOWN, intArrayOf(0), floatArrayOf(0.1f), floatArrayOf(0.1f))), p.next(mapOf(PadControl.A to a)))
        val second = p.next(mapOf(PadControl.A to a, PadControl.B to b)).single()
        assertEquals(TouchPointers.ACTION_POINTER_DOWN or (1 shl 8), second.action)
        assertEquals(listOf(0, 1), second.ids.toList())
        // A lifts first: the event still has both, then B keeps id 1 on its own.
        val lift = p.next(mapOf(PadControl.B to b)).single()
        assertEquals(TouchPointers.ACTION_POINTER_UP or (0 shl 8), lift.action)
        assertEquals(listOf(0, 1), lift.ids.toList())
        val moved = p.next(mapOf(PadControl.B to TouchSpot(0.8f, 0.9f))).single()
        assertEquals(TouchPointers.ACTION_MOVE, moved.action)
        assertEquals(listOf(1), moved.ids.toList())
        assertEquals(TouchPointers.ACTION_UP, p.cancel().single().action)
        assertFalse(p.down)
    }

    @Test
    fun theSpotStoreRemembersEachApp() {
        val s = TouchSpotStore()
        s.set("com.example.game", PadControl.A, TouchSpot(0.9f, 0.85f))
        s.set("com.example.game", PadControl.LEFT_STICK, TouchSpot(0.15f, 0.75f))
        val back = TouchSpotStore(s.encode())
        assertEquals(TouchSpot(0.9f, 0.85f), back.of("com.example.game")[PadControl.A])
        assertEquals(2, back.of("com.example.game").size)
        assertTrue(back.of("other.app").isEmpty())
    }

    @Test
    fun theMenuCyclesTheModes() {
        assertEquals(GameControllerMode.GAMEPAD, GameControllerMode.OFF.next())
        assertEquals(GameControllerMode.TOUCH, GameControllerMode.GAMEPAD.next())
        assertEquals(GameControllerMode.OFF, GameControllerMode.TOUCH.next())
    }
}
