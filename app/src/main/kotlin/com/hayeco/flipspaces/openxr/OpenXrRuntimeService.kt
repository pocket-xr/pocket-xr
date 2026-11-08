package com.hayeco.flipspaces.openxr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceRenderer
import com.hayeco.flipspaces.spatial.SpaceStore
import kotlin.math.tan
import org.freedesktop.monado.ipc.MonadoImpl
import org.freedesktop.monado.ipc.PocketFeed

/**
 * Pocket XR as an OpenXR runtime, built on Monado. A game's OpenXR loader finds this service through the Khronos
 * runtime broker, loads libopenxr_monado.so from this app, and binds here with Monado's connect action. The
 * discovery action only marks the app as a runtime, so it gets no binder.
 *
 * Without the Monado libraries in the build (the public repository may not carry them), binding fails quietly and
 * the rest of the app is not touched.
 */
class OpenXrRuntimeService : Service() {
    private var monado: PocketMonado? = null

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != CONNECT_ACTION) return null
        val runtime = monado ?: runCatching {
            configureDisplay()
            PocketMonado(this)
        }.getOrElse {
            Log.w(TAG, "no OpenXR runtime in this build: ${it.message}")
            return null
        }.also { monado = it }
        OpenXrGlassesLayer.open(runtime)
        return runtime
    }

    override fun onRebind(intent: Intent) {
        monado?.let { OpenXrGlassesLayer.open(it) }
    }

    override fun onUnbind(intent: Intent): Boolean {
        OpenXrGlassesLayer.close("the game left")
        return true
    }

    override fun onDestroy() {
        OpenXrGlassesLayer.close("the runtime stopped")
        // Off the main thread: after a lost surface Monado's shutdown can wait a long time, and the Space runs on this
        // thread (a session that lost its surface froze the app until Android called it not responding).
        monado?.let { runtime -> Thread({ runtime.shutdown() }, "openxr-shutdown").apply { isDaemon = true }.start() }
        monado = null
        super.onDestroy()
    }

    /** Tells the driver about the glasses, before Monado starts and makes its device. */
    private fun configureDisplay() {
        val display = SpaceActivity.forGame()?.display ?: return
        val mode = display.mode
        val sideBySide = mode.physicalWidth >= mode.physicalHeight * 3
        val frustum = SpaceStore(this).optics(sideBySide).pictureFrustum(1920, 1080)
        val tanX = tan(Math.toRadians(frustum.horizontalFovDeg / 2.0)).toFloat()
        val tanY = tan(Math.toRadians(frustum.verticalFovDeg / 2.0)).toFloat()
        Log.i(TAG, "the glasses: ${mode.physicalWidth}x${mode.physicalHeight} at ${mode.refreshRate} Hz, field of view tangents $tanX by $tanY")
        PocketFeed.nativeConfigure(mode.physicalWidth, mode.physicalHeight, mode.refreshRate, tanX, tanY)
        // Mike, 7 November 2026, Beat Saber: "everything feels close", and the sabers only sat in his hands pulled
        // 0.4 m back. The glasses angle both eyes in so a picture with no disparity sits at their 3D distance (4 m by
        // default); games drew for parallel eyes, so infinity was seen at 4 m and a block at 2 m at about 1.3 m. Each
        // eye's view centre now turns outward by the same angle the Space corrects ([ScreenDistance]), and games get
        // the wearer's IPD from Pocket XR's setting, not a fixed 63 mm.
        if (sideBySide) {
            val store = SpaceStore(this)
            val ipdMm = store.ipdMm
            val distanceM = SpaceRenderer.screenDistanceM
            val shift = (ipdMm / 2000f) / distanceM
            Log.i(TAG, "the eyes: IPD %.1f mm, the glasses' 3D distance %.2f m, each view turned out by %.2f degrees".format(
                java.util.Locale.ROOT, ipdMm, distanceM, Math.toDegrees(kotlin.math.atan(shift.toDouble()))))
            PocketFeed.nativeConfigureEyes(ipdMm / 1000f, shift)
        } else {
            PocketFeed.nativeConfigureEyes(0f, 0f)
        }
    }

    /**
     * Saying yes here stops the game from offering its own screen, so the picture goes to the surface
     * [OpenXrGlassesLayer] hands over. With no Space on the glasses the game keeps Monado's usual path.
     */
    private class PocketMonado(context: Context) : MonadoImpl(context) {
        override fun canDrawOverOtherApps(): Boolean = OpenXrGlassesLayer.canShow()
    }

    private companion object {
        const val TAG = "PocketXrOpenXr"
        const val CONNECT_ACTION = "org.freedesktop.monado.ipc.CONNECT"
    }
}
