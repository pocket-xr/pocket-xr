package com.hayeco.flipspaces

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.Support
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
 * 1.0.1, 1.1.15: the "Buy me a beer" row, first in the trackpad's Menu ([PadScreen]) in a plain build (GitHub,
 * contributors, CI), the update row right under it, and none of it in a build made with -Ppocketxr.play=true for Google
 * Play. Run both: `gradle :app:testDebugUnitTest` and `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SupportMenuRowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val askedForPlay = System.getProperty("pocketxr.play") == "true"

    /** A Menu with a host that answers every question with a plain default. */
    private fun menu(update: String?): PadScreen {
        val host = Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "test host"
                // 1.2.3: no window focused, so the Menu has no Text size or Side by side 3D rows (their Pairs are not
                // the cursor distance's).
                m.name == "focusedTextSize" || m.name == "focusedSideBySide" || m.name == "focusedShape" -> null
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
            showUpdate(update, onUpdate = {}, onLater = {})
            openDrawer()
        }
    }

    @Test
    fun playBuildFollowsTheGradleProperty() {
        assertEquals(askedForPlay, BuildConfig.PLAY_BUILD)
    }

    @Test
    fun koFiIsTheMenusFirstRowInTheGitHubBuild() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        assertEquals(listOf(PadScreen.MENU_KO_FI), menu(update = null).menuRowTags().take(1))
        assertFalse(PadScreen.MENU_UPDATE in menu(update = null).menuRowTags())
        val row = menu(update = null).menuRow(PadScreen.MENU_KO_FI)!!
        assertEquals(Support.DESCRIPTION, row.contentDescription)
        assertTrue("at least 48 dp tall", row.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
    }

    /**
     * 1.1.16: the Menu's other tagged rows come after these two; 1.2.6: the quick tiles first. Unreleased: the Play
     * testers card sits between them until it is done with.
     */
    @Test
    fun theUpdateRowFollowsIt() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val rows = menu(update = "99.0.0").menuRowTags().filterNot { it.startsWith(PadScreen.MENU_PLAY_TESTERS + ":") }
        assertEquals(listOf(PadScreen.MENU_KO_FI, PadScreen.MENU_PLAY_TESTERS, PadScreen.MENU_UPDATE), rows.take(3))
        assertTrue("the quick tiles after the header", rows[3].startsWith(PadScreen.MENU_TILE))
    }

    /** Unreleased: once the testers card is done with, the update row is right under the pill again. */
    @Test
    fun withTheTestersCardDoneTheUpdateRowIsSecond() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        TrackpadPrefs(app).playTesters = com.hayeco.flipspaces.core.PlayTesters.State(done = true)
        assertEquals(listOf(PadScreen.MENU_KO_FI, PadScreen.MENU_UPDATE), menu(update = "99.0.0").menuRowTags().take(2))
    }

    @Test
    fun thePlayBuildHasNoKoFiRow() {
        assumeTrue(BuildConfig.PLAY_BUILD)
        assertNull(menu(update = null).menuRow(PadScreen.MENU_KO_FI))
        assertEquals(listOf(PadScreen.MENU_UPDATE), menu(update = "99.0.0").menuRowTags().take(1))
    }

    @Test
    fun aTapOpensKoFi() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        assertTrue(menu(update = null).menuRow(PadScreen.MENU_KO_FI)!!.performClick())
        val opened = shadowOf(app).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://ko-fi.com/mikehaye", opened.dataString)
        assertEquals(Support.KO_FI_URL, SupportLink.intent().dataString)
    }
}
