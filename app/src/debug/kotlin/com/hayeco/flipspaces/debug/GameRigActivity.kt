package com.hayeco.flipspaces.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.openxr.OpenXrHeadFeed
import com.hayeco.flipspaces.platform.AppLauncher
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A wearer and a pair of glasses for testing games with nobody on the glasses. A 3840 x 1080 virtual display stands in for
 * the glasses in 3D, the Space opens on it, and a head that turns by itself feeds Monado. What reaches the display comes back
 * through an ImageReader, so frame timing and picture quality can be read with no eyes on it.
 *
 * `adb shell am start -n <package>/com.hayeco.flipspaces.debug.GameRigActivity --ei width 3840 --ei height 1080 --ef yaw 30
 * --ef hz 0.25`. Then `am broadcast -n <package>/.debug.DebugReceiver -a com.hayeco.flipspaces.debug.RIG --es do snap` saves
 * the latest frame, and `--es do stats` logs the frame times.
 */
class GameRigActivity : Activity() {
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("game-rig").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var text: TextView

    private val frameGapsNs = LongArray(GAPS)
    @Volatile private var frames = 0L
    private var lastFrameNs = 0L
    @Volatile private var snapPending = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        running = this
        text = TextView(this).apply { text = "Game rig starting" }
        setContentView(text)
        val width = intent.getIntExtra("width", 3840)
        val height = intent.getIntExtra("height", 1080)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ read(it) }, handler)
        reader = r
        val manager = getSystemService(DisplayManager::class.java)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        // Named like one of Pocket XR's own window displays, so no Pocket XR takes it for glasses on its own: the real
        // app did, all of 7 November, and opened its Space and every saved window on the rig at each start.
        val d = manager.createVirtualDisplay(com.hayeco.flipspaces.core.spatial.PanelDisplays.RIG_NAME, width, height, 320, r.surface, flags)
        display = d
        val id = d.display.displayId
        Log.i(TAG, "virtual glasses: display $id, ${width}x$height, refresh ${d.display.mode.refreshRate} Hz")
        wearer(intent.getFloatExtra("yaw", 30f), intent.getFloatExtra("hz", 0.25f))
        val failure = AppLauncher.openSpace(this, id)
        Log.i(TAG, if (failure == null) "the Space was asked to open on display $id" else "the Space did not open: ${failure.message}")
        text.text = "Game rig: display $id ${width}x$height"
    }

    /** A command from DebugReceiver, so asking for numbers does not bring this screen over the game. */
    fun command(next: android.content.Intent) {
        when (next.getStringExtra("do")) {
            "snap" -> snapPending = true
            "stats" -> stats()
            "wearer" -> wearer(next.getFloatExtra("yaw", 30f), next.getFloatExtra("hz", 0.25f))
            // The test copy is a separate app to Shizuku and needs its own yes, for the panel service and Joy-Cons.
            "shizuku" -> com.hayeco.flipspaces.platform.ShizukuBridge.requestPermission()
        }
    }

    /** A head turning left and right, [yawDeg] each way, [hz] times a second, as a smooth sine. */
    private fun wearer(yawDeg: Float, hz: Float) {
        val start = System.nanoTime()
        OpenXrHeadFeed.testPose = { now ->
            val t = (now - start) / 1e9
            val half = yawDeg * sin(2.0 * PI * hz * t) * PI / 360.0
            Quat(cos(half).toFloat(), 0f, sin(half).toFloat(), 0f)
        }
        Log.i(TAG, "wearer: $yawDeg degrees each way at $hz Hz")
    }

    private fun read(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val now = image.timestamp
            if (lastFrameNs != 0L) frameGapsNs[(frames % GAPS).toInt()] = now - lastFrameNs
            lastFrameNs = now
            frames++
            if (snapPending) {
                snapPending = false
                val plane = image.planes[0]
                val w = plane.rowStride / plane.pixelStride
                val bitmap = Bitmap.createBitmap(w, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val file = File(getExternalFilesDir(null), "rig-$frames.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                Log.i(TAG, "snap: ${file.absolutePath}")
            }
        } finally {
            image.close()
        }
    }

    private fun stats() {
        val n = minOf(frames - 1, GAPS.toLong()).toInt()
        if (n <= 0) {
            Log.i(TAG, "stats: no frames yet")
            return
        }
        val gaps = frameGapsNs.copyOf(n).sorted()
        fun ms(share: Double) = gaps[((n - 1) * share).toInt()] / 1e6
        val late = gaps.count { it > 25_000_000L }
        Log.i(TAG, "stats: %d frames; gap p50 %.2f p95 %.2f p99 %.2f max %.2f ms; %d of the last %d over 25 ms".format(java.util.Locale.ROOT, frames, ms(0.5), ms(0.95), ms(0.99), ms(1.0), late, n))
    }

    override fun onDestroy() {
        OpenXrHeadFeed.testPose = null
        running = null
        display?.release()
        reader?.close()
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        @Volatile var running: GameRigActivity? = null
        private const val TAG = "GameRig"
        private const val GAPS = 1200
    }
}
