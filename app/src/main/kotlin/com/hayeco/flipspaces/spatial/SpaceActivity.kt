package com.hayeco.flipspaces.spatial

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.HoldLoopGuard
import com.hayeco.flipspaces.core.SlipStep
import com.hayeco.flipspaces.core.SpaceStart
import com.hayeco.flipspaces.core.SpaceSurface
import com.hayeco.flipspaces.core.SpaceSurfacePlan
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.pointer.AccessibilityPrompt
import com.hayeco.flipspaces.pointer.PointerHub
import com.hayeco.flipspaces.core.spatial.DisplayFlags
import com.hayeco.flipspaces.core.spatial.OnScreen
import com.hayeco.flipspaces.core.spatial.OnScreenKind
import com.hayeco.flipspaces.core.spatial.ShowAction
import com.hayeco.flipspaces.core.spatial.DexChrome
import com.hayeco.flipspaces.core.spatial.DexFullScreenPlan
import com.hayeco.flipspaces.core.spatial.ShowFallbacks
import com.hayeco.flipspaces.core.spatial.StereoLayout
import com.hayeco.flipspaces.core.spatial.ShowPlan
import com.hayeco.flipspaces.core.spatial.ShowStage
import com.hayeco.flipspaces.core.spatial.VisibleCheck
import com.hayeco.flipspaces.core.spatial.LauncherTyping
import com.hayeco.flipspaces.core.spatial.PhoneBack
import com.hayeco.flipspaces.core.spatial.PhoneKeyRoute
import com.hayeco.flipspaces.core.spatial.SearchEdit
import com.hayeco.flipspaces.core.spatial.SpaceUnplug
import com.hayeco.flipspaces.hands.HandInputManager
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.DesktopWindow
import com.hayeco.flipspaces.platform.InstalledApps

/**
 * The Space: full screen on the glasses, an OpenGL ES 3 scene of app panels around the user.
 * Since 0.4.0 it opens by itself when the glasses are plugged in and Shizuku is ready, and is
 * the glasses' launcher: an Apps button below the horizon (or an open palm) opens a curved app
 * grid. While it is on screen, trackpad and hand input go to it instead of to DeX windows. A
 * long press of any key (the glasses button sends key events) recentres; while the launcher is
 * open, typed keys search it.
 *
 * Under Samsung DeX it is opened full screen (AppLauncher.openSpace) and, if DeX still gives it a
 * freeform window, maximized here with the DeX and system bars hidden; the renderer follows each
 * resize. On the phone's own display nothing changes.
 *
 * 0.4.1: keys from a hardware keyboard go to the panel the pointer last pressed (when they
 * still reach the Space rather than that panel's own display), and Android's thermal status is
 * passed to the renderer, which lowers panel frame rates from moderate on.
 *
 * 0.4.3: when the glasses are unplugged the Space closes (Android used to move it to the
 * phone's screen), and closing releases its panel displays.
 *
 * 0.4.4: it closes the moment its glasses display is removed ([SpaceUnplug]), hidden and with
 * no close animation, so it no longer shows on the phone for half a second first.
 *
 * 0.4.6: that was still a frame late, since Android moves the tasks first. The glasses' display
 * was set to destroy its tasks when removed, which Android 15 turned out to ignore. 0.4.7: the
 * panel service holds the Space's task in a root task of its own on the glasses, which Android
 * removes with the display, so the Space goes with it; the 0.4.4 close stays for when Shizuku
 * is not there.
 *
 * 0.5.7: a full screen hold must be seen on the glasses. SurfaceFlinger is asked whether it
 * composes the Space there ([confirmOnScreen]); with no yes in 3 s the Space goes back into its
 * DeX window (the state that shows after a relaunch), and if that is not seen either it is started
 * again once, as closing and opening Pocket XR does. The glasses are never left blank.
 */
class SpaceActivity : Activity() {
    /** The GL view: in the window, or (0.5.1) in [presentation]. */
    private lateinit var view: GLSurfaceView
    private lateinit var renderer: SpaceRenderer

    /** The display the Space opened on; when it is not the phone's and the Space lands there, the glasses went away. */
    private var openedOn = Display.INVALID_DISPLAY

    /** Opened on the glasses (any display but the phone's), so an unplug closes it. */
    val openedOffPhone: Boolean get() = SpaceUnplug.openedOffPhone(openedOn)

    /** Its own display's removal, seen before Android moves the Space to the phone (0.4.4). */
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayRemoved(displayId: Int) {
            // 0.5.24: the warp is told first, with the reason (unplugged or the DisplayPort dropped),
            // so its restore is not the background one that onStop would give.
            if (displayId == openedOn) GlassesWarp.displayRemoved(this@SpaceActivity, displayId)
            if (SpaceUnplug.closeOnRemoved(openedOn, displayId)) closeForUnplug("display $displayId removed")
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        // 0.5.10: a mode switch (the glasses' 3D mode) changes the refresh: ask for the new one.
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == openedOn && ::view.isInitialized) view.holder.surface?.takeIf { it.isValid }?.let { voteFrameRate(it, "display mode changed") }
            // 0.5.14: the modes offered may have changed too (the glasses' own 3D switch).
            if (displayId == openedOn) askForStereoMode()
        }
    }

    /** Keys typed into the open launcher's search (0.4.6). */
    private val typing = LauncherTyping()

    /**
     * 0.5.0: a Back key that reaches the Space itself (it has top focus after launcher typing,
     * a keyboard's Back, the glasses' button) is Back in the Space, as the phone's Back is
     * (0.4.8), once per press. Before, Activity's default finished the Space.
     */
    private val backKey = PhoneBack()

    internal fun backInSpace(how: String) {
        Log.i(MODE_TAG, "Back ($how) on the Space: sent as Back in the Space")
        SpaceSession.submit(PointerSignal.Back)
    }

    /** The DeX maximize is asked for once per window, so a window the user resized stays put. */
    private var maximizeRequested = false

    private val thermal = PowerManager.OnThermalStatusChangedListener {
        renderer.setThermalStatus(it)
        // 0.5.12: the fast refresh is given up from severe and asked again once cool.
        val hot = it >= com.hayeco.flipspaces.core.spatial.ThermalGuard.SEVERE
        if (hot != thermalHot) {
            thermalHot = hot
            askForStereoMode()
        }
    }

    /** 0.5.12: the phone is at severe or worse: no fast refresh is asked ([askForStereoMode]). */
    private var thermalHot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        opaqueWindow(window)
        renderer = SpaceRenderer(this)
        view = newGlView(this)
        setContentView(view)
        hideBars()
        openedOn = display?.displayId ?: Display.INVALID_DISPLAY
        // 0.5.9: started by the plain display session: its checks report to the session.
        sessionToken = PlainDisplaySession.claim(openedOn)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, Handler(Looper.getMainLooper()))
        reportDisplaySize()
        loadApps()
        // 0.5.60: the test card's window and display line, each time it shows; the intent's test_card extra.
        renderer.onTestCardShown = { main.post { logTestCardWindow() } }
        takeTestCardExtra(intent)
        takeEdgeFadeExtra(intent)
        // With predictive Back (Android 16, targetSdk 36) Back arrives here instead of as a key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { backInSpace("callback") }
        }
    }

    private fun newGlView(context: android.content.Context): GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        // 0.5.19 (the eye passes' p99 13.5 ms against 6.6, the GPU shared with
        // the hand model and SurfaceFlinger): a high priority GPU context where the driver offers one.
        setEGLContextFactory(SpaceContextFactory)
        preserveEGLContextOnPause = true
        // 0.5.18 (the Space and 4 layers composed on the GPU every frame,
        // the prepare pass waiting 13 ms on the window buffer): an opaque surface above its window, so
        // SurfaceFlinger drops the SurfaceView's "Background" colour layer and can put the Space on a
        // hardware plane. Nothing of the window is drawn over it: the GL view is all the window holds.
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        // 0.5.10: the Space's surface asks for the glasses' refresh rate, so SurfaceFlinger does not
        // compose it at a lower one (renderFrameRate 30 in Mike's 3840x1080 log) and drop every other frame.
        holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) = voteFrameRate(h.surface, "surface created")
            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = voteFrameRate(h.surface, "surface ${width}x$height")
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) = Unit
        })
        // A Bluetooth or USB mouse moves the Space's own pointer, captured while this window has focus (MouseRoute).
        com.hayeco.flipspaces.pointer.MouseRoute.attach(this)
    }

    private var lastVote: String? = null

    /** 0.5.12: the vsync source for the late draw, and the glasses' period and presentation deadline it falls back on. */
    private var vsync: VsyncSource? = null
    @Volatile private var glassesPeriodNs = 16_666_667L
    @Volatile private var presentationDeadlineNs = 0L

    /** 0.5.10: [surface] at the glasses' current refresh rate, every frame shown ([FrameRateVote]). */
    private fun voteFrameRate(surface: android.view.Surface, why: String) {
        val d = display ?: return
        val hz = com.hayeco.flipspaces.core.spatial.FrameRateVote.rateFor(d.mode.refreshRate)
        renderer.setRefreshRate(d.mode.refreshRate)
        glassesPeriodNs = (1e9 / hz).toLong()
        presentationDeadlineNs = d.presentationDeadlineNanos
        // 0.5.13: the glasses display's own vsync numbers, for vsyncs at its rate.
        renderer.setDisplayTiming((1e9 / d.mode.refreshRate).toLong(), d.appVsyncOffsetNanos, d.presentationDeadlineNanos, d.mode.physicalHeight, d.mode.physicalWidth)
        val result = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { surface.setFrameRate(hz, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS) }
                .fold({ "asked for %.0f Hz".format(hz) }, { "setFrameRate refused (${it.javaClass.simpleName}: ${it.message})" })
        } else {
            "setFrameRate needs Android 12"
        }
        val line = "Space frame rate on display ${d.displayId} ($why): mode ${d.mode.physicalWidth}x${d.mode.physicalHeight}@%.0f, $result".format(d.mode.refreshRate)
        if (line != lastVote) {
            lastVote = line
            Log.i(MODE_TAG, line)
        }
    }

    // ---- 0.5.1: the Presentation fallback ----

    /** The Presentation the Space is drawn in when its window stays freeform; null while in the window. */
    private var presentation: SpacePresentation? = null

    /** The task hold has answered, or the panel service was not there to try. */
    private var holdSettled = false

    /** 0.5.22: the task hold answered that it moved the Space into its full screen task, and whether that was looked at again. */
    private var holdMovedIt = false
    private var surfaceRechecked = false
    private var surfaceLogged = false
    private val main = Handler(Looper.getMainLooper())

    private val settleWithoutHold = Runnable {
        if (!holdSettled) {
            holdSettled = true
            checkSurface("no task hold answered in ${HOLD_SETTLE_MS / 1000} s")
        }
    }

    /**
     * Picks the Space's surface ([SpaceSurfacePlan]) once the hold has had its say: a window
     * still freeform (the desktop shell's caption and taskbar) moves the Space into a Presentation.
     */
    private fun checkSurface(why: String) {
        if (isFinishing || isDestroyed || presentation != null) return
        val freeform = DesktopWindow.isFreeform(this)
        val flags = display?.flags ?: 0
        val takes = DisplayFlags.takesPresentation(flags)
        if (freeform && holdSettled && !forcePresentation && openedOffPhone && !surfaceLogged) {
            if (fallbacks.windowOnly(openedOn)) {
                Log.i(MODE_TAG, "no Presentation on display $openedOn ($why): the Space stays in its DeX window, the one surface seen on the glasses")
            } else if (!takes) {
                Log.i(MODE_TAG, "no Presentation on display $openedOn ($why): flags ${DisplayFlags.describe(flags)}, not a public presentation display, so Android would refuse it; the Space stays in its window")
            }
        }
        // 0.5.23: SurfaceFlinger's "composed" and the hold's answer win over the stale freeform flag.
        val plan = SpaceSurfacePlan.choose(openedOn, freeform, holdSettled, forcePresentation, fallbacks.windowOnly(openedOn), takes, holdMovedIt, composed = fullScreenSeen, triedHere = openedOn in presentationTried)
        if (plan == SpaceSurface.WINDOW && freeform && holdSettled && openedOffPhone && !surfaceLogged && (fullScreenSeen || holdMovedIt || openedOn in presentationTried)) {
            surfaceLogged = true
            val because = when {
                fullScreenSeen -> "SurfaceFlinger already said the glasses compose it full screen"
                holdMovedIt -> "the task hold answered that it moved the Space into its full screen task"
                else -> "a Presentation was already tried on this display"
            }
            Log.i(MODE_TAG, "no Presentation on display $openedOn ($why): the window still reads freeform, but $because; it stays in its window")
            return
        }
        when (plan) {
            SpaceSurface.PRESENTATION -> enterPresentation(why, freeform)
            // 0.5.22: the hold moved the Space; its window's configuration is still catching up.
            SpaceSurface.RECHECK -> {
                surfaceRechecked = true
                Log.i(MODE_TAG, "the Space's window still reads freeform on display $openedOn ($why), but the task hold moved it; looking again in ${SURFACE_SETTLE_MS} ms before any Presentation")
                main.postDelayed({ checkSurface("$why, looked at again") }, SURFACE_SETTLE_MS)
            }
            SpaceSurface.WINDOW -> if (holdSettled && !surfaceLogged && openedOffPhone) {
                surfaceLogged = true
                Log.i(MODE_TAG, "the Space stays in its window on display $openedOn ($why): ${if (freeform) "freeform" else "full screen, no desktop caption"}")
                // 0.5.9: a DeX window with no full screen hold to try: DeX's own full screen.
                if (freeform && !fallbacks.holdAllowed(openedOn)) dexFullScreen("the Space stays in its DeX window")
            }
        }
    }

    private fun enterPresentation(why: String, freeform: Boolean) {
        // 0.5.19: the display looked up again now (DeX's display change may have
        // replaced the one this activity holds), and the Presentation opened with a black placeholder
        // before the GL view leaves the window. 0.5.18 took the view off first, so a Presentation Android
        // refused (InvalidDisplayException) still tore the GL surface down, and with it
        // every panel display, which were all made again 1 s later.
        val d = getSystemService(DisplayManager::class.java)?.getDisplay(display?.displayId ?: return) ?: run {
            Log.w(MODE_TAG, "no Presentation for the Space ($why): its display ${display?.displayId} is gone from DisplayManager; it stays in its window")
            surfaceLogged = true
            return
        }
        val p = SpacePresentation(this, d, View(this).apply { setBackgroundColor(Color.BLACK) })
        // 0.5.23: once a display, whatever comes of it.
        presentationTried += d.displayId
        val shown = runCatching { p.show() }
        if (shown.isFailure) {
            val e = shown.exceptionOrNull()
            Log.w(
                MODE_TAG,
                "the Space could not open a Presentation on display ${d.displayId} (${e?.javaClass?.simpleName}: ${e?.message}); " +
                    "display flags ${DisplayFlags.describe(d.flags)}, state ${DisplayFlags.stateName(d.state)}; it stays in its window, its GL surface and panels untouched",
            )
            runCatching { p.dismiss() }
            surfaceLogged = true
            return
        }
        // Off the window first, so its GL thread has ended before the new one starts.
        val fresh = newGlView(this)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        p.showSpace(fresh)
        view = fresh
        presentation = p
        surfaceLogged = true
        Log.i(
            MODE_TAG,
            "the Space is drawn in a Presentation on display ${d.displayId} ($why" +
                (if (freeform) "; its window stayed freeform under the desktop shell's caption" else "") +
                "): no desktop caption or taskbar over it",
        )
        reportDisplaySize()
    }

    /** The window an OpenXR game's sub-window attaches to: wherever the Space is drawn now. */
    fun gameHostWindow(): android.view.Window? = presentation?.window ?: window

    /** An OpenXR game owns the glasses: the Space stops drawing under its window, and picks up again after it. */
    fun pauseForGame(paused: Boolean) {
        // The Space keeps a frame a refresh while a game runs, one pixel, to give Monado the glasses' real refresh
        // ([SpaceRenderer.panelsForGame]); its own drawing stops.
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        // Nothing reads the vsyncs while the Space draws nothing: 3.5 % of a core on the glasses (7 November 2026).
        if (paused) vsync?.stop() else vsync?.start()
        // The GL thread still takes queued events while it draws nothing.
        view.queueEvent { renderer.panelsForGame(paused) }
    }

    /** The launcher's app list, read off the main thread and handed to the GL thread. */
    private fun loadApps() {
        val installed = InstalledApps(this)
        Thread {
            val found = runCatching { AppCatalog.prepare(installed.load(), packageName) }.getOrDefault(emptyList())
            // PC VR first, where the stream can be built in.
            val apps = if (com.hayeco.flipspaces.pcvr.AlvrBridge.available) listOf(com.hayeco.flipspaces.pcvr.PcVrSession.tile(packageName)) + found else found
            SpaceSession.send(SpaceCommand.LauncherApps(apps))
        }.start()
    }

    override fun onResume() {
        super.onResume()
        fillDisplay()
    }

    /**
     * The manifest keeps the Space alive across resizes (DeX maximize, a new display mode). The
     * GL surface reports its new size itself; the display size, bars and maximize are redone here.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (closeIfMovedToPhone()) return
        reportDisplaySize()
        // 0.5.1: turned freeform after the first onResume (the desktop shell's transition): ask
        // for full screen again, so the log says what the system answers.
        val freeform = DesktopWindow.isFreeform(this)
        if (freeform && !wasFreeform) maximizeRequested = false
        val step = SpaceStart.onWindowMode(wasFreeform, freeform, PanelHost.ready && openedOffPhone, presentation != null)
        wasFreeform = freeform
        fillDisplay()
        when (step) {
            // 0.5.2: the DeX Desk took the held task back (Mike's 0.5.1: a second start did): hold
            // it again before the Presentation is even considered, since only a full screen task
            // hides the DeX taskbar and the Desk's outline.
            SlipStep.HOLD_AGAIN -> {
                Log.i(MODE_TAG, "the Space's window turned freeform on display $openedOn (back in the DeX Desk): holding it in a full screen task again")
                holdSettled = false
                main.removeCallbacks(settleWithoutHold)
                main.postDelayed(settleWithoutHold, HOLD_SETTLE_MS)
                holdOnGlasses()
            }
            SlipStep.CHECK_SURFACE -> if (holdSettled) checkSurface("the window turned freeform")
            SlipStep.NONE -> Unit
        }
    }

    /**
     * 0.5.2: asked to open again while already up on its glasses display (the main screen, the
     * plug-in check, a launch from the phone): no second startActivity, which put the held task
     * back in the DeX Desk in Mike's 0.5.1 log; the panel service brings its task to the front.
     */
    fun bringForward(why: String) {
        Log.i(MODE_TAG, "the Space is already up on display $openedOn ($why): brought to the front, not started again")
        maximizeRequested = false
        if (PanelHost.ready && SpaceUnplug.holdOnOpen(openedOn)) {
            guardedHold("brought to the front") { reply ->
                main.post { if (!isFinishing) hideBars() }
                if (reply.startsWith("failed")) main.post { checkSurface("bringing it forward, the task hold failed") }
                main.post { afterHold(reply, "brought to the front") }
            }
        }
        if (forcePresentation) main.post { checkSurface("asked for by a debug command") }
    }

    private var wasFreeform = false

    /**
     * The glasses went away and Android moved the Space to the phone's screen: close it there
     * (0.4.3). A Space opened on the phone's own display stays.
     */
    private fun closeIfMovedToPhone(): Boolean {
        val now = display?.displayId ?: return false
        if (isFinishing || !SpaceUnplug.closeOnMove(openedOn, now)) return false
        closeForUnplug("moved from display $openedOn to the phone's screen")
        return true
    }

    /**
     * Closes the Space because its glasses display is gone; the panel displays are released in
     * onDestroy. 0.4.4: hidden first and with no close animation, so nothing shows on the phone
     * should Android move it there before it is gone.
     */
    fun closeForUnplug(why: String) {
        if (isFinishing || !openedOffPhone) return
        Log.i(MODE_TAG, "glasses unplugged ($why): closing the Space")
        view.visibility = View.INVISIBLE
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        window.setWindowAnimations(0)
        window.attributes = window.attributes.apply { alpha = 0f }
        // Android stops the Space only after it is gone; the trackpad's line follows at once.
        SpaceSession.active = false
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** "Open Space" tapped again while the Space is open: maximize it again on resume. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maximizeRequested = false
        takeTestCardExtra(intent)
        takeEdgeFadeExtra(intent)
        if (forcePresentation) main.post { checkSurface("asked for by a debug command") }
    }

    override fun onStart() {
        super.onStart()
        current = java.lang.ref.WeakReference(this)
        presentation?.let { runCatching { it.show() } }
        view.onResume()
        // 0.5.13: the display delay saved for these glasses (EDID name and product id).
        display?.takeIf { openedOffPhone }?.let { d ->
            val store = SpaceStore(this)
            store.useCalibrationFor(SpaceStore.glassesId(d))?.let { Log.i(MODE_TAG, it) }
            renderer.setGlasses(store.glasses)
            // 0.5.91: the model decides the optics, screen sizes and hands; the
            // projection is built again for it.
            Log.i(MODE_TAG, "glasses model: " + store.model.line())
            SpaceSession.send(SpaceCommand.SettingsChanged)
        }
        HeadTracker.acquire(this)
        // 0.5.21: the glasses' own warp, if Mike turned its switch on (off by default).
        GlassesWarp.spaceStarted(this, openedOn)
        // 0.5.26: while the glasses warp, what is composed over the record is read every second.
        main.removeCallbacks(watchRecordCover)
        main.postDelayed(watchRecordCover, RECORD_COVER_EVERY_MS)
        // 0.5.0: the Space is visible on the glasses, so Android lets the camera service start.
        HandInputManager.autoStart(this, "the Space")
        PanelHost.connect(this)
        if (PanelHost.ready) holdOnGlasses()
        PanelHost.onReady += holdOnGlasses
        main.postDelayed(settleWithoutHold, HOLD_SETTLE_MS)
        if (forcePresentation) main.post { checkSurface("asked for by a debug command") }
        logDisplay()
        askForStereoMode()
        // 0.5.12: vsyncs for the late draw.
        vsync?.stop()
        vsync = VsyncSource({ glassesPeriodNs }, { presentationDeadlineNs }).also {
            it.start()
            renderer.vsyncs = it.samples
        }
        // 0.5.9: the plain display session's Space is checked whatever the hold does.
        if (sessionToken != 0) main.postDelayed({ if (checkingStage == null && !isFinishing) confirmOnScreen(ShowStage.FULL_SCREEN_TASK, "plain display session") }, SESSION_CHECK_MS)
        SpaceSession.active = true
        // 0.5.54: a Space on the glasses with "Glasses set the frame clock (test)" on: the glasses are the pacesetter.
        if (openedOffPhone) GlassesPacesetter.spaceStarted(this, openedOn)
        getSystemService(PowerManager::class.java)?.let {
            renderer.setThermalStatus(it.currentThermalStatus)
            it.addThermalStatusListener(mainExecutor, thermal)
        }
        // 0.5.18: watched for 3 s for DeX's launcher coming over it.
        if (openedOffPhone && !coverRestart) coverWatch.started(SystemClock.uptimeMillis())
        coverRestart = false
    }

    /** 0.5.18: DeX's launcher over the Space right after it started ([com.hayeco.flipspaces.core.spatial.SpaceCoverWatch]). */
    private val coverWatch = com.hayeco.flipspaces.core.spatial.SpaceCoverWatch()

    /** 0.5.18: the next start is the Space coming back from a hold the watch asked for, not a new start. */
    private var coverRestart = false

    /** 0.5.19: a stop the watch saw before the panel service was ready, and when. */
    private var coverPending: String? = null
    private var coverPendingMs = 0L

    /**
     * 0.5.19: the panel service is ready: a stop remembered before it ([covered]) is judged now, at the
     * stop's own time, so the watch's 3 s are counted from the start as before. Main thread or binder:
     * posted to the main thread.
     */
    private val coverOnReady: () -> Unit = {
        main.post {
            PanelHost.onReady -= coverOnReady
            val how = coverPending ?: return@post
            coverPending = null
            if (isFinishing || isDestroyed || coverRestart) return@post
            val there = getSystemService(DisplayManager::class.java)?.getDisplay(openedOn) != null
            when (val s = coverWatch.lostTop(coverPendingMs, there, isFinishing)) {
                is com.hayeco.flipspaces.core.spatial.SpaceCoverWatch.Step.HoldAgain -> {
                    Log.i(MODE_TAG, "the Space $how on display $openedOn (${SystemClock.uptimeMillis() - coverPendingMs} ms before the panel service was ready): ${s.why}")
                    coverRestart = true
                    PanelHost.holdSpace(openedOn, true, "DeX came over the Space before the panel service was ready") { reply ->
                        main.post {
                            Log.i(MODE_TAG, "the Space's task held again after something came over it: $reply")
                            if (!isFinishing) afterHold(reply, "held again after DeX came over it")
                        }
                    }
                }
                is com.hayeco.flipspaces.core.spatial.SpaceCoverWatch.Step.Leave ->
                    Log.i(MODE_TAG, "the Space $how before the panel service was ready: the watch leaves it (${SystemClock.uptimeMillis() - coverPendingMs} ms ago)")
            }
        }
    }

    /**
     * The Space was stopped ([how]) with its display still there: held again if something covered it
     * just after it started. Only a stop counts: the Space also loses the top resumed state whenever
     * the phone's trackpad takes focus (seen in vc38), which hides nothing; DeX's launcher stopped
     * it (wm_on_stop_called 0.55 s after its start).
     */
    private fun covered(how: String) {
        // One hold at a time.
        if (!openedOffPhone || isFinishing || coverRestart) return
        // 0.5.19 (stopped 0.6 s after the start, the service ready 1.3 s later,
        // and the watch had returned at once): remembered, and held the moment the service is ready if
        // that is still within the watch's 3 s.
        if (!PanelHost.ready) {
            if (coverPending == null) {
                coverPending = how
                coverPendingMs = SystemClock.uptimeMillis()
                Log.i(MODE_TAG, "the Space $how on display $openedOn before the panel service was ready: it is held again once the service connects")
                PanelHost.onReady += coverOnReady
            }
            return
        }
        val there = getSystemService(DisplayManager::class.java)?.getDisplay(openedOn) != null
        when (val s = coverWatch.lostTop(SystemClock.uptimeMillis(), there, isFinishing)) {
            is com.hayeco.flipspaces.core.spatial.SpaceCoverWatch.Step.HoldAgain -> {
                Log.i(MODE_TAG, "the Space $how on display $openedOn: ${s.why}")
                coverRestart = true
                PanelHost.holdSpace(openedOn, true, "DeX came over the Space") { reply ->
                    main.post {
                        Log.i(MODE_TAG, "the Space's task held again after something came over it: $reply")
                        if (!isFinishing) afterHold(reply, "held again after DeX came over it")
                    }
                }
            }
            is com.hayeco.flipspaces.core.spatial.SpaceCoverWatch.Step.Leave -> Unit
        }
    }

    override fun onStop() {
        // 0.5.18: stopped by something coming over it (vc38: wm_stop_activity 0.5 s after the start).
        covered("was stopped")
        PanelHost.onReady -= holdOnGlasses
        main.removeCallbacks(settleWithoutHold)
        main.removeCallbacks(askOnScreen)
        checkingStage = null
        // Nothing of the Space covers the glasses while it is not in front.
        presentation?.let { runCatching { it.hide() } }
        runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermal) }
        SpaceSession.active = false
        SpaceSession.aim = null
        // 0.5.21: Pocket XR in the background: scene mode 0 back to the glasses if it was changed.
        // 0.5.24: stopped because its glasses display went (the stop can come before the
        // display listener): that restore is said and done as the display's, not as the background's.
        if (openedOffPhone && getSystemService(DisplayManager::class.java)?.getDisplay(openedOn) == null) GlassesWarp.displayRemoved(this, openedOn)
        GlassesWarp.spaceStopped()
        main.removeCallbacks(watchRecordCover)
        // 0.5.24: no frame follows a stop, so the records' end line is said here (vc44 said it only at the switch off).
        view.queueEvent { renderer.endRecordsAtExit("the Space stopped (${GlassesWarp.state})") }
        HeadTracker.release()
        renderer.vsyncs = null
        vsync?.stop()
        vsync = null
        view.onPause()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideBars()
            fillDisplay()
        }
    }

    /** Full screen on the glasses or DeX display: maximize a freeform window and hide the bars. */
    private fun fillDisplay() {
        DesktopWindow.fill(this, immersive = true, maximize = !maximizeRequested)
        maximizeRequested = true
        reportDisplaySize()
    }

    /** Hand positions arrive in display pixels; the renderer maps them onto its surface. */
    private fun reportDisplaySize() {
        val bounds = windowManager.maximumWindowMetrics.bounds
        renderer.setDisplaySize(bounds.width(), bounds.height())
        display?.let { renderer.setDisplayId(it.displayId) }
    }

    /**
     * 0.4.7: with the panel service connected, the Space's task is held in a root task of its
     * own on the glasses ([SpaceUnplug.holdOnOpen]), which Android removes with the display, so
     * on unplug it never moves the Space to the phone. Run again each time the service is Ready
     * (it may have restarted); a Space already held stays as it is.
     */
    private val holdOnGlasses: () -> Unit = {
        if (SpaceUnplug.holdOnOpen(openedOn)) {
            guardedHold("the Space is on the glasses") { reply ->
                // 0.5.1: give the shell's transition a moment, then see whether the window is full screen.
                main.postDelayed({
                    holdSettled = true
                    holdMovedIt = !reply.startsWith("failed")
                    // 0.5.2: in its own full screen task the window takes the bars' control again: hide them.
                    if (!isFinishing) hideBars()
                    checkSurface(if (reply.startsWith("failed")) "the task hold failed" else "after the task hold")
                }, SURFACE_SETTLE_MS)
                main.post { afterHold(reply, "after the task hold") }
            }
        }
    }

    private var holdRetryPosted = false

    /**
     * 0.5.3: task moves out of the DeX Desk, limited and spaced ([HoldLoopGuard]). The guard is
     * the process's, not this activity's, so a Space started again by a loop still counts; it
     * starts over on another display (the glasses plugged in again). Main thread.
     */
    private fun guardedHold(why: String, done: (String) -> Unit) {
        if (holdGuardDisplay != openedOn) {
            holdGuard.reset()
            holdGuardDisplay = openedOn
        }
        // 0.5.7: a full screen task was not seen on this display: the Space stays in its DeX window.
        if (!fallbacks.holdAllowed(openedOn)) {
            if (!holdSkipLogged) {
                holdSkipLogged = true
                val reason = if (fallbacks.wasRestarted(openedOn)) "the Space was started again in its DeX window" else "its full screen task was not seen on the glasses"
                Log.i(MODE_TAG, "no full screen task hold on display $openedOn ($why): $reason; the Space stays in its DeX window")
                if (fallbacks.wasRestarted(openedOn)) main.post { confirmOnScreen(ShowStage.RESTARTED, "started again") }
            }
            done("skipped: the Space stays in its DeX window on display $openedOn")
            return
        }
        when (val d = holdGuard.request(SystemClock.uptimeMillis())) {
            HoldLoopGuard.Decision.Go -> {
                holdGaveUpLogged = false
                PanelHost.holdSpace(openedOn, true, why, done)
            }
            is HoldLoopGuard.Decision.Wait -> {
                if (holdRetryPosted) return
                holdRetryPosted = true
                Log.i(MODE_TAG, "task hold on display $openedOn ($why) held back ${d.inMs} ms: the last one was too recent")
                main.postDelayed({
                    holdRetryPosted = false
                    if (!isFinishing) guardedHold(why, done)
                }, d.inMs)
            }
            is HoldLoopGuard.Decision.GiveUp -> {
                if (holdGaveUpLogged) return
                holdGaveUpLogged = true
                Log.w(MODE_TAG, "task hold on display $openedOn ($why): ${d.holds} holds in ${holdGuard.windowMs / 1000} s and DeX keeps moving the Space back; no more task moves, the Presentation instead")
                main.post {
                    if (!isFinishing) {
                        // 0.5.23: the hold did not stay, so neither its answer nor an earlier composed check stands.
                        holdMovedIt = false
                        fullScreenSeen = false
                        checkSurface("the task hold kept slipping back into the DeX Desk")
                    }
                }
            }
        }
    }

    private var holdGaveUpLogged = false
    private var holdSkipLogged = false

    // ---- 0.5.7: the glasses must show the Space ----

    private val showCheck = VisibleCheck()
    private var checkingStage: ShowStage? = null
    private var lastCheckKind: OnScreenKind? = null
    private var fullScreenSeen = false

    /** A hold that put the Space in a new full screen task, or the first that found it there, is checked. */
    private fun afterHold(reply: String, why: String) {
        if (isFinishing || isDestroyed || !ShowPlan.heldFullScreen(reply)) return
        if (ShowPlan.newHold(reply)) {
            fullScreenSeen = false
        } else if (fullScreenSeen || checkingStage != null) {
            return
        }
        confirmOnScreen(ShowStage.FULL_SCREEN_TASK, why)
    }

    /** Asks SurfaceFlinger, through the panel service, whether the glasses compose the Space [stage]. */
    private fun confirmOnScreen(stage: ShowStage, why: String) {
        if (!openedOffPhone || isFinishing || isDestroyed) return
        main.removeCallbacks(askOnScreen)
        checkingStage = stage
        lastCheckKind = null
        // 0.5.20: in the first seconds of a Space (just after the
        // glasses' own 2D to 3D switch made a new display) a "not composed" says nothing for 6 s.
        val now = SystemClock.uptimeMillis()
        val settle = if (now - createdAtMs < VisibleCheck.NEW_DISPLAY_MS) VisibleCheck.SETTLE_MS else 0L
        showCheck.start(now, settle)
        Log.i(MODE_TAG, "checking that the glasses show the Space ${stage.label} on display $openedOn ($why): asking SurfaceFlinger for up to ${showCheck.deadlineNowMs / 1000} s" + if (settle > 0) " (a new display: no answer counts against it for ${settle / 1000} s)" else "")
        main.postDelayed(askOnScreen, showCheck.firstMs)
    }

    /** 0.5.20: when this Space was made, for the new display's settle time. */
    private val createdAtMs = SystemClock.uptimeMillis()

    private val askOnScreen = Runnable {
        val stage = checkingStage ?: return@Runnable
        PanelHost.spaceOnScreen(openedOn) { reply ->
            if (OnScreen.kindOf(reply) == OnScreenKind.COMPOSED) {
                main.post { onScreenReply(stage, reply) }
                return@spaceOnScreen
            }
            // 0.5.20: SurfaceFlinger presents the Space's layer only
            // where it is shown, so its own --latency table, with frames presented in the last second,
            // is proof the glasses show it whatever the HWC tables say.
            PanelHost.presentTimes { text ->
                val now = System.nanoTime()
                val table = com.hayeco.flipspaces.core.spatial.SfLatency.parse(text)
                val n = com.hayeco.flipspaces.core.spatial.PresentedProof.presentedFrames(table, now)
                val combined = if (com.hayeco.flipspaces.core.spatial.PresentedProof.shows(table, now)) {
                    "${OnScreen.PREFIX_COMPOSED}the Space's layer presented $n frames in the last second (SurfaceFlinger --latency); the tables said: ${reply.take(200)}"
                } else {
                    reply
                }
                main.post { onScreenReply(stage, combined) }
            }
        }
    }

    private fun onScreenReply(stage: ShowStage, reply: String) {
        if (checkingStage != stage || isFinishing || isDestroyed) return
        val kind = OnScreen.kindOf(reply)
        when (val step = showCheck.result(SystemClock.uptimeMillis(), kind)) {
            VisibleCheck.Step.Confirmed -> {
                checkingStage = null
                if (stage == ShowStage.FULL_SCREEN_TASK) fullScreenSeen = true
                Log.i(MODE_TAG, "the glasses show the Space ${stage.label} on display $openedOn: $reply")
                // 0.5.24 (`DeX chrome composed: title bar, border` at the hold on 90 and 92, where the
                // record's top rows are): read again 1 s later, and said with the warp when it turns on.
                val chrome = DexChrome.fromReply(reply)
                GlassesWarp.dexChrome = chrome?.line()
                // 0.5.25: the switch is refused while DeX's chrome covers the record's rows.
                GlassesWarp.chromeOverRecord = chrome?.any == true
                GlassesWarp.recheckChrome = { main.post(recheckChrome) }
                if (stage == ShowStage.FULL_SCREEN_TASK && chrome?.any == true) main.postDelayed(recheckChrome, CHROME_RECHECK_MS)
                if (sessionToken != 0) PlainDisplaySession.seen(sessionToken, reply.substringBefore(';'))
                // 0.5.37 (Mike: after switching the glasses to Full SBS, completely black until Home is pressed; vc57: the
                // Space drew, but its window's own renderer started only when Home gave display 161 focus): focus the
                // Space's display and draw its window now, as Home did.
                PanelHost.focus(openedOn, "the Space is shown on the glasses: its window drawn at once")
                window.decorView.invalidate()
                window.decorView.requestLayout()
                // 0.5.9: in its DeX window: DeX's own full screen takes the taskbar and title bar away.
                if (stage != ShowStage.FULL_SCREEN_TASK) dexFullScreen("the Space shows ${stage.label}")
            }
            is VisibleCheck.Step.Again -> {
                if (kind != lastCheckKind) Log.i(MODE_TAG, "the Space ${stage.label} is not seen on display $openedOn yet: $reply")
                lastCheckKind = kind
                main.postDelayed(askOnScreen, step.inMs)
            }
            is VisibleCheck.Step.GiveUp -> {
                checkingStage = null
                notSeen(stage, "${step.asked} checks, last: $reply")
            }
            // 0.5.10: nothing could tell (no layer table, no window list): kept, never reloaded.
            is VisibleCheck.Step.Undecided -> {
                checkingStage = null
                if (stage == ShowStage.FULL_SCREEN_TASK) fullScreenSeen = true
                Log.i(MODE_TAG, "no reload: the Space ${stage.label} on display $openedOn is kept; ${step.asked} checks could not tell whether the glasses show it and nothing says they do not (last: ${reply.take(240)})")
                if (stage != ShowStage.FULL_SCREEN_TASK) dexFullScreen("the Space shows ${stage.label} (not confirmed, not refuted)")
            }
        }
    }

    /** 0.5.24: the composition read again after the hold, for whether DeX's chrome stayed. */
    private val recheckChrome = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        val id = openedOn
        PanelHost.spaceOnScreen(id) { reply ->
            val chrome = DexChrome.fromReply(reply)
            GlassesWarp.dexChrome = chrome?.line()
            GlassesWarp.chromeOverRecord = chrome?.any == true
            Log.i(MODE_TAG, "the Space's full screen task on display $id, ${CHROME_RECHECK_MS / 1000} s after: DeX chrome composed: " +
                (chrome?.let { if (it.any) "${it.line()}, still (${it.coveredRows} of 1080 rows under DeX; the warp's record is in the top two)" else "none now" } ?: "could not tell (${reply.take(120)})"))
        }
    }

    /**
     * 0.5.26 ("in SBS as in 2D, read SurfaceFlinger's layers on the glasses' display every
     * second while warping, and log any layer over the top two rows ... pause it while one is there"): one read a
     * second while the glasses warp. DeX's chrome or label indicator over the record pauses the warp at once (scene mode
     * 0, said on the glasses); the composed layers are logged whenever they change, so a drop can be matched to them.
     */
    private val watchRecordCover: Runnable = object : Runnable {
        private var lastLine: String? = null
        private var checks = 0
        private var lastCheckMs = 0L
        override fun run() {
            if (isFinishing || isDestroyed) return
            main.postDelayed(this, RECORD_COVER_EVERY_MS)
            if (!GlassesWarp.drawRecords) {
                lastLine = null
                checks = 0
                return
            }
            // 0.5.32 (from the drops in the logs: a full SurfaceFlinger dump each second can stall the compositor, and the
            // glasses drop the warp at one bad frame): the first two seconds of each warp, then every 10 s.
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (checks >= 2 && nowMs - lastCheckMs < RECORD_COVER_LATER_MS) return
            checks++
            lastCheckMs = nowMs
            Log.i(GlassesWarp.TAG, "while the glasses warp: composition check $checks starts (SurfaceFlinger read)")
            val id = openedOn
            PanelHost.spaceOnScreen(id) { reply ->
                val chrome = DexChrome.fromReply(reply)
                main.post {
                    GlassesWarp.dexChrome = chrome?.line()
                    GlassesWarp.chromeOverRecord = chrome?.any == true
                    val line = "while the glasses warp: composed on display $id: ${reply.take(400)}"
                    if (line != lastLine) {
                        lastLine = line
                        Log.i(GlassesWarp.TAG, line)
                    }
                    if (chrome?.any == true) GlassesWarp.recordCovered(this@SpaceActivity, chrome.line())
                }
            }
        }
    }

    /** [stage] was not confirmed: the next surface ([ShowPlan]). */
    private fun notSeen(stage: ShowStage, detail: String) {
        val id = openedOn
        val said = "the Space ${stage.label} was not seen on display $id within ${showCheck.deadlineNowMs / 1000} s ($detail)"
        // 0.5.9: on the plain display session's display: DeX comes back and opens the Space as before.
        if (sessionToken != 0) {
            Log.w(MODE_TAG, "$said: ending the plain display session")
            PlainDisplaySession.notSeen(this, sessionToken, detail)
            finish()
            return
        }
        when (ShowPlan.after(stage, seen = false, restartAllowed = fallbacks.restartAllowed(id))) {
            ShowAction.BACK_TO_WINDOW -> {
                fallbacks.fellBack(id)
                Log.w(MODE_TAG, "$said: back to the DeX window that shows, no more full screen task holds on this display")
                PanelHost.windowSpace(id, "its full screen task was not seen") { r ->
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        hideBars()
                        if (r.startsWith("failed")) {
                            notSeen(ShowStage.FREEFORM_WINDOW, "the move back failed: $r")
                        } else {
                            confirmOnScreen(ShowStage.FREEFORM_WINDOW, "moved back from the full screen task")
                        }
                    }
                }
            }
            ShowAction.RESTART -> {
                fallbacks.restarted(id)
                Log.w(MODE_TAG, "$said: closing the Space and starting it again in its DeX window, as closing and opening Pocket XR does")
                restartOn = id
                finish()
            }
            ShowAction.STAY -> Log.w(MODE_TAG, "$said: nothing more to try; send this log")
            ShowAction.KEEP -> Unit
        }
    }

    // ---- 0.5.9: DeX's own full screen, side by side 3D, the plain display session ----

    /** The plain display session that started this Space ([PlainDisplaySession.claim]); 0 for none. */
    private var sessionToken = 0

    /** 0.5.20: the skipped DeX Full screen press said once. */
    private var dexPressSkipLogged = false

    /** Presses DeX's full screen button on the Space's window ([DexFullScreenPlan]) and checks it took. */
    private fun dexFullScreen(why: String) {
        if (!openedOffPhone || isFinishing || isDestroyed || sessionToken != 0) return
        // 0.5.20: DeX's Full screen button never took (vc39, and
        // vc40's two presses): it is no longer pressed. The task hold is the way out of
        // DeX's caption; said once.
        if (!PRESS_DEX_FULL_SCREEN) {
            if (!dexPressSkipLogged) {
                dexPressSkipLogged = true
                Log.i(MODE_TAG, "DeX's Full screen button is not pressed for the Space on display $openedOn ($why): it never took on Mike's Flip7; the full screen task hold is used")
            }
            return
        }
        val enabled = SpaceStore(this).dexFullScreen && DesktopWindow.isFreeform(this)
        when (dexPlan.start(openedOn, enabled)) {
            DexFullScreenPlan.Step.PRESS -> pressDexFullScreen(why)
            DexFullScreenPlan.Step.GIVE_UP -> Unit
            else -> Unit
        }
    }

    private fun pressDexFullScreen(why: String) {
        val id = openedOn
        PointerHub.pressDexFullScreen(id) { result ->
            Log.i(MODE_TAG, "DeX full screen for the Space on display $id ($why), press ${dexPlan.tried(id)} of ${dexPlan.maxTries}: $result")
            // 0.5.10: nothing else may press DeX's button; say so once on the phone, with the way to turn it on.
            if (result.contains(PointerHub.SERVICE_OFF)) AccessibilityPrompt.showOnce(this, "DeX's taskbar and title bar stay on the glasses")
            main.removeCallbacks(checkDexFullScreen)
            main.postDelayed(checkDexFullScreen, dexPlan.checkAfterMs)
        }
    }

    private val checkDexFullScreen = Runnable {
        val id = openedOn
        PanelHost.spaceOnScreen(id) { reply ->
            main.post {
                if (isFinishing || isDestroyed) return@post
                // 0.5.10: SurfaceFlinger's or the window manager's chrome; with neither, the Space's own
                // window: no longer freeform and as big as its display means DeX took its chrome away.
                val chrome = DexChrome.fromReply(reply) ?: ownWindowChrome()
                val said = chrome?.let { "DeX chrome on the glasses: ${it.line()}" } ?: "nothing could tell what DeX draws (${reply.take(160)})"
                when (dexPlan.checked(id, chrome)) {
                    DexFullScreenPlan.Step.DONE ->
                        Log.i(MODE_TAG, "DeX full screen on display $id: no taskbar and no title bar on the glasses ($said); the Space fills the display")
                    DexFullScreenPlan.Step.PRESS -> {
                        Log.i(MODE_TAG, "DeX full screen on display $id did not take yet ($said): pressing again")
                        pressDexFullScreen("again")
                    }
                    else -> Log.w(
                        MODE_TAG,
                        "DeX full screen on display $id did not take after ${dexPlan.tried(id)} presses ($said); the Space stays in its DeX window" +
                            (chrome?.takeIf { it.coveredRows > 0 }?.let { ", ${it.coveredRows} of 1080 rows under DeX" } ?: ""),
                    )
                }
            }
        }
    }

    /** 0.5.10: no taskbar or title bar when the Space's own window is not freeform and fills its display; null when it cannot tell. */
    private fun ownWindowChrome(): DexChrome? {
        if (DesktopWindow.isFreeform(this)) return null
        val w = windowManager.currentWindowMetrics.bounds
        val d = windowManager.maximumWindowMetrics.bounds
        return if (w.width() >= d.width() && w.height() >= d.height()) DexChrome(taskbar = false, caption = false, outline = false) else null
    }

    /**
     * 0.5.9: with "3D side by side" on, asks for the glasses' 32:9 mode (3840 x 1080, their Full
     * SBS 3D) when they offer one; every mode the glasses offer is logged once per display, so the
     * log says whether the Aura offers it over USB-C at all.
     */
    private fun askForStereoMode() {
        val d = display ?: return
        if (!openedOffPhone) return
        val modes = d.supportedModes.toList()
        if (loggedModes != d.displayId) {
            loggedModes = d.displayId
            Log.i(
                MODE_TAG,
                "glasses display ${d.displayId} modes: ${modes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@%.0f".format(it.refreshRate) + if (it.modeId == d.mode.modeId) " (current)" else "" }}",
            )
            // 0.5.32: a 72 Hz try waits for a 3840x1080 at 72 mode among these.
            modes.forEach { GlassesDisplayControl.displayMode(it.physicalWidth, it.physicalHeight, it.refreshRate) }
        }
        val store = SpaceStore(this)
        val triples = modes.map { Triple(it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val attrs = window.attributes
        val currentIndex = modes.indexOfFirst { it.modeId == d.mode.modeId }
        // 0.5.14: side by side 3D at 3840x1080@60 unless 2D is chosen (1920x1080@120), only
        // from the modes Android offers; nothing is sent to the glasses. 0.5.12: the
        // fastest refresh at that size unless the phone runs hot.
        val plan = com.hayeco.flipspaces.core.spatial.ViewModeChoice.plan(store.view, triples, fastestAllowed = store.fastestRefresh && !thermalHot)
        renderer.setViewPlan(plan)
        // 0.5.65 (Mike's go): the view needs the glasses' own 2D / 3D state changed: ask them over the
        // control link (the command seen in our own captures of the glasses' USB traffic); Android then offers the
        // wanted mode and this picks it.
        if (plan.needsGlasses != null && store.autoGlassesView) {
            GlassesDisplayControl.requestStereo(this, plan.wanted == com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D)
        }
        val refreshLine = com.hayeco.flipspaces.core.spatial.RefreshChoice.line(triples, currentIndex, plan.modeIndex?.takeIf { modes[it].modeId != d.mode.modeId })
        if (refreshLine != loggedRefresh) {
            loggedRefresh = refreshLine
            Log.i(MODE_TAG, refreshLine)
            // 0.5.13: settled by the capture; the decision is logged, nothing asked.
            Log.i(MODE_TAG, com.hayeco.flipspaces.core.spatial.GlassesModeDecision.line(triples, currentIndex))
        }
        val wanted = plan.modeIndex?.let { modes[it].modeId } ?: 0
        if (plan.line != loggedView) {
            loggedView = plan.line
            Log.i(MODE_TAG, plan.line + if (wanted != 0) "; asking for mode id $wanted" else "; no display mode asked for")
        }
        if (attrs.preferredDisplayModeId != wanted) window.attributes = attrs.apply { preferredDisplayModeId = wanted }
    }

    private var loggedView: String? = null

    /** 0.5.14: the 3D / 2D switch (the launcher tile or the phone's setting): saved, then asked for. UI thread. */
    fun switchView(why: String) {
        val store = SpaceStore(this)
        val next = store.view.toggled()
        store.view = next
        Log.i(MODE_TAG, "view switched to ${next.label} ($why); remembered for the next plug-in")
        askForStereoMode()
    }

    /**
     * 1.2.3 (issue 58): Side by side 3D was turned on with the Space in 2D: the Space's view goes to 3D (as the launcher's
     * 3D / 2D button does, remembered) and the glasses are asked for 3D once more. UI thread.
     */
    fun want3d(why: String) {
        val store = SpaceStore(this)
        if (store.view != com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D) {
            store.view = com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D
            Log.i(MODE_TAG, "view switched to 3D side by side ($why); remembered for the next plug-in")
        }
        GlassesDisplayControl.userWantsStereo()
        askForStereoMode()
    }

    private var loggedRefresh: String? = null

    /** 0.5.7: the display to start the Space on again once this one is destroyed ([ShowAction.RESTART]). */
    private var restartOn = Display.INVALID_DISPLAY

    /** Once per glasses display: what Android says of it, for the log. */
    private fun logDisplay() {
        val d = display ?: return
        if (!openedOffPhone || loggedDisplay == d.displayId) return
        loggedDisplay = d.displayId
        Log.i(
            MODE_TAG,
            "the Space is on display ${d.displayId} (${d.name}): state ${DisplayFlags.stateName(d.state)}, flags ${DisplayFlags.describe(d.flags)}, " +
                "takes a Presentation: ${if (DisplayFlags.takesPresentation(d.flags)) "yes" else "no"}",
        )
    }

    /** 0.5.60: `--es test_card eyes|full|off` on the Space's intent switches the test card ([TestCardSwitch]). */
    private fun takeTestCardExtra(intent: Intent?) {
        val raw = intent?.getStringExtra(TestCardSwitch.EXTRA) ?: return
        val mode = com.hayeco.flipspaces.core.spatial.TestCardMode.parse(raw)
        if (mode == null) {
            Log.w(MODE_TAG, "test card: intent extra ${TestCardSwitch.EXTRA}=$raw names no mode (off, eyes or full)")
            return
        }
        Log.i(MODE_TAG, "test card: ${mode.arg} from the intent extra ${TestCardSwitch.EXTRA}")
        TestCardSwitch.mode = mode
    }

    /** 0.5.61: `--es edge_fade off|soft|wide` on the Space's intent sets the edge fade ([EdgeFadeSwitch]), saved as the Menu's. */
    private fun takeEdgeFadeExtra(intent: Intent?) {
        val raw = intent?.getStringExtra(EdgeFadeSwitch.EXTRA) ?: return
        val mode = com.hayeco.flipspaces.core.spatial.EdgeFadeMode.parse(raw)
        if (mode == null) {
            Log.w(MODE_TAG, "edge fade: intent extra ${EdgeFadeSwitch.EXTRA}=$raw names no mode (off, soft or wide)")
            return
        }
        Log.i(MODE_TAG, "edge fade: ${mode.arg} from the intent extra ${EdgeFadeSwitch.EXTRA}")
        EdgeFadeSwitch.set(SpaceStore(this), mode)
    }

    /**
     * 0.5.60 (Mike: the left eye cut on its outer edge with the Aura on the Flip): while the test card shows, where
     * Android has put the Space's window on the glasses' display, from WindowManager's WindowMetrics, the window's own
     * attributes and the GL view's place on the screen, then the display's mode, cutout and the window's insets.
     */
    private fun logTestCardWindow() {
        val p = presentation
        val win = p?.window ?: window
        val wm = win.windowManager
        val d = p?.display ?: display
        val metrics = runCatching { wm.currentWindowMetrics }.getOrNull()
        val max = runCatching { wm.maximumWindowMetrics }.getOrNull()
        val at = IntArray(2)
        runCatching { view.getLocationOnScreen(at) }
        val a = win.attributes
        val insets = metrics?.windowInsets ?: view.rootWindowInsets
        fun ins(type: Int): String = runCatching { insets?.getInsets(type)?.let { "${it.left},${it.top},${it.right},${it.bottom}" } }.getOrNull() ?: "none"
        Log.i(
            MODE_TAG,
            "test card window: ${if (p != null) "in a Presentation" else "in the activity's window"}; WindowMetrics bounds ${metrics?.bounds} (maximum ${max?.bounds}); " +
                "GL view at ${at[0]},${at[1]} on the display, ${view.width}x${view.height}; window attributes x ${a.x} y ${a.y} w ${a.width} h ${a.height} gravity 0x${Integer.toHexString(a.gravity)} " +
                "flags 0x${Integer.toHexString(a.flags)}, windowing mode ${runCatching { resources.configuration.toString().substringAfter("mWindowingMode=", "?").substringBefore(' ') }.getOrDefault("?")}, " +
                "in multi-window ${isInMultiWindowMode}; insets (left,top,right,bottom) system bars ${ins(WindowInsets.Type.systemBars())}, " +
                "display cutout ${ins(WindowInsets.Type.displayCutout())}, caption bar ${ins(WindowInsets.Type.captionBar())}, mandatory gestures ${ins(WindowInsets.Type.mandatorySystemGestures())}",
        )
        if (d == null) {
            Log.i(MODE_TAG, "test card display: none")
            return
        }
        val mode = d.mode
        val modes = d.supportedModes.joinToString { "${it.modeId}: ${it.physicalWidth}x${it.physicalHeight} %.2f Hz".format(java.util.Locale.ROOT, it.refreshRate) }
        @Suppress("DEPRECATION")
        val real = android.graphics.Point().also { d.getRealSize(it) }
        Log.i(
            MODE_TAG,
            "test card display: ${d.displayId} (${d.name}), mode ${mode.modeId} ${mode.physicalWidth}x${mode.physicalHeight} %.2f Hz (supported: $modes), real size ${real.x}x${real.y}, rotation ${d.rotation}, cutout ${d.cutout?.let { "safe insets ${it.safeInsetLeft},${it.safeInsetTop},${it.safeInsetRight},${it.safeInsetBottom} bounds ${it.boundingRects}" } ?: "none"}, flags ${DisplayFlags.describe(d.flags)}, state ${DisplayFlags.stateName(d.state)}".format(java.util.Locale.ROOT, mode.refreshRate),
        )
    }

    override fun onDestroy() {
        com.hayeco.flipspaces.pcvr.PcVrSession.close("the Space closed")
        PanelHost.onReady -= coverOnReady
        GlassesWarp.recheckChrome = null
        GlassesWarp.chromeOverRecord = false
        main.removeCallbacksAndMessages(null)
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays) }
        if (current?.get() === this) current = null
        // 0.5.21: the Space ends: scene mode 0 back to the glasses at once if it was changed.
        GlassesWarp.spaceEnded()
        // 0.5.32: the glasses' own duty back and the display control link closed (a 72 Hz try under way keeps it).
        GlassesDisplayControl.configure(this, spaceUp = false, turnDuty = false)
        // The mouse goes back to Android, a press it held let go of.
        com.hayeco.flipspaces.pointer.MouseRoute.release(view)
        renderer.shutdown()
        if (isFinishing && sessionToken != 0) PlainDisplaySession.spaceClosed(this, sessionToken)
        // 0.5.54: the Space session ends: a forced pacesetter goes back to SurfaceFlinger's own choice.
        if (isFinishing && openedOffPhone) GlassesPacesetter.spaceEnded(this, openedOn)
        if (isFinishing) {
            // Closed with the glasses still there: no empty root task is left on them.
            val stillThere = getSystemService(DisplayManager::class.java)?.getDisplay(openedOn) != null
            if (SpaceUnplug.releaseOnClose(openedOn, stillThere)) {
                PanelHost.holdSpace(openedOn, false, "the Space closed")
            } else if (SpaceUnplug.sweepPhoneOnClose(openedOn, stillThere)) {
                // 0.5.21: unplugged, the held task went to the phone's display; it is swept there.
                PanelHost.holdSpace(openedOn, false, "the Space closed after the glasses went")
            }
            PanelHost.disconnect()
            SpaceSession.publish(SpaceSnapshot(running = false))
        }
        if (restartOn != Display.INVALID_DISPLAY) {
            val id = restartOn
            val app = applicationContext
            restartHandler.postDelayed({ startAgain(app, id) }, RESTART_DELAY_MS)
        }
        super.onDestroy()
    }

    /**
     * A hardware keyboard's keys go to the focused panel's app while the launcher is closed.
     * The glasses' own button (not a full keyboard) and keys Android keeps (volume, power,
     * home) are left alone.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyboard = event.device?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true
        val state = SpaceSession.state.value
        val forward = keyboard && !state.launcherOpen && state.focusedPanelId != null && event.keyCode !in KEPT_KEYS &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        if (forward) {
            SpaceSession.send(SpaceCommand.PanelKey(event.action, event.keyCode, event.metaState, event.repeatCount, event.downTime))
            return true
        }
        return when (backKey.key(event.keyCode, event.action, event.repeatCount, event.downTime, event.isCanceled)) {
            PhoneKeyRoute.SPACE_BACK -> {
                backInSpace("key")
                true
            }
            PhoneKeyRoute.CONSUME -> true
            PhoneKeyRoute.PASS -> super.dispatchKeyEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem) return super.onKeyDown(keyCode, event)
        if (SpaceSession.state.value.launcherOpen) {
            // 0.5.25 (Mike: the Android XR launcher): arrows walk its focus, Enter opens, Esc closes.
            val nav = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> com.hayeco.flipspaces.core.spatial.LauncherKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> com.hayeco.flipspaces.core.spatial.LauncherKey.RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> com.hayeco.flipspaces.core.spatial.LauncherKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> com.hayeco.flipspaces.core.spatial.LauncherKey.DOWN
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> com.hayeco.flipspaces.core.spatial.LauncherKey.ENTER
                KeyEvent.KEYCODE_ESCAPE -> com.hayeco.flipspaces.core.spatial.LauncherKey.ESCAPE
                else -> null
            }
            if (nav != null) {
                SpaceSession.send(SpaceCommand.LauncherNav(nav))
                return true
            }
            // 0.4.6: each press types once, repeats and a press seen twice left out (LauncherTyping).
            val printable = keyCode == KeyEvent.KEYCODE_DEL || (event.unicodeChar != 0 && !Character.isISOControl(event.unicodeChar))
            when (val edit = typing.edit(keyCode, event.unicodeChar, event.repeatCount, event.downTime)) {
                is SearchEdit.Type -> SpaceSession.send(SpaceCommand.LauncherKey(edit.char))
                SearchEdit.Backspace -> SpaceSession.send(SpaceCommand.LauncherKey(null))
                null -> Unit
            }
            if (printable) return true
        }
        event.startTracking()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        typing.up(keyCode)
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        SpaceSession.send(SpaceCommand.Recentre)
        return true
    }

    /**
     * Immersive: hides the DeX taskbar if One UI allows it (docs/SPATIAL.md 2.5), and since 0.5.0
     * the caption bar too while the window is still freeform (the fallback when DeX keeps it so).
     */
    private fun hideBars() {
        val freeform = DesktopWindow.isFreeform(this)
        window.insetsController?.let {
            it.hide(if (freeform) WindowInsets.Type.systemBars() or WindowInsets.Type.captionBar() else WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (freeform && !freeformLogged) {
            freeformLogged = true
            Log.i(
                MODE_TAG,
                "the Space is in a freeform window on display ${display?.displayId}: the caption there is the desktop shell's, " +
                    "which the app cannot hide; the panel service moves the Space into a full screen task, else it is drawn in a Presentation",
            )
        }
    }

    private var freeformLogged = false

    companion object {
        private const val MODE_TAG = "FlipSpaces/Mode"

        /** 0.5.24: when DeX's chrome is read again after the hold. */
        private const val CHROME_RECHECK_MS = 1_000L

        /** 0.5.26: how often the composition is read while the glasses warp. */
        private const val RECORD_COVER_EVERY_MS = 1_000L

        /** 0.5.32: after a warp's first two checks, one every this long. */
        private const val RECORD_COVER_LATER_MS = 10_000L

        /**
         * 0.5.18: the Space's window opaque, black and with no wallpaper
         * shown through it, in the activity and in the Presentation, so SurfaceFlinger need not
         * compose what is under it (DeX's 3840x1080 wallpaper) or blend over it.
         */
        internal fun opaqueWindow(w: android.view.Window) {
            w.setFormat(android.graphics.PixelFormat.OPAQUE)
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }

        /** 0.5.3: see [guardedHold]. Main thread. */
        private val holdGuard = HoldLoopGuard()
        private var holdGuardDisplay = Display.INVALID_DISPLAY

        /** 0.5.7: per glasses display, a full screen task not seen there and a restart done ([ShowPlan]). Main thread. */
        private val fallbacks = ShowFallbacks()

        /** 0.5.9: DeX full screen presses per glasses display. Main thread. */
        private val dexPlan = DexFullScreenPlan()
        private var loggedModes = Display.INVALID_DISPLAY

        /** 0.5.9: how long after start the plain display session's Space is checked if no hold did it. */
        private const val SESSION_CHECK_MS = 1_500L
        private var loggedDisplay = Display.INVALID_DISPLAY
        private val restartHandler = Handler(Looper.getMainLooper())
        private const val RESTART_DELAY_MS = 500L

        /**
         * 0.5.7: starts the Space again on [displayId] after [ShowAction.RESTART], the way the phone
         * screen opens it (a DeX window), or through the shell if Android refuses the app's start.
         */
        private fun startAgain(app: android.content.Context, displayId: Int) {
            if (alive() != null) return
            if (app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) == null) {
                Log.i(MODE_TAG, "glasses display $displayId is gone; the Space is not started again")
                return
            }
            val failure = AppLauncher.openSpace(app, displayId)
            if (failure == null) {
                Log.i(MODE_TAG, "the Space was started again on display $displayId, with no full screen task hold")
                return
            }
            Log.w(MODE_TAG, "starting the Space again on display $displayId: Android refused the app's start (${failure.message}); through the shell")
            Thread {
                val r = com.hayeco.flipspaces.platform.ShizukuBridge.shell(
                    listOf("am", "start", "--user", "current", "--display", displayId.toString(), "-f", "0x10000000", "-n", "${app.packageName}/${SpaceActivity::class.java.name}"),
                )
                Log.i(MODE_TAG, "shell start of the Space on display $displayId: exit ${r.exitCode}, ${r.output.trim().ifEmpty { "no output" }}")
            }.start()
        }

        /** How long after the hold answers the window's mode is read (the shell's transition runs first). */
        private const val SURFACE_SETTLE_MS = 800L

        /** With no panel service, how long before the window is judged as it is. */
        private const val HOLD_SETTLE_MS = 4_000L

        /** Debug builds: draw the Space in a Presentation whatever its window is (DebugReceiver OPEN --ez presentation true). */
        @Volatile
        var forcePresentation = false

        /** 0.5.23 (twice on displays 82, 84 and 86): the displays a Presentation was tried on, this process. Main thread. */
        private val presentationTried = HashSet<Int>()

        /** Keys Android handles itself, never sent to a panel. */
        private val KEPT_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        )

        private var current: java.lang.ref.WeakReference<SpaceActivity>? = null

        /** The Space if it is alive on [displayId]: app launches there become panels. */
        fun runningOn(displayId: Int): SpaceActivity? =
            current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.display?.displayId == displayId }

        /** The Space if it is alive on any display (0.4.3: after an unplug, Android may have moved it to the phone). */
        fun alive(): SpaceActivity? = current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        /** The Space an OpenXR game can draw over: one that is open on the glasses, not on the phone. */
        fun forGame(): SpaceActivity? = alive()?.takeIf { (it.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY }
    }
}

/** 0.5.20: DeX's Full screen button is not pressed (it never took on the Flip7). */
private const val PRESS_DEX_FULL_SCREEN = false
