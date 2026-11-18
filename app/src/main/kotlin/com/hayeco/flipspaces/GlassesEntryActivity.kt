package com.hayeco.flipspaces

import android.app.Activity
import android.os.Bundle
import com.hayeco.flipspaces.Log
import android.view.Display
import com.hayeco.flipspaces.platform.DisplayMonitor

/**
 * The glasses' home (SECONDARY_HOME, 0.4.0). When Android or One UI starts the secondary home
 * on the glasses, this opens the Space full screen there, or in flat mode the DeX home with its
 * banner, then gets out of the way. It declares no HOME category, so the phone's own launcher
 * is never replaced. On the phone's display it does nothing.
 *
 * It is a plain trampoline with the standard launch mode: Android does not accept a secondary
 * home that is singleTask or singleInstance (RootWindowContainer checks this before starting it
 * on a display).
 */
class GlassesEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = display?.displayId ?: Display.DEFAULT_DISPLAY
        val target = DisplayMonitor(this).snapshot().firstOrNull { it.id == id }
        if (id == Display.DEFAULT_DISPLAY || target == null) {
            Log.i(TAG, "secondary home started on display $id: not the glasses, nothing to open")
        } else {
            SpaceRouting.openOnGlasses(this, target, "secondary home")?.let { Log.w(TAG, it) }
        }
        finish()
    }

    private companion object {
        const val TAG = "FlipSpaces/Mode"
    }
}
