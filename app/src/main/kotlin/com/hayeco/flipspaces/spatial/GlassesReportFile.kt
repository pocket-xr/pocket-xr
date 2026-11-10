package com.hayeco.flipspaces.spatial

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.hayeco.flipspaces.core.hands.ProviderStatus
import com.hayeco.flipspaces.core.spatial.GlassesReport
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.hands.GlassesCameraProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 0.5.91: the Menu's "Glasses report": the report ([GlassesReport]) from
 * what Android and the app know now, saved under the app's files (reports/) and handed to Android's share sheet as text,
 * so a tester can paste it into a GitHub issue.
 */
object GlassesReportFile {
    private const val TAG = "FlipSpaces/Report"

    fun build(context: Context): GlassesReport {
        val dm = context.getSystemService(DisplayManager::class.java)
        val displays = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.name?.contains("Built-in", ignoreCase = true) != true }
            .filter { it.name?.startsWith("Pocket XR") != true }
            .map { d ->
                val id = SpaceStore.glassesId(d)
                GlassesReport.Display(
                    name = id?.name ?: d.name.orEmpty(),
                    productId = id?.productId,
                    modes = d.supportedModes.map { m -> "${m.physicalWidth}x${m.physicalHeight}@${m.refreshRate.toInt()}" },
                    current = d.mode.let { m -> "${m.physicalWidth}x${m.physicalHeight}@${m.refreshRate.toInt()}" },
                )
            }
        // 1.1.10: with each interface's class, so a HID interface without a name is listed too.
        val usb = GlassesUsbList.read(context)
        val head = HeadTracker.status.value
        val hands = when (val s = GlassesCameraProvider.status.value) {
            is ProviderStatus.Unavailable -> "not available: ${s.reason}"
            is ProviderStatus.Running -> "running (${s.detail})"
            else -> s.toString()
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        return GlassesReport(
            appVersion = version,
            phone = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            displays = displays,
            usb = usb,
            controlLink = GlassesDisplayControl.reportLink,
            serial = GlassesDisplayControl.reportSerial,
            stereoState = GlassesDisplayControl.reportStereo,
            edidIndex = GlassesDisplayControl.reportEdid,
            headTracking = head.summary,
            headTrackingWorks = head is HeadTrackingStatus.Streaming,
            calibration = GlassesCalibrationStore.reportLine(context),
            hands = hands,
            timeUtc = utc,
            tracking = HeadTracker.lastTrackingLine,
            // 1.2.8: the report descriptors need USB access, so they come from the newest motion recording.
            hidDescriptors = HidMotionRecorder.newest(context)?.let { f -> runCatching { com.hayeco.flipspaces.core.spatial.HidRecording.descriptorsIn(f.readText()) }.getOrNull() }.orEmpty(),
            // 1.2.13 (Discussion #81): the axis layout, why it is the one, and gravity at the last rest.
            headAxes = HeadTracker.axesReport(context),
        )
    }

    /** Builds the report, saves it, logs it and opens the share sheet; the saved file, or null if it could not be written. */
    fun share(context: Context): File? {
        val text = build(context).text()
        Log.i(TAG, "glasses report:\n$text")
        val file = runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports").apply { mkdirs() }
            File(dir, "glasses-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt").apply { writeText(text) }
        }.getOrNull()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Pocket XR glasses report")
            .putExtra(Intent.EXTRA_TEXT, text)
        // 1.1.5 (issue 26: saving it to Downloads with a file manager failed): the file too, so apps that save files get one.
        file?.let { f ->
            runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".reports", f) }
                .onSuccess { u -> send.putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.i(TAG, "the report file is not attached: ${it.message}") }
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share the glasses report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "no share sheet: ${it.message}") }
        return file
    }
}
