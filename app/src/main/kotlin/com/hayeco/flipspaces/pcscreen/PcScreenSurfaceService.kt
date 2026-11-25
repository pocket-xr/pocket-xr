package com.hayeco.flipspaces.pcscreen

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Surface
import com.hayeco.flipspaces.Log

/**
 * PC screens, the PC screen's process: takes the Space's surface for the stream ([PcScreenLink]) and keeps it in
 * [PcScreenSurfaces] for [PcScreenActivity]'s decoder.
 */
open class PcScreenSurfaceService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg: Message ->
        if (msg.what == PcScreenLink.MSG_SURFACE) {
            @Suppress("DEPRECATION")
            val s = msg.data?.getParcelable<Surface>(PcScreenLink.KEY_SURFACE)
            Log.i("PcScreen", "link: the Space's surface ${if (s != null) "arrived (valid ${s.isValid})" else "was taken back"}")
            PcScreenSurfaces.set(s)
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        PcScreenSurfaces.set(null)
        return false
    }
}

/** The second to sixth PC screens' services, each in its own process ([PcScreenSlots]). */
class PcScreenSurfaceService2 : PcScreenSurfaceService()
class PcScreenSurfaceService3 : PcScreenSurfaceService()
class PcScreenSurfaceService4 : PcScreenSurfaceService()
class PcScreenSurfaceService5 : PcScreenSurfaceService()
class PcScreenSurfaceService6 : PcScreenSurfaceService()

/** The Space's surface for the stream in this process, and who to tell when it changes. Main thread. */
object PcScreenSurfaces {
    @Volatile var current: Surface? = null
        private set
    var listener: (() -> Unit)? = null

    fun set(s: Surface?) {
        if (current === s) return
        current = s
        listener?.invoke()
    }
}
