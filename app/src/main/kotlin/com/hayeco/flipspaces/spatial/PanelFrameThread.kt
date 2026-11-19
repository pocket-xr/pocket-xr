package com.hayeco.flipspaces.spatial

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import android.view.Surface
import androidx.opengl.EGLExt
import androidx.opengl.EGLImageKHR
import com.hayeco.flipspaces.core.spatial.PanelImageMailbox
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One copy of a panel image on the GPU: the fence after the draw that read it, and the EGL image
 * and HardwareBuffer it was read through. Freed on the render thread once the fence has passed.
 */
internal class CopyToken(val sync: Long, val eglImage: EGLImageKHR?, val buffer: HardwareBuffer?, val oesTexture: Int = 0) {
    /** Render thread, never waits: the GPU has finished the copy. */
    fun done(): Boolean {
        if (sync == 0L) return true
        val r = GLES30.glClientWaitSync(sync, 0, 0L)
        return r == GLES30.GL_ALREADY_SIGNALED || r == GLES30.GL_CONDITION_SATISFIED || r == GLES30.GL_WAIT_FAILED
    }

    /** Render thread. */
    fun free() {
        if (sync != 0L) GLES30.glDeleteSync(sync)
        GlKit.deleteTexture(oesTexture)
        eglImage?.let { runCatching { EGLExt.eglDestroyImageKHR(EGL14.eglGetCurrentDisplay(), it) } }
        buffer?.let { runCatching { it.close() } }
    }
}

/**
 * 0.5.3: one panel's frames. Its virtual display renders into an [ImageReader] whose images are
 * taken by the [PanelDrain] thread the moment they arrive and kept in a [PanelImageMailbox]
 * (at most the newest one waiting). The render thread copies the newest into [texture], the
 * mipmapped texture the Space draws, at most at the panel's thermal cap.
 *
 * 0.5.1 and 0.5.2 used a SurfaceTexture, whose frames only GL could take (updateTexImage), on the
 * render thread in 0.5.1 and on a frame thread sharing its EGL display in 0.5.2. Whenever GL was
 * held up (the render thread waiting in its swap for SurfaceFlinger, MediaPipe rebuilding its
 * GPU graph) the display's queue filled and SurfaceFlinger's main thread waited in dequeueBuffer
 * until its watchdog reset the phone's graphics.
 */
internal class PanelSource(width: Int, height: Int, decoded: Boolean = false) {
    // PC screens: a decoded source takes a video decoder's own buffers (ImageFormat.PRIVATE, often YUV), which the copy
    // reads through its external texture like any other; an app's display draws RGBA as before.
    val reader: ImageReader = if (decoded) {
        ImageReader.newInstance(
            width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE,
            PanelImageMailbox.DEFAULT_MAX_IMAGES + 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
    } else {
        ImageReader.newInstance(
            width.coerceAtLeast(1), height.coerceAtLeast(1), PixelFormat.RGBA_8888,
            PanelImageMailbox.DEFAULT_MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
    }
    val surface: Surface = reader.surface
    // 0.5.26: two pictures may wait, so each is shown on the refresh its own timestamp says ([latch]).
    val mailbox = PanelImageMailbox<Image>(close = { runCatching { it.close() } }, maxWaiting = 2)

    /** 0.5.26: which waiting picture each refresh shows, by its timestamp; locked on itself. */
    val latch = com.hayeco.flipspaces.core.spatial.PanelLatch()

    /** The mipmapped texture the Space draws; each image is copied in through an external texture of its own. Render thread. */
    val texture = PanelMipTexture()

    @Volatile var width = width
    @Volatile var height = height
    @Volatile var released = false

    /** When the drain thread last took an image, for the log. */
    @Volatile var lastImageMs = 0L

    /** 0.5.26: the app's frames as they came (each image's timestamp) and as the Space showed them; locked on itself. */
    val pacing = com.hayeco.flipspaces.core.spatial.PanelPacing()

    /** Render thread only from here. */
    var lastCopyMs = 0L
    var copies = 0
    var importFailed = false

    /** 0.5.17: the last [PanelImport.copy] built the whole mip chain for the black check. */
    var blackCheckChain = false

    /** The black check (0.4.1), sampled on a copy about once a second. */
    var luma = 1f
    var lumaSeq = 0
    var framesSinceLuma = 0
    var lumaMs = 0L
    var offeredAtLuma = 0L
}

/**
 * 0.5.3: takes every panel's images off its display on a thread that does nothing else: acquire,
 * hand to the mailbox, close the one it replaces. No GL, no EGL, no binder call, no lock held by
 * anything that waits for SurfaceFlinger, and a normal thread priority. Event driven (the
 * reader's listener), so it never spins.
 */
internal class PanelDrain {
    private val tag = "FlipSpaces/Space"
    private val thread = HandlerThread("space-panel-drain").also { it.start() }
    private val handler = Handler(thread.looper)
    private val sources = java.util.concurrent.ConcurrentHashMap.newKeySet<PanelSource>()

    /** Copy tokens let go of off the render thread (a stall's shed), freed at its next frame. */
    val orphans = ConcurrentLinkedQueue<Any?>()

    val all: Collection<PanelSource> get() = sources

    fun add(s: PanelSource) {
        sources += s
        s.reader.setOnImageAvailableListener({ take(s) }, handler)
    }

    private fun take(s: PanelSource) {
        var n = 0
        while (n < MAX_PER_CALLBACK && !s.released) {
            if (!s.mailbox.makeRoom()) break
            val image = try {
                s.reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                Log.w(tag, "panel image acquire: ${e.message}")
                null
            } ?: break
            dumpDir?.let { dir -> if ((s.mailbox.offered + 1) % 25 == 0L) dump(image, dir, s.mailbox.offered + 1) }
            // 0.5.26: the app's own frame time, for the window's pacing line.
            val ts = runCatching { image.timestamp }.getOrDefault(0L)
            if (ts > 0L) {
                synchronized(s.pacing) { s.pacing.arrived(ts) }
                synchronized(s.latch) { s.latch.arrived(ts, System.nanoTime()) }
            }
            s.mailbox.offer(image)
            n++
        }
        if (n > 0) s.lastImageMs = SystemClock.uptimeMillis()
    }

    /** Stops [s]: its images go now, its reader on the drain thread (after any take in flight). Render thread. */
    fun remove(s: PanelSource) {
        s.released = true
        sources -= s
        s.mailbox.clear().forEach { (it as? CopyToken)?.free() }
        s.texture.release()
        handler.post {
            runCatching { s.reader.setOnImageAvailableListener(null, null) }
            s.mailbox.clear().forEach { orphans += it }
            runCatching { s.reader.close() }
        }
    }

    /** Any thread (the watchdog): every panel lets go of the images on the GPU and keeps only its newest until [resumeAll]. */
    fun shedAll() {
        for (s in sources) s.mailbox.shed().forEach { orphans += it }
    }

    fun resumeAll() {
        for (s in sources) s.mailbox.resume()
    }

    /** Ends the thread: every reader closes on it (the displays are released first). */
    fun shutdown() {
        val left = sources.toList()
        sources.clear()
        left.forEach { it.released = true }
        handler.post {
            left.forEach { s ->
                runCatching { s.reader.setOnImageAvailableListener(null, null) }
                s.mailbox.clear()
                runCatching { s.reader.close() }
            }
            thread.quitSafely()
        }
    }

    /** Debug: the image as Android itself reads it (not through the Space's GL), as a PNG. */
    private fun dump(image: Image, dir: java.io.File, n: Long) {
        runCatching {
            image.hardwareBuffer?.use { hb ->
                val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, null)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return
                java.io.File(dir, "panel-image-$n.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(tag, "panel image $n dumped (${image.width}x${image.height})")
            }
        }.onFailure { Log.w(tag, "panel image dump: ${it.message}") }
    }

    companion object {
        private const val MAX_PER_CALLBACK = 16

        /** Debug builds with `debug.flipspaces.panelframes=dump`: every 25th panel image is written here. */
        @Volatile var dumpDir: java.io.File? = null

        fun dumpTo(dir: java.io.File?) {
            val mode = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.flipspaces.panelframes") as String
            }.getOrDefault("")
            dumpDir = dir?.takeIf { mode.endsWith("dump") }
        }
    }
}

/**
 * 0.5.3: the render thread's side. Copies a panel's newest image into its texture through an EGL
 * image of the image's HardwareBuffer, and gives images back once the GPU has read them. Never
 * waits: a fence not yet passed is looked at again next frame.
 */
internal object PanelImport {
    private const val TAG = "FlipSpaces/Space"

    /** SurfaceTexture's usual transform: buffers come top row first, GL samples bottom row first. */
    private val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    private const val LUMA_EVERY_MS = 1000L

    /** Debug: level 0 of the Space's copy, as GL reads it back, as a PNG next to the image dumps. */
    private fun dumpCopy(t: PanelMipTexture, dir: java.io.File, n: Int) {
        runCatching {
            val w = t.width
            val h = t.height
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.framebuffer)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            java.io.File(dir, "panel-copy-$n.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "panel copy $n dumped (${w}x$h)")
        }.onFailure { Log.w(TAG, "panel copy dump: ${it.message}") }
    }

    /** Frees what the GPU is done with (and the orphans of a shed). Every frame, every panel. */
    fun reap(drain: PanelDrain) {
        while (true) (drain.orphans.poll() as? CopyToken ?: break).free()
        for (s in drain.all) s.mailbox.reap { (it as? CopyToken)?.done() ?: true }.forEach { (it as? CopyToken)?.free() }
    }

    /**
     * Copies the newest image of [s], if the mailbox lends one. Returns whether a copy was made.
     * 0.5.17: no mip chain here; the render thread builds one after the
     * copy only for a panel that needs it, and only the levels it needs. The black check's chain
     * (once a second) is the one built here, noted in [PanelSource.blackCheckChain].
     */
    fun copy(s: PanelSource, nowMs: Long, sceneWidth: Int, sceneHeight: Int, presentNs: Long = 0L): Boolean {
        s.blackCheckChain = false
        // 0.5.26: the picture due on the refresh at [presentNs] by its own timestamp ([com.hayeco.flipspaces.core.spatial.PanelLatch]).
        val latchNs = System.nanoTime()
        val image = (if (presentNs > 0L) s.mailbox.lease { waiting ->
            val stamps = waiting.map { runCatching { it.timestamp }.getOrDefault(0L) }
            synchronized(s.latch) { s.latch.pick(stamps, presentNs, latchNs) }
        } else s.mailbox.lease()) ?: return false
        var buffer: HardwareBuffer? = null
        var eglImage: EGLImageKHR? = null
        var oes = 0
        try {
            buffer = image.hardwareBuffer ?: error("no HardwareBuffer")
            eglImage = EGLExt.eglCreateImageFromHardwareBuffer(EGL14.eglGetCurrentDisplay(), buffer) ?: error("eglCreateImageFromHardwareBuffer failed")
            // A new external texture for each image: one name re-targeted from image to image
            // kept showing the first image it was bound to (seen on the emulator, 0.5.3).
            oes = GlKit.newOesTexture()
            EGLExt.glEGLImageTargetTexture2DOES(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, eglImage)
            val w = image.width
            val h = image.height
            val t = s.texture
            t.ensure(w, h)
            t.copyFrom(oes, FLIP_Y, sceneWidth, sceneHeight)
            PanelDrain.dumpDir?.let { dir -> if ((s.copies + 1) % 25 == 0) dumpCopy(t, dir, s.copies + 1) }
            val lumaDue = nowMs - s.lumaMs >= LUMA_EVERY_MS
            if (lumaDue) {
                s.lumaMs = nowMs
                s.luma = t.maxLuma()
                s.blackCheckChain = t.lumaChainLevels > 0
                s.lumaSeq++
                val offered = s.mailbox.offered
                s.framesSinceLuma = (offered - s.offeredAtLuma).toInt()
                s.offeredAtLuma = offered
            }
            val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            s.mailbox.retire(image, CopyToken(sync, eglImage, buffer, oes))
            s.lastCopyMs = nowMs
            s.copies++
            if (s.importFailed) Log.i(TAG, "panel frame import works again")
            s.importFailed = false
            return true
        } catch (e: Throwable) {
            GlKit.deleteTexture(oes)
            eglImage?.let { runCatching { EGLExt.eglDestroyImageKHR(EGL14.eglGetCurrentDisplay(), it) } }
            runCatching { buffer?.close() }
            // Given back at once: nothing of it reached the GPU.
            s.mailbox.retire(image, null)
            if (!s.importFailed) Log.w(TAG, "panel frame import failed: ${e.javaClass.simpleName}: ${e.message}")
            s.importFailed = true
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, sceneWidth, sceneHeight)
            GLES30.glEnable(GLES30.GL_BLEND)
            return false
        }
    }
}
