package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HomeEvent
import com.hayeco.flipspaces.core.pointer.MouseReport
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PhysicalMouse
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unreleased (a tester's Bluetooth mouse looked dead): with the trackpad left on Spatial Mouse the phone's aim overwrote
 * every mouse move. Now a mouse that moved in the last [MOUSE_TAKES_OVER_MS] has the pointer, and the phone picks up
 * from where the pointer is once the mouse goes quiet.
 */
class MouseWinsSpatialMouseTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f, widthDeg = 20f, heightDeg = 12f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }
    private var now = 0L
    private val mouse = PhysicalMouse()

    /** Mouse counts a degree at the middle speed. */
    private val countsPerDeg = 1f / mouse.degPerCount

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, pixels)

    private fun SpatialPointer.tick(ms: Long = 16): List<SpaceAction> {
        now += ms
        return frame(Quat.IDENTITY, layout, now)
    }

    /** A mouse move of [yawDeg] right and [pitchDeg] up, through [PhysicalMouse] as the app sends it. */
    private fun SpatialPointer.moveMouse(yawDeg: Float, pitchDeg: Float = 0f): List<SpaceAction> =
        mouse.report(MouseReport(0, yawDeg * countsPerDeg, -pitchDeg * countsPerDeg, timeMs = now)).flatMap { send(it) }

    private fun SpatialPointer.aimed(): Angles = Angles.of(ray(Quat.IDENTITY).dir)

    private fun spatialMouse(mode: PadMode = PadMode.SPATIAL_MOUSE) = SpatialPointer(frustum).also {
        it.send(PadSignal.Mode(mode))
        it.send(PadSignal.Aim(0f, 0f))
        it.tick()
    }

    @Test
    fun theMouseMovesThePointerAndThePhoneDoesNotUndoIt() {
        val p = spatialMouse()
        p.send(PadSignal.Aim(10f, 0f))
        p.tick()
        assertEquals(10f, p.aimed().yawDeg, 0.05f)
        p.moveMouse(6f, 3f)
        assertTrue(p.mouseTookOver)
        assertEquals(16f, p.aimed().yawDeg, 0.05f)
        assertEquals(3f, p.aimed().pitchDeg, 0.05f)
        // The phone's aim keeps coming every sensor update for a second; it does not move the pointer.
        repeat(60) { i ->
            p.send(PadSignal.Aim(10f + (i % 3), -1f))
            p.tick()
        }
        assertEquals(16f, p.aimed().yawDeg, 0.05f)
        assertEquals(3f, p.aimed().pitchDeg, 0.05f)
        p.moveMouse(-4f)
        assertEquals(12f, p.aimed().yawDeg, 0.05f)
    }

    @Test
    fun aClickLandsWhereTheMouseMovedThePointer() {
        val p = spatialMouse()
        p.send(PadSignal.Aim(-25f, 0f))
        p.tick()
        assertEquals(null, p.hover)
        p.moveMouse(30f)
        p.send(PadSignal.Aim(-25f, 0f))
        p.tick()
        val down = mouse.report(MouseReport(PhysicalMouse.BUTTON_PRIMARY, timeMs = now)).flatMap { p.send(it) }
        val touch = down.filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(1, touch.panelId)
        assertEquals(TouchPhase.DOWN, touch.phase)
        assertTrue("the press is right of the panel's centre, at yaw 5, not at the phone's -25", touch.u > 0.6f)
    }

    @Test
    fun whenTheMouseGoesQuietThePhonePicksUpFromThePointer() {
        val p = spatialMouse()
        p.send(PadSignal.Aim(10f, 0f))
        p.tick()
        p.moveMouse(6f, 2f)
        // Quiet for just under the hold: still the mouse's.
        p.tick(MOUSE_TAKES_OVER_MS - 100)
        p.send(PadSignal.Aim(12f, 0f))
        assertTrue(p.mouseTookOver)
        assertEquals(16f, p.aimed().yawDeg, 0.05f)
        // Past it: the phone aims again, from where the pointer is (no jump back to 12).
        p.tick(200)
        assertFalse(p.mouseTookOver)
        p.send(PadSignal.Aim(12f, 0f))
        p.tick()
        assertEquals(16f, p.aimed().yawDeg, 0.05f)
        assertEquals(2f, p.aimed().pitchDeg, 0.05f)
        // And turns it by the phone's turn from there.
        p.send(PadSignal.Aim(15f, 1f))
        p.tick()
        assertEquals(19f, p.aimed().yawDeg, 0.05f)
        assertEquals(3f, p.aimed().pitchDeg, 0.05f)
    }

    @Test
    fun theMouseCanTakeItBackAgainAfterThePhone() {
        val p = spatialMouse()
        p.moveMouse(5f)
        p.tick(MOUSE_TAKES_OVER_MS + 100)
        p.send(PadSignal.Aim(3f, 0f))
        p.tick()
        assertEquals(5f, p.aimed().yawDeg, 0.05f)
        p.send(PadSignal.Aim(8f, 0f))
        p.tick()
        assertEquals(10f, p.aimed().yawDeg, 0.05f)
        p.moveMouse(-7f)
        p.send(PadSignal.Aim(20f, 0f))
        p.tick()
        assertEquals(3f, p.aimed().yawDeg, 0.05f)
    }

    @Test
    fun homeHeldStillRecentresInFront() {
        val p = spatialMouse()
        p.moveMouse(12f)
        p.tick(MOUSE_TAKES_OVER_MS + 100)
        p.send(PadSignal.Home(HomeEvent.LONG_PRESS))
        p.send(PadSignal.Aim(30f, 0f))
        p.tick()
        assertEquals(0f, p.aimed().yawDeg, 0.05f)
    }

    @Test
    fun aClickOrTheWheelAloneDoesNotTakeThePointer() {
        val p = spatialMouse()
        mouse.report(MouseReport(PhysicalMouse.BUTTON_PRIMARY, timeMs = now)).forEach { p.send(it) }
        mouse.report(MouseReport(0, timeMs = now)).forEach { p.send(it) }
        mouse.report(MouseReport(0, wheelV = 1f, timeMs = now)).forEach { p.send(it) }
        assertFalse(p.mouseTookOver)
        p.send(PadSignal.Aim(7f, 0f))
        p.tick()
        assertEquals(7f, p.aimed().yawDeg, 0.05f)
    }

    @Test
    fun otherPagesAreAsBefore() {
        val p = spatialMouse(PadMode.VERTICAL)
        p.moveMouse(6f)
        assertFalse(p.mouseTookOver)
        assertEquals(6f, p.aimed().yawDeg, 0.05f)
        // On the Vertical Trackpad the phone's aim never moved the pointer.
        p.send(PadSignal.Aim(30f, 0f))
        p.tick()
        assertEquals(6f, p.aimed().yawDeg, 0.05f)
    }
}
