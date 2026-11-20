package com.hayeco.flipspaces.pcvr

import android.content.Context
import android.os.Build
import com.hayeco.flipspaces.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The phone's side of the Pocket XR PC helper (pc-helper/PocketXrPcHelper.ps1): find it on the local network, be
 * allowed once on the PC, and ask it to start SteamVR. Blocking calls: run off the main and GL threads.
 */
class PcVrHelper(context: Context) {
    /** What the loading scene says while the helper works. */
    enum class Stage { LOOKING, NO_HELPER, ASK_ON_PC, REFUSED, NO_STEAM, NO_ALVR, STARTING_STEAMVR, STEAMVR_READY }

    class Pc(val address: InetAddress, val port: Int, val name: String)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("pcvr_helper", Context.MODE_PRIVATE)

    /** The helper's answer to a broadcast, or the PC it was found at last time; null if none answers. */
    fun find(): Pc? {
        val last = prefs.getString("address", null)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = FIND_WAIT_MS
            val ask = "POCKETXR-FIND 1".toByteArray()
            val targets = listOfNotNull(last?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }, InetAddress.getByName("255.255.255.255"))
            repeat(FIND_TRIES) {
                for (t in targets) runCatching { socket.send(DatagramPacket(ask, ask.size, t, PORT)) }
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val parts = String(packet.data, 0, packet.length).trim().split(" ", limit = 4)
                    if (parts.size >= 4 && parts[0] == "POCKETXR-HERE") {
                        prefs.edit().putString("address", packet.address.hostAddress).apply()
                        return Pc(packet.address, parts[2].toIntOrNull() ?: PORT, parts[3])
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    /**
     * The helper on the computer at [host] only, asked there directly: a PC screen pairs with one computer, and the first
     * helper to answer a broadcast may be another's (Mike's phone, 19 Nov 2026: pairing with his Mac found his Windows
     * PC's helper, so nobody typed the PIN). Null if that computer's helper does not answer.
     */
    fun findAt(host: InetAddress): Pc? {
        DatagramSocket().use { socket ->
            socket.soTimeout = FIND_WAIT_MS
            val ask = "POCKETXR-FIND 1".toByteArray()
            repeat(FIND_TRIES) {
                runCatching { socket.send(DatagramPacket(ask, ask.size, host, PORT)) }
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val parts = String(packet.data, 0, packet.length).trim().split(" ", limit = 4)
                    if (packet.address == host && parts.size >= 4 && parts[0] == "POCKETXR-HERE") {
                        return Pc(packet.address, parts[2].toIntOrNull() ?: PORT, parts[3])
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    private fun tokenFor(pc: Pc): String? = prefs.getString("token:${pc.name}", null)

    /** One request, one answer. */
    private fun ask(pc: Pc, request: JSONObject, waitMs: Int): JSONObject? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(pc.address, pc.port), CONNECT_MS)
            s.soTimeout = waitMs
            s.getOutputStream().write((request.toString() + "\n").toByteArray())
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return@use null
            JSONObject(line)
        }
    }.onFailure { Log.w(TAG, "PC helper on ${pc.name}: ${it.message}") }.getOrNull()

    /** The PC's user is asked once whether this phone may start SteamVR. True if allowed. */
    fun pair(pc: Pc): Boolean {
        val answer = ask(pc, JSONObject().put("op", "pair").put("phone", phoneName()), PAIR_WAIT_MS) ?: return false
        val token = answer.optString("token")
        if (!answer.optBoolean("ok") || token.isEmpty()) return false
        prefs.edit().putString("token:${pc.name}", token).apply()
        return true
    }

    /**
     * "off", "starting" or "ready"; "no_steam" or "no_alvr" when the PC is missing one; "unpaired" if the PC forgot this
     * phone; null if the helper doesn't answer. Starting also has the helper trust this phone in ALVR and set it up.
     */
    fun status(pc: Pc, start: Boolean): String? {
        val token = tokenFor(pc) ?: return "unpaired"
        val request = JSONObject().put("op", if (start) "start" else "status").put("token", token).put("alvr", alvrName)
        val answer = ask(pc, request, START_WAIT_MS) ?: return null
        if (!answer.optBoolean("ok")) {
            if (answer.optString("why") == "not paired") prefs.edit().remove("token:${pc.name}").apply()
            return "unpaired"
        }
        if (!answer.optBoolean("steam", true)) return "no_steam"
        if (!answer.optBoolean("alvr", true)) return "no_alvr"
        return answer.optString("steamvr")
    }

    /** The streamer is asked for a fresh full frame, when the picture has stopped (the freeze guard). */
    fun freshFrame(pc: Pc) {
        val token = tokenFor(pc) ?: return
        ask(pc, JSONObject().put("op", "idr").put("token", token), CONNECT_MS)
    }

    /** This phone has been allowed on [pc] before. */
    fun knows(pc: Pc): Boolean = tokenFor(pc) != null

    /**
     * PC screens: the helper types [pin] into the Apollo on HTTP port [port] for the user, and gives this phone the
     * permissions a PC screen needs. Null when it worked, else why not.
     */
    fun apolloPin(pc: Pc, pin: String, port: Int): String? {
        val token = tokenFor(pc) ?: return "not paired"
        val answer = ask(pc, JSONObject().put("op", "apollo-pin").put("token", token).put("pin", pin).put("name", phoneName()).put("port", port), APOLLO_WAIT_MS)
            ?: return "no answer"
        if (answer.optString("why") == "not paired") prefs.edit().remove("token:${pc.name}").apply()
        return if (answer.optBoolean("ok")) null else answer.optString("why", "refused")
    }

    /** PC screens: the helper asks the PC's user, then runs the PC screens setup there. True when it started. */
    fun screensSetup(pc: Pc): Boolean {
        val token = tokenFor(pc) ?: return false
        return ask(pc, JSONObject().put("op", "screens-setup").put("token", token).put("phone", phoneName()), SETUP_WAIT_MS)?.optBoolean("ok") == true
    }

    /** The PC found by the last [startSteamVr], for later requests. */
    @Volatile var lastPc: Pc? = null
        private set

    /** The phone's name in ALVR, sent so the helper can trust it. */
    var alvrName: String = ""

    /**
     * The whole start: find the PC, be allowed if needed, start SteamVR and wait until it is ready, telling [stage]
     * each step. Stops early when [stop] says so (the stream started by itself, or PC VR closed).
     */
    fun startSteamVr(stage: (Stage, String?) -> Unit, stop: () -> Boolean) {
        stage(Stage.LOOKING, null)
        val pc = find()
        if (pc == null) {
            Log.i(TAG, "PC helper: none answered on the local network")
            stage(Stage.NO_HELPER, null)
            return
        }
        Log.i(TAG, "PC helper: found on ${pc.name} (${pc.address.hostAddress})")
        lastPc = pc
        var state = status(pc, start = true)
        if (state == "unpaired") {
            stage(Stage.ASK_ON_PC, pc.name)
            if (!pair(pc)) {
                Log.i(TAG, "PC helper: not allowed on ${pc.name}")
                stage(Stage.REFUSED, pc.name)
                return
            }
            state = status(pc, start = true)
        }
        val deadline = System.currentTimeMillis() + READY_WAIT_MS
        while (!stop() && System.currentTimeMillis() < deadline) {
            when (state) {
                "ready" -> {
                    Log.i(TAG, "PC helper: SteamVR ready on ${pc.name}")
                    stage(Stage.STEAMVR_READY, pc.name)
                    return
                }
                null -> return
                "no_steam" -> {
                    stage(Stage.NO_STEAM, pc.name)
                    return
                }
                "no_alvr" -> {
                    stage(Stage.NO_ALVR, pc.name)
                    return
                }
                else -> stage(Stage.STARTING_STEAMVR, pc.name)
            }
            Thread.sleep(POLL_MS)
            state = status(pc, start = state == "off")
        }
    }

    /** The name the phone's owner gave it ("Mike's Z Flip7"), as the PC's question shows it. */
    private fun phoneName(): String =
        runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        private const val TAG = "PocketXrAlvr"
        const val PORT = 47610
        private const val FIND_WAIT_MS = 700
        private const val FIND_TRIES = 3
        private const val CONNECT_MS = 2_000
        /** The PC's user has a minute to answer, as the helper waits. */
        private const val PAIR_WAIT_MS = 65_000
        /** Starting may first set ALVR up over its API. */
        private const val START_WAIT_MS = 8_000
        private const val POLL_MS = 1_000L
        private const val READY_WAIT_MS = 120_000L
        /** Apollo takes the PIN and the permissions in a few seconds; the helper tries for up to 20. */
        // Up to 40 s on a Mac or Linux PC: its helper waits for the pairing to show in Sunshine before typing the PIN.
        private const val APOLLO_WAIT_MS = 50_000
        /** The PC's user has two minutes to say yes to the setup. */
        private const val SETUP_WAIT_MS = 125_000

        /** Where the PC helper is downloaded (its releases, tagged pc-helper-v*). */
        const val DOWNLOAD_URL = "https://github.com/pocket-xr/pocket-xr/releases?q=pc-helper"
    }
}
