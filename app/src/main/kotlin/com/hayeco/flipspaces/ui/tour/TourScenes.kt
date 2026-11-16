package com.hayeco.flipspaces.ui.tour

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.core.tutorial.Tour
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/*
 * 1.5.0: the tour's pictures, one per step (docs/TUTORIAL.md). Each is drawn from the seconds since it came up, so it
 * is the same picture every time and a still at any moment. Each has one thing the eye follows, what answers it, and
 * slow life behind; nothing moves that the words do not say.
 */

/** What a picture needs beside the time. */
internal class SceneInput(
    /** 0 to 1: the glasses are seen (the cable lights). */
    val link: Float = 0f,
    val practice: PracticeState? = null,
    /** Everything needed is set up (the last step's tick). */
    val complete: Boolean = false,
    /** The phone can aim (it has the rotation sensor), so Spatial Mouse is offered. */
    val spatialMouse: Boolean = true,
)

/** Which of a step's lines the picture is showing now, so the words can light the same line; -1 for none. */
internal fun scenePhase(step: Tour.Step, t: Float, spatialMouse: Boolean = true): Int = when (step) {
    Tour.Step.THREE_FINGERS -> ((t % THREE_LOOP) / THREE_PART).toInt().coerceIn(0, 2)
    Tour.Step.WINDOWS -> (t % WINDOWS_LOOP).let { if (it < 3.1f) 0 else if (it < 6.6f) 1 else -1 }
    Tour.Step.DEPTH -> if (t % DEPTH_LOOP < DEPTH_ROW_AT) 0 else 1
    Tour.Step.MORE -> morePart(t, spatialMouse)
    else -> -1
}

/** The moment that shows each picture's point, for when animations are removed. */
internal fun scenePoster(step: Tour.Step): Float = when (step) {
    Tour.Step.WELCOME -> 3.1f
    Tour.Step.SETUP -> 1.2f
    Tour.Step.POINT -> 0f
    Tour.Step.THREE_FINGERS -> 1.6f
    Tour.Step.WINDOWS -> 2.2f
    Tour.Step.LOOK -> 6f
    Tour.Step.DEPTH -> 3.2f
    Tour.Step.MORE -> 1.8f
    Tour.Step.DONE -> 3f
}

internal fun DrawScope.drawScene(step: Tour.Step, t: Float, input: SceneInput) {
    when (step) {
        Tour.Step.WELCOME -> welcome(t)
        Tour.Step.SETUP -> setup(t, input.link)
        Tour.Step.POINT -> point(t, input.practice)
        Tour.Step.THREE_FINGERS -> threeFingers(t)
        Tour.Step.WINDOWS -> windows(t)
        Tour.Step.LOOK -> look(t)
        Tour.Step.DEPTH -> depth(t)
        Tour.Step.MORE -> more(t, input.spatialMouse)
        Tour.Step.DONE -> done(t, input.complete)
    }
}

private fun DrawScope.focal(): Float = min(size.width, size.height * 1.35f) * 0.92f
private fun DrawScope.cam(yaw: Float = 0f, shiftX: Float = 0f, cyShare: Float = 0.5f, zoom: Float = 1f) =
    Cam(yaw, focal() * zoom, size.width / 2f, size.height * cyShare, shiftX)

// ---- 1. Welcome: windows all around, the view drifting as a head would ----

private val ARC = listOf(
    Triple(0f, 0f, WinKind.MEDIA),
    Triple(-35f, 0.03f, WinKind.CHAT),
    Triple(35f, -0.02f, WinKind.TEXT),
    Triple(-70f, -0.02f, WinKind.LIST),
    Triple(70f, 0.03f, WinKind.TEXT),
)

private fun DrawScope.welcome(t: Float) {
    val cam = cam(yaw = 6f * sin(t * 0.5f), zoom = 0.86f)
    ARC.forEachIndexed { i, (theta, y, kind) ->
        val delay = 0.14f * ((i + 1) / 2)
        val a = span(t, delay, delay + 0.55f)
        val win = SpaceWin(theta, y - (1f - a) * 0.05f, 0.92f, 0.58f)
        val breathe = 0.82f + 0.18f * sin(t * 1.3f)
        spaceWindow(cam, win, kind, focus = if (i == 0) breathe else 0f, alpha = a * if (i == 0) 1f else 0.82f)
    }
}

// ---- 2. Get set up: a phone, glasses, and the cable between them ----

private fun DrawScope.setup(t: Float, link: Float) {
    val w = size.width
    val h = size.height
    val unit = min(w, h * 1.5f)
    val enter = span(t, 0f, 0.5f)
    val phoneH = min(h * 0.62f, unit * 0.36f)
    val phoneRect = Rect(Offset(w * 0.5f - unit * 0.34f, h * 0.44f - phoneH * 0.5f + (1f - enter) * 10.dp.toPx()), Size(phoneH * 0.5f, phoneH))
    val gWidth = unit * 0.36f
    val gCentre = Offset(w * 0.5f + unit * 0.17f, h * 0.36f + (1f - span(t, 0.08f, 0.58f)) * 10.dp.toPx())
    phone(phoneRect, alpha = enter)
    val gRect = glasses(gCentre, gWidth, lit = link, alpha = span(t, 0.08f, 0.58f))

    // The cable: from the phone's port, under it, up to the end of the glasses' arm.
    val port = Offset(phoneRect.center.x, phoneRect.bottom)
    val armEnd = Offset(gRect.left, gRect.top + gRect.height * 0.12f)
    // Unplugged, the plug hangs a little below the port and nudges toward it: this is the thing to do.
    val nudge = (1f - link) * (16.dp.toPx() - 6.dp.toPx() * (0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * t / 1.8f)))
    val plug = Offset(port.x, port.y + nudge + 3.dp.toPx())
    val cable = Path().apply {
        moveTo(plug.x, plug.y)
        cubicTo(plug.x, plug.y + h * 0.2f, armEnd.x - unit * 0.1f, plug.y + h * 0.22f, armEnd.x - unit * 0.06f, armEnd.y + h * 0.2f)
        quadraticTo(armEnd.x - unit * 0.045f, armEnd.y + h * 0.02f, armEnd.x, armEnd.y)
    }
    val cableAlpha = span(t, 0.2f, 0.7f)
    drawPath(cable, mix(Ink3, Ink2, link), alpha = cableAlpha, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    // The plug.
    val plugW = phoneRect.width * 0.2f
    drawRoundRect(mix(Ink2, Color.White, link), Offset(plug.x - plugW / 2f, plug.y - 3.dp.toPx()), Size(plugW, 7.dp.toPx()), CornerRadius(2.5.dp.toPx()), alpha = cableAlpha)
    if (link < 0.5f) {
        // Two small chevrons pointing up at the port.
        val k = (t / 1.8f) % 1f
        val a = (1f - link * 2f) * cableAlpha * (0.25f + 0.55f * sin(PI.toFloat() * k))
        val x = plug.x + plugW * 1.3f
        val y = port.y + 12.dp.toPx() - 4.dp.toPx() * k
        val c = 4.dp.toPx()
        for (dy in listOf(0f, 7.dp.toPx())) {
            drawPath(Path().apply { moveTo(x - c, y + dy + c); lineTo(x, y + dy); lineTo(x + c, y + dy + c) }, Accent, alpha = a, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    if (link > 0.01f) {
        // Light running along the cable to the glasses, again and again while they are connected.
        val measure = PathMeasure().apply { setPath(cable, false) }
        val len = measure.length
        val head = ((t / 2.2f) % 1f).let { InOutEasing.transform(it) } * (len + 60.dp.toPx())
        val lit = Path()
        measure.getSegment((head - 60.dp.toPx()).coerceAtLeast(0f), head.coerceAtMost(len), lit, true)
        drawPath(lit, Accent, alpha = link * cableAlpha, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(lit, Accent.copy(alpha = 0.3f), alpha = link * cableAlpha, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---- 3. Point and click: the user's own finger moves the dot ----

/** The practice view's button, on its window (u, v). */
internal val PRACTICE_BUTTON = Rect(0.56f, 0.66f, 0.92f, 0.88f)
internal val PRACTICE_WIN = SpaceWin(0f, 0.0f, 1.28f, 0.74f)

private fun DrawScope.point(t: Float, practice: PracticeState?) {
    val cam = cam()
    val win = PRACTICE_WIN
    val enter = span(t, 0f, 0.5f)
    val p = practice
    val scroll = p?.scroll ?: 0f
    val clicked = p?.buttonLit ?: 0f
    spaceWindow(cam, win.copy(y = -(1f - enter) * 0.04f), WinKind.BLANK, focus = 1f, alpha = enter) { w, c, a ->
        val outline = w.outline(c) ?: return@spaceWindow
        clipPath(outline) {
            // A page of lines that scrolls under two fingers.
            winLine(c, w, 0.08f, 0.46f, 0.14f - scroll * 0.0f, Ink.copy(alpha = 0.34f), a, 4f)
            for (i in 0 until 14) {
                val v = 0.3f + i * 0.11f - scroll
                if (v < 0.2f || v > 1.05f) continue
                val end = floatArrayOf(0.9f, 0.82f, 0.88f, 0.6f, 0.86f, 0.74f, 0.9f)[i % 7]
                val fade = span(v, 0.2f, 0.27f)
                winLine(c, w, 0.08f, if (v > PRACTICE_BUTTON.top - 0.06f) minOf(end, 0.5f) else end, v, Ink.copy(alpha = 0.16f), a * fade)
            }
        }
        // The button to click.
        val b = PRACTICE_BUTTON
        w.patch(c, b.left, b.top, b.right, b.bottom)?.let {
            drawPath(it, mix(Color(0xFF2A3142), Accent, clicked), alpha = a)
            drawPath(it, mix(RimFocus, Color.White, clicked), alpha = a, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
        val bc = w.at(c, b.center.x, b.center.y)
        if (bc != null) {
            if (clicked > 0.5f) tickRing(bc, 9.dp.toPx(), 0f, span(clicked, 0.5f, 1f), Color.White, a, 2.2f)
            else winLine(c, w, b.left + 0.09f, b.right - 0.09f, b.center.y, Ink.copy(alpha = 0.5f), a * (1f - clicked * 2f), 3.5f)
        }
    }
    val at = (p?.cursorOn(win, cam, t)) ?: win.at(cam, 0.3f, 0.45f) ?: return
    p?.clickAgeSeconds(t)?.let { age -> burst(age, 0f, 0.45f)?.let { ripple(at, it, alpha = enter) } }
    cursor(at, alpha = enter)
}

// ---- 4. Three fingers ----

internal const val THREE_PART = 2.7f
internal const val THREE_LOOP = THREE_PART * 3f

private fun DrawScope.threeFingers(t: Float) {
    val part = ((t % THREE_LOOP) / THREE_PART).toInt().coerceIn(0, 2)
    val lt = t % THREE_PART
    val enter = span(t, 0f, 0.5f)
    val cam = cam(cyShare = 0.36f, zoom = 0.86f)
    val win = SpaceWin(0f, 0f, 1.2f, 0.66f)
    val answer = span(lt, 0.55f, 0.9f) * (1f - span(lt, 2.25f, 2.42f, ExitEasing))

    // Sideways is Back: the page slides off to the right and the one before comes in from the left.
    val back = if (part == 2) span(lt, 0.55f, 1.05f) * (1f - span(lt, 2.25f, 2.42f, ExitEasing)) else 0f
    spaceWindow(cam, win, WinKind.BLANK, focus = 1f - 0.6f * answer * (if (part < 2) 1f else 0f), alpha = enter) { w, c, a ->
        val outline = w.outline(c) ?: return@spaceWindow
        clipPath(outline) {
            val shifted = SpaceWin(w.thetaDeg + back * 14f, w.y, w.w, w.h, w.r)
            windowContent(c, shifted, WinKind.TEXT, a * (1f - back))
            if (back > 0.01f) windowContent(c, SpaceWin(w.thetaDeg - (1f - back) * 14f, w.y, w.w, w.h, w.r), WinKind.LIST, a * back)
        }
        if (part == 2) {
            // The Back chevron at the window's left edge lights as it happens.
            val m = w.at(c, 0.06f, 0.5f)
            val lit = burst(lt, 0.55f, 0.7f)?.let { sin(PI.toFloat() * it) } ?: 0f
            if (m != null && lit > 0.01f) {
                val u = 7.dp.toPx()
                drawCircle(Accent.copy(alpha = 0.9f), 13.dp.toPx(), m, alpha = lit * a)
                drawPath(Path().apply { moveTo(m.x + u * 0.35f, m.y - u * 0.7f); lineTo(m.x - u * 0.4f, m.y); lineTo(m.x + u * 0.35f, m.y + u * 0.7f) }, Color.White, alpha = lit * a, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    if (part == 0 && answer > 0.01f) {
        // Up: your apps. A glass sheet of app tiles rises in front of the window.
        val rise = (1f - answer) * 0.05f
        val sheet = SpaceWin(0f, -0.02f - rise, 0.94f, 0.52f, 1.42f)
        spaceWindow(cam, sheet, WinKind.BLANK, focus = 1f, alpha = answer * enter) { w, c, a ->
            val tints = listOf(0xFF5A93FF, 0xFF3D4A66, 0xFF4B5873, 0xFF2F3B57, 0xFF3D4A66, 0xFF8FB6FF, 0xFF2F3B57, 0xFF4B5873)
            for (i in 0 until 8) {
                val col = i % 4
                val row = i / 4
                val k = span(lt, 0.62f + i * 0.03f, 0.9f + i * 0.03f)
                val u0 = 0.09f + col * 0.215f
                val v0 = 0.16f + row * 0.4f + (1f - k) * 0.05f
                w.patch(c, u0, v0, u0 + 0.17f, v0 + 0.29f)?.let { drawPath(it, Color(tints[i]), alpha = a * k) }
            }
        }
    }
    if (part == 1 && answer > 0.01f) {
        // Down: the Control Center comes down over the window.
        val drop = (1f - answer) * 0.05f
        val card = SpaceWin(0f, 0.03f + drop, 0.98f, 0.5f, 1.42f)
        spaceWindow(cam, card, WinKind.BLANK, focus = 1f, alpha = answer * enter) { w, c, a ->
            winLine(c, w, 0.07f, 0.34f, 0.15f, Ink.copy(alpha = 0.5f), a, 4f)
            for (i in 0 until 6) {
                val col = i % 3
                val row = i / 3
                val u0 = 0.07f + col * 0.3f
                val v0 = 0.29f + row * 0.24f
                val on = i == 1 || i == 3
                w.patch(c, u0, v0, u0 + 0.26f, v0 + 0.17f)?.let { drawPath(it, if (on) Accent else Color(0xFF2A3142), alpha = a) }
            }
            winLine(c, w, 0.07f, 0.93f, 0.86f, Ink.copy(alpha = 0.16f), a, 3f)
            winLine(c, w, 0.07f, 0.62f, 0.86f, Accent, a, 3f)
        }
    }

    // The pad under it, with three fingers swiping the way the words say.
    val padW = min(size.width * 0.3f, 112.dp.toPx())
    val padH = min(size.height * 0.36f, padW * 1.05f)
    val pad = Rect(Offset(size.width / 2f - padW / 2f, size.height * 0.95f - padH), Size(padW, padH))
    miniPad(pad, alpha = enter)
    val down = span(lt, 0.1f, 0.3f) * (1f - span(lt, 0.95f, 1.2f, ExitEasing))
    val go = span(lt, 0.3f, 0.9f)
    val travel = padH * 0.44f
    val dir = when (part) { 0 -> Offset(0f, -1f); 1 -> Offset(0f, 1f); else -> Offset(1f, 0f) }
    val centre = Offset(pad.center.x - dir.x * travel * 0.5f + dir.x * travel * go, pad.center.y - dir.y * travel * 0.5f + dir.y * travel * go)
    val gap = padW * 0.2f
    for (k in -1..1) {
        val at = if (part == 2) Offset(centre.x, centre.y + k * gap) else Offset(centre.x + k * gap, centre.y + kotlin.math.abs(k) * 3.dp.toPx())
        // A short trail behind each finger shows the way it went.
        if (go > 0.02f && down > 0.02f) {
            drawLine(Accent, Offset(at.x - dir.x * travel * go, at.y - dir.y * travel * go), at, 2.5.dp.toPx(), StrokeCap.Round, alpha = 0.55f * down * enter)
        }
        finger(at, down, alpha = enter * maxOf(down, 0.0f), sizeDp = 6.5f)
    }
}

// ---- 5. Move and resize ----

internal const val WINDOWS_LOOP = 9f

private fun DrawScope.windows(t: Float) {
    val lt = t % WINDOWS_LOOP
    val alpha = loopAlpha(lt, WINDOWS_LOOP) * span(t, 0f, 0.4f)
    val cam = cam(cyShare = 0.5f, zoom = 0.9f)

    val carried = span(lt, 1.25f, 2.6f, InOutEasing)
    val held = span(lt, 0.9f, 1.02f) * (1f - span(lt, 2.8f, 2.96f, ExitEasing))
    val grown = span(lt, 4.95f, 6.3f, InOutEasing)
    val w = mix(0.86f, 1.16f, grown)
    val h = mix(0.52f, 0.7f, grown)
    // Growing from the corner handle keeps the top left corner where it is.
    val theta = mix(10f, -9f, carried) + Math.toDegrees(((w - 0.86f) / 2f / 1.5f).toDouble()).toFloat()
    val y = mix(0.1f, 0.13f, carried) - (h - 0.52f) / 2f
    val win = SpaceWin(theta, y, w, h)
    val resizing = span(lt, 4.0f, 4.12f) * (1f - span(lt, 6.4f, 6.56f, ExitEasing))

    // A quiet window behind, so the moved one has something to move against.
    spaceWindow(cam, SpaceWin(-27f, -0.02f, 0.6f, 0.4f, 1.9f), WinKind.LIST, alpha = alpha * 0.5f)
    spaceWindow(cam, win, WinKind.MEDIA, focus = 1f, held = maxOf(held, resizing * 0.6f), alpha = alpha)

    // The controls over the window: Back, the Move pill, three round buttons.
    val cv = -0.085f * (0.52f / h)
    val ch = 0.05f * (0.52f / h)
    val pillLit = maxOf(span(lt, 0.55f, 0.9f) * (1f - span(lt, 2.9f, 3.2f)), held)
    val showControls = alpha * (1f - span(lt, 3.5f, 3.9f) * (1f - span(lt, 6.6f, 7f)))
    win.patch(cam, 0.36f, cv - ch, 0.64f, cv + ch)?.let { drawPath(it, mix(Color(0xFF2A3142), mix(Accent, Held, held), pillLit), alpha = showControls) }
    val ru = ch * h / w
    for (u in floatArrayOf(0.04f, 0.8f, 0.88f, 0.96f)) {
        win.patch(cam, u - ru, cv - ch, u + ru, cv + ch)?.let { drawPath(it, Color(0xFF2A3142), alpha = showControls) }
    }

    // The blue corner handle.
    val handle = win.at(cam, 1f, 1f) ?: return
    val handleLit = 0.55f + 0.45f * maxOf(span(lt, 3.5f, 4f) * (1f - span(lt, 6.5f, 7f)), resizing)
    run {
        // A bracket hugging the corner from outside, the same length along each edge.
        val arm = 15.dp.toPx()
        val out = 5.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(handle.x - arm, handle.y + out)
                lineTo(handle.x - 4.dp.toPx(), handle.y + out)
                quadraticTo(handle.x + out, handle.y + out, handle.x + out, handle.y - 4.dp.toPx())
                lineTo(handle.x + out, handle.y - arm)
            },
            Accent, alpha = alpha * handleLit, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // The dot: to the pill, carried with the window, then to the handle, held until it fills, then dragging.
    val pill = win.at(cam, 0.5f, cv) ?: return
    val start = Offset(size.width * 0.66f, size.height * 0.7f)
    val dot = when {
        lt < 3.15f -> mix(start, pill, span(lt, 0.1f, 0.85f, InOutEasing))
        else -> mix(pill, Offset(handle.x + 2.dp.toPx(), handle.y + 2.dp.toPx()), span(lt, 3.15f, 3.95f, InOutEasing))
    }
    val fill = span(lt, 4.05f, 4.9f, androidx.compose.animation.core.LinearEasing) * (1f - span(lt, 6.4f, 6.56f, ExitEasing))
    burst(lt, 0.9f, 0.45f)?.let { ripple(dot, it, alpha = alpha) }
    burst(lt, 2.8f, 0.45f)?.let { ripple(dot, it, alpha = alpha) }
    cursor(dot, fill = fill, alpha = alpha)

    // The pad, small in the corner: what the finger does for each of those.
    val padW = min(size.width * 0.2f, 76.dp.toPx())
    val pad = Rect(Offset(size.width - padW - 14.dp.toPx(), size.height - padW * 1.15f - 12.dp.toPx()), Size(padW, padW * 1.15f))
    miniPad(pad, alpha = alpha * 0.92f)
    val tap1 = burst(lt, 0.86f, 0.22f)?.let { sin(PI.toFloat() * it) } ?: 0f
    val tap2 = burst(lt, 2.76f, 0.22f)?.let { sin(PI.toFloat() * it) } ?: 0f
    val swipe = span(lt, 1.2f, 1.3f) * (1f - span(lt, 2.6f, 2.7f))
    val reach = span(lt, 3.1f, 3.2f) * (1f - span(lt, 6.4f, 6.56f))
    val touch = maxOf(tap1, tap2, swipe, reach, span(lt, 0.1f, 0.2f) * (1f - span(lt, 0.8f, 0.86f)))
    val fx = pad.center.x + padW * (0.12f * (1f - span(lt, 0.1f, 0.85f, InOutEasing)) - 0.22f * carried + 0.22f * span(lt, 3.15f, 3.95f, InOutEasing) + 0.1f * grown)
    val fy = pad.center.y + padW * (0.1f - 0.2f * span(lt, 0.1f, 0.85f, InOutEasing) + 0.22f * span(lt, 3.15f, 3.95f, InOutEasing) + 0.1f * grown)
    finger(Offset(fx, fy), touch, alpha = alpha * maxOf(touch, 0.25f), sizeDp = 6f)
}

// ---- 6. Look around, and Recentre ----

internal const val LOOK_LOOP = 9f

private fun DrawScope.look(t: Float) {
    val lt = t % LOOK_LOOP
    val alpha = loopAlpha(lt, LOOK_LOOP) * span(t, 0f, 0.4f)
    val yaw = 46f * span(lt, 0.9f, 2.9f, InOutEasing)
    val centre = 46f * span(lt, 4.25f, 5f)
    val cam = cam(yaw = yaw, cyShare = 0.47f, zoom = 0.82f)
    listOf(Triple(-39f, 0.02f, WinKind.CHAT), Triple(0f, 0f, WinKind.MEDIA), Triple(39f, -0.02f, WinKind.TEXT)).forEachIndexed { i, (theta, y, kind) ->
        spaceWindow(cam, SpaceWin(theta + centre, y, 0.92f, 0.58f), kind, focus = if (i == 1) 1f else 0f, alpha = alpha * if (i == 1) 1f else 0.85f)
    }

    // Over it, which way the head points (the white mark) and where the windows are centred (the blue one).
    val gw = min(size.width * 0.56f, 240.dp.toPx())
    val gy = size.height * 0.07f + 4.dp.toPx()
    val gx = size.width / 2f
    val perDeg = gw / 120f
    drawLine(Rim, Offset(gx - gw / 2f, gy), Offset(gx + gw / 2f, gy), 1.5.dp.toPx(), StrokeCap.Round, alpha = alpha)
    for (d in -60..60 step 15) {
        drawLine(RimFocus, Offset(gx + d * perDeg, gy - 3.dp.toPx()), Offset(gx + d * perDeg, gy + 3.dp.toPx()), 1.dp.toPx(), alpha = alpha)
    }
    drawCircle(Accent, 4.5.dp.toPx(), Offset(gx + centre * perDeg, gy), alpha = alpha)
    val head = Offset(gx + yaw * perDeg, gy)
    drawPath(Path().apply { moveTo(head.x, head.y + 5.dp.toPx()); lineTo(head.x - 5.dp.toPx(), head.y + 13.dp.toPx()); lineTo(head.x + 5.dp.toPx(), head.y + 13.dp.toPx()); close() }, Ink, alpha = alpha)

    // The trackpad's bar, and a finger on Recentre.
    val barW = min(size.width * 0.62f, 260.dp.toPx())
    val barH = min(46.dp.toPx(), size.height * 0.15f)
    val bar = Rect(Offset(size.width / 2f - barW / 2f, size.height * 0.97f - barH), Size(barW, barH))
    val press = burst(lt, 4.05f, 0.5f)?.let { sin(PI.toFloat() * it) } ?: 0f
    val rc = padBar(bar, recentreLit = maxOf(press, span(lt, 3.5f, 4f) * (1f - span(lt, 5f, 5.5f)) * 0.5f), alpha = alpha)
    val approach = span(lt, 3.4f, 4.05f) * (1f - span(lt, 4.5f, 4.9f, ExitEasing))
    if (approach > 0.01f) finger(Offset(rc.x, rc.y + (1f - approach) * 16.dp.toPx()), press, alpha = alpha * approach, sizeDp = 7f)
    burst(lt, 4.2f, 0.5f)?.let { ripple(rc, it, alpha = alpha) }
}

// ---- 7. 3D and Steady mode ----

internal const val DEPTH_LOOP = 11f
internal const val DEPTH_ROW_AT = 5.6f

private fun DrawScope.depth(t: Float) {
    val lt = t % DEPTH_LOOP
    val alpha = loopAlpha(lt, DEPTH_LOOP) * span(t, 0f, 0.4f)
    val row = span(lt, DEPTH_ROW_AT, DEPTH_ROW_AT + 0.9f, InOutEasing)
    // 3D: the two eyes' pictures come together, then a small sway shows near windows moving more than far ones.
    val apart = (1f - span(lt, 0.3f, 1.5f, InOutEasing)) * 0.11f
    val sway = 0.13f * sin((lt - 1.5f).coerceAtLeast(0f) * 1.5f) * span(lt, 1.5f, 2.1f) * (1f - row)
    val near = SpaceWin(mix(-29f, -27f, row), mix(-0.05f, 0f, row), mix(0.4f, 0.7f, row), mix(0.27f, 0.46f, row), mix(1.0f, 1.6f, row))
    val mid = SpaceWin(mix(1f, 0f, row), 0f, mix(0.74f, 0.7f, row), mix(0.48f, 0.46f, row), 1.6f)
    val far = SpaceWin(mix(30f, 27f, row), mix(0.12f, 0f, row), mix(1.02f, 0.7f, row), mix(0.66f, 0.46f, row), mix(2.5f, 1.6f, row))
    val wins = listOf(far to WinKind.TEXT, mid to WinKind.MEDIA, near to WinKind.CHAT)
    val locked = span(lt, DEPTH_ROW_AT + 0.9f, DEPTH_ROW_AT + 1.3f)
    fun pass(shift: Float, a: Float) {
        val cam = cam(shiftX = shift, zoom = mix(0.8f, 0.62f, row))
        for ((win, kind) in wins) spaceWindow(cam, win, kind, focus = if (win === mid) mix(1f, 0f, row) + locked * 0.0f else locked * 0.0f, held = 0f, alpha = a)
    }
    if (apart > 0.004f) {
        pass(sway - apart, alpha * 0.55f)
        pass(sway + apart, alpha * 0.55f)
    } else {
        pass(sway, alpha)
    }
    if (locked > 0.01f) {
        // Steady: one brighter rim round the whole row, the wide screen holding still.
        val cam = cam(zoom = 0.62f)
        val a = near.at(cam, 0f, 0f)
        val b = far.at(cam, 1f, 1f)
        if (a != null && b != null) {
            val p = 9.dp.toPx()
            drawRoundRect(RimFocus, Offset(a.x - p, a.y - p), Size(b.x - a.x + 2 * p, b.y - a.y + 2 * p), CornerRadius(16.dp.toPx()), alpha = alpha * locked, style = Stroke(1.5.dp.toPx()))
        }
    }
}

// ---- 8. More ways to control ----

internal const val MORE_PART = 3.6f

/** The part showing now: 0 Spatial Mouse (when the phone can aim), 1 hands, 2 PC VR. */
internal fun morePart(t: Float, spatialMouse: Boolean): Int {
    val parts = if (spatialMouse) 3 else 2
    val i = ((t % (MORE_PART * parts)) / MORE_PART).toInt().coerceIn(0, parts - 1)
    return if (spatialMouse) i else i + 1
}

private fun DrawScope.more(t: Float, spatialMouse: Boolean) {
    val part = morePart(t, spatialMouse)
    val lt = t % MORE_PART
    val alpha = loopAlpha(lt, MORE_PART, 0.3f, 0.3f) * span(t, 0f, 0.4f)
    val cam = cam(cyShare = 0.36f, zoom = 0.84f)
    val win = SpaceWin(0f, 0f, 1.24f, 0.66f)
    val w = size.width
    val h = size.height
    when (part) {
        0 -> {
            // Spatial Mouse: the phone held like a remote; where its top edge points, the dot goes.
            val swing = sin(lt * 1.5f)
            spaceWindow(cam, win, WinKind.TEXT, focus = 1f, alpha = alpha)
            val dot = win.at(cam, 0.5f + 0.3f * swing, 0.45f - 0.12f * sin(lt * 0.9f)) ?: return
            val pw = min(w * 0.11f, 44.dp.toPx())
            val base = Offset(w / 2f, h * 0.99f)
            val angle = Math.toDegrees(kotlin.math.atan2((dot.x - base.x).toDouble(), (base.y - dot.y).toDouble())).toFloat()
            val top = Offset(base.x + (pw * 2.1f) * sin(Math.toRadians(angle.toDouble())).toFloat(), base.y - (pw * 2.1f) * kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat())
            drawLine(Accent, top, dot, 2.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 6.dp.toPx()), -lt * 30.dp.toPx()), alpha = alpha * 0.9f)
            rotate(angle, base) { phone(Rect(Offset(base.x - pw / 2f, base.y - pw * 2.1f), Size(pw, pw * 2.1f)), alpha = alpha) }
            cursor(dot, alpha = alpha)
        }
        1 -> {
            // Hands: a pinch picks the window up and carries it.
            val pinch = span(lt, 0.7f, 1.05f) * (1f - span(lt, 2.7f, 2.95f, ExitEasing))
            val carry = span(lt, 1.15f, 2.5f, InOutEasing)
            spaceWindow(cam, win.copy(thetaDeg = mix(-6f, 7f, carry), w = 1f, h = 0.56f), WinKind.MEDIA, focus = 1f, held = pinch, alpha = alpha)
            val hand = Offset(w * mix(0.42f, 0.58f, carry), h * 0.78f)
            val open = 17.dp.toPx() * (1f - pinch) + 5.5.dp.toPx()
            val thumb = Offset(hand.x - open, hand.y + 4.dp.toPx())
            val index = Offset(hand.x + open, hand.y - 4.dp.toPx())
            // Thumb and index from the palm, coming together.
            val palm = Offset(hand.x + 5.dp.toPx(), hand.y + 46.dp.toPx())
            for (tip in listOf(thumb, index)) {
                drawPath(Path().apply { moveTo(palm.x, palm.y); quadraticTo(tip.x + (tip.x - hand.x) * 0.6f, hand.y + 24.dp.toPx(), tip.x, tip.y) }, Color(0xFF8E96A8), alpha = alpha, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
            drawCircle(Color(0xFF8E96A8), 13.dp.toPx(), palm, alpha = alpha)
            finger(thumb, pinch, alpha = alpha, sizeDp = 5.5f)
            finger(index, pinch, alpha = alpha, sizeDp = 5.5f)
            burst(lt, 1.0f, 0.45f)?.let { ripple(hand, it, Color(0xFF4CD98A), alpha) }
        }
        else -> {
            // PC VR: the PC's picture streams to the glasses; inside it, a world instead of windows.
            val frame = SpaceWin(0f, 0f, 1.5f, 0.74f)
            spaceWindow(cam, frame, WinKind.BLANK, focus = 1f, alpha = alpha) { fw, c, a ->
                val outline = fw.outline(c) ?: return@spaceWindow
                clipPath(outline) {
                    // A floor of lines running to a horizon, sliding toward you.
                    val horizon = 0.46f
                    winLine(c, fw, 0f, 1f, horizon, Accent.copy(alpha = 0.7f), a, 1.5f)
                    for (i in -7..7) {
                        val top = fw.at(c, 0.5f + i * 0.035f, horizon) ?: continue
                        val bottom = fw.at(c, 0.5f + i * 0.22f, 1.02f) ?: continue
                        drawLine(Accent.copy(alpha = 0.4f), top, bottom, 1.2.dp.toPx(), alpha = a)
                    }
                    for (i in 0 until 6) {
                        val k = ((i + (lt * 0.5f) % 1f) / 6f)
                        val v = horizon + (1.02f - horizon) * k * k
                        winLine(c, fw, 0f, 1f, v, Accent.copy(alpha = 0.4f * k + 0.08f), a, 1.2f)
                    }
                    // Two controllers' marks.
                    for (u in floatArrayOf(0.34f, 0.66f)) fw.at(c, u + 0.02f * sin(lt * 2f + u * 9f), 0.7f)?.let { drawCircle(Ink, 5.dp.toPx(), it, alpha = a, style = Stroke(2.dp.toPx())) }
                }
            }
            // The PC under it, with the picture going up.
            val mw = min(w * 0.2f, 84.dp.toPx())
            val monitor = Rect(Offset(w / 2f - mw / 2f, h * 0.97f - mw * 0.72f), Size(mw, mw * 0.56f))
            drawRoundRect(GlassRaised, monitor.topLeft, monitor.size, CornerRadius(5.dp.toPx()), alpha = alpha)
            drawRoundRect(RimFocus, monitor.topLeft, monitor.size, CornerRadius(5.dp.toPx()), alpha = alpha, style = Stroke(1.5.dp.toPx()))
            drawLine(RimFocus, Offset(monitor.center.x - mw * 0.2f, monitor.bottom + mw * 0.12f), Offset(monitor.center.x + mw * 0.2f, monitor.bottom + mw * 0.12f), 2.dp.toPx(), StrokeCap.Round, alpha = alpha)
            for (i in 0 until 3) {
                val k = ((lt * 0.9f + i / 3f) % 1f)
                val y = monitor.top - 8.dp.toPx() - k * 26.dp.toPx()
                val c = 6.dp.toPx()
                drawPath(Path().apply { moveTo(monitor.center.x - c, y + c * 0.7f); lineTo(monitor.center.x, y); lineTo(monitor.center.x + c, y + c * 0.7f) }, Accent, alpha = alpha * sin(PI.toFloat() * k), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---- 9. You're set ----

private fun DrawScope.done(t: Float, complete: Boolean) {
    val cam = cam(yaw = 5f * sin(t * 0.45f), zoom = 0.72f)
    ARC.forEachIndexed { i, (theta, y, kind) ->
        val delay = 0.1f * ((i + 1) / 2)
        val a = span(t, delay, delay + 0.55f)
        spaceWindow(cam, SpaceWin(theta * 0.92f, y - (1f - a) * 0.05f, 0.86f, 0.54f), kind, focus = if (i == 0) 1f else 0f, alpha = a * if (i == 0) 1f else 0.8f)
    }
    if (complete) {
        // The tick is drawn only when everything needed really is set up.
        val centre = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.height * 0.16f, 34.dp.toPx())
        drawCircle(Page.copy(alpha = 0.82f), r + 9.dp.toPx(), centre, alpha = span(t, 0.5f, 0.8f))
        tickRing(centre, r, span(t, 0.6f, 1.1f, InOutEasing), span(t, 1.0f, 1.4f), widthDp = 3f)
    }
}
