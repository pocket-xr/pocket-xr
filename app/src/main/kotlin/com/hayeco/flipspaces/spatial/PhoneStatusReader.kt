package com.hayeco.flipspaces.spatial

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.PhoneStatus
import java.util.concurrent.Executors

/**
 * 0.5.100: the phone's battery, cell signal and Wi-Fi for the launcher's status row ([PhoneStatus]). Read on its own
 * thread at most every [EVERY_MS] while the launcher asks ([current], from the GL thread, never blocks). Needs no new
 * permission: the battery's sticky broadcast, TelephonyManager.getSignalStrength and the networks' capabilities
 * (ACCESS_NETWORK_STATE, already held).
 */
internal class PhoneStatusReader(private val context: Context) {
    private val tag = "FlipSpaces/Status"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "phone-status") }
    @Volatile private var last = PhoneStatus()
    @Volatile private var readMs = Long.MIN_VALUE / 2
    @Volatile private var reading = false
    private var failedOnce = false

    /** The latest reading; asks for a fresh one when it is older than [EVERY_MS]. */
    fun current(): PhoneStatus {
        val now = SystemClock.elapsedRealtime()
        if (!reading && now - readMs >= EVERY_MS) {
            reading = true
            readMs = now
            runCatching { worker.execute { last = read(); reading = false } }.onFailure { reading = false }
        }
        return last
    }

    fun stop() {
        worker.shutdownNow()
    }

    private fun read(): PhoneStatus = try {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = battery?.let { PhoneStatus.batteryPct(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val tm = context.getSystemService(TelephonyManager::class.java)
        val cell = if (tm != null && tm.simState == TelephonyManager.SIM_STATE_READY) PhoneStatus.cellBars(tm.signalStrength?.level ?: 0) else null

        // Wi-Fi: any network on Wi-Fi (the glasses' own link is USB or Ethernet, never this), its signal in dBm.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifiCaps = cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }?.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val wifi = wifiCaps?.let { PhoneStatus.wifiBars(it.signalStrength) }

        PhoneStatus(pct, charging, cell, wifi)
    } catch (e: Exception) {
        if (!failedOnce) Log.w(tag, "the phone's status could not be read: ${e.javaClass.simpleName}: ${e.message}")
        failedOnce = true
        last
    }

    companion object {
        const val EVERY_MS = 5_000L
    }
}
