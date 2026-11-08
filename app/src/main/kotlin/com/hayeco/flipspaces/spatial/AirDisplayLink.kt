package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import com.hayeco.flipspaces.core.spatial.AirDisplayMode
import java.io.IOException

/**
 * 1.2.3 (issue 54): one exchange with an Air's control interface ([AirDisplayMode]): open it, write a command, read until
 * its answer, let it go. **Untested on Air glasses.** Only used while the Menu's "Air display mode (untested)" switch is
 * on, which is off by default. Blocking: call it off the main thread.
 */
object AirDisplayLink {
    private const val TIMEOUT_MS = 1000
    private const val MAX_PACKETS = 64

    /** The mode the plugged in Air reports. */
    fun mode(context: Context): Int {
        val payload = exchange(context, AirDisplayMode.getMode(), AirDisplayMode.CMD_GET_MODE)
        return AirDisplayMode.modeOf(payload) ?: throw IOException("the glasses' answer to get mode was too short")
    }

    /** Asks the plugged in Air for [mode] ([AirDisplayMode.MODE_2D] or [AirDisplayMode.MODE_3D_SBS]). */
    fun set(context: Context, mode: Int) {
        val payload = exchange(context, AirDisplayMode.setMode(mode), AirDisplayMode.CMD_SET_MODE)
        if (!AirDisplayMode.setTaken(payload)) throw IOException("the glasses did not take mode $mode (answer ${payload.firstOrNull()})")
    }

    private fun exchange(context: Context, packet: ByteArray, id: Int): ByteArray {
        val device = AirImuLink.find(context) ?: throw IOException("no Air glasses plugged in")
        val manager = AirImuLink.manager(context)
        if (!manager.hasPermission(device)) throw IOException("no USB permission for ${AirImuLink.glassesName(device)}")
        val wanted = AirDisplayMode.controlInterface(device.productId)
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == wanted && it.interfaceClass == UsbConstants.USB_CLASS_HID && endpoint(it, UsbConstants.USB_DIR_IN) != null }
            ?: throw IOException("no HID interface $wanted with an interrupt IN endpoint on ${AirImuLink.glassesName(device)}")
        val connection = manager.openDevice(device) ?: throw IOException("Android would not open ${AirImuLink.glassesName(device)}")
        try {
            if (!connection.claimInterface(intf, true)) throw IOException("could not claim HID interface ${intf.id}")
            try {
                if (!send(connection, intf, packet)) throw IOException("HID interface ${intf.id} did not take the command")
                val input = endpoint(intf, UsbConstants.USB_DIR_IN)!!
                val buf = ByteArray(maxOf(AirDisplayMode.PACKET_BYTES, input.maxPacketSize))
                val until = SystemClock.uptimeMillis() + TIMEOUT_MS
                repeat(MAX_PACKETS) {
                    val left = (until - SystemClock.uptimeMillis()).toInt()
                    if (left <= 0) throw IOException("no answer from the glasses within $TIMEOUT_MS ms")
                    val n = connection.bulkTransfer(input, buf, buf.size, left)
                    if (n > 0) AirDisplayMode.answer(buf, n, id)?.let { return it }
                }
                throw IOException("no answer among $MAX_PACKETS packets from the glasses")
            } finally {
                connection.releaseInterface(intf)
            }
        } finally {
            connection.close()
        }
    }

    private fun endpoint(intf: UsbInterface, direction: Int): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
        .firstOrNull { it.direction == direction && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

    /** To the interrupt OUT endpoint, or without one as a HID SET_REPORT (output report), as [AirImuLink] writes. */
    private fun send(connection: UsbDeviceConnection, intf: UsbInterface, packet: ByteArray): Boolean {
        val out = endpoint(intf, UsbConstants.USB_DIR_OUT)
        val n = if (out != null) connection.bulkTransfer(out, packet, packet.size, TIMEOUT_MS) else connection.controlTransfer(0x21, 0x09, 0x0200, intf.id, packet, packet.size, TIMEOUT_MS)
        return n == packet.size
    }
}
