package com.hayeco.flipspaces.core.pointer

import kotlin.math.roundToInt

/**
 * 1.2.3 (issue 59: "sticks and buttons on the phone that games see as a real controller"): a virtual gamepad made through
 * Linux's user space HID driver, `/dev/uhid`, which the shell user may open (Pocket XR's panel service runs as the shell
 * through Shizuku). Android then lists a new game controller, and games use their own controller support with no mapping.
 *
 * The device describes itself with our own HID report descriptor ([DESCRIPTOR]): a Game Pad with two sticks (X, Y and
 * Z, Rz), a hat switch (unused, centred) and 16 buttons. Linux numbers a game pad's buttons from BTN_SOUTH, and Android's
 * generic key layout reads them as A, B, C, X, Y, Z, L1, R1, L2, R2, Select, Start ([BUTTON_BIT]). It uses the pid.codes
 * open source vendor id and its test product id, so it claims no other maker's device.
 *
 * The uhid events are the kernel's packed structs (include/uapi/linux/uhid.h), little endian: [create] (UHID_CREATE2)
 * and [input] (UHID_INPUT2). Closing the file destroys the device.
 */
object UhidGamepad {
    const val UHID_CREATE2 = 11
    const val UHID_INPUT2 = 12
    const val BUS_USB = 0x03

    /** pid.codes, the open source hardware vendor id, and its test product id. */
    const val VENDOR = 0x1209
    const val PRODUCT = 0x0001

    const val NAME = "Pocket XR gamepad"
    const val REPORT_BYTES = 7
    private const val DATA_MAX = 4096

    /** Our report descriptor: a Game Pad, 4 axes of a byte, a 4 bit hat and 4 bits of padding, 16 buttons. */
    val DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x05, // Usage (Game Pad)
        0xA1, 0x01, // Collection (Application)
        0xA1, 0x00, //   Collection (Physical)
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, // Usage X, Y, Z, Rz
        0x15, 0x00, 0x26, 0xFF, 0x00, // Logical 0 to 255
        0x75, 0x08, 0x95, 0x04, // 4 fields of 8 bits
        0x81, 0x02, // Input (Data, Variable, Absolute)
        0xC0, //   End Collection
        0x09, 0x39, // Usage (Hat switch)
        0x15, 0x00, 0x25, 0x07, // Logical 0 to 7
        0x35, 0x00, 0x46, 0x3B, 0x01, // Physical 0 to 315
        0x65, 0x14, // Unit (degrees)
        0x75, 0x04, 0x95, 0x01, // 1 field of 4 bits
        0x81, 0x42, // Input (Data, Variable, Absolute, Null state)
        0x65, 0x00, // Unit (none)
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03, // 4 bits of padding
        0x05, 0x09, // Usage Page (Button)
        0x19, 0x01, 0x29, 0x10, // Buttons 1 to 16
        0x15, 0x00, 0x25, 0x01, // Logical 0 to 1
        0x75, 0x01, 0x95, 0x10, // 16 fields of 1 bit
        0x81, 0x02, // Input (Data, Variable, Absolute)
        0xC0, // End Collection
    )

    /** Each control's bit in the 16 button field (button n is bit n - 1). */
    val BUTTON_BIT: Map<PadControl, Int> = mapOf(
        PadControl.A to 0,
        PadControl.B to 1,
        PadControl.X to 3,
        PadControl.Y to 4,
        PadControl.L1 to 6,
        PadControl.R1 to 7,
        PadControl.L2 to 8,
        PadControl.R2 to 9,
        PadControl.SELECT to 10,
        PadControl.START to 11,
    )

    /** The hat's null value: centred, no direction. */
    private const val HAT_NONE = 8

    /** [s] as one input report: X, Y, Z, Rz, the hat, the buttons. */
    fun report(s: ControllerState): ByteArray {
        var buttons = 0
        for (c in s.buttons) BUTTON_BIT[c]?.let { buttons = buttons or (1 shl it) }
        return byteArrayOf(
            axis(s.leftX), axis(s.leftY), axis(s.rightX), axis(s.rightY),
            HAT_NONE.toByte(),
            buttons.toByte(), (buttons ushr 8).toByte(),
        )
    }

    /** A stick's -1 to 1 as the report's 0 to 255, 128 centred. */
    fun axis(v: Float): Byte {
        val c = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
        return ((c + 1f) / 2f * 255f).roundToInt().coerceIn(0, 255).toByte()
    }

    /** UHID_CREATE2: type, name[128], phys[64], uniq[64], rd_size u16, bus u16, vendor, product, version, country u32, rd_data[4096]. */
    fun create(name: String = NAME): ByteArray {
        val e = ByteArray(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + DATA_MAX)
        put(e, 0, UHID_CREATE2, 4)
        val n = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(n, 0, e, 4, minOf(n.size, 127))
        var at = 4 + 128 + 64 + 64
        put(e, at, DESCRIPTOR.size, 2); at += 2
        put(e, at, BUS_USB, 2); at += 2
        put(e, at, VENDOR, 4); at += 4
        put(e, at, PRODUCT, 4); at += 4
        put(e, at, 1, 4); at += 4 // version
        put(e, at, 0, 4); at += 4 // country
        System.arraycopy(DESCRIPTOR, 0, e, at, DESCRIPTOR.size)
        return e
    }

    /** UHID_INPUT2: type, size u16, data[4096]. */
    fun input(report: ByteArray): ByteArray {
        require(report.size <= DATA_MAX)
        val e = ByteArray(4 + 2 + DATA_MAX)
        put(e, 0, UHID_INPUT2, 4)
        put(e, 4, report.size, 2)
        System.arraycopy(report, 0, e, 6, report.size)
        return e
    }

    private fun put(p: ByteArray, at: Int, v: Int, bytes: Int) {
        for (i in 0 until bytes) p[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}
