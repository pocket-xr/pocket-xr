package com.hayeco.flipspaces.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandThresholds
import com.hayeco.flipspaces.core.hands.NpuHands
import com.hayeco.flipspaces.core.hands.PalmIdleRate
import java.io.Closeable

/**
 * 0.5.43 (Mike: "build NPU hand tracking into Pocket XR"): MediaPipe's two hand models on the Exynos 2500's NPU
 * through LiteRT, with the palm decoding, the hand crop and the landmark mapping ours ([NpuHands]). On the Flip7 the
 * palm detector takes 3.3 ms there and the landmark model 2.4 ms (measured with a small benchmark app), against 46 to 55 ms each on the CPU; the
 * NPU leaves the GPU to the Space.
 *
 * Tracking as MediaPipe's live stream: each hand followed from the last frame's landmarks; the palm detector runs only
 * while fewer than the wanted hands are followed. The models were compiled ahead of time for the E9955 and
 * run through LiteRT's Samsung dispatch (built from LiteRT v2.3.2, in jniLibs with that build's libLiteRt.so).
 *
 * Not thread safe: [process] from one thread at a time.
 */
class NpuHandEngine private constructor(
    private val env: Environment,
    private val palm: CompiledModel,
    private val land: CompiledModel,
) : Closeable {
    private val palmIn: List<TensorBuffer> = palm.createInputBuffers()
    private val palmOut: List<TensorBuffer> = palm.createOutputBuffers()
    private val landIn: List<TensorBuffer> = land.createInputBuffers()
    private val landOut: List<TensorBuffer> = land.createOutputBuffers()

    private val palmBitmap = Bitmap.createBitmap(NpuHands.PALM_SIZE, NpuHands.PALM_SIZE, Bitmap.Config.ARGB_8888)
    private val cropBitmap = Bitmap.createBitmap(NpuHands.LANDMARK_SIZE, NpuHands.LANDMARK_SIZE, Bitmap.Config.ARGB_8888)
    private val palmCanvas = Canvas(palmBitmap)
    private val cropCanvas = Canvas(cropBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(NpuHands.LANDMARK_SIZE * NpuHands.LANDMARK_SIZE)
    private val palmFloats = FloatArray(NpuHands.PALM_SIZE * NpuHands.PALM_SIZE * 3)
    private val cropFloats = FloatArray(NpuHands.LANDMARK_SIZE * NpuHands.LANDMARK_SIZE * 3)
    private val matrix = Matrix()

    // 0.5.55: per-frame garbage cut: the palm detector's target rect, and the two crop lists below, made once.
    private val palmRect = Rect(0, 0, NpuHands.PALM_SIZE, NpuHands.PALM_SIZE)

    /** The hands followed into the next frame; [spare] is filled for the frame after and the two swap. */
    private var tracked = ArrayList<NpuHands.Roi>(2)
    private var spare = ArrayList<NpuHands.Roi>(2)

    /** The crop for the next frame from the last [landmark] that found a hand (instead of a Pair for each hand). */
    private var landmarkRoi: NpuHands.Roi? = null

    /** 0.5.55: how often the palm detector runs ([PalmIdleRate]): slower while one hand is followed and no other comes. */
    val palmRate = PalmIdleRate()

    /** Timings of the last [process], ms: palm detector (0 when it did not run) and landmark models together. */
    var lastPalmMs = 0.0
        private set
    var lastLandmarkMs = 0.0
        private set
    var palmRuns = 0L
        private set
    var frames = 0L
        private set

    fun reset() {
        tracked.clear()
        palmRate.reset()
    }

    /** Hands in [square] (the frame cut square, as MediaPipe gets it), normalised to it, MediaPipe's order. */
    fun process(square: Bitmap, numHands: Int, t: HandThresholds, timestampMs: Long): List<HandFrame> {
        frames++
        // 0.5.55: no list for a frame without a hand (most idle frames); index loops, no iterators or Pairs.
        var out: ArrayList<HandFrame>? = null
        val next = spare
        next.clear()
        val t0 = System.nanoTime()
        for (i in 0 until tracked.size) {
            if ((out?.size ?: 0) >= numHands) break
            val hand = landmark(square, tracked[i], t, timestampMs) ?: continue
            (out ?: ArrayList<HandFrame>(numHands).also { out = it }).add(hand)
            next.add(landmarkRoi!!)
        }
        var palmNs = 0L
        val found = out?.size ?: 0
        // 0.5.55: the palm detector at [palmRate]'s pace (it was every frame while fewer than the wanted hands were
        // followed): off with every hand followed, as before, and slower once one hand has been followed 2 s with no other.
        if (palmRate.shouldRun(timestampMs, found, numHands)) {
            val p0 = System.nanoTime()
            val palms = detectPalms(square, t.detection, numHands)
            palmNs = System.nanoTime() - p0
            palmRuns++
            for (pi in palms.indices) {
                if ((out?.size ?: 0) >= numHands) break
                val roi = NpuHands.roiFromPalm(palms[pi])
                if (sameAsAny(next, roi)) continue
                val hand = landmark(square, roi, t, timestampMs)
                if (hand == null) {
                    // A palm the landmark model did not take yet (a hand coming into view): the search keeps its full rate.
                    palmRate.palmSeen(timestampMs)
                    continue
                }
                (out ?: ArrayList<HandFrame>(numHands).also { out = it }).add(hand)
                next.add(landmarkRoi!!)
            }
        }
        palmRate.takeLine(timestampMs)?.let { Log.i(TAG, it) }
        spare = tracked
        tracked = next
        lastPalmMs = palmNs / 1e6
        lastLandmarkMs = (System.nanoTime() - t0 - palmNs) / 1e6
        return out ?: emptyList()
    }

    private fun sameAsAny(rois: List<NpuHands.Roi>, roi: NpuHands.Roi): Boolean {
        for (i in rois.indices) if (NpuHands.sameHand(rois[i], roi)) return true
        return false
    }

    private fun detectPalms(square: Bitmap, minScore: Float, maxPalms: Int): List<NpuHands.Palm> {
        palmCanvas.drawBitmap(square, null, palmRect, paint)
        toFloats(palmBitmap, NpuHands.PALM_SIZE, palmFloats)
        palmIn[0].writeFloat(palmFloats)
        palm.run(palmIn, palmOut)
        // 0.5.55: the scores first (8 KB); the boxes (145 KB, a new array each read) only when a palm is there to decode.
        val scores = palmOut[1].readFloat()
        if (!NpuHands.anyPalm(scores, minScore)) return emptyList()
        val boxes = palmOut[0].readFloat()
        return NpuHands.suppress(NpuHands.decode(boxes, scores, minScore), maxPalms)
    }

    /**
     * The landmark model on [roi]'s crop of [square]: the hand, or null when no hand is there. 0.5.55: its crop for the
     * next frame is left in [landmarkRoi].
     */
    private fun landmark(square: Bitmap, roi: NpuHands.Roi, t: HandThresholds, timestampMs: Long): HandFrame? {
        val sq = square.width.toFloat()
        val n = NpuHands.LANDMARK_SIZE.toFloat()
        matrix.reset()
        matrix.postTranslate(-roi.cx * sq, -roi.cy * sq)
        matrix.postRotate(-Math.toDegrees(roi.rotation.toDouble()).toFloat())
        matrix.postScale(n / (roi.w * sq), n / (roi.h * sq))
        matrix.postTranslate(n / 2f, n / 2f)
        // Outside the frame is black, as MediaPipe's crop (border zero).
        cropCanvas.drawColor(Color.BLACK)
        cropCanvas.drawBitmap(square, matrix, paint)
        toFloats(cropBitmap, NpuHands.LANDMARK_SIZE, cropFloats)
        landIn[0].writeFloat(cropFloats)
        land.run(landIn, landOut)
        // Both heads end in a sigmoid inside the model: presence and handedness are probabilities already (a blank crop
        // reads 0.007 presence).
        val presence = landOut[1].readFloat()[0]
        if (presence < t.presence) return null
        val points = NpuHands.landmarks(landOut[0].readFloat(), roi)
        val right = landOut[2].readFloat()[0]
        val hand = HandFrame(
            landmarks = points,
            timestampMs = timestampMs,
            // MediaPipe's handedness: the model's probability of a right hand.
            rightHand = right > 0.5f,
            score = maxOf(right, 1f - right),
            world = NpuHands.world(landOut[3].readFloat(), roi),
        )
        landmarkRoi = NpuHands.roiFromLandmarks(points)
        return hand
    }

    /** RGB 0..1, as MediaPipe's image to tensor gives both models. */
    private fun toFloats(b: Bitmap, side: Int, out: FloatArray) {
        b.getPixels(pixels, 0, side, 0, 0, side, side)
        var j = 0
        // 0.5.55: a table of the same n / 255f quotients, not three divisions a pixel (150 000 a palm search).
        val unit = UNIT
        for (i in 0 until side * side) {
            val c = pixels[i]
            out[j++] = unit[(c shr 16) and 0xFF]
            out[j++] = unit[(c shr 8) and 0xFF]
            out[j++] = unit[c and 0xFF]
        }
    }

    override fun close() {
        runCatching { palm.close() }
        runCatching { land.close() }
        runCatching { env.close() }
    }

    companion object {
        private const val TAG = "FlipSpacesHands"
        const val PALM_ASSET = "npu/hand_detector_e9955.tflite"
        const val LANDMARK_ASSET = "npu/hand_landmarks_e9955.tflite"

        /** 0.5.55: n / 255f for every channel value n, the very quotients [toFloats] divided out before. */
        private val UNIT = FloatArray(256) { it / 255f }

        /** The engine, or null (logged) when this phone or build cannot run it: then MediaPipe runs as before. */
        fun create(context: Context): NpuHandEngine? {
            if (android.os.Build.SOC_MODEL != "s5e9955") {
                Log.i(TAG, "Hands on the NPU: not this phone (SoC ${android.os.Build.SOC_MODEL}; the models are compiled for the Exynos 2500), MediaPipe runs")
                return null
            }
            val t0 = SystemClock.uptimeMillis()
            return try {
                val libDir = context.applicationInfo.nativeLibraryDir
                val env = Environment.create(mapOf(Environment.Option.DispatchLibraryDir to libDir))
                val options = CompiledModel.Options(Accelerator.NPU)
                val palm = CompiledModel.create(context.assets, PALM_ASSET, options, env)
                val land = CompiledModel.create(context.assets, LANDMARK_ASSET, options, env)
                NpuHandEngine(env, palm, land).also {
                    Log.i(TAG, "Hands on the NPU: both models loaded in ${SystemClock.uptimeMillis() - t0} ms (Samsung dispatch, Exynos 2500)")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Hands on the NPU unavailable (${e.javaClass.simpleName}: ${e.message}); MediaPipe runs", e)
                null
            }
        }
    }
}
