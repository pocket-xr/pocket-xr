package com.hayeco.flipspaces

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.FreeformStatus
import com.hayeco.flipspaces.core.spatial.AppRoute
import com.hayeco.flipspaces.core.spatial.FlatBanner
import com.hayeco.flipspaces.core.spatial.FlatFix
import com.hayeco.flipspaces.core.spatial.GlassesSurface
import com.hayeco.flipspaces.core.spatial.LaunchSource
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.SpaceRouter
import com.hayeco.flipspaces.core.spatial.SpatialModeChooser
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.ModeLog
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceCommand
import com.hayeco.flipspaces.spatial.SpaceSession

/**
 * 0.4.0's single entry to the glasses (core.SpaceRouter decides, this carries it out): when
 * the glasses come up the Space opens full screen unless the mode is flat, and every app launch
 * from the phone list, the glasses dock, the Spatial windows screen or recents becomes a panel
 * whenever the Space is possible. DeX windows only in flat mode, where the flat home shows a
 * banner with the reason and a one-tap fix. Every decision is one FlipSpaces/Mode log line.
 */
object SpaceRouting {
    fun choice(context: Context): ModeChoice =
        SpatialModeChooser.choose(HeadTracker.status.value, ShizukuBridge.status(context))

    private fun spaceRunning(displayId: Int): Boolean =
        SpaceActivity.runningOn(displayId) != null || SpaceSession.state.value.running

    /**
     * Puts Pocket XR on the glasses: the Space, or in flat mode the DeX home with the banner.
     * [where] names the caller for the log. Returns null on success, else the message to show.
     */
    fun openOnGlasses(caller: Activity, display: DisplayCandidate, where: String): String? {
        val shizuku = ShizukuBridge.status(caller)
        val choice = SpatialModeChooser.choose(HeadTracker.status.value, shizuku)
        val surface = SpaceRouter.surfaceFor(choice)
        SpacesState.setBanner(SpaceRouter.bannerFor(choice, shizuku))
        ModeLog.decided("$where, display ${display.id}", choice, if (surface == GlassesSurface.SPACE) "Space full screen" else "flat DeX home with banner")
        return when (surface) {
            GlassesSurface.SPACE -> AppLauncher.openSpace(caller, display.id)?.let { failure ->
                // The Space would not start: the flat home still gives Mike his apps.
                ModeLog.decided("$where, display ${display.id}", choice, "Space refused (${failure.message}); flat DeX home instead")
                SpacesState.setBanner(FlatBanner(caller.getString(R.string.home_flat_space_refused), failure.message, null))
                SpacesLauncher.openHome(caller, display)
                    ?: caller.getString(R.string.home_space_refused_flat_home, failure.message)
            }
            GlassesSurface.FLAT_HOME -> SpacesLauncher.openHome(caller, display)
        }
    }

    /**
     * One app launch from anywhere. Returns the message for the phone snackbar or the dock
     * status. [flat] opens the DeX window in flat mode (the glasses dock launches from itself).
     */
    fun launch(
        caller: Activity,
        app: AppEntry,
        display: DisplayCandidate?,
        windowing: FreeformStatus,
        source: LaunchSource,
        flat: (DisplayCandidate) -> String = { SpacesLauncher.launchFromPhone(caller, app, it, windowing) },
    ): String {
        val shizuku = ShizukuBridge.status(caller)
        val choice = SpatialModeChooser.choose(HeadTracker.status.value, shizuku)
        val route = SpaceRouter.route(choice, display != null, display?.let { spaceRunning(it.id) } == true)
        ModeLog.decided("launch ${app.label} from ${source.name}", choice, route.name)
        SpacesState.setBanner(SpaceRouter.bannerFor(choice, shizuku))
        return when (route) {
            AppRoute.NO_GLASSES -> caller.getString(R.string.home_plug_in_for_app, app.label)
            AppRoute.DEX_WINDOW -> flat(display!!)
            AppRoute.PANEL -> {
                SpaceSession.send(SpaceCommand.AddApp(app))
                // Bring the Space forward if a DeX window or the flat home is over it.
                if (!SpaceSession.active) AppLauncher.openSpace(caller, display!!.id)
                caller.getString(R.string.home_adding_panel, app.label)
            }
            AppRoute.OPEN_SPACE_THEN_PANEL -> {
                // Queued until the Space's first frame, so it is the new Space's first panel.
                SpaceSession.send(SpaceCommand.AddApp(app))
                AppLauncher.openSpace(caller, display!!.id)?.let { caller.getString(R.string.home_space_refused, it.message) }
                    ?: caller.getString(R.string.home_opening_space_with_panel, app.label)
            }
        }
    }

    /** The banner's one-tap fix. Shizuku and the Play Store open on the phone, where Mike starts Shizuku. */
    fun fix(caller: Activity, fix: FlatFix) {
        when (fix) {
            FlatFix.OPEN_SHIZUKU -> {
                val intent = caller.packageManager.getLaunchIntentForPackage(ShizukuBridge.PACKAGE)
                if (intent == null) openShizukuPage(caller) else startOnPhone(caller, intent)
            }
            FlatFix.ALLOW_SHIZUKU -> ShizukuBridge.requestPermission()
            FlatFix.GET_SHIZUKU -> openShizukuPage(caller)
        }
    }

    fun openShizukuPage(caller: Activity) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.PACKAGE}"))
        if (!startOnPhone(caller, market)) {
            startOnPhone(caller, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuBridge.PACKAGE}")))
        }
    }

    private fun startOnPhone(caller: Activity, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        caller.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        runCatching { caller.startActivity(intent) }.isSuccess
    }
}
