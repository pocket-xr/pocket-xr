package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.20 (Mike: "when I select apps in the app launcher, the cursor is drawn behind the launcher
 * panel, although selecting still works"): where the cursor is drawn, so it is always in front of
 * every surface it covers, in both eyes.
 *
 * Why it was behind: the cursor was drawn last with no depth test, so it was on top in each eye's
 * picture, but its place in the room was behind the launcher. Over the open launcher (or the control
 * panel, or the home tile) the pointer claims the ray, so nothing is hovered, and the cursor went to
 * its "over nothing" distance: 2.5 m for the trackpad's reticle, 2 m for a hand's dot. The launcher
 * is 1.0 m away. In side by side 3D each eye sees the cursor with the disparity of 2 to 2.5 m and the
 * launcher with that of 1 m, so the eyes fuse the cursor 1 to 1.5 m behind a panel that is drawn
 * over it: it looks behind. In 2D (no disparity) it showed on top. A hand's dot had a second fault:
 * 2 m along the hand's ray is not on the eye's line to the tile the hand points at.
 *
 * Now the cursor's point is found in three steps: (1) along the pointer's ray, at the hovered
 * surface ([hoverM]) or, when the launcher or a menu claims the ray, at that surface (a sphere of
 * [claimM] round the head, met by the ray from wherever it starts), else [defaultM]; (2) seen from
 * the eyes, never behind the nearest window, curved or flat, or its controls on that line of sight
 * ([PanelHitTester.nearest] along the eye's ray to the point); (3) while the launcher or a menu is
 * open, never behind it. Each pulled in to [IN_FRONT_M] before the surface. Its size is a constant
 * angle from the eyes, so moving it along the line of sight changes nothing in 2D and puts it in
 * front in 3D.
 */
object CursorDepth {
    /** How far in front of the surface the cursor floats, metres (0.5.19's 5 mm). */
    const val IN_FRONT_M = 0.005f

    /** The hand dot's distance over nothing (0.5.19's 2 m). */
    const val HAND_DEFAULT_M = 2f

    /**
     * The cursor's point in the room (the eyes at the origin). [ray] is the pointer's (the head's
     * or a hand's), [hoverM] the distance along it to the hovered window (null over none), [claimM]
     * the radius of the launcher or menu that claims the ray (null when none does), [openM] the
     * radius of any launcher or menu open at all, [panels] the windows to keep the cursor in front of.
     */
    fun point(
        ray: Ray,
        hoverM: Float?,
        claimM: Float?,
        openM: Float?,
        panels: List<SpacePanel>,
        defaultM: Float,
    ): Vec3 {
        val along = when {
            hoverM != null -> hoverM - IN_FRONT_M
            claimM != null -> sphere(ray, claimM)?.let { it - IN_FRONT_M } ?: claimM
            else -> defaultM
        }.coerceAtLeast(0.1f)
        var p = ray.at(along)
        val seen = p.length
        if (seen < 1e-4f) return p
        val dir = p * (1f / seen)
        var d = seen
        // Seen from the eyes: in front of the nearest window on the line of sight.
        PanelHitTester.nearest(Ray(Vec3.ZERO, dir), panels)?.let { hit -> if (hit.distance - IN_FRONT_M < d) d = hit.distance - IN_FRONT_M }
        // In front of the open launcher or menu.
        if (openM != null && openM - IN_FRONT_M < d) d = openM - IN_FRONT_M
        d = d.coerceAtLeast(0.1f)
        if (d != seen) p = dir * d
        return p
    }

    /**
     * 0.5.45 (Mike, vc65: on the three buttons under a window and on the bar above it "the cursor dot goes away from
     * the buttons toward me"): the gaps between a window, its bar and its buttons hit nothing, and over nothing the dot
     * went to [HAND_DEFAULT_M], 2 m, in front of a window further out, so in 3D it jumped toward him. Within [NEAR_DEG]
     * of a window it now stays on that window's surface (where the ray meets it), so it slides past the edges at the
     * window's depth. Null when no window is that close.
     */
    fun nearM(ray: Ray, panels: List<SpacePanel>): Float? {
        val (panel, off) = PanelHitTester.closest(ray, panels) ?: return null
        if (off > NEAR_DEG) return null
        return PanelCurve.intersect(ray, panel)?.t
    }

    /** 0.5.45: how far outside a window's body (degrees) the dot still sits at its depth; the bar and buttons are within it. */
    const val NEAR_DEG = 8f

    /** The distance along [ray] to the sphere of [radius] round the head (from inside it), or null. */
    fun sphere(ray: Ray, radius: Float): Float? {
        val dir = ray.dir.normalized()
        val b = ray.origin dot dir
        val c = (ray.origin dot ray.origin) - radius * radius
        val disc = b * b - c
        if (disc < 0f) return null
        val t = -b + kotlin.math.sqrt(disc)
        return if (t > 0f) t / ray.dir.length.coerceAtLeast(1e-6f) else null
    }
}

/**
 * 0.5.20: what each eye's pass draws, in order (painter's order; the Space uses no depth buffer).
 * The renderer runs this list, and a test holds the cursor after every panel: windows, their
 * controls and cards, the control panel, the launcher and its home tile, and the checks.
 */
enum class SpaceLayer {
    WINDOWS,
    CONTROL_PANEL,
    LAUNCHER,
    HOME,
    DELAY_CHECK,
    LEVEL_CHECK,
    EDGE_HINT,
    CURSOR,
    ;

    companion object {
        /** The order of one eye's pass. */
        val ORDER: List<SpaceLayer> = values().toList()
    }
}
