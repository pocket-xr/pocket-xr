package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.37 (Mike: an Immersive button, one window as a movie screen, the others gone into nothingness). */
class Immersive0537Test {
    private val video = SpacePanel(1, "YouTube", "y", "y", yawDeg = -30f, pitchDeg = 8f, distanceM = 1.4f, widthDeg = 40f, heightDeg = 22.5f)
    private val chat = SpacePanel(2, "Chat", "c", "c", yawDeg = 25f, pitchDeg = 0f, distanceM = 1.2f, widthDeg = 24f, heightDeg = 34f)
    private val mail = SpacePanel(3, "Mail", "m", "m", yawDeg = 60f, pitchDeg = 0f, distanceM = 1.6f, widthDeg = 30f, heightDeg = 30f)
    private val layout = SpaceLayout(listOf(video, chat, mail))

    @Test
    fun theHeroBecomesAWideScreenAheadKeepingItsShapeAndTheOthersFadeAway() {
        val m = ImmersiveMode()
        assertTrue(m.enter(layout, 1, gazeYaw = 0f, gazePitch = 0f, nowMs = 0L))
        var l = layout
        var t = 0L
        var commits = 0
        while (m.phase != ImmersiveMode.Phase.ON) {
            t += 16L
            m.step(l, t)?.let { s -> l = s.layout; if (s.commit) commits++ }
        }
        assertEquals(1, commits)
        val hero = l.panel(1)!!
        assertEquals(0f, hero.yawDeg, 0.01f)
        assertEquals(ImmersiveMode.CINEMA_M, hero.distanceM, 1e-4f)
        assertEquals(video.widthM / video.heightM, hero.widthM / hero.heightM, 0.01f)
        // 0.5.38: inside the glasses' 52.2 by 30.8 deg view with a margin, filling it one way.
        assertTrue("inside the view across", hero.widthDeg <= ImmersiveMode.FIT * 52.2f + 0.05f)
        assertTrue("inside the view up and down", hero.heightDeg <= ImmersiveMode.FIT * 30.8f + 0.05f)
        assertTrue("fills it one way", hero.widthDeg >= ImmersiveMode.FIT * 52.2f - 0.5f || hero.heightDeg >= ImmersiveMode.FIT * 30.8f - 0.5f)
        assertEquals(0f, m.alpha(2, t), 0f)
        assertEquals(1f, m.alpha(1, t), 0f)
        assertTrue(m.hidden(2, t))
        assertFalse(m.hidden(1, t))
        assertEquals("receded", chat.distanceM * ImmersiveMode.RECEDE, l.panel(2)!!.distanceM, 1e-3f)
        assertEquals("its size in the room kept as it recedes", chat.widthM, l.panel(2)!!.widthM, 1e-3f)
    }

    @Test
    fun aTallWindowTakesTheViewsShapeAndFillsIt() {
        // 1.6.0 (GitHub #118): a phone-shaped YouTube window in Immersive filled only a strip of the view.
        val tall = video.copy(id = 9, widthDeg = 12f, heightDeg = 24f)
        val c = ImmersiveMode.cinema(tall, 0f, 0f, 52.2f, 30.8f)
        assertEquals("across", ImmersiveMode.FIT * 52.2f, c.widthDeg, 0.6f)
        assertEquals("up and down", ImmersiveMode.FIT * 30.8f, c.heightDeg, 0.6f)
        // A 16:9 video is near the view's shape and keeps its own.
        val v = ImmersiveMode.cinema(video, 0f, 0f, 52.2f, 30.8f)
        assertEquals(video.widthM / video.heightM, v.widthM / v.heightM, 0.01f)
    }

    @Test
    fun exitingPutsEveryWindowBackExactly() {
        val m = ImmersiveMode()
        m.enter(layout, 1, 10f, 0f, 0L)
        var l = layout
        var t = 0L
        while (m.phase != ImmersiveMode.Phase.ON) { t += 16L; m.step(l, t)?.let { l = it.layout } }
        m.exit(t)
        while (m.phase != ImmersiveMode.Phase.IDLE) { t += 16L; m.step(l, t)?.let { l = it.layout } }
        for (p in layout.panels) {
            val back = l.panel(p.id)!!
            assertEquals(p.yawDeg, back.yawDeg, 1e-3f)
            assertEquals(p.pitchDeg, back.pitchDeg, 1e-3f)
            assertEquals(p.distanceM, back.distanceM, 1e-3f)
            assertEquals(p.widthDeg, back.widthDeg, 1e-2f)
            assertEquals(p.heightDeg, back.heightDeg, 1e-2f)
        }
        assertNull(m.heroId)
        assertEquals(1f, m.alpha(2, t), 0f)
        assertNull(m.step(l, t + 16))
    }

    @Test
    fun theHeroMovesSmoothlyWithNoJumpsAndTheOthersLeaveOneAfterAnother() {
        val m = ImmersiveMode()
        m.enter(layout, 1, 0f, 0f, 0L)
        var prev = video
        var l = layout
        var maxStep = 0f
        for (t in 16L..ImmersiveMode.ENTER_MS step 16) {
            l = m.step(l, t)!!.layout
            val h = l.panel(1)!!
            maxStep = maxOf(maxStep, kotlin.math.abs(wrapDegrees(h.yawDeg - prev.yawDeg)))
            prev = h
        }
        assertTrue("no frame moves it more than 3 degrees ($maxStep)", maxStep < 3f)
        // At 100 ms the nearest other (Chat) has started fading, the farther (Mail) barely.
        assertTrue(m.alpha(2, 300L) < m.alpha(3, 300L))
    }
}
