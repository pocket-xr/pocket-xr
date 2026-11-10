package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.tan

/**
 * Steady mode's windows: its canvas has a fixed height, so anything past an edge cannot be reached by looking at it. Going in,
 * the open windows fill the canvas in the rows that give them the best shape ([rows]), each with its buttons inside. Coming
 * out, each goes back where the user had it ([restored]).
 */
object SteadyArrange {
    /** How much of the view is used each way. Height is tighter: the Aura's top and bottom rows were cut at 0.88. */
    const val WIDTH_FIT = 0.96f
    const val HEIGHT_FIT = 0.78f

    /** A share of each cell left between windows. */
    const val GAP_SHARE = 0.04f
    const val ROW_LIMIT = 3

    /** The window shape aimed for, width over height. */
    const val GOOD_SHAPE = 1.4f

    /**
     * 1.3.3 (a tester: "the windows are super wide now"): no window wider than this, width over height. One window used
     * to stretch across the whole Ultrawide canvas; now it keeps a screen's shape, centred in its place.
     */
    const val WIDEST_SHAPE = 16f / 9f

    /** About what a window's buttons take above and below it, degrees. */
    const val BUTTONS_DEG = 3.6f

    /**
     * Windows in each row, top first, the fuller rows on top. Up to [ROW_LIMIT] take one row. Past that the rows are chosen by
     * the shape they give: on the Aura's short, wide canvas two rows made four windows very wide and thin (7 November 2026),
     * with the buttons between them taking much of the height, so one row wins there. A taller view gets two.
     */
    fun rows(n: Int, viewWDeg: Float, viewHDeg: Float): List<Int> {
        if (n <= 0) return emptyList()
        if (n <= ROW_LIMIT) return listOf(n)
        fun miss(count: Int): Float {
            val w = viewWDeg * WIDTH_FIT / ceil(n / count.toFloat())
            val h = (viewHDeg * HEIGHT_FIT / count - BUTTONS_DEG).coerceAtLeast(1f)
            return abs(ln(w / h / GOOD_SHAPE))
        }
        val count = (1..ROW_LIMIT).minByOrNull(::miss) ?: 1
        val base = n / count
        return List(count) { i -> base + if (i < n % count) 1 else 0 }
    }

    /** [layout]'s windows filling a [viewWDeg] by [viewHDeg] canvas centred on [yawDeg], [pitchDeg]. */
    fun arrange(layout: SpaceLayout, yawDeg: Float, pitchDeg: Float, viewWDeg: Float, viewHDeg: Float): SpaceLayout {
        val rows = rows(layout.panels.size, viewWDeg, viewHDeg)
        if (rows.isEmpty()) return layout
        val tanW = tan(viewWDeg * WIDTH_FIT * DEG / 2f)
        val tanH = tan(viewHDeg * HEIGHT_FIT * DEG / 2f)
        // Even on the flat canvas, so the edges are spaced by tangent, not by angle.
        fun edge(i: Int, count: Int, t: Float) = atan(t * (2f * i / count - 1f)) / DEG
        val out = ArrayList<SpacePanel>(layout.panels.size)
        var next = 0
        rows.forEachIndexed { r, cols ->
            val top = -edge(r, rows.size, tanH)
            val bottom = -edge(r + 1, rows.size, tanH)
            val cellH = (top - bottom) * (1f - GAP_SHARE)
            for (c in 0 until cols) {
                val left = edge(c, cols, tanW)
                val right = edge(c + 1, cols, tanW)
                var p = layout.panels[next++].copy(
                    yawDeg = wrapDegrees(yawDeg + (left + right) / 2f),
                    pitchDeg = pitchDeg + (top + bottom) / 2f,
                    distanceM = PanelLimits.DEFAULT_DISTANCE,
                    widthDeg = (right - left) * (1f - GAP_SHARE),
                    heightDeg = cellH,
                    curve = 0f,
                )
                // Twice: the buttons' share grows a little as the window shrinks.
                repeat(2) { p = p.copy(heightDeg = cellH / (1f + WindowControls.aboveShare(p) + WindowControls.belowShare(p))) }
                // Never wider than a screen's shape: narrower, still centred in its cell.
                repeat(2) {
                    val shape = PanelDisplaySize.aspect(p)
                    if (shape > WIDEST_SHAPE) p = p.copy(widthDeg = p.widthDeg * WIDEST_SHAPE / shape)
                }
                // Centred with its buttons: a window with none below sits a little lower in its cell.
                val above = WindowControls.aboveShare(p)
                val below = WindowControls.belowShare(p)
                p = p.copy(pitchDeg = p.pitchDeg - (above - below) * p.heightDeg / 2f)
                out += p.sizedByUser()
            }
        }
        return layout.copy(panels = out, wrap = null)
    }

    /** [current] with each window that was in [saved] back as it was there; one opened since stays where it is. */
    fun restored(current: SpaceLayout, saved: SpaceLayout): SpaceLayout =
        current.copy(panels = current.panels.map { saved.panel(it.id) ?: it }, wrap = saved.wrap)
}
