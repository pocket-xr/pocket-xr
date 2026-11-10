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
import android.os.Handler
import android.os.Looper
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
 * 1.3.1 (Mike: "when i click the PC VR button, or if i click the Steady mode, the switching causes the recording to
 * cancel"): the video file ([RecordingFile]) belongs to the process, not to a renderer. Steady mode switching the glasses
 * between 2D and 3D recreates the Space, and PC VR draws with its own renderer; each renderer has one [SpaceRecorder],
 * which only holds its EGL surface on the encoder. One renderer feeds the file at a time: a renderer that stops
 * drawing ([detach]) lets the next one connect, and a file nobody feeds for [RecordingFile.ORPHAN_MS] is finished and
 * kept. Every EGL call is on the owning renderer's thread.
 */
class SpaceRecorder(private val context: Context, private val who: String) {
    private val tag = "FlipSpaces/Record"

    /** Asked for by the Control Center or the Menu ([SpaceSession.recordWanted]); acted on in [frame]. */
    @Volatile var wanted = false

    private var encoderSurface: EGLSurface? = null
    /** The [RecordingFile.generation] [encoderSurface] belongs to. */
    private var surfaceGeneration = 0L
    private var frameNo = 0L
    private var nextTryFrame = 0L

    /**
     * Each frame, render thread, with the finished picture in the window's back buffer. [eyeX], [eyeY], [eyeW], [eyeH]
     * is the left eye's rectangle on the window. Starts, joins or stops the recording as [wanted] says; true while this
     * renderer feeds it, for the red dot.
     */
    fun frame(eyeX: Int, eyeY: Int, eyeW: Int, eyeH: Int): Boolean {
        frameNo++
        // The video this surface fed was finished (and maybe a new one started): its encoder is gone.
        if (encoderSurface != null && (!RecordingFile.open || surfaceGeneration != RecordingFile.generation)) dropStale()
        if (!wanted) {
            if (RecordingFile.open) {
                detach()
                RecordingFile.finish("stopped")
            }
            return false
        }
        if (!RecordingFile.open && !RecordingFile.start(context)) return false
        if (encoderSurface == null) {
            // Another renderer may still be connected to the encoder for a frame or two: try again shortly.
            if (frameNo < nextTryFrame) return true
            val input = RecordingFile.input ?: return false
            encoderSurface = runCatching { encoderSurface(EGL14.eglGetCurrentDisplay(), input) }
                .onFailure {
                    nextTryFrame = frameNo + RETRY_FRAMES
                    if (RecordingFile.failedAttach(who, it.message)) {
                        wanted = false
                        SpaceSession.recordWanted = false
                    }
                }.getOrNull() ?: return true
            surfaceGeneration = RecordingFile.generation
            RecordingFile.attached(who)
        }
        val enc = encoderSurface ?: return false
        if (frameNo % 2L != 0L) return true
        val display = EGL14.eglGetCurrentDisplay()
        val window = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val ctx = EGL14.eglGetCurrentContext()
        if (!EGL14.eglMakeCurrent(display, enc, window, ctx)) {
            Log.w(tag, "$who: the encoder's surface could not be made current (EGL 0x${Integer.toHexString(EGL14.eglGetError())}); connecting again")
            detach()
            return true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, RecordingFile.WIDTH, RecordingFile.HEIGHT)
        GLES30.glBlitFramebuffer(eyeX, eyeY, eyeX + eyeW, eyeY + eyeH, 0, 0, RecordingFile.WIDTH, RecordingFile.HEIGHT, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        EGLExt.eglPresentationTimeANDROID(display, enc, System.nanoTime())
        EGL14.eglSwapBuffers(display, enc)
        EGL14.eglMakeCurrent(display, window, window, ctx)
        RecordingFile.frames++
        return true
    }

    /**
     * This renderer stops feeding the recording (it is going away, or pausing under a game): its EGL surface lets go of
     * the encoder so another renderer can connect. The file stays open. Render thread.
     */
    fun detach() {
        encoderSurface?.let {
            EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it)
            encoderSurface = null
            if (surfaceGeneration == RecordingFile.generation) RecordingFile.detached(who)
        }
        nextTryFrame = 0
    }

    /** The surface of a video already finished: let go of it without telling the file. Render thread. */
    private fun dropStale() {
        encoderSurface?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
        encoderSurface = null
    }

    /** The renderer is going away (or the GL context with it): it lets go, and the file waits for the next. Render thread. */
    fun release() {
        detach()
        wanted = false
    }

    /**
     * 1.2.11: the encoder's surface, on the first config in [RecordSurface.order] that the driver both makes a surface
     * of and makes current with this renderer's context (then the window is current again). Render thread.
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
                Log.i(tag, "$who: encoder surface on the $name config ($ownSizes as its own)${if (tried.isEmpty()) "" else "; refused: " + tried.joinToString()}")
                return s
            }
            EGL14.eglDestroySurface(display, s)
            tried += "$name: not current with the context (EGL 0x${Integer.toHexString(err)})"
        }
        error("no EGL surface for the encoder works with $who's context: ${tried.joinToString()}")
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

    /** The config of the current context, so the encoder's surface matches it. */
    private fun currentConfig(display: android.opengl.EGLDisplay): EGLConfig {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, EGL14.eglGetCurrentContext(), EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        return configs[0] ?: error("the context's EGL config was not found")
    }

    companion object {
        /** EGL_ANDROID_recordable. */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** A renderer that could not connect tries again after this many frames. */
        private const val RETRY_FRAMES = 15L
    }
}

/**
 * 1.3.1: the video being recorded, for the whole process: the encoder, its input surface, the MP4 and the thread that
 * writes it. The renderers feed it through their [SpaceRecorder]; it outlives them, so a Space that is recreated (Steady
 * mode) or PC VR opening carries on in the same video. Finished by the Record button, or when no renderer has fed it
 * for [ORPHAN_MS] (the Space closed).
 */
object RecordingFile {
    private const val TAG = "FlipSpaces/Record"
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FPS = 30
    const val BIT_RATE = 12_000_000

    /** Nobody feeding for this long (the Space and PC VR both gone): the video is finished and kept. */
    const val ORPHAN_MS = 10_000L

    /** A renderer that can't connect for this long gives up the recording. */
    private const val ATTACH_GIVE_UP_MS = 5_000L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var open = false
        private set
    @Volatile var input: Surface? = null
        private set
    @Volatile var frames = 0L

    /** Counts videos, so a renderer knows its surface belongs to one already finished. */
    @Volatile var generation = 0L
        private set

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var uri: Uri? = null
    private var resolverContext: Context? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    @Volatile private var samples = 0L
    private var startNs = 0L
    private var feeder: String? = null
    private var failingSinceMs = 0L
    private val orphaned = Runnable { finish("nothing drew into it for ${ORPHAN_MS / 1000} s (the Space closed)") }

    /** Opens a new video. False when it could not, said on the glasses. Any thread. */
    fun start(context: Context): Boolean = synchronized(lock) {
        if (open) return true
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
            input = c.createInputSurface()
            c.start()

            val app = context.applicationContext.also { resolverContext = it }
            val name = "PocketXR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pocket XR")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val u = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore gave no place for the video")
            uri = u
            val fd = app.contentResolver.openFileDescriptor(u, "rw") ?: error("the video file could not be opened")
            val m = fd.use { MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            muxer = m

            frames = 0
            samples = 0
            idleAfterStop = 0
            feeder = null
            failingSinceMs = 0L
            startNs = SystemClock.elapsedRealtimeNanos()
            draining = true
            drainThread = Thread({ drain(c, m) }, "space-record").apply { start() }
            generation++
            open = true
            SpaceSession.recording = true
            // Until a renderer connects, it counts as unfed.
            main.postDelayed(orphaned, ORPHAN_MS)
            Log.i(TAG, "recording the left eye to Movies/Pocket XR/$name: ${WIDTH}x$HEIGHT at $FPS fps, ${BIT_RATE / 1_000_000} Mbit/s")
            true
        } catch (e: Exception) {
            Log.w(TAG, "recording could not start: ${e.javaClass.simpleName}: ${e.message}")
            SpaceSession.recordWanted = false
            cleanUp(keep = false)
            SpaceSession.recordSaved = RecordSurface.notice(kept = false, frames = 0, seconds = 0.0)
            false
        }
    }

    /** A renderer connected its EGL surface: it feeds the video now. */
    fun attached(who: String) = synchronized(lock) {
        main.removeCallbacks(orphaned)
        failingSinceMs = 0L
        val before = feeder
        feeder = who
        Log.i(TAG, if (before == null) "$who records" else "$who records now, after $before")
    }

    /** A renderer let go. With nobody feeding, the video waits [ORPHAN_MS] for the next before it is finished. */
    fun detached(who: String) = synchronized(lock) {
        if (feeder == who) feeder = null
        if (open && feeder == null) {
            main.removeCallbacks(orphaned)
            main.postDelayed(orphaned, ORPHAN_MS)
            Log.i(TAG, "$who stopped feeding the recording; it waits up to ${ORPHAN_MS / 1000} s for the next")
        }
    }

    /**
     * A renderer could not connect. While another one feeds, that is expected (it is about to let go); alone for
     * [ATTACH_GIVE_UP_MS], the recording ends. True when it ended.
     */
    fun failedAttach(who: String, why: String?): Boolean {
        val give = synchronized(lock) {
            if (feeder != null) return false
            val now = SystemClock.elapsedRealtime()
            if (failingSinceMs == 0L) failingSinceMs = now
            Log.w(TAG, "$who could not connect to the encoder yet: $why")
            now - failingSinceMs >= ATTACH_GIVE_UP_MS
        }
        if (give) finish("$who could not connect to the encoder: $why")
        return give
    }

    /** Ends the video and keeps it when it has frames. Any thread, after every renderer let go of the encoder. */
    fun finish(why: String) {
        val c: MediaCodec
        synchronized(lock) {
            if (!open) return
            open = false
            main.removeCallbacks(orphaned)
            c = codec ?: return
        }
        val secs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e9
        c.runCatching { signalEndOfInputStream() }
        draining = false
        drainThread?.join(3000)
        synchronized(lock) {
            // 1.1.5: kept only when the encoder wrote video into the file; frames drawn alone left a 0 byte file "saved".
            val kept = samples > 0
            cleanUp(keep = kept)
            Log.i(TAG, "recording $why: %.1f s, %d frames drawn, %d samples written%s".format(Locale.ROOT, secs, frames, samples, if (kept) ", saved to Movies/Pocket XR" else ", nothing saved"))
            feeder = null
            SpaceSession.recordWanted = false
            SpaceSession.recording = false
            SpaceSession.recordSaved = RecordSurface.notice(kept, frames, secs)
        }
    }

    private fun cleanUp(keep: Boolean) {
        open = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { input?.release() }
        input = null
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        val resolver = resolverContext?.contentResolver
        uri?.let { u ->
            if (keep) {
                if (Build.VERSION.SDK_INT >= 29) resolver?.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                runCatching { resolver?.delete(u, null, null) }
            }
        }
        uri = null
    }

    private var idleAfterStop = 0

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
}
