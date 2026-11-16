package com.hayeco.flipspaces.core.tutorial

import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.tutorial.Tour.Arrival
import com.hayeco.flipspaces.core.tutorial.Tour.Memory
import com.hayeco.flipspaces.core.tutorial.Tour.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.0: when the tour opens by itself, the one offer after an update, and what it says for the glasses. */
class Tour150Test {
    // ---- first run ----

    @Test
    fun `a new install gets the tour on its first ordinary open of the trackpad`() {
        assertEquals(Opening.SHOW, Tour.onTrackpadOpen(Memory(), openedByItself = false, cover = false))
    }

    @Test
    fun `once shown it never opens by itself again, skipped or not`() {
        val after = Tour.after(Memory(), Opening.SHOW)
        assertTrue(after.seen)
        assertEquals(Arrival.NEW_INSTALL, after.arrival)
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = false))
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = true))
    }

    @Test
    fun `a trackpad that opened by itself shows nothing and uses nothing up`() {
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(Memory(), openedByItself = true, cover = false))
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(Memory(), openedByItself = true, cover = true))
        val unchanged = Tour.after(Memory(), Opening.NOTHING)
        assertFalse(unchanged.seen)
        assertEquals(Opening.SHOW, Tour.onTrackpadOpen(unchanged, openedByItself = false, cover = false))
    }

    @Test
    fun `a new install first opened on the cover gets the gesture cards there and still the tour on the main screen`() {
        assertEquals(Opening.GESTURE_CARDS, Tour.onTrackpadOpen(Memory(), openedByItself = false, cover = true))
        val after = Tour.after(Memory(), Opening.GESTURE_CARDS)
        assertTrue(after.gestureCardsShown)
        // It is still a new install: the cards on the cover do not turn it into an update.
        assertEquals(Arrival.NEW_INSTALL, after.arrival)
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = true))
        assertEquals(Opening.SHOW, Tour.onTrackpadOpen(after, openedByItself = false, cover = false))
    }

    // ---- an update from a version without the tour ----

    private val updated = Memory(gestureCardsShown = true)

    @Test
    fun `an update is never shown the tour by itself, only offered it`() {
        assertEquals(Arrival.UPDATE, Tour.arrived(updated).arrival)
        assertEquals(Opening.OFFER, Tour.onTrackpadOpen(updated, openedByItself = false, cover = false))
    }

    @Test
    fun `the offer is made once, whatever the answer`() {
        val after = Tour.after(updated, Opening.OFFER)
        assertTrue(after.offerAnswered)
        assertFalse(after.seen)
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = false))
    }

    @Test
    fun `no offer on the cover screen or over a trackpad that opened by itself`() {
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(updated, openedByItself = false, cover = true))
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(updated, openedByItself = true, cover = false))
        // The offer is still owed on the next ordinary open.
        assertEquals(Opening.OFFER, Tour.onTrackpadOpen(Tour.after(updated, Opening.NOTHING), openedByItself = false, cover = false))
    }

    @Test
    fun `an update that found the tour in the Menu is not offered it afterwards`() {
        val after = Tour.afterOpenedByUser(updated)
        assertEquals(Arrival.UPDATE, after.arrival)
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = false))
    }

    @Test
    fun `how a copy arrived is decided once and kept`() {
        // A new install whose tour was shown has the old flag set too; it must not turn into an update later.
        val shown = Tour.after(Memory(), Opening.SHOW)
        assertTrue(shown.gestureCardsShown)
        assertEquals(Arrival.NEW_INSTALL, Tour.arrived(shown).arrival)
        assertEquals(Arrival.UPDATE, Tour.arrived(Tour.arrived(updated).copy(gestureCardsShown = false)).arrival)
    }

    @Test
    fun `reopening from the Menu on a new install changes nothing it would be shown`() {
        val after = Tour.afterOpenedByUser(Tour.after(Memory(), Opening.SHOW))
        assertEquals(Opening.NOTHING, Tour.onTrackpadOpen(after, openedByItself = false, cover = false))
    }

    // ---- the steps ----

    @Test
    fun `nine steps, welcome first and done last, and moving stays inside`() {
        assertEquals(9, Tour.STEPS.size)
        assertTrue(Tour.isFirst(Tour.Step.WELCOME))
        assertTrue(Tour.isLast(Tour.Step.DONE))
        assertEquals(Tour.Step.SETUP, Tour.move(Tour.Step.WELCOME, 1))
        assertEquals(Tour.Step.WELCOME, Tour.move(Tour.Step.WELCOME, -1))
        assertEquals(Tour.Step.DONE, Tour.move(Tour.Step.DONE, 1))
        assertEquals(Tour.Step.SETUP, Tour.move(Tour.Step.DONE, -7))
    }

    // ---- setup ----

    @Test
    fun `Shizuku has one next thing to do in each state`() {
        assertEquals(Tour.ShizukuAction.GET, Tour.shizukuAction(ShizukuStatus.NOT_INSTALLED))
        assertEquals(Tour.ShizukuAction.OPEN, Tour.shizukuAction(ShizukuStatus.NOT_RUNNING))
        assertEquals(Tour.ShizukuAction.ALLOW, Tour.shizukuAction(ShizukuStatus.NO_PERMISSION))
        assertEquals(Tour.ShizukuAction.NONE, Tour.shizukuAction(ShizukuStatus.READY))
    }

    @Test
    fun `the last step lists what is still open, and nothing once it is all there`() {
        assertEquals(listOf(Tour.Remaining.SHIZUKU, Tour.Remaining.PLUG_IN_GLASSES), Tour.remaining(false, ShizukuStatus.NOT_INSTALLED))
        assertEquals(listOf(Tour.Remaining.SHIZUKU), Tour.remaining(true, ShizukuStatus.NO_PERMISSION))
        assertEquals(listOf(Tour.Remaining.PLUG_IN_GLASSES), Tour.remaining(false, ShizukuStatus.READY))
        assertTrue(Tour.remaining(true, ShizukuStatus.READY).isEmpty())
    }

    // ---- glasses: the README's tiers ----

    @Test
    fun `every glasses model has a tier, and only the ones the project ran are called tested`() {
        assertNull(Tour.support(null))
        for (m in GlassesModel.entries) {
            val tier = Tour.support(m)!!
            assertEquals("${m.label}: tested by the project", m.tested, tier == Tour.Support.TESTED)
        }
        assertEquals(Tour.Support.COMMUNITY, Tour.support(GlassesModel.ONE_PRO))
        assertEquals(Tour.Support.COMMUNITY, Tour.support(GlassesModel.ONE_S))
        assertEquals(Tour.Support.EARLY, Tour.support(GlassesModel.ONE_PRO_PLUS))
        assertEquals(Tour.Support.AIR_FAMILY, Tour.support(GlassesModel.AIR))
        assertEquals(Tour.Support.VITURE_FAMILY, Tour.support(GlassesModel.VITURE))
        assertEquals(Tour.Support.UNLISTED, Tour.support(GlassesModel.OTHER_XREAL))
        assertEquals(Tour.Support.UNLISTED, Tour.support(GlassesModel.UNKNOWN))
    }

    @Test
    fun `3D is only promised where Pocket XR can switch it`() {
        assertEquals(Tour.Depth.BY_ITSELF, Tour.depth(null))
        assertEquals(Tour.Depth.BY_ITSELF, Tour.depth(GlassesModel.AURA))
        assertEquals(Tour.Depth.GLASSES_MENU, Tour.depth(GlassesModel.VITURE))
        assertEquals(Tour.Depth.NOT_YET, Tour.depth(GlassesModel.AIR))
    }

    @Test
    fun `Steady mode is called tested on the Aura only, and not offered where the glasses lack it`() {
        assertEquals(Tour.Steady.SAY_WHICH, Tour.steady(null))
        assertEquals(Tour.Steady.HERE_TESTED, Tour.steady(GlassesModel.AURA))
        for (m in listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_PRO_PLUS, GlassesModel.ONE_S)) {
            assertEquals(m.label, Tour.Steady.HERE_UNTESTED, Tour.steady(m))
        }
        for (m in listOf(GlassesModel.AIR, GlassesModel.VITURE, GlassesModel.OTHER_XREAL, GlassesModel.UNKNOWN)) {
            assertEquals(m.label, Tour.Steady.NOT_HERE, Tour.steady(m))
        }
    }
}
