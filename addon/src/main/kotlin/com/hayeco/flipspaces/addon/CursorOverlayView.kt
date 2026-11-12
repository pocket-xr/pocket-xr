package com.hayeco.flipspaces.addon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.withTranslation
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.DockLayout
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.spatial.CursorWindow
import com.hayeco.flipspaces.core.windows.WindowZone

/**
 * Everything Pocket XR draws over the apps on the glasses: the cursor, a hint for what a
 * pinch would do where the cursor is, the outline of a window being moved or resized, hand
 * status, and the dock. Black is see-through on the glasses, so it only draws light strokes.
 * The window is not touchable; injected touches pass straight through to the apps.
 *
 * 1.1.0: in the add-on. Pocket XR sends what to draw over the link ([LinkService]) as plain values, and hit tests
 * the dock itself with the same [DockLayout].
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context, private val density: Float) : View(context) {
    data class DockItem(val label: String, val icon: Bitmap?)

    /** The hand as Pocket XR last reported it: null when no hand provider runs. */
    data class Hand(val present: Boolean, val pinchProgress: Float, val openPalm: Boolean)

    private var cursor: Point? = null
    private var pressed = false
    private var hover: WindowZone? = null
    private var preview: Bounds? = null
    private var hand: Hand? = null
    private var source: String = ""
    private var banner: String? = null
    private var bannerUntil = 0L
    private var dock: List<DockItem> = emptyList()

    private fun dp(v: Float) = v * density

    private val accent = Color.rgb(90, 220, 255)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = accent
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(6f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 30, 40) }
    private val dockBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 18, 24, 32) }
    private val dockHot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = accent }

    /**
     * 0.5.0: true while the Space is up. It draws its own cursor per hand, so the DeX cursor, the
     * hand status and the DeX pinch hint stay hidden; banners still show.
     */
    var spaceUp = false
        set(value) {
            if (field == value) return
            field = value
            updateWindow()
            invalidate()
        }

    /**
     * 0.5.16: while the Space is up the window is hidden (its root view
     * GONE, so the window has no visible surface) unless a banner, the dock or a preview is on it.
     * 0.5.15 kept this full screen window over the Space with nothing drawn: it took a hardware
     * plane on the glasses while the Space itself was composed on the GPU.
     */
    private fun updateWindow() {
        val bannerOn = banner != null && SystemClock.uptimeMillis() < bannerUntil
        val want = if (CursorWindow.shown(spaceUp, bannerOn, dock.isNotEmpty(), preview != null)) VISIBLE else GONE
        if (visibility != want) visibility = want
    }

    fun showCursor(at: Point, zone: WindowZone?, isPressed: Boolean) {
        cursor = at
        hover = zone
        pressed = isPressed
        invalidate()
    }

    fun showPreview(bounds: Bounds?) {
        preview = bounds
        updateWindow()
        invalidate()
    }

    fun showHand(status: Hand?, sourceLabel: String) {
        if (status?.present == true && hand?.present != true && !spaceUp) {
            flash(context.getString(R.string.addon_hand_tip), 5000)
        }
        hand = status
        source = sourceLabel
        invalidate()
    }

    fun flash(message: String, millis: Long = 3500) {
        banner = message
        bannerUntil = SystemClock.uptimeMillis() + millis
        updateWindow()
        invalidate()
        postDelayed({ updateWindow(); invalidate() }, millis + 50)
    }

    fun showDock(items: List<DockItem>) {
        dock = items
        updateWindow()
        invalidate()
    }

    private val location = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        // Everything is in display coordinates; undo any offset the window got from the system.
        getLocationOnScreen(location)
        canvas.withTranslation(-location[0].toFloat(), -location[1].toFloat()) { drawContent(this) }
    }

    private fun drawContent(canvas: Canvas) {
        preview?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), outline) }
        drawDock(canvas)
        drawStatus(canvas)
        if (spaceUp) return
        val c = cursor ?: return
        val r = dp(11f)
        val progress = if (pressed) 1f else hand?.pinchProgress ?: 0f
        if (progress > 0f) {
            fill.alpha = (80 + 175 * progress).toInt()
            canvas.drawCircle(c.x, c.y, r * (0.35f + 0.65f * progress), fill)
        }
        ring.color = if (hand?.present == false) Color.GRAY else Color.WHITE
        canvas.drawCircle(c.x, c.y, r, ring)
        hint()?.let { drawChip(canvas, it, c.x + r + dp(6f), c.y + r + dp(14f)) }
    }

    private fun hint(): String? {
        val zone = hover ?: return null
        val held = pressed
        return when {
            zone == WindowZone.TITLE_BAR -> context.getString(if (held) R.string.addon_hint_moving else R.string.addon_hint_hold_move)
            zone.isCorner -> context.getString(if (held) R.string.addon_hint_resizing else R.string.addon_hint_hold_resize)
            zone.isResize -> context.getString(if (held) R.string.addon_hint_resizing else R.string.addon_hint_hold_resize)
            held -> null
            hand?.openPalm == true -> context.getString(R.string.addon_hint_dock)
            else -> null
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val y = dp(22f)
        val status = when {
            hand == null || spaceUp -> null
            hand?.present == true -> context.getString(R.string.addon_status_hand, source)
            else -> context.getString(R.string.addon_status_no_hand, source)
        }
        status?.let { drawChip(canvas, it, dp(16f), y) }
        val b = banner
        if (b != null && SystemClock.uptimeMillis() < bannerUntil) {
            val w = text.measureText(b)
            drawChip(canvas, b, (width - w) / 2f, y)
        }
    }

    private fun drawChip(canvas: Canvas, label: String, x: Float, baseline: Float) {
        val w = text.measureText(label)
        val pad = dp(6f)
        canvas.drawRoundRect(x - pad, baseline - text.textSize - pad / 2, x + w + pad, baseline + pad, dp(8f), dp(8f), chip)
        canvas.drawText(label, x, baseline, text)
    }

    private fun drawDock(canvas: Canvas) {
        if (dock.isEmpty()) return
        val boxes = DockLayout.boxes(dock.size, width, height, density)
        val gap = dp(14f)
        val first = boxes.first()
        val last = boxes.last()
        canvas.drawRoundRect(first.left - gap, first.top - gap, last.right + gap, first.bottom + gap + dp(18f), dp(18f), dp(18f), dockBg)
        dock.forEachIndexed { i, item ->
            val b = boxes[i]
            val rect = RectF(b.left, b.top, b.right, b.bottom)
            val icon = item.icon
            if (icon != null) {
                canvas.drawBitmap(icon, null, rect, null)
            } else {
                canvas.drawRoundRect(rect, dp(12f), dp(12f), chip)
                val initial = item.label.take(1)
                canvas.drawText(initial, rect.centerX() - text.measureText(initial) / 2, rect.centerY() + text.textSize / 3, text)
            }
            val c = cursor
            if (c != null && rect.contains(c.x, c.y)) {
                canvas.drawRoundRect(rect, dp(12f), dp(12f), dockHot)
                val w = text.measureText(item.label)
                canvas.drawText(item.label, rect.centerX() - w / 2, rect.bottom + dp(16f), text)
            }
        }
    }
}
