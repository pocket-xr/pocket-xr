package com.hayeco.flipspaces

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import com.hayeco.flipspaces.Log
import android.view.Display
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.FoldHandover
import com.hayeco.flipspaces.core.spatial.FoldPosture
import com.hayeco.flipspaces.core.spatial.Handover
import com.hayeco.flipspaces.core.spatial.PanelDisplayFilter
import com.hayeco.flipspaces.core.spatial.PhoneScreenFilter
import com.hayeco.flipspaces.core.hands.PhoneHandling
import com.hayeco.flipspaces.core.spatial.PhoneScreen
import com.hayeco.flipspaces.core.spatial.PostureReading
import com.hayeco.flipspaces.core.spatial.TrackpadPlace
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceSession
import java.lang.ref.WeakReference

/**
 * 0.5.17 cover screen: puts the phone's trackpad on whichever Flip screen is in use while a Space
 * session is on. With the phone closed when the Space starts (the glasses plugged in folded) it
 * opens on the cover screen by itself; a fold or an unfold mid session moves it; the Space on the
 * glasses is never touched. The decisions are core's [FoldHandover]; this reads the phone's screens
 * and starts the trackpad on a display.
 *
 * Starting an activity from the background: the Space's own activity is a visible window of Flip
 * Spaces (on the glasses), which Android counts as in the foreground, so it starts the trackpad;
 * then the bound pointer service; then the shell through Shizuku (`am start --display`).
 * Main thread only.
 */
object CoverTrackpad {
    private const val TAG = "FlipSpaces/Cover"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val handover = FoldHandover()
    private var trackpad: WeakReference<TrackpadActivity>? = null
    private var trackpadInFront = false
    private var lastReading: PostureReading? = null

    private val stepCheck = Runnable { step() }

    private val spaceListener: (Boolean) -> Unit = { active ->
        main.post {
            if (active) {
                apply(handover.spaceStarted(reading(), place(), SystemClock.uptimeMillis()))
            } else {
                handover.spaceEnded()
                Log.i(TAG, "the Space ended; the trackpad is left where it is")
            }
        }
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed()
        override fun onDisplayRemoved(displayId: Int) = changed()
        override fun onDisplayChanged(displayId: Int) = changed()
    }

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, main)
        SpaceSession.activeListeners += spaceListener
        lastReading = reading()
        Log.i(TAG, "fold watcher on: ${line(lastReading)}")
    }

    /** The phone is folded now (the cover screen is the one in use). */
    fun folded(): Boolean = reading().posture == com.hayeco.flipspaces.core.spatial.Posture.FOLDED

    fun resumed(a: TrackpadActivity) {
        trackpad = WeakReference(a)
        trackpadInFront = true
        handover.trackpadOpened()
    }

    fun paused(a: TrackpadActivity) {
        if (trackpad?.get() === a) trackpadInFront = false
    }

    fun destroyed(a: TrackpadActivity) {
        if (trackpad?.get() === a) {
            trackpad = null
            trackpadInFront = false
        }
    }

    /** The user left the trackpad (Back, Return to Home Screen): unfolding does not bring it back. */
    fun leftByUser() = handover.trackpadLeftByUser()

    private fun changed() {
        val r = reading()
        if (r != lastReading) {
            Log.i(TAG, "phone screens: ${line(r)}")
            // 0.5.18: folding or opening the phone mutes hand presses for a second.
            if (lastReading != null && r.posture != lastReading?.posture) PhoneHandling.shared.postureChanged(SystemClock.uptimeMillis())
            lastReading = r
        }
        handover.observe(r, SystemClock.uptimeMillis())
        main.removeCallbacks(stepCheck)
        main.postDelayed(stepCheck, FoldHandover.SETTLE_MS + 30L)
    }

    private fun step() {
        val s = handover.step(place(), SystemClock.uptimeMillis()) ?: return
        apply(s)
    }

    private fun place(): TrackpadPlace? {
        val a = trackpad?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return null
        val id = a.display?.displayId ?: return null
        return TrackpadPlace(id, trackpadInFront)
    }

    private fun apply(step: Handover) {
        Log.i(TAG, step.why)
        when (step) {
            is Handover.Keep -> Unit
            is Handover.Relayout -> trackpad?.get()?.relayout()
            is Handover.Show -> {
                val old = trackpad?.get()?.takeIf { step.replaceOn != null && !it.isFinishing }
                if (old != null) {
                    old.finishForMove()
                    // The old task goes first, so the new one is a new task on the new display.
                    main.postDelayed({ launch(step.displayId) }, MOVE_DELAY_MS)
                } else {
                    launch(step.displayId)
                }
            }
        }
    }

    /** Starts the trackpad on [displayId], from the first context Android lets start it. */
    private fun launch(displayId: Int) {
        val app = appContext ?: return
        // 1.1.10: nothing is opened after Exit Pocket XR.
        if (AppExit.exited) return
        val intent = Intent(app, TrackpadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(TrackpadActivity.EXTRA_AUTO, true)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val starters = listOfNotNull(
            SpaceActivity.alive()?.let { "the Space" to it },
            trackpad?.get()?.takeIf { !it.isFinishing }?.let { "the trackpad" to it },
            GlassesAutoOpen.hostService()?.let { "the pointer service" to it },
        )
        for ((who, c) in starters) {
            val ok = runCatching { c.startActivity(intent, options) }
                .onFailure { Log.w(TAG, "trackpad start on display $displayId from $who refused: ${it.message}") }
                .isSuccess
            if (ok) {
                Log.i(TAG, "trackpad started on display $displayId from $who")
                return
            }
        }
        if (ShizukuBridge.status(app) == ShizukuStatus.READY) {
            val cmd = listOf("am", "start", "--display", "$displayId", "-f", "0x10000000", "-n", "${app.packageName}/${TrackpadActivity::class.java.name}", "--ez", TrackpadActivity.EXTRA_AUTO, "true")
            Thread {
                val r = ShizukuBridge.shell(cmd)
                main.post { Log.i(TAG, "trackpad start on display $displayId by the shell: ${r.exitCode}, ${r.output.trim()}") }
            }.start()
        } else {
            Log.w(TAG, "trackpad not started on display $displayId: nothing of Pocket XR may start it from the background (Space not up, pointer service off, Shizuku not ready)")
        }
    }

    /**
     * The phone's own screens: built in, not the glasses, private or Pocket XR panel displays.
     * 0.5.18: a built in presentation display is kept, as the Flip7's cover
     * (display 1, INTERNAL, FLAG_PRESENTATION) is one ([PhoneScreenFilter]).
     */
    fun reading(): PostureReading {
        val dm = appContext?.getSystemService(DisplayManager::class.java) ?: return FoldPosture.read(emptyList())
        val glasses = GlassesAutoOpen.glassesDisplayId()
        val screens = dm.displays.filter { d ->
            PhoneScreenFilter.keep(
                PhoneScreenFilter.Listed(
                    d.displayId, d.flags, displayType(d),
                    isGlasses = d.displayId == glasses,
                    isOwnPanel = PanelDisplayFilter.isOwnPanel(d.displayId, d.name, PanelHost.reportedDisplayIds),
                ),
                Display.DEFAULT_DISPLAY,
            )
        }.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            PhoneScreen(d.displayId, m.widthPixels, m.heightPixels, m.densityDpi, d.state == Display.STATE_ON || d.state == Display.STATE_VR || d.state == Display.STATE_ON_SUSPEND)
        }
        return FoldPosture.read(screens)
    }

    /** Android's hidden display type (1 internal, 2 external, 5 virtual), or null where it cannot be read. */
    private fun displayType(d: Display): Int? = runCatching { Display::class.java.getMethod("getType").invoke(d) as Int }.getOrNull()

    private fun line(r: PostureReading?): String =
        if (r == null) "not read" else "${r.posture.name.lowercase()}, in use display ${r.inUseDisplayId ?: "none"}, cover display ${r.coverDisplayId ?: "not found"}"

    private const val MOVE_DELAY_MS = 150L
}
