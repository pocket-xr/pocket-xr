package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.6: how long the head tracker waits before trying the glasses' IMU port again. Mike's 0.5.5
 * log had a try every 3 s for as long as the Space was up, each one a new Shizuku `app_process`
 * timing out on 169.254.2.1:52998. Now: 3 s after the first failure, then 6, 12, 24, 48 and 60 s
 * at most, and back to 3 s when the glasses are plugged in again or their network changes.
 */
class HeadRetryBackoff(val firstMs: Long = 3_000L, val maxMs: Long = 60_000L) {
    init {
        require(firstMs > 0 && maxMs >= firstMs) { "The backoff needs a first wait and a cap at or above it" }
    }

    /** Failed rounds since the last reset. */
    var failures = 0
        private set

    /** One more failed round: the wait before the next. */
    fun nextDelayMs(): Long {
        val shift = failures.coerceAtMost(30)
        failures++
        val d = firstMs shl shift
        return if (d <= 0 || d > maxMs) maxMs else d
    }

    /** Plug-in, a network change, or a stream that worked: the next failure waits [firstMs] again. */
    fun reset() {
        failures = 0
    }
}

/**
 * 0.5.6: whether an Android network can reach the glasses at all, from its LinkProperties: an
 * IPv4 address or a route whose prefix holds [OneImuProtocol.HOST]. Mike's eth1 answered
 * ENETUNREACH every 3 s: no point connecting there until the network changes.
 */
object GlassesReach {
    /**
     * [linkAddresses] and [routes] as Android prints them ("169.254.2.2/24",
     * "169.254.0.0/16 -> 0.0.0.0 eth0"); anything that is not IPv4 is skipped.
     */
    fun hasRoute(host: String, linkAddresses: List<String>, routes: List<String>): Boolean {
        val target = ipv4(host) ?: return false
        return (linkAddresses + routes).any { entry ->
            val prefix = entry.trim().substringBefore(' ')
            val ip = ipv4(prefix.substringBefore('/')) ?: return@any false
            val bits = prefix.substringAfter('/', "32").toIntOrNull()?.coerceIn(0, 32) ?: return@any false
            // A default route (0.0.0.0/0) on a USB network is not a route to the glasses' link.
            if (bits == 0) return@any false
            val mask = if (bits == 32) -1L and 0xFFFFFFFFL else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            (ip and mask) == (target and mask)
        }
    }

    /**
     * 1.1.8: the glasses' address this network reaches: [OneImuProtocol.HOST] on the NCM link, else
     * [OneImuProtocol.ECM_HOST] on the ECM link; null when it reaches neither.
     */
    fun host(linkAddresses: List<String>, routes: List<String>): String? =
        listOf(OneImuProtocol.HOST, OneImuProtocol.ECM_HOST).firstOrNull { hasRoute(it, linkAddresses, routes) }

    /**
     * 1.1.8: [hosts] (one per network, from [host]) with the ECM link's address kept only when no network reaches
     * [OneImuProtocol.HOST]: a phone with both links (the Flip's eth0 and eth1) stays on the NCM link it streams on.
     */
    fun pick(hosts: List<String?>): List<String?> =
        if (OneImuProtocol.HOST in hosts) hosts.map { it.takeIf { h -> h == OneImuProtocol.HOST } } else hosts

    /** True when [error] says the kernel has no way there, not that the glasses stayed quiet. */
    fun noRoute(error: String): Boolean = "ENETUNREACH" in error || "EHOSTUNREACH" in error || "Network is unreachable" in error || "No route to host" in error

    /** True when the connection went out and nothing came back in time. */
    fun noAnswer(error: String): Boolean = ("SocketTimeoutException" in error || "ETIMEDOUT" in error) && !noRoute(error)

    /** True when the glasses answered and refused the port. */
    fun refused(error: String): Boolean = "ECONNREFUSED" in error || "Connection refused" in error

    /**
     * Plain words for the diagnostics screen and the log when the glasses were reachable but
     * the stream did not open; null when [error] is something else.
     */
    fun explain(error: String): String? = when {
        noAnswer(error) ->
            UiText.format("spatial_reach_no_answer", "The glasses do not answer on %1\$s port %2\$s: the connection went out and nothing came back. This firmware may not serve the XREAL One head tracking stream. The Space stays head locked.", OneImuProtocol.HOST, OneImuProtocol.STREAM_PORT.toString())
        refused(error) ->
            UiText.format("spatial_reach_refused", "The glasses refused port %1\$s: they are there but do not serve the head tracking stream on it. The Space stays head locked.", OneImuProtocol.STREAM_PORT.toString())
        noRoute(error) ->
            UiText.format("spatial_reach_no_route", "No route to %1\$s on this network; it is not tried again until the glasses' network changes.", OneImuProtocol.HOST)
        else -> null
    }

    private fun ipv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}

/**
 * 0.5.6: the shell helper is started once and reused. The app writes one command line per try on
 * its stdin and the helper answers with one [HeadShellHeader] line: ERR keeps it waiting for the
 * next command, OK is followed by the glasses' bytes until the stream ends, and then it exits.
 */
object HeadShellCommand {
    const val CONNECT = "CONNECT"

    /**
     * 0.5.21: as CONNECT, and after the OK the helper also writes to the socket what
     * the app sends it as [SEND] lines (the control channel to 52999 needs both ways).
     */
    const val DUPLEX = "DUPLEX"
    const val SEND = "SEND"

    data class Connect(val networkHandle: Long, val iface: String, val host: String, val port: Int, val timeoutMs: Int, val duplex: Boolean = false)

    fun connect(c: Connect): String = "${if (c.duplex) DUPLEX else CONNECT} ${c.networkHandle} ${c.iface} ${c.host} ${c.port} ${c.timeoutMs}"

    /** Null for a line that is not a well formed command. */
    fun parse(line: String): Connect? {
        val p = line.trim().split(' ').filter { it.isNotEmpty() }
        if (p.size != 6 || (p[0] != CONNECT && p[0] != DUPLEX)) return null
        return Connect(
            networkHandle = p[1].toLongOrNull() ?: return null,
            iface = p[2],
            host = p[3],
            port = p[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
            timeoutMs = p[5].toIntOrNull()?.takeIf { it > 0 } ?: return null,
            duplex = p[0] == DUPLEX,
        )
    }

    /**
     * 0.5.24 step s_d62bf0e3: "LISTEN NETWORK_HANDLE IFACE PORT": the helper listens on PORT for the glasses' own
     * log connections (they connect to the phone, [GlassesControl.NET_LOG_PORT]) and relays them as
     * [GlassesLogRelay] lines after its OK header, until the app closes its stdin.
     */
    const val LISTEN = "LISTEN"

    data class Listen(val networkHandle: Long, val iface: String, val port: Int)

    fun listen(l: Listen): String = "$LISTEN ${l.networkHandle} ${l.iface} ${l.port}"

    /** Null for a line that is not a well formed LISTEN command. */
    fun parseListen(line: String): Listen? {
        val p = line.trim().split(' ').filter { it.isNotEmpty() }
        if (p.size != 4 || p[0] != LISTEN) return null
        return Listen(
            networkHandle = p[1].toLongOrNull() ?: return null,
            iface = p[2],
            port = p[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
        )
    }

    /** One write for a duplex stream: "SEND" and the bytes in hex. */
    fun send(bytes: ByteArray): String = "$SEND " + bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The bytes of a SEND line; null for anything else. */
    fun parseSend(line: String): ByteArray? {
        val p = line.trim()
        if (!p.startsWith("$SEND ")) return null
        val hex = p.substring(SEND.length + 1)
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) or lo).toByte()
        }
    }
}
