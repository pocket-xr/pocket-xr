package com.hayeco.flipspaces.hands.uvc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.uvc.UvcBulkPayloadSplitter
import com.hayeco.flipspaces.core.uvc.UvcBulkReadPlan
import com.hayeco.flipspaces.core.uvc.UvcCamera
import com.hayeco.flipspaces.core.uvc.UvcChoice
import com.hayeco.flipspaces.core.uvc.UvcDescriptorParser
import com.hayeco.flipspaces.core.uvc.UvcFormatKind
import com.hayeco.flipspaces.core.uvc.UvcFrameAssembler
import com.hayeco.flipspaces.core.uvc.UvcRequest
import com.hayeco.flipspaces.core.uvc.UvcStreamControl
import com.hayeco.flipspaces.core.uvc.UvcStreamingInterface
import com.hayeco.flipspaces.core.uvc.UvcVideoFrame
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * A USB Video Class camera read directly through Android's USB host API: descriptors from
 * UsbDeviceConnection.getRawDescriptors, the standard probe and commit negotiation with class
 * control requests, then bulk IN transfers split into payloads and frames by core. Only
 * requests from the public UVC specification are sent; nothing vendor specific.
 */
class UvcCameraSession(private val usb: UsbManager, private val device: UsbDevice) : Closeable {
    private var connection: UsbDeviceConnection? = null
    private val claimed = mutableListOf<UsbInterface>()
    private var endpoint: UsbEndpoint? = null

    @Volatile
    private var running = false

    lateinit var camera: UvcCamera
        private set

    /** Android's name for the device (/dev/bus/usb/...), to match attach and detach broadcasts. */
    val deviceName: String get() = device.deviceName

    /** What the camera agreed to in the probe, for the setup screen. */
    var agreed: UvcStreamControl? = null
        private set

    fun open(): UvcCamera {
        val c = usb.openDevice(device) ?: throw IOException("Android would not open ${device.deviceName}; USB permission missing or the device went away")
        connection = c
        val raw = c.rawDescriptors ?: throw IOException("The device returned no USB descriptors")
        // 1.4.7: with the device's version and USB setup names, which differ between Aura firmwares (16.1.03 lists a
        // "uvc_bulk" function, 16.1.04 on a Fold4 showed no camera).
        camera = UvcDescriptorParser.parse(raw) ?: throw IOException(
            "No USB Video Class interface on this device (USB version ${device.version}, setups: " +
                (0 until device.configurationCount).joinToString("; ") { device.getConfiguration(it).name ?: "unnamed" } + ")",
        )
        return camera
    }

    /**
     * Negotiates [choice] on [streaming] and reads frames until [stop] or an error. Blocks;
     * run it on its own thread. [onFrame] gets each complete frame on that thread with the
     * uptime (ms) of the USB transfer that completed it; the frame's array comes from
     * [allocate] and belongs to the receiver.
     *
     * The bulk endpoint is read with a queue of UsbRequests (UvcBulkReadPlan), so the host
     * controller always has buffers to fill and this thread sleeps in requestWait until one
     * completes. 0.2.0 called bulkTransfer in a loop, which on the Flip 7 returned at once about
     * 14,900 times a second between frames and kept a core busy.
     *
     * Throws [UsbLinkLostException] when the stream stops after it had worked (the glasses left
     * USB or the link broke) and a plain IOException when it never got going.
     */
    fun stream(
        streaming: UvcStreamingInterface,
        choice: UvcChoice,
        allocate: (Int) -> ByteArray,
        onFrame: (frame: UvcVideoFrame, capturedAtMs: Long) -> Unit,
        onStats: (Stats) -> Unit,
    ) {
        val c = connection ?: throw IOException("Camera not open")
        val ep = streaming.bulkEndpoint
            ?: throw IOException("The camera streams over isochronous transfers, which Android's Java USB API cannot read")
        claim(c, camera.controlInterface)
        val vs = claim(c, streaming.interfaceNumber)

        val length = UvcStreamControl.lengthFor(camera.uvcVersion)
        val want = UvcStreamControl(
            hint = 1,
            formatIndex = choice.format.index,
            frameIndex = choice.frame.index,
            frameIntervalUnits = choice.intervalUnits,
        )
        val result = negotiate(c, streaming.interfaceNumber, want, length)
        agreed = result
        Log.i(TAG, "Committed $result")

        // Bulk streaming runs on alternate setting 0 (UVC 1.5, 2.4.3.2.2): select it explicitly.
        c.setInterface(vs)
        val usbEp = (0 until vs.endpointCount).map { vs.getEndpoint(it) }.firstOrNull { it.address == ep.address }
            ?: throw IOException("Endpoint 0x%02X not found on interface ${vs.id}".format(ep.address))
        endpoint = usbEp

        val packet = usbEp.maxPacketSize.coerceAtLeast(64)
        // Without a stated maximum, payloads end only at short packets.
        val maxPayload = result.maxPayloadTransferSize.toInt().takeIf { it > 0 }?.coerceAtMost(MAX_PAYLOAD) ?: MAX_PAYLOAD
        val plan = UvcBulkReadPlan.of(maxPayload, packet, result.maxVideoFrameSize.toInt().coerceAtLeast(0))
        val splitter = UvcBulkPayloadSplitter(maxPayload, packet)
        val assembler = UvcFrameAssembler(expectedBytes = expectedBytes(choice), allocate = allocate)
        val scratch = ByteArray(plan.requestBytes)
        Log.i(TAG, "Reading with ${plan.requests} queued requests of ${plan.requestBytes} B")

        running = true
        val requests = ArrayList<UsbRequest>(plan.requests)
        try {
            repeat(plan.requests) {
                val r = UsbRequest()
                if (!r.initialize(c, usbEp)) throw IOException("Android would not set up a USB request on endpoint 0x%02X".format(usbEp.address))
                val buffer = ByteBuffer.allocateDirect(plan.requestBytes)
                r.clientData = buffer
                requests += r
                if (!r.queue(buffer)) throw IOException("Android would not queue a USB read on endpoint 0x%02X".format(usbEp.address))
            }
            var lastFrameAt = SystemClock.uptimeMillis()
            var lastStatsAt = lastFrameAt
            // 0.5.55: one payload sink for the whole stream (it was a new lambda for every USB read, hundreds a second);
            // [readAt] carries the read's time into it.
            var readAt = lastFrameAt
            val sink: (ByteArray, Int) -> Unit = { payload, size ->
                val frames = assembler.onPayload(payload, 0, size)
                for (i in frames.indices) {
                    lastFrameAt = readAt
                    onFrame(frames[i], readAt)
                }
            }
            var bytes = 0L
            var reads = 0L
            var waits = 0L
            var errors = 0L
            var errorRun = 0
            while (running) {
                var timedOut = false
                val done: UsbRequest? = try {
                    c.requestWait(WAIT_MS)
                } catch (_: TimeoutException) {
                    timedOut = true
                    null
                }
                val now = SystemClock.uptimeMillis()
                if (done != null) {
                    errorRun = 0
                    val buffer = done.clientData as ByteBuffer
                    val n = buffer.position()
                    buffer.flip()
                    buffer.get(scratch, 0, n)
                    reads++
                    bytes += n
                    readAt = now
                    splitter.onRead(scratch, n, plan.requestBytes, sink)
                    buffer.clear()
                    if (running && !done.queue(buffer)) throw UsbLinkLostException("Android would not queue the next USB read; the glasses may have left USB")
                } else if (timedOut) {
                    waits++
                } else if (running) {
                    // requestWait returned null: the transfer failed (the device went away, or a stall).
                    errors++
                    if (++errorRun >= MAX_ERROR_RUN) {
                        throw UsbLinkLostException("USB reads failed $errorRun times in a row; the glasses may have left USB")
                    }
                    SystemClock.sleep(ERROR_PAUSE_MS)
                }
                if (now - lastStatsAt >= 1000) {
                    onStats(Stats(assembler.framesDone, assembler.framesDropped, assembler.badHeaders, bytes, reads, waits, errors))
                    lastStatsAt = now
                }
                val limit = if (assembler.framesDone > 0) STALL_MS else FIRST_FRAME_MS
                if (running && now - lastFrameAt > limit) {
                    val counts = "$reads reads, $bytes bytes, $waits empty waits, $errors errors, ${assembler.framesDropped} broken frames, " +
                        "${assembler.badHeaders} bad headers"
                    if (assembler.framesDone > 0) {
                        throw UsbLinkLostException("The camera stopped sending video after ${assembler.framesDone} frames ($counts)")
                    }
                    throw IOException(
                        "The camera accepted ${choice.label} but sent no complete frame in ${FIRST_FRAME_MS / 1000} s ($counts). " +
                            "It may need commands outside the UVC standard, which Pocket XR does not send.",
                    )
                }
            }
        } finally {
            running = false
            requests.forEach { runCatching { it.cancel() } }
            // Reap the cancelled requests before freeing them, so no transfer still points at their buffers.
            var left = requests.size
            while (left > 0) {
                val r = try {
                    c.requestWait(REAP_MS)
                } catch (_: TimeoutException) {
                    null
                } ?: break
                if (r in requests) left--
            }
            requests.forEach { runCatching { it.close() } }
        }
    }

    fun stop() {
        running = false
    }

    override fun close() {
        running = false
        val c = connection ?: return
        // Stopping a bulk stream: CLEAR_FEATURE(ENDPOINT_HALT) on the video endpoint (UVC 1.5, 2.4.3.2.2).
        endpoint?.let { c.controlTransfer(0x02, 0x01, 0x00, it.address, null, 0, 200) }
        claimed.forEach { runCatching { c.releaseInterface(it) } }
        claimed.clear()
        c.close()
        connection = null
    }

    private fun claim(c: UsbDeviceConnection, number: Int): UsbInterface {
        val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == number && it.alternateSetting == 0 }
            ?: throw IOException("Interface $number not found")
        if (claimed.none { it.id == number }) {
            // force = true detaches a kernel driver if one is bound; only the camera's own
            // interfaces are claimed, never the HID, audio or network ones.
            if (!c.claimInterface(iface, true)) throw IOException("Android would not let Pocket XR claim USB interface $number")
            claimed += iface
        }
        return iface
    }

    /** SET_CUR probe, GET_CUR probe, SET_CUR commit with what the camera returned. */
    private fun negotiate(c: UsbDeviceConnection, vs: Int, want: UvcStreamControl, length: Int): UvcStreamControl {
        var len = length
        var sent = c.controlTransfer(UvcRequest.TYPE_SET, UvcRequest.SET_CUR, UvcRequest.value(UvcRequest.VS_PROBE_CONTROL), vs, want.encode(len), len, 1000)
        if (sent < 0 && len > 26) {
            // Some cameras report a newer UVC version than their probe block; retry with 1.0's.
            len = 26
            sent = c.controlTransfer(UvcRequest.TYPE_SET, UvcRequest.SET_CUR, UvcRequest.value(UvcRequest.VS_PROBE_CONTROL), vs, want.encode(len), len, 1000)
        }
        if (sent < 0) throw IOException("The camera refused the UVC probe (SET_CUR returned $sent)")
        val reply = ByteArray(len)
        val got = c.controlTransfer(UvcRequest.TYPE_GET, UvcRequest.GET_CUR, UvcRequest.value(UvcRequest.VS_PROBE_CONTROL), vs, reply, len, 1000)
        if (got < 26) throw IOException("The camera did not answer the UVC probe (GET_CUR returned $got)")
        val agreed = UvcStreamControl.decode(reply, got)
        val committed = c.controlTransfer(UvcRequest.TYPE_SET, UvcRequest.SET_CUR, UvcRequest.value(UvcRequest.VS_COMMIT_CONTROL), vs, agreed.encode(len), len, 1000)
        if (committed < 0) throw IOException("The camera refused the UVC commit (SET_CUR returned $committed)")
        return agreed
    }

    private fun expectedBytes(choice: UvcChoice): Int =
        if (choice.format.kind == UvcFormatKind.UNCOMPRESSED && choice.format.bitsPerPixel > 0) {
            choice.frame.width * choice.frame.height * choice.format.bitsPerPixel / 8
        } else {
            0
        }

    /** [waits] counts requestWait calls that timed out with nothing to read (normal between frames, a few a second at most). */
    data class Stats(val frames: Long, val dropped: Long, val badHeaders: Long, val bytes: Long, val reads: Long, val waits: Long, val errors: Long)

    companion object {
        private const val TAG = "FlipSpacesUvc"
        private const val MAX_PAYLOAD = 4 * 1024 * 1024
        private const val WAIT_MS = 250L
        private const val REAP_MS = 50L
        private const val MAX_ERROR_RUN = 20
        private const val ERROR_PAUSE_MS = 5L

        /** No first frame for this long: the camera never streamed. */
        private const val FIRST_FRAME_MS = 6000L

        /** No frame for this long after frames had come: the link is lost. */
        private const val STALL_MS = 2000L
    }
}

/** The stream had worked and then stopped: the glasses left USB or the link broke. Worth reconnecting. */
class UsbLinkLostException(message: String) : IOException(message)
