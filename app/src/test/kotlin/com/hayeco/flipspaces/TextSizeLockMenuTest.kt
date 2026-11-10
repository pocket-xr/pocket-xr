package com.hayeco.flipspaces

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 1.2.7 (Mike: "user shouldnt be allowed to press the resize buttons again until its reloaded from the actual resize"):
 * after a tap the Menu's Text size keys are off and dimmed, with Resizing under them, until the Space laid it out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TextSizeLockMenuTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val answers = mutableMapOf<String, Any?>(
        "spaceActive" to true, "windowCount" to 1, "focusedTextSize" to Pair("Chrome", 100), "textSizeBusy" to false,
    )

    /** The steps the host was asked for; each one locks the keys, as [TrackpadActivity]'s lock does. */
    private val steps = mutableListOf<Int>()

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
                m.name == "textSizeStep" -> {
                    steps += args!![0] as Int
                    answers["textSizeBusy"] = true
                    null
                }
                m.name in answers -> answers[m.name]
                m.name == "focusedSideBySide" || m.name == "focusedShape" -> null
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

    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun key(screen: PadScreen, description: String): View =
        views(screen.menuRow(PadScreen.MENU_TEXT_SIZE)!!).first { it.contentDescription == description }

    private fun texts(screen: PadScreen): List<String> = views(screen.menuRow(PadScreen.MENU_TEXT_SIZE)!!).filterIsInstance<TextView>().map { it.text.toString() }

    @Test
    fun aTapTurnsTheKeysOffWithResizingUntilTheSpaceSettles() {
        val screen = menu()
        assertTrue(key(screen, "Larger text").isEnabled)
        assertFalse(PadScreen.TEXT_SIZE_BUSY_LABEL in texts(screen))
        key(screen, "Larger text").performClick()
        assertEquals(listOf(1), steps)
        // Dimmed at once, all three off, with the hint.
        assertFalse(key(screen, "Larger text").isEnabled)
        assertFalse(key(screen, "Smaller text").isEnabled)
        assertTrue(PadScreen.TEXT_SIZE_BUSY_LABEL in texts(screen))
        assertEquals(PadScreen.TEXT_SIZE_BUSY_ALPHA, (key(screen, "Larger text").parent as View).alpha)
        // The Space laid it out at 110 %: the keys come back.
        answers["textSizeBusy"] = false
        answers["focusedTextSize"] = Pair("Chrome", 110)
        screen.refreshDrawerForFocus()
        assertTrue(key(screen, "Larger text").isEnabled)
        assertTrue(key(screen, "Text size back to 100 %").isEnabled)
        assertFalse(PadScreen.TEXT_SIZE_BUSY_LABEL in texts(screen))
    }

    @Test
    fun tapsWhileOffAreDroppedNotQueued() {
        val screen = menu()
        val larger = key(screen, "Larger text")
        larger.performClick()
        // The old view, still on screen for a moment, and the new dimmed one: neither sends anything more.
        larger.performClick()
        key(screen, "Smaller text").performClick()
        key(screen, "Larger text").performClick()
        assertEquals(listOf(1), steps)
    }

    @Test
    fun theKeysComeBackWhenTheLockTimesOutWithNoNewSize() {
        val screen = menu()
        key(screen, "Smaller text").performClick()
        assertFalse(key(screen, "Smaller text").isEnabled)
        // The 3 s time out, the size unchanged as far as the Menu knows.
        answers["textSizeBusy"] = false
        screen.refreshDrawerForFocus()
        assertTrue(key(screen, "Smaller text").isEnabled)
        key(screen, "Smaller text").performClick()
        assertEquals(listOf(-1, -1), steps)
    }
}
