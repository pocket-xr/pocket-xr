package com.hayeco.flipspaces

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hayeco.flipspaces.core.CrashReport
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.0.1: catches a crash, keeps it, and offers it the next time Pocket XR opens.
 *
 * [install] runs first in [FlipSpacesApp], in every process. A crash writes [CrashReport.text] to
 * `files/crash/last-crash.txt` and then goes on to Android's own handler, so the app still closes as before. [offer],
 * from the trackpad and the settings screen, asks once whether to send it: by email to Mike, or as a GitHub bug, both
 * filled in. Nothing leaves the phone unless the user sends it from their email app or GitHub.
 */
object CrashCatcher {
    private const val TAG = "FlipSpaces/Crash"
    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"
    private const val STACK_FILE = "last-crash-stack.txt"
    private const val INFO_FILE = "last-crash-info.txt"

    /** At most one offer per process, so a second screen opening does not ask again. */
    @Volatile private var offered = false

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }.onFailure { Log.e(TAG, "could not save the crash report", it) }
            if (previous != null) previous.uncaughtException(thread, error) else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val info = info(context, thread.name)
        File(dir, STACK_FILE).writeText(stack)
        File(dir, INFO_FILE).writeText(listOf(info.appVersion, info.phone, info.android, info.process, info.thread, info.time).joinToString("\n"))
        File(dir, FILE).writeText(CrashReport.text(info, stack))
        Log.e(TAG, "crash saved for the next start: ${CrashReport.firstLine(stack)}")
    }

    private fun info(context: Context, thread: String): CrashReport.Info {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (vc ${it.longVersionCode})" }
        }.getOrDefault("?")
        return CrashReport.Info(
            appVersion = version,
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            process = Application.getProcessName(),
            thread = thread,
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()),
        )
    }

    /** If the last run crashed, asks once whether to send the report. Safe to call from any onResume. */
    fun offer(activity: Activity) {
        if (offered) return
        val dir = File(activity.filesDir, DIR)
        val report = File(dir, FILE).takeIf { it.exists() }?.readText() ?: return
        val stack = File(dir, STACK_FILE).takeIf { it.exists() }?.readText() ?: report
        offered = true
        // The crash's own version, phone, process, thread and time, as saved; the phone's own details if that file is gone.
        val info = File(dir, INFO_FILE).takeIf { it.exists() }?.readLines()?.takeIf { it.size == 6 }
            ?.let { CrashReport.Info(it[0], it[1], it[2], it[3], it[4], it[5]) }
            ?: info(activity, "?")
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.home_crash_title)
            .setMessage(R.string.home_crash_message)
            .setPositiveButton(R.string.home_crash_email) { _, _ -> if (email(activity, info, report)) clear(activity) }
            .setNeutralButton(R.string.home_crash_github) { _, _ -> if (open(activity, CrashReport.issueUrl(info, stack))) clear(activity) }
            .setNegativeButton(R.string.home_crash_dont_send) { _, _ -> clear(activity) }
            .setCancelable(true)
            .show()
    }

    private fun email(activity: Activity, info: CrashReport.Info, report: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(CrashReport.emailUri(info, report)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReport.EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, CrashReport.subject(info))
            .putExtra(Intent.EXTRA_TEXT, report)
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.home_no_email_app, Toast.LENGTH_LONG).show()
            offered = false
            false
        }
    }

    private fun open(activity: Activity, url: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(activity, R.string.home_no_browser, Toast.LENGTH_LONG).show()
        offered = false
        false
    }

    private fun clear(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
