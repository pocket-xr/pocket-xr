package com.hayeco.flipspaces.pcscreen

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PC screens: frame rate, bitrate and reconnect timing, worked out apart from Android so they can be tested.
 */
object PcScreenQuality {
    /** Frame rate choices ([PcScreenStore.fps]): "auto" is 120 where the phone decodes the stream's size at 120. */
    val FPS = listOf("auto", "60", "120")

    /** Quality choices ([PcScreenStore.quality]) and their bitrate factor. */
    val QUALITY = listOf("auto", "sharp", "saver")

    fun fps(choice: String, decodes120: Boolean): Int = when (choice) {
        "60" -> 60
        "120" -> 120
        else -> if (decodes120) 120 else 60
    }

    /**
     * Bitrate in kbps for a [w] x [h] stream at [fps]: 40 Mbps at 1080p60, the proof's rate that Mike found sharp
     * (18 Nov 2026), scaled with the pixels and with the frame rate to the power 0.6 (later frames of a desktop change
     * little, so twice the frames does not need twice the bits). "sharp" is 1.6 times, "saver" half. 8 to 150 Mbps.
     */
    fun bitrateKbps(w: Int, h: Int, fps: Int, quality: String): Int {
        val pixels = (w.toDouble() * h) / (1920.0 * 1080.0)
        val rate = (fps / 60.0).pow(0.6)
        val factor = when (quality) {
            "sharp" -> 1.6
            "saver" -> 0.5
            else -> 1.0
        }
        return (BASE_KBPS * pixels * rate * factor).roundToInt().coerceIn(MIN_KBPS, MAX_KBPS)
    }

    /**
     * The wait before reconnect try [attempt] (0 first): 1, 2, 4, 8 s, then every 10 s; null once [GIVE_UP_MS] has
     * passed since the stream was lost ([lostForMs]).
     */
    fun reconnectDelayMs(attempt: Int, lostForMs: Long): Long? {
        if (lostForMs >= GIVE_UP_MS) return null
        return if (attempt < 4) 1000L shl attempt else 10_000L
    }

    const val BASE_KBPS = 40_000.0
    const val MIN_KBPS = 8_000
    const val MAX_KBPS = 150_000
    const val GIVE_UP_MS = 3 * 60_000L
}
