package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.2: scroll clamping, the App closed state, the Back question, mode log debounce, focus and plug-in. */
class PanelGuardsTest {
    @Test
    fun wheelAndHoverOnTheTopStripLandInsideTheContent() {
        val m = PanelInputMapper()
        m.map(SpaceAction.Wheel(1, 0.5f, 0f, 0f, 0f, Phase.BEGIN), 1280, 720)
        val scroll = m.map(SpaceAction.Wheel(1, 0.5f, -0.02f, 0f, 120f, Phase.UPDATE), 1280, 720).single()
        val insets = ContentInsets.forPanel(1280, 720)
        assertEquals(PanelMotion.ACTION_SCROLL, scroll.action)
        assertEquals(insets.top.toFloat(), scroll.ys[0], 0f)
        assertTrue(insets.contains(scroll.xs[0], scroll.ys[0], 1280, 720))
        val hover = m.map(SpaceAction.Hover(1, HoverPhase.MOVE, 1.2f, -0.1f), 1280, 720).single()
        assertTrue(insets.contains(hover.xs[0], hover.ys[0], 1280, 720))
        assertEquals(1280f - 1 - ContentInsets.EDGE_PX, hover.xs[0], 0f)
        // Mid panel nothing moves.
        val mid = m.map(SpaceAction.Hover(1, HoverPhase.MOVE, 0.5f, 0.5f), 1280, 720).single()
        assertEquals(640f, mid.xs[0], 0f)
        assertEquals(360f, mid.ys[0], 0f)
        // A tap at the very top still lands there: only mouse events are clamped.
        val tap = m.map(SpaceAction.Touch(1, TouchPhase.DOWN, 0.5f, 0f), 1280, 720).single()
        assertEquals(0f, tap.ys[0], 0f)
    }

    @Test
    fun contentInsetsScaleWithTallPanelsAndSurviveTinyOnes() {
        assertEquals(ContentInsets.TOP_PX, ContentInsets.forPanel(1280, 720).top)
        assertEquals(1440 / 20, ContentInsets.forPanel(2560, 1440).top)
        // A display smaller than its insets: the centre, never outside.
        val (x, y) = ContentInsets(10, 10, 10, 10).clamp(0f, 0f, 12, 12)
        assertEquals(5.5f, x, 0f)
        assertEquals(5.5f, y, 0f)
    }

    @Test
    fun aDisplayLeftWithoutATaskBecomesAppClosed() {
        val d = EmptyPanelDetector(graceMs = 3000, confirmChecks = 2)
        val running = DisplayTasks(tasks = 1, visibleTasks = 1, topActivities = 2)
        val empty = DisplayTasks(0, 0, 0)
        d.launched(0)
        // Empty while the app is still starting: not closed.
        assertFalse(d.sample(empty, 1000))
        assertFalse(d.sample(empty, 2500))
        assertFalse(d.sample(running, 4000))
        // Gone: one empty check is not enough, two in a row are.
        assertFalse(d.sample(empty, 5500))
        assertTrue(d.sample(empty, 7000))
        // Unknown answers never close a panel and do not count.
        val u = EmptyPanelDetector(graceMs = 0, confirmChecks = 2)
        assertFalse(u.sample(null, 0))
        assertFalse(u.sample(empty, 1))
        assertFalse(u.sample(null, 2))
        assertTrue(u.sample(empty, 3))
        // Reopened: the grace starts again.
        d.launched(8000)
        assertFalse(d.sample(empty, 9000))
    }

    @Test
    fun aTaskMovedToTheBackCountsAsClosed() {
        assertTrue(DisplayTasks(1, 0, 1).empty)
        assertFalse(DisplayTasks(1, 1, 1).empty)
        assertNull(DisplayTasks.of(null))
        assertNull(DisplayTasks.of(intArrayOf(1, 1)))
        assertNull(DisplayTasks.of(intArrayOf(1, -1, 1)))
        assertEquals(DisplayTasks(2, 1, 3), DisplayTasks.of(intArrayOf(2, 1, 3)))
    }

    @Test
    fun theAppClosedCardHasReopenAndClose() {
        val card = PanelCard.AppClosed("Clock")
        assertEquals(listOf("Clock", "App closed"), card.lines)
        val reopen = card.buttons.single { it.kind == CardAction.REOPEN }
        val close = card.buttons.single { it.kind == CardAction.CLOSE_PANEL }
        assertEquals(CardAction.REOPEN, card.hit((reopen.u0 + reopen.u1) / 2, (reopen.v0 + reopen.v1) / 2))
        assertEquals(CardAction.CLOSE_PANEL, card.hit((close.u0 + close.u1) / 2, (close.v0 + close.v1) / 2))
        assertNull(card.hit(0.5f, 0.2f))
        assertTrue(card.buttons.none { a -> card.buttons.any { b -> a !== b && a.u0 < b.u1 && b.u0 < a.u1 && a.v0 < b.v1 && b.v0 < a.v1 } })
    }

    @Test
    fun backAtTheLastActivityAsksFirst() {
        val last = DisplayTasks(1, 1, 1)
        val deeper = DisplayTasks(1, 1, 3)
        assertEquals(BackDecision.ASK, BackGuard.decide(last, appClosed = false, allowedLast = false))
        assertEquals(BackDecision.SEND, BackGuard.decide(deeper, appClosed = false, allowedLast = false))
        // Once Mike chose Back anyway on the panel, Back goes straight to the app.
        assertEquals(BackDecision.SEND, BackGuard.decide(last, appClosed = false, allowedLast = true))
        // Unknown: as before 0.4.2.
        assertEquals(BackDecision.SEND, BackGuard.decide(null, appClosed = false, allowedLast = false))
        assertEquals(BackDecision.NOTHING, BackGuard.decide(DisplayTasks(0, 0, 0), appClosed = false, allowedLast = false))
        assertEquals(BackDecision.NOTHING, BackGuard.decide(deeper, appClosed = true, allowedLast = false))
        val ask = PanelCard.AskBack("Chrome")
        // 1.6.29: Cancel, Go back, Close window.
        assertEquals(CardAction.KEEP, ask.hit(0.3f, 0.9f))
        assertEquals(CardAction.BACK_ANYWAY, ask.hit(0.5f, 0.9f))
        assertEquals(CardAction.CLOSE_PANEL, ask.hit(0.7f, 0.9f))
        assertNull(ask.hit(0.5f, 0.3f)) // elsewhere: keep the app as it is
        assertTrue(ask.v0 > 0.5f)
    }

    @Test
    fun theModeIsLoggedOnlyWhenItOrItsReasonsReallyChange() {
        val gate = ModeLogGate(debounceMs = 5000)
        fun headLocked(status: HeadTrackingStatus) = ModeChoice(SpatialMode.HEAD_LOCKED, listOf("No head tracking: ${status.summary}"))
        val searching = headLocked(HeadTrackingStatus.Searching)
        val connecting = headLocked(HeadTrackingStatus.Connecting("usb0"))
        assertTrue(gate.offer(searching, 0))
        // The reason flaps every second for a minute: not one more line (0.4.1 logged 150).
        var lines = 0
        for (t in 1..60) if (gate.offer(if (t % 2 == 0) searching else connecting, t * 1000L)) lines++
        assertEquals(0, lines)
        // Counters inside a reason are not a new reason.
        val counts = FramerCounts(0, 0)
        val gate2 = ModeLogGate(debounceMs = 5000)
        assertTrue(gate2.offer(headLocked(HeadTrackingStatus.NoReports("usb0", 5, 100, counts)), 0))
        for (t in 1..20) assertFalse(gate2.offer(headLocked(HeadTrackingStatus.NoReports("usb0", 5 + t, 100L * t, counts)), t * 1000L))
        // A reason that holds for the debounce is logged once.
        assertFalse(gate.offer(connecting, 70_000))
        assertFalse(gate.offer(connecting, 74_000))
        assertTrue(gate.offer(connecting, 75_000))
        assertFalse(gate.offer(connecting, 80_000))
        // A new mode is logged at once.
        assertTrue(gate.offer(ModeChoice(SpatialMode.SPATIAL, emptyList()), 80_100))
        assertFalse(gate.offer(ModeChoice(SpatialMode.SPATIAL, emptyList()), 80_200))
        assertEquals("Connected over usb# but no sensor reports in # s", ModeLogGate.reasonKey("Connected over usb0 but no sensor reports in 12 s"))
    }

    @Test
    fun theTrackpadKeepsFocusOnTheFocusedPanel() {
        // Launcher closed: the focused panel's display; open: the Space's own; none: nothing.
        assertEquals(14, PanelFocus.target(launcherOpen = false, spaceDisplayId = 8, focusedPanelDisplayId = 14))
        assertEquals(8, PanelFocus.target(launcherOpen = true, spaceDisplayId = 8, focusedPanelDisplayId = 14))
        assertNull(PanelFocus.target(launcherOpen = false, spaceDisplayId = 8, focusedPanelDisplayId = null))
        assertNull(PanelFocus.target(launcherOpen = true, spaceDisplayId = -1, focusedPanelDisplayId = 14))
        // 1.6.35 (Crr66, Netflix over the Space): nothing is focused while an app is over it, launcher open or not.
        assertNull(PanelFocus.target(launcherOpen = false, spaceDisplayId = 8, focusedPanelDisplayId = 14, appOver = true))
        assertNull(PanelFocus.target(launcherOpen = true, spaceDisplayId = 8, focusedPanelDisplayId = 14, appOver = true))
        // The trackpad's window never takes focus while the Space is up.
        assertFalse(PanelFocus.trackpadFocusable(spaceActive = true))
        assertTrue(PanelFocus.trackpadFocusable(spaceActive = false))
        // A burst of touches is one refocus; a new target or a pause refocuses again.
        val keeper = FocusKeeper(minGapMs = 400)
        assertEquals(14, keeper.onTrackpadTouch(1000, 14))
        assertNull(keeper.onTrackpadTouch(1100, 14))
        assertNull(keeper.onTrackpadTouch(1300, 14))
        assertEquals(15, keeper.onTrackpadTouch(1350, 15))
        assertEquals(15, keeper.onTrackpadTouch(1800, 15))
        assertNull(keeper.onTrackpadTouch(1900, null))
    }

    @Test
    fun plugInOpensTheGlassesFromWhateverIsAvailable() {
        assertEquals(PlugInOpener.MAIN_SCREEN, PlugIn.opener(mainInFront = true, otherInFront = true, hostService = true, shizukuReady = true))
        assertEquals(PlugInOpener.FRONT_SCREEN, PlugIn.opener(mainInFront = false, otherInFront = true, hostService = false, shizukuReady = false))
        assertEquals(PlugInOpener.HOST_SERVICE, PlugIn.opener(mainInFront = false, otherInFront = false, hostService = true, shizukuReady = true))
        assertEquals(PlugInOpener.SHELL, PlugIn.opener(mainInFront = false, otherInFront = false, hostService = false, shizukuReady = true))
        assertEquals(PlugInOpener.NONE, PlugIn.opener(mainInFront = false, otherInFront = false, hostService = false, shizukuReady = false))
        assertEquals("8/SPACE", PlugIn.key(8, GlassesSurface.SPACE))
    }
}
