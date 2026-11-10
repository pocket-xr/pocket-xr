package com.hayeco.flipspaces.spatial

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.graphics.SurfaceTexture
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES11Ext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * which only holds its EGL surface on its own feed into the file. One renderer feeds at a time: a renderer that stops
 * drawing ([detach]) lets the next one join, and a file nobody feeds for [RecordingFile.ORPHAN_MS] is finished and
 * kept. The renderer's EGL calls are on its own thread.
 */
class SpaceRecorder(private val context: Context, private val who: String) {
    private val tag = "FlipSpaces/Record"

    /** Asked for by the Control Center or the Menu ([SpaceSession.recordWanted]); acted on in [frame]. */
    @Volatile var wanted = false

    /** This renderer's EGL surface on its feed ([RecordingFile.newFeed]), and the feed's id and video. */
    private var encoderSurface: EGLSurface? = null
    private var feedId = 0L
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
        // Another renderer took over (it joined while this one was away): this one lets go.
        if (encoderSurface != null && !RecordingFile.isCurrent(feedId)) detach()
        if (encoderSurface == null) {
            // One renderer feeds at a time: this one joins when nobody does (the other let go).
            if (RecordingFile.feeding || frameNo < nextTryFrame) return false
            Log.i(tag, "$who joins the recording")
            val (id, surface) = RecordingFile.newFeed(who) ?: run {
                Log.w(tag, "$who: no recording feed yet")
                nextTryFrame = frameNo + RETRY_FRAMES
                return false
            }
            val made = runCatching { encoderSurface(EGL14.eglGetCurrentDisplay(), surface) }
                .onFailure { Log.w(tag, "$who: no EGL surface on its recording feed: ${it.message}") }.getOrNull()
            if (made == null) {
                RecordingFile.detached(who, id)
                nextTryFrame = frameNo + RETRY_FRAMES
                return false
            }
            encoderSurface = made
            feedId = id
            surfaceGeneration = RecordingFile.generation
        }
        val enc = encoderSurface ?: return false
        if (frameNo % 2L != 0L) return true
        val display = EGL14.eglGetCurrentDisplay()
        val window = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val ctx = EGL14.eglGetCurrentContext()
        if (!EGL14.eglMakeCurrent(display, enc, window, ctx)) {
            Log.w(tag, "$who: its recording feed could not be made current (EGL 0x${Integer.toHexString(EGL14.eglGetError())}); joining again")
            detach()
            return true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, RecordingFile.WIDTH, RecordingFile.HEIGHT)
        GLES30.glBlitFramebuffer(eyeX, eyeY, eyeX + eyeW, eyeY + eyeH, 0, 0, RecordingFile.WIDTH, RecordingFile.HEIGHT, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        EGLExt.eglPresentationTimeANDROID(display, enc, System.nanoTime())
        EGL14.eglSwapBuffers(display, enc)
        EGL14.eglMakeCurrent(display, window, window, ctx)
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
            if (surfaceGeneration == RecordingFile.generation) RecordingFile.detached(who, feedId)
        }
        feedId = 0L
        nextTryFrame = 0
    }

    /** The surface of a video already finished: let go of it without telling the file. Render thread. */
    private fun dropStale() {
        encoderSurface?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
        encoderSurface = null
        feedId = 0L
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
 * 1.3.1: the video being recorded, for the whole process: the encoder, the MP4 and the thread that writes it. The
 * renderers feed it through their [SpaceRecorder]; it outlives them, so a Space that is recreated (Steady mode) or PC VR
 * opening carries on in the same video. Finished by the Record button, or when no renderer has fed it for [ORPHAN_MS]
 * (the Space closed).
 *
 * 1.3.2 (1.3.1 still stopped at Steady mode: the Flip 7's driver kept the encoder's input reserved for the Space that
 * had gone, EGL 0x3003 for the new one): the encoder's input belongs to this object's own GL thread, connected once for
 * the whole video. Each renderer that joins gets a fresh input of its own ([newFeed], a SurfaceTexture), which nothing
 * was ever connected to; the GL thread copies the newest feed's frames into the encoder.
 */
object RecordingFile {
    private const val TAG = "FlipSpaces/Record"
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FPS = 30
    const val BIT_RATE = 12_000_000

    /** Nobody feeding for this long (the Space and PC VR both gone): the video is finished and kept. */
    const val ORPHAN_MS = 10_000L

    /** How long a renderer waits for the GL thread to make its feed. */
    private const val FEED_WAIT_MS = 1_000L

    /** EGL_ANDROID_recordable. */
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var open = false
        private set
    @Volatile var frames = 0L

    /** Counts videos, so a renderer knows its feed belongs to one already finished. */
    @Volatile var generation = 0L
        private set

    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var muxer: MediaMuxer? = null
    private var uri: Uri? = null
    private var resolverContext: Context? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    @Volatile private var samples = 0L
    private var startNs = 0L
    private var feeder: String? = null
    private val orphaned = Runnable { finish("nothing drew into it for ${ORPHAN_MS / 1000} s (the Space closed)") }

    // The GL thread, its EGL and the feeds (GL thread only, except where said).
    private var glThread: HandlerThread? = null
    private var gl: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglEncoder: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var quad = 0
    private val matrix = FloatArray(16)
    private class Feed(val id: Long, val who: String, val texture: Int, val st: SurfaceTexture, val surface: Surface)
    private val feeds = mutableMapOf<Long, Feed>()
    @Volatile private var currentFeed = 0L
    private var nextFeedId = 1L

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
            val enc = c.createInputSurface().also { input = it }
            c.start()

            // The GL thread, connected to the encoder for the whole video.
            val t = HandlerThread("space-record-gl").apply { start() }
            glThread = t
            val h = Handler(t.looper)
            gl = h
            val setUp = onGl(h) { setUpGl(enc) } ?: error("the recording's GL thread did not start")
            setUp.getOrThrow()

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
            currentFeed = 0L
            startNs = SystemClock.elapsedRealtimeNanos()
            draining = true
            drainThread = Thread({ drain(c, m) }, "space-record").apply { start() }
            generation++
            open = true
            SpaceSession.recording = true
            // Until a renderer joins, it counts as unfed.
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

    /**
     * A fresh input for [who] to draw its frames into, newest first: from now on its frames are the video's. Null when
     * the recording isn't open or the GL thread didn't answer. Any thread (a renderer's).
     */
    fun newFeed(who: String): Pair<Long, Surface>? {
        val h = synchronized(lock) { if (!open) return null; gl } ?: return null
        val made = onGl(h) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val st = SurfaceTexture(t[0]).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
            val id = nextFeedId++
            val feed = Feed(id, who, t[0], st, Surface(st))
            st.setOnFrameAvailableListener({ onFrame(feed) }, gl)
            feeds[id] = feed
            currentFeed = id
            id to feed.surface
        }?.getOrNull() ?: return null
        synchronized(lock) {
            main.removeCallbacks(orphaned)
            val before = feeder
            feeder = who
            Log.i(TAG, if (before == null) "$who records" else "$who records now, after $before")
        }
        return made
    }

    /** [who] stopped drawing into feed [id]. With nobody feeding, the video waits [ORPHAN_MS] for the next. Any thread. */
    fun detached(who: String, id: Long) {
        val h = synchronized(lock) { gl } ?: return
        h.post {
            feeds.remove(id)?.let { f ->
                runCatching { f.surface.release() }
                runCatching { f.st.release() }
                GLES30.glDeleteTextures(1, intArrayOf(f.texture), 0)
            }
        }
        synchronized(lock) {
            if (!open || currentFeed != id) return
            currentFeed = 0L
            feeder = null
            main.removeCallbacks(orphaned)
            main.postDelayed(orphaned, ORPHAN_MS)
            Log.i(TAG, "$who stopped feeding the recording; it waits up to ${ORPHAN_MS / 1000} s for the next")
        }
    }

    /** Whether a renderer feeds the video now. */
    val feeding: Boolean get() = currentFeed != 0L

    /** Whether feed [id] is the one the video shows (the newest). */
    fun isCurrent(id: Long): Boolean = currentFeed == id

    /** A frame in a feed (GL thread): taken always, so its renderer never waits; copied only from the newest feed. */
    private fun onFrame(feed: Feed) {
        if (feeds[feed.id] !== feed) return
        runCatching { feed.st.updateTexImage() }.onFailure { return }
        if (feed.id != currentFeed || !open) return
        feed.st.getTransformMatrix(matrix)
        GLES30.glViewport(0, 0, WIDTH, HEIGHT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, feed.texture)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "tex"), 1, false, matrix, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val ts = feed.st.timestamp
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoder, if (ts > 0L) ts else System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglEncoder)
        frames++
    }

    private fun setUpGl(enc: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "no EGL display" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no recordable EGL config" }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "no EGL context (EGL 0x${Integer.toHexString(EGL14.eglGetError())})" }
        eglEncoder = EGL14.eglCreateWindowSurface(eglDisplay, config, enc, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglEncoder != EGL14.EGL_NO_SURFACE) { "no surface on the encoder (EGL 0x${Integer.toHexString(EGL14.eglGetError())})" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglEncoder, eglEncoder, eglContext)) { "the encoder's surface is not current (EGL 0x${Integer.toHexString(EGL14.eglGetError())})" }
        program = link(
            "#version 300 es\nlayout(location = 0) in vec2 pos;\nuniform mat4 tex;\nout vec2 uv;\nvoid main() { uv = (tex * vec4(pos * 0.5 + 0.5, 0.0, 1.0)).xy; gl_Position = vec4(pos, 0.0, 1.0); }\n",
            "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;\nuniform samplerExternalOES frame;\nin vec2 uv;\nout vec4 color;\nvoid main() { color = vec4(texture(frame, uv).rgb, 1.0); }\n",
        )
        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = java.nio.ByteBuffer.allocateDirect(verts.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(verts).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        Log.i(TAG, "the recording's GL thread is connected to the encoder")
    }

    private fun tearDownGl() {
        for (f in feeds.values) {
            runCatching { f.surface.release() }
            runCatching { f.st.release() }
        }
        feeds.clear()
        currentFeed = 0L
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglEncoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglEncoder)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            // The display is the process's, shared with the Space: never terminated here.
        }
        eglEncoder = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0
        quad = 0
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "recording shader: ${GLES30.glGetShaderInfoLog(it)}" }
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        GLES30.glUseProgram(p)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "frame"), 0)
        return p
    }

    /** Runs [work] on the GL thread and waits for it. Null when it didn't answer in time. */
    private fun <T> onGl(h: Handler, work: () -> T): Result<T>? {
        if (Looper.myLooper() == h.looper) return runCatching(work)
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        if (!h.post { result = runCatching(work); done.countDown() }) return null
        if (!done.await(FEED_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        return result
    }

    /** Ends the video and keeps it when it has frames. Any thread. */
    fun finish(why: String) {
        val c: MediaCodec
        val h: Handler?
        synchronized(lock) {
            if (!open) return
            open = false
            main.removeCallbacks(orphaned)
            c = codec ?: return
            h = gl
        }
        val secs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e9
        // The GL thread lets go of the feeds and the encoder first.
        h?.let { onGl(it) { tearDownGl() } }
        c.runCatching { signalEndOfInputStream() }
        draining = false
        drainThread?.join(3000)
        synchronized(lock) {
            // 1.1.5: kept only when the encoder wrote video into the file; frames drawn alone left a 0 byte file "saved".
            val kept = samples > 0
            cleanUp(keep = kept)
            Log.i(TAG, "recording $why: %.1f s, %d frames, %d samples written%s".format(Locale.ROOT, secs, frames, samples, if (kept) ", saved to Movies/Pocket XR" else ", nothing saved"))
            feeder = null
            SpaceSession.recordWanted = false
            SpaceSession.recording = false
            SpaceSession.recordSaved = RecordSurface.notice(kept, frames, secs)
        }
    }

    private fun cleanUp(keep: Boolean) {
        open = false
        gl?.let { h -> onGl(h) { tearDownGl() } }
        glThread?.quitSafely()
        glThread = null
        gl = null
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
