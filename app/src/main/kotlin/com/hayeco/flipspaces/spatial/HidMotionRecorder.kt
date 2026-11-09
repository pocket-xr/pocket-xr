package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.spatial.GlassesVendors
import com.hayeco.flipspaces.core.spatial.HidRecording
import com.hayeco.flipspaces.core.spatial.VitureImuProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 1.2.8 (Discussion #24): the head tracking screen's opt-in "Record motion for 20 seconds", for glasses that are not
 * XREAL's ([GlassesVendors.recordable]). With the user's USB access it claims the glasses' HID interfaces, reads each one's
 * report descriptor, then keeps every input report for [HidRecording.SECONDS] with the phone's time it came at. On VITURE
 * glasses the documented raw stream start ([VitureImuProtocol.startRaw]) goes out [HidRecording.START_COMMAND_AT_MS] in,
 * and the stop at the end, so the file shows what they send on their own and after it. Saved under motion/, the newest
 * few kept; Send logs takes the newest.
 */
object HidMotionRecorder {
    private const val TAG = "FlipSpaces/Head"
    private const val KEEP = 3
    private const val PREFIX = "hid-motion-"
    private const val ACCESS_WAIT_MS = 30_000L

    private val _line = MutableStateFlow<String?>(null)

    /** What the recorder is doing or did, for the head tracking screen; null before the first. */
    val line: StateFlow<String?> = _line.asStateFlow()

    @Volatile private var running: Thread? = null

    val busy: Boolean get() = running?.isAlive == true

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "motion")

    /** The newest recording, null when none was made. */
    fun newest(context: Context): File? =
        dir(context).listFiles { f -> f.name.startsWith(PREFIX) }?.maxByOrNull { it.name }

    /** The newest recording's summary line (its fourth), for the diagnostics. */
    fun newestLine(context: Context): String? = newest(context)?.let { f ->
        runCatching { f.useLines { it.drop(3).firstOrNull() } }.getOrNull()?.let { "${f.name}: $it" }
    }

    /** Starts a recording; false when one is under way. */
    fun start(context: Context): Boolean {
        if (busy) return false
        val app = context.applicationContext
        val t = Thread({ runCatching { record(app) }.onFailure { fail("${it.javaClass.simpleName}: ${it.message}") } }, "pocketxr-motion-record")
        running = t
        t.start()
        return true
    }

    private fun fail(why: String) {
        _line.value = "Not recorded: $why"
        Log.w(TAG, "motion recording: not recorded: $why")
    }

    private fun record(context: Context) {
        val store = SpaceStore(context)
        if (store.vitureHeadTracking) return fail("turn off Experimental VITURE head tracking first; it holds the glasses' motion sensor")
        val listed = GlassesUsbList.read(context)
        val pick = GlassesVendors.recordable(listed) ?: return fail("no glasses other than XREAL's on USB (XREAL glasses have their own reader)")
        val manager = VitureImuLink.manager(context)
        val device: UsbDevice = manager.deviceList.values.firstOrNull { it.vendorId == pick.vendorId && it.productId == pick.productId }
            ?: return fail("the glasses went away")
        val name = "%04X:%04X \"%s\"".format(Locale.ROOT, device.vendorId, device.productId, device.productName.orEmpty())
        if (!manager.hasPermission(device)) {
            _line.value = "Allow Pocket XR in Android's USB prompt for $name..."
            com.hayeco.flipspaces.hands.GlassesUsbAccess.requestPermission(context, device)
            val until = SystemClock.elapsedRealtime() + ACCESS_WAIT_MS
            while (!manager.hasPermission(device) && SystemClock.elapsedRealtime() < until) Thread.sleep(250)
            if (!manager.hasPermission(device)) return fail("USB access for $name was not given")
        }
        val connection = manager.openDevice(device) ?: return fail("Android would not open $name")
        val viture = VitureImuProtocol.isViture(device.vendorId)
        val hids = (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_HID }
        val claimed = mutableListOf<UsbInterface>()
        val descriptors = mutableListOf<HidRecording.Descriptor>()
        for (i in hids) {
            if (!connection.claimInterface(i, true)) {
                descriptors += HidRecording.Descriptor(i.id, null, "could not claim it")
                continue
            }
            claimed += i
            val buf = ByteArray(4096)
            // GET_DESCRIPTOR (standard, to the interface) for the HID report descriptor (type 0x22).
            val n = connection.controlTransfer(0x81, 0x06, 0x2200, i.id, buf, buf.size, 1000)
            descriptors += if (n > 0) HidRecording.Descriptor(i.id, buf.copyOf(n)) else HidRecording.Descriptor(i.id, null, "no answer ($n)")
        }
        val reports = ArrayList<HidRecording.Report>()
        val sent = mutableListOf<Pair<Long, String>>()
        val notes = mutableListOf<String>()
        val start = System.nanoTime()
        val readers = claimed.mapNotNull { i ->
            val input = VitureImuLink.inEndpoint(i) ?: return@mapNotNull null
            Thread({
                val buf = ByteArray(maxOf(64, input.maxPacketSize))
                while (!Thread.currentThread().isInterrupted) {
                    val n = connection.bulkTransfer(input, buf, buf.size, 100)
                    if (n > 0) synchronized(reports) {
                        if (reports.size < HidRecording.MAX_REPORTS) reports += HidRecording.Report(System.nanoTime(), i.id, buf.copyOf(n))
                    }
                }
            }, "pocketxr-motion-if${i.id}").apply { start() }
        }
        if (readers.isEmpty()) notes += "No HID interface with an interrupt IN endpoint could be read."
        Log.i(TAG, "motion recording: $name, ${claimed.size} of ${hids.size} HID interfaces claimed, ${readers.size} read, ${HidRecording.SECONDS} s")
        val commandIf = claimed.firstOrNull { VitureImuLink.inEndpoint(it) != null }
        var startSent = false
        try {
            for (s in 0 until HidRecording.SECONDS * 4) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (viture && !startSent && elapsedMs >= HidRecording.START_COMMAND_AT_MS && commandIf != null) {
                    startSent = true
                    val ok = VitureImuLink.send(connection, commandIf, VitureImuProtocol.startRaw(), 1000)
                    sent += System.nanoTime() to "VITURE raw IMU stream start (0x0301, 02 02) on interface ${commandIf.id}: ${if (ok) "accepted" else "not accepted"}"
                }
                _line.value = "Recording motion from $name: ${HidRecording.SECONDS - s / 4} s left. Move your head slowly: left, right, up, down, then tilt."
                Thread.sleep(250)
            }
        } finally {
            if (viture && startSent && commandIf != null) {
                val ok = VitureImuLink.send(connection, commandIf, VitureImuProtocol.stop(), 1000)
                sent += System.nanoTime() to "VITURE IMU stream stop (0x0301, 00 00): ${if (ok) "accepted" else "not accepted"}"
            }
            readers.forEach { it.interrupt() }
            readers.forEach { it.join(500) }
            claimed.forEach { runCatching { connection.releaseInterface(it) } }
            runCatching { connection.close() }
        }
        val end = System.nanoTime()
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        val kept = synchronized(reports) { reports.toList() }
        val text = HidRecording.text(name, version, utc, descriptors, sent, kept, start, end, notes)
        val dir = dir(context).apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
        val file = File(dir, PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
        file.writeText(text)
        val summary = HidRecording.summary(kept, (end - start) / 1e9)
        _line.value = "Saved ${file.name}: $summary. Send logs takes it along. If the glasses' own buttons act up, unplug them and plug them in again."
        Log.i(TAG, "motion recording saved: ${file.name}: $summary; descriptors: ${descriptors.joinToString("; ") { HidRecording.descriptorLine(it).take(80) }}")
    }
}
