package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.17: the trackpad's gestures.
 * The tests kept from the old trackpad (0.4.1) say what changed in their names' comments.
 */
class TrackpadInterpreterTest {
    private fun pad(mode: PadMode = PadMode.VERTICAL) = TrackpadInterpreter(mode = mode)
    private val tap = listOf(PointerSignal.Down(pad = true), PointerSignal.Up())

    private fun List<PointerSignal>.moves() = filterIsInstance<PadSignal.Move>()

    // ---- kept from 0.4.1, updated ----

    @Test
    fun quickTapClicks() {
        // Was Down(), Up(); the press now says it is the pad's (it picks a window up on a title bar).
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 0)
        assertEquals(tap, p.up(0, 120))
    }

    @Test
    fun liftBeforeTheLongPressIsATap() {
        // Was slowLiftWithoutMovingIsNotATap (a 250 ms tap limit). There is none now: a still finger
        // lifted before the 500 ms long press is a tap.
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 0)
        assertTrue(p.tick(300).isEmpty())
        assertEquals(tap, p.up(0, 300))
    }

    @Test
    fun dragMovesTheCursorWithTheCurveAndNoSlop() {
        // Was dragMovesTheCursorWithGain (20 px slop, 1.6 gain in pixels): no slop, the touch ray curve in degrees.
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 0)
        val first = p.move(listOf(PadTouch(0, 0.001f, 0f)), 17).moves().single()
        assertEquals(0.001f * 0.63f * 18f * 1.2f, first.yawDeg, 1e-6f)
        val out = p.move(listOf(PadTouch(0, 0.011f, -0.01f)), 34).moves().single()
        assertEquals(0.01f, out.dx, 1e-6f)
        assertEquals(-0.01f, out.dy, 1e-6f)
        assertEquals(0.01f * 0.63f * 18f * 1.2f, out.yawDeg, 1e-5f)
        assertEquals(-0.01f * 18f * 1.2f, out.pitchDeg, 1e-5f)
        // Moves under 0.15 units (and 0.03 in the first 0.15 s) still click at the lift.
        assertEquals(tap, p.up(0, 100))
    }

    @Test
    fun accelerationBoostsFastMoves() {
        // Was TrackpadFeel's 1 + 0.9 speed, capped at 3; now 1 + 2.5 sin above m 0.2, up to 3.5.
        val slow = PadCurve.turn(0.01f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL).first
        val fast = PadCurve.turn(0.1f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL).first
        assertEquals(0.01f * 0.63f * 18f * 1.2f, slow, 1e-6f)
        // m = (0.1 x 0.63 x 18)^2 = 1.2859; accel = 1 + 2.5 sin(48.87 deg) = 2.8828.
        assertEquals(0.1f * 0.63f * 18f * 1.2f * 2.8828f, fast, 2e-3f)
    }

    @Test
    fun holdThenDragPressesAndReleases() {
        // Was a 350 ms hold with a haptic; now a 500 ms hold that does not buzz.
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 0)
        assertTrue(p.tick(499).isEmpty())
        assertEquals(listOf(PointerSignal.Down(pad = true)), p.tick(500))
        assertTrue(p.holding)
        assertTrue(p.takeHaptics().isEmpty())
        assertEquals(1, p.move(listOf(PadTouch(0, 0.3f, 0f)), 520).moves().size)
        assertEquals(listOf(PointerSignal.Up()), p.up(0, 600))
        assertFalse(p.holding)
    }

    @Test
    fun twoFingerPinchZooms() {
        // Was twoFingerPinchScales (Scale signals at an 8% spread change); now PadSignal.Pinch by the pad's pinch rule.
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        val begin = p.down(PadTouch(1, 0.2f, 0f), 10)
        assertEquals(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 0.4f, Phase.BEGIN), begin.single())
        val out = p.move(listOf(PadTouch(0, -0.25f, 0f), PadTouch(1, 0.25f, 0f)), 30)
        assertEquals(PadSignal.Pinch(1f, Phase.BEGIN), out.filterIsInstance<PadSignal.Pinch>().first())
        assertEquals(1.25f, out.filterIsInstance<PadSignal.Pinch>().last().factor, 1e-5f)
        val end = p.up(1, 60)
        assertEquals(1.25f, end.filterIsInstance<PadSignal.Pinch>().single().factor, 1e-5f)
        assertEquals(Phase.END, end.filterIsInstance<PadSignal.Pinch>().single().phase)
        // The finger left behind does nothing until it lifts.
        assertTrue(p.move(listOf(PadTouch(0, 0.9f, 0.9f)), 80).isEmpty())
        assertTrue(p.up(0, 90).isEmpty())
    }

    @Test
    fun twoFingerDragScrolls() {
        // Was Scroll in phone pixels; now the pad's scroll axis (4 a pad unit, y negative going up) and the pad travel.
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        val out = p.move(listOf(PadTouch(0, -0.2f, 0.1f), PadTouch(1, 0.2f, 0.1f)), 30).filterIsInstance<PadSignal.Scroll>()
        assertEquals(Phase.BEGIN, out.first().phase)
        val u = out.last()
        assertEquals(0f, u.axisX, 1e-6f)
        assertEquals(-0.4f, u.axisY, 1e-5f)
        assertEquals(0.1f, u.dy, 1e-6f)
        val end = p.up(0, 50).filterIsInstance<PadSignal.Scroll>().single()
        assertEquals(Phase.END, end.phase)
    }

    @Test
    fun threeFingerTapShowsDock() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0f, 0f), 20)
        p.down(PadTouch(2, 0.2f, 0f), 40)
        // It fires when the first finger lifts, so a swipe can be something else instead.
        assertEquals(listOf<PointerSignal>(PointerSignal.ShowDock), p.up(0, 120))
        assertTrue(p.up(1, 130).isEmpty())
        assertTrue(p.up(2, 140).isEmpty())
    }

    @Test
    fun threeFingerSwipeSidewaysIsASwipe() {
        // Was threeFingerSwipeSidewaysIsBack after 120 px; now Swipe(RIGHT) past 0.04 pad units (the Space makes it Back).
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0f, 0f), 20)
        p.down(PadTouch(2, 0.2f, 0f), 40)
        p.move(listOf(PadTouch(0, -0.15f, 0f), PadTouch(1, 0.05f, 0f), PadTouch(2, 0.25f, 0f)), 60)
        assertEquals(listOf<PointerSignal>(PadSignal.Swipe(SwipeDirection.RIGHT)), p.up(0, 120))
        assertTrue(p.up(1, 130).isEmpty())
        assertTrue(p.up(2, 140).isEmpty())
    }

    @Test
    fun quickTwoFingerTapIsARightClick() {
        // Was quickTwoFingerTapIsALongPress: the same SecondaryTap, now with the two finger begin and end around it.
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 20)
        assertTrue(PointerSignal.SecondaryTap in p.up(0, 150))
        assertTrue(p.up(1, 160).isEmpty())
    }

    @Test
    fun stillTwoFingerRestIsARightClickToo() {
        // Was slowTwoFingerRestIsNotATap (300 ms limit). A two finger tap now has no time limit.
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 20)
        assertTrue(PointerSignal.SecondaryTap in p.up(0, 700))
    }

    @Test
    fun cancelWhileHoldingCancels() {
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 0)
        p.tick(500)
        assertEquals(listOf<PointerSignal>(PointerSignal.Cancel), p.cancel())
        assertTrue(pad().cancel().isEmpty())
    }

    // ---- 0.5.17: the trackpad's thresholds ----

    @Test
    fun padUnitsComeFromPixels() {
        val t = PadTouch.of(3, 0f, 0f, 1080f, 1920f)
        assertEquals(-1f, t.x, 0f)
        assertEquals(1f, t.y, 0f)
        val c = PadTouch.of(3, 540f, 960f, 1080f, 1920f)
        assertEquals(0f, c.x, 1e-6f)
        assertEquals(0f, c.y, 1e-6f)
        // Clamped to -1..1.
        assertEquals(1f, PadTouch.of(0, 2000f, 3000f, 1080f, 1920f).x, 0f)
        assertEquals(-1f, PadTouch.of(0, 2000f, 3000f, 1080f, 1920f).y, 0f)
    }

    @Test
    fun longPressIsAt500Ms() {
        val p = pad()
        p.down(PadTouch(0, 0f, 0f), 1000)
        assertTrue(p.tick(1499).isEmpty())
        assertEquals(listOf(PointerSignal.Down(pad = true)), p.tick(1500))
        assertTrue(p.tick(1600).isEmpty())
    }

    @Test
    fun aPressThatMovedPast015UnitsIsNoTapAndNoLongPress() {
        // 0.5.107: 0.07 units, after the safe-down window, lifted by 300 ms: still a tap.
        val a = pad()
        a.down(PadTouch(0, 0f, 0f), 0)
        a.move(listOf(PadTouch(0, 0.07f, 0f)), 200)
        assertEquals(tap, a.up(0, 300))
        // 0.5.107 (Mike: Control Center toggles flipped by themselves): 0.14 units slowly was a tap; now it only moves.
        val nudge = pad()
        nudge.down(PadTouch(0, 0f, 0f), 0)
        nudge.move(listOf(PadTouch(0, 0.14f, 0f)), 200)
        assertTrue(nudge.up(0, 300).isEmpty())
        // A still finger lifted after 300 ms (and before the long press): no tap either.
        val slow = pad()
        slow.down(PadTouch(0, 0f, 0f), 0)
        assertTrue(slow.up(0, 420).isEmpty())
        // 0.16 units: moving, no tap and no long press.
        val b = pad()
        b.down(PadTouch(0, 0f, 0f), 0)
        b.move(listOf(PadTouch(0, 0.16f, 0f)), 200)
        assertTrue(b.tick(600).isEmpty())
        assertTrue(b.up(0, 700).isEmpty())
        // Back to where it went down: the 0.16 it reached still counts.
        val c = pad()
        c.down(PadTouch(0, 0f, 0f), 0)
        c.move(listOf(PadTouch(0, 0.16f, 0f)), 200)
        c.move(listOf(PadTouch(0, 0f, 0f)), 250)
        assertTrue(c.up(0, 300).isEmpty())
    }

    @Test
    fun safeDownCancelsAPressThatMovesOver003UnitsIn015S() {
        val a = pad()
        a.down(PadTouch(0, 0f, 0f), 0)
        a.move(listOf(PadTouch(0, 0.031f, 0f)), 149)
        a.move(listOf(PadTouch(0, 0f, 0f)), 160)
        assertTrue(a.up(0, 200).isEmpty())
        assertTrue(a.tick(600).isEmpty())
        // 0.029 within 0.15 s: still a tap.
        val b = pad()
        b.down(PadTouch(0, 0f, 0f), 0)
        b.move(listOf(PadTouch(0, 0.029f, 0f)), 100)
        assertEquals(tap, b.up(0, 200))
        // 0.05 after 0.15 s is inside the 0.15 move limit: still a tap.
        val c = pad()
        c.down(PadTouch(0, 0f, 0f), 0)
        c.move(listOf(PadTouch(0, 0.05f, 0f)), 151)
        assertEquals(tap, c.up(0, 200))
    }

    @Test
    fun scrollOrPinchIsDecidedByTheDistanceChangeAgainstTheMidpointMove() {
        assertTrue(TrackpadInterpreter.decideIsPinch(0.4f, 0.5f, 0f, 0f))
        assertFalse(TrackpadInterpreter.decideIsPinch(0.4f, 0.4f, 0f, 0.1f))
        // Distance change 0.05 against a midpoint move of 0.103: a scroll.
        assertFalse(TrackpadInterpreter.decideIsPinch(0.4f, 0.45f, 0.025f, 0.1f))
        // Distance change 0.11 against 0.1: a pinch.
        assertTrue(TrackpadInterpreter.decideIsPinch(0.4f, 0.51f, 0f, 0.1f))
    }

    @Test
    fun undecidedWhileOneFingerHasNotMoved() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        // Only finger 0 moves (0.1 apart): undecided, nothing begins.
        val first = p.move(listOf(PadTouch(0, -0.3f, 0f)), 30)
        assertTrue(first.none { it is PadSignal.Pinch || it is PadSignal.Scroll })
        // 0.002 units is under 1e-5 squared: still undecided.
        assertTrue(p.move(listOf(PadTouch(1, 0.202f, 0f)), 40).none { it is PadSignal.Pinch || it is PadSignal.Scroll })
        // Finger 1 moves 0.004 (0.004^2 = 1.6e-5): decided, a pinch (0.104 change, 0.049 midpoint move).
        val out = p.move(listOf(PadTouch(1, 0.204f, 0f)), 50)
        assertEquals(Phase.BEGIN, out.filterIsInstance<PadSignal.Pinch>().first().phase)
    }

    @Test
    fun theDecisionIsMadeOncePerGesture() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        p.move(listOf(PadTouch(0, -0.2f, 0.1f), PadTouch(1, 0.2f, 0.1f)), 30)
        // Spreading after the scroll was decided stays a scroll.
        val out = p.move(listOf(PadTouch(0, -0.5f, 0.1f), PadTouch(1, 0.5f, 0.1f)), 50)
        assertTrue(out.none { it is PadSignal.Pinch })
        assertTrue(out.any { it is PadSignal.Scroll })
    }

    @Test
    fun scrollAxisIsFourAUnitAndClamped() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        val s1 = p.move(listOf(PadTouch(0, -0.2f, -0.2f), PadTouch(1, 0.2f, -0.2f)), 30).filterIsInstance<PadSignal.Scroll>().last()
        assertEquals(0.8f, s1.axisY, 1e-5f)
        // A quarter height (0.5 units) saturates the axis.
        val s2 = p.move(listOf(PadTouch(0, -0.2f, -0.5f), PadTouch(1, 0.2f, -0.5f)), 50).filterIsInstance<PadSignal.Scroll>().last()
        assertEquals(1f, s2.axisY, 0f)
        assertEquals(-0.5f, s2.dy, 1e-6f)
        val s3 = p.move(listOf(PadTouch(0, 0.05f, -0.5f), PadTouch(1, 0.45f, -0.5f)), 70).filterIsInstance<PadSignal.Scroll>().last()
        assertEquals(1f, s3.axisX, 1e-5f)
        val s4 = p.move(listOf(PadTouch(0, 0.3f, -0.5f), PadTouch(1, 0.7f, -0.5f)), 90).filterIsInstance<PadSignal.Scroll>().last()
        assertEquals(1f, s4.axisX, 0f)
        assertEquals(0.5f, s4.dx, 1e-5f)
    }

    @Test
    fun aTwoFingerTapNeverStartsAScrollUnder003Units() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        val out = p.move(listOf(PadTouch(0, -0.2f, 0.02f), PadTouch(1, 0.2f, 0.02f)), 30)
        assertTrue(out.none { it is PadSignal.Scroll })
        assertTrue(out.any { it is PadSignal.TwoFinger })
        val up = p.up(0, 60)
        assertTrue(PointerSignal.SecondaryTap in up)
        assertTrue(up.none { it is PadSignal.Scroll })
    }

    @Test
    fun twoFingerMovesAreSentForAHeldWindow() {
        val p = pad()
        p.down(PadTouch(0, -0.2f, 0f), 0)
        p.down(PadTouch(1, 0.2f, 0f), 10)
        val t = p.move(listOf(PadTouch(0, -0.2f, 0.05f), PadTouch(1, 0.21f, 0.04f)), 30).filterIsInstance<PadSignal.TwoFinger>().single()
        assertEquals(0f, t.ax, 1e-6f)
        assertEquals(0.05f, t.ay, 1e-6f)
        assertEquals(0.01f, t.bx, 1e-6f)
        assertEquals(0.04f, t.by, 1e-6f)
        assertEquals(Phase.END, p.up(0, 40).filterIsInstance<PadSignal.TwoFinger>().single().phase)
    }

    @Test
    fun threeFingerSwipesPass004Units() {
        fun swipe(dx: Float, dy: Float): List<PointerSignal> {
            val p = pad()
            p.down(PadTouch(0, -0.2f, 0f), 0)
            p.down(PadTouch(1, 0f, 0f), 10)
            p.down(PadTouch(2, 0.2f, 0f), 20)
            p.move(listOf(PadTouch(0, -0.2f + dx, dy), PadTouch(1, dx, dy), PadTouch(2, 0.2f + dx, dy)), 40)
            return p.up(0, 100)
        }
        assertEquals(listOf<PointerSignal>(PadSignal.Swipe(SwipeDirection.UP)), swipe(0f, 0.041f))
        assertEquals(listOf<PointerSignal>(PadSignal.Swipe(SwipeDirection.DOWN)), swipe(0.01f, -0.05f))
        assertEquals(listOf<PointerSignal>(PadSignal.Swipe(SwipeDirection.LEFT)), swipe(-0.05f, 0.01f))
        // 0.039: a tap, the dock.
        assertEquals(listOf<PointerSignal>(PointerSignal.ShowDock), swipe(0f, 0.039f))
    }

    @Test
    fun theScrollStripIs035UnitsWideAndWidensTo135TimesWithin1000Ms() {
        val p = pad()
        assertTrue(p.inStrip(0.66f, 0))
        assertFalse(p.inStrip(0.64f, 0))
        p.down(PadTouch(0, 0.9f, 0f), 0)
        assertEquals(listOf(PadHaptic.STRIP), p.takeHaptics())
        p.up(0, 100)
        // Within 1000 ms of the last strip touch: 0.35 x 1.35 = 0.4725 units.
        assertTrue(p.inStrip(0.53f, 1099))
        assertFalse(p.inStrip(0.52f, 1099))
        // After 1000 ms: back to 0.35.
        assertFalse(p.inStrip(0.53f, 1100))
        // The left side, by the setting; none in landscape.
        assertTrue(TrackpadInterpreter(stripSide = StripSide.LEFT).inStrip(-0.66f, 0))
        assertFalse(TrackpadInterpreter(stripSide = StripSide.LEFT).inStrip(0.9f, 0))
        assertFalse(TrackpadInterpreter(mode = PadMode.HORIZONTAL).inStrip(0.9f, 0))
        // The cover screen layout has none either.
        assertFalse(TrackpadInterpreter().also { it.stripAllowed = false }.inStrip(0.9f, 0))
    }

    @Test
    fun theStripsFirstMoveDecidesScrollOrCursor() {
        val p = pad()
        p.down(PadTouch(0, 0.9f, 0f), 0)
        val out = p.move(listOf(PadTouch(0, 0.9f, 0.1f)), 20).filterIsInstance<PadSignal.Scroll>()
        assertEquals(Phase.BEGIN, out.first().phase)
        assertTrue(out.all { it.strip })
        // One finger: 4 axis units a pad unit, negative going up.
        assertEquals(-0.4f, out.last().axisY, 1e-5f)
        assertEquals(0.1f, out.last().dy, 1e-6f)
        assertEquals(Phase.END, p.up(0, 40).filterIsInstance<PadSignal.Scroll>().single().phase)
        // Sideways first: the cursor.
        val c = pad()
        c.down(PadTouch(0, 0.9f, 0f), 2000)
        assertEquals(1, c.move(listOf(PadTouch(0, 0.85f, 0.01f)), 2020).moves().size)
        // A strip touch that never moves is no click (its type stays undecided).
        val n = pad()
        n.down(PadTouch(0, 0.9f, 0f), 5000)
        assertTrue(n.up(0, 5100).isEmpty())
    }

    @Test
    fun homeClicksWithin03SAndLongPressesAt1S() {
        val h = HomeButton()
        assertEquals(listOf<PointerSignal>(PadSignal.Home(HomeEvent.PRESS)), h.down(0))
        assertEquals(listOf<PointerSignal>(PadSignal.Home(HomeEvent.RELEASE), PadSignal.Home(HomeEvent.CLICK)), h.up(299))
        h.down(1000)
        assertEquals(listOf<PointerSignal>(PadSignal.Home(HomeEvent.RELEASE)), h.up(1300))
        h.down(2000)
        assertTrue(h.tick(2999).isEmpty())
        assertEquals(listOf<PointerSignal>(PadSignal.Home(HomeEvent.LONG_PRESS)), h.tick(3000))
        assertTrue(h.tick(3500).isEmpty())
        assertEquals(listOf<PointerSignal>(PadSignal.Home(HomeEvent.RELEASE)), h.up(3600))
    }

    @Test
    fun unlockHoldsOneSecondThroughJitterAndCancelsOnDrift() {
        val u = UnlockHold()
        u.down(100f, 100f, 1, 0)
        // 1.6.33: a resting finger's jitter at 120 Hz, 0.6 px in 8 ms (75 px/s), no longer cancels.
        for (k in 1..120) u.move(100f + if (k % 2 == 0) 0.6f else 0f, 100f, 1, k * 8L)
        assertFalse(u.tick(999))
        assertTrue(u.tick(1000))
        assertFalse(u.holding)
        // Drifting past 48 px from the down point cancels, and says why.
        u.down(100f, 100f, 1, 0)
        u.move(150f, 100f, 1, 100)
        assertFalse(u.holding)
        assertFalse(u.tick(1000))
        assertEquals("the finger moved 50 px", u.takeCancel())
        // 40 px does not.
        u.down(100f, 100f, 1, 0)
        u.move(140f, 100f, 1, 100)
        assertTrue(u.holding)
        // A second finger cancels.
        u.move(140f, 100f, 2, 150)
        assertFalse(u.holding)
        assertEquals("a second finger", u.takeCancel())
        // A lift before the second is logged with its time.
        u.down(100f, 100f, 1, 0)
        u.up(400)
        assertEquals("lifted after 400 ms", u.takeCancel())
    }

    @Test
    fun backAndModeBuzz10Ms() {
        val p = pad()
        assertEquals(listOf<PointerSignal>(PointerSignal.Back), p.back())
        assertEquals(listOf<PointerSignal>(PadSignal.Mode(PadMode.HORIZONTAL)), p.changeMode(PadMode.HORIZONTAL))
        assertEquals(listOf(PadHaptic.BACK, PadHaptic.MODE), p.takeHaptics())
        assertEquals(10L, PadHaptic.BACK.ms)
        assertEquals(10L, PadHaptic.MODE.ms)
        assertEquals(PadMode.HORIZONTAL, p.mode)
    }
}
