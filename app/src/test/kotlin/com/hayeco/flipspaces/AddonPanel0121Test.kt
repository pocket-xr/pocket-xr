package com.hayeco.flipspaces

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.spatial.FollowMode
import com.hayeco.flipspaces.core.spatial.GlassesCheck
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup
import com.hayeco.flipspaces.core.spatial.SetupHost
import com.hayeco.flipspaces.platform.AddonInstaller
import com.hayeco.flipspaces.platform.AddonLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 1.2.1: the add-on step on screen: Install add-on when it is missing, Allow installs first when Pocket XR may not
 * install apps (one tap to Android's setting), the add-on's setup once it is installed, and the step under the glasses
 * check's passed result, which Done still closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddonPanel0121Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pm get() = shadowOf(app.packageManager)

    @Before fun setUp() = AddonInstaller.resetForTest()

    @After fun tearDown() = AddonInstaller.resetForTest()

    private fun installAddon() {
        pm.installPackage(PackageInfo().apply { packageName = AddonLink.PACKAGE })
    }

    @Test fun notInstalledShowsInstall() {
        pm.setCanRequestPackageInstalls(true)
        val p = AddonPanel(app)
        assertEquals(AddonSetup.State.NOT_INSTALLED, p.step.state)
        assertTrue(p.labels().contains(AddonSetup.INSTALL))
    }

    @Test fun withoutInstallPermissionOneTapOpensTheSetting() {
        pm.setCanRequestPackageInstalls(false)
        val p = AddonPanel(app)
        assertEquals(AddonSetup.State.NEEDS_PERMISSION, p.step.state)
        assertTrue(p.labels().contains(AddonSetup.ALLOW))
        val allow = (0 until p.childCount).map { p.getChildAt(it) }.first { (it as? android.widget.TextView)?.text == AddonSetup.ALLOW }
        allow.performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
    }

    @Test fun installedShowsTheAddonsSetup() {
        installAddon()
        val p = AddonPanel(app)
        assertEquals(AddonSetup.State.ACCESS_OFF, p.step.state)
        assertTrue(p.step.done)
        assertTrue(p.labels().contains(AddonSetup.TURN_ON))
        assertFalse(p.labels().contains(AddonSetup.INSTALL))
    }

    private class Host : GlassesCheckHost {
        val finished = ArrayList<GlassesCheck.CheckResult?>()
        override fun headYawDeg(): Float = 0f
        override fun follow() = FollowMode.BODY_ANCHOR
        override fun steady() = false
        override fun checkFinished(result: GlassesCheck.CheckResult?) { finished += result }
    }

    @Test fun thePassedCheckOffersTheAddonAndDoneStillCloses() {
        pm.setCanRequestPackageInstalls(true)
        val h = Host()
        var closed = 0
        val turning = (0 until 400).map { (30.0 * kotlin.math.sin(it / 20.0)).toFloat() }
        var i = 0
        val host = object : GlassesCheckHost by h {
            override fun headYawDeg(): Float = turning[(i++).coerceAtMost(turning.size - 1)]
        }
        val c = GlassesCheckCard(app, GlassesSetup.page(GlassesModel.ONE_PRO, SetupHost.PHONE), null, SetupHost.PHONE, host) { closed++ }
        assertTrue(c.press("Start the check"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GlassesCheck.DURATION_MS + 200))
        assertTrue(c.press("Yes, they stayed put"))
        assertTrue(c.labels().contains(AddonSetup.TITLE))
        assertTrue(c.labels().contains(AddonSetup.WHAT))
        assertTrue(c.press("Done"))
        assertEquals(1, closed)
        assertTrue(h.finished.single()!!.passed)
    }
}
