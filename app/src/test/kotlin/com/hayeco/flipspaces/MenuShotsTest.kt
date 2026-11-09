package com.hayeco.flipspaces

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.Look
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy

/**
 * 1.2.6: pictures of the trackpad's Menu (docs/design/menu-reorganization.md), drawn by Robolectric's native graphics
 * with a host for a typical session: the Space open with three windows, one focused, glasses known. Written to
 * app/build/outputs/menu-shots, then copied to docs/design by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuShotsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun host(answers: Map<String, Any?>): PadHost =
        Proxy.newProxyInstance(PadHost::class.java.classLoader, arrayOf(PadHost::class.java)) { proxy, m, args ->
            val t = m.returnType
            when {
                m.name == "equals" -> proxy === args?.get(0)
                m.name == "hashCode" -> System.identityHashCode(proxy)
                m.name == "toString" -> "shots host"
                m.name in answers -> answers[m.name]
                t == java.lang.Boolean.TYPE -> false
                t == Integer.TYPE -> 0
                t == java.lang.Float.TYPE -> 0f
                t == java.lang.Long.TYPE -> 0L
                t == java.lang.Double.TYPE -> 0.0
                t == List::class.java -> emptyList<Any>()
                t == String::class.java -> ""
                t == Pair::class.java -> null
                t.isEnum -> t.enumConstants!![0]
                else -> null
            }
        } as PadHost

    private val session = mapOf<String, Any?>(
        "spaceActive" to true,
        "windowCount" to 3,
        "focusedTextSize" to Pair("Chrome", 100),
        "focusedSideBySide" to Pair("Chrome", false),
        "glassesCheckAvailable" to true,
        "steadyOffered" to true,
        "cursorFollowsHeadOn" to true,
        "keepsScreenOnOn" to true,
        "snapWindowsOn" to true,
        "currentFollow" to com.hayeco.flipspaces.core.spatial.FollowMode.BODY_ANCHOR,
    )

    private fun shoot(name: String, answers: Map<String, Any?>) {
        val screen = PadScreen(app, host(answers)).apply { openDrawer() }
        val body = PadScreen::class.java.getDeclaredField("drawerBody").apply { isAccessible = true }.get(screen) as ViewGroup
        (body.parent as? ViewGroup)?.removeView(body)
        val w = (412 * app.resources.displayMetrics.density).toInt()
        body.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
        val bmp = Bitmap.createBitmap(body.measuredWidth, body.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Look.PAGE)
        body.draw(canvas)
        val dir = File("build/outputs/menu-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun drawTheMenu() {
        shoot("menu-session", session)
        shoot("menu-no-space", emptyMap())
        // Every section opened, to show what is inside.
        val prefs = app.getSharedPreferences("trackpad", android.content.Context.MODE_PRIVATE).edit()
        for (s in com.hayeco.flipspaces.core.pointer.TrackpadMenu.Section.entries) prefs.putBoolean(s.key, true)
        prefs.commit()
        shoot("menu-session-all-open", session)
    }
}
