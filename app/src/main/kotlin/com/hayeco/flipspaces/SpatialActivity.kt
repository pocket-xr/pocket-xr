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
import com.hayeco.flipspaces.spatial.WallpaperLoader
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** 1.6.37: your wallpaper picture's preview and whether it is a panorama; null while none is saved. */
    private var wallpaperPicture by mutableStateOf<Pair<android.graphics.Bitmap, Boolean>?>(null)

    /** The saved wallpaper picture, small (about 512 pixels wide), for its tile. */
    private fun pictureThumb(): Pair<android.graphics.Bitmap, Boolean>? {
        val f = WallpaperLoader.pictureFile(this)
        if (!f.exists()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp to com.hayeco.flipspaces.core.spatial.Wallpaper.isPanorama(bounds.outWidth, bounds.outHeight)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val monitor = DisplayMonitor(this)
        val store = SpaceStore(this)
        lifecycleScope.launch { wallpaperPicture = withContext(Dispatchers.IO) { pictureThumb() } }
        setContent {
            FlipSpacesTheme {
                val displays by monitor.displays().collectAsStateWithLifecycle(monitor.snapshot())
                val choices by SpacesState.choices.collectAsStateWithLifecycle()
                val head by HeadTracker.status.collectAsStateWithLifecycle()
                val space by SpaceSession.state.collectAsStateWithLifecycle()
                val host by PanelHost.state.collectAsStateWithLifecycle()
                fun saved() = SpaceSettings(store.screen(false).sizeFactor, store.screen(true).sizeFactor, store.handsSteer, store.stereo, store.ipdMm, store.plainDisplay, store.windowGlowPercent, store.windowGlowColor, store.readability, store.wallpaper)
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
                        // Readability: every window's density, applied to a running Space at once (SettingsChanged).
                        store.readability = it.readability
                        // 1.6.37: the wallpaper, made again by a running Space (SettingsChanged).
                        store.wallpaper = it.wallpaper
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
                    wallpaperPicture = wallpaperPicture?.first,
                    wallpaperPanorama = wallpaperPicture?.second == true,
                    onWallpaperPicture = { uri ->
                        // 1.6.37: copied into the app's files off the main thread, then chosen and shown at once.
                        lifecycleScope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { WallpaperLoader.savePicture(this@SpatialActivity, uri) } }
                            saved.onSuccess { (w, h) ->
                                wallpaperPicture = withContext(Dispatchers.IO) { pictureThumb() }
                                val pano = com.hayeco.flipspaces.core.spatial.Wallpaper.isPanorama(w, h)
                                val next = settings.copy(wallpaper = settings.wallpaper.copy(scene = com.hayeco.flipspaces.core.spatial.Wallpaper.Scene.PICTURE))
                                settings = next
                                store.wallpaper = next.wallpaper
                                SpaceSession.send(SpaceCommand.SettingsChanged)
                                message = getString(if (pano) R.string.wallpaper_saved_panorama else R.string.wallpaper_saved, w, h)
                                Log.i("FlipSpaces/Space", "wallpaper picture saved: ${w}x$h${if (pano) ", a 360 panorama" else ""}")
                            }.onFailure { e ->
                                message = getString(R.string.wallpaper_not_saved)
                                Log.w("FlipSpaces/Space", "wallpaper picture not saved: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                    },
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
