package com.hayeco.flipspaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.spatial.SpatialModeChooser
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.SpaceCommand
import com.hayeco.flipspaces.spatial.SpaceSession
import com.hayeco.flipspaces.spatial.SpaceStore
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.SpaceSettings
import com.hayeco.flipspaces.ui.SpatialScreen

/**
 * Phone side of M3-spatial: which mode is possible and why, the one-time Shizuku setup, opening
 * the Space on the glasses, adding app panels, the Space's launcher, and the Space's settings.
 * Without Shizuku, apps open in the existing flat DeX mode instead (SpaceRouting decides).
 */
class SpatialActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val monitor = DisplayMonitor(this)
        val store = SpaceStore(this)
        setContent {
            FlipSpacesTheme {
                val displays by monitor.displays().collectAsStateWithLifecycle(monitor.snapshot())
                val choices by SpacesState.choices.collectAsStateWithLifecycle()
                val head by HeadTracker.status.collectAsStateWithLifecycle()
                val space by SpaceSession.state.collectAsStateWithLifecycle()
                val host by PanelHost.state.collectAsStateWithLifecycle()
                fun saved() = SpaceSettings(store.screen(false).sizeFactor, store.screen(true).sizeFactor, store.handsSteer, store.stereo, store.ipdMm, store.plainDisplay, store.windowGlowPercent, store.windowGlowColor)
                var settings by remember { mutableStateOf(saved()) }
                val shizuku = remember(resumes, host) { ShizukuBridge.status(this) }
                val glasses = DisplaySelector.state(displays, choices.preferredDisplayId) as? GlassesState.Connected
                val choice = SpatialModeChooser.choose(head, shizuku)

                SpatialScreen(
                    choice = choice,
                    shizuku = shizuku,
                    host = host,
                    space = space,
                    glassesName = glasses?.display?.name,
                    settings = settings,
                    message = message,
                    onMessageShown = { message = null },
                    onOpenSpace = {
                        message = glasses?.let { g -> SpaceRouting.openOnGlasses(this, g.display, "Spatial windows button") }
                            ?: if (glasses == null) getString(R.string.home_plug_in_first) else null
                    },
                    onToggleLauncher = { SpaceSession.send(SpaceCommand.ToggleLauncher) },
                    onRemove = { SpaceSession.send(SpaceCommand.Remove(it)) },
                    // 0.5.16: one way only. A running Space recentres the
                    // tracker itself (and centres its pointer); 0.5.15 also called the tracker here,
                    // so each press recentred twice, 12 ms apart.
                    onRecentre = {
                        when (com.hayeco.flipspaces.core.spatial.RecentreRoute.paths(space.running).single()) {
                            com.hayeco.flipspaces.core.spatial.RecentreRoute.Path.SPACE_COMMAND -> SpaceSession.send(SpaceCommand.Recentre)
                            com.hayeco.flipspaces.core.spatial.RecentreRoute.Path.TRACKER -> HeadTracker.recentre("the phone's Recentre button")
                        }
                    },
                    onClear = { SpaceSession.send(SpaceCommand.ClearLayout) },
                    onSettings = {
                        // 0.5.26: the glasses' screen sizes, saved only when changed here (else the measured ones stand).
                        if (it.screen2d != settings.screen2d) store.setScreen(false, it.screen2d, "set on the phone")
                        if (it.screen3d != settings.screen3d) store.setScreen(true, it.screen3d, "set on the phone")
                        settings = it
                        store.handsSteer = it.handsSteer
                        store.stereo = it.stereo
                        store.ipdMm = it.ipdMm
                        store.plainDisplay = it.plainDisplay
                        // 1.2.2: Window glow, applied to a running Space at once (SettingsChanged).
                        store.windowGlowPercent = it.windowGlow
                        store.windowGlowColor = it.windowGlowColor
                        // 0.5.9: the field of view and stereo change the hands' projection too.
                        com.hayeco.flipspaces.hands.HandFeed.resize()
                        SpaceSession.send(SpaceCommand.SettingsChanged)
                    },
                    // 0.5.101: Reset to Pocket XR defaults, applied to a running Space at once.
                    onReset = {
                        store.resetSpatialSettings()
                        settings = saved()
                        com.hayeco.flipspaces.hands.HandFeed.resize()
                        SpaceSession.send(SpaceCommand.SettingsChanged)
                        message = getString(R.string.home_spatial_reset_done)
                    },
                    onAllowShizuku = {
                        ShizukuBridge.requestPermission()
                        resumes++
                    },
                    onGetShizuku = { SpaceRouting.openShizukuPage(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GlassesAutoOpen.resumed(this)
        resumes++
    }

    override fun onPause() {
        GlassesAutoOpen.paused(this)
        super.onPause()
    }
}
