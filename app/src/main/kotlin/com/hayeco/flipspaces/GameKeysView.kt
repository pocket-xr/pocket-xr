package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.hayeco.flipspaces.core.pointer.GameKey
import com.hayeco.flipspaces.core.pointer.GameKeyChange
import com.hayeco.flipspaces.core.pointer.GameKeyPresses

/**
 * 1.2.3 (issue 59): one cluster of Game keys ([com.hayeco.flipspaces.core.pointer.GameKeyLayout]) on the open phone's
 * trackpad. Every finger on it is tracked on its own ([GameKeyPresses]), each key change goes to [onChange] at once, and a
 * held key is drawn lit. A tick buzzes on each key down, so a thumb feels which key it found.
 */
@SuppressLint("ViewConstructor")
internal class GameKeysView(context: Context, private val keys: List<GameKey>, private val onChange: (GameKeyChange) -> Unit) : View(context) {
    private val presses = GameKeyPresses(keys)
    private val gap = context.dpf(5f)
    private val radius = context.dpf(12f)
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C3D7BFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = context.dpf(15f)
    }

    init {
        contentDescription = keys.joinToString(", ") { it.label }
    }

    override fun onDraw(canvas: Canvas) {
        val held = presses.held
        for (k in keys) {
            rect.set(k.x * width + gap, k.y * height + gap, (k.x + k.w) * width - gap, (k.y + k.h) * height - gap)
            canvas.drawRoundRect(rect, radius, radius, if (k in held) lit else fill)
            canvas.drawText(k.label, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val changes = ArrayList<GameKeyChange>()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                changes += presses.down(e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) changes += presses.move(e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> changes += presses.up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> changes += presses.cancel()
        }
        if (changes.any { it.down }) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        changes.forEach(onChange)
        if (changes.isNotEmpty()) invalidate()
        return true
    }

    /** Lets go of every held key (the keys hidden, the trackpad paused). */
    fun releaseAll() {
        presses.cancel().forEach(onChange)
        invalidate()
    }
}
