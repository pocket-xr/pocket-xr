package com.hayeco.flipspaces

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.pointer.TrackpadMenu
import com.hayeco.flipspaces.core.tutorial.Tour
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 1.5.0: what the phone remembers about the tour ([TourPrefs]) across the first open, an update and a reopen, and the
 * Menu's row that opens it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Tour150Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val stored get() = app.getSharedPreferences("trackpad", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        stored.edit().clear().apply()
    }

    /** The trackpad's own steps as it opens ([TrackpadActivity.onCreate]). */
    private fun open(byItself: Boolean = false, cover: Boolean = false): Tour.Opening {
        val prefs = TourPrefs(app)
        val opening = Tour.onTrackpadOpen(prefs.memory, byItself, cover)
        prefs.memory = Tour.after(prefs.memory, opening)
        return opening
    }

    @Test
    fun aNewInstallIsShownTheTourOnceAndNeverAgain() {
        assertEquals(Tour.Opening.SHOW, open())
        assertTrue(stored.getBoolean("TourSeen", false))
        // The old first-run flag is set too, so an older version put back over this one shows no cards again.
        assertTrue(stored.getBoolean("IntroShown", false))
        assertEquals("NEW_INSTALL", stored.getString("TourArrival", null))
        assertEquals(Tour.Opening.NOTHING, open())
        assertEquals(Tour.Opening.NOTHING, open(cover = true))
    }

    @Test
    fun aCopyFromBeforeTheTourIsOnlyOfferedItAndOnlyOnce() {
        // What every version before 1.5.0 left behind once its cards were shown.
        stored.edit().putBoolean("IntroShown", true).apply()
        assertEquals(Tour.Opening.OFFER, open())
        assertEquals("UPDATE", stored.getString("TourArrival", null))
        assertFalse(stored.getBoolean("TourSeen", false))
        assertEquals(Tour.Opening.NOTHING, open())
    }

    @Test
    fun theOfferWaitsWhileTheTrackpadOpensByItself() {
        stored.edit().putBoolean("IntroShown", true).apply()
        assertEquals(Tour.Opening.NOTHING, open(byItself = true))
        assertEquals(Tour.Opening.NOTHING, open(cover = true))
        assertEquals(Tour.Opening.OFFER, open())
    }

    @Test
    fun aNewInstallPluggedInFoldedStillGetsTheTourLater() {
        assertEquals(Tour.Opening.NOTHING, open(byItself = true, cover = true))
        assertEquals("NEW_INSTALL", stored.getString("TourArrival", null))
        assertEquals(Tour.Opening.GESTURE_CARDS, open(cover = true))
        assertEquals(Tour.Opening.NOTHING, open(cover = true))
        assertEquals(Tour.Opening.SHOW, open())
    }

    @Test
    fun openingItFromTheMenuEndsTheOffer() {
        stored.edit().putBoolean("IntroShown", true).apply()
        // What TourActivity does as it opens.
        TourPrefs(app).apply { memory = Tour.afterOpenedByUser(memory) }
        assertEquals(Tour.Opening.NOTHING, open())
    }

    @Test
    fun whatIsRememberedReadsBackAsWritten() {
        val prefs = TourPrefs(app)
        assertEquals(Tour.Memory(), prefs.memory)
        val m = Tour.Memory(Tour.Arrival.UPDATE, gestureCardsShown = true, seen = true, offerAnswered = true)
        prefs.memory = m
        assertEquals(m, TourPrefs(app).memory)
    }

    // ---- the Menu ----

    private var opened = 0

    private fun menu(): PadScreen {
        val host = Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "test host"
                m.name == "openTour" -> { opened++; null }
                m.name == "focusedTextSize" || m.name == "focusedSideBySide" || m.name == "focusedSwipeScroll" || m.name == "focusedShape" -> null
                t == java.lang.Boolean.TYPE -> false
                t == Integer.TYPE -> 0
                t == java.lang.Float.TYPE -> 0f
                t == java.lang.Long.TYPE -> 0L
                t == java.lang.Double.TYPE -> 0.0
                t == List::class.java -> emptyList<Any>()
                t == String::class.java -> ""
                t.isEnum -> t.enumConstants!![0]
                else -> null
            }
        } as PadHost
        return PadScreen(app, host).apply { openDrawer() }
    }

    @Test
    fun theMenusHelpSectionHasTheTourAboveTheGestureCards() {
        val help = TrackpadMenu.sections(TrackpadMenu.State()).first { it.first == TrackpadMenu.Section.HELP }.second
        assertEquals(listOf(TrackpadMenu.Item.TOUR, TrackpadMenu.Item.HOW_TO), help.take(2))
        // The cover screen is too small for the tour: it keeps the gesture cards only.
        assertFalse(TrackpadMenu.shows(TrackpadMenu.Item.TOUR, TrackpadMenu.State(cover = true)))
        assertTrue(TrackpadMenu.shows(TrackpadMenu.Item.HOW_TO, TrackpadMenu.State(cover = true)))
    }

    @Test
    fun theMenusTourRowOpensTheTour() {
        val screen = menu()
        // Help and more starts closed.
        assertNull(screen.menuRow(PadScreen.MENU_TOUR))
        assertTrue(screen.menuRow("menu:section:help")!!.performClick())
        val row = screen.menuRow(PadScreen.MENU_TOUR)
        assertNotNull(row)
        assertTrue(row!!.performClick())
        assertEquals(1, opened)
    }
}
