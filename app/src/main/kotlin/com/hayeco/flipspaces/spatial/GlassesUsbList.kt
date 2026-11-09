package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.hayeco.flipspaces.core.spatial.GlassesReport
import com.hayeco.flipspaces.core.spatial.UsbInterfaceInfo

/**
 * 1.1.10: the USB devices Android lists, each interface with its class, subclass and name. Reading the list needs no USB
 * permission and opens nothing on the glasses. Shared by the Glasses report, the head tracker's link choice
 * ([com.hayeco.flipspaces.core.spatial.HeadImuTransport]) and the head tracking diagnostics.
 *
 * 1.2.6 (Discussion #24): each interface's number, protocol and endpoints too, for glasses Pocket XR has no reader for.
 */
object GlassesUsbList {
    fun read(context: Context): List<GlassesReport.Usb> = runCatching {
        context.getSystemService(UsbManager::class.java).deviceList.values.map { u ->
            val ifaces = (0 until u.interfaceCount).map { i ->
                val f = u.getInterface(i)
                UsbInterfaceInfo(
                    f.interfaceClass, f.interfaceSubclass, f.name, id = f.id, protocol = f.interfaceProtocol,
                    endpoints = (0 until f.endpointCount).map { endpoint(f.getEndpoint(it)) },
                )
            }
            GlassesReport.Usb(u.vendorId, u.productId, u.productName.orEmpty(), ifaces.mapNotNull { it.name }, ifaces)
        }
    }.getOrDefault(emptyList())

    /** "IN interrupt 64 B": the direction, the transfer type and the packet size. */
    fun endpoint(e: UsbEndpoint): String {
        val dir = if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        val type = when (e.type) {
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            else -> "control"
        }
        return "$dir $type ${e.maxPacketSize} B"
    }
}
