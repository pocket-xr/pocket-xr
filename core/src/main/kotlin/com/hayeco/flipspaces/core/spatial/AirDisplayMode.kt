package com.hayeco.flipspaces.core.spatial

import java.util.zip.CRC32

/**
 * 1.2.3 (issue 54: "Pocket XR always shows 1920 x 1080 at 60 Hz for me, and doesn't switch the Air 2 to 3D by itself"):
 * the XREAL Air family's display mode, read and set on the glasses' control (MCU) HID interface. **Untested: never run on
 * Air glasses by the project**, so it sits behind a switch that is off by default, and only offers 2D and 3D side by side
 * at 60 Hz.
 *
 * Written in our own words from the protocol as the public ar-drivers-rs driver (MIT) documents it in code, the same
 * source as [AirImuProtocol]. The control interface is not the IMU's: 4 on the Air, Air 2 and Air 2 Pro, 0 on the Air 2
 * Ultra (that driver's claim; not checked here).
 *
 * A control packet (64 bytes, both ways):
 *
 * | Offset | Field |
 * |---|---|
 * | 0 | 0xFD |
 * | 1 | CRC-32 (standard, little endian) of the bytes from the length to the payload's end |
 * | 5 | length, u16 little endian: the payload's plus 17 |
 * | 7 | request id, u32 (0x1337) |
 * | 11 | timestamp, u32 (0) |
 * | 15 | command id, u16 little endian |
 * | 17 | 5 reserved bytes (0) |
 * | 22 | the payload |
 *
 * Get mode is [CMD_GET_MODE] with no payload; its answer's payload has the mode at index 1. Set mode is [CMD_SET_MODE]
 * with the mode as one byte; its answer's payload starts with 0 when it was taken.
 */
object AirDisplayMode {
    const val PACKET_BYTES = 64
    const val HEAD = 0xFD
    const val CMD_GET_MODE = 0x07
    const val CMD_SET_MODE = 0x08

    private const val LENGTH_OVER_PAYLOAD = 17
    private const val PAYLOAD_AT = 22
    private const val REQUEST_ID = 0x1337

    /** The two modes Pocket XR sets: the same picture to both eyes, and side by side, both at 60 Hz. */
    const val MODE_2D = 1
    const val MODE_3D_SBS = 3

    /** The control interface: 0 on the Air 2 Ultra, 4 on the others. */
    fun controlInterface(productId: Int): Int = if (productId == AirImuProtocol.PID_AIR_2_ULTRA) 0 else 4

    fun command(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= PACKET_BYTES - PAYLOAD_AT) { "payload too long: ${payload.size}" }
        val p = ByteArray(PACKET_BYTES)
        p[0] = HEAD.toByte()
        val length = payload.size + LENGTH_OVER_PAYLOAD
        put(p, 5, length, 2)
        put(p, 7, REQUEST_ID, 4)
        put(p, 15, id, 2)
        System.arraycopy(payload, 0, p, PAYLOAD_AT, payload.size)
        val crc = CRC32().apply { update(p, 5, length) }.value.toInt()
        put(p, 1, crc, 4)
        return p
    }

    fun getMode(): ByteArray = command(CMD_GET_MODE)

    /** Set [mode]: only [MODE_2D] and [MODE_3D_SBS] are sent. */
    fun setMode(mode: Int): ByteArray {
        require(mode == MODE_2D || mode == MODE_3D_SBS) { "mode $mode is not one Pocket XR sets" }
        return command(CMD_SET_MODE, byteArrayOf(mode.toByte()))
    }

    /** The payload of [packet] when it answers command [id], else null (another packet, or not a control packet). */
    fun answer(packet: ByteArray, size: Int, id: Int): ByteArray? {
        if (size < PAYLOAD_AT || (packet[0].toInt() and 0xFF) != HEAD) return null
        if (u16(packet, 15) != id) return null
        val n = (u16(packet, 5) - LENGTH_OVER_PAYLOAD).coerceIn(0, minOf(size, PACKET_BYTES) - PAYLOAD_AT)
        return packet.copyOfRange(PAYLOAD_AT, PAYLOAD_AT + n)
    }

    /** The mode in a get mode answer's [payload], or null when it is too short. */
    fun modeOf(payload: ByteArray): Int? = if (payload.size >= 2) payload[1].toInt() and 0xFF else null

    /** Whether a set mode answer's [payload] says the mode was taken. */
    fun setTaken(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0].toInt() == 0

    /** Whether reported [mode] is side by side (a picture for each eye). */
    fun isSideBySide(mode: Int): Boolean = mode in SIDE_BY_SIDE

    /** What the glasses report as words, for the Menu. Unknown values say so. */
    fun label(mode: Int): String = LABELS[mode] ?: "mode $mode (not known)"

    /** The mode a tap switches to from [mode]: 2D from side by side, side by side from anything else. */
    fun toggled(mode: Int): Int = if (isSideBySide(mode)) MODE_2D else MODE_3D_SBS

    private val SIDE_BY_SIDE = setOf(3, 4, 8, 9)
    private val LABELS = mapOf(
        1 to "2D, 60 Hz",
        3 to "3D side by side, 60 Hz",
        4 to "3D side by side, 72 Hz",
        5 to "2D, 72 Hz",
        8 to "3D half side by side, 60 Hz",
        9 to "3D side by side, 90 Hz",
        10 to "2D, 90 Hz",
        11 to "2D, 120 Hz",
    )

    const val SWITCH_ROW = "Air display mode (untested)"
    const val SWITCH_SUBTITLE = "Read and switch the Air's 2D or 3D mode from here. Never tried on Air glasses yet."

    private fun put(p: ByteArray, at: Int, v: Int, bytes: Int) {
        for (i in 0 until bytes) p[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun u16(p: ByteArray, at: Int): Int = (p[at].toInt() and 0xFF) or ((p[at + 1].toInt() and 0xFF) shl 8)
}
