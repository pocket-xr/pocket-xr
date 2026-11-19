package com.hayeco.flipspaces.pcscreen

/**
 * PC screens: the stream's size when the PC makes a virtual display for it (Apollo): the window's own shape and
 * pixels, so the picture fills the window with no bars and an ultrawide or portrait window is an ultrawide or
 * portrait screen on the PC.
 *
 * Even and a multiple of 8 (encoders work in blocks); at most 3840 a side and 3840 x 2160 in all (beyond that the
 * phone's decoder and Wi-Fi are the limit); at least 640 x 360 in area, scaled up whole if smaller.
 */
object PcScreenShape {
    const val MAX_SIDE = 3840
    const val MAX_AREA = 3840L * 2160L
    const val MIN_AREA = 640L * 360L

    fun forWindow(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return PcScreenLink.STREAM_W to PcScreenLink.STREAM_H
        var s = 1.0
        s = minOf(s, MAX_SIDE.toDouble() / maxOf(w, h))
        s = minOf(s, Math.sqrt(MAX_AREA.toDouble() / (w.toLong() * h)))
        if (w.toLong() * h < MIN_AREA) s = Math.sqrt(MIN_AREA.toDouble() / (w.toLong() * h))
        val sw = ((w * s).toInt() / 8 * 8).coerceAtLeast(8)
        val sh = ((h * s).toInt() / 8 * 8).coerceAtLeast(8)
        return sw to sh
    }

    /** Whether a window of [w] x [h] is far enough from the stream's shape to start the stream again at its own. */
    fun reshapeNeeded(w: Int, h: Int, streamW: Int, streamH: Int): Boolean {
        if (w <= 0 || h <= 0 || streamW <= 0 || streamH <= 0) return false
        val (nw, nh) = forWindow(w, h)
        return Math.abs(nw - streamW) > 16 || Math.abs(nh - streamH) > 16
    }
}
