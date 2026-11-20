package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.ui.LookType

/**
 * PC screens: the setup page's parts in Pocket XR's look ([Look]: glass cards, the accent blue, Inter), so the page
 * reads as part of the app (Mike, 19 Nov 2026: the plain buttons were "really ugly").
 */
internal class PcScreenSetupKit(private val c: Context) {
    fun dp(v: Float) = (v * c.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * c.resources.displayMetrics.density

    fun shape(fill: Int, radiusDp: Float = Look.RADIUS_M, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(fill)
        if (stroke != null) setStroke(dp(Look.RIM_DP), stroke)
    }

    /** A pressable surface: [fill] with a white ripple. */
    fun pressable(fill: Int, radiusDp: Float = Look.RADIUS_M, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(fill, radiusDp, stroke), shape(0xFFFFFFFF.toInt(), radiusDp))

    fun text(s: CharSequence, sizeSp: Float, color: Int = Look.INK, medium: Boolean = false) = TextView(c).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = if (medium) LookType.medium else LookType.regular
        includeFontPadding = false
        setLineSpacing(0f, 1.2f)
    }

    /** A small heading over a section, in capitals. */
    fun section(s: String) = text(s.uppercase(), Look.TYPE_CAPTION, Look.INK_3, medium = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4f), dp(22f), 0, dp(8f))
    }

    /** The main action: accent fill, white text, 52 dp. */
    fun primary(s: String, tap: () -> Unit) = text(s, Look.TYPE_LABEL, 0xFFFFFFFF.toInt(), medium = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52f)
        setPadding(dp(18f), 0, dp(18f), 0)
        background = pressable(Look.ACCENT)
        isClickable = true
        setOnClickListener { tap() }
    }

    /** A quieter action: raised glass with a rim. */
    fun secondary(s: String, tap: () -> Unit) = text(s, Look.TYPE_BODY + 1, Look.INK, medium = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(46f)
        setPadding(dp(16f), 0, dp(16f), 0)
        background = pressable(Look.GLASS_RAISED, stroke = Look.RIM)
        isClickable = true
        setOnClickListener { tap() }
    }

    /** A text link, for something rarely wanted (Forget this PC). */
    fun link(s: String, color: Int, tap: () -> Unit) = text(s, Look.TYPE_BODY, color, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
        background = pressable(0x00000000, Look.RADIUS_S)
        isClickable = true
        setOnClickListener { tap() }
    }

    /** A PC to tap: the monitor glyph, its name, a line under it, and a chevron. */
    fun pcRow(name: String, detail: String, enabled: Boolean, tap: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72f)
        setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        background = pressable(Look.CARD, Look.RADIUS_L, Look.RIM)
        alpha = if (enabled) 1f else 0.55f
        isClickable = true
        setOnClickListener { tap() }
        addView(MonitorGlyph(c, if (enabled) Look.ACCENT else Look.INK_3), LinearLayout.LayoutParams(dp(40f), dp(40f)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, dp(8f), 0)
            addView(text(name, Look.TYPE_LABEL + 1, Look.INK, medium = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text(detail, Look.TYPE_BODY - 1, Look.INK_2).apply { setPadding(0, dp(4f), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 28f, Look.INK_3))
    }

    /** A row of choices, one lit: [labels] shown, [selected] lit, [pick] told the index tapped. */
    fun segmented(labels: List<String>, selected: Int, pick: (Int) -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(Look.PAD, Look.RADIUS_M, Look.RIM)
        setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        labels.forEachIndexed { i, s ->
            val on = i == selected
            addView(text(s, Look.TYPE_BODY, if (on) 0xFFFFFFFF.toInt() else Look.INK_2, medium = on).apply {
                gravity = Gravity.CENTER
                minHeight = dp(38f)
                background = if (on) shape(Look.ACCENT_DEEP, Look.RADIUS_S + 2) else pressable(0x00000000, Look.RADIUS_S + 2)
                isClickable = true
                setOnClickListener { pick(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** A labelled setting: its name over its choices. */
    fun setting(name: String, choices: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10f), 0, dp(4f))
        addView(text(name, Look.TYPE_BODY, Look.INK_2).apply { setPadding(dp(4f), 0, 0, dp(8f)) })
        addView(choices)
    }

    /** A section that opens and closes: a row with its title and a turning chevron, over [body]. */
    fun foldable(title: String, body: View): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        body.visibility = View.GONE
        val chevron = text("›", 24f, Look.INK_3).apply { rotation = 90f }
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(16f), 0, dp(16f), 0)
            background = pressable(Look.GLASS, Look.RADIUS_M, Look.RIM)
            isClickable = true
            addView(text(title, Look.TYPE_LABEL, Look.INK, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) 270f else 90f).setDuration(Look.ENTER_MS).start()
            }
        })
        addView(body)
    }
}

/** A monitor on its stand, the PC screen's glyph (as the launcher's PC screen button). */
internal class MonitorGlyph(context: Context, private val color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val box = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        stroke.strokeWidth = box * 0.08f
        stroke.color = color
        val w = box * 0.8f
        val h = box * 0.52f
        val top = cy - box * 0.36f
        canvas.drawRoundRect(RectF(cx - w / 2f, top, cx + w / 2f, top + h), box * 0.08f, box * 0.08f, stroke)
        canvas.drawLine(cx, top + h, cx, top + h + box * 0.16f, stroke)
        canvas.drawLine(cx - box * 0.2f, top + h + box * 0.16f, cx + box * 0.2f, top + h + box * 0.16f, stroke)
    }
}
