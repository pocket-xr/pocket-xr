package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.10 (a tester's XREAL-made glasses on a Pixel 8a, USB 3318:0442: no head tracking, and the diagnostics blamed a
 * missing NCM or ECM interface): how a pair of XREAL glasses carries its IMU, from what Android lists for the USB device.
 *
 * Two families (docs/SPATIAL.md 1.1):
 * - USB network: the One family and the Aura, a CDC NCM / ECM network with the glasses at 169.254.2.1. The only path the
 *   app reads.
 * - USB HID: the Air family (0x0423 to 0x0432) and the newer Helen family (0x043F to 0x0442), whose IMU comes over a HID
 *   interface. The app has no HID IMU reader.
 *
 * The product id decides where it is known; otherwise the interfaces do: a CDC network interface means the network path,
 * a HID interface with no CDC network means HID. The One family is matched first, so nothing changes for it.
 */
enum class ImuTransport(val label: String) {
    USB_NETWORK("USB network (NCM / ECM), the One family's link"),
    USB_HID("USB HID, like XREAL's Air family"),
    /** 1.2.8: VITURE's glasses ([VitureImuProtocol]): read only with the experimental switch on. */
    VITURE_HID("USB HID, VITURE's own protocol (experimental, off unless turned on)"),
    UNKNOWN("not known from the USB device"),
}

/**
 * One USB interface as Android lists it: class, subclass and its name string, when it has one. 1.2.8: [id], [protocol]
 * and [endpoints] too, for the motion recording and Send logs.
 */
data class UsbInterfaceInfo(
    val cls: Int,
    val subclass: Int = 0,
    val name: String? = null,
    val id: Int = -1,
    val protocol: Int = 0,
    /** Each endpoint as "IN interrupt 64 B" and the like. */
    val endpoints: List<String> = emptyList(),
) {
    /** [label] with the interface number, subclass, protocol and endpoints, for the logs. */
    fun detail(): String {
        val head = (if (id >= 0) "#$id " else "") + label()
        val extra = buildList {
            if (cls >= 0) add("subclass $subclass, protocol $protocol")
            if (endpoints.isNotEmpty()) add("endpoints " + endpoints.joinToString(" / "))
        }
        return if (extra.isEmpty()) head else "$head [${extra.joinToString("; ")}]"
    }

    /** For the Glasses report: the name and the class in words. */
    fun label(): String {
        val kind = when (cls) {
            USB_CLASS_HID -> "HID"
            USB_CLASS_CDC -> when (subclass) {
                CDC_SUBCLASS_ECM -> "CDC ECM"
                CDC_SUBCLASS_NCM -> "CDC NCM"
                else -> "CDC subclass $subclass"
            }
            USB_CLASS_CDC_DATA -> "CDC data"
            USB_CLASS_AUDIO -> "audio"
            USB_CLASS_VIDEO -> "video"
            USB_CLASS_STORAGE -> "mass storage"
            USB_CLASS_VENDOR -> "vendor specific"
            -1 -> null
            else -> "class $cls"
        }
        val n = name?.trim().orEmpty()
        return when {
            kind == null -> n.ifEmpty { "unnamed" }
            n.isEmpty() -> kind
            else -> "$n ($kind)"
        }
    }

    companion object {
        const val USB_CLASS_AUDIO = 0x01
        const val USB_CLASS_CDC = 0x02
        const val USB_CLASS_HID = 0x03
        const val USB_CLASS_STORAGE = 0x08
        const val USB_CLASS_CDC_DATA = 0x0A
        const val USB_CLASS_VIDEO = 0x0E
        const val USB_CLASS_VENDOR = 0xFF
        const val CDC_SUBCLASS_ECM = 0x06
        const val CDC_SUBCLASS_NCM = 0x0D

        /** A name only, for reports made before the class was kept: the class is not known. */
        fun named(name: String) = UsbInterfaceInfo(-1, 0, name)
    }
}

object HeadImuTransport {
    /** Product ids on the One family's USB network link, kernel id and the one below it. */
    val NETWORK_PRODUCTS: Set<Int> = setOf(
        0x0435, 0x0436, // One Pro
        0x0437, 0x0438, // One
        0x0439, 0x043A, // Aura
        0x043D, 0x043E, // 1S
        0x0443, 0x0444, // not named, network link
    )

    /** Product ids whose IMU is on USB HID: the Air family, and Helen / Helen Pro. */
    val HID_PRODUCTS: Set<Int> = setOf(
        0x0423, 0x0424, // Air
        0x0431, 0x0432, // Air 2 Pro
        0x0427, 0x0428, // Air 2
        0x0425, 0x0426, // Air 2 Ultra
        0x043F, 0x0440, // Helen
        0x0441, 0x0442, // Helen Pro
    )

    fun hasCdcNetwork(interfaces: List<UsbInterfaceInfo>): Boolean = interfaces.any { i ->
        (i.cls == UsbInterfaceInfo.USB_CLASS_CDC && (i.subclass == UsbInterfaceInfo.CDC_SUBCLASS_ECM || i.subclass == UsbInterfaceInfo.CDC_SUBCLASS_NCM)) ||
            i.name.orEmpty().let { n -> NETWORK_NAME.containsMatchIn(n) }
    }

    fun hasHid(interfaces: List<UsbInterfaceInfo>): Boolean = interfaces.any { i ->
        i.cls == UsbInterfaceInfo.USB_CLASS_HID || (i.cls == -1 && i.name.orEmpty().contains("hid", ignoreCase = true))
    }

    private val NETWORK_NAME = Regex("\\b(NCM|ECM)\\b|Network Control Model|Ethernet", RegexOption.IGNORE_CASE)

    /** The transport for an XREAL USB device ([vendorId] 0x3318) or a VITURE one (1.2.8); [UNKNOWN] for any other vendor. */
    fun choose(vendorId: Int, productId: Int, interfaces: List<UsbInterfaceInfo>): ImuTransport = when {
        vendorId == VitureImuProtocol.VENDOR -> ImuTransport.VITURE_HID
        vendorId != GlassesReport.XREAL_VENDOR -> ImuTransport.UNKNOWN
        productId in NETWORK_PRODUCTS -> ImuTransport.USB_NETWORK
        hasCdcNetwork(interfaces) -> ImuTransport.USB_NETWORK
        productId in HID_PRODUCTS -> ImuTransport.USB_HID
        hasHid(interfaces) -> ImuTransport.USB_HID
        else -> ImuTransport.UNKNOWN
    }

    /** The first XREAL device of [usb] whose link is HID, with its transport decided; null when none is. */
    fun hidGlasses(usb: List<GlassesReport.Usb>): GlassesReport.Usb? =
        usb.firstOrNull { it.vendorId == GlassesReport.XREAL_VENDOR && choose(it.vendorId, it.productId, it.infos) == ImuTransport.USB_HID }

    /** 1.2.8: the first VITURE device of [usb], null when none is plugged in. */
    fun vitureGlasses(usb: List<GlassesReport.Usb>): GlassesReport.Usb? = usb.firstOrNull { it.vendorId == VitureImuProtocol.VENDOR }
}

/**
 * 1.1.10: the phone's own system in words, for lines that say what the system may not have done. One UI only on Samsung;
 * every other phone (a Pixel included) is "Android".
 */
object PhoneSystem {
    fun name(manufacturer: String?): String = if (manufacturer?.trim()?.equals("samsung", ignoreCase = true) == true) "One UI" else "Android"
}
