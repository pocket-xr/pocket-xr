package com.hayeco.flipspaces

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.ProviderStatus
import com.hayeco.flipspaces.core.pointer.CoverButton
import com.hayeco.flipspaces.core.pointer.CoverCurve
import com.hayeco.flipspaces.core.pointer.CoverLayout
import com.hayeco.flipspaces.core.pointer.CoverLayoutMath
import com.hayeco.flipspaces.core.pointer.CoverRepeat
import com.hayeco.flipspaces.core.pointer.CoverText
import com.hayeco.flipspaces.core.pointer.DpRect
import com.hayeco.flipspaces.core.pointer.GuardEvent
import com.hayeco.flipspaces.core.pointer.GuardTouch
import com.hayeco.flipspaces.core.pointer.HapticGate
import com.hayeco.flipspaces.core.pointer.HomeButton
import com.hayeco.flipspaces.core.pointer.HomeEvent
import com.hayeco.flipspaces.core.pointer.PadDirection
import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PadTouch
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.StripSide
import com.hayeco.flipspaces.core.pointer.TextOp
import com.hayeco.flipspaces.core.pointer.ThumbFeel
import com.hayeco.flipspaces.core.pointer.ThumbGuard
import com.hayeco.flipspaces.core.pointer.TrackpadInterpreter
import com.hayeco.flipspaces.core.pointer.TrackpadSettings
import com.hayeco.flipspaces.core.pointer.UnlockHold
import com.hayeco.flipspaces.core.pointer.WindowAction
import com.hayeco.flipspaces.core.spatial.ArrangeKind
import com.hayeco.flipspaces.core.spatial.FollowMode
import com.hayeco.flipspaces.core.spatial.FoldPosture
import com.hayeco.flipspaces.core.spatial.PanelFocus
import com.hayeco.flipspaces.core.spatial.PhoneAim
import com.hayeco.flipspaces.core.spatial.PhoneBack
import com.hayeco.flipspaces.core.spatial.PhoneKeyRoute
import com.hayeco.flipspaces.core.spatial.TrackpadLine
import com.hayeco.flipspaces.core.spatial.TrackpadStatus
import com.hayeco.flipspaces.hands.GlassesCameraProvider
import com.hayeco.flipspaces.hands.HandInputManager
import com.hayeco.flipspaces.pointer.MouseRoute
import com.hayeco.flipspaces.pointer.PointerHub
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceCommand
import com.hayeco.flipspaces.spatial.SpaceSession

/**
 * Provider (c): the Flip's main screen as a trackpad for the glasses cursor. Works with no hand
 * data at all.
 *
 * 0.5.17: rebuilt as a full phone trackpad: the Vertical Trackpad, Horizontal Trackpad and Spatial
 * Mouse pages with their selector and popup, the Back and Home bar, the drawer with Cursor Speed,
 * the scroll strip's side, the landscape direction and the usage instructions, the toast, the
 * dialog and the screen-lock overlay, and on the Flip's cover screen a horizontal style layout.
 * The screen only forwards normalised touches and button presses; the gestures are core's
 * [TrackpadInterpreter], the window controls the Space's SpatialPointer.
 *
 * 0.4.2: while the Space is up the trackpad's window cannot take focus, so touching it leaves
 * input and the phone's keyboard with the focused panel on the glasses; each touch also asks the
 * Space to give the panel focus back, in case Android moved it anyway.
 *
 * 0.4.8: while the Space is up a [KeySink] window takes the keys sent to the phone (phone Back
 * goes to the Space). The sink takes no touches, so the trackpad stays not focusable.
 */
class TrackpadActivity : Activity(), PadHost {
    private val keySink by lazy { KeySink(this) { PointerHub.submit(PointerSignal.Back, HandProviderKind.TRACKPAD) } }
    private var resumed = false
    private lateinit var prefs: TrackpadPrefs
    private var settings = TrackpadSettings()
    private val pad = TrackpadInterpreter()
    private val home = HomeButton()
    private val unlock = UnlockHold()
    private val gate = HapticGate()
    private lateinit var screen: PadScreen

    /** 0.5.55: holds [screen]; draws it into a panel-sized surface when the window is compat scaled. */
    private lateinit var frame: NativeFrame
    private val main = Handler(Looper.getMainLooper())
    private var touching = false
    private var cover = false
    private var sensors: SensorManager? = null
    private var rotation: Sensor? = null
    private var rotationOn = false
    private var gestureLoadingUntil = 0L
    private var screenWentOff = false
    private var lastLine: TrackpadLine? = null

    /** 0.5.82: the last of the launcher's keyboard asks answered ([SpaceSession.keyboardWanted]). */
    private var keyboardSeen = SpaceSession.keyboardWanted.get()

    /** 0.5.74: the status toasts, settled and held back while the glasses start ([TrackpadStatusToast]). */
    private val statusToast = com.hayeco.flipspaces.core.spatial.TrackpadStatusToast(SystemClock.uptimeMillis())
    private var imeShown = false
    private var backCallback: Any? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
    }

    // 0.5.17 cover screen.
    private var coverLayout: CoverLayout? = null
    private var guard: ThumbGuard? = null
    private val repeat = CoverRepeat()
    private var repeatAction: WindowAction? = null
    private var typing = false
    private var shownHold: WindowAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.1.10: after Exit Pocket XR, nothing opens the trackpad by itself; the user opening it brings Pocket XR back.
        if (AppExit.exited && intent?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            finish()
            return
        }
        AppExit.reopened(this)
        // The phone's screen stays on while it is the trackpad, so it never dims under a resting thumb.
        // 0.5.17 cover screen: over the lock screen, waking the screen it starts on (the manifest
        // says so too), and drawn into the cutout area so core's layout places around the cameras.
        // 0.5.51: both only while the Menu's "Trackpad keeps the screen on" is on ([applyKeepScreenOn]).
        setShowWhenLocked(true)
        applyKeepScreenOn()
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        CoverTrackpad.start(this)
        prefs = TrackpadPrefs(this)
        settings = prefs.settings
        SpaceSession.follow = prefs.follow
        prefs.applyCurve()
        sensors = getSystemService(SensorManager::class.java)
        rotation = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (settings.mode == PadMode.SPATIAL_MOUSE && rotation == null) settings = settings.copy(mode = PadMode.VERTICAL)
        screen = PadScreen(this, this)
        screen.landscapeDevice = prefs.landscapeDevice
        // 0.5.55: the pad drawn at the panel's own pixels where Samsung's size compatibility
        // makes the window 1.5 times the cover ([NativeFrame]); the screen itself is laid out and touched as before.
        frame = NativeFrame(this, screen, ::bufferFor) { b, drawn, why ->
            Log.i(TAG, b.line(if (cover) "cover" else "main screen", display?.displayId ?: -1, drawn, why))
        }
        frame.nativeOn = prefs.nativeBuffer
        setContentView(frame)
        // The phone's own keyboard opens over the trackpad: say where typing goes. 0.5.96 (open source audit): our own words.
        screen.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.isVisible(WindowInsets.Type.ime())
            if (ime && !imeShown && !typing) screen.showToast("Type here; it goes to the window you're using")
            imeShown = ime
            // 0.5.17 cover screen: the cutouts and corners may only be known now.
            if (cover) v.post { updateCoverLayout() }
            v.onApplyWindowInsets(insets)
        }
        screen.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (cover && (r - l != oldR - oldL || b - t != oldB - oldT)) screen.post { updateCoverLayout() }
        }
        cover = isCoverScreen()
        applyMode()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, filter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback {
                if (!screen.closeTop()) {
                    CoverTrackpad.leftByUser()
                    finish()
                }
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
        // First open: the usage instructions, as the drawer shows them at any time.
        // 0.5.17 cover screen: not over a trackpad that opened by itself (the glasses plugged in folded).
        // 1.1.9: the plugged-in glasses' setup first when they have one (issue 31), and Spatial Mouse for this device (issue 33).
        // 1.1.16 (issue 12): the glasses' setup is the setup check now, which opens by itself once the tutorial closes.
        if (!prefs.introShown && !intent.getBooleanExtra(EXTRA_AUTO, false)) {
            prefs.introShown = true
            screen.showIntro(com.hayeco.flipspaces.core.pointer.IntroPages.firstRun(prefs.landscapeDevice, rotation != null, settings.oneFingerScroll))
        }
        // 1.1.16: Settings' "Glasses setup check" row.
        if (intent?.getBooleanExtra(EXTRA_GLASSES_CHECK, false) == true) {
            intent.removeExtra(EXTRA_GLASSES_CHECK)
            if (!cover) openGlassesCheck()
        }
    }

    /** 0.5.101: the trackpad already open, then Settings' Trackpad row: onResume reads the new intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    @Deprecated("Android 13 and later use the OnBackInvokedCallback registered in onCreate")
    override fun onBackPressed() {
        if (!screen.closeTop()) {
            CoverTrackpad.leftByUser()
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 0.5.77 (Mike: "my cover screen is flickering"): with DeX on the glasses holding the phone's global
        // configuration (font scale 1.15, Samsung's font_scale_on_external_desktop_display) and the cover at 1.0, Android
        // reported a font scale change to the trackpad about ten times a second and relaunched it each time (the events log's
        // wm_relaunch_resume_activity, changes 0x40000000). The manifest now takes fontScale itself, and a change that is only
        // the font scale (or weight) leaves the pad as it is: its sizes are the layout's own per screen.
        val changed = lastConfig?.diff(newConfig) ?: -1
        lastConfig = Configuration(newConfig)
        if (changed != -1 && changed != 0 && changed and (android.content.pm.ActivityInfo.CONFIG_FONT_SCALE or android.content.pm.ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT).inv() == 0) return
        // 1.1.9 (issue 33): Spatial Mouse on a handheld locks no orientation, so its page follows the screen's turn.
        aimRotation = display?.rotation ?: aimRotation
        if (!cover && changed and ActivityInfo.CONFIG_ORIENTATION != 0) screen.configure(settings, cover, coverLayout)
        relayout()
    }

    private var lastConfig: Configuration? = null

    /**
     * The screen changed under the trackpad (a fold or an unfold on the same display, or
     * [CoverTrackpad]'s handover): the gesture under way is cancelled, and the layout follows.
     */
    fun relayout() {
        val nowCover = isCoverScreen()
        if (nowCover != cover) {
            if (touching) send(pad.cancel())
            touching = false
            guard?.cancel()
            repeat.up()
            cover = nowCover
            Log.i(TAG, "trackpad now on the ${if (cover) "cover" else "main"} screen layout (display ${display?.displayId})")
            applyMode()
        } else {
            if (cover) updateCoverLayout()
            screen.requestLayout()
        }
        // 0.5.55: the buffer for this screen again (a size change also comes through NativeFrame.onSizeChanged).
        frame.post { frame.refresh() }
    }

    /**
     * 0.5.55: the buffer for a window of [w] x [h] app pixels on this display: the panel's mode (its physical pixels) over
     * the app's view of the display (its real metrics, 1422 x 1572 under Samsung's size compatibility on the cover).
     */
    private fun bufferFor(w: Int, h: Int): com.hayeco.flipspaces.core.pointer.NativeBuffer? {
        val d = display ?: return null
        val mode = d.mode
        val real = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            d.getRealMetrics(it)
        }
        return com.hayeco.flipspaces.core.pointer.NativeBuffer.pick(mode.physicalWidth, mode.physicalHeight, real.widthPixels, real.heightPixels, w, h)
    }

    /** 0.5.17 cover screen: [CoverTrackpad] moves the trackpad to the other screen; this one goes, the session stays. */
    fun finishForMove() {
        if (touching) send(pad.cancel())
        touching = false
        finishAndRemoveTask()
    }

    /** The Flip's cover screen (399 x 441 dp): small and nearly square ([FoldPosture.isCoverSize]). */
    private fun isCoverScreen(): Boolean {
        val c = resources.configuration
        return FoldPosture.isCoverSize(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat())
    }

    /**
     * 0.5.17 cover screen: core's layout from this window's size, its cutouts and rounded corners
     * (the Flip7's figures where Android gives none), then the cover curve, the thumb's tap limits
     * and the palm and edge guard for that pad.
     */
    private fun updateCoverLayout() {
        if (!cover) return
        val dm = resources.displayMetrics
        val d = dm.density
        val wPx = screen.width.takeIf { it > 0 } ?: dm.widthPixels
        val hPx = screen.height.takeIf { it > 0 } ?: dm.heightPixels
        val insets = window.decorView.rootWindowInsets
        val loc = IntArray(2).also { screen.getLocationInWindow(it) }
        val cutouts = insets?.displayCutout?.boundingRects.orEmpty().map {
            DpRect((it.left - loc[0]) / d, (it.top - loc[1]) / d, (it.right - loc[0]) / d, (it.bottom - loc[1]) / d)
        }.ifEmpty { if (FoldPosture.isCoverSize(wPx / d, hPx / d)) listOf(CoverLayoutMath.FLIP7.cutoutFor(wPx / d, hPx / d)) else emptyList() }
        val corner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && insets != null) {
            listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                .mapNotNull { insets.getRoundedCorner(it)?.radius }.maxOrNull()?.let { it / d }
        } else {
            null
        } ?: CoverLayoutMath.FLIP7.CORNER_RADIUS_DP
        val l = CoverLayoutMath.compute(wPx / d, hPx / d, cutouts, corner, settings.coverControls, steadyOffered())
        if (l == coverLayout) return
        coverLayout = l
        val problems = CoverLayoutMath.problems(l)
        Log.i(TAG, "cover layout %.0f x %.0f dp, %d cutouts, corners %.0f dp: pad %.0f x %.0f dp%s".format(
            l.widthDp, l.heightDp, cutouts.size, corner, l.pad.width, l.pad.height, if (problems.isEmpty()) ", every rule kept" else "; problems: $problems",
        ))
        val real = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            display?.getRealMetrics(it)
        }
        // 0.5.18: millimetres from the panel's real pixels an inch, times
        // One UI's cover compatibility scale (real pixels over the app's: 948 / 1422 on the Flip7).
        val physicalLong = display?.mode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        coverFactor = com.hayeco.flipspaces.core.pointer.CoverScale.factor(physicalLong, maxOf(real.widthPixels, real.heightPixels))
        val mmX = com.hayeco.flipspaces.core.pointer.CoverScale.mmPerAppPx(dm.xdpi, coverFactor)
        val mmY = com.hayeco.flipspaces.core.pointer.CoverScale.mmPerAppPx(dm.ydpi, coverFactor)
        val padW = l.pad.width * d * mmX
        val padH = l.pad.height * d * mmY
        val curve = CoverCurve(padW, padH)
        pad.cover = curve
        val (move, safe) = ThumbFeel.limits(padW, padH)
        pad.moveLimit = move
        pad.safeDownMove = safe
        guard = ThumbGuard(real.widthPixels.coerceAtLeast(wPx) * mmX, real.heightPixels.coerceAtLeast(hPx) * mmY)
        Log.i(
            TAG,
            "cover pad %.1f x %.1f mm: %.2f deg/mm slow, %.2f fast, a tap within %.1f mm, a palm from %.0f mm; compatibility scale %.3f (panel %d px, the app sees %d)".format(
                padW, padH, curve.slowGain, curve.fastGain, ThumbFeel.TAP_MOVE_MM, ThumbGuard.PALM_MM, coverFactor, physicalLong, maxOf(real.widthPixels, real.heightPixels),
            ),
        )
        screen.configure(settings, true, l)
    }

    /**
     * The page the pad works as: the chosen one, or on the cover screen (which has no page
     * selector) always the Horizontal Trackpad's, so the finger moves the cursor.
     */
    // 0.5.35 (Mike: "how come I can't enable spatial mouse while my Flip is closed?"): the cover screen takes Spatial Mouse
    // too; the phone aims with its top edge, closed or open. 0.5.67 (Mike: open had Spatial Mouse and closed the trackpad,
    // "they save different settings which is weird"): one choice for both, the page setting's; the cover's own trackpad
    // stands for every other page.
    private val padMode: PadMode get() = if (cover) (if (settings.mode == PadMode.SPATIAL_MOUSE && rotation != null) PadMode.SPATIAL_MOUSE else PadMode.HORIZONTAL) else settings.mode

    /** The page in use: the interpreter, the orientation lock, the layout, the Space and the phone's aim sensor. */
    private fun applyMode() {
        pad.mode = padMode
        pad.cursorSpeed = settings.cursorSpeed
        pad.scrollFactor = TrackpadSettings.scrollFactor(settings.scrollSpeed)
        pad.stripFactor = TrackpadSettings.scrollFactor(settings.stripSpeed)
        pad.oneFingerScroll = settings.oneFingerScroll
        pad.stripSide = settings.stripSide
        pad.stripAllowed = !cover
        // Spatial Mouse and Vertical lock portrait, Horizontal landscape (0 or 8 by its direction);
        // the cover screen keeps its own. 1.1.9 (issue 33): Spatial Mouse on a landscape handheld locks nothing, as its aim
        // follows the screen's rotation.
        requestedOrientation = if (cover) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else TrackpadSettings.screenOrientation(settings.mode, settings.direction, prefs.landscapeDevice)
        // 0.5.17 cover screen: the whole Flex Window, no status bar over the top row (a swipe shows it).
        window.insetsController?.let { c ->
            if (cover) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        }
        if (!cover) {
            pad.cover = null
            pad.moveLimit = TrackpadInterpreter.MOVE_LIMIT
            pad.safeDownMove = TrackpadInterpreter.SAFE_DOWN_MOVE
            guard = null
            coverLayout = null
            if (typing) screen.showText(false)
        }
        screen.configure(settings, cover, coverLayout)
        if (cover) screen.post { updateCoverLayout() }
        submit(PadSignal.Mode(padMode))
        updateSensor()
    }

    /** The Space opened or closed while the trackpad is in front. */
    private val spaceActive: (Boolean) -> Unit = {
        runOnUiThread {
            updateFocusable()
            if (it) submit(PadSignal.Mode(padMode))
        }
    }

    /** 1.2.3 (issue 55): a keyboard the text field asked for before this window had focus opens now. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::screen.isInitialized) screen.windowFocused()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 0.5.67: the cover and the open phone share one pad choice; a change made on the other screen is taken up here.
        val saved = prefs.settings.mode.let { if (it == PadMode.SPATIAL_MOUSE && rotation == null) PadMode.VERTICAL else it }
        if (saved != settings.mode) {
            settings = settings.copy(mode = saved)
            send(pad.changeMode(padMode))
            applyMode()
        }
        GlassesAutoOpen.resumed(this)
        CoverTrackpad.resumed(this)
        // 1.0.1: if the last run crashed, offer to send the report.
        CrashCatcher.offer(this)
        // 0.5.101: Settings' Trackpad row opens the trackpad with its Menu out, where its settings are.
        if (intent?.getBooleanExtra(EXTRA_MENU, false) == true) {
            intent.removeExtra(EXTRA_MENU)
            screen.openDrawer(atTrackpad = true)
        }
        // 1.1.15: the update banner; opened from the update notification, it is logged.
        if (intent?.getBooleanExtra(UpdateChecker.EXTRA_UPDATE, false) == true) {
            intent.removeExtra(UpdateChecker.EXTRA_UPDATE)
            Log.i(TAG, "opened from the update notification (banner for ${UpdateChecker.banner.value?.version ?: "nothing newer now"})")
        }
        refreshUpdate()
        // 0.5.55: each show logs its `trackpad buffer:` line once (relayout's refresh, or the first size).
        frame.forgetReport()
        // 0.5.17 cover screen: back in front (a move, or Samsung resuming it after a fold): the layout for this screen.
        relayout()
        SpaceSession.activeListeners += spaceActive
        updateFocusable()
        HandInputManager.trackpadOpened(true)
        // The screen-lock overlay: back from the screen going off (a pocket), the pad is locked.
        if (screenWentOff) {
            screenWentOff = false
            if (SpaceSession.active) screen.showLock(true)
        }
        submit(PadSignal.Mode(padMode))
        updateSensor()
        main.removeCallbacks(ticker)
        main.post(ticker)
        offerShizuku()
    }

    /**
     * 0.5.28 (after a phone restart Shizuku was not running, and two plug-ins opened the
     * flat home for it): the trackpad says so with a one-tap start (Shizuku opens on the phone). The glasses show the flat
     * home's banner with the same fix.
     */
    private fun offerShizuku() {
        val status = com.hayeco.flipspaces.platform.ShizukuBridge.status(this)
        // 1.1.3 (issue 20: "there was no Shizuku enable flow"): Shizuku running but Pocket XR not allowed yet. The allow
        // button was only under Menu, Settings, Spatial windows; the trackpad now asks Shizuku's own dialog at one tap.
        if (status == com.hayeco.flipspaces.core.ShizukuStatus.NO_PERMISSION) {
            Log.i(TAG, "Shizuku is running but has not allowed Pocket XR: the trackpad offers to allow it")
            screen.showAction("Allow Pocket XR in Shizuku for app windows.", "Tap here to allow", millis = 15_000L) {
                Log.i(TAG, "Shizuku's allow dialog asked for from the trackpad's note")
                com.hayeco.flipspaces.platform.ShizukuBridge.requestPermission()
            }
            return
        }
        if (status != com.hayeco.flipspaces.core.ShizukuStatus.NOT_RUNNING) return
        Log.i(TAG, "Shizuku is installed but not running: the trackpad offers to start it")
        screen.showAction("Start Shizuku for app windows.", "Tap here to open Shizuku") {
            Log.i(TAG, "Shizuku opened from the trackpad's note")
            SpaceRouting.fix(this, com.hayeco.flipspaces.core.spatial.FlatFix.OPEN_SHIZUKU)
        }
    }

    override fun onPause() {
        resumed = false
        // 1.2.3 (issue 59): the Game controller lets go too (touches lifted, the gamepad centred), via releaseGameKeys.
        // 1.2.3 (issue 59): a game key held as the trackpad goes is let go, so the game is not left holding it.
        screen.releaseGameKeys()
        main.removeCallbacks(ticker)
        main.removeCallbacks(coaster)
        if (touching || pad.coasting) send(pad.cancel())
        touching = false
        if (home.pressed) send(home.up(SystemClock.uptimeMillis()))
        guard?.cancel()
        repeat.up()
        repeatAction = null
        updateSensor()
        keySink.hide()
        SpaceSession.activeListeners -= spaceActive
        GlassesAutoOpen.paused(this)
        CoverTrackpad.paused(this)
        HandInputManager.trackpadOpened(false)
        super.onPause()
    }

    override fun onDestroy() {
        keySink.hide()
        CoverTrackpad.destroyed(this)
        runCatching { unregisterReceiver(screenOff) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenWentOff = true
        }
    }

    /**
     * Focusable only while no Space is up ([PanelFocus.trackpadFocusable]); the key sink is up
     * whenever it is not, while the trackpad is in front ([PanelFocus.trackpadKeySink]).
     */
    private fun updateFocusable() {
        val active = SpaceSession.active
        // 0.5.17 cover screen: while its text field is open the trackpad takes focus, so the
        // cover's own keyboard opens for it; the keys it types are sent on to the window.
        if (resumed && !typing && PanelFocus.trackpadKeySink(active)) keySink.show() else keySink.hide()
        // A mouse on the phone moves the Space's pointer while the Space is up (MouseRoute): Android's arrow is hidden over the pad.
        frame.pointerIcon = if (active) android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL) else null
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val focusable = typing || PanelFocus.trackpadFocusable(active)
        val now = window.attributes.flags and flag == 0
        if (focusable == now) return
        if (focusable) window.clearFlags(flag) else window.addFlags(flag)
    }

    // ---- input ----

    /** A mouse's clicks and drags on the phone go to the Space's pointer while the Space is up, never to the pad as a finger. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = MouseRoute.onMotion(ev) || super.dispatchTouchEvent(ev)

    /** A mouse's moves and wheel on the phone, likewise. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = MouseRoute.onMotion(ev) || super.dispatchGenericMotionEvent(ev)

    private fun submit(signal: PointerSignal) {
        PointerHub.submit(signal, HandProviderKind.TRACKPAD)
        gestureLog.add(signal)
        for (line in gestureLog.take()) Log.i(TAG, if (cover) line else line.replaceFirst("cover gesture", "trackpad gesture"))
    }

    private fun send(signals: List<PointerSignal>) {
        signals.forEach(::submit)
        // 0.5.32 (Mike: a way to recenter the Spatial Mouse's dot): a Home long press recentres it, and the hold now
        // says it worked, with a buzz and a toast.
        if (signals.any { it is PadSignal.Home && it.event == com.hayeco.flipspaces.core.pointer.HomeEvent.LONG_PRESS }) {
            buzz(PadHaptic.RECENTRE)
            if (padMode == PadMode.SPATIAL_MOUSE) screen.showToast("Laser recentred in front of you.")
        }
        pad.takeHaptics().forEach(::buzz)
        // 0.5.22: a three finger gesture that did nothing says why.
        pad.takeThreeRefusal()?.let { Log.i(TAG, "${if (cover) "cover" else "trackpad"} pad: $it") }
        // 0.5.21: a flick on the thin strip left the page coasting.
        if (pad.coasting && !touching) {
            main.removeCallbacks(coaster)
            main.postDelayed(coaster, COAST_FRAME_MS)
        }
    }

    /** One impulse of the haptic table, unless inside the 500 ms cooldown ([HapticGate]). */
    private fun buzz(h: PadHaptic) {
        val ms = gate.play(h, SystemClock.uptimeMillis()) ?: return
        // 0.5.21: the thin strip's ticks are the system's light tick, not a one shot buzz.
        val effect = if (h == PadHaptic.SCROLL_TICK) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching { vibrator?.vibrate(effect) }
            .onFailure { Log.w(TAG, "haptic ${h.what} not played: ${it.message}") }
    }

    /**
     * 0.5.19 (the trackpad's window had 48.8 % janky frames, 35 slow UI thread
     * frames): a line whenever one touch, or one 30 ms tick, keeps the UI thread over [SLOW_UI_MS], with
     * what it was, so the next test says where the time goes.
     */
    private fun timedUi(what: String, block: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        block()
        val ms = SystemClock.uptimeMillis() - t0
        if (ms >= SLOW_UI_MS) {
            slowUi++
            if (slowUi <= 40 || slowUi % 50 == 0) Log.i(TAG, "trackpad UI thread: $what took $ms ms (slow $slowUi times so far, ${if (cover) "cover" else "main"} screen)")
        }
    }

    private var slowUi = 0

    /** Every pointer and every action, normalised to pad units. */
    override fun padTouch(event: MotionEvent, width: Float, height: Float) = timedUi("a touch (${MotionEvent.actionToString(event.actionMasked)}, ${event.pointerCount} fingers)") { padTouchNow(event, width, height) }

    /** 0.5.21: the thin scroll strip on this pad view, [widthPx] x [heightPx] ([com.hayeco.flipspaces.core.pointer.ScrollStrip]). */
    private fun stripLayout(widthPx: Float, heightPx: Float): com.hayeco.flipspaces.core.pointer.ScrollStrip.Layout {
        val d = resources.displayMetrics.density
        return if (cover) {
            com.hayeco.flipspaces.core.pointer.ScrollStrip.layout(widthPx / d, heightPx / d, com.hayeco.flipspaces.core.pointer.ScrollStrip.coverSide(settings.coverControls))
        } else {
            com.hayeco.flipspaces.core.pointer.ScrollStrip.main(widthPx / d, heightPx / d, settings.stripSide, portrait = settings.mode != PadMode.HORIZONTAL)
        }
    }

    /** 0.5.21: each frame of the thin strip's coast after a flick, until it stops or a finger lands. */
    private val coaster = object : Runnable {
        override fun run() {
            // [send] posts the next frame while the page still coasts.
            if (pad.coasting) send(pad.coast(SystemClock.uptimeMillis()))
        }
    }

    private fun padTouchNow(event: MotionEvent, width: Float, height: Float) {
        if (screen.locked) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pad.scrollStrip = stripLayout(width, height).zone
        val g = guard
        if (cover && g != null) return guardedTouch(g, event, width, height)
        phoneTouch(event)
        val now = event.eventTime
        fun t(i: Int) = PadTouch.of(event.getPointerId(i), event.getX(i), event.getY(i), width, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                updateFocusable()
                if (SpaceSession.active) SpaceSession.send(SpaceCommand.TrackpadTouched)
                send(pad.down(t(event.actionIndex), now))
            }
            MotionEvent.ACTION_POINTER_DOWN -> send(pad.down(t(event.actionIndex), now))
            MotionEvent.ACTION_MOVE -> send(pad.move((0 until event.pointerCount).map { t(it) }, now))
            MotionEvent.ACTION_POINTER_UP -> send(pad.up(event.getPointerId(event.actionIndex), now))
            MotionEvent.ACTION_UP -> {
                touching = false
                send(pad.up(event.getPointerId(event.actionIndex), now))
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                send(pad.cancel())
            }
        }
    }

    /**
     * 0.5.17 cover screen: the pad's touches through the palm and edge guard ([ThumbGuard]), in
     * screen millimetres (the raw position over the screen's real pixels an inch), then back to pad
     * units for the interpreter.
     */
    private fun guardedTouch(g: ThumbGuard, event: MotionEvent, width: Float, height: Float) {
        val now = event.eventTime
        val dm = resources.displayMetrics
        // 0.5.18: real millimetres ([coverFactor], One UI's compatibility scale).
        val mmX = com.hayeco.flipspaces.core.pointer.CoverScale.mmPerAppPx(dm.xdpi, coverFactor)
        val mmY = com.hayeco.flipspaces.core.pointer.CoverScale.mmPerAppPx(dm.ydpi, coverFactor)
        // The pad's top left on the screen, from the event itself (raw minus local).
        val offX = event.rawX - event.x
        val offY = event.rawY - event.y
        fun gt(i: Int) = GuardTouch(event.getPointerId(i), (event.getX(i) + offX) * mmX, (event.getY(i) + offY) * mmY, event.getTouchMajor(i) * mmX)
        fun toPad(t: GuardTouch) = PadTouch.of(t.id, t.xMm / mmX - offX, t.yMm / mmY - offY, width, height)
        phoneTouch(event)
        val events = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                threeHeldLogged = false
                updateFocusable()
                if (SpaceSession.active) SpaceSession.send(SpaceCommand.TrackpadTouched)
                g.down(gt(event.actionIndex), now)
            }
            MotionEvent.ACTION_POINTER_DOWN -> g.down(gt(event.actionIndex), now).also {
                // 0.5.22: three fingers down that the guard does not all let through
                // cannot make a three finger swipe; said once a gesture.
                if (event.pointerCount >= 3 && !threeHeldLogged) {
                    val through = g.accepted
                    if (through < 3 && it.none { e -> e is GuardEvent.Cancel }) {
                        threeHeldLogged = true
                        Log.i(TAG, "cover pad: ${event.pointerCount} fingers down, $through let through by the edge and palm guard (a finger within ${ThumbGuard.EDGE_MM.toInt()} mm of the edge waits until it moves ${ThumbGuard.EDGE_RELEASE_MM.toInt()} mm); a three finger swipe needs three")
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> g.move((0 until event.pointerCount).map { gt(it) }, now)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) touching = false
                val canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0
                g.up(event.getPointerId(event.actionIndex), now, canceled)
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                g.cancel().ifEmpty { listOf(GuardEvent.Cancel) }
            }
            else -> emptyList()
        }
        for (e in events) {
            when (e) {
                is GuardEvent.Down -> send(pad.down(toPad(e.touch), now))
                is GuardEvent.Move -> send(pad.move(e.touches.map(::toPad), now))
                is GuardEvent.Up -> send(pad.up(e.id, now))
                GuardEvent.Cancel -> {
                    send(pad.cancel())
                    if (g.takeRefusal()?.let { Log.i(TAG, "cover pad: $it") } == null) Log.i(TAG, "cover pad: a cancelled touch; the gesture is cancelled")
                }
            }
        }
        // 0.5.18: every palm and resting edge grip, one line each.
        g.takeRefusal()?.let { Log.i(TAG, "cover pad: $it") }
    }

    /** 0.5.22: the guard's held back third finger was logged for this gesture. */
    private var threeHeldLogged = false

    /** 0.5.18: the cover's One UI compatibility scale, real pixels over app pixels ([com.hayeco.flipspaces.core.pointer.CoverScale]). */
    private var coverFactor = 1f

    /** 0.5.18: the phone's trackpad told the hand recognizer about each touch ([com.hayeco.flipspaces.core.hands.PhoneHandling]). */
    private fun phoneTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> com.hayeco.flipspaces.core.hands.PhoneHandling.shared.touch(true, SystemClock.uptimeMillis())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> com.hayeco.flipspaces.core.hands.PhoneHandling.shared.touch(false, SystemClock.uptimeMillis())
        }
    }

    /** 0.5.18: one line per pad gesture and window button step. */
    private val gestureLog = com.hayeco.flipspaces.core.pointer.PadGestureLog()

    override fun backClicked() = send(pad.back())

    override fun homeDown() = send(home.down(SystemClock.uptimeMillis()))

    override fun homeUp() = send(home.up(SystemClock.uptimeMillis()))

    /** Every 30 ms while in front: the long presses, the unlock hold, the Space's buzzes, the gesture state and the status. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            timedUi("the 30 ms tick") { tick() }
            main.postDelayed(this, 30L)
        }

        private fun tick() {
            val now = SystemClock.uptimeMillis()
            // 0.5.37: the Control Center's requests from the glasses, then this trackpad's state for it to show.
            while (true) {
                when (val r = SpaceSession.padRequests.poll() ?: break) {
                    SpaceSession.PadRequest.ToggleSpatialMouse -> if (cover) {
                        coverSpatialMouseChosen(settings.mode != PadMode.SPATIAL_MOUSE)
                    } else {
                        modeChosen(if (settings.mode == PadMode.SPATIAL_MOUSE) PadMode.VERTICAL else PadMode.SPATIAL_MOUSE)
                    }
                    SpaceSession.PadRequest.ToggleHands -> gestureClicked()
                    is SpaceSession.PadRequest.Speed -> {
                        speedChanged((settings.cursorSpeed + r.delta).coerceIn(0, 100))
                        screen.refreshSpeed(settings.cursorSpeed)
                    }
                }
            }
            // 1.1.15: a check in the background may find a newer Pocket XR while the trackpad is open.
            refreshUpdate()
            SpaceSession.padSpatialMouse = padMode == PadMode.SPATIAL_MOUSE
            SpaceSession.padSpatialMouseAvailable = rotation != null
            SpaceSession.padHands = gestureState() == 2
            SpaceSession.padCursorSpeed = settings.cursorSpeed
            if (touching) send(pad.tick(now))
            SpaceSession.padHold = if (touching) pad.holdProgress(now) else 0f
            send(home.tick(now))
            // 0.5.17 cover screen: Further or Closer held repeats, faster the longer it is held.
            repeatAction?.let { a -> repeat.tick(now).forEach { submit(PadSignal.Window(a, it)) } }
            val hold = when {
                SpaceSession.heldPanelId == null -> null
                SpaceSession.heldCurves -> WindowAction.CURVE
                SpaceSession.heldAdjusts -> WindowAction.RESIZE
                else -> WindowAction.MOVE
            }
            if (hold != shownHold) {
                shownHold = hold
                screen.refreshCover()
            }
            if (unlock.tick(now)) unlocked()
            if (screen.locked) screen.lockProgress(unlock.progress(now), unlock.holding)
            while (true) buzz(SpaceSession.haptics.poll() ?: break)
            // 0.5.82: the launcher's search card was pressed in the glasses: open the text field and its keyboard here.
            SpaceSession.keyboardWanted.get().let { k ->
                if (k != keyboardSeen) {
                    keyboardSeen = k
                    screen.showText(true)
                }
            }
            screen.refreshGesture()
            status()
            checkGlassesSetup(now)
        }
    }

    /** The old status line, now a toast when it changes. */
    private fun status() {
        val raw = TrackpadStatus.line(SpaceSession.active, PointerHub.connected.value, PointerHub.displayId.value != null, false)
        val line = statusToast.offer(raw, SystemClock.uptimeMillis()) ?: return
        val first = lastLine == null
        lastLine = line
        when (line) {
            TrackpadLine.DRIVING_SPACE -> if (!first) screen.showToast("Driving the Space on the glasses.")
            TrackpadLine.POINTER_OFF -> screen.showToast("Turn on the Pocket XR pointer service first.")
            TrackpadLine.NO_GLASSES -> screen.showToast("No glasses display found.")
            TrackpadLine.HOLDING, TrackpadLine.NONE -> Unit
        }
    }

    // ---- the mode popup ----

    override fun modeAvailable(mode: PadMode): Boolean = mode != PadMode.SPATIAL_MOUSE || rotation != null

    override fun modeChosen(mode: PadMode) {
        if (!modeAvailable(mode)) return
        settings = prefs.update { it.copy(mode = mode) }
        send(pad.changeMode(mode))
        applyMode()
    }

    override fun gestureState(): Int {
        val s = GlassesCameraProvider.status.value
        return when {
            s is ProviderStatus.Unavailable -> 0
            s is ProviderStatus.Running -> 2
            SystemClock.uptimeMillis() < gestureLoadingUntil -> 3
            else -> 1
        }
    }

    /** The gesture button toggles hand tracking. */
    override fun gestureClicked() {
        if (gestureState() == 2) {
            HandInputManager.stopHands(this)
            gestureLoadingUntil = 0L
            settings = prefs.update { it.copy(gestures = false) }
        } else {
            val problem = HandInputManager.startHands(this)
            if (problem != null) {
                screen.showToast(problem)
            } else {
                gestureLoadingUntil = SystemClock.uptimeMillis() + GESTURE_LOADING_MS
                settings = prefs.update { it.copy(gestures = true) }
            }
        }
        screen.refreshGesture()
    }

    // ---- the drawer ----

    /** Cursor Speed, sent on the lift. */
    /** 0.5.83: the Menu's Two-finger scroll speed and Scroll strip speed. */
    override fun scrollSpeedChanged(speed: Int) {
        settings = prefs.update { it.copy(scrollSpeed = speed) }
        pad.scrollFactor = TrackpadSettings.scrollFactor(settings.scrollSpeed)
    }

    /** 1.1.10 (issue 35): the Menu's "One-finger scroll on Spatial Mouse". */
    override fun oneFingerScrollChosen(on: Boolean) {
        if (touching) send(pad.cancel())
        settings = prefs.update { it.copy(oneFingerScroll = on) }
        pad.oneFingerScroll = on
        screen.configure(settings, cover, coverLayout)
        Log.i(TAG, "one finger scroll on Spatial Mouse ${if (on) "on" else "off"} (${if (cover) "cover" else "main"} screen)")
    }

    override fun stripSpeedChanged(speed: Int) {
        settings = prefs.update { it.copy(stripSpeed = speed) }
        pad.stripFactor = TrackpadSettings.scrollFactor(settings.stripSpeed)
    }

    override fun speedChanged(speed: Int) {
        settings = prefs.update { it.copy(cursorSpeed = speed) }
        pad.cursorSpeed = settings.cursorSpeed
    }

    // ---- 1.2.3 (issue 54): the Air's display mode, untested, behind a switch that is off by default ----

    /** The mode the Air last reported, or null before a reading. */
    @Volatile private var airMode: Int? = null

    /** What the mode row says while there is no mode: reading, or the last problem. */
    @Volatile private var airNote: String? = null

    @Volatile private var airBusy = false

    override fun airDisplayOffered(): Boolean = com.hayeco.flipspaces.spatial.AirImuLink.find(this) != null

    override fun airDisplayOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).airDisplayMode

    override fun airDisplayChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).airDisplayMode = on
        Log.i(TAG, "Air display mode switch ${if (on) "on (untested on Air glasses)" else "off"}")
        airMode = null
        airNote = null
        if (on) airRead(0L)
    }

    override fun airDisplayLine(): String {
        if (airMode == null && airNote == null && !airBusy) airRead(0L)
        return airMode?.let { com.hayeco.flipspaces.core.spatial.AirDisplayMode.label(it) } ?: airNote ?: "reading"
    }

    override fun airDisplayAction(): String = when (val m = airMode) {
        null -> "Tap to read it again"
        else -> if (com.hayeco.flipspaces.core.spatial.AirDisplayMode.isSideBySide(m)) "Tap to switch to 2D" else "Tap to switch to 3D side by side"
    }

    override fun airDisplaySwitch() {
        if (airBusy) return
        val from = airMode
        if (from == null) {
            airRead(0L)
            return
        }
        val to = com.hayeco.flipspaces.core.spatial.AirDisplayMode.toggled(from)
        airBusy = true
        Thread({
            val result = runCatching { com.hayeco.flipspaces.spatial.AirDisplayLink.set(this, to) }
            runOnUiThread {
                airBusy = false
                result.onSuccess {
                    Log.i(TAG, "Air display mode: asked for $to (${com.hayeco.flipspaces.core.spatial.AirDisplayMode.label(to)}), the glasses took it")
                    screen.showToast("The glasses switch to ${com.hayeco.flipspaces.core.spatial.AirDisplayMode.label(to)}. Their screen reconnects, so give it a few seconds.")
                    airMode = null
                    airNote = "switching"
                    // The display reconnects after a switch; the mode is read again once it has had time.
                    airRead(AIR_REREAD_MS)
                }.onFailure {
                    Log.w(TAG, "Air display mode: asking for $to failed: ${it.message}")
                    screen.showToast("The glasses did not switch: ${it.message}")
                }
            }
        }, "air-display").start()
    }

    private fun airRead(delayMs: Long) {
        if (airBusy) return
        airBusy = true
        airNote = "reading"
        Thread({
            if (delayMs > 0) SystemClock.sleep(delayMs)
            val result = runCatching { com.hayeco.flipspaces.spatial.AirDisplayLink.mode(this) }
            runOnUiThread {
                airBusy = false
                result.onSuccess {
                    airMode = it
                    airNote = null
                    Log.i(TAG, "Air display mode: the glasses report $it (${com.hayeco.flipspaces.core.spatial.AirDisplayMode.label(it)})")
                }.onFailure {
                    airMode = null
                    airNote = "could not read (${it.message})"
                    Log.w(TAG, "Air display mode: reading failed: ${it.message}")
                }
                screen.refreshDrawer()
            }
        }, "air-display").start()
    }

    override fun focusedSideBySide(): Pair<String, Boolean>? = if (SpaceSession.active) SpaceSession.focusedSideBySide else null

    override fun sideBySideChosen(on: Boolean) {
        if (SpaceSession.active) SpaceSession.send(SpaceCommand.SideBySide(on))
    }

    override fun stripChosen(side: StripSide) {
        settings = prefs.update { it.copy(stripSide = side) }
        pad.stripSide = side
        screen.configure(settings, cover, coverLayout)
    }

    override fun directionChosen(direction: PadDirection) {
        settings = prefs.update { it.copy(direction = direction) }
        if (settings.mode == PadMode.HORIZONTAL && !cover) requestedOrientation = direction.screenOrientation
    }

    /**
     * 0.5.101 (Mike: each settings screen "a way to reset to Pocket XR default settings"): the Menu's settings back to their
     * defaults, applied at once. The pad type stays (it is the page in use), and so do windows and presets.
     */
    override fun resetSettings() {
        settings = prefs.update { TrackpadSettings(mode = it.mode) }
        pad.cursorSpeed = settings.cursorSpeed
        pad.scrollFactor = TrackpadSettings.scrollFactor(settings.scrollSpeed)
        pad.stripFactor = TrackpadSettings.scrollFactor(settings.stripSpeed)
        pad.oneFingerScroll = settings.oneFingerScroll
        pad.stripSide = settings.stripSide
        if (settings.mode == PadMode.HORIZONTAL && !cover) requestedOrientation = settings.direction.screenOrientation
        coverLayout = null
        if (cover) updateCoverLayout()
        screen.configure(settings, cover, coverLayout)
        val store = com.hayeco.flipspaces.spatial.SpaceStore(this)
        store.resetTrackpadSettings()
        com.hayeco.flipspaces.SpacesState.setNewInstance(store.newInstance)
        applyKeepScreenOn()
        prefs.curveDefault = 0f
        prefs.applyCurve()
        followChosen(FollowMode.BODY_ANCHOR)
        Log.i(TAG, "trackpad settings reset to Pocket XR's defaults (the Menu)")
        screen.showToast("Trackpad settings are back to Pocket XR's defaults.")
    }

    // ---- 1.1.9: the tutorial's pages and the glasses setup ----

    private val spaceStore by lazy { com.hayeco.flipspaces.spatial.SpaceStore(this) }

    /** The XREAL glasses on USB now: the model ([com.hayeco.flipspaces.core.spatial.GlassesSetup.plugged]) and the USB device. */
    private class Plugged(val model: com.hayeco.flipspaces.core.spatial.GlassesModel, val productId: Int, val line: String)

    private fun plugged(): Plugged? = runCatching {
        val usb = getSystemService(android.hardware.usb.UsbManager::class.java)
        val d = usb?.deviceList?.values?.firstOrNull { com.hayeco.flipspaces.core.hands.GlassesUsb.isXreal(it.vendorId) } ?: return@runCatching null
        val model = com.hayeco.flipspaces.core.spatial.GlassesSetup.plugged(d.productId, d.productName) ?: return@runCatching null
        // How they were recognised: the name, the USB ids and the device's version (its firmware's, as USB reports it).
        val line = "Seen as ${d.productName?.trim()?.ifEmpty { null } ?: model.label}, USB %04X:%04X%s".format(
            java.util.Locale.ROOT, d.vendorId, d.productId, runCatching { d.version }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ", version $it" } ?: "",
        )
        Plugged(model, d.productId, line)
    }.getOrNull()

    override fun introPages(): List<com.hayeco.flipspaces.core.pointer.IntroPage> =
        com.hayeco.flipspaces.core.pointer.IntroPages.trackpad(prefs.landscapeDevice, rotation != null, settings.oneFingerScroll)

    /**
     * 1.1.16: this device as the setup check sees it ([com.hayeco.flipspaces.core.spatial.SetupHost.detect]): a Beam Pro,
     * and XREAL's apps among the launchable ones (the manifest's launcher query lets them be seen).
     */
    private val setupHost by lazy {
        val installed = runCatching {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcher, 0).associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        }.getOrDefault(emptyMap())
        com.hayeco.flipspaces.core.spatial.SetupHost.detect(Build.MANUFACTURER, Build.BRAND, Build.MODEL, installed).also {
            Log.i(TAG, "setup host: ${Build.MANUFACTURER} ${Build.MODEL}, Beam Pro ${it.beamPro}, XREAL apps ${it.xrealApps.ifEmpty { listOf("none") }}")
        }
    }

    /** The glasses plugged in, else the ones the Space last ran on (the model from their display's EDID). */
    private fun checkTarget(): Pair<com.hayeco.flipspaces.core.spatial.GlassesModel, Plugged?>? {
        val p = plugged()
        val model = p?.model ?: spaceStore.model.takeIf { it != com.hayeco.flipspaces.core.spatial.GlassesModel.UNKNOWN } ?: return null
        return model to p
    }

    override fun glassesCheckAvailable(): Boolean = checkTarget() != null

    override fun openGlassesCheck() {
        val (model, p) = checkTarget() ?: run {
            screen.showToast("Plug in your glasses first.")
            return
        }
        openGlassesCheck(model, p, "the Menu")
    }

    private fun openGlassesCheck(model: com.hayeco.flipspaces.core.spatial.GlassesModel, p: Plugged?, from: String) {
        val host = setupHost
        val page = com.hayeco.flipspaces.core.spatial.GlassesSetup.page(model, host, p?.productId)
        checkShownThisRun += model.key
        Log.i(TAG, "glasses check opened from $from: ${model.label} (${page.family}), ${page.steps.size} steps, ${page.hostNotes.size} host notes")
        val checkHost = object : GlassesCheckHost {
            override fun headYawDeg(): Float? = if (SpaceSession.active) SpaceSession.aim?.head?.yawPitchRoll()?.first else null
            override fun follow() = SpaceSession.follow
            override fun steady() = com.hayeco.flipspaces.spatial.GlassesDisplayControl.steadyOn
            override fun checkFinished(result: com.hayeco.flipspaces.core.spatial.GlassesCheck.CheckResult?) {
                val states = prefs.glassesCheck
                val next = com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.after(states[model.key], result)
                prefs.glassesCheck = states + (model.key to next)
                Log.i(TAG, "glasses check for ${model.label}: ${result?.let { if (it.passed) "passed" else "failed (${it.fix})" } ?: "skipped"}; now $next")
            }
        }
        screen.showGlassesCheck { close -> GlassesCheckCard(this, page, p?.line, host, checkHost, close) }
    }

    private var glassesCheckedMs = 0L

    /**
     * 1.1.9, once a second from [ticker]: (issue 31) a glasses model plugged in for the first time shows its setup page
     * by itself, and glasses that are likely in their own Anchor mode get a card with one tap to that page. Never over
     * another card or the Menu, and not on the cover screen, so the page waits for the open phone.
     * 1.1.16 (issue 12): the setup check instead of the page, with what applies to this host too; once passed or skipped
     * it never opens by itself, and once passed the Anchor card is not shown either.
     */
    private fun checkGlassesSetup(now: Long) {
        if (now - glassesCheckedMs < GLASSES_CHECK_MS) return
        glassesCheckedMs = now
        if (cover || screen.introOpen || screen.dialogOpen || screen.drawerOpen) return
        val p = plugged()
        if (p != null) {
            val hasAdvice = com.hayeco.flipspaces.core.spatial.GlassesSetup.page(p.model, setupHost, p.productId).hasAdvice
            if (com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.showOnConnect(p.model.key, hasAdvice, prefs.glassesCheck, checkShownThisRun)) {
                openGlassesCheck(p.model, p, "${p.model.label} connected (not passed here yet)")
                return
            }
        }
        if (anchorAskedThisRun || !SpaceSession.active) return
        val model = spaceStore.model
        if (!com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.anchorCardAllowed(model.key, prefs.glassesCheck)) return
        val duty = com.hayeco.flipspaces.spatial.GlassesDisplayControl.glassesDuty
        if (!com.hayeco.flipspaces.core.spatial.GlassesSetup.anchorLikely(model, duty, com.hayeco.flipspaces.spatial.GlassesDisplayControl.dutyOurs)) return
        anchorAskedThisRun = true
        Log.i(TAG, "${model.label} answered a duty of $duty % (their world lock range, at most ${com.hayeco.flipspaces.core.spatial.GlassesSetup.WORLD_LOCK_DUTY_MAX} %): likely in their own Anchor mode; card shown")
        screen.showDialog(
            com.hayeco.flipspaces.core.spatial.GlassesSetup.ANCHOR_CARD,
            ok = com.hayeco.flipspaces.core.spatial.GlassesSetup.ANCHOR_CARD_OK,
            cancel = com.hayeco.flipspaces.core.spatial.GlassesSetup.ANCHOR_CARD_LATER,
        ) { openGlassesCheck(model, plugged(), "the Anchor card") }
    }

    override fun followChosen(mode: FollowMode) {
        if (!mode.available) {
            screen.showToast("${mode.label} needs 6DoF glasses tracking, which these glasses do not give.")
            return
        }
        SpaceSession.follow = mode
        prefs.follow = mode
    }

    override fun currentFollow(): FollowMode = SpaceSession.follow

    override fun steadyOffered(): Boolean = com.hayeco.flipspaces.core.spatial.SteadyMode.offeredOn(com.hayeco.flipspaces.spatial.SpaceStore(this).model)
    override fun steadyOn(): Boolean = com.hayeco.flipspaces.spatial.GlassesDisplayControl.steadyOn

    override fun windowCount(): Int = if (SpaceSession.active) SpaceSession.windowCount else 0

    override fun arrange(kind: ArrangeKind) {
        if (!SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        SpaceSession.send(SpaceCommand.Arrange(kind))
    }

    /** Return to Home Screen: leaves the trackpad for the phone's home screen, after asking. */
    private var notificationAskChecked = false

    /**
     * 1.1.15: the update banner at the top of the trackpad ([UpdateChecker.banner]): Update keeps the download as before
     * ([UpdateChecker.open]), Later hides it until the next version. The first time one shows, on Android 13 and later,
     * the notification permission is asked for once, with a line saying why; declined, the banner is all there is.
     */
    private fun refreshUpdate() {
        val release = UpdateChecker.banner.value
        screen.showUpdate(release?.version, onUpdate = { UpdateChecker.banner.value?.let { UpdateChecker.open(this, it) } }, onLater = {
            UpdateChecker.later(this)
            refreshUpdate()
        })
        if (release == null || notificationAskChecked || !resumed || cover) return
        notificationAskChecked = true
        if (!UpdateChecker.takeNotificationAsk(this)) return
        Log.i(TAG, "notification permission asked for with the first update banner")
        screen.showToast("Allow notifications to hear when a new Pocket XR is ready")
        requestPermissions(arrayOf(com.hayeco.flipspaces.core.GlassesReady.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "notification permission for updates ${if (granted) "granted" else "declined: the banner only"}")
    }

    override fun openSettings() {
        screen.closeDrawer()
        // 0.5.104 (Mike: Settings, Trackpad, then the Menu's Pocket XR Settings "nothing happens it just closes the sidebar"):
        // the trackpad opened from Settings sits above it in the same task, and NEW_TASK only brought that task forward with
        // the trackpad still on top. REORDER_TO_FRONT moves the settings screen above the trackpad (Back returns to it).
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    override fun recordingGlasses(): Boolean = com.hayeco.flipspaces.spatial.SpaceSession.recording || com.hayeco.flipspaces.spatial.SpaceSession.recordWanted

    override fun recordGlasses(on: Boolean) {
        if (on && !com.hayeco.flipspaces.spatial.SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        com.hayeco.flipspaces.spatial.SpaceSession.recordWanted = on
        screen.showToast(if (on) "Recording the glasses. Menu, then Stop recording, when you're done." else "Recording stopped: saved to Movies/Pocket XR.")
    }

    /** 1.2.3 (issue 59): Game keys on; for this run of the trackpad, off again when it closes. */
    private var gameKeys = false

    /** When each game key went down, for its key up (Android pairs them by the down time). */
    private val gameKeyDownAt = HashMap<Int, Long>()

    override fun gameKeysOn(): Boolean = gameKeys

    override fun gameKeysChosen(on: Boolean) {
        if (on && !SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        if (gameKeys == on) return
        // 1.2.3: Game keys and the Game controller share their place; one at a time.
        if (on && controllerMode != com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF) controllerModeChosen(com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF)
        gameKeys = on
        Log.i(TAG, "Game keys ${if (on) "on: held keys go to the focused window" else "off"}")
        screen.configure(settings, cover, coverLayout)
        if (on) screen.showToast("Click a game's window first, then hold the keys.")
    }

    /**
     * 1.2.3 (issue 59): a game key's down or up to the focused window, as a real key event (the window's display, the
     * same path as a hardware keyboard's keys), so a held key stays held in the game. Done turns Game keys off.
     */
    override fun gameKey(change: com.hayeco.flipspaces.core.pointer.GameKeyChange) {
        if (change.keyCode == com.hayeco.flipspaces.core.pointer.GameKeyLayout.EXIT) {
            if (!change.down) return
            gameKeysChosen(false)
            return
        }
        val now = SystemClock.uptimeMillis()
        val downAt = if (change.down) now.also { gameKeyDownAt[change.keyCode] = it } else gameKeyDownAt.remove(change.keyCode) ?: now
        SpaceSession.send(SpaceCommand.PanelKey(if (change.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, change.keyCode, 0, 0, downAt))
    }

    // ---- 1.2.3 (issue 59): the Game controller, as a virtual gamepad or on a game's on-screen controls ----

    private var controllerMode = com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF
    private var spotSetting = false
    private val touchPointers = com.hayeco.flipspaces.core.pointer.TouchPointers()
    private val touchSpots by lazy { com.hayeco.flipspaces.core.pointer.TouchSpotStore(com.hayeco.flipspaces.spatial.SpaceStore(this).touchSpots) }

    /** While setting spots: the control each held control began at, for the hold that sets it. */
    private val spotHeldSince = HashMap<com.hayeco.flipspaces.core.pointer.PadControl, Long>()

    override fun controllerMode(): com.hayeco.flipspaces.core.pointer.GameControllerMode = controllerMode

    override fun controllerModeChosen(mode: com.hayeco.flipspaces.core.pointer.GameControllerMode) {
        val off = com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF
        if (mode != off && !SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        if (mode == controllerMode) return
        val before = controllerMode
        // Leaving a mode lets go of what it held: the gamepad centred and closed, the touches lifted.
        if (before == com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD) {
            com.hayeco.flipspaces.spatial.PanelHost.gamepadInput(com.hayeco.flipspaces.core.pointer.UhidGamepad.report(com.hayeco.flipspaces.core.pointer.ControllerState()))
            com.hayeco.flipspaces.spatial.PanelHost.gamepadClose()
        }
        if (before == com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH) sendTouches(touchPointers.cancel())
        spotSetting = false
        spotHeldSince.clear()
        if (mode != off && gameKeys) {
            gameKeys = false
            gameKeyDownAt.clear()
        }
        controllerMode = mode
        Log.i(TAG, "Game controller: ${mode.label}")
        screen.configure(settings, cover, coverLayout)
        when (mode) {
            com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD -> com.hayeco.flipspaces.spatial.PanelHost.gamepadOpen { err ->
                if (err != null) {
                    Log.w(TAG, "the virtual gamepad could not start: $err")
                    screen.showToast("The gamepad could not start: $err")
                    if (controllerMode == com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD) controllerModeChosen(off)
                } else {
                    // Gamepad input goes to the focused display: the game's window is put on top.
                    SpaceSession.send(SpaceCommand.FocusWindow)
                    screen.showToast("Gamepad on. Click the game's window first if it does not answer.")
                }
            }
            com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH -> {
                val pkg = SpaceSession.focusedApp?.first
                if (pkg == null || touchSpots.of(pkg).isEmpty()) {
                    spotSetting = true
                    screen.showToast("Set the spots: aim the dot at the game's button, then hold the matching control.")
                } else {
                    screen.showToast("Touch on: the controls press the game's own buttons.")
                }
            }
            com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF -> Unit
        }
    }

    override fun controllerChanged() {
        val state = screen.controllerState()
        when (controllerMode) {
            com.hayeco.flipspaces.core.pointer.GameControllerMode.GAMEPAD ->
                com.hayeco.flipspaces.spatial.PanelHost.gamepadInput(com.hayeco.flipspaces.core.pointer.UhidGamepad.report(state))
            com.hayeco.flipspaces.core.pointer.GameControllerMode.TOUCH -> if (spotSetting) spotHolds(state) else {
                val (pkg, aspect) = SpaceSession.focusedApp ?: return
                sendTouches(touchPointers.next(com.hayeco.flipspaces.core.pointer.TouchSpots.touches(state, touchSpots.of(pkg), aspect)))
            }
            com.hayeco.flipspaces.core.pointer.GameControllerMode.OFF -> Unit
        }
    }

    private fun sendTouches(events: List<com.hayeco.flipspaces.core.pointer.TouchPointers.Event>) {
        if (events.isNotEmpty()) SpaceSession.send(SpaceCommand.PadTouches(events))
    }

    /** Setting spots: a control held [com.hayeco.flipspaces.core.pointer.TouchSpots.SET_HOLD_MS] takes the dot's spot. */
    private fun spotHolds(state: com.hayeco.flipspaces.core.pointer.ControllerState) {
        val held = state.buttons + state.sticksHeld
        spotHeldSince.keys.retainAll(held)
        val now = SystemClock.uptimeMillis()
        for (c in held) if (c !in spotHeldSince) {
            spotHeldSince[c] = now
            main.postDelayed({ spotHeld(c, now) }, com.hayeco.flipspaces.core.pointer.TouchSpots.SET_HOLD_MS)
        }
    }

    private fun spotHeld(c: com.hayeco.flipspaces.core.pointer.PadControl, since: Long) {
        if (!spotSetting || spotHeldSince[c] != since) return
        val (pkg, u, v) = SpaceSession.pointerSpot ?: run {
            screen.showToast("Put the dot on the game's window first.")
            return
        }
        touchSpots.set(pkg, c, com.hayeco.flipspaces.core.pointer.TouchSpot(u, v))
        com.hayeco.flipspaces.spatial.SpaceStore(this).touchSpots = touchSpots.encode()
        buzz(PadHaptic.MODE)
        Log.i(TAG, "touch spot for ${c.label} set at %.3f, %.3f in $pkg".format(java.util.Locale.ROOT, u, v))
        screen.showToast("${c.label} set. Set the others, then turn Set touch spots off.")
    }

    override fun touchSpotSetting(): Boolean = spotSetting

    override fun touchSpotSettingChosen(on: Boolean) {
        spotSetting = on
        spotHeldSince.clear()
        if (on) sendTouches(touchPointers.cancel())
        screen.showToast(if (on) "Aim the dot at the game's button, then hold the matching control for a second." else "Spots saved for this game.")
    }

    override fun clearTouchSpots() {
        val pkg = SpaceSession.focusedApp?.first ?: return screen.showToast("Click the game's window first.")
        touchSpots.clear(pkg)
        com.hayeco.flipspaces.spatial.SpaceStore(this).touchSpots = touchSpots.encode()
        screen.showToast("Touch spots cleared for this game.")
    }

    override fun openControlPanel() {
        submit(PadSignal.Swipe(com.hayeco.flipspaces.core.pointer.SwipeDirection.DOWN))
    }

    override fun focusedTextSize(): Pair<String, Int>? = if (SpaceSession.active) SpaceSession.focusedTextSize else null

    override fun textSizeStep(steps: Int) {
        if (!SpaceSession.active) return
        SpaceSession.send(SpaceCommand.TextSize(steps))
        // The Space takes it on its next frame; the Menu shows the new size a moment later.
        window.decorView.postDelayed({ screen.refreshDrawer() }, TEXT_SIZE_REFRESH_MS)
    }

    override fun returnHome() {
        screen.closeDrawer()
        // 0.5.96 (open source audit): in our own words (the earlier line was another product's).
        screen.showDialog("Leave the trackpad for the phone's home screen? The glasses keep showing your windows.") {
            CoverTrackpad.leftByUser()
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- 0.5.17: the cover screen's buttons ----

    override fun windowHold(): WindowAction? = shownHold

    override fun coverButton(button: CoverButton, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (down) buzz(PadHaptic.COVER_BUTTON)
        val needsSpace = button.window || button == CoverButton.RECENTRE || button == CoverButton.KEYBOARD || button == CoverButton.STEADY
        if (needsSpace && !SpaceSession.active) {
            if (!down) screen.showToast("Open the Space on the glasses first.")
            return
        }
        when (button) {
            CoverButton.MENU -> if (!down) screen.openDrawer()
            CoverButton.BACK -> if (!down) backClicked()
            CoverButton.HOME -> if (down) homeDown() else homeUp()
            CoverButton.RECENTRE -> if (!down) {
                // The Space in front of the head, and the cursor in front with it.
                SpaceSession.send(SpaceCommand.Recentre)
                submit(PadSignal.Home(HomeEvent.LONG_PRESS))
            }
            CoverButton.STEADY -> if (!down) {
                com.hayeco.flipspaces.spatial.GlassesDisplayControl.steady(this, !steadyOn())
                screen.refreshCover()
            }
            CoverButton.KEYBOARD -> if (!down) screen.showText(true)
            CoverButton.MOVE -> if (down) submit(PadSignal.Window(WindowAction.MOVE))
            CoverButton.RESIZE -> if (down) submit(PadSignal.Window(WindowAction.RESIZE))
            CoverButton.FURTHER, CoverButton.CLOSER -> {
                val action = if (button == CoverButton.FURTHER) WindowAction.FURTHER else WindowAction.CLOSER
                if (down) {
                    repeatAction = action
                    submit(PadSignal.Window(action, repeat.down(now)))
                } else if (repeatAction == action) {
                    repeat.up()
                    repeatAction = null
                }
            }
            // 0.5.19: a tap (its release, so a slide along the key is not one) holds or drops the window to curve.
            CoverButton.CURVE -> if (!down) {
                submit(PadSignal.Window(WindowAction.CURVE))
                offerUnwrap()
            }
        }
    }

    /**
     * 0.5.28 (the wraparound was on for a day and every Curve swipe looked broken):
     * while the whole Space is wrapped, a Curve press or slide bends the wrap, and the trackpad shows a note
     * that turns the wraparound off with one tap. The glasses say the same ([com.hayeco.flipspaces.core.spatial.WRAP_CURVE_NOTICE]).
     */
    private fun offerUnwrap() {
        if (!prefs.wrap) return
        screen.showAction("Wraparound is on: Curve bends the whole Space.", "Tap here to unwrap") {
            if (prefs.wrap) {
                wrapLastMs = 0L
                wrapChosen(false, "the Curve note")
                screen.showToast("Wraparound off: each window curves on its own.")
            }
        }
    }

    /** 0.5.28: when the wraparound was last switched, for [WRAP_DEBOUNCE_MS]. */
    private var wrapLastMs = 0L

    /**
     * 0.5.19: a slide along a Curve key: the thumb's travel in real millimetres (the cover's compatibility
     * scale included), 30 mm the curve's whole range ([com.hayeco.flipspaces.core.spatial.CurveControl.PER_MM]).
     */
    override fun curveSlide(dyPx: Float, phase: com.hayeco.flipspaces.core.pointer.Phase) {
        if (!SpaceSession.active) {
            if (phase == com.hayeco.flipspaces.core.pointer.Phase.END) screen.showToast("Open the Space on the glasses first.")
            return
        }
        if (phase == com.hayeco.flipspaces.core.pointer.Phase.BEGIN) offerUnwrap()
        val mm = dyPx * com.hayeco.flipspaces.core.pointer.CoverScale.mmPerAppPx(resources.displayMetrics.ydpi, if (cover) coverFactor else 1f)
        submit(PadSignal.Curve(mm * com.hayeco.flipspaces.core.spatial.CurveControl.PER_MM, phase))
    }

    override fun currentCurveDefault(): Float = prefs.curveDefault

    override fun curveDefaultChosen(curve: Float) {
        prefs.curveDefault = curve
        prefs.applyCurve()
        Log.i(TAG, "new windows open with curve %d %%".format(java.util.Locale.ROOT, com.hayeco.flipspaces.core.spatial.PanelCurve.percent(curve)))
    }

    override fun currentWrap(): Boolean = prefs.wrap

    override fun wrapChosen(on: Boolean) = wrapChosen(on, "menu")

    private fun wrapChosen(on: Boolean, from: String) {
        // 0.5.28: a second tap within 1 s is ignored (a test log showed an on, off, on within 6 s).
        val now = SystemClock.uptimeMillis()
        if (wrapLastMs != 0L && now - wrapLastMs < WRAP_DEBOUNCE_MS) {
            Log.i(TAG, "wraparound tap ignored: ${now - wrapLastMs} ms after the last switch (under ${WRAP_DEBOUNCE_MS} ms)")
            return
        }
        wrapLastMs = now
        prefs.wrap = on
        prefs.applyCurve()
        Log.i(TAG, "the whole Space ${if (on) "wraps round the head" else "unwrapped"} ($from)")
        SpaceSession.send(SpaceCommand.WrapChanged)
    }

    override fun rowPoseSbsOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).rowPoseSbs

    override fun rowPoseSbsChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).rowPoseSbs = on
        Log.i(TAG, "per-row pose ${if (on) "on" else "off"} in side by side (the trackpad's Menu, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Per-row pose in 3D on." else "Per-row pose in 3D off.")
    }


    override fun edgeCheckOn(): Boolean = com.hayeco.flipspaces.spatial.EdgeCheckTest.on

    /** 0.5.27: the edge check from the Menu; drawn by the Space, nothing sent. */
    override fun edgeCheckChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.EdgeCheckTest.on = on
        Log.i(TAG, "edge check ${if (on) "on" else "off"} from the trackpad's Menu (${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Edge check on: one eye at a time, count the ticks on each side." else "Edge check off.")
    }

    override fun edgeEyes(): com.hayeco.flipspaces.core.spatial.EdgeEyes = com.hayeco.flipspaces.spatial.EdgeCheckTest.eyes

    /** 0.5.29: the edge check on one eye at a time, the other eye's half black. */
    override fun edgeEyesChosen(eyes: com.hayeco.flipspaces.core.spatial.EdgeEyes) {
        com.hayeco.flipspaces.spatial.EdgeCheckTest.eyes = eyes
        Log.i(TAG, "edge check on ${eyes.label} from the trackpad's Menu (${if (cover) "cover" else "main"} screen)")
        screen.showToast("Edge check: ${eyes.label}.")
    }

    override fun glassesDistance(): Pair<Float, String> = com.hayeco.flipspaces.spatial.SpaceStore(this).let { it.glassesDistanceM to it.glassesDistanceFrom }

    /** 0.5.29: the glasses' 3D distance as set on their own menu, set on the phone's; the Space reads it on its next frame. */
    override fun glassesDistanceChanged(m: Float) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).setGlassesDistance(m, "set on the Menu")
        com.hayeco.flipspaces.spatial.EdgeCheckTest.adjustVersion++
        Log.i(TAG, "glasses 3D distance %.1f m from the trackpad's Menu (${if (cover) "cover" else "main"} screen)".format(java.util.Locale.ROOT, m))
    }

    override fun debugInfoOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).debugInfo

    override fun debugInfoChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).debugInfo = on
        Log.i(TAG, "debug info panel: ${if (on) "on" else "off"} (the trackpad's Menu, Developer, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Debug info panel on." else "Debug info panel off.")
    }

    override fun headCursorMode(): com.hayeco.flipspaces.core.spatial.HeadCursorMode =
        com.hayeco.flipspaces.core.spatial.HeadCursorMode.of(com.hayeco.flipspaces.spatial.SpaceStore(this).headCursor)

    override fun headCursorChosen(mode: com.hayeco.flipspaces.core.spatial.HeadCursorMode) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).headCursor = mode.key
        Log.i(TAG, "head cursor: ${mode.label} (the trackpad's Menu, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(
            when (mode) {
                com.hayeco.flipspaces.core.spatial.HeadCursorMode.AUTO -> "Head cursor hides when you're not using it."
                com.hayeco.flipspaces.core.spatial.HeadCursorMode.ALWAYS -> "Head cursor always shown."
                com.hayeco.flipspaces.core.spatial.HeadCursorMode.OFF -> "Head cursor off."
            },
        )
    }

    override fun warpNoLsrOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).warpNoLsr

    override fun warpNoLsrChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).warpNoLsr = on
        Log.i(TAG, "glasses warp without LSR: ${if (on) "on" else "off"} (Developer; applied at the next switch on)")
        screen.showToast(if (on) "Warp without LSR: on next time it's switched on." else "Warp with LSR: on next time it's switched on.")
    }

    override fun warpReenterOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).warpReenter

    override fun warpReenterChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).warpReenter = on
        Log.i(TAG, "glasses warp back in after a drop: ${if (on) "on" else "off"} (Developer; applied at the next switch on)")
        screen.showToast(if (on) "Warp goes back in after a drop." else "Warp pauses at the first drop.")
    }

    override fun windowsLockedOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).windowsLocked

    override fun windowsLockedChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).windowsLocked = on
        Log.i(TAG, "Lock screens: ${if (on) "on" else "off"} (the trackpad)")
        if (on) buzz(PadHaptic.COVER_BUTTON)
        screen.showToast(if (on) "Screens locked" else "Screens unlocked")
    }

    override fun snapWindowsOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).snapWindows

    override fun snapWindowsChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).snapWindows = on
        Log.i(TAG, "snap windows: ${if (on) "on" else "off"} (the Menu)")
    }

    override fun newInstanceOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).newInstance

    override fun newInstanceChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).newInstance = on
        com.hayeco.flipspaces.SpacesState.setNewInstance(on)
        Log.i(TAG, "open a new instance if the app is already running: ${if (on) "on" else "off"} (the Menu)")
    }

    override fun presets(): List<com.hayeco.flipspaces.core.spatial.WindowPreset> = com.hayeco.flipspaces.spatial.SpaceStore(this).presets

    override fun savePreset() {
        val store = com.hayeco.flipspaces.spatial.SpaceStore(this)
        val saved = com.hayeco.flipspaces.core.spatial.WindowPresets.save(store.presets, store.layout(), System.currentTimeMillis())
        if (saved == null) {
            screen.showToast("Open some windows first.")
            return
        }
        store.presets = saved
        Log.i(TAG, "window preset saved: ${saved.first().name}, ${saved.first().apps}")
        screen.showToast("Saved as ${saved.first().name}.")
    }

    override fun openPreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset) {
        if (!SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        SpaceSession.send(com.hayeco.flipspaces.spatial.SpaceCommand.OpenPreset(preset))
        Log.i(TAG, "window preset opened from the Menu: ${preset.name}")
    }

    /** 0.5.47 (Mike: "users should be able to rename their window presets"): a name box with the keyboard up. */
    override fun renamePreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset, done: () -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(preset.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(com.hayeco.flipspaces.core.spatial.WindowPresets.MAX_NAME))
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename preset")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val store = com.hayeco.flipspaces.spatial.SpaceStore(this)
                val renamed = com.hayeco.flipspaces.core.spatial.WindowPresets.rename(store.presets, preset.id, input.text.toString())
                store.presets = renamed
                val now = renamed.firstOrNull { it.id == preset.id }?.name ?: preset.name
                Log.i(TAG, "window preset renamed: ${preset.name} -> $now")
                screen.showToast(if (now == preset.name) "Name unchanged." else "Renamed to $now.")
                done()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    override fun deletePreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset) {
        val store = com.hayeco.flipspaces.spatial.SpaceStore(this)
        store.presets = store.presets.filter { it.id != preset.id }
        screen.showToast("${preset.name} deleted.")
    }

    override fun cursorFollowsHeadOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).cursorFollowsHead

    /**
     * 0.5.51 (Mike: the cover screen never goes dark): the screen kept awake and woken by the trackpad, or left to sleep.
     * The setting is read again here, so a change applies at once. With it off the manifest's turnScreenOn is undone too.
     */
    private fun applyKeepScreenOn() {
        val on = com.hayeco.flipspaces.spatial.SpaceStore(this).trackpadKeepsScreenOn
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(on)
    }

    override fun keepsScreenOnOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).trackpadKeepsScreenOn

    override fun keepsScreenOnChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).trackpadKeepsScreenOn = on
        applyKeepScreenOn()
        Log.i(TAG, "trackpad keeps the screen on: ${if (on) "on" else "off, the screen sleeps on its own timeout"} (${if (cover) "cover" else "main"} screen)")
        buzz(PadHaptic.MODE)
    }

    override fun nativeBufferOn(): Boolean = prefs.nativeBuffer

    override fun nativeBufferChosen(on: Boolean) {
        prefs.nativeBuffer = on
        Log.i(TAG, "trackpad at the screen's own pixels: ${if (on) "on" else "off, drawn in its window and scaled by SurfaceFlinger where compat scaled"} (Developer, ${if (cover) "cover" else "main"} screen)")
        // 0.5.55: NativeFrame logs the new `trackpad buffer:` line.
        frame.nativeOn = on
    }

    override fun frameClockOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).glassesSetFrameClock

    override fun frameClockUnavailable(): String? = com.hayeco.flipspaces.spatial.GlassesPacesetter.unavailable(this)

    override fun frameClockChosen(on: Boolean) {
        val missing = frameClockUnavailable()
        // 0.5.58: OFF says what it ended in once the dump is read back, not before.
        val self = java.lang.ref.WeakReference(this)
        com.hayeco.flipspaces.spatial.GlassesPacesetter.onGiveBack = if (on) null else { line ->
            self.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { a ->
                a.screen.showToast(if (line.contains(com.hayeco.flipspaces.core.spatial.GlassesClock.STUCK_ADVICE)) "Still on the glasses' clock: unplug the glasses, or turn the cover screen off and on." else "The phone sets the frame clock again.")
                a.screen.refreshDrawer()
            }
            com.hayeco.flipspaces.spatial.GlassesPacesetter.onGiveBack = null
        }
        com.hayeco.flipspaces.spatial.GlassesPacesetter.switchChanged(this, on, GlassesAutoOpen.glassesDisplayId())
        if (missing != null) Log.w(TAG, "Glasses set the frame clock (test) turned ${if (on) "on" else "off"} without Shizuku: $missing (${if (cover) "cover" else "main"} screen)")
        screen.showToast(
            when {
                missing != null -> "$missing: the glasses can't set the frame clock."
                on -> "The glasses set the frame clock (test)."
                else -> "Giving the phone its frame clock back…"
            },
        )
        buzz(PadHaptic.MODE)
    }

    override fun frameClockState(): String? = com.hayeco.flipspaces.spatial.GlassesPacesetter.lastResult

    override fun cursorFollowsHeadChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).cursorFollowsHead = on
        Log.i(TAG, "cursor follows the head: ${if (on) "on" else "off, anchored in the room"} (${if (cover) "cover" else "main"} screen)")
        buzz(PadHaptic.MODE)
    }

    override fun coverSpatialMouseOn(): Boolean = settings.mode == PadMode.SPATIAL_MOUSE

    override fun coverSpatialMouseAvailable(): Boolean = rotation != null

    override fun coverSpatialMouseChosen(on: Boolean) {
        // 0.5.67: the same choice the open phone's page selector makes; off goes back to the trackpad (Vertical when open).
        if (on && rotation == null) return
        settings = prefs.update { it.copy(mode = if (on) PadMode.SPATIAL_MOUSE else PadMode.VERTICAL) }
        send(pad.changeMode(padMode))
        Log.i(TAG, "Spatial Mouse ${if (on) "on" else "off"} from the cover's Menu (one choice for the cover and the open phone)")
        screen.showToast(if (on) "Spatial Mouse on: point the phone's top edge, tap to click, hold Home to recenter." else "Cover trackpad back.")
        applyMode()
    }


    override fun turnDutyOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).turnDuty

    override fun turnDutyChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).turnDuty = on
        Log.i(TAG, "sharper turns (the glasses' duty lowered on fast turns): ${if (on) "on" else "off"} (Developer)")
        screen.showToast(if (on) "Sharper turns on. The picture dims a little while you turn." else "Sharper turns off.")
    }

    override fun npuHandsOn(): Boolean = com.hayeco.flipspaces.hands.HandsPrefs(this).npuHands

    override fun deviceWindowsOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).panelsOnVirtualDevice

    override fun deviceWindowsChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).panelsOnVirtualDevice = on
        com.hayeco.flipspaces.spatial.PanelHost.onVirtualDevice = on
        Log.i(TAG, "window displays on a virtual device: ${if (on) "on" else "off"} (Developer; windows opened from now on)")
        screen.showToast(if (on) "Windows opened from now on stay awake when the phone sleeps (test)." else "Windows opened from now on are plain displays again.")
    }

    override fun npuHandsChosen(on: Boolean) {
        com.hayeco.flipspaces.hands.HandsPrefs(this).npuHands = on
        // 1.1.15: applied to the running hands between two frames (1.1.14 waited for a hands start that never came).
        com.hayeco.flipspaces.hands.GlassesCameraProvider.npuHandsWanted = on
        Log.i(TAG, "hands on the NPU: ${if (on) "on" else "off"} (Developer; applied now, between two hand frames)")
        screen.showToast(if (on) "Hands on the NPU: on." else "Hands on the NPU: off.")
    }

    override fun stillLockOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).stillLock

    override fun stillLockChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).stillLock = on
        Log.i(TAG, "still lock: ${if (on) "on" else "off"} (the trackpad's Menu, Developer, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Still lock on." else "Still lock off.")
    }

    override fun drawSmoothingOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).drawSmoothing

    override fun drawSmoothingChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).drawSmoothing = on
        Log.i(TAG, "draw smoothing: ${if (on) "on" else "off"} (the trackpad's Menu, Developer, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Draw smoothing on." else "Draw smoothing off.")
    }

    override fun autoGlassesViewOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).autoGlassesView

    override fun autoGlassesViewChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).autoGlassesView = on
        Log.i(TAG, "switch the glasses' 3D / 2D: ${if (on) "on" else "off"} (the trackpad's Menu, Developer, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Pocket XR switches the glasses' 3D / 2D." else "The glasses' 3D / 2D is left to their own control.")
    }

    override fun factoryCalibrationOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).factoryCalibration

    override fun factoryCalibrationChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).factoryCalibration = on
        com.hayeco.flipspaces.spatial.HeadTracker.applySettings(this)
        Log.i(TAG, "factory calibration: ${if (on) "on" else "off"} (the trackpad's Menu, Developer, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Factory calibration on." else "Factory calibration off.")
    }

    override fun distanceFixOn(): Boolean = com.hayeco.flipspaces.spatial.SpaceStore(this).distanceFix

    override fun distanceFixChosen(on: Boolean) {
        com.hayeco.flipspaces.spatial.SpaceStore(this).distanceFix = on
        com.hayeco.flipspaces.spatial.EdgeCheckTest.adjustVersion++
        Log.i(TAG, "each eye centred for the glasses' 3D distance: ${if (on) "on" else "off"} (the trackpad's Menu, ${if (cover) "cover" else "main"} screen)")
        screen.showToast(if (on) "Each eye centred for the glasses' 3D distance." else "Distance correction off.")
    }

    override fun pictureAdjust(): com.hayeco.flipspaces.core.spatial.PictureAdjust = com.hayeco.flipspaces.spatial.SpaceStore(this).pictureAdjust

    /**
     * 0.5.27 (Mike: SBS cut off on the left at Display size 1.0): a change of the per-eye picture adjust from either
     * trackpad's Menu, saved for the glasses the Space last ran on; the Space reads it on its next frame, applies it and
     * logs the values. Nothing is sent to the glasses.
     */
    override fun pictureAdjustChanged(next: com.hayeco.flipspaces.core.spatial.PictureAdjust, what: String) {
        val store = com.hayeco.flipspaces.spatial.SpaceStore(this)
        store.pictureAdjust = next
        com.hayeco.flipspaces.spatial.EdgeCheckTest.adjustVersion++
        Log.i(TAG, "picture adjust from the trackpad's Menu (${if (cover) "cover" else "main"} screen), $what: ${if (next.neutral) "none" else next.line()}")
    }

    override fun coverControlsChosen(side: StripSide) {
        settings = prefs.update { it.copy(coverControls = side) }
        coverLayout = null
        updateCoverLayout()
    }

    /** Unlock the phone where Android allows it: the keyguard's own prompt (its PIN on the cover screen if it is secure). */
    override fun exitApp() {
        AppExit.exit(this, "the trackpad Menu")
    }

    override fun unlockPhone() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            screen.showToast("The phone is not locked.")
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i(TAG, "keyguard dismissed from the trackpad")
            }

            override fun onDismissCancelled() {
                Log.i(TAG, "keyguard dismiss cancelled")
            }

            override fun onDismissError() {
                Log.w(TAG, "keyguard dismiss refused")
                runOnUiThread { screen.showToast("Unlock the phone from its own lock screen.") }
            }
        })
    }

    // ---- 0.5.17: text from the cover screen ----

    override fun typing(on: Boolean) {
        // 0.5.82: the field closing sends what is still held, without Enter.
        if (!on && typing) flushText(enter = false)
        typing = on
        updateFocusable()
    }

    /*
     * 0.5.82 (Mike: in the "Type here, it goes to the window" field, "everytime i press a key it closes keyboard"): each
     * typed key went to the window's display at once, and Android moves a display that a key targets to the top
     * (PhoneWindowManager: "Attempting to move non-focused display 937 to top because a key is targeting it"), which took
     * focus from the trackpad and closed its keyboard after every key. Text for a window is now held in the field (Delete
     * edits it there) and sent in one go on Enter, then Enter itself; the launcher's search still gets each key at once,
     * as it goes to the Space, not to a display.
     */
    private val heldText = StringBuilder()

    private fun flushText(enter: Boolean) {
        if (heldText.isEmpty() && !enter) return
        if (!SpaceSession.active) {
            heldText.clear()
            return
        }
        if (heldText.isNotEmpty()) {
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(heldText.toString().toCharArray())
            if (events == null) {
                screen.showToast("These characters cannot be typed into the window.")
            } else {
                events.forEach { SpaceSession.send(SpaceCommand.PanelKey(it.action, it.keyCode, it.metaState, it.repeatCount, it.downTime)) }
            }
            Log.i(TAG, "cover keyboard: ${heldText.length} characters sent to the focused window${if (enter) ", then Enter" else ""}")
            heldText.clear()
        }
        if (enter) key(KeyEvent.KEYCODE_ENTER)
    }

    override fun textChanged(old: String, new: String) = CoverText.diff(old, new).forEach(::typeOp)

    override fun textEnter() = typeOp(TextOp.Enter)

    /** One text step to the Space: the launcher's search when it is open, else keys to the focused window. */
    private fun typeOp(op: TextOp) {
        if (!SpaceSession.active) {
            Log.i(TAG, "cover keyboard: ${op.javaClass.simpleName.lowercase()} not sent, the Space is not up")
            return
        }
        val launcher = SpaceSession.state.value.launcherOpen
        // 0.5.18: one line per Keyboard field send, its size only (never the text).
        Log.i(
            TAG,
            "cover keyboard: " + when (op) {
                is TextOp.Type -> "%d characters typed".format(java.util.Locale.ROOT, op.text.length)
                TextOp.Delete -> "delete"
                TextOp.Enter -> "enter"
            } + if (launcher) " into the launcher's search" else " for the focused window (held until Enter)",
        )
        when (op) {
            is TextOp.Type -> if (launcher) {
                op.text.forEach { SpaceSession.send(SpaceCommand.LauncherKey(it)) }
            } else {
                // 0.5.82: held until Enter ([flushText]).
                heldText.append(op.text)
            }
            TextOp.Delete -> if (launcher) {
                SpaceSession.send(SpaceCommand.LauncherKey(null))
            } else if (heldText.isNotEmpty()) {
                heldText.setLength(heldText.length - 1)
            } else {
                key(KeyEvent.KEYCODE_DEL)
            }
            TextOp.Enter -> if (!launcher) flushText(enter = true)
        }
    }

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        SpaceSession.send(SpaceCommand.PanelKey(KeyEvent.ACTION_DOWN, code, 0, 0, t))
        SpaceSession.send(SpaceCommand.PanelKey(KeyEvent.ACTION_UP, code, 0, 0, t))
    }

    // ---- the screen-lock overlay ----

    override fun unlockTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> unlock.down(event.rawX, event.rawY, event.pointerCount, event.eventTime)
            MotionEvent.ACTION_MOVE -> unlock.move(event.rawX, event.rawY, event.pointerCount, event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> unlock.up()
        }
    }

    private fun unlocked() {
        buzz(PadHaptic.UNLOCK)
        val wasPocket = screen.pocket
        screen.showLock(false)
        if (wasPocket) pocketBrightness(false)
    }

    /**
     * 1.2.3 (issue 52): Pocket mode: the lock overlay at once, black ([com.hayeco.flipspaces.core.pointer.PocketMode]), the
     * screen at its lowest brightness and kept on, so the glasses keep their picture and stray touches do nothing.
     */
    override fun pocketMode() {
        if (!SpaceSession.active) {
            screen.showToast("Open the Space on the glasses first.")
            return
        }
        screen.showLock(true, pocket = true)
        pocketBrightness(true)
        Log.i(TAG, "Pocket mode on: the screen black at its lowest brightness and kept on; hold the lock to leave")
    }

    private fun pocketBrightness(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) com.hayeco.flipspaces.core.pointer.PocketMode.BRIGHTNESS else com.hayeco.flipspaces.core.pointer.PocketMode.NO_OVERRIDE
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else applyKeepScreenOn()
        if (!on) Log.i(TAG, "Pocket mode off: the phone's own brightness again")
    }

    // ---- Spatial Mouse: the phone's rotation vector aims the ray ----

    private val aimListener = object : SensorEventListener {
        private val matrix = FloatArray(9)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val (yaw, pitch) = PhoneAim.angles(matrix, aimRotation) ?: return
            submit(PadSignal.Aim(yaw, pitch))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 1.1.9 (issue 33): the screen's rotation now (Display.getRotation), so the aim is its top edge as it is held, in
     * portrait, landscape and landscape turned, whether the app or the system turned it. A half turn (90 to 270) is no
     * configuration change, so the display's own changes are watched while Spatial Mouse aims.
     */
    @Volatile private var aimRotation = 0

    private val rotationWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val d = display ?: return
            if (d.displayId == displayId) aimRotation = d.rotation
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun updateSensor() {
        val want = resumed && padMode == PadMode.SPATIAL_MOUSE && rotation != null
        if (want == rotationOn) return
        rotationOn = want
        val displays = getSystemService(DisplayManager::class.java)
        if (want) {
            aimRotation = display?.rotation ?: 0
            displays?.registerDisplayListener(rotationWatch, null)
            sensors?.registerListener(aimListener, rotation, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors?.unregisterListener(aimListener)
            displays?.unregisterDisplayListener(rotationWatch)
        }
    }

    companion object {
        private const val TAG = "FlipSpaces/Trackpad"

        /** 1.2.3 (issue 54): after a switch the Air's mode is read again this long later, once its screen is back. */
        private const val AIR_REREAD_MS = 4000L

        /** 1.2.3 (issue 57): the Menu reads the new text size back this long after a step. */
        private const val TEXT_SIZE_REFRESH_MS = 200L

        /** 0.5.19: a touch or tick that keeps the UI thread this long is logged. */
        private const val SLOW_UI_MS = 8L

        /** 0.5.21: the thin strip's coast steps, one a 60 Hz frame. */
        private const val COAST_FRAME_MS = 16L

        /** The gesture button shows its loading dots this long at most while hands start. */
        private const val GESTURE_LOADING_MS = 10_000L

        /** 0.5.28: a second wraparound tap within this long is ignored. */
        private const val WRAP_DEBOUNCE_MS = 1_000L

        /** 0.5.17 cover screen: set when [CoverTrackpad] started the trackpad by itself. */
        const val EXTRA_AUTO = "com.hayeco.flipspaces.extra.TRACKPAD_AUTO"

        /** 0.5.101: open with the Menu out (Settings' Trackpad row). */
        const val EXTRA_MENU = "com.hayeco.flipspaces.extra.TRACKPAD_MENU"

        /** 1.1.16: open with the glasses setup check (Settings' "Glasses setup check"). */
        const val EXTRA_GLASSES_CHECK = "com.hayeco.flipspaces.extra.GLASSES_CHECK"

        /** 1.1.15: the notification permission's ask, with the first update banner. */
        private const val REQUEST_NOTIFICATIONS = 1115

        /** 1.1.9: how often the plugged-in glasses and the Anchor signal are looked at. */
        private const val GLASSES_CHECK_MS = 1_000L

        /** 1.1.9: the Anchor card was offered in this run of the app (the trackpad may open more than once). */
        @Volatile private var anchorAskedThisRun = false

        /** 1.1.16: models whose glasses check opened in this run of the app, so a failed one is asked again only on the next. */
        private val checkShownThisRun: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

/**
 * A 1 px transparent window over the trackpad (0.4.8; 0.5.55: left out of SurfaceFlinger's composition by a transparent
 * region hint, see [show]) that may take focus but no touches
 * (FLAG_NOT_TOUCHABLE, so touches reach the trackpad below and, it not being focusable, never
 * move focus to the phone) and no IME (FLAG_ALT_FOCUSABLE_IM). It is the phone's focused window
 * while the Space is up, so a key sent to the phone is delivered and answered at once: Back goes
 * to [onBack], once per press ([PhoneBack]), and every other key is left unhandled.
 *
 * Back arrives as a key on Android 11 and 12, and on 13 and later unless predictive Back is on;
 * with it on (Android 16, targetSdk 36) the system calls the window's [OnBackInvokedCallback]
 * instead. Both lead to [onBack].
 */
private class KeySink(private val activity: Activity, private val onBack: () -> Unit) {
    private val keys = PhoneBack()
    private var view: View? = null
    private var callback: Any? = null

    fun show() {
        if (view != null) return
        val v = object : View(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                when (keys.key(event.keyCode, event.action, event.repeatCount, event.downTime, event.isCanceled)) {
                    PhoneKeyRoute.SPACE_BACK -> {
                        Log.i(TAG, "phone Back (key) sent to the Space")
                        onBack()
                        true
                    }
                    PhoneKeyRoute.CONSUME -> true
                    PhoneKeyRoute.PASS -> false
                }
        }
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        // The sink holds the phone's focus while the Space is up, so it is where a mouse's capture is asked for (MouseRoute).
        com.hayeco.flipspaces.pointer.MouseRoute.attach(v)
        // 0.5.55 (the 1x1 "trackpad keys" layer was one of the cover's three CLIENT layers): the
        // sink draws nothing and says its whole window is transparent (ViewRootImpl's transparent region hint), so
        // SurfaceFlinger leaves the layer out of composition. It stays shown, alpha 1, with its buffer: hidden or alpha 0,
        // SurfaceFlinger would mark its input window NOT_VISIBLE and it could no longer take focus, which is all it is for.
        v.setWillNotDraw(true)
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                attached.parent?.requestTransparentRegion(attached)
            }

            override fun onViewDetachedFromWindow(detached: View) = Unit
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) = registerBack(attached)
                override fun onViewDetachedFromWindow(detached: View) = unregisterBack(detached)
            })
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Pocket XR trackpad keys"
        }
        try {
            activity.windowManager.addView(v, params)
            view = v
            Log.i(TAG, "key sink up: phone keys reach the trackpad while the Space is up (its 1 px window marked transparent, left out of composition)")
        } catch (e: RuntimeException) {
            Log.w(TAG, "key sink not added: ${e.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            activity.windowManager.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(TAG, "key sink not removed: ${e.message}")
        }
    }

    private fun registerBack(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || callback != null) return
        val dispatcher = v.findOnBackInvokedDispatcher() ?: return
        val cb = OnBackInvokedCallback {
            Log.i(TAG, "phone Back (callback) sent to the Space")
            onBack()
        }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        callback = cb
    }

    private fun unregisterBack(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val cb = callback as? OnBackInvokedCallback ?: return
        callback = null
        v.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb)
    }

    companion object {
        private const val TAG = "FlipSpaces/Trackpad"
    }
}
