package com.hayeco.flipspaces.core.spatial

import kotlin.math.tan

/**
 * 1.2.6 (Discussion #24, VITURE Beast in its 32:9 ultrawide mode: "two duplicate side by side views, each 1080p"): what a
 * 3840 x 1080 mode means. On XREAL glasses it is their side by side 3D, or the Ultrawide their control link reports. Up to
 * 1.2.5 every other glasses' 3840 x 1080 was taken as side by side too, and the Space in 2D put the same picture in both
 * halves: on glasses showing that mode as one wide screen, two copies next to each other.
 */
enum class WideKind(val label: String) {
    /** Two eyes, one half each: XREAL's 3D, or glasses that said they are in 3D. */
    SIDE_BY_SIDE("side by side 3D"),

    /** One 32:9 canvas: glasses that said they are in their ultrawide mode. */
    CANVAS("one 32:9 canvas"),

    /** Not known: one canvas in 2D, side by side once 3D is on. */
    BY_VIEW("one canvas in 2D, side by side in 3D (not known from the glasses)"),
}

/**
 * 1.2.6: VITURE glasses change their display's EDID product id with their mode. Seen in the Discussion #24 logs (a Beast):
 * 288 (0x0120) in 2D at 1920 x 1200 and 1920 x 1080; 800 (0x0320) at 3840 x 1080 in the sessions that showed the two
 * copies (the ultrawide mode, by the tester's report); 544 (0x0220) at 3840 x 1080 in the session where 3D then worked
 * (the glasses' own 3D, by the tester's report). Inferred from one tester's log, so anything else is [WideKind.BY_VIEW].
 */
object VitureEdid {
    const val MODE_2D = "288"
    const val MODE_3D = "544"
    const val MODE_ULTRAWIDE = "800"

    fun wideKind(edidProduct: String?): WideKind = when (edidProduct?.trim()) {
        MODE_3D -> WideKind.SIDE_BY_SIDE
        MODE_ULTRAWIDE -> WideKind.CANVAS
        else -> WideKind.BY_VIEW
    }
}

object WideCanvas {
    /** What a 3840 x 1080 mode is on [model] whose display's EDID product id is [edidProduct]. */
    fun kind(model: GlassesModel, edidProduct: String?): WideKind = when {
        model.isXreal -> WideKind.SIDE_BY_SIDE
        model == GlassesModel.VITURE -> VitureEdid.wideKind(edidProduct)
        else -> WideKind.BY_VIEW
    }

    /**
     * Whether a [width] by [height] surface is drawn as one flat canvas, not two eyes. XREAL glasses with their control
     * link open decide by what it read ([stereoState] 0 and [ultrawideRead] not 0, as before); without a link, XREAL's
     * wide mode stays side by side; other glasses follow [kind], with [view] deciding when it is not known.
     */
    fun isCanvas(width: Int, height: Int, stereoState: Int?, ultrawideRead: Int?, kind: WideKind, view: GlassesView): Boolean = when {
        !StereoLayout.isSbsMode(width, height) -> false
        stereoState != null -> StereoLayout.isUltrawide(width, height, stereoState) && ultrawideRead != 0
        kind == WideKind.SIDE_BY_SIDE -> false
        kind == WideKind.CANVAS -> true
        else -> view == GlassesView.FLAT_2D
    }
}

/**
 * 1.2.6 (Discussion #24: 3D "works only after turning on 3D in the glasses' own menu first", and before that the Depth
 * button did nothing that could be seen): what the launcher's Depth button does. Pocket XR switches the glasses itself
 * only over XREAL's control link; on any other glasses, turning 3D on when they offer no side by side mode (or say they are
 * in their ultrawide mode) cannot work from the phone, so the button says what to do instead of changing a setting that
 * shows nothing.
 */
object DepthPress {
    const val GLASSES_MENU = "Turn on 3D in your glasses menu first"

    sealed interface Result {
        data object Toggle : Result
        data class AskGlasses(val text: String) : Result
    }

    fun decide(current: GlassesView, sbsOffered: Boolean, wide: WideKind, canSwitchGlasses: Boolean): Result = when {
        canSwitchGlasses || current == GlassesView.SBS_3D -> Result.Toggle
        !sbsOffered || wide == WideKind.CANVAS -> Result.AskGlasses(GLASSES_MENU)
        else -> Result.Toggle
    }
}

/**
 * 1.2.6 (Discussion #24: "launcher icons look pixelated on the edges"): the size an app icon is baked at, its size in the
 * glasses' own pixels when it is drawn largest (hovered, at the launcher's distance). Baked at that size the texture's
 * first level is the one sampled, so its anti-aliased edge reaches the glasses as drawn; up to 1.2.5 it was a fixed 2 px a
 * dp, with an edge that was not anti-aliased at all.
 */
object LauncherIconRaster {
    const val MIN_PX = 64
    const val MAX_PX = 512

    /**
     * [iconDp] across, on a view [viewWidthPx] wide spanning [hFovDeg], at [distanceM] with [dpPerM] dp a metre, grown by
     * [growth] on hover: rounded up to a multiple of 4.
     */
    fun px(iconDp: Float, viewWidthPx: Int, hFovDeg: Float, dpPerM: Float = LauncherGrid.DP_PER_M, distanceM: Float = XrLauncher.DISTANCE_M, growth: Float = 1.1f): Int {
        if (viewWidthPx <= 0 || hFovDeg <= 0f || distanceM <= 0f) return MIN_PX
        val focalPx = viewWidthPx / 2f / tan(hFovDeg * DEG / 2f)
        val raw = focalPx * (iconDp / dpPerM) / distanceM * growth
        val up = ((kotlin.math.ceil(raw.toDouble()).toInt() + 3) / 4) * 4
        return up.coerceIn(MIN_PX, MAX_PX)
    }
}
