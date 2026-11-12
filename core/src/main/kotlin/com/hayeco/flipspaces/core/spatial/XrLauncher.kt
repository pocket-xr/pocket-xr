package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.UiText
import com.hayeco.flipspaces.core.pointer.Phase
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * 0.5.25 (Mike: the Android XR launcher): "The app launcher must look exactly like, and work exactly
 * like, the Android XR app launcher, with a perfectly transparent background." Our own launcher in that
 * style, with no code, images or icon files from anyone else; the sizes, timings and curves below give it
 * that look and feel.
 * This file is the pure model: the panel's place, the grid maths, paging, hover, focus, the long press
 * menu, search, open and close, and the hit tests. The renderer draws what it says (LauncherGl.kt).
 */

/**
 * 0.5.25 (Mike: the Android XR launcher): CSS / PathInterpolator cubic-bezier(x1, y1, x2, y2) from
 * (0, 0) to (1, 1). [at] takes the time fraction x and gives the eased value y: t is solved from x by
 * Newton's method, with bisection when Newton does not settle (flat spots of the curve).
 */
class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    private fun bx(t: Double): Double = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t
    private fun by(t: Double): Double = 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t
    private fun dbx(t: Double): Double = 3 * (1 - t) * (1 - t) * x1 + 6 * (1 - t) * t * (x2 - x1) + 3 * t * t * (1 - x2)

    /** The curve's t for time fraction [x] (0..1). */
    fun solveT(x: Float): Double {
        val target = x.toDouble().coerceIn(0.0, 1.0)
        var t = target
        repeat(8) {
            val err = bx(t) - target
            if (abs(err) < 1e-7) return t
            val d = dbx(t)
            if (abs(d) < 1e-6) return@repeat
            t = (t - err / d).coerceIn(0.0, 1.0)
        }
        if (abs(bx(t) - target) < 1e-7) return t
        var lo = 0.0
        var hi = 1.0
        t = target
        repeat(60) {
            val v = bx(t)
            if (abs(v - target) < 1e-7) return t
            if (v < target) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return t
    }

    fun at(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return by(solveT(x)).toFloat()
    }

    companion object {
        /** The open (and close) fade, Material's standard decelerate. */
        val DECELERATE = CubicBezier(0f, 0f, 0f, 1f)

        /** Hover enter and exit, PathInterpolator(0.2, 0, 0, 1). */
        val HOVER = CubicBezier(0.2f, 0f, 0f, 1f)

        /** The launched window's alpha. */
        val WINDOW_ALPHA = CubicBezier(0.4f, 0f, 0f, 1f)

        /** The launched window's scale. */
        val WINDOW_SCALE = CubicBezier(0.4f, 0.3f, 0f, 1f)
    }
}

/** 0.5.25: a rectangle in the launcher panel's dp, x right from the panel's left edge, y down from its top. */
data class DpRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h

    /** Scaled by [s] about ([px], [py]), then moved by [dx]. */
    fun transformed(px: Float, py: Float, s: Float, dx: Float = 0f): DpRect =
        DpRect(px + (x - px) * s + dx, py + (y - py) * s, w * s, h * s)

    /** Scaled by [s] about its own centre. */
    fun scaled(s: Float): DpRect = DpRect(cx - w * s / 2f, cy - h * s / 2f, w * s, h * s)
}

/**
 * 0.5.25 (Mike: the Android XR launcher): the launcher's layout in dp.
 * One 1450 x 950 dp panel at 1440 dp a metre; All apps pages of 5 x 3 cells of 200 x 208 dp from
 * 225 dp in and 170 dp down; page 0, the home tray, with its round buttons and up to 10 pinned apps.
 */
object LauncherGrid {
    const val PANEL_W = 1450f
    const val PANEL_H = 950f

    /** 1440 dp a metre (1.44 dp a millimetre), the panel's scale in the room. */
    const val DP_PER_M = 1440f
    const val COLUMNS = 5
    const val ROWS = 3
    const val PAGE_SIZE = COLUMNS * ROWS
    const val CELL_W = 200f
    const val CELL_H = 208f
    const val MARGIN_X = 225f
    const val MARGIN_TOP = 170f
    const val ICON = 108f
    const val ICON_TOP = 18f
    const val LABEL_GAP = 10f
    const val LABEL_W = 160f
    const val LABEL_SP = 16f
    const val LABEL_LINE_SP = 22f
    const val LABEL_LINES = 2

    /** The labels' light grey, #E3E2E6. */
    const val LABEL_COLOR = 0xFFE3E2E6.toInt()
    const val PAGER_H = 830f

    /** The pager's fading edge and the page shift of the page transform. */
    const val FADE_EDGE = 225f
    const val PAGE_SHIFT = 225f

    const val DOT = 10f
    const val DOT_STEP = 24f
    const val PIN_W = 12f
    const val PIN_H = 16f
    const val DOT_INACTIVE_ALPHA = 0.4f

    /** The indicator's centre: just under the 830 dp pager (836 to 838 dp). */
    const val INDICATOR_Y = PAGER_H + PIN_H / 2f

    /** The search card, top-centred above the grid. */
    const val SEARCH_W = 400f
    const val SEARCH_H = 64f
    const val SEARCH_TOP = 64f

    /** The home tray's date and clock: their centres. */
    const val DATE_Y = 24f
    const val CLOCK_Y = 98f
    const val DATE_SP = 20f
    const val CLOCK_SP = 86f
    const val TRAY_TEXT_COLOR = 0xFFE3E2E4.toInt()

    /** The round system buttons: 94 dp, 20 dp apart, centred 269 dp down. */
    const val BUTTON = 94f
    const val BUTTON_GAP = 20f
    const val BUTTON_Y = 269f

    /** The pinned rows' first cell top: icons centred 422 dp down. */
    const val PINNED_TOP = 350f
    const val PIN_LIMIT = 10

    /** The keyboard focus' rounded cell. */
    const val FOCUS_RADIUS = 16f
    const val FOCUS_COLOR = 0xFF0B0E13.toInt()

    val widthM: Float get() = PANEL_W / DP_PER_M
    val heightM: Float get() = PANEL_H / DP_PER_M

    /** The panel's angular width and height at [distanceM], degrees (about 53 x 37 at 1 m). */
    fun angularDeg(distanceM: Float = XrLauncher.DISTANCE_M): Pair<Float, Float> =
        Pair(2f * kotlin.math.atan(widthM / 2f / distanceM) * RAD, 2f * kotlin.math.atan(heightM / 2f / distanceM) * RAD)

    /** Cell [index] (0..14) of an All apps page, filled row by row from the top left. */
    fun cell(index: Int): DpRect {
        val col = index % COLUMNS
        val row = index / COLUMNS
        return DpRect(MARGIN_X + CELL_W * col, MARGIN_TOP + CELL_H * row, CELL_W, CELL_H)
    }

    /** The icon in [cell]: 108 dp, centred, 18 dp from the cell's top. */
    fun icon(cell: DpRect): DpRect = DpRect(cell.cx - ICON / 2f, cell.y + ICON_TOP, ICON, ICON)

    /** The label in [cell]: 160 dp wide, two 22 sp lines, 10 dp under the icon. */
    fun label(cell: DpRect): DpRect = DpRect(cell.cx - LABEL_W / 2f, cell.y + ICON_TOP + ICON + LABEL_GAP, LABEL_W, LABEL_LINE_SP * LABEL_LINES)

    /** All apps pages for [apps] apps: chunks of 15, at least one (an empty search shows one empty page). */
    fun appPages(apps: Int): Int = maxOf(1, ceil(apps / PAGE_SIZE.toFloat()).toInt())

    /** Pinned rows: up to 5 in one row, then 3+3, 4+3, 4+4, 5+4, 5+5. */
    fun pinnedRows(n: Int): List<Int> {
        val count = n.coerceIn(0, PIN_LIMIT)
        if (count == 0) return emptyList()
        if (count <= COLUMNS) return listOf(count)
        val first = (count + 1) / 2
        return listOf(first, count - first)
    }

    /** The cells of [n] pinned apps on the home tray, each row centred. */
    fun pinnedCells(n: Int): List<DpRect> {
        val out = ArrayList<DpRect>()
        pinnedRows(n).forEachIndexed { row, inRow ->
            for (i in 0 until inRow) {
                val cx = PANEL_W / 2f + (i - (inRow - 1) / 2f) * CELL_W
                out += DpRect(cx - CELL_W / 2f, PINNED_TOP + row * CELL_H, CELL_W, CELL_H)
            }
        }
        return out
    }

    /** [n] round system buttons, centred, 94 dp with 20 dp gaps. */
    fun buttons(n: Int): List<DpRect> = (0 until n).map { i ->
        val cx = PANEL_W / 2f + (i - (n - 1) / 2f) * (BUTTON + BUTTON_GAP)
        DpRect(cx - BUTTON / 2f, BUTTON_Y - BUTTON / 2f, BUTTON, BUTTON)
    }

    /** The indicator, one per page, 24 dp apart and centred; page 0's is the 12 x 16 dp pin. None with one page. */
    fun dots(pageCount: Int): List<DpRect> {
        if (pageCount <= 1) return emptyList()
        return (0 until pageCount).map { i ->
            val cx = PANEL_W / 2f + (i - (pageCount - 1) / 2f) * DOT_STEP
            if (i == 0) DpRect(cx - PIN_W / 2f, INDICATOR_Y - PIN_H / 2f, PIN_W, PIN_H) else DpRect(cx - DOT / 2f, INDICATOR_Y - DOT / 2f, DOT, DOT)
        }
    }

    /** The search card. */
    fun search(): DpRect = DpRect(PANEL_W / 2f - SEARCH_W / 2f, SEARCH_TOP, SEARCH_W, SEARCH_H)

    /** The search card's clear cross, its right end (one card height wide). */
    fun searchClear(): DpRect = search().let { DpRect(it.right - SEARCH_H, it.y, SEARCH_H, SEARCH_H) }

    /**
     * The fading edge: content fades out linearly across the 225 dp side margins; [cx] is an
     * item's centre. 1 anywhere between the margins.
     */
    fun edgeFade(cx: Float): Float = (minOf(cx, PANEL_W - cx) / FADE_EDGE).coerceIn(0f, 1f)
}

/**
 * 0.5.25: how one page looks at a pager position: its shift in dp, its scale about the
 * pager's centre, its alpha, and for the home tray the right edge page 1 has reached, past which it is
 * clipped.
 */
data class PageLook(val dx: Float, val scale: Float, val alpha: Float, val clipRight: Float = Float.MAX_VALUE) {
    fun apply(r: DpRect): DpRect = r.transformed(PIVOT_X, PIVOT_Y, scale, dx)

    companion object {
        const val PIVOT_X = LauncherGrid.PANEL_W / 2f
        const val PIVOT_Y = LauncherGrid.PAGER_H / 2f
        private const val STEP = LauncherGrid.PANEL_W - LauncherGrid.PAGE_SHIFT

        /**
         * Page [page] with the pager at [scroll] (0 the home tray, 1 the first All apps page). p is
         * ViewPager2's position, page - scroll. The home tray going left stays put and recedes
         * (a depth transform: alpha min(1.15 + p, 1), scale 0.75 + 0.25 (1 - |p|)); page 1 coming over
         * it slides by p (1450 - 225) dp and is opaque from 45 % of the way, alpha (1 - p) / 0.55 before.
         * Later pages slide by p (1450 - 225) dp at alpha 1.
         */
        fun of(page: Int, scroll: Float): PageLook {
            val p = page - scroll
            if (page == 0) {
                if (p <= -1f) return PageLook(0f, 0.75f, 0f, 0f)
                if (p >= 0f) return PageLook(0f, 1f, 1f)
                val alpha = minOf(1.15f + p, 1f)
                val scale = 0.75f + 0.25f * (1f - abs(p))
                return PageLook(0f, scale, alpha, (1f + p) * STEP)
            }
            val dx = p * STEP
            val alpha = if (page == 1 && p > 0.45f) ((1f - p) / 0.55f).coerceIn(0f, 1f) else 1f
            return PageLook(dx, 1f, alpha)
        }
    }
}

/**
 * 0.5.25: where the launcher panel is: 1.0 m from the head, centred 5 degrees below where the head
 * looked when it opened, facing the head. It stays there; it never follows the head.
 */
data class LauncherPlacement(val yawDeg: Float, val pitchDeg: Float, val distanceM: Float = XrLauncher.DISTANCE_M) {
    val dir: Vec3 get() = Vec3.direction(yawDeg, pitchDeg)
    val centre: Vec3 get() = dir * distanceM
    val right: Vec3 get() = (dir cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
    val up: Vec3 get() = right cross dir

    /** Where [ray] meets the panel's plane, in dp of the panel shown at [scale]; null when it points away. */
    fun toDp(ray: Ray, scale: Float = 1f): Pair<Float, Float>? {
        val d = dir
        val denom = ray.dir dot d
        if (denom <= 1e-6f) return null
        val t = ((centre - ray.origin) dot d) / denom
        if (t <= 0f) return null
        val rel = ray.origin + ray.dir * t - centre
        val s = scale.coerceAtLeast(0.01f)
        val x = LauncherGrid.PANEL_W / 2f + (rel dot right) * LauncherGrid.DP_PER_M / s
        val y = LauncherGrid.PANEL_H / 2f - (rel dot up) * LauncherGrid.DP_PER_M / s
        return Pair(x, y)
    }

    /** The world point of panel dp ([x], [y]) with the panel at [scale] (about its centre). */
    fun toWorld(x: Float, y: Float, scale: Float = 1f): Vec3 =
        centre + right * ((x - LauncherGrid.PANEL_W / 2f) / LauncherGrid.DP_PER_M * scale) + up * ((LauncherGrid.PANEL_H / 2f - y) / LauncherGrid.DP_PER_M * scale)

    /** A unit quad's model for [r] on the panel at [scale]. */
    fun model(r: DpRect, scale: Float = 1f): Mat4 =
        Mat4.basis(toWorld(r.cx, r.cy, scale), right, up, -dir, r.w / LauncherGrid.DP_PER_M * scale, r.h / LauncherGrid.DP_PER_M * scale)

    companion object {
        /** In front of a head looking at [headYaw], [headPitch]: 5 degrees below the gaze, the pitch kept within 35 degrees. */
        fun inFrontOf(headYaw: Float, headPitch: Float): LauncherPlacement =
            LauncherPlacement(wrapDegrees(headYaw), (headPitch - XrLauncher.BELOW_GAZE_DEG).coerceIn(-XrLauncher.MAX_PITCH_DEG, XrLauncher.MAX_PITCH_DEG))
    }
}

/** 0.5.25: the home tray's round buttons, each mapped to something Pocket XR has. */
enum class LauncherButton(private val key: String, private val english: String) {
    /**
     * PC VR (private builds with the stream built in): first in the row, a built-in part of Pocket XR rather than an app
     * among the user's (9 November 2026, Mike: "shouldnt the PC VR tile be with the default app launcher buttons so it
     * doesnt look like just another app"). Drawn with a soft breathing glow.
     */
    PC_VR("spatial_launcher_pc_vr", "PC VR"),

    /** Recentre: brings the Space back in front. */
    RECENTRE("spatial_launcher_recentre", "Recentre"),

    /** Steady mode, beside Recentre, on glasses that have it. */
    STEADY("spatial_launcher_steady", "Steady"),

    /**
     * Pocket XR's 3D / 2D switch. 0.5.45 (Mike: "when users click 3D, they will think that the media they are watching
     * or their apps will be converted into 3D like a 3D movie"): it is the glasses' side by side mode, which gives the
     * windows their distance in the room (each eye sees them from its own side); what an app shows stays flat. Named for
     * that, "Depth", and drawn with its state, On or Off.
     */
    VIEW("spatial_launcher_depth", "Depth (3D side by side)"),

    /**
     * 0.5.46 (Mike: "since the windows presets circle button and the Control Panel circle button open the same window now,
     * just replace the Windows presets circle button with a Notifications button"): the Control Center's Notifications
     * page ([ControlSheets]). 0.5.45's Window presets button opened the Control Center, whose bottom row keeps them.
     */
    NOTIFICATIONS("spatial_page_notifications", "Notifications"),

    /** Opens Pocket XR's Control panel. */
    CONTROLS("spatial_launcher_control_panel", "Control panel"),
    ;

    /** The button's tooltip, in the phone's language. */
    val label: String get() = UiText.get(key, english)
}

/** 0.5.25: one thing on a launcher page: an app (All apps, or pinned on the home tray) or a round button. */
sealed interface LauncherItem {
    val key: String
    val page: Int

    /** The item's cell (a button's circle box) on its page, before the page's transform. */
    val cell: DpRect

    data class App(val app: AppEntry, override val page: Int, val index: Int, override val cell: DpRect) : LauncherItem {
        override val key: String get() = "$page:${app.key}"
        val pinnedSlot: Boolean get() = page == 0
    }

    data class Button(val button: LauncherButton, override val cell: DpRect) : LauncherItem {
        override val key: String get() = "button:${button.name}"
        override val page: Int get() = 0
    }
}

/** 0.5.25: an item where it is drawn now: its page's [look] (shift, scale) and the item's [alpha] (page alpha and fading edge). */
data class PlacedItem(val item: LauncherItem, val look: PageLook, val alpha: Float) {
    /** The cell (a button's circle box) after the page's transform. */
    val rect: DpRect get() = look.apply(item.cell)
    val scale: Float get() = look.scale
    val icon: DpRect get() = when (item) {
        is LauncherItem.App -> look.apply(LauncherGrid.icon(item.cell))
        is LauncherItem.Button -> rect
    }
    val label: DpRect get() = when (item) {
        is LauncherItem.App -> look.apply(LauncherGrid.label(item.cell))
        is LauncherItem.Button -> rect
    }
}

/** 0.5.25: the long press menu's items. Uninstall is left out: Pocket XR cannot start the uninstall flow. */
enum class LauncherMenuItem(private val key: String, private val english: String) {
    APP_INFO("spatial_launcher_app_info", "App Info"),
    PIN("spatial_launcher_pin_app", "Pin App"),
    UNPIN("spatial_launcher_unpin_app", "Unpin App"),
    ;

    /** The row's words, in the phone's language. */
    val label: String get() = UiText.get(key, english)
}

/**
 * 0.5.25: the open long press menu for [app], anchored on its icon's centre ([anchorX], [anchorY], dp):
 * a 240 dp card, rows 48 dp with 8 dp margins (64 dp a row), centred on the icon and above it.
 */
data class LauncherMenu(val app: AppEntry, val itemKey: String, val anchorX: Float, val anchorY: Float, val items: List<LauncherMenuItem>, val openedMs: Long) {
    var closingMs: Long? = null

    val card: DpRect get() {
        val h = items.size * LauncherMenuLook.ROW_PITCH
        return DpRect(anchorX - LauncherMenuLook.WIDTH / 2f, anchorY - LauncherMenuLook.ABOVE_ICON_CENTRE - h, LauncherMenuLook.WIDTH, h)
    }

    fun row(i: Int): DpRect = card.let { c -> DpRect(c.x + LauncherMenuLook.ROW_MARGIN, c.y + LauncherMenuLook.ROW_MARGIN + i * LauncherMenuLook.ROW_PITCH, c.w - 2 * LauncherMenuLook.ROW_MARGIN, LauncherMenuLook.ROW_H) }

    /** Fades in after 200 ms; out over 100 ms once [closingMs] is set. */
    fun alpha(nowMs: Long): Float {
        closingMs?.let { return (1f - (nowMs - it) / LauncherMenuLook.FADE_OUT_MS.toFloat()).coerceIn(0f, 1f) }
        return ((nowMs - openedMs - LauncherMenuLook.FADE_IN_DELAY_MS) / LauncherMenuLook.FADE_IN_MS.toFloat()).coerceIn(0f, 1f)
    }

    fun rowAt(x: Float, y: Float): Int? = items.indices.firstOrNull { row(it).contains(x, y) }
}

/** 0.5.25: the long press menu's look. */
object LauncherMenuLook {
    const val WIDTH = 240f
    const val RADIUS = 32f
    const val COLOR = 0xFF1D2024.toInt()
    const val ROW_H = 48f
    const val ROW_MARGIN = 8f
    const val ROW_PITCH = ROW_H + 2 * ROW_MARGIN
    const val ICON = 24f
    const val ICON_GAP = 12f
    const val PAD_LEFT = 20f
    const val PAD_RIGHT = 32f
    const val TEXT_SP = 14f
    const val HOVER_ALPHA = 0.12f
    const val FOCUS_ALPHA = 0.08f

    /**
     * The card's bottom edge above the icon's centre: the 128 dp card's bottom sits 67 dp above the
     * hovered icon's centre, clear of the icon and its 1.1 hover scale.
     */
    const val ABOVE_ICON_CENTRE = 67f
    const val FADE_IN_DELAY_MS = 200L

    /** The fade after the delay. */
    const val FADE_IN_MS = 150L
    const val FADE_OUT_MS = 100L
}

/** 0.5.25: what the pointer is on, from a ray ([XrLauncher.hit]). */
sealed interface LauncherHit {
    data class Item(val placed: PlacedItem) : LauncherHit
    data class Dot(val page: Int) : LauncherHit
    data class Search(val clear: Boolean) : LauncherHit
    data class MenuRow(val item: LauncherMenuItem) : LauncherHit

    /** The menu's card, between its rows. */
    data object MenuCard : LauncherHit

    /** Anywhere else, on the panel or off it: a tap there closes the launcher. */
    data object Empty : LauncherHit
}

/** 0.5.25: what the pointer did on the launcher besides the press itself ([SpaceAction.Claimed]), from [SpatialPointer]. */
sealed interface LauncherInput {
    /** The pressing pointer's ray now, every frame while it is held. */
    data class Move(val ray: Ray) : LauncherInput

    /** The press ended ([cancel]: the input went away). */
    data class Release(val cancel: Boolean) : LauncherInput

    /** A right click (two finger tap) along [ray]. */
    data class Secondary(val ray: Ray) : LauncherInput

    /** A two finger swipe: the fingers' travel since BEGIN in dp, x right, y down. */
    data class Swipe(val dxDp: Float, val dyDp: Float, val phase: Phase) : LauncherInput

    /** A wheel: the units turned since BEGIN, positive toward the next page. */
    data class Wheel(val units: Float, val phase: Phase) : LauncherInput
}

/** 0.5.25: the keys the launcher takes from a keyboard (DPAD walks, Esc closes). */
enum class LauncherKey { LEFT, RIGHT, UP, DOWN, ENTER, ESCAPE }

/** 0.5.25: what the launcher asks the Space to do. */
sealed interface XrLauncherAction {
    /** Launch [app] into a window where the launcher was ([at]); the launcher has begun closing. */
    data class Launch(val app: AppEntry, val at: LauncherPlacement) : XrLauncherAction

    /** The launcher began closing, for [why]. */
    data class Closed(val why: String) : XrLauncherAction

    data class AppInfo(val app: AppEntry) : XrLauncherAction

    /** The pinned apps changed: save [keys]. */
    data class Pinned(val keys: List<String>) : XrLauncherAction

    /** Pin was chosen with 10 apps pinned. */
    data object PinLimit : XrLauncherAction

    /** A home tray button was pressed (the launcher has begun closing). */
    data class Button(val button: LauncherButton) : XrLauncherAction

    /**
     * 0.5.82 (Mike: "how come in the app launcher, the Search apps bar doesnt work?"): the search card was pressed: the
     * phone's text field and keyboard open, and what is typed there filters the launcher ([setQuery]).
     */
    data object WantsKeyboard : XrLauncherAction
}

/** 0.5.25: an eased value from [from] to [to] since [startMs] (hover's icon scale). */
class Eased(private var value0: Float) {
    private var from = value0
    private var to = value0
    private var startMs = Long.MIN_VALUE / 2

    fun target(v: Float, nowMs: Long, durationMs: Long, curve: CubicBezier) {
        if (v == to) return
        from = at(nowMs, durationMs, curve)
        to = v
        startMs = nowMs
    }

    fun at(nowMs: Long, durationMs: Long, curve: CubicBezier): Float {
        val t = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * curve.at(t)
    }

    val goal: Float get() = to
}

/**
 * 0.5.25 (Mike: "The app launcher must look exactly like, and work exactly like, the Android XR app
 * launcher, with a perfectly transparent background"): our launcher in that style.
 *
 * - One transparent 1450 x 950 dp panel 1.0 m away, 5 degrees below the gaze, placed when it opens and
 *   never following the head ([LauncherPlacement]); no backing, blur, border or scrim.
 * - Page 0, the home tray: date and clock, round buttons for what Pocket XR has, up to 10 pinned apps.
 *   Pages 1+: every app sorted by `label.compareTo` (case-sensitive), 15 a page, 5 x 3 from the top left.
 * - Paging: a drag follows the pointer and settles past 20 % (the floor rule, so going back needs 80 %),
 *   the wheel turns a page every 2.5 units, the depth effect from page 0 to 1, the 225 dp fading edge.
 * - Hover: the icon alone scales to 1.1 over 200 ms; a press is a hover exit. Keyboard focus: a 16 dp
 *   rounded near-black cell.
 * - Open: alpha 0 to 1 and scale 0.95 to 1 over 500 ms, cubic-bezier(0, 0, 0, 1); close: to alpha 0 and
 *   scale 0.95 over 180 ms, then hidden, back on page 0 with the search cleared.
 * - Long press or right click: the App Info and Pin / Unpin menu above the icon.
 * - Search (Pocket XR's own): the 400 x 64 dp card on the All apps pages, filtering the same
 *   sorted list into the same pages; typed from the phone's or a hardware keyboard.
 */
class XrLauncher {
    var isOpen = false
        private set

    var placement = LauncherPlacement(0f, -BELOW_GAZE_DEG)
        private set

    /** Steady mode's view height, degrees, else null: looking up or down shows no more, so the panel fits inside it. */
    var fitViewHeightDeg: Float? = null

    private fun inFront(headYaw: Float, headPitch: Float): LauncherPlacement {
        val view = fitViewHeightDeg ?: return LauncherPlacement.inFrontOf(headYaw, headPitch)
        val fitM = LauncherGrid.heightM / 2f / kotlin.math.tan(view * FIT_VIEW * DEG / 2f)
        return LauncherPlacement(wrapDegrees(headYaw), headPitch, maxOf(DISTANCE_M, fitM))
    }

    /** Every app, sorted. */
    var apps: List<AppEntry> = emptyList()
        private set

    var pinnedKeys: List<String> = emptyList()
        private set

    var query = ""
        private set

    /** The pager's position in pages: 0 the home tray, 1 the first All apps page. */
    var scroll = 0f
        private set

    var menu: LauncherMenu? = null
        private set

    /** The keyboard focus' item key, or null when the pointer was used last. */
    var focusKey: String? = null
        private set

    /** The buttons the home tray shows. */
    var buttons: List<LauncherButton> = LauncherButton.values().filter { it != LauncherButton.STEADY && it != LauncherButton.PC_VR }

    // Open and close.
    private val alphaAnim = Eased(0f)
    private val scaleAnim = Eased(OPEN_SCALE_FROM)
    private var fadeMs = OPEN_MS
    private var hiddenReset = true

    // Paging.
    private var settleFrom = 0f
    private var settleTo = 0f
    private var settleStartMs = Long.MIN_VALUE / 2
    private var settling = false
    private var dragStartScroll: Float? = null
    private var wheelAccum = 0f
    private var wheelLast = 0f
    private var swipeAxis = 0
    private var lastNowMs = 0L

    // Hover.
    private val hoverScale = HashMap<String, Eased>()
    private var hoveredKeys: Set<String> = emptySet()
    private var hoverButtonSince: Pair<String, Long>? = null

    /** The menu row a pointer is on, for its hover pill. */
    var menuHoverRow: Int? = null
        private set

    // The press in progress (one pointer at a time).
    private class Press(val source: Int, val startX: Float?, val startY: Float?, val hit: LauncherHit, val startMs: Long, val withMenu: Boolean) {
        var x: Float? = startX
        var y: Float? = startY
        var dragging = false
        var longPressed = false
        var moved = 0f
    }

    private var press: Press? = null

    val pressing: Boolean get() = press != null

    /** Sets the apps the launcher shows (Pocket XR itself left out by the caller), sorted by label. */
    fun setApps(list: List<AppEntry>) {
        apps = sorted(list.distinctBy { it.key })
        clampScroll()
    }

    fun setPinned(keys: List<String>) {
        pinnedKeys = keys.distinct().take(LauncherGrid.PIN_LIMIT)
    }

    /** The pinned apps on the home tray, in pin order, those installed only. */
    fun pinnedApps(): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        return pinnedKeys.mapNotNull { byKey[it] }.take(LauncherGrid.PIN_LIMIT)
    }

    /** The apps the search matches, in the same sorted order. */
    fun matches(): List<AppEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return apps
        return apps.filter { needle in labelOf(it).lowercase() || needle in it.packageName.lowercase() }
    }

    /** The home tray and the All apps pages. */
    fun pageCount(): Int = 1 + LauncherGrid.appPages(matches().size)

    /** The page the pager rests nearest to. */
    val page: Int get() = Math.round(scroll)

    // ---- open and close ----

    /** Opens in front of a head looking at [headYaw], [headPitch], on the home tray. */
    fun open(headYaw: Float, headPitch: Float, nowMs: Long) {
        resetIfHidden(nowMs)
        placement = inFront(headYaw, headPitch)
        isOpen = true
        hiddenReset = false
        fadeMs = OPEN_MS
        alphaAnim.target(1f, nowMs, fadeMs, CubicBezier.DECELERATE)
        scaleAnim.target(1f, nowMs, fadeMs, CubicBezier.DECELERATE)
        lastNowMs = nowMs
    }

    /** Begins the close; the panel fades out over 180 ms and is then reset to page 0. */
    fun close(nowMs: Long) {
        if (!isOpen) return
        isOpen = false
        fadeMs = CLOSE_MS
        alphaAnim.target(0f, nowMs, fadeMs, CubicBezier.DECELERATE)
        scaleAnim.target(CLOSE_SCALE_TO, nowMs, fadeMs, CubicBezier.DECELERATE)
        menu?.let { if (it.closingMs == null) it.closingMs = nowMs }
        press = null
        dragStartScroll = null
        hoveredKeys = emptySet()
        menuHoverRow = null
    }

    /**
     * The toggle (the Home button, three fingers up, an open palm): opens; closes; or, open but more than
     * [OUT_OF_VIEW_DEG] from where the head looks, recentres in front instead.
     * Returns what it did, for the log.
     */
    fun toggle(headYaw: Float, headPitch: Float, nowMs: Long): String {
        if (!isOpen) {
            open(headYaw, headPitch, nowMs)
            return "opened"
        }
        val away = angleDeg(placement.dir, Vec3.direction(headYaw, headPitch - BELOW_GAZE_DEG))
        if (away > OUT_OF_VIEW_DEG) {
            placement = inFront(headYaw, headPitch)
            return "recentred in front (it was %.0f degrees away)".format(java.util.Locale.ROOT, away)
        }
        close(nowMs)
        return "closed"
    }

    fun alpha(nowMs: Long): Float = alphaAnim.at(nowMs, fadeMs, CubicBezier.DECELERATE)

    fun scale(nowMs: Long): Float = scaleAnim.at(nowMs, fadeMs, CubicBezier.DECELERATE)

    /** Drawn: open, or still fading out. */
    fun visible(nowMs: Long): Boolean = isOpen || alpha(nowMs) > 0.001f

    private fun resetIfHidden(nowMs: Long) {
        if (isOpen || alpha(nowMs) > 0.001f) return
        scroll = 0f
        settling = false
        dragStartScroll = null
        wheelAccum = 0f
        menu = null
        focusKey = null
        query = ""
        hoverScale.clear()
        hiddenReset = true
    }

    // ---- every frame ----

    /**
     * Every frame: the page settles, the long press opens its menu, a faded menu goes, and once the
     * close has faded the launcher goes back to page 0 with the search cleared.
     */
    fun frame(nowMs: Long) {
        lastNowMs = nowMs
        if (settling) {
            val t = ((nowMs - settleStartMs).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            scroll = settleFrom + (settleTo - settleFrom) * eased
            if (t >= 1f) {
                scroll = settleTo
                settling = false
            }
        }
        val p = press
        if (p != null && !p.dragging && !p.longPressed && !p.withMenu && nowMs - p.startMs >= LONG_PRESS_MS) {
            val hit = p.hit
            if (hit is LauncherHit.Item && hit.placed.item is LauncherItem.App) {
                p.longPressed = true
                openMenu(hit.placed, nowMs)
            }
        }
        menu?.let { m -> if (m.closingMs != null && m.alpha(nowMs) <= 0f) menu = null }
        if (!isOpen && !hiddenReset) resetIfHidden(nowMs)
    }

    // ---- layout ----

    /** The items of [page] before its transform. */
    fun itemsOf(page: Int): List<LauncherItem> {
        if (page == 0) {
            val out = ArrayList<LauncherItem>()
            LauncherGrid.buttons(buttons.size).forEachIndexed { i, r -> out += LauncherItem.Button(buttons[i], r) }
            val pins = pinnedApps()
            LauncherGrid.pinnedCells(pins.size).forEachIndexed { i, r -> out += LauncherItem.App(pins[i], 0, i, r) }
            return out
        }
        val shown = matches().drop((page - 1) * LauncherGrid.PAGE_SIZE).take(LauncherGrid.PAGE_SIZE)
        return shown.mapIndexed { i, app -> LauncherItem.App(app, page, i, LauncherGrid.cell(i)) }
    }

    /**
     * Every item drawn at pager position [at] (default: now), back to front (the home tray first, it
     * is at z -1), with its page's transform, the fading edge and the home tray's clip applied.
     */
    fun placed(at: Float = scroll): List<PlacedItem> {
        val out = ArrayList<PlacedItem>()
        val last = pageCount() - 1
        val lo = maxOf(0, floor(at).toInt() - 1)
        val hi = minOf(last, ceil(at).toInt() + 1)
        val pages = (listOf(0) + (lo..hi).filter { it != 0 }).distinct()
        for (page in pages) {
            val look = PageLook.of(page, at)
            if (look.alpha <= 0.001f) continue
            for (item in itemsOf(page)) {
                val r = look.apply(item.cell)
                if (r.right <= 0f || r.x >= LauncherGrid.PANEL_W) continue
                if (page == 0 && r.cx > look.clipRight) continue
                val fade = if (page == 0) 1f else LauncherGrid.edgeFade(r.cx)
                val alpha = look.alpha * fade
                if (alpha <= 0.001f) continue
                out += PlacedItem(item, look, alpha)
            }
        }
        return out
    }

    /** The home tray's date and clock block's alpha and scale (they go with page 0). */
    fun trayLook(): PageLook = PageLook.of(0, scroll)

    /** The search card's alpha, 0 on the home tray, 1 from page 1. */
    fun searchAlpha(): Float = scroll.coerceIn(0f, 1f)

    /** Each indicator's alpha, 0.4 inactive to 1 active, cross-faded by the scroll. */
    fun dotAlpha(page: Int): Float = LauncherGrid.DOT_INACTIVE_ALPHA + (1f - LauncherGrid.DOT_INACTIVE_ALPHA) * (1f - abs(scroll - page)).coerceIn(0f, 1f)

    /** An item's icon scale now: 1, or toward 1.1 while hovered. */
    fun iconScale(key: String, nowMs: Long): Float = hoverScale[key]?.at(nowMs, HOVER_MS, CubicBezier.HOVER) ?: 1f

    /** A home tray button the pointer has rested on for 700 ms: its tooltip. */
    fun tooltip(nowMs: Long): LauncherButton? {
        val (key, since) = hoverButtonSince ?: return null
        if (nowMs - since < TOOLTIP_MS) return null
        return buttons.firstOrNull { "button:${it.name}" == key }
    }

    // ---- hit tests ----

    /** Where [ray] meets the panel now, dp, or null when it points away. */
    fun pointOf(ray: Ray, nowMs: Long = lastNowMs): Pair<Float, Float>? = placement.toDp(ray, scale(nowMs))

    /** What a point at ([x], [y]) dp is on (null: the ray missed the panel's plane, also Empty). */
    fun hitAt(x: Float?, y: Float?): LauncherHit {
        if (x == null || y == null) return LauncherHit.Empty
        menu?.takeIf { it.closingMs == null }?.let { m ->
            m.rowAt(x, y)?.let { return LauncherHit.MenuRow(m.items[it]) }
            if (m.card.contains(x, y)) return LauncherHit.MenuCard
        }
        if (searchAlpha() >= 0.5f && LauncherGrid.search().contains(x, y)) {
            return LauncherHit.Search(clear = query.isNotEmpty() && LauncherGrid.searchClear().contains(x, y))
        }
        val dots = LauncherGrid.dots(pageCount())
        if (dots.isNotEmpty() && abs(y - LauncherGrid.INDICATOR_Y) <= DOT_HIT_HALF && x >= dots.first().cx - DOT_HIT_HALF && x <= dots.last().cx + DOT_HIT_HALF) {
            return LauncherHit.Dot(dots.indices.minByOrNull { abs(dots[it].cx - x) } ?: 0)
        }
        for (p in placed().asReversed()) {
            if (p.alpha < HIT_ALPHA) continue
            val inside = when (p.item) {
                is LauncherItem.Button -> {
                    val dx = x - p.rect.cx
                    val dy = y - p.rect.cy
                    sqrt(dx * dx + dy * dy) <= p.rect.w / 2f
                }
                is LauncherItem.App -> p.rect.contains(x, y)
            }
            if (inside) return LauncherHit.Item(p)
        }
        return LauncherHit.Empty
    }

    /** What [ray] is on. */
    fun hit(ray: Ray, nowMs: Long = lastNowMs): LauncherHit {
        val pt = pointOf(ray, nowMs)
        return hitAt(pt?.first, pt?.second)
    }

    // ---- hover ----

    /** Every frame with the rays that aim now: the hovered icons ease to 1.1, the rest back to 1. */
    fun hover(rays: List<Ray>, nowMs: Long) {
        val keys = HashSet<String>()
        var row: Int? = null
        var button: String? = null
        var search = false
        if (isOpen && press?.dragging != true && dragStartScroll == null) {
            for (r in rays) {
                when (val h = hit(r, nowMs)) {
                    is LauncherHit.Item -> {
                        keys += h.placed.item.key
                        if (h.placed.item is LauncherItem.Button) button = h.placed.item.key
                    }
                    is LauncherHit.MenuRow -> row = menu?.items?.indexOf(h.item)
                    is LauncherHit.Search -> search = true
                    else -> Unit
                }
            }
        }
        // A press is a hover exit for the pressed item; the open menu's icon stays at 1.1.
        (press?.hit as? LauncherHit.Item)?.let { keys -= it.placed.item.key }
        menu?.takeIf { it.closingMs == null }?.let { keys += it.itemKey }
        hoveredKeys = keys
        searchHovered = search
        menuHoverRow = row
        if (button == null) hoverButtonSince = null else if (hoverButtonSince?.first != button) hoverButtonSince = button to nowMs
        for (k in keys) hoverScale.getOrPut(k) { Eased(1f) }.target(HOVER_SCALE, nowMs, HOVER_MS, CubicBezier.HOVER)
        for ((k, e) in hoverScale) if (k !in keys) e.target(1f, nowMs, HOVER_MS, CubicBezier.HOVER)
    }

    val hovered: Set<String> get() = hoveredKeys

    /** 0.5.83 (Mike: the search input "needs depth design" and the cursor "wont directly go on the input"): a ray is on the search card. */
    var searchHovered = false
        private set

    /** 0.5.83: something that rises toward the eyes on hover is under a ray (an icon, a button, the search card). */
    val hoverLifts: Boolean get() = hoveredKeys.isNotEmpty() || searchHovered

    // ---- presses ----

    /** A press (pinch, trackpad click) from pointer [source] along [ray]. */
    fun down(ray: Ray, source: Int, nowMs: Long) {
        if (!isOpen || press != null) return
        focusKey = null
        val pt = pointOf(ray, nowMs)
        val menuOpen = menu?.closingMs == null && menu != null
        press = Press(source, pt?.first, pt?.second, hitAt(pt?.first, pt?.second), nowMs, menuOpen)
    }

    /** What the pointer did since the press, or a swipe or wheel ([LauncherInput]). */
    fun input(input: LauncherInput, source: Int, nowMs: Long): List<XrLauncherAction> {
        if (!isOpen) return emptyList()
        return when (input) {
            is LauncherInput.Move -> {
                move(input.ray, source, nowMs)
                emptyList()
            }
            is LauncherInput.Release -> release(source, input.cancel, nowMs)
            is LauncherInput.Secondary -> secondary(input.ray, nowMs)
            is LauncherInput.Swipe -> {
                swipe(input, nowMs)
                emptyList()
            }
            is LauncherInput.Wheel -> {
                wheel(input, nowMs)
                emptyList()
            }
        }
    }

    private fun move(ray: Ray, source: Int, nowMs: Long) {
        val p = press?.takeIf { it.source == source } ?: return
        val pt = pointOf(ray, nowMs) ?: return
        val sx = p.startX ?: return
        val sy = p.startY ?: return
        p.x = pt.first
        p.y = pt.second
        val dx = pt.first - sx
        val dy = pt.second - sy
        p.moved = maxOf(p.moved, sqrt(dx * dx + dy * dy))
        if (!p.dragging && !p.longPressed && p.hit !is LauncherHit.MenuRow && abs(dx) >= TAP_SLOP_DP && abs(dx) >= abs(dy)) {
            p.dragging = true
            dismissMenu(nowMs)
            // A drag mid-settle catches the page where it is.
            settling = false
            dragStartScroll = scroll
        }
        if (p.dragging) dragTo(dx)
    }

    private fun release(source: Int, cancel: Boolean, nowMs: Long): List<XrLauncherAction> {
        val p = press?.takeIf { it.source == source } ?: return emptyList()
        press = null
        if (p.dragging) {
            endDrag(nowMs)
            return emptyList()
        }
        if (cancel || p.longPressed || p.moved >= TAP_SLOP_DP) return emptyList()
        return tap(p.hit, p.withMenu, nowMs)
    }

    /** A tap on [hit]. [withMenu]: the press began with the menu open (then only the menu takes it). */
    private fun tap(hit: LauncherHit, withMenu: Boolean, nowMs: Long): List<XrLauncherAction> {
        val m = menu?.takeIf { it.closingMs == null }
        if (m != null || withMenu) {
            if (hit is LauncherHit.MenuRow && m != null) return choose(m, hit.item, nowMs)
            if (hit == LauncherHit.MenuCard) return emptyList()
            dismissMenu(nowMs)
            return emptyList()
        }
        return when (hit) {
            is LauncherHit.Item -> when (val item = hit.placed.item) {
                is LauncherItem.App -> {
                    val at = placement
                    close(nowMs)
                    listOf(XrLauncherAction.Launch(item.app, at), XrLauncherAction.Closed("${labelOf(item.app)} launched"))
                }
                is LauncherItem.Button -> {
                    close(nowMs)
                    listOf(XrLauncherAction.Button(item.button), XrLauncherAction.Closed("${item.button.label} pressed"))
                }
            }
            is LauncherHit.Dot -> {
                settleTo(hit.page, nowMs)
                emptyList()
            }
            is LauncherHit.Search -> {
                if (hit.clear) {
                    setQuery("")
                    emptyList()
                } else {
                    listOf(XrLauncherAction.WantsKeyboard)
                }
            }
            LauncherHit.Empty -> {
                close(nowMs)
                listOf(XrLauncherAction.Closed("a tap on empty space"))
            }
            is LauncherHit.MenuRow, LauncherHit.MenuCard -> emptyList()
        }
    }

    private fun choose(m: LauncherMenu, item: LauncherMenuItem, nowMs: Long): List<XrLauncherAction> {
        dismissMenu(nowMs)
        return when (item) {
            LauncherMenuItem.APP_INFO -> listOf(XrLauncherAction.AppInfo(m.app))
            LauncherMenuItem.PIN -> if (pinnedApps().size >= LauncherGrid.PIN_LIMIT) {
                listOf(XrLauncherAction.PinLimit)
            } else {
                pinnedKeys = (pinnedKeys.filter { it != m.app.key } + m.app.key).take(LauncherGrid.PIN_LIMIT)
                listOf(XrLauncherAction.Pinned(pinnedKeys))
            }
            LauncherMenuItem.UNPIN -> {
                pinnedKeys = pinnedKeys.filter { it != m.app.key }
                listOf(XrLauncherAction.Pinned(pinnedKeys))
            }
        }
    }

    /** A right click on an app opens its menu at once; elsewhere it closes an open menu. */
    private fun secondary(ray: Ray, nowMs: Long): List<XrLauncherAction> {
        val hit = hit(ray, nowMs)
        if (hit is LauncherHit.Item && hit.placed.item is LauncherItem.App) {
            openMenu(hit.placed, nowMs)
        } else if (hit !is LauncherHit.MenuRow && hit != LauncherHit.MenuCard) {
            dismissMenu(nowMs)
        }
        return emptyList()
    }

    private fun openMenu(placed: PlacedItem, nowMs: Long) {
        val item = placed.item as? LauncherItem.App ?: return
        val pinned = item.app.key in pinnedKeys
        val items = listOf(LauncherMenuItem.APP_INFO, if (pinned) LauncherMenuItem.UNPIN else LauncherMenuItem.PIN)
        val icon = placed.icon
        menu = LauncherMenu(item.app, item.key, icon.cx, icon.cy, items, nowMs)
    }

    /** The menu fades out (tap outside, a scroll, a choice). */
    fun dismissMenu(nowMs: Long) {
        menu?.let { if (it.closingMs == null) it.closingMs = nowMs }
    }

    /** The menu for the item with [key] is open now (for its anchor icon's 1.1). */
    val menuOpen: Boolean get() = menu?.closingMs == null && menu != null

    // ---- paging ----

    private fun dragTo(dxDp: Float) {
        val start = dragStartScroll ?: return
        scroll = (start - dxDp / LauncherGrid.PANEL_W).coerceIn(0f, (pageCount() - 1).toFloat())
    }

    private fun endDrag(nowMs: Long) {
        dragStartScroll = null
        settleTo(snapTarget(scroll, pageCount()), nowMs)
    }

    private fun swipe(s: LauncherInput.Swipe, nowMs: Long) {
        when (s.phase) {
            Phase.BEGIN -> {
                swipeAxis = 0
                wheelLast = 0f
                dismissMenu(nowMs)
            }
            Phase.UPDATE, Phase.END -> {
                if (swipeAxis == 0 && maxOf(abs(s.dxDp), abs(s.dyDp)) >= SWIPE_AXIS_DP) {
                    swipeAxis = if (abs(s.dxDp) >= abs(s.dyDp)) 1 else 2
                    if (swipeAxis == 1) {
                        settling = false
                        dragStartScroll = scroll
                    }
                }
                when (swipeAxis) {
                    1 -> {
                        dragTo(s.dxDp)
                        if (s.phase == Phase.END) endDrag(nowMs)
                    }
                    2 -> wheelBy(-s.dyDp / WHEEL_DP_PER_UNIT - wheelLast, nowMs).also { wheelLast = -s.dyDp / WHEEL_DP_PER_UNIT }
                }
                if (s.phase == Phase.END) swipeAxis = 0
            }
        }
    }

    private fun wheel(w: LauncherInput.Wheel, nowMs: Long) {
        if (w.phase == Phase.BEGIN) {
            wheelLast = 0f
            dismissMenu(nowMs)
            return
        }
        wheelBy(w.units - wheelLast, nowMs)
        wheelLast = w.units
    }

    /** Wheel deltas add up; each 2.5 units turns one page. */
    fun wheelBy(units: Float, nowMs: Long) {
        if (units == 0f) return
        dismissMenu(nowMs)
        wheelAccum += units
        var target = if (settling) Math.round(settleTo) else Math.round(scroll)
        while (wheelAccum >= WHEEL_UNITS_PER_PAGE) {
            wheelAccum -= WHEEL_UNITS_PER_PAGE
            target++
        }
        while (wheelAccum <= -WHEEL_UNITS_PER_PAGE) {
            wheelAccum += WHEEL_UNITS_PER_PAGE
            target--
        }
        val clamped = target.coerceIn(0, pageCount() - 1)
        if (clamped != target) wheelAccum = 0f
        if (clamped.toFloat() != (if (settling) settleTo else scroll)) settleTo(clamped, nowMs)
    }

    /** Settles the pager on [page] (a dot, the wheel, the keyboard). */
    fun settleTo(page: Int, nowMs: Long) {
        val target = page.coerceIn(0, pageCount() - 1).toFloat()
        dismissMenu(nowMs)
        if (target == scroll) {
            settling = false
            return
        }
        settleFrom = scroll
        settleTo = target
        settleStartMs = nowMs
        settling = true
    }

    private fun clampScroll() {
        val last = (pageCount() - 1).toFloat()
        if (scroll > last) scroll = last
        if (settling && settleTo > last) settleTo = last
    }

    // ---- search and keys ----

    fun setQuery(text: String) {
        val next = text.take(MAX_QUERY)
        if (next == query) return
        query = next
        clampScroll()
        // Results show on the All apps pages: typing on the home tray goes to page 1.
        if (query.isNotEmpty() && isOpen) {
            val p = if (settling) Math.round(settleTo) else Math.round(scroll)
            if (p != 1) settleTo(1, lastNowMs)
        }
    }

    fun type(c: Char) = setQuery(query + c)

    fun backspace() = setQuery(query.dropLast(1))

    /** A key from a keyboard. Returns actions as a tap would. */
    fun key(k: LauncherKey, nowMs: Long): List<XrLauncherAction> {
        if (!isOpen) return emptyList()
        if (k == LauncherKey.ESCAPE) {
            if (menuOpen) {
                dismissMenu(nowMs)
                return emptyList()
            }
            close(nowMs)
            return listOf(XrLauncherAction.Closed("Esc"))
        }
        val here = if (settling) Math.round(settleTo) else Math.round(scroll)
        val items = itemsOf(here)
        val current = items.firstOrNull { it.key == focusKey }
        if (k == LauncherKey.ENTER) {
            val item = current ?: return emptyList()
            val placed = PlacedItem(item, PageLook.of(here, here.toFloat()), 1f)
            return tap(LauncherHit.Item(placed), false, nowMs)
        }
        if (current == null) {
            focusKey = items.firstOrNull()?.key
            return emptyList()
        }
        val i = items.indexOf(current)
        when (k) {
            LauncherKey.RIGHT -> if (i < items.size - 1) {
                focusKey = items[i + 1].key
            } else if (here < pageCount() - 1) {
                itemsOf(here + 1).firstOrNull()?.let { focusKey = it.key }
                settleTo(here + 1, nowMs)
            }
            LauncherKey.LEFT -> if (i > 0) {
                focusKey = items[i - 1].key
            } else if (here > 0) {
                itemsOf(here - 1).lastOrNull()?.let { focusKey = it.key }
                settleTo(here - 1, nowMs)
            }
            LauncherKey.UP, LauncherKey.DOWN -> {
                val up = k == LauncherKey.UP
                val next = items.filter { if (up) it.cell.cy < current.cell.cy - 1f else it.cell.cy > current.cell.cy + 1f }
                    .minWithOrNull(compareBy<LauncherItem> { abs(it.cell.cy - current.cell.cy) }.thenBy { abs(it.cell.cx - current.cell.cx) })
                if (next != null) focusKey = next.key
            }
            LauncherKey.ENTER, LauncherKey.ESCAPE -> Unit
        }
        return emptyList()
    }

    /** One line for the log: pages, apps, the panel's size and place. */
    fun line(): String {
        val (w, h) = LauncherGrid.angularDeg(placement.distanceM)
        return "%d pages (the home tray and %d of All apps), %d apps, %d pinned; panel %.0f x %.0f dp, %.3f x %.3f m (%.1f x %.1f degrees) at %.2f m, yaw %.1f, pitch %.1f".format(
            java.util.Locale.ROOT, pageCount(), pageCount() - 1, apps.size, pinnedApps().size,
            LauncherGrid.PANEL_W, LauncherGrid.PANEL_H, LauncherGrid.widthM, LauncherGrid.heightM, w, h, placement.distanceM, placement.yawDeg, placement.pitchDeg,
        )
    }

    companion object {
        const val DISTANCE_M = 1.0f
        const val BELOW_GAZE_DEG = 5f
        const val FIT_VIEW = 0.92f
        const val MAX_PITCH_DEG = 35f

        /** Open but this far from the gaze, the toggle brings it back in front. */
        const val OUT_OF_VIEW_DEG = 35f

        const val OPEN_MS = 500L
        const val OPEN_SCALE_FROM = 0.95f
        const val CLOSE_MS = 180L
        const val CLOSE_SCALE_TO = 0.95f

        const val HOVER_SCALE = 1.1f
        const val HOVER_MS = 200L
        const val TOOLTIP_MS = 700L

        /** Wheel units a page turn takes. */
        const val WHEEL_UNITS_PER_PAGE = 2.5f

        /** A two finger swipe's vertical travel as wheel units: the thin strip's 8 notches a pad unit, a pad unit being one page width. */
        const val WHEEL_DP_PER_UNIT = LauncherGrid.PANEL_W / STRIP_NOTCHES_PER_UNIT

        /** A two finger swipe's axis is decided after this much travel, dp. */
        const val SWIPE_AXIS_DP = 12f

        /** A press that moves less than 50 panel px (1.5 px a dp) is a tap. */
        const val TAP_SLOP_DP = 50f / 1.5f

        /** Android's long press timeout (ViewConfiguration, 400 ms since Android 12). */
        const val LONG_PRESS_MS = 400L

        /** RecyclerView's pager snap, a decelerate of about 300 ms. */
        const val SETTLE_MS = 300L

        /** An item is hit only while at least this visible (mid-swipe pages fading). */
        const val HIT_ALPHA = 0.5f

        /** The indicator's hit band either side of its dots' line, dp. */
        const val DOT_HIT_HALF = 16f
        const val MAX_QUERY = 40

        /** The launched window's fade and scale, 500 ms. */
        const val WINDOW_OPEN_MS = 500L
        const val WINDOW_SCALE_FROM = 0.92f

        /** App windows ghost to about 15 % while the launcher is open. */
        const val GHOST_ALPHA = 0.15f

        /** The label, or the package name when it is blank. */
        fun labelOf(app: AppEntry): String = app.label.ifBlank { app.packageName }

        /** Plain case-sensitive string order of the label, nothing else. */
        fun sorted(list: List<AppEntry>): List<AppEntry> = list.sortedWith { a, b -> labelOf(a).compareTo(labelOf(b)) }

        /**
         * Where a released drag settles: past 20 % toward the next page it goes on, else it
         * snaps back; going back to the previous page therefore needs 80 % (ViewPager2's position plus
         * offset rule).
         */
        fun snapTarget(scroll: Float, pageCount: Int): Int {
            val base = floor(scroll + 1e-4f).toInt()
            val frac = scroll - base
            return (if (frac > 0.2f) base + 1 else base).coerceIn(0, maxOf(0, pageCount - 1))
        }

        /** The launched window's alpha [ms] after it opened. */
        fun windowAlpha(ms: Long): Float = CubicBezier.WINDOW_ALPHA.at(ms / WINDOW_OPEN_MS.toFloat())

        /** The launched window's scale [ms] after it opened, 0.92 to 1. */
        fun windowScale(ms: Long): Float = WINDOW_SCALE_FROM + (1f - WINDOW_SCALE_FROM) * CubicBezier.WINDOW_SCALE.at(ms / WINDOW_OPEN_MS.toFloat())

        private fun angleDeg(a: Vec3, b: Vec3): Float {
            val c = (a.normalized() dot b.normalized()).coerceIn(-1f, 1f)
            return kotlin.math.acos(c) * RAD
        }
    }
}

/** The apps pinned for someone who has never pinned one, so the launcher's top row shows what it is for. */
object LauncherDefaults {
    const val COUNT = 4

    /** Picked in this order when installed; any other apps fill what is left. */
    val PREFERRED = listOf(
        "com.android.chrome",
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.google.android.apps.maps",
        "com.android.settings",
    )

    fun pick(apps: List<AppEntry>): List<String> {
        val liked = PREFERRED.mapNotNull { p -> apps.firstOrNull { it.packageName == p } }
        return (liked + apps).distinctBy { it.packageName }.take(COUNT).map { it.key }
    }
}
