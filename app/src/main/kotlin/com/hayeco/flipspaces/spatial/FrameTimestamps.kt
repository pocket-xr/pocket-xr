package com.hayeco.flipspaces.spatial

import android.opengl.EGL14
import android.util.Log
import com.hayeco.flipspaces.core.spatial.GlassesFrameClock

/**
 * 0.5.58 (findings-choppiness-vc70-vc74.md 13.5 item 1): each Space frame's own display present time, read once a frame
 * from EGL_ANDROID_get_frame_timestamps (native, `libflipspaces_frametimes.so`), for the glasses frame clock
 * ([GlassesFrameClock.onShown]). vc78 learnt a step in the commits' depth from the 2 s `--latency` tables only, two in a
 * row: 4 to 10 s of frames a refresh off their pose each time. The tables stay for the 5 s counts and as the fallback
 * when the extension or the display present time is missing.
 *
 * App only: Android's own EGL layer keeps these times for our surface; nothing is set on the phone or sent to the glasses.
 * GL thread only.
 */
class FrameTimestamps(private val tag: String) {
    private class Pending(val frameId: Long, val plan: GlassesFrameClock.Plan?)

    private val pending = ArrayDeque<Pending>()
    private var surface: Long = 0L
    private var enabled = false

    /** Why each frame's present is not read, for the log; null while it is. */
    var off: String? = "not started"
        private set

    /** Each frame's present came back, since the surface was set up. */
    var read = 0L
        private set
    var neverShown = 0L
        private set
    var lost = 0L
        private set

    /** Before the swap: the frame about to be swapped, drawn from [plan] (null off the clock). */
    fun beforeSwap(plan: GlassesFrameClock.Plan?) {
        if (!LOADED) return
        val s = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)?.nativeHandle ?: 0L
        if (s == 0L) return
        if (s != surface) {
            surface = s
            pending.clear()
            val r = runCatching { nativeEnable() }.getOrDefault(FAILED)
            enabled = r == 1L
            off = if (enabled) null else reason(r)
            Log.i(tag, if (enabled) "each frame's present: read from EGL_ANDROID_get_frame_timestamps for the glasses frame clock's depth (0.5.58)"
            else "each frame's present: not read (${off}); the glasses frame clock learns its depth from the 2 s tables as in 0.5.57")
        }
        if (!enabled) return
        val id = nativeNextFrameId()
        if (id < 0L) return
        pending.addLast(Pending(id, plan))
        while (pending.size > MAX_PENDING) {
            pending.removeFirst()
            lost++
        }
    }

    /** Once a frame: every frame whose present is known now goes to [clock], oldest first; stops at the first still pending. */
    fun poll(clock: GlassesFrameClock) {
        if (!enabled) return
        while (true) {
            val p = pending.firstOrNull() ?: return
            val t = nativePresentTime(p.frameId)
            when {
                t == PENDING -> return
                t == INVALID -> { neverShown++; pending.removeFirst() }
                t > 0L -> { read++; clock.onShown(p.plan, t); pending.removeFirst() }
                else -> { lost++; pending.removeFirst() }
            }
        }
    }

    /** As [poll], for a game's clock: each shown time to [shown], oldest first. */
    fun pollShown(shown: (Long) -> Unit) {
        if (!enabled) return
        while (true) {
            val p = pending.firstOrNull() ?: return
            val t = nativePresentTime(p.frameId)
            when {
                t == PENDING -> return
                t == INVALID -> { neverShown++; pending.removeFirst() }
                t > 0L -> { read++; shown(t); pending.removeFirst() }
                else -> { lost++; pending.removeFirst() }
            }
        }
    }

    fun line(): String = off?.let { "each frame's present not read ($it)" }
        ?: "each frame's present: %d read, %d never shown, %d lost".format(java.util.Locale.ROOT, read, neverShown, lost)

    private fun reason(r: Long): String = when (r) {
        NO_SURFACE -> "no EGL surface"
        NO_EXTENSION -> "EGL_ANDROID_get_frame_timestamps missing"
        NO_PRESENT_TIME -> "no display present time on this surface"
        ATTRIB_REFUSED -> "EGL_TIMESTAMPS_ANDROID refused"
        NOT_LOADED -> "native library not loaded"
        else -> "error $r"
    }

    private companion object {
        /** A present is known 2 or 3 frames after its swap; 12 frames is a fifth of a second at 60 Hz. */
        const val MAX_PENDING = 12
        const val PENDING = -2L
        const val INVALID = -1L
        const val NO_SURFACE = -10L
        const val NO_EXTENSION = -11L
        const val NO_PRESENT_TIME = -12L
        const val ATTRIB_REFUSED = -13L
        const val FAILED = -14L
        const val NOT_LOADED = -15L

        val LOADED: Boolean = runCatching { System.loadLibrary("flipspaces_frametimes") }
            .onFailure { Log.w("FlipSpaces/Space", "each frame's present: libflipspaces_frametimes not loaded: ${it.message}") }.isSuccess

        @JvmStatic external fun nativeEnable(): Long
        @JvmStatic external fun nativeNextFrameId(): Long
        @JvmStatic external fun nativePresentTime(id: Long): Long
    }
}
