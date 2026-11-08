package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.spatial.FollowMode
import com.hayeco.flipspaces.core.spatial.GlassesCheck
import com.hayeco.flipspaces.core.spatial.GlassesSetupPage
import com.hayeco.flipspaces.core.spatial.SetupHost

/** What the check card needs from the trackpad. */
interface GlassesCheckHost {
    /** Pocket XR's head yaw now, degrees right positive; null while the Space is not drawing. */
    fun headYawDeg(): Float?
    fun follow(): FollowMode

    /** Steady mode is on, so the head pose does not turn. */
    fun steady(): Boolean

    /** The check ended with [result], or was skipped or closed (null). */
    fun checkFinished(result: GlassesCheck.CheckResult?)
}

/**
 * 1.1.16 (issue 12): the glasses setup check, one card in the trackpad tutorial's frame: the model's steps and what on
 * the host can also turn the picture ([GlassesSetupPage]), then the 10 second head turn ([GlassesCheck.TurnMeter]) and two
 * questions, then a pass or the one setting to change ([GlassesCheck.result]). Every button is at least 48 dp tall and is
 * read as a button; the countdown and the result are read out as they change.
 */
@SuppressLint("ViewConstructor")
internal class GlassesCheckCard(
    context: Context,
    private val page: GlassesSetupPage,
    /** One quiet line on how the glasses were recognised ("XREAL 1S, USB 3318:043E"), or null. */
    private val seenAs: String?,
    private val host: SetupHost,
    private val checkHost: GlassesCheckHost,
    private val close: () -> Unit,
) : FrameLayout(context) {
    private enum class Step { SETUP, TURNING, ASK_STAYED, ASK_WHAT, RESULT }

    private var step = Step.SETUP
    private var meter = GlassesCheck.TurnMeter()
    private var answers = GlassesCheck.Answers(null)
    private var result: GlassesCheck.CheckResult? = null
    private var startedMs = 0L
    private var finished = false

    /** The head turn's samples run on the main thread, attached or not. */
    private val ticks = android.os.Handler(android.os.Looper.getMainLooper())

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20f), context.dp(18f), context.dp(20f), context.dp(16f))
    }

    init {
        setBackgroundResource(R.drawable.bg_pad_dialog)
        clipToOutline = true
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Look.CARD)
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Look.INK_2)
            contentDescription = "Skip the glasses check"
            val p = context.dp(12f)
            setPadding(p, p, p, p)
            setOnClickListener { finish(null) }
        }, LayoutParams(context.dp(48f), context.dp(48f), Gravity.TOP or Gravity.END).apply {
            topMargin = context.dp(2f)
            marginEnd = context.dp(2f)
        })
        build()
    }

    private val tickRun = object : Runnable {
        override fun run() {
            if (step != Step.TURNING) return
            checkHost.headYawDeg()?.let(meter::add)
            val left = GlassesCheck.DURATION_MS - (android.os.SystemClock.uptimeMillis() - startedMs)
            if (left <= 0) {
                val turn = meter.turned()
                if (turn == null) {
                    show(GlassesCheck.result(page.family, host, null, answers, checkHost.follow(), checkHost.steady()))
                } else {
                    step = Step.ASK_STAYED
                    build()
                }
                return
            }
            updateTurning(left)
            ticks.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onDetachedFromWindow() {
        ticks.removeCallbacks(tickRun)
        super.onDetachedFromWindow()
    }

    private var countdown: TextView? = null
    private var seen: TextView? = null

    private fun build() {
        val c = context
        body.removeAllViews()
        countdown = null
        seen = null
        when (step) {
            Step.SETUP -> {
                title(page.title)
                seenAs?.let { body.addView(c.padText(it, 12f, TEXT_QUIET), params(4f)) }
                page.steps.forEachIndexed { i, s -> numbered(i + 1, s) }
                if (page.steps.isEmpty()) body.addView(c.padText(NOTHING_TO_SET, 14f, Look.INK_2), params(10f))
                for (note in page.hostNotes) body.addView(c.padText(note, 13f, Look.INK_2).apply {
                    setPadding(c.dp(12f), c.dp(10f), c.dp(12f), c.dp(10f))
                    background = GradientDrawable().apply {
                        cornerRadius = c.dpf(10f)
                        setStroke(c.dp(1f), Look.RIM)
                    }
                }, params(12f))
                body.addView(c.padText("Then a 10 second check: turn your head and say what the windows did.", 13f, TEXT_QUIET), params(14f))
                button("Start the check", primary = true) { startTurning() }
                button("Skip") { finish(null) }
            }
            Step.TURNING -> {
                title(GlassesCheck.TITLE)
                body.addView(c.padText(GlassesCheck.TURN_PROMPT, 14f, Look.INK_2), params(10f))
                countdown = c.padText("10", 40f, Look.INK, bold = true).apply {
                    gravity = Gravity.CENTER
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                body.addView(countdown, params(16f))
                seen = c.padText("", 13f, TEXT_QUIET).apply { gravity = Gravity.CENTER }
                body.addView(seen, params(6f))
                button("Stop") {
                    ticks.removeCallbacks(tickRun)
                    step = Step.SETUP
                    build()
                }
                updateTurning(GlassesCheck.DURATION_MS)
            }
            Step.ASK_STAYED -> {
                title(GlassesCheck.TITLE)
                body.addView(c.padText(GlassesCheck.Q_STAYED, 16f, Look.INK), params(12f))
                button("Yes, they stayed put", primary = true) { answer(GlassesCheck.Answers(stayed = true)) }
                button("No") {
                    answers = GlassesCheck.Answers(stayed = false)
                    step = Step.ASK_WHAT
                    build()
                }
            }
            Step.ASK_WHAT -> {
                title(GlassesCheck.TITLE)
                body.addView(c.padText(GlassesCheck.Q_WHAT, 16f, Look.INK), params(12f))
                for (m in GlassesCheck.Moved.entries) button(m.label) { answer(GlassesCheck.Answers(stayed = false, moved = m)) }
            }
            Step.RESULT -> {
                val r = result ?: return
                title(if (r.passed) "All set" else "One thing to change")
                body.addView(c.padText(r.text, 15f, Look.INK_2).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }, params(12f))
                if (!r.passed) button("Check again", primary = true) { startTurning() }
                // 1.2.1: once the glasses are set, the optional add-on (the phone's notifications and the pointer), until
                // it is installed with notification access on. Done skips it.
                if (r.passed && addonShown()) {
                    body.addView(c.padText(AddonSetup.TITLE, 16f, Look.INK, bold = true), params(18f))
                    body.addView(c.padText(AddonSetup.WHAT, 13f, Look.INK_2), params(6f))
                    body.addView(AddonPanel(c), params(8f))
                }
                button("Done", primary = r.passed && !addonShown()) { finish(r) }
            }
        }
        announceForAccessibility((body.getChildAt(0) as? TextView)?.text)
    }

    private fun addonShown(): Boolean {
        val installed = com.hayeco.flipspaces.platform.AddonLink.isInstalled(context)
        return AddonSetup.showAfterCheck(installed, installed && com.hayeco.flipspaces.phone.PhoneNotifications.hasAccess(context))
    }

    private fun startTurning() {
        meter = GlassesCheck.TurnMeter()
        answers = GlassesCheck.Answers(null)
        result = null
        startedMs = android.os.SystemClock.uptimeMillis()
        step = Step.TURNING
        build()
        ticks.removeCallbacks(tickRun)
        ticks.post(tickRun)
    }

    private fun updateTurning(leftMs: Long) {
        countdown?.text = ((leftMs + 999) / 1000).coerceIn(0, 10).toString()
        countdown?.contentDescription = "${countdown?.text} seconds left"
        val t = meter.turned()
        seen?.text = if (t == null) "Waiting for the Space's head tracking" else
            "Pocket XR sees ${t.leftDeg.toInt()} degrees left, ${t.rightDeg.toInt()} right"
    }

    private fun answer(a: GlassesCheck.Answers) {
        answers = a
        show(GlassesCheck.result(page.family, host, meter.turned(), a, checkHost.follow(), checkHost.steady()))
    }

    private fun show(r: GlassesCheck.CheckResult?) {
        r ?: return
        result = r
        step = Step.RESULT
        build()
    }

    /** Back, or the close mark: skipped (a result already reached still counts). */
    fun skip() = finish(null)

    private fun finish(r: GlassesCheck.CheckResult?) {
        if (finished) return
        finished = true
        ticks.removeCallbacks(tickRun)
        // A check that reached a result counts, even if closed from the result screen.
        checkHost.checkFinished(r ?: result)
        close()
    }

    private fun title(text: String) {
        body.addView(context.padText(text, 20f, Look.INK, bold = true).apply {
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(40f) })
    }

    private fun numbered(n: Int, text: String) {
        val c = context
        body.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(c.padText("$n", 10f, 0xFFFFFFFF.toInt()).apply {
                setBackgroundResource(R.drawable.bg_intro_number)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(c.dp(15f), c.dp(15f)).apply { topMargin = c.dp(2f) })
            addView(c.padText(text, 14f, Look.INK_2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(8f) })
            contentDescription = "Step $n: $text"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, params(10f))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) {
        val c = context
        body.addView(c.padText(label, 15f, if (primary) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(14f), c.dp(8f), c.dp(14f), c.dp(8f))
            minHeight = c.dp(52f)
            setBackgroundResource(if (primary) R.drawable.bg_pad_button_light else R.drawable.bg_pad_button_dark)
            contentDescription = label
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }, params(10f))
    }

    private fun params(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topDp)
    }

    /** The labels shown now, top down (the tests). */
    internal fun labels(): List<String> = (0 until body.childCount).mapNotNull { (body.getChildAt(it) as? TextView)?.text?.toString() }

    /** Presses the button labelled [label] (the tests). */
    internal fun press(label: String): Boolean {
        for (i in 0 until body.childCount) {
            val v = body.getChildAt(i) as? TextView ?: continue
            if (v.text.toString() == label && v.hasOnClickListeners()) return v.performClick()
        }
        return false
    }

    private companion object {
        const val SAMPLE_MS = 50L
        const val TEXT_QUIET = 0x99FFFFFF.toInt()
        const val NOTHING_TO_SET = com.hayeco.flipspaces.core.spatial.GlassesSetup.NOTHING_TO_SET
    }
}
