package com.hayeco.flipspaces.pcscreen

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hayeco.flipspaces.R

/**
 * The wake relay's screen ([WakeRelayService]), from the trackpad Menu: what it is for, one switch, and the two things a
 * relay phone needs (Tailscale on, and leave to run in the background).
 */
class WakeRelayActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun text(s: String, size: Float, color: Int = Color.LTGRAY) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            setPadding(0, pad / 3, 0, pad / 3)
        }
        fun button(s: String, tap: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { tap() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(text(getString(R.string.wake_relay_title), 24f, Color.WHITE))
            addView(text(getString(R.string.wake_relay_explain), 16f))
            toggle = button("") {
                WakeRelayService.set(this@WakeRelayActivity, !WakeRelayService.isOn(this@WakeRelayActivity))
                refresh()
            }
            addView(toggle)
            status = text("", 15f)
            addView(status)
            addView(button(getString(R.string.wake_relay_battery)) {
                // The battery settings list (asking directly needs a permission Google Play restricts).
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
            addView(button(getString(R.string.pc_screen_away_button)) { PcScreenRemote.openTailscale(this@WakeRelayActivity) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = WakeRelayService.isOn(this)
        toggle.text = getString(if (on) R.string.wake_relay_turn_off else R.string.wake_relay_turn_on)
        val ts = WakeRelay.ownTailscaleAddress()
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = listOf(
            getString(if (on) R.string.wake_relay_status_on else R.string.wake_relay_status_off),
            if (ts != null) getString(R.string.wake_relay_tailscale_on, ts) else getString(R.string.wake_relay_tailscale_off),
            getString(if (battery) R.string.wake_relay_battery_ok else R.string.wake_relay_battery_needed),
        ).joinToString("\n\n")
    }
}
