package com.hayeco.flipspaces.pcvr

import android.content.Context
import android.util.Log
import com.hayeco.flipspaces.spatial.SpaceRenderer
import com.hayeco.flipspaces.spatial.SpaceStore
import kotlin.math.atan
import kotlin.math.tan

/**
 * PC VR in the Space: Pocket XR's own copy of ALVR's client (alvr_client_core, MIT licence), in place of the separate
 * ALVR app and Monado (docs/PCVR-PLAN.md). ALVR finds the PC streamer, decodes its video and draws each frame, or its
 * lobby before a stream starts, into two OpenGL textures; Pocket XR shows them on the glasses.
 *
 * The OpenGL calls ([glResume], [startStream], [renderLobby], [renderFrame]) run on the caller's GL thread.
 */
object AlvrBridge {
    private const val TAG = "PocketXrAlvr"

    /** Event tags from [pollEvent], ALVR's own numbering. */
    const val EVENT_HUD = 0
    const val EVENT_STREAMING_STARTED = 1
    const val EVENT_STREAMING_STOPPED = 2
    const val EVENT_HAPTICS = 3
    const val EVENT_DECODER_CONFIG = 4

    /** False where the library isn't built (the x86_64 emulator). */
    val available: Boolean = runCatching { System.loadLibrary("pocketxr_alvr") }
        .onFailure { Log.w(TAG, "PC VR unavailable: ${it.message}") }.isSuccess

    /** Each eye's view for the glasses, as games get it: the field of view, and the turn out to their 3D distance. */
    class Views(val ipdM: Float, val fovs: FloatArray) {
        fun line(): String = "IPD %.1f mm; left eye %.1f to %.1f degrees across, %.1f to %.1f up and down".format(
            java.util.Locale.ROOT, ipdM * 1000f, Math.toDegrees(fovs[0].toDouble()), Math.toDegrees(fovs[1].toDouble()),
            Math.toDegrees(fovs[3].toDouble()), Math.toDegrees(fovs[2].toDouble()),
        )
    }

    /**
     * The glasses' side by side views: the Space's own field of view and IPD, each eye turned out by (IPD / 2) / the
     * glasses' 3D distance, as games get it through Monado since 7 November 2026.
     */
    fun views(context: Context): Views = views(context, 0f)

    /**
     * The views widened by [margin] of each eye's width and height on every side, at the same sharpness: what Pocket XR
     * asks the PC to draw, so a head turned since the PC's pose still has picture to turn into ([viewSize]).
     */
    fun views(context: Context, margin: Float): Views {
        val store = SpaceStore(context)
        val frustum = store.optics(true).pictureFrustum(1920, 1080)
        val tanX = tan(Math.toRadians(frustum.horizontalFovDeg / 2.0)).toFloat()
        val tanY = tan(Math.toRadians(frustum.verticalFovDeg / 2.0)).toFloat()
        val ipdM = store.ipdMm / 1000f
        val shift = (ipdM / 2f) / SpaceRenderer.screenDistanceM
        val mx = margin * tanX
        val my = margin * tanY
        fun eye(out: Float) = floatArrayOf(-atan(tanX - out + mx), atan(tanX + out + mx), atan(tanY + my), -atan(tanY + my))
        return Views(ipdM, eye(shift) + eye(-shift))
    }

    /**
     * [context] must be an Activity: ALVR asks it for the microphone permission at start (Activity.requestPermissions)
     * when it isn't granted yet.
     */
    /** Pixels per eye for views widened by [margin] on every side, at the glasses' sharpness (rounded to 16). */
    fun viewSize(margin: Float): Pair<Int, Int> {
        fun round16(v: Float) = ((v / 16f).toInt() + 1) * 16
        return round16(1920f * (1f + 2f * margin)) to round16(1080f * (1f + 2f * margin))
    }

    /** ALVR's measured delay, ns: how far ahead the head sent to the PC is predicted. 0 before a stream. */
    fun predictionOffsetNs(): Long = nativePredictionOffsetNs()

    fun init(context: Context, viewWidth: Int, viewHeight: Int, refreshHz: Float) =
        nativeInit(context, viewWidth, viewHeight, refreshHz)

    fun destroy() = nativeDestroy()
    fun glResume(width: Int, height: Int, leftTexture: Int, rightTexture: Int) = nativeGlResume(width, height, leftTexture, rightTexture)
    fun glPause() = nativeGlPause()
    fun pollEvent(out: FloatArray): Int = nativePollEvent(out)
    fun hudMessage(): String = nativeHudMessage()
    fun createDecoder(codec: Int, maxBuffering: Float = 2f) = nativeCreateDecoder(codec, maxBuffering)
    fun destroyDecoder() = nativeDestroyDecoder()
    fun startStream(width: Int, height: Int) = nativeStartStream(width, height)
    fun sendViewParams(views: Views) = nativeSendViewParams(views.ipdM, views.fovs)
    fun sendHead(targetNs: Long, head: FloatArray) = nativeSendHead(targetNs, head)
    fun renderLobby(orientation: FloatArray, views: Views) = nativeRenderLobby(orientation, views.ipdM, views.fovs)
    fun renderFrame(nowOrientation: FloatArray, vsyncQueueNs: Long, outPose: FloatArray): Long =
        nativeRenderFrame(nowOrientation, vsyncQueueNs, outPose)

    /**
     * GL thread: the newest decoded frame (both eyes side by side) bound to the external texture [oesTexture] in the
     * caller's own context, or 0 with none new. [outPose] (12 floats) gets the head orientation the PC rendered it with,
     * then each eye's view as left, right, up, down angles.
     */
    fun acquireFrame(oesTexture: Int, outPose: FloatArray): Long = nativeAcquireFrame(oesTexture, outPose)

    /** After drawing the frame [timestampNs]: [vsyncQueueNs] until it is shown, for ALVR's statistics. */
    fun submitFrame(timestampNs: Long, vsyncQueueNs: Long) = nativeSubmitFrame(timestampNs, vsyncQueueNs)

    @JvmStatic private external fun nativeInit(context: Context, viewWidth: Int, viewHeight: Int, refreshHz: Float)
    @JvmStatic private external fun nativeDestroy()
    @JvmStatic private external fun nativeGlResume(width: Int, height: Int, leftTexture: Int, rightTexture: Int)
    @JvmStatic private external fun nativeGlPause()
    @JvmStatic private external fun nativePollEvent(out: FloatArray): Int
    @JvmStatic private external fun nativeHudMessage(): String
    @JvmStatic private external fun nativeCreateDecoder(codec: Int, maxBuffering: Float)
    @JvmStatic private external fun nativeDestroyDecoder()
    @JvmStatic private external fun nativeStartStream(width: Int, height: Int)
    @JvmStatic private external fun nativeSendViewParams(ipdM: Float, fovs: FloatArray)
    @JvmStatic private external fun nativeSendHead(targetNs: Long, head: FloatArray)
    @JvmStatic private external fun nativeRenderLobby(orientation: FloatArray, ipdM: Float, fovs: FloatArray)
    @JvmStatic private external fun nativePredictionOffsetNs(): Long
    @JvmStatic private external fun nativeAcquireFrame(oesTexture: Int, outPose: FloatArray): Long
    @JvmStatic private external fun nativeSubmitFrame(timestampNs: Long, vsyncQueueNs: Long)
    @JvmStatic private external fun nativeRenderFrame(nowOrientation: FloatArray, vsyncQueueNs: Long, outPose: FloatArray): Long
}
