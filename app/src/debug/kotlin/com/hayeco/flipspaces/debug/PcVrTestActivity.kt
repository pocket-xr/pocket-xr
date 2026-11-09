package com.hayeco.flipspaces.debug

import android.app.Activity
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.hayeco.flipspaces.openxr.OpenXrPads
import com.hayeco.flipspaces.pcvr.AlvrBridge
import com.hayeco.flipspaces.pcvr.PcVrPads
import com.hayeco.flipspaces.pcvr.PcVrRenderer
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.HeadTracker

/**
 * The PC VR proof (docs/PCVR-PLAN.md, steps 2 and 3): Pocket XR's own ALVR client, its two eye pictures side by side on
 * this activity's display. Started on the glasses (`am start --display <id>`) it is the stream in 3D, turned to the
 * glasses' head every refresh. Debug only.
 *
 * Each new decoded frame is copied once into a texture of Pocket XR's own: ALVR hands the decoder's buffer back as soon
 * as it is asked for the next frame, so a frame shown again with no new one would read a buffer already reused. Then
 * every refresh draws that copy turned from the head the PC drew it for to the head now (a rotational late warp), so
 * turning the head shows at once, not a stream delay later.
 *
 * `adb shell am start -n <package>/com.hayeco.flipspaces.debug.PcVrTestActivity`
 */
class PcVrTestActivity : Activity() {
    private lateinit var view: GLSurfaceView
    private var multicast: WifiManager.MulticastLock? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!AlvrBridge.available) {
            Log.w(TAG, "the ALVR library is not in this build")
            finish()
            return
        }
        // ALVR finds the PC streamer by mDNS, which needs multicast on Wi-Fi.
        multicast = getSystemService(WifiManager::class.java)?.createMulticastLock("pocketxr-pcvr")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        HeadTracker.acquire(this)
        // Joy-Cons: the same pose, arm and buttons as for games, sent to this ALVR client instead of Monado.
        OpenXrPads.start(this)
        OpenXrPads.sink = OpenXrPads.Sink { hand, sampleNs, p, q, w, v, buttons, trigger, squeeze, sx, sy ->
            pads.store(hand, sampleNs, p, q, w, v)
            if (pads.streaming) AlvrBridge.sendButtons(hand, buttons, trigger, squeeze, sx, sy)
        }
        // The Joy-Con reader lives in Pocket XR's panel service, which the Space normally connects.
        PanelHost.onReady += joyConsOn
        PanelHost.connect(this)
        PanelHost.joyCons(true)
        val (vw, vh) = AlvrBridge.viewSize(PcVrRenderer.MARGIN)
        AlvrBridge.init(this, vw, vh, 60f)
        view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(PcVrRenderer(this@PcVrTestActivity, pads))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        showOnGlasses(0)
    }

    /**
     * On the glasses, a Presentation: the desktop mode there makes an activity a floating window, a Presentation always
     * fills its display (the Space does the same). The glasses blink off for a moment when another app lets them go, so
     * a display that is listed but not ready yet is tried again for a few seconds instead of closing the stream.
     */
    private fun showOnGlasses(attempt: Int) {
        if (isFinishing || isDestroyed) return
        val glasses = getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.mode.physicalWidth >= 3 * it.mode.physicalHeight }
        if (glasses == null) {
            if (presentation == null && attempt == 0) setContentView(view)
            return
        }
        val shown = runCatching {
            Presentation(this, glasses).apply {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setContentView(view)
                show()
            }
        }
        shown.onSuccess {
            presentation = it
            Log.i(TAG, "PC VR proof on the glasses: display ${glasses.displayId}, ${glasses.mode.physicalWidth}x${glasses.mode.physicalHeight}")
            setContentView(android.widget.TextView(this).apply { text = "PC VR proof: on the glasses" })
        }.onFailure {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            if (attempt >= GLASSES_TRIES) {
                Log.w(TAG, "the glasses' display never became ready: ${it.message}")
                setContentView(view)
                return
            }
            Log.i(TAG, "the glasses' display is not ready yet, trying again")
            window.decorView.postDelayed({ showOnGlasses(attempt + 1) }, GLASSES_RETRY_MS)
        }
    }

    private var presentation: Presentation? = null

    private val pads = PcVrPads()
    private val joyConsOn: () -> Unit = { PanelHost.joyCons(true) }

    override fun onDestroy() {
        if (AlvrBridge.available) {
            PanelHost.onReady -= joyConsOn
            PanelHost.joyCons(false)
            OpenXrPads.sink = null
            OpenXrPads.stop()
        }
        presentation?.dismiss()
        if (AlvrBridge.available) AlvrBridge.destroy()
        multicast?.release()
        if (AlvrBridge.available) HeadTracker.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PocketXrAlvr"
        const val GLASSES_TRIES = 10
        const val GLASSES_RETRY_MS = 1_000L
    }
}
