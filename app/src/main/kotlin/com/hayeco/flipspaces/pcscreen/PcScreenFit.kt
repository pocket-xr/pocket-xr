package com.hayeco.flipspaces.pcscreen

/** PC screens: the largest size of [srcW] x [srcH]'s shape inside [boxW] x [boxH], so a stream is never stretched. */
object PcScreenFit {
    fun fit(boxW: Int, boxH: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
        if (boxW <= 0 || boxH <= 0 || srcW <= 0 || srcH <= 0) return boxW.coerceAtLeast(0) to boxH.coerceAtLeast(0)
        return if (boxW.toLong() * srcH > boxH.toLong() * srcW) {
            ((boxH.toLong() * srcW / srcH).toInt()) to boxH
        } else {
            boxW to ((boxW.toLong() * srcH / srcW).toInt())
        }
    }

    /** The same fit as [fit], as the picture's corners in the box: u0, v0, u1, v1, each 0..1. */
    fun uv(boxW: Int, boxH: Int, srcW: Int, srcH: Int): FloatArray {
        if (boxW <= 0 || boxH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val (w, h) = fit(boxW, boxH, srcW, srcH)
        val du = (1f - w.toFloat() / boxW) / 2f
        val dv = (1f - h.toFloat() / boxH) / 2f
        return floatArrayOf(du, dv, 1f - du, 1f - dv)
    }
}
