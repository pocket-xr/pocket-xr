package com.hayeco.flipspaces.pcscreen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.ByteArrayOutputStream

/**
 * PC screens: the stream's video, decoded by the phone's hardware decoder straight into [surface].
 *
 * Latency follows Artemis (ClassicOldSong/moonlight-android, GPLv3), MediaCodecHelper.setDecoderLowLatencyOptions:
 * - Android's "low-latency" key;
 * - the vendor keys for the decoder's maker;
 * - realtime priority;
 * - each picture released to the screen as soon as it is decoded, with any older pictures still waiting dropped.
 *
 * A configure that fails is tried again with fewer options ([tries]), as Artemis does.
 * The operating rate is 120, not Short.MAX_VALUE: the Flip 7's Exynos decoders fail with NO_MEMORY above it
 * (found with ALVR, docs/OPENXR.md).
 *
 * moonlight-common-c calls [submitDecodeUnit] once per NAL group. An IDR comes as its parameter sets (SPS, PPS and,
 * for HEVC, VPS) then its picture. The sets are gathered and queued as one codec config buffer before the picture.
 */
class PcScreenDecoder(private var surface: Surface, private val fullRange: Boolean = false) : VideoDecoderRenderer() {
    /** The stream's numbers once a second, for the stats overlay; null with it off. Called on the decoder's threads. */
    @Volatile var onStats: ((String) -> Unit)? = null

    /**
     * Decoded pictures are put on the surface. Off while the stream is held for the Space coming back
     * ([PcScreenParked]): the pictures are still decoded, so the next one shown needs no key frame, but nothing is
     * drawn on the stand-in surface, which has no reader.
     */
    @Volatile var showing = true

    /** Whether the pictures go to [s] now. */
    fun isOn(s: Surface): Boolean = surface === s

    /**
     * The pictures go to [s] from now on, the stream and the decoder kept (MediaCodec.setOutputSurface). False when the
     * decoder is not set up yet or refuses; the caller then starts the stream again as before.
     */
    fun retarget(s: Surface): Boolean {
        val c = codec ?: return false
        return try {
            c.setOutputSurface(s)
            surface = s
            true
        } catch (e: Exception) {
            Log.w(TAG, "decoder: could not move to another surface: $e")
            false
        }
    }

    private var codec: MediaCodec? = null
    private var output: Thread? = null
    private var lastOutputWarnMs = 0L
    @Volatile private var running = false
    private val config = ByteArrayOutputStream()
    private var mime = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Pictures decoded and shown, and dropped because a newer one was ready, for the log. */
    @Volatile var shown = 0L
        private set
    @Volatile var dropped = 0L
        private set

    /**
     * Latency, measured as Moonlight's stats overlay does, so the two can be compared, and logged every 5 s:
     * - host: the host's own capture and encode time, which it reports with each frame;
     * - network: the round trip moonlight-common-c estimates;
     * - receive: from a frame's first packet to its last;
     * - decode: from the whole frame being ready to the decoder having it decoded;
     * - to screen: from the whole frame being ready to Android putting it on this window's surface
     *   (OnFrameRenderedListener).
     * Pocket XR's own step after that, from the panel to the glasses, is not in these numbers.
     */
    private val stats = LatencyStats()
    private val renderedThread = android.os.HandlerThread("PcScreen-rendered").apply { start() }
    private val renderedHandler = android.os.Handler(renderedThread.looper)

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        mime = when {
            format and MoonBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> MediaFormat.MIMETYPE_VIDEO_AV1
            format and MoonBridge.VIDEO_FORMAT_MASK_H265 != 0 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val info = findDecoder(mime) ?: run {
            Log.w(TAG, "no hardware decoder for $mime")
            return -1
        }
        for (tryNumber in 0 until TRIES) {
            val f = MediaFormat.createVideoFormat(mime, width, height)
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, redrawRate)
            // As Moonlight: BT.709, SDR, limited range or (Quality: Sharp) full range, as the stream was asked for
            // (PcScreenActivity).
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, if (fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            lowLatency(f, info.name, tryNumber)
            val c = MediaCodec.createByCodecName(info.name)
            try {
                c.configure(f, surface, null, 0)
                c.setOnFrameRenderedListener({ _, ptsUs, renderNs ->
                    stats.screen((renderNs / 1000 - ptsUs) / 1000.0)
                    // A null handler means the calling thread's looper, and setup runs on moonlight-common-c's thread,
                    // which has none: no callbacks came (phone test, 18 Nov 2026).
                }, renderedHandler)
                codec = c
                Log.i(TAG, "decoder ${info.name} $mime ${width}x$height@$redrawRate, try $tryNumber: $f")
                return 0
            } catch (e: Exception) {
                Log.w(TAG, "decoder ${info.name} configure try $tryNumber failed: $e")
                runCatching { c.release() }
            }
        }
        return -1
    }

    override fun start() {
        val c = codec ?: return
        c.start()
        running = true
        output = Thread({ drain(c) }, "PcScreen-output").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Shows the newest decoded picture at once and drops any older one still waiting, as Artemis's low-latency mode. */
    private fun drain(c: MediaCodec) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                var index = c.dequeueOutputBuffer(info, 50_000)
                if (index < 0) continue
                while (true) {
                    val next = c.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    c.releaseOutputBuffer(index, false)
                    dropped++
                    index = next
                }
                if (!showing) {
                    c.releaseOutputBuffer(index, false)
                    dropped++
                    continue
                }
                c.releaseOutputBuffer(index, true)
                shown++
                stats.decode((System.nanoTime() / 1000 - info.presentationTimeUs) / 1000.0)
                stats.maybeLog()
                stats.maybeStats(onStats)
            } catch (e: IllegalStateException) {
                if (!running) break
                // The surface went under it (the Space closing before the stream is moved off): keep draining, so the
                // pictures carry on once it has a surface again ([retarget]). At most one line a second.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastOutputWarnMs > 1000) {
                    lastOutputWarnMs = now
                    Log.w(TAG, "output: $e")
                }
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun submitDecodeUnit(
        decodeUnitData: ByteArray, decodeUnitLength: Int, decodeUnitType: Int, frameNumber: Int, frameType: Int,
        frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long,
    ): Int {
        val c = codec ?: return MoonBridge.DR_NEED_IDR
        if (decodeUnitType != MoonBridge.BUFFER_TYPE_PICDATA) {
            config.write(decodeUnitData, 0, decodeUnitLength)
            return MoonBridge.DR_OK
        }
        try {
            if (config.size() > 0) {
                if (!queue(c, config.toByteArray(), config.size(), MediaCodec.BUFFER_FLAG_CODEC_CONFIG, receiveTimeUs)) return MoonBridge.DR_NEED_IDR
                config.reset()
            }
            val flags = if (frameType == MoonBridge.FRAME_TYPE_IDR) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            // Each picture's time is when it went into the decoder, on System.nanoTime's clock (the one
            // OnFrameRenderedListener reports in). Moonlight's mapping of moonlight-common-c's enqueue time onto
            // uptimeMillis was off by a millisecond or two here: the phone test read decode -1.2 ms.
            stats.frame(frameHostProcessingLatency.code / 10.0, (enqueueTimeUs - receiveTimeUs) / 1000.0)
            return if (queue(c, decodeUnitData, decodeUnitLength, flags, System.nanoTime() / 1000)) MoonBridge.DR_OK else MoonBridge.DR_NEED_IDR
        } catch (e: IllegalStateException) {
            Log.w(TAG, "input: $e")
            return MoonBridge.DR_NEED_IDR
        }
    }

    private fun queue(c: MediaCodec, data: ByteArray, length: Int, flags: Int, timeUs: Long): Boolean {
        val index = c.dequeueInputBuffer(INPUT_WAIT_US)
        if (index < 0) return false
        val buffer = c.getInputBuffer(index) ?: return false
        buffer.clear()
        buffer.put(data, 0, length)
        c.queueInputBuffer(index, 0, length, timeUs, flags)
        return true
    }

    override fun stop() {
        running = false
        output?.join(500)
        output = null
        codec?.let { runCatching { it.stop() } }
    }

    override fun cleanup() {
        codec?.let { runCatching { it.release() } }
        codec = null
        renderedThread.quitSafely()
        Log.i(TAG, "decoder closed: $shown shown, $dropped dropped")
    }

    // Each NAL group is submitted from moonlight-common-c's receive thread, without its own decode queue.
    override fun getCapabilities(): Int = MoonBridge.CAPABILITY_DIRECT_SUBMIT

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) = Unit

    companion object {
        private const val TAG = "PcScreen"
        private const val TRIES = 5
        private const val INPUT_WAIT_US = 10_000L

        /** The first hardware decoder for [mime], as Moonlight prefers; else any (the emulator has software ones only). */
        fun findDecoder(mime: String, hardwareOnly: Boolean = false): MediaCodecInfo? {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            val hardware = decoders.firstOrNull { Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }
            return if (hardwareOnly) hardware else hardware ?: decoders.firstOrNull()
        }

        /**
         * Whether every codec in [formats] decodes [w] x [h] at [fps] in hardware, by the decoder's own figures: a
         * 120 Hz stream is asked for only when whichever codec the host picks can keep up.
         */
        fun decodes(formats: Int, w: Int, h: Int, fps: Int): Boolean {
            val mimes = buildList {
                add(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (formats and MoonBridge.VIDEO_FORMAT_MASK_H265 != 0) add(MediaFormat.MIMETYPE_VIDEO_HEVC)
                if (formats and MoonBridge.VIDEO_FORMAT_MASK_AV1 != 0) add(MediaFormat.MIMETYPE_VIDEO_AV1)
            }
            return mimes.all { mime ->
                val info = findDecoder(mime, hardwareOnly = true) ?: return@all false
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble())
                }.getOrNull() == true
            }
        }

        /** Whether a hardware HEVC decoder is there, to offer HEVC to the host. */
        fun hevcAvailable(): Boolean = findDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, hardwareOnly = true) != null

        /** Whether a hardware AV1 decoder is there. */
        fun av1Available(): Boolean = findDecoder(MediaFormat.MIMETYPE_VIDEO_AV1, hardwareOnly = true) != null

        /** The formats offered to the host for the chosen codec ([PcScreenStore.codec]); H.264 always, as a fallback. */
        fun formatsFor(codec: String): Int = when (codec) {
            "h264" -> MoonBridge.VIDEO_FORMAT_H264
            "av1" -> MoonBridge.VIDEO_FORMAT_H264 or (if (av1Available()) MoonBridge.VIDEO_FORMAT_AV1_MAIN8 else 0)
            // Auto: every codec the phone decodes in hardware; the host picks the best it can encode. On the Flip 7 all
            // three decoded in 10 to 14 ms (18 Nov 2026), so the host's choice, AV1 at the best quality for its bits
            // where it has it, costs nothing.
            else -> MoonBridge.VIDEO_FORMAT_H264 or (if (hevcAvailable()) MoonBridge.VIDEO_FORMAT_H265 else 0) or
                (if (av1Available()) MoonBridge.VIDEO_FORMAT_AV1_MAIN8 else 0)
        }

        /** Artemis's options, fewer on each [tryNumber] (0 is all of them). */
        internal fun lowLatency(f: MediaFormat, name: String, tryNumber: Int) {
            val n = name.lowercase()
            if (tryNumber < 1) f.setInteger("low-latency", 1)
            if (tryNumber < 2) f.setInteger("vdec-lowlatency", 1)
            if (tryNumber < 3) {
                f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            }
            if (tryNumber < 4) when {
                n.startsWith("omx.qcom") || n.startsWith("c2.qti") -> {
                    f.setInteger("vendor.qti-ext-dec-picture-order.enable", if (n.startsWith("omx.qcom")) 0 else 1)
                    f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                }
                n.startsWith("omx.mtk") || n.startsWith("c2.mtk") -> {
                    f.setInteger("vendor.mtk.vdec.low-latency.mode", 1)
                    f.setInteger("vendor.mtk.vdec.cpu.boost.mode", 1)
                }
                n.startsWith("omx.exynos") || n.startsWith("c2.exynos") -> f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                n.startsWith("omx.hisi") || n.startsWith("c2.hisi") -> {
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
                }
                n.startsWith("omx.nvidia") || n.startsWith("c2.nvidia") -> {
                    f.setInteger("vendor.nvidia.disable-output-reorder", 1)
                    f.setInteger("vendor.low-latency.enable", 1)
                }
            }
        }
    }
}

/** Running sums for [PcScreenDecoder]'s latency line. Each field is touched from one thread at a time, near enough for a log. */
internal class LatencyStats {
    private var frames = 0
    private var hostSum = 0.0
    private var hostMax = 0.0
    private var receiveSum = 0.0
    private var decodeSum = 0.0
    private var decodeMax = 0.0
    private var decoded = 0
    private var screenSum = 0.0
    private var screenMax = 0.0
    private var screened = 0
    private var sinceMs = 0L

    fun frame(hostMs: Double, receiveMs: Double) {
        frames++
        secFrames++
        if (hostMs > 0) { hostSum += hostMs; hostMax = maxOf(hostMax, hostMs) }
        receiveSum += receiveMs
    }

    fun decode(ms: Double) {
        decoded++
        secDecoded++
        secDecodeSum += ms
        decodeSum += ms
        decodeMax = maxOf(decodeMax, ms)
    }

    fun screen(ms: Double) {
        screened++
        screenSum += ms
        screenMax = maxOf(screenMax, ms)
    }

    private var secFrames = 0
    private var secDecodeSum = 0.0
    private var secDecoded = 0
    private var secSinceMs = 0L

    /** One short line a second for the stats overlay: frame rate in, network round trip, decode time. */
    fun maybeStats(sink: ((String) -> Unit)?) {
        if (sink == null) return
        val now = android.os.SystemClock.uptimeMillis()
        if (secSinceMs == 0L) secSinceMs = now
        if (now - secSinceMs < 1000) return
        val rtt = MoonBridge.getEstimatedRttInfo()
        val decode = secDecodeSum / secDecoded.coerceAtLeast(1)
        sink("${(secFrames * 1000 / (now - secSinceMs)).toInt()} fps  ·  ${rtt ushr 32} ms network  ·  ${"%.1f".format(java.util.Locale.ROOT, decode)} ms decode")
        secFrames = 0; secDecodeSum = 0.0; secDecoded = 0; secSinceMs = now
    }

    fun maybeLog() {
        val now = android.os.SystemClock.uptimeMillis()
        if (sinceMs == 0L) sinceMs = now
        if (now - sinceMs < 5000 || frames == 0) return
        val rtt = MoonBridge.getEstimatedRttInfo()
        val f = { v: Double -> "%.1f".format(java.util.Locale.ROOT, v) }
        Log.i(
            "PcScreen",
            "latency over 5 s: ${frames / 5} fps in; host ${f(hostSum / frames)} ms (max ${f(hostMax)}); " +
                "network round trip ${rtt ushr 32} ms (variance ${rtt and 0xFFFFFFFFL}); receive ${f(receiveSum / frames)} ms; " +
                "decode ${f(decodeSum / decoded.coerceAtLeast(1))} ms (max ${f(decodeMax)}); " +
                "to screen ${f(screenSum / screened.coerceAtLeast(1))} ms (max ${f(screenMax)})",
        )
        frames = 0; hostSum = 0.0; hostMax = 0.0; receiveSum = 0.0
        decoded = 0; decodeSum = 0.0; decodeMax = 0.0
        screened = 0; screenSum = 0.0; screenMax = 0.0
        sinceMs = now
    }
}
