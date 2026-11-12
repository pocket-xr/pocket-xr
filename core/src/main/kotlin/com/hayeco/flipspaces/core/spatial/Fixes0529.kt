package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 0.5.29 (the picture sat too far right in the glasses): the glasses' 3D screen distance.
 *
 * The phone draws each eye's half centred. The glasses show the side by side picture on a screen that floats at their
 * saved 3D distance ([depthM], 1 to 10 m, their default 4 m), and each eye looks at it from its own place, so each eye's
 * half lands moved toward the nose by (IPD / 2) / distance in tangent: 1.8 degrees (59 panel px) at 1 m, 0.45 degrees
 * (15 px) at 4 m. Their screen's own centre offset (the status line's quad, 0.16 degrees right and 0.13 up on Mike's
 * Aura) moves both eyes the same way. In vc49 the distance was 1.00 m, so the left eye's picture sat 64 px right and lost
 * about 17 px off its right edge.
 *
 * The correction moves each eye's picture back out by the same angle, in its half's own pixels at the screen's size
 * ([sizeFactor]): the left eye left, the right eye right, both by the screen's offset the other way. It is added to the
 * picture adjust, so the late warp, the pointer and the edge check all follow it. 0.5.59: the screen's centre offset is
 * no longer added, and only a distance seen this session is used ([inUse]). It also puts real 3D's depth back:
 * with parallel eyes drawn on a 1 m screen, a window placed at 2.6 m was seen at about 0.7 m.
 */
object ScreenDistance {
    /** The glasses' default 3D distance, which gives the full, centred view. */
    const val DEFAULT_M = 4f
    const val MIN_M = 1f
    const val MAX_M = 10f

    /** The setting's steps, matching the glasses' own 1 to 10 m. */
    val STEPS = listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)

    /**
     * The correction per eye, in the half's pixels: [leftDx] and [rightDx] across (+ right). 0.5.59 (the left eye's
     * clipped edge): mirror images, the screen's centre offset is no longer added (it moved both eyes the same way, which
     * made the left eye's undrawn strip wider than the right eye's).
     */
    data class Correction(val leftDx: Int, val rightDx: Int, val depthM: Float, val from: String) {
        val none: Boolean get() = leftDx == 0 && rightDx == 0

        fun line(): String = String.format(
            Locale.ROOT, "the glasses' 3D screen at %.2f m (%s): left eye %+d px, right eye %+d px, mirror images (no screen centre offset), each eye drawn over its whole half",
            depthM, from, leftDx, rightDx,
        )
    }

    /**
     * How far each eye's half lands toward the nose on a screen at [depthM], in the half's pixels at [sizeFactor].
     * 0.5.91: on the glasses' own 1:1 scale [tanPerPx1to1] ([GlassesModel.tanPerPx1to1]; the Aura's by default).
     */
    fun nasalShiftPx(ipdMm: Float, depthM: Float, sizeFactor: Float, tanPerPx1to1: Float = GlassesScreen.TAN_PER_PX_1TO1): Float {
        val d = depthM.coerceIn(MIN_M, MAX_M)
        return (ipdMm / 2000f) / d / (tanPerPx1to1 * sizeFactor.coerceIn(GlassesScreen.MIN_FACTOR, GlassesScreen.MAX_FACTOR))
    }

    /** The correction for a screen at [depthM] of size [sizeFactor], eyes [ipdMm] apart: each eye out by the same amount. */
    fun correction(ipdMm: Float, depthM: Float, sizeFactor: Float, from: String, tanPerPx1to1: Float = GlassesScreen.TAN_PER_PX_1TO1): Correction {
        val d = depthM.coerceIn(MIN_M, MAX_M)
        val nasal = nasalShiftPx(ipdMm, d, sizeFactor, tanPerPx1to1).roundToInt()
        return Correction(-nasal, nasal, d, from)
    }

    /**
     * 0.5.59 (a saved 1 m or 10 m from an old log or an undecoded read was still used
     * while the glasses sat at their default 4 m): the distance the correction uses, only one seen in this session
     * ([seenThisSession], from the glasses' own status line), else their default [DEFAULT_M]. A saved value is never used.
     */
    fun inUse(seenThisSession: Float?): Float =
        seenThisSession?.takeIf { it > 0f }?.coerceIn(MIN_M, MAX_M) ?: DEFAULT_M

    /** Where [inUse]'s distance came from, for the log line. */
    fun inUseFrom(seenThisSession: Float?): String =
        if (seenThisSession != null && seenThisSession > 0f) "their log, this session" else "their default, none seen this session"

    /** [adjust] with [c] added to each eye (the eyes then differ, so "same for both eyes" no longer ties them). */
    fun apply(adjust: PictureAdjust, c: Correction?): PictureAdjust {
        if (c == null || c.none) return adjust
        val l = adjust.eye(0)
        val r = adjust.eye(1)
        return PictureAdjust(
            l.copy(dxPx = l.dxPx + c.leftDx).clamped(),
            r.copy(dxPx = r.dxPx + c.rightDx).clamped(),
            same = false,
        )
    }

    /** The step nearest [m], for the setting. */
    fun nearestStep(m: Float): Float = STEPS.minByOrNull { abs(it - m) } ?: DEFAULT_M

    /** True when [m] is the glasses' default. */
    fun isDefault(m: Float): Boolean = abs(m - DEFAULT_M) < 0.05f

    /**
     * The note said on the glasses when their 3D distance is not 4 m: three lines of at most 44 characters (the hint
     * label's one line), shown one after the other. Nothing is sent to the glasses; it points at their own menu.
     */
    fun note(depthM: Float): List<String>? {
        if (isDefault(depthM)) return null
        return listOf(
            UiText.format("spatial_distance_note_1", "Glasses 3D distance %1\$.1f m, not 4 m", depthM),
            UiText.get("spatial_distance_note_2", "Their default 4 m gives the full, centred view"),
            UiText.get("spatial_distance_note_3", "Glasses menu, by Display size: Distance 4 m"),
        )
    }
}

/** 0.5.29 ("how to confirm in one look"): the edge check on both eyes, or one at a time. */
enum class EdgeEyes(val label: String) {
    BOTH("both eyes"), LEFT("L only"), RIGHT("R only");

    /** Whether eye [i] (0 left) shows the check; the other eye's half is black while one eye is shown. */
    fun shows(i: Int): Boolean = this == BOTH || (this == LEFT && i == 0) || (this == RIGHT && i == 1)

    /** The next on the Menu's button: both, then L only, then R only, then both again. */
    fun next(): EdgeEyes = entries[(ordinal + 1) % entries.size]
}

/**
 * 0.5.29 (the border and the pixel ticks did not move while Mike
 * tapped the picture adjust): the edge check's pixel marks drawn on the placed picture, like the degree ticks.
 */
object PlacedEdgeCheck {
    /** [EdgeCheck.pixelMarks] on the whole adjusted picture ([left], [top], [w] by [h] in the half), cut to the half. */
    fun pixelMarks(halfW: Int, halfH: Int, left: Int, top: Int, w: Int, h: Int, label: Char?): List<EdgeCheck.Mark> =
        EdgeCheck.pixelMarks(w, h, label).mapNotNull { EdgeCheck.clip(EdgeCheck.Mark(it.x + left, it.y + top, it.w, it.h), halfW, halfH) }
}

/**
 * 0.5.29 (the Menu looked as if closing it turned the warp off, but the glasses left scene
 * mode 1 by themselves 0.6 to 1.4 s after accepting it): what the Menu says after such a drop.
 */
object WarpDropNote {
    fun menu(heldMs: Long?, times: Int): String {
        val held = heldMs?.let { UiText.format("spatial_warp_drop_after", " after %1\$.1f s", it / 1000.0) } ?: ""
        val count = if (times > 1) UiText.format("spatial_warp_drop_times", " (%1\$d times this session)", times) else ""
        return UiText.format("spatial_warp_drop", "Off: the glasses dropped out of warp mode%1\$s%2\$s. Not the Menu or the switch: they leave it by themselves, and the warp then turns off so the picture comes back.", held, count)
    }
}

/**
 * 0.5.29 (hand tracking at 4 to 5 fps on the CPU, about 100 ms latency, all session):
 * the hand model's way back to the GPU. [SpaceGpuGuard] sent it to the CPU on the first timing line (the Space missed
 * 2 to 3 % of its frames, as it does thermally moderate with or without the hand model) and it stayed there for the
 * camera session. Now, once moved, the hand model goes back to the GPU while a hand is up (it needs 30 fps then), after
 * a hold of [firstHoldMs]; if the guard moves it off again, the hold doubles, up to [maxHoldMs]. With no hand up the
 * idle palm search runs on the CPU anyway ([DetectionCycle.IdleSearch]), 5 a second.
 */
class HandGpuReturn(val firstHoldMs: Long = 20_000L, val maxHoldMs: Long = 320_000L, val handUpMs: Long = 500L) {
    private var movedAtMs = Long.MIN_VALUE
    private var holdMs = firstHoldMs
    private var handSinceMs = Long.MIN_VALUE

    var moves = 0
        private set
    var returns = 0
        private set

    /** 0.5.31: moves to the CPU for the phone's heat ([movedOffForHeat]), not the guard's. */
    var heatMoves = 0
        private set

    /** The guard moved the hand model to the CPU at [nowMs]. */
    fun movedOff(nowMs: Long) {
        if (moves > 0) holdMs = (holdMs * 2).coerceAtMost(maxHoldMs)
        moves++
        movedAtMs = nowMs
    }

    /** 0.5.31: the phone reached severe heat with a Space up, so the hand model went to the CPU at [nowMs]; the hold does not double. */
    fun movedOffForHeat(nowMs: Long) {
        heatMoves++
        movedAtMs = nowMs
    }

    /**
     * Whether the hand model, on the CPU because of the guard or the heat ([offByGuard]), goes back to the GPU now.
     * 0.5.31: never while [heatHolds] ([heatStatus] severe or hotter with a Space drawing); a held return is not counted.
     */
    fun shouldReturn(nowMs: Long, offByGuard: Boolean, handTracked: Boolean, heatStatus: Int = ThermalGuard.NONE, spaceDraws: Boolean = false): Boolean {
        handSinceMs = if (!handTracked) Long.MIN_VALUE else if (handSinceMs == Long.MIN_VALUE) nowMs else handSinceMs
        if (!offByGuard || movedAtMs == Long.MIN_VALUE || !handTracked) return false
        if (heatHolds(heatStatus, spaceDraws)) return false
        if (nowMs - movedAtMs < holdMs || nowMs - handSinceMs < handUpMs) return false
        returns++
        return true
    }

    companion object {
        /**
         * 0.5.31 (each 0.5.30 return to the GPU at severe heat was followed by a burst of
         * missed Space frames, 5.0, 29.2 and 42.5 %): from severe heat, while a Space draws, the hand model stays on the CPU.
         */
        fun heatHolds(status: Int, spaceDraws: Boolean): Boolean = spaceDraws && status >= ThermalGuard.SEVERE

        /**
         * 0.5.30 (Mike: hand tracking choppy and sluggish): a hand up is back on the GPU, at the camera's full rate,
         * 5 s after the guard moved it (was 20 s), once it has been up 0.2 s; the hold still doubles each time the
         * guard has to move it off again, up to 320 s.
         */
        fun v0530() = HandGpuReturn(firstHoldMs = 5_000L, handUpMs = 200L)

        /**
         * 0.5.32 (vc52, a cool phone: two returns 17 s apart, 5 s and 10 s after the guard,
         * were followed by 20.8 and 25.0 % missed frames): the first hold back to 0.5.29's 20 s, doubling as before;
         * a hand still comes back to the GPU 0.2 s after it is up once the hold has passed.
         */
        fun v0532() = HandGpuReturn(firstHoldMs = 20_000L, handUpMs = 200L)
    }

    fun line(): String = String.format(Locale.ROOT, "the hand model back on the GPU for a hand (moved off %d times, back %d; next hold %.0f s)", moves, returns, holdMs / 1000.0)
}
