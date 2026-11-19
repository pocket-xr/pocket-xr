package com.hayeco.flipspaces.pcscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.view.Surface
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.SpacePanel

/**
 * PC screens, the Space's side: hands each PC screen panel's decoded-picture surface ([com.hayeco.flipspaces.spatial]
 * PanelGl.stream) to that PC screen's process ([PcScreenSurfaceService], one per slot: [PcScreenSlots]), so its
 * decoder draws straight into the Space's reader, with no composite of the window's display in between. Any thread.
 */
object PcScreenLink {
    private const val TAG = "PcScreen"
    const val STREAM_W = 1920
    const val STREAM_H = 1080
    const val MSG_SURFACE = 1
    const val KEY_SURFACE = "surface"

    fun isPcScreen(panel: SpacePanel): Boolean = slotOf(panel) >= 0

    fun slotOf(panel: SpacePanel): Int = PcScreenSlots.slotOf(panel.activityName)

    private val links = Array(PcScreenSlots.COUNT) { Slot(it) }

    fun offer(slot: Int, surface: Surface) = links[slot].offer(surface)

    fun withdraw(slot: Int, surface: Surface) = links[slot].withdraw(surface)

    /** One PC screen's binding to its process. */
    private class Slot(private val slot: Int) {
        @Volatile private var offered: Surface? = null
        @Volatile private var messenger: Messenger? = null
        private var bound = false

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                messenger = service?.let { Messenger(it) }
                Log.i(TAG, "link ${slot + 1}: the PC screen's process is bound")
                send(offered)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                messenger = null
                Log.i(TAG, "link ${slot + 1}: the PC screen's process went away")
            }
        }

        @Synchronized
        fun offer(surface: Surface) {
            offered = surface
            val ctx = FlipSpacesApp.app
            if (!bound) {
                bound = runCatching {
                    ctx.bindService(Intent(ctx, PcScreenSlots.service(slot)), connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                Log.i(TAG, "link ${slot + 1}: binding the PC screen's process: $bound")
            }
            send(surface)
        }

        @Synchronized
        fun withdraw(surface: Surface) {
            if (offered !== surface) return
            offered = null
            send(null)
            if (bound) {
                runCatching { FlipSpacesApp.app.unbindService(connection) }
                bound = false
                messenger = null
            }
        }

        private fun send(surface: Surface?) {
            val m = messenger ?: return
            val msg = Message.obtain(null, MSG_SURFACE).apply { data = Bundle().apply { putParcelable(KEY_SURFACE, surface) } }
            runCatching { m.send(msg) }.onFailure { Log.w(TAG, "link ${slot + 1}: send: $it") }
        }
    }
}
