package com.hayeco.flipspaces.pcvr

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.openxr.OpenXrPads
import com.hayeco.flipspaces.spatial.HeadTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.hayeco.flipspaces.core.hands.HandSkeleton
import kotlin.math.abs
import kotlin.math.tan

/**
 * PC VR on the glasses: Pocket XR's own ALVR client drawn side by side, with the loading scene until SteamVR is steady.
 *
 * Each new decoded frame is copied once into a texture of Pocket XR's own: ALVR hands the decoder's buffer back as soon
 * as it is asked for the next frame, so a frame shown again with no new one would read a buffer already reused. Then
 * every refresh draws that copy turned from the head the PC drew it for to the head now (a rotational late warp), so
 * turning the head shows at once, not a stream delay later. [AlvrBridge.init] must have run.
 */
class PcVrRenderer(private val context: Context, private val pads: PcVrPads) : GLSurfaceView.Renderer {
    private var oes = 0
    private var copyTex = 0
    private var copyFbo = 0
    private var copyW = 0
    private var copyH = 0
    private var hasFrame = false
    private var copyProgram = 0
    private var warpProgram = 0
    private var warpOesProgram = 0
    private var portalProgram = 0
    private var quad = 0
    private var surfaceW = 1
    private var surfaceH = 1
    private var streaming = false
    private var streamW = 0
    private var streamH = 0
    private var viewsSent = false
    private var decoderMade = false
    private val event = FloatArray(4)
    private val head = FloatArray(10)
    private val hands = FloatArray(28)
    private val sentOrientation = floatArrayOf(0f, 0f, 0f, 1f)
    private val nowOrientation = floatArrayOf(0f, 0f, 0f, 1f)
    private val framePose = FloatArray(12).also { it[3] = 1f }
    private val copyPose = FloatArray(12).also { it[3] = 1f }
    private val frameTans = FloatArray(8)
    private var predictNs = DEFAULT_PREDICT_NS
    private val warp = FloatArray(9)
    private var newFrames = 0
    /** The loading scene, until the stream is steady, then faded out over it. */
    private val loading = PcVrLoading()
    /** Its own card, so holding to leave never repaints the loading card back and forth. */
    private val leaving = PcVrLoading()
    private val stalledCard = PcVrLoading()
    private var stallSinceNs = 0L
    private var lastFreshAskNs = 0L
    private var stallAlpha = 0f
    private var loadStartNs = 0L
    private var everStreamed = false
    private var lastFrameNs = 0L
    private var steadySinceNs = 0L
    private var revealNs = 0L
    /** Per log: summed turn between successive frames' poses, and from each frame's pose to the newest head sent. */
    private var poseStep = 0.0
    private var poseLag = 0.0
    private var drawn = 0
    private var lastLogMs = 0L
    /** The views each eye's half shows (zoomed out by [PcVrTuning.zoom]) and the wider views the PC is asked to draw. */
    private var zoom = 0f
    private lateinit var views: AlvrBridge.Views
    private lateinit var wideViews: AlvrBridge.Views
    /** The shown views as tangents: left, right, up, down (left and down negative), left eye first. */
    private val tans = FloatArray(8)

    /** On a new zoom: new views, sent to the PC on the next frame. Frames already drawn carry their own view. */
    private fun applyZoom() {
        zoom = PcVrTuning.zoom
        views = AlvrBridge.views(context, 0f, zoom)
        wideViews = AlvrBridge.views(context, MARGIN, zoom)
        for (i in 0 until 8) tans[i] = tan(views.fovs[i].toDouble()).toFloat()
        viewsSent = false
        Log.i(TAG, "PC VR zoom ${"%.2f".format(java.util.Locale.ROOT, zoom)}: ${views.line()}")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oes = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        copyProgram = link(VERTEX, COPY_FRAGMENT)
        warpProgram = link(VERTEX, WARP_FRAGMENT)
        warpOesProgram = link(VERTEX, WARP_OES_FRAGMENT)
        portalProgram = link(VERTEX, PORTAL_FRAGMENT)
        val verts = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        applyZoom()
        loading.create(VERTEX)
        leaving.create(VERTEX)
        stalledCard.create(VERTEX)
        loadStartNs = System.nanoTime()
        Log.i(TAG, "PC VR: each frame copied once, then turned to the head every refresh; glasses ${views.line()}; the PC draws ${(MARGIN * 100).toInt()} % wider: ${wideViews.line()}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        pollEvents()
        val now = System.nanoTime()
        if (streaming) {
            // The head at the time the PC's frame will be shown: ALVR's own measured delay, as its client does.
            predictNs = AlvrBridge.predictionOffsetNs().takeIf { it in 1..MAX_PREDICT_NS } ?: DEFAULT_PREDICT_NS
            val target = now + predictNs
            val q0 = headAt(target)
            val q1 = headAt(target + RATE_STEP_NS)
            if (q0 != null) set(sentOrientation, q0.x, q0.y, q0.z, q0.w)
            head[0] = sentOrientation[0]; head[1] = sentOrientation[1]; head[2] = sentOrientation[2]; head[3] = sentOrientation[3]
            head[4] = 0f; head[5] = EYE_HEIGHT_M; head[6] = 0f
            if (q0 != null && q1 != null) turnRate(q0.x, q0.y, q0.z, q0.w, q1.x, q1.y, q1.z, q1.w, head) else { head[7] = 0f; head[8] = 0f; head[9] = 0f }
            pads.at(target, now, EYE_HEIGHT_M, hands)
            val handMask = trackedHands(now)
            AlvrBridge.sendTracking(target, head, hands, skeletons, handMask)
            if (zoom != PcVrTuning.zoom) applyZoom()
            if (!viewsSent) {
                AlvrBridge.sendViewParams(wideViews)
                viewsSent = true
            }
            val ts = AlvrBridge.acquireFrame(oes, framePose)
            if (ts != 0L) {
                if (!PcVrTuning.direct) copyFrame()
                poseStep += angle(copyPose, framePose)
                poseLag += angle(framePose, sentOrientation)
                copyPose.indices.forEach { copyPose[it] = framePose[it] }
                for (i in 0 until 8) frameTans[i] = tan(copyPose[4 + i].toDouble()).toFloat()
                hasFrame = true
                newFrames++
                if (lastFrameNs == 0L || now - lastFrameNs > STEADY_GAP_NS) steadySinceNs = now
                lastFrameNs = now
            }
            // The head at this refresh's light, about a frame and a half ahead.
            headAt(now + PHOTON_NS)?.let { q -> set(nowOrientation, q.x, q.y, q.z, q.w) }
            drawWarped()
            if (ts != 0L) AlvrBridge.submitFrame(ts, PHOTON_NS)
            // Steady new frames for a moment: SteamVR is up. Fade the loading scene out.
            if (revealNs == 0L && steadySinceNs != 0L && now - lastFrameNs < STEADY_GAP_NS &&
                now - steadySinceNs >= STEADY_NS && now - loadStartNs >= MIN_LOADING_NS
            ) {
                revealNs = now
                Log.i(TAG, "PC VR ready after %.1f s: fading in".format(java.util.Locale.ROOT, (now - loadStartNs) / 1e9))
            }
            drawLoading(now)
            drawStalled(now)
            drawLeaving(now)
        } else {
            clear()
            headAt(now + PHOTON_NS)?.let { q -> set(nowOrientation, q.x, q.y, q.z, q.w) }
            drawLoading(now)
        }
        drawPortal(now)
        drawn++
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastLogMs >= 2_000L) {
            val battery = context.getSystemService(android.os.BatteryManager::class.java)
            val currentMa = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.div(1000) ?: 0
            Log.i(TAG, "PC VR power: battery current $currentMa mA, thermal ${powerThermal()}")
            Log.i(TAG, "PC VR: ${if (streaming) "streaming ${streamW}x$streamH" else "waiting"}, ${newFrames / 2f} new frames a second, ${drawn / 2f} drawn, head predicted ${predictNs / 1_000_000} ms ahead")
            if (streaming && newFrames > 0) Log.i(TAG, "PC VR frames: pose turned %.2f degrees a frame, %.1f degrees behind the head sent".format(
                java.util.Locale.ROOT, poseStep / newFrames, poseLag / newFrames))
            poseStep = 0.0
            poseLag = 0.0
            lastLogMs = nowMs
            newFrames = 0
            drawn = 0
        }
    }

    private fun powerThermal(): Int =
        context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: -1

    private val skeletons = FloatArray(2 * HandSkeleton.JOINTS * HandSkeleton.STRIDE)
    private val handJoints = FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE)
    private var handsLoggedMs = 0L

    /**
     * Free hands the glasses' camera sees, for SteamVR: a hand with no Joy-Con in it (or the Joy-Con silent) and seen in
     * the last [HAND_FRESH_MS] is turned from the head frame into the room with the head pose of the moment the camera
     * saw it, and sent as a hand skeleton, with its wrist as that hand's pose. Returns the mask of hands sent.
     */
    private fun trackedHands(now: Long): Int {
        var mask = 0
        val nowMs = SystemClock.uptimeMillis()
        for (side in 0..1) {
            if (hands[14 * side] > 0.5f) continue
            val seenMs = PcVrHands.latest(side, handJoints)
            if (seenMs == 0L || nowMs - seenMs > HAND_FRESH_MS) continue
            // The uptime and nanoTime clocks share their base on Android.
            val q = headAt(seenMs * 1_000_000L) ?: continue
            val o = side * HandSkeleton.JOINTS * HandSkeleton.STRIDE
            for (j in 0 until HandSkeleton.JOINTS) {
                val i = j * HandSkeleton.STRIDE
                val jq = com.hayeco.flipspaces.core.spatial.Quat(handJoints[i + 3], handJoints[i], handJoints[i + 1], handJoints[i + 2])
                val wq = (q * jq).normalized()
                val wp = q.rotate(com.hayeco.flipspaces.core.spatial.Vec3(handJoints[i + 4], handJoints[i + 5], handJoints[i + 6]))
                skeletons[o + i] = wq.x; skeletons[o + i + 1] = wq.y; skeletons[o + i + 2] = wq.z; skeletons[o + i + 3] = wq.w
                skeletons[o + i + 4] = wp.x; skeletons[o + i + 5] = wp.y + EYE_HEIGHT_M; skeletons[o + i + 6] = wp.z
            }
            // The hand's own pose: its wrist (joint 1).
            val h = 14 * side
            val w = o + HandSkeleton.STRIDE
            hands[h] = 1f
            hands[h + 1] = skeletons[w]; hands[h + 2] = skeletons[w + 1]; hands[h + 3] = skeletons[w + 2]; hands[h + 4] = skeletons[w + 3]
            hands[h + 5] = skeletons[w + 4]; hands[h + 6] = skeletons[w + 5]; hands[h + 7] = skeletons[w + 6]
            for (k in 8..13) hands[h + k] = 0f
            mask = mask or (1 shl side)
        }
        if (mask != 0 && nowMs - handsLoggedMs > 5_000L) {
            handsLoggedMs = nowMs
            Log.i(TAG, "PC VR hands: ${if (mask and 1 != 0) "left " else ""}${if (mask and 2 != 0) "right " else ""}sent to SteamVR as tracked hands")
        }
        return mask
    }

    /** The glasses' head, or the debug "wearer" command's turning head in its place. */
    private fun headAt(ns: Long) = com.hayeco.flipspaces.openxr.OpenXrHeadFeed.testPose?.invoke(ns) ?: HeadTracker.gamePose(ns)

    /** Degrees between two orientations, x y z w first in each array. */
    private fun angle(a: FloatArray, b: FloatArray): Double {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f)
        return Math.toDegrees(2.0 * kotlin.math.acos(d.toDouble()))
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
                    everStreamed = true
                    pads.streaming = true
                    viewsSent = false
                    AlvrBridge.sendControllerProfile()
                }
                AlvrBridge.EVENT_STREAMING_STOPPED -> {
                    Log.i(TAG, "streaming stopped")
                    streaming = false
                    pads.streaming = false
                    hasFrame = false
                    // Back to the loading scene until the stream is steady again.
                    revealNs = 0L
                    lastFrameNs = 0L
                    steadySinceNs = 0L
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

    /**
     * The freeze guard: no new frame for [STALL_NS] after the stream was showing. Rather than a still picture that only
     * turns with the head, the loading room fades back in saying so, the PC is asked for a fresh full frame, and it all
     * fades out again once frames come back. Logged, to catch the cause (8 November 2026: freezes of about 20 s).
     */
    private fun drawStalled(now: Long) {
        val stalled = revealNs != 0L && lastFrameNs != 0L && now - lastFrameNs > STALL_NS
        if (stalled && stallSinceNs == 0L) {
            stallSinceNs = now
            Log.w(TAG, "PC VR freeze: no new frame for %.1f s; asking the PC for a full frame".format(java.util.Locale.ROOT, (now - lastFrameNs) / 1e9))
            PcVrSession.askFreshFrame()
            lastFreshAskNs = now
        } else if (stalled && now - lastFreshAskNs > FRESH_AGAIN_NS) {
            PcVrSession.askFreshFrame()
            lastFreshAskNs = now
        } else if (!stalled && stallSinceNs != 0L) {
            Log.i(TAG, "PC VR freeze over after %.1f s".format(java.util.Locale.ROOT, (now - stallSinceNs) / 1e9 + STALL_NS / 1e9))
            stallSinceNs = 0L
        }
        // Eased in and out over about half a second.
        val target = if (stalled) 1f else 0f
        stallAlpha += (target - stallAlpha) * STALL_EASE
        if (stallAlpha < 0.01f) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        bindQuad()
        stalledCard.draw(now, nowOrientation, tans, surfaceW, surfaceH, stallAlpha.coerceAtMost(0.95f),
            PcVrLoading.Phase.STALLED, 0f, PcVrLoading.Pad.NONE, PcVrLoading.Pad.NONE)
    }

    /** Home or Capture held: the game dims into the loading room, with a ring filling until the Space comes back. */
    private fun drawLeaving(now: Long) {
        val p = PcVrSession.leaveProgress
        if (p <= 0f || revealNs == 0L) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        bindQuad()
        leaving.draw(now, nowOrientation, tans, surfaceW, surfaceH, (p * 2.5f).coerceAtMost(0.92f),
            PcVrLoading.Phase.LEAVING, 0f, PcVrLoading.Pad.NONE, PcVrLoading.Pad.NONE, p)
    }

    /**
     * The portal: PC VR opens as a circle growing from the middle of each eye's view over the live Space, with a soft
     * blue rim, and closes the same way. Everything outside the circle is made see-through (the surface is
     * premultiplied), so the Space shows there.
     */
    private fun drawPortal(now: Long) {
        val open = PcVrSession.portalOpenNs
        val close = PcVrSession.portalCloseNs
        val opened = if (open == 0L) 1f else ((now - open) / (PcVrSession.PORTAL_OPEN_MS * 1e6f)).coerceIn(0f, 1f)
        val closed = if (close == 0L) 0f else ((now - close) / (PcVrSession.PORTAL_CLOSE_MS * 1e6f)).coerceIn(0f, 1f)
        // Eased: quick to start, gentle to settle.
        fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        val size = ease(opened) * (1f - ease(closed))
        if (size >= 1f) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glUseProgram(portalProgram)
        bindQuad()
        GLES30.glUniform1f(GLES30.glGetUniformLocation(portalProgram, "size"), size)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(portalProgram, "aspect"), (surfaceW / 2f) / surfaceH.coerceAtLeast(1))
        GLES30.glEnable(GLES30.GL_BLEND)
        val half = surfaceW / 2
        for (eye in 0..1) {
            GLES30.glViewport(eye * half, 0, half, surfaceH)
            // Outside the circle to nothing, inside kept: destination times the shader's alpha.
            GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_SRC_ALPHA)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(portalProgram, "rim"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            // The rim's glow added on top.
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(portalProgram, "rim"), 1)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawLoading(now: Long) {
        val alpha = if (revealNs == 0L) 1f else {
            val t = ((now - revealNs) / FADE_NS.toFloat()).coerceIn(0f, 1f)
            1f - t * t * (3f - 2f * t)
        }
        if (alpha <= 0f) return
        val phase = when {
            !streaming && everStreamed -> PcVrLoading.Phase.RECONNECTING
            !streaming -> PcVrLoading.Phase.FINDING_PC
            else -> PcVrLoading.Phase.STARTING
        }
        fun pad(h: Int) = PcVrLoading.Pad.entries[OpenXrPads.padState(h)]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        bindQuad()
        loading.draw(now, nowOrientation, tans, surfaceW, surfaceH, alpha, phase, (now - loadStartNs) / 1e9f, pad(0), pad(1),
            words = if (phase == PcVrLoading.Phase.FINDING_PC) helperWords() else null)
    }

    /** What the PC helper is doing, in the card's words; null for the usual "Connecting to your PC". */
    private fun helperWords(): Pair<String, String>? {
        val pc = PcVrSession.helperPc ?: "your PC"
        return when (PcVrSession.helperStage) {
            PcVrHelper.Stage.ASK_ON_PC -> "Allow Pocket XR on your PC" to "Click Yes on $pc. You only do this once."
            PcVrHelper.Stage.REFUSED -> "Your PC said no" to "Start SteamVR on $pc yourself, or allow Pocket XR next time."
            PcVrHelper.Stage.NO_STEAM -> "SteamVR isn't on $pc" to "Install Steam and SteamVR on your PC, then try again."
            PcVrHelper.Stage.NO_ALVR -> "ALVR isn't set up on $pc" to "Install the ALVR streamer on your PC and open it once."
            PcVrHelper.Stage.STARTING_STEAMVR -> "Starting SteamVR on $pc" to "This takes a few seconds."
            PcVrHelper.Stage.STEAMVR_READY -> "Connecting to SteamVR" to "Almost there."
            PcVrHelper.Stage.NO_HELPER -> "Connecting to your PC" to "Start SteamVR on your PC, or run the Pocket XR PC helper."
            PcVrHelper.Stage.LOOKING, null -> null
        }
    }

    /** Each eye's half: the copy turned from the pose the PC drew it for to the head now. */
    private fun drawWarped() {
        clear()
        if (!hasFrame) return
        // Directions in the view now, turned into the frame's view: inverse(frame) * now, as a 3x3 matrix.
        relative(copyPose, nowOrientation, warp)
        // Test: straight from the decoder's image, no copy (PcVrTuning.direct).
        val direct = PcVrTuning.direct
        val program = if (direct) warpOesProgram else warpProgram
        GLES30.glUseProgram(program)
        bindQuad()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (direct) GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes) else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, copyTex)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "warp"), 1, false, warp, 0)
        val half = surfaceW / 2
        for (eye in 0..1) {
            GLES30.glViewport(eye * half, 0, half, surfaceH)
            val o = eye * 4
            // Left, right, up, down tangents: the glasses' view for this eye's half, and the wider one the PC drew.
            GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "tans"), tans[o], tans[o + 1], tans[o + 2], tans[o + 3])
            GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "frameTans"), frameTans[o], frameTans[o + 1], frameTans[o + 2], frameTans[o + 3])
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "eyeOffset"), eye * 0.5f)
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

    companion object {
        private const val TAG = "PocketXrAlvr"
        /** The PC draws this share of each eye's width and height more on every side, so the late warp has room. */
        const val MARGIN = 0.06f
        /** A tracked hand older than this is not sent (the camera runs at about 30 a second). */
        private const val HAND_FRESH_MS = 150L
        /** No new frame this long, after the stream was showing: the freeze guard steps in. */
        private const val STALL_NS = 1_000_000_000L
        private const val FRESH_AGAIN_NS = 3_000_000_000L
        private const val STALL_EASE = 0.08f
        /** New frames no further apart than this count as steady; this long steady and the loading scene fades. */
        private const val STEADY_GAP_NS = 150_000_000L
        private const val STEADY_NS = 1_500_000_000L
        /** The loading scene shows at least this long, so it never flashes, and fades over [FADE_NS]. */
        private const val MIN_LOADING_NS = 1_200_000_000L
        private const val FADE_NS = 800_000_000L
        /** Until ALVR has measured its delay. */
        private const val DEFAULT_PREDICT_NS = 60_000_000L
        private const val MAX_PREDICT_NS = 150_000_000L
        /** A Joy-Con is carried forward at most this far past its newest sample. */
        const val MAX_HAND_AHEAD_NS = 40_000_000L
        /** A Joy-Con silent this long is not sent (ALVR then shows it as lost). */
        const val HAND_STALE_NS = 150_000_000L
        /** Two predicted poses this far apart give the head's turn rate. */
        private const val RATE_STEP_NS = 8_000_000L
        /** From drawing to light on the glasses: about a frame and a half at 60 Hz. */
        private const val PHOTON_NS = 25_000_000L
        /** A standing head in the play space: the glasses track turns, not position. */
        private const val EYE_HEIGHT_M = 1.6f

        /** The world angular velocity (rad/s) turning a into b over [RATE_STEP_NS], into out[7..9]. */
        fun turnRate(ax: Float, ay: Float, az: Float, aw: Float, bx: Float, by: Float, bz: Float, bw: Float, out: FloatArray) {
            // d = b * conj(a)
            var x = bw * -ax + bx * aw + (by * -az - bz * -ay)
            var y = bw * -ay + by * aw + (bz * -ax - bx * -az)
            var z = bw * -az + bz * aw + (bx * -ay - by * -ax)
            var w = bw * aw - (bx * -ax + by * -ay + bz * -az)
            if (w < 0f) { x = -x; y = -y; z = -z; w = -w }
            val s = kotlin.math.sqrt(x * x + y * y + z * z)
            if (s < 1e-7f) { out[7] = 0f; out[8] = 0f; out[9] = 0f; return }
            val k = 2f * kotlin.math.atan2(s, w) / (RATE_STEP_NS / 1e9f) / s
            out[7] = x * k; out[8] = y * k; out[9] = z * k
        }

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
            uniform vec4 frameTans;
            uniform float eyeOffset;
            out vec4 color;
            void main() {
                vec3 dir = vec3(mix(tans.x, tans.y, vUv.x), mix(tans.w, tans.z, vUv.y), -1.0);
                vec3 d = warp * dir;
                if (d.z >= -1e-4) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                vec2 t = d.xy / -d.z;
                vec2 f = vec2((t.x - frameTans.x) / (frameTans.y - frameTans.x), (t.y - frameTans.w) / (frameTans.z - frameTans.w));
                if (any(lessThan(f, vec2(0.0))) || any(greaterThan(f, vec2(1.0)))) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                color = texture(tex, vec2(eyeOffset + f.x * 0.5, f.y));
            }
        """

        /**
         * The portal's mask ([rim] 0: alpha 1 inside the circle, 0 outside, a soft edge) and its rim (1: a blue glow at
         * the edge, premultiplied). [size] 0 to 1: the circle's radius from nothing to past the view's corners.
         */
        const val PORTAL_FRAGMENT = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform float size;
            uniform float aspect;
            uniform int rim;
            out vec4 color;
            void main() {
                vec2 p = (vUv - 0.5) * vec2(aspect, 1.0);
                float r = length(p);
                float radius = size * (0.5 * length(vec2(aspect, 1.0)) + 0.08);
                float edge = 0.02;
                float inside = 1.0 - smoothstep(radius - edge, radius + edge, r);
                if (rim == 0) {
                    color = vec4(0.0, 0.0, 0.0, inside);
                } else {
                    float glow = exp(-abs(r - radius) * 38.0) * (1.0 - size * size) * 0.9;
                    color = vec4(vec3(0.30, 0.55, 1.0) * glow, glow);
                }
            }
        """

        /** As [WARP_FRAGMENT], straight from the decoder's image (its first row at the top). */
        const val WARP_OES_FRAGMENT = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vUv;
            uniform samplerExternalOES tex;
            uniform mat3 warp;
            uniform vec4 tans;
            uniform vec4 frameTans;
            uniform float eyeOffset;
            out vec4 color;
            void main() {
                vec3 dir = vec3(mix(tans.x, tans.y, vUv.x), mix(tans.w, tans.z, vUv.y), -1.0);
                vec3 d = warp * dir;
                if (d.z >= -1e-4) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                vec2 t = d.xy / -d.z;
                vec2 f = vec2((t.x - frameTans.x) / (frameTans.y - frameTans.x), (t.y - frameTans.w) / (frameTans.z - frameTans.w));
                if (any(lessThan(f, vec2(0.0))) || any(greaterThan(f, vec2(1.0)))) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
                color = texture(tex, vec2(eyeOffset + f.x * 0.5, 1.0 - f.y));
            }
        """
    }
}
