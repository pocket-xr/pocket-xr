package com.hayeco.flipspaces.platform

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.GlassesHomeActivity
import com.hayeco.flipspaces.core.DesktopFullscreen
import com.hayeco.flipspaces.core.LaunchFailure
import com.hayeco.flipspaces.core.LaunchFlag
import com.hayeco.flipspaces.core.LaunchSpec
import com.hayeco.flipspaces.core.ShellLaunch
import com.hayeco.flipspaces.core.SpaceStart
import com.hayeco.flipspaces.core.SpaceStartStep
import com.hayeco.flipspaces.spatial.SpaceActivity

/**
 * Maps a [LaunchSpec] onto Intent flags and ActivityOptions and starts the activity from
 * [caller]. Returns null on success, or the failure with the platform's own error text.
 */
object AppLauncher {

    fun launch(caller: Context, spec: LaunchSpec): LaunchFailure? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(spec.app.packageName, spec.app.activityName))
        spec.flags.forEach { intent.addFlags(it.toIntentFlag()) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(spec.displayId)
        spec.bounds?.let {
            options.setLaunchBounds(Rect(it.left, it.top, it.right, it.bottom))
            requestFreeform(options)
        }
        return start(caller, intent, options)
    }

    /**
     * Opens the Pocket XR home surface full screen on [displayId]. DeX would otherwise give it
     * a freeform window, so ask for fullscreen windowing with no launch bounds.
     */
    fun openGlassesHome(context: Context, displayId: Int): LaunchFailure? =
        openFullscreen(context, Intent(context, GlassesHomeActivity::class.java), displayId)

    /**
     * 1.6.16: opens [app] full screen on the glasses' own display [displayId], for an app that protects its video
     * ([com.hayeco.flipspaces.core.spatial.ProtectedApps]): the only place its picture shows.
     */
    fun openAppFullscreen(context: Context, app: AppEntry, displayId: Int): LaunchFailure? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
        return openFullscreen(context, intent, displayId)
    }

    /**
     * Opens [app] as a centred DeX window on [displayId], 70% of the display. For an app that
     * shows black on a panel because it protects its screen (0.4.1): DeX windows on the
     * glasses' own display are composited like any window, so secure content shows there.
     */
    fun openDexWindow(caller: Context, app: AppEntry, displayId: Int): LaunchFailure? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        displayBounds(caller, displayId)?.let { d ->
            val w = d.width * 7 / 10
            val h = d.height * 7 / 10
            val left = (d.width - w) / 2
            val top = (d.height - h) / 2
            options.setLaunchBounds(Rect(left, top, left + w, top + h))
            requestFreeform(options)
        }
        return start(caller, intent, options)
    }

    /**
     * Opens the Space full screen on [displayId], the same way as the home surface. 0.5.2: a
     * Space already up there is brought to the front instead ([SpaceStart]): a second start
     * moved its held task back into the DeX Desk in Mike's 0.5.1 log.
     */
    fun openSpace(context: Context, displayId: Int): LaunchFailure? {
        val running = SpaceActivity.runningOn(displayId)
        if (SpaceStart.decide(running != null) == SpaceStartStep.BRING_FORWARD) {
            running!!.runOnUiThread { running.bringForward("asked to open again") }
            return null
        }
        return openFullscreen(context, Intent(context, SpaceActivity::class.java), displayId)
    }

    /**
     * Tries [DesktopFullscreen.fullscreenAttempts] in order. An attempt whose hidden windowing
     * mode Android will not take is skipped (Android 16 denies it, see the hiddenapi lines in
     * Mike's 0.3.5 log), so a refusal falls back to launch bounds covering the whole display,
     * then the display id alone. Logs which path started the activity.
     */
    private fun openFullscreen(context: Context, intent: Intent, displayId: Int): LaunchFailure? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val what = intent.component?.shortClassName ?: "activity"
        var failure: LaunchFailure? = null
        val skipped = mutableListOf<String>()
        for (request in DesktopFullscreen.fullscreenAttempts(displayId, displayBounds(context, displayId))) {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(request.displayId)
            options.setLaunchBounds(request.bounds?.let { Rect(it.left, it.top, it.right, it.bottom) })
            val mode = request.windowingMode
            if (mode != null && !setWindowingMode(options, mode)) {
                skipped += "${request.label} (denied)"
                continue
            }
            failure = start(context, intent, options)
            if (failure == null) {
                Log.i(TAG, "Full screen start of $what on display $displayId: ${request.label}${if (skipped.isEmpty()) "" else "; skipped ${skipped.joinToString()}"}")
                return null
            }
            skipped += "${request.label} (${failure.message})"
        }
        Log.w(TAG, "Full screen start of $what on display $displayId failed: ${skipped.joinToString()}")
        return failure
    }

    /** The whole display as launch bounds, or null when the display is gone. */
    @Suppress("DEPRECATION")
    private fun displayBounds(context: Context, displayId: Int): Bounds? {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        return Bounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun start(context: Context, intent: Intent, options: ActivityOptions): LaunchFailure? = try {
        context.startActivity(intent, options.toBundle())
        null
    } catch (e: Exception) {
        // SecurityException "Permission Denial ... launchDisplayId=N" when the display hosts no
        // tasks (mirror mode); ActivityNotFoundException; IllegalArgumentException for a display
        // that went away. The message is kept word for word for the UI.
        LaunchFailure.classify(e.javaClass.simpleName, e.message)
    }

    /**
     * ActivityOptions.setLaunchWindowingMode is hidden. Asking for freeform helps on desktop
     * displays that would otherwise go full screen; DeX windows are freeform anyway, so a refusal
     * here changes nothing.
     */
    private fun requestFreeform(options: ActivityOptions) {
        setWindowingMode(options, ShellLaunch.WINDOWING_MODE_FREEFORM)
    }

    /** Set once Android has refused the hidden method, so it is not asked (and logged) again. */
    @Volatile
    private var windowingModeDenied = false

    /**
     * ActivityOptions.setLaunchWindowingMode by reflection. True when the call went through;
     * false, and never tried again in this process, when the hidden API policy denies it.
     */
    private fun setWindowingMode(options: ActivityOptions, mode: Int): Boolean {
        if (windowingModeDenied) return false
        return runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, mode)
        }.onFailure {
            windowingModeDenied = true
            Log.i(TAG, "ActivityOptions.setLaunchWindowingMode is not available to apps here (${it.javaClass.simpleName}); using launch bounds and requestFullscreenMode")
        }.isSuccess
    }

    private const val TAG = "FlipSpaces/Launch"

    private fun LaunchFlag.toIntentFlag(): Int = when (this) {
        LaunchFlag.NEW_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK
        LaunchFlag.MULTIPLE_TASK -> Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    }
}
