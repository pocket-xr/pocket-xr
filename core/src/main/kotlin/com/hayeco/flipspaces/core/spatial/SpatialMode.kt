package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus

/** Where the head tracker got to, step by step, with the exact error when a step failed. */
sealed interface HeadTrackingStatus {
    val summary: String

    data object Off : HeadTrackingStatus {
        override val summary = "Head tracking is off."
    }

    /**
     * 1.1.14 (a tester asked for a way to turn head tracking on and off): switched off with Settings' Head tracking
     * switch. Nothing is read from the glasses' motion sensor until it is on again.
     */
    data object TurnedOff : HeadTrackingStatus {
        override val summary = "Head tracking is turned off in Settings. Windows stay in front of you and turn with your head."
    }

    data object Searching : HeadTrackingStatus {
        override val summary = "Looking for the glasses' USB network."
    }

    /**
     * No Android network reaches the glasses. [networks] lists what Android does have.
     *
     * 1.1.10 (a Pixel 8a's diagnostics said "One UI may not bring up..."): [system] is the phone's own system
     * ([PhoneSystem.name]): One UI only on a Samsung, else Android.
     */
    data class NoNetwork(val networks: List<String>, val system: String = "Android") : HeadTrackingStatus {
        override val summary =
            "No USB network to the glasses. Android lists: " + (networks.ifEmpty { listOf("no networks") }.joinToString("; ")) +
                ". $system may not have brought up the glasses' NCM or ECM interface."
    }

    /**
     * 1.1.10 (USB 3318:0442 on a Pixel 8a): the glasses carry their IMU over USB HID ([ImuTransport.USB_HID]), not a USB
     * network, so no network is looked for and none is blamed. 1.1.12: the Air family is read over HID ([AirImuProtocol]);
     * this is for the other HID glasses, which have no reader yet.
     */
    data class HidGlasses(val vendorId: Int, val productId: Int, val interfaces: List<String>) : HeadTrackingStatus {
        override val summary =
            "These glasses (USB %04X:%04X) send head tracking over USB HID, like XREAL's Air family, not over a USB network. ".format(java.util.Locale.ROOT, vendorId, productId) +
                "Pocket XR reads it over HID only on the Air, Air 2, Air 2 Pro and Air 2 Ultra, so head tracking is not available on these glasses yet; the Space stays head locked. " +
                "USB interfaces: " + interfaces.ifEmpty { listOf("none listed") }.joinToString(", ") + "."
    }

    /**
     * 1.2.6 (Discussion #24, VITURE Beast: "no head tracking", while the log blamed a missing USB network): VITURE glasses
     * have no USB network; their motion sensor is on USB HID in VITURE's own protocol. Said plainly, with what to try.
     */
    data class VitureNotYet(val productId: Int, val experimentalOn: Boolean = false) : HeadTrackingStatus {
        override val summary =
            "Head tracking isn't supported yet on VITURE glasses (USB %04X:%04X). The Space stays in front of you and turns with your head. ".format(java.util.Locale.ROOT, VitureImuProtocol.VENDOR, productId) +
                if (experimentalOn) "The experimental VITURE reader is on but got no motion from these glasses. " +
                    "Record motion for 20 seconds on the head tracking screen and send logs, so they can be added."
                else "You can try the experimental VITURE reader on the head tracking screen, or record motion for 20 seconds there and send logs."
    }

    data class Connecting(val network: String) : HeadTrackingStatus {
        override val summary = "Connecting to ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} over $network."
    }

    /** The TCP connection failed: [error] is the exception class and message, word for word. */
    data class ConnectFailed(val network: String, val error: String) : HeadTrackingStatus {
        override val summary = "TCP connect to ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} over $network failed: $error"
    }

    /** Connected, but no sensor report arrived. */
    data class NoReports(val network: String, val seconds: Int, val bytes: Long, val counts: FramerCounts, val hint: String = ONE_FAMILY_HINT) : HeadTrackingStatus {
        override val summary = "Connected over $network but no sensor reports in $seconds s ($bytes bytes received, " +
            "${counts.badLength} frames of another length, ${counts.unknownType} of another type). $hint"
    }

    /** 1.1.12: Air family glasses found on USB, but Android has not given Pocket XR access to them yet. */
    data class UsbPermission(val glasses: String) : HeadTrackingStatus {
        override val summary = "Found the $glasses on USB; waiting for USB access for head tracking. Allow Pocket XR in Android's prompt " +
            "(unplug and plug the glasses in again to see it again). The Space stays head locked until then."
    }

    /** 1.1.12: the Air family's motion sensor could not be opened over USB: [error] word for word. */
    data class UsbFailed(val glasses: String, val error: String) : HeadTrackingStatus {
        override val summary = "Could not open the $glasses' motion sensor over USB: $error"
    }

    companion object {
        const val ONE_FAMILY_HINT = "This firmware may not serve the One family stream."
        const val AIR_FAMILY_HINT = "The glasses' motion sensor did not answer the start command over USB HID."
        const val VITURE_HINT = "These VITURE glasses did not answer the documented start command; head tracking isn't supported on them yet. Record motion for 20 seconds on the head tracking screen and send logs."
    }

    data class Streaming(val network: String, val rateHz: Double) : HeadTrackingStatus {
        // 0.5.48: built when read, not with every status (the head thread made one a report, 1000 a second).
        override val summary: String get() = "Streaming over $network at %.0f reports a second.".format(rateHz)
    }

    /** The stream stopped after it had worked. */
    data class Lost(val network: String, val error: String) : HeadTrackingStatus {
        override val summary = "Lost the sensor stream over $network: $error. Retrying."
    }

    /**
     * 0.5.6: no glasses network has a route to the glasses' address; nothing is tried until the
     * network changes or the glasses are plugged in again.
     */
    data class NoRoute(val networks: List<String>) : HeadTrackingStatus {
        override val summary = "No route to ${OneImuProtocol.HOST} on " + networks.ifEmpty { listOf("any glasses network") }.joinToString(", ") +
            ". Not retrying until the glasses' network changes or they are plugged in again; the Space stays head locked."
    }

    /** 0.5.6: the last round failed for [why]; the next try is in [inSeconds] (backoff up to a minute). */
    data class RetryLater(val why: String, val inSeconds: Int) : HeadTrackingStatus {
        override val summary = "$why Next try in $inSeconds s."
    }

    val tracking: Boolean get() = this is Streaming
}

/**
 * 0.5.22 (at the unplug the stream "ended after 263.049 s: Connected ... but no sensor
 * reports in 263 s (33638154 bytes received ...)", after a steady 1000 Hz stream all session): a stream
 * that goes quiet. "No sensor reports" is kept for a stream that never delivered one; a stream that worked
 * and then stopped is [HeadTrackingStatus.Lost], with how long it has been silent.
 */
object HeadSilence {
    fun status(
        network: String, everStreamed: Boolean, silentS: Long, sinceStartS: Int, bytes: Long, counts: FramerCounts,
        hint: String = HeadTrackingStatus.ONE_FAMILY_HINT,
    ): HeadTrackingStatus =
        if (everStreamed) {
            HeadTrackingStatus.Lost(network, "no sensor report for $silentS s after it had streamed ($bytes bytes received in $sinceStartS s)")
        } else {
            HeadTrackingStatus.NoReports(network, sinceStartS, bytes, counts, hint)
        }
}

enum class SpatialMode(val label: String) {
    SPATIAL("Spatial: app panels stay put as you turn your head"),
    HEAD_LOCKED("Head locked: app panels in front of you, turned with the trackpad"),
    FLAT("Flat: apps open as DeX windows on the glasses"),
}

data class ModeChoice(val mode: SpatialMode, val reasons: List<String>)

/** Picks the best mode for what is available and says why the better ones are not. */
object SpatialModeChooser {
    fun choose(head: HeadTrackingStatus, shizuku: ShizukuStatus): ModeChoice {
        val reasons = mutableListOf<String>()
        if (shizuku != ShizukuStatus.READY) {
            reasons += "${shizuku.label}: app panels need Shizuku to create their virtual displays."
            return ModeChoice(SpatialMode.FLAT, reasons)
        }
        if (!head.tracking) {
            reasons += "No head tracking: ${head.summary}"
            return ModeChoice(SpatialMode.HEAD_LOCKED, reasons)
        }
        return ModeChoice(SpatialMode.SPATIAL, reasons)
    }
}

/** One five second window of renderer timings. */
data class PerfSummary(
    val fps: Double,
    val frameCpuMs: Double,
    val panels: List<PanelPerf>,
) {
    fun line(): String = "%.0f fps, frame %.2f ms CPU".format(fps, frameCpuMs) +
        panels.joinToString("") { "; panel ${it.panelId}: %.0f fps, %.2f ms".format(it.fps, it.costMs) }
}

data class PanelPerf(val panelId: Int, val fps: Double, val costMs: Double)

/**
 * Counts scene frames and, per panel, new app frames and the CPU time spent latching and drawing
 * them. Called from the GL thread only.
 */
class SpacePerf(private val windowNs: Long = 5_000_000_000L) {
    private var start = Long.MIN_VALUE
    private var frames = 0
    private var frameNs = 0L
    private val panelFrames = HashMap<Int, Int>()
    private val panelNs = HashMap<Int, Long>()

    var last: PerfSummary? = null
        private set

    fun panel(id: Int, costNs: Long, newFrame: Boolean) {
        panelNs[id] = (panelNs[id] ?: 0L) + costNs
        if (newFrame) panelFrames[id] = (panelFrames[id] ?: 0) + 1
    }

    /** Ends a frame. Returns a summary when a window completes. */
    fun frame(nowNs: Long, costNs: Long): PerfSummary? {
        if (start == Long.MIN_VALUE) start = nowNs
        frames++
        frameNs += costNs
        val elapsed = nowNs - start
        if (elapsed < windowNs) return null
        val seconds = elapsed / 1e9
        val ids = (panelNs.keys + panelFrames.keys).sorted()
        val summary = PerfSummary(
            fps = frames / seconds,
            frameCpuMs = frameNs / 1e6 / frames,
            panels = ids.map { PanelPerf(it, (panelFrames[it] ?: 0) / seconds, (panelNs[it] ?: 0L) / 1e6 / frames) },
        )
        start = nowNs
        frames = 0
        frameNs = 0
        panelFrames.clear()
        panelNs.clear()
        last = summary
        return summary
    }
}
