package com.hayeco.flipspaces.pcvr

import android.content.Context
import android.util.Log
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R

/**
 * The PC VR tile's name says how the PC is, before anyone taps it: "PC VR: ready" when SteamVR runs, "PC VR: PC off" when
 * the PC helper found before doesn't answer, plain "PC VR" otherwise (a tap starts SteamVR). Asked every
 * [EVERY_MS] while the Space is open and PC VR isn't.
 */
class PcVrTileStatus(context: Context, private val onLabel: (String) -> Unit) {
    enum class State { UNKNOWN, PC_OFF, STARTING, READY }
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
                    val now = runCatching { look() }.getOrDefault(State.UNKNOWN)
                    state = now
                    val label = labelFor(now)
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

    private fun look(): State {
        val pc = helper.find() ?: return if (prefs.getString("address", null) != null) State.PC_OFF else State.UNKNOWN
        return when (helper.status(pc, start = false)) {
            "ready" -> State.READY
            "starting" -> State.STARTING
            else -> State.UNKNOWN
        }
    }

    /** The tile's name for [s], in the user's language. */
    private fun labelFor(s: State): String = when (s) {
        State.READY -> FlipSpacesApp.text(R.string.sys_pcvr_tile_ready)
        State.STARTING -> FlipSpacesApp.text(R.string.sys_pcvr_tile_starting)
        State.PC_OFF -> FlipSpacesApp.text(R.string.sys_pcvr_tile_pc_off)
        State.UNKNOWN -> TILE
    }

    companion object {
        private const val TAG = "PocketXrAlvr"
        val TILE: String get() = FlipSpacesApp.text(R.string.sys_pcvr_name)

        /** How the PC is, for the launcher's PC VR button. */
        @Volatile var state = State.UNKNOWN
        private const val EVERY_MS = 10_000L
    }
}
