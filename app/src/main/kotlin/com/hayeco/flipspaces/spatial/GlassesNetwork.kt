package com.hayeco.flipspaces.spatial

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.hayeco.flipspaces.core.spatial.HeadRouteCandidate
import com.hayeco.flipspaces.core.spatial.HeadRouteKind
import com.hayeco.flipspaces.core.spatial.HeadRoutePlan
import com.hayeco.flipspaces.core.spatial.GlassesReach
import com.hayeco.flipspaces.core.spatial.HeadShellCommand
import com.hayeco.flipspaces.core.spatial.HeadShellHeader
import com.hayeco.flipspaces.core.spatial.OneImuProtocol
import com.hayeco.flipspaces.platform.ShizukuBridge
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Finds the glasses' USB network: the XREAL One family shows up as a USB Ethernet device (NCM or
 * ECM) with the glasses at 169.254.2.1 and the phone on another link-local address. Android may
 * expose it as a [Network] (ethernet or USB transport), or only as a kernel interface; both are
 * tried, and everything seen is listed for the diagnostics screen.
 *
 * 0.5.5: each network has two routes, the app's own socket bound to it ([HeadRouteKind.DIRECT])
 * and, with Shizuku, a shell helper that binds and pipes the stream ([HeadRouteKind.SHELL]),
 * because Android refused the app's own bind with EPERM on Mike's Flip 7. [HeadRoutePlan] orders
 * them.
 */
object GlassesNetwork {
    /** An open stream from the glasses. */
    interface Link : Closeable {
        /** The local end, "169.254.2.2:41234", or what the shell helper reported. */
        val local: String

        /** Bytes read into [buf]; 0 when none came within the read timeout; -1 at the end. */
        fun read(buf: ByteArray): Int

        /**
         * 1.2.3: a read wakes only once more than [bytes] are waiting (the socket's receive low-water mark), or at the
         * read timeout as before; a description of what was set, or null where this link can't. The kernel holds the
         * read until then, with no sleep timer, so a hot phone can't oversleep it (0.5.49's rests did).
         */
        fun wakeAfter(bytes: Int): String? = null
    }

    /** 0.5.21: a link that also writes (the control channel). */
    interface Duplex : Link {
        /** Throws when the bytes could not be written. */
        fun write(bytes: ByteArray)
    }

    /** A way to reach the glasses. */
    sealed interface Route {
        val iface: String
        val kind: HeadRouteKind
        /** "eth1 (ethernet)": the interface and what Android calls it. */
        val network: String
        val label: String get() = "$network, ${kind.label}"
        /** 1.1.8: the glasses' address on this route's link ([GlassesReach.host]). */
        val host: String get() = OneImuProtocol.HOST

        /** Blocking. Throws with the exact error when the glasses cannot be reached this way. */
        fun open(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Link

        /** 0.5.21: as [open], for a link that writes too. */
        fun openDuplex(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Duplex

        /** The app's own socket from [Network.socketFactory], which binds it to the network. */
        class Direct(val net: Network, override val iface: String, override val network: String, override val host: String = OneImuProtocol.HOST) : Route {
            override val kind = HeadRouteKind.DIRECT
            override fun open(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Link =
                SocketLink(net.socketFactory.createSocket().apply { connect(InetSocketAddress(host, port), timeoutMs) }, readTimeoutMs)
            override fun openDuplex(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Duplex =
                SocketLink(net.socketFactory.createSocket().apply { connect(InetSocketAddress(host, port), timeoutMs) }, readTimeoutMs)
        }

        /**
         * [com.hayeco.flipspaces.platform.HeadStreamShell] through Shizuku, as the shell user.
         * 0.5.6: one helper is reused for every try until a stream opens on it ([ShellHelper]).
         */
        class Shell(val net: Network, override val iface: String, override val network: String, override val host: String = OneImuProtocol.HOST) : Route {
            override val kind = HeadRouteKind.SHELL
            override fun open(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Link =
                ShellHelper.connect(context, HeadShellCommand.Connect(net.networkHandle, iface, host, port, timeoutMs), readTimeoutMs)
            override fun openDuplex(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Duplex =
                ShellHelper.connect(context, HeadShellCommand.Connect(net.networkHandle, iface, host, port, timeoutMs, duplex = true), readTimeoutMs, fresh = true) as Duplex
        }

        /** The app's own socket bound to a link-local address Android made no Network for. */
        class Address(val address: InetAddress, override val iface: String) : Route {
            override val kind = HeadRouteKind.ADDRESS
            override val network = "$iface (kernel interface, ${address.hostAddress})"
            override fun open(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Link = openDuplex(context, host, port, timeoutMs, readTimeoutMs)
            override fun openDuplex(context: Context, host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int): Duplex = SocketLink(
                Socket().apply {
                    bind(InetSocketAddress(address, 0))
                    connect(InetSocketAddress(host, port), timeoutMs)
                },
                readTimeoutMs,
            )
        }
    }

    /**
     * A network Android lists that may be the glasses'. 0.5.6: with its addresses and routes as
     * Android prints them ("169.254.2.2/24", "169.254.0.0/16"), so [reachable] can tell whether
     * it has a way to the glasses at all.
     */
    data class Found(
        val net: Network,
        val iface: String,
        val description: String,
        val linkLocal: Boolean,
        val addresses: List<String> = emptyList(),
        val routes: List<String> = emptyList(),
        /** 1.1.8: the glasses' address on this network (169.254.2.1, or 169.254.1.1 on the ECM link), null for none. */
        val host: String? = GlassesReach.host(addresses, routes),
    ) {
        val reachable: Boolean get() = host != null
    }

    /**
     * [vpn] names a VPN this app sees (Android only shows an app the VPNs that cover it), which
     * is what makes netd refuse the app's bind with EPERM.
     */
    data class Scan(
        val networks: List<Found>,
        val kernel: List<Pair<String, InetAddress>>,
        val seen: List<String>,
        val vpn: String?,
    ) {
        val empty: Boolean get() = networks.isEmpty() && kernel.isEmpty()

        /** 0.5.6: what changes when the glasses' network does (plugged, addressed, routed, gone). */
        val fingerprint: String get() =
            (networks.map { "${it.iface}|${it.net.networkHandle}|${it.addresses.sorted()}|${it.routes.sorted()}" } +
                kernel.map { "${it.first}|${it.second.hostAddress}" }).sorted().joinToString(";")

        /** Routes in the order [HeadRoutePlan] gives. */
        fun routes(shizukuReady: Boolean, refusedDirect: Set<String>): List<Route> {
            val byIface = networks.associateBy { it.iface }
            val kernelByIface = kernel.toMap()
            val plan = HeadRoutePlan.order(networks.map { it.iface }, kernel.map { it.first }, shizukuReady, refusedDirect)
            return plan.mapNotNull { c: HeadRouteCandidate ->
                when (c.kind) {
                    HeadRouteKind.DIRECT -> byIface[c.iface]?.let { Route.Direct(it.net, it.iface, it.description, it.host ?: OneImuProtocol.HOST) }
                    HeadRouteKind.SHELL -> byIface[c.iface]?.let { Route.Shell(it.net, it.iface, it.description, it.host ?: OneImuProtocol.HOST) }
                    HeadRouteKind.ADDRESS -> kernelByIface[c.iface]?.let { Route.Address(it, c.iface) }
                }
            }
        }
    }

    /** Time on top of the connect timeout for Shizuku to start app_process. */
    private const val SHELL_START_MS = 6000

    /** 0.5.6: time on top of the connect timeout for a running helper to answer. */
    private const val SHELL_ANSWER_MS = 1500

    @Suppress("DEPRECATION") // allNetworks: the callback API cannot list networks without INTERNET.
    fun scan(context: Context): Scan {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val seen = mutableListOf<String>()
        val preferred = mutableListOf<Found>()
        val others = mutableListOf<Found>()
        var vpn: String? = null
        cm?.allNetworks?.forEach { network ->
            val lp = cm.getLinkProperties(network)
            val caps = cm.getNetworkCapabilities(network)
            val iface = lp?.interfaceName ?: "?"
            val addresses = lp?.linkAddresses?.map { it.address.hostAddress ?: "?" }.orEmpty()
            val transports = transports(caps)
            // 0.5.5: restricted networks need CONNECTIVITY_USE_RESTRICTED_NETWORKS (EACCES without it).
            val restricted = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            seen += "$iface ${transports.joinToString("+")} ${addresses.joinToString(",")}".trim() +
                " (netId ${network.networkHandle ushr 32}${if (restricted) ", restricted" else ""})"
            if ("vpn" in transports && vpn == null) vpn = "$iface (netId ${network.networkHandle ushr 32})"
            val linkLocal = addresses.any { it.startsWith("169.254.") }
            val wired = "ethernet" in transports || "usb" in transports
            val routes = lp?.routes?.map { r -> r.destination.toString() }.orEmpty()
            val found = Found(network, iface, "$iface (${transports.joinToString("+")})", linkLocal, lp?.linkAddresses?.map { it.toString() }.orEmpty(), routes)
            when {
                linkLocal -> preferred += found
                wired -> others += found
            }
        }
        if (vpn != null) seen += "VPN covering Pocket XR: $vpn"
        val found = preferred + others
        val networks = found.zip(GlassesReach.pick(found.map { it.host })) { n, host -> n.copy(host = host) }
        // Kernel interfaces with a link-local IPv4 address that Android did not turn into a Network.
        val known = networks.map { it.iface }.toSet()
        val kernel = mutableListOf<Pair<String, InetAddress>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                val v4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                if (ni.isLoopback || v4.isEmpty()) return@forEach
                seen += "kernel ${ni.name} ${if (ni.isUp) "up" else "down"} ${v4.joinToString(",") { it.hostAddress ?: "?" }}"
                val ll = v4.firstOrNull { it.isLinkLocalAddress }
                if (ll != null && ni.name !in known) kernel += ni.name to ll
            }
        }
        return Scan(networks, kernel, seen, vpn)
    }

    private fun transports(caps: NetworkCapabilities?): List<String> {
        if (caps == null) return listOf("no capabilities")
        val out = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += "ethernet"
        if (Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) out += "usb"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "wifi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "cellular"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += "vpn"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out += "bluetooth"
        return out.ifEmpty { listOf("other") }
    }

    private class SocketLink(private val socket: Socket, readTimeoutMs: Int) : Duplex {
        init {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
        }

        private val input = socket.getInputStream()
        private val output by lazy { socket.getOutputStream() }
        override val local = "${socket.localAddress.hostAddress}:${socket.localPort}"

        override fun read(buf: ByteArray): Int = try {
            input.read(buf)
        } catch (_: SocketTimeoutException) {
            0
        }

        override fun wakeAfter(bytes: Int): String? = try {
            android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
                Os.setsockoptInt(pfd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVLOWAT, bytes + 1)
                "wakes after more than $bytes bytes"
            }
        } catch (e: Exception) {
            null.also { Log.w("FlipSpaces/Head", "receive low-water mark not set: ${e.javaClass.simpleName}: ${e.message}") }
        }

        @Synchronized
        override fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun close() = socket.close()
    }

    /**
     * 0.5.6: the Shizuku shell helper, started once and reused. Each try writes one command line
     * ([HeadShellCommand]) and reads one header line back with poll(2): an error keeps the helper
     * for the next try, a connection turns it into the stream ([ShellLink]) and a new helper is
     * started for the try after that stream. 0.5.5 started a new `app_process` for every try
     * (every 3 s in Mike's log, each one exiting with code 1).
     */
    class ShellHelper private constructor(
        private val process: Process,
        private val input: InputStream,
        private val fd: FileDescriptor?,
        private val err: StringBuffer,
    ) {
        /**
         * 0.5.10: the helper's stdin, taken once and kept for as long as the helper or its stream
         * lives. 0.5.6 to 0.5.9 asked the process for it on every write; a stream object nobody
         * holds may be closed when it is collected, which ends the helper's stdin, and the helper
         * stopped on that (a likely cause of the drops every 4 to 10 s in one of Mike's logs).
         */
        private val stdin = process.outputStream
        /** Tries this helper served, for the log. */
        var tries = 0
            private set

        private fun alive(): Boolean = runCatching { process.exitValue(); false }.getOrDefault(true)

        private fun destroy() {
            runCatching { stdin.close() }
            runCatching { process.destroy() }
            runCatching { input.close() }
        }

        /** Sends [c] and waits up to [headerMs] for the answer. */
        private fun ask(c: HeadShellCommand.Connect, headerMs: Int): HeadShellHeader.Parsed {
            tries++
            stdin.write((HeadShellCommand.connect(c) + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
            val line = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + headerMs
            while (true) {
                val left = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (left <= 0) throw SocketTimeoutException("the Shizuku helper said nothing in ${headerMs / 1000} s${stderr(err)}")
                if (fd != null && !readable(fd, left)) continue
                val b = input.read()
                if (b < 0) throw IOException("the Shizuku helper ended before answering${exit(process)}${stderr(err)}")
                if (b == '\n'.code) break
                if (line.size() > HeadShellHeader.MAX_LENGTH) throw IOException("the Shizuku helper printed something else: ${line.toString(Charsets.UTF_8.name()).take(200)}")
                line.write(b)
            }
            return HeadShellHeader.parse(line.toString(Charsets.UTF_8.name()))
        }

        companion object {
            /** The idle helper, waiting for its next command; null when none is running. */
            private var idle: ShellHelper? = null

            /** Helpers started since the app started, for the log and the diagnostics screen. */
            @Volatile var started = 0
                private set

            /**
             * Blocking. Throws with the helper's error (it then stays for the next try) or what went wrong.
             * 0.5.21: [fresh] starts a helper of its own and never keeps it (the control link), so the
             * head tracker's idle helper is left to it.
             */
            fun connect(context: Context, c: HeadShellCommand.Connect, readTimeoutMs: Int, fresh: Boolean = false): Link {
                val reused: Boolean
                val helper = if (fresh) {
                    reused = false
                    start(context)
                } else synchronized(this) {
                    val h = idle?.takeIf { it.alive() }
                    idle = null
                    reused = h != null
                    h
                } ?: start(context)
                val parsed = try {
                    // A new helper needs time for Shizuku to start app_process on top of the connect timeout.
                    helper.ask(c, c.timeoutMs + if (reused) SHELL_ANSWER_MS else SHELL_START_MS)
                } catch (e: Exception) {
                    helper.destroy()
                    throw e
                }
                return when (parsed) {
                    is HeadShellHeader.Parsed.Ok -> ShellLink(helper.process, readTimeoutMs, "shell helper, ${parsed.detail}", helper.input, helper.fd, helper.stdin)
                    is HeadShellHeader.Parsed.Failed -> {
                        if (fresh) helper.destroy() else keep(helper)
                        throw IOException("Shizuku helper: ${parsed.error}")
                    }
                    is HeadShellHeader.Parsed.Garbled -> {
                        helper.destroy()
                        throw IOException("the Shizuku helper printed something else: ${parsed.line.take(200)}")
                    }
                }
            }

            /** Stops the idle helper (the head tracker stopped). */
            fun close() {
                val h = synchronized(this) { idle.also { idle = null } }
                h?.destroy()
            }

            private fun keep(h: ShellHelper) {
                val old = synchronized(this) { idle.also { idle = h } }
                if (old !== h) old?.destroy()
            }

            private fun start(context: Context): ShellHelper {
                val process = ShizukuBridge.headStreamHelper(context)
                started++
                Log.i(TAG, "head tracking: Shizuku shell helper started (helper $started since Pocket XR started); it serves every try until a stream opens")
                val input = process.inputStream
                val err = StringBuffer()
                Thread {
                    runCatching {
                        process.errorStream.bufferedReader().forEachLine {
                            // 0.5.12: the helper's own read stats go to the log, not into the error text.
                            if (it.startsWith(HeadShellHeader.STAT)) Log.i(TAG, "shell helper:" + it.removePrefix(HeadShellHeader.STAT))
                            else if (err.length < 2000) err.append(it).append(' ')
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                return ShellHelper(process, input, (input as? FileInputStream)?.fd, err)
            }
        }
    }

    /**
     * The shell helper's stdout once it connected: the glasses' bytes. Reads wait with poll(2)
     * so a quiet stream still returns 0 every [readTimeoutMs], as a socket timeout would.
     */
    private class ShellLink(
        private val process: Process,
        private val readTimeoutMs: Int,
        override val local: String,
        private val input: InputStream,
        private val fd: FileDescriptor?,
        /** 0.5.10: held for the stream's life, so the helper's stdin stays open ([ShellHelper.stdin]). */
        private val stdin: java.io.OutputStream,
    ) : Duplex {
        override fun read(buf: ByteArray): Int {
            if (fd != null && !readable(fd, readTimeoutMs)) return 0
            return input.read(buf)
        }

        /** 0.5.21: only a DUPLEX helper writes these to its socket ([HeadStreamShell]); others ignore them. */
        @Synchronized
        override fun write(bytes: ByteArray) {
            stdin.write((HeadShellCommand.send(bytes) + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
        }

        override fun close() {
            runCatching { stdin.close() }
            runCatching { process.destroy() }
            runCatching { input.close() }
        }
    }

    private fun readable(fd: FileDescriptor, timeoutMs: Int): Boolean {
        val p = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        return try {
            Os.poll(arrayOf(p), timeoutMs) > 0
        } catch (e: ErrnoException) {
            // Interrupted by a signal: nothing read this time, the caller tries again.
            if (e.errno == OsConstants.EINTR) false else throw IOException("poll: ${e.message}", e)
        }
    }

    private fun exit(process: Process) = runCatching { " (exit ${process.exitValue()})" }.getOrDefault("")

    private fun stderr(err: StringBuffer) = if (err.isBlank()) "" else ", stderr: ${err.toString().trim().take(300)}"

    private const val TAG = "FlipSpaces/Head"
}
