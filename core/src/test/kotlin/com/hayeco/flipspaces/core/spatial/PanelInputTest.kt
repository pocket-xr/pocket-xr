package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelInputTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private fun pointer(settings: PointerSettings = PointerSettings()) = SpatialPointer(frustum, settings)

    // ---- the pointer's panel input ----

    @Test
    fun pointerOverContentHoversEntersMovesAndExits() {
        val p = pointer()
        val enter = p.frame(Quat.IDENTITY, layout).filterIsInstance<SpaceAction.Hover>().single()
        assertEquals(HoverPhase.ENTER, enter.phase)
        assertEquals(0.5f, enter.u, 1e-3f)
        // Same spot next frame: nothing new.
        assertTrue(p.frame(Quat.IDENTITY, layout).none { it is SpaceAction.Hover })
        val move = p.handle(PointerSignal.MoveBy(25f, 0f), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Hover>().single()
        assertEquals(HoverPhase.MOVE, move.phase)
        assertTrue(move.u > 0.5f)
        // Off the panel: a last move at the edge it left by (here the right, u 1), then an exit at the same spot, so a PC
        // screen's cursor reaches the edge and stays there.
        val off = p.handle(PointerSignal.MoveBy(1e5f, 0f), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Hover>()
        assertEquals(listOf(HoverPhase.MOVE, HoverPhase.EXIT), off.map { it.phase })
        assertEquals(1f, off[0].u, 1e-3f)
        val exit = off.last()
        assertEquals(1f, exit.u, 1e-3f)
        assertEquals(1, exit.panelId)
    }

    @Test
    fun aPressEndsHoverAndFocusesThePanel() {
        val p = pointer()
        p.frame(Quat.IDENTITY, layout)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertEquals(HoverPhase.EXIT, down.filterIsInstance<SpaceAction.Hover>().single().phase)
        assertEquals(SpaceAction.Focus(1), down.filterIsInstance<SpaceAction.Focus>().single())
        assertTrue(down.indexOfFirst { it is SpaceAction.Hover } < down.indexOfFirst { it is SpaceAction.Touch })
        // While pressed: no hover events.
        assertTrue(p.frame(Quat.fromYawPitch(3f, 0f), layout).none { it is SpaceAction.Hover })
    }

    @Test
    fun noHoverUnderTheOpenLauncher() {
        val p = pointer()
        p.frame(Quat.IDENTITY, layout)
        p.pressFilter = { true }
        assertEquals(HoverPhase.EXIT, p.frame(Quat.IDENTITY, layout).filterIsInstance<SpaceAction.Hover>().single().phase)
        assertTrue(p.frame(Quat.fromYawPitch(2f, 0f), layout).none { it is SpaceAction.Hover })
    }

    @Test
    fun hoverCanBeTurnedOff() {
        val p = pointer(PointerSettings(hoverEvents = false))
        assertTrue(p.frame(Quat.IDENTITY, layout).none { it is SpaceAction.Hover })
    }

    @Test
    fun trackpadScrollOverContentIsTheMouseWheel() {
        val p = pointer()
        val begin = p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertTrue(begin.none { it is SpaceAction.Touch })
        assertEquals(Phase.BEGIN, begin.filterIsInstance<SpaceAction.Wheel>().single().phase)
        val update = p.handle(PointerSignal.Scroll(0f, 90f, Phase.UPDATE), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Wheel>().single()
        assertEquals(90f, update.dy, 0f)
        assertEquals(1, update.panelId)
        val end = p.handle(PointerSignal.Scroll(0f, 120f, Phase.END), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Wheel>().single()
        assertEquals(Phase.END, end.phase)
        assertFalse(p.busy)
    }

    @Test
    fun trackpadPinchOverContentZoomsInsideTheApp() {
        val p = pointer()
        val begin = p.handle(PointerSignal.Scale(1f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertEquals(Phase.BEGIN, begin.filterIsInstance<SpaceAction.Zoom>().single().phase)
        val end = p.handle(PointerSignal.Scale(1.5f, Phase.END), Quat.IDENTITY, layout, pixels)
        assertEquals(1.5f, end.filterIsInstance<SpaceAction.Zoom>().single().factor, 0f)
        assertTrue(end.none { it is SpaceAction.Layout })
    }

    @Test
    fun trackpadPinchOnTheTitleBarStillScalesThePanel() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(0f, -(panel.heightDeg / 2f + 1f) / 0.04f), Quat.IDENTITY, layout, pixels)
        assertEquals(PanelZone.TITLE_BAR, p.hover?.zone)
        p.handle(PointerSignal.Scale(1f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        val end = p.handle(PointerSignal.Scale(1.5f, Phase.END), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Layout>().single()
        assertEquals(60f, end.layout.panel(1)!!.widthDeg, 1e-3f)
    }

    @Test
    fun cancelMidZoomLiftsBothFingers() {
        val p = pointer()
        p.handle(PointerSignal.Scale(1f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        p.handle(PointerSignal.Scale(1.3f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
        val out = p.handle(PointerSignal.Cancel, Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Zoom>().single()
        assertEquals(Phase.END, out.phase)
        assertEquals(1.3f, out.factor, 0f)
    }

    @Test
    fun twoFingerTapIsALongPressOnTheContent() {
        val p = pointer()
        val out = p.handle(PointerSignal.SecondaryTap, Quat.IDENTITY, layout, pixels)
        val lp = out.filterIsInstance<SpaceAction.LongPress>().single()
        assertEquals(1, lp.panelId)
        assertTrue(SpaceAction.Focus(1) in out)
        // Over nothing: nothing.
        val away = pointer()
        away.handle(PointerSignal.MoveBy(1e5f, 0f), Quat.IDENTITY, layout, pixels)
        assertTrue(away.handle(PointerSignal.SecondaryTap, Quat.IDENTITY, layout, pixels).none { it is SpaceAction.LongPress })
    }

    @Test
    fun theBackButtonAndTheBackGesture() {
        val p = pointer()
        // The Back button: left end of the title bar.
        val yaw = -panel.widthDeg / 2f + 0.5f
        val pitch = panel.heightDeg / 2f + 1f
        p.handle(PointerSignal.MoveBy(yaw / 0.04f, -pitch / 0.04f), Quat.IDENTITY, layout, pixels)
        assertEquals(PanelZone.BACK, p.hover?.zone)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertTrue(SpaceAction.Back(1) in down)
        assertTrue(down.none { it is SpaceAction.Touch })
        val up = p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
        assertTrue(up.none { it is SpaceAction.Touch || it is SpaceAction.Layout })
        // The gesture: Back for the panel under the ray, or for the focused one.
        p.centre()
        assertTrue(SpaceAction.Back(1) in p.handle(PointerSignal.Back, Quat.IDENTITY, layout, pixels))
        p.handle(PointerSignal.MoveBy(1e5f, 0f), Quat.IDENTITY, layout, pixels)
        assertTrue(SpaceAction.Back(null) in p.handle(PointerSignal.Back, Quat.IDENTITY, layout, pixels))
    }

    // ---- the events each app gets ----

    @Test
    fun touchesBecomeTouchscreenEventsAtDisplayPixels() {
        val m = PanelInputMapper()
        val down = m.map(SpaceAction.Touch(1, TouchPhase.DOWN, 0.25f, 0.5f), 1280, 720).single()
        assertEquals(PanelMotion.ACTION_DOWN, down.action)
        assertEquals(PanelMotion.SOURCE_TOUCHSCREEN, down.source)
        assertEquals(320f, down.xs.single(), 0f)
        assertEquals(360f, down.ys.single(), 0f)
        assertTrue(down.startsGesture)
        // Clamped to the display.
        val off = m.map(SpaceAction.Touch(1, TouchPhase.MOVE, 1.2f, -0.1f), 1280, 720).single()
        assertEquals(1279f, off.xs.single(), 0f)
        assertEquals(0f, off.ys.single(), 0f)
        assertFalse(off.startsGesture)
        assertEquals(PanelMotion.ACTION_CANCEL, m.map(SpaceAction.Touch(1, TouchPhase.CANCEL, 0.5f, 0.5f), 1280, 720).single().action)
    }

    @Test
    fun hoverIsAMouse() {
        val m = PanelInputMapper()
        val enter = m.map(SpaceAction.Hover(1, HoverPhase.ENTER, 0.5f, 0.5f), 1280, 720).single()
        assertEquals(PanelMotion.ACTION_HOVER_ENTER, enter.action)
        assertEquals(PanelMotion.SOURCE_MOUSE, enter.source)
        assertEquals(PanelMotion.ACTION_HOVER_MOVE, m.map(SpaceAction.Hover(1, HoverPhase.MOVE, 0.5f, 0.5f), 1280, 720).single().action)
        assertEquals(PanelMotion.ACTION_HOVER_EXIT, m.map(SpaceAction.Hover(1, HoverPhase.EXIT, 0.5f, 0.5f), 1280, 720).single().action)
    }

    @Test
    fun wheelSendsWholeNotchesWithNaturalDirection() {
        val m = PanelInputMapper(pxPerNotch = 40f)
        assertTrue(m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 0f, Phase.BEGIN), 1280, 720).isEmpty())
        // Not a whole notch yet.
        assertTrue(m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 30f, Phase.UPDATE), 1280, 720).isEmpty())
        // Fingers down 100 px in all: two notches up (show what is above).
        val up = m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 100f, Phase.UPDATE), 1280, 720).single()
        assertEquals(PanelMotion.ACTION_SCROLL, up.action)
        assertEquals(PanelMotion.SOURCE_MOUSE, up.source)
        assertEquals(2f, up.vScroll, 0f)
        assertEquals(0f, up.hScroll, 0f)
        // Back up to 10 px (0.25 notch in all): one notch down, the rest waits.
        assertEquals(-1f, m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 10f, Phase.UPDATE), 1280, 720).single().vScroll, 0f)
        // Fingers left: scroll right.
        assertEquals(1f, m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, -45f, 10f, Phase.END), 1280, 720).single().hScroll, 0f)
        // A new gesture starts from zero.
        m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 0f, Phase.BEGIN), 1280, 720)
        assertEquals(1f, m.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 40f, Phase.UPDATE), 1280, 720).single().vScroll, 0f)
    }

    @Test
    fun appZoomIsATwoFingerPinch() {
        val m = PanelInputMapper(pinchSpread = 0.1f)
        val begin = m.map(SpaceAction.Zoom(1, 0.5f, 0.5f, 1f, Phase.BEGIN), 1280, 720)
        assertEquals(listOf(PanelMotion.ACTION_DOWN, PanelMotion.ACTION_POINTER_DOWN or (1 shl 8)), begin.map { it.action })
        assertEquals(1, begin[0].pointerCount)
        assertEquals(2, begin[1].pointerCount)
        assertEquals(640f - 72f, begin[1].xs[0], 1e-3f)
        assertEquals(640f + 72f, begin[1].xs[1], 1e-3f)
        val update = m.map(SpaceAction.Zoom(1, 0.5f, 0.5f, 2f, Phase.UPDATE), 1280, 720).single()
        assertEquals(PanelMotion.ACTION_MOVE, update.action)
        assertEquals(288f, update.xs[1] - update.xs[0], 1e-3f)
        val end = m.map(SpaceAction.Zoom(1, 0.5f, 0.5f, 2f, Phase.END), 1280, 720)
        assertEquals(listOf(PanelMotion.ACTION_MOVE, PanelMotion.ACTION_POINTER_UP or (1 shl 8), PanelMotion.ACTION_UP), end.map { it.action })
        // Fingers never leave the display.
        val wide = m.map(SpaceAction.Zoom(1, 0.95f, 0.5f, 5f, Phase.UPDATE), 1280, 720).single()
        assertEquals(1279f, wide.xs[1], 0f)
    }

    @Test
    fun longPressHoldsThenLifts() {
        val m = PanelInputMapper(longPressMs = 650)
        val events = m.map(SpaceAction.LongPress(1, 0.5f, 0.5f), 1280, 720)
        assertEquals(listOf(PanelMotion.ACTION_DOWN, PanelMotion.ACTION_UP), events.map { it.action })
        assertEquals(0L, events[0].delayMs)
        assertEquals(650L, events[1].delayMs)
        assertEquals(events[0].xs, events[1].xs)
    }

    @Test
    fun nonInputActionsMapToNothing() {
        assertTrue(PanelInputMapper().map(SpaceAction.Recentre, 1280, 720).isEmpty())
        assertTrue(PanelInputMapper().map(SpaceAction.Focus(1), 1280, 720).isEmpty())
    }

    // ---- keys and focus ----

    @Test
    fun keysGoToTheLauncherTheFocusedPanelOrTheSpace() {
        assertEquals(KeyTarget.Launcher, KeyRouting.target(launcherOpen = true, fullKeyboard = true, focusedPanelId = 1, panelIds = listOf(1)))
        assertEquals(KeyTarget.Panel(1), KeyRouting.target(launcherOpen = false, fullKeyboard = true, focusedPanelId = 1, panelIds = listOf(1, 2)))
        // The glasses' button is not a keyboard: it stays with the Space (long press recentres).
        assertEquals(KeyTarget.Space, KeyRouting.target(launcherOpen = false, fullKeyboard = false, focusedPanelId = 1, panelIds = listOf(1)))
        // No focused panel, or it was closed.
        assertEquals(KeyTarget.Space, KeyRouting.target(launcherOpen = false, fullKeyboard = true, focusedPanelId = null, panelIds = listOf(1)))
        assertEquals(KeyTarget.Space, KeyRouting.target(launcherOpen = false, fullKeyboard = true, focusedPanelId = 3, panelIds = listOf(1)))
    }

    @Test
    fun backGoesToTheNamedThenFocusedThenTopPanel() {
        assertEquals(2, KeyRouting.backTarget(named = 2, focusedPanelId = 1, panelIdsTopLast = listOf(1, 2, 3)))
        assertEquals(1, KeyRouting.backTarget(named = null, focusedPanelId = 1, panelIdsTopLast = listOf(1, 2, 3)))
        assertEquals(3, KeyRouting.backTarget(named = 9, focusedPanelId = 7, panelIdsTopLast = listOf(1, 2, 3)))
        assertNull(KeyRouting.backTarget(named = null, focusedPanelId = null, panelIdsTopLast = emptyList()))
    }

    @Test
    fun backButtonSitsAtTheTitleBarsLeftEnd() {
        val left = PanelHitTester.hit(Ray(Vec3.ZERO, Vec3.direction(-panel.widthDeg / 2f + 0.5f, panel.heightDeg / 2f + 1f)), panel)
        assertEquals(PanelZone.BACK, left?.zone)
        val middle = PanelHitTester.hit(Ray(Vec3.ZERO, Vec3.direction(0f, panel.heightDeg / 2f + 1f)), panel)
        assertEquals(PanelZone.TITLE_BAR, middle?.zone)
    }
}
