package com.hayeco.flipspaces.core.spatial

/**
 * Steady mode remembered across launches (a user on Windows asked for it). The user's own choice, on or off, is saved each
 * time they turn Steady mode on or off with any of its controls; an unplug or the app closing changes nothing. When glasses
 * that can use Steady mode connect, or the app starts with them connected, and the saved choice is on, Steady mode is
 * turned on once through the same path as the user's button. Glasses that cannot use it ignore the saved choice.
 */
object SteadyRemember {
    /** What the link knows when [turnOn] is asked. */
    data class Now(
        /** The user's saved choice. */
        val saved: Boolean,
        /** The glasses can use Steady mode ([SteadyMode.offeredOn]). */
        val offered: Boolean,
        /** The saved choice was dealt with already for these glasses ([settled]): once a plug-in. */
        val tried: Boolean,
        /** A Space is up on the glasses. */
        val spaceUp: Boolean,
        /** A Steady mode change is under way, Steady mode is held, or something else owns the glasses (PC VR, a game, a test). */
        val busy: Boolean,
        /** Steady mode is on already, as its button shows it. */
        val steadyOn: Boolean,
        /** The link reads the glasses' menu settings (the Aura), so it waits for their Ultrawide answer. */
        val readsSettings: Boolean,
        /** The glasses' Ultrawide answer on this link, null before it. */
        val ultrawide: Int?,
        /** How long the control link has been open, ms. */
        val sinceLinkMs: Long,
    )

    /**
     * Glasses that do not have their settings read on every link get this long after it opens, so the first reads and the
     * 3D ask of a new plug-in come first.
     */
    const val SETTLE_MS = 3_000L

    /** The control link is ready: the Aura's Ultrawide answer is in, others have had [SETTLE_MS]. */
    fun linkReady(n: Now): Boolean = if (n.readsSettings) n.ultrawide != null else n.sinceLinkMs >= SETTLE_MS

    /** Turn Steady mode on now, as the user's button would. */
    fun turnOn(n: Now): Boolean =
        n.saved && n.offered && !n.tried && n.spaceUp && !n.busy && !n.steadyOn && n.ultrawide != 1 && linkReady(n)

    /**
     * The saved choice is dealt with for these glasses once the link is ready with a Space up: turned on then, on already, or
     * left alone because something else had the glasses. It is not acted on again until the next plug-in, so Steady mode
     * turned off by Pocket XR itself (for PC VR) or given up on is not turned back on behind the user.
     */
    fun settled(n: Now): Boolean = !n.saved || !n.offered || n.tried || (n.spaceUp && linkReady(n))
}
