package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.platform.AddonInstaller

/**
 * 1.2.1: the add-on step's line and buttons ([AddonSetup.step]), in the tutorial's add-on page and under the glasses
 * check's result: Install add-on with the download's progress, Allow installs, or the add-on's setup once it is
 * installed. It shows the step again as the download goes and when a Pocket XR screen gets the focus back (from Android's
 * install prompt or settings). Skipping is the card's own Next, Done or close mark.
 */
@SuppressLint("ViewConstructor")
internal class AddonPanel(context: Context) : LinearLayout(context) {
    private val changed: () -> Unit = { render() }

    /** The step shown now (the tests). */
    internal var step: AddonSetup.Step = AddonInstaller.step(context)
        private set

    init {
        orientation = VERTICAL
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AddonInstaller.listen(changed)
        render()
    }

    override fun onDetachedFromWindow() {
        AddonInstaller.unlisten(changed)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) return
        AddonInstaller.resumed(context)
        render()
    }

    internal fun render() {
        val c = context
        val s = AddonInstaller.step(c)
        step = s
        removeAllViews()
        addView(c.padText(s.line, 14f, if (s.done) Look.ACCENT else Look.INK_2).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, params(0f))
        s.progress?.let { p ->
            addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = p
                isIndeterminate = AddonInstaller.download.let { it is AddonSetup.Download.Running && it.percent == null }
                contentDescription = s.line
            }, params(8f))
        }
        for (b in s.buttons) button(b)
    }

    private fun button(b: AddonSetup.Button) {
        val c = context
        addView(c.padText(b.label, 15f, if (b.primary) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(14f), c.dp(8f), c.dp(14f), c.dp(8f))
            minHeight = c.dp(48f)
            setBackgroundResource(if (b.primary) R.drawable.bg_pad_button_light else R.drawable.bg_pad_button_dark)
            contentDescription = b.label
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                AddonInstaller.run(c, b.action)
                render()
            }
        }, params(8f))
    }

    private fun params(topDp: Float) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topDp)
    }

    /** The labels shown now, top down (the tests). */
    internal fun labels(): List<String> = (0 until childCount).mapNotNull { (getChildAt(it) as? TextView)?.text?.toString() }
}
