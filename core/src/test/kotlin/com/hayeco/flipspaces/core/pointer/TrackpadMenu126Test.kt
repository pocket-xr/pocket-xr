package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.pointer.TrackpadMenu.Item
import com.hayeco.flipspaces.core.pointer.TrackpadMenu.Section
import com.hayeco.flipspaces.core.pointer.TrackpadMenu.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.6 (Mike: the Menu felt congested): the Menu's sections, their order, and the rows left out where they don't apply. */
class TrackpadMenu126Test {
    /** The Space open on the open phone, three windows, one focused, glasses known. */
    private val session = State(
        spaceActive = true,
        steadyOffered = true,
        textSizeShown = true,
        sideBySideShown = true,
        windowCount = 3,
        glassesKnown = true,
    )

    @Test
    fun theSectionsComeInThisOrder() {
        assertEquals(
            listOf(Section.QUICK, Section.THIS_WINDOW, Section.SPACE, Section.TRACKPAD, Section.WINDOWS_GLASSES, Section.HELP, Section.END),
            TrackpadMenu.sections(session).map { it.first },
        )
        assertEquals(listOf("This window", "Space", "Trackpad and controls", "Windows and glasses", "Help and more"), Section.entries.mapNotNull { it.title })
    }

    @Test
    fun theEverySessionThingsAreTheQuickTilesFirst() {
        val quick = TrackpadMenu.sections(session).first()
        assertEquals(Section.QUICK, quick.first)
        assertEquals(listOf(Item.STEADY, Item.POCKET_MODE, Item.GAME_KEYS, Item.CONTROL_CENTER, Item.RECORD, Item.PHONE_HOME, Item.SETTINGS), quick.second)
        assertEquals(listOf(Item.TEXT_SIZE, Item.SIDE_BY_SIDE), TrackpadMenu.sections(session)[1].second)
    }

    @Test
    fun onlyTheSpaceStartsOpen() {
        assertEquals(listOf(Section.SPACE), Section.entries.filter { it.collapsible && it.opensByDefault })
        assertEquals(listOf(Section.TRACKPAD, Section.WINDOWS_GLASSES, Section.HELP), Section.entries.filter { it.collapsible && !it.opensByDefault })
        // Each kept under its own key, none an existing setting's.
        assertEquals(Section.entries.size, Section.entries.map { it.key }.toSet().size)
        assertTrue(Section.entries.all { it.key.startsWith("menu_open_") })
    }

    @Test
    fun exitIsAlwaysLast() {
        assertEquals(Item.EXIT, TrackpadMenu.items(session).last())
        assertEquals(Item.EXIT, TrackpadMenu.items(State()).last())
        assertEquals(Item.EXIT, TrackpadMenu.items(State(cover = true)).last())
    }

    @Test
    fun withoutTheSpaceItsActionsAreLeftOut() {
        val items = TrackpadMenu.items(State())
        for (gone in listOf(Item.POCKET_MODE, Item.CONTROL_CENTER, Item.GAME_KEYS, Item.RECORD, Item.GAME_CONTROLLER, Item.ARRANGE, Item.ADJUST_ALL, Item.TEXT_SIZE, Item.SIDE_BY_SIDE)) {
            assertFalse("$gone", gone in items)
        }
        // Settings stay, so they can be set before the Space opens.
        for (kept in listOf(Item.PHONE_HOME, Item.SETTINGS, Item.LOCK_SCREENS, Item.PRESETS, Item.CURSOR_SPEED, Item.NEW_WINDOWS_CURVE, Item.HOW_TO, Item.RESET, Item.DEVELOPER)) {
            assertTrue("$kept", kept in items)
        }
    }

    @Test
    fun theControlCentersSpaceControlsAreNotRepeatedInTheMenu() {
        val everything = State(spaceActive = true, windowCount = 3, textSizeShown = true, shapeShown = true, sideBySideShown = true)
        for (item in TrackpadMenu.CONTROL_CENTER_ONLY) assertFalse("$item", item in TrackpadMenu.items(everything))
    }

    @Test
    fun aModeThatIsOnStaysSoItCanBeTurnedOff() {
        val items = TrackpadMenu.items(State(gameKeysOn = true, recording = true, controllerMode = GameControllerMode.TOUCH))
        assertTrue(Item.GAME_KEYS in items)
        assertTrue(Item.RECORD in items)
        assertTrue(Item.GAME_CONTROLLER in items)
        assertTrue(Item.TOUCH_SPOTS in items)
        assertTrue(Item.CLEAR_TOUCH_SPOTS in items)
        assertFalse(Item.TOUCH_SPOTS in TrackpadMenu.items(session.copy(controllerMode = GameControllerMode.GAMEPAD)))
    }

    @Test
    fun deviceAndScreenRules() {
        assertFalse(Item.STEADY in TrackpadMenu.items(session.copy(steadyOffered = false)))
        assertFalse(Item.UNLOCK in TrackpadMenu.items(session))
        assertTrue(Item.UNLOCK in TrackpadMenu.items(session.copy(phoneLocked = true)))
        assertFalse(Item.GLASSES_CHECK in TrackpadMenu.items(session.copy(glassesKnown = false)))
        assertFalse(Item.AIR_DISPLAY in TrackpadMenu.items(session))
        assertEquals(listOf(Item.AIR_DISPLAY), TrackpadMenu.items(session.copy(airOffered = true)).filter { it == Item.AIR_DISPLAY || it == Item.AIR_DISPLAY_MODE })
        assertTrue(Item.AIR_DISPLAY_MODE in TrackpadMenu.items(session.copy(airOffered = true, airOn = true)))
        assertTrue(Item.SIDE_BY_SIDE_FLAT in TrackpadMenu.items(session.copy(sideBySideOn = true)))
        assertFalse(Item.SIDE_BY_SIDE_FLAT in TrackpadMenu.items(session))
        assertTrue(Item.DEVELOPER_CARD in TrackpadMenu.items(session.copy(developerShown = true)))
        assertFalse(Item.DEVELOPER_CARD in TrackpadMenu.items(session))
    }

    @Test
    fun theSidewaysDirectionOnlyOnTheSidewaysTrackpad() {
        assertFalse(Item.SIDEWAYS_DIRECTION in TrackpadMenu.items(session))
        assertTrue(Item.SIDEWAYS_DIRECTION in TrackpadMenu.items(session.copy(directionApplies = true)))
        assertFalse(Item.SIDEWAYS_DIRECTION in TrackpadMenu.items(session.copy(directionApplies = true, cover = true)))
    }

    @Test
    fun theCoverHasItsOwnSidesAndNoGameKeys() {
        val cover = TrackpadMenu.items(session.copy(cover = true, gameKeysOn = true, controllerMode = GameControllerMode.TOUCH))
        assertTrue(Item.COVER_BUTTONS_SIDE in cover)
        assertTrue(Item.COVER_STRIP_SIDE in cover)
        for (gone in listOf(Item.STRIP_SIDE, Item.GAME_KEYS, Item.GAME_CONTROLLER, Item.TOUCH_SPOTS)) assertFalse("$gone", gone in cover)
        val open = TrackpadMenu.items(session)
        assertTrue(Item.STRIP_SIDE in open)
        assertFalse(Item.COVER_BUTTONS_SIDE in open)
    }

    @Test
    fun everyItemHasAPlace() {
        // Everything at once: each item shows somewhere, so no feature fell out of the Menu, except the ones only the
        // Control Center on the glasses has (1.3.4).
        val all = State(
            spaceActive = true, phoneLocked = true, steadyOffered = true, gameKeysOn = true, recording = true,
            controllerMode = GameControllerMode.TOUCH, textSizeShown = true, shapeShown = true, sideBySideShown = true, sideBySideOn = true,
            windowCount = 2, glassesKnown = true, airOffered = true, airOn = true, directionApplies = true, developerShown = true,
        )
        val open = TrackpadMenu.items(all)
        val cover = TrackpadMenu.items(all.copy(cover = true))
        assertEquals(Item.entries.toSet() - TrackpadMenu.CONTROL_CENTER_ONLY, (open + cover).toSet())
    }
}
