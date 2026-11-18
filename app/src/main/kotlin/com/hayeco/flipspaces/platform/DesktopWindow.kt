package com.hayeco.flipspaces.platform

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.OutcomeReceiver
import com.hayeco.flipspaces.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.DesktopFullscreen

/**
 * Makes an activity fill a DeX or glasses display. DeX opens activities as freeform windows even
 * when launched full screen, so the window is maximized after it appears. The phone display with
 * DeX off is left alone (see core.DesktopFullscreen.plan).
 */
object DesktopWindow {

    /** Samsung's Configuration.semDesktopModeEnabled, read by reflection; false on other builds. */
    fun isDexDesktop(config: Configuration): Boolean {
        val type = config.javaClass
        val current = runCatching { type.getField("semDesktopModeEnabled").getInt(config) }.getOrNull()
        val enabled = runCatching { type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null) }.getOrNull()
        return DesktopFullscreen.isDexDesktop(current, enabled)
    }

    /**
     * Applies the plan for [activity]. [maximize] asks Android 14+ to turn a freeform window full
     * screen; pass false after the first time so a window the user resized stays put.
     * [immersive] also hides the bars. Returns true when a full screen request was sent.
     */
    fun fill(activity: Activity, immersive: Boolean, maximize: Boolean): Boolean {
        val displayId = activity.display?.displayId ?: return false
        val metrics = activity.windowManager
        // 0.5.0: a freeform window is windowed at any size (Mike's 0.4.8 Space was a 1920x1080
        // DeX freeform window with a caption bar and the taskbar, logged as "already full screen").
        val freeform = isFreeform(activity)
        val windowed = DesktopFullscreen.isWindowed(
            metrics.currentWindowMetrics.bounds.toBounds(),
            metrics.maximumWindowMetrics.bounds.toBounds(),
            freeform = freeform,
        )
        val plan = DesktopFullscreen.plan(
            displayId = displayId,
            dexDesktop = isDexDesktop(activity.resources.configuration),
            windowed = windowed,
            sdkInt = Build.VERSION.SDK_INT,
            immersive = immersive,
            freeform = freeform,
        )
        if (plan.hideSystemBars) hideSystemBars(activity, caption = plan.hideCaption)
        val name = activity.javaClass.simpleName
        val window = metrics.currentWindowMetrics.bounds
        val whole = metrics.maximumWindowMetrics.bounds
        val size = "window ${window.width()}x${window.height()} of display ${whole.width()}x${whole.height()}, ${if (freeform) "freeform" else "not freeform"}"
        if (!maximize || !plan.requestFullscreen || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (maximize && displayId != DesktopFullscreen.DEFAULT_DISPLAY) {
                Log.i(TAG, "$name on display $displayId: $size, ${if (windowed) "windowed, requestFullscreenMode needs Android 14" else "already full screen (launch path took effect)"}")
            }
            return false
        }
        return runCatching {
            // Only valid for a freeform window; the result says whether DeX took it.
            activity.requestFullscreenMode(
                Activity.FULLSCREEN_MODE_REQUEST_ENTER,
                object : OutcomeReceiver<Void, Throwable> {
                    override fun onResult(result: Void?) {
                        Log.i(TAG, "$name on display $displayId: requestFullscreenMode took effect ($size before)")
                    }

                    override fun onError(error: Throwable) {
                        Log.w(TAG, "$name on display $displayId: requestFullscreenMode refused: ${error.message} ($size)")
                    }
                },
            )
        }.onFailure { Log.w(TAG, "$name on display $displayId: requestFullscreenMode threw: ${it.message}") }.isSuccess
    }

    /**
     * 0.5.0: true while [activity] is in a freeform window. Configuration's windowing mode is
     * hidden, so its text is read ([DesktopFullscreen.windowingModeIn]); a DeX or desktop
     * freeform window is also in multi window mode, which is the public fallback.
     */
    fun isFreeform(activity: Activity): Boolean {
        val mode = DesktopFullscreen.windowingModeIn(activity.resources.configuration.toString())
        return mode == DesktopFullscreen.WINDOWING_MODE_FREEFORM || (mode == null && activity.isInMultiWindowMode && activity.display?.displayId != DesktopFullscreen.DEFAULT_DISPLAY)
    }

    /**
     * Hides status, navigation and DeX task bars; a swipe from the edge shows them briefly.
     * [caption] (0.5.0, for a window still freeform) also hides its caption bar and asks
     * Android 15 for a transparent caption background, the fallback when DeX keeps the window
     * freeform.
     */
    fun hideSystemBars(activity: Activity, caption: Boolean = false) {
        val window = activity.window
        // Android 15+ draws edge to edge anyway; older versions need to be told.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(if (caption) WindowInsets.Type.systemBars() or WindowInsets.Type.captionBar() else WindowInsets.Type.systemBars())
            if (caption && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                )
            }
        }
    }

    private fun Rect.toBounds() = Bounds(left, top, right, bottom)

    private const val TAG = "FlipSpaces/Window"
}
