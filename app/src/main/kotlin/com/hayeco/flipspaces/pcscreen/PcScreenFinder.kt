package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.hayeco.flipspaces.Log
import java.net.Inet4Address

/**
 * PC screens: finds Sunshine and Apollo hosts on the Wi-Fi by their `_nvstream._tcp` mDNS service, as Moonlight does,
 * so a PC is a tap on the setup page instead of an address to type. Each Apollo instance shows as its own entry, by its
 * own port. Results come on the main thread, each new or changed host once.
 */
class PcScreenFinder(context: Context, private val found: (List<Host>) -> Unit) {
    /** A host as found: its mDNS name (the PC's; every Apollo instance gives the same), its IPv4 address and HTTP port. */
    data class Host(val name: String, val address: String, val port: Int) {
        /** The address as the setup page saves it: the port only when it is not the usual one. */
        val typed: String get() = if (port == PcScreenAddress.DEFAULT_PORT) address else "$address:$port"
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val hosts = LinkedHashMap<String, Host>()
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "finder: looking for PCs") }
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "finder: could not look ($errorCode)") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = main.post {
            toResolve.addLast(info)
            resolveNext()
        }.let { }
        override fun onServiceLost(info: NsdServiceInfo) = main.post {
            if (hosts.entries.removeAll { it.value.name == info.serviceName }) found(hosts.values.toList())
        }.let { }
    }

    fun start() {
        if (running || nsd == null) return
        running = runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isSuccess
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd?.stopServiceDiscovery(discovery) }
        toResolve.clear()
    }

    /** One resolve at a time: older Androids refuse a second while one runs. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = main.post {
                Log.w(TAG, "finder: could not resolve ${serviceInfo.serviceName} ($errorCode)")
                resolving = false
                resolveNext()
            }.let { }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = main.post {
                resolving = false
                val addr = serviceInfo.host
                // IPv4 where there is one: the phone and PC on one Wi-Fi, and simplest to show.
                if (addr is Inet4Address) {
                    val h = Host(serviceInfo.serviceName, addr.hostAddress ?: "", serviceInfo.port)
                    // Keyed by address and port: Apollo's instances all give the PC's own name.
                    if (h.address.isNotEmpty() && hosts[h.typed] != h) {
                        hosts[h.typed] = h
                        Log.i(TAG, "finder: found ${h.name} at ${h.typed}")
                        found(hosts.values.toList())
                    }
                }
                resolveNext()
            }.let { }
        }) ?: run { resolving = false }
    }

    private companion object {
        const val TAG = "PcScreen"
        const val SERVICE = "_nvstream._tcp"
    }
}
