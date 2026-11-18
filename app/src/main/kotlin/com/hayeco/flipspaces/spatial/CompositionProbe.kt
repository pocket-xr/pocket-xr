package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.HwcComposition
import com.hayeco.flipspaces.platform.ShizukuBridge

/**
 * 0.5.16: once per side by side session, [DELAY_MS] after it starts,
 * reads SurfaceFlinger's HWC tables (`dumpsys SurfaceFlinger` through Shizuku, as the shell user,
 * read only) and logs how the Space is composed on the glasses and what sits above it
 * ([HwcComposition.line]): DEVICE is a hardware overlay, CLIENT a copy on the GPU every frame.
 * Without Shizuku it says so and does nothing. The dump takes SurfaceFlinger's lock for a moment,
 * so it runs once, off the GL thread.
 */
class CompositionProbe(private val context: Context, private val tag: String) {
    private var sbsSinceMs = -1L
    private var done = false
    @Volatile private var running = false

    /** Called with every perf line: [sbs] while the Space is side by side. */
    fun maybeRun(sbs: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!sbs) {
            sbsSinceMs = -1L
            done = false
            return
        }
        if (sbsSinceMs < 0) sbsSinceMs = now
        if (done || running || now - sbsSinceMs < DELAY_MS) return
        done = true
        if (ShizukuBridge.status(context) != ShizukuStatus.READY) {
            Log.i(tag, "glasses composition: not read (Shizuku is not ready); check with adb shell dumpsys SurfaceFlinger")
            return
        }
        running = true
        Thread({
            try {
                val r = ShizukuBridge.shell(listOf("dumpsys", "SurfaceFlinger"), timeoutSeconds = 10)
                val displays = HwcComposition.parse(r.output)
                Log.i(tag, HwcComposition.line(displays))
            } catch (e: Exception) {
                Log.i(tag, "glasses composition: not read (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                running = false
            }
        }, "composition-probe").apply { isDaemon = true }.start()
    }

    companion object {
        const val DELAY_MS = 20_000L
    }
}
