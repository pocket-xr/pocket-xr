package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.14, Mike's decision: the Space starts in side by side 3D (3840 x 1080 at
 * 60 Hz), with 2D (1920 x 1080 at 120 Hz) one switch away and
 * remembered.
 */
enum class GlassesView(val label: String, val short: String) {
    SBS_3D("3D side by side", "3D"),
    FLAT_2D("2D", "2D"),
    ;

    fun toggled(): GlassesView = if (this == SBS_3D) FLAT_2D else SBS_3D

    companion object {
        val DEFAULT = SBS_3D

        fun parse(name: String?): GlassesView = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * What the Space does for a [GlassesView] with the modes Android offers on the glasses display.
 *
 * [modeIndex]: the mode to ask for (preferredDisplayModeId), an index into the offered modes; null
 * when there is nothing to ask. [draws]: how the surface is split. [sameForBothEyes]: side by side
 * with no eye separation (2D wanted while Android offers only the 3D mode). [needsGlasses]: what only
 * the glasses' own control can change (Pocket XR sends nothing to the glasses); null when the view
 * is what was wanted.
 */
data class ViewPlan(
    val wanted: GlassesView,
    val modeIndex: Int?,
    val draws: StereoMode,
    val sameForBothEyes: Boolean,
    val needsGlasses: String?,
    val line: String,
    /** 1.2.8: the wide mode is drawn as one 32:9 canvas ([WideCanvas]), not two eyes. */
    val canvas: Boolean = false,
) {
    /** True when the glasses show what was wanted. */
    val met: Boolean get() = needsGlasses == null
}

object ViewModeChoice {
    private fun fmt(m: Triple<Int, Int, Float>) = "${m.first}x${m.second}@%.0f".format(Locale.ROOT, m.third)

    /** The flat mode to ask for: the widest mode that is not side by side, at its highest refresh. */
    fun pickFlatMode(modes: List<Triple<Int, Int, Float>>): Int? =
        modes.withIndex().filter { !StereoLayout.isSbsMode(it.value.first, it.value.second) }
            .maxWithOrNull(compareBy<IndexedValue<Triple<Int, Int, Float>>> { it.value.first }.thenBy { it.value.third })?.index

    /**
     * The plan for [wanted] given Android's [modes] (width, height, Hz). Only these modes are
     * asked for: switching the glasses themselves between 2D and 3D needs their control channel
     * (EDID index 5), which Pocket XR does not use without Mike's approval.
     */
    fun plan(
        wanted: GlassesView,
        modes: List<Triple<Int, Int, Float>>,
        fastestAllowed: Boolean = true,
        // 1.2.8 (Discussion #24): what the wide mode is on these glasses ([WideCanvas.kind]), and whether only their own
        // menu can switch them to 3D (every glasses but XREAL's with the control link).
        wide: WideKind = WideKind.SIDE_BY_SIDE,
        glassesMenuOnly: Boolean = false,
    ): ViewPlan {
        val sbs = StereoLayout.pickSbsMode(modes)
        val flat = pickFlatMode(modes)
        val all = modes.joinToString { fmt(it) }
        val turnOn3d = if (glassesMenuOnly) DepthPress.GLASSES_MENU else "Turn on the glasses' 3D mode (Full SBS) for 3D"
        // Thermal limits keep the rate Android picked: ask only for the size, at the slowest rate offered there.
        fun atSize(i: Int?): Int? = i?.let { pick ->
            if (fastestAllowed) pick else modes.withIndex().filter { it.value.first == modes[pick].first && it.value.second == modes[pick].second }.minByOrNull { it.value.third }!!.index
        }
        return when (wanted) {
            GlassesView.SBS_3D -> if (sbs != null && wide == WideKind.CANVAS) {
                // The glasses said their wide mode is one canvas: two eyes drawn on it would show as two pictures.
                ViewPlan(
                    wanted, atSize(sbs), StereoMode.MONO, false, turnOn3d,
                    "view: 3D side by side wanted, but the glasses' ${fmt(modes[atSize(sbs)!!])} is their ultrawide mode (${wide.label}): one wide canvas until 3D is on in their own menu (modes $all)",
                    canvas = true,
                )
            } else if (sbs != null) {
                ViewPlan(wanted, atSize(sbs), StereoMode.SIDE_BY_SIDE, false, null, "view: 3D side by side at ${fmt(modes[atSize(sbs)!!])}, one half per eye (modes $all)")
            } else {
                ViewPlan(
                    wanted, atSize(flat), StereoMode.MONO, false,
                    turnOn3d,
                    "view: 3D side by side wanted, but Android offers no 3840x1080 mode on the glasses now (modes $all): the glasses are in their 2D mode, " +
                        "and only their own 3D switch (or the control channel, not used) changes that; one picture for both eyes" + (flat?.let { " at ${fmt(modes[atSize(it)!!])}" } ?: ""),
                )
            }
            GlassesView.FLAT_2D -> if (flat != null) {
                ViewPlan(wanted, atSize(flat), StereoMode.MONO, false, null, "view: 2D at ${fmt(modes[atSize(flat)!!])}, one picture for both eyes (modes $all)")
            } else if (sbs != null && wide != WideKind.SIDE_BY_SIDE) {
                // 1.2.8: glasses that are not XREAL's show their 3840 x 1080 as one 32:9 screen unless they said 3D.
                ViewPlan(wanted, atSize(sbs), StereoMode.MONO, false, null, "view: 2D on the glasses' wide mode at ${fmt(modes[atSize(sbs)!!])}: one wide canvas (${wide.label}) (modes $all)", canvas = true)
            } else {
                ViewPlan(
                    wanted, atSize(sbs), if (sbs != null) StereoMode.SIDE_BY_SIDE else StereoMode.MONO, sbs != null,
                    "Turn off the glasses' 3D mode for 2D at 120 Hz",
                    "view: 2D wanted, but Android offers only the side by side mode now (modes $all): the same picture goes to both halves" +
                        (sbs?.let { " at ${fmt(modes[atSize(it)!!])}" } ?: "") + " until the glasses' own 3D mode is off",
                )
            }
        }
    }

    /**
     * The eyes to draw for [plan] on a [width] by [height] surface. The split follows the surface
     * (a 3840 x 1080 surface is side by side even before the plan knows); 2D on a side by side
     * surface puts the same picture in both halves.
     */
    fun eyes(plan: ViewPlan?, width: Int, height: Int, ipdMm: Float): List<EyeView> {
        val sbsSurface = StereoLayout.isSbsMode(width, height)
        // 1.2.8: a plan that draws one picture on a wide surface is one wide canvas ([WideCanvas]), not two halves.
        if (!sbsSurface || plan?.canvas == true) {
            return StereoLayout.eyes(StereoMode.MONO, width, height, ipdMm)
        }
        val same = plan?.wanted == GlassesView.FLAT_2D
        val eyes = StereoLayout.eyes(StereoMode.SIDE_BY_SIDE, width, height, ipdMm)
        return if (same) eyes.map { it.copy(offset = Vec3.ZERO) } else eyes
    }
}
