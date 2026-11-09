package com.hayeco.flipspaces.pcvr

import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.hayeco.flipspaces.openxr.OpenXrPads
import com.hayeco.flipspaces.spatial.JoyConReader
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceSession

/**
 * PC VR from the Space's launcher tile: the stream over the Space, in a sub-window of the Space's own window, the way an
 * OpenXR game takes the glasses ([com.hayeco.flipspaces.openxr.OpenXrGlassesLayer]). The Space pauses underneath with
 * its windows where they were, and comes back when PC VR closes: hold Home or Capture on a Joy-Con.
 *
 * Main thread only.
 */
object PcVrSession {
    private const val TAG = "PocketXrAlvr"
    /**
     * Holding a Joy-Con's Home or Capture this long goes back to the Space. A press still recentres at once, so the hold
     * is long and shown: after [HOLD_SHOWN_MS] the game dims into the loading room with a ring filling up.
     */
    private const val HOLD_TO_LEAVE_MS = 2_000L
    private const val HOLD_SHOWN_MS = 300L
    /** The phone's screen goes dark this long after PC VR opens or after the last touch on it. */
    private const val PHONE_DARK_AFTER_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private var layer: View? = null
    private var gl: GLSurfaceView? = null
    private var layerManager: WindowManager? = null
    private var pausedSpace: SpaceActivity? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private val heldSinceMs = LongArray(2)

    val isOpen: Boolean get() = layer != null

    /** 0 to 1 while Home or Capture is held to leave, for the glasses to show; 0 otherwise. */
    @Volatile var leaveProgress = 0f
        private set

    /** Told on the main thread when PC VR opens or closes: the phone's trackpad shows its way back. */
    val changed = mutableListOf<(Boolean) -> Unit>()

    /** A touch on the dark phone: its screen on again for a while. */
    fun wakePhone() {
        if (wake == null) return
        main.removeCallbacks(panelOff)
        PanelHost.phonePanel(true)
        main.postDelayed(panelOff, PHONE_DARK_AFTER_MS)
    }

    /** The launcher's tile: an app entry for the manifest's PC VR alias, which only gives it a name and an icon. */
    fun tile(packageName: String) = com.hayeco.flipspaces.core.AppEntry("PC VR", packageName, TILE_ACTIVITY)

    fun isTile(app: com.hayeco.flipspaces.core.AppEntry) = app.activityName == TILE_ACTIVITY

    private const val TILE_ACTIVITY = "com.hayeco.flipspaces.pcvr.PcVrTile"

    /** The launcher's PC VR tile. */
    fun open(why: String) {
        main.post {
            if (layer != null || !AlvrBridge.available) {
                if (!AlvrBridge.available) Log.w(TAG, "PC VR: the ALVR library is not in this build")
                return@post
            }
            val space = SpaceActivity.forGame()
            val host = space?.gameHostWindow()
            val token = host?.decorView?.windowToken
            if (space == null || host == null || token == null) {
                Log.w(TAG, "PC VR: no Space on the glasses to open over")
                return@post
            }
            // ALVR finds the PC streamer by mDNS, which needs multicast on Wi-Fi.
            multicast = space.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("pocketxr-pcvr")?.apply { setReferenceCounted(false); acquire() }
            val (vw, vh) = AlvrBridge.viewSize(PcVrRenderer.MARGIN)
            // ALVR asks this activity for the microphone permission at start, if it isn't granted yet.
            AlvrBridge.init(space, vw, vh, 60f)

            val pads = PcVrPads()
            heldSinceMs.fill(0L)
            OpenXrPads.start(space)
            OpenXrPads.sink = OpenXrPads.Sink { hand, sampleNs, p, q, w, v, buttons, trigger, squeeze, sx, sy ->
                pads.store(hand, sampleNs, p, q, w, v)
                if (pads.streaming) AlvrBridge.sendButtons(hand, buttons, trigger, squeeze, sx, sy)
                watchLeave(hand, buttons)
            }
            PanelHost.joyCons(true)

            val view = GLSurfaceView(host.context).apply {
                setEGLContextClientVersion(3)
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.OPAQUE)
                setRenderer(PcVrRenderer(space, pads))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            val root = FrameLayout(host.context).apply {
                addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                title = "Pocket XR PC VR"
            }
            val manager = host.context.getSystemService(WindowManager::class.java)
            val shown = runCatching { manager.addView(root, params) }
            if (shown.isFailure) {
                Log.w(TAG, "PC VR: no window over the Space: ${shown.exceptionOrNull()?.message}")
                teardown()
                return@post
            }
            layer = root
            gl = view
            layerManager = manager
            pausedSpace = space
            SpaceSession.gameOwnsGlasses = true
            space.pauseForGame(true)
            keepAwake(space, true)
            leaveProgress = 0f
            changed.toList().forEach { it(true) }
            Log.i(TAG, "PC VR open over the Space ($why)")
        }
    }

    /** Back to the Space, its windows where they were. */
    fun close(why: String) {
        main.post {
            if (layer == null) return@post
            gl?.onPause()
            layer?.let { v -> runCatching { layerManager?.removeView(v) } }
            layer = null
            gl = null
            layerManager = null
            teardown()
            pausedSpace?.pauseForGame(false)
            pausedSpace = null
            SpaceSession.gameOwnsGlasses = false
            leaveProgress = 0f
            changed.toList().forEach { it(false) }
            Log.i(TAG, "PC VR closed, the Space is back: $why")
        }
    }

    private fun teardown() {
        OpenXrPads.sink = null
        PanelHost.joyCons(false)
        OpenXrPads.stop()
        keepAwake(null, false)
        AlvrBridge.destroy()
        multicast?.let { if (it.isHeld) it.release() }
        multicast = null
    }

    /** Joy-Con thread: Home or Capture held for [HOLD_TO_LEAVE_MS] leaves PC VR. */
    private fun watchLeave(hand: Int, buttons: Int) {
        if (hand !in 0..1) return
        val now = SystemClock.uptimeMillis()
        if (buttons and JoyConReader.RECENTRE == 0) heldSinceMs[hand] = 0L
        else if (heldSinceMs[hand] == 0L) heldSinceMs[hand] = now
        // A hold that already left counts for nothing until the button is let go.
        val since = heldSinceMs.filter { it > 0L }.minOrNull()
        val held = if (since == null) 0L else now - since
        leaveProgress = ((held - HOLD_SHOWN_MS).toFloat() / (HOLD_TO_LEAVE_MS - HOLD_SHOWN_MS)).coerceIn(0f, 1f)
        if (held >= HOLD_TO_LEAVE_MS) {
            for (h in 0..1) if (heldSinceMs[h] > 0L) heldSinceMs[h] = -1L
            close("Home or Capture held on a Joy-Con")
        }
    }

    /** As for games: the phone stays awake with its panel off, so locking never takes the glasses' display away. */
    @Suppress("DEPRECATION")
    private fun keepAwake(space: SpaceActivity?, on: Boolean) {
        if (on && space != null) {
            val power = space.applicationContext.getSystemService(PowerManager::class.java)
            wake = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "PocketXR:pcvr").apply { acquire() }
            main.postDelayed(panelOff, PHONE_DARK_AFTER_MS)
        } else {
            main.removeCallbacks(panelOff)
            wake?.let { if (it.isHeld) it.release() }
            if (wake != null) PanelHost.phonePanel(true)
            wake = null
        }
    }

    private val panelOff = Runnable { PanelHost.phonePanel(false) }
}
