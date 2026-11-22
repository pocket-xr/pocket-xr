package com.hayeco.flipspaces.core.spatial

import kotlin.math.cos
import kotlin.math.sin

/**
 * 1.6.7 (Discussion #24, Crr66: a VITURE Luma XR, 35CA:1131, sent nothing on either HID interface to [VitureImuProtocol]'s
 * start, "VITURE Brille hat nicht auf den dokumentierten Startbefehl geantwortet"): VITURE's own SDK page lists the Luma
 * as a first generation ("Gen1") device, with the Pro and the Luma Pro; [VitureImuProtocol] is the later protocol the
 * Beast (Gen2) answers. The first generation speaks another one.
 *
 * Written from two public write-ups, both made by watching the glasses: bfvogel/viture-webxr-extension's
 * VITURE_PROTOCOL.md (confirmed there on a VITURE Pro, 35CA:101D) and mgschwan/viture_virtual_display's
 * viture_connection.c (MIT, a VITURE Pro XR). No code from either is used, and no VITURE SDK code or library. Untested
 * on the Pro and Luma Pro; Crr66's Luma XR tracks with it (1.6.7, no axis switches). The project has no VITURE glasses.
 *
 * Two HID interfaces, both 64 byte reports: interface 1 (the MCU) takes commands, interface 0 sends the motion.
 *
 * A packet:
 *
 * | Offset | Field |
 * |---|---|
 * | 0 | header: FF FE a command, FF FD an answer, FF FC motion |
 * | 2 | CRC-16 (polynomial 0x1021) over the length field and the [length] bytes after it |
 * | 4 | length, u16 little endian: everything from offset 6 on (8 zero bytes, the command id, 2 bytes, the data) |
 * | 6 | timestamp, u32 (motion) |
 * | 14 | command id, u16 little endian |
 * | 16 | message counter, u16 little endian |
 * | 18 | data |
 *
 * The write-ups differ on the details of a command: VITURE_PROTOCOL.md starts the CRC at 0xFFFF, stores it big endian and
 * ends the packet with 0x03 (counted in the length); viture_connection.c starts it at 0, stores it little endian and has
 * no end marker. Both say theirs works, so [Variant] has both and the reader tries one, then the other.
 *
 * Command 0x15 with one data byte, 1, starts the motion stream (0 stops it).
 *
 * Motion (FF FC): three angles in degrees from offset 18, each a float stored big endian. VITURE_PROTOCOL.md turns
 * them into a head pose as yaw = -a0 (about the back axis), roll = -a1 (about the right axis, looking up and down) and
 * pitch = a2 (about the up axis, turning left and right), in that order (its "ZYX" formula), in x right, y up, z back,
 * which are Pocket XR's head axes ([orientation]).
 */
object VitureGen1Protocol {
    /** First generation VITURE glasses, from VITURE's SDK page: the Pro, the Luma, the Luma Pro. */
    val PRODUCTS: Set<Int> = setOf(0x1019, 0x101D, 0x1131, 0x1121, 0x1141)

    fun isGen1(productId: Int?): Boolean = productId in PRODUCTS

    const val PACKET_BYTES = 64
    const val MCU_INTERFACE = 1
    const val IMU_INTERFACE = 0

    const val HEADER_COMMAND = 0xFFFE
    const val HEADER_ANSWER = 0xFFFD
    const val HEADER_IMU = 0xFFFC

    const val CMD_IMU = 0x15
    const val DATA_AT = 18
    const val END_MARKER: Byte = 0x03

    /** The two ways the write-ups build a command. */
    enum class Variant(val label: String, val crcStart: Int, val crcBigEndian: Boolean, val endMarker: Boolean) {
        WEBXR("CRC from 0xFFFF, big endian, end marker 0x03", 0xFFFF, true, true),
        C("CRC from 0, little endian, no end marker", 0x0000, false, false),
        ;

        fun other(): Variant = if (this == WEBXR) C else WEBXR
    }

    private val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    /** CRC-16, polynomial 0x1021, not reflected, from [start], over [length] bytes of [p] from [from]. */
    fun crc16(p: ByteArray, from: Int, length: Int, start: Int): Int {
        var crc = start
        for (i in from until from + length) crc = ((crc shl 8) xor table[((crc ushr 8) xor (p[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        return crc
    }

    /** A 64 byte command packet. */
    fun command(cmdId: Int, data: ByteArray, counter: Int, variant: Variant): ByteArray {
        val p = ByteArray(PACKET_BYTES)
        p[0] = 0xFF.toByte()
        p[1] = 0xFE.toByte()
        val length = 12 + data.size + if (variant.endMarker) 1 else 0
        require(6 + length <= PACKET_BYTES) { "data too long: ${data.size}" }
        put16le(p, 4, length)
        put16le(p, 14, cmdId)
        put16le(p, 16, counter and 0xFFFF)
        System.arraycopy(data, 0, p, DATA_AT, data.size)
        if (variant.endMarker) p[DATA_AT + data.size] = END_MARKER
        val crc = crc16(p, 4, length + 2, variant.crcStart)
        if (variant.crcBigEndian) {
            p[2] = (crc ushr 8).toByte()
            p[3] = crc.toByte()
        } else {
            put16le(p, 2, crc)
        }
        return p
    }

    fun imuOn(counter: Int, variant: Variant): ByteArray = command(CMD_IMU, byteArrayOf(1), counter, variant)

    fun imuOff(counter: Int, variant: Variant): ByteArray = command(CMD_IMU, byteArrayOf(0), counter, variant)

    fun header(p: ByteArray, size: Int): Int = if (size < 2) -1 else ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

    /** The three angles (degrees, as sent) in a motion packet, or null when it is not one or holds no number. */
    fun angles(p: ByteArray, size: Int): Vec3? {
        if (size < DATA_AT + 12 || header(p, size) != HEADER_IMU) return null
        val v = Vec3(f32be(p, DATA_AT), f32be(p, DATA_AT + 4), f32be(p, DATA_AT + 8))
        return if (v.x.isFinite() && v.y.isFinite() && v.z.isFinite()) v else null
    }

    /** The head pose for angles [a] (a0, a1, a2 in degrees), in Pocket XR's head axes. */
    fun orientation(a: Vec3): Quat {
        val yaw = Math.toRadians(-a.x.toDouble()) // about z
        val roll = Math.toRadians(-a.y.toDouble()) // about x
        val pitch = Math.toRadians(a.z.toDouble()) // about y
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        val cr = cos(roll / 2); val sr = sin(roll / 2)
        return Quat(
            (cr * cp * cy + sr * sp * sy).toFloat(),
            (sr * cp * cy - cr * sp * sy).toFloat(),
            (cr * sp * cy + sr * cp * sy).toFloat(),
            (cr * cp * sy - sr * sp * cy).toFloat(),
        ).normalized()
    }

    private fun f32be(p: ByteArray, i: Int): Float {
        val bits = ((p[i].toInt() and 0xFF) shl 24) or ((p[i + 1].toInt() and 0xFF) shl 16) or
            ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun put16le(p: ByteArray, at: Int, v: Int) {
        p[at] = v.toByte()
        p[at + 1] = (v ushr 8).toByte()
    }
}

/**
 * 1.6.7: first generation VITURE motion ([VitureGen1Protocol]) into [ImuReport]s. Those glasses send a finished head
 * pose, not the gyro and the accelerometer the fusion takes, so each pose is turned back into what a sensor in the head
 * would have read: the turn since the last pose over the time between them (the gyro, rad/s, head axes) and gravity's
 * reaction in head axes (the accelerometer). The fusion then runs as for any glasses, recentre and Steady included.
 * The axis switches ([VitureAxes]) turn both alike, as for the later VITURE stream.
 */
class VitureGen1Framer(axes: VitureAxes = VitureAxes(), nominalHz: Float = 60f) {
    @Volatile var axes: VitureAxes = axes

    private val clock = VitureClock(nominalHz)
    private var counter = 0L
    private var last: Quat? = null
    private var lastNs = 0L
    private var nImu = 0L
    private var nOther = 0L
    private var nBad = 0L
    private var nBytes = 0L

    val clockLine: String get() = clock.line()

    val counts: FramerCounts
        get() = FramerCounts(reports = nImu + nOther, imu = nImu, unknownType = nOther, badLength = nBad, bytes = nBytes)

    /** The motion in [packet] appended to [out] (not cleared); false when it held none. */
    fun append(packet: ByteArray, size: Int, hostNs: Long, out: ArrayList<ImuReport>): Boolean {
        nBytes += size
        val h = VitureGen1Protocol.header(packet, size)
        if (h != VitureGen1Protocol.HEADER_IMU) {
            if (h == VitureGen1Protocol.HEADER_ANSWER || h == VitureGen1Protocol.HEADER_COMMAND) nOther++ else nBad++
            return false
        }
        val a = VitureGen1Protocol.angles(packet, size)
        if (a == null) {
            nBad++
            return false
        }
        val q = VitureGen1Protocol.orientation(a)
        val ns = clock.toNs(counter++, hostNs)
        val prev = last
        val gyro = if (prev == null || ns <= lastNs) Vec3.ZERO else turnRate(prev, q, (ns - lastNs) / 1e9f)
        last = q
        lastNs = ns
        val accel = q.conjugate().rotate(Vec3(0f, AirImuProtocol.G, 0f))
        val sw = axes
        out += ImuReport(
            deviceId = 0L,
            timestampNs = ns,
            type = ImuReportType.IMU,
            gyro = sw.apply(gyro),
            accel = sw.apply(accel),
            mag = Vec3.ZERO,
            temperatureC = 0f,
        )
        nImu++
        return true
    }

    companion object {
        /** The head-axes turn rate (rad/s) taking pose [from] to [to] in [dt] seconds. */
        fun turnRate(from: Quat, to: Quat, dt: Float): Vec3 {
            var d = from.conjugate() * to
            if (d.w < 0f) d = Quat(-d.w, -d.x, -d.y, -d.z)
            val s = kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            if (s < 1e-9f || dt <= 0f) return Vec3.ZERO
            val angle = 2f * kotlin.math.atan2(s, d.w)
            val k = angle / (s * dt)
            return Vec3(d.x * k, d.y * k, d.z * k)
        }
    }
}

/** Builds first generation VITURE motion packets; used by the tests. */
object VitureGen1Encoder {
    fun imu(a0: Float, a1: Float, a2: Float): ByteArray {
        val p = ByteArray(VitureGen1Protocol.PACKET_BYTES)
        p[0] = 0xFF.toByte()
        p[1] = 0xFC.toByte()
        floatArrayOf(a0, a1, a2).forEachIndexed { k, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            for (i in 0..3) p[VitureGen1Protocol.DATA_AT + 4 * k + i] = (bits ushr (8 * (3 - i))).toByte()
        }
        return p
    }
}
