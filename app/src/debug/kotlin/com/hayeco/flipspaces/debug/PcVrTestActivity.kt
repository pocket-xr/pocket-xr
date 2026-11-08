package com.hayeco.flipspaces.debug

import android.app.Activity
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

/**
 * The PC VR proof (docs/PCVR-PLAN.md, step 2): Pocket XR's own ALVR client, its two eye pictures side by side on this
 * activity's display. On the phone it shows the lobby; started on the glasses (`am start --display <id>`) it is the
 * stream in 3D with the glasses' head. Debug only.
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
        setContentView(view)
    }

    override fun onDestroy() {
        if (AlvrBridge.available) AlvrBridge.destroy()
        multicast?.release()
        if (AlvrBridge.available) HeadTracker.release()
        super.onDestroy()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var oes = 0
        private var hasFrame = false
        private var program = 0
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
        private val orientation = floatArrayOf(0f, 0f, 0f, 1f)
        private val framePose = FloatArray(4)
        private var newFrames = 0
        private var drawn = 0
        private var lastLogMs = 0L
        private val views by lazy { AlvrBridge.views(this@PcVrTestActivity) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            oes = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            program = linkProgram()
            val verts = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
            val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).position(0)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            quad = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
            Log.i(TAG, "PC VR proof: frames shown straight from the decoder in this context, ${views.line()}")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            pollEvents()
            val now = System.nanoTime()
            // The head as the glasses see it, predicted to when the PC's frame will be shown (a fixed guess for now).
            val q = HeadTracker.gamePose(now + PREDICT_NS)
            if (q != null) {
                orientation[0] = q.x; orientation[1] = q.y; orientation[2] = q.z; orientation[3] = q.w
            }
            if (streaming) {
                head[0] = orientation[0]; head[1] = orientation[1]; head[2] = orientation[2]; head[3] = orientation[3]
                head[4] = 0f; head[5] = EYE_HEIGHT_M; head[6] = 0f
                head[7] = 0f; head[8] = 0f; head[9] = 0f
                AlvrBridge.sendHead(now + PREDICT_NS, head)
                if (!viewsSent) {
                    AlvrBridge.sendViewParams(views)
                    viewsSent = true
                }
                val ts = AlvrBridge.acquireFrame(oes, framePose)
                if (ts != 0L) {
                    newFrames++
                    hasFrame = true
                }
                drawFrame()
                if (ts != 0L) AlvrBridge.submitFrame(ts, VSYNC_QUEUE_NS)
            } else {
                drawFrame()
            }
            drawn++
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastLogMs >= 2_000L) {
                Log.i(TAG, "PC VR proof: ${if (streaming) "streaming ${streamW}x$streamH" else "lobby"}, ${newFrames / 2f} new frames a second, ${drawn / 2f} drawn; HUD \"${AlvrBridge.hudMessage().replace('\n', ' ')}\"")
                lastLogMs = nowMs
                newFrames = 0
                drawn = 0
            }
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

        /** The whole decoded frame across the surface: left eye on the left half, right eye on the right. */
        private fun drawFrame() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (!hasFrame) return
            GLES30.glUseProgram(program)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun linkProgram(): Int {
            fun shader(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
                GLES30.glShaderSource(it, src)
                GLES30.glCompileShader(it)
            }
            val vs = shader(GLES30.GL_VERTEX_SHADER, """#version 300 es
                layout(location = 0) in vec2 pos;
                layout(location = 1) in vec2 uv;
                out vec2 vUv;
                void main() { vUv = vec2(uv.x, 1.0 - uv.y); gl_Position = vec4(pos, 0.0, 1.0); }
            """.trimIndent())
            val fs = shader(GLES30.GL_FRAGMENT_SHADER, """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vUv;
                uniform samplerExternalOES tex;
                out vec4 color;
                void main() { color = texture(tex, vUv); }
            """.trimIndent())
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, vs)
                GLES30.glAttachShader(it, fs)
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
        const val VSYNC_QUEUE_NS = 16_000_000L
        /** A standing head in the play space: the glasses track turns, not position. */
        const val EYE_HEIGHT_M = 1.6f
    }
}
