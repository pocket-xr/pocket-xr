package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

/**
 * PC screens away from home, over Tailscale (the official apps on the phone and the PC; the PC's side is set up by
 * "Set up PC screens"). At home the PC is reached at its home address; away, at its Tailscale name, which the PC
 * setup makes its Windows name ("mike"), so the phone needs nothing typed: it learns the name when it finds the PC at
 * home ([PcScreenFinder]'s mDNS name is the PC's Windows name).
 */
object PcScreenRemote {
    const val TAILSCALE = "com.tailscale.ipn"

    /** The PC's Tailscale (MagicDNS) short name from its Windows name: lower case, letters, digits and hyphens. */
    fun magicName(pcName: String): String? {
        val n = pcName.trim().replace(Regex("""\(\d+\)$"""), "").replace(Regex("""\s+screen\s+\d+$""", RegexOption.IGNORE_CASE), "")
            .lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        return n.ifEmpty { null }
    }

    /** The away address for a home [address] (its port kept) and the PC's [pcName], or null. */
    fun awayAddress(address: String, pcName: String?): String? {
        val name = pcName?.let { magicName(it) } ?: return null
        val port = PcScreenAddress.parse(address)?.second ?: PcScreenAddress.DEFAULT_PORT
        return if (port == PcScreenAddress.DEFAULT_PORT) name else "$name:$port"
    }

    /** A VPN is up on the phone (Tailscale, when it is the one installed). */
    fun vpnUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    fun tailscaleInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TAILSCALE, 0); true }.getOrDefault(false)

    /** Opens Tailscale, or its Play Store page when it is not installed. */
    fun openTailscale(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Whether the host's plain HTTP serverinfo answers at [address] within [timeoutMs]. Blocking. */
    fun answers(address: String, timeoutMs: Int): Boolean = runCatching {
        val (host, port) = PcScreenAddress.parse(address) ?: return false
        val c = java.net.URL("http://$host:$port/serverinfo").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try { c.responseCode in 200..499 } finally { c.disconnect() }
    }.getOrDefault(false)
}
