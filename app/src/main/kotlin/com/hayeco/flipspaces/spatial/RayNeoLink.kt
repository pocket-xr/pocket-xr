package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hayeco.flipspaces.core.spatial.FramerCounts
import com.hayeco.flipspaces.core.spatial.ImuReport
import com.hayeco.flipspaces.core.spatial.RayNeoFramer
import com.hayeco.flipspaces.core.spatial.RayNeoProtocol
import com.hayeco.flipspaces.core.spatial.VitureAxes
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.6.21: RayNeo glasses' motion ([RayNeoProtocol]) over Android's USB host API: the first HID interface with an
 * interrupt endpoint each way (an Air 4 Pro's interface 0, its only one), the start written to it, the motion read from
 * it. When no motion has come [RESEND_MS] after a start, the start is sent again. The first few reports are written to the
 * log as bytes, so a tester's log shows what the glasses send. Not yet tried on RayNeo glasses.
 */
class RayNeoLink private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint?,
    axes: VitureAxes,
    readTimeoutMs: Int,
) : VitureLink {
    private val framer = RayNeoFramer(axes)
    private val buf = ByteArray(maxOf(RayNeoProtocol.PACKET_BYTES, input.maxPacketSize))
    private val timeoutMs = readTimeoutMs
    private val closed = AtomicBoolean(false)
    private var lastStartMs = 0L
    private var starts = 0
    private var dumped = 0

    override val label: String = "USB HID interface ${intf.id} of ${glassesName(device)} (experimental RayNeo reader)"

    override var startLine: String = ""
        private set

    override var axes: VitureAxes
        get() = framer.axes
        set(value) {
            framer.axes = value
        }

    override val counts: FramerCounts get() = framer.counts
    override val noReportsHint: String = "RayNeo glasses sent no motion after the start; please send logs to Mike"
    override val clockLine: String get() = "start sent ${starts}x"

    private fun start() {
        val sent = write(RayNeoProtocol.imuOn())
        starts++
        lastStartMs = SystemClock.elapsedRealtime()
        startLine = "start (66 01 00) to interface ${intf.id}: ${if (sent) "written" else "not written"}" +
            if (output == null) " (by a control transfer: no interrupt OUT endpoint)" else ""
        android.util.Log.i(TAG, "head tracking: $startLine")
    }

    override fun read(out: ArrayList<ImuReport>): Int {
        out.clear()
        val n = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        if (n < 0) {
            if (!(attached() && runCatching { manager.hasPermission(device) }.getOrDefault(false))) return -1
        } else if (n > 0) {
            if (dumped < DUMP_REPORTS) {
                dumped++
                val hex = buf.copyOf(minOf(n, 64)).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                val parsed = RayNeoProtocol.parse(buf, n)?.let { s ->
                    "; accel %.3f, %.3f, %.3f m/s2, gyro %.4f, %.4f, %.4f rad/s, tick %d".format(java.util.Locale.ROOT,
                        s.accel.x, s.accel.y, s.accel.z, s.gyro.x, s.gyro.y, s.gyro.z, s.tick)
                } ?: "; not a motion report"
                android.util.Log.i(TAG, "head tracking: RayNeo report $dumped ($n bytes): $hex$parsed")
            }
            framer.append(buf, n, System.nanoTime(), out)
        }
        if (framer.counts.imu == 0L && SystemClock.elapsedRealtime() - lastStartMs > RESEND_MS) start()
        return maxOf(n, 0)
    }

    private fun write(packet: ByteArray): Boolean {
        val n = if (output != null) connection.bulkTransfer(output, packet, packet.size, COMMAND_TIMEOUT_MS)
        else connection.controlTransfer(0x21, 0x09, 0x0200, intf.id, packet, packet.size, COMMAND_TIMEOUT_MS)
        return n == packet.size
    }

    private fun attached(): Boolean = runCatching { manager.deviceList.values.any { it.deviceName == device.deviceName } }.getOrDefault(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (attached()) runCatching { write(RayNeoProtocol.imuOff()) }
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "HeadTracker"
        private const val COMMAND_TIMEOUT_MS = 500
        private const val RESEND_MS = 3_000L
        private const val DUMP_REPORTS = 5

        fun find(context: Context): UsbDevice? = runCatching {
            VitureImuLink.manager(context).deviceList.values.firstOrNull { RayNeoProtocol.isRayNeo(it.vendorId) }
        }.getOrNull()

        fun glassesName(device: UsbDevice): String =
            "%s (USB %04X:%04X)".format(java.util.Locale.ROOT, RayNeoProtocol.modelName(device.productId), device.vendorId, device.productId)

        private fun endpoint(intf: UsbInterface, dir: Int): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == dir && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        /** Claims the glasses' HID interface and sends the start; throws [IOException] with what went wrong. */
        fun open(context: Context, device: UsbDevice, axes: VitureAxes, readTimeoutMs: Int): RayNeoLink {
            val manager = VitureImuLink.manager(context)
            if (!manager.hasPermission(device)) throw IOException("no USB permission for ${glassesName(device)}")
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID && endpoint(it, UsbConstants.USB_DIR_IN) != null }
                ?: throw IOException("no HID interface with an interrupt IN endpoint; Android lists: " +
                    com.hayeco.flipspaces.hands.GlassesUsbAccess.interfaces(device).replace('\n', ';'))
            val input = endpoint(intf, UsbConstants.USB_DIR_IN)!!
            val connection = manager.openDevice(device) ?: throw IOException("Android would not open ${glassesName(device)}")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("could not claim HID interface ${intf.id} (another app may hold it)")
            }
            val link = RayNeoLink(manager, device, connection, intf, input, endpoint(intf, UsbConstants.USB_DIR_OUT), axes, readTimeoutMs)
            link.start()
            return link
        }
    }
}
