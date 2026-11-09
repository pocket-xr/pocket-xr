package com.hayeco.flipspaces.spatial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hayeco.flipspaces.core.spatial.HeadDiagnosticsText
import com.hayeco.flipspaces.core.spatial.HeadImuTransport
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.GlassesVendors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 1.1.10 (a tester could copy only the start of the head tracking diagnostics): the whole screen as one text
 * ([HeadDiagnosticsText]), put on the clipboard in full, or saved under reports/ and shared like the Glasses report
 * ([GlassesReportFile]) through the same FileProvider.
 */
object HeadDiagnosticsFile {
    private const val TAG = "FlipSpaces/Head"

    fun text(context: Context, soc: String, dynamicSensors: List<String>, timing: String?): String {
        val store = SpaceStore(context)
        val status = HeadTracker.status.value
        val live = HeadTracker.live.value
        // 1.2.6 (Discussion #24: a VITURE Beast's diagnostics said "USB devices from XREAL: none"): every USB device, with
        // its interfaces' numbers, protocols and endpoints.
        val usb = GlassesUsbList.read(context).map { u ->
            GlassesVendors.line(u) + "; head tracking link: " + HeadImuTransport.choose(u.vendorId, u.productId, u.infos).label
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        return HeadDiagnosticsText(
            appVersion = version,
            phone = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            timeUtc = utc,
            status = status.summary,
            tracking = status is HeadTrackingStatus.Streaming,
            route = HeadTracker.route.value,
            answer = HeadTracker.answer.value,
            networks = HeadTracker.networks.value,
            ethernet = HeadTracker.ethernet.value,
            connectResult = HeadTracker.connectResult.value,
            attempts = HeadTracker.attempts.value,
            reports = live?.let { "%.0f IMU reports a second".format(Locale.ROOT, it.rateHz) } ?: "none yet",
            reportCounts = live?.counts?.let { c ->
                "${c.imu} IMU, ${c.magnetometer} magnetometer, ${c.unknownType} unknown type, ${c.badLength} other length, ${c.droppedBytes} bytes skipped of ${c.bytes}"
            },
            orientation = live?.let { "yaw %+.1f, pitch %+.1f, roll %+.1f".format(Locale.ROOT, it.yaw, it.pitch, it.roll) },
            axes = store.axisMap.label,
            predictMs = store.predictMs,
            delayCalibrated = store.delayCalibrated,
            levelCalibrated = store.levelCalibrated,
            timing = timing,
            soc = soc,
            dynamicSensors = dynamicSensors,
            usb = usb,
            motion = HidMotionRecorder.newestLine(context),
        ).text()
    }

    /** 1.1.14: [text] from a screen that has not read the phone's facts (Send logs to Mike in Settings). */
    fun text(context: Context): String = text(
        context,
        soc = if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE,
        dynamicSensors = runCatching {
            context.getSystemService(android.hardware.SensorManager::class.java)?.getDynamicSensorList(android.hardware.Sensor.TYPE_ALL)
                ?.map { "${it.name} (${it.stringType})" }.orEmpty()
        }.getOrDefault(emptyList()),
        timing = SpaceSession.timingLine,
    )

    /** The whole text on the clipboard; false if Android refused it. */
    fun copy(context: Context, text: String): Boolean = runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket XR head tracking diagnostics", text))
        Log.i(TAG, "head tracking diagnostics copied (${text.length} characters)")
    }.isSuccess

    /** Saves [text] under reports/ and opens the share sheet with the text and the file. */
    fun share(context: Context, text: String): File? {
        val file = runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports").apply { mkdirs() }
            File(dir, "head-tracking-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt").apply { writeText(text) }
        }.getOrNull()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Pocket XR head tracking diagnostics")
            .putExtra(Intent.EXTRA_TEXT, text)
        file?.let { f ->
            runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".reports", f) }
                .onSuccess { u -> send.putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.i(TAG, "the diagnostics file is not attached: ${it.message}") }
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share the head tracking diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "no share sheet: ${it.message}") }
        return file
    }
}
