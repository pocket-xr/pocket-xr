package com.hayeco.flipspaces.hands.uvc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hayeco.flipspaces.core.hands.DetectionScale
import com.hayeco.flipspaces.core.uvc.MjpegTableInserter
import com.hayeco.flipspaces.core.uvc.UvcChoice
import com.hayeco.flipspaces.core.uvc.UvcFormatKind
import com.hayeco.flipspaces.core.uvc.UvcVideoFrame
import com.hayeco.flipspaces.core.uvc.XrealUltraCamera
import com.hayeco.flipspaces.core.uvc.YuvConverter
import java.io.Closeable

/**
 * Turns UVC frames into Bitmaps for MediaPipe, shrunk with DetectionScale (1920x1080 becomes
 * 960x540). MJPEG and YUV decoders reuse one Bitmap: the result is valid until the next
 * [decode] call, so take a copy to keep it.
 */
interface FrameDecoder : Closeable {
    fun decode(frame: UvcVideoFrame): Bitmap?

    /**
     * True for H.264 and HEVC, whose frames depend on earlier ones: every frame must be decoded,
     * in order, so none can be skipped as stale before decoding.
     */
    val needsEveryFrame: Boolean get() = false

    override fun close() = Unit

    companion object {
        fun forChoice(choice: UvcChoice, xrealUltra: Boolean = false): FrameDecoder {
            val w = choice.frame.width
            val h = choice.frame.height
            return when {
                xrealUltra -> UltraDecoder()
                choice.format.kind == UvcFormatKind.MJPEG -> MjpegDecoder(w, h)
                choice.format.fourcc == "YUY2" -> YuvDecoder(w, h, nv12 = false)
                choice.format.fourcc == "NV12" -> YuvDecoder(w, h, nv12 = true)
                choice.format.kind == UvcFormatKind.H264 || choice.format.fourcc == "H264" -> CodecDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                choice.format.fourcc == "HEVC" || choice.format.fourcc == "H265" -> CodecDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
                else -> throw IllegalArgumentException("No decoder for ${choice.format.label}")
            }
        }
    }
}

private class MjpegDecoder(width: Int, height: Int) : FrameDecoder {
    private val tables = MjpegTableInserter()
    private val options = BitmapFactory.Options().apply {
        inSampleSize = DetectionScale.sampleSize(width, height)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }

    override fun decode(frame: UvcVideoFrame): Bitmap? {
        val jpeg = tables.fix(frame.data, frame.size)
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg.data, 0, jpeg.length, options)
        } catch (_: IllegalArgumentException) {
            // The reused bitmap no longer fits (the stream changed size): decode into a new one.
            options.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg.data, 0, jpeg.length, options)
        }
        // Decode the next frame into the same pixels instead of a new 2 MB bitmap.
        if (bitmap != null) options.inBitmap = bitmap
        return bitmap
    }
}

private class YuvDecoder(private val width: Int, private val height: Int, private val nv12: Boolean) : FrameDecoder {
    private val step = DetectionScale.sampleSize(width, height)
    private val pixels = IntArray((width / step) * (height / step))
    private val bitmap = Bitmap.createBitmap(width / step, height / step, Bitmap.Config.ARGB_8888)

    override fun decode(frame: UvcVideoFrame): Bitmap? {
        val argb = if (nv12) {
            YuvConverter.nv12ToArgb(frame.data, width, height, step, pixels)
        } else {
            YuvConverter.yuy2ToArgb(frame.data, width, height, step, pixels)
        }
        bitmap.setPixels(argb, 0, width / step, 0, 0, width / step, height / step)
        return bitmap
    }
}

/**
 * H.264 or HEVC frame based streams through MediaCodec, which uses the phone's hardware
 * decoder. Frames carry their parameter sets in band (Annex B), so no codec config is set.
 */
private class CodecDecoder(mime: String, private val width: Int, private val height: Int) : FrameDecoder {
    override val needsEveryFrame: Boolean get() = true

    private val codec = MediaCodec.createDecoderByType(mime).apply {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        configure(format, null, null, 0)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    override fun decode(frame: UvcVideoFrame): Bitmap? {
        val input = codec.dequeueInputBuffer(10_000)
        if (input >= 0) {
            val buffer = codec.getInputBuffer(input) ?: return null
            buffer.clear()
            if (frame.size > buffer.capacity()) {
                codec.queueInputBuffer(input, 0, 0, ptsUs, 0)
                return null
            }
            buffer.put(frame.data, 0, frame.size)
            ptsUs += 33_333
            codec.queueInputBuffer(input, 0, frame.size, ptsUs, 0)
        }
        var latest: Bitmap? = null
        while (true) {
            val output = codec.dequeueOutputBuffer(info, 2_000)
            if (output < 0) break
            codec.getOutputImage(output)?.use { image ->
                val step = DetectionScale.sampleSize(image.width, image.height)
                val (y, u, v) = image.planes
                val yb = ByteArray(y.buffer.remaining()).also { y.buffer.get(it) }
                val ub = ByteArray(u.buffer.remaining()).also { u.buffer.get(it) }
                val vb = ByteArray(v.buffer.remaining()).also { v.buffer.get(it) }
                val argb = YuvConverter.planarToArgb(
                    y = yb, yOffset = 0, yRowStride = y.rowStride,
                    u = ub, uOffset = 0, v = vb, vOffset = 0,
                    uvRowStride = u.rowStride, uvPixelStride = u.pixelStride,
                    width = image.width, height = image.height, step = step,
                )
                latest = Bitmap.createBitmap(argb, image.width / step, image.height / step, Bitmap.Config.ARGB_8888)
            }
            codec.releaseOutputBuffer(output, false)
        }
        return latest
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}

/**
 * 1.6.9: the XREAL Air 2 Ultra's grey tracking cameras ([XrealUltraCamera]): only camera 0's frames are used (the
 * other camera's come in between and are skipped), unscrambled into a 480x640 grey picture and drawn as ARGB for
 * MediaPipe. Untested on the Ultra: the project has none.
 */
private class UltraDecoder : FrameDecoder {
    private val grey = ByteArray(XrealUltraCamera.PICTURE_BYTES)
    private val other = ByteArray(XrealUltraCamera.PICTURE_BYTES)
    private val pixels = IntArray(XrealUltraCamera.PICTURE_BYTES)
    private val bitmap = Bitmap.createBitmap(XrealUltraCamera.OUT_WIDTH, XrealUltraCamera.OUT_HEIGHT, Bitmap.Config.ARGB_8888)

    /** 1.6.11: when the telemetry doesn't give the byte order, the first frames vote by [XrealUltraCamera.smoothness]. */
    private var swapVotes = 0
    private var plainVotes = 0
    private var swapped: Boolean? = null

    override fun decode(frame: UvcVideoFrame): Bitmap? {
        if (XrealUltraCamera.cameraOf(frame.data, frame.size) != XrealUltraCamera.CAMERA_0) return null
        val order = XrealUltraCamera.pairSwapped(frame.data, frame.size) ?: swapped ?: vote(frame) ?: return null
        if (!XrealUltraCamera.descramble(frame.data, frame.size, grey, camera0 = true, swapped = order)) return null
        for (i in grey.indices) {
            val v = grey[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, XrealUltraCamera.OUT_WIDTH, 0, 0, XrealUltraCamera.OUT_WIDTH, XrealUltraCamera.OUT_HEIGHT)
        return bitmap
    }

    /** One frame's vote; the order once 5 frames agree more one way than the other (or after 9), else null. */
    private fun vote(frame: UvcVideoFrame): Boolean? {
        XrealUltraCamera.descramble(frame.data, frame.size, grey, camera0 = true, swapped = true)
        XrealUltraCamera.descramble(frame.data, frame.size, other, camera0 = true, swapped = false)
        val s = XrealUltraCamera.smoothness(grey)
        val p = XrealUltraCamera.smoothness(other)
        if (s < p) swapVotes++ else plainVotes++
        com.hayeco.flipspaces.Log.i("FlipSpacesHands", "Ultra byte order vote: pair-swapped $s, as sent $p (lower is smoother)")
        if (swapVotes >= 5 || plainVotes >= 5 || swapVotes + plainVotes >= 9) {
            swapped = swapVotes >= plainVotes
            com.hayeco.flipspaces.Log.i("FlipSpacesHands", "Ultra byte order: ${if (swapped == true) "pair-swapped" else "as sent"} ($swapVotes to $plainVotes)")
        }
        return swapped
    }
}
