package com.hayeco.flipspaces.phone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.spatial.BtDevice
import com.hayeco.flipspaces.core.spatial.BtState
import com.hayeco.flipspaces.core.spatial.ControlSheets
import com.hayeco.flipspaces.core.spatial.WifiState
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.core.ShizukuStatus
import java.util.concurrent.Executors

/**
 * 0.5.46 (Mike: "the Control Panel should have a Bluetooth and Wifi button that shows the user active wifi and bluetooth
 * connections ... and a way for them to add/remove bluetooth devices and wifi networks"): the phone's Wi-Fi and Bluetooth
 * for the Control Center's pages, read off the GL thread.
 *
 * Wi-Fi through Shizuku's shell (`cmd wifi status`, `list-networks`, `forget-network`, `set-wifi-enabled`): an app may not
 * read the network's name without location access, nor forget a network it did not add. Bluetooth through the API
 * (BLUETOOTH_CONNECT, granted through Shizuku on the page's one press): the paired devices, and which are connected by the
 * audio, LE audio and input profiles. Adding a network and pairing a device open the phone's own settings as a window.
 */
object PhoneConnections {
    private const val TAG = "FlipSpacesConnections"
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-connections").apply { isDaemon = true } }

    // 1.1.14: internal, so Send logs to Mike's test can give it the Wi-Fi names to take out.
    @Volatile var wifi = WifiState()
        internal set
    // 1.1.14: internal too, for the Bluetooth device names Send logs to Mike's test takes out.
    @Volatile var bt = BtState()
        internal set
    @Volatile var version = 0L
        private set

    private fun shizuku(context: Context) = ShizukuBridge.status(context) == ShizukuStatus.READY

    fun refresh(context: Context) {
        val app = context.applicationContext
        worker.execute {
            wifi = readWifi(app)
            bt = readBt(app)
            version++
        }
    }

    private fun readWifi(context: Context): WifiState {
        if (!shizuku(context)) return WifiState(available = false)
        val status = ShizukuBridge.shell(listOf("cmd", "wifi", "status")).output
        val (enabled, ssid, rssi) = ControlSheets.parseStatus(status)
        val saved = ControlSheets.parseNetworks(ShizukuBridge.shell(listOf("cmd", "wifi", "list-networks")).output)
        val freq = Regex("Frequency: (\\d+)MHz").find(status)?.groupValues?.get(1)?.toIntOrNull()
        val speed = Regex("Link speed: (\\d+)Mbps").find(status)?.groupValues?.get(1)
        val band = when {
            freq == null -> null
            freq >= 5925 -> "6 GHz"
            freq >= 4900 -> "5 GHz"
            else -> "2.4 GHz"
        }
        val detail = listOfNotNull(context.getString(R.string.sys_wifi_connected), band, speed?.let { context.getString(R.string.sys_wifi_mbps, it) }).joinToString(" · ")
        return WifiState(true, enabled, ssid, rssi?.let { ControlSheets.bars(it) } ?: 0, detail, saved)
    }

    private fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun readBt(context: Context): BtState {
        if (!permitted(context)) return BtState(permitted = false)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BtState(true, false)
        if (!adapter.isEnabled) return BtState(true, false)
        val bonded = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
        val connected = connectedAddresses(context, adapter)
        return BtState(true, true, bonded.map { BtDevice(it.address, runCatching { it.alias ?: it.name }.getOrNull() ?: it.address, it.address in connected) })
    }

    /**
     * 1.1.14: the paired devices' names (alias and name, "Jordan's Buds"), read now, for Send logs to Mike to take out;
     * with the last list read for the Control Center, which may be older. Empty without Bluetooth access.
     */
    @Suppress("MissingPermission")
    fun pairedNames(context: Context): List<String> {
        val read = if (!permitted(context)) emptyList() else runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .flatMap { d -> listOfNotNull(runCatching { d.alias }.getOrNull(), runCatching { d.name }.getOrNull()) }
        }.getOrDefault(emptyList())
        val known = bt.devices.filter { it.name != it.address }.map { it.name }
        return (read + known).filter { it.isNotBlank() }.distinct()
    }

    /** The addresses connected on the audio, LE audio and input profiles; waits up to a second for the proxies. */
    @Suppress("MissingPermission")
    private fun connectedAddresses(context: Context, adapter: BluetoothAdapter): Set<String> {
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO, HID_HOST)
        val out = java.util.Collections.synchronizedSet(HashSet<String>())
        val latch = java.util.concurrent.CountDownLatch(profiles.size)
        for (p in profiles) {
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { d: BluetoothDevice -> out += d.address } }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                }, p)
            }.getOrDefault(false)
            if (!asked) latch.countDown()
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return out.toSet()
    }

    /** Blocking (Shizuku): lets Pocket XR read the paired devices; null when it worked, else why not. */
    fun grantBluetooth(context: Context): String? {
        val r = ShizukuBridge.shell(listOf("pm", "grant", context.packageName, Manifest.permission.BLUETOOTH_CONNECT))
        Log.i(TAG, "Bluetooth access asked for through Shizuku: exit ${r.exitCode} ${r.output.take(200)}")
        return if (r.exitCode == 0) null else r.output.ifBlank { context.getString(R.string.sys_shizuku_not_running) }
    }

    /** Blocking (Shizuku): Wi-Fi or Bluetooth on or off. */
    fun setPower(context: Context, wifiPage: Boolean, on: Boolean): String? {
        val cmd = if (wifiPage) listOf("cmd", "wifi", "set-wifi-enabled", if (on) "enabled" else "disabled") else listOf("cmd", "bluetooth_manager", if (on) "enable" else "disable")
        val r = ShizukuBridge.shell(cmd)
        Log.i(TAG, "${cmd.joinToString(" ")}: exit ${r.exitCode} ${r.output.take(200)}")
        return if (r.exitCode == 0) null else r.output.ifBlank { context.getString(R.string.sys_shizuku_not_running) }
    }

    /** Blocking (Shizuku): forgets the saved network [id]. */
    fun forget(id: Int): String? {
        val r = ShizukuBridge.shell(listOf("cmd", "wifi", "forget-network", id.toString()))
        Log.i(TAG, "forget-network $id: exit ${r.exitCode} ${r.output.take(200)}")
        return if (r.exitCode == 0) null else r.output
    }

    /** BluetoothProfile.HID_HOST (hidden in the SDK). */
    private const val HID_HOST = 4
}
