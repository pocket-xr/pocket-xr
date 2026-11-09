package com.hayeco.flipspaces

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hayeco.flipspaces.core.LogBundle
import com.hayeco.flipspaces.core.spatial.GlassesVendors
import com.hayeco.flipspaces.spatial.GlassesReportFile
import com.hayeco.flipspaces.spatial.GlassesUsbList
import com.hayeco.flipspaces.spatial.HeadDiagnosticsFile
import com.hayeco.flipspaces.spatial.HidMotionRecorder
import com.hayeco.flipspaces.spatial.SpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 1.1.14 (a tester asked how to send Mike his logs): "Send logs to Mike", in Settings and on the head tracking screen.
 * The rotating log files ([AppLogFile]) and the head tracking diagnostics go into one zip ([LogBundle]) under reports/,
 * with personal details taken out, and an email to [LogBundle.EMAIL] opens with it attached for the user to read and
 * send. Nothing leaves the phone unless they tap send in their email app; no server, no new permission.
 */
object SendLogs {
    private const val TAG = "FlipSpaces/SendLogs"
    private const val PREFIX = "pocketxr-logs-"

    /** The zips kept in reports/: the newest few, so a second try does not pile them up. */
    private const val KEEP = 2

    /** 1.2.6: a motion recording older than this is not sent. */
    private const val MOTION_MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000

    /** Builds the zip off the main thread, then opens the email from [context] (an activity). */
    fun send(context: Context, owner: LifecycleOwner) {
        Toast.makeText(context, "Getting the logs ready...", Toast.LENGTH_SHORT).show()
        owner.lifecycleScope.launch {
            val made = withContext(Dispatchers.IO) { runCatching { build(context) } }
            made.onSuccess { (file, info) -> open(context, file, info) }
                .onFailure {
                    Log.w(TAG, "logs not zipped: ${it.javaClass.simpleName}: ${it.message}")
                    Toast.makeText(context, "Could not put the logs together: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun info(context: Context): LogBundle.Info {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val store = SpaceStore(context)
        // 1.2.6 (Discussion #24: a VITURE Beast's zip said "Unknown glasses (the last used; none plugged in now)"): glasses
        // of every make Pocket XR knows, not only XREAL's.
        val plugged = GlassesUsbList.read(context).filter { GlassesVendors.name(it.vendorId) != null }
        val glasses = if (plugged.isEmpty()) {
            "${store.model.label} (the last used; none plugged in now)"
        } else {
            store.model.label + ", plugged in: " + plugged.joinToString("; ") { "%s (USB %04X:%04X)".format(Locale.ROOT, it.name, it.vendorId, it.productId) }
        }
        return LogBundle.Info(
            appVersion = pkg?.versionName ?: "?",
            versionCode = pkg?.longVersionCode ?: 0L,
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            glasses = glasses,
            timeUtc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
        )
    }

    internal fun build(context: Context): Pair<File, LogBundle.Info> {
        AppLogFile.flush()
        val info = info(context)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
        val file = File(dir, PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip")
        val logDir = AppLogFile.dir ?: runCatching { context.getExternalFilesDir("logs") }.getOrNull() ?: File(context.filesDir, "logs")
        // The Wi-Fi names the phone knows (read through Shizuku for the Control Center), taken out wherever they appear.
        val wifi = com.hayeco.flipspaces.phone.PhoneConnections.wifi
        val secrets = (listOfNotNull(wifi.connected) + wifi.saved.map { it.ssid }).distinct()
        // The paired Bluetooth names, likewise, each the same <bt-device-N> in every file of the zip.
        val btNames = com.hayeco.flipspaces.phone.PhoneConnections.pairedNames(context)
        // 1.2.6 (Discussion #24): the Glasses report (every USB device with its interfaces, endpoints and HID report
        // descriptors, and the display modes Android reports) and the newest motion recording from the last two days.
        val extras = buildList {
            runCatching { GlassesReportFile.build(context).text() }.onSuccess { add("glasses-report.txt" to it) }
                .onFailure { add("glasses-report.txt" to "not made: ${it.javaClass.simpleName}: ${it.message}") }
            HidMotionRecorder.newest(context)?.takeIf { System.currentTimeMillis() - it.lastModified() < MOTION_MAX_AGE_MS }?.let { f ->
                runCatching { f.readText() }.onSuccess { add("motion/${f.name}" to it) }
            }
        }
        val written = file.outputStream().use { out ->
            LogBundle.write(out, info, HeadDiagnosticsFile.text(context), LogBundle.logFiles(logDir), secrets, btNames = btNames, extras = extras)
        }
        Log.i(TAG, "logs zipped for Mike: ${file.name}, ${written.bytes / 1024} KB, ${written.included.size} log files" +
            if (written.leftOut.isEmpty()) "" else ", ${written.leftOut.size} older ones left out")
        return file to info
    }

    internal fun open(context: Context, file: File, info: LogBundle.Info) {
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".reports", file) }
            .getOrElse {
                Log.w(TAG, "the zip cannot be attached: ${it.message}")
                Toast.makeText(context, "Could not attach the logs: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
        val send = email(uri, file.name, info)
        // Email apps first (a mailto selector); with none, any app that takes the zip, still addressed to Mike.
        val toEmail = Intent(send).apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
        try {
            context.startActivity(toEmail)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent.createChooser(send, LogBundle.BUTTON))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(context, "No email app found. The logs are saved as ${file.name}.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The email to Mike with the zip at [uri] attached, readable by the app that takes it (the grant goes with the clip). */
    internal fun email(uri: Uri, name: String, info: LogBundle.Info): Intent =
        Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(LogBundle.EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, LogBundle.subject(info))
            .putExtra(Intent.EXTRA_TEXT, LogBundle.body(info))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(name, uri) }
}
