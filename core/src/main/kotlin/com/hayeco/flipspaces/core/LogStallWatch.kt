package com.hayeco.flipspaces.core

/**
 * 1.5.3 (a vivo V2562 on Android 17, three reports on 18 Nov 2026): Pocket XR's log file had only the first fraction of
 * a second after each start. The reader (AppLogFile) follows `logcat --pid` and writes what it prints; on that phone
 * logcat printed the lines already in its buffer and then nothing more, without ending, so the reader waited for ever.
 *
 * This decides when that has happened. When no line has come for [quietMs], the reader logs a check line of its own
 * ([checkLine]) through Android's log. A working logcat hands it back within [answerMs]; if it does not, the stream
 * is stalled and the reader stops waiting on it. After [maxMisses] stalls in a row (the next way of reading did not
 * help either), Android is not handing the app its own lines on this phone, and the file says so once.
 */
class LogStallWatch(
    private val quietMs: Long = QUIET_MS,
    private val answerMs: Long = ANSWER_MS,
    private val maxMisses: Int = MAX_MISSES,
) {
    sealed interface Step {
        /** Nothing to do. */
        data object Wait : Step

        /** Log this check line now. */
        data class SendCheck(val line: String) : Step

        /** The check line did not come back: stop waiting on this logcat and read it another way. */
        data class Stalled(val quietForMs: Long) : Step

        /** Checks have failed [misses] times in a row: Android is not giving the app its own lines here. */
        data class NoLines(val misses: Int) : Step
    }

    private var lastLineMs = -1L
    private var sentAt = -1L
    private var sentLine: String? = null
    private var count = 0
    private var misses = 0
    private var toldNoLines = false

    /** The check line being waited for, if any. */
    val waitingFor: String? get() = sentLine

    /** A line came from logcat at [nowMs]. */
    @Synchronized
    fun onLine(line: String, nowMs: Long) {
        lastLineMs = nowMs
        val waiting = sentLine
        if (waiting != null && line.contains(waiting)) {
            sentLine = null
            sentAt = -1L
            misses = 0
        }
    }

    /** The reader started (or restarted) at [nowMs]: quiet time counts from here. */
    @Synchronized
    fun started(nowMs: Long) {
        if (lastLineMs < 0) lastLineMs = nowMs
    }

    /** What to do at [nowMs]; call every second or so. */
    @Synchronized
    fun tick(nowMs: Long): Step {
        if (lastLineMs < 0) lastLineMs = nowMs
        val waiting = sentLine
        if (waiting != null) {
            if (nowMs - sentAt < answerMs) return Step.Wait
            val quietFor = nowMs - lastLineMs
            sentLine = null
            sentAt = -1L
            misses++
            lastLineMs = nowMs
            if (misses >= maxMisses) {
                if (toldNoLines) return Step.Wait
                toldNoLines = true
                return Step.NoLines(misses)
            }
            return Step.Stalled(quietFor)
        }
        if (nowMs - lastLineMs < quietMs) return Step.Wait
        count++
        val line = "$CHECK_PREFIX $count"
        sentLine = line
        sentAt = nowMs
        return Step.SendCheck(line)
    }

    companion object {
        /** Quiet this long before checking: an idle app logs little, so a check costs one line every half minute. */
        const val QUIET_MS = 30_000L
        /** A working logcat hands a line back well within this. */
        const val ANSWER_MS = 5_000L
        const val MAX_MISSES = 2
        const val CHECK_PREFIX = "log file check"
    }
}
