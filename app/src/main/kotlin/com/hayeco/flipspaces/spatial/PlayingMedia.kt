package com.hayeco.flipspaces.spatial

import android.content.Context
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.platform.AddonLink
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 0.5.56: the apps playing media now, for [com.hayeco.flipspaces.core.spatial.PanelRatePolicy]: a window playing video keeps
 * the full rate. Android's active media sessions, read once a second off the render thread through the notification access
 * of the Pocket XR add-on (1.1.0; Notifications in the Control Center); without that access the set is empty and only Fit video
 * windows count as video. Reads only, nothing is set.
 */
object PlayingMedia {
    private const val TAG = "FlipSpaces"
    private const val EVERY_MS = 1_000L

    /** Packages with a session playing or buffering. */
    @Volatile var packages: Set<String> = emptySet()
        private set

    /** Whether the sessions can be read (notification access), for the log. */
    @Volatile var readable: Boolean = false
        private set

    private var exec: ScheduledExecutorService? = null
    private var lastError: String? = null

    @Synchronized
    fun start(context: Context) {
        if (exec != null) return
        val app = context.applicationContext
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "playing-media").also { it.isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ read(app) }, 0L, EVERY_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        exec?.shutdownNow()
        exec = null
        packages = emptySet()
    }

    private fun read(context: Context) {
        val r = runCatching {
            // 1.1.0: only a listener's own app may read the sessions, so the add-on's listener reads them.
            val playing = AddonLink.call<Array<String>?>(null) { it.playingPackages() }
            if (playing == null) {
                readable = false
                packages = emptySet()
                return
            }
            playing.toHashSet()
        }
        r.onSuccess { readable = true; packages = it; lastError = null }
        r.onFailure {
            readable = false
            packages = emptySet()
            val why = "${it.javaClass.simpleName}: ${it.message}"
            if (why != lastError) {
                lastError = why
                Log.i(TAG, "playing media: the sessions cannot be read ($why); only Fit video windows count as video")
            }
        }
    }
}
