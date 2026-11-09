package com.hayeco.flipspaces.core.spatial

/**
 * Unreleased (John Davis: the trackpad's Menu never showed Text size, even after clicking a window): the window the Menu's
 * This window section (Text size, Side by side 3D) and the Game controller act on, worked out in one place.
 *
 * A click, tap, pinch or gaze press on a window focuses it ([SpaceAction.Focus]). Each Space start made a new renderer
 * with nothing focused, and on glasses that switch to 3D (a display re-plug) the Space starts twice, so a window clicked
 * in the first seconds, or before any 2D / 3D switch, was no longer focused when the Menu opened. The focus is now kept
 * across Space starts in the same run ([Kept]), and a window just opened takes it.
 */
object FocusedWindow {
    /** The focused window and its app, kept for the next Space in the same run. */
    data class Kept(val panelId: Int, val packageName: String)

    /**
     * The focused window when it holds an app, or null: nothing focused, its window gone, or no app in it. [panel] looks
     * a window up by id (inline, so the renderer's every-frame call makes no lambda).
     */
    inline fun of(focusedId: Int?, panel: (Int) -> SpacePanel?): SpacePanel? =
        focusedId?.let(panel)?.takeIf { it.packageName.isNotEmpty() }

    /** What to keep of [focused] for the next Space, or null for a window without an app. */
    fun keep(focused: SpacePanel?): Kept? = focused?.takeIf { it.packageName.isNotEmpty() }?.let { Kept(it.id, it.packageName) }

    /**
     * The window a new Space starts focused on: the [kept] one, while a window with that id still shows the same app in
     * [panels]; otherwise none (a closed window, or its id now someone else's).
     */
    fun restored(kept: Kept?, panels: Collection<SpacePanel>): Int? =
        kept?.let { k -> panels.firstOrNull { it.id == k.panelId && it.packageName == k.packageName }?.id }
}
