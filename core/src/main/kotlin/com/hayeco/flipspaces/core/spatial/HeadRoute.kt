package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.5: the ways the head tracker reaches the glasses' IMU port. Mike's 0.5.4 log on the Flip 7
 * showed every attempt failing with "Binding socket to network 221 failed: EPERM": Android
 * refused to bind the app's own socket to the glasses' USB network.
 */
enum class HeadRouteKind(val label: String) {
    /** The app's own socket, bound to the glasses' Android Network (Network.socketFactory). */
    DIRECT("app socket bound to the network"),

    /** A helper run as the shell user through Shizuku binds to the network and pipes the stream to the app. */
    SHELL("Shizuku shell helper bound to the network"),

    /** The app's own socket from the link-local address of an interface Android made no Network for. */
    ADDRESS("app socket from the link-local address"),
}

data class HeadRouteCandidate(val iface: String, val kind: HeadRouteKind)

object HeadRoutePlan {
    /**
     * The order to try: for every network the app's own socket, then for every network the shell
     * helper (with Shizuku ready), then kernel interfaces without a Network. An interface in
     * [refusedDirect] (Android refused the app's bind there before) goes straight to the shell
     * helper when Shizuku is ready; without Shizuku the app's own socket is still tried, so the
     * screen keeps showing Android's exact refusal.
     */
    fun order(networks: List<String>, kernel: List<String>, shizukuReady: Boolean, refusedDirect: Set<String>): List<HeadRouteCandidate> {
        val out = mutableListOf<HeadRouteCandidate>()
        for (iface in networks) if (!(shizukuReady && iface in refusedDirect)) out += HeadRouteCandidate(iface, HeadRouteKind.DIRECT)
        if (shizukuReady) for (iface in networks) out += HeadRouteCandidate(iface, HeadRouteKind.SHELL)
        for (iface in kernel) if (iface !in networks) out += HeadRouteCandidate(iface, HeadRouteKind.ADDRESS)
        return out.distinct()
    }

    /** True when [error] is Android refusing to bind a socket to a network, not the glasses not answering. */
    fun bindRefused(error: String): Boolean =
        BIND.containsMatchIn(error) && (error.contains("EPERM") || error.contains("EACCES"))

    /**
     * What a failure means, in words for the diagnostics screen and the log; null when the error
     * says it already. netd (system/netd NetworkController.checkUserNetworkAccessLocked) answers
     * EPERM when a VPN that cannot be bypassed covers the app, and EACCES for a restricted
     * network (no NOT_RESTRICTED) without CONNECTIVITY_USE_RESTRICTED_NETWORKS.
     */
    fun explain(error: String, vpn: String?, shizuku: ShizukuStatus): String? {
        if (!BIND.containsMatchIn(error)) return null
        val next = if (shizuku == ShizukuStatus.READY) UiText.get("spatial_route_shell_next", "the Shizuku shell helper is tried next") else UiText.format("spatial_route_start_shizuku", "%1\$s: start Shizuku so its shell helper can connect instead", shizuku.label)
        return when {
            error.contains("EPERM") ->
                if (vpn != null) UiText.format("spatial_route_eperm_vpn", "Android refused to bind Pocket XR's socket to the glasses' network (EPERM): a VPN that cannot be bypassed covers the app (Android shows one: %1\$s); %2\$s", vpn, next)
                else UiText.format("spatial_route_eperm", "Android refused to bind Pocket XR's socket to the glasses' network (EPERM): a VPN that cannot be bypassed covers the app; %1\$s", next)
            error.contains("EACCES") ->
                UiText.format("spatial_route_eacces", "Android refused to bind Pocket XR's socket to the glasses' network (EACCES): the network is restricted; %1\$s", next)
            else -> null
        }
    }

    private val BIND = Regex("Binding socket to network \\d+ failed")
}

/**
 * 0.5.5: the first line the shell helper (app HeadStreamShell) prints on stdout before the
 * glasses' bytes: "FSHEAD OK how it connected" or "FSHEAD ERR the exact error".
 */
object HeadShellHeader {
    const val PREFIX = "FSHEAD"
    const val MAX_LENGTH = 1024

    /** 0.5.12: the helper's stderr line on how the glasses' bytes reach it, every 10 s of a stream. */
    const val STAT = "$PREFIX STAT"

    sealed interface Parsed {
        data class Ok(val detail: String) : Parsed
        data class Failed(val error: String) : Parsed
        data class Garbled(val line: String) : Parsed
    }

    fun ok(detail: String) = "$PREFIX OK ${oneLine(detail)}"

    fun error(detail: String) = "$PREFIX ERR ${oneLine(detail)}"

    fun parse(line: String): Parsed {
        val t = line.trimEnd('\r', '\n')
        return when {
            t.startsWith("$PREFIX OK") -> Parsed.Ok(t.removePrefix("$PREFIX OK").trim())
            t.startsWith("$PREFIX ERR") -> Parsed.Failed(t.removePrefix("$PREFIX ERR").trim())
            else -> Parsed.Garbled(t)
        }
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').take(MAX_LENGTH - 20)
}
