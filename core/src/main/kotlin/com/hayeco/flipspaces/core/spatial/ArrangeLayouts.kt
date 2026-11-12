package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.5.32 (Mike: "more premade Arrange Windows options instead of just one; it should know how many windows the user
 * has active"): the layouts the Menu offers for the number of windows open ([forCount]), each placing every window on
 * a cylinder round the head, its shape kept. "Main" is the focused window (else the newest); the others keep their
 * left to right order.
 */
object ArrangeLayouts {
    data class Choice(val kind: ArrangeKind, val label: String)

    /** The Menu's choices for [n] windows, the most useful first. */
    fun forCount(n: Int): List<Choice> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(Choice(ArrangeKind.CENTER, UiText.get("spatial_arrange_center", "Center")), Choice(ArrangeKind.THEATER, UiText.get("spatial_arrange_theater_big", "Theater (big screen)")))
        n == 2 -> listOf(
            Choice(ArrangeKind.SIDE_BY_SIDE, UiText.get("spatial_arrange_side_by_side", "Side by side")),
            Choice(ArrangeKind.MAIN_SIDE, UiText.get("spatial_arrange_main_side", "Main + side")),
            Choice(ArrangeKind.STACKED, UiText.get("spatial_arrange_stacked", "Stacked")),
        )
        n == 3 -> listOf(
            Choice(ArrangeKind.ACROSS, UiText.get("spatial_arrange_three_across", "Three across")),
            Choice(ArrangeKind.MAIN_SIDE, UiText.get("spatial_arrange_main_two_stacked", "Main + two stacked")),
            Choice(ArrangeKind.CIRCLE, UiText.get("spatial_arrange_focus_center", "Focus center")),
        )
        n == 4 -> listOf(
            Choice(ArrangeKind.GRID, UiText.get("spatial_arrange_grid_2x2", "2 × 2 grid")),
            Choice(ArrangeKind.MAIN_SIDE, UiText.get("spatial_arrange_main_three_stacked", "Main + three stacked")),
            Choice(ArrangeKind.MAIN_STRIP, UiText.get("spatial_arrange_main_strip_below", "Main + strip below")),
            Choice(ArrangeKind.ACROSS, UiText.get("spatial_arrange_four_across", "Four across")),
        )
        else -> listOf(
            Choice(ArrangeKind.GRID, UiText.get("spatial_arrange_grid", "Grid")),
            Choice(ArrangeKind.MAIN_STRIP, UiText.get("spatial_arrange_main_strip_below", "Main + strip below")),
            Choice(ArrangeKind.CIRCLE, UiText.get("spatial_arrange_focus_center", "Focus center")),
            Choice(ArrangeKind.MAIN_SIDE, UiText.get("spatial_arrange_main_column", "Main + column")),
            Choice(ArrangeKind.WRAPAROUND, UiText.get("spatial_arrange_all_around_you", "All around you")),
        )
    }

    const val R = 3f
    const val GAP_M = 0.3f
    const val ROW_GAP_M = 0.2f

    /** Where [kind] puts [panels] round [headYawDeg] (the gaze, at eye level), [mainId] the main window. */
    fun place(kind: ArrangeKind, panels: List<SpacePanel>, headYawDeg: Float, mainId: Int?): List<SpacePanel> {
        if (panels.isEmpty()) return emptyList()
        val main = panels.firstOrNull { it.id == mainId } ?: panels.last()
        // The others left to right as they are now, seen from the gaze.
        val others = panels.filter { it.id != main.id }.sortedBy { wrapDegrees(it.yawDeg - headYawDeg) }
        val n = panels.size
        return when (kind) {
            ArrangeKind.COLLECT -> Arrange.collect(panels, headYawDeg)
            ArrangeKind.CIRCLE -> Arrange.circle(panels, headYawDeg, mainId)
            ArrangeKind.CENTER -> mainStrip(main, others, headYawDeg, mainW = 2.0f, r = 2.6f)
            ArrangeKind.THEATER -> mainStrip(main, others, headYawDeg, mainW = 4.2f, r = 5f)
            ArrangeKind.SIDE_BY_SIDE, ArrangeKind.ACROSS -> {
                val w = when (n) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.1f; 4 -> 0.95f; else -> 0.8f }
                // The main window in the middle of the row (left of centre for an even count).
                val order = others.toMutableList().apply { add(size / 2, main) }
                row(order.map { it to w }, 0f, headYawDeg, R)
            }
            ArrangeKind.STACKED -> column(listOf(main) + others, 1.3f, 0f, headYawDeg, R)
            ArrangeKind.MAIN_SIDE -> {
                val sideW = when (others.size) { 0 -> 0f; 1 -> 1.0f; 2 -> 0.9f; 3 -> 0.75f; else -> 0.6f }
                val mainW = 1.8f
                val mainX = -(sideW + GAP_M) / 2f
                val out = mutableListOf(at(main, mainX, 0f, mainW, headYawDeg, R))
                if (others.isNotEmpty()) out += column(others, sideW, mainX + mainW / 2f + GAP_M + sideW / 2f, headYawDeg, R)
                out
            }
            ArrangeKind.GRID -> {
                val cols = ceil(sqrt(n.toDouble())).toInt()
                val w = if (cols <= 2) 1.25f else 1.0f
                grid((listOf(main) + others).chunked(cols), w, headYawDeg, R)
            }
            ArrangeKind.MAIN_STRIP -> mainStrip(main, others, headYawDeg, mainW = 1.8f, r = R)
            ArrangeKind.WRAPAROUND -> wraparound(main, others, headYawDeg)
            // 0.5.88: the windows stay where they are, held to move together ([SpatialPointer.holdAll]).
            ArrangeKind.ADJUST_ALL -> panels
        }
    }

    private fun aspect(p: SpacePanel): Float = p.heightM / p.widthM.coerceAtLeast(1e-3f)

    /** [p] centred [xM] along the arc and [yM] up, [wM] wide, at radius [r]. */
    private fun at(p: SpacePanel, xM: Float, yM: Float, wM: Float, headYawDeg: Float, r: Float): SpacePanel {
        val (wDeg, hDeg) = PadWindowMath.anglesKeepingShape(wM, wM * aspect(p), r)
        return p.copy(
            yawDeg = wrapDegrees(headYawDeg + xM / r * RAD),
            pitchDeg = (asin((yM / r).coerceIn(-0.99f, 0.99f)) * RAD).coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
            distanceM = r,
            widthDeg = wDeg,
            heightDeg = hDeg,
        )
    }

    /** The windows' heights at radius [r] once placed at width [w] (the angular limits applied). */
    private fun placedHeight(p: SpacePanel, w: Float, r: Float): Pair<Float, Float> {
        val (wDeg, hDeg) = PadWindowMath.anglesKeepingShape(w, w * aspect(p), r)
        return 2f * r * tan(wDeg * DEG / 2f) to 2f * r * tan(hDeg * DEG / 2f)
    }

    private fun row(items: List<Pair<SpacePanel, Float>>, yM: Float, headYawDeg: Float, r: Float): List<SpacePanel> {
        val widths = items.map { (p, w) -> placedHeight(p, w, r).first }
        var left = -(widths.sum() + GAP_M * (items.size - 1)) / 2f
        return items.mapIndexed { i, (p, w) ->
            val x = left + widths[i] / 2f
            left += widths[i] + GAP_M
            at(p, x, yM, w, headYawDeg, r)
        }
    }

    private fun column(items: List<SpacePanel>, w: Float, xM: Float, headYawDeg: Float, r: Float): List<SpacePanel> {
        val heights = items.map { placedHeight(it, w, r).second }
        var top = (heights.sum() + ROW_GAP_M * (items.size - 1)) / 2f
        return items.mapIndexed { i, p ->
            val y = top - heights[i] / 2f
            top -= heights[i] + ROW_GAP_M
            at(p, xM, y, w, headYawDeg, r)
        }
    }

    private fun grid(rows: List<List<SpacePanel>>, w: Float, headYawDeg: Float, r: Float): List<SpacePanel> {
        val rowHeights = rows.map { rowPanels -> rowPanels.maxOf { placedHeight(it, w, r).second } }
        var top = (rowHeights.sum() + ROW_GAP_M * (rows.size - 1)) / 2f
        return rows.flatMapIndexed { i, rowPanels ->
            val y = top - rowHeights[i] / 2f
            top -= rowHeights[i] + ROW_GAP_M
            row(rowPanels.map { it to w }, y, headYawDeg, r)
        }
    }

    /** The main window big, the others in a row of small ones under it, the whole block centred at eye level. */
    private fun mainStrip(main: SpacePanel, others: List<SpacePanel>, headYawDeg: Float, mainW: Float, r: Float): List<SpacePanel> {
        val mainH = placedHeight(main, mainW, r).second
        if (others.isEmpty()) return listOf(at(main, 0f, 0f, mainW, headYawDeg, r))
        val stripW = if (others.size <= 3) 0.8f else 0.65f
        val stripH = others.maxOf { placedHeight(it, stripW, r).second }
        val total = mainH + ROW_GAP_M + stripH
        val mainY = total / 2f - mainH / 2f
        val stripY = mainY - mainH / 2f - ROW_GAP_M - stripH / 2f
        return listOf(at(main, 0f, mainY, mainW, headYawDeg, r)) + row(others.map { it to stripW }, stripY, headYawDeg, r)
    }

    /** Every window spaced evenly round the head, the main one ahead, the next ones right then left. */
    private fun wraparound(main: SpacePanel, others: List<SpacePanel>, headYawDeg: Float): List<SpacePanel> {
        val n = others.size + 1
        val stepDeg = 360f / n
        val r = 2.5f
        // As wide as fits its share of the circle with 8 degrees between, at most 1.6 m.
        val maxW = minOf(1.6f, 2f * r * tan((stepDeg - 8f).coerceAtLeast(10f) * DEG / 2f))
        val ordered = listOf(main) + others
        return ordered.mapIndexed { i, p ->
            val slot = if (i == 0) 0 else if (i % 2 == 1) (i + 1) / 2 else -(i / 2)
            val yaw = headYawDeg + slot * stepDeg
            val w = minOf(p.widthM, maxW)
            at(p, 0f, 0f, w, yaw, r)
        }
    }
}
