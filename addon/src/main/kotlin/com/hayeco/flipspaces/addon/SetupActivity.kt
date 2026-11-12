package com.hayeco.flipspaces.addon

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.hayeco.flipspaces.core.Disclosure
import com.hayeco.flipspaces.core.Look

/**
 * 1.1.11 (Google Play): the add-on's own screen, and the prominent disclosure and consent before either of its services
 * is switched on. Pocket XR opens it at one disclosure ([EXTRA_KIND]: "pointer" or "notes"); from the launcher it shows
 * both. "Agree and turn on" opens Android's page for that service, where the user switches it on; "No thanks" leaves it
 * off. The texts are [Disclosure]'s.
 */
class SetupActivity : Activity() {
    private lateinit var column: LinearLayout

    @android.annotation.SuppressLint("DiscouragedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Core's disclosure words in the phone's language: core names a string in this add-on's strings.xml.
        val res = applicationContext.resources
        com.hayeco.flipspaces.core.UiText.lookup = { key ->
            res.getIdentifier(key, "string", packageName).takeIf { it != 0 }?.let { res.getString(it) }
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Look.PAGE)
            fitsSystemWindows = true
            addView(column, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(text(getString(R.string.app_name), 26f, Look.INK, bold = true))
        column.addView(text(getString(R.string.addon_setup_intro), 15f, Look.INK_2), gap(16))
        val only = kindOf(intent)
        for (kind in Disclosure.Kind.entries) if (only == null || only == kind) column.addView(card(kind), gap(16))
    }

    private fun card(kind: Disclosure.Kind): View {
        val t = Disclosure.text(kind)
        val on = isOn(kind)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Look.CARD)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Look.RIM)
            }
            addView(text(t.title, 19f, Look.INK, bold = true))
            addView(text(t.intro, 15f, Look.INK), gap(10))
            addView(text(getString(R.string.addon_setup_it_reads), 15f, Look.INK, bold = true), gap(10))
            for (line in t.reads) addView(text("•  $line", 15f, Look.INK), gap(4))
            addView(text(t.use, 15f, Look.INK), gap(10))
            addView(text(t.sharing, 15f, Look.INK, bold = true), gap(10))
            addView(text(t.withoutIt, 14f, Look.INK_2), gap(10))
            if (on) {
                addView(text(getString(R.string.addon_setup_on), 15f, Look.ACCENT, bold = true), gap(14))
                addView(button(getString(R.string.addon_setup_open_settings), filled = false) { openSettings(kind) }, gap(8))
            } else {
                addView(button(Disclosure.AGREE, filled = true) { answer(kind, agreed = true) }, gap(14))
                addView(button(Disclosure.DECLINE, filled = false) { answer(kind, agreed = false) }, gap(8))
            }
        }
    }

    private fun answer(kind: Disclosure.Kind, agreed: Boolean) {
        Log.i(TAG, "disclosure ${kind.name}: ${if (agreed) "agreed" else "declined"}")
        when (Disclosure.after(agreed)) {
            Disclosure.Step.OPEN_SETTINGS -> openSettings(kind)
            Disclosure.Step.STAY_OFF -> if (kindOf(intent) != null) finish()
        }
    }

    private fun openSettings(kind: Disclosure.Kind) {
        val own = ComponentName(this, if (kind == Disclosure.Kind.POINTER) PointerAccess::class.java else NotesListener::class.java)
        val (details, list) = if (kind == Disclosure.Kind.POINTER) {
            Intent(DETAILS_ACTION).putExtra(Intent.EXTRA_COMPONENT_NAME, own) to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, own.flattenToString()) to
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        runCatching { startActivity(details) }.recoverCatching { startActivity(list) }
            .onFailure { Log.w(TAG, "no settings page for ${kind.name}: ${it.message}") }
    }

    private fun isOn(kind: Disclosure.Kind): Boolean = when (kind) {
        Disclosure.Kind.POINTER -> getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == packageName } == true
        Disclosure.Kind.NOTES -> NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else Look.ACCENT)
        gravity = Gravity.CENTER
        minHeight = dp(52)
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            if (filled) setColor(Look.ACCENT) else setStroke(dp(1), Look.ACCENT)
        }
        setOnClickListener { onClick() }
    }

    private fun gap(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "PocketXRAddon"

        /** Which disclosure to show: [KIND_POINTER] or [KIND_NOTES]; both when absent. */
        const val EXTRA_KIND = "kind"
        const val KIND_POINTER = "pointer"
        const val KIND_NOTES = "notes"

        /** The hidden Settings action for one service's page (AOSP AccessibilityDetailsSettingsActivity). */
        private const val DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

        fun kindOf(intent: Intent?): Disclosure.Kind? = when (intent?.getStringExtra(EXTRA_KIND)) {
            KIND_POINTER -> Disclosure.Kind.POINTER
            KIND_NOTES -> Disclosure.Kind.NOTES
            else -> null
        }
    }
}
