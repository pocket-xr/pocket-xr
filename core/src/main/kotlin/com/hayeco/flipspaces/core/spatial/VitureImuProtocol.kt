package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.8 (Discussion #24, a Galaxy Z Flip 7 with VITURE Beast glasses: no head tracking in either anchor mode): VITURE's
 * glasses carry their IMU over USB HID on VITURE's own vendor id, 0x35CA, and Pocket XR only knew XREAL's.
 *
 * Experimental and off by default. Written from one public, MIT licensed protocol write-up, elasticjava/viture-v2's
 * PROTOCOL.md, which its author measured on a VITURE Pro 2 (35CA:1301) by watching the USB traffic. No VITURE SDK code,
 * no closed library and none of viture-v2's code is used. The project has not run it on any VITURE glasses, and the Beast
 * is not named in that write-up, so the Beast may speak something else; the head tracking screen's motion recording
 * ([HidRecording]) is how to find out.
 *
 * A frame (one 64 byte HID report, zero padded, all little endian):
 *
 * | Offset | Field |
 * |---|---|
 * | 0 | preamble 0x0010, u16 |
 * | 2 | message id, u16 |
 * | 4 | payload length, u16 |
 * | 6 | checksum, u16: the plain sum of the payload's bytes, mod 65536 |
 * | 8 | payload |
 *
 * 0x0301 (IMU control), payload 2 bytes: which streams (0x01 fused pose, 0x02 raw gyro and accelerometer, 0 off) and a
 * rate index, 0 the slowest (60 Hz) to 5 the fastest; the write-up's example asks for index 2 as 120 Hz.
 *
 * 0x7309 (a raw sample), payload:
 *
 * | Offset | Field |
 * |---|---|
 * | 0 | u32, not known |
 * | 4 | u32 sample counter, one a sample |
 * | 8 | u16, not known |
 * | 10 | gyro x, y, z, f32, rad/s |
 * | 22 | accelerometer x, y, z, f32, g |
 *
 * Axes: the write-up does not say which way the sensor's axes point, so nothing is guessed here: the sensor's x, y, z are
 * taken as head x (right), y (up), z (back), and [VitureAxes] (two switches on the head tracking screen, saved for VITURE
 * glasses) and the per model axis choice turn them when that is wrong.
 */
object VitureImuProtocol {
    const val VENDOR = 0x35CA

    const val PACKET_BYTES = 64
    const val HEADER_BYTES = 8
    const val PREAMBLE = 0x0010

    const val MSG_IMU_CONTROL = 0x0301
    const val MSG_POSE = 0x7308
    const val MSG_RAW = 0x7309

    const val STREAM_OFF = 0x00
    const val STREAM_POSE = 0x01
    const val STREAM_RAW = 0x02

    /** The rate index the write-up shows working (120 Hz). */
    const val RATE_120 = 2

    /** The smallest raw payload holding the gyro and the accelerometer. */
    const val RAW_MIN_PAYLOAD = 34

    fun isViture(vendorId: Int): Boolean = vendorId == VENDOR

    data class Frame(val msgId: Int, val payload: ByteArray)

    /** The plain byte sum of [payload], mod 65536. */
    fun checksum(payload: ByteArray, from: Int = 0, length: Int = payload.size): Int {
        var s = 0
        for (i in from until from + length) s += payload[i].toInt() and 0xFF
        return s and 0xFFFF
    }

    /** A frame for [msgId] with [payload], header and payload only (the USB layer pads it to 64 bytes). */
    fun frame(msgId: Int, payload: ByteArray): ByteArray {
        require(payload.size <= PACKET_BYTES - HEADER_BYTES) { "payload too long: ${payload.size}" }
        val p = ByteArray(HEADER_BYTES + payload.size)
        put16(p, 0, PREAMBLE)
        put16(p, 2, msgId)
        put16(p, 4, payload.size)
        put16(p, 6, checksum(payload))
        System.arraycopy(payload, 0, p, HEADER_BYTES, payload.size)
        return p
    }

    /** The command that starts the raw gyro and accelerometer stream at [rateIndex]. */
    fun startRaw(rateIndex: Int = RATE_120): ByteArray = frame(MSG_IMU_CONTROL, byteArrayOf(STREAM_RAW.toByte(), rateIndex.toByte()))

    /** The command that stops every IMU stream. */
    fun stop(): ByteArray = frame(MSG_IMU_CONTROL, byteArrayOf(STREAM_OFF.toByte(), 0))

    /** The frame in the first [size] bytes of [packet], or null when the preamble, the length or the checksum is wrong. */
    fun readFrame(packet: ByteArray, size: Int = packet.size): Frame? {
        if (size < HEADER_BYTES || u16(packet, 0) != PREAMBLE) return null
        val len = u16(packet, 4)
        if (HEADER_BYTES + len > size) return null
        if (checksum(packet, HEADER_BYTES, len) != u16(packet, 6)) return null
        return Frame(u16(packet, 2), packet.copyOfRange(HEADER_BYTES, HEADER_BYTES + len))
    }

    /** One raw sample: the counter, the gyro in rad/s and the accelerometer in g, in the sensor's own axes. */
    data class RawSample(val counter: Long, val gyro: Vec3, val accelG: Vec3)

    /** The raw sample in a 0x7309 [payload], or null when it is too short or holds a value that is not a number. */
    fun parseRaw(payload: ByteArray): RawSample? {
        if (payload.size < RAW_MIN_PAYLOAD) return null
        val gyro = Vec3(f32(payload, 10), f32(payload, 14), f32(payload, 18))
        val accel = Vec3(f32(payload, 22), f32(payload, 26), f32(payload, 30))
        if (!finite(gyro) || !finite(accel)) return null
        return RawSample(u32(payload, 4), gyro, accel)
    }

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    private fun u8(p: ByteArray, i: Int): Int = p[i].toInt() and 0xFF
    internal fun u16(p: ByteArray, i: Int): Int = u8(p, i) or (u8(p, i + 1) shl 8)
    private fun u32(p: ByteArray, i: Int): Long = (u16(p, i).toLong() or (u16(p, i + 2).toLong() shl 16)) and 0xFFFFFFFFL
    private fun f32(p: ByteArray, i: Int): Float = java.lang.Float.intBitsToFloat(u16(p, i) or (u16(p, i + 2) shl 16))
    private fun put16(p: ByteArray, at: Int, v: Int) {
        p[at] = v.toByte()
        p[at + 1] = (v ushr 8).toByte()
    }
}

/**
 * 1.2.8: the two VITURE axis switches on the head tracking screen, applied to the gyro and the accelerometer alike so the
 * fusion's gravity and turns stay in step. Both are whole turns of the sensor, never a mirror, so a turn stays a turn:
 * - [flipUpDown]: the sensor taken as facing backwards, half a turn about the up axis (x and z change sign). Looking up
 *   then reads as up, and a turn left stays a turn left.
 * - [swapTurnTilt]: the sensor taken as lying on its back, a quarter turn about the right axis (the sensor's z as up).
 *   A head turn then reads as a turn, not a tilt.
 * The MiruXR report in Discussion #24 ("up was down, left was a head tilt, not a turn") is what both switches are for.
 */
data class VitureAxes(val flipUpDown: Boolean = false, val swapTurnTilt: Boolean = false) {
    fun apply(v: Vec3): Vec3 {
        var o = v
        if (swapTurnTilt) o = Vec3(o.x, o.z, -o.y)
        if (flipUpDown) o = Vec3(-o.x, o.y, -o.z)
        return o
    }

    fun line(): String = when {
        !flipUpDown && !swapTurnTilt -> "VITURE axes as the sensor sends them"
        else -> "VITURE axes: " + listOfNotNull("up and down flipped".takeIf { flipUpDown }, "turn and tilt swapped".takeIf { swapTurnTilt }).joinToString(", ")
    }
}

/**
 * 1.2.8: the raw samples' times. A sample carries only a counter, one a sample, so the time step comes from the rate the
 * glasses really send at, measured against the phone's clock over the first [LEARN_SAMPLES] samples; until then the
 * asked for rate's ([nominalHz]). A counter that goes back (a restart) starts over.
 */
class VitureClock(private val nominalHz: Float = 120f) {
    private var firstCounter = -1L
    private var firstHostNs = 0L
    private var lastCounter = -1L

    /** Ns a sample. */
    var periodNs: Double = 1e9 / nominalHz
        private set

    var learned = false
        private set

    /** The sample's time, ns, from its [counter] and the phone's [hostNs] when it came. */
    fun toNs(counter: Long, hostNs: Long): Long {
        if (firstCounter < 0 || counter < lastCounter) {
            firstCounter = counter
            firstHostNs = hostNs
            learned = false
            periodNs = 1e9 / nominalHz
        }
        lastCounter = counter
        val steps = counter - firstCounter
        if (!learned && steps >= LEARN_SAMPLES) {
            val measured = (hostNs - firstHostNs).toDouble() / steps
            // A rate off by more than a factor of 20 from what was asked is not believed.
            if (measured > periodNs / 20 && measured < periodNs * 20) periodNs = measured
            learned = true
        }
        return firstHostNs + Math.round(steps * periodNs)
    }

    fun line(): String = "%s %.1f Hz".format(java.util.Locale.ROOT, if (learned) "samples measured at" else "samples taken as", 1e9 / periodNs)

    companion object {
        const val LEARN_SAMPLES = 120L
    }
}

/**
 * 1.2.8: VITURE frames into [ImuReport]s in head axes, with the totals the diagnostics show. 1.2.10: [axes] can change
 * while it reads (the head tracking screen's switches), from the next sample on.
 */
class VitureImuFramer(axes: VitureAxes = VitureAxes(), nominalHz: Float = 120f) {
    @Volatile var axes: VitureAxes = axes

    private val clock = VitureClock(nominalHz)
    private var nRaw = 0L
    private var nOther = 0L
    private var nBad = 0L
    private var nBytes = 0L

    val clockLine: String get() = clock.line()

    val counts: FramerCounts
        get() = FramerCounts(reports = nRaw + nOther, imu = nRaw, unknownType = nOther, badLength = nBad, bytes = nBytes)

    /** The sample in [packet] appended to [out] (not cleared); false when it was not a raw sample. */
    fun append(packet: ByteArray, size: Int, hostNs: Long, out: ArrayList<ImuReport>): Boolean {
        nBytes += size
        val f = VitureImuProtocol.readFrame(packet, size)
        if (f == null) {
            nBad++
            return false
        }
        if (f.msgId != VitureImuProtocol.MSG_RAW) {
            nOther++
            return false
        }
        val s = VitureImuProtocol.parseRaw(f.payload)
        if (s == null) {
            nBad++
            return false
        }
        // One read of the switches for both, so the gyro and the accelerometer are always turned alike.
        val a = axes
        out += ImuReport(
            deviceId = 0L,
            timestampNs = clock.toNs(s.counter, hostNs),
            type = ImuReportType.IMU,
            gyro = a.apply(s.gyro),
            accel = a.apply(s.accelG * AirImuProtocol.G),
            mag = Vec3.ZERO,
            temperatureC = 0f,
        )
        nRaw++
        return true
    }
}

/** Builds VITURE frames; used by the tests. */
object VitureImuEncoder {
    fun raw(counter: Long, gyroRadS: Vec3, accelG: Vec3): ByteArray {
        val p = ByteArray(46)
        for (i in 0..3) p[4 + i] = (counter ushr (8 * i)).toByte()
        val values = floatArrayOf(gyroRadS.x, gyroRadS.y, gyroRadS.z, accelG.x, accelG.y, accelG.z)
        values.forEachIndexed { k, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            for (i in 0..3) p[10 + 4 * k + i] = (bits ushr (8 * i)).toByte()
        }
        return VitureImuProtocol.frame(VitureImuProtocol.MSG_RAW, p).copyOf(VitureImuProtocol.PACKET_BYTES)
    }
}
