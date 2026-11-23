package com.hayeco.flipspaces.hands.uvc

import java.nio.ByteBuffer

/** 1.6.9: isochronous USB reads through usbdevfs (cpp/usb_iso.c), which Android's Java USB API does not offer. */
object UsbIso {
    val loaded: Boolean = runCatching { System.loadLibrary("pocketxr_usbiso") }.isSuccess

    /** A handle, or a negative errno. */
    @JvmStatic external fun start(fd: Int, endpoint: Int, packetSize: Int, packets: Int, urbs: Int): Long

    /** Bytes copied into [out], 0 on a timeout, or a negative errno; see cpp/usb_iso.c for [lengths]. */
    @JvmStatic external fun reap(handle: Long, out: ByteBuffer, lengths: IntArray, timeoutMs: Int): Int

    @JvmStatic external fun stop(handle: Long)
}
