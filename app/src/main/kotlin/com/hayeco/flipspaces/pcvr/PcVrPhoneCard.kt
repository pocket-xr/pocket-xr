package com.hayeco.flipspaces.pcvr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The phone's screen while PC VR is on the glasses: one large "Back to your Space" button. The screen goes dark to keep
 * the phone cool ([PcVrSession]); a touch anywhere lights it again for a while.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PcVrPhoneCard(context: Context) : FrameLayout(context) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        setBackgroundColor(Color.BLACK)
        visibility = GONE
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f).toInt(), 0, dp(24f).toInt(), 0)
        }
        column.addView(TextView(context).apply {
            text = "PC VR is on your glasses"
            setTextColor(Color.rgb(127, 168, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
        })
        val back = TextView(context).apply {
            text = "Back to your Space"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(76f).toInt()
            setPadding(dp(36f).toInt(), 0, dp(36f).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(38f)
                setColor(Color.rgb(28, 95, 216))
            }
            isClickable = true
            setOnClickListener { PcVrSession.close("the phone's Back to your Space button") }
        }
        column.addView(back, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20f).toInt()
        })
        column.addView(TextView(context).apply {
            text = "Or hold Home or Capture on a Joy-Con for 2 seconds."
            setTextColor(Color.rgb(140, 150, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18f).toInt()
        })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private var shownAtMs = 0L
    private var gestureCounts = false

    /**
     * Every touch lights the screen again; the button then works as usual. Nothing reaches the trackpad below. A touch
     * that began before the card showed, or in its first [IGNORE_MS], presses nothing: the tap that opened PC VR from
     * the trackpad must not land on "Back to your Space" as the card appears under it.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            PcVrSession.wakePhone()
            gestureCounts = android.os.SystemClock.uptimeMillis() - shownAtMs >= IGNORE_MS
        }
        if (gestureCounts) super.dispatchTouchEvent(ev)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    fun show(open: Boolean) {
        if (open && visibility != VISIBLE) {
            shownAtMs = android.os.SystemClock.uptimeMillis()
            gestureCounts = false
        }
        visibility = if (open) VISIBLE else GONE
    }

    private companion object {
        const val IGNORE_MS = 1_000L
    }
}
