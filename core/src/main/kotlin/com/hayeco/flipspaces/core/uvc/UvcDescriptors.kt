package com.hayeco.flipspaces.core.uvc

/**
 * Parses the USB configuration descriptors of a camera into the UVC video streaming formats it
 * offers. Written from the USB Video Class 1.5 specification (sections 3.7 to 3.9) and the UVC
 * payload format specifications; nothing vendor specific.
 */

enum class UvcFormatKind(val label: String) {
    UNCOMPRESSED("Uncompressed"),
    MJPEG("MJPEG"),
    FRAME_BASED("Frame based"),
    H264("H.264 (UVC 1.5)"),
    OTHER("Other"),
}

/** One frame size of a format, with its frame intervals in 100 ns units. */
data class UvcFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val defaultIntervalUnits: Long,
    /** Discrete intervals, or for a continuous range: min, max and step. */
    val intervalUnits: List<Long>,
    val continuous: Boolean = false,
    val maxFrameBytes: Long = 0,
) {
    val maxFps: Double get() = (intervalUnits.minOrNull()?.takeIf { it > 0 } ?: defaultIntervalUnits).let { if (it > 0) 1e7 / it else 0.0 }
    val defaultFps: Double get() = if (defaultIntervalUnits > 0) 1e7 / defaultIntervalUnits else 0.0
    val label: String get() = "${width}x$height up to ${"%.0f".format(maxFps)} fps"
}

data class UvcFormat(
    val index: Int,
    val kind: UvcFormatKind,
    /** Four character code from the format GUID (YUY2, NV12, H264, HEVC...) or MJPG. */
    val fourcc: String,
    val bitsPerPixel: Int,
    val frames: List<UvcFrame>,
    val defaultFrameIndex: Int = 1,
) {
    val label: String get() = "$fourcc (${kind.label})"
}

data class UvcEndpoint(
    val address: Int,
    val bulk: Boolean,
    val maxPacketSize: Int,
    /** 1.6.9: an isochronous endpoint (the XREAL Air 2 Ultra's cameras stream this way). */
    val iso: Boolean = false,
    /** 1.6.9: the endpoint's wMaxPacketSize as sent, with the extra transactions per microframe in bits 11 and 12. */
    val rawMaxPacketSize: Int = maxPacketSize,
) {
    val isIn: Boolean get() = address and 0x80 != 0

    /** Bytes an isochronous endpoint carries each (micro)frame: the packet size times 1 to 3 transactions. */
    val payloadPerInterval: Int get() = (rawMaxPacketSize and 0x7FF) * (1 + ((rawMaxPacketSize shr 11) and 0x03))
}

/** One alternate setting of the streaming interface and its endpoint, if any. */
data class UvcAltSetting(val alternate: Int, val endpoint: UvcEndpoint?)

data class UvcStreamingInterface(
    val interfaceNumber: Int,
    val formats: List<UvcFormat>,
    val altSettings: List<UvcAltSetting>,
    /** The endpoint the input header names, which carries the video. */
    val endpointAddress: Int,
) {
    /** Bulk streaming uses alternate setting 0; isochronous needs a non-zero one. */
    val bulkEndpoint: UvcEndpoint?
        get() = altSettings.mapNotNull { it.endpoint }.firstOrNull { it.address == endpointAddress && it.bulk }

    /**
     * 1.6.9: the isochronous alternate setting for payloads of up to [payloadBytes]: the smallest that carries them,
     * else the largest there is; null when the interface has no isochronous one.
     */
    fun isoAltFor(payloadBytes: Int): UvcAltSetting? {
        val iso = altSettings.filter { it.alternate != 0 && it.endpoint?.iso == true && it.endpoint.isIn }
        return iso.filter { it.endpoint!!.payloadPerInterval >= payloadBytes }.minByOrNull { it.endpoint!!.payloadPerInterval }
            ?: iso.maxByOrNull { it.endpoint!!.payloadPerInterval }
    }
}

data class UvcCamera(
    /** UVC version from the class specific VideoControl header, as BCD (0x0110 = 1.1). */
    val uvcVersion: Int,
    val controlInterface: Int,
    val streaming: List<UvcStreamingInterface>,
) {
    val versionLabel: String
        get() = "${uvcVersion shr 8}.${uvcVersion shr 4 and 0xF}" + (uvcVersion and 0xF).let { if (it != 0) "$it" else "" }

    /** Human readable list of everything the camera offers, for the setup screen and logs. */
    fun describe(): String = buildString {
        append("UVC $versionLabel, control interface $controlInterface")
        streaming.forEach { s ->
            val ep = s.bulkEndpoint
            append("\nStreaming interface ${s.interfaceNumber}, endpoint 0x%02X".format(s.endpointAddress))
            append(if (ep != null) " (bulk, ${ep.maxPacketSize} B packets)" else " (isochronous)")
            s.formats.forEach { f ->
                append("\n  Format ${f.index}: ${f.label}")
                f.frames.forEach { fr -> append("\n    Frame ${fr.index}: ${fr.label}") }
            }
        }
    }
}

object UvcDescriptorParser {
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val CS_INTERFACE = 0x24
    private const val CC_VIDEO = 0x0E
    private const val SC_VIDEOCONTROL = 0x01
    private const val SC_VIDEOSTREAMING = 0x02

    private const val VC_HEADER = 0x01
    private const val VS_INPUT_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FORMAT_FRAME_BASED = 0x10
    private const val VS_FRAME_FRAME_BASED = 0x11
    private const val VS_FORMAT_H264 = 0x13
    private const val VS_FRAME_H264 = 0x14

    /**
     * Parses raw descriptors (as from UsbDeviceConnection.getRawDescriptors: the device
     * descriptor followed by the active configuration). Returns null when there is no video
     * interface. Malformed descriptors end the walk instead of throwing.
     */
    fun parse(raw: ByteArray): UvcCamera? {
        var uvcVersion = 0
        var controlInterface = -1
        val streaming = linkedMapOf<Int, StreamingBuilder>()
        var current: StreamingBuilder? = null
        var currentAlt = 0
        var inVideo = false
        var format: FormatBuilder? = null

        fun closeFormat() {
            format?.let { current?.formats?.add(it.build()) }
            format = null
        }

        var i = 0
        while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(raw, i + 1)
            val d = raw.copyOfRange(i, i + len)
            when (type) {
                DT_INTERFACE -> if (len >= 9) {
                    closeFormat()
                    val number = u8(d, 2)
                    currentAlt = u8(d, 3)
                    val cls = u8(d, 5)
                    val sub = u8(d, 6)
                    inVideo = cls == CC_VIDEO
                    current = null
                    if (inVideo && sub == SC_VIDEOCONTROL && controlInterface < 0) controlInterface = number
                    if (inVideo && sub == SC_VIDEOSTREAMING) {
                        current = streaming.getOrPut(number) { StreamingBuilder(number) }
                        current!!.alts.add(UvcAltSetting(currentAlt, null))
                    }
                }
                DT_ENDPOINT -> if (len >= 7) {
                    current?.let { s ->
                        val ep = UvcEndpoint(u8(d, 2), u8(d, 3) and 0x03 == 0x02, u16(d, 4) and 0x7FF, iso = u8(d, 3) and 0x03 == 0x01, rawMaxPacketSize = u16(d, 4))
                        val idx = s.alts.indexOfLast { it.alternate == currentAlt }
                        if (idx >= 0 && s.alts[idx].endpoint == null) s.alts[idx] = UvcAltSetting(currentAlt, ep)
                    }
                }
                CS_INTERFACE -> if (inVideo && len >= 3) {
                    val subtype = u8(d, 2)
                    val s = current
                    if (s == null) {
                        if (subtype == VC_HEADER && len >= 5) uvcVersion = u16(d, 3)
                    } else {
                        when (subtype) {
                            VS_INPUT_HEADER -> if (len >= 7) s.endpoint = u8(d, 6)
                            VS_FORMAT_UNCOMPRESSED, VS_FORMAT_FRAME_BASED -> if (len >= 23) {
                                closeFormat()
                                val kind = if (subtype == VS_FORMAT_UNCOMPRESSED) UvcFormatKind.UNCOMPRESSED else UvcFormatKind.FRAME_BASED
                                format = FormatBuilder(u8(d, 3), kind, fourccFromGuid(d, 5), u8(d, 21), u8(d, 22))
                            }
                            VS_FORMAT_MJPEG -> if (len >= 7) {
                                closeFormat()
                                format = FormatBuilder(u8(d, 3), UvcFormatKind.MJPEG, "MJPG", 0, u8(d, 6))
                            }
                            VS_FORMAT_H264 -> if (len >= 5) {
                                closeFormat()
                                format = FormatBuilder(u8(d, 3), UvcFormatKind.H264, "H264", 0, if (len > 5) u8(d, 5) else 1)
                            }
                            VS_FRAME_UNCOMPRESSED, VS_FRAME_MJPEG -> if (len >= 26) {
                                format?.frames?.add(frame(d, len, maxFrameAt = 17, defaultAt = 21, typeAt = 25, listAt = 26))
                            }
                            VS_FRAME_FRAME_BASED -> if (len >= 26) {
                                format?.frames?.add(frame(d, len, maxFrameAt = -1, defaultAt = 17, typeAt = 21, listAt = 26))
                            }
                            VS_FRAME_H264 -> if (len >= 8) {
                                format?.frames?.add(UvcFrame(u8(d, 3), u16(d, 4), u16(d, 6), 0, emptyList()))
                            }
                            else -> Unit
                        }
                    }
                }
                else -> Unit
            }
            i += len
        }
        closeFormat()
        if (controlInterface < 0 && streaming.isEmpty()) return null
        return UvcCamera(uvcVersion, controlInterface, streaming.values.map { it.build() })
    }

    private fun frame(d: ByteArray, len: Int, maxFrameAt: Int, defaultAt: Int, typeAt: Int, listAt: Int): UvcFrame {
        val type = u8(d, typeAt)
        val count = if (type == 0) 3 else type
        val intervals = (0 until count).mapNotNull { k ->
            val at = listAt + 4 * k
            if (at + 4 <= len) u32(d, at) else null
        }
        return UvcFrame(
            index = u8(d, 3),
            width = u16(d, 5),
            height = u16(d, 7),
            defaultIntervalUnits = u32(d, defaultAt),
            intervalUnits = intervals,
            continuous = type == 0,
            maxFrameBytes = if (maxFrameAt >= 0) u32(d, maxFrameAt) else 0,
        )
    }

    /** The first four bytes of a UVC format GUID are its four character code (YUY2, NV12, H264...). */
    fun fourccFromGuid(d: ByteArray, at: Int): String =
        (0 until 4).map { (d[at + it].toInt() and 0xFF).toChar() }
            .joinToString("")
            .let { code -> if (code.all { it in ' '..'~' }) code.trim() else "%02X%02X%02X%02X".format(d[at], d[at + 1], d[at + 2], d[at + 3]) }

    private class FormatBuilder(val index: Int, val kind: UvcFormatKind, val fourcc: String, val bpp: Int, val defaultFrame: Int) {
        val frames = mutableListOf<UvcFrame>()
        fun build() = UvcFormat(index, kind, fourcc, bpp, frames.toList(), defaultFrame)
    }

    private class StreamingBuilder(val number: Int) {
        val formats = mutableListOf<UvcFormat>()
        val alts = mutableListOf<UvcAltSetting>()
        var endpoint = -1
        fun build(): UvcStreamingInterface {
            val address = if (endpoint >= 0) endpoint else alts.firstNotNullOfOrNull { it.endpoint }?.address ?: 0
            return UvcStreamingInterface(number, formats.toList(), alts.toList(), address)
        }
    }
}

internal fun u8(d: ByteArray, at: Int): Int = d[at].toInt() and 0xFF
internal fun u16(d: ByteArray, at: Int): Int = u8(d, at) or (u8(d, at + 1) shl 8)
internal fun u32(d: ByteArray, at: Int): Long = u16(d, at).toLong() or (u16(d, at + 2).toLong() shl 16)
