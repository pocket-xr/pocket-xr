package com.hayeco.flipspaces

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.DisplayInsets
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.spatial.LaunchSource
import com.hayeco.flipspaces.platform.DesktopWindow
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.platform.WindowingProbe
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.GlassesHomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The flat mode home surface on the glasses (since 0.4.0 the Space is the glasses' home whenever
 * Shizuku is ready; this opens only in flat mode, with a banner saying why). It sits behind the
 * app windows and shows the slot map plus an app dock; a dock tap still becomes a panel if the
 * Space has become possible meanwhile. It is also the caller for app launches onto the glasses: an activity already on
 * that display is the most reliable thing to start other activities there from, and under DeX it
 * is a DeX window like any other.
 */
class GlassesHomeActivity : ComponentActivity() {
    private lateinit var monitor: DisplayMonitor
    private var status by mutableStateOf<String?>(null)

    /** Bumped on every resize or maximize so the display summary is read again. */
    private var configVersion by mutableIntStateOf(0)

    /** The DeX maximize is asked for once per window, so a window the user resized stays put. */
    private var maximizeRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (finishIfOnPhone()) return
        val installedApps = InstalledApps(this)
        monitor = DisplayMonitor(this)

        setContent {
            FlipSpacesTheme {
                val choices by SpacesState.choices.collectAsStateWithLifecycle()
                val banner by SpacesState.banner.collectAsStateWithLifecycle()
                var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
                val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
                val me = remember(displayId, configVersion) { monitor.snapshot().firstOrNull { it.id == displayId } }

                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) {
                        AppCatalog.prepare(installedApps.load(), packageName)
                    }
                }

                GlassesHomeScreen(
                    displaySummary = me?.summary ?: getString(R.string.home_display_number, displayId),
                    choices = choices,
                    apps = apps,
                    status = status,
                    banner = banner,
                    iconLoader = installedApps::icon,
                    onLaunch = { app -> launchHere(app, LaunchSource.GLASSES_DOCK) },
                    onFix = { SpaceRouting.fix(this, it) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (finishIfOnPhone()) return
        val id = display?.displayId ?: return
        current = WeakReference(this)
        SpacesState.homeStarted(id)
        com.hayeco.flipspaces.hands.HandInputManager.autoStart(this, "the glasses home")
        fillDisplay()
        reportInsets(id)
        SpacesState.takePending(id, SystemClock.elapsedRealtime())?.let { pending ->
            val message = launchHere(pending.app, LaunchSource.QUEUED)
            SpacesState.post(message)
        }
    }

    /**
     * The manifest keeps this activity alive across resizes (DeX maximize, a new display mode), so
     * Compose relayouts to the new window size here; the bars and insets are refreshed with it.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (finishIfOnPhone()) return
        configVersion++
        fillDisplay()
        display?.displayId?.let(::reportInsets)
    }

    /** "Show Spaces home" tapped again while the home is open: maximize it again on resume. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maximizeRequested = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fillDisplay()
    }

    /** Full screen on the glasses or DeX display: maximize a freeform window and hide the bars. */
    private fun fillDisplay() {
        DesktopWindow.fill(this, immersive = true, maximize = !maximizeRequested)
        maximizeRequested = true
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            display?.displayId?.let(SpacesState::homeStopped)
        }
        super.onDestroy()
    }

    /** Through SpaceRouting: a DeX window from here in flat mode, a panel otherwise. */
    private fun launchHere(app: AppEntry, source: LaunchSource): String {
        val id = display?.displayId ?: Display.DEFAULT_DISPLAY
        val target = monitor.snapshot().firstOrNull { it.id == id }
            ?: return getString(R.string.home_display_went_away).also { status = it }
        val windowing = FreeformSetup.evaluate(WindowingProbe.read(this))
        return SpaceRouting.launch(this, app, target, windowing, source) { d ->
            SpacesLauncher.launchFromHome(this, app, d, windowing)
        }.also { status = it }
    }

    /** System bars on this display (the DeX taskbar), so slots stay clear of them. */
    private fun reportInsets(displayId: Int) {
        runCatching {
            val insets = windowManager.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            SpacesState.reportInsets(displayId, DisplayInsets(insets.top, insets.bottom))
        }
    }

    /** When the glasses are unplugged Android moves this task to the phone; it is useless there. */
    private fun finishIfOnPhone(): Boolean {
        if (display?.displayId == Display.DEFAULT_DISPLAY) {
            finish()
            return true
        }
        return false
    }

    companion object {
        private var current: WeakReference<GlassesHomeActivity>? = null

        /** The home activity if it is alive on [displayId]. */
        fun runningOn(displayId: Int): GlassesHomeActivity? =
            current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.display?.displayId == displayId }
    }
}
