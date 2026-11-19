package com.hayeco.flipspaces.pcscreen

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * PC screens: the wake relay. A magic packet (Wake-on-LAN) only travels on the home network, so a PC asleep at home
 * cannot be woken from a cafe directly. An old Android phone left at home on its Wi-Fi, running Pocket XR as a wake
 * relay ([WakeRelayService]) with Tailscale on, can: the phone away asks it over Tailscale, and it sends the magic
 * packet at home.
 *
 * The relay is found at home by mDNS ([SERVICE]); its Tailscale address travels in the service's TXT record, so the
 * phone away knows where to ask without anything typed.
 *
 * Protocol (TCP [PORT], one line each way): `POCKETXR-WAKE 1 <mac> <pc address>` in, `OK` or `NO <why>` out. Only
 * private, Tailscale and link-local addresses are answered. Waking a PC is all it can do.
 */
object WakeRelay {
    const val PORT = 47620
    const val SERVICE = "_pocketxr-wake._tcp"
    const val TXT_TAILSCALE = "ts"
    private const val PREFS = "pc_wake_relay"
    private const val KEY_LAN = "lan"
    private const val KEY_TS = "ts"
    private const val KEY_NAME = "name"

    /** A relay this phone found at home: its home and Tailscale addresses, and its name. */
    data class Known(val name: String, val lan: String?, val tailscale: String?)

    fun known(context: Context): Known? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lan = p.getString(KEY_LAN, null)
        val ts = p.getString(KEY_TS, null)
        if (lan == null && ts == null) return null
        return Known(p.getString(KEY_NAME, "") ?: "", lan, ts)
    }

    fun remember(context: Context, r: Known) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, r.name).putString(KEY_LAN, r.lan)
            .apply { if (r.tailscale != null) putString(KEY_TS, r.tailscale) }
            .commit()
    }

    /** This device's Tailscale address (100.64.0.0/10 on its VPN interface), or null when Tailscale is off. */
    fun ownTailscaleAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { isTailscale(it) }?.hostAddress
    }.getOrNull()

    fun isTailscale(a: InetAddress): Boolean {
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    /** Requests are answered only from home, Tailscale or link-local addresses. */
    fun allowed(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || isTailscale(a)

    /** The request line for [mac] (and the PC's address, for the log). */
    fun request(mac: String, pc: String): String = "POCKETXR-WAKE 1 $mac $pc"

    /** The MAC in a request line, or null for anything else. */
    fun parse(line: String?): String? {
        val parts = line?.trim()?.split(' ') ?: return null
        if (parts.size < 3 || parts[0] != "POCKETXR-WAKE" || parts[1] != "1") return null
        return parts[2].takeIf { PcScreenWake.macBytes(it) != null }
    }

    /**
     * Asks the known relay to wake [mac]: its Tailscale address first (away), then its home one. True when one said
     * OK. Blocking.
     */
    fun ask(context: Context, mac: String, pc: String): Boolean {
        val r = known(context) ?: return false
        for (address in listOfNotNull(r.tailscale, r.lan)) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(address, PORT), 4000)
                    s.soTimeout = 4000
                    PrintWriter(s.getOutputStream(), true).println(request(mac, pc))
                    BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim() == "OK"
                }
            }.getOrDefault(false)
            com.hayeco.flipspaces.Log.i("PcScreen", "wake relay ${r.name} at $address: ${if (ok) "sent the wake" else "no answer"}")
            if (ok) return true
        }
        return false
    }
}
