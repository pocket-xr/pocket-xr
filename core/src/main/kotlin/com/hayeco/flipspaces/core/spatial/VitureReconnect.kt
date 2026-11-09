package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.10 (Discussion #24, second report, a VITURE Beast on 1.2.8): which of VITURE's USB devices has the motion sensor,
 * and the Beast's own axes.
 *
 * The Beast shows Android two devices on VITURE's vendor id: the glasses, 35CA:1211 "VITURE Beast XR Glasses" (HID on
 * interface 5, 40 byte vendor report descriptor, 119 reports a second), and "VITURE Microphone", 35CA:1102, whose HID
 * interfaces carry no motion. After a replug Android sometimes listed the microphone first; 1.2.8 took the first VITURE
 * device, asked for USB access to the microphone, opened its interface 2 and waited for reports that never came.
 */
object VitureDevices {
    /** The VITURE Beast XR Glasses. */
    const val BEAST = 0x1211

    /** VITURE devices with no motion sensor: the Beast's microphone. */
    val NO_IMU: Set<Int> = setOf(0x1102)

    /** Whether a VITURE device ([productId], Android's [productName]) can carry the glasses' motion sensor. */
    fun carriesImu(productId: Int, productName: String?): Boolean {
        if (productId in NO_IMU) return false
        val name = productName?.lowercase() ?: return true
        return "microphone" !in name && "camera" !in name
    }

    /** The VITURE device the reader opens: the first that can carry the motion sensor, in Android's order; null with none. */
    fun <T> pick(devices: List<T>, productId: (T) -> Int, productName: (T) -> String?): T? =
        devices.firstOrNull { carriesImu(productId(it), productName(it)) }

    /**
     * The sensor axis layout VITURE glasses start with until one is picked on the head tracking screen.
     *
     * The Beast's: "x right, y down, z front". At rest and upright its accelerometer read 0.04, -9.94, -0.74 m/s2 as the
     * sensor sends it (the reaction to gravity, which points up, along the sensor's -y), so the sensor's y points down
     * and, with x right, its z points forward. The tester's working setup in Discussion #24, Swap turn and tilt on with
     * the layout "x right, y front, z up", is this same turn: the swap takes the sensor's (x, y, z) to (x, z, -y) and that
     * layout takes it on to (x, -y, -z). With the switches off, this layout is the whole of it. Other VITURE models
     * keep the default axes and the two switches.
     */
    fun defaultAxes(productId: Int?): AxisMap = if (productId == BEAST) AxisMap.XRIGHT_YDOWN_ZFRONT else GlassesModel.VITURE.defaultAxes

    /** The two switches VITURE glasses start with: both off, the Beast's included (its layout does the turning). */
    fun defaultSwitches(@Suppress("UNUSED_PARAMETER") productId: Int?): VitureAxes = VitureAxes()
}

/**
 * 1.2.10 (Discussion #24, second report): when the experimental VITURE reader asks for USB access, opens the glasses,
 * keeps reading or lets them go. One per process; the reader thread asks [next] each round.
 *
 * What the tester's logs showed in 1.2.8: the USB prompt was asked once per device name for the life of the process,
 * so after a replug that kept the name, or a dismissed prompt, nothing asked again and the reader waited silently; a
 * changed axis switch stopped the reader and opened the glasses again. Here:
 * - USB access is checked before every open. Without it the prompt is asked, once for each plug-in, each time the
 *   switch is turned on, and again after access was lost on a device that had it.
 * - The axis switches apply to the open stream ([axesChanged]); nothing is closed or opened for them.
 * - A detach, a lost permission, another device or the switch turned off closes the open link ([Step.CLOSE]).
 */
class VitureReconnect {
    enum class Step {
        /** Nothing to do: the reader is off or no VITURE glasses are plugged in. */
        IDLE,

        /** Close the open link and release the interface (detached, access lost, switched off, other glasses). */
        CLOSE,

        /** Ask Android for USB access (its prompt). */
        ASK,

        /** Access was asked for on this plug-in: wait for the answer, without asking again. */
        WAIT_FOR_ANSWER,

        /** Open the glasses and start the stream. */
        OPEN,

        /** The link is open on these glasses: keep reading. */
        READ,
    }

    /** The glasses as the reader sees them this round: Android's device name, and whether Pocket XR has USB access. */
    data class Device(val name: String, val permitted: Boolean)

    var enabled = false
        private set

    /** The device name the open link is on; null with none open. */
    var openOn: String? = null
        private set

    /** The device name USB access was last asked for; cleared by a plug-in, an unplug, the switch on or an open. */
    var askedFor: String? = null
        private set

    var axes: VitureAxes = VitureAxes()
        private set

    /** The Experimental VITURE head tracking switch. Turned on, the prompt may be asked again. */
    fun setEnabled(on: Boolean) {
        if (on && !enabled) askedFor = null
        enabled = on
    }

    /** USB_DEVICE_ATTACHED for VITURE glasses: a new plug-in, so access is asked for again. */
    fun attached() {
        askedFor = null
    }

    /** USB_DEVICE_DETACHED: Android drops the access with the device. */
    fun detached() {
        askedFor = null
    }

    /** New axis switches. True when a link is open, which takes them as it reads; nothing reopens either way. */
    fun axesChanged(next: VitureAxes): Boolean {
        axes = next
        return openOn != null
    }

    /** What to do now for [device] (null with no VITURE glasses on USB). */
    fun next(device: Device?): Step {
        val open = openOn
        if (open != null && (!enabled || device == null || device.name != open || !device.permitted)) return Step.CLOSE
        if (!enabled || device == null) return Step.IDLE
        if (!device.permitted) {
            if (askedFor == device.name) return Step.WAIT_FOR_ANSWER
            askedFor = device.name
            return Step.ASK
        }
        return if (open == device.name) Step.READ else Step.OPEN
    }

    /** The link opened on [name]: a later loss of access asks again. */
    fun opened(name: String) {
        openOn = name
        askedFor = null
    }

    /** The link was closed (or failed to open). */
    fun closed() {
        openOn = null
    }
}

/**
 * 1.2.10 (Discussion #24, second report): a command to VITURE glasses over their HID interface, with any report they
 * still hold read first.
 *
 * In 1.2.8 the first start after a plug-in always worked and every later one, in the same plug-in, was "not accepted"
 * until the glasses were unplugged: after the switch, an axis change or the Space's restart. The motion recording made
 * between two such failures told why. It reads the interface for 5 seconds before it sends the start; its very first
 * read returned `10 00 01 23 01 00 00 00 00`, a frame the glasses had kept since the reader's stop (message 0x2301,
 * one byte), and its start was then accepted and streamed at 120 reports a second. The reader sent its start right
 * after claiming the interface, with that answer still waiting, and the glasses took no command until it was read.
 * So: read what is waiting before a command, read the answer after it, and try again a few times.
 */
object VitureHidCommands {
    /** How a command reaches the glasses; the app's is the claimed HID interface. */
    interface Pipe {
        /** One input report into [buf]; its size, 0 or less when none came within [timeoutMs]. */
        fun read(buf: ByteArray, timeoutMs: Int): Int

        /** [packet] to the interrupt OUT endpoint; true when all of it went. */
        fun write(packet: ByteArray, timeoutMs: Int): Boolean

        /** [packet] as a HID SET_REPORT on the control pipe; true when all of it went. */
        fun setReport(packet: ByteArray, timeoutMs: Int): Boolean
    }

    /** What happened to a command, for the log and the diagnostics. */
    data class Result(
        val accepted: Boolean,
        val tries: Int,
        /** Reports read before and between the tries (answers the glasses still held, or the stream). */
        val drained: Int,
        /** Raw motion samples among them: the stream is already running. */
        val rawSeen: Int,
        val viaControl: Boolean,
    ) {
        fun line(): String = when {
            accepted && viaControl -> "accepted on the control pipe after $tries tries"
            accepted -> "accepted" + (if (tries > 1) " on try $tries" else "")
            rawSeen > 0 -> "not accepted, but the stream is already running ($rawSeen motion reports)"
            else -> "not accepted after $tries tries"
        } + if (drained > 0) "; $drained waiting reports read first" else ""
    }

    const val TRIES = 3

    /** Reports read at most before one command: far more than the glasses hold. */
    const val MAX_DRAIN = 64

    /** Raw samples that show the stream is running: no need to read on. */
    const val RAW_ENOUGH = 8

    /** Reports read at most for a command's answer. */
    const val MAX_ANSWER_READS = 32

    /**
     * Reads what the glasses hold, until a read times out after [quietMs], [RAW_ENOUGH] raw samples came (the stream is
     * running) or [MAX_DRAIN]; how many reports, and how many were raw samples.
     */
    fun drain(pipe: Pipe, quietMs: Int, buf: ByteArray = ByteArray(VitureImuProtocol.PACKET_BYTES)): Pair<Int, Int> {
        var n = 0
        var raw = 0
        while (n < MAX_DRAIN && raw < RAW_ENOUGH) {
            val got = pipe.read(buf, quietMs)
            if (got <= 0) break
            n++
            if (VitureImuProtocol.readFrame(buf, got)?.msgId == VitureImuProtocol.MSG_RAW) raw++
        }
        return n to raw
    }

    /** Reads a command's answer: until a frame that is not a raw sample, a quiet [quietMs] or [MAX_ANSWER_READS]. */
    fun readAnswer(pipe: Pipe, quietMs: Int, buf: ByteArray = ByteArray(VitureImuProtocol.PACKET_BYTES)): VitureImuProtocol.Frame? {
        repeat(MAX_ANSWER_READS) {
            val got = pipe.read(buf, quietMs)
            if (got <= 0) return null
            val f = VitureImuProtocol.readFrame(buf, got)
            if (f != null && f.msgId != VitureImuProtocol.MSG_RAW) return f
        }
        return null
    }

    /**
     * Sends [frame] (padded to 64 bytes): what is waiting is read first, then the interrupt OUT is tried up to [TRIES]
     * times with a read between, then the control pipe once. The answer is read after it is accepted.
     */
    fun send(pipe: Pipe, frame: ByteArray, timeoutMs: Int, quietMs: Int = 30): Result {
        val packet = frame.copyOf(VitureImuProtocol.PACKET_BYTES)
        val buf = ByteArray(VitureImuProtocol.PACKET_BYTES)
        var drained = 0
        var raw = 0
        var tries = 0
        fun readWaiting() {
            val (n, r) = drain(pipe, quietMs, buf)
            drained += n
            raw += r
        }
        while (tries < TRIES) {
            readWaiting()
            tries++
            if (pipe.write(packet, timeoutMs)) {
                readAnswer(pipe, quietMs, buf)
                return Result(true, tries, drained, raw, viaControl = false)
            }
        }
        readWaiting()
        tries++
        if (pipe.setReport(packet, timeoutMs)) {
            readAnswer(pipe, quietMs, buf)
            return Result(true, tries, drained, raw, viaControl = true)
        }
        return Result(false, tries, drained, raw, viaControl = false)
    }

    /** Whether a start's [result] lets the reader go on: taken, or the stream is already coming. */
    fun streamWillCome(result: Result): Boolean = result.accepted || result.rawSeen > 0
}
