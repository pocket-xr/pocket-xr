package com.hayeco.flipspaces

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hayeco.flipspaces.core.ContentMode
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.GlassesSurface
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.PanelDisplayFilter
import com.hayeco.flipspaces.core.spatial.PanelServiceReturn
import com.hayeco.flipspaces.core.spatial.PlugIn
import com.hayeco.flipspaces.core.spatial.PlugInBranch
import com.hayeco.flipspaces.core.spatial.PlugInCheck
import com.hayeco.flipspaces.core.spatial.PlugInFacts
import com.hayeco.flipspaces.core.spatial.ServiceReturn
import com.hayeco.flipspaces.core.spatial.ShellOutcome
import com.hayeco.flipspaces.core.spatial.SpaceRouter
import com.hayeco.flipspaces.core.spatial.SpaceStartPath
import com.hayeco.flipspaces.core.spatial.SpaceStartRoute
import com.hayeco.flipspaces.core.spatial.SpatialModeChooser
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.ModeLog
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.PlainDisplaySession
import com.hayeco.flipspaces.spatial.SpaceActivity
import rikka.shizuku.Shizuku
import java.lang.ref.WeakReference

/**
 * Opens the Space (or in flat mode the flat home) when the glasses are plugged in, whatever
 * Pocket XR screen is in front, and when none is (0.4.2). Before, only the phone's main screen
 * watched the displays, and only while it was in front.
 *
 * One display listener for the whole process, started by every phone screen, the pointer
 * accessibility service (bound by the system, so it lives while it is switched on and may start
 * activities) and the hand tracking foreground service. [PlugIn.opener] picks who opens: the
 * main screen itself (it keeps its basic surface fallback), another Pocket XR screen in front,
 * the bound pointer service, or the shell through Shizuku, which starts the glasses' entry
 * activity on their display. Each display and surface opens once ([claim]), shared with the
 * main screen, so nothing opens twice. Main thread only.
 *
 * 0.4.3: every branch of the check is one FlipSpaces/Mode line ([PlugInCheck]); the process
 * starts watching as soon as it starts ([FlipSpacesApp]), whatever started it; display events
 * for our own panel displays are ignored ([PanelDisplayFilter]), so Shizuku dying no longer
 * swaps the Space for the flat home; a Space already up is kept; the shell opens the Space
 * itself; an unplug closes the Space; and when the panel service is back the Space is
 * restored if Shizuku took it down ([PanelServiceReturn]).
 *
 * 0.4.4: [GlassesReadyService] keeps the process awake, so the glasses' display event reaches
 * this listener with the pointer service off and nothing in front (Android held it back from
 * an idle process), and the shell's start of the Space is judged by am's lines and by the
 * Space being up, not by any "Error" in the output.
 */
object GlassesAutoOpen {
    private const val TAG = "FlipSpaces/Mode"
    private const val SETTLE_MS = 300L
    private const val SPACE_UP_WAIT_MS = 2_000L
    private const val SPACE_UP_POLL_MS = 200L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var monitor: DisplayMonitor? = null
    private val opened = mutableSetOf<String>()
    private var front: WeakReference<Activity>? = null
    private var host: WeakReference<Context>? = null

    /** The last check's line, so a stream of display changes logs a branch once, not each time. */
    private var lastLine: String? = null

    /** The Space was up on the glasses when Shizuku stopped: restore it when the service is back. */
    private var spaceUpWhenShizukuLost = false

    private val check = Runnable { check() }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (ownPanel(displayId, "added")) return
            DisplayMonitor.noteHotplug(displayId)
            // 0.5.6: a plug-in starts the head tracker's backoff over (3 s, not up to 60 s).
            HeadTracker.kick("display $displayId added")
            // 0.5.14: the panel service is bound as soon as a display appears, so
            // the Space's start with launch bounds and its task hold do not wait 2 to 3 s for it.
            connectPanelServiceEarly("display $displayId added")
            schedule()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (ownPanel(displayId, "removed")) return
            schedule()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (ownPanel(displayId, null)) return
            schedule()
        }
    }

    /**
     * A panel display of ours, by the ids the panel service reported or the panel name: not the
     * glasses, so its events never run the check (0.4.3). [event] is logged; changes are not.
     */
    private fun ownPanel(displayId: Int, event: String?): Boolean {
        val name = appContext?.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)?.name
        val own = PanelDisplayFilter.isOwnPanel(displayId, name, PanelHost.reportedDisplayIds)
        if (own && event != null) Log.i(TAG, "display $displayId $event: a Pocket XR panel display; no plug-in check")
        return own
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            val glasses = glassesId()
            spaceUpWhenShizukuLost = glasses != null && SpaceActivity.runningOn(glasses) != null
            Log.i(TAG, "Shizuku stopped; " + if (spaceUpWhenShizukuLost) "the Space stays up on display $glasses with placeholders" else "no Space up on the glasses")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        main.post {
            Log.i(TAG, "Shizuku is back; checking the glasses again")
            appContext?.let { com.hayeco.flipspaces.spatial.PlainDisplaySession.restoreLeftOver(it) }
            serviceBack("Shizuku is back")
            schedule()
        }
    }

    /** Starts watching the displays, once per process. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        monitor = DisplayMonitor(app)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, main)
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
        }
        PanelHost.onReady += { serviceBack("the panel service is Ready") }
        // 0.5.17 cover screen: the fold watcher, which puts the trackpad on the screen in use.
        CoverTrackpad.start(app)
        Log.i(TAG, "watching for the glasses (started by ${context.javaClass.simpleName})")
        schedule()
    }

    /** A Pocket XR phone screen came to the front. */
    fun resumed(activity: Activity) {
        front = WeakReference(activity)
        // 1.1.0: finds an add-on installed since the process started.
        com.hayeco.flipspaces.platform.AddonLink.connect(activity)
        start(activity)
        schedule()
    }

    fun paused(activity: Activity) {
        if (front?.get() === activity) front = null
    }

    /** The pointer accessibility service is bound: it can open the glasses with nothing in front. */
    fun attachHost(service: Context) {
        host = WeakReference(service)
        start(service)
    }

    fun detachHost(service: Context) {
        if (host?.get() === service) host = null
    }

    /** True the first time for [key] since the glasses were last unplugged: the caller opens them. */
    fun claim(key: String): Boolean = opened.add(key)

    /** The glasses went away: the next plug-in opens them again, and starts hands again (0.5.0). */
    fun reset() {
        opened.clear()
        spaceUpWhenShizukuLost = false
        com.hayeco.flipspaces.hands.HandInputManager.pausedThisPlug = false
        com.hayeco.flipspaces.hands.UsbPrompt.reset()
    }

    private fun schedule() {
        main.removeCallbacks(check)
        main.postDelayed(check, SETTLE_MS)
    }

    /** 0.5.14: binds the panel service now if Shizuku can (main thread); a no-op while bound or binding. */
    private fun connectPanelServiceEarly(why: String) {
        val context = appContext ?: return
        if (ShizukuBridge.status(context) != ShizukuStatus.READY) return
        if (PanelHost.state.value == PanelHost.State.Ready) return
        Log.i(TAG, "binding the panel service early ($why), before the Space starts")
        PanelHost.connect(context)
    }

    private fun log(line: String) {
        if (line == lastLine) return
        lastLine = line
        Log.i(TAG, line)
    }

    /** 0.5.17 cover screen: the bound pointer service, which may start activities from the background. */
    fun hostService(): Context? = host?.get()

    /** 0.5.17 cover screen: the glasses' display, so the fold watcher never takes it for a phone screen. */
    fun glassesDisplayId(): Int? = glassesId()

    private fun glassesId(): Int? {
        val displays = monitor?.snapshot() ?: return null
        return (DisplaySelector.state(displays, SpacesState.choices.value.preferredDisplayId) as? GlassesState.Connected)?.display?.id
    }

    private fun check() {
        val context = appContext
        val displays = monitor?.snapshot()
        if (context == null || displays == null) {
            Log.w(TAG, "plug-in check before the watcher started; skipped")
            return
        }
        // 1.1.10: after Exit Pocket XR nothing opens on a plug-in until Pocket XR is opened again.
        if (AppExit.exited) {
            log("plug-in check: Pocket XR was exited; not opening anything until it is opened again")
            return
        }
        // 0.5.9: the plain display session switches DeX off and on; its display changes are its own.
        if (PlainDisplaySession.busy) {
            log("plug-in check: the plain display session is ${PlainDisplaySession.stage.name.lowercase().replace('_', ' ')}; not opening anything meanwhile")
            return
        }
        val connected = DisplaySelector.state(displays, SpacesState.choices.value.preferredDisplayId) as? GlassesState.Connected
        val display = connected?.display
        // 0.5.54: the glasses connected, came back or changed mode (or went): the frame clock switch acts on it.
        com.hayeco.flipspaces.spatial.GlassesPacesetter.displayCheck(context, display?.id)
        val inFront = front?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val shizuku = ShizukuBridge.status(context)
        val opener = PlugIn.opener(
            mainInFront = inFront is MainActivity,
            otherInFront = inFront != null,
            hostService = host?.get() != null,
            shizukuReady = shizuku == ShizukuStatus.READY,
        )
        val choice = SpatialModeChooser.choose(HeadTracker.status.value, shizuku)
        val surface = SpaceRouter.surfaceFor(choice)
        val alive = SpaceActivity.alive()
        val facts = PlugInFacts(
            glassesDisplayId = display?.id,
            spaceOnGlasses = display != null && SpaceActivity.runningOn(display.id) != null,
            // A Space opened on the glasses that is now elsewhere (Android moves it to the phone on unplug).
            spaceOffGlasses = alive != null && alive.openedOffPhone && (display == null || alive.display?.displayId != display.id),
            opener = opener,
            // Only asked with glasses there: the assessment needs their display.
            mirrorOnly = display != null && SpacesLauncher.assess(context, display).mode == ContentMode.MIRROR,
            surface = surface,
            alreadyOpened = display != null && PlugIn.key(display.id, surface) in opened,
        )
        val branch = PlugInCheck.decide(facts)
        log(PlugInCheck.line(branch, facts) + " (Shizuku ${shizuku.name}, opener ${opener.name})")
        when (branch) {
            PlugInBranch.NO_GLASSES -> {
                reset()
                PlainDisplaySession.unplugged(context)
            }
            PlugInBranch.UNPLUGGED_CLOSE_SPACE -> {
                reset()
                PlainDisplaySession.unplugged(context)
                alive?.closeForUnplug("the glasses display is gone")
            }
            PlugInBranch.SPACE_KEPT, PlugInBranch.MAIN_SCREEN, PlugInBranch.MIRROR_ONLY, PlugInBranch.ALREADY_OPENED -> Unit
            PlugInBranch.NOT_OPENED -> ModeLog.decided("plug-in, display ${display!!.id}", choice, "not opened: no Pocket XR screen in front, the pointer service is off and Shizuku is not ready")
            PlugInBranch.OPEN_FROM_FRONT_SCREEN, PlugInBranch.OPEN_FROM_POINTER_SERVICE, PlugInBranch.OPEN_WITH_SHELL -> {
                claim(PlugIn.key(display!!.id, surface))
                // 0.5.9, experimental: the glasses as a plain display without DeX for the Space's session.
                if (surface == GlassesSurface.SPACE && PlainDisplaySession.wanted(context)) {
                    PlainDisplaySession.begin(context, display.id) {
                        // It did not hold: DeX is back, open the Space in DeX as before.
                        opened.clear()
                        schedule()
                    }
                    return
                }
                open(branch, context, inFront, display, surface, choice)
            }
        }
    }

    private fun open(branch: PlugInBranch, context: Context, inFront: Activity?, display: DisplayCandidate, surface: GlassesSurface, choice: ModeChoice) {
        // The private test copy sits beside the real app. Both opening on one plug-in left neither on the glasses
        // (7 November 2026), so the test copy only opens when asked (`am start --display`).
        if (context.packageName.endsWith(".dev")) {
            Log.i(TAG, "plug-in, display ${display.id}: the test copy leaves the glasses to Pocket XR; open it by hand")
            return
        }
        when (branch) {
            PlugInBranch.OPEN_FROM_FRONT_SCREEN -> {
                val where = "plug-in from ${inFront!!.javaClass.simpleName}"
                SpaceRouting.openOnGlasses(inFront, display, where)?.let { SpacesState.post(it) }
            }
            // 0.5.14: the Space starts with launch bounds of the whole display
            // first (the pointer service's start, as on the 0.5.13 reconnect that came up 1920x1080
            // at once); the panel service's task hold then takes the DeX caption away. 0.5.0 to 0.5.13
            // used the shell's `am start` first, which DeX turned into a 960x1080 portrait window.
            PlugInBranch.OPEN_FROM_POINTER_SERVICE ->
                if (surface == GlassesSurface.SPACE) {
                    // 0.5.20: a successful start (null) is no longer read as "no host".
                    val failure = com.hayeco.flipspaces.core.LaunchFailure.through(host?.get()) { AppLauncher.openSpace(it, display.id) }
                    if (failure == null) {
                        ModeLog.decided("plug-in from the pointer service, display ${display.id}", choice, SpaceStartRoute.line(SpaceStartPath.APP_BOUNDS, display.id, null, "started"))
                    } else if (ShizukuBridge.status(context) == ShizukuStatus.READY) {
                        Log.i(TAG, SpaceStartRoute.line(SpaceStartPath.APP_BOUNDS, display.id, null, "refused (${failure.message}); the shell paths next"))
                        openWithShell(context, display, surface, choice)
                    } else {
                        ModeLog.decided("plug-in from the pointer service, display ${display.id}", choice, "Space full screen refused: ${failure.message}")
                    }
                } else {
                    openFrom(host?.get() ?: context, display, surface, choice)
                }
            else -> openWithShell(context, display, surface, choice)
        }
    }

    /**
     * The panel service or Shizuku is back (0.4.3). With the Space still up its recovery
     * reopens the panels; if the Space went away while Shizuku was down and the glasses are
     * still plugged in, it opens again, with its saved panels.
     */
    private fun serviceBack(why: String) {
        val context = appContext ?: return
        // Only after Shizuku took the Space's service down: a first connection restores nothing.
        if (!spaceUpWhenShizukuLost) return
        val glasses = glassesId()
        val surface = SpaceRouter.surfaceFor(SpatialModeChooser.choose(HeadTracker.status.value, ShizukuBridge.status(context)))
        val step = PanelServiceReturn.decide(glasses != null, glasses != null && SpaceActivity.runningOn(glasses) != null, spaceUpWhenShizukuLost, surface)
        when (step) {
            ServiceReturn.NO_GLASSES -> Log.i(TAG, "$why: no glasses plugged in; nothing to restore")
            ServiceReturn.SPACE_UP -> {
                spaceUpWhenShizukuLost = false
                Log.i(TAG, "$why: the Space is up on display $glasses; its panels reopen")
            }
            ServiceReturn.NOTHING_TO_RESTORE -> Log.i(TAG, "$why: the Space was not up when Shizuku stopped; nothing to restore")
            ServiceReturn.REOPEN_SPACE -> {
                spaceUpWhenShizukuLost = false
                Log.i(TAG, "$why: the Space went away while Shizuku was down; opening it again on display $glasses")
                // Opened again whatever was claimed while Shizuku was down.
                opened.removeAll { it.startsWith("$glasses/") }
                schedule()
            }
        }
    }

    /** From the bound pointer service, which Android lets start activities from the background. */
    private fun openFrom(service: Context, display: DisplayCandidate, surface: GlassesSurface, choice: ModeChoice) {
        val where = "plug-in from the pointer service, display ${display.id}"
        val failure = when (surface) {
            GlassesSurface.SPACE -> AppLauncher.openSpace(service, display.id)
            GlassesSurface.FLAT_HOME -> AppLauncher.openGlassesHome(service, display.id)
        }
        val outcome = if (surface == GlassesSurface.SPACE) "Space full screen" else "flat DeX home"
        ModeLog.decided(where, choice, failure?.let { "$outcome refused: ${it.message}" } ?: outcome)
    }

    /**
     * Through Shizuku: the shell may start activities with nothing in front. 0.4.3: it starts
     * the Space itself, full screen on the glasses' display (`am start --display`); in flat mode
     * the glasses' entry, which opens the flat home. 0.4.4: whether the Space started is what
     * am printed ([PlugInCheck.shellStarted]) and whether it is up on the glasses shortly after
     * ([PlugInCheck.shellOutcome]); only a failure with no Space up tries the entry, which
     * opens it in turn.
     */
    private fun openWithShell(context: Context, display: DisplayCandidate, surface: GlassesSurface, choice: ModeChoice, fallback: Context? = null) {
        val space = "${context.packageName}/${SpaceActivity::class.java.name}"
        val entry = "${context.packageName}/${GlassesEntryActivity::class.java.name}"
        val where = "plug-in in the background, display ${display.id}"
        // 0.5.2: never a second start of a Space already up there (it put the held task back in the DeX Desk).
        val running = if (surface == GlassesSurface.SPACE) SpaceActivity.runningOn(display.id) else null
        if (running != null) {
            ModeLog.decided(where, choice, "the Space is already up; brought to the front, not started again")
            running.bringForward("plug-in check")
            return
        }
        Thread {
            // 0.5.14: first through the panel service, as the shell with launch
            // bounds of the whole display; `am start` (no bounds) only if that is not possible.
            if (surface == GlassesSurface.SPACE) {
                main.post { connectPanelServiceEarly("Space start") }
                val bounds = SpaceStartRoute.launchBounds(display.widthPx, display.heightPx)
                val waitStart = android.os.SystemClock.uptimeMillis()
                if (PanelHost.awaitService(SpaceStartRoute.PANEL_WAIT_MS)) {
                    val waited = android.os.SystemClock.uptimeMillis() - waitStart
                    val problem = PanelHost.startSpace(display.id, space, bounds)
                    if (problem == null) {
                        val up = spaceUpSoon(display.id)
                        val outcome = SpaceStartRoute.line(
                            SpaceStartPath.PANEL_SERVICE_BOUNDS, display.id, bounds,
                            (if (up) "up" else "started, not up on the glasses after ${SPACE_UP_WAIT_MS} ms") + " (panel service ready after $waited ms)",
                        )
                        main.post { ModeLog.decided(where, choice, outcome) }
                        return@Thread
                    }
                    Log.w(TAG, SpaceStartRoute.line(SpaceStartPath.PANEL_SERVICE_BOUNDS, display.id, bounds, "refused ($problem); am start next"))
                } else {
                    Log.w(TAG, SpaceStartRoute.line(SpaceStartPath.PANEL_SERVICE_BOUNDS, display.id, bounds, "the panel service did not connect in ${SpaceStartRoute.PANEL_WAIT_MS} ms; am start next"))
                }
            }
            val first = ShizukuBridge.shell(PlugInCheck.shellCommand(display.id, surface, space, entry))
            val amStarted = PlugInCheck.shellStarted(first.exitCode, first.output)
            val am = "am: ${first.exitCode}, ${first.output.trim().ifEmpty { "no output" }}"
            if (surface == GlassesSurface.FLAT_HOME) {
                val outcome = if (amStarted) "glasses entry started by the shell (flat home)" else "shell start of the glasses entry failed ($am)"
                main.post { ModeLog.decided(where, choice, outcome) }
                return@Thread
            }
            val result = PlugInCheck.shellOutcome(amStarted, spaceUpSoon(display.id))
            var outcome = when (result) {
                ShellOutcome.STARTED -> "Space started full screen by the shell ($am)"
                ShellOutcome.STARTED_DESPITE_REPORT -> "Space started full screen by the shell (it is up; $am)"
                ShellOutcome.REPORTED_NOT_UP_YET -> "Space start sent by the shell; not up on the glasses after ${SPACE_UP_WAIT_MS} ms ($am)"
                ShellOutcome.FAILED -> "shell start of the Space failed ($am)"
            }
            if (result == ShellOutcome.FAILED && fallback != null) {
                main.post {
                    ModeLog.decided(where, choice, "$outcome; opening it from the pointer service instead")
                    openFrom(fallback, display, surface, choice)
                }
                return@Thread
            }
            if (result == ShellOutcome.FAILED) {
                val second = ShizukuBridge.shell(PlugInCheck.shellCommand(display.id, GlassesSurface.FLAT_HOME, space, entry))
                outcome += if (PlugInCheck.shellStarted(second.exitCode, second.output)) {
                    "; glasses entry started instead, it opens the Space"
                } else {
                    "; glasses entry failed too (${second.exitCode}): ${second.output.trim()}"
                }
            }
            main.post { ModeLog.decided(where, choice, outcome) }
        }.start()
    }

    /**
     * Off the main thread: waits up to [SPACE_UP_WAIT_MS] for the Space to be up on
     * [displayId], checking on the main thread every [SPACE_UP_POLL_MS].
     */
    private fun spaceUpSoon(displayId: Int): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + SPACE_UP_WAIT_MS
        while (true) {
            val up = java.util.concurrent.FutureTask { SpaceActivity.runningOn(displayId) != null }
            main.post(up)
            if (runCatching { up.get(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) return true
            if (android.os.SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(SPACE_UP_POLL_MS)
        }
    }
}
