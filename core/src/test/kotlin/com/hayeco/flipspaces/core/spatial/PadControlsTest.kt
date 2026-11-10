package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HomeEvent
import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 0.5.17: managing windows from the trackpad in the Space, through [SpatialPointer]. */
class PadControlsTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private var layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }
    private var now = 0L

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, pixels).also(::apply)

    private fun SpatialPointer.tick(ms: Long = 16): List<SpaceAction> {
        now += ms
        return frame(Quat.IDENTITY, layout, now).also(::apply)
    }

    private fun apply(out: List<SpaceAction>) {
        out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let { layout = it.layout }
    }

    /** Aims the main ray at [x], [y] of the panel (-0.5..0.5 across and up, past 0.5 on the title bar). */
    private fun SpatialPointer.aim(x: Float, y: Float) {
        val p = layout.panel(1)!!
        val at = Angles.of(p.centre + p.right * (x * p.widthM) + p.up * (y * p.heightM))
        send(PadSignal.Move(0f, 0f, at.yawDeg - offsetYaw, at.pitchDeg - offsetPitch))
    }

    // 0.5.20: the Move pill floating above the window (no title bar), at the window under test.
    private val barY: Float get() = WindowControls.layout(layout.panel(1)!!).of(WindowControls.Control.MOVE).yCentre

    private fun controlX(c: WindowControls.Control): Float = WindowControls.layout(layout.panel(1)!!).of(c).xCentre

    private fun pickUp(): SpatialPointer {
        val p = SpatialPointer(frustum)
        p.tick()
        p.aim(0f, barY)
        assertEquals(PanelZone.TITLE_BAR, p.hover?.zone)
        val down = p.send(PointerSignal.Down(pad = true))
        assertTrue(SpaceAction.Haptic(PadHaptic.PICKUP) in down)
        assertTrue(down.none { it is SpaceAction.Touch })
        p.send(PointerSignal.Up())
        return p
    }

    @Test
    fun aClickOnTheTitleBarPicksTheWindowUpAndKeepsItHeld() {
        val p = pickUp()
        assertEquals(1, p.heldPanelId)
        // The reticle hides: nothing is hovered while held.
        p.tick()
        assertNull(p.hover)
        assertEquals(PanelZone.TITLE_BAR, p.grabOn(1))
    }

    @Test
    fun oneFingerMovesTheHeldWindow57DegreesAPadUnitEasedByFactor12() {
        val p = pickUp()
        p.tick()
        p.send(PadSignal.Move(0.5f, 0f, 99f, 99f))
        // One frame of 16 ms eases 12 x 0.016 = 19.2% of the way.
        p.tick(16)
        assertEquals(28.6479f * 0.192f, layout.panel(1)!!.yawDeg, 0.01f)
        repeat(60) { p.tick(16) }
        assertEquals(28.6479f, layout.panel(1)!!.yawDeg, 0.01f)
        // Up moves it up.
        p.send(PadSignal.Move(0f, 0.1f, 0f, 0f))
        repeat(60) { p.tick(16) }
        assertEquals(5.72958f, layout.panel(1)!!.pitchDeg, 0.01f)
        // The finger does not move the cursor while a window is held.
        assertEquals(1, p.heldPanelId)
    }

    @Test
    fun aTapDropsAfterTwoFramesUnlessTheFingerMoved() {
        val p = pickUp()
        p.send(PadSignal.Move(0.2f, 0f, 0f, 0f))
        // A tap in the same frame as the pick up does not drop.
        p.send(PointerSignal.Down(pad = true))
        p.send(PointerSignal.Up())
        assertEquals(1, p.heldPanelId)
        p.tick()
        p.tick()
        // A press with a swipe over 0.15 units does not drop.
        p.send(PointerSignal.Down(pad = true))
        p.send(PadSignal.Move(0.16f, 0f, 0f, 0f))
        p.send(PointerSignal.Up())
        assertEquals(1, p.heldPanelId)
        // A press held 0.3 s does not drop.
        p.send(PointerSignal.Down(pad = true))
        p.tick(300)
        p.send(PointerSignal.Up())
        assertEquals(1, p.heldPanelId)
        // A tap drops it where it was going, saved.
        p.send(PointerSignal.Down(pad = true))
        val drop = p.send(PointerSignal.Up())
        val commit = drop.filterIsInstance<SpaceAction.Layout>().last()
        assertTrue(commit.commit)
        assertEquals(wrapDegrees(0.36f * 57.2958f), commit.layout.panel(1)!!.yawDeg, 0.01f)
        assertNull(p.heldPanelId)
        // The cursor comes back on the window.
        assertEquals(commit.layout.panel(1)!!.yawDeg, p.offsetYaw, 0.01f)
    }

    @Test
    fun homePressDropsTheHeldWindow() {
        val p = pickUp()
        p.tick()
        val out = p.send(PadSignal.Home(HomeEvent.PRESS))
        assertTrue(out.filterIsInstance<SpaceAction.Layout>().last().commit)
        assertNull(p.heldPanelId)
        // Home's click shows the Home screen (the launcher); its long press recentres the cursor.
        assertTrue(SpaceAction.ToggleLauncher in p.send(PadSignal.Home(HomeEvent.CLICK)))
        p.send(PadSignal.Move(0f, 0f, 10f, 5f))
        p.send(PadSignal.Home(HomeEvent.LONG_PRESS))
        assertEquals(0f, p.offsetYaw, 0f)
        assertEquals(0f, p.offsetPitch, 0f)
    }

    @Test
    fun pressDragReleaseDropsAtTheRelease() {
        val p = SpatialPointer(frustum)
        p.tick()
        p.aim(0f, barY)
        p.send(PointerSignal.Down(pad = true))
        p.tick(400)
        p.send(PadSignal.Move(0.1f, 0f, 0f, 0f))
        val up = p.send(PointerSignal.Up())
        assertTrue(up.filterIsInstance<SpaceAction.Layout>().last().commit)
        assertNull(p.heldPanelId)
    }

    @Test
    fun twoFingersTheSameWayPushAndPull() {
        val p = pickUp()
        p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.BEGIN))
        // Up is further: 0.02 x 3.0 x 1.4^2 / 60 x 10 = 0.0196 m.
        val out = p.send(PadSignal.TwoFinger(0f, 0.02f, 0f, 0.02f, 0.4f, Phase.UPDATE))
        assertEquals(1.4196f, out.filterIsInstance<SpaceAction.Layout>().last().layout.panel(1)!!.distanceM, 1e-4f)
        assertEquals("FARTHER", p.holdGuide())
        // Down is closer, by the d^2 rule from the new distance (0.5.27: damped by 1.4 / d past 1.4 m).
        val d = layout.panel(1)!!.distanceM
        p.send(PadSignal.TwoFinger(0f, -0.05f, 0f, -0.05f, 0.4f, Phase.UPDATE))
        assertEquals(d - 0.05f * 3f * d * d / 6f * (1.4f / d), layout.panel(1)!!.distanceM, 1e-4f)
        assertEquals("CLOSER", p.holdGuide())
        // The guide goes 0.5 s after the last change.
        p.tick(501)
        assertNull(p.holdGuide())
        // 0.5.26: pulling far stops at 0.3 m, pushing far at 15 m (0.5.25: 1.3 and 3.8); 0.5.27: 10 % of the distance a move at most.
        repeat(40) { p.send(PadSignal.TwoFinger(0f, -2f, 0f, -2f, 0.4f, Phase.UPDATE)) }
        assertEquals(0.3f, layout.panel(1)!!.distanceM, 0f)
        repeat(60) { p.send(PadSignal.TwoFinger(0f, 1f, 0f, 1f, 0.4f, Phase.UPDATE)) }
        assertEquals(15f, layout.panel(1)!!.distanceM, 0f)
        // 0.5.27: the gesture's end logs where it left the window, once.
        val end = p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.END))
        val notes = end.filterIsInstance<SpaceAction.Note>().filter { it.line.contains("pushed to") }
        assertEquals(1, notes.size)
        assertTrue(notes.single().line, notes.single().line.contains("pushed to 15.00 m (two fingers)"))
    }

    @Test
    fun tinyTwoFingerMovesWaitForEachFingerToMove001() {
        val p = pickUp()
        p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.BEGIN))
        assertTrue(p.send(PadSignal.TwoFinger(0f, 0.005f, 0f, 0.005f, 0.4f, Phase.UPDATE)).none { it is SpaceAction.Layout })
        // Both past 0.01 together: decided, and this move pushes.
        assertTrue(p.send(PadSignal.TwoFinger(0f, 0.006f, 0f, 0.006f, 0.4f, Phase.UPDATE)).any { it is SpaceAction.Layout })
    }

    @Test
    fun twoFingersApartScaleTheHeldWindow() {
        val p = pickUp()
        p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.BEGIN))
        p.send(PadSignal.TwoFinger(-0.02f, 0f, 0.02f, 0f, 0.44f, Phase.UPDATE))
        assertEquals(1.1f, PadWindowMath.scaleOf(layout.panel(1)!!), 1e-3f)
        assertEquals("LARGER", p.holdGuide())
        p.send(PadSignal.TwoFinger(0.1f, 0f, -0.1f, 0f, 0.2f, Phase.UPDATE))
        // 0.5.41: half the size, or the corner handle's 12 degrees on the shorter side if that comes first.
        val shrunk = layout.panel(1)!!
        assertEquals(maxOf(0.5f, PadWindowMath.scaleOf(shrunk)), PadWindowMath.scaleOf(shrunk), 1e-3f)
        assertTrue(PadWindowMath.scaleOf(shrunk) < 1.1f)
        assertTrue(minOf(shrunk.widthDeg, shrunk.heightDeg) >= PanelLimits.MIN_SIZE_DEG - 0.05f)
        // A pinch signal of the same gesture does not also zoom the app.
        assertTrue(p.send(PadSignal.Pinch(1.5f, Phase.UPDATE)).isEmpty())
    }

    /** 0.5.42 (Mike: resizing shook the window "as if there's an earthquake"): beside a window it may snap to, it stays still. */
    @Test
    fun aWindowBeingResizedBesideAnotherDoesNotShake() {
        // A second window just past snapping distance's edge of the first: the snap's spot moves with the first's size.
        layout = SpaceLayout(listOf(panel, panel.copy(id = 2, yawDeg = panel.widthDeg + WindowSnap.GAP_DEG + 2f)))
        val p = pickUp()
        // Settled where it snaps before the resize starts.
        repeat(90) { p.tick() }
        val startYaw = layout.panel(1)!!.yawDeg
        p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.BEGIN))
        val yaws = mutableListOf<Float>()
        for (k in 1..30) {
            val spread = 0.4f + 0.01f * k
            p.send(PadSignal.TwoFinger(-0.005f, 0f, 0.005f, 0f, spread, Phase.UPDATE))
            p.tick()
            yaws += layout.panel(1)!!.yawDeg
        }
        assertTrue("it grew", layout.panel(1)!!.widthDeg > panel.widthDeg * 1.5f)
        assertTrue("held where it was: ${yaws.min()}..${yaws.max()} from $startYaw", yaws.all { abs(it - startYaw) < 1e-3f })
        // Let go: it stays there too, not pulled to a snap the new size makes.
        p.send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.7f, Phase.END))
        repeat(10) { p.tick() }
        assertEquals(startYaw, layout.panel(1)!!.yawDeg, 1e-3f)
    }

    @Test
    fun theScrollStripPushesAndPullsAHeldWindow() {
        val p = pickUp()
        p.send(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true))
        p.send(PadSignal.Scroll(0f, -0.4f, 0f, 0.1f, Phase.UPDATE, strip = true))
        assertEquals(1.4f + 0.1f * 3f * 1.96f / 6f, layout.panel(1)!!.distanceM, 1e-4f)
    }

    @Test
    fun resetPutsTheWindowInFront02MBelowTheEyesOver05S() {
        val p = SpatialPointer(frustum)
        layout = SpaceLayout(listOf(panel.copy(yawDeg = 5f, pitchDeg = 3f, distanceM = 2f)))
        p.tick()
        p.aim(controlX(WindowControls.Control.RESET), barY)
        assertEquals(PanelZone.RESET, p.hover?.zone)
        p.send(PointerSignal.Down(pad = true))
        p.send(PointerSignal.Up())
        p.tick(250)
        val half = layout.panel(1)!!
        assertEquals(5f * 0.25f, half.yawDeg, 0.01f)
        val end = p.tick(250).filterIsInstance<SpaceAction.Layout>().last()
        assertTrue(end.commit)
        assertEquals(0f, end.layout.panel(1)!!.yawDeg, 1e-4f)
        assertEquals(-0.2f, end.layout.panel(1)!!.centre.y, 1e-4f)
    }

    @Test
    fun closeOnTheTitleBarClosesAndBarButtonsBuzzOnHover() {
        val p = SpatialPointer(frustum)
        p.tick()
        p.aim(controlX(WindowControls.Control.CLOSE), barY)
        assertEquals(PanelZone.CLOSE, p.hover?.zone)
        assertTrue(SpaceAction.Haptic(PadHaptic.HOVER_BUTTON) in p.tick())
        assertTrue(p.tick().none { it is SpaceAction.Haptic })
        assertTrue(SpaceAction.ClosePanel(1) in p.send(PointerSignal.Down(pad = true)))
    }

    @Test
    fun refreshReloadsTheWindowsAppAndLeavesItWhereItIs() {
        // 0.5.21 (Mike: back, close and refresh buttons): Reset only moves the window, so Refresh is its own button.
        val p = SpatialPointer(frustum)
        layout = SpaceLayout(listOf(panel.copy(yawDeg = 5f, pitchDeg = 3f, distanceM = 2f)))
        p.tick()
        p.aim(controlX(WindowControls.Control.REFRESH), barY)
        assertEquals(PanelZone.REFRESH, p.hover?.zone)
        assertTrue(SpaceAction.Haptic(PadHaptic.HOVER_BUTTON) in p.tick())
        val out = p.send(PointerSignal.Down(pad = true)) + p.send(PointerSignal.Up()) + p.tick(600)
        assertTrue(SpaceAction.RefreshPanel(1) in out)
        assertTrue(out.none { it is SpaceAction.ClosePanel || it is SpaceAction.Back })
        assertEquals(5f, layout.panel(1)!!.yawDeg, 1e-4f)
        assertEquals(3f, layout.panel(1)!!.pitchDeg, 1e-4f)
        // The command: the launcher activity started again on its display with its task cleared.
        val cmd = PanelDisplays.refreshCommand(7, "tv.twitch.android.app", "tv.twitch.android.feature.LaunchActivity")
        assertEquals(listOf("am", "start", "--user", "current", "--display", "7"), cmd.take(6))
        assertEquals("0x10808000", cmd[cmd.indexOf("-f") + 1])
        // Kept out of the phone's recent apps, like every window.
        val launch = PanelDisplays.launchCommand(7, "tv.twitch.android.app", "tv.twitch.android.feature.LaunchActivity")
        assertTrue(launch[launch.indexOf("-f") + 1].removePrefix("0x").toLong(16) and 0x00800000L != 0L)
        assertTrue("no force stop", "-S" !in cmd)
    }

    @Test
    fun titleBarsShowWhilePointedAtAndFade() {
        val p = SpatialPointer(frustum)
        p.tick()
        assertEquals(1f, p.barAlpha(1), 0f)
        p.send(PadSignal.Move(0f, 0f, 30f, 0f))
        p.tick(1800)
        assertEquals(1f, p.barAlpha(1), 1e-3f)
        p.tick(350)
        assertEquals(0.5f, p.barAlpha(1), 0.03f)
        p.tick(400)
        assertEquals(0f, p.barAlpha(1), 0f)
    }

    /** 0.5.41 (Mike: two finger scroll did not work well): the window's smooth wheel, one axis, no end, and a coast. */
    @Test
    fun twoFingerScrollIsTheWindowsSmoothWheelWithACoast() {
        val p = SpatialPointer(frustum)
        p.tick()
        val begin = p.send(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN))
        assertTrue(begin.none { it is SpaceAction.Touch })
        assertEquals(Phase.BEGIN, begin.filterIsInstance<SpaceAction.Wheel>().single().phase)
        // Fingers up 0.1 units (a little across too): the page moves to what is below, up and down only.
        var t = 0f
        var last: SpaceAction.Wheel? = null
        repeat(5) {
            t += 0.1f
            p.tick()
            last = p.send(PadSignal.Scroll(0f, 0f, 0.02f * t, t, Phase.UPDATE)).filterIsInstance<SpaceAction.Wheel>().single()
        }
        assertEquals(0f, last!!.dx, 0f)
        assertEquals(-0.5f * PAD_WHEEL_PX_PER_UNIT, last!!.dy, 1e-2f)
        // Far past where 0.5.40's touch drag ended (1.5 of the window): still going.
        p.tick()
        val far = p.send(PadSignal.Scroll(0f, 0f, 0f, 3f, Phase.UPDATE)).filterIsInstance<SpaceAction.Wheel>().single()
        assertEquals(-3f * PAD_WHEEL_PX_PER_UNIT, far.dy, 1e-2f)
        // A flick: lifted mid-move, the page coasts on, slows, and ends.
        p.tick()
        p.send(PadSignal.Scroll(0f, 0f, 0f, 3.3f, Phase.END))
        val coast = (0 until 300).flatMap { p.tick() }.filterIsInstance<SpaceAction.Wheel>()
        assertTrue(coast.size > 5)
        assertTrue("coasting on past the lift", coast.first().dy < -3.3f * PAD_WHEEL_PX_PER_UNIT)
        assertEquals(Phase.END, coast.last().phase)
    }

    @Test
    fun pinchZoomsInTheAppButNeverScalesAWindowThatIsNotHeld() {
        val p = SpatialPointer(frustum)
        p.tick()
        assertTrue(p.send(PadSignal.Pinch(1f, Phase.BEGIN)).any { it is SpaceAction.Zoom })
        p.send(PadSignal.Pinch(1.2f, Phase.END))
        p.aim(0f, barY)
        val out = p.send(PadSignal.Pinch(1f, Phase.BEGIN)) + p.send(PadSignal.Pinch(1.5f, Phase.END))
        assertTrue(out.none { it is SpaceAction.Layout || it is SpaceAction.Zoom })
    }

    @Test
    fun threeFingerSwipesGoToTheSpace() {
        val p = SpatialPointer(frustum)
        assertTrue(SpaceAction.ThreeFinger(SwipeDirection.DOWN) in p.send(PadSignal.Swipe(SwipeDirection.DOWN)))
    }

    @Test
    fun spatialMouseAimsWithThePhone() {
        val p = SpatialPointer(frustum)
        p.send(PadSignal.Mode(PadMode.SPATIAL_MOUSE))
        p.send(PadSignal.Aim(100f, -20f))
        p.tick()
        assertEquals(0f, p.offsetYaw, 1e-3f)
        p.send(PadSignal.Aim(110f, -15f))
        p.tick()
        assertEquals(10f, p.offsetYaw, 1e-3f)
        assertEquals(5f, p.offsetPitch, 1e-3f)
        // The pad's finger does not move the cursor in Spatial Mouse.
        p.send(PadSignal.Move(0.1f, 0f, 5f, 0f))
        assertEquals(10f, p.offsetYaw, 1e-3f)
        // Holding Home recentres the ray in front.
        p.send(PadSignal.Home(HomeEvent.LONG_PRESS))
        p.send(PadSignal.Aim(110f, -15f))
        p.tick()
        assertEquals(0f, p.offsetYaw, 1e-3f)
    }

    @Test
    fun spatialMouseLaserDragsTheHeldWindow() {
        val p = SpatialPointer(frustum)
        p.send(PadSignal.Mode(PadMode.SPATIAL_MOUSE))
        p.send(PadSignal.Aim(0f, 0f))
        p.tick()
        val bar = Angles.of(panel.centre + panel.up * (barY * panel.heightM))
        p.send(PadSignal.Aim(0f, bar.pitchDeg))
        p.tick()
        p.send(PointerSignal.Down(pad = true))
        p.send(PointerSignal.Up())
        assertEquals(1, p.heldPanelId)
        p.send(PadSignal.Aim(20f, bar.pitchDeg))
        repeat(60) { p.tick() }
        assertEquals(20f, layout.panel(1)!!.yawDeg, 0.05f)
        // A vertical swipe 0.3 s after the grab: the depth step, 0.1 x 4.0 m, at most 10 % of the distance a move (0.5.27).
        p.send(PadSignal.Move(0f, 0.1f, 0f, 0f))
        assertEquals(1.54f, layout.panel(1)!!.distanceM, 1e-3f)
    }

    @Test
    fun arrangeTweensEveryWindow() {
        val p = SpatialPointer(frustum)
        layout = SpaceLayout(listOf(panel, panel.copy(id = 2, yawDeg = 60f)))
        p.tick()
        p.arrange(ArrangeKind.COLLECT, layout, null)
        p.tick(500)
        layout.panels.forEach { assertEquals(Arrange.COLLECT_RADIUS_M, it.distanceM, 1e-4f) }
    }

    @Test
    fun legacyPressesStillMoveWithTheRay() {
        // A press that is not the pad's (hands, look and pinch) keeps the old title bar grab.
        val p = SpatialPointer(frustum)
        p.aim(0f, barY)
        p.send(PointerSignal.Down())
        assertNull(p.heldPanelId)
        assertFalse(p.send(PointerSignal.Up()).isEmpty())
    }
}
