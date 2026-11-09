package com.hayeco.flipspaces.spatial

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.hayeco.flipspaces.core.spatial.EglConfigSizes
import com.hayeco.flipspaces.core.spatial.RecordSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.5.99 (Mike: "is there no way to record using the xreal glasses recording like how i do when using the aura puck";
 * "A Record button in the Control Center (and the Menu)... a clean 1080p MP4 in your Videos folder: exactly what you see"):
 * the Space records itself. While recording, every other frame (30 a second) the finished picture's left eye is copied
 * on the GPU into a video encoder's input surface (one framebuffer blit, the encoder's surface as the draw surface and
 * the window as the read one), so the video is exactly what the left eye sees, at 1920 x 1080. H.264 at 12 Mbit/s,
 * no audio, saved to Movies/Pocket XR through MediaStore. The red dot that says it is recording is drawn after the copy,
 * so it is on the glasses only, never in the video ([SpaceRenderer]).
 *
 * The encoder and the muxer live on [drainThread]; every EGL call is on the render thread.
 */
class SpaceRecorder(private val context: Context) {
    private val tag = "FlipSpaces/Record"

    /** Asked for by the Control Center or the Menu ([SpaceSession.recordWanted]); acted on in [frame]. */
    @Volatile var wanted = false

    val recording: Boolean get() = encoderSurface != null

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var encoderSurface: EGLSurface? = null
    private var uri: Uri? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    private var frameNo = 0L
    private var startNs = 0L
    private var frames = 0L

    /**
     * Each frame, render thread, with the finished picture in the window's back buffer. [eyeX], [eyeY], [eyeW], [eyeH]
     * is the left eye's rectangle on the window ([surfaceH] tall). Starts or stops as [wanted] says; true while it
     * records, for the red dot.
     */
    fun frame(eyeX: Int, eyeY: Int, eyeW: Int, eyeH: Int): Boolean {
        if (wanted && encoderSurface == null) start()
        if (!wanted && encoderSurface != null) stop("stopped")
        val enc = encoderSurface ?: return false
        if (frameNo++ % 2L != 0L) return true
        val display = EGL14.eglGetCurrentDisplay()
        val window = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val ctx = EGL14.eglGetCurrentContext()
        if (!EGL14.eglMakeCurrent(display, enc, window, ctx)) {
            stop("the encoder's surface could not be made current (EGL 0x${Integer.toHexString(EGL14.eglGetError())})")
            return false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, WIDTH, HEIGHT)
        GLES30.glBlitFramebuffer(eyeX, eyeY, eyeX + eyeW, eyeY + eyeH, 0, 0, WIDTH, HEIGHT, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        EGLExt.eglPresentationTimeANDROID(display, enc, System.nanoTime())
        EGL14.eglSwapBuffers(display, enc)
        EGL14.eglMakeCurrent(display, window, window, ctx)
        frames++
        return true
    }

    /** The Space is going away (or the GL context with it): the video is finished and kept. Render thread. */
    fun release() {
        if (encoderSurface != null) stop("the Space closed")
        wanted = false
    }

    private fun start() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            // 1.2.11: each part kept as it is made, so a start that fails part way releases them and deletes the entry.
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec = it }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = c.createInputSurface().also { inputSurface = it }
            c.start()

            val name = "PocketXR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pocket XR")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore gave no place for the video")
            uri = u
            val fd = context.contentResolver.openFileDescriptor(u, "rw") ?: error("the video file could not be opened")
            val m = fd.use { MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            muxer = m

            val display = EGL14.eglGetCurrentDisplay()
            // 1.1.5 (issue 26: a 0 byte video on a Retroid): a video encoder's surface wants a recordable config. Adreno
            // takes the Space's own config anyway; other GPUs then drop every frame. 1.2.11 (the Flip 7's Xclipse refused
            // a recordable config unlike the context's, EGL 0x3009, every time): one that matches the context, tried.
            val surface = encoderSurface(display, input)

            encoderSurface = surface
            frameNo = 0
            frames = 0
            samples = 0
            startNs = SystemClock.elapsedRealtimeNanos()
            draining = true
            drainThread = Thread({ drain(c, m) }, "space-record").apply { start() }
            SpaceSession.recording = true
            Log.i(tag, "recording the left eye to Movies/Pocket XR/$name: ${WIDTH}x$HEIGHT at $FPS fps, ${BIT_RATE / 1_000_000} Mbit/s")
        } catch (e: Exception) {
            Log.w(tag, "recording could not start: ${e.javaClass.simpleName}: ${e.message}")
            wanted = false
            SpaceSession.recordWanted = false
            cleanUp(keep = false)
            SpaceSession.recordSaved = RecordSurface.notice(kept = false, frames = 0, seconds = 0.0)
        }
    }

    private fun stop(why: String) {
        val secs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e9
        codec?.runCatching { signalEndOfInputStream() }
        draining = false
        drainThread?.join(3000)
        // 1.1.5: kept only when the encoder wrote video into the file; frames drawn alone left a 0 byte file "saved".
        val kept = samples > 0
        cleanUp(keep = kept)
        Log.i(tag, "recording $why: %.1f s, %d frames drawn, %d samples written%s".format(Locale.ROOT, secs, frames, samples, if (kept) ", saved to Movies/Pocket XR" else ", nothing saved"))
        wanted = false
        SpaceSession.recordWanted = false
        SpaceSession.recording = false
        SpaceSession.recordSaved = RecordSurface.notice(kept, frames, secs)
    }

    private fun cleanUp(keep: Boolean) {
        encoderSurface?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
        encoderSurface = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        uri?.let { u ->
            if (keep) {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                runCatching { context.contentResolver.delete(u, null, null) }
            }
        }
        uri = null
    }

    /** The encoder's output into the MP4, until the end of the stream. */
    private fun drain(c: MediaCodec, m: MediaMuxer) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        while (true) {
            val i = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                }
                i >= 0 -> {
                    val buf = c.getOutputBuffer(i)
                    if (buf != null && info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                        samples++
                    }
                    c.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                i == MediaCodec.INFO_TRY_AGAIN_LATER && !draining -> {
                    // The end of the stream was asked for; give it a moment, then stop.
                    if (++idleAfterStop > 50) return
                }
            }
        }
    }

    private var idleAfterStop = 0

    /** 1.1.5: samples the encoder wrote into the MP4 (drain thread). */
    @Volatile private var samples = 0L

    /**
     * 1.2.11: the encoder's surface, on the first config in [RecordSurface.order] that the driver both makes a surface
     * of and makes current with the Space's context (then the window is current again). Render thread.
     */
    private fun encoderSurface(display: android.opengl.EGLDisplay, input: Surface): EGLSurface {
        val window = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val ctx = EGL14.eglGetCurrentContext()
        val own = currentConfig(display)
        val recordable = recordableConfigs(display)
        val ownSizes = sizes(display, own)
        val order = RecordSurface.order(ownSizes, recordable.map { sizes(display, it) })
        val tried = mutableListOf<String>()
        for (i in order) {
            val config = if (i == RecordSurface.OWN) own else recordable[i]
            val name = if (i == RecordSurface.OWN) "own" else "recordable $i"
            val s = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
            if (s == null || s == EGL14.EGL_NO_SURFACE) {
                tried += "$name: no surface (EGL 0x${Integer.toHexString(EGL14.eglGetError())})"
                continue
            }
            val ok = EGL14.eglMakeCurrent(display, s, window, ctx)
            val err = EGL14.eglGetError()
            EGL14.eglMakeCurrent(display, window, window, ctx)
            if (ok) {
                Log.i(tag, "encoder surface on the $name config ($ownSizes as the Space's)${if (tried.isEmpty()) "" else "; refused: " + tried.joinToString()}")
                return s
            }
            EGL14.eglDestroySurface(display, s)
            tried += "$name: not current with the Space (EGL 0x${Integer.toHexString(err)})"
        }
        error("no EGL surface for the encoder works with the Space's context: ${tried.joinToString()}")
    }

    /** 1.1.5: the OpenGL ES 3 configs that a video encoder's surface takes (EGL_RECORDABLE_ANDROID), the driver's order. */
    private fun recordableConfigs(display: android.opengl.EGLDisplay): List<EGLConfig> {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(32)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, n, 0)) return emptyList()
        return configs.take(n[0]).filterNotNull()
    }

    private fun sizes(display: android.opengl.EGLDisplay, config: EGLConfig): EglConfigSizes {
        fun attr(a: Int): Int = IntArray(1).also { EGL14.eglGetConfigAttrib(display, config, a, it, 0) }[0]
        return EglConfigSizes(
            red = attr(EGL14.EGL_RED_SIZE), green = attr(EGL14.EGL_GREEN_SIZE), blue = attr(EGL14.EGL_BLUE_SIZE),
            alpha = attr(EGL14.EGL_ALPHA_SIZE), depth = attr(EGL14.EGL_DEPTH_SIZE), stencil = attr(EGL14.EGL_STENCIL_SIZE),
            samples = attr(EGL14.EGL_SAMPLES), recordable = attr(EGL_RECORDABLE_ANDROID) == 1,
        )
    }

    /** The config of the Space's own context, so the encoder's surface matches it. */
    private fun currentConfig(display: android.opengl.EGLDisplay): EGLConfig {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, EGL14.eglGetCurrentContext(), EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        return configs[0] ?: error("the Space's EGL config was not found")
    }

    companion object {
        /** EGL_ANDROID_recordable. */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
        const val BIT_RATE = 12_000_000
    }
}
