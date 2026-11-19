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
}
