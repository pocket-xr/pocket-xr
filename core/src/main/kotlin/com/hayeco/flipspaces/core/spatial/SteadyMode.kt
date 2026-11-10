package com.hayeco.flipspaces.core.spatial

/**
 * Steady mode, a key on the trackpad and a button in the launcher: one button for what the user otherwise sets in the glasses' menu each time. On is
 * Spatial Anchor off, Ultrawide on and the largest screen size Ultrawide offers. Off is Ultrawide off, then 3D as usual.
 * Each screen size belongs to its mode, so nothing has to be put back: 2D and 3D keep their own.
 *
 * 1.4.0 (docs/6DOF-PLAN.md): Steady mode in 6DoF, for the XREAL Aura. Its Spatial Anchor tracks the head's position as
 * well as its turn, and holds Pocket XR's Ultrawide canvas in place in the room (Mike, 10 November 2026; 3D side by
 * side stops it). So on is 2D, Ultrawide on and the largest screen size, then Spatial Anchor **on**, last. Off puts
 * Spatial Anchor off first, so normal tracking is not moved twice, then Ultrawide off.
 */
object SteadyMode {
    enum class Step { WAIT, ANCHOR_OFF, ANCHOR_ON, LEAVE_3D, ULTRAWIDE_ON, SCREEN_MAX, ULTRAWIDE_OFF, DONE }

    /** The glasses' answers, null until read on this link. */
    data class Glasses(
        val stereo: Int? = null,
        val ultrawide: Int? = null,
        val anchor: Int? = null,
        val screenSize: Float? = null,
        val screenSizeMax: Float? = null,
    )

    /**
     * The one thing to do next. Ultrawide can only be turned on from 2D. With [sixDof] the glasses' Spatial Anchor is
     * turned on last, after Ultrawide and the screen size (each of those re-plugs the display), and off first on the
     * way out.
     */
    fun next(on: Boolean, g: Glasses, sixDof: Boolean = false): Step {
        val ultrawide = g.ultrawide ?: return Step.WAIT
        if (!on) return when {
            sixDof && g.anchor == 1 -> Step.ANCHOR_OFF
            ultrawide == 1 -> Step.ULTRAWIDE_OFF
            else -> Step.DONE
        }
        val anchor = g.anchor ?: return Step.WAIT
        val stereo = g.stereo ?: return Step.WAIT
        return when {
            !sixDof && anchor == 1 -> Step.ANCHOR_OFF
            stereo == 1 -> Step.LEAVE_3D
            ultrawide != 1 -> Step.ULTRAWIDE_ON
            g.screenSize == null || g.screenSizeMax == null -> Step.WAIT
            g.screenSize < g.screenSizeMax - SIZE_SAME -> Step.SCREEN_MAX
            sixDof && anchor != 1 -> Step.ANCHOR_ON
            else -> Step.DONE
        }
    }

    /** Glasses that can hold Steady mode in 6DoF: the Aura, checked on 10 November 2026. */
    fun sixDofOn(model: GlassesModel?): Boolean = model == GlassesModel.AURA

    /** Glasses that get the key. The One series is untested (7 November 2026): it should work, so a refusal only ends the change. */
    fun offeredOn(model: GlassesModel?): Boolean = model == GlassesModel.AURA || model == GlassesModel.ONE || model == GlassesModel.ONE_PRO || model == GlassesModel.ONE_PRO_PLUS || model == GlassesModel.ONE_S

    /** Glasses whose menu settings are read and put right on every link. Others only once Steady mode has been used. */
    fun managedOn(model: GlassesModel?): Boolean = model == GlassesModel.AURA

    private const val SIZE_SAME = 0.005f

    /**
     * 1.3.3 (a tester: "pressing the action button on the glasses twice exits 'ultrawide mode' which completely screws
     * things up and is easy to do by accident"): what Pocket XR does when the glasses leave Ultrawide by themselves in
     * Steady mode. The first time it puts Ultrawide back ([Dropped.BACK_IN]); a second time within [SECOND_DROP_MS]
     * means the user wants out, so Steady mode ends properly, with 3D and the windows back ([Dropped.LEAVE]).
     */
    enum class Dropped { BACK_IN, LEAVE }

    const val SECOND_DROP_MS = 30_000L

    fun dropped(nowMs: Long, lastDropMs: Long?): Dropped =
        if (lastDropMs != null && nowMs - lastDropMs in 0 until SECOND_DROP_MS) Dropped.LEAVE else Dropped.BACK_IN
}
