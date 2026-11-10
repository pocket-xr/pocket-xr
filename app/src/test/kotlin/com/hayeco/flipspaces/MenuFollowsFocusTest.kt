package com.hayeco.flipspaces

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Unreleased (John Davis: the trackpad's Menu never showed Text size, even after clicking a window): the open Menu follows
 * the focused window. It was built once as it opened, so a window focused while it was open never showed in This window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MenuFollowsFocusTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** What the Space publishes, changed by each test as a click or a new Space would. */
    private val answers = mutableMapOf<String, Any?>("spaceActive" to true, "windowCount" to 2)

    @After
    fun tearDown() {
        app.getSharedPreferences("trackpad", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun menu(): PadScreen {
        val host = Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "test host"
                m.name in answers -> answers[m.name]
                m.name == "focusedTextSize" || m.name == "focusedSideBySide" || m.name == "focusedShape" -> null
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

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun aWindowFocusedWhileTheMenuIsOpenShowsItsTextSize() {
        val screen = menu()
        assertNull(screen.menuRow(PadScreen.MENU_TEXT_SIZE))
        // A hand pinch, the gaze or a mouse focuses Chrome with the Menu open.
        answers["focusedTextSize"] = Pair("Chrome", 100)
        answers["focusedSideBySide"] = Pair("Chrome", false)
        screen.refreshDrawerForFocus()
        val row = screen.menuRow(PadScreen.MENU_TEXT_SIZE)
        assertNotNull(row)
        assertEquals("Text size in Chrome", texts(row!!).first())
        assertNotNull(screen.menuRow(PadScreen.MENU_SIDE_BY_SIDE))
        assertEquals("this_window", screen.menuRowTags().first { it.startsWith("menu:section:") }.removePrefix("menu:section:"))
    }

    @Test
    fun itStaysWhileTheMenuIsOpenAndFollowsAChangeOfFocus() {
        answers["focusedTextSize"] = Pair("Chrome", 100)
        val screen = menu()
        val row = screen.menuRow(PadScreen.MENU_TEXT_SIZE)!!
        // Nothing changed: the Menu is not built again, the row is the same view.
        screen.refreshDrawerForFocus()
        assertSame(row, screen.menuRow(PadScreen.MENU_TEXT_SIZE))
        // YouTube clicked with the Menu open.
        answers["focusedTextSize"] = Pair("YouTube", 80)
        screen.refreshDrawerForFocus()
        val texts = texts(screen.menuRow(PadScreen.MENU_TEXT_SIZE)!!)
        assertEquals("Text size in YouTube", texts.first())
        assertEquals(true, "80 %" in texts)
    }

    @Test
    fun theFocusedWindowClosedTakesItsRowAway() {
        answers["focusedTextSize"] = Pair("Chrome", 100)
        val screen = menu()
        answers["focusedTextSize"] = null
        screen.refreshDrawerForFocus()
        assertNull(screen.menuRow(PadScreen.MENU_TEXT_SIZE))
    }

    @Test
    fun aClosedMenuIsNotBuilt() {
        val screen = menu()
        screen.closeDrawer()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(PadScreen.DRAWER_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        answers["focusedTextSize"] = Pair("Chrome", 100)
        screen.refreshDrawerForFocus()
        assertNull(screen.menuRow(PadScreen.MENU_TEXT_SIZE))
    }
}
