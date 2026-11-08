package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPointerTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private fun pointer() = SpatialPointer(frustum)

    @Test
    fun headGazeTapsTheCentreOfThePanelAhead() {
        val p = pointer()
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        val touch = down.filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(TouchPhase.DOWN, touch.phase)
        assertEquals(0.5f, touch.u, 1e-4f)
        assertEquals(0.5f, touch.v, 1e-4f)
        val up = p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>()
        assertEquals(TouchPhase.UP, up.last().phase)
        assertFalse(p.busy)
    }

    @Test
    fun turningTheHeadMovesTheRayToAnotherPanel() {
        val right = panel.copy(id = 2, yawDeg = 50f)
        val l = SpaceLayout(listOf(panel, right))
        val p = pointer()
        p.frame(Quat.fromYawPitch(50f, 0f), l)
        assertEquals(2, p.hover?.panel?.id)
        p.frame(Quat.IDENTITY, l)
        assertEquals(1, p.hover?.panel?.id)
    }

    @Test
    fun heldTouchDragsAsTheHeadMoves() {
        val p = pointer()
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        val moves = p.frame(Quat.fromYawPitch(5f, 0f), layout).filterIsInstance<SpaceAction.Touch>()
        assertEquals(TouchPhase.MOVE, moves.single().phase)
        assertTrue(moves.single().u > 0.5f)
    }

    @Test
    fun trackpadNudgesTheRayRelativeToTheHead() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(250f, 0f), Quat.IDENTITY, layout, pixels) // 10 degrees right
        assertEquals(10f, p.offsetYaw, 1e-3f)
        val hit = p.hover!!
        assertTrue(hit.u > 0.6f)
        // The offset clamps to the view.
        p.handle(PointerSignal.MoveBy(100000f, -100000f), Quat.IDENTITY, layout, pixels)
        assertEquals(frustum.horizontalFovDeg / 2f, p.offsetYaw, 1e-3f)
        assertEquals(frustum.verticalFovDeg / 2f, p.offsetPitch, 1e-3f)
    }

    @Test
    fun handsAimThroughThePictureOrOnlyClick() {
        val p = pointer()
        p.handle(PointerSignal.MoveTo(1920f, 540f), Quat.IDENTITY, layout, pixels)
        assertEquals(frustum.horizontalFovDeg / 2f, p.offsetYaw, 0.01f)
        val gazeOnly = SpatialPointer(frustum, PointerSettings(handsSteer = false))
        gazeOnly.handle(PointerSignal.MoveTo(1920f, 540f), Quat.IDENTITY, layout, pixels)
        assertEquals(0f, gazeOnly.offsetYaw, 0f)
    }

    @Test
    fun titleBarGrabMovesThePanelWithTheHead() {
        val p = pointer()
        val bar = panel.heightDeg / 2f + 1f
        p.handle(PointerSignal.MoveBy(0f, -bar / 0.04f), Quat.IDENTITY, layout, pixels)
        assertEquals(PanelZone.TITLE_BAR, p.hover?.zone)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertTrue(down.none { it is SpaceAction.Touch })
        val moved = p.frame(Quat.fromYawPitch(30f, 0f), layout).filterIsInstance<SpaceAction.Layout>().last()
        assertFalse(moved.commit)
        assertEquals(30f, moved.layout.panel(1)!!.yawDeg, 0.5f)
        val up = p.handle(PointerSignal.Up(), Quat.fromYawPitch(30f, 0f), moved.layout, pixels)
        val commit = up.filterIsInstance<SpaceAction.Layout>().last()
        assertTrue(commit.commit)
        assertEquals(30f, commit.layout.panel(1)!!.yawDeg, 0.5f)
    }

    @Test
    fun cornerGrabResizes() {
        val p = pointer()
        // 1.1.15: the handle is just outside the bottom right corner, and a resize needs a short hold and a drag.
        val yaw = panel.widthDeg / 2f + 0.5f
        val pitch = -panel.heightDeg / 2f - 0.5f
        p.handle(PointerSignal.MoveBy(yaw / 0.04f, -pitch / 0.04f), Quat.IDENTITY, layout, pixels)
        assertEquals(PanelZone.CORNER, p.hover?.zone)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        p.frame(Quat.IDENTITY, layout, CornerDrag.HOLD_MS + 50)
        val grown = p.handle(PointerSignal.MoveBy(10f / 0.04f, 0f), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Layout>().last().layout.panel(1)!!
        assertTrue(grown.widthDeg > panel.widthDeg + 5f)
        val up = p.handle(PointerSignal.Up(), Quat.IDENTITY, layout.replace(grown), pixels)
        assertTrue(up.filterIsInstance<SpaceAction.Layout>().last().commit)
    }

    @Test
    fun twoFingerScrollDragsInsideThePanelWithWheelScrollOff() {
        val p = SpatialPointer(frustum, PointerSettings(wheelScroll = false))
        val begin = p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.DOWN, begin.filterIsInstance<SpaceAction.Touch>().single().phase)
        val move = p.handle(PointerSignal.Scroll(0f, -100f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Touch>().single()
        assertTrue(move.v < 0.5f)
        val end = p.handle(PointerSignal.Scroll(0f, -120f, Phase.END), Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.UP, end.filterIsInstance<SpaceAction.Touch>().single().phase)
    }

    @Test
    fun scrollOverEmptySpaceTurnsTheView() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(0f, 1e6f), Quat.IDENTITY, SpaceLayout(), pixels)
        p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, SpaceLayout(), pixels)
        val turn = p.handle(PointerSignal.Scroll(100f, 0f, Phase.UPDATE), Quat.IDENTITY, SpaceLayout(), pixels)
            .filterIsInstance<SpaceAction.TurnView>().single()
        assertEquals(8f, turn.deltaYawDeg, 1e-3f)
    }

    /**
     * 1.2.3 (John Davis: "scroll the screens using the mousewheel when focus / cursor is away from the screens"): a wheel
     * (up and down only) over empty space turns the view as a sideways scroll does, a scroll back as a drag right.
     */
    @Test
    fun aMouseWheelOverEmptySpaceTurnsTheView() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(0f, 1e6f), Quat.IDENTITY, SpaceLayout(), pixels)
        p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, SpaceLayout(), pixels)
        val away = p.handle(PointerSignal.Scroll(0f, 100f, Phase.UPDATE), Quat.IDENTITY, SpaceLayout(), pixels)
            .filterIsInstance<SpaceAction.TurnView>().single()
        assertEquals(8f, away.deltaYawDeg, 1e-3f)
        val back = p.handle(PointerSignal.Scroll(0f, 50f, Phase.END), Quat.IDENTITY, SpaceLayout(), pixels)
            .filterIsInstance<SpaceAction.TurnView>().single()
        assertEquals(-4f, back.deltaYawDeg, 1e-3f)
    }

    /** 1.2.3 (John Davis: "2 finger trackpad to scroll the screens when cursor is underneath the screens"). */
    @Test
    fun anUpOrDownTwoFingerScrollOverEmptySpaceTurnsTheView() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(0f, 1e6f), Quat.IDENTITY, SpaceLayout(), pixels)
        p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, SpaceLayout(), pixels)
        // Fingers going down (negative dy on the pad) move the row as fingers going right do.
        val down = p.handle(PadSignal.Scroll(0f, 0f, 0f, -0.1f, Phase.UPDATE), Quat.IDENTITY, SpaceLayout(), pixels)
            .filterIsInstance<SpaceAction.TurnView>().single()
        val right = pointer().let { q ->
            q.handle(PointerSignal.MoveBy(0f, 1e6f), Quat.IDENTITY, SpaceLayout(), pixels)
            q.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, SpaceLayout(), pixels)
            q.handle(PadSignal.Scroll(0f, 0f, 0.1f, 0f, Phase.UPDATE), Quat.IDENTITY, SpaceLayout(), pixels)
                .filterIsInstance<SpaceAction.TurnView>().single()
        }
        assertTrue(down.deltaYawDeg > 0f)
        assertEquals(right.deltaYawDeg, down.deltaYawDeg, 1e-4f)
    }

    @Test
    fun twoHandStretchScalesTheHoveredPanel() {
        val p = pointer()
        p.handle(PointerSignal.Scale(1f, Phase.BEGIN, twoHands = true), Quat.IDENTITY, layout, pixels)
        val end = p.handle(PointerSignal.Scale(1.5f, Phase.END, twoHands = true), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Layout>().single()
        assertTrue(end.commit)
        assertEquals(60f, end.layout.panel(1)!!.widthDeg, 1e-3f)
    }

    @Test
    fun dockGestureTogglesTheLauncherAndCancelEndsTouches() {
        val p = pointer()
        p.handle(PointerSignal.MoveBy(100f, 0f), Quat.IDENTITY, layout, pixels)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        val out = p.handle(PointerSignal.ShowDock, Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.CANCEL, out.filterIsInstance<SpaceAction.Touch>().single().phase)
        assertTrue(SpaceAction.ToggleLauncher in out)
        assertFalse(SpaceAction.Recentre in out)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.CANCEL, p.handle(PointerSignal.Cancel, Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>().single().phase)
    }

    @Test
    fun aPressTheLauncherClaimsNeverTouchesAPanel() {
        val p = pointer()
        p.pressFilter = { true }
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertTrue(down.any { it is SpaceAction.Claimed })
        assertTrue(down.none { it is SpaceAction.Touch })
        assertTrue(p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels).none { it is SpaceAction.Touch })
        assertFalse(p.busy)
        p.pressFilter = { false }
        assertTrue(p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels).any { it is SpaceAction.Touch })
    }

    @Test
    fun modeChoiceExplainsItself() {
        val streaming = HeadTrackingStatus.Streaming("usb0", 1000.0)
        assertEquals(SpatialMode.SPATIAL, SpatialModeChooser.choose(streaming, ShizukuStatus.READY).mode)
        val noNet = SpatialModeChooser.choose(HeadTrackingStatus.NoNetwork(listOf("wlan0 wifi")), ShizukuStatus.READY)
        assertEquals(SpatialMode.HEAD_LOCKED, noNet.mode)
        assertTrue(noNet.reasons.single().contains("wlan0"))
        val flat = SpatialModeChooser.choose(streaming, ShizukuStatus.NOT_RUNNING)
        assertEquals(SpatialMode.FLAT, flat.mode)
        assertTrue(flat.reasons.single().contains("Shizuku"))
    }

    @Test
    fun panelDisplayFlagsAndLaunchCommand() {
        val f33 = PanelDisplays.flags(33)
        assertTrue(f33 and PanelDisplays.FLAG_TRUSTED != 0)
        assertTrue(f33 and PanelDisplays.FLAG_OWN_FOCUS == 0)
        assertTrue(PanelDisplays.flags(37) and PanelDisplays.FLAG_OWN_FOCUS != 0)
        assertEquals(0, PanelDisplays.flags(30) and PanelDisplays.FLAG_TRUSTED)
        val cmd = PanelDisplays.launchCommand(42, "com.android.chrome", "com.google.android.apps.chrome.Main")
        assertEquals(listOf("am", "start", "--user", "current", "--display", "42"), cmd.take(6))
        assertEquals("com.android.chrome/com.google.android.apps.chrome.Main", cmd.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun launchCommandRejectsShellCharacters() {
        PanelDisplays.launchCommand(1, "a;rm", "b")
    }

    @Test
    fun perfSummarisesFramesAndPanels() {
        val perf = SpacePerf(windowNs = 1_000_000_000L)
        var t = 0L
        var summary: PerfSummary? = null
        repeat(122) {
            perf.panel(1, 200_000L, newFrame = it % 2 == 0)
            summary = perf.frame(t, 1_000_000L) ?: summary
            t += 1_000_000_000L / 120
        }
        val s = summary!!
        assertEquals(120.0, s.fps, 2.0)
        assertEquals(1.0, s.frameCpuMs, 0.01)
        assertEquals(60.0, s.panels.single().fps, 2.0)
        assertEquals(0.2, s.panels.single().costMs, 0.01)
        assertTrue(s.line().contains("panel 1"))
    }
}
