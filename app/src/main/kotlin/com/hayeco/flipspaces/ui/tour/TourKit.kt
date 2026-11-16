package com.hayeco.flipspaces.ui.tour

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.core.Look
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 1.5.0: what the tour's pictures are drawn with. Everything is "Light on glass" (docs/VISUAL-DIRECTION.md): smoked
 * glass with a hairline rim, one accent, depth shown with light. The colours, radii and motion times are [Look]'s.
 */

internal val Accent = Color(Look.ACCENT)
internal val AccentDeep = Color(Look.ACCENT_DEEP)
internal val Held = Color(Look.HELD)
internal val Ink = Color(Look.INK)
internal val Ink2 = Color(Look.INK_2)
internal val Ink3 = Color(Look.INK_3)
internal val Rim = Color(Look.RIM)
internal val RimFocus = Color(Look.RIM_FOCUS)
internal val Page = Color(Look.PAGE)
internal val Pad = Color(Look.PAD)
internal val Card = Color(Look.CARD)

/** Glass, opaque: on the phone there is a page behind it, not a room. */
internal val GlassTop = Color(0xFF242A39)
internal val GlassBottom = Color(0xFF181C26)
internal val GlassRaised = Color(0xFF1E2330)

/** [Look.EASE_ENTER] and [Look.EASE_EXIT] for Compose, and a sine for slow drifting. */
internal val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val ExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
internal val InOutEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
internal val SineEasing = Easing { 0.5f - 0.5f * cos(PI.toFloat() * it) }

/** The tour's three durations (docs/TUTORIAL.md): a press, an arrival, a beat inside a picture. */
internal const val QUICK_MS = 120
internal const val ENTER_MS = 280
internal const val EXIT_MS = 160
internal const val BEAT_S = 0.6f

/**
 * Seconds since this picture came up, read while drawing so a new frame never recomposes anything. With animations
 * removed ([reduced]) it stays at [poster], the moment that shows the picture's point as a still.
 */
@Composable
internal fun rememberSceneSeconds(key: Any?, reduced: Boolean, poster: Float): FloatState {
    val seconds = remember(key, reduced) { mutableFloatStateOf(if (reduced) poster else 0f) }
    if (!reduced) {
        LaunchedEffect(key) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { seconds.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    return seconds
}

/** How far [t] is from [from] to [to], 0 to 1, eased. */
internal fun span(t: Float, from: Float, to: Float, easing: Easing = EnterEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

internal fun mix(a: Float, b: Float, k: Float): Float = a + (b - a) * k
internal fun mix(a: Offset, b: Offset, k: Float): Offset = Offset(mix(a.x, b.x, k), mix(a.y, b.y, k))
internal fun mix(a: Color, b: Color, k: Float): Color = androidx.compose.ui.graphics.lerp(a, b, k.coerceIn(0f, 1f))

/** A one-off between [at] and [at] + [length]: its progress 0 to 1, or null outside. */
internal fun burst(t: Float, at: Float, length: Float): Float? = ((t - at) / length).takeIf { it in 0f..1f }

/** Fades a looping picture in at its start and out before [length], so the loop never jumps. */
internal fun loopAlpha(t: Float, length: Float, fadeIn: Float = 0.4f, fadeOut: Float = 0.45f): Float =
    span(t, 0f, fadeIn) * (1f - span(t, length - fadeOut, length, ExitEasing))

// ---- the Space, seen through the glasses ----

/** The eye: turned [yawDeg] to the right, standing [shiftX] metres to the right, drawing with [focal] pixels at 1 m. */
internal class Cam(val yawDeg: Float, val focal: Float, val cx: Float, val cy: Float, val shiftX: Float = 0f)

/**
 * A flat window in the Space: its middle [thetaDeg] to the right at [r] metres and [y] metres up, [w] by [h] metres,
 * facing the eye's place (as Pocket XR sets windows around you).
 */
internal class SpaceWin(val thetaDeg: Float, val y: Float, val w: Float, val h: Float, val r: Float = 1.5f) {
    /** The screen point of ([u], [v]) on the window (0,0 top left, 1,1 bottom right), or null behind the eye. */
    fun at(cam: Cam, u: Float, v: Float): Offset? {
        val a = Math.toRadians(thetaDeg.toDouble())
        val along = (u - 0.5f) * w
        val wx = r * sin(a).toFloat() + cos(a).toFloat() * along
        val wz = r * cos(a).toFloat() - sin(a).toFloat() * along
        val wy = y + (0.5f - v) * h
        val c = Math.toRadians(cam.yawDeg.toDouble())
        val x = (wx - cam.shiftX) * cos(c).toFloat() - wz * sin(c).toFloat()
        val z = (wx - cam.shiftX) * sin(c).toFloat() + wz * cos(c).toFloat()
        if (z < 0.2f) return null
        return Offset(cam.cx + cam.focal * x / z, cam.cy - cam.focal * wy / z)
    }

    /** The window's outline with rounded corners ([radius] metres), or null when any of it is behind the eye. */
    fun outline(cam: Cam, radius: Float = 0.035f): Path? {
        val ru = (radius / w).coerceAtMost(0.5f)
        val rv = (radius / h).coerceAtMost(0.5f)
        fun p(u: Float, v: Float) = at(cam, u, v)
        val pts = listOf(
            p(ru, 0f), p(1f - ru, 0f), p(1f, 0f), p(1f, rv), p(1f, 1f - rv), p(1f, 1f),
            p(1f - ru, 1f), p(ru, 1f), p(0f, 1f), p(0f, 1f - rv), p(0f, rv), p(0f, 0f),
        )
        if (pts.any { it == null }) return null
        val q = pts.map { it!! }
        return Path().apply {
            moveTo(q[0].x, q[0].y)
            lineTo(q[1].x, q[1].y)
            quadraticTo(q[2].x, q[2].y, q[3].x, q[3].y)
            lineTo(q[4].x, q[4].y)
            quadraticTo(q[5].x, q[5].y, q[6].x, q[6].y)
            lineTo(q[7].x, q[7].y)
            quadraticTo(q[8].x, q[8].y, q[9].x, q[9].y)
            lineTo(q[10].x, q[10].y)
            quadraticTo(q[11].x, q[11].y, q[0].x, q[0].y)
            close()
        }
    }

    /** A four cornered patch of the window, for a block of its content. */
    fun patch(cam: Cam, u0: Float, v0: Float, u1: Float, v1: Float): Path? {
        val a = at(cam, u0, v0) ?: return null
        val b = at(cam, u1, v0) ?: return null
        val c = at(cam, u1, v1) ?: return null
        val d = at(cam, u0, v1) ?: return null
        return Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
    }

    fun copy(thetaDeg: Float = this.thetaDeg, y: Float = this.y, w: Float = this.w, h: Float = this.h, r: Float = this.r) = SpaceWin(thetaDeg, y, w, h, r)
}

/** What a window shows: a sketch of an app, never a real one. */
internal enum class WinKind { TEXT, MEDIA, CHAT, LIST, BLANK }

private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }

/**
 * A window as the glasses show it: glass, its content, the hairline rim. [focus] brightens the rim and lights the blue
 * halo just outside the edge; [held] turns the rim to the "you are holding this" colour; [alpha] fades all of it.
 */
internal fun DrawScope.spaceWindow(
    cam: Cam,
    win: SpaceWin,
    kind: WinKind,
    focus: Float = 0f,
    held: Float = 0f,
    alpha: Float = 1f,
    content: (DrawScope.(SpaceWin, Cam, Float) -> Unit)? = null,
) {
    if (alpha <= 0.01f) return
    val outline = win.outline(cam) ?: return
    val bounds = outline.getBounds()
    val glow = maxOf(focus, held)
    if (glow > 0.01f) {
        // The halo: one soft stroke outside the edge. Light, not shadow.
        drawIntoCanvas { canvas ->
            haloPaint.color = mix(Accent, Held, held).copy(alpha = 0.42f * glow * alpha).toArgb()
            haloPaint.strokeWidth = 5.dp.toPx()
            haloPaint.maskFilter = BlurMaskFilter(9.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawPath(outline.asAndroidPath(), haloPaint)
        }
    }
    drawPath(outline, Brush.verticalGradient(listOf(GlassTop, GlassBottom), bounds.top, bounds.bottom), alpha = alpha)
    if (content != null) content(win, cam, alpha) else windowContent(cam, win, kind, alpha)
    val rim = mix(mix(Rim, RimFocus, focus), Held, held)
    drawPath(outline, rim, alpha = alpha, style = Stroke(1.5.dp.toPx()))
}

/** A line of the window's content from ([u0], [v]) to ([u1], [v]). */
internal fun DrawScope.winLine(cam: Cam, win: SpaceWin, u0: Float, u1: Float, v: Float, color: Color, alpha: Float, widthDp: Float = 3f) {
    val a = win.at(cam, u0, v) ?: return
    val b = win.at(cam, u1, v) ?: return
    drawLine(color, a, b, strokeWidth = widthDp.dp.toPx(), cap = StrokeCap.Round, alpha = alpha)
}

internal fun DrawScope.winBlock(cam: Cam, win: SpaceWin, u0: Float, v0: Float, u1: Float, v1: Float, color: Color, alpha: Float) {
    win.patch(cam, u0, v0, u1, v1)?.let { drawPath(it, color, alpha = alpha) }
}

internal fun DrawScope.windowContent(cam: Cam, win: SpaceWin, kind: WinKind, alpha: Float) {
    val soft = Ink.copy(alpha = 0.16f)
    val softer = Ink.copy(alpha = 0.09f)
    when (kind) {
        WinKind.BLANK -> Unit
        WinKind.TEXT -> {
            winLine(cam, win, 0.1f, 0.5f, 0.16f, Ink.copy(alpha = 0.34f), alpha, 4f)
            var v = 0.32f
            for (end in floatArrayOf(0.9f, 0.84f, 0.88f, 0.6f, 0.86f, 0.72f)) {
                winLine(cam, win, 0.1f, end, v, soft, alpha)
                v += 0.105f
            }
        }
        WinKind.MEDIA -> {
            winBlock(cam, win, 0.07f, 0.1f, 0.93f, 0.7f, Color(0xFF2B3550), alpha)
            // A play mark in the picture's middle.
            val a = win.at(cam, 0.46f, 0.31f)
            val b = win.at(cam, 0.46f, 0.49f)
            val c = win.at(cam, 0.56f, 0.4f)
            if (a != null && b != null && c != null) {
                drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, Ink.copy(alpha = 0.7f), alpha = alpha)
            }
            winLine(cam, win, 0.07f, 0.55f, 0.8f, Ink.copy(alpha = 0.34f), alpha, 4f)
            winLine(cam, win, 0.07f, 0.38f, 0.9f, softer, alpha)
        }
        WinKind.CHAT -> {
            winBlock(cam, win, 0.08f, 0.12f, 0.62f, 0.26f, soft, alpha)
            winBlock(cam, win, 0.4f, 0.33f, 0.92f, 0.47f, AccentDeep.copy(alpha = 0.75f), alpha)
            winBlock(cam, win, 0.08f, 0.54f, 0.7f, 0.68f, soft, alpha)
            winBlock(cam, win, 0.52f, 0.75f, 0.92f, 0.89f, AccentDeep.copy(alpha = 0.75f), alpha)
        }
        WinKind.LIST -> {
            var v = 0.16f
            repeat(5) {
                winBlock(cam, win, 0.08f, v - 0.045f, 0.2f, v + 0.045f, softer, alpha)
                winLine(cam, win, 0.27f, if (it % 2 == 0) 0.86f else 0.7f, v, soft, alpha)
                v += 0.17f
            }
        }
    }
}

// ---- the pointer and the fingers ----

/** The dot: a crisp light ring with a small glow; [fill] is the hold that fills it before a resize. */
internal fun DrawScope.cursor(at: Offset, fill: Float = 0f, alpha: Float = 1f) {
    drawCircle(Ink.copy(alpha = 0.12f), 12.dp.toPx(), at, alpha = alpha)
    drawCircle(Color(0xCC0C0D10), 6.dp.toPx(), at, alpha = alpha)
    if (fill > 0.01f) drawCircle(Accent, 6.dp.toPx() * fill, at, alpha = alpha)
    drawCircle(Ink, 6.dp.toPx(), at, alpha = alpha, style = Stroke(2.dp.toPx()))
}

/** A click or a tap answering: a ring going out, [k] 0 to 1. */
internal fun DrawScope.ripple(at: Offset, k: Float, color: Color = Accent, alpha: Float = 1f) {
    val e = EnterEasing.transform(k.coerceIn(0f, 1f))
    drawCircle(color.copy(alpha = 0.7f * (1f - e)), 7.dp.toPx() + 20.dp.toPx() * e, at, alpha = alpha, style = Stroke(2.dp.toPx()))
}

/** A fingertip on a pad: white with the accent's glow while [down] (0 lifted, 1 touching). */
internal fun DrawScope.finger(at: Offset, down: Float = 1f, alpha: Float = 1f, sizeDp: Float = 8f) {
    val r = sizeDp.dp.toPx()
    drawCircle(Accent.copy(alpha = 0.3f * down), r * 2f, at, alpha = alpha)
    drawCircle(Ink.copy(alpha = mix(0.3f, 1f, down)), r, at, alpha = alpha)
}

/** A small trackpad, for showing what the fingers do: glass with a rim. */
internal fun DrawScope.miniPad(rect: Rect, alpha: Float = 1f) {
    val r = CornerRadius(14.dp.toPx())
    drawRoundRect(GlassRaised, rect.topLeft, rect.size, r, alpha = alpha)
    drawRoundRect(RimFocus, rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(1.dp.toPx()))
}

// ---- the things themselves ----

/** A pair of glasses, front on, [width] wide about [centre]. [lit] 0 to 1 lights the lenses in the accent. */
internal fun DrawScope.glasses(centre: Offset, width: Float, lit: Float = 0f, alpha: Float = 1f): Rect {
    val lens = Size(width * 0.4f, width * 0.24f)
    val gap = width * 0.1f
    val top = centre.y - lens.height / 2f
    val left = Offset(centre.x - gap / 2f - lens.width, top)
    val right = Offset(centre.x + gap / 2f, top)
    val r = CornerRadius(lens.height * 0.34f)
    val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val frame = mix(Ink, Color.White, lit)
    for (o in listOf(left, right)) {
        if (lit > 0.01f) {
            drawRoundRect(Accent.copy(alpha = 0.22f * lit), Offset(o.x - 6.dp.toPx(), o.y - 6.dp.toPx()), Size(lens.width + 12.dp.toPx(), lens.height + 12.dp.toPx()), CornerRadius(r.x + 6.dp.toPx()), alpha = alpha)
        }
        drawRoundRect(mix(GlassRaised, Color(0xFF24365E), lit), o, lens, r, alpha = alpha)
        drawRoundRect(frame, o, lens, r, alpha = alpha, style = stroke)
    }
    val bridgeY = top + lens.height * 0.3f
    drawLine(frame, Offset(centre.x - gap / 2f, bridgeY), Offset(centre.x + gap / 2f, bridgeY), stroke.width, StrokeCap.Round, alpha = alpha)
    val arm = width * 0.09f
    drawLine(frame, Offset(left.x, bridgeY), Offset(left.x - arm, bridgeY - arm * 0.45f), stroke.width, StrokeCap.Round, alpha = alpha)
    drawLine(frame, Offset(right.x + lens.width, bridgeY), Offset(right.x + lens.width + arm, bridgeY - arm * 0.45f), stroke.width, StrokeCap.Round, alpha = alpha)
    return Rect(left.x - arm, top, right.x + lens.width + arm, top + lens.height)
}

/** A phone, upright in [rect]: glass body, the trackpad's page on its screen. */
internal fun DrawScope.phone(rect: Rect, alpha: Float = 1f) {
    val r = CornerRadius(rect.width * 0.16f)
    drawRoundRect(GlassRaised, rect.topLeft, rect.size, r, alpha = alpha)
    val inset = rect.width * 0.07f
    drawRoundRect(Pad, Offset(rect.left + inset, rect.top + inset), Size(rect.width - 2 * inset, rect.height - 2 * inset), CornerRadius(r.x - inset), alpha = alpha)
    drawRoundRect(RimFocus, rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(1.5.dp.toPx()))
}

/**
 * The trackpad's bar as it is on the open phone: Back, Recentre, Home (glasses with Steady mode get its key too, left
 * out here). Returns Recentre's middle.
 */
internal fun DrawScope.padBar(rect: Rect, recentreLit: Float = 0f, alpha: Float = 1f): Offset {
    val r = CornerRadius(rect.height * 0.36f)
    drawRoundRect(GlassRaised, rect.topLeft, rect.size, r, alpha = alpha)
    drawRoundRect(Rim, rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(1.dp.toPx()))
    val slot = rect.width / 3f
    val cy = rect.center.y
    val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val u = rect.height * 0.2f
    fun mid(i: Int) = Offset(rect.left + slot * (i + 0.5f), cy)
    // Back: an arrow to the left.
    mid(0).let { m ->
        drawLine(Ink, Offset(m.x - u, m.y), Offset(m.x + u, m.y), s.width, StrokeCap.Round, alpha = alpha)
        drawPath(Path().apply { moveTo(m.x - u * 0.2f, m.y - u * 0.8f); lineTo(m.x - u, m.y); lineTo(m.x - u * 0.2f, m.y + u * 0.8f) }, Ink, alpha = alpha, style = s)
    }
    // Recentre: a ring with four ticks and a centre dot (ic_cover_recentre).
    val rc = mid(1)
    if (recentreLit > 0.01f) drawCircle(Accent.copy(alpha = 0.3f * recentreLit), rect.height * 0.44f, rc, alpha = alpha)
    drawCircle(Ink, u * 0.8f, rc, alpha = alpha, style = s)
    drawCircle(Ink, u * 0.24f, rc, alpha = alpha)
    for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
        drawLine(Ink, Offset(rc.x + dx * u * 0.8f, rc.y + dy * u * 0.8f), Offset(rc.x + dx * u * 1.3f, rc.y + dy * u * 1.3f), s.width, StrokeCap.Round, alpha = alpha)
    }
    // Home: a house.
    mid(2).let { m ->
        drawPath(
            Path().apply {
                moveTo(m.x - u * 0.85f, m.y + u)
                lineTo(m.x - u * 0.85f, m.y - u * 0.2f)
                lineTo(m.x, m.y - u)
                lineTo(m.x + u * 0.85f, m.y - u * 0.2f)
                lineTo(m.x + u * 0.85f, m.y + u)
                close()
            },
            Ink, alpha = alpha, style = s,
        )
    }
    for (i in 1..2) {
        val x = rect.left + slot * i
        drawLine(Rim, Offset(x, rect.top + rect.height * 0.3f), Offset(x, rect.bottom - rect.height * 0.3f), 1.dp.toPx(), alpha = alpha)
    }
    return rc
}

/** A tick drawing itself inside a ring: [ring] and [tick] 0 to 1. */
internal fun DrawScope.tickRing(centre: Offset, radius: Float, ring: Float, tick: Float, color: Color = Accent, alpha: Float = 1f, widthDp: Float = 2.5f) {
    val s = Stroke(widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (ring > 0.001f) {
        drawArc(color, -90f, 360f * ring, false, Offset(centre.x - radius, centre.y - radius), Size(radius * 2f, radius * 2f), alpha = alpha, style = s)
    }
    if (tick > 0.001f) {
        val a = Offset(centre.x - radius * 0.42f, centre.y + radius * 0.02f)
        val b = Offset(centre.x - radius * 0.1f, centre.y + radius * 0.34f)
        val c = Offset(centre.x + radius * 0.46f, centre.y - radius * 0.3f)
        val first = (tick / 0.4f).coerceIn(0f, 1f)
        val second = ((tick - 0.4f) / 0.6f).coerceIn(0f, 1f)
        drawLine(color, a, mix(a, b, first), s.width, StrokeCap.Round, alpha = alpha)
        if (second > 0f) drawLine(color, b, mix(b, c, second), s.width, StrokeCap.Round, alpha = alpha)
    }
}
