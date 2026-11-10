package com.hayeco.flipspaces.spatial

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.spatial.FrameStallGuard

/**
 * 0.5.3: the Space's own watchdog ([FrameStallGuard]), on a thread of its own at a normal
 * priority, ticking four times a second. It only reads a timestamp and calls back; the callbacks
 * do no GL and no blocking binder call, so it keeps working while the render thread is stuck.
 */
internal class SpaceWatchdog(
    private val lastFrameMs: () -> Long,
    private val onShed: (gapMs: Long) -> Unit,
    private val onRelease: (gapMs: Long) -> Unit,
    private val onRecover: (stalls: Int, backoffMs: Long) -> Unit,
) {
    private val tag = "FlipSpaces/Watchdog"
    private val guard = FrameStallGuard()
    private val thread = HandlerThread("space-watchdog").also { it.start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val last = lastFrameMs()
            val action = guard.tick(now, last, SpaceSession.active && !SpaceSession.gameOwnsGlasses)
            val gap = now - last
            when (action) {
                FrameStallGuard.Action.SHED -> {
                    Log.w(tag, "no Space frame for $gap ms: letting go of the panel images on the GPU (only the newest of each is kept) and pausing GPU hand tracking, so SurfaceFlinger never waits on Pocket XR")
                    runCatching { onShed(gap) }.onFailure { Log.w(tag, "shed: ${it.message}") }
                }
                FrameStallGuard.Action.RELEASE -> {
                    Log.w(tag, "still no Space frame after $gap ms: releasing the panel displays; they reopen once frames run again")
                    runCatching { onRelease(gap) }.onFailure { Log.w(tag, "release: ${it.message}") }
                }
                FrameStallGuard.Action.RECOVER -> {
                    Log.i(tag, "Space frames ran for ${guard.currentBackoffMs} ms again (stall ${guard.stalls}, worst gap ${guard.worstGapMs} ms): back to normal")
                    runCatching { onRecover(guard.stalls, guard.currentBackoffMs) }.onFailure { Log.w(tag, "recover: ${it.message}") }
                }
                FrameStallGuard.Action.NONE -> Unit
            }
            SpaceSession.steady = guard.steady(now)
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        SpaceSession.steady = false
        SpaceSession.stalled = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    companion object {
        private const val TICK_MS = 250L
    }
}
