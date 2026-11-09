package com.hayeco.flipspaces

import android.app.Application
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.Support
import com.hayeco.flipspaces.core.pointer.TrackpadMenu
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 1.2.6 (Mike: the Menu felt congested): the built Menu follows [TrackpadMenu]: the quick tiles first, the sections in
 * order, the closed ones showing only their heading until tapped, and the Ko-fi pill in the theme's faint blue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrackpadMenu126Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        app.getSharedPreferences("trackpad", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun menu(answers: Map<String, Any?>, atTrackpad: Boolean = false): PadScreen {
        val host = Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "test host"
                m.name in answers -> answers[m.name]
                m.name == "focusedTextSize" || m.name == "focusedSideBySide" -> null
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
        return PadScreen(app, host).apply { openDrawer(atTrackpad) }
    }

    private val session = mapOf<String, Any?>("spaceActive" to true, "steadyOffered" to true, "windowCount" to 2, "focusedTextSize" to Pair("Chrome", 100))

    private fun tiles(screen: PadScreen) = screen.menuRowTags().filter { it.startsWith(PadScreen.MENU_TILE) }.map { it.removePrefix(PadScreen.MENU_TILE) }
    private fun sections(screen: PadScreen) = screen.menuRowTags().filter { it.startsWith("menu:section:") }.map { it.removePrefix("menu:section:") }

    @Test
    fun theQuickTilesComeFirstInTheModelsOrder() {
        val screen = menu(session)
        assertEquals(listOf("steady", "pocket_mode", "game_keys", "control_center", "record", "phone_home", "settings"), tiles(screen))
        val tags = screen.menuRowTags()
        assertTrue("tiles before the sections", tags.indexOfFirst { it.startsWith(PadScreen.MENU_TILE) } < tags.indexOfFirst { it.startsWith("menu:section:") })
    }

    @Test
    fun theSectionsComeInOrder() {
        assertEquals(listOf("this_window", "space", "trackpad", "windows_glasses", "help"), sections(menu(session)))
        // Nothing focused: no This window.
        assertEquals(listOf("space", "trackpad", "windows_glasses", "help"), sections(menu(emptyMap())))
    }

    @Test
    fun withoutTheSpaceItsTilesAreLeftOut() {
        assertEquals(listOf("phone_home", "settings"), tiles(menu(emptyMap())))
        assertEquals(listOf("unlock", "phone_home", "settings"), tiles(menu(mapOf("phoneLocked" to true))))
    }

    @Test
    fun theSpaceIsOpenAndTheRestStartClosedUntilTapped() {
        val screen = menu(session)
        // Lock screens is in the Space, open by default.
        assertNotNull(screen.menuRow(com.hayeco.flipspaces.LockKey.MENU_TAG))
        // The glasses check is in Windows and glasses, closed by default.
        val withGlasses = menu(session + ("glassesCheckAvailable" to true))
        assertNull(withGlasses.menuRow(PadScreen.MENU_GLASSES_CHECK))
        assertTrue(withGlasses.menuRow("menu:section:windows_glasses")!!.performClick())
        assertNotNull(withGlasses.menuRow(PadScreen.MENU_GLASSES_CHECK))
        // Remembered: the next Menu has it open too.
        assertNotNull(menu(session + ("glassesCheckAvailable" to true)).menuRow(PadScreen.MENU_GLASSES_CHECK))
        assertTrue(app.getSharedPreferences("trackpad", Context.MODE_PRIVATE).getBoolean(TrackpadMenu.Section.WINDOWS_GLASSES.key, false))
    }

    @Test
    fun fromSettingsTheTrackpadSectionOpens() {
        assertFalse(app.getSharedPreferences("trackpad", Context.MODE_PRIVATE).getBoolean(TrackpadMenu.Section.TRACKPAD.key, false))
        menu(session, atTrackpad = true)
        assertTrue(app.getSharedPreferences("trackpad", Context.MODE_PRIVATE).getBoolean(TrackpadMenu.Section.TRACKPAD.key, false))
    }

    @Test
    fun theKoFiPillIsTheFaintThemeBlue() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val pill = menu(session).menuRow(PadScreen.MENU_KO_FI)!!
        val bg = pill.background as GradientDrawable
        assertEquals(Support.FILL, bg.color!!.defaultColor)
        assertEquals(PadScreen.MENU_KO_FI, menu(session).menuRowTags().first())
    }
}
