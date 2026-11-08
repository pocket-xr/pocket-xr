package com.hayeco.flipspaces.core.spatial

import java.util.zip.CRC32

/**
 * 1.1.12 (a Reddit report: "Xreal air 2 pro, 3dof, head tracking is not working even though it shows 3dof. Body anchor
 * on"): the XREAL Air family's sensor stream. The Air, Air 2, Air 2 Pro and Air 2 Ultra have no USB network: their IMU
 * streams on a USB HID interface (docs/SPATIAL.md 1.1), so the One family's TCP stream ([OneImuProtocol]) is never
 * found and the Space stayed head locked while the tracking mode still said "Body Anchor, 3DoF". Written from the
 * publicly documented protocol (ar-drivers-rs, MIT; nrealAirLinuxDriver), not from any of their code, and not yet run
 * on Air glasses by the project.
 *
 * A command (64 bytes, to the IMU interface): 0xAA, a CRC32 (little endian) of everything from the length on, a 2 byte
 * little endian length (the payload's plus 3), the command id, the payload. 0x19 with payload 1 starts the stream.
 *
 * A sensor report (64 bytes, from the IMU interface):
 *
 * | Offset | Field |
 * |---|---|
 * | 0x00 | 0x01 0x02 |
 * | 0x02 | temperature, i16 |
 * | 0x04 | timestamp, u64 (ns; [AirTimestamp] checks) |
 * | 0x0C | gyro multiplier u16, divisor i32, then x, y, z as i24 (deg/s once multiplied and divided) |
 * | 0x1B | accelerometer multiplier u16, divisor i32, then x, y, z as i24 (g; [parse] gives m/s2, as the fusion expects) |
 * | 0x2A | magnetometer (not used) |
 */
object AirImuProtocol {
    const val PID_AIR = 0x0424
    const val PID_AIR_2 = 0x0428
    const val PID_AIR_2_PRO = 0x0432
    const val PID_AIR_2_ULTRA = 0x0426

    const val PACKET_BYTES = 64
    const val COMMAND_HEAD = 0xAA
    const val CMD_IMU_STREAM = 0x19

    /** The model's name for the log, or null when [productId] is not an Air family product. */
    fun modelName(productId: Int): String? = when (productId) {
        PID_AIR -> "XREAL Air"
        PID_AIR_2 -> "XREAL Air 2"
        PID_AIR_2_PRO -> "XREAL Air 2 Pro"
        PID_AIR_2_ULTRA -> "XREAL Air 2 Ultra"
        else -> null
    }

    fun isAir(vendorId: Int, productId: Int): Boolean = vendorId == XREAL_VENDOR && modelName(productId) != null

    /** The HID interface the IMU streams on: 3, and 2 on the Air 2 Ultra. */
    fun imuInterface(productId: Int): Int = if (productId == PID_AIR_2_ULTRA) 2 else 3

    /** A 64 byte command [id] with [data]. */
    fun command(id: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= PACKET_BYTES - 8) { "command payload too long: ${data.size}" }
        val p = ByteArray(PACKET_BYTES)
        p[0] = COMMAND_HEAD.toByte()
        val length = data.size + 3
        p[5] = length.toByte()
        p[6] = (length ushr 8).toByte()
        p[7] = id.toByte()
        System.arraycopy(data, 0, p, 8, data.size)
        val crc = CRC32().apply { update(p, 5, length) }.value
        for (i in 0..3) p[1 + i] = (crc ushr (8 * i)).toByte()
        return p
    }

    /** The command that starts (or with [on] false, stops) the IMU stream. */
    fun streamCommand(on: Boolean = true): ByteArray = command(CMD_IMU_STREAM, byteArrayOf(if (on) 1 else 0))

    /** Whether [packet] is a sensor report (not a command's answer, which starts 0xAA). */
    fun isReport(packet: ByteArray, size: Int = packet.size): Boolean =
        size >= 0x2A && packet[0].toInt() == 0x01 && packet[1].toInt() == 0x02

    /**
     * The report in [packet] as an [ImuReport] in head axes (x right, y up, z back: [AxisMap.DEFAULT]), or null when it is
     * not one or its divisors are 0. The sensor's own axes are x left, y back, z up, as ar-drivers-rs maps them (head
     * x = -x, y = z, z = y); unconfirmed on glasses, so the Glasses report and the tracking log show the raw gravity
     * too and the head tracking screen's axis choice still applies on top.
     */
    fun parse(packet: ByteArray, size: Int = packet.size): ImuReport? {
        if (!isReport(packet, size)) return null
        val gMul = u16(packet, 0x0C)
        val gDiv = i32(packet, 0x0E)
        val aMul = u16(packet, 0x1B)
        val aDiv = i32(packet, 0x1D)
        if (gDiv == 0 || aDiv == 0) return null
        val gScale = gMul.toFloat() / gDiv.toFloat() * DEG
        // 1.2.3 (issue 49, an Air 2 user: "the horizon was somewhere in the floor", Recentre "didn't level it"): the
        // report is in g, about 1 at rest, but the fusion works in m/s2. Its start gate wants 9.81 within 2, so an Air
        // never passed it: gravity was never used, the view kept only the first sample's tilt, and the gyro's error
        // built up as tilt with nothing to pull it back. Recentre and Save level wait for the gate, so they did nothing.
        val aScale = aMul.toFloat() / aDiv.toFloat() * G
        val gx = i24(packet, 0x12) * gScale
        val gy = i24(packet, 0x15) * gScale
        val gz = i24(packet, 0x18) * gScale
        val ax = i24(packet, 0x21) * aScale
        val ay = i24(packet, 0x24) * aScale
        val az = i24(packet, 0x27) * aScale
        return ImuReport(
            deviceId = 0L,
            timestampNs = u64(packet, 0x04),
            type = ImuReportType.IMU,
            gyro = toHead(Vec3(gx, gy, gz)),
            accel = toHead(Vec3(ax, ay, az)),
            mag = Vec3.ZERO,
            temperatureC = i16(packet, 0x02).toFloat(),
        )
    }

    /** The sensor's axes (x left, y back, z up) in head axes (x right, y up, z back). */
    fun toHead(v: Vec3): Vec3 = Vec3(-v.x, v.z, v.y)

    private const val XREAL_VENDOR = 0x3318

    /** m/s2 in one g: the report's accelerometer unit to the fusion's. */
    const val G = 9.81f

    private fun u8(p: ByteArray, i: Int): Int = p[i].toInt() and 0xFF
    private fun u16(p: ByteArray, i: Int): Int = u8(p, i) or (u8(p, i + 1) shl 8)
    private fun i16(p: ByteArray, i: Int): Int = u16(p, i).toShort().toInt()
    private fun i24(p: ByteArray, i: Int): Int = ((u8(p, i) or (u8(p, i + 1) shl 8) or (u8(p, i + 2) shl 16)) shl 8) shr 8
    private fun i32(p: ByteArray, i: Int): Int = u16(p, i) or (u16(p, i + 2) shl 16)
    private fun u64(p: ByteArray, i: Int): Long = (i32(p, i).toLong() and 0xFFFFFFFFL) or (i32(p, i + 4).toLong() shl 32)
}

/**
 * 1.1.12: the Air report's timestamp unit, from its first steps. The public drivers read it as ns; should a firmware send
 * µs instead, every step would be 1000 times too short and the fusion would turn the view by a thousandth of the head's
 * turn: a pose that "shows 3DoF" yet never moves. At about 1 kHz a step of under 50 000 can only be µs.
 */
class AirTimestamp {
    private var last = Long.MIN_VALUE

    /** Ns per unit: 1 until decided, then 1 (ns) or 1000 (µs). */
    var scale = 1L
        private set

    var decided = false
        private set

    fun toNs(raw: Long): Long {
        if (!decided && last != Long.MIN_VALUE) {
            val step = raw - last
            if (step > 0) {
                scale = if (step < MICROS_BELOW) 1000L else 1L
                decided = true
            }
        }
        last = raw
        return raw * scale
    }

    fun line(): String = when {
        !decided -> "timestamps: unit not decided yet"
        scale == 1L -> "timestamps: ns"
        else -> "timestamps: µs (taken as ns x 1000)"
    }

    companion object {
        const val MICROS_BELOW = 50_000L
    }
}

/**
 * 1.1.12: where the head thread's reports come from: the One family's TCP stream ([OneImuFramer] over a network link) or
 * the Air family's HID reports ([AirImuFramer] over USB).
 */
interface ImuReportSource {
    /** Reads what came into [out] (cleared first): bytes read, 0 when nothing came within the read timeout, -1 at the end. */
    fun read(out: ArrayList<ImuReport>): Int

    val counts: FramerCounts

    /** What a stream that sent no report at all means, for [HeadTrackingStatus.NoReports]. */
    val noReportsHint: String
}

/** 1.1.12: the Air family's 64 byte HID reports into [ImuReport]s, with the totals the diagnostics show. */
class AirImuFramer {
    private val clock = AirTimestamp()
    private var nReports = 0L
    private var nOther = 0L
    private var nBytes = 0L

    val timestampLine: String get() = clock.line()

    val counts: FramerCounts
        get() = FramerCounts(reports = nReports, imu = nReports, unknownType = nOther, bytes = nBytes)

    /** The report in [packet] appended to [out] (not cleared); false when it was not one (a command's answer). */
    fun append(packet: ByteArray, size: Int, out: ArrayList<ImuReport>): Boolean {
        nBytes += size
        val r = AirImuProtocol.parse(packet, size)
        if (r == null) {
            nOther++
            return false
        }
        out += r.copy(timestampNs = clock.toNs(r.timestampNs))
        nReports++
        return true
    }
}

/** Builds Air sensor reports; used by the tests. Values are in the sensor's own axes, deg/s and g. */
object AirImuEncoder {
    fun report(timestamp: Long, gyroDegS: Vec3, accelG: Vec3, gyroDiv: Int = 8192, accelDiv: Int = 8192): ByteArray {
        val p = ByteArray(AirImuProtocol.PACKET_BYTES)
        p[0] = 0x01
        p[1] = 0x02
        for (i in 0..7) p[0x04 + i] = (timestamp ushr (8 * i)).toByte()
        put16(p, 0x0C, 1); put32(p, 0x0E, gyroDiv)
        put24(p, 0x12, Math.round(gyroDegS.x * gyroDiv)); put24(p, 0x15, Math.round(gyroDegS.y * gyroDiv)); put24(p, 0x18, Math.round(gyroDegS.z * gyroDiv))
        put16(p, 0x1B, 1); put32(p, 0x1D, accelDiv)
        put24(p, 0x21, Math.round(accelG.x * accelDiv)); put24(p, 0x24, Math.round(accelG.y * accelDiv)); put24(p, 0x27, Math.round(accelG.z * accelDiv))
        return p
    }

    private fun put16(p: ByteArray, at: Int, v: Int) { p[at] = v.toByte(); p[at + 1] = (v ushr 8).toByte() }
    private fun put24(p: ByteArray, at: Int, v: Int) { p[at] = v.toByte(); p[at + 1] = (v ushr 8).toByte(); p[at + 2] = (v ushr 16).toByte() }
    private fun put32(p: ByteArray, at: Int, v: Int) { for (i in 0..3) p[at + i] = (v ushr (8 * i)).toByte() }
}
