package com.hayeco.flipspaces

import android.content.Context
import android.os.Process
import android.util.Log
import com.hayeco.flipspaces.core.LogStallWatch
import com.hayeco.flipspaces.core.RotatingLogFile
import java.io.File

/**
 * 0.5.28 (the phone's background logcat recorder stopped mid session, and a phone
 * restart kills it): a log that cannot be lost. Started with the process ([FlipSpacesApp]), always on, it reads this
 * process's own lines from logcat (an app may read its own; the :panels service's lines reach it through the panel
 * log relay) and writes them, as `logcat -v threadtime` prints them, into
 * `Android/data/com.hayeco.flipspaces/files/logs/` on the phone's storage: `flipspaces.log` the newest, then
 * `flipspaces.1.log` to `flipspaces.7.log`, 8 MB each ([RotatingLogFile]). A log pull reads them with
 * `adb pull /sdcard/Android/data/com.hayeco.flipspaces/files/logs`, no recorder needed. Logcat itself is untouched.
 * If the reader stops it starts again a second later from the last line's time.
 *
 * 1.5.3 (a vivo V2562 on Android 17: logcat printed its buffer and then nothing, without ending): a watchdog
 * ([LogStallWatch]) logs a check line after 30 quiet seconds. If logcat does not hand it back, the reader stops
 * following logcat and reads it in short dumps every 2 seconds instead. If that brings nothing either, the file says
 * once that Android is not giving the app its lines on this phone.
 *
 * 1.5.4 (the same vivo: its file then had only those notes): Pocket XR's own lines also come here by way of
 * [com.hayeco.flipspaces.Log] ([direct]). The latest [KEEP] are kept in memory; once Android is found not to give the
 * app its lines, they and every line after go straight into the file. On phones where logcat works nothing is written
 * twice.
 */
object AppLogFile {
    private const val TAG = "FlipSpaces/LogFile"

    /** The flush interval: at most this much is lost with a killed process. */
    private const val FLUSH_MS = 1_000L
    private const val RESTART_MS = 1_000L
    /** How often the dump reader asks logcat for new lines, once following it has stalled. */
    private const val DUMP_MS = 2_000L

    private val watch = LogStallWatch()
    /** Following logcat stalled: read it in dumps instead. */
    @Volatile private var dumps = false
    /** The logcat being followed now, so the watchdog can stop it. */
    @Volatile private var following: java.lang.Process? = null

    /** 1.5.4: Pocket XR's own lines written straight to the file (logcat gives the app nothing on this phone). */
    @Volatile private var directToFile = false
    private const val KEEP = 600
    private val kept = ArrayDeque<String>()
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)

    /**
     * One of Pocket XR's own lines ([com.hayeco.flipspaces.Log]), as `logcat -v threadtime` prints it. Kept in memory,
     * and written to the file once [directToFile] is on.
     */
    fun direct(priority: Int, tag: String, text: String) {
        val level = when (priority) {
            android.util.Log.VERBOSE -> 'V'
            android.util.Log.DEBUG -> 'D'
            android.util.Log.INFO -> 'I'
            android.util.Log.WARN -> 'W'
            android.util.Log.ERROR -> 'E'
            else -> 'F'
        }
        val head = synchronized(stamp) { stamp.format(java.util.Date()) } +
            String.format(java.util.Locale.ROOT, " %5d %5d %c %s: ", Process.myPid(), Process.myTid(), level, tag)
        val lines = text.split('\n').map { head + it }
        val target = file
        synchronized(kept) {
            if (directToFile && target != null) {
                lines.forEach { target.write(it) }
                return
            }
            lines.forEach { kept.addLast(it) }
            while (kept.size > KEEP) kept.removeFirst()
        }
    }

    /** From now on Pocket XR's own lines go straight to the file, starting with the ones kept so far. */
    private fun startDirect(file: RotatingLogFile) {
        synchronized(kept) {
            if (directToFile) return
            file.write("---- log file: the last ${kept.size} lines Pocket XR logged before this note, and every line after it, are written here directly ----")
            kept.forEach { file.write(it) }
            kept.clear()
            directToFile = true
        }
        file.flush()
    }

    @Volatile private var started = false

    /** Where the files are, once started. */
    @Volatile var dir: File? = null
        private set

    @Volatile private var file: RotatingLogFile? = null

    /** 1.1.14: the buffered lines written out now, before Send logs to Mike reads the files. */
    fun flush() {
        runCatching { file?.flush() }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val pid = Process.myPid()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (vc ${it.longVersionCode})" } }.getOrDefault("?")
        val header = "---- Pocket XR $version, pid $pid, started ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} ----"
        // 1.1.2 (a fresh reinstall: the external files folder refused with EACCES, and the app closed at start): the log is
        // best effort. The external folder, else the app's own, else no log file at all.
        val candidates = listOfNotNull(runCatching { context.getExternalFilesDir("logs") }.getOrNull(), File(context.filesDir, "logs"))
        var file: RotatingLogFile? = null
        for (d in candidates) {
            val f = runCatching { RotatingLogFile(d).also { it.write(header); it.flush() } }
                .onFailure { android.util.Log.w("FlipSpaces/Log", "log file in $d not usable: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull() ?: continue
            dir = d
            file = f
            break
        }
        if (file == null) return
        this.file = file
        Thread({ run(file, pid) }, "fs-log-file").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        // Written out every second even when no new line comes, so a quiet process's last lines are on disk.
        Thread({
            while (true) {
                Thread.sleep(FLUSH_MS)
                runCatching { file.flush() }
            }
        }, "fs-log-flush").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Thread({ watchdog(file) }, "fs-log-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Log.i(TAG, "this process's log lines also go to ${dir?.absolutePath} (8 files of 8 MB, flipspaces.log the newest; adb pull /sdcard/Android/data/${context.packageName}/files/logs)")
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    /** Checks every second that logcat still hands lines back (see [LogStallWatch]). */
    private fun watchdog(file: RotatingLogFile) {
        watch.started(now())
        while (true) {
            Thread.sleep(1_000L)
            when (val step = watch.tick(now())) {
                is LogStallWatch.Step.SendCheck -> Log.i(TAG, step.line)
                is LogStallWatch.Step.Stalled -> {
                    file.write("---- log file: logcat sent nothing for ${step.quietForMs / 1000} s, not even this file's own check line; reading it every ${DUMP_MS / 1000} s instead ----")
                    file.flush()
                    dumps = true
                    runCatching { following?.destroy() }
                }
                is LogStallWatch.Step.NoLines -> {
                    file.write("---- log file: Android on this phone is not giving Pocket XR its own log lines (${step.misses} checks in a row came back empty). Some phone makers limit app logs. ----")
                    startDirect(file)
                }
                LogStallWatch.Step.Wait -> Unit
            }
        }
    }

    private fun run(file: RotatingLogFile, pid: Int) {
        var since: String? = null
        // The dump reader asks again from the last line's time, so the lines at that time come again: skip the ones
        // already written.
        val recent = ArrayDeque<String>()
        fun take(line: String): Boolean {
            if (dumps && line in recent) return false
            recent.addLast(line)
            if (recent.size > 200) recent.removeFirst()
            file.write(line)
            watch.onLine(line, now())
            // threadtime: "MM-DD HH:MM:SS.mmm ..." (18 characters).
            if (line.length >= 18 && line[2] == '-' && line[5] == ' ') since = line.substring(0, 18)
            return true
        }
        while (true) {
            if (dumps) {
                val cmd = mutableListOf("logcat", "-d", "-v", "threadtime", "--pid=$pid")
                since?.let { cmd += listOf("-T", it) }
                val proc = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start() }.getOrNull()
                if (proc != null) {
                    runCatching { proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { take(it) } } }
                    runCatching { proc.destroy() }
                }
                Thread.sleep(DUMP_MS)
                continue
            }
            val cmd = mutableListOf("logcat", "-v", "threadtime", "--pid=$pid")
            // After a restart, from the last line's time on (that one line may come twice).
            since?.let { cmd += listOf("-T", it) }
            val proc = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start() }.getOrNull()
            if (proc == null) {
                file.write("---- log file: logcat could not be started; trying again ----")
                file.flush()
                Thread.sleep(RESTART_MS * 10)
                continue
            }
            following = proc
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) take(line)
                }
            }
            following = null
            runCatching { proc.destroy() }
            if (dumps) continue
            file.write("---- log file: logcat stopped; starting it again ----")
            file.flush()
            Thread.sleep(RESTART_MS)
        }
    }
}
