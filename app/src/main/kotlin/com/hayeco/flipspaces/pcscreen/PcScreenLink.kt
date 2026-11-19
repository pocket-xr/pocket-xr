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
 * PC screens, the Space's side: hands a PC screen panel's decoded-picture surface ([com.hayeco.flipspaces.spatial]
 * PanelGl.stream) to the PC screen's process ([PcScreenSurfaceService]), so its decoder draws straight into the
 * Space's reader, with no composite of the window's display in between. One PC screen for now: the newest surface
 * offered is the one sent. Any thread.
 */
object PcScreenLink {
    private const val TAG = "PcScreen"
    const val STREAM_W = 1920
    const val STREAM_H = 1080
    const val MSG_SURFACE = 1
    const val KEY_SURFACE = "surface"

    private val activity = PcScreenActivity::class.java.name

    fun isPcScreen(panel: SpacePanel): Boolean = panel.activityName == activity

    @Volatile private var offered: Surface? = null
    @Volatile private var messenger: Messenger? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = service?.let { Messenger(it) }
            Log.i(TAG, "link: the PC screen's process is bound")
            send(offered)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            messenger = null
            Log.i(TAG, "link: the PC screen's process went away")
        }
    }

    @Synchronized
    fun offer(surface: Surface) {
        offered = surface
        val ctx = FlipSpacesApp.app
        if (!bound) {
            bound = runCatching {
                ctx.bindService(Intent(ctx, PcScreenSurfaceService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            Log.i(TAG, "link: binding the PC screen's process: $bound")
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
        runCatching { m.send(msg) }.onFailure { Log.w(TAG, "link: send: $it") }
    }
}
