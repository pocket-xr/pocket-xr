package com.hayeco.flipspaces

/**
 * 1.5.4: Pocket XR's own log calls, a drop-in for android.util.Log (the same names and arguments). Every line goes to
 * Android's log exactly as before, and also to [AppLogFile.direct], which keeps the latest lines and writes them
 * straight into the log file on phones where Android does not hand an app its own lines back (a vivo V2562 on
 * Android 17, 18 Nov 2026: its log file was empty apart from Pocket XR's own notes).
 *
 * AppLogFile itself keeps android.util.Log, so its check lines still test whether logcat works.
 */
object Log {
    const val VERBOSE = android.util.Log.VERBOSE
    const val DEBUG = android.util.Log.DEBUG
    const val INFO = android.util.Log.INFO
    const val WARN = android.util.Log.WARN
    const val ERROR = android.util.Log.ERROR
    const val ASSERT = android.util.Log.ASSERT

    fun v(tag: String?, msg: String): Int = put(VERBOSE, tag, msg, null)
    fun v(tag: String?, msg: String, tr: Throwable?): Int = put(VERBOSE, tag, msg, tr)
    fun d(tag: String?, msg: String): Int = put(DEBUG, tag, msg, null)
    fun d(tag: String?, msg: String, tr: Throwable?): Int = put(DEBUG, tag, msg, tr)
    fun i(tag: String?, msg: String): Int = put(INFO, tag, msg, null)
    fun i(tag: String?, msg: String, tr: Throwable?): Int = put(INFO, tag, msg, tr)
    fun w(tag: String?, msg: String): Int = put(WARN, tag, msg, null)
    fun w(tag: String?, msg: String, tr: Throwable?): Int = put(WARN, tag, msg, tr)
    fun w(tag: String?, tr: Throwable?): Int = put(WARN, tag, "", tr)
    fun e(tag: String?, msg: String): Int = put(ERROR, tag, msg, null)
    fun e(tag: String?, msg: String, tr: Throwable?): Int = put(ERROR, tag, msg, tr)
    fun wtf(tag: String?, msg: String): Int = put(ASSERT, tag, msg, null)
    fun wtf(tag: String?, msg: String, tr: Throwable?): Int = put(ASSERT, tag, msg, tr)
    fun println(priority: Int, tag: String?, msg: String): Int = put(priority, tag, msg, null)

    fun getStackTraceString(tr: Throwable?): String = android.util.Log.getStackTraceString(tr)
    fun isLoggable(tag: String?, level: Int): Boolean = android.util.Log.isLoggable(tag, level)

    private fun put(priority: Int, tag: String?, msg: String, tr: Throwable?): Int {
        val text = if (tr == null) msg else (if (msg.isEmpty()) "" else msg + "\n") + getStackTraceString(tr)
        val written = android.util.Log.println(priority, tag, text)
        AppLogFile.direct(priority, tag ?: "", text)
        return written
    }
}
