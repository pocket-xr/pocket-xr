package com.hayeco.flipspaces.debug

import android.app.Activity
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.hayeco.flipspaces.pcvr.AlvrBridge
import com.hayeco.flipspaces.spatial.HeadTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

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
        AlvrBridge.init(this, VIEW_WIDTH, VIEW_HEIGHT, 60f)
        view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(Renderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // On the glasses, a Presentation: the desktop mode there makes an activity a floating window, a Presentation
        // always fills its display (the Space does the same).
        val glasses = getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.mode.physicalWidth >= 3 * it.mode.physicalHeight }
        if (glasses != null) {
            Log.i(TAG, "PC VR proof on the glasses: display ${glasses.displayId}, ${glasses.mode.physicalWidth}x${glasses.mode.physicalHeight}")
            presentation = Presentation(this, glasses).apply {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setContentView(view)
                show()
            }
            setContentView(android.widget.TextView(this).apply { text = "PC VR proof: on the glasses" })
        } else {
            setContentView(view)
        }
    }

    private var presentation: Presentation? = null

    override fun onDestroy() {
        presentation?.dismiss()
        if (AlvrBridge.available) AlvrBridge.destroy()
        multicast?.release()
        if (AlvrBridge.available) HeadTracker.release()
        super.onDestroy()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var oes = 0
        private var copyTex = 0
        private var copyFbo = 0
        private var copyW = 0
        private var copyH = 0
        private var hasFrame = false
        private var copyProgram = 0
        private var warpProgram = 0
        private var quad = 0
        private var surfaceW = 1
        private var surfaceH = 1
        private var streaming = false
        private var streamW = VIEW_WIDTH
        private var streamH = VIEW_HEIGHT
        private var viewsSent = false
        private var decoderMade = false
        private val event = FloatArray(4)
        private val head = FloatArray(10)
        private val sentOrientation = floatArrayOf(0f, 0f, 0f, 1f)
        private val nowOrientation = floatArrayOf(0f, 0f, 0f, 1f)
        private val framePose = floatArrayOf(0f, 0f, 0f, 1f)
        private val copyPose = floatArrayOf(0f, 0f, 0f, 1f)
        private val warp = FloatArray(9)
        private var newFrames = 0
        private var drawn = 0
        private var lastLogMs = 0L
        private val views by lazy { AlvrBridge.views(this@PcVrTestActivity) }
        /** Each eye's view as tangents: left, right, up, down (left and down negative). */
        private val tans by lazy { FloatArray(8) { tan(views.fovs[it].toDouble()).toFloat() } }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            oes = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            copyProgram = link(VERTEX, COPY_FRAGMENT)
            warpProgram = link(VERTEX, WARP_FRAGMENT)
            val verts = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
            val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).position(0)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            quad = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
            Log.i(TAG, "PC VR proof: each frame copied once, then turned to the head every refresh; ${views.line()}")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            pollEvents()
            val now = System.nanoTime()
            if (streaming) {
                // The head as the glasses see it at the time the PC's frame will be shown (a fixed guess for now).
                HeadTracker.gamePose(now + PREDICT_NS)?.let { q -> set(sentOrientation, q.x, q.y, q.z, q.w) }
                head[0] = sentOrientation[0]; head[1] = sentOrientation[1]; head[2] = sentOrientation[2]; head[3] = sentOrientation[3]
                head[4] = 0f; head[5] = EYE_HEIGHT_M; head[6] = 0f
                head[7] = 0f; head[8] = 0f; head[9] = 0f
                AlvrBridge.sendHead(now + PREDICT_NS, head)
                if (!viewsSent) {
                    AlvrBridge.sendViewParams(views)
                    viewsSent = true
                }
                val ts = AlvrBridge.acquireFrame(oes, framePose)
                if (ts != 0L) {
                    copyFrame()
                    copyPose.indices.forEach { copyPose[it] = framePose[it] }
                    hasFrame = true
                    newFrames++
                }
                // The head at this refresh's light, about a frame and a half ahead.
                HeadTracker.gamePose(now + PHOTON_NS)?.let { q -> set(nowOrientation, q.x, q.y, q.z, q.w) }
                drawWarped()
                if (ts != 0L) AlvrBridge.submitFrame(ts, PHOTON_NS)
            } else {
                clear()
            }
            drawn++
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastLogMs >= 2_000L) {
                Log.i(TAG, "PC VR proof: ${if (streaming) "streaming ${streamW}x$streamH" else "waiting"}, ${newFrames / 2f} new frames a second, ${drawn / 2f} drawn; HUD \"${AlvrBridge.hudMessage().replace('\n', ' ')}\"")
                lastLogMs = nowMs
                newFrames = 0
                drawn = 0
            }
        }

        private fun set(q: FloatArray, x: Float, y: Float, z: Float, w: Float) {
            q[0] = x; q[1] = y; q[2] = z; q[3] = w
        }

        private fun pollEvents() {
            while (true) {
                when (AlvrBridge.pollEvent(event)) {
                    -1 -> return
                    AlvrBridge.EVENT_STREAMING_STARTED -> {
                        streamW = event[0].toInt()
                        streamH = event[1].toInt()
                        Log.i(TAG, "streaming started: ${streamW}x$streamH per eye, refresh hint ${event[2]} Hz, foveation ${event[3] > 0f}")
                        streaming = true
                        viewsSent = false
                    }
                    AlvrBridge.EVENT_STREAMING_STOPPED -> {
                        Log.i(TAG, "streaming stopped")
                        streaming = false
                        hasFrame = false
                        AlvrBridge.destroyDecoder()
                        decoderMade = false
                    }
                    AlvrBridge.EVENT_DECODER_CONFIG -> {
                        // ALVR: every DecoderConfig after the first is ignored until the next connection.
                        if (!decoderMade) {
                            Log.i(TAG, "decoder config: codec ${event[0].toInt()}")
                            AlvrBridge.createDecoder(event[0].toInt())
                            decoderMade = true
                        }
                    }
                    else -> Unit
                }
            }
        }

        /** The decoded frame (both eyes, side by side) into the copy texture, upright. */
        private fun copyFrame() {
            val w = streamW * 2
            val h = streamH
            if (copyTex == 0 || copyW != w || copyH != h) makeCopyTarget(w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(copyProgram)
            bindQuad()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        private fun makeCopyTarget(w: Int, h: Int) {
            if (copyTex != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(copyTex), 0)
                GLES30.glDeleteFramebuffers(1, intArrayOf(copyFbo), 0)
            }
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            copyTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, copyTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            copyFbo = f[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, copyTex, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            copyW = w
            copyH = h
            Log.i(TAG, "copy texture ${w}x$h")
        }

        private fun clear() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        /** Each eye's half: the copy turned from the pose the PC drew it for to the head now. */
        private fun drawWarped() {
            clear()
            if (!hasFrame) return
            // Directions in the view now, turned into the frame's view: inverse(frame) * now, as a 3x3 matrix.
            relative(copyPose, nowOrientation, warp)
            GLES30.glUseProgram(warpProgram)
            bindQuad()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, copyTex)
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(warpProgram, "warp"), 1, false, warp, 0)
            val half = surfaceW / 2
            for (eye in 0..1) {
                GLES30.glViewport(eye * half, 0, half, surfaceH)
                val o = eye * 4
                // Left, right, up, down tangents; the PC drew with the same views, so now and the frame share them.
                GLES30.glUniform4f(GLES30.glGetUniformLocation(warpProgram, "tans"), tans[o], tans[o + 1], tans[o + 2], tans[o + 3])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(warpProgram, "eyeOffset"), eye * 0.5f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        private fun bindQuad() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        }

        private fun link(vsSrc: String, fsSrc: String): Int {
            fun shader(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
                GLES30.glShaderSource(it, src)
                GLES30.glCompileShader(it)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) Log.w(TAG, "shader: ${GLES30.glGetShaderInfoLog(it)}")
            }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, shader(GLES30.GL_VERTEX_SHADER, vsSrc))
                GLES30.glAttachShader(it, shader(GLES30.GL_FRAGMENT_SHADER, fsSrc))
                GLES30.glLinkProgram(it)
            }
        }
    }

    private companion object {
        const val TAG = "PocketXrAlvr"
        const val VIEW_WIDTH = 1920
        const val VIEW_HEIGHT = 1088
        /** A fixed guess at the whole delay, PC and network included, until ALVR's own average is read. */
        const val PREDICT_NS = 60_000_000L
        /** From drawing to light on the glasses: about a frame and a half at 60 Hz. */
        const val PHOTON_NS = 25_000_000L
        /** A standing head in the play space: the glasses track turns, not position. */
        const val EYE_HEIGHT_M = 1.6f

        /** Column-major 3x3 of the rotation inverse(a) * b, both quaternions x, y, z, w. */
        fun relative(a: FloatArray, b: FloatArray, out: FloatArray) {
            // r = conj(a) * b
            val ax = -a[0]; val ay = -a[1]; val az = -a[2]; val aw = a[3]
            val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
            val x = aw * bx + ax * bw + ay * bz - az * by
            val y = aw * by - ax * bz + ay * bw + az * bx
            val z = aw * bz + ax * by - ay * bx + az * bw
            val w = aw * bw - ax * bx - ay * by - az * bz
            out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y + z * w); out[2] = 2 * (x * z - y * w)
            out[3] = 2 * (x * y - z * w); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z + x * w)
            out[6] = 2 * (x * z + y * w); out[7] = 2 * (y * z - x * w); out[8] = 1 - 2 * (x * x + y * y)
        }

        const val VERTEX = """#version 300 es
            layout(location = 0) in vec2 pos;
            layout(location = 1) in vec2 uv;
            out vec2 vUv;
            void main() { vUv = uv; gl_Position = vec4(pos, 0.0, 1.0); }
        """

        /** The decoder's image has its first row at the top; the copy keeps GL's bottom-up rows, so it flips once. */
        const val COPY_FRAGMENT = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vUv;
            uniform samplerExternalOES tex;
            out vec4 color;
            void main() { color = texture(tex, vec2(vUv.x, 1.0 - vUv.y)); }
        """

        /**
         * One eye: this pixel's direction in the view now (from its tangents), turned into the view the PC drew
         * ([warp]), and looked up in that eye's half of the copy. Outside the frame it is black.
         */
        const val WARP_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D tex;
            uniform mat3 warp;
            uniform vec4 tans;
            uniform float eyeOffset;
            out vec4 color;
            void main() {
                vec3 dir = vec3(mix(tans.x, tans.y, vUv.x), mix(tans.w, tans.z, vUv.y), -1.0);
                vec3 d = warp * dir;
                if (d.z >= -1e-4) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                vec2 t = d.xy / -d.z;
                vec2 f = vec2((t.x - tans.x) / (tans.y - tans.x), (t.y - tans.w) / (tans.z - tans.w));
                if (any(lessThan(f, vec2(0.0))) || any(greaterThan(f, vec2(1.0)))) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                color = texture(tex, vec2(eyeOffset + f.x * 0.5, f.y));
            }
        """
    }
}
