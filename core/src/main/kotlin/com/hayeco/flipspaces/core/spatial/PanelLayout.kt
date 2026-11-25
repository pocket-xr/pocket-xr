package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * One window floating around the user: its direction (yaw, pitch), distance and angular size.
 * It always faces the user (a flat quad on a sphere), so its size in degrees is what Mike sees
 * whatever the distance. [packageName] and [activityName] say which app it shows; both empty
 * for Pocket XR's own panels.
 */
data class SpacePanel(
    val id: Int,
    val label: String,
    val packageName: String,
    val activityName: String,
    val yawDeg: Float,
    val pitchDeg: Float,
    val distanceM: Float = PanelLimits.DEFAULT_DISTANCE,
    val widthDeg: Float = PanelLimits.DEFAULT_WIDTH_DEG,
    val heightDeg: Float = PanelLimits.DEFAULT_HEIGHT_DEG,
    /**
     * 0.5.19: how far the window is curved round Mike, 0 flat to 1 (centred on his head at the
     * window's distance, every column the same distance from his eyes) ([PanelCurve]).
     */
    val curve: Float = 0f,
    /**
     * 0.5.21 (Arrange shrank every app to 256 dp): the app's own size, dp,
     * which only Mike's resizes set ([sizedByUser]); 0 is "from the window's angular size". Arrange,
     * Reset, depth and the wrap move and scale the window but keep this, so the app is not laid out
     * again ([PanelDisplaySize]).
     */
    val appWidthDp: Float = 0f,
    val appHeightDp: Float = 0f,
    /**
     * 0.5.27: the angular area, square degrees, the window's display pixels were sized
     * at; 0 is "its own angles". A depth move keeps the window's metres, so its angles change, but sets this to the area
     * before it, so the app's display keeps its pixels and density and only its picture is drawn smaller or bigger. Mike's
     * own resizes ([sizedByUser]) put it back to 0.
     */
    val displayAreaDeg2: Float = 0f,
) {
    val centreDir: Vec3 get() = Vec3.direction(yawDeg, pitchDeg)
    val centre: Vec3 get() = centreDir * distanceM

    /** Faces the user; right stays horizontal, so panels never roll. */
    val normal: Vec3 get() = -centreDir
    val right: Vec3 get() = (centreDir cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
    val up: Vec3 get() = right cross centreDir

    val widthM: Float get() = 2f * distanceM * tan(widthDeg * DEG / 2f)
    val heightM: Float get() = 2f * distanceM * tan(heightDeg * DEG / 2f)

    /**
     * Pixel size of the virtual display behind the panel, from its angular size. 0.5.2: never a
     * NaN or infinite size (roundToInt throws on them), and a big panel gets fewer pixels per
     * degree so it stays within [PanelLimits.MAX_AREA_PX]: a resize to 100 by 70 degrees used to
     * ask SurfaceFlinger for 2560 by 2240 buffers.
     */
    fun pixelSize(pixelsPerDegree: Float = PanelLimits.PIXELS_PER_DEGREE): Pair<Int, Int> {
        fun safe(deg: Float, fallback: Float) = if (deg.isFinite() && deg > 0f) deg else fallback
        val w = safe(widthDeg, PanelLimits.DEFAULT_WIDTH_DEG) * pixelsPerDegree
        val h = safe(heightDeg, PanelLimits.DEFAULT_HEIGHT_DEG) * pixelsPerDegree
        val capped = w * h > PanelLimits.MAX_AREA_PX
        val shrink = if (capped) kotlin.math.sqrt(PanelLimits.MAX_AREA_PX / (w * h)) else 1f
        // Rounded down when capped, so rounding never takes it back over the cap.
        fun even(v: Float): Int {
            val half = v * shrink / 2f
            return ((if (capped) kotlin.math.floor(half).toInt() else half.roundToInt()) * 2).coerceIn(PanelLimits.MIN_PX, PanelLimits.MAX_PX)
        }
        return Pair(even(w), even(h))
    }

    fun model(): Mat4 = Mat4.basis(centre, right, up, normal, widthM, heightM)

    // 0.5.20: the title bar and its buttons' quads are gone; the window's controls float above it ([WindowControls]).

    /** The resize handle at the bottom right corner. */
    fun cornerModel(): Mat4 {
        val s = PanelLimits.CORNER_FRACTION * minOf(widthM, heightM)
        return Mat4.basis(centre + right * (widthM / 2f) - up * (heightM / 2f), right, up, normal, s, s)
    }

    val isApp: Boolean get() = packageName.isNotEmpty()

    /** 0.5.21: Mike sized this window: the app takes the window's new shape and size ([PanelDisplaySize.naturalDp]). */
    fun sizedByUser(): SpacePanel {
        val (w, h) = PanelDisplaySize.naturalDp(this)
        return copy(appWidthDp = w, appHeightDp = h, displayAreaDeg2 = 0f)
    }

    /** 0.5.21: the app's size set if it was not yet (a new window, an old save). */
    fun withAppSize(): SpacePanel = if (appWidthDp > 0f && appHeightDp > 0f) this else sizedByUser()
}

object PanelLimits {
    /** The windows a Space holds unless Windows in the Space says more (1.6.20); six since the first version. */
    const val MAX_PANELS = 6

    /** 1.6.20: the most Windows in the Space offers (six PC screens and six apps). */
    const val MOST_PANELS = 12

    /** 1.6.20: the windows a Space holds now ([MAX_PANELS] to [MOST_PANELS]), from Windows in the Space. */
    @Volatile var maxPanels: Int = MAX_PANELS
        set(value) { field = value.coerceIn(MAX_PANELS, MOST_PANELS) }
    const val DEFAULT_DISTANCE = 1.4f

    /**
     * 0.5.26 (Mike: "push windows even further away and bring them even closer"): 0.3 to 15 m, for every path (the
     * Further and Closer buttons, the depth swipes, both trackpads, hands and a saved layout). 0.5.25 had 0.6 to 4 m
     * for hands and 1.3 to 3.8 m for the trackpads. A window keeps its
     * apparent size at any distance ([PadWindowMath.atDistance]); side by side keeps close ones comfortable
     * ([StereoComfort]). 0.5.27: a window keeps its size in metres again, so it looks smaller further away.
     */
    const val MIN_DISTANCE = 0.3f
    const val MAX_DISTANCE = 15f
    const val DEFAULT_WIDTH_DEG = 40f
    const val DEFAULT_HEIGHT_DEG = 22.5f
    const val MIN_SIZE_DEG = 12f

    /**
     * 0.5.27: the smallest a window may look after a depth move, so a 1.4 m window
     * really shrinks at 15 m (5.3 degrees there). Mike's own resizes keep [MIN_SIZE_DEG].
     */
    const val DEPTH_MIN_SIZE_DEG = 3f
    const val MAX_WIDTH_DEG = 100f
    const val MAX_HEIGHT_DEG = 70f
    const val MAX_PITCH = 70f
    /**
     * 0.5.11: 44 (was 32). Mike: "app content in the floating windows is blurry". 32 was the
     * glasses' own density (1920 px over the Aura's 61.4 degrees is 31.3 px a degree), so each glasses
     * pixel sampled the panel at about 1.02 texels: a bilinear lookup at an arbitrary sub-pixel
     * offset, which averages neighbouring texels and softens every glyph edge (the worst case of
     * resampling, a half texel blur). XR compositors render panels above the display's density and
     * filter down ([PanelSharpness]): 1.4 times the glasses' density, 1760 by 990 for the 40 by 22.5
     * degree default, sampled bilinearly from the full size level (no mip blend below 1.9 times).
     */
    const val PIXELS_PER_DEGREE = 44f
    const val MIN_PX = 320
    const val MAX_PX = 2560
    /** 0.5.2: at most a 2560 by 1440 display's pixels behind one panel. */
    const val MAX_AREA_PX = 2560f * 1440f
    const val CORNER_FRACTION = 0.12f
}

/**
 * Which part of a panel a ray hit. [BACK] is the Back button (0.4.1); 0.5.17: [RESET] and [CLOSE]
 * are the window's Reset and Close buttons. 0.5.20: there is no title bar; [TITLE_BAR] is the Move pill in the
 * middle of the window's controls ([WindowControls]), which picks the window up as the bar did.
 */
enum class PanelZone { BODY, TITLE_BAR, CORNER, BACK, REFRESH, RESET, CLOSE, WIDE_VIEW, FIT_VIDEO, IMMERSIVE, SWITCH_APP }

data class PanelHit(
    val panel: SpacePanel,
    val zone: PanelZone,
    /** 0..1 across the panel body, left to right; outside 0..1 on the title bar or corner. */
    val u: Float,
    /** 0..1 down the panel body, top to bottom. */
    val v: Float,
    val distance: Float,
    val point: Vec3,
    /** 0.5.75: on the grab band round the window's outside ([PanelHitTester.FRAME_DEG]); [zone] is then [PanelZone.TITLE_BAR]. */
    val frame: Boolean = false,
) {
    /** The pixel on the panel's virtual display, clamped to it. */
    fun pixel(width: Int, height: Int): Pair<Float, Float> =
        Pair((u.coerceIn(0f, 1f) * width).coerceAtMost(width - 1f), (v.coerceIn(0f, 1f) * height).coerceAtMost(height - 1f))
}

data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float): Vec3 = origin + dir * t

    /** Distance along the ray to the panel's plane, or null when it is parallel or behind. */
    fun planeDistance(panel: SpacePanel): Float? {
        val n = panel.normal
        val denom = dir dot n
        if (abs(denom) < 1e-6f) return null
        val t = ((panel.centre - origin) dot n) / denom
        return if (t > 0f) t else null
    }
}

object PanelHitTester {
    /** How far around the body the title bar and corner handle still catch the ray (fractions). */
    private const val CORNER_REACH = 0.08f

    /*
     * 0.5.75 (Mike: "i should also be able to drag windows around from there outsides similar to how it is for
     * android xr"): a band round the window's outside grabs it as the Move pill does ([PanelZone.TITLE_BAR], [PanelHit.frame]).
     * It starts [FRAME_INSET_DEG] out and is [FRAME_DEG] wide; the controls and the resize corner win where they are.
     * 0.5.78 (Mike: "sometimes it doesnt appear even though my cursor dot is correctly on the outside close to the window
     * border"): 0.5.75 started it 1.5 deg out, past the whole press margin, so the first 1.5 deg outside the border did
     * nothing on hover. It starts 0.3 deg out now, and a press's margin round the body's sides is cut to that (the
     * controls keep the full margin): a press just outside the border grabs, as in Android XR.
     */
    const val FRAME_INSET_DEG = 0.3f
    const val FRAME_DEG = 1.6f

    /**
     * 1.1.15 (John Davis: the blue resize handle covered an app's own button at the window's bottom right): the handle
     * catches the pointer outside the window, where it is drawn ([PanelCurve.cornerBracket]), and only this far inside its
     * very edge. 0.5.20 to 1.1.14 took 12 % of the window's smaller side inside the corner, over the app.
     */
    const val CORNER_EDGE_DEG = 0.3f

    /**
     * [marginDeg] widens the body and title bar by that many degrees on every side (0.3.6, for
     * presses), so a press just outside an icon at the panel's edge still lands; u and v then
     * run a little past 0..1 and are clamped when turned into pixels.
     */
    fun hit(ray: Ray, panel: SpacePanel, marginDeg: Float = 0f): PanelHit? {
        // 0.5.19: on the window's surface, a cylinder when it is curved ([PanelCurve.intersect]); x
        // and y are fractions of the body along that surface, so every zone below follows the curve.
        val s = PanelCurve.intersect(ray, panel) ?: return null
        val t = s.t
        val p = ray.at(t)
        val x = s.x // -0.5..0.5 on the body
        val y = s.y
        val u = x + 0.5f
        val v = 0.5f - y
        // 0.5.78: the body's press margin stops where the grab band starts; the controls keep the whole margin.
        val cmx = marginDeg / panel.widthDeg
        val cmy = marginDeg / panel.heightDeg
        val mx = minOf(marginDeg, FRAME_INSET_DEG) / panel.widthDeg
        val my = minOf(marginDeg, FRAME_INSET_DEG) / panel.heightDeg
        val bodyW = PanelCurve.arcWidthM(panel)
        val cornerX = PanelLimits.CORNER_FRACTION * minOf(bodyW, panel.heightM) / bodyW
        val cornerY = PanelLimits.CORNER_FRACTION * minOf(bodyW, panel.heightM) / panel.heightM
        val across = (-0.5f - mx)..(0.5f + mx)
        // 0.5.20: no title bar. The window's controls float apart from it, a gap above its top edge
        // ([WindowControls]); the body reaches up to its own edge (and the press margin), never into them.
        val controlsBottom = 0.5f + WindowControls.layout(panel).gapM / panel.heightM.coerceAtLeast(1e-4f)
        // 0.5.21: Wide view floats the same gap under the bottom edge.
        val broadTop = -0.5f - WindowControls.layout(panel).gapM / panel.heightM.coerceAtLeast(1e-4f)
        // 0.5.75: the grab band, outside the press margin, inside the band's outer edge.
        fun frame(): PanelHit? {
            val inX = FRAME_INSET_DEG / panel.widthDeg
            val inY = FRAME_INSET_DEG / panel.heightDeg
            val outX = (FRAME_INSET_DEG + FRAME_DEG) / panel.widthDeg
            val outY = (FRAME_INSET_DEG + FRAME_DEG) / panel.heightDeg
            val ax = abs(x)
            val ay = abs(y)
            if (ax > 0.5f + outX || ay > 0.5f + outY) return null
            if (ax <= 0.5f + inX && ay <= 0.5f + inY) return null
            // 0.5.78: not up into the controls' row above, nor down into Wide view's below (their gaps catch nothing, 0.5.20).
            if (y > controlsBottom || (panel.isApp && y < broadTop)) return null
            return PanelHit(panel, PanelZone.TITLE_BAR, u, v, t, p, frame = true)
        }
        // 1.1.15: the handle's own area is outside the body, or within [CORNER_EDGE_DEG] of its edge.
        val edgeX = CORNER_EDGE_DEG / panel.widthDeg
        val edgeY = CORNER_EDGE_DEG / panel.heightDeg
        val zone = when {
            x in (0.5f - cornerX)..(0.5f + CORNER_REACH) && y in (-0.5f - CORNER_REACH)..(-0.5f + cornerY) &&
                (x >= 0.5f - edgeX || y <= -0.5f + edgeY) -> PanelZone.CORNER
            y > 0.5f -> WindowControls.at(panel, x, y, cmx, cmy)?.zone
                ?: if (x in across && y <= minOf(0.5f + my, controlsBottom)) PanelZone.BODY else return frame()
            y < -0.5f && panel.isApp -> WindowControls.at(panel, x, y, cmx, cmy)?.zone
                ?: if (x in across && y >= maxOf(-0.5f - my, broadTop)) PanelZone.BODY else return frame()
            x in across && y >= -0.5f - my -> PanelZone.BODY
            else -> return frame()
        }
        return PanelHit(panel, zone, u, v, t, p)
    }

    /**
     * The frontmost panel the ray hits: the one drawn last among those hit (0.4.3). The order
     * is [PanelOrder.backToFront], the same one the renderer draws in: each panel's own
     * distance (to [PanelOrder.TIE_M]), then the most recently raised or focused. [panels] is
     * in raise order, the last raised last, as [SpaceLayout] keeps it. 0.4.2 sorted hits by the
     * distance along the ray instead, which for two panels at different angles is not the
     * drawing order, so a pinch on Clock drawn in front could land on Settings behind it.
     */
    fun nearest(ray: Ray, panels: List<SpacePanel>, marginDeg: Float = 0f): PanelHit? {
        for (panel in PanelOrder.frontToBack(panels)) hit(ray, panel, marginDeg)?.let { return it }
        return null
    }

    /**
     * The panel whose body the ray passes closest to, and how many degrees outside the body it
     * passes (0 when inside), or null when no panel faces the ray at all.
     */
    fun closest(ray: Ray, panels: List<SpacePanel>): Pair<SpacePanel, Float>? = panels.mapNotNull { panel ->
        val s = PanelCurve.intersect(ray, panel) ?: return@mapNotNull null
        val x = s.x
        val y = s.y
        val off = kotlin.math.hypot((abs(x) - 0.5f).coerceAtLeast(0f) * panel.widthDeg, (abs(y) - 0.5f).coerceAtLeast(0f) * panel.heightDeg)
        panel to off
    }.minByOrNull { it.second }
}

/** What a press in the Space landed on, for the log (0.3.6: a miss used to log nothing). */
data class PressReport(
    val zone: PanelZone?,
    val panelId: Int?,
    val label: String?,
    val u: Float,
    val v: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val nearestId: Int?,
    val nearestLabel: String?,
    val nearestOffDeg: Float?,
    /**
     * 0.5.25: for a press that hit nothing, the nearest window control or body by angle
     * ([PressMiss]), its own line; null for a press that hit something.
     */
    val missLine: String? = null,
) {
    fun line(): String {
        val aim = "yaw %.1f, pitch %.1f".fmt(yawDeg, pitchDeg)
        val what = when (zone) {
            PanelZone.BODY -> "panel body of %s (panel %d) at u %.2f, v %.2f".fmt(label, panelId, u, v)
            PanelZone.TITLE_BAR -> "title bar of %s (panel %d)".fmt(label, panelId)
            PanelZone.CORNER -> "corner handle of %s (panel %d)".fmt(label, panelId)
            PanelZone.BACK -> "Back button of %s (panel %d)".fmt(label, panelId)
            PanelZone.REFRESH -> "Refresh button of %s (panel %d)".fmt(label, panelId)
            PanelZone.SWITCH_APP -> "Switch app button of %s (panel %d)".fmt(label, panelId)
            PanelZone.RESET -> "Reset button of %s (panel %d)".fmt(label, panelId)
            PanelZone.CLOSE -> "Close button of %s (panel %d)".fmt(label, panelId)
            PanelZone.WIDE_VIEW -> "Wide view button of %s (panel %d)".fmt(label, panelId)
            PanelZone.FIT_VIDEO -> "Fit video button of %s (panel %d)".fmt(label, panelId)
            PanelZone.IMMERSIVE -> "Immersive button of %s (panel %d)".fmt(label, panelId)
            null -> "nothing"
        }
        val nearest = when {
            nearestId == null -> "no panel faces the ray"
            nearestOffDeg == null || nearestOffDeg <= 0f -> "nearest %s (panel %d)".fmt(nearestLabel, nearestId)
            else -> "nearest %s (panel %d), %.1f deg outside its body".fmt(nearestLabel, nearestId, nearestOffDeg)
        }
        return "Space press at $aim: $what; $nearest"
    }

    companion object {
        fun of(ray: Ray, hit: PanelHit?, panels: List<SpacePanel>): PressReport {
            val a = Angles.of(ray.dir)
            val closest = PanelHitTester.closest(ray, panels)
            return PressReport(
                hit?.zone, hit?.panel?.id, hit?.panel?.label, hit?.u ?: 0f, hit?.v ?: 0f, a.yawDeg, a.pitchDeg,
                closest?.first?.id, closest?.first?.label, closest?.second,
                missLine = if (hit == null) PressMiss.line(PressMiss.nearest(ray, panels)) else null,
            )
        }
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

/**
 * The set of panels around the user and the maths to place, move and resize them. Immutable:
 * every change returns a new layout, which is what gets saved.
 */
data class SpaceLayout(
    val panels: List<SpacePanel> = emptyList(),
    /** 0.5.19: the whole Space wrapped round Mike as one cylinder ([SpaceWrap]), or null. */
    val wrap: SpaceWrap? = null,
) {
    val full: Boolean get() = panels.size >= PanelLimits.maxPanels

    fun panel(id: Int): SpacePanel? = panels.firstOrNull { it.id == id }

    fun nextId(): Int = (panels.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * Adds a panel straight ahead on [aroundYaw], or as close to it along the arc as it fits
     * without overlapping an open panel ([PanelPlacement]). Returns null when the layout is full.
     */
    fun add(label: String, packageName: String, activityName: String, aroundYaw: Float = 0f, curve: Float = 0f): Pair<SpaceLayout, SpacePanel>? =
        addAt(label, packageName, activityName, aroundYaw, 0f, curve)

    /**
     * Adds a panel centred on [yawDeg], [pitchDeg] (in front of the gaze, for the launcher), or,
     * when that would overlap an open panel, stepped sideways along the arc to the nearest spot
     * that does not (0.4.2: it used to open exactly on top of the panel there). Returns null
     * when the layout is full.
     */
    fun addAt(label: String, packageName: String, activityName: String, yawDeg: Float, pitchDeg: Float, curve: Float = 0f): Pair<SpaceLayout, SpacePanel>? {
        if (full) return null
        // 0.5.19: a new window takes the default curve; in a wrapped Space it joins the wrap.
        val w = wrap
        val wanted = SpacePanel(
            nextId(), label, packageName, activityName, wrapDegrees(yawDeg), pitchDeg.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
            distanceM = w?.radiusM ?: PanelLimits.DEFAULT_DISTANCE,
            curve = if (w != null) w.curve else curve.coerceIn(0f, PanelCurve.MAX),
        )
        val panel = PanelPlacement.place(panels, wanted).withAppSize()
        return Pair(copy(panels = panels + panel), panel)
    }

    /** Panels back to front, the order they are drawn in; the last one is in front of the rest. */
    fun drawOrder(): List<SpacePanel> = PanelOrder.backToFront(panels)

    fun remove(id: Int) = copy(panels = panels.filterNot { it.id == id })

    fun replace(panel: SpacePanel) = copy(panels = panels.map { if (it.id == panel.id) panel else it })

    /**
     * 0.5.21 (opening an app while panel 3 held it made panel 6; Android
     * moved its one task there and panel 3 showed "App closed"): the window that already shows
     * [packageName], or null. An app has one task, so it gets one window.
     */
    fun showing(packageName: String): SpacePanel? = if (packageName.isEmpty()) null else panels.lastOrNull { it.packageName == packageName }

    /** Panel [id] brought round to [yawDeg], [pitchDeg] (in front of the gaze) and raised; its size, depth and app size kept. */
    fun bringTo(id: Int, yawDeg: Float, pitchDeg: Float): SpaceLayout {
        val p = panel(id) ?: return this
        val moved = p.copy(yawDeg = wrapDegrees(yawDeg), pitchDeg = pitchDeg.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH))
        return replace(moved).raise(id)
    }

    /** Brings a panel to the front of the draw and hit order (last drawn). */
    fun raise(id: Int): SpaceLayout {
        val p = panel(id) ?: return this
        return copy(panels = panels.filterNot { it.id == id } + p)
    }

    companion object {
        /** Points the panel's centre at [dir] (from the user), keeping it within the limits. */
        fun moveTo(panel: SpacePanel, dir: Vec3): SpacePanel {
            val a = Angles.of(dir)
            return panel.copy(yawDeg = wrapDegrees(a.yawDeg), pitchDeg = a.pitchDeg.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH))
        }

        fun pushPull(panel: SpacePanel, deltaM: Float): SpacePanel =
            panel.copy(distanceM = (panel.distanceM + deltaM).coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE))

        /**
         * Resizes from the bottom right corner to where [ray] points, keeping the top left corner
         * where it is. Worked in angles (yaw and pitch), the units panels are sized in.
         */
        fun resizeTo(panel: SpacePanel, ray: Ray): SpacePanel {
            val a = Angles.of(ray.dir)
            val left = panel.yawDeg - panel.widthDeg / 2f
            val top = panel.pitchDeg + panel.heightDeg / 2f
            val w = wrapDegrees(a.yawDeg - left).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG)
            val h = (top - a.pitchDeg).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG)
            return panel.copy(
                yawDeg = wrapDegrees(left + w / 2f),
                pitchDeg = (top - h / 2f).coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
                widthDeg = w,
                heightDeg = h,
            ).sizedByUser()
        }

        /** Scales the panel's angular size around its centre (two finger pinch); 0.5.2: a NaN or infinite factor changes nothing. */
        fun scale(panel: SpacePanel, factor: Float): SpacePanel = if (!factor.isFinite() || factor <= 0f) panel else panel.copy(
            widthDeg = (panel.widthDeg * factor).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG),
            heightDeg = (panel.heightDeg * factor).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG),
        ).sizedByUser()
    }
}

/**
 * Saves layouts as plain text, one panel per line, tab separated, so core stays free of a JSON
 * library. Labels and names never contain tabs or newlines (they are replaced if they do).
 */
object LayoutCodec {
    private const val HEADER_V1 = "flipspaces-layout v1"

    /** 0.5.19: a tenth field per window, its curve, and an optional `wrap` line ([SpaceWrap]). */
    private const val HEADER_V2 = "flipspaces-layout v2"

    /** 0.5.21: an eleventh and twelfth field, the app's own size in dp ([SpacePanel.appWidthDp]). */
    private const val HEADER_V3 = "flipspaces-layout v3"

    /** 0.5.27: a thirteenth field, the area the display's pixels were sized at ([SpacePanel.displayAreaDeg2]). */
    private const val HEADER = "flipspaces-layout v4"
    private const val WRAP = "wrap"

    fun encode(layout: SpaceLayout): String = buildString {
        append(HEADER).append('\n')
        layout.panels.forEach { p0 ->
            val p = p0.withAppSize()
            listOf(
                p.id.toString(), clean(p.label), clean(p.packageName), clean(p.activityName),
                p.yawDeg.toString(), p.pitchDeg.toString(), p.distanceM.toString(), p.widthDeg.toString(), p.heightDeg.toString(),
                (if (PanelCurve.isCurved(p)) p.curve else 0f).toString(),
                p.appWidthDp.toString(), p.appHeightDp.toString(), p.displayAreaDeg2.toString(),
            ).joinTo(this, "\t")
            append('\n')
        }
        // The wrap's distance, then each window's own distance and curve to restore: id:distance:curve.
        layout.wrap?.let { w ->
            append(WRAP).append('\t').append(w.radiusM.toString()).append('\t')
            w.saved.entries.joinTo(this, ",") { (id, dc) -> "$id:${dc.first}:${dc.second}" }
            // 0.5.28: the wrap's own curve, a fourth field (older saves without it read as fully curved).
            append('\t').append(w.curve.toString())
            append('\n')
        }
    }

    /** Lines that do not parse are skipped, so an old or damaged save never crashes the Space. */
    fun decode(text: String?): SpaceLayout {
        if (text.isNullOrBlank()) return SpaceLayout()
        val lines = text.lines()
        val header = lines.firstOrNull()?.trim()
        if (header != HEADER && header != HEADER_V3 && header != HEADER_V2 && header != HEADER_V1) return SpaceLayout()
        val fields = when (header) {
            HEADER_V1 -> 9
            HEADER_V2 -> 10
            HEADER_V3 -> 12
            else -> 13
        }
        val panels = lines.drop(1).filterNot { it.startsWith(WRAP + "\t") }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != fields) return@mapNotNull null
            runCatching {
                SpacePanel(
                    id = f[0].toInt(), label = f[1], packageName = f[2], activityName = f[3],
                    yawDeg = wrapDegrees(f[4].toFloat()),
                    pitchDeg = f[5].toFloat().coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
                    distanceM = f[6].toFloat().coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE),
                    // 0.5.27: a window pushed far looks smaller than Mike's own 12 degree minimum.
                    widthDeg = f[7].toFloat().coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG),
                    heightDeg = f[8].toFloat().coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG),
                    curve = if (fields >= 10) curveOf(f[9]) else 0f,
                    appWidthDp = if (fields >= 12) dpOf(f[10]) else 0f,
                    appHeightDp = if (fields >= 12) dpOf(f[11]) else 0f,
                    displayAreaDeg2 = if (fields >= 13) f[12].toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..(PanelLimits.MAX_WIDTH_DEG * PanelLimits.MAX_HEIGHT_DEG) } ?: 0f else 0f,
                ).takeIf { it.yawDeg.isFinite() && it.pitchDeg.isFinite() }?.withAppSize()
            }.getOrNull()
        }.distinctBy { it.id }.take(PanelLimits.MOST_PANELS)
        val wrap = if (header != HEADER_V1) lines.firstOrNull { it.startsWith(WRAP + "\t") }?.let(::wrapOf) else null
        return SpaceLayout(panels, wrap)
    }

    /** An app size in dp, or 0 (from the window's angles) when it is not a sane one. */
    private fun dpOf(s: String): Float = s.toFloatOrNull()?.takeIf { it.isFinite() && it in 100f..10_000f } ?: 0f

    private fun curveOf(s: String): Float = s.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, PanelCurve.MAX)?.takeIf { it > PanelCurve.FLAT_EPS } ?: 0f

    private fun wrapOf(line: String): SpaceWrap? = runCatching {
        val f = line.split('\t')
        val r = f[1].toFloat().takeIf { it.isFinite() }?.coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE) ?: return null
        val saved = f.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { e ->
            val p = e.split(':')
            if (p.size != 3) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            val d = p[1].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE) ?: return@mapNotNull null
            id to (d to curveOf(p[2]))
        }.toMap()
        val curve = f.getOrNull(3)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, PanelCurve.MAX) ?: PanelCurve.MAX
        SpaceWrap(r, saved, curve)
    }.getOrNull()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}

/**
 * Which panel is in front (0.4.2, one shared order since 0.4.3). Drawing and hit testing use
 * the same order: the nearer panel (by its own distance from the user) first, and between
 * panels at the same distance (to [TIE_M]) the one raised or focused most recently. Without depth testing the panel drawn last covers the others, so a pinch
 * always lands on the panel Mike sees.
 */
object PanelOrder {
    /** Distances closer than this are a tie, broken by raise order: 1 cm. */
    const val TIE_M = 0.01f

    private fun bucket(d: Float): Long = kotlin.math.round(d / TIE_M).toLong()

    /**
     * [panels] (in raise order) back to front, the order they are drawn in: farthest first by
     * each panel's own distance, ties by raise order. The one key for drawing and hit testing.
     */
    fun backToFront(panels: List<SpacePanel>): List<SpacePanel> =
        panels.withIndex().sortedWith(compareByDescending<IndexedValue<SpacePanel>> { bucket(it.value.distanceM) }.thenBy { it.index }).map { it.value }

    /** [backToFront] reversed: the panel drawn in front first, the order hits are tried in. */
    fun frontToBack(panels: List<SpacePanel>): List<SpacePanel> = backToFront(panels).asReversed()
}

/**
 * Where a new panel goes (0.4.2): where it was asked for when nothing open is in the way,
 * otherwise stepped sideways along the arc, right then left in [STEP_DEG] steps, to the
 * nearest spot clear of every open panel by [GAP_DEG]; when a whole row is taken, a row above,
 * then below. Sizes are angular, so panels at different distances still count as overlapping
 * when one would hide the other.
 */
object PanelPlacement {
    const val GAP_DEG = 2f
    const val STEP_DEG = 2f

    /** Pitch from the panel's bottom edge to its title bar's top. */
    fun verticalSpan(p: SpacePanel): ClosedFloatingPointRange<Float> {
        val top = p.pitchDeg + p.heightDeg / 2f + p.heightDeg * WindowControls.aboveShare(p)
        // 0.5.21: and Wide view under an app window.
        return (p.pitchDeg - p.heightDeg / 2f - p.heightDeg * WindowControls.belowShare(p))..top
    }

    /** Half the panel's width in yaw degrees: wider than its angular width away from the horizon. */
    private fun halfYaw(p: SpacePanel): Float =
        p.widthDeg / 2f / kotlin.math.cos(p.pitchDeg.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH) * DEG)

    fun overlaps(a: SpacePanel, b: SpacePanel, gapDeg: Float = GAP_DEG): Boolean {
        if (angleDiff(a.yawDeg, b.yawDeg) >= halfYaw(a) + halfYaw(b) + gapDeg) return false
        val va = verticalSpan(a)
        val vb = verticalSpan(b)
        return va.start < vb.endInclusive + gapDeg && vb.start < va.endInclusive + gapDeg
    }

    fun free(panels: List<SpacePanel>, candidate: SpacePanel): Boolean = panels.none { it.id != candidate.id && overlaps(it, candidate) }

    fun place(panels: List<SpacePanel>, wanted: SpacePanel): SpacePanel {
        if (free(panels, wanted)) return wanted
        val rowStep = (verticalSpan(wanted).let { it.endInclusive - it.start }) + GAP_DEG
        for (row in listOf(0, 1, -1, 2, -2)) {
            val pitch = wanted.pitchDeg + row * rowStep
            if (row != 0 && kotlin.math.abs(pitch) > PanelLimits.MAX_PITCH) continue
            val onRow = wanted.copy(pitchDeg = pitch)
            sideways(panels, onRow)?.let { return it }
        }
        return wanted
    }

    /** The nearest free spot on [onRow]'s row, right first at equal steps; null when the row is full. */
    private fun sideways(panels: List<SpacePanel>, onRow: SpacePanel): SpacePanel? {
        if (free(panels, onRow)) return onRow
        val steps = (180f / STEP_DEG).toInt()
        for (k in 1..steps) for (sign in intArrayOf(1, -1)) {
            val c = onRow.copy(yawDeg = wrapDegrees(onRow.yawDeg + sign * k * STEP_DEG))
            if (free(panels, c)) return c
        }
        return null
    }
}
