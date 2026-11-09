package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.hayeco.flipspaces.core.spatial.FramerCounts
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.ImuReport
import com.hayeco.flipspaces.core.spatial.ImuReportSource
import com.hayeco.flipspaces.core.spatial.VitureAxes
import com.hayeco.flipspaces.core.spatial.VitureImuFramer
import com.hayeco.flipspaces.core.spatial.VitureImuProtocol
import java.io.Closeable
import java.io.IOException

/**
 * 1.2.8 (Discussion #24, experimental, off by default): VITURE glasses' motion sensor over Android's USB host API
 * ([VitureImuProtocol]): the HID interface with an interrupt IN endpoint claimed from the kernel, the documented raw stream
 * command written to it, and its 64 byte reports read. Needs the user's USB permission for the glasses.
 */
class VitureImuLink private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val input: UsbEndpoint,
    axes: VitureAxes,
    readTimeoutMs: Int,
) : ImuReportSource, Closeable {
    private val framer = VitureImuFramer(axes)
    private val buf = ByteArray(maxOf(VitureImuProtocol.PACKET_BYTES, input.maxPacketSize))
    private val timeoutMs = readTimeoutMs

    val label: String = "USB HID interface ${intf.id} of the ${glassesName(device)} (experimental VITURE reader)"

    override val counts: FramerCounts get() = framer.counts
    override val noReportsHint: String = HeadTrackingStatus.VITURE_HINT

    val clockLine: String get() = framer.clockLine

    override fun read(out: ArrayList<ImuReport>): Int {
        out.clear()
        val n = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        if (n < 0) return if (attached()) 0 else -1
        if (n > 0) framer.append(buf, n, System.nanoTime(), out)
        return n
    }

    private fun attached(): Boolean = runCatching { manager.deviceList.values.any { it.deviceName == device.deviceName } }.getOrDefault(false)

    override fun close() {
        runCatching { send(connection, intf, VitureImuProtocol.stop(), timeoutMs) }
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }

    companion object {
        fun manager(context: Context): UsbManager = context.getSystemService(UsbManager::class.java)

        /** The first VITURE device Android lists, or null. */
        fun find(context: Context): UsbDevice? =
            runCatching { manager(context).deviceList.values.firstOrNull { VitureImuProtocol.isViture(it.vendorId) } }.getOrNull()

        fun glassesName(device: UsbDevice): String =
            "%s (USB %04X:%04X)".format(java.util.Locale.ROOT, device.productName?.takeIf { it.isNotBlank() } ?: "VITURE glasses", device.vendorId, device.productId)

        /** The HID interfaces of [device] with an interrupt IN endpoint, in Android's order. */
        fun hidInterfaces(device: UsbDevice): List<UsbInterface> =
            (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID && inEndpoint(it) != null }

        /** Opens the first HID interface that has an OUT path and starts the raw stream; throws [IOException] with what went wrong. */
        fun open(context: Context, device: UsbDevice, axes: VitureAxes, readTimeoutMs: Int): VitureImuLink {
            val manager = manager(context)
            if (!manager.hasPermission(device)) throw IOException("no USB permission for ${glassesName(device)}")
            val intf = hidInterfaces(device).firstOrNull()
                ?: throw IOException("no HID interface with an interrupt IN endpoint; Android lists: " + com.hayeco.flipspaces.hands.GlassesUsbAccess.interfaces(device).replace('\n', ';'))
            val connection = manager.openDevice(device) ?: throw IOException("Android would not open ${glassesName(device)}")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("could not claim HID interface ${intf.id} (another app may hold it)")
            }
            val link = VitureImuLink(manager, device, connection, intf, inEndpoint(intf)!!, axes, readTimeoutMs)
            if (!send(connection, intf, VitureImuProtocol.startRaw(), readTimeoutMs)) {
                link.close()
                throw IOException("the start command to HID interface ${intf.id} was not accepted")
            }
            return link
        }

        fun inEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        private fun outEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        /**
         * [frame] padded to 64 bytes, to the interface's interrupt OUT endpoint, or without one as a HID SET_REPORT
         * (output report, no report id). True when all of it went.
         */
        fun send(connection: UsbDeviceConnection, intf: UsbInterface, frame: ByteArray, timeoutMs: Int): Boolean {
            val packet = frame.copyOf(VitureImuProtocol.PACKET_BYTES)
            val out = outEndpoint(intf)
            val n = if (out != null) connection.bulkTransfer(out, packet, packet.size, timeoutMs)
            else connection.controlTransfer(0x21, 0x09, 0x0200, intf.id, packet, packet.size, timeoutMs)
            return n == packet.size
        }
    }
}
