package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 1.2.8 (Discussion #24, VITURE Beast glasses: Pocket XR had nothing to read their motion sensor with, and the logs had
 * nothing about it): the head tracking screen's opt-in "Record motion for 20 seconds". For glasses that are not XREAL's it
 * keeps every HID input report the glasses send, with the phone's time it came at, plus each HID interface's report
 * descriptor, as one plain text file the next Send logs takes along. Nothing in it names the person: USB ids, interface
 * numbers, byte counts and the bytes the motion sensor sent.
 */
object HidRecording {
    const val SECONDS = 20

    /** For VITURE glasses the documented start command goes out this long into the recording, so both halves show. */
    const val START_COMMAND_AT_MS = 5_000L

    /** Reports kept at most (20 s at 1000 a second on two interfaces), so the file stays a few MB. */
    const val MAX_REPORTS = 40_000

    data class Report(val hostNs: Long, val iface: Int, val bytes: ByteArray, val size: Int = bytes.size)

    /** One HID interface's report descriptor, or why it was not read. */
    data class Descriptor(val iface: Int, val bytes: ByteArray?, val error: String? = null)

    /** "12.345 ms": [ns] after [startNs]. */
    fun ms(ns: Long, startNs: Long): String = "%.3f".format(Locale.ROOT, (ns - startNs) / 1e6)

    fun hex(b: ByteArray, size: Int = b.size): String = buildString(size * 3) {
        for (i in 0 until size) {
            if (i > 0) append(' ')
            append("%02X".format(Locale.ROOT, b[i].toInt() and 0xFF))
        }
    }

    fun descriptorLine(d: Descriptor): String = when {
        d.bytes != null -> "interface ${d.iface}: ${d.bytes.size} bytes: ${hex(d.bytes)}"
        else -> "interface ${d.iface}: not read (${d.error ?: "no answer"})"
    }

    /** The reports per interface and their rate, one line. */
    fun summary(reports: List<Report>, seconds: Double): String {
        if (reports.isEmpty()) return "no HID input reports in %.1f s".format(Locale.ROOT, seconds)
        val by = reports.groupBy { it.iface }.toSortedMap()
        return "${reports.size} HID input reports in %.1f s: ".format(Locale.ROOT, seconds) + by.entries.joinToString("; ") { (i, rs) ->
            val sizes = rs.map { it.size }.distinct().sorted().joinToString("/")
            "interface $i %d (%.0f a second, %s bytes)".format(Locale.ROOT, rs.size, rs.size / seconds.coerceAtLeast(0.001), sizes)
        }
    }

    /**
     * The whole file: what was recorded, the descriptors, the commands sent (each with its time), then one line a report:
     * its time in ms from the start, the interface, the size and the bytes in hex.
     */
    fun text(
        device: String,
        appVersion: String,
        timeUtc: String,
        descriptors: List<Descriptor>,
        sent: List<Pair<Long, String>>,
        reports: List<Report>,
        startNs: Long,
        endNs: Long,
        notes: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Pocket XR motion recording (raw HID input reports)")
        appendLine("Made $timeUtc (UTC), Pocket XR $appVersion")
        appendLine("Glasses: $device")
        appendLine(summary(reports, (endNs - startNs) / 1e9))
        if (reports.size >= MAX_REPORTS) appendLine("Stopped keeping reports at $MAX_REPORTS.")
        notes.forEach { appendLine(it) }
        appendLine()
        appendLine("HID report descriptors:")
        if (descriptors.isEmpty()) appendLine("- no HID interface")
        descriptors.forEach { appendLine("- " + descriptorLine(it)) }
        appendLine()
        appendLine("Sent to the glasses:")
        if (sent.isEmpty()) appendLine("- nothing")
        sent.forEach { (t, what) -> appendLine("- at ${ms(t, startNs)} ms: $what") }
        appendLine()
        appendLine("Reports (ms from the start, interface, bytes, hex):")
        reports.forEach { r -> appendLine("${ms(r.hostNs, startNs)} if${r.iface} ${r.size}: ${hex(r.bytes, r.size)}") }
    }

    /** The descriptor lines a recording's text holds, for the Glasses report; empty when it has none. */
    fun descriptorsIn(text: String): List<String> =
        text.lineSequence().dropWhile { it != "HID report descriptors:" }.drop(1).takeWhile { it.startsWith("- ") }.map { it.removePrefix("- ") }.toList()
}

/**
 * 1.2.8: the USB makers of glasses Pocket XR knows by vendor id, for the Glasses report, Send logs and the head tracking
 * screen, which up to 1.2.5 listed only XREAL's devices.
 */
object GlassesVendors {
    const val XREAL = 0x3318
    const val VITURE = VitureImuProtocol.VENDOR

    fun name(vendorId: Int): String? = when (vendorId) {
        XREAL -> "XREAL"
        VITURE -> "VITURE"
        else -> null
    }

    /** The glasses maker named by a display's name, for glasses Android shows before their USB device is read. */
    fun fromDisplayName(name: String?): String? {
        val n = name.orEmpty().lowercase(Locale.ROOT)
        return when {
            "viture" in n -> "VITURE"
            "xreal" in n || "nreal" in n -> "XREAL"
            else -> null
        }
    }

    /**
     * Which device a motion recording reads: a VITURE device first, then any other device that is not XREAL's (the
     * XREAL ones have their own readers) and has a HID interface. Null when there is none.
     */
    fun recordable(usb: List<GlassesReport.Usb>): GlassesReport.Usb? =
        usb.firstOrNull { it.vendorId == VITURE } ?: usb.firstOrNull { it.vendorId != XREAL && HeadImuTransport.hasHid(it.infos) }

    /** One line for a USB device: ids, name, maker and every interface with its class, protocol and endpoints. */
    fun line(u: GlassesReport.Usb): String =
        "%04X:%04X \"%s\": %s".format(Locale.ROOT, u.vendorId, u.productId, u.name, u.infos.joinToString(", ") { it.detail() }.ifEmpty { "no interfaces listed" }) +
            (name(u.vendorId)?.let { " [$it glasses]" } ?: "")
}
