package com.hayeco.flipspaces.pcscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * The wake relay ([WakeRelay]), run on a phone left at home: listens on [WakeRelay.PORT], sends the magic packet asked
 * for on the home network, and says where it is by mDNS, its Tailscale address in the TXT record. A foreground service
 * (connected device: it wakes the user's PC), with a partial wake lock and a Wi-Fi lock so a sleeping phone still
 * answers. On while [isOn]; started again after a reboot ([WakeRelayBoot]).
 */
class WakeRelayService : Service() {
    @Volatile private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastWakeMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (server == null) start()
        return START_STICKY
    }

    private fun start() {
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketXR:WakeRelay").apply { acquire() }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketXR:WakeRelay").apply { acquire() }
        Thread({ serve() }, "WakeRelay").start()
        advertise()
    }

    private fun serve() {
        val s = runCatching { ServerSocket(WakeRelay.PORT) }.getOrElse {
            Log.w(TAG, "wake relay: cannot listen on ${WakeRelay.PORT}: $it")
            return
        }
        server = s
        Log.i(TAG, "wake relay: listening on ${WakeRelay.PORT}, Tailscale ${WakeRelay.ownTailscaleAddress() ?: "off"}")
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            Thread({ answer(c) }, "WakeRelay-client").start()
        }
    }

    private fun answer(c: Socket) = runCatching {
        c.use {
            it.soTimeout = 4000
            val out = PrintWriter(it.getOutputStream(), true)
            if (!WakeRelay.allowed(it.inetAddress)) {
                Log.w(TAG, "wake relay: refused ${it.inetAddress.hostAddress} (not home or Tailscale)")
                out.println("NO not allowed")
                return@use
            }
            val line = BufferedReader(InputStreamReader(it.getInputStream())).readLine()
            val mac = WakeRelay.parse(line)
            if (mac == null) {
                out.println("NO bad request")
                return@use
            }
            val now = android.os.SystemClock.uptimeMillis()
            synchronized(this) {
                if (now - lastWakeMs < 1000) { out.println("OK"); return@use }
                lastWakeMs = now
            }
            val pc = line!!.trim().split(' ').getOrNull(3) ?: ""
            val sent = PcScreenWake.send(mac, pc.ifEmpty { "255.255.255.255" })
            Log.i(TAG, "wake relay: ${it.inetAddress.hostAddress} asked to wake $mac: ${if (sent) "sent" else "could not send"}")
            out.println(if (sent) "OK" else "NO could not send")
        }
    }

    /** mDNS, so a phone at home finds the relay; the Tailscale address rides along for when it is away. */
    private fun advertise() {
        val info = NsdServiceInfo().apply {
            serviceName = "Pocket XR wake relay (${android.os.Build.MODEL})"
            serviceType = WakeRelay.SERVICE
            port = WakeRelay.PORT
            WakeRelay.ownTailscaleAddress()?.let { setAttribute(WakeRelay.TXT_TAILSCALE, it) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { Log.i(TAG, "wake relay: advertised as ${serviceInfo.serviceName}") }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "wake relay: advertising failed ($errorCode)") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd = getSystemService(NsdManager::class.java)
        registration = l
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    override fun onDestroy() {
        runCatching { server?.close() }
        server = null
        registration?.let { r -> runCatching { nsd?.unregisterService(r) } }
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        Log.i(TAG, "wake relay: stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wake_relay_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WakeRelayActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pcvr_tile)
            .setContentTitle(getString(R.string.wake_relay_title))
            .setContentText(getString(R.string.wake_relay_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PcScreen"
        private const val CHANNEL = "wake_relay"
        private const val NOTIFICATION_ID = 4762
        private const val PREFS = "pc_wake_relay_service"
        private const val KEY_ON = "on"

        fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).commit()
            val i = Intent(context, WakeRelayService::class.java)
            if (on) context.startForegroundService(i) else context.stopService(i)
        }
    }
}

/** Starts the wake relay again after the phone restarts, when it was on. */
class WakeRelayBoot : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && WakeRelayService.isOn(context)) {
            runCatching { context.startForegroundService(Intent(context, WakeRelayService::class.java)) }
        }
    }
}
