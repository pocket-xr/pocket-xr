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
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.ImuReport
import com.hayeco.flipspaces.core.spatial.VitureAxes
import com.hayeco.flipspaces.core.spatial.VitureGen1Framer
import com.hayeco.flipspaces.core.spatial.VitureGen1Protocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.6.7 (Discussion #24, Crr66's VITURE Luma XR): first generation VITURE glasses ([VitureGen1Protocol]) over Android's
 * USB host API. Both HID interfaces are claimed: the start goes to the MCU's (interface 1) and the motion is read from
 * the other (interface 0). The two write-ups build the start differently, so when no motion has come [RESEND_MS] after
 * a start, the other way is sent, and so on in turn. Works on Crr66's Luma XR (1.6.7); untested on other VITURE glasses.
 */
class VitureGen1Link private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val mcu: UsbInterface,
    private val imu: UsbInterface,
    private val input: UsbEndpoint,
    axes: VitureAxes,
    readTimeoutMs: Int,
) : VitureLink {
    private val framer = VitureGen1Framer(axes)
    private val buf = ByteArray(maxOf(VitureGen1Protocol.PACKET_BYTES, input.maxPacketSize))
    private val mcuBuf = ByteArray(VitureGen1Protocol.PACKET_BYTES)
    private val timeoutMs = readTimeoutMs
    private val closed = AtomicBoolean(false)
    private var counter = 0
    private var variant = VitureGen1Protocol.Variant.WEBXR
    private var lastStartMs = 0L
    private var starts = 0

    override val label: String =
        "USB HID interfaces ${mcu.id} and ${imu.id} of the ${VitureImuLink.glassesName(device)} (experimental first generation VITURE reader)"

    override var startLine: String = ""
        private set

    override var axes: VitureAxes
        get() = framer.axes
        set(value) {
            framer.axes = value
        }

    override val counts: FramerCounts get() = framer.counts
    override val noReportsHint: String = HeadTrackingStatus.VITURE_HINT
    override val clockLine: String get() = "${framer.clockLine}; start sent ${starts}x, last as ${variant.label}"

    private fun start() {
        val sent = write(VitureGen1Protocol.imuOn(counter++, variant))
        starts++
        lastStartMs = SystemClock.elapsedRealtime()
        startLine = "start (command 0x15, ${variant.label}) to interface ${mcu.id}: ${if (sent) "written" else "not written"}"
        android.util.Log.i(TAG, "head tracking: $startLine")
    }

    override fun read(out: ArrayList<ImuReport>): Int {
        out.clear()
        // Answers on the MCU interface are read so they do not pile up; they are not needed.
        mcuIn?.let { connection.bulkTransfer(it, mcuBuf, mcuBuf.size, 1) }
        val n = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        if (n < 0) {
            if (!(attached() && runCatching { manager.hasPermission(device) }.getOrDefault(false))) return -1
        } else if (n > 0) {
            framer.append(buf, n, System.nanoTime(), out)
        }
        if (framer.counts.imu == 0L && SystemClock.elapsedRealtime() - lastStartMs > RESEND_MS) {
            variant = variant.other()
            start()
        }
        return maxOf(n, 0)
    }

    private val mcuIn: UsbEndpoint? = endpoint(mcu, UsbConstants.USB_DIR_IN)

    private fun write(packet: ByteArray): Boolean {
        val out = endpoint(mcu, UsbConstants.USB_DIR_OUT)
        val n = if (out != null) connection.bulkTransfer(out, packet, packet.size, COMMAND_TIMEOUT_MS)
        else connection.controlTransfer(0x21, 0x09, 0x0200, mcu.id, packet, packet.size, COMMAND_TIMEOUT_MS)
        return n == packet.size
    }

    private fun attached(): Boolean = runCatching { manager.deviceList.values.any { it.deviceName == device.deviceName } }.getOrDefault(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (attached()) runCatching { write(VitureGen1Protocol.imuOff(counter++, variant)) }
        runCatching { connection.releaseInterface(mcu) }
        runCatching { connection.releaseInterface(imu) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "HeadTracker"
        private const val COMMAND_TIMEOUT_MS = 250

        /** How long a start waits for motion before the other way of building it is sent. */
        private const val RESEND_MS = 3_000L

        fun endpoint(intf: UsbInterface, dir: Int): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == dir && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        /**
         * Claims both HID interfaces and sends the first start; throws [IOException] with what went wrong. The MCU is the
         * HID interface numbered 1 and the motion the one numbered 0, as both write-ups say; glasses that number them
         * otherwise take the second and first HID interfaces Android lists.
         */
        fun open(context: Context, device: UsbDevice, axes: VitureAxes, readTimeoutMs: Int): VitureGen1Link {
            val manager = VitureImuLink.manager(context)
            if (!manager.hasPermission(device)) throw IOException("no USB permission for ${VitureImuLink.glassesName(device)}")
            val hids = (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            val imu = hids.firstOrNull { it.id == VitureGen1Protocol.IMU_INTERFACE } ?: hids.getOrNull(0)
            val mcu = hids.firstOrNull { it.id == VitureGen1Protocol.MCU_INTERFACE && it !== imu } ?: hids.firstOrNull { it !== imu }
            if (imu == null || mcu == null) {
                throw IOException("needs two HID interfaces, found ${hids.size}; Android lists: " +
                    com.hayeco.flipspaces.hands.GlassesUsbAccess.interfaces(device).replace('\n', ';'))
            }
            val input = endpoint(imu, UsbConstants.USB_DIR_IN) ?: throw IOException("HID interface ${imu.id} has no interrupt IN endpoint")
            val connection = manager.openDevice(device) ?: throw IOException("Android would not open ${VitureImuLink.glassesName(device)}")
            for (intf in listOf(imu, mcu)) {
                if (!connection.claimInterface(intf, true)) {
                    runCatching { connection.releaseInterface(imu) }
                    connection.close()
                    throw IOException("could not claim HID interface ${intf.id} (another app may hold it)")
                }
            }
            val link = VitureGen1Link(manager, device, connection, mcu, imu, input, axes, readTimeoutMs)
            link.start()
            return link
        }
    }
}
