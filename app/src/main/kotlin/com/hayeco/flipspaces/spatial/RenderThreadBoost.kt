package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import com.hayeco.flipspaces.Log

/**
 * 0.5.55 (findings-choppiness-vc70-vc74 9.3 item 2, Mike's OK of 10/28): the Space's GL render thread at
 * THREAD_PRIORITY_URGENT_DISPLAY, as the head tracking threads already are (until 0.5.54 it ran at the default 0 and
 * woke up to 3 ms late at p99 under heat), and an ADPF hint session (PerformanceHintManager) on it with the frame's
 * period as the target and each frame's own work reported, so the CPU governor sees the deadline instead of a thread
 * that sleeps most of the frame. Render thread only; nothing here changes a phone setting.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
class RenderThreadBoost(private val context: Context, private val tag: String) {
    private var session: PerformanceHintManager.Session? = null
    private var tid = 0
    private var targetNs = 0L

    /** The figures for the log: priority before and after, and the hint session's state. */
    var line: String = "render thread: not set up yet"
        private set

    /** Reported work durations since the last [takeReports], for the perf lines. */
    private var reports = 0
    private var reportFailures = 0

    /** On the render thread (onSurfaceCreated): the priority and a new hint session for this thread. */
    fun onRenderThread(periodNs: Long) {
        val me = Process.myTid()
        val before = runCatching { Process.getThreadPriority(me) }.getOrDefault(Int.MIN_VALUE)
        val set = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val after = runCatching { Process.getThreadPriority(me) }.getOrDefault(Int.MIN_VALUE)
        val prio = "render thread %d: priority %d (urgent display %d; was %d)%s".format(
            java.util.Locale.ROOT, me, after, Process.THREAD_PRIORITY_URGENT_DISPLAY, before,
            set.exceptionOrNull()?.let { ", the change was refused: ${it.javaClass.simpleName}" } ?: "",
        )
        if (me != tid) {
            closeSession()
            tid = me
        }
        val hint = openSession(periodNs)
        line = "$prio; $hint"
        Log.i(tag, line)
    }

    private fun openSession(periodNs: Long): String {
        if (session != null) {
            retarget(periodNs)
            return "hint session kept, target %.2f ms".format(java.util.Locale.ROOT, targetNs / 1e6)
        }
        val target = periodNs.takeIf { it > 0L } ?: DEFAULT_PERIOD_NS
        val mgr = context.getSystemService(PerformanceHintManager::class.java) ?: return "hint session: no PerformanceHintManager on this phone"
        val preferred = runCatching { mgr.preferredUpdateRateNanos }.getOrDefault(-1L)
        val s = runCatching { mgr.createHintSession(intArrayOf(tid), target) }
        val created = s.getOrNull() ?: return "hint session: not available (${s.exceptionOrNull()?.javaClass?.simpleName ?: "the phone gave none"}); the render thread runs without one"
        session = created
        targetNs = target
        return "hint session on: target %.2f ms (the frame), each frame's work reported; preferred update rate %s".format(
            java.util.Locale.ROOT, target / 1e6, if (preferred > 0L) "%.1f ms".format(java.util.Locale.ROOT, preferred / 1e6) else "not given",
        )
    }

    /** The display's period changed (a new mode): the target follows. Render thread. */
    fun retarget(periodNs: Long) {
        val s = session ?: return
        if (periodNs <= 0L || kotlin.math.abs(periodNs - targetNs) < periodNs / 50) return
        if (runCatching { s.updateTargetWorkDuration(periodNs) }.isSuccess) {
            targetNs = periodNs
            Log.i(tag, "hint session: target %.2f ms (a new display period)".format(java.util.Locale.ROOT, periodNs / 1e6))
        }
    }

    /** One frame's own work on the render thread, ns (the late sleep left out). Render thread, every frame; no allocation. */
    fun report(workNs: Long) {
        val s = session ?: return
        if (workNs <= 0L) return
        try {
            s.reportActualWorkDuration(workNs)
            reports++
        } catch (e: RuntimeException) {
            reportFailures++
        }
    }

    /** 0.5.62: the head reader thread in the session with this one, once it runs ([HeadTracker.headReaderTid]). */
    private var headTidInSession = 0
    private var headJoin = "head reader not in the session yet"

    /**
     * 0.5.62 (reference-rig findings): the head reader joins this hint session, so the governor keeps it on the cores the
     * frame runs on (XREAL pins its IMU thread to the big cores). Session.setThreads needs Android 14. Render thread.
     */
    private fun followHeadReader() {
        val s = session ?: return
        val head = HeadTracker.headReaderTid
        if (head <= 0 || head == headTidInSession || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val r = runCatching { s.setThreads(intArrayOf(tid, head)) }
        headTidInSession = head
        headJoin = if (r.isSuccess) "head reader %d in the session".format(java.util.Locale.ROOT, head)
        else "head reader %d not taken in (%s)".format(java.util.Locale.ROOT, head, r.exceptionOrNull()?.javaClass?.simpleName)
        Log.i(tag, "hint session: $headJoin")
    }

    /** For the perf line: "hint session N reports, target T ms" since the last call. */
    fun takeReportLine(): String {
        followHeadReader()
        val s = if (session == null) "hint session off" else "hint session %d reports%s, target %.2f ms, %s".format(
            java.util.Locale.ROOT, reports, if (reportFailures > 0) " ($reportFailures refused)" else "", targetNs / 1e6, headJoin,
        )
        reports = 0
        reportFailures = 0
        return s
    }

    fun closeSession() {
        session?.let { runCatching { it.close() } }
        session = null
        targetNs = 0L
        headTidInSession = 0
        headJoin = "head reader not in the session yet"
    }

    companion object {
        const val DEFAULT_PERIOD_NS = 16_666_667L
    }
}
