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
import com.hayeco.flipspaces.core.spatial.VitureDevices
import com.hayeco.flipspaces.core.spatial.VitureHidCommands
import com.hayeco.flipspaces.core.spatial.VitureImuFramer
import com.hayeco.flipspaces.core.spatial.VitureImuProtocol
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.2.8 (Discussion #24, experimental, off by default): VITURE glasses' motion sensor over Android's USB host API
 * ([VitureImuProtocol]): the HID interface with an interrupt IN endpoint claimed from the kernel, the documented raw stream
 * command written to it, and its 64 byte reports read. Needs the user's USB permission for the glasses.
 *
 * 1.2.10 (Discussion #24, second report: after the first stream, every start in the same plug-in was "not accepted"):
 * commands go through [VitureHidCommands], which reads what the glasses still hold first; [close] sends the stop, reads
 * its answer, releases the interface and closes the connection, once; one link holds the glasses at a time, so a new
 * reader waits for the old one to let go; and [axes] change on the open stream.
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
    private val closed = AtomicBoolean(false)

    val label: String = "USB HID interface ${intf.id} of the ${glassesName(device)} (experimental VITURE reader)"

    /** The Android device name the link is on (a new one each plug-in). */
    val deviceName: String = device.deviceName

    /** How the start command went, for the log. */
    var startLine: String = ""
        private set

    /** The axis switches, applied from the next sample on: no reopen. */
    var axes: VitureAxes
        get() = framer.axes
        set(value) {
            framer.axes = value
        }

    override val counts: FramerCounts get() = framer.counts
    override val noReportsHint: String = HeadTrackingStatus.VITURE_HINT

    val clockLine: String get() = framer.clockLine

    override fun read(out: ArrayList<ImuReport>): Int {
        out.clear()
        val n = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        // 1.2.10: unplugged, or the USB access gone, ends the stream (the tracker then asks again or waits for the plug-in).
        if (n < 0) return if (attached() && runCatching { manager.hasPermission(device) }.getOrDefault(false)) 0 else -1
        if (n > 0) framer.append(buf, n, System.nanoTime(), out)
        return n
    }

    private fun attached(): Boolean = runCatching { manager.deviceList.values.any { it.deviceName == device.deviceName } }.getOrDefault(false)

    private val pipe = pipeOf(connection, intf, input)

    /** Stops the stream (its answer read), releases the interface and closes the connection. Once; any thread. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (attached()) runCatching { VitureHidCommands.send(pipe, VitureImuProtocol.stop(), COMMAND_TIMEOUT_MS) }
            runCatching { connection.releaseInterface(intf) }
            runCatching { connection.close() }
        } finally {
            if (current === this) current = null
            gate.release()
        }
    }

    companion object {
        /** A command's write timeout: the glasses answer in a few ms, or not while they hold an unread report. */
        private const val COMMAND_TIMEOUT_MS = 250

        /** How long a new link waits for the old one to let go before closing it itself. */
        private const val HANDOVER_MS = 2_000L

        /** 1.2.10: one link holds the glasses at a time; [close] lets the next in. */
        private val gate = Semaphore(1)
        @Volatile private var current: VitureImuLink? = null

        fun manager(context: Context): UsbManager = context.getSystemService(UsbManager::class.java)

        /**
         * The VITURE device with the motion sensor, or null. 1.2.10: not simply the first VITURE device Android lists;
         * after a replug the Beast's microphone (35CA:1102) was sometimes first ([VitureDevices]).
         */
        fun find(context: Context): UsbDevice? = runCatching {
            VitureDevices.pick(manager(context).deviceList.values.filter { VitureImuProtocol.isViture(it.vendorId) }, { it.productId }, { it.productName })
        }.getOrNull()

        fun glassesName(device: UsbDevice): String =
            "%s (USB %04X:%04X)".format(java.util.Locale.ROOT, device.productName?.takeIf { it.isNotBlank() } ?: "VITURE glasses", device.vendorId, device.productId)

        /** The HID interfaces of [device] with an interrupt IN endpoint, in Android's order. */
        fun hidInterfaces(device: UsbDevice): List<UsbInterface> =
            (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID && inEndpoint(it) != null }

        /**
         * Opens the first HID interface that has an OUT path and starts the raw stream; throws [IOException] with what
         * went wrong. 1.2.10: USB access is checked first, a link still open is waited for (and closed if it does not let
         * go), and the start is sent after reading what the glasses hold.
         */
        fun open(context: Context, device: UsbDevice, axes: VitureAxes, readTimeoutMs: Int): VitureImuLink {
            val manager = manager(context)
            if (!manager.hasPermission(device)) throw IOException("no USB permission for ${glassesName(device)}")
            val intf = hidInterfaces(device).firstOrNull()
                ?: throw IOException("no HID interface with an interrupt IN endpoint; Android lists: " + com.hayeco.flipspaces.hands.GlassesUsbAccess.interfaces(device).replace('\n', ';'))
            if (!gate.tryAcquire(HANDOVER_MS, TimeUnit.MILLISECONDS)) {
                current?.close()
                if (!gate.tryAcquire(HANDOVER_MS, TimeUnit.MILLISECONDS)) throw IOException("the last VITURE link did not let go of the glasses")
            }
            val connection = manager.openDevice(device)
            if (connection == null) {
                gate.release()
                throw IOException("Android would not open ${glassesName(device)}")
            }
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                gate.release()
                throw IOException("could not claim HID interface ${intf.id} (another app may hold it)")
            }
            val link = VitureImuLink(manager, device, connection, intf, inEndpoint(intf)!!, axes, readTimeoutMs)
            current = link
            val result = VitureHidCommands.send(link.pipe, VitureImuProtocol.startRaw(), COMMAND_TIMEOUT_MS)
            link.startLine = "start ${result.line()}"
            if (!VitureHidCommands.streamWillCome(result)) {
                link.close()
                throw IOException("the start command to HID interface ${intf.id} was not accepted (${result.line()})")
            }
            return link
        }

        fun inEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        private fun outEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        /** The claimed interface as [VitureHidCommands] sends on it: the interrupt endpoints, and SET_REPORT on the control pipe. */
        private fun pipeOf(connection: UsbDeviceConnection, intf: UsbInterface, input: UsbEndpoint) = object : VitureHidCommands.Pipe {
            override fun read(buf: ByteArray, timeoutMs: Int): Int = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
            override fun write(packet: ByteArray, timeoutMs: Int): Boolean {
                val out = outEndpoint(intf) ?: return setReport(packet, timeoutMs)
                return connection.bulkTransfer(out, packet, packet.size, timeoutMs) == packet.size
            }
            override fun setReport(packet: ByteArray, timeoutMs: Int): Boolean =
                connection.controlTransfer(0x21, 0x09, 0x0200, intf.id, packet, packet.size, timeoutMs) == packet.size
        }

        /**
         * [frame] padded to 64 bytes, to the interface's interrupt OUT endpoint, or without one as a HID SET_REPORT
         * (output report, no report id). True when all of it went. The motion recording's plain send.
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
