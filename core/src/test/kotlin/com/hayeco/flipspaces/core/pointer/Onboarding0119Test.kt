package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.9: which tutorial pages show for which glasses and device. Issue 31 (a One Pro in Anchor mode): the One family
 * gets a glasses setup page first; issue 33 (a Retroid Nova): Spatial Mouse is named and shown held the device's way.
 */
class Onboarding0119Test {
    private val oneFamily = listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_S)

    @Test fun theOneFamilyGetsFollowModeAndStabilizerOff() {
        for (m in oneFamily) {
            val page = GlassesSetup.forModel(m)
            assertNotNull(m.label, page)
            page!!
            assertEquals("Set up your ${m.label}", page.title)
            assertEquals(3, page.steps.size)
            assertTrue(page.steps.any { it.contains("Follow mode, not Anchor") })
            assertTrue(page.steps.any { it.contains("Stabilizer off") })
            assertTrue(page.check.contains("look left and right"))
            assertTrue(page.check.contains("twice as far"))
            assertTrue(page.check.contains("Anchor mode"))
        }
    }

    @Test fun otherGlassesHaveNoSetupPage() {
        for (m in listOf(GlassesModel.AURA, GlassesModel.OTHER_XREAL, GlassesModel.UNKNOWN, null)) {
            assertNull("$m", GlassesSetup.forModel(m))
            assertNull("$m", IntroPages.glassesSetup(m))
        }
    }

    @Test fun thePageFollowsTheProfilesHint() {
        // Only what applies to the model: exactly the models with the One's setup hint have the page.
        for (m in GlassesModel.entries) assertEquals(m.name, m.setupHint != null, GlassesSetup.forModel(m) != null)
    }

    /** 1.1.16 (issue 12): the glasses' setup moved from the first run's pages to the setup check. */
    @Test fun firstRunIsTheTrackpadPagesTheSetupIsTheCheck() {
        val pages = IntroPages.firstRun(landscapeHandheld = false, spatialMouse = true)
        // 1.2.1: with the add-on's page before the last one.
        assertEquals(listOf("Point and click", "Scroll and zoom", "Three fingers", com.hayeco.flipspaces.core.AddonSetup.TITLE, IntroPages.SPATIAL_MOUSE_TITLE), pages.map { it.title })
        val page = IntroPages.glassesSetup(GlassesModel.ONE_PRO)
        assertNotNull(page)
        assertEquals(IntroArt.GLASSES_MENU, page!!.art)
        assertNotNull(page.note)
    }

    @Test fun spatialMouseIsNamedAndHeldUprightOnAPhone() {
        val p = IntroPages.spatialMouse(landscapeHandheld = false)
        assertEquals("Spatial Mouse (aim with your phone)", p.title)
        assertEquals(IntroArt.AIM_PHONE, p.art)
        assertTrue(p.items.any { it.contains("upright") })
        assertFalse(p.items.any { it.contains("sideways") })
    }

    @Test fun spatialMouseIsHeldSidewaysOnALandscapeHandheld() {
        val p = IntroPages.spatialMouse(landscapeHandheld = true)
        assertEquals("Spatial Mouse (aim with your phone)", p.title)
        assertEquals(IntroArt.AIM_HANDHELD, p.art)
        assertTrue(p.items.any { it.contains("sideways") && it.contains("game controller") })
        assertTrue(p.items.any { it.contains("Tilt the top edge up and down") })
        // The same check the trackpad uses: a Retroid Nova is portrait by nature with built in controls.
        val nova = TrackpadSettings.landscapeHandheld(TrackpadSettings.landscapeByNature(1080, 1920, 1), builtInGamepad = true)
        assertEquals(IntroArt.AIM_HANDHELD, IntroPages.firstRun(nova, spatialMouse = true).last().art)
    }

    @Test fun noSpatialMousePageWithoutTheSensor() {
        val pages = IntroPages.firstRun(landscapeHandheld = true, spatialMouse = false)
        // 1.2.1: with the add-on's page before the last one.
        assertEquals(listOf("Point and click", "Scroll and zoom", com.hayeco.flipspaces.core.AddonSetup.TITLE, "Three fingers"), pages.map { it.title })
        // 1.1.10: the same pages as the gestures, the last one with where Exit is.
        assertEquals(IntroPages.GESTURES.map { it.title }, IntroPages.trackpad(landscapeHandheld = false, spatialMouse = false).map { it.title })
    }

    @Test fun everyPageIsShortEnoughForAShortLandscapeScreen() {
        // 1.1.2: a Retroid's 432 dp tall card holds a title, three steps of up to two lines and a three line note.
        val all = IntroPages.trackpad(true, true) + IntroPages.trackpad(false, true) + oneFamily.mapNotNull { IntroPages.glassesSetup(it) }
        for (p in all) {
            assertTrue(p.title, p.items.size <= 3)
            assertTrue(p.title, p.title.length <= 40)
            for (item in p.items) assertTrue(item, item.length <= 95)
            p.note?.let { assertTrue(it, it.length <= 130) }
        }
    }

    @Test fun thePluggedInModelComesFromTheUsbIdAndName() {
        assertNull(GlassesSetup.plugged(null, "XREAL One Pro"))
        assertEquals(GlassesModel.AURA, GlassesSetup.plugged(GlassesModel.USB_AURA, null))
        assertEquals(GlassesModel.ONE, GlassesSetup.plugged(GlassesModel.USB_ONE, "XREAL One"))
        assertEquals(GlassesModel.ONE, GlassesSetup.plugged(GlassesModel.USB_ONE, ""))
        // A One Pro's product id is not known: its USB name tells.
        assertEquals(GlassesModel.ONE_PRO, GlassesSetup.plugged(0x0440, "XREAL One Pro"))
        assertEquals(GlassesModel.OTHER_XREAL, GlassesSetup.plugged(0x0440, null))
    }

    @Test fun aNewModelShowsItsPageOnceWhenPluggedIn() {
        assertTrue(GlassesSetup.showOnPlug(GlassesModel.ONE_PRO, emptySet()))
        assertTrue(GlassesSetup.showOnPlug(GlassesModel.ONE_PRO, setOf(GlassesModel.ONE.key)))
        assertFalse(GlassesSetup.showOnPlug(GlassesModel.ONE_PRO, setOf(GlassesModel.ONE_PRO.key)))
        assertFalse(GlassesSetup.showOnPlug(GlassesModel.AURA, emptySet()))
        assertFalse(GlassesSetup.showOnPlug(null, emptySet()))
    }

    @Test fun aWorldLockDutyOnTheOneFamilyIsLikelyAnchorMode() {
        assertTrue(GlassesSetup.anchorLikely(GlassesModel.ONE_PRO, 30, dutyOurs = false))
        assertTrue(GlassesSetup.anchorLikely(GlassesModel.ONE, GlassesSetup.WORLD_LOCK_DUTY_MAX, dutyOurs = false))
        // Head lock (Follow mode) reads about 90 %.
        assertFalse(GlassesSetup.anchorLikely(GlassesModel.ONE_PRO, 90, dutyOurs = false))
        // Not read, set by Pocket XR itself (the hover test, Sharper turns), or glasses with no setup page.
        assertFalse(GlassesSetup.anchorLikely(GlassesModel.ONE_PRO, null, dutyOurs = false))
        assertFalse(GlassesSetup.anchorLikely(GlassesModel.ONE_PRO, 30, dutyOurs = true))
        assertFalse(GlassesSetup.anchorLikely(GlassesModel.AURA, 30, dutyOurs = false))
        assertFalse(GlassesSetup.anchorLikely(null, 30, dutyOurs = false))
        assertFalse(GlassesSetup.anchorLikely(GlassesModel.ONE, 0, dutyOurs = false))
    }
}
