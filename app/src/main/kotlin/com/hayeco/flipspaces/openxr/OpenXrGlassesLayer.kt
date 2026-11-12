package com.hayeco.flipspaces.openxr

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.SpaceActivity
import org.freedesktop.monado.ipc.MonadoImpl

/**
 * The game's picture on the glasses: a sub-window of the Space's own window, holding the surface Monado draws to.
 * The Space's GL view and its panels stay exactly as they are, and come back as they were when the game leaves. The
 * Space stops drawing while the game is up.
 *
 * It is not a Presentation: Android refuses one on a display the app's own activity is already on ("Attempted to add
 * presentation window to a non-suitable display"). A sub-window also follows the Space into its own Presentation.
 * It takes no focus or touches, so keys and the pointer still reach the Space.
 */
object OpenXrGlassesLayer {
    private const val TAG = "PocketXrOpenXr"

    private val main = Handler(Looper.getMainLooper())
    private var layer: View? = null
    private var layerManager: WindowManager? = null
    private var pausedSpace: SpaceActivity? = null
    private var wake: PowerManager.WakeLock? = null
    private val panelOff = Runnable { PanelHost.phonePanel(false) }
    private const val PANEL_OFF_AFTER_MS = 5_000L

    /** Called from Monado's binder thread as well as the main thread. */
    fun canShow(): Boolean = SpaceActivity.forGame() != null

    /**
     * The phone locking takes the glasses' display away, so while a game runs the phone stays awake with its panel off:
     * Android never locks, the game keeps running on the phone's display, and the screen is dark.
     */
    @Suppress("DEPRECATION")
    private fun keepAwake(space: SpaceActivity?, on: Boolean) {
        if (on && space != null) {
            // From the app, not the Space: a lock made on the glasses' display holds only their display group, and the
            // phone's group then sleeps on its timeout with the lock still held.
            val power = space.applicationContext.getSystemService(PowerManager::class.java)
            wake = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "PocketXR:game").apply { acquire() }
            main.postDelayed(panelOff, PANEL_OFF_AFTER_MS)
        } else {
            main.removeCallbacks(panelOff)
            wake?.let { if (it.isHeld) it.release() }
            if (wake != null) PanelHost.phonePanel(true)
            wake = null
        }
    }

    fun open(monado: MonadoImpl) {
        main.post {
            if (layer != null) return@post
            val space = SpaceActivity.forGame()
            val host = space?.gameHostWindow()
            val token = host?.decorView?.windowToken
            if (space == null || host == null || token == null) {
                Log.i(TAG, "no Space on the glasses: the game draws on its own screen")
                return@post
            }
            val surface = SurfaceView(host.context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.OPAQUE)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        Log.i(TAG, "the game's surface is ready")
                        monado.passAppSurface(h.surface)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                        Log.i(TAG, "the game's surface is ${width}x$height")
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        Log.i(TAG, "the game's surface is gone")
                    }
                })
            }
            val root = FrameLayout(host.context).apply {
                addView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                this.token = token
                title = "Pocket XR game"
            }
            val manager = host.context.getSystemService(WindowManager::class.java)
            val shown = runCatching { manager.addView(root, params) }
            if (shown.isFailure) {
                Log.w(TAG, "no window for the game: ${shown.exceptionOrNull()?.message}")
                return@post
            }
            layer = root
            layerManager = manager
            pausedSpace = space
            com.hayeco.flipspaces.spatial.SpaceSession.gameOwnsGlasses = true
            space.pauseForGame(true)
            OpenXrHeadFeed.start()
            keepAwake(space, true)
            OpenXrPads.start(host.context)
            PanelHost.joyCons(true)
            Log.i(TAG, "a game owns the glasses")
            // 1.4.3: a game is side by side 3D; Steady mode's Ultrawide shows it as one flat canvas, and leaving it now would
            // re-plug the display and close the game's window. Say so instead.
            if (com.hayeco.flipspaces.spatial.GlassesDisplayControl.steadyActive) {
                com.hayeco.flipspaces.spatial.GlassesNotice.post(FlipSpacesApp.text(R.string.sys_game_needs_3d), 10_000L)
                Log.i(TAG, "a game started in Steady mode: told to turn Steady mode off first")
            }
        }
    }

    fun close(why: String) {
        main.post {
            OpenXrHeadFeed.stop()
            PanelHost.joyCons(false)
            OpenXrPads.stop()
            keepAwake(null, false)
            layer?.let { view -> runCatching { layerManager?.removeView(view) } }
            layer = null
            layerManager = null
            pausedSpace?.pauseForGame(false)
            pausedSpace = null
            com.hayeco.flipspaces.spatial.SpaceSession.gameOwnsGlasses = false
            Log.i(TAG, "the glasses are back with the Space: $why")
        }
    }
}
