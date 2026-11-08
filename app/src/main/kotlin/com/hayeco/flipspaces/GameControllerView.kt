package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.hayeco.flipspaces.core.pointer.ControllerState
import com.hayeco.flipspaces.core.pointer.GameControllerPresses
import com.hayeco.flipspaces.core.pointer.PadControlPlace

/**
 * 1.2.3 (issue 59): one cluster of the Game controller ([com.hayeco.flipspaces.core.pointer.GameControllerLayout]) on the
 * open phone's trackpad: buttons drawn lit while held, a stick as a ring with its knob where the thumb steers it. Every
 * finger is tracked on its own ([GameControllerPresses]); [onChange] is called when anything a game reads changed.
 */
@SuppressLint("ViewConstructor")
internal class GameControllerView(context: Context, private val places: List<PadControlPlace>, private val onChange: () -> Unit) : View(context) {
    private val presses = GameControllerPresses(places)
    private val gap = context.dpf(5f)
    private val radius = context.dpf(12f)
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C3D7BFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = context.dpf(14f)
    }

    init {
        contentDescription = places.joinToString(", ") { it.control.label }
    }

    /** What this cluster's fingers hold now. */
    fun state(): ControllerState = presses.state()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        presses.aspect = if (h > 0) w.toFloat() / h else 1f
    }

    override fun onDraw(canvas: Canvas) {
        val held = presses.held
        for (p in places) {
            rect.set(p.x * width + gap, p.y * height + gap, (p.x + p.w) * width - gap, (p.y + p.h) * height - gap)
            if (p.control.stick) {
                val r = minOf(rect.width(), rect.height()) / 2f
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, ring)
                val (tx, ty) = presses.tilt(p.control)
                val knob = r * 0.42f
                canvas.drawCircle(rect.centerX() + tx * (r - knob), rect.centerY() + ty * (r - knob), knob, if (p.control in held) lit else fill)
            } else {
                canvas.drawRoundRect(rect, radius, radius, if (p.control in held) lit else fill)
                canvas.drawText(p.control.label, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val before = presses.held
        val changed = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                presses.down(e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
            }
            MotionEvent.ACTION_MOVE -> (0 until e.pointerCount).map { presses.move(e.getPointerId(it), e.getX(it) / w, e.getY(it) / h) }.any { it }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> presses.up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> presses.cancel()
            else -> false
        }
        if ((presses.held - before).isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (changed) {
            onChange()
            invalidate()
        }
        return true
    }

    /** Lets go of everything (the controller hidden, the trackpad paused). */
    fun releaseAll() {
        if (presses.cancel()) onChange()
        invalidate()
    }
}
