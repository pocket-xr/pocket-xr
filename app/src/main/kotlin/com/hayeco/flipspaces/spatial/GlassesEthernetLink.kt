package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.BringUpAction
import com.hayeco.flipspaces.core.spatial.EthernetDump
import com.hayeco.flipspaces.core.spatial.EthernetIpConfig
import com.hayeco.flipspaces.core.spatial.EthernetPhase
import com.hayeco.flipspaces.core.spatial.EthernetReply
import com.hayeco.flipspaces.core.spatial.EthernetRestore
import com.hayeco.flipspaces.core.spatial.GlassesEthernetPlan
import com.hayeco.flipspaces.core.spatial.OneImuProtocol
import com.hayeco.flipspaces.core.spatial.RestoreAction
import com.hayeco.flipspaces.platform.ShizukuBridge
import java.util.concurrent.Executors

/**
 * Brings up the glasses' USB Ethernet when Android has Ethernet switched off (One UI 9 on the
 * Flip 7: `dumpsys ethernet` says "Ethernet State: disabled", eth0 and eth1 stay down and no
 * Network appears). Through Shizuku it switches Ethernet on, waits for DHCP from the glasses,
 * falls back to a static 169.254.2.2/24, and checks 169.254.2.1:52998 answers before moving to
 * the next interface. What it changed is put back by [restore] when the Space closes. Without
 * Shizuku it does nothing and the Space stays head locked.
 */
object GlassesEthernetLink {
    private const val TAG = "FlipSpaces/Ethernet"
    private const val PROBE_TIMEOUT_MS = 1500

    enum class Outcome {
        /** A route answered on the IMU port. */
        REACHED,
        /** Shizuku is not ready; nothing was touched. */
        NO_SHIZUKU,
        /** No eth interface: the glasses are not plugged in, or not as USB Ethernet. */
        NO_INTERFACE,
        /** Tried every interface, the glasses did not answer. */
        NOT_REACHED,
        INTERRUPTED,
    }

    private val lock = Any()
    private val changes = EthernetRestore()
    /** Restores run here in order; a bring-up first waits for any restore queued before it. */
    private val restorer = Executors.newSingleThreadExecutor { Thread(it, "ethernet-restore") }

    /** Blocking, on the head tracker's thread. Every step goes to [log] word for word. */
    fun bringUp(context: Context, log: (String) -> Unit): Outcome = try {
        restorer.submit {}.get()
        synchronized(lock) { run(context) { Log.i(TAG, it); log(it) } }
    } catch (_: InterruptedException) {
        Outcome.INTERRUPTED
    }

    private fun run(context: Context, log: (String) -> Unit): Outcome {
        val shizuku = ShizukuBridge.status(context)
        if (shizuku != ShizukuStatus.READY) {
            log("${shizuku.label}: Ethernet left as it is, so the Space stays head locked.")
            return Outcome.NO_SHIZUKU
        }
        val dump = ShizukuBridge.shell(listOf("dumpsys", "ethernet"))
        val state = EthernetDump.parse(dump.output)
        log("dumpsys ethernet: ${state.summary}" + if (dump.exitCode != 0) " (exit ${dump.exitCode}: ${dump.output.take(200)})" else "")

        val list = ShizukuBridge.ethernet(context, "list")
        logReply("Interfaces", list, log)
        list.interfaces.forEach { log("  ${it.label}") }
        val candidates = GlassesEthernetPlan.candidates(list.interfaces, state)
        if (candidates.isEmpty()) {
            log("No eth interface to try: plug in the glasses.")
            return Outcome.NO_INTERFACE
        }
        log("Will try: ${candidates.joinToString(", ")}")

        if (GlassesEthernetPlan.needsEnable(state)) {
            val r = ShizukuBridge.ethernet(context, "enable", "true")
            logReply("setEthernetEnabled(true)", r, log)
            if (r.ok) changes.enabledByUs(state.enabled)
        } else {
            log("Ethernet already enabled: setEthernetEnabled not needed.")
        }

        for (iface in candidates) {
            if (GlassesEthernetPlan.tetheringBlocks(state, iface)) {
                logReply("$iface is in tethering server mode; releaseTetheredInterface", ShizukuBridge.ethernet(context, "untether"), log)
            } else if (iface == state.tetheringInterface) {
                log("$iface is Android's tethering candidate but in client mode, so nothing to release.")
            }
            if (tryInterface(context, iface, log)) return Outcome.REACHED
        }
        log("The glasses did not answer on ${candidates.joinToString(" or ")}.")
        return Outcome.NOT_REACHED
    }

    /** DHCP first, then a static link-local address; true once the glasses answer. */
    private fun tryInterface(context: Context, iface: String, log: (String) -> Unit): Boolean {
        logReply("enableInterface($iface)", ShizukuBridge.ethernet(context, "ifup", iface), log)
        var phase = EthernetPhase.DHCP
        var staticAccepted: Boolean? = null
        var start = SystemClock.elapsedRealtime()
        log("$iface: waiting up to ${GlassesEthernetPlan.DHCP_WAIT_MS / 1000} s for DHCP.")
        while (true) {
            val routes = routesOn(context, iface)
            when (GlassesEthernetPlan.decide(phase, SystemClock.elapsedRealtime() - start, routes.isNotEmpty(), staticAccepted)) {
                BringUpAction.WAIT -> Thread.sleep(GlassesEthernetPlan.POLL_MS)
                BringUpAction.CONNECT -> {
                    log("$iface: ${if (phase == EthernetPhase.DHCP) "address from DHCP" else "static address up"}, ${routes.first().network}.")
                    // 0.5.5: the app's own socket first, then the shell helper if Android refuses the bind.
                    val errors = mutableListOf<String>()
                    for (r in routes) {
                        val error = probe(context, r)
                        if (error == null) {
                            log("$iface: ${r.host}:${OneImuProtocol.STREAM_PORT} answers (${r.kind.label}).")
                            return true
                        }
                        errors += "${r.kind.label}: $error"
                    }
                    log("$iface: ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} did not answer: ${errors.joinToString("; ")}")
                    putBack(context, iface, log)
                    return false
                }
                BringUpAction.APPLY_STATIC -> {
                    log("$iface: no DHCP address, asking for static ${GlassesEthernetPlan.STATIC_ADDRESS}.")
                    val before = ShizukuBridge.ethernet(context, "getconfig", iface)
                    logReply("getConfiguration($iface)", before, log)
                    val previous = if (before.ok) EthernetIpConfig.decode(before.value) else EthernetIpConfig.DEFAULT
                    val wanted = EthernetIpConfig(static = true, address = GlassesEthernetPlan.STATIC_ADDRESS)
                    val r = ShizukuBridge.ethernet(context, "config", iface, *wanted.encode().split(' ').toTypedArray())
                    logReply("updateConfiguration($iface, ${wanted.encode()})", r, log)
                    staticAccepted = r.ok
                    if (r.ok) changes.configChanged(iface, previous)
                    phase = EthernetPhase.STATIC
                    start = SystemClock.elapsedRealtime()
                }
                BringUpAction.NEXT_INTERFACE -> {
                    log("$iface: no address after ${if (staticAccepted == true) "the static request" else "DHCP"}, moving on.")
                    putBack(context, iface, log)
                    return false
                }
            }
        }
    }

    /** Shizuku is ready here, so each network has the app's own route and the shell helper's. */
    private fun routesOn(context: Context, iface: String): List<GlassesNetwork.Route> =
        GlassesNetwork.scan(context).routes(shizukuReady = true, refusedDirect = emptySet()).filter { it.iface == iface }

    /** Null when the glasses accept a TCP connection on the IMU port. */
    private fun probe(context: Context, route: GlassesNetwork.Route): String? = try {
        route.open(context, route.host, OneImuProtocol.STREAM_PORT, PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS).close()
        null
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /** Undoes a static address on an interface that turned out not to be the glasses. */
    private fun putBack(context: Context, iface: String, log: (String) -> Unit) {
        val action = changes.actions().filterIsInstance<RestoreAction.Config>().firstOrNull { it.iface == iface } ?: return
        val r = ShizukuBridge.ethernet(context, "config", iface, *action.config.encode().split(' ').toTypedArray())
        logReply("restore $iface to ${action.config.encode()}", r, log)
        if (r.ok) changes.configRestored(iface)
    }

    /** Puts back what [bringUp] changed, off the caller's thread, after any bring-up in progress. */
    fun restore(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext
        restorer.execute {
            synchronized(lock) {
                if (changes.empty) return@synchronized
                for (action in changes.actions()) {
                    when (action) {
                        is RestoreAction.Config -> {
                            val r = ShizukuBridge.ethernet(app, "config", action.iface, *action.config.encode().split(' ').toTypedArray())
                            logReply("Space closed: restore ${action.iface} to ${action.config.encode()}", r) { Log.i(TAG, it); log(it) }
                        }
                        RestoreAction.DisableEthernet -> {
                            val r = ShizukuBridge.ethernet(app, "enable", "false")
                            logReply("Space closed: setEthernetEnabled(false), as it was before", r) { Log.i(TAG, it); log(it) }
                        }
                    }
                }
                changes.clear()
            }
        }
    }

    private fun logReply(what: String, r: EthernetReply, log: (String) -> Unit) {
        val detail = r.log.filter { it.startsWith("calling") || it.startsWith("raw") || it.contains("refused") || it.contains("failed") }
        log("$what: ${if (r.ok) "OK" else "failed"}${if (r.value.isNotEmpty()) " (${r.value})" else ""}" +
            if (detail.isNotEmpty()) " [${detail.joinToString("; ")}]" else "")
    }
}
