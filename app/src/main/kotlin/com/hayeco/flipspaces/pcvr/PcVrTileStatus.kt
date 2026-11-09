package com.hayeco.flipspaces.pcvr

import android.content.Context
import android.util.Log

/**
 * The PC VR tile's name says how the PC is, before anyone taps it: "PC VR: ready" when SteamVR runs, "PC VR: PC off" when
 * the PC helper found before doesn't answer, plain "PC VR" otherwise (a tap starts SteamVR). Asked every
 * [EVERY_MS] while the Space is open and PC VR isn't.
 */
class PcVrTileStatus(context: Context, private val onLabel: (String) -> Unit) {
    private val helper = PcVrHelper(context)
    private val prefs = context.applicationContext.getSharedPreferences("pcvr_helper", Context.MODE_PRIVATE)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var shown = TILE

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                if (!PcVrSession.isOpen) {
                    val label = runCatching { look() }.getOrDefault(TILE)
                    if (label != shown) {
                        shown = label
                        Log.i(TAG, "PC VR tile: \"$label\"")
                        onLabel(label)
                    }
                }
                try {
                    Thread.sleep(EVERY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "pcvr-tile-status").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun look(): String {
        val pc = helper.find() ?: return if (prefs.getString("address", null) != null) "$TILE: PC off" else TILE
        return when (helper.status(pc, start = false)) {
            "ready" -> "$TILE: ready"
            "starting" -> "$TILE: starting"
            else -> TILE
        }
    }

    companion object {
        private const val TAG = "PocketXrAlvr"
        const val TILE = "PC VR"
        private const val EVERY_MS = 10_000L
    }
}
