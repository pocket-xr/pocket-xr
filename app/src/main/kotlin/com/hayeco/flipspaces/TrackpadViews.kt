package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.hayeco.flipspaces.core.pointer.CoverButton
import com.hayeco.flipspaces.core.pointer.CoverLayout
import com.hayeco.flipspaces.core.pointer.CoverLayoutMath
import com.hayeco.flipspaces.core.pointer.DpRect
import com.hayeco.flipspaces.core.pointer.WindowAction
import com.hayeco.flipspaces.core.pointer.PadDirection
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.StripSide
import com.hayeco.flipspaces.core.pointer.TrackpadSettings
import com.hayeco.flipspaces.core.spatial.ArrangeKind
import com.hayeco.flipspaces.core.spatial.FollowMode

/*
 * 0.5.17: the phone trackpad's screens, drawn with Android views. Sizes are in dp, laid out for
 * a thumb on the Flip's main screen and cover screen.
 * Nothing here decides a gesture: the pad forwards its touches to [TrackpadActivity], whose
 * TrackpadInterpreter (core) does.
 */

private const val WHITE = 0xFFFFFFFF.toInt()
private const val TEXT_B3 = 0xB3FFFFFF.toInt()
private const val TEXT_99 = 0x99FFFFFF.toInt()
private const val TEXT_80 = 0x80FFFFFF.toInt()
private const val DIVIDER_1A = 0x1AFFFFFF
private const val DIVIDER_33 = 0x33FFFFFF
private const val MASK = 0x80000000.toInt()

/** The Spatial Mouse page's hint (1.1.10, issue 35: after the one finger scroll's line while it is on). */
private const val LASER_TIP = "Lost the dot? Hold Home to bring it in front of you"

// 0.5.73 ("Light on glass", docs/VISUAL-DIRECTION.md): the page and the pad in the design tokens, as the glasses' Space.
private const val BACKGROUND = com.hayeco.flipspaces.core.Look.PAGE
private const val PAD_FILL = com.hayeco.flipspaces.core.Look.PAD
private const val DARK_TEXT = 0xFF222222.toInt()

internal fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
internal fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

internal fun Context.padText(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    includeFontPadding = false
    // 0.5.68: the bundled Inter; bold is its Medium ([com.hayeco.flipspaces.core.Look]: Regular and Medium only).
    typeface = if (bold) com.hayeco.flipspaces.ui.LookType.medium else com.hayeco.flipspaces.ui.LookType.regular
}

private fun Context.icon(res: Int, sizeDp: Float): ImageView = ImageView(this).apply {
    setImageResource(res)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

private fun Context.divider(insetDp: Float, color: Int = DIVIDER_1A): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
        marginStart = dp(insetDp)
        marginEnd = dp(insetDp)
    }
}

/** The mode icons, normal and disabled. */
internal fun modeIcon(mode: PadMode, enabled: Boolean = true): Int = when (mode) {
    PadMode.SPATIAL_MOUSE -> if (enabled) R.drawable.ic_air_mouse else R.drawable.ic_air_mouse_disabled
    PadMode.VERTICAL -> if (enabled) R.drawable.ic_touchpad_portrait else R.drawable.ic_touchpad_portrait_disabled
    PadMode.HORIZONTAL -> if (enabled) R.drawable.ic_touchpad_landscape else R.drawable.ic_touchpad_landscape_disabled
}

/**
 * The touch area and its art: a near-black rounded rectangle with a grid of white dots at 30% and
 * a centre dot at 60%, and the Vertical Trackpad's scroll strip on one side. In portrait the art is
 * inset [insetPx] each side but the whole width takes touches, so a finger at the very edge still
 * counts; in landscape the art is the touch area.
 */
@SuppressLint("ViewConstructor")
internal class PadArtView(context: Context, private val onTouch: (MotionEvent, Float, Float) -> Unit) : View(context) {
    var landscape = false
    var insetPx = 0f
    var strip: StripSide? = StripSide.RIGHT

    /** 0.5.17 cover screen: the cover pad's own art, a square dot grid (the landscape art would stretch). */
    var cover = false

    /** 0.5.21: the thin scroll strip's side, drawn as a faint groove down the pad ([ScrollStrip]); null for none. */
    var thin: StripSide? = null
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0B.toInt() }
    private val grooveLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAD_FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val stripArt = context.getDrawable(R.drawable.ic_scroll_bar)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touches(event)
        onTouch(event, width.toFloat(), height.toFloat())
        return true
    }

    /*
     * 0.5.73 (Mike: the trackpad is one of the four things he dislikes; "Light on glass"): the pad answers the
     * finger. A soft glow of light sits under each finger while it touches and fades over [TOUCH_FADE_MS] after it lifts;
     * a quick tap sends a ring out ([RIPPLE_MS]). The pad's dots, rim and light are drawn once into a bitmap ([art]) and
     * blitted, so a touch's redraws cost a copy and a few circles, not the whole dot grid.
     */
    private class Touch(var x: Float, var y: Float, val downMs: Long, var upMs: Long = 0L)

    private val live = HashMap<Int, Touch>()
    private val fading = ArrayList<Touch>()
    private val ripples = ArrayList<Touch>()
    private val glowRadius = context.dpf(46f)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            0f, 0f, glowRadius,
            intArrayOf(0x405A93FF, 0x1A5A93FF, 0x005A93FF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }
    private val glowMatrix = android.graphics.Matrix()
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.5f)
    }

    private fun touches(e: MotionEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                live[e.getPointerId(i)] = Touch(e.getX(i), e.getY(i), now)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) live[e.getPointerId(i)]?.let { it.x = e.getX(i); it.y = e.getY(i) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> live.remove(e.getPointerId(e.actionIndex))?.let { t ->
                t.upMs = now
                fading += t
                if (now - t.downMs < TAP_MS) ripples += Touch(t.x, t.y, now)
            }
            MotionEvent.ACTION_CANCEL -> {
                live.values.forEach { it.upMs = now; fading += it }
                live.clear()
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawTouches(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        fading.removeAll { now - it.upMs > TOUCH_FADE_MS }
        ripples.removeAll { now - it.downMs > RIPPLE_MS }
        fun glow(t: Touch, a: Float) {
            glowMatrix.setTranslate(t.x, t.y)
            glowPaint.shader.setLocalMatrix(glowMatrix)
            glowPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            canvas.drawCircle(t.x, t.y, glowRadius, glowPaint)
        }
        for (t in live.values) glow(t, 1f)
        for (t in fading) glow(t, 1f - (now - t.upMs).toFloat() / TOUCH_FADE_MS)
        for (r in ripples) {
            val k = (now - r.downMs).toFloat() / RIPPLE_MS
            val e = 1f - (1f - k) * (1f - k) * (1f - k)
            ripplePaint.color = com.hayeco.flipspaces.core.Look.alpha(com.hayeco.flipspaces.core.Look.ACCENT, 0.8f * (1f - k))
            canvas.drawCircle(r.x, r.y, context.dpf(10f) + context.dpf(34f) * e, ripplePaint)
        }
        if (fading.isNotEmpty() || ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    private var art: android.graphics.Bitmap? = null
    private var artKey = ""

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val key = "$width/$height/$insetPx/$landscape/$cover/$strip/$thin"
        if (art == null || key != artKey) {
            art?.recycle()
            art = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { drawArt(Canvas(it)) }
            artKey = key
        }
        art?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        drawTouches(canvas)
    }

    /** 0.5.73: the pad's glass: its fill, a faint light falling from the top, and the tokens' hairline rim. */
    private fun padGlass(canvas: Canvas, l: Float, r: Float, ah: Float, radius: Float) {
        canvas.drawRoundRect(l, 0f, r, ah, radius, radius, fill)
        light.shader = LinearGradient(0f, 0f, 0f, ah * 0.5f, 0x0FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l, 0f, r, ah, radius, radius, light)
        val h = rim.strokeWidth / 2f
        canvas.drawRoundRect(l + h, h, r - h, ah - h, radius, radius, rim)
    }

    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = com.hayeco.flipspaces.core.Look.RIM
    }

    private fun drawArt(canvas: Canvas) {
        val l = insetPx
        val r = width - insetPx
        val aw = r - l
        val ah = height.toFloat()
        if (aw <= 0f || ah <= 0f) return
        if (cover) {
            // The cover pad: #161616 with its corners rounded like the layout's (14 dp), dots every
            // 18 dp at 30% and the centre one at 60%, so the thumb can see the middle.
            val radius = context.dpf(CoverLayoutMath.PAD_RADIUS)
            padGlass(canvas, l, r, ah, radius)
            val step = context.dpf(18f)
            val dr = context.dpf(1.2f)
            val cols = ((aw - 2 * step) / step).toInt()
            val rows = ((ah - 2 * step) / step).toInt()
            val x0 = l + (aw - cols * step) / 2f
            val y0 = (ah - rows * step) / 2f
            dot.alpha = 77
            for (i in 0..cols) for (j in 0..rows) canvas.drawCircle(x0 + i * step, y0 + j * step, dr, dot)
            dot.alpha = 153
            canvas.drawCircle(l + aw / 2f, ah / 2f, dr * 1.6f, dot)
            drawGroove(canvas)
            return
        }
        if (!landscape) {
            // ic_portrait_touchpad: 960 x 1725, fitXY.
            val radius = 60f / 960f * aw
            padGlass(canvas, l, r, ah, radius)
            val sx = aw / 960f
            val sy = ah / 1725f
            val dr = 4f / 960f * aw
            dot.alpha = 77
            for (row in PORTRAIT_ROWS) for (col in 0..12) {
                val x = 96 + 64 * col
                if (x == 480 && row == 857) continue
                canvas.drawCircle(l + x * sx, row * sy, dr, dot)
            }
            dot.alpha = 153
            canvas.drawCircle(l + 480f * sx, 861f * sy, dr, dot)
            drawGroove(canvas)
            val side = strip ?: return
            val art = stripArt ?: return
            val w = context.dp(21f)
            val h = minOf(context.dp(387f), (ah * 0.9f).toInt())
            val top = ((ah - h) / 2f).toInt()
            val left = if (side == StripSide.RIGHT) (r - context.dpf(6f) - w).toInt() else (l + context.dpf(6f)).toInt()
            art.setBounds(left, top, left + w, top + h)
            art.draw(canvas)
        } else {
            // ic_landscape_touchpad: 2280 x 1020, fitXY, the dots hidden under the four controls.
            val radius = 60f / 2280f * aw
            padGlass(canvas, l, r, ah, radius)
            val sx = aw / 2280f
            val sy = ah / 1020f
            val dr = 4f * sx
            dot.alpha = 77
            var y = 62
            while (y <= 958) {
                var x = 84
                while (x <= 2196) {
                    if (!underControl(x, y)) canvas.drawCircle(l + x * sx, y * sy, dr, dot)
                    x += 64
                }
                y += 64
            }
            drawGroove(canvas)
        }
    }

    /**
     * 0.5.21: the thin scroll strip's groove: a dark rounded channel [ScrollStrip.GROOVE_DP] wide down
     * the strip, with a faint light edge on its inner side, so it reads as cut into the pad.
     */
    private fun drawGroove(canvas: Canvas) {
        val side = thin ?: return
        val d = resources.displayMetrics.density
        val inset = if (!cover && !landscape) com.hayeco.flipspaces.core.pointer.ScrollStrip.MAIN_PORTRAIT_INSET_DP else 0f
        val g = com.hayeco.flipspaces.core.pointer.ScrollStrip.layout(width / d, height / d, side, inset).groove
        val r = g.width * d / 2f
        canvas.drawRoundRect(g.left * d, g.top * d, g.right * d, g.bottom * d, r, r, groove)
        val lx = if (side == StripSide.RIGHT) g.left * d else g.right * d - d
        canvas.drawRect(lx, g.top * d + r, lx + d, g.bottom * d - r, grooveLight)
    }

    private fun underControl(x: Int, y: Int): Boolean =
        (x in 18..188 && y in 18..188) || (x in 18..248 && y in 784..1002) || (x in 1996..2262 && y in 801..1002) || (x in 1782..2262 && y in 18..188)

    companion object {
        /** 0.5.73: a finger's glow fading after it lifts, a touch short enough to ripple, and the ripple's time, ms. */
        const val TOUCH_FADE_MS = 220L
        const val TAP_MS = 220L
        const val RIPPLE_MS = 360L

        /** Rows at y = 157 ... 1561 step 64, with one 60 step at 669 to 729. */
        val PORTRAIT_ROWS: List<Int> = (0..8).map { 157 + 64 * it } + (0..13).map { 729 + 64 * it }
    }
}

/**
 * 0.5.37 (Mike: preset app windows in the Menu, "very visually well designed"): one preset's row. At the left a small dark
 * room: a soft arc at eye level and each of the preset's windows where it sits around the head (left to right by its
 * direction, up and down by its pitch, sized by its angles, nearer windows brighter). Then the preset's name and its
 * apps, and a delete mark at the right. A tap on the row opens it; a tap on the mark deletes it.
 */
@SuppressLint("ViewConstructor")
internal class PresetRow(
    context: Context,
    private val preset: com.hayeco.flipspaces.core.spatial.WindowPreset,
    private val onOpen: () -> Unit,
    private val onDelete: () -> Unit,
    /** 0.5.47: the pencil left of the delete mark. */
    private val onRename: () -> Unit = {},
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = com.hayeco.flipspaces.ui.LookType.medium; color = 0xFFFFFFFF.toInt() }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private var pressed0 = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(84f))
    }

    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1f)
        val h = height.toFloat()
        if (pressed0) {
            fill.color = 0x14FFFFFF
            canvas.drawRect(0f, 0f, width.toFloat(), h, fill)
        }
        // The room: 120 x 60 dp, a dark rounded box with a faint horizon arc.
        val room = android.graphics.RectF(16 * d, 12 * d, 136 * d, h - 12 * d)
        fill.shader = android.graphics.LinearGradient(0f, room.top, 0f, room.bottom, 0xFF1E2433.toInt(), 0xFF10131B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(room, 12 * d, 12 * d, fill)
        fill.shader = null
        line.color = 0x22FFFFFF
        line.strokeWidth = 1.5f * d
        canvas.drawArc(android.graphics.RectF(room.left - 30 * d, room.centerY() - 4 * d, room.right + 30 * d, room.bottom + 40 * d), 200f, 140f, false, line)
        // Each window: x from its direction (-100 to 100 deg across the room), y from its pitch, its size from its angles.
        val panels = preset.layout.panels
        val yaws = panels.map { it.yawDeg }
        val mid = if (yaws.isEmpty()) 0f else (yaws.max() + yaws.min()) / 2f
        val sx = room.width() / 200f
        val sy = room.height() / 90f
        for (p in panels.sortedByDescending { it.distanceM }) {
            val cx = room.centerX() + com.hayeco.flipspaces.core.spatial.wrapDegrees(p.yawDeg - mid) * sx
            val cy = room.centerY() - p.pitchDeg * sy
            val w = (p.widthDeg * sx).coerceAtLeast(6 * d)
            val ht = (p.heightDeg * sy).coerceAtLeast(5 * d)
            val near = (1f - ((p.distanceM - 0.5f) / 4f).coerceIn(0f, 0.6f))
            fill.color = android.graphics.Color.argb((255 * near).toInt(), 0x5A, 0x93, 0xFF)
            val r = android.graphics.RectF(cx - w / 2f, cy - ht / 2f, cx + w / 2f, cy + ht / 2f)
            canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
            line.color = 0x66FFFFFF
            line.strokeWidth = 1f * d
            canvas.drawRoundRect(r, 3 * d, 3 * d, line)
        }
        // The name, the apps, and the delete mark.
        val tx = room.right + 16 * d
        val right = width - 104 * d
        name.textSize = 16 * d
        canvas.drawText(ellipsize(preset.name, name, right - tx), tx, h / 2f - 4 * d, name)
        sub.textSize = 12.5f * d
        canvas.drawText(ellipsize("${panels.size} windows · ${preset.apps}", sub, right - tx), tx, h / 2f + 16 * d, sub)
        line.color = 0x88FFFFFF.toInt()
        line.strokeWidth = 2f * d
        line.strokeCap = Paint.Cap.ROUND
        val xc = width - 30 * d
        val k = 6 * d
        canvas.drawLine(xc - k, h / 2f - k, xc + k, h / 2f + k, line)
        canvas.drawLine(xc + k, h / 2f - k, xc - k, h / 2f + k, line)
        // 0.5.47: Rename, a pencil.
        val px = width - 78 * d
        val py = h / 2f
        canvas.drawLine(px - 7 * d, py + 7 * d, px + 5 * d, py - 5 * d, line)
        canvas.drawLine(px + 5 * d, py - 5 * d, px + 8 * d, py - 2 * d, line)
        canvas.drawLine(px + 8 * d, py - 2 * d, px - 4 * d, py + 10 * d, line)
        canvas.drawLine(px - 7 * d, py + 7 * d, px - 8 * d, py + 11 * d, line)
        canvas.drawLine(px - 8 * d, py + 11 * d, px - 4 * d, py + 10 * d, line)
    }

    private fun ellipsize(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pressed0 = true; invalidate() }
            android.view.MotionEvent.ACTION_CANCEL -> { pressed0 = false; invalidate() }
            android.view.MotionEvent.ACTION_UP -> {
                pressed0 = false
                invalidate()
                when {
                    event.x > width - context.dpf(56f) -> onDelete()
                    event.x > width - context.dpf(104f) -> onRename()
                    else -> onOpen()
                }
            }
        }
        return true
    }
}

/**
 * Cursor Speed: a 0..100 slider, 40 tall with 20 padding: a 190 x 10 track of white at 5% with
 * three 2 wide grey ticks at 0, 50% and 100%, and a 20 x 25 rounded thumb with a grey gradient
 * (white while pressed). The value is sent on the lift, so dragging never floods the Space.
 */
@SuppressLint("ViewConstructor")
internal class SpeedSlider(context: Context, private val onLift: (Int) -> Unit) : View(context) {
    var progress = 50
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }
    private var pressed0 = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0DFFFFFF }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E5E5E.toInt() }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad get() = context.dpf(20f)

    override fun onDraw(canvas: Canvas) {
        val l = pad
        val r = width - pad
        val cy = height / 2f
        val th = context.dpf(10f)
        canvas.drawRoundRect(l, cy - th / 2f, r, cy + th / 2f, th / 2f, th / 2f, track)
        val tw = context.dpf(2f)
        for (f in floatArrayOf(0f, 0.5f, 1f)) {
            val x = l + (r - l) * f
            canvas.drawRect(x - tw / 2f, cy - th / 2f, x + tw / 2f, cy + th / 2f, tick)
        }
        val x = l + (r - l) * progress / 100f
        val w = context.dpf(20f)
        val h = context.dpf(25f)
        val top = cy - h / 2f
        thumb.shader = if (pressed0) {
            LinearGradient(0f, top, 0f, top + h, WHITE, 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, top, 0f, top + h, 0xFFA6A6A6.toInt(), 0xFF828282.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(x - w / 2f, top, x + w / 2f, top + h), context.dpf(4f), context.dpf(4f), thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val span = (width - 2 * pad).coerceAtLeast(1f)
        fun at() = (((event.x - pad) / span) * 100f).toInt().coerceIn(0, 100)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed0 = true
                progress = at()
            }
            MotionEvent.ACTION_MOVE -> progress = at()
            MotionEvent.ACTION_UP -> {
                pressed0 = false
                progress = at()
                onLift(progress)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed0 = false
                invalidate()
                onLift(progress)
            }
        }
        return true
    }
}

/**
 * The usage instructions' cover picture: a
 * still sketch of the gesture instead: a pad with the fingers and the way they go.
 */
internal class IntroCover(context: Context, private val art: com.hayeco.flipspaces.core.pointer.IntroArt) : View(context) {
    // 0.5.78 (Mike: "update the trackpad usage design and color ... flip spaces is dark themed but that usage page is white"):
    // the tokens' glass pad with its rim on the page's dark, white fingers with a soft glow, the accent's arrows.
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.hayeco.flipspaces.core.Look.GLASS_RAISED or 0xFF000000.toInt() }
    private val padRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = com.hayeco.flipspaces.core.Look.RIM_FOCUS
    }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.hayeco.flipspaces.core.Look.INK }
    private val fingerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.hayeco.flipspaces.core.Look.alpha(com.hayeco.flipspaces.core.Look.ACCENT, 0.28f) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.hayeco.flipspaces.core.Look.ACCENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.hayeco.flipspaces.core.Look.INK
        textSize = context.dpf(13f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(com.hayeco.flipspaces.core.Look.PAD)
        val cx = width / 2f
        val cy = height / 2f
        arrow.strokeWidth = context.dpf(3f)
        val r = context.dpf(9f)
        val len = context.dpf(46f)
        // 1.1.9: the pages that are not a gesture on the pad draw their own picture.
        when (art) {
            com.hayeco.flipspaces.core.pointer.IntroArt.GLASSES_MENU -> return glassesMenu(canvas, cx, cy)
            com.hayeco.flipspaces.core.pointer.IntroArt.AIM_PHONE -> return aim(canvas, cx, cy, handheld = false)
            com.hayeco.flipspaces.core.pointer.IntroArt.AIM_HANDHELD -> return aim(canvas, cx, cy, handheld = true)
            com.hayeco.flipspaces.core.pointer.IntroArt.ADDON -> return addon(canvas, cx, cy)
            else -> Unit
        }
        val w = context.dpf(120f)
        val h = context.dpf(190f)
        canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, context.dpf(14f), context.dpf(14f), padPaint)
        canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, context.dpf(14f), context.dpf(14f), padRim)
        when (art) {
            com.hayeco.flipspaces.core.pointer.IntroArt.POINT -> {
                canvas.drawCircle(cx - len / 2f, cy, r * 1.8f, fingerGlow)
                canvas.drawCircle(cx - len / 2f, cy, r, finger)
                line(canvas, cx - len / 2f + r * 1.6f, cy, cx + len / 2f + r, cy)
            }
            com.hayeco.flipspaces.core.pointer.IntroArt.SCROLL -> {
                val dx = context.dpf(14f)
                canvas.drawCircle(cx - dx, cy + len / 3f, r * 1.8f, fingerGlow)
                canvas.drawCircle(cx - dx, cy + len / 3f, r, finger)
                canvas.drawCircle(cx + dx, cy + len / 3f, r * 1.8f, fingerGlow)
                canvas.drawCircle(cx + dx, cy + len / 3f, r, finger)
                line(canvas, cx - dx, cy + len / 3f - r * 1.6f, cx - dx, cy - len)
                line(canvas, cx + dx, cy + len / 3f - r * 1.6f, cx + dx, cy - len)
            }
            else -> {
                val dx = context.dpf(22f)
                for (k in -1..1) {
                    canvas.drawCircle(cx + k * dx, cy, r * 1.8f, fingerGlow)
                    canvas.drawCircle(cx + k * dx, cy, r, finger)
                }
                line(canvas, cx, cy - r * 1.6f, cx, cy - len - r)
                line(canvas, cx, cy + r * 1.6f, cx, cy + len + r)
            }
        }
    }

    /**
     * 1.1.9 (issue 31): the glasses' own menu, Follow picked and Stabilizer off, over a pair of glasses. A sketch, not
     * their real menu.
     */
    private fun glassesMenu(canvas: Canvas, cx: Float, cy: Float) {
        val d = context::dpf
        // The menu card, two rows.
        val mw = d(180f)
        val rowH = d(34f)
        val top = cy - d(70f)
        canvas.drawRoundRect(cx - mw / 2f, top, cx + mw / 2f, top + 2 * rowH, d(12f), d(12f), padPaint)
        canvas.drawRoundRect(cx - mw / 2f, top, cx + mw / 2f, top + 2 * rowH, d(12f), d(12f), padRim)
        val textX = cx - mw / 2f + d(14f)
        val base = (rowH - label.descent() - label.ascent()) / 2f
        canvas.drawText("Follow mode", textX, top + base, label)
        // The pick: an accent tick at the row's end.
        arrow.strokeWidth = d(2.5f)
        val tx = cx + mw / 2f - d(26f)
        val ty = top + rowH / 2f
        canvas.drawLine(tx, ty, tx + d(5f), ty + d(5f), arrow)
        canvas.drawLine(tx + d(5f), ty + d(5f), tx + d(14f), ty - d(6f), arrow)
        canvas.drawText("Stabilizer", textX, top + rowH + base, label)
        // An off switch: a dim track with its knob at the left.
        val sw = d(30f)
        val sh = d(16f)
        val sx = cx + mw / 2f - d(14f) - sw
        val sy = top + rowH + (rowH - sh) / 2f
        canvas.drawRoundRect(sx, sy, sx + sw, sy + sh, sh / 2f, sh / 2f, padRim)
        canvas.drawCircle(sx + sh / 2f, sy + sh / 2f, sh / 2f - d(3f), finger)
        // The glasses: two lenses, the bridge and the arms going back.
        val lw = d(62f)
        val lh = d(36f)
        val gap = d(16f)
        val gy = top + 2 * rowH + d(34f)
        val rim = Paint(padRim).apply { strokeWidth = d(3f); color = com.hayeco.flipspaces.core.Look.INK }
        canvas.drawRoundRect(cx - gap / 2f - lw, gy, cx - gap / 2f, gy + lh, d(12f), d(12f), rim)
        canvas.drawRoundRect(cx + gap / 2f, gy, cx + gap / 2f + lw, gy + lh, d(12f), d(12f), rim)
        canvas.drawLine(cx - gap / 2f, gy + d(10f), cx + gap / 2f, gy + d(10f), rim)
        canvas.drawLine(cx - gap / 2f - lw, gy + d(8f), cx - gap / 2f - lw - d(14f), gy + d(2f), rim)
        canvas.drawLine(cx + gap / 2f + lw, gy + d(8f), cx + gap / 2f + lw + d(14f), gy + d(2f), rim)
    }

    /**
     * 1.1.9 (issue 33): Spatial Mouse held the device's way: a phone upright, a landscape handheld sideways like a game
     * controller. The dot goes where the top edge points; the arrows show the tilt up and down.
     */
    private fun aim(canvas: Canvas, cx: Float, cy: Float, handheld: Boolean) {
        val d = context::dpf
        val w = if (handheld) d(170f) else d(70f)
        val h = if (handheld) d(76f) else d(130f)
        val top = cy - h / 2f + d(36f)
        canvas.drawRoundRect(cx - w / 2f, top, cx + w / 2f, top + h, d(14f), d(14f), padPaint)
        canvas.drawRoundRect(cx - w / 2f, top, cx + w / 2f, top + h, d(14f), d(14f), padRim)
        if (handheld) {
            // The screen in the middle, a pad on the left, buttons on the right.
            val sw = d(84f)
            canvas.drawRoundRect(cx - sw / 2f, top + d(10f), cx + sw / 2f, top + h - d(10f), d(6f), d(6f), padRim)
            val lx = cx - w / 2f + d(22f)
            val my = top + h / 2f
            val arm = d(8f)
            val dpad = Paint(finger).apply { strokeWidth = d(5f); strokeCap = Paint.Cap.ROUND }
            canvas.drawLine(lx - arm, my, lx + arm, my, dpad)
            canvas.drawLine(lx, my - arm, lx, my + arm, dpad)
            val rx = cx + w / 2f - d(22f)
            for ((ox, oy) in listOf(0f to -8f, 0f to 8f, -8f to 0f, 8f to 0f)) canvas.drawCircle(rx + d(ox), my + d(oy), d(3.5f), finger)
        }
        // The laser from the top edge to the dot.
        val dotY = top - d(52f)
        val laser = Paint(arrow).apply { strokeWidth = d(2f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(d(5f), d(5f)), 0f) }
        canvas.drawLine(cx, top - d(4f), cx, dotY + d(9f), laser)
        canvas.drawCircle(cx, dotY, d(16f), fingerGlow)
        canvas.drawCircle(cx, dotY, d(7f), finger)
        // Tilt up and down: a double arrow beside the top edge.
        arrow.strokeWidth = d(3f)
        val ax = cx + w / 2f + d(20f)
        line(canvas, ax, top + d(14f), ax, top - d(22f))
        line(canvas, ax, top - d(8f), ax, top + d(28f))
    }

    /** 1.2.1: the add-on's page: a phone notification going up to a pair of glasses. A sketch, not the real page. */
    private fun addon(canvas: Canvas, cx: Float, cy: Float) {
        val d = context::dpf
        // The notification: a card with a dot for the app's icon and two lines of text.
        val nw = d(170f)
        val nh = d(44f)
        val top = cy + d(14f)
        canvas.drawRoundRect(cx - nw / 2f, top, cx + nw / 2f, top + nh, d(12f), d(12f), padPaint)
        canvas.drawRoundRect(cx - nw / 2f, top, cx + nw / 2f, top + nh, d(12f), d(12f), padRim)
        val ix = cx - nw / 2f + d(22f)
        canvas.drawCircle(ix, top + nh / 2f, d(9f), fingerGlow)
        canvas.drawCircle(ix, top + nh / 2f, d(6f), finger)
        val bar = Paint(finger).apply { strokeWidth = d(4f); strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(ix + d(18f), top + d(16f), cx + nw / 2f - d(20f), top + d(16f), bar)
        bar.alpha = 120
        canvas.drawLine(ix + d(18f), top + d(28f), cx + d(30f), top + d(28f), bar)
        // Up to the glasses.
        arrow.strokeWidth = d(3f)
        line(canvas, cx, top - d(6f), cx, top - d(30f))
        val lw = d(62f)
        val lh = d(36f)
        val gap = d(16f)
        val gy = top - d(40f) - lh
        val rim = Paint(padRim).apply { strokeWidth = d(3f); color = com.hayeco.flipspaces.core.Look.INK }
        canvas.drawRoundRect(cx - gap / 2f - lw, gy, cx - gap / 2f, gy + lh, d(12f), d(12f), rim)
        canvas.drawRoundRect(cx + gap / 2f, gy, cx + gap / 2f + lw, gy + lh, d(12f), d(12f), rim)
        canvas.drawLine(cx - gap / 2f, gy + d(10f), cx + gap / 2f, gy + d(10f), rim)
        canvas.drawLine(cx - gap / 2f - lw, gy + d(8f), cx - gap / 2f - lw - d(14f), gy + d(2f), rim)
        canvas.drawLine(cx + gap / 2f + lw, gy + d(8f), cx + gap / 2f + lw + d(14f), gy + d(2f), rim)
    }

    /** A line with an arrow head at its end. */
    private fun line(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawLine(x0, y0, x1, y1, arrow)
        val a = kotlin.math.atan2(y1 - y0, x1 - x0)
        val head = context.dpf(9f)
        for (s in floatArrayOf(-0.5f, 0.5f)) {
            canvas.drawLine(x1, y1, x1 - head * kotlin.math.cos(a + s).toFloat(), y1 - head * kotlin.math.sin(a + s).toFloat(), arrow)
        }
    }
}

/** What the screen asks the trackpad to do; the activity implements it. */
interface PadHost {
    /** 1.2.3 (issue 54): Air glasses are plugged in, so the Menu offers the display mode switch. */
    fun airDisplayOffered(): Boolean

    /** 1.2.3 (issue 54): the "Air display mode (untested)" switch, off by default. */
    fun airDisplayOn(): Boolean

    fun airDisplayChosen(on: Boolean)

    /** 1.2.3 (issue 54): the mode the glasses last reported, or what is happening ("reading", an error). */
    fun airDisplayLine(): String

    /** 1.2.3 (issue 54): what a tap on the mode row does. */
    fun airDisplayAction(): String

    /** 1.2.3 (issue 54): switch the Air between 2D and 3D side by side. */
    fun airDisplaySwitch()

    /** 1.2.3 (issue 58): the focused window's label and whether its app shows Side by side 3D, or null with none focused. */
    fun focusedSideBySide(): Pair<String, Boolean>?

    /** 1.2.3 (issue 58): Side by side 3D on or off for the focused window's app. */
    fun sideBySideChosen(on: Boolean)

    /** 1.2.3 (issue 58): 2D while moving for side by side windows (off by default). */
    fun sideBySideFlatOn(): Boolean

    fun sideBySideFlatChosen(on: Boolean)

    /** 0.5.101: the Menu's settings back to Pocket XR's defaults (the pad type, presets and windows stay). */
    fun resetSettings()

    /** 1.1.9: the Menu's "How to use the trackpad" pages, for this device ([com.hayeco.flipspaces.core.pointer.IntroPages.trackpad]). */
    fun introPages(): List<com.hayeco.flipspaces.core.pointer.IntroPage>

    /**
     * 1.1.9: the glasses setup page for the glasses plugged in (else the last ones used), or null when they have none.
     * 1.1.16 (issue 12): the setup check ([GlassesCheckCard]) for any glasses known, opened by [openGlassesCheck].
     */
    fun glassesCheckAvailable(): Boolean
    fun openGlassesCheck()
    fun padTouch(event: MotionEvent, width: Float, height: Float)
    fun backClicked()
    fun homeDown()
    fun homeUp()
    fun modeChosen(mode: PadMode)
    fun modeAvailable(mode: PadMode): Boolean
    fun gestureClicked()

    /** 0 hidden (no hand tracking), 1 off, 2 on, 3 loading. */
    fun gestureState(): Int
    fun speedChanged(speed: Int)

    /** 0.5.83: the Menu's scroll speed sliders, 0..100. */
    fun scrollSpeedChanged(speed: Int)
    fun stripSpeedChanged(speed: Int)

    /** 1.1.10 (issue 35): the Menu's "One-finger scroll on Spatial Mouse". */
    fun oneFingerScrollChosen(on: Boolean)
    fun stripChosen(side: StripSide)
    fun directionChosen(direction: PadDirection)
    fun followChosen(mode: FollowMode)
    fun currentFollow(): FollowMode

    /** Steady mode ([com.hayeco.flipspaces.core.spatial.SteadyMode]): its key shows for glasses that have it. */
    fun steadyOffered(): Boolean
    fun steadyOn(): Boolean
    fun arrange(kind: ArrangeKind)

    /** 0.5.32: the windows the Space has open (0 without a Space). */
    fun windowCount(): Int
    fun returnHome()

    /** 0.5.87: the app's settings home. */
    fun openSettings()

    /** 0.5.99: whether the Space is recording, and start ([on]) or stop it. */
    fun recordingGlasses(): Boolean
    fun recordGlasses(on: Boolean)
    fun unlockTouch(event: MotionEvent)

    /** 0.5.17 cover screen: a cover button went down ([down]) or up. */
    fun coverButton(button: CoverButton, down: Boolean)

    /** The window rail's side. */
    fun coverControlsChosen(side: StripSide)

    /** Move or Resize while it holds a window, else null (the button is drawn lit). */
    fun windowHold(): WindowAction?

    /** Unlock the phone (a keyguard dismiss, its PIN on the cover where it is secure). */
    fun unlockPhone()

    /** 1.2.3 (issue 59): Game keys are on (the open phone shows them). */
    fun gameKeysOn(): Boolean

    /** 1.2.3 (issue 59): the Menu's Game keys row, or the cluster's Done. */
    fun gameKeysChosen(on: Boolean)

    /** 1.2.3 (issue 59): the Game controller's mode (Off, Gamepad, Touch). */
    fun controllerMode(): com.hayeco.flipspaces.core.pointer.GameControllerMode

    fun controllerModeChosen(mode: com.hayeco.flipspaces.core.pointer.GameControllerMode)

    /** 1.2.3 (issue 59): a Game controller control moved; the screen's [PadScreen.controllerState] says to what. */
    fun controllerChanged()

    /** 1.2.3 (issue 59): Touch mode is setting spots (a held control takes the dot's spot). */
    fun touchSpotSetting(): Boolean

    fun touchSpotSettingChosen(on: Boolean)

    /** 1.2.3 (issue 59): forget the focused game's touch spots. */
    fun clearTouchSpots()

    /** 1.2.3 (issue 59): a game key went down or up. */
    fun gameKey(change: com.hayeco.flipspaces.core.pointer.GameKeyChange)

    /** 1.2.3 (issue 52): the lock overlay now, black, at the lowest brightness, the screen kept on. */
    fun pocketMode()

    /** 1.2.3 (issue 50): the Menu's Control panel row: opens it in the glasses as three fingers down do. */
    fun openControlPanel()

    /** 1.2.3 (issue 57): the focused window's label and its app's text size in percent, or null with no window focused. */
    fun focusedTextSize(): Pair<String, Int>?

    /** 1.2.3 (issue 57): the focused window's app's text size [steps] steps up or down; 0 puts it back to 100 %. */
    fun textSizeStep(steps: Int)

    /** 1.1.10: Exit Pocket XR ([AppExit]): the Space, the services and every screen closed. */
    fun exitApp()

    /** The cover's text field: its text went from [old] to [new]; Enter; the field opened or closed. */
    fun textChanged(old: String, new: String)
    fun textEnter()
    fun typing(on: Boolean)

    /** 0.5.19: a slide along a Curve key, [dyPx] screen pixels up since the last call. */
    fun curveSlide(dyPx: Float, phase: com.hayeco.flipspaces.core.pointer.Phase)

    /** 0.5.19: the menu's default curve for new windows, and the wraparound. */
    fun currentCurveDefault(): Float
    fun curveDefaultChosen(curve: Float)
    fun currentWrap(): Boolean
    fun wrapChosen(on: Boolean)

    /** 0.5.27: the edge check on the Menu, a test that sends nothing. */
    fun edgeCheckOn(): Boolean
    fun edgeCheckChosen(on: Boolean)

    /** 0.5.29: the edge check on both eyes or one at a time. */
    fun edgeEyes(): com.hayeco.flipspaces.core.spatial.EdgeEyes
    fun edgeEyesChosen(eyes: com.hayeco.flipspaces.core.spatial.EdgeEyes)

    /**
     * 0.5.29: the glasses' 3D screen distance (metres, and where it came from: the setting or
     * their own log) and the per-eye correction for it.
     */
    fun glassesDistance(): Pair<Float, String>
    fun glassesDistanceChanged(m: Float)
    fun distanceFixOn(): Boolean
    fun distanceFixChosen(on: Boolean)

    /** 0.5.30: the Space's debug info panel (the grey block of text below the view), off by default, on the hidden Developer card. */
    fun debugInfoOn(): Boolean
    fun debugInfoChosen(on: Boolean)

    /** 0.5.32: the head cursor's mode ([com.hayeco.flipspaces.core.spatial.HeadCursorMode]), auto hide by default. */
    fun headCursorMode(): com.hayeco.flipspaces.core.spatial.HeadCursorMode
    fun headCursorChosen(mode: com.hayeco.flipspaces.core.spatial.HeadCursorMode)

    /** 0.5.43: hand tracking on the NPU (Developer). 1.1.15: applied to the running hands at once. */
    fun npuHandsOn(): Boolean = true
    fun npuHandsChosen(on: Boolean) {}

    /** 0.5.52: window displays on a virtual device of their own, so they stay awake when the phone sleeps (test). */
    fun deviceWindowsOn(): Boolean = false
    fun deviceWindowsChosen(on: Boolean) {}

    /** 0.5.32: the still head's lock on the Developer card. */
    fun stillLockOn(): Boolean
    fun stillLockChosen(on: Boolean)
    fun drawSmoothingOn(): Boolean
    fun drawSmoothingChosen(on: Boolean)
    fun autoGlassesViewOn(): Boolean
    fun autoGlassesViewChosen(on: Boolean)
    fun factoryCalibrationOn(): Boolean
    fun factoryCalibrationChosen(on: Boolean)

    /** 0.5.32: the glasses warp without LSR, and its way back in after a drop (Developer). Applied at the next switch on. */
    fun warpNoLsrOn(): Boolean
    fun warpNoLsrChosen(on: Boolean)
    fun warpReenterOn(): Boolean
    fun warpReenterChosen(on: Boolean)

    /** 1.1.15: Lock screens ([com.hayeco.flipspaces.core.spatial.WindowLock]), from the Lock button and the Menu. */
    fun windowsLockedOn(): Boolean = false
    fun windowsLockedChosen(on: Boolean) {}

    /** 0.5.37: window snapping, a new instance for a running app, and the window presets. */
    fun snapWindowsOn(): Boolean
    fun snapWindowsChosen(on: Boolean)
    fun newInstanceOn(): Boolean
    fun newInstanceChosen(on: Boolean)
    fun presets(): List<com.hayeco.flipspaces.core.spatial.WindowPreset>
    fun savePreset()
    fun openPreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset)
    fun deletePreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset)

    /** 0.5.47: asks for a new name for [preset], then [done] (the Menu builds its list again). */
    fun renamePreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset, done: () -> Unit)

    /** 0.5.36: the trackpad cursor follows the head (on) or stays where it is in the room (off). */
    fun cursorFollowsHeadOn(): Boolean
    fun cursorFollowsHeadChosen(on: Boolean)

    /** 0.5.51: the trackpad keeps the phone's screen awake (and wakes it), or lets it sleep. */
    fun keepsScreenOnOn(): Boolean
    fun keepsScreenOnChosen(on: Boolean)

    /** 0.5.54: the glasses as SurfaceFlinger's pacesetter (a test, off by default); [frameClockUnavailable] says why it cannot run. */
    fun frameClockOn(): Boolean
    fun frameClockUnavailable(): String?
    fun frameClockChosen(on: Boolean)

    /** 0.5.58: the last force or give back's outcome, under the switch on the Developer card; null before any. */
    fun frameClockState(): String?

    /** 0.5.55: the trackpad drawn at the panel's own pixels where its window is compat scaled (Developer, on by default). */
    fun nativeBufferOn(): Boolean
    fun nativeBufferChosen(on: Boolean)

    /** 0.5.35: Spatial Mouse on the cover screen (the Flip closed), from the cover's Menu. */
    fun coverSpatialMouseOn(): Boolean
    fun coverSpatialMouseAvailable(): Boolean
    fun coverSpatialMouseChosen(on: Boolean)

    /** 0.5.32: the glasses' duty lowered on fast turns, and the 72 Hz 3D try (Developer). */
    fun turnDutyOn(): Boolean
    fun turnDutyChosen(on: Boolean)

    /** 1.1.0: the hover duty test (Developer, off at every start), and its line under the switch. */

    /** 0.5.28: the per-row pose in the glasses' 3D mode, for an A/B by eye. */
    fun rowPoseSbsOn(): Boolean
    fun rowPoseSbsChosen(on: Boolean)

    /** 0.5.27: the per-eye picture adjust, opened from the edge check: as saved for these glasses, and a change ([what], for the log). */
    fun pictureAdjust(): com.hayeco.flipspaces.core.spatial.PictureAdjust
    fun pictureAdjustChanged(next: com.hayeco.flipspaces.core.spatial.PictureAdjust, what: String)


}

/**
 * 0.5.17 cover screen: one cover button, a 24 dp icon over a 10 sp label that shrinks to 8 sp
 * rather than be cut, on a #1C1C1C rounded square (lit in the held colour while it holds a window).
 */
@SuppressLint("ViewConstructor")
internal class CoverKey(
    context: Context,
    val button: CoverButton,
    iconRes: Int,
    private val onPress: (Boolean) -> Unit,
    /**
     * 0.5.19: a slide along the key (the Curve key): the thumb's travel since the last call in pixels,
     * up positive, once it has moved [SLIDE_DP]; a slide is no press (its release sends no up).
     */
    private val onSlide: ((Float, com.hayeco.flipspaces.core.pointer.Phase) -> Unit)? = null,
) : LinearLayout(context) {
    private val label = TextView(context).apply {
        text = button.label
        setTextColor(TEXT_B3)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CoverLayoutMath.LABEL_SP)
        includeFontPadding = false
        maxLines = 1
        ellipsize = null
        gravity = Gravity.CENTER
        setAutoSizeTextTypeUniformWithConfiguration(8, CoverLayoutMath.LABEL_SP.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_cover_button)
        val p = context.dp(CoverLayoutMath.LABEL_PAD)
        setPadding(p, 0, p, 0)
        addView(ImageView(context).apply { setImageResource(iconRes) }, LayoutParams(context.dp(24f), context.dp(24f)))
        addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(14f)).apply { topMargin = context.dp(4f) })
        contentDescription = button.label
        isClickable = true
        // An accessibility click is a press and a release.
        setOnClickListener {
            onPress(true)
            onPress(false)
        }
    }

    var lit = false
        set(value) {
            if (field == value) return
            field = value
            setBackgroundResource(if (value) R.drawable.bg_cover_button_on else R.drawable.bg_cover_button)
            label.setTextColor(if (value) WHITE else TEXT_B3)
        }

    private var downY = 0f
    private var lastY = 0f
    private var sliding = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                downY = event.rawY
                lastY = event.rawY
                sliding = false
                onPress(true)
            }
            MotionEvent.ACTION_MOVE -> onSlide?.let { slide ->
                if (!sliding && kotlin.math.abs(event.rawY - downY) >= context.dpf(SLIDE_DP)) {
                    sliding = true
                    slide(0f, com.hayeco.flipspaces.core.pointer.Phase.BEGIN)
                }
                if (sliding && event.rawY != lastY) slide(lastY - event.rawY, com.hayeco.flipspaces.core.pointer.Phase.UPDATE)
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (sliding) onSlide?.invoke(0f, com.hayeco.flipspaces.core.pointer.Phase.END) else onPress(false)
                sliding = false
            }
        }
        return true
    }

    companion object {
        /** 0.5.19: the thumb's travel along the key that makes a press a slide (about 2 mm on the cover). */
        const val SLIDE_DP = 12f
    }
}

/**
 * 1.1.15 (tester John Davis: a "lock screens" option): the Lock screens quick toggle beside the window keys, in their
 * look ([CoverKey]): a 24 dp padlock over a 10 sp label, lit in the held colour while the screens are locked. At least
 * 48 dp each way, and read by a screen reader as one switch with its state.
 */
@SuppressLint("ViewConstructor")
internal class LockKey(context: Context, private val onToggle: () -> Unit) : LinearLayout(context) {
    private val icon = ImageView(context).apply { setImageResource(R.drawable.ic_cover_lock_open) }
    private val label = TextView(context).apply {
        text = com.hayeco.flipspaces.core.spatial.WindowLock.SHORT_LABEL
        setTextColor(TEXT_B3)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CoverLayoutMath.LABEL_SP)
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setAutoSizeTextTypeUniformWithConfiguration(8, CoverLayoutMath.LABEL_SP.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_cover_button)
        val p = context.dp(CoverLayoutMath.LABEL_PAD)
        setPadding(p, 0, p, 0)
        minimumWidth = context.dp(MIN_DP)
        minimumHeight = context.dp(MIN_DP)
        addView(icon, LayoutParams(context.dp(24f), context.dp(24f)))
        addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(14f)).apply { topMargin = context.dp(4f) })
        isClickable = true
        isFocusable = true
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                // Android 16's setChecked(int) is not on the older releases Pocket XR runs on.
                @Suppress("DEPRECATION")
                info.isChecked = locked
            }
        }
        contentDescription = com.hayeco.flipspaces.core.spatial.WindowLock.description(false)
        setOnClickListener { onToggle() }
    }

    var locked = false
        set(value) {
            if (field == value) return
            field = value
            setBackgroundResource(if (value) R.drawable.bg_cover_button_on else R.drawable.bg_cover_button)
            icon.setImageResource(if (value) R.drawable.ic_cover_lock else R.drawable.ic_cover_lock_open)
            label.text = if (value) com.hayeco.flipspaces.core.spatial.WindowLock.SHORT_LABEL_ON else com.hayeco.flipspaces.core.spatial.WindowLock.SHORT_LABEL
            label.setTextColor(if (value) WHITE else TEXT_B3)
            contentDescription = com.hayeco.flipspaces.core.spatial.WindowLock.description(value)
        }

    companion object {
        /** The smallest the toggle is drawn, each way (Android's touch target). */
        const val MIN_DP = 48f

        /** The Menu's Lock screens row's tag (the tests). */
        const val MENU_TAG = "menu:lock-screens"
    }
}

/** The cover buttons' icons. */
internal fun coverIcon(b: CoverButton): Int = when (b) {
    CoverButton.MENU -> R.drawable.ic_drawer_button
    CoverButton.KEYBOARD -> R.drawable.ic_cover_keyboard
    CoverButton.BACK -> R.drawable.ic_back
    CoverButton.HOME -> R.drawable.ic_home_new
    CoverButton.RECENTRE -> R.drawable.ic_cover_recentre
    CoverButton.STEADY -> R.drawable.ic_cover_steady
    CoverButton.MOVE -> R.drawable.ic_cover_move
    CoverButton.RESIZE -> R.drawable.ic_cover_resize
    CoverButton.FURTHER -> R.drawable.ic_cover_further
    CoverButton.CLOSER -> R.drawable.ic_cover_closer
    CoverButton.CURVE -> R.drawable.ic_cover_curve
}

/**
 * 0.5.17: the whole trackpad screen: the page (Vertical, Horizontal or Spatial Mouse, or the Flip's
 * cover screen layout), and over it the drawer, the mode popup, the usage instructions, a dialog,
 * the toast and the screen-lock overlay. Children are placed at fixed dp positions for the
 * screen's size each time it is measured.
 */
@SuppressLint("ViewConstructor")
internal class PadScreen(context: Context, private val host: PadHost) : ViewGroup(context) {
    private var settings = TrackpadSettings()
    private var cover = false

    /** 1.1.8 (issue 33): the phone is a landscape handheld, so Spatial Mouse's page is landscape too. */
    var landscapeDevice = false

    /**
     * The open phone's page is laid out landscape ([TrackpadSettings.landscapePage]), or the cover screen's. 1.1.9: Spatial
     * Mouse on a handheld as the screen is now, which it no longer locks.
     */
    private val landscapeLayout: Boolean
        get() = cover || TrackpadSettings.landscapePage(
            settings.mode, landscapeDevice, resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        )
    private val rects = HashMap<View, IntArray>()

    val pad = PadArtView(context) { e, w, h -> host.padTouch(e, w, h) }
    private val drawerButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_drawer_button)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = context.dp(13f)
        setPadding(p, p, p, p)
        setOnClickListener { openDrawer() }
        contentDescription = "Menu"
    }
    private val selectorIcon = context.icon(R.drawable.ic_touchpad_portrait, 20f)
    private val selectorLabel = context.padText("Vertical Trackpad", 16f, WHITE)
    private val selectorTriangle = context.icon(R.drawable.ic_down_triangle, 20f)
    private val selector = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = context.dp(10f)
        setPadding(p, p, p, p)
        addView(selectorIcon)
        addView(selectorLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(7f) })
        addView(selectorTriangle, LinearLayout.LayoutParams(context.dp(20f), context.dp(20f)).apply { marginStart = context.dp(3f) })
        setOnClickListener { if (System.currentTimeMillis() >= selectorBlockedUntil) togglePopup() }
    }
    private var selectorBlockedUntil = 0L
    private val hintIcon = context.icon(R.drawable.ic_gesture_hint, 13f)
    private val hint = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(hintIcon)
        addView(context.padText("Hand tracking", 12f, TEXT_80), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(5f) })
        visibility = View.GONE
    }

    // 0.5.36 (Mike: on the cover screen, Spatial Mouse at the top of the trackpad like when the Flip is open, not in the
    // Menu): a chip over the top of the cover pad; a tap switches between the cover trackpad and Spatial Mouse.
    private val coverMouseIcon = ImageView(context).apply { setImageResource(R.drawable.ic_air_mouse) }
    private val coverMouseLabel = context.padText("Spatial Mouse", 13f, WHITE)
    private val coverMouseChip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(context.dp(14f), 0, context.dp(14f), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = context.dpf(18f)
            setColor(0x33FFFFFF)
        }
        addView(coverMouseIcon, LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)))
        addView(coverMouseLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(6f) })
        visibility = View.GONE
        setOnClickListener {
            host.coverSpatialMouseChosen(!host.coverSpatialMouseOn())
            refreshCoverMouse()
        }
    }

    /** 0.5.36: the cover chip's look for the mode in use. */
    private fun refreshCoverMouse() {
        val on = host.coverSpatialMouseOn()
        coverMouseLabel.text = if (on) "Spatial Mouse: ON" else "Spatial Mouse: OFF"
        (coverMouseChip.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (on) com.hayeco.flipspaces.core.Look.ACCENT_DEEP else 0x33FFFFFF)
        coverMouseChip.visibility = if (cover && !textOpen && host.coverSpatialMouseAvailable()) View.VISIBLE else View.GONE
    }

    /**
     * 1.1.15 (Mike: updates were only found under Menu, Pocket XR Settings): "Update available: <version>" across the top of
     * the open phone's trackpad, with Update and Later ([showUpdate]). Not on the cover screen, which has no room.
     */
    private var updateVersion: String? = null
    private var onUpdate: (() -> Unit)? = null
    private var onUpdateLater: (() -> Unit)? = null
    private val updateText = context.padText("", 14f, WHITE, bold = true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val updateLater = context.padText("Later", 14f, WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(12f), 0, context.dp(12f), 0)
        setOnClickListener { onUpdateLater?.invoke() }
    }
    private val updateNow = context.padText("Update", 14f, DARK_TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_pad_button_light)
        setPadding(context.dp(14f), 0, context.dp(14f), 0)
        setOnClickListener { onUpdate?.invoke() }
    }
    private val updateBanner = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16f), context.dp(8f), context.dp(8f), context.dp(8f))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = context.dpf(16f)
            setColor(com.hayeco.flipspaces.core.Look.GLASS_RAISED or 0xFF000000.toInt())
            setStroke(context.dp(1f), com.hayeco.flipspaces.core.Look.ACCENT)
        }
        addView(updateText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(updateLater, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(updateNow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = context.dp(4f) })
        visibility = View.GONE
        isClickable = true
    }

    /** 1.1.15: the update banner for [version] (null hides it); also a row at the top of the Menu. */
    fun showUpdate(version: String?, onUpdate: () -> Unit, onLater: () -> Unit) {
        this.onUpdate = onUpdate
        onUpdateLater = onLater
        if (version == updateVersion) return
        updateVersion = version
        updateText.text = version?.let { com.hayeco.flipspaces.core.UpdateCheck.bannerLine(it) } ?: ""
        updateBanner.visibility = if (version != null && !cover) View.VISIBLE else View.GONE
        if (drawer.visibility == View.VISIBLE) buildDrawer()
        requestLayout()
    }

    /** 1.1.15: whether the banner is on screen (the tests, and the notification permission's ask). */
    val updateShown: Boolean get() = updateBanner.visibility == View.VISIBLE

    /** 1.1.15: the banner's line, for the tests. */
    val updateLine: CharSequence get() = updateText.text

    /** 1.1.15: the banner's buttons, as a tap would press them (the tests). */
    fun pressUpdate(later: Boolean) {
        if (later) updateLater.performClick() else updateNow.performClick()
    }

    // 0.5.32 (Mike: a way to recenter the Spatial Mouse's dot): a tip that stays on the page, above the Home it names,
    // rather than one shown once in the intro and forgotten.
    private val laserTip = context.padText(LASER_TIP, 12f, TEXT_80).apply { visibility = View.GONE }

    // The portrait Back and Home bar.
    private val barBackIcon = ImageView(context).apply { setImageResource(R.drawable.ic_back) }
    private val barHomeIcon = ImageView(context).apply { setImageResource(R.drawable.ic_home_new) }
    private val barBack = FrameLayout(context).apply { addView(barBackIcon, FrameLayout.LayoutParams(context.dp(30f), context.dp(30f), Gravity.CENTER)) }
    private val barHome = FrameLayout(context).apply { addView(barHomeIcon, FrameLayout.LayoutParams(context.dp(30f), context.dp(30f), Gravity.CENTER)) }

    /** 0.5.106 (Mike: the open phone's trackpad needs "more needed buttons like how the cover screen has"): Recentre in the bar. */
    private val barRecentreIcon = ImageView(context).apply { setImageResource(R.drawable.ic_cover_recentre) }
    private val barRecentre = FrameLayout(context).apply {
        addView(barRecentreIcon, FrameLayout.LayoutParams(context.dp(28f), context.dp(28f), Gravity.CENTER))
        contentDescription = "Recentre"
    }
    /** Steady mode in the portrait bar, beside Recentre, as in the launcher. Landscape has a key, the cover its own. */
    private val barSteadyIcon = ImageView(context).apply { setImageResource(R.drawable.ic_cover_steady) }
    private val barSteady = FrameLayout(context).apply {
        addView(barSteadyIcon, FrameLayout.LayoutParams(context.dp(28f), context.dp(28f), Gravity.CENTER))
        contentDescription = "Steady mode"
    }
    private val barSteadyDivider = View(context).apply { setBackgroundColor(DIVIDER_1A) }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundResource(R.drawable.black_gradient_rounded_rect)
        fun divider() = addView(View(context).apply { setBackgroundColor(DIVIDER_1A) }, LinearLayout.LayoutParams(context.dp(1f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = context.dp(22f)
            bottomMargin = context.dp(22f)
        })
        addView(barBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        divider()
        addView(barRecentre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(barSteadyDivider, LinearLayout.LayoutParams(context.dp(1f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = context.dp(22f)
            bottomMargin = context.dp(22f)
        })
        addView(barSteady, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        divider()
        addView(barHome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    // The landscape (and cover screen) Back and Home buttons, 70 x 54.
    private val sideBackIcon = ImageView(context).apply { setImageResource(R.drawable.ic_back) }
    private val sideHomeIcon = ImageView(context).apply { setImageResource(R.drawable.ic_home_new) }
    private val sideBack = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.black_gradient_rounded_rect)
        addView(sideBackIcon, FrameLayout.LayoutParams(context.dp(24f), context.dp(24f), Gravity.CENTER))
    }
    private val sideHome = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.black_gradient_rounded_rect)
        addView(sideHomeIcon, FrameLayout.LayoutParams(context.dp(24f), context.dp(24f), Gravity.CENTER))
    }

    // Overlays.
    private val mask = View(context).apply {
        setBackgroundColor(MASK)
        visibility = View.GONE
        isClickable = true
        setOnClickListener { if (popup.visibility == View.VISIBLE) closePopup() }
    }
    private val popup = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.ic_control_mode_bg)
        val p = context.dp(20f)
        setPadding(p, 0, p, 0)
        visibility = View.GONE
        isClickable = true
    }
    private val drawerScrim = View(context).apply {
        setBackgroundColor(MASK)
        visibility = View.GONE
        alpha = 0f
        isClickable = true
        setOnClickListener { closeDrawer() }
    }
    private val drawerBody = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(30f)
        setPadding(p, 0, p, p)
    }
    private val drawer = ScrollView(context).apply {
        setBackgroundColor(BACKGROUND)
        isVerticalScrollBarEnabled = false
        addView(drawerBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }
    private val dialog = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_pad_dialog)
        val p = context.dp(20f)
        setPadding(p, p, p, p)
        visibility = View.GONE
        isClickable = true
    }
    private val intro = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
    }
    private val toast = context.padText("", 16f, WHITE).apply {
        setBackgroundResource(R.drawable.bg_pad_toast)
        setPadding(context.dp(20f), context.dp(9f), context.dp(20f), context.dp(9f))
        minHeight = context.dp(40f)
        maxWidth = context.dp(200f)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val lockGradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00FFFFFF, 0x33FFFFFF))
    private val lockIcon = ImageView(context).apply { setImageResource(R.drawable.ic_unlock_icon) }
    private val lockText = context.padText("Hold to unlock", 13f, TEXT_80)
    private val lockGlow = View(context).apply { background = lockGradient }
    @SuppressLint("ClickableViewAccessibility")
    private val lockTarget = View(context).apply {
        setOnTouchListener { _, e ->
            host.unlockTouch(e)
            true
        }
    }
    private val lock = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
        addView(lockGlow)
        addView(lockIcon)
        addView(lockText)
        addView(lockTarget)
    }

    // 0.5.17 cover screen: the cover layout's buttons and its text field.
    private var coverLayout: CoverLayout? = null
    private val coverKeys: Map<CoverButton, CoverKey> = CoverButton.entries.associateWith { b ->
        CoverKey(context, b, coverIcon(b), { down -> host.coverButton(b, down) }, if (b == CoverButton.CURVE) host::curveSlide else null).apply { visibility = View.GONE }
    }

    /**
     * 0.5.19: the main screen's Curve key (the cover has its own on the rail): at the top right in
     * portrait, between Back and Home in landscape. Tap holds the window under the cursor to curve it
     * with a sideways swipe on the pad; a slide up and down the key curves it directly.
     */
    private val curveKey = CoverKey(context, CoverButton.CURVE, coverIcon(CoverButton.CURVE), { down -> host.coverButton(CoverButton.CURVE, down) }, host::curveSlide)

    /** 1.1.15: Lock screens' quick toggle: last in the window keys' row in portrait, left of Keyboard in landscape. */
    private val lockKey = LockKey(context) {
        host.windowsLockedChosen(!host.windowsLockedOn())
        refreshCover()
        if (drawerOpen) buildDrawer()
    }

    /**
     * 0.5.106 (Mike: "On the opened flip trackpad, there also needs to be a keyboard button so think about how to redesign the
     * trackpad to include more needed buttons like how the cover screen has"): the cover's keys on the open phone. Portrait:
     * Keyboard at the top right (where Curve was), and Move, Resize, Further, Closer and Curve in a row over the bar, which
     * is Back, Recentre, Home. Landscape: Keyboard and Recentre either side of Curve, between Back and Home.
     */
    private val mainKeys: Map<CoverButton, CoverKey> = listOf(
        CoverButton.KEYBOARD, CoverButton.RECENTRE, CoverButton.STEADY, CoverButton.MOVE, CoverButton.RESIZE, CoverButton.FURTHER, CoverButton.CLOSER,
    ).associateWith { b -> CoverKey(context, b, coverIcon(b), { down -> host.coverButton(b, down) }) }
    private val mainRow = listOf(CoverButton.MOVE, CoverButton.RESIZE, CoverButton.FURTHER, CoverButton.CLOSER)
    private var typedSoFar = ""
    private val textField = EditText(context).apply {
        setTextColor(WHITE)
        setHintTextColor(TEXT_80)
        // 0.5.82: held in the field and sent on Enter (TrackpadActivity.flushText), so the keyboard stays open while typing.
        hint = "Type, then Enter sends it to the window"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setSingleLine(true)
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(12f)
            setColor(com.hayeco.flipspaces.core.Look.GLASS_RAISED or 0xFF000000.toInt())
            setStroke(context.dp(1f), DIVIDER_33)
        }
        setPadding(context.dp(12f), 0, context.dp(12f), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        // No full screen extract editor on the small cover screen: the field stays in view.
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setOnEditorActionListener { _, _, _ ->
            host.textEnter()
            typedSoFar = ""
            setText("")
            true
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val now = s?.toString() ?: ""
                if (now == typedSoFar) return
                host.textChanged(typedSoFar, now)
                typedSoFar = now
            }
        })
    }
    private val textDone = context.padText("Done", 14f, DARK_TEXT).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_pad_button_light)
        setOnClickListener { showText(false) }
    }
    private val textBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(textField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(textDone, LinearLayout.LayoutParams(context.dp(72f), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = context.dp(8f) })
    }

    val textOpen: Boolean get() = textBar.visibility == View.VISIBLE

    /** 1.2.3 (issue 59): Game keys, a cluster for each thumb ([GameKeysView]), on the open phone while on. */
    private val gameLeft = GameKeysView(context, com.hayeco.flipspaces.core.pointer.GameKeyLayout.LEFT) { host.gameKey(it) }.apply { visibility = View.GONE }
    private val gameRight = GameKeysView(context, com.hayeco.flipspaces.core.pointer.GameKeyLayout.RIGHT) { host.gameKey(it) }.apply { visibility = View.GONE }

    /** Game keys are shown: the window keys and the bar make room for them. */
    private val gameKeysShown: Boolean get() = !cover && host.gameKeysOn()

    /** 1.2.3 (issue 59): the Game controller, in the same place as Game keys, while its mode is not Off. */
    private val padLeft = GameControllerView(context, com.hayeco.flipspaces.core.pointer.GameControllerLayout.LEFT) { host.controllerChanged() }.apply { visibility = View.GONE }
    private val padRight = GameControllerView(context, com.hayeco.flipspaces.core.pointer.GameControllerLayout.RIGHT) { host.controllerChanged() }.apply { visibility = View.GONE }
    private val controllerShown: Boolean get() = !cover && host.controllerMode() != com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF

    /** What the Game controller's two clusters hold now, as one controller. */
    fun controllerState(): com.hayeco.flipspaces.core.pointer.ControllerState = com.hayeco.flipspaces.core.pointer.GameControllerPresses.merge(padLeft.state(), padRight.state())

    init {
        setBackgroundColor(BACKGROUND)
        listOf(pad, drawerButton, selector, hint, laserTip, bar, sideBack, sideHome, curveKey, coverMouseChip, updateBanner).forEach { addView(it) }
        mainKeys.values.forEach { addView(it) }
        coverKeys.values.forEach { addView(it) }
        addView(lockKey)
        addView(gameLeft)
        addView(gameRight)
        addView(padLeft)
        addView(padRight)
        listOf(textBar, mask, popup, intro, dialog, drawerScrim, drawer, toast, lock).forEach { addView(it) }
        pressable(barBack, barBackIcon, R.drawable.ic_back, R.drawable.ic_back_pressed, R.drawable.left_black_gradient_bg_pressed, isHome = false)
        pressable(barHome, barHomeIcon, R.drawable.ic_home_new, R.drawable.ic_home_new_pressed, R.drawable.right_black_gradient_bg_pressed, isHome = true)
        pressable(sideBack, sideBackIcon, R.drawable.ic_back, R.drawable.ic_back_pressed, R.drawable.black_gradient_rounded_rect_pressed, isHome = false, restore = R.drawable.black_gradient_rounded_rect)
        pressable(sideHome, sideHomeIcon, R.drawable.ic_home_new, R.drawable.ic_home_new_pressed, R.drawable.black_gradient_rounded_rect_pressed, isHome = true, restore = R.drawable.black_gradient_rounded_rect)
        barSteady.setOnClickListener {
            host.coverButton(CoverButton.STEADY, true)
            host.coverButton(CoverButton.STEADY, false)
        }
        barRecentre.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    barRecentreIcon.alpha = 0.5f
                    host.coverButton(CoverButton.RECENTRE, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barRecentreIcon.alpha = 1f
                    if (e.actionMasked == MotionEvent.ACTION_UP && e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) host.coverButton(CoverButton.RECENTRE, false)
                }
            }
            true
        }
    }

    /** Back clicks on its release inside; Home sends its press and its release, so a long press can be timed. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View, icon: ImageView, normal: Int, pressed: Int, pressedBg: Int, isHome: Boolean, restore: Int? = null) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundResource(pressedBg)
                    icon.setImageResource(pressed)
                    if (isHome) host.homeDown()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (restore != null) v.setBackgroundResource(restore) else v.background = null
                    icon.setImageResource(normal)
                    if (isHome) {
                        host.homeUp()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) {
                        host.backClicked()
                    }
                }
            }
            true
        }
    }

    /**
     * The page for [s]; [onCover] for the Flip's cover screen layout, placed by [layout] (core's
     * [CoverLayoutMath], from the screen's cutouts and corners; the Flip7's when null).
     */
    fun configure(s: TrackpadSettings, onCover: Boolean, layout: CoverLayout? = null) {
        settings = s
        cover = onCover
        coverLayout = layout
        val landscape = landscapeLayout
        pad.landscape = landscape
        pad.cover = cover
        // 0.5.21: the thin scroll strip on every page and on the cover takes the place of the old
        // wide strip (and its art): opposite the cover's rail, on the Scroll strip side on the main screen.
        pad.strip = null
        pad.thin = if (cover) com.hayeco.flipspaces.core.pointer.ScrollStrip.coverSide(s.coverControls) else com.hayeco.flipspaces.core.pointer.ScrollStrip.mainSide(s.stripSide)
        selectorIcon.setImageResource(modeIcon(s.mode))
        selectorLabel.text = s.mode.label
        bar.visibility = if (landscape) View.GONE else View.VISIBLE
        laserTip.visibility = if (!cover && s.mode == PadMode.SPATIAL_MOUSE) View.VISIBLE else View.GONE
        // 1.1.10 (issue 35): the Spatial Mouse page's hint names the one finger scroll while it is on.
        laserTip.text = if (s.oneFingerScroll) "${com.hayeco.flipspaces.core.pointer.IntroPages.ONE_FINGER_SCROLL_LINE}. $LASER_TIP" else LASER_TIP
        // 0.5.17 cover screen: its own buttons take the place of the selector, the drawer button and the corner buttons.
        sideBack.visibility = if (landscape && !cover) View.VISIBLE else View.GONE
        sideHome.visibility = if (landscape && !cover) View.VISIBLE else View.GONE
        drawerButton.visibility = if (cover) View.GONE else View.VISIBLE
        selector.visibility = if (cover) View.GONE else View.VISIBLE
        curveKey.visibility = if (cover) View.GONE else View.VISIBLE
        // 1.1.15: the update banner on the open phone only.
        updateBanner.visibility = if (!cover && updateVersion != null) View.VISIBLE else View.GONE
        // 0.5.106: the open phone's keys; landscape has room for Keyboard and Recentre only.
        for ((b, k) in mainKeys) {
            val shown = !cover && if (b == CoverButton.STEADY) landscape && host.steadyOffered() else !landscape || b == CoverButton.KEYBOARD || b == CoverButton.RECENTRE
            k.visibility = if (shown) View.VISIBLE else View.GONE
        }
        val barStill = if (host.steadyOffered()) View.VISIBLE else View.GONE
        barSteady.visibility = barStill
        barSteadyDivider.visibility = barStill
        for ((b, k) in coverKeys) k.visibility = if (cover && !textOpen && coverShown(b)) View.VISIBLE else View.GONE
        // 1.1.15: Lock screens' toggle on the open phone; the cover reaches it from its Menu.
        lockKey.visibility = if (cover) View.GONE else View.VISIBLE
        // 1.2.3 (issue 59): Game keys take the bottom of the screen; Keyboard and the pad stay. Done on the right cluster,
        // or the Menu, gives the window keys back.
        val keys = gameKeysShown
        val controller = controllerShown && !keys
        val game = keys || controller
        gameLeft.visibility = if (keys) View.VISIBLE else View.GONE
        gameRight.visibility = if (keys) View.VISIBLE else View.GONE
        padLeft.visibility = if (controller) View.VISIBLE else View.GONE
        padRight.visibility = if (controller) View.VISIBLE else View.GONE
        if (!keys) {
            gameLeft.releaseAll()
            gameRight.releaseAll()
        }
        if (!controller) {
            padLeft.releaseAll()
            padRight.releaseAll()
        }
        if (game) {
            for (v in listOf(bar, sideBack, sideHome, curveKey, lockKey, laserTip)) v.visibility = View.GONE
            for ((b, k) in mainKeys) if (b != CoverButton.KEYBOARD) k.visibility = View.GONE
        }
        if (!cover && textOpen) showText(false)
        refreshCover()
        // 0.5.74 (Mike: the cover's Spatial Mouse pill "gets left over on the flipped open trackpad at the very top of the
        // screen"): its visibility was only set in the cover's layout pass, so opening the phone never hid it.
        refreshCoverMouse()
        refreshGesture()
        if (drawer.visibility == View.VISIBLE) buildDrawer()
        if (popup.visibility == View.VISIBLE) buildPopup()
        requestLayout()
        pad.invalidate()
    }

    private fun coverShown(b: CoverButton) = b != CoverButton.STEADY || host.steadyOffered()

    /** 0.5.17 cover screen: Move or Resize lit while it holds a window. 0.5.19: Curve too, on both screens. */
    fun refreshCover() {
        val hold = host.windowHold()
        lockKey.locked = host.windowsLockedOn()
        val stillOn = host.steadyOn()
        mainKeys[CoverButton.STEADY]?.lit = stillOn
        coverKeys[CoverButton.STEADY]?.lit = stillOn
        barSteady.setBackgroundResource(if (stillOn) R.drawable.bg_cover_button_on else 0)
        curveKey.lit = hold == WindowAction.CURVE
        mainKeys[CoverButton.MOVE]?.lit = hold == WindowAction.MOVE
        mainKeys[CoverButton.RESIZE]?.lit = hold == WindowAction.RESIZE
        if (!cover) return
        coverKeys[CoverButton.MOVE]?.lit = hold == WindowAction.MOVE
        coverKeys[CoverButton.RESIZE]?.lit = hold == WindowAction.RESIZE
        coverKeys[CoverButton.CURVE]?.lit = hold == WindowAction.CURVE
    }

    /**
     * 1.2.3 (issue 55: in the launcher's Search apps "I have to tap it myself to start typing"): the keyboard is asked for
     * once the trackpad's window has focus. Opening the field makes the window focusable ([PadHost.typing]), but it
     * gains focus a moment later, and Android drops a keyboard request from a window without focus. From the cover's
     * Keyboard button the window often had focus already, so it worked there; from the glasses' search it did not.
     */
    private var keyboardOnFocus = false

    /** 0.5.17 cover screen: the text field over the top of the cover, its keyboard below; the buttons hide meanwhile. */
    fun showText(show: Boolean) {
        if (show == textOpen) return
        textBar.visibility = if (show) View.VISIBLE else View.GONE
        for ((b, k) in coverKeys) k.visibility = if (cover && !show && coverShown(b)) View.VISIBLE else View.GONE
        host.typing(show)
        val ime = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (show) {
            typedSoFar = ""
            textField.setText("")
            textField.requestFocus()
            keyboardOnFocus = !textField.hasWindowFocus()
            if (!keyboardOnFocus) textField.post { ime?.showSoftInput(textField, 0) }
        } else {
            keyboardOnFocus = false
            ime?.hideSoftInputFromWindow(textField.windowToken, 0)
            textField.clearFocus()
        }
        requestLayout()
    }

    /** The trackpad's window gained focus: the keyboard the open text field asked for before it had focus. */
    fun windowFocused() {
        if (!keyboardOnFocus || !textOpen) return
        keyboardOnFocus = false
        textField.requestFocus()
        textField.post {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(textField, 0)
        }
    }

    /** The gesture hint shows only while gestures are on (state 2). */
    fun refreshGesture() {
        val on = host.gestureState() == 2 && !cover
        hint.visibility = if (on) View.VISIBLE else View.GONE
        if (popup.visibility == View.VISIBLE) updateGestureButton()
    }

    // ---- placement ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        place(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val rc = rects[c] ?: continue
            c.layout(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3])
        }
    }

    private fun put(v: View, x: Int, y: Int, w: Int, h: Int) {
        rects[v] = intArrayOf(x, y, w, h)
        v.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY))
    }

    private fun wrap(v: View, maxW: Int): Pair<Int, Int> {
        v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return v.measuredWidth to v.measuredHeight
    }

    private fun dp(v: Float) = context.dp(v)

    private fun place(w: Int, h: Int) {
        val landscape = landscapeLayout
        val (sw, sh) = wrap(selector, w)
        var selectorBottom: Int
        if (cover) {
            // 0.5.17 cover screen: core's layout for this screen (the pad clear of the cutouts and
            // the rounded corners, the buttons 48 dp and more for one thumb), in this view's pixels.
            // While typing: the text field at the top and the pad under it, above the keyboard.
            val l = coverLayout ?: CoverLayoutMath.flip7(settings.coverControls, host.steadyOffered())
            val sx = w / context.dpf(l.widthDp)
            val sy = h / context.dpf(l.heightDp)
            fun at(v: View, rc: DpRect) = put(
                v,
                Math.round(context.dpf(rc.left) * sx), Math.round(context.dpf(rc.top) * sy),
                Math.round(context.dpf(rc.width) * sx), Math.round(context.dpf(rc.height) * sy),
            )
            pad.insetPx = 0f
            if (textOpen) {
                val m = dp(CoverLayoutMath.MARGIN)
                put(textBar, m, m, w - 2 * m, dp(52f))
                val top = m + dp(52f) + dp(CoverLayoutMath.GAP)
                put(pad, m, top, w - 2 * m, (h - top - m).coerceAtLeast(dp(48f)))
            } else {
                at(pad, l.pad)
                for ((b, rc) in l.buttons) coverKeys[b]?.let { at(it, rc) }
                // 0.5.36: the Spatial Mouse chip, centred over the top of the pad.
                refreshCoverMouse()
                // 0.5.37 (Mike: the Spatial Mouse toggle not on the trackpad but in the empty area above it, centred): the chip
                // centred in the strip between the screen's top and the pad.
                if (coverMouseChip.visibility == View.VISIBLE) {
                    val py = Math.round(context.dpf(l.pad.top) * sy)
                    val chipH = minOf(dp(36f), (py - dp(4f)).coerceAtLeast(dp(28f)))
                    val (cw, _) = wrap(coverMouseChip, w)
                    put(coverMouseChip, (w - cw) / 2, ((py - chipH) / 2).coerceAtLeast(0), cw, chipH)
                }
            }
            selectorBottom = dp(64f)
        } else if (landscape) {
            // Landscape: the pad with 20 and 10 margins, the drawer button 12 / 9 inside it, the
            // selector at the top right 24 in, Back and Home 70 x 54 at the bottom corners 6 in.
            val pl = dp(20f)
            val pt = dp(10f)
            put(pad, pl, pt, w - 2 * pl, h - 2 * pt)
            pad.insetPx = 0f
            put(drawerButton, pl + dp(12f), pt + dp(9f), dp(50f), dp(50f))
            val cy = pt + dp(9f) + dp(25f)
            put(selector, w - pl - dp(24f) - sw, cy - sh / 2, sw, sh)
            selectorBottom = cy + sh / 2
            // 1.1.15: the update banner between the drawer button and the selector, over the top of the pad.
            if (updateBanner.visibility == View.VISIBLE) {
                val left = pl + dp(12f) + dp(50f) + dp(16f)
                val bw = minOf(dp(440f), w - pl - dp(24f) - sw - dp(16f) - left)
                put(updateBanner, left + ((w - pl - dp(24f) - sw - dp(16f) - left) - bw) / 2, cy - dp(26f), bw, dp(52f))
            }
            put(sideBack, pl + dp(6f), h - pt - dp(6f) - dp(54f), dp(70f), dp(54f))
            put(sideHome, w - pl - dp(6f) - dp(70f), h - pt - dp(6f) - dp(54f), dp(70f), dp(54f))
            // 0.5.19: Curve between Back and Home, the same size, so a thumb finds it from either. 0.5.106: Keyboard on its left
            // and Recentre on its right.
            val ky = h - pt - dp(6f) - dp(54f)
            val kx = (w - dp(70f)) / 2
            put(curveKey, kx, ky, dp(70f), dp(54f))
            mainKeys[CoverButton.KEYBOARD]?.let { put(it, kx - dp(8f) - dp(70f), ky, dp(70f), dp(54f)) }
            mainKeys[CoverButton.RECENTRE]?.let { put(it, kx + dp(70f) + dp(8f), ky, dp(70f), dp(54f)) }
            // Steady mode right of Recentre, across from Lock screens.
            mainKeys[CoverButton.STEADY]?.let { put(it, kx + 2 * (dp(8f) + dp(70f)), ky, dp(70f), dp(54f)) }
            // 1.1.15: Lock screens left of Keyboard.
            put(lockKey, kx - 2 * (dp(8f) + dp(70f)), ky, dp(70f), dp(54f))
            // 1.1.8 (issue 33): Spatial Mouse's recentre tip over the keys, on a landscape handheld.
            if (laserTip.visibility == View.VISIBLE) wrap(laserTip, w).let { (tw, th) -> put(laserTip, (w - tw) / 2, ky - dp(8f) - th, tw, th) }
        } else {
            // Portrait: the drawer button 7 / 48, the selector centred on it, the pad from 34 below
            // the selector to 10 above the bar, the 80 tall bar 20 from the bottom and the sides.
            // 1.1.15: the update banner at the very top; the drawer button, the selector and Keyboard move down under it.
            val down = if (updateBanner.visibility == View.VISIBLE) {
                put(updateBanner, dp(12f), dp(44f), w - dp(24f), dp(56f))
                dp(56f) + dp(8f)
            } else {
                0
            }
            put(drawerButton, dp(7f), dp(48f) + down, dp(50f), dp(50f))
            // 0.5.19: Curve at the top right, across from the drawer button. 0.5.106: Keyboard there; Curve joins the row of
            // window keys over the bar.
            mainKeys[CoverButton.KEYBOARD]?.let { put(it, w - dp(7f) - dp(56f), dp(45f) + down, dp(56f), dp(56f)) }
            val cy = dp(48f) + dp(25f) + down
            put(selector, (w - sw) / 2, cy - sh / 2, sw, sh)
            selectorBottom = cy + sh / 2
            val barTop = h - dp(20f) - dp(80f)
            put(bar, dp(20f), barTop, w - dp(40f), dp(80f))
            // 0.5.106: Move, Resize, Further, Closer and Curve in a row over the bar, the bar's width, 8 dp apart.
            val rowH = dp(60f)
            val rowTop = barTop - dp(10f) - rowH
            // 1.1.15: and Lock screens at the row's end; the gaps narrow on a small screen so every key keeps 48 dp.
            val keys = mainRow.mapNotNull { mainKeys[it] } + curveKey + lockKey
            val kGap = if (w - dp(40f) - dp(8f) * (keys.size - 1) >= dp(LockKey.MIN_DP) * keys.size) dp(8f) else dp(4f)
            val kw = (w - dp(40f) - kGap * (keys.size - 1)) / keys.size
            keys.forEachIndexed { i, k -> put(k, dp(20f) + i * (kw + kGap), rowTop, kw, rowH) }
            val padTop = selectorBottom + dp(34f)
            // 0.5.32: the Spatial Mouse's recentre tip sits between the pad and the bar.
            val tipRoom = if (laserTip.visibility == View.VISIBLE) wrap(laserTip, w).let { (tw, th) -> put(laserTip, (w - tw) / 2, rowTop - dp(8f) - th, tw, th); th + dp(14f) } else 0
            put(pad, 0, padTop, w, (rowTop - dp(10f) - tipRoom - padTop).coerceAtLeast(dp(100f)))
            pad.insetPx = dp(20f).toFloat()
        }
        val (hw, hh) = wrap(hint, w)
        // 0.5.82 (Mike: the launcher's "Search apps bar doesnt work"): on the open phone too the text field shows at the top,
        // over the selector, while typing into the Space (the cover's has its own place above).
        if (!cover && textOpen) put(textBar, dp(20f), dp(12f), w - dp(40f), dp(52f))
        if (landscape) put(hint, w - dp(23f) - dp(20f) - hw, selectorBottom + dp(35f), hw, hh) else put(hint, (w - hw) / 2, selectorBottom + dp(35f), hw, hh)
        put(mask, 0, 0, w, h)
        put(drawerScrim, 0, 0, w, h)
        val dw = minOf(w, if (landscape) dp(316f) else dp(290f))
        put(drawer, 0, 0, dw, h)
        if (drawer.visibility != View.VISIBLE) drawer.translationX = -dw.toFloat()
        // Popup: 230 wide, 240 tall with the gesture button, 156 without; 30 below the selector,
        // centred in portrait and right aligned in landscape.
        val ph = if (host.gestureState() > 0) dp(240f) else dp(156f)
        val pw = dp(230f)
        val selectorRect = rects[selector] ?: intArrayOf(0, 0, 0, 0)
        val popX = if (landscape) (selectorRect[0] + selectorRect[2] - pw).coerceAtLeast(0) else (w - pw) / 2
        put(popup, popX, (selectorBottom + dp(30f)).coerceAtMost((h - ph).coerceAtLeast(0)), pw, ph)
        // Dialog: 30 side margins, as tall as its content, centred.
        val dlgW = w - 2 * dp(30f)
        dialog.measure(MeasureSpec.makeMeasureSpec(dlgW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        put(dialog, dp(30f), (h - dialog.measuredHeight) / 2, dlgW, dialog.measuredHeight)
        // Usage instructions: 300 x 486, centred (smaller on a short screen).
        val iw = minOf(dp(300f), w)
        val ih = minOf(dp(486f), h)
        put(intro, (w - iw) / 2, (h - ih) / 2, iw, ih)
        // Toast: 120 above the bottom, centred.
        val (tw, th) = wrap(toast, dp(200f))
        put(toast, (w - tw) / 2, h - dp(120f) - th, tw, th)
        if (gameKeysShown || controllerShown) placeGameKeys(w, h, landscape)
        placeLock(landscape)
        put(lock, 0, 0, w, h)
    }

    /** 1.2.3 (issue 59): lets go of every held game key and controller control (the trackpad paused). */
    fun releaseGameKeys() {
        gameLeft.releaseAll()
        gameRight.releaseAll()
        padLeft.releaseAll()
        padRight.releaseAll()
    }

    /**
     * 1.2.3 (issue 59): portrait: the two clusters side by side across the bottom, the pad between the selector and them.
     * Landscape: one in each lower corner, under the thumbs of a phone held sideways, over the pad's edges. Game keys and
     * the Game controller share the place; only one of them shows.
     */
    private fun placeGameKeys(w: Int, h: Int, landscape: Boolean) {
        val m = dp(12f)
        if (landscape) {
            val cw = minOf(dp(300f), (w * 0.34f).toInt())
            val ch = minOf(dp(230f), (h * 0.62f).toInt())
            put(gameLeft, m, h - m - ch, cw, ch)
            put(gameRight, w - m - cw, h - m - ch, cw, ch)
            put(padLeft, m, h - m - ch, cw, ch)
            put(padRight, w - m - cw, h - m - ch, cw, ch)
        } else {
            val ch = minOf(dp(240f), (h * 0.36f).toInt())
            val top = h - dp(20f) - ch
            val cw = (w - 2 * m - dp(8f)) / 2
            put(gameLeft, m, top, cw, ch)
            put(gameRight, m + cw + dp(8f), top, cw, ch)
            put(padLeft, m, top, cw, ch)
            put(padRight, m + cw + dp(8f), top, cw, ch)
            val padRect = rects[pad] ?: return
            put(pad, padRect[0], padRect[1], padRect[2], (top - dp(10f) - padRect[1]).coerceAtLeast(dp(100f)))
        }
    }

    private var lockPlacedLandscape: Boolean? = null

    /** The screen-lock overlay's parts, portrait or landscape figures; set only when they change, so measuring never asks for another layout. */
    private fun placeLock(landscape: Boolean) {
        if (lockPlacedLandscape == landscape) return
        lockPlacedLandscape = landscape
        fun lp(wDp: Float, hDp: Float, bottomDp: Float) = FrameLayout.LayoutParams(if (wDp < 0) ViewGroup.LayoutParams.MATCH_PARENT else dp(wDp), dp(hDp), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(bottomDp) }
        if (landscape) {
            lockGlow.layoutParams = lp(-1f, 303f, 0f)
            lockIcon.layoutParams = lp(140f, 140f, 120f)
            lockText.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(76f) }
            lockTarget.layoutParams = lp(200f, 220f, 57f)
        } else {
            lockGlow.layoutParams = lp(-1f, 407f, 0f)
            lockIcon.layoutParams = lp(140f, 140f, 159f)
            lockText.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(125f) }
            lockTarget.layoutParams = lp(180f, 200f, 109f)
        }
    }

    // ---- the mode popup ----

    private var gestureButton: LinearLayout? = null
    private var gestureIcon: ImageView? = null
    private var gestureLabel: TextView? = null
    private var gestureLoading: TextView? = null

    private fun togglePopup() = if (popup.visibility == View.VISIBLE) closePopup() else openPopup()

    fun openPopup() {
        buildPopup()
        mask.visibility = View.VISIBLE
        popup.visibility = View.VISIBLE
        selectorTriangle.rotation = 180f
        selectorBlockedUntil = System.currentTimeMillis() + 300L
        requestLayout()
    }

    fun closePopup() {
        popup.visibility = View.GONE
        if (dialog.visibility != View.VISIBLE && intro.visibility != View.VISIBLE) mask.visibility = View.GONE
        selectorTriangle.rotation = 0f
    }

    private fun buildPopup() {
        popup.removeAllViews()
        PadMode.entries.forEachIndexed { i, m ->
            if (i > 0) popup.addView(context.divider(0f, DIVIDER_33))
            val enabled = host.modeAvailable(m)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(context.icon(modeIcon(m, enabled), 20f))
                addView(context.padText(m.label, 14f, if (enabled) WHITE else 0xFF666666.toInt()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10f) })
                if (m == settings.mode) addView(context.icon(R.drawable.ic_scroll_checked, 20f))
                isEnabled = enabled
                if (enabled) pressLight(this, 0f)
                setOnClickListener {
                    if (!enabled) return@setOnClickListener
                    tick(this)
                    closePopup()
                    host.modeChosen(m)
                }
            }
            popup.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(46f)).apply { if (i == 0) topMargin = context.dp(8f) })
        }
        gestureButton = null
        if (host.gestureState() > 0) {
            val icon = context.icon(R.drawable.ic_gesture_off, 30f)
            val label = context.padText("Hand tracking: Off", 14f, 0xE6FFFFFF.toInt())
            val loading = context.padText("• • •", 14f, 0xFF888888.toInt())
            val b = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(icon)
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8f) })
                addView(loading)
                setOnClickListener { host.gestureClicked() }
            }
            popup.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(50f)).apply { topMargin = context.dp(22f) })
            gestureButton = b
            gestureIcon = icon
            gestureLabel = label
            gestureLoading = loading
            shownGestureState = -1
            updateGestureButton()
        }
    }

    private var shownGestureState = -1

    /** States 1 off, 2 on, 3 loading (three dots, icon and text hidden); redrawn only when it changes. */
    private fun updateGestureButton() {
        val b = gestureButton ?: return
        val state = host.gestureState()
        if (state == shownGestureState) return
        shownGestureState = state
        when (state) {
            2 -> {
                b.setBackgroundResource(R.drawable.bg_gesture_on)
                gestureIcon?.setImageResource(R.drawable.ic_gesture_on)
                gestureLabel?.text = "Hand tracking: On"
                gestureLabel?.setTextColor(DARK_TEXT)
                gestureIcon?.visibility = View.VISIBLE
                gestureLabel?.visibility = View.VISIBLE
                gestureLoading?.visibility = View.GONE
            }
            3 -> {
                b.setBackgroundResource(R.drawable.bg_gesture_off)
                gestureIcon?.visibility = View.GONE
                gestureLabel?.visibility = View.GONE
                gestureLoading?.visibility = View.VISIBLE
            }
            else -> {
                b.setBackgroundResource(R.drawable.bg_gesture_off)
                gestureIcon?.setImageResource(R.drawable.ic_gesture_off)
                gestureLabel?.text = "Hand tracking: Off"
                gestureLabel?.setTextColor(0xE6FFFFFF.toInt())
                gestureIcon?.visibility = View.VISIBLE
                gestureLabel?.visibility = View.VISIBLE
                gestureLoading?.visibility = View.GONE
            }
        }
    }

    // ---- the drawer ----

    private var speedSlider: SpeedSlider? = null

    /** 0.5.37: the Menu's speed slider follows a change made on the glasses' Control Center. */
    fun refreshSpeed(speed: Int) {
        speedSlider?.let {
            it.progress = speed
            it.invalidate()
        }
    }

    /**
     * 0.5.105 (Mike: from Settings' Trackpad row the Menu "should auto scroll down to the trackpad section since its confusing
     * when its not showing that"): [atTrackpad] scrolls it to the Trackpad heading as it slides in.
     */
    fun openDrawer(atTrackpad: Boolean = false) {
        closePopup()
        buildDrawer()
        drawer.visibility = View.VISIBLE
        drawerScrim.visibility = View.VISIBLE
        requestLayout()
        drawer.post {
            drawer.translationX = -drawer.width.toFloat()
            drawer.animate().translationX(0f).setDuration(DRAWER_MS).start()
            drawerScrim.animate().alpha(1f).setDuration(DRAWER_MS).start()
            trackpadHeading?.takeIf { atTrackpad }?.let { h ->
                drawer.post { drawer.smoothScrollTo(0, (h.top - context.dp(24f)).coerceAtLeast(0)) }
            }
        }
    }

    /** 0.5.105: the Menu's Trackpad heading, for [openDrawer]'s scroll. */
    private var trackpadHeading: View? = null

    fun closeDrawer() {
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(DRAWER_MS).withEndAction {
            drawer.visibility = View.GONE
            drawerScrim.visibility = View.GONE
        }.start()
        drawerScrim.animate().alpha(0f).setDuration(DRAWER_MS).start()
    }

    val drawerOpen: Boolean get() = drawer.visibility == View.VISIBLE

    /** 0.5.58: the open Menu built again (a result that came in after its tap, such as the frame clock's give back). */
    fun refreshDrawer() {
        if (drawerOpen) buildDrawer()
    }

    private fun buildDrawer() {
        val c = context
        drawerBody.removeAllViews()
        val landscape = landscapeLayout
        // The wordmark: the app's own name at the drawer's top (64 x 14).
        // 1.1.6 (issue 26: "there's no Developer settings"): the Developer card has its own switch at the Menu's end; the
        // wordmark's hidden long press (0.5.30) is gone.
        drawerBody.addView(c.padText("POCKET XR", 11f, WHITE, bold = true).apply {
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, c.dp(14f)).apply { topMargin = c.dp(61f) })
        // 1.1.15 (Mike: the Ko-fi link at the very top of the Menu): one slim pill, then the update row when there is one.
        // Never in the Play build.
        val top = com.hayeco.flipspaces.core.Support.menuTop(BuildConfig.PLAY_BUILD, updateVersion)
        val koFi = com.hayeco.flipspaces.core.Support.MenuTop.KO_FI in top
        if (koFi) drawerBody.addView(supportPill(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(48f)).apply { topMargin = c.dp(20f) })
        // Unreleased (Mike: sideload users into the Play closed test): the testers card, over the update row. Never in the
        // Play build.
        val testers = com.hayeco.flipspaces.core.PlayTesters.card(BuildConfig.PLAY_BUILD, padPrefs.playTesters, System.currentTimeMillis())
        val testersShown = testers != com.hayeco.flipspaces.core.PlayTesters.Card.HIDDEN
        if (testersShown) drawerBody.addView(playTestersCard(testers), cardParams(if (koFi) 12f else 25f))
        // The function card: Capture, Record and Scan are left out (Pocket XR has none of them).
        val functions = card()
        // 1.1.15: a newer Pocket XR, first in the Menu's card until Later.
        updateVersion?.takeIf { com.hayeco.flipspaces.core.Support.MenuTop.UPDATE in top }?.let { v ->
            functions.addView(row(R.drawable.ic_settings_sliders, com.hayeco.flipspaces.core.UpdateCheck.bannerLine(v), subtitle = "Tap to download it") {
                closeDrawer()
                onUpdate?.invoke()
            }.apply { tag = MENU_UPDATE })
            functions.addView(c.divider(20f))
        }
        // 1.2.3 (issue 50: three finger gestures "don't work" on phones that keep them for screenshots): the Control panel
        // without a swipe. Your apps are Home, and Back is its own button.
        functions.addView(row(R.drawable.ic_control_panel, com.hayeco.flipspaces.core.pointer.IntroPages.CONTROL_PANEL_ROW) {
            closeDrawer()
            host.openControlPanel()
        })
        functions.addView(c.divider(20f))
        functions.addView(row(R.drawable.ic_back_home, "Go to the phone's home screen") { host.returnHome() })
        // 0.5.87: the app's settings home (MainActivity), which the app's icon skips for the trackpad (0.5.37). 0.5.103
        // (Mike: "put Pocket XR Settings back where it was, i dont like it as its own tile"): back in this card.
        functions.addView(c.divider(20f))
        functions.addView(row(R.drawable.ic_settings_sliders, "Pocket XR Settings") { host.openSettings() })
        // 1.2.3 (issue 57): the focused window's text size, remembered for its app.
        host.focusedTextSize()?.let { (label, percent) ->
            functions.addView(c.divider(20f))
            functions.addView(textSizeRow(label, percent))
        }
        // 1.1.9 (issue 31): the glasses' own settings the Space needs. 1.1.16 (issue 12): with the head turn check, for
        // any glasses known (plugged in or last used), at any time.
        if (host.glassesCheckAvailable()) {
            functions.addView(c.divider(20f))
            functions.addView(row(R.drawable.ic_usage_intro, GLASSES_CHECK_ROW, subtitle = "The settings your glasses need, and a 10 second check") {
                closeDrawer()
                host.openGlassesCheck()
            }.apply { tag = MENU_GLASSES_CHECK })
        }
        // 1.2.3 (issue 58): Side by side 3D for the focused window's app.
        host.focusedSideBySide()?.let { (label, on) ->
            functions.addView(c.divider(20f))
            functions.addView(row(R.drawable.ic_cover_view, "Side by side 3D in $label: ${if (on) "ON" else "OFF"}", tick = on, subtitle = "For a video split into two halves. Each eye sees its half.") {
                closeDrawer()
                host.sideBySideChosen(!on)
            })
            // 1.2.3 (issue 58): 2D while moving, offered while the window shows Side by side 3D; off by default.
            if (on) {
                val flat = host.sideBySideFlatOn()
                functions.addView(c.divider(20f))
                functions.addView(row(R.drawable.ic_cover_view, "${com.hayeco.flipspaces.core.spatial.SideBySideMotion.MENU_ROW}: ${if (flat) "ON" else "OFF"}", tick = flat, subtitle = com.hayeco.flipspaces.core.spatial.SideBySideMotion.MENU_SUBTITLE) {
                    host.sideBySideFlatChosen(!flat)
                    buildDrawer()
                })
            }
        }
        // 0.5.102 (Mike): the Glasses report moved to Advanced on the settings home.
        functions.addView(c.divider(20f))
        // 0.5.99 (Mike: record the glasses as the Aura's puck does): the Space records what the left eye sees.
        val recordingNow = host.recordingGlasses()
        functions.addView(row(R.drawable.ic_record, if (recordingNow) "Stop recording the glasses" else "Record the glasses") {
            closeDrawer()
            host.recordGlasses(!recordingNow)
        })
        // 1.2.3 (issue 54): an Air's display mode, behind a switch that is off by default (untested on Air glasses).
        if (host.airDisplayOffered()) {
            val on = host.airDisplayOn()
            functions.addView(c.divider(20f))
            functions.addView(row(R.drawable.ic_cover_view, "${com.hayeco.flipspaces.core.spatial.AirDisplayMode.SWITCH_ROW}: ${if (on) "ON" else "OFF"}", tick = on, subtitle = com.hayeco.flipspaces.core.spatial.AirDisplayMode.SWITCH_SUBTITLE) {
                host.airDisplayChosen(!on)
                buildDrawer()
            })
            if (on) {
                val line = host.airDisplayLine()
                functions.addView(c.divider(20f))
                functions.addView(row(R.drawable.ic_cover_view, "Glasses display: $line", subtitle = host.airDisplayAction()) {
                    closeDrawer()
                    host.airDisplaySwitch()
                })
            }
        }
        // 1.2.3 (issue 59): Game keys on the open phone.
        if (!cover) {
            val game = host.gameKeysOn()
            functions.addView(c.divider(20f))
            functions.addView(row(R.drawable.ic_cover_keyboard, "${com.hayeco.flipspaces.core.pointer.GameKeyLayout.MENU_ROW}: ${if (game) "ON" else "OFF"}", tick = game, subtitle = com.hayeco.flipspaces.core.pointer.GameKeyLayout.MENU_SUBTITLE) {
                closeDrawer()
                host.gameKeysChosen(!game)
            })
            // 1.2.3 (issue 59): the Game controller, cycling Off, Gamepad and Touch; in Touch, its spots.
            val mode = host.controllerMode()
            functions.addView(c.divider(20f))
            val sub = when (mode) {
                com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF -> com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF_SUBTITLE
                com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD -> com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD_SUBTITLE
                com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH -> com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH_SUBTITLE
            }
            functions.addView(row(R.drawable.ic_cover_keyboard, "${com.hayeco.flipspaces.core.pointer.GameControllerMode.MENU_ROW}: ${mode.label}", tick = mode != com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF, subtitle = "$sub. Tap for ${mode.next().label}.") {
                closeDrawer()
                host.controllerModeChosen(mode.next())
            })
            if (mode == com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH) {
                val setting = host.touchSpotSetting()
                functions.addView(c.divider(20f))
                functions.addView(row(R.drawable.ic_cover_keyboard, "Set touch spots: ${if (setting) "ON" else "OFF"}", tick = setting, subtitle = "Aim the dot at the game's own button, then hold the matching control for a second") {
                    closeDrawer()
                    host.touchSpotSettingChosen(!setting)
                })
                functions.addView(c.divider(20f))
                functions.addView(row(R.drawable.ic_cover_keyboard, "Clear touch spots for this game") {
                    closeDrawer()
                    host.clearTouchSpots()
                })
            }
        }
        // 0.5.17 cover screen: the trackpad runs over the lock screen; this asks for the phone to unlock.
        functions.addView(c.divider(20f))
        functions.addView(row(R.drawable.ic_unlock_icon, "Unlock phone") {
            closeDrawer()
            host.unlockPhone()
        })
        // 1.2.3 (issue 52): the phone in a pocket while the glasses keep running.
        functions.addView(c.divider(20f))
        functions.addView(row(R.drawable.ic_cover_lock, com.hayeco.flipspaces.core.pointer.PocketMode.MENU_ROW, subtitle = com.hayeco.flipspaces.core.pointer.PocketMode.MENU_SUBTITLE) {
            closeDrawer()
            host.pocketMode()
        })
        drawerBody.addView(functions, cardParams(if (koFi || testersShown) 12f else 25f))

        trackpadHeading = c.padText("Trackpad", 14f, WHITE)
        drawerBody.addView(trackpadHeading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
        val s = card()
        // Cursor Speed.
        val speed = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, c.dp(20f), 0, c.dp(14f))
        }
        speed.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20f), 0, c.dp(20f), 0)
            addView(c.icon(R.drawable.ic_cursor_speed, 20f))
            addView(c.padText("Cursor Speed", 14f, TEXT_B3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8f) })
        })
        val slider = SpeedSlider(c) { host.speedChanged(it) }.apply { progress = settings.cursorSpeed }
        speedSlider = slider
        speed.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(40f)).apply { topMargin = c.dp(6f) })
        speed.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(20f), 0, c.dp(20f), 0)
            addView(c.padText("Slow", 12f, TEXT_B3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(c.padText("Fast", 12f, TEXT_B3))
        })
        s.addView(speed)
        // 0.5.83 (Mike: "two finger scroll and trackpad scroll speed should be adjustable in settings"): the same slider for each.
        s.addView(c.divider(20f))
        s.addView(speedRow("Two-finger scroll speed", settings.scrollSpeed) { host.scrollSpeedChanged(it) })
        s.addView(c.divider(20f))
        s.addView(speedRow("Scroll strip speed", settings.stripSpeed) { host.stripSpeedChanged(it) })
        // 1.1.10 (issue 35): one finger scrolls on Spatial Mouse, on by default.
        s.addView(c.divider(20f))
        val oneFinger = settings.oneFingerScroll
        s.addView(row(R.drawable.ic_scroll_right, "One-finger scroll on Spatial Mouse: ${if (oneFinger) "ON" else "OFF"}", tick = oneFinger) {
            host.oneFingerScrollChosen(!oneFinger)
            buildDrawer()
        })
        // 0.5.21: the thin scroll strip's side, on every main screen page (the old wide strip's side
        // choice, now for the thin strip); the cover's strip goes opposite its window buttons.
        if (!cover) {
            s.addView(c.divider(20f))
            s.addView(choice(
                "Scroll strip",
                listOf(Triple(R.drawable.ic_scroll_left, "Left side", settings.stripSide == StripSide.LEFT), Triple(R.drawable.ic_scroll_right, "Right side", settings.stripSide == StripSide.RIGHT)),
                24f, 41f,
            ) { i -> host.stripChosen(if (i == 0) StripSide.LEFT else StripSide.RIGHT) })
        }
        // 0.5.17 cover screen: which side the window buttons are on, for the thumb in use.
        if (cover) {
            s.addView(c.divider(20f))
            s.addView(choice(
                "Window buttons on the cover screen",
                listOf(Triple(R.drawable.ic_scroll_left, "Left side", settings.coverControls == StripSide.LEFT), Triple(R.drawable.ic_scroll_right, "Right side", settings.coverControls == StripSide.RIGHT)),
                24f, 41f,
            ) { i -> host.coverControlsChosen(if (i == 0) StripSide.LEFT else StripSide.RIGHT) })
            // 0.5.41 (Mike: "the scroll bar on the trackpad should be able to be switched to the right side"): on the cover
            // the strip is opposite the window buttons, so choosing its side moves the buttons to the other one.
            val stripNow = com.hayeco.flipspaces.core.pointer.ScrollStrip.coverSide(settings.coverControls)
            s.addView(c.divider(20f))
            s.addView(choice(
                "Scroll strip on the cover screen",
                listOf(Triple(R.drawable.ic_scroll_left, "Left side", stripNow == StripSide.LEFT), Triple(R.drawable.ic_scroll_right, "Right side", stripNow == StripSide.RIGHT)),
                24f, 41f,
            ) { i -> host.coverControlsChosen(if (i == 0) StripSide.RIGHT else StripSide.LEFT) })
        }
        // Horizontal Trackpad Direction (not on the cover screen, whose layout has no direction). 1.2.3 (issue 56): only on
        // the Horizontal Trackpad, the one page it turns; elsewhere the choice did nothing.
        if (!cover && com.hayeco.flipspaces.core.pointer.TrackpadSettings.directionApplies(settings.mode)) {
            s.addView(c.divider(20f))
            s.addView(choice(
                "Sideways trackpad direction",
                listOf(Triple(R.drawable.svg_orientation_default, "Default", settings.direction == PadDirection.DEFAULT), Triple(R.drawable.svg_orientation_flip, "Inverted", settings.direction == PadDirection.INVERTED)),
                60f, 60f,
            ) { i -> host.directionChosen(if (i == 0) PadDirection.DEFAULT else PadDirection.INVERTED) })
        }
        // 0.5.37 (Mike: the Cursor follows head toggle in the Menu, not on the trackpad).
        s.addView(c.divider(20f))
        val follows = host.cursorFollowsHeadOn()
        s.addView(row(R.drawable.ic_cursor_speed, "Cursor follows head: ${if (follows) "ON" else "OFF"}", tick = follows) {
            host.cursorFollowsHeadChosen(!follows)
            buildDrawer()
        })
        // 0.5.51 (Mike: the cover screen never goes dark): off lets the phone's screen sleep while the glasses are used.
        s.addView(c.divider(20f))
        val keeps = host.keepsScreenOnOn()
        s.addView(row(R.drawable.ic_usage_intro, "Trackpad keeps screen on: ${if (keeps) "ON" else "OFF"}", tick = keeps) {
            host.keepsScreenOnChosen(!keeps)
            buildDrawer()
        })
        // 0.5.58: "Glasses set the frame clock (test)" is on the Developer card only now, measured
        // worse three times.
        s.addView(c.divider(20f))
        s.addView(row(R.drawable.ic_usage_intro, "How to use the trackpad") {
            closeDrawer()
            showIntro(host.introPages())
        })
        drawerBody.addView(s, cardParams(12f))

        // The tracking mode and layouts are in the glasses' Control panel (a three finger swipe down);
        // they are here too, so they can be reached without a gesture.
        drawerBody.addView(c.padText("Space", 14f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
        val space = card()
        FollowMode.entries.forEachIndexed { i, m ->
            if (i > 0) space.addView(c.divider(20f))
            val selected = host.currentFollow() == m
            space.addView(row(R.drawable.ic_follow_mode, "${m.label} (${m.dof})", tick = selected, enabled = m.available) {
                host.followChosen(m)
                buildDrawer()
            })
        }
        // 1.1.15 (John Davis: a "lock screens" option): Lock screens, also the Lock button by the window keys.
        space.addView(c.divider(20f))
        val lockedOn = host.windowsLockedOn()
        space.addView(row(R.drawable.ic_cover_lock, com.hayeco.flipspaces.core.spatial.WindowLock.rowLabel(lockedOn), tick = lockedOn, subtitle = com.hayeco.flipspaces.core.spatial.WindowLock.SUBTITLE) {
            host.windowsLockedChosen(!lockedOn)
            refreshCover()
            buildDrawer()
        }.apply { tag = LockKey.MENU_TAG })
        // 0.5.37 (Mike: a setting for window snapping; the "Open a new instance" switch in the Menu). 1.1.10: its subtitle
        // says what it is for, as a window whose app moved away names it.
        space.addView(c.divider(20f))
        val snapOn = host.snapWindowsOn()
        space.addView(row(R.drawable.ic_arrange, "Snap windows: ${if (snapOn) "ON" else "OFF"}", tick = snapOn) {
            host.snapWindowsChosen(!snapOn)
            buildDrawer()
        })
        space.addView(c.divider(20f))
        val newOn = host.newInstanceOn()
        space.addView(row(R.drawable.ic_arrange, "${com.hayeco.flipspaces.core.spatial.NewInstanceSwitch.LABEL}: ${if (newOn) "ON" else "OFF"}", tick = newOn, subtitle = com.hayeco.flipspaces.core.spatial.NewInstanceSwitch.SUBTITLE) {
            host.newInstanceChosen(!newOn)
            buildDrawer()
        })
        drawerBody.addView(space, cardParams(12f))

        // 0.5.32 (Mike: more premade Arrange Windows layouts, knowing how many windows are open): the layouts that fit
        // the windows open now, in a card of their own under a heading with the count.
        val count = host.windowCount()
        drawerBody.addView(c.padText(if (count == 1) "Arrange 1 window" else "Arrange $count windows", 14f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
        val arrangeCard = card()
        val choices = com.hayeco.flipspaces.core.spatial.ArrangeLayouts.forCount(count)
        if (choices.isEmpty()) arrangeCard.addView(row(R.drawable.ic_arrange, "Open a window first", enabled = false) {})
        choices.forEachIndexed { i, choice ->
            if (i > 0) arrangeCard.addView(c.divider(20f))
            val icon = if (choice.kind == ArrangeKind.CIRCLE || choice.kind == ArrangeKind.WRAPAROUND) R.drawable.ic_circle_layout else R.drawable.ic_arrange
            arrangeCard.addView(row(icon, choice.label) {
                closeDrawer()
                host.arrange(choice.kind)
            })
        }
        // 0.5.88 (Mike: "a simple way to do this with trackpad and spatial mouse cursor dot"): every window held together,
        // to push, pull, resize and turn as one with the held window's own gestures.
        if (count > 0) {
            arrangeCard.addView(c.divider(20f))
            arrangeCard.addView(row(R.drawable.ic_arrange, "Adjust all windows together") {
                closeDrawer()
                host.arrange(ArrangeKind.ADJUST_ALL)
            })
        }
        drawerBody.addView(arrangeCard, cardParams(12f))

        // 0.5.37 (Mike: save preset app windows, reopened exactly as they were): each preset with a drawing of its windows
        // around the head; a tap opens it (the windows now close), the mark deletes it; Save keeps the windows open now.
        drawerBody.addView(c.padText("Window presets", 14f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
        val presetCard = card()
        val presets = host.presets()
        presets.forEach { p ->
            presetCard.addView(PresetRow(c, p, onOpen = {
                closeDrawer()
                host.openPreset(p)
            }, onDelete = {
                host.deletePreset(p)
                buildDrawer()
            }, onRename = {
                host.renamePreset(p) { buildDrawer() }
            }))
            presetCard.addView(c.divider(20f))
        }
        if (presets.isEmpty()) {
            presetCard.addView(c.padText("Save the windows you have open, then open them all again with one tap, each where it was.", 12f, TEXT_99).apply { setPadding(c.dp(20f), c.dp(14f), c.dp(20f), c.dp(6f)) })
        }
        presetCard.addView(row(R.drawable.ic_arrange, if (count == 0) "Save current windows (open some first)" else "Save current windows as a preset", enabled = count > 0) {
            host.savePreset()
            buildDrawer()
        })
        drawerBody.addView(presetCard, cardParams(12f))

        // 0.5.36 (Mike: one slider instead of four curve choices; Curve and depth on the selected window only): the curve new
        // windows open with, 0 to 100 % of the strongest ([com.hayeco.flipspaces.core.spatial.PanelCurve.MAX]). The
        // wraparound is gone: it made Curve and Further/Closer act on every window at once.
        drawerBody.addView(c.padText("Window curve", 14f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
        val curve = card()
        val curveBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, c.dp(20f), 0, c.dp(14f))
        }
        val curvePercent = com.hayeco.flipspaces.core.spatial.PanelCurve.percent(host.currentCurveDefault())
        val curveLabel = c.padText("New windows curve: ${if (curvePercent == 0) "flat" else "$curvePercent %"}", 14f, TEXT_B3)
        curveBox.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20f), 0, c.dp(20f), 0)
            addView(c.icon(R.drawable.ic_cover_curve, 20f))
            addView(curveLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8f) })
        })
        curveBox.addView(SpeedSlider(c) { pct ->
            host.curveDefaultChosen(pct / 100f * com.hayeco.flipspaces.core.spatial.PanelCurve.MAX)
            curveLabel.text = "New windows curve: ${if (pct == 0) "flat" else "$pct %"}"
        }.apply { progress = curvePercent }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(40f)).apply { topMargin = c.dp(6f) })
        curveBox.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(20f), 0, c.dp(20f), 0)
            addView(c.padText("Flat", 12f, TEXT_B3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(c.padText("Strong", 12f, TEXT_B3))
        })
        curve.addView(curveBox)
        drawerBody.addView(curve, cardParams(12f))

        // 0.5.101 (Mike: each settings screen needs "a way to reset to Pocket XR default settings"): asks first.
        val reset = card()
        reset.addView(row(R.drawable.ic_follow_mode, "Reset these settings to Pocket XR defaults") {
            android.app.AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset trackpad settings?")
                .setMessage(
                    "Cursor, scroll and strip speeds, the strip's side, the sideways direction, cursor follows head, " +
                        "keep screen on, the tracking mode, snap windows, new instances and the new windows' curve go back to " +
                        "Pocket XR's defaults. The trackpad type, your windows and presets stay.",
                )
                .setPositiveButton("Reset") { _, _ ->
                    host.resetSettings()
                    buildDrawer()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        drawerBody.addView(reset, cardParams(30f))

        // 0.5.66 (Mike: remove the edge fade and the glasses test card settings): their Menu cards are gone. The test card
        // is off at every start; the edge fade keeps its saved mode.

        // 0.5.30 (Mike: remove the grey debug block at the bottom of the view): the Space's debug info panel is off
        // by default and only here, in the Developer card. 1.1.6: shown by its own switch, remembered.
        val devToggle = card()
        devToggle.addView(row(R.drawable.ic_follow_mode, "Developer settings: ${if (developerShown) "ON" else "OFF"}", tick = developerShown) {
            developerShown = !developerShown
            buildDrawer()
        })
        drawerBody.addView(devToggle, cardParams(30f))
        if (developerShown) {
            drawerBody.addView(c.padText("Developer", 14f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(30f) })
            val dev = card()
            val infoOn = host.debugInfoOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Debug info panel: ${if (infoOn) "ON" else "OFF"}", tick = infoOn) {
                host.debugInfoChosen(!infoOn)
                buildDrawer()
            })
            // 0.5.32: the still head's lock, on by default; off for an A/B against 0.5.31's tremor.
            val lockOn = host.stillLockOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Still lock: ${if (lockOn) "ON" else "OFF"}", tick = lockOn) {
                host.stillLockChosen(!lockOn)
                buildDrawer()
            })
            // 0.5.64: the drawn orientation's One Euro filter, on by default; off for an A/B.
            val smoothOn = host.drawSmoothingOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Draw smoothing: ${if (smoothOn) "ON" else "OFF"}", tick = smoothOn) {
                host.drawSmoothingChosen(!smoothOn)
                buildDrawer()
            })
            // 0.5.65: the glasses switched to the Space's view at plug-in, on by default.
            val autoViewOn = host.autoGlassesViewOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Switch the glasses' 3D / 2D: ${if (autoViewOn) "ON" else "OFF"}", tick = autoViewOn) {
                host.autoGlassesViewChosen(!autoViewOn)
                buildDrawer()
            })
            // 0.5.65: the glasses' factory calibration in the head tracking, on by default; off for an A/B.
            val factoryOn = host.factoryCalibrationOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Factory calibration: ${if (factoryOn) "ON" else "OFF"}", tick = factoryOn) {
                host.factoryCalibrationChosen(!factoryOn)
                buildDrawer()
            })
            // 0.5.43: the hand models on the Exynos 2500's NPU, MediaPipe behind them.
            val npuOn = host.npuHandsOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Hands on the NPU: ${if (npuOn) "ON" else "OFF"}", tick = npuOn) {
                host.npuHandsChosen(!npuOn)
                buildDrawer()
            })
            // 0.5.52: window displays on a virtual device, in a display group of their own (applies to windows opened after).
            val devOn = host.deviceWindowsOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Windows stay awake when the phone sleeps (test): ${if (devOn) "ON" else "OFF"}", tick = devOn) {
                host.deviceWindowsChosen(!devOn)
                buildDrawer()
            })
            // 0.5.55: off draws the trackpad in its window again, scaled by SurfaceFlinger on the cover.
            val nativeOn = host.nativeBufferOn()
            dev.addView(row(R.drawable.ic_follow_mode, "Trackpad at the screen's own pixels: ${if (nativeOn) "ON" else "OFF"}", tick = nativeOn) {
                host.nativeBufferChosen(!nativeOn)
                buildDrawer()
            })
            // 0.5.54: the glasses set the Space's frame clock (sfdo force-pacesetter). 0.5.58: here,
            // not in the Menu; OFF gives the phone its clock back and checks it, and the last outcome shows under it.
            val clock = host.frameClockOn()
            val clockNote = host.frameClockUnavailable()?.let { " ($it, so it can't run)" } ?: ""
            dev.addView(row(R.drawable.ic_follow_mode, "Glasses set the frame clock (test): ${if (clock) "ON" else "OFF"}$clockNote", tick = clock) {
                host.frameClockChosen(!clock)
                buildDrawer()
            })
            host.frameClockState()?.let { dev.addView(c.padText(it, 12f, WHITE)) }
            drawerBody.addView(dev, cardParams(12f))
        }
        // 1.1.10 (a tester: "an exit app button would be nice, as it stays active in the notification bar"): the Menu's
        // last row quits Pocket XR. The tutorial's last page says it is here (IntroPages.EXIT_NOTE).
        val exit = card()
        exit.addView(row(R.drawable.ic_close, com.hayeco.flipspaces.core.pointer.IntroPages.EXIT_LABEL) {
            closeDrawer()
            host.exitApp()
        })
        drawerBody.addView(exit, cardParams(30f).apply { bottomMargin = c.dp(30f) })
    }

    /** 0.5.30: the hidden Developer card is shown (a long press on the wordmark); not kept past the Menu's view. */
    /** 1.1.6: the Developer card's switch, remembered across starts. */
    private var developerShown: Boolean
        get() = context.getSharedPreferences("trackpad", Context.MODE_PRIVATE).getBoolean("developer_shown", false)
        set(value) = context.getSharedPreferences("trackpad", Context.MODE_PRIVATE).edit().putBoolean("developer_shown", value).apply()

    /** 0.5.27: whether the picture adjust is open under the edge check, and which eye it adjusts (0 left, 1 right). */
    private var adjustOpen = false
    private var adjustEye = 0

    /**
     * 0.5.27: the per-eye picture adjust: which eye, "Same for both eyes", a shift across and up or down in steps of
     * [com.hayeco.flipspaces.core.spatial.PictureAdjust.STEP_PX] px, the size in steps of 1 %, and a reset. Each change
     * is saved for these glasses and shown on the glasses at once.
     */
    private fun addPictureAdjust(card: LinearLayout, adjust: com.hayeco.flipspaces.core.spatial.PictureAdjust) {
        val c = context
        val step = com.hayeco.flipspaces.core.spatial.PictureAdjust.STEP_PX
        card.addView(c.padText("Applied in the glasses' 3D mode (3D and \"2D in 3D\"). Move each eye's picture by the pixels the edge check says it loses, or make it smaller until every edge shows.", 12f, TEXT_99).apply { setPadding(c.dp(20f), c.dp(4f), c.dp(20f), c.dp(8f)) })
        card.addView(row(R.drawable.ic_follow_mode, "Same for both eyes", tick = adjust.same) {
            host.pictureAdjustChanged(adjust.withSame(!adjust.same), "same for both eyes ${if (adjust.same) "off" else "on"}")
            buildDrawer()
        })
        if (!adjust.same) {
            card.addView(c.divider(20f))
            for ((i, name) in listOf("Left eye", "Right eye").withIndex()) {
                card.addView(row(R.drawable.ic_follow_mode, "Adjust the ${name.lowercase()}", tick = adjustEye == i) {
                    adjustEye = i
                    buildDrawer()
                })
            }
        }
        val eye = if (adjust.same) 0 else adjustEye
        val who = if (adjust.same) "both eyes" else if (eye == 0) "left eye" else "right eye"
        val e = adjust.eye(eye)
        fun change(axis: com.hayeco.flipspaces.core.spatial.AdjustAxis, dir: Int) {
            val next = adjust.step(eye, axis, dir)
            if (next != adjust) host.pictureAdjustChanged(next, "$who ${axis.label} ${if (dir > 0) "+" else "-"}")
            buildDrawer()
        }
        card.addView(c.divider(20f))
        card.addView(stepper("Across: ${if (e.dxPx < 0) "left" else "right"} ${kotlin.math.abs(e.dxPx)} px", "Left $step", "Right $step", { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SHIFT_X, -1) }, { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SHIFT_X, 1) }))
        card.addView(stepper("Up / down: ${if (e.dyPx < 0) "up" else "down"} ${kotlin.math.abs(e.dyPx)} px", "Up $step", "Down $step", { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SHIFT_Y, -1) }, { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SHIFT_Y, 1) }))
        card.addView(stepper("Size: ${e.scalePct} %", "-1 %", "+1 %", { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SCALE, -1) }, { change(com.hayeco.flipspaces.core.spatial.AdjustAxis.SCALE, 1) }))
        card.addView(c.divider(20f))
        card.addView(row(R.drawable.ic_follow_mode, "Reset both eyes", enabled = !adjust.neutral || adjust.same) {
            host.pictureAdjustChanged(com.hayeco.flipspaces.core.spatial.PictureAdjust.NONE, "reset")
            buildDrawer()
        })
    }

    /** 0.5.27: a label with two buttons, for the picture adjust's steps. */
    private fun stepper(label: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit): View {
        val c = context
        fun button(text: String, onClick: () -> Unit) = c.padText(text, 13f, WHITE).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_drawer_tile)
            setPadding(c.dp(10f), c.dp(8f), c.dp(10f), c.dp(8f))
            minWidth = c.dp(64f)
            contentDescription = "$label: $text"
            setOnClickListener { onClick() }
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20f), c.dp(6f), c.dp(20f), c.dp(6f))
            minimumHeight = c.dp(52f)
            addView(c.padText(label, 14f, TEXT_B3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(less, onLess))
            addView(button(more, onMore), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8f) })
        }
    }

    /** 0.5.83: a titled Slow to Fast slider like Cursor Speed's, sending its value on the lift. */
    private fun speedRow(title: String, value: Int, onLift: (Int) -> Unit): View {
        val c = context
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, c.dp(20f), 0, c.dp(14f))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(20f), 0, c.dp(20f), 0)
                addView(c.icon(R.drawable.ic_cursor_speed, 20f))
                addView(c.padText(title, 14f, TEXT_B3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8f) })
            })
            addView(SpeedSlider(c, onLift).apply { progress = value }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(40f)).apply { topMargin = c.dp(6f) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(c.dp(20f), 0, c.dp(20f), 0)
                addView(c.padText("Slow", 12f, TEXT_B3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c.padText("Fast", 12f, TEXT_B3))
            })
        }
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_drawer_card)
    }

    /**
     * 1.1.15: the Menu's "Buy me a beer" pill: 48 tall, the cards' own fill and rim, an 18 beer, the 14 sp line, and a quiet
     * "Ko-fi" at its end. No accent colour, so it reads as part of the Menu rather than an ad. Opens Ko-fi in the browser.
     */
    private fun supportPill(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(18f), 0, context.dp(18f), 0)
        minimumHeight = context.dp(48f)
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(24f)
            setColor(com.hayeco.flipspaces.core.Look.CARD)
            setStroke(context.dp(1f), com.hayeco.flipspaces.core.Look.RIM)
        }
        addView(context.icon(R.drawable.ic_beer, 18f).apply { alpha = 0.7f })
        addView(context.padText(com.hayeco.flipspaces.core.Support.LABEL, 14f, TEXT_B3).apply { setSingleLine(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10f) })
        addView(context.padText(com.hayeco.flipspaces.core.Support.WHERE, 12f, TEXT_80))
        // TalkBack reads one button, not its parts.
        contentDescription = com.hayeco.flipspaces.core.Support.DESCRIPTION
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        tag = MENU_KO_FI
        pressLight(this, 24f)
        setOnClickListener {
            tick(this)
            closeDrawer()
            SupportLink.open(context)
        }
    }

    /** Unreleased: where the Play testers card's state is kept ([TrackpadPrefs.playTesters]). */
    private val padPrefs by lazy { TrackpadPrefs(context) }

    /**
     * Unreleased (Mike: get the sideload users into the Play closed test): a soft blue card with the Ko-fi pill's round
     * corners. Join opens the Google Group, and the card then also offers Open Play invite; opening that finishes it. Not
     * now hides it for 7 days, I already joined for good ([com.hayeco.flipspaces.core.PlayTesters]).
     */
    private fun playTestersCard(card: com.hayeco.flipspaces.core.PlayTesters.Card): View {
        val c = context
        val t = com.hayeco.flipspaces.core.PlayTesters
        fun save(change: (com.hayeco.flipspaces.core.PlayTesters.State) -> com.hayeco.flipspaces.core.PlayTesters.State) {
            padPrefs.playTesters = change(padPrefs.playTesters)
            buildDrawer()
        }
        fun pill(label: String, primary: Boolean, tagged: String, onClick: () -> Unit) = c.padText(label, 14f, if (primary) DARK_TEXT else WHITE, bold = primary).apply {
            gravity = Gravity.CENTER
            minHeight = c.dp(40f)
            setPadding(c.dp(14f), 0, c.dp(14f), 0)
            if (primary) setBackgroundResource(R.drawable.bg_pad_button_light)
            tag = tagged
            setOnClickListener { tick(this); onClick() }
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(18f), c.dp(14f), c.dp(12f), c.dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = c.dpf(24f)
                setColor(com.hayeco.flipspaces.core.Look.HELD_GLASS)
                setStroke(c.dp(1f), com.hayeco.flipspaces.core.Look.ACCENT and 0x66FFFFFF)
            }
            tag = MENU_PLAY_TESTERS
            addView(c.padText(t.TITLE, 14f, WHITE, bold = true))
            addView(c.padText(t.BODY, 12f, TEXT_B3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4f); marginEnd = c.dp(6f) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(t.JOIN, primary = card == com.hayeco.flipspaces.core.PlayTesters.Card.JOIN, tagged = MENU_PLAY_TESTERS_JOIN) {
                    SupportLink.openUrl(c, t.GROUP_URL)
                    save { t.joined(it) }
                })
                if (card == com.hayeco.flipspaces.core.PlayTesters.Card.PLAY_INVITE) {
                    addView(pill(t.OPEN_PLAY, primary = true, tagged = MENU_PLAY_TESTERS_PLAY) {
                        SupportLink.openUrl(c, t.PLAY_URL)
                        save { t.playOpened(it) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8f) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12f) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(t.NOT_NOW, primary = false, tagged = MENU_PLAY_TESTERS_NOT_NOW) { save { t.notNow(it, System.currentTimeMillis()) } })
                addView(pill(t.ALREADY_JOINED, primary = false, tagged = MENU_PLAY_TESTERS_ALREADY) { save { t.alreadyJoined(it) } })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4f) })
        }
    }

    /** 1.1.15: the tags of the Menu's tagged rows, top down (the tests). */
    internal fun menuRowTags(): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            (v.tag as? String)?.let { out += it }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(drawerBody)
        return out
    }

    /** 1.1.15: the Menu row tagged [tag], for the tests. */
    internal fun menuRow(tag: String): View? = drawerBody.findViewWithTag(tag)

    private fun cardParams(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(topDp) }

    /**
     * A 60 tall row: a 20 icon, a 14 sp label, then the right chevron (or a tick). 1.1.10: a [subtitle] is a quieter
     * 12 sp line under the label, and the row grows to fit it.
     */
    private fun row(iconRes: Int, label: String, tick: Boolean? = null, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(20f), if (subtitle != null) context.dp(10f) else 0, context.dp(20f), if (subtitle != null) context.dp(10f) else 0)
        minimumHeight = context.dp(60f)
        alpha = if (enabled) 1f else 0.4f
        addView(context.icon(iconRes, 20f))
        val text: View = if (subtitle == null) context.padText(label, 14f, TEXT_B3) else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.padText(label, 14f, TEXT_B3))
            addView(context.padText(subtitle, 12f, TEXT_80), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2f) })
        }
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10f) })
        when (tick) {
            null -> addView(ImageView(context).apply { setImageResource(R.drawable.ic_right_arrow) }, LinearLayout.LayoutParams(context.dp(8f), context.dp(14f)))
            true -> addView(context.icon(R.drawable.ic_scroll_checked, 20f))
            false -> addView(context.icon(R.drawable.ic_scroll_unchecked, 20f))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (subtitle != null) ViewGroup.LayoutParams.WRAP_CONTENT else context.dp(60f))
        if (enabled) {
            pressLight(this, 0f)
            setOnClickListener { tick(this); onClick() }
        }
    }

    /**
     * 0.5.73 (the survey: most Menu rows gave no sign of a press): a light that blooms from the finger while pressed (the
     * framework's ripple, white at 12 %), clipped to [radiusDp] corners, on any of the Menu's pressable views.
     */
    private fun pressLight(v: View, radiusDp: Float) {
        val mask = GradientDrawable().apply {
            setColor(WHITE)
            cornerRadius = context.dpf(radiusDp)
        }
        v.foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF), null, mask)
    }

    /**
     * 1.2.3 (issue 57: "a density slider for each window ... remembered per app"): the focused window's text size, with
     * smaller and larger steps and a reset to 100 %. Smaller fits more on the window (a browser's desktop layout), larger
     * makes text easier to read; the window keeps its size and place.
     */
    private fun textSizeRow(label: String, percent: Int): View {
        val c = context
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20f), c.dp(14f), c.dp(20f), c.dp(14f))
            addView(c.padText("Text size in $label", 14f, WHITE))
            addView(c.padText("Smaller fits more on the window. Remembered for this app.", 12f, TEXT_99), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(2f) })
            val buttons = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                fun key(text: String, enabled: Boolean, steps: Int) = c.padText(text, 16f, if (enabled) WHITE else 0xFF666666.toInt(), bold = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_drawer_tile)
                    isEnabled = enabled
                    if (enabled) pressLight(this, 14f)
                    contentDescription = when {
                        steps < 0 -> "Smaller text"
                        steps > 0 -> "Larger text"
                        else -> "Text size back to 100 %"
                    }
                    setOnClickListener {
                        tick(this)
                        host.textSizeStep(steps)
                    }
                }
                val minus = com.hayeco.flipspaces.core.spatial.AppTextSize.MIN
                val plus = com.hayeco.flipspaces.core.spatial.AppTextSize.MAX
                addView(key("A-", percent > minus, -1), LinearLayout.LayoutParams(0, c.dp(44f), 1f))
                addView(key("$percent %", percent != com.hayeco.flipspaces.core.spatial.AppTextSize.DEFAULT, 0), LinearLayout.LayoutParams(0, c.dp(44f), 1.2f).apply { marginStart = c.dp(8f); marginEnd = c.dp(8f) })
                addView(key("A+", percent < plus, 1), LinearLayout.LayoutParams(0, c.dp(44f), 1f))
            }
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12f) })
        }
    }

    /** 0.5.73: the trackpad's light tick on a Menu press, as its other keys have. */
    private fun tick(v: View) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Two 85 x 80 tiles with pictures, labels (12 sp, 60% white) and 20 dp radios under a 14 sp title. */
    private fun choice(title: String, options: List<Triple<Int, String, Boolean>>, picW: Float, picH: Float, onPick: (Int) -> Unit): View {
        val c = context
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20f), c.dp(20f), c.dp(20f), c.dp(16f))
            addView(c.padText(title, 14f, TEXT_B3))
            val tiles = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEachIndexed { i, (res, label, checked) ->
                val column = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val tile = FrameLayout(c).apply {
                        setBackgroundResource(R.drawable.bg_drawer_tile)
                        addView(ImageView(c).apply { setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(c.dp(picW), c.dp(picH), Gravity.CENTER))
                    }
                    addView(tile, LinearLayout.LayoutParams(c.dp(85f), c.dp(80f)))
                    addView(c.padText(label, 12f, TEXT_99), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(10f) })
                    addView(c.icon(if (checked) R.drawable.ic_scroll_checked else R.drawable.ic_scroll_unchecked, 20f).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = c.dp(8f)
                    })
                    tile.isDuplicateParentStateEnabled = true
                    pressLight(tile, 14f)
                    setOnClickListener {
                        tick(this)
                        onPick(i)
                        buildDrawer()
                    }
                }
                tiles.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12f) })
        }
    }

    // ---- the usage instructions ----

    private var introPage = 0
    private var introDownX = 0f

    /** 1.1.9: the pages shown now: first run's, the Menu's trackpad pages, or the glasses setup page alone. */
    private var introPages: List<com.hayeco.flipspaces.core.pointer.IntroPage> = com.hayeco.flipspaces.core.pointer.IntroPages.GESTURES

    fun showIntro(pages: List<com.hayeco.flipspaces.core.pointer.IntroPage>) {
        if (pages.isEmpty()) return
        closePopup()
        introPages = pages
        introPage = 0
        buildIntro()
        mask.visibility = View.VISIBLE
        intro.visibility = View.VISIBLE
        requestLayout()
    }

    /**
     * 1.1.16 (issue 12): the glasses setup check ([GlassesCheckCard], built by the activity) in the tutorial's frame. It
     * closes itself through [closeIntro].
     */
    fun showGlassesCheck(build: (close: () -> Unit) -> View) {
        closePopup()
        intro.removeAllViews()
        intro.background = null
        intro.addView(build { closeIntro() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mask.visibility = View.VISIBLE
        intro.visibility = View.VISIBLE
        requestLayout()
    }

    /** 1.1.16: the glasses check open now, or null (the tests). */
    internal val glassesCheck: GlassesCheckCard? get() = intro.getChildAt(0) as? GlassesCheckCard

    private fun closeIntro() {
        intro.visibility = View.GONE
        intro.removeAllViews()
        if (dialog.visibility != View.VISIBLE && popup.visibility != View.VISIBLE) mask.visibility = View.GONE
    }

    val introOpen: Boolean get() = intro.visibility == View.VISIBLE

    @SuppressLint("ClickableViewAccessibility")
    private fun buildIntro() {
        val c = context
        intro.removeAllViews()
        val shown = introPages[introPage]
        val n = introPages.size
        fun turn(by: Int) {
            introPage = ((introPage + by) % n + n) % n
            buildIntro()
        }
        // 0.5.78: a dark glass card like the dialog's (it was white and light grey).
        intro.setBackgroundResource(R.drawable.bg_pad_dialog)
        intro.clipToOutline = true
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            // 1.1.2 (a Retroid in landscape, 432 dp tall: the last line and the dots were cut off): on a short screen the
            // picture goes and the words get the card. 1.1.9: the words take what they need and the picture the rest, so
            // a page with more words (the glasses setup) still fits.
            val picture = resources.configuration.screenHeightDp >= 500
            if (picture) addView(IntroCover(c, shown.art), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val text = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(com.hayeco.flipspaces.core.Look.CARD)
                setPadding(c.dp(20f), c.dp(16f), c.dp(20f), c.dp(10f))
                // The title clears the close mark at the card's top right when there is no picture above it.
                addView(c.padText(shown.title, 20f, com.hayeco.flipspaces.core.Look.INK, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (!picture) marginEnd = c.dp(24f)
                })
                shown.items.forEachIndexed { i, item ->
                    addView(LinearLayout(c).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(c.padText("${i + 1}", 10f, WHITE).apply {
                            setBackgroundResource(R.drawable.bg_intro_number)
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(c.dp(15f), c.dp(15f)).apply { topMargin = c.dp(2f) })
                        addView(c.padText(item, 14f, com.hayeco.flipspaces.core.Look.INK_2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(8f) })
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(10f) })
                }
                // 1.2.1: the add-on's page has its Install button (or its setup, once installed) under the lines.
                if (shown.art == com.hayeco.flipspaces.core.pointer.IntroArt.ADDON) {
                    addView(AddonPanel(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12f) })
                }
                // 1.1.9: a quieter line under the steps (the glasses setup's self-check).
                shown.note?.let { note ->
                    addView(c.padText(note, 13f, TEXT_99), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12f) })
                }
                if (!picture) addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
                // Page dots: the accent selected, faint white the others (0.5.78: 6 dp, they were 16 raw px). 1.1.9: with
                // Next (Done on the last page), so a page can be skipped without knowing the swipe; none for a single page.
                addView(FrameLayout(c).apply {
                    if (n > 1) addView(LinearLayout(c).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        for (k in 0 until n) {
                            addView(View(c).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(if (k == introPage) com.hayeco.flipspaces.core.Look.ACCENT else 0x33FFFFFF)
                                }
                            }, LinearLayout.LayoutParams(c.dp(6f), c.dp(6f)).apply {
                                marginStart = c.dp(4f)
                                marginEnd = c.dp(4f)
                            })
                        }
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                    val last = introPage == n - 1
                    addView(c.padText(if (last) "Done" else "Next", 14f, com.hayeco.flipspaces.core.Look.ACCENT, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(c.dp(12f), 0, c.dp(4f), 0)
                        setOnClickListener { if (last) closeIntro() else turn(1) }
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(32f)).apply { topMargin = c.dp(8f) })
            }
            addView(text, if (picture) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // A looping pager: a swipe sideways turns the page.
        page.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> introDownX = e.x
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - introDownX
                    if (n > 1 && kotlin.math.abs(dx) > c.dp(40f)) {
                        turn(if (dx < 0) 1 else -1)
                        intro.getChildAt(0)?.let { it.translationX = if (dx < 0) v.width.toFloat() else -v.width.toFloat(); it.animate().translationX(0f).setDuration(220L).start() }
                    }
                }
            }
            true
        }
        intro.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        intro.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_close)
            // 0.5.78: light on the dark card (its art is a dark grey for the old white page).
            setColorFilter(com.hayeco.flipspaces.core.Look.INK_2)
            contentDescription = "Close"
            setOnClickListener { closeIntro() }
        }, FrameLayout.LayoutParams(c.dp(30f), c.dp(30f), Gravity.TOP or Gravity.END).apply {
            topMargin = c.dp(10f)
            marginEnd = c.dp(10f)
        })
    }

    // ---- the dialog and the toast ----

    fun showDialog(message: String, ok: String = "OK", cancel: String = "Cancel", onOk: () -> Unit) {
        val c = context
        dialog.removeAllViews()
        dialog.addView(c.padText(message, 16f, WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(40f) })
        val buttons = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(c.padText(cancel, 16f, WHITE).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pad_button_dark)
            setOnClickListener { closeDialog() }
        }, LinearLayout.LayoutParams(0, c.dp(52f), 1f))
        buttons.addView(c.padText(ok, 16f, DARK_TEXT).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pad_button_light)
            setOnClickListener {
                closeDialog()
                onOk()
            }
        }, LinearLayout.LayoutParams(0, c.dp(52f), 1f).apply { marginStart = c.dp(12f) })
        dialog.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(40f) })
        mask.visibility = View.VISIBLE
        dialog.visibility = View.VISIBLE
        requestLayout()
    }

    fun closeDialog() {
        dialog.visibility = View.GONE
        if (popup.visibility != View.VISIBLE && intro.visibility != View.VISIBLE) mask.visibility = View.GONE
    }

    val dialogOpen: Boolean get() = dialog.visibility == View.VISIBLE

    private val hideToast = Runnable {
        toast.visibility = View.GONE
        toast.setOnClickListener(null)
        toast.isClickable = false
    }

    /**
     * 0.5.28: the toast with a one-tap action ([action] on a second line): a tap on it runs [onTap] and hides it.
     * Shown for [ACTION_TOAST_MS] (the wrapped Space's Curve note).
     */
    fun showAction(text: String, action: String, millis: Long = ACTION_TOAST_MS, onTap: () -> Unit) {
        toast.text = "$text\n$action"
        toast.visibility = View.VISIBLE
        toast.setOnClickListener {
            removeCallbacks(hideToast)
            hideToast.run()
            onTap()
        }
        removeCallbacks(hideToast)
        postDelayed(hideToast, millis)
        requestLayout()
    }

    /** The toast: hides after [TOAST_MS] 2 s. */
    fun showToast(text: String) {
        toast.setOnClickListener(null)
        toast.isClickable = false
        toast.text = text
        toast.visibility = View.VISIBLE
        removeCallbacks(hideToast)
        postDelayed(hideToast, TOAST_MS)
        requestLayout()
    }

    // ---- the screen-lock overlay ----

    /** 1.2.3 (issue 52): the lock is up as Pocket mode ([com.hayeco.flipspaces.core.pointer.PocketMode]). */
    var pocket = false
        private set

    fun showLock(show: Boolean, pocket: Boolean = false) {
        this.pocket = show && pocket
        lock.visibility = if (show) View.VISIBLE else View.GONE
        lockText.text = com.hayeco.flipspaces.core.pointer.PocketMode.hint(this.pocket)
        lockProgress(0f, false)
        requestLayout()
    }

    val locked: Boolean get() = lock.visibility == View.VISIBLE

    /** The hold's progress: the gradient brightens while pressed, the icon fills in. 1.2.3: in Pocket mode all black until held. */
    fun lockProgress(progress: Float, pressed: Boolean) {
        val hint = com.hayeco.flipspaces.core.pointer.PocketMode.showsHint(pocket, pressed)
        lockGlow.visibility = if (hint) View.VISIBLE else View.INVISIBLE
        lockText.visibility = if (hint) View.VISIBLE else View.INVISIBLE
        lockGradient.colors = intArrayOf(0x00FFFFFF, if (pressed) 0x40FFFFFF else 0x33FFFFFF)
        lockIcon.alpha = com.hayeco.flipspaces.core.pointer.PocketMode.iconAlpha(pocket, progress, pressed)
        lockIcon.scaleX = 1f + 0.1f * progress
        lockIcon.scaleY = 1f + 0.1f * progress
    }

    /** Closes the topmost overlay; false when none is open. */
    fun closeTop(): Boolean = when {
        textOpen -> { showText(false); true }
        dialog.visibility == View.VISIBLE -> { closeDialog(); true }
        intro.visibility == View.VISIBLE -> { glassesCheck?.skip() ?: closeIntro(); true }
        popup.visibility == View.VISIBLE -> { closePopup(); true }
        drawer.visibility == View.VISIBLE -> { closeDrawer(); true }
        else -> false
    }

    companion object {
        const val DRAWER_MS = 250L
        const val TOAST_MS = 2000L

        /** 1.1.15: the Menu's Ko-fi pill and update row ([menuRowTags]). */
        const val MENU_KO_FI = "menu:ko-fi"
        const val MENU_UPDATE = "menu:update"
        const val MENU_GLASSES_CHECK = "menu:glasses-check"
        const val GLASSES_CHECK_ROW = "Glasses setup check"

        /** Unreleased: the Google Play testers card and its buttons. */
        const val MENU_PLAY_TESTERS = "menu:play-testers"
        const val MENU_PLAY_TESTERS_JOIN = "menu:play-testers:join"
        const val MENU_PLAY_TESTERS_PLAY = "menu:play-testers:play"
        const val MENU_PLAY_TESTERS_NOT_NOW = "menu:play-testers:not-now"
        const val MENU_PLAY_TESTERS_ALREADY = "menu:play-testers:already"

        /** 0.5.28: a toast with a one-tap action stays this long. */
        const val ACTION_TOAST_MS = 5000L

        // 1.1.9: the intro's pages moved to core ([com.hayeco.flipspaces.core.pointer.IntroPages]), with the glasses setup
        // and Spatial Mouse pages.
    }
}
