package com.hayeco.flipspaces.core.spatial

import kotlin.math.sqrt

/**
 * 1.6.21 (a new user's Send logs: "xr real air 4 pro glasses and the spacial mode doesnt work"; the glasses report listed
 * USB 1BBB:AF50 and a display called "SmartGlasses"): RayNeo's glasses (TCL), the Air 4 Pro and Air 3S Pro, which share
 * that USB id. Their motion comes over USB HID, from the same interface the commands go to. The protocol is as the MIT
 * licensed RayNeo SDK by verncat writes it (github.com/verncat/RayNeo-Air-3S-Pro-OpenVR, src/RayneoApi.cpp), tested
 * there on macOS with an Air 4 Pro; Pocket XR has not run on RayNeo glasses yet.
 *
 * Commands are 64 bytes: 0x66, the command, a value, then zeros (no checksum). 0x01 starts the motion stream, 0x02
 * stops it. A motion report is 64 bytes starting 0x99 0x65: accelerometer x, y, z as floats at 4, 8 and 12, gyro x, y,
 * z in degrees a second at 16, 20 and 24, temperature at 28, a tick (32 bit) at 40, all little endian. The SDK says
 * nothing of the accelerometer's unit or the axes, so a report near 1 is taken as g and near 9.8 as m/s2, and the axes
 * are the sensor's own: the head axis layout and its gravity check ([GravityAxes]) turn them as for any untested glasses.
 */
object RayNeoProtocol {
    const val VENDOR = 0x1BBB
    const val AIR_4_PRO = 0xAF50

    /**
     * 1.6.23 (a new user's Send logs: "rayneo GT max glasses", USB 3941:AF50 on an XREAL Beam Pro, the display again
     * "SmartGlasses" EDID 980): the RayNeo GT's vendor id, as the RayNeo SDK by verncat lists it (include/rayneo_api.h,
     * RAYNEO_GT_VID), with the same product id, start command and motion report.
     */
    const val VENDOR_GT = 0x3941

    const val PACKET_BYTES = 64
    const val OUT_MAGIC = 0x66
    const val IN_MAGIC = 0x99
    const val TYPE_IMU = 0x65
    const val CMD_IMU_ON = 0x01
    const val CMD_IMU_OFF = 0x02

    private const val G = 9.80665f
    private const val DEG = (Math.PI / 180.0).toFloat()

    fun isRayNeo(vendorId: Int): Boolean = vendorId == VENDOR || vendorId == VENDOR_GT

    /**
     * 1.6.31 (Zen's Afterglow, RayNeo GT: "moving left and right ... the image just tilts clockwise to the left anti
     * clockwise to the right when looking ahead or down"): the GT's gyro is about 20 degrees pitched against its
     * accelerometer, about the sensor's y. In its 20 s motion recording fast head turns lie about 31 degrees from the
     * sensor's z while gravity, at the same moments, lies 16 to 23 degrees from it, and the accelerometer's sideways axis
     * barely moves (no real roll). Replayed through a head fusion, the raw gyro gives a roll of -0.37 degrees for each
     * degree of turn, the pendulum in his 1.6.25 log (about -0.6 there); the gyro turned by -20 degrees about y gives
     * -0.02. Only the GT: no Air 4 Pro has sent motion yet.
     */
    const val GT_GYRO_PITCH_DEG = -20f

    /** The gyro turn about the sensor's y for glasses of [vendorId] (degrees), 0 when none is known. */
    fun gyroPitchDeg(vendorId: Int): Float = if (vendorId == VENDOR_GT) GT_GYRO_PITCH_DEG else 0f

    /** [g] (sensor axes) turned by [deg] about the sensor's y: x' = x cos + z sin, z' = -x sin + z cos. */
    fun turnGyro(g: Vec3, deg: Float): Vec3 {
        if (deg == 0f) return g
        val c = kotlin.math.cos(deg * DEG)
        val s = kotlin.math.sin(deg * DEG)
        return Vec3(c * g.x + s * g.z, g.y, -s * g.x + c * g.z)
    }

    fun modelName(vendorId: Int, productId: Int): String = when {
        vendorId == VENDOR_GT -> "RayNeo GT"
        productId == AIR_4_PRO -> "RayNeo Air 4 Pro or Air 3S Pro"
        else -> "RayNeo glasses"
    }

    fun command(cmd: Int, value: Int = 0): ByteArray = ByteArray(PACKET_BYTES).also {
        it[0] = OUT_MAGIC.toByte()
        it[1] = cmd.toByte()
        it[2] = value.toByte()
    }

    fun imuOn(): ByteArray = command(CMD_IMU_ON)
    fun imuOff(): ByteArray = command(CMD_IMU_OFF)

    /** One motion report: accelerometer (m/s2), gyro (rad/s), both in the sensor's axes, and the glasses' tick. */
    data class Sample(val accel: Vec3, val gyro: Vec3, val tick: Long, val temperatureC: Float)

    /** The motion in [p] (the first [size] bytes), or null when it is not a motion report or holds no number. */
    fun parse(p: ByteArray, size: Int): Sample? {
        if (size < 44 || (p[0].toInt() and 0xFF) != IN_MAGIC || (p[1].toInt() and 0xFF) != TYPE_IMU) return null
        val a = Vec3(f32le(p, 4), f32le(p, 8), f32le(p, 12))
        val g = Vec3(f32le(p, 16), f32le(p, 20), f32le(p, 24))
        if (!finite(a) || !finite(g)) return null
        val n = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        // A report near 1 g is in g; near 9.8 already m/s2.
        val accel = if (n in 0.3f..3f) Vec3(a.x * G, a.y * G, a.z * G) else a
        return Sample(accel, Vec3(g.x * DEG, g.y * DEG, g.z * DEG), u32le(p, 40), f32le(p, 28))
    }

    /** The report's type byte when it starts with the reply marker, else -1 (for the log). */
    fun type(p: ByteArray, size: Int): Int = if (size >= 2 && (p[0].toInt() and 0xFF) == IN_MAGIC) p[1].toInt() and 0xFF else -1

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    private fun f32le(p: ByteArray, i: Int): Float = java.lang.Float.intBitsToFloat(u32le(p, i).toInt())

    private fun u32le(p: ByteArray, i: Int): Long =
        ((p[i].toLong() and 0xFF) or ((p[i + 1].toLong() and 0xFF) shl 8) or ((p[i + 2].toLong() and 0xFF) shl 16) or
            ((p[i + 3].toLong() and 0xFF) shl 24))
}

/** Turns RayNeo motion reports into [ImuReport]s, with the user's axis switches ([VitureAxes]), timed on arrival. */
class RayNeoFramer(axes: VitureAxes = VitureAxes(), private val gyroPitchDeg: Float = 0f) {
    @Volatile var axes: VitureAxes = axes
    private var nImu = 0L
    private var nOther = 0L
    private var nBad = 0L
    private var nBytes = 0L

    val counts: FramerCounts
        get() = FramerCounts(reports = nImu + nOther, imu = nImu, unknownType = nOther, badLength = nBad, bytes = nBytes)

    /** The motion in [packet] appended to [out] (not cleared); false when it held none. */
    fun append(packet: ByteArray, size: Int, hostNs: Long, out: ArrayList<ImuReport>): Boolean {
        nBytes += size
        val s = RayNeoProtocol.parse(packet, size)
        if (s == null) {
            if (RayNeoProtocol.type(packet, size) >= 0) nOther++ else nBad++
            return false
        }
        val sw = axes
        out += ImuReport(
            deviceId = 0L,
            timestampNs = hostNs,
            type = ImuReportType.IMU,
            gyro = sw.apply(RayNeoProtocol.turnGyro(s.gyro, gyroPitchDeg)),
            accel = sw.apply(s.accel),
            mag = Vec3.ZERO,
            temperatureC = s.temperatureC,
        )
        nImu++
        return true
    }
}

/** Builds RayNeo motion reports; used by the tests. */
object RayNeoEncoder {
    fun imu(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, tick: Int = 0): ByteArray {
        val p = ByteArray(RayNeoProtocol.PACKET_BYTES)
        p[0] = RayNeoProtocol.IN_MAGIC.toByte()
        p[1] = RayNeoProtocol.TYPE_IMU.toByte()
        fun put(i: Int, bits: Int) { for (k in 0..3) p[i + k] = (bits ushr (8 * k)).toByte() }
        floatArrayOf(ax, ay, az, gx, gy, gz).forEachIndexed { k, v -> put(4 + 4 * k, java.lang.Float.floatToIntBits(v)) }
        put(40, tick)
        return p
    }
}
