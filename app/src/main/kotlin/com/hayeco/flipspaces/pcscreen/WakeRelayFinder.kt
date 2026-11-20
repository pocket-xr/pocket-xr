package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hayeco.flipspaces.Log
import java.net.Inet4Address

/** Finds a wake relay ([WakeRelayService]) on the home Wi-Fi and remembers it, Tailscale address and all ([WakeRelay]). */
class WakeRelayFinder(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        @Suppress("DEPRECATION")
        override fun onServiceFound(info: NsdServiceInfo) {
            runCatching {
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val lan = (serviceInfo.host as? Inet4Address)?.hostAddress
                        val ts = serviceInfo.attributes[WakeRelay.TXT_TAILSCALE]?.let { String(it) }
                        val known = WakeRelay.Known(serviceInfo.serviceName, lan, ts)
                        if (WakeRelay.known(app) != known) {
                            WakeRelay.remember(app, known)
                            Log.i("PcScreen", "wake relay found: ${known.name} at $lan, Tailscale ${ts ?: "off"}")
                        }
                    }
                })
            }
        }
    }

    fun start() {
        if (running || nsd == null) return
        running = runCatching { nsd.discoverServices(WakeRelay.SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isSuccess
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd?.stopServiceDiscovery(discovery) }
    }
}
