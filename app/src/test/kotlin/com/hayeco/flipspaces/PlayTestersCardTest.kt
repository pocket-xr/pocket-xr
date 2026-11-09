package com.hayeco.flipspaces

import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.PlayTesters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Unreleased: the Google Play testers card in the trackpad's Menu ([PadScreen], [PlayTesters]), shown in a plain build and
 * never in one made with -Ppocketxr.play=true. Run both: `gradle :app:testDebugUnitTest` and
 * `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayTestersCardTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = TrackpadPrefs(app)

    /** A Menu with a host that answers every question with a plain default (as [SupportMenuRowTest]'s). */
    private fun menu(): PadScreen {
        val host = Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "test host"
                m.name == "focusedTextSize" || m.name == "focusedSideBySide" -> null
                t == java.lang.Boolean.TYPE -> false
                t == Integer.TYPE -> 0
                t == java.lang.Float.TYPE -> 0f
                t == java.lang.Long.TYPE -> 0L
                t == java.lang.Double.TYPE -> 0.0
                t == List::class.java -> emptyList<Any>()
                t == Pair::class.java -> Pair(2f, "2 m")
                t.isEnum -> t.enumConstants!![0]
                else -> null
            }
        } as PadHost
        return PadScreen(app, host).apply {
            showUpdate("99.0.0", onUpdate = {}, onLater = {})
            openDrawer()
        }
    }

    private fun PadScreen.press(tag: String) = assertTrue(tag, menuRow(tag)!!.performClick())

    @Test
    fun aFreshInstallSeesTheCardOverTheUpdateRow() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val m = menu()
        assertNotNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS))
        // In the Menu's header: right under the Ko-fi pill, over the update row and the quick tiles.
        val tags = m.menuRowTags()
        assertEquals(tags.indexOf(PadScreen.MENU_KO_FI) + 1, tags.indexOf(PadScreen.MENU_PLAY_TESTERS))
        assertTrue(tags.indexOf(PadScreen.MENU_PLAY_TESTERS) < tags.indexOf(PadScreen.MENU_UPDATE))
        assertTrue(tags.indexOf(PadScreen.MENU_UPDATE) < tags.indexOfFirst { it.startsWith(PadScreen.MENU_TILE) })
        assertEquals(PlayTesters.JOIN, (m.menuRow(PadScreen.MENU_PLAY_TESTERS_JOIN) as TextView).text)
        assertNull("no Play invite before Join", m.menuRow(PadScreen.MENU_PLAY_TESTERS_PLAY))
        assertNotNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS_NOT_NOW))
        assertNotNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS_ALREADY))
    }

    @Test
    fun thePlayBuildNeverShowsIt() {
        assumeTrue(BuildConfig.PLAY_BUILD)
        assertNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS))
    }

    @Test
    fun joinOpensTheGroupThenOffersThePlayInvite() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val m = menu()
        m.press(PadScreen.MENU_PLAY_TESTERS_JOIN)
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(PlayTesters.GROUP_URL, opened.dataString)
        assertTrue(prefs.playTesters.groupOpened)
        assertNotNull("still shown after Join", m.menuRow(PadScreen.MENU_PLAY_TESTERS))
        assertEquals(PlayTesters.OPEN_PLAY, (m.menuRow(PadScreen.MENU_PLAY_TESTERS_PLAY) as TextView).text)
        // A new Menu (back from the browser) still offers it.
        assertNotNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS_PLAY))
    }

    @Test
    fun thePlayInviteFinishesItForGood() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val m = menu()
        m.press(PadScreen.MENU_PLAY_TESTERS_JOIN)
        shadowOf(app).nextStartedActivity
        m.press(PadScreen.MENU_PLAY_TESTERS_PLAY)
        assertEquals(PlayTesters.PLAY_URL, shadowOf(app).nextStartedActivity.dataString)
        assertTrue(prefs.playTesters.done)
        assertNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS))
        assertNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS))
    }

    @Test
    fun notNowHidesItForSevenDays() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val before = System.currentTimeMillis()
        val m = menu()
        m.press(PadScreen.MENU_PLAY_TESTERS_NOT_NOW)
        assertNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS))
        assertNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS))
        val until = prefs.playTesters.snoozedUntil
        assertTrue(until >= before + PlayTesters.SNOOZE_MS)
        assertFalse(prefs.playTesters.done)
        // Seven days on, it is back.
        prefs.playTesters = prefs.playTesters.copy(snoozedUntil = System.currentTimeMillis() - 1)
        assertNotNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS))
    }

    @Test
    fun alreadyJoinedHidesItForGood() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val m = menu()
        m.press(PadScreen.MENU_PLAY_TESTERS_ALREADY)
        assertNull(m.menuRow(PadScreen.MENU_PLAY_TESTERS))
        assertTrue(prefs.playTesters.done)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(menu().menuRow(PadScreen.MENU_PLAY_TESTERS))
    }

    @Test
    fun itsOwnKeysLeaveTheTrackpadSettingsAlone() {
        val settings = prefs.settings
        prefs.playTesters = PlayTesters.State(groupOpened = true, snoozedUntil = 5L, done = true)
        assertEquals(settings, prefs.settings)
        assertEquals(PlayTesters.State(groupOpened = true, snoozedUntil = 5L, done = true), prefs.playTesters)
    }
}
