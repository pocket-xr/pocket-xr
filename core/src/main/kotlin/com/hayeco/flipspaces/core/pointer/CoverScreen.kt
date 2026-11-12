package com.hayeco.flipspaces.core.pointer

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * 0.5.17 cover screen: Pocket XR on the Galaxy Z Flip7's Flex Window (SM-F766U, 948 x 1048 px at
 * 380 dpi, 399 x 441 dp, the camera cutouts at the bottom), for one thumb with the phone closed.
 * Everything here is a Pocket XR design, in plain maths
 * the app's views and the trackpad screen follow.
 */

/** A rectangle in dp, left top right bottom, y down. */
data class DpRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    /** True when the two overlap, or come closer than [gap]. */
    fun intersects(o: DpRect, gap: Float = 0f): Boolean =
        left < o.right + gap && o.left < right + gap && top < o.bottom + gap && o.top < bottom + gap

    fun shift(dx: Float, dy: Float) = DpRect(left + dx, top + dy, right + dx, bottom + dy)

    fun overlapsX(o: DpRect, gap: Float = 0f): Boolean = left < o.right + gap && o.left < right + gap
}

/** The cover layout's buttons. [window] marks the window rail; [label] is drawn under the icon, never cut. */
enum class CoverButton(val label: String, val window: Boolean = false) {
    MENU("Menu"),

    /** 0.5.106 (Mike: "remove the 2D/3D button from the cover screen ... make the keyboard button wider"): two buttons wide. */
    KEYBOARD("Keyboard"),
    BACK("Back"),
    HOME("Home"),
    RECENTRE("Recentre"),

    /** Steady mode, on glasses that have it: beside Keyboard, which then gives up its second width. */
    STEADY("Steady"),
    MOVE("Move", window = true),
    RESIZE("Resize", window = true),
    FURTHER("Further", window = true),
    CLOSER("Closer", window = true),

    /** 0.5.19: curves the window: a tap holds it to curve with a sideways swipe, a slide along the button curves it directly. */
    CURVE("Curve", window = true),
    ;

    /** The button's word as shown, in the phone's language; [label] stays the English for the log. */
    val text: String get() = com.hayeco.flipspaces.core.UiText.get("core_cover_" + name.lowercase(), label)
}

/**
 * The cover screen's layout: the pad, every button, and what it was built around (the screen,
 * its cutouts and rounded corners, the rail's side). [CoverLayoutMath.problems] checks it.
 */
data class CoverLayout(
    val widthDp: Float,
    val heightDp: Float,
    val pad: DpRect,
    val buttons: Map<CoverButton, DpRect>,
    val cutouts: List<DpRect>,
    val cornerRadiusDp: Float,
    val controls: StripSide,
) {
    val padRadiusDp: Float get() = CoverLayoutMath.PAD_RADIUS
}

/**
 * 0.5.17 cover screen: the layout for one thumb, from the screen's size, its cutouts and its corner
 * radius (the app reads them from the window insets; [FLIP7] when Android reports none):
 *
 * - a top row: Menu at the left, Keyboard at the right, two buttons wide ([KEYBOARD_SPAN]; 0.5.106: 3D / 2D is gone,
 *   the launcher has it);
 * - the window rail (Move, Resize, Further, Closer, and 0.5.19's Curve) down the [StripSide] the thumb is on, right by
 *   default, between the top row and whatever is at the bottom (the cutouts);
 * - a bottom row (Back, Home, Recentre) in the widest free span beside the cutouts, or above them
 *   if there is no room beside them;
 * - the pad fills what is left, [GAP] from everything, [CUTOUT_CLEAR] from a cutout.
 *
 * Every button is at least [MIN_TOUCH] 48 dp each way (Android's touch target), at most
 * [MAX_BUTTON]; every rectangle is nudged in until its rounded corners are inside the screen's.
 */
object CoverLayoutMath {
    const val MARGIN = 8f
    const val GAP = 6f
    const val BUTTON = 56f
    const val MIN_TOUCH = 48f
    const val MAX_BUTTON = 64f
    const val BUTTON_RADIUS = 14f
    const val PAD_RADIUS = 14f
    const val CUTOUT_CLEAR = 6f

    /** The labels under the icons: 10 sp, 2 dp in from the button's sides, shrinking to 8 sp before any is cut. */
    const val LABEL_SP = 10f
    const val LABEL_PAD = 2f

    /** The pad keeps at least this share of the screen. */
    const val MIN_PAD_SHARE = 0.4f

    val TOP_LEFT = listOf(CoverButton.MENU)
    val TOP_RIGHT = listOf(CoverButton.KEYBOARD)

    /** 0.5.106: the Keyboard button spans this many button widths (and the gaps between), where 3D / 2D was. */
    const val KEYBOARD_SPAN = 2
    val NAV = listOf(CoverButton.BACK, CoverButton.HOME, CoverButton.RECENTRE)
    val RAIL = listOf(CoverButton.MOVE, CoverButton.RESIZE, CoverButton.FURTHER, CoverButton.CLOSER, CoverButton.CURVE)

    /** The Flip7's Flex Window as `dumpsys display` gave it: 399 x 441 dp, a 219 x 88 dp cutout at the bottom right. */
    object FLIP7 {
        const val WIDTH_DP = 399f
        const val HEIGHT_DP = 441f
        const val DENSITY_DPI = 380
        const val WIDTH_PX = 948
        const val HEIGHT_PX = 1048

        /** Physical: 4.1 inch diagonal over 1413 px is 344.6 px an inch, so 0.175 mm a dp. */
        const val MM_PER_DP = 0.175f
        const val CORNER_RADIUS_DP = 40f
        val CUTOUT = DpRect(WIDTH_DP - 219f, HEIGHT_DP - 88f, WIDTH_DP, HEIGHT_DP)

        /** The measured cutout at the bottom right of a [w] x [h] dp window, for when Android reports none. */
        fun cutoutFor(w: Float, h: Float): DpRect = DpRect(w - CUTOUT.width, h - CUTOUT.height, w, h)
    }

    fun flip7(controls: StripSide = StripSide.RIGHT, steady: Boolean = false): CoverLayout =
        compute(FLIP7.WIDTH_DP, FLIP7.HEIGHT_DP, listOf(FLIP7.CUTOUT), FLIP7.CORNER_RADIUS_DP, controls, steady)

    fun compute(w: Float, h: Float, cutouts: List<DpRect>, cornerR: Float, controls: StripSide = StripSide.RIGHT, steady: Boolean = false): CoverLayout {
        val m = MARGIN
        val g = GAP
        val b = BUTTON
        val r = cornerR.coerceAtLeast(0f)
        val inside = { rect: DpRect, radius: Float -> rectInside(rect, radius, w, h, r) }
        val buttons = LinkedHashMap<CoverButton, DpRect>()
        val topCuts = cutouts.filter { it.centreY < h / 2f }
        val bottomCuts = cutouts.filter { it.centreY >= h / 2f }

        // Top row: below any top cutout.
        val topY = max(m, (topCuts.maxOfOrNull { it.bottom } ?: -CUTOUT_CLEAR) + CUTOUT_CLEAR)
        var x = m
        for (k in TOP_LEFT) {
            val rc = nudge(DpRect(x, topY, x + b, topY + b), 1f, 0f) { inside(it, BUTTON_RADIUS) }
            buttons[k] = rc
            x = rc.right + g
        }
        var xr = w - m
        for (k in (if (steady) listOf(CoverButton.STEADY) + TOP_RIGHT else TOP_RIGHT).reversed()) {
            val kw = if (k == CoverButton.KEYBOARD && !steady) KEYBOARD_SPAN * b + (KEYBOARD_SPAN - 1) * g else b
            val rc = nudge(DpRect(xr - kw, topY, xr, topY + b), -1f, 0f) { inside(it, BUTTON_RADIUS) }
            buttons[k] = rc
            xr = rc.left - g
        }
        val topBottom = topY + b

        // Bottom row: beside the bottom cutouts if they leave room, else above them.
        val bandTop = bottomCuts.minOfOrNull { it.top } ?: h
        val beside = (h - m) - bandTop
        val navY = when {
            bottomCuts.isEmpty() -> h - m - b
            beside >= b -> bandTop + (beside - b) / 2f
            else -> bandTop - CUTOUT_CLEAR - b
        }
        val navRow = DpRect(0f, navY, w, navY + b)
        val blocked = cutouts.filter { it.top < navRow.bottom && navRow.top < it.bottom }
            .map { (it.left - CUTOUT_CLEAR) to (it.right + CUTOUT_CLEAR) }
            .sortedBy { it.first }
        val spans = mutableListOf<Pair<Float, Float>>()
        var from = m
        for ((l, rr) in blocked) {
            if (l > from) spans += from to min(l, w - m)
            from = max(from, rr)
        }
        if (from < w - m) spans += from to (w - m)
        val span = spans.maxByOrNull { it.second - it.first }
        val overflow = mutableListOf<CoverButton>()
        if (span != null) {
            // The span's ends, in from the rounded corners for a row at this height.
            val start = nudge(DpRect(span.first, navY, span.first + b, navY + b), 1f, 0f) { inside(it, BUTTON_RADIUS) }.left
            val end = nudge(DpRect(span.second - b, navY, span.second, navY + b), -1f, 0f) { inside(it, BUTTON_RADIUS) }.right
            val room = end - start
            val fit = min(NAV.size, floor((room + g) / (MIN_TOUCH + g)).toInt()).coerceAtLeast(0)
            if (fit > 0) {
                val bw = min(MAX_BUTTON, (room - (fit - 1) * g) / fit)
                val total = fit * bw + (fit - 1) * g
                var bx = start + (room - total) / 2f
                for (k in NAV.take(fit)) {
                    buttons[k] = DpRect(bx, navY, bx + bw, navY + b)
                    bx += bw + g
                }
            }
            overflow += NAV.drop(fit)
        } else {
            overflow += NAV
        }
        // Buttons with no room at the bottom go in the top row, between Menu and Keyboard.
        for (k in overflow) {
            buttons[k] = DpRect(x, topY, x + b, topY + b)
            x += b + g
        }

        // The window rail.
        val railX0 = if (controls == StripSide.RIGHT) w - m - b else m
        var rail = DpRect(railX0, topBottom + g, railX0 + b, h - m)
        val below = (cutouts + NAV.mapNotNull { buttons[it] }).filter { it.overlapsX(rail, CUTOUT_CLEAR) && it.top > rail.top }
        val railBottom = below.minOfOrNull { it.top - CUTOUT_CLEAR } ?: (h - m)
        rail = DpRect(rail.left, rail.top, rail.right, railBottom)
        rail = nudge(rail, if (controls == StripSide.RIGHT) -1f else 1f, 0f) { inside(it, BUTTON_RADIUS) }
        // 0.5.19: Curve is the rail's fifth button where five fit at 48 dp (the Flip7); else it goes in
        // the top row, after Menu and anything already there.
        val fits = RAIL.size * MIN_TOUCH + (RAIL.size - 1) * g <= rail.height + 1e-3f
        val railKeys = if (fits) RAIL else RAIL - CoverButton.CURVE
        if (!fits) {
            buttons[CoverButton.CURVE] = DpRect(x, topY, x + b, topY + b)
            x += b + g
        }
        val n = railKeys.size
        val bh = min(MAX_BUTTON, (rail.height - (n - 1) * g) / n)
        val stack = n * bh + (n - 1) * g
        var by = rail.top + (rail.height - stack) / 2f
        for (k in railKeys) {
            buttons[k] = DpRect(rail.left, by, rail.right, by + bh)
            by += bh + g
        }

        // The pad: everything left, clear of the rail, the rows and the cutouts.
        val padLeft = if (controls == StripSide.RIGHT) m else rail.right + g
        val padRight = if (controls == StripSide.RIGHT) rail.left - g else w - m
        val padTop = topBottom + g
        val span0 = DpRect(padLeft, padTop, padRight, h)
        val under = (cutouts + NAV.mapNotNull { buttons[it] } + overflow.mapNotNull { buttons[it] })
            .filter { it.overlapsX(span0) && it.bottom > padTop }
            .map { if (it in cutouts) it.top - CUTOUT_CLEAR else it.top - g }
        var pad = DpRect(padLeft, padTop, padRight, under.minOrNull() ?: (h - m))
        // Shrink a side at a time until the pad's rounded corners are inside the screen's.
        var guard = 0
        while (!inside(pad, PAD_RADIUS) && guard++ < 200) {
            pad = DpRect(pad.left + 0.5f, pad.top, pad.right - 0.5f, pad.bottom - 0.5f)
        }
        return CoverLayout(w, h, pad, buttons, cutouts, r, controls)
    }

    /** What is wrong with [l], in words; empty when the layout keeps every rule. */
    fun problems(l: CoverLayout): List<String> {
        val out = mutableListOf<String>()
        val all = l.buttons.entries.toList()
        val missing = CoverButton.entries - CoverButton.STEADY - l.buttons.keys
        if (missing.isNotEmpty()) out += "buttons missing: $missing"
        for ((k, rc) in all) {
            if (rc.width < MIN_TOUCH - 1e-3f || rc.height < MIN_TOUCH - 1e-3f) out += "$k is %.1f x %.1f dp, under 48".format(rc.width, rc.height)
            if (!rectInside(rc, BUTTON_RADIUS, l.widthDp, l.heightDp, l.cornerRadiusDp)) out += "$k is outside the rounded screen"
            if (l.cutouts.any { it.intersects(rc) }) out += "$k covers a cutout"
            if (rc.intersects(l.pad)) out += "$k overlaps the pad"
        }
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (all[i].value.intersects(all[j].value)) out += "${all[i].key} overlaps ${all[j].key}"
        }
        if (!rectInside(l.pad, PAD_RADIUS, l.widthDp, l.heightDp, l.cornerRadiusDp)) out += "the pad is outside the rounded screen"
        if (l.cutouts.any { it.intersects(l.pad, CUTOUT_CLEAR - 1e-3f) }) out += "the pad is within $CUTOUT_CLEAR dp of a cutout"
        val share = l.pad.width * l.pad.height / (l.widthDp * l.heightDp)
        if (share < MIN_PAD_SHARE) out += "the pad is only %.0f%% of the screen".format(share * 100f)
        return out
    }

    /** True when the point is on a screen of [w] x [h] whose corners are rounded by [r]. */
    fun pointInside(x: Float, y: Float, w: Float, h: Float, r: Float): Boolean {
        if (x < -1e-3f || y < -1e-3f || x > w + 1e-3f || y > h + 1e-3f) return false
        if (r <= 0f) return true
        val cx = when {
            x < r -> r
            x > w - r -> w - r
            else -> return true
        }
        val cy = when {
            y < r -> r
            y > h - r -> h - r
            else -> return true
        }
        return hypot(x - cx, y - cy) <= r + 1e-3f
    }

    /** True when [rc], its own corners rounded by [radius], is inside the rounded screen: its 45 degree corner points are. */
    fun rectInside(rc: DpRect, radius: Float, w: Float, h: Float, r: Float): Boolean {
        val k = radius * (1f - 1f / sqrt(2f))
        return pointInside(rc.left + k, rc.top + k, w, h, r) && pointInside(rc.right - k, rc.top + k, w, h, r) &&
            pointInside(rc.left + k, rc.bottom - k, w, h, r) && pointInside(rc.right - k, rc.bottom - k, w, h, r) &&
            rc.left >= -1e-3f && rc.top >= -1e-3f && rc.right <= w + 1e-3f && rc.bottom <= h + 1e-3f
    }

    private fun nudge(rc: DpRect, dx: Float, dy: Float, ok: (DpRect) -> Boolean): DpRect {
        var c = rc
        var i = 0
        while (!ok(c) && i++ < 120) c = c.shift(dx * 0.5f, dy * 0.5f)
        return c
    }
}

/**
 * 0.5.17 cover screen: the cursor curve for the cover pad, by the finger's travel in millimetres so
 * it feels the same on any pad size. The main pad's curve is in pad units ([PadCurve]): on the cover's
 * smaller pad its slow full width would be 43 degrees, and the view is 62.8 degrees wide (70
 * diagonal on 1920 x 1080), so one swipe could never cross it.
 *
 * - The gain rises with the finger's speed (smoothed over [SMOOTH_MS]) on a smoothstep from
 *   [slowGain] at [slowMmS] and under to [fastGain] at [fastMmS] and over. [fastMmS] is the stroke
 *   below done in [QUICK_STROKE_S] 0.25 s (135 mm/s on the Flip7's pad), [slowMmS] [SLOW_SHARE] of it.
 * - [fastGain]: a quick thumb stroke over [STROKE_SHARE] 60% of the pad's width crosses the whole
 *   view with [CROSS_MARGIN] 10% to spare.
 * - [slowGain]: a third of that ([FAST_OVER_SLOW], about the main pad's 3.5 times acceleration), and
 *   never over [MAX_SLOW_GAIN] 1 degree a millimetre, so slow aiming stays fine.
 * - Both axes have the same degrees a millimetre; Cursor Speed scales it from 0.5 to 1.5 times
 *   ([speedScale], 50 is 1).
 */
class CoverCurve(val padWidthMm: Float, val padHeightMm: Float, val viewWidthDeg: Float = DEFAULT_VIEW_DEG) {
    val fastGain: Float = viewWidthDeg * CROSS_MARGIN / (STROKE_SHARE * padWidthMm.coerceAtLeast(10f))
    val slowGain: Float = min(fastGain / FAST_OVER_SLOW, MAX_SLOW_GAIN)
    val fastMmS: Float = STROKE_SHARE * padWidthMm.coerceAtLeast(10f) / QUICK_STROKE_S
    val slowMmS: Float = fastMmS * SLOW_SHARE

    private var speed = 0f

    /** A finger went down: the speed starts from rest. */
    fun reset() {
        speed = 0f
    }

    /** Degrees a millimetre at a finger speed of [mmPerS]. */
    fun gainAt(mmPerS: Float): Float {
        val t = ((mmPerS - slowMmS) / (fastMmS - slowMmS)).coerceIn(0f, 1f)
        return slowGain + (fastGain - slowGain) * t * t * (3f - 2f * t)
    }

    /** The cursor turn (yaw right, pitch up, degrees) for a move of [dx], [dy] pad units over [dtMs]. */
    fun turn(dx: Float, dy: Float, dtMs: Float, cursorSpeed: Int = PadCurve.DEFAULT_SPEED): Pair<Float, Float> {
        val mx = dx * padWidthMm / 2f
        val my = dy * padHeightMm / 2f
        val dt = dtMs.coerceIn(2f, 100f)
        val v = hypot(mx, my) * 1000f / dt
        speed += (v - speed) * (dt / (dt + SMOOTH_MS))
        val g = gainAt(speed) * speedScale(cursorSpeed)
        return Pair(mx * g, my * g)
    }

    companion object {
        const val STROKE_SHARE = 0.6f
        const val CROSS_MARGIN = 1.1f
        const val FAST_OVER_SLOW = 3f
        const val MAX_SLOW_GAIN = 1f
        const val QUICK_STROKE_S = 0.25f
        const val SLOW_SHARE = 0.18f
        const val SMOOTH_MS = 24f

        /** The Space's view: 70 degrees diagonal on 1920 x 1080 is 62.8 wide, where the cursor stops. */
        const val DEFAULT_VIEW_DEG = 62.8f

        fun speedScale(cursorSpeed: Int): Float = 0.5f + cursorSpeed.coerceIn(0, 100) / 100f

        /** For a pad of [layout] at [mmPerDp]. */
        fun forLayout(layout: CoverLayout, mmPerDp: Float = CoverLayoutMath.FLIP7.MM_PER_DP, viewWidthDeg: Float = DEFAULT_VIEW_DEG): CoverCurve =
            CoverCurve(layout.pad.width * mmPerDp, layout.pad.height * mmPerDp, viewWidthDeg)
    }
}

/**
 * 0.5.17 cover screen: the tap and swipe limits in millimetres for a thumb. The main pad's 0.15 pad
 * units to stay a tap and 0.03 units for the safe down were sized for a finger on a tall pad; a
 * thumb rolls about a millimetre as it lands, which is 0.03 units on the cover pad, so every
 * thumb tap would have read as a swipe.
 */
object ThumbFeel {
    /** A press that moves less than this stays a tap (the main pad: 0.15 units, about 4 mm). */
    const val TAP_MOVE_MM = 4f

    /** Moving more than this within the safe down's 150 ms makes it a swipe (the main pad: 0.03 units). */
    const val SAFE_DOWN_MM = 1.6f

    /** [TAP_MOVE_MM] and [SAFE_DOWN_MM] in pad units for a pad of [widthMm] x [heightMm] (the shorter side's units, the stricter). */
    fun limits(widthMm: Float, heightMm: Float): Pair<Float, Float> {
        val mmPerUnit = min(widthMm, heightMm).coerceAtLeast(1f) / 2f
        return Pair(TAP_MOVE_MM / mmPerUnit, SAFE_DOWN_MM / mmPerUnit)
    }
}

/**
 * 0.5.18: One UI runs Pocket XR on the cover screen in a 1.5x
 * compatibility scale: the trackpad's window is 1422 x 1572 app pixels on the 948 x 1048 panel, but
 * `xdpi` is the real panel's 343.99. 0.5.17 turned app pixels into millimetres with that xdpi, so
 * every millimetre read 1.5 times too long: the pad read 87.7 x 62.9 mm (it is 58.5 x 42.0), a real
 * tap could roll only 2.7 mm before it stopped being one, and a 12 mm thumb counted as an 18 mm palm.
 * Now millimetres come from app pixels times [factor] (real pixels over app pixels).
 */
object CoverScale {
    /**
     * Real pixels per app pixel: the panel's long side (its mode's physical size) over the app's own
     * view of it (its real metrics, scaled by One UI); 1 when they agree or either is unknown.
     */
    fun factor(physicalLongPx: Int, appLongPx: Int): Float {
        if (physicalLongPx <= 0 || appLongPx <= 0) return 1f
        val f = physicalLongPx.toFloat() / appLongPx
        // Within 2 % is no scale; nothing outside 0.3 to 1 is a compatibility scale.
        return if (kotlin.math.abs(f - 1f) < 0.02f || f !in 0.3f..1f) 1f else f
    }

    /** Millimetres per app pixel for a panel of [dpi] real pixels an inch. */
    fun mmPerAppPx(dpi: Float, factor: Float): Float = 25.4f / dpi.coerceAtLeast(1f) * factor
}

/**
 * 0.5.55 (on the cover all three layers were CLIENT, the TrackpadActivity buffer 1572 x 1422
 * scaled down by SurfaceFlinger to the 1048 x 948 panel): the size the trackpad is drawn at.
 *
 * Why the buffer was 1.5 times the panel: Samsung's FlipSizeCompatPolicy keeps the trackpad's activity in size
 * compatibility on the cover (vc44's `dumpsys activity`: `SizeCompatAttributes: mScale=0.6666667, mBounds=(0,0)(948x1048),
 * mReason=FlipSizeCompatPolicy`; the window's configuration is 1422 x 1572 at 480 dpi, 474 x 523 dp). The window manager
 * lays the window out in those app pixels and scales the window's surface by 2/3 (`mGlobalScale=0.6666667`); the client is
 * told `compatScale=1.0`, so its ViewRootImpl allocates 1422 x 1572. An app cannot set that buffer's size (FLAG_SCALED's
 * frame is its own size times the same 2/3). It can draw into a child surface of the window at [width] x [height], the
 * panel's own pixels: the window's 2/3 times that surface's 1.5 is 1:1, so SurfaceFlinger has nothing to scale.
 *
 * The views stay laid out in app pixels ([appWidth] x [appHeight]), so touches, [CoverScale]'s millimetres and [ThumbGuard]
 * are as before; [toBufferX] and [toAppX] map a point between the two. Where there is no compatibility scale ([factor] 1:
 * the main screen, or a cover process Samsung left at the panel's size) the buffer is the window's own and nothing changes.
 */
data class NativeBuffer(val appWidth: Int, val appHeight: Int, val width: Int, val height: Int, val factor: Float) {
    /** Buffer pixels per app pixel across (1 when the window is drawn at its own size). */
    val scaleX: Float get() = if (appWidth > 0) width.toFloat() / appWidth else 1f

    /** Buffer pixels per app pixel down. */
    val scaleY: Float get() = if (appHeight > 0) height.toFloat() / appHeight else 1f

    /** The window is bigger than the panel shows it: drawing at [width] x [height] spares SurfaceFlinger the scaling. */
    val scaled: Boolean get() = width != appWidth || height != appHeight

    fun toBufferX(appX: Float): Float = appX * scaleX
    fun toBufferY(appY: Float): Float = appY * scaleY
    fun toAppX(bufferX: Float): Float = if (scaleX > 0f) bufferX / scaleX else bufferX
    fun toAppY(bufferY: Float): Float = if (scaleY > 0f) bufferY / scaleY else bufferY

    /**
     * The `trackpad buffer:` log line (keep the prefix: logs are searched by it). [screen] is "cover" or "main screen";
     * [drawnNative] whether the trackpad is drawn into the panel-sized surface now, else [why] it is not.
     */
    fun line(screen: String, displayId: Int, drawnNative: Boolean, why: String? = null): String = when {
        !scaled -> "trackpad buffer: ${width}x$height (the $screen's native ${width}x$height; the window's own buffer, nothing for SurfaceFlinger to scale), display $displayId"
        drawnNative -> "trackpad buffer: ${width}x$height (the $screen's native ${width}x$height; was ${appWidth}x$appHeight app pixels scaled by SurfaceFlinger), display $displayId, compatibility scale %.3f; drawn into a SurfaceView over the window, which SurfaceFlinger can leave out".format(java.util.Locale.ROOT, factor)
        else -> "trackpad buffer: ${appWidth}x$appHeight app pixels, scaled by SurfaceFlinger to the $screen's ${width}x$height (not drawn at the panel's pixels: ${why ?: "off"}), display $displayId, compatibility scale %.3f".format(java.util.Locale.ROOT, factor)
    }

    companion object {
        /**
         * The buffer for a window of [windowWidth] x [windowHeight] app pixels on a panel whose mode is [physicalWidth] x
         * [physicalHeight] (`Display.Mode.physicalWidth`/`Height`, either orientation) that the app sees as [appWidth] x
         * [appHeight] (its real metrics). The scale is [CoverScale.factor] of the long sides, so a window shorter than the
         * display (the keyboard up) or turned keeps the same scale.
         */
        fun pick(physicalWidth: Int, physicalHeight: Int, appWidth: Int, appHeight: Int, windowWidth: Int = appWidth, windowHeight: Int = appHeight): NativeBuffer {
            val w = windowWidth.coerceAtLeast(1)
            val h = windowHeight.coerceAtLeast(1)
            val f = CoverScale.factor(max(physicalWidth, physicalHeight), max(appWidth, appHeight))
            if (f == 1f) return NativeBuffer(w, h, w, h, 1f)
            return NativeBuffer(w, h, (w * f).roundToInt().coerceAtLeast(1), (h * f).roundToInt().coerceAtLeast(1), f)
        }
    }
}

/** A finger as the cover screen saw it: screen millimetres from the top left, and its contact's long axis (0 when not reported). */
data class GuardTouch(val id: Int, val xMm: Float, val yMm: Float, val majorMm: Float = 0f)

/** What [ThumbGuard] lets through to the trackpad. */
sealed interface GuardEvent {
    data class Down(val touch: GuardTouch) : GuardEvent
    data class Move(val touches: List<GuardTouch>) : GuardEvent
    data class Up(val id: Int) : GuardEvent

    /** A palm: the gesture so far is cancelled, and nothing more until every finger is up. */
    data object Cancel : GuardEvent
}

/**
 * 0.5.17 cover screen: palm and edge rejection for a thumb on a small screen held in the same hand.
 *
 * - **Edge**: a touch that lands within [EDGE_MM] of a screen edge (where the holding fingers
 *   and the thumb's base rest) is held back. It is let through, from where it is then, once it
 *   has moved [EDGE_RELEASE_MM] (a swipe that starts at the edge); lifted within [EDGE_REST_MS]
 *   without moving, alone, it is a tap; still there after [EDGE_REST_MS], it is a resting grip and
 *   is ignored until it lifts, so it never makes a one finger swipe a two finger gesture. 0.5.22: a
 *   third finger landing there while two are through goes through at once (a three finger swipe).
 * - **Palm**: a contact [PALM_MM] or longer, at its landing or later, cancels the gesture and
 *   everything is ignored until all fingers are up. Android's own palm flag (a pointer up with
 *   FLAG_CANCELED, or ACTION_CANCEL) does the same ([up] with `canceled`).
 * - **Lift roll**: as a thumb lifts its contact shrinks and its centre slides; moves after the
 *   contact has shrunk under [LIFT_SHRINK] of its largest are held at the last good point, so the
 *   cursor does not jump at the lift.
 */
class ThumbGuard(val screenWidthMm: Float, val screenHeightMm: Float) {
    private enum class State { ACCEPTED, PENDING, REJECTED }

    private class P(var state: State, val downMs: Long, val start: GuardTouch, var last: GuardTouch, var peak: Float)

    private val pointers = LinkedHashMap<Int, P>()
    private var rejectAll = false

    fun inEdge(xMm: Float, yMm: Float): Boolean =
        xMm < EDGE_MM || yMm < EDGE_MM || xMm > screenWidthMm - EDGE_MM || yMm > screenHeightMm - EDGE_MM

    val accepted: Int get() = pointers.values.count { it.state == State.ACCEPTED }

    fun down(t: GuardTouch, nowMs: Long): List<GuardEvent> {
        if (rejectAll) {
            pointers[t.id] = P(State.REJECTED, nowMs, t, t, t.majorMm)
            return emptyList()
        }
        if (t.majorMm >= PALM_MM) return palm(t, nowMs)
        // 0.5.22 (no three finger swipe reached Pocket XR on the 58.5 mm cover pad):
        // with two fingers already through, a third that lands at the edge is the gesture's, not a grip, and goes
        // through at once (held back, it came through late or never, and the swipe read as two fingers).
        val state = if (inEdge(t.xMm, t.yMm) && accepted < 2) State.PENDING else State.ACCEPTED
        pointers[t.id] = P(state, nowMs, t, t, t.majorMm)
        return if (state == State.ACCEPTED) listOf(GuardEvent.Down(t)) else emptyList()
    }

    fun move(all: List<GuardTouch>, nowMs: Long): List<GuardEvent> {
        if (rejectAll) return emptyList()
        val out = mutableListOf<GuardEvent>()
        var moved = false
        for (t in all) {
            val p = pointers[t.id] ?: continue
            if (t.majorMm >= PALM_MM && p.state != State.REJECTED) return palm(t, nowMs)
            when (p.state) {
                State.REJECTED -> Unit
                State.PENDING -> when {
                    hypot(t.xMm - p.start.xMm, t.yMm - p.start.yMm) >= EDGE_RELEASE_MM -> {
                        p.state = State.ACCEPTED
                        p.last = t
                        p.peak = max(p.peak, t.majorMm)
                        out += GuardEvent.Down(t)
                    }
                    nowMs - p.downMs >= EDGE_REST_MS -> {
                        p.state = State.REJECTED
                        refusal = "an edge touch at %.1f, %.1f mm rested %d ms: a grip, ignored until it lifts".format(java.util.Locale.ROOT, p.start.xMm, p.start.yMm, nowMs - p.downMs)
                    }
                }
                State.ACCEPTED -> {
                    p.peak = max(p.peak, t.majorMm)
                    val lifting = t.majorMm > 0f && p.peak > 0f && t.majorMm < LIFT_SHRINK * p.peak
                    if (!lifting && (t.xMm != p.last.xMm || t.yMm != p.last.yMm)) {
                        p.last = t
                        moved = true
                    }
                }
            }
        }
        if (moved) out += GuardEvent.Move(pointers.values.filter { it.state == State.ACCEPTED }.map { it.last })
        return out
    }

    /** A pointer lifted; [canceled] when Android flagged it as a palm (FLAG_CANCELED). */
    fun up(id: Int, nowMs: Long, canceled: Boolean = false): List<GuardEvent> {
        val p = pointers[id] ?: return emptyList()
        if (canceled && !rejectAll && p.state != State.REJECTED) return palm(p.last, nowMs).also { forget(id) }
        val out = mutableListOf<GuardEvent>()
        if (!rejectAll) {
            when (p.state) {
                State.ACCEPTED -> out += GuardEvent.Up(id)
                State.PENDING -> if (nowMs - p.downMs < EDGE_REST_MS && accepted == 0) {
                    out += GuardEvent.Down(p.start)
                    out += GuardEvent.Up(id)
                }
                State.REJECTED -> Unit
            }
        }
        forget(id)
        return out
    }

    /** The whole gesture was cancelled (ACTION_CANCEL). */
    fun cancel(): List<GuardEvent> {
        val any = pointers.values.any { it.state == State.ACCEPTED }
        pointers.clear()
        rejectAll = false
        return if (any) listOf(GuardEvent.Cancel) else emptyList()
    }

    private fun forget(id: Int) {
        pointers.remove(id)
        if (pointers.isEmpty()) rejectAll = false
    }

    private fun palm(t: GuardTouch, nowMs: Long): List<GuardEvent> {
        val any = pointers.values.any { it.state == State.ACCEPTED }
        pointers.values.forEach { it.state = State.REJECTED }
        pointers[t.id] = pointers[t.id]?.also { it.state = State.REJECTED } ?: P(State.REJECTED, nowMs, t, t, t.majorMm)
        rejectAll = true
        refusal = if (t.majorMm >= PALM_MM) {
            "a palm: contact %.1f mm long (from %.0f mm) at %.1f, %.1f mm; the gesture is cancelled".format(java.util.Locale.ROOT, t.majorMm, PALM_MM, t.xMm, t.yMm)
        } else {
            "Android flagged the touch at %.1f, %.1f mm as a palm (FLAG_CANCELED); the gesture is cancelled".format(java.util.Locale.ROOT, t.xMm, t.yMm)
        }
        return if (any) listOf(GuardEvent.Cancel) else emptyList()
    }

    /** 0.5.18: why the last touch was refused (a palm or a resting edge grip), once, for the log. */
    private var refusal: String? = null

    fun takeRefusal(): String? = refusal.also { refusal = null }

    companion object {
        const val EDGE_MM = 3f
        const val EDGE_RELEASE_MM = 2f
        const val EDGE_REST_MS = 300L
        const val PALM_MM = 18f
        const val LIFT_SHRINK = 0.55f
    }
}

/**
 * 0.5.17 cover screen: the Further and Closer buttons held down. A press steps once at once; held
 * past [DELAY_MS] it repeats every [EVERY_MS], each step growing from [START_STEP] to [MAX_STEP]
 * over [RAMP_MS], so a short hold is fine and a long one crosses 1.3 to 3.8 m in about two seconds.
 *
 * 0.5.26: [MAX_STEP] 3.2 (was 1.6), so the wider 0.3 to 15 m range is crossed in about three seconds of holding; the
 * first second is as before, so a short hold is just as fine.
 */
class CoverRepeat {
    private var downMs: Long? = null
    private var nextMs = 0L

    val holding: Boolean get() = downMs != null

    /** The first step, at the press. */
    fun down(nowMs: Long): Float {
        downMs = nowMs
        nextMs = nowMs + DELAY_MS
        return 1f
    }

    fun up() {
        downMs = null
    }

    /** The steps due by [nowMs] (at most [MAX_CATCH_UP], so a late timer never jumps). */
    fun tick(nowMs: Long): List<Float> {
        val d = downMs ?: return emptyList()
        val out = mutableListOf<Float>()
        while (nowMs >= nextMs && out.size < MAX_CATCH_UP) {
            out += stepAt(nextMs - d)
            nextMs += EVERY_MS
        }
        if (nowMs >= nextMs) nextMs = nowMs + EVERY_MS
        return out
    }

    companion object {
        const val DELAY_MS = 300L
        const val EVERY_MS = 50L
        const val START_STEP = 0.6f
        const val MAX_STEP = 3.2f
        const val RAMP_MS = 1000L

        /** 0.5.26: the step a second into the hold, 0.5.25's whole ramp; past it the ramp goes on to [MAX_STEP]. */
        const val OLD_MAX_STEP = 1.6f
        const val MAX_CATCH_UP = 3

        /** The step for a repeat [heldMs] after the press. */
        fun stepAt(heldMs: Long): Float {
            val t = (heldMs - DELAY_MS).toFloat() / RAMP_MS
            if (t <= 1f) return START_STEP + (OLD_MAX_STEP - START_STEP) * t.coerceAtLeast(0f)
            // 0.5.26: the second second of the hold ramps on to MAX_STEP.
            return OLD_MAX_STEP + (MAX_STEP - OLD_MAX_STEP) * (t - 1f).coerceIn(0f, 1f)
        }
    }
}

/** 0.5.17 cover screen: one Further or Closer step is [STEP] 3% of the distance times its size. */
object CoverWindowStep {
    const val STEP = 0.03f

    fun distance(distanceM: Float, further: Boolean, step: Float): Float {
        val k = 1f + STEP * step.coerceIn(0f, 4f)
        return if (further) distanceM * k else distanceM / k
    }
}

/** What the cover screen's text field sends to the focused window. */
sealed interface TextOp {
    data class Type(val text: String) : TextOp
    data object Delete : TextOp
    data object Enter : TextOp
}

/**
 * 0.5.17 cover screen: text typed on the cover screen's own field (its keyboard opens on the
 * cover) goes to the focused window as keys. Each change of the field is the deletes back to
 * the common start and the new text after it, so autocorrect's replacements arrive right too.
 */
object CoverText {
    fun diff(old: String, new: String): List<TextOp> {
        var p = 0
        val n = min(old.length, new.length)
        while (p < n && old[p] == new[p]) p++
        val out = mutableListOf<TextOp>()
        repeat(old.length - p) { out += TextOp.Delete }
        val typed = new.substring(p)
        if (typed.isNotEmpty()) {
            // A new line typed is Enter, the rest text.
            val parts = typed.split('\n')
            parts.forEachIndexed { i, s ->
                if (i > 0) out += TextOp.Enter
                if (s.isNotEmpty()) out += TextOp.Type(s)
            }
        }
        return out
    }
}
