package com.hayeco.flipspaces.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.hands.HandCalibration
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.BackDecision
import com.hayeco.flipspaces.core.spatial.BackGuard
import com.hayeco.flipspaces.core.spatial.BlackFrameDetector
import com.hayeco.flipspaces.core.spatial.CardAction
import com.hayeco.flipspaces.core.spatial.EmptyPanelDetector
import com.hayeco.flipspaces.core.spatial.FocusKeeper
import com.hayeco.flipspaces.core.spatial.HeadView
import com.hayeco.flipspaces.core.spatial.HoverGate
import com.hayeco.flipspaces.core.spatial.KeyRouting
import com.hayeco.flipspaces.core.spatial.KeyTarget
import com.hayeco.flipspaces.core.spatial.Mat4
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.ModeLogGate
import com.hayeco.flipspaces.core.spatial.PanelCard
import com.hayeco.flipspaces.core.spatial.PanelCulling
import com.hayeco.flipspaces.core.spatial.PanelCurve
import com.hayeco.flipspaces.core.spatial.PanelFocus
import com.hayeco.flipspaces.core.spatial.PanelResizeGate
import com.hayeco.flipspaces.core.spatial.PanelInputMapper
import com.hayeco.flipspaces.core.spatial.PanelMips
import com.hayeco.flipspaces.core.spatial.PanelMotion
import com.hayeco.flipspaces.core.spatial.PanelRecovery
import com.hayeco.flipspaces.core.spatial.PanelState
import com.hayeco.flipspaces.core.spatial.PanelZone
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.LookTween
import com.hayeco.flipspaces.core.spatial.PointerSettings
import com.hayeco.flipspaces.core.spatial.ProtectedOffer
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.SpaceAction
import com.hayeco.flipspaces.core.spatial.SpaceLauncher
import com.hayeco.flipspaces.core.spatial.SpaceLayout
import com.hayeco.flipspaces.core.spatial.SpacePanel
import com.hayeco.flipspaces.core.spatial.WindowControls
import com.hayeco.flipspaces.core.spatial.SpacePerf
import com.hayeco.flipspaces.core.spatial.SpaceViewport
import com.hayeco.flipspaces.core.spatial.SpatialMode
import com.hayeco.flipspaces.core.spatial.SpatialModeChooser
import com.hayeco.flipspaces.core.spatial.SpatialPointer
import com.hayeco.flipspaces.core.spatial.StereoLayout
import com.hayeco.flipspaces.core.spatial.StereoMode
import com.hayeco.flipspaces.core.spatial.ThermalGuard
import com.hayeco.flipspaces.core.spatial.TouchPhase
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import com.hayeco.flipspaces.hands.HandFeed
import com.hayeco.flipspaces.hands.HandsPrefs
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.platform.ShizukuBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * One app panel's GL side: the frames its virtual display renders ([PanelSource], an ImageReader
 * drained by the [PanelDrain] thread since 0.5.3). GL thread only.
 */
private class PanelGl(var panel: SpacePanel, private val frames: PanelDrain, broad: Boolean = false, videoSmallestDp: Float? = null) {
    var src: PanelSource
        private set
    val surface: Surface get() = src.surface

    /** A frame was copied and can be drawn. */
    val hasFrame: Boolean get() = drawn.copies > 0
    var width: Int
    var height: Int

    /** 0.5.21: the display's density, which a resize changes too ([com.hayeco.flipspaces.core.spatial.PanelDisplaySize]). */
    var dpi: Int

    /** 0.5.21: Wide view under the window ([com.hayeco.flipspaces.core.spatial.WideView]). */
    val broad = IconTexture()

    /** 0.5.24: Fit video beside it ([com.hayeco.flipspaces.core.spatial.VideoFit]), and the window's angles before it, for its second press. */
    val fit = IconTexture()

    /** 0.5.37: the Immersive button under the window. */
    val immersive = IconTexture()

    /** 0.5.85: the Switch app button, and its card while it is up ([PanelCard.SwitchApp]) with its app tiles. */
    val switchApp = IconTexture()
    var switcher: PanelCard.SwitchApp? = null
    val cardTiles = HashMap<Int, LabelTexture>()
    var beforeFit: Pair<Float, Float>? = null

    /** 0.5.21: what the app asks Android for (read every few seconds through the panel service), and when it was asked. */
    @Volatile var appShape: com.hayeco.flipspaces.core.spatial.AppShape? = null
    var shapeAskedMs = 0L
    @Volatile var shapePending = false

    /** 0.5.21: the window shape last set for what the app asks ([com.hayeco.flipspaces.core.spatial.WideView.Shape]), so it is set once a change. */
    var shapeSet: com.hayeco.flipspaces.core.spatial.WideView.Shape? = null

    /** 0.5.21: a notice on the window (Wide view's tip, or why it cannot turn off) until [noticeUntilMs]. */
    var notice: String? = null
    var noticeUntilMs = 0L

    /** 0.5.21: when Wide view was last pressed, for the line on whether the app filled the window. */
    var broadToggledMs = 0L

    /** 0.5.24: when Fit video was last pressed (the same 1 s debounce as Wide view). */
    var fitToggledMs = 0L

    /** 0.5.23: a released resize waits for the app's own size to be read ([com.hayeco.flipspaces.core.spatial.PanelFit]); the nudges sent since. */
    var fitArmed = false
    var fitNudges = 0

    /** Written by the panel host thread's callbacks too, so volatile. */
    @Volatile var state: PanelState = PanelState.Waiting("Waiting for Shizuku")

    /** False after a failure or a lost service: open the next try on a new Surface. */
    @Volatile var surfaceFresh = true

    /** Closed while its display was still opening: a late display is released, not shown. */
    @Volatile var removed = false
    var downTime = 0L
    /** 0.4.5: the mouse hover Android has for this panel, so its exits are never held back. */
    val hover = HoverGate()
    val placeholder = LabelTexture(960, 540)

    /** 0.5.56: the rate the window is asked for and copied at now ([com.hayeco.flipspaces.core.spatial.PanelRatePolicy]). */
    var rate: com.hayeco.flipspaces.core.spatial.PanelRatePolicy.Rate? = null

    /** 0.5.20: the window's floating round buttons (no title bar): Back, Reset and Close ([WindowControls]). */
    val back = IconTexture()
    val banner = LabelTexture(1024, 160)

    /** 0.5.17: the window's Reset and Close, and the held window's guide ("FARTHER", ...). */
    val reset = IconTexture()
    val close = IconTexture()

    /** 0.5.21 (Mike: back, close and refresh buttons): Refresh reloads the window's app. */
    val refresh = IconTexture()

    /** 1.1.15: Lock screens' small lock, where the resize handle is while the screens are locked. */
    val lockIcon = IconTexture()

    /** 1.1.15: the last press on the window's app (uptime), for [com.hayeco.flipspaces.core.spatial.LaunchDisplay]. */
    var pressedMs: Long? = null
    val guide = LabelTexture(512, 64)
    val holdFrame = com.hayeco.flipspaces.core.spatial.HoldFrameFade()

    /** 0.5.69: each control's hover light, and the window's focus, eased by the frame clock ([LookTween]). */
    val controlLooks = java.util.EnumMap<WindowControls.Control, LookTween>(WindowControls.Control::class.java)
    val focusLook = LookTween(Look.ENTER_MS, Look.EXIT_MS)

    /** 0.5.75: the pointer on the window's grab band round its outside ([com.hayeco.flipspaces.core.spatial.PanelHit.frame]). */
    val frameLook = LookTween(Look.HOVER_MS, Look.EXIT_MS)
    val hoverLook = LookTween(Look.HOVER_MS, Look.EXIT_MS)

    fun controlLook(c: WindowControls.Control): LookTween = controlLooks.getOrPut(c) { LookTween() }

    /** 0.5.74: this frame's controls layout and capsules, from the first eye's pass ([SpaceRenderer.drawPanel]). */
    var controlLayout: WindowControls.Layout? = null
    var controlCapsules: List<WindowControls.Capsule> = emptyList()

    /** The last black check sample the render thread looked at (the frame thread takes them). */
    var lumaSeen = 0

    /** In view at the last frame, and the frame thread's copy count then (render thread). */
    var wasVisible = false
    var copiesSeen = 0

    /** 0.5.15: a new copy this frame ([SpaceRenderer.preparePanels]), for the perf counts. */
    var freshThisFrame = false
    val black = BlackFrameDetector()

    /** The protected app offer on the panel (0.4.1); set from the panel host thread too. */
    @Volatile var offer: String? = null

    /** The user kept the panel after the offer: no more offers for it. */
    var offerDismissed = false

    /** A press began on the offer or a card: its moves and lift are not the app's. */
    var swallowing = false

    /** 0.4.2: notices the app closing (no task left on the display). GL thread. */
    val empty = EmptyPanelDetector()
    var tasksCheckedMs = 0L
    @Volatile var tasksPending = false

    /** 1.1.9: the window this one's app moved to when it went empty ([com.hayeco.flipspaces.core.spatial.SingleWindowApp]), else null. */
    var movedTo: Int? = null

    /** 1.1.10: whether the Menu's Open a new instance was on when this window's app moved ([com.hayeco.flipspaces.core.spatial.SingleWindowApp.card]). */
    var movedSwitchOn = false

    /** 1.1.9: this window for [com.hayeco.flipspaces.core.spatial.SingleWindowApp]. */
    fun window(): com.hayeco.flipspaces.core.spatial.SingleWindowApp.Window {
        val showing = state as? PanelState.Showing
        return com.hayeco.flipspaces.core.spatial.SingleWindowApp.Window(panel.id, panel.packageName, empty.launchedAtMs, showing != null && !showing.appClosed)
    }

    /** The Back question (0.4.2), while it is up; App closed comes from [state]. */
    var ask: PanelCard? = null

    /** Mike chose Back anyway once: Back now goes straight to the app. */
    var backAllowed = false
    val cardButtons = List(2) { LabelTexture(CARD_BUTTON_W, CARD_BUTTON_H) }

    /** 0.5.69: the card's title and detail lines, its fade and rise, and each button's hover ([SpaceRenderer.drawCard]). */
    val cardTitle = LabelTexture(CARD_LINE_W, CARD_LINE_H)
    val cardDetail = LabelTexture(CARD_LINE_W, CARD_LINE_H)
    val cardLook = LookTween(Look.ENTER_MS, Look.EXIT_MS)
    val cardButtonLooks = List(2) { LookTween() }
    val cardTileLooks = List(PanelCard.SwitchApp.MAX_APPS) { LookTween() }

    val displayId: Int? get() = (state as? PanelState.Showing)?.displayId
    val status: String get() = PanelRecovery.label(state, panel.label)

    init {
        // 0.5.21: the app's own size, the window's shape, and Wide view's density ([PanelDisplaySize]).
        val s = com.hayeco.flipspaces.core.spatial.PanelDisplaySize.of(panel, broad, videoSmallestDp = videoSmallestDp)
        width = s.widthPx
        height = s.heightPx
        dpi = s.dpi
        src = newSource()
    }

    private fun newSource(): PanelSource = PanelSource(width, height).also { frames.add(it) }

    /** A new ImageReader and Surface, so a retry never reuses a producer a dead display may still hold. */
    fun renewSurface() {
        previous?.let { frames.remove(it) }
        previous = null
        frames.remove(src)
        src = newSource()
        black.reset()
        lumaSeen = 0
        offer = null
        surfaceFresh = true
    }

    /**
     * 0.5.3: the reader before a resize, drawn until the new one has a frame, then let go of.
     * Both keep being drained meanwhile, so the display never waits on either.
     */
    var previous: PanelSource? = null
        private set

    /**
     * The source to draw: the current one once it has a frame, else the one before a resize. 0.5.45: while the one before
     * is kept ([dropPrevious]), that one.
     */
    val drawn: PanelSource get() = previous?.takeIf { it.copies > 0 } ?: src.takeIf { it.copies > 0 } ?: src

    /** 0.5.45: when the current reader took its first frame after a resize (0 before), for [dropPrevious]. */
    private var firstCopyMs = 0L

    /** 0.5.45: the picture from before a resize, fading out over the new one, and when the fade began. */
    var fadingOut: PanelSource? = null
        private set
    var fadeStartMs = 0L
        private set

    /** 0.5.45: how far the new picture has faded in, 0 to 1 (1 with no fade). */
    fun fadeIn(nowMs: Long): Float =
        if (fadingOut == null) 1f else ((nowMs - fadeStartMs).toFloat() / RESIZE_FADE_MS).coerceIn(0f, 1f)

    /**
     * The panel's size changed (the display itself is resized by the gate). An ImageReader's
     * buffers keep the size it was made with (0.5.2 resized its SurfaceTexture's instead), so a
     * new reader at the new size takes over; the caller points the display at [surface].
     *
     * 0.5.8: resizes now come during a stretch too. The reader kept for drawing is the one with
     * a frame: if the current one has none yet (the gate's settle time ran out), it is the one
     * let go of and the older one stays drawn, so the panel never falls back to its placeholder.
     * Both keep being drained until removed, so the display never waits on either.
     */
    fun resize(w: Int, h: Int) {
        width = w
        height = h
        val keep = previous?.takeIf { src.copies == 0 && it.copies > 0 }
        if (keep != null) {
            frames.remove(src)
        } else {
            previous?.let { frames.remove(it) }
            previous = src
        }
        src = newSource()
        firstCopyMs = 0L
    }

    /** 0.5.8: the app drew at the size last sent (the newest reader took an image), for the resize gate. */
    val drewAtSize: Boolean get() = src.mailbox.offered > 0

    /**
     * The new reader has a frame: the one from before the resize goes. GL thread.
     *
     * 0.5.45 (Mike: resizing "flickers for just a bit ... it should have a better smooth animation"): an app's first
     * frames at a new size are often its relayout in progress (a blank or half drawn screen), which 0.5.44 showed at
     * once. Now the picture from before stays until the new reader has [RESIZE_SETTLE_COPIES] frames or
     * [RESIZE_SETTLE_MS] passed since its first, then the new one fades in over it ([RESIZE_FADE_MS]).
     */
    fun dropPrevious(nowMs: Long) {
        fadingOut?.let { if (nowMs - fadeStartMs >= RESIZE_FADE_MS) { frames.remove(it); fadingOut = null } }
        val old = previous ?: return
        if (src.copies == 0) return
        if (firstCopyMs == 0L) firstCopyMs = nowMs
        if (src.copies < RESIZE_SETTLE_COPIES && nowMs - firstCopyMs < RESIZE_SETTLE_MS) return
        previous = null
        fadingOut?.let { frames.remove(it) }
        if (old.copies > 0) {
            fadingOut = old
            fadeStartMs = nowMs
        } else {
            frames.remove(old)
        }
    }

    fun release() {
        previous?.let { frames.remove(it) }
        previous = null
        fadingOut?.let { frames.remove(it) }
        fadingOut = null
        frames.remove(src)
        placeholder.release()
        back.release()
        banner.release()
        reset.release()
        close.release()
        refresh.release()
        lockIcon.release()
        broad.release()
        fit.release()
        immersive.release()
        switchApp.release()
        cardTiles.values.forEach { it.release() }
        guide.release()
        cardTitle.release()
        cardDetail.release()
        cardButtons.forEach { it.release() }
    }
}

/**
 * Draws the Space: app panels around the user, their title bars and resize handles, an
 * information panel, the launcher with its home button, and the pointer, from the head pose.
 * Owns the layout, the launcher and the pointer; the phone and input reach it through
 * [SpaceSession].
 *
 * 0.4.0: each panel has a [PanelState]. A failed open is retried with backoff
 * ([PanelRecovery], three tries), and when the panel service goes away every display id is
 * dropped and every saved panel is reopened on the new connection.
 *
 * 0.4.1: panel input beyond touches ([PanelInputMapper]: hover, wheel, app pinch, long press,
 * Back, keyboard keys) and explicit focus for the panel last pressed; frames copied into
 * mipmapped textures; panels out of view are neither latched nor drawn ([PanelCulling]) and a
 * thermal guard caps how often the others are ([ThermalGuard]); a panel that stays black while
 * its app draws offers to open the app as a DeX window; Android's pause, resume and stop of
 * each display are followed.
 *
 * 0.4.2: panels are drawn and hit in one order ([com.hayeco.flipspaces.core.spatial.PanelOrder])
 * and new ones never open on top of another; the open launcher fits the view and covers the
 * panels (an opaque backdrop, no hover behind it); a panel whose app closed shows App closed
 * with Reopen and Close, and Back at an app's last activity asks first; mouse hover and wheel
 * land inside the app's content and every scroll is logged (FlipSpaces/Input); the mode is
 * logged only when it really changes; a trackpad touch gives focus back to the focused panel.
 */
class SpaceRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val tag = "FlipSpaces/Space"
    private val store = SpaceStore(context)
    private var layout = store.layout()

    /** 0.5.19: the wraparound setting was applied to the loaded layout (the curve settings are read from the trackpad's prefs first). */
    private var wrapSynced = false.also { com.hayeco.flipspaces.TrackpadPrefs(context).applyCurve() }
    private val panels = LinkedHashMap<Int, PanelGl>()
    private var frustum: ViewFrustum = store.optics().pictureFrustum(1920, 1080)

    /** 0.5.9: mono, or side by side 3D when the surface is 32:9 ([StereoLayout]); the eyes to draw. GL thread. */
    private var stereo = StereoMode.MONO
    private var eyes = StereoLayout.eyes(StereoMode.MONO, 1920, 1080)
    private val pointer = SpatialPointer(frustum, PointerSettings(handsSteer = store.handsSteer))

    /** The viewport the projection and pointer were last built for. GL thread only. */
    private var viewport = SpaceViewport(frustum.widthPx, frustum.heightPx)

    /** The display the Space is on, from the UI thread; hand positions arrive in its pixels. */
    @Volatile private var displayWidth = 0
    @Volatile private var displayHeight = 0
    private val perf = SpacePerf()

    /** 0.5.17: the prepare pass's copies, skips and mip chains for each perf line. */
    private val prepareCounts = com.hayeco.flipspaces.core.spatial.PanelPrepareCounts()

    /** 0.5.21: Wide view's per app memory ([com.hayeco.flipspaces.core.spatial.WideViewStore]), saved in the Space's settings. */
    private val wideView = com.hayeco.flipspaces.core.spatial.WideViewStore(store.wideView)

    /** 0.5.24: each app's own density setting (Fit video, [com.hayeco.flipspaces.core.spatial.AppDensityStore]), saved in the Space's settings. */
    private val appDensity = com.hayeco.flipspaces.core.spatial.AppDensityStore(store.appDensity)

    /** 0.5.24: the saved windows were capped once this run ([capReopened]). */
    private var reopenCapped = false
    private var seeThrough = store.seeThrough
    private var manualYaw = 0f
    private var mode: ModeChoice? = null
    private var shizuku = ShizukuStatus.NOT_INSTALLED
    private var modeCheckedMs = 0L
    private var publishedMs = 0L
    private var problem: String? = null
    private var info: LabelTexture? = null
    private val infoPanel = SpacePanel(0, "Pocket XR", "", "", yawDeg = 0f, pitchDeg = -30f, distanceM = 1.6f, widthDeg = 34f, heightDeg = 15f)

    /** Display ids still open, readable from any thread for teardown. */
    private val openDisplays = ConcurrentHashMap.newKeySet<Int>()

    private val launcher = SpaceLauncher().apply {
        setFavourites(store.favourites)
        setRecents(store.recents)
    }

    /**
     * 0.5.25 (Mike: the Android XR launcher): the launcher itself
     * ([com.hayeco.flipspaces.core.spatial.XrLauncher], drawn by [LauncherGl]). [launcher] keeps the
     * closed Apps button and the open flag the pointer and keys look at; the two open and close together
     * ([setLauncherOpen]). The saved favourites are its pinned apps.
     */
    private val xr = com.hayeco.flipspaces.core.spatial.XrLauncher().apply { setPinned(store.favourites) }
    private val launcherGl = LauncherGl(context)

    /** 0.5.25: windows opened from the launcher and when, for their 500 ms fade and scale in. */
    private val windowOpenMs = HashMap<Int, Long>()
    private val tileTextures = HashMap<String, LabelTexture>()
    private var homeTexture: LabelTexture? = null

    /** The head pose of the frame being drawn, for placing panels in front of the gaze. */
    private var head = Quat.IDENTITY

    /** The panel host generation the open panels' displays belong to; -1 while none is open. */
    @Volatile private var liveGeneration = -1

    /** Logs the mode only when it or its reasons really change (0.4.2). */
    private val modeGate = ModeLogGate()

    /** Gives focus back after the phone's trackpad is touched (0.4.2). */
    private val focusKeeper = FocusKeeper(minGapMs = 1000)

    /** Work from the panel host thread, run at the start of the next frame on the GL thread. */
    private val onGlQueue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    private fun onGl(work: () -> Unit) {
        onGlQueue += work
    }

    private val input = PanelInputMapper()

    /** The panel the pointer last pressed: keys, Back and the phone's keyboard go to it. */
    private var focusedPanelId: Int? = null

    /** 0.5.28: the Space idles while the glasses lie off the head ([com.hayeco.flipspaces.core.spatial.OffHeadIdle]). */
    private val offHead = com.hayeco.flipspaces.core.spatial.OffHeadIdle()

    /** Android's thermal status (PowerManager), from the activity's listener. */
    @Volatile private var thermalStatus = ThermalGuard.NONE
    private var loggedThermal = ThermalGuard.NONE

    /** The display the Space is on, for giving it focus back while the launcher is open. */
    @Volatile private var spaceDisplayId = -1

    /** Panels in view at the last frame, for the information panel. */
    private var inView = 0

    /** 0.5.3: the thread that takes the panel displays' images, with no GL (PanelFrameThread.kt). */
    @Volatile private var frames: PanelDrain? = null

    /** 0.5.3: when the last frame was finished, for the watchdog; and the watchdog itself. */
    @Volatile private var lastFrameEndMs = SystemClock.uptimeMillis()
    @Volatile private var watchdog: SpaceWatchdog? = null

    /** Displays the watchdog released in a stall; their panels reopen when it says frames run again. */
    private val stallReleased = ConcurrentHashMap.newKeySet<Int>()

    /**
     * 0.5.2: panel displays are resized to the last size asked; 0.5.8: live during a stretch, at
     * most every 150 ms and never before the app drew at the size before, plus once at release.
     */
    // 0.5.45: no live resizes; the app is laid out once at the release and its new picture fades in ([PanelGl.dropPrevious]).
    private val resizeGate = PanelResizeGate(PanelResizeGate.LIVE_GAP_MS, takeLive = false)

    /** 0.5.9: which eye is being drawn (0 in mono); panel copies and counts happen in pass 0 only. */
    private var eyePass = 0

    /**
     * 0.5.10: when each frame reaches the eyes ([FramePacer]); the head pose is predicted to that
     * time, taken as late as possible (just before drawing) and the same for both eyes, and the frame
     * is stamped with it (eglPresentationTimeANDROID).
     */
    private val pacer = com.hayeco.flipspaces.core.spatial.FramePacer()
    private var presentStampFailed = false
    private var pacerLoggedMs = 0L

    /**
     * 0.5.12: the vsyncs Choreographer reports ([VsyncSource]), drained each
     * frame into the late draw's scheduler. Null until the activity starts the source.
     */
    @Volatile var vsyncs: java.util.concurrent.ConcurrentLinkedQueue<com.hayeco.flipspaces.core.spatial.VsyncSample>? = null
    private val late = com.hayeco.flipspaces.core.spatial.LateDrawScheduler()

    /** 0.5.12: what the timing line reports, over the last ten seconds or so. GL thread. */
    private val poseToPhotons = com.hayeco.flipspaces.core.spatial.RollingStats(600)
    private val imuAge = com.hayeco.flipspaces.core.spatial.RollingStats(600)
    private val wakeLate = com.hayeco.flipspaces.core.spatial.RollingStats(600)
    private var lateReason: String? = null
    private var lateOffReason: String? = null
    private var lateNow = false

    /** 0.5.10: the glasses display's refresh rate (Hz), from the activity; 0 when not known. UI thread. */
    fun setRefreshRate(hz: Float) {
        pacer.refreshHz = if (hz.isFinite() && hz > 0f) hz else 0f
    }

    /**
     * 0.5.13: the late draw's vsyncs at the glasses' own rate ([GlassesVsync]),
     * from the glasses display's period, appVsyncOffset and presentation deadline. UI thread.
     */
    private val glassesVsync = com.hayeco.flipspaces.core.spatial.GlassesVsync()
    @Volatile private var displayPeriodNs = 0L
    @Volatile private var appVsyncOffsetNs = 0L
    @Volatile private var presentationDeadlineNs = 0L
    @Volatile private var displayRows = 1080

    fun setDisplayTiming(periodNs: Long, appVsyncOffsetNs: Long, presentationDeadlineNs: Long, rows: Int, width: Int = 0) {
        displayPeriodNs = periodNs
        this.appVsyncOffsetNs = appVsyncOffsetNs
        this.presentationDeadlineNs = presentationDeadlineNs
        if (rows > 0) displayRows = rows
        // 0.5.14: a new mode (side by side at 60 Hz after 2D at 120) starts the late draw over
        // on the GL thread, so nothing measured at the old rate plans the new one.
        val timing = com.hayeco.flipspaces.core.spatial.ModeTiming(periodNs, appVsyncOffsetNs, presentationDeadlineNs, rows, StereoLayout.isSbsMode(width, rows))
        if (timing.changed(modeTiming)) {
            modeTiming = timing
            timingChanged = true
        }
    }

    @Volatile private var modeTiming: com.hayeco.flipspaces.core.spatial.ModeTiming? = null
    @Volatile private var timingChanged = false

    /** 0.5.14: the view the activity planned from Android's modes ([ViewModeChoice]); the next frame applies it. UI thread. */
    @Volatile private var viewPlan: com.hayeco.flipspaces.core.spatial.ViewPlan? = null
    private var appliedPlan: com.hayeco.flipspaces.core.spatial.ViewPlan? = null

    fun setViewPlan(plan: com.hayeco.flipspaces.core.spatial.ViewPlan) {
        if (plan != viewPlan) viewHintUntilMs = if (plan.met) 0L else SystemClock.uptimeMillis() + VIEW_HINT_MS
        viewPlan = plan
    }

    /** 0.5.14: what the glasses must do themselves for the view wanted, shown for a few seconds. */
    @Volatile private var viewHintUntilMs = 0L

    private fun viewHint(nowMs: Long): String? {
        // 0.5.29: the next queued line once the one shown has had its time.
        if (nowMs >= spaceNoticeUntilMs) noticeQueue.poll()?.let {
            spaceNotice = it
            spaceNoticeUntilMs = nowMs + NOTE_LINE_MS
        }
        return spaceNotice?.takeIf { nowMs < spaceNoticeUntilMs } ?: viewPlan?.needsGlasses?.takeIf { nowMs < viewHintUntilMs }
    }

    /** 0.5.22: a short confirmation, head locked below the view, until [spaceNoticeUntilMs] (the Glasses warp switch). */
    @Volatile private var spaceNotice: String? = null
    @Volatile private var spaceNoticeUntilMs = 0L

    /**
     * 0.5.22: the Glasses warp (beta) switch turned [on] or off, from the trackpad's
     * Menu or the Control panel ([from]): said on the glasses for [WARP_NOTICE_MS]. Nothing is sent from here;
     * [GlassesWarp] alone talks to the glasses, and only with the switch on.
     */
    private fun warpNotice(on: Boolean, from: String) {
        // The hint label is one line of about 44 characters at 40 px.
        spaceNotice = if (on) "Glasses warp (experimental): ON" else "Glasses warp (experimental): OFF"
        spaceNoticeUntilMs = SystemClock.uptimeMillis() + WARP_NOTICE_MS
        Log.i(tag, "Glasses warp (beta) ${if (on) "on" else "off"} from $from: confirmation shown on the glasses for ${WARP_NOTICE_MS / 1000} s")
    }

    /** 0.5.14: no Space frame until its window covers the display. */
    private val windowGate = com.hayeco.flipspaces.core.spatial.SpaceWindowGate()
    private var windowGateLogged = false

    /** 0.5.13: the glasses the Space is on, for the timing line. UI thread. */
    @Volatile private var glasses: com.hayeco.flipspaces.core.spatial.GlassesId? = null

    fun setGlasses(id: com.hayeco.flipspaces.core.spatial.GlassesId?) {
        glasses = id
        // 0.5.95: a setting the glasses themselves need (the One's Stabilizer off), said for their first few sessions.
        val m = store.model
        val hint = m.setupHint ?: return
        val said = store.setupHintSaid
        if (said >= com.hayeco.flipspaces.core.spatial.GlassesModel.SETUP_HINT_SESSIONS) return
        store.setupHintSaid = said + 1
        spaceNotice = hint
        spaceNoticeUntilMs = SystemClock.uptimeMillis() + SETUP_HINT_MS
        Log.i(tag, "said on the glasses (${m.label}, session ${said + 1} of ${com.hayeco.flipspaces.core.spatial.GlassesModel.SETUP_HINT_SESSIONS}): $hint")
    }

    /**
     * 0.5.13: each row's light time from the panel's duty, and the row fix
     * of the eye being drawn (null: one pose). GL thread.
     */
    private var rowTiming: com.hayeco.flipspaces.core.spatial.PanelRowTiming? = null
    private var rowFix: FloatArray? = null
    private var rowLine: String? = null

    /** 0.5.48: what the row timing line was last built from. */
    private data class RowLineKey(val t: com.hayeco.flipspaces.core.spatial.PanelRowTiming, val rowPose: Boolean, val sbs: Boolean, val warp: Boolean)
    private var rowLineKey: RowLineKey? = null

    private fun rowTimingNow(periodNs: Long): com.hayeco.flipspaces.core.spatial.PanelRowTiming {
        val t = rowTiming?.takeIf { it.periodNs == periodNs && it.rows == displayRows }
            ?: com.hayeco.flipspaces.core.spatial.PanelRowTiming.forRows(periodNs, displayRows).also { rowTiming = it }
        val sbs = stereo == StereoMode.SIDE_BY_SIDE
        val rowPose = store.rowPoseFor(sbs)
        // 0.5.48 (Mike, vc68: shaky windows on a hot phone): the line is built only when what it says changes. 0.5.25 to
        // 0.5.47 formatted it every frame to compare it with the last, about 7 % of the render thread (simpleperf).
        val key = RowLineKey(t, rowPose, sbs, warpWas)
        if (key == rowLineKey) return t
        rowLineKey = key
        // 0.5.25: said in side by side too, at 60 Hz, where it is on by default now.
        val line = t.line() + "; per-row pose " + (if (rowPose) "on (two poses over ${com.hayeco.flipspaces.core.spatial.RowScan.ROWS} strips)" else "off in the settings") +
            (if (sbs) " for side by side" + (if (warpWas) ", applied in the late warp (the top and the bottom row's pose blended by height)" else "") else " in 2D")
        if (line != rowLine) {
            rowLine = line
            Log.i(tag, line)
        }
        return t
    }

    /** 0.5.17: the Control panel (a three finger swipe down), the tracking mode's view and the trackpad's reticle. */
    // 0.5.37 (Mike: the Control Panel looked ugly; Arrange without its options): the Control Center, one painted card.
    private val controls = com.hayeco.flipspaces.core.spatial.ControlCenter()
    private val follow = com.hayeco.flipspaces.core.spatial.FollowView()
    private val reticle = com.hayeco.flipspaces.core.spatial.ReticleAnim()
    private var controlTexture: CanvasTexture? = null

    init {
        // 0.5.11: the closed home button takes a press only while it shows ([com.hayeco.flipspaces.core.spatial.HomeReveal]).
        // 0.5.17: the open Control panel takes every press too.
        pointer.pressFilter = { ray -> controls.isOpen || launcher.isOpen || (launcher.homeHittable && launcher.homeTile().hit(ray) != null) }
        SpaceSession.follow = com.hayeco.flipspaces.TrackpadPrefs(context).follow
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every texture is gone, so panels start over on fresh displays.
        panels.values.forEach { p -> p.displayId?.let { closeDisplay(it) } }
        panels.clear()
        tileTextures.clear()
        controlTexture?.release()
        controlTexture = null
        // 0.5.21: the frame record's row texture went with the old context.
        recordTex = 0
        recordFbo = 0
        recordWidth = 0
        // 0.5.25: and the late warp's eye buffers.
        eyeWarp.forget()
        lateWarp.reset()
        // 0.5.61: and the edge fade's program.
        edgeFadeGl.forget()
        GlKit.init()
        // 0.5.56: which apps play media, for the windows' rates.
        PlayingMedia.start(context)
        // 0.5.3: panel images are taken on a thread with no GL at all, so SurfaceFlinger never
        // waits for this context.
        frames?.shutdown()
        frames = PanelDrain()
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) PanelDrain.dumpTo(context.getExternalFilesDir(null))
        Log.i(tag, "panel frames: ImageReader per panel, taken on a thread with no GL as they arrive (0.5.3); SurfaceFlinger never waits for the Space")
        lastFrameEndMs = SystemClock.uptimeMillis()
        if (watchdog == null) watchdog = SpaceWatchdog(
            lastFrameMs = { lastFrameEndMs },
            onShed = { shedForStall() },
            onRelease = { releaseForStall() },
            onRecover = { _, _ -> recoverFromStall() },
        )
        info = LabelTexture(1024, 448)
        edgeHintLabel = LabelTexture(1024, 96)
        homeTexture = LabelTexture(192, 192)
        capReopened()
        layout.panels.forEach { panels[it.id] = PanelGl(it, frames!!, wideView.isOn(it.packageName), appDensity.smallestDp(it.packageName)) }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        // 0.5.68: premultiplied everywhere ([GlKit.BLEND_SRC]).
        GLES30.glBlendFunc(GlKit.BLEND_SRC, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // 0.5.16: GPU timer queries per pass, for the gpu passes line.
        gpuTimer.init()
        // 0.5.26: a new context: fences of the old one's frames are not counted, and the trackpad's spread starts over.
        gpuWatcher.take<FrameTag>()
        moveSpreader.reset()
        gpuTimes.unavailable?.let { Log.i(tag, "gpu passes: $it; only the pose to fence figure is logged") }
        // 0.5.55: this thread at urgent display priority with a hint session ([RenderThreadBoost]).
        boost?.onRenderThread(displayPeriodNs)
    }

    /** 0.5.55: the render thread's priority and ADPF hint session; null before Android 12. */
    private val boost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderThreadBoost(context, tag) else null

    /** 0.5.16: the GPU's own time for the prepare pass and each eye ([GpuPassTimer]). */
    private val gpuTimes = com.hayeco.flipspaces.core.spatial.GpuPassTimes(listOf("clear", "copies", "left eye", "right eye", "warp"))
    private val gpuTimer = GpuPassTimer(5, gpuTimes)

    /** 0.5.68: the developer Space snapshot, both eyes to a PNG on request ([SpaceCapture]). */
    private val snapshot = SpaceCapture(context)

    /** 0.5.99: the Space records itself (the left eye, to an MP4), when asked ([SpaceSession.recordWanted]). */
    private val recorder = SpaceRecorder(context)
    private var wasRecording = false

    // ---- 0.5.26 (Mike: "everything is a bit choppy"): the frame's pacing ----

    /** Each frame's GPU fence, watched off the render thread ([GpuDoneWatcher]). */
    private val gpuWatcher = GpuDoneWatcher()

    /** A frame waiting for its GPU fence: its plan, when its pose was taken, and the present its picture is for. */
    private class FrameTag(val plan: com.hayeco.flipspaces.core.spatial.DrawPlan, val poseNs: Long, val presentNs: Long)

    /** The trackpad's moves spread over the refreshes. */
    private val moveSpreader = com.hayeco.flipspaces.core.spatial.MoveSpreader()

    /** 0.5.55: the frame's incoming signals, the same list every frame. */
    private val incomingSignals = ArrayList<com.hayeco.flipspaces.core.pointer.PointerSignal>()

    /** Fresh, repeated and dropped refreshes, for the `space pacing` line. */
    private val refreshPacing = com.hayeco.flipspaces.core.spatial.RefreshPacing()

    /**
     * The clock the frame's animations, tweens and cursor are drawn with: the frame's own present time (ms, the
     * uptime clock's base), so they step by exactly a refresh every frame. 0.5.25 read the clock as each draw ran, which
     * moved with when the frame started (the late draw's wake, the late warp's early scene).
     */
    private var frameClockMs = SystemClock.uptimeMillis()

    /** The finished frames' GPU times into the late draw and the pacing count. */
    private var gpuWatchLogged = false

    private val spaceGpuGuard = com.hayeco.flipspaces.core.spatial.SpaceGpuGuard()
    private var pacingLoggedNs = 0L

    /** 0.5.26: the `space pacing` line and one `window ... pacing` line for each window showing. */
    private fun logPacing() {
        val now = System.nanoTime()
        val secs = if (pacingLoggedNs == 0L) 5.0 else (now - pacingLoggedNs) / 1e9
        pacingLoggedNs = now
        Log.i(tag, refreshPacing.line() + "; trackpad moves every %.1f ms, spread over the refreshes%s".format(
            java.util.Locale.ROOT, moveSpreader.inputPeriodNs / 1e6, if (gpuWatcher.refused == null) "; frame fences watched off the render thread" else "",
        ))
        for (p in panels.values) {
            if (p.state !is PanelState.Showing) continue
            val line = synchronized(p.src.pacing) { p.src.pacing.line("${p.panel.id} ${p.panel.label}", secs) }
            Log.i(PANEL_TAG, line)
        }
    }

    private fun takeFinishedFrames() {
        for (d in gpuWatcher.take<FrameTag>()) frameFinished(d.tag, d.doneNs)
    }

    private fun frameFinished(tag: FrameTag, doneNs: Long) {
        late.drawn(tag.plan, tag.poseNs, doneNs)
        val missed = doneNs > tag.plan.deadlineNs + tag.plan.depth * tag.plan.periodNs
        refreshPacing.frame(tag.plan.vsyncNs, tag.plan.periodNs, missed)
    }

    /** 0.5.18: the CPU time of the frame's first call on the window (its glClear), where the window buffer is dequeued, ms. */
    private val dequeueWait = com.hayeco.flipspaces.core.spatial.RollingStats(600)

    /** 0.5.16: how the glasses compose the Space (hardware overlay or GPU), read once per side by side session. */
    private val compositionProbe = CompositionProbe(context, tag)

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        applyViewport(viewport.withSurface(width, height).withInput(displayWidth, displayHeight), force = true)
    }

    /**
     * The size of the display the Space is on (its maximum window). Called from the UI thread
     * after every resize or maximize; the next frame picks it up.
     */
    fun setDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        displayWidth = width
        displayHeight = height
    }

    /** The Space's own display id. UI thread. */
    fun setDisplayId(id: Int) {
        spaceDisplayId = id
    }

    /** Android's thermal status changed (PowerManager.OnThermalStatusChangedListener). Any thread. */
    fun setThermalStatus(status: Int) {
        thermalStatus = status
    }

    /**
     * Rebuilds the GL viewport, projection and pointer mapping for [next] so a window that grew
     * (a DeX maximize) is drawn and aimed at full size, not at the size it opened with.
     */
    /**
     * The glasses are in Ultrawide ([StereoLayout.isUltrawide]): they hold the canvas in the room, so the Space draws the room
     * from [canvasPose], the head's pose when it began, with every window at its saved place. Recentre brings it round.
     */
    private var ultrawide = false
    private var canvasPose: Quat? = null
    @Volatile private var canvasRecentre = false

    private fun applyViewport(next: SpaceViewport, force: Boolean = false) {
        if (!force && next == viewport && appliedPlan == viewPlan) return
        viewport = next
        GLES30.glViewport(0, 0, next.surfaceWidth, next.surfaceHeight)
        // 0.5.9: the glasses' real field of view (GlassesOptics), one eye's share of the surface.
        // 0.5.14: a side by side surface is always split; 2D there puts the same picture in both halves.
        // Ultrawide: the 3840 x 1080 surface is one flat canvas, not two eyes, though Android cannot tell it from 3D.
        stereo = StereoLayout.mode(next.surfaceWidth, next.surfaceHeight, !ultrawide)
        eyes = if (ultrawide) StereoLayout.eyes(StereoMode.MONO, next.surfaceWidth, next.surfaceHeight, store.ipdMm)
        else com.hayeco.flipspaces.core.spatial.ViewModeChoice.eyes(viewPlan, next.surfaceWidth, next.surfaceHeight, store.ipdMm)
        appliedPlan = viewPlan
        // 0.5.26: the projection is the glasses' screen in this mode, so 2D uses their full field of view.
        val sbs = stereo == StereoMode.SIDE_BY_SIDE
        val screen = store.screen(sbs)
        frustum = if (ultrawide) store.optics(false).ultrawideFrustum(next.surfaceWidth, next.surfaceHeight) else next.frustum(store.optics(sbs), stereo)
        pointer.frustum = frustum
        xr.fitViewHeightDeg = if (ultrawide) frustum.verticalFovDeg else null
        // 0.5.27: each side by side eye's picture adjust, and the pointer's pixels mapped through it.
        // 0.5.29: worked out again on the next frame for this mode (the distance correction is side by side only).
        adjustVersionSeen = -1
        placeEyes()
        pointer.viewport = next.forEye(stereo, activeAdjust())
        fitLauncher()
        // 0.5.91: in the glasses' own degrees (the One said the Aura's 54.5 by 32.3 in 0.5.91's first run).
        Log.i(tag, screen.line(if (sbs) "their 3D mode" else "2D", store.model.tanPerPx1to1))
        screenHint(screen, sbs)
        Log.i(
            tag,
            "viewport ${next.surfaceWidth}x${next.surfaceHeight}, display ${next.inputWidth}x${next.inputHeight}: ${stereo.label}" +
                (if (stereo == StereoMode.SIDE_BY_SIDE) (if (eyes.all { it.offset == Vec3.ZERO }) " (2D: the same picture in both halves)" else " (eyes %.0f mm apart)".format(store.ipdMm)) else "") +
                ", each eye %dx%d at %.1f by %.1f degrees (%s)".format(frustum.widthPx, frustum.heightPx, frustum.horizontalFovDeg, frustum.verticalFovDeg, store.optics(sbs).line()) +
                // 0.5.15: the per-row pose and the panel filter in use for this view.
                (if (stereo == StereoMode.SIDE_BY_SIDE) "; per-row pose %s for side by side, %s per panel pixel".format(if (store.rowPoseSbs) "on" else "off", if (store.footprintFilter) "the footprint filter (four taps)" else "one bilinear tap")
                else "; per-row pose %s in 2D".format(if (store.rowPose) "on" else "off")) +
                if (next.windowed) "; a window smaller than the display shows its slice of the view" else "",
        )
    }

    /** 0.5.26: the modes (side by side or not) whose small screen was said on the glasses this session. */
    private val screenHintSaid = HashSet<Boolean>()

    /**
     * 0.5.26 (warn on the glasses when the 2D size is under 1.0): once a session and
     * mode, a screen under 100 % is said on the glasses with how to get the full view (their own menu; nothing is sent).
     */
    private fun screenHint(screen: com.hayeco.flipspaces.core.spatial.GlassesScreen, sbs: Boolean) {
        // Not over the view's own hint (the glasses' 3D button), which comes first. Nothing about 3D in Ultrawide.
        if (viewPlan?.met == false || ultrawide) return
        // 0.5.27: in 2D, once, how to get the full view (their 3D mode at 100 %); in 3D, the
        // size to set on them while it is under 100 % or only the phone's default ([com.hayeco.flipspaces.core.spatial.FullViewHint]).
        val text = com.hayeco.flipspaces.core.spatial.FullViewHint.hint(sbs, screen, store.screenSet(sbs)) ?: return
        if (!screenHintSaid.add(sbs)) return
        spaceNotice = text
        spaceNoticeUntilMs = SystemClock.uptimeMillis() + WARP_OFF_NOTICE_MS
        Log.i(tag, "said on the glasses: $spaceNotice (their own menu, Display size; then the same size on the phone's Space settings, or it is read from their log the next time it is on; nothing is sent)")
    }

    /**
     * 0.5.26: the screen size the glasses name in their own log (while it is on, in a warp) is saved for its mode and the
     * projection rebuilt with it, so it follows a change made on the glasses' menu.
     */
    private fun followGlassesScreen() {
        val st = GlassesWarp.glassesStatus ?: return
        val sbs = StereoLayout.isSbsMode(st.srcW, st.srcH)
        // 0.5.29: their 3D screen's distance and centre offset, from the status line, saved
        // and used for each eye's correction; once per status line seen.
        if (sbs && st !== distanceStatusSeen) {
            distanceStatusSeen = st
            // 0.5.59: only this session's distance is used; the saved one stays on record.
            if (st.depthM > 0f) distanceSeenM = st.depthM
            if (st.depthM > 0f && kotlin.math.abs(st.depthM - store.glassesDistanceM) >= 0.05f) {
                Log.i(tag, "the glasses' 3D screen is at %.2f m (their log), the phone had %.2f m: saved, and each eye's correction follows".format(java.util.Locale.ROOT, st.depthM, store.glassesDistanceM))
                store.setGlassesDistance(st.depthM, "read from their log")
            }
            EdgeCheckTest.adjustVersion++
        }
        val f = st.sizeFactor ?: return
        val saved = store.screen(sbs).sizeFactor
        if (kotlin.math.abs(f - saved) < 0.01f) return
        store.setScreen(sbs, f, "read from the glasses' own log")
        Log.i(tag, "the glasses show their ${if (sbs) "3D" else "2D"} screen at %.0f %% (their log), the phone had %.0f %%: saved, and the projection follows".format(java.util.Locale.ROOT, f * 100, saved * 100))
        if (sbs == (stereo == StereoMode.SIDE_BY_SIDE)) applyViewport(viewport, force = true)
        com.hayeco.flipspaces.hands.HandFeed.resize()
    }

    /** The head pose when a hand frame was taken, for hand rays in world space; null without head tracking. */
    private val headAt: (Long) -> Quat? = { t -> HeadTracker.poseAt(t)?.let { HeadView.pose(follow.relative(it), manualYaw) } }

    /**
     * While a game has the glasses: the glasses' real refresh for Monado's pacer ([com.hayeco.flipspaces.core.spatial.VblankLock]).
     * The Space draws one pixel a refresh under the game, only to learn when each was shown.
     */
    @Volatile private var gameClock: com.hayeco.flipspaces.core.spatial.VblankLock? = null
    private var gameClockLoggedMs = 0L
    private var gameClockFed = 0L

    private var gameClockLastNs = 0L

    private fun drawGameClockFrame(lock: com.hayeco.flipspaces.core.spatial.VblankLock) {
        // The hidden Space's swaps don't wait for the refresh (it ran about 170 a second): one frame a refresh is enough.
        val period = if (lock.periodNs > 0L) lock.periodNs else if (displayPeriodNs > 0L) displayPeriodNs else 16_666_667L
        val waitNs = gameClockLastNs + period - 1_000_000L - System.nanoTime()
        if (waitNs > 0L) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
        gameClockLastNs = System.nanoTime()
        frameTimes.pollShown { lock.add(it) }
        if (!feedGameClock) {
            if (gameClockFed != 0L) org.freedesktop.monado.ipc.PocketFeed.nativeVblank(0L, 0L)
            gameClockFed = 0L
        } else if (lock.periodNs > 0L) {
            org.freedesktop.monado.ipc.PocketFeed.nativeVblank(lock.lastNs, lock.periodNs)
            gameClockFed++
        }
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(0, 0, 1, 1)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        frameTimes.beforeSwap(null)
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - gameClockLoggedMs >= 10_000L) {
            gameClockLoggedMs = nowMs
            Log.i(tag, "game clock: the glasses' refresh %.4f ms, fed to Monado %d times; %d shown times off a refresh; %s".format(
                java.util.Locale.ROOT, lock.periodNs / 1e6, gameClockFed, lock.rejected, frameTimes.line()))
        }
    }

    companion object GameClockSwitch {
        /** Debug: the game clock's feed to Monado on or off, for an A/B with the pacer's own clock. */
        @Volatile var feedGameClock = true

        /** The glasses' 3D distance the Space draws for, metres, for a game's eye views ([com.hayeco.flipspaces.openxr.OpenXrRuntimeService]). */
        @Volatile var screenDistanceM: Float = com.hayeco.flipspaces.core.spatial.ScreenDistance.DEFAULT_M
    }

    override fun onDrawFrame(gl: GL10?) {
        gameClock?.let {
            drawGameClockFrame(it)
            return
        }
        val start = System.nanoTime()
        val nowMs = SystemClock.uptimeMillis()
        // 0.5.25: the glasses' delay for this mode, the 60 Hz scan's half added in side by side.
        val glassesMs = glassesDelayMs()
        pacer.extraMs = glassesMs.toFloat()
        val glassesNs = (glassesMs * 1e6).toLong()
        // 0.5.11: the stamp (before the vsync the frame is meant for) and the pose's time (its
        // photons on the glasses) are two times; 0.5.10 stamped the pose's time, which held every
        // frame one vsync longer than its pose was predicted for ([FramePacer]).
        // 0.5.14: a new display mode: the late draw starts over at its period.
        if (timingChanged) {
            timingChanged = false
            late.reset()
            fence.reset()
            splitGrid.reset()
            // 0.5.57: the glasses frame clock locks again at the new mode's period.
            frameClock.reset(displayPeriodNs)
            modeTiming?.let { Log.i(tag, it.line()) }
            // 0.5.26: fences of the old mode's frames are not counted against the new one.
            gpuWatcher.take<FrameTag>()
            // 0.5.55: the hint session's target follows the new period.
            boost?.retarget(displayPeriodNs)
        }
        // 0.5.26: the frames whose GPU work finished since the last frame ([GpuDoneWatcher]): the late draw's measure.
        takeFinishedFrames()
        var stampNs = pacer.frame(start)
        var presentNs = pacer.targetNs
        // 0.5.12: with a vsync timeline for this display, the frame is drawn late
        // ([LateDrawScheduler]); input below uses this first estimate of its present time.
        // 0.5.13: each Choreographer vsync as the glasses vsyncs it stands for.
        vsyncs?.let { q ->
            while (true) {
                val s = q.poll() ?: break
                glassesVsync.convert(s, Build.VERSION.SDK_INT >= 33, displayPeriodNs, appVsyncOffsetNs, presentationDeadlineNs).forEach(late::onVsync)
                // 0.5.57: each frame timeline's deadline is SurfaceFlinger's commit on the phone's clock, for the frame clock's lead.
                frameClock.onCommit(s.deadlineNs, s.presentNs, s.periodNs)
            }
        }
        // 0.5.58: the presents of the frames swapped a few frames ago, each for the clock's depth.
        frameTimes.poll(frameClock)
        lateNow = lateDrawUsable(start)
        // 0.5.57: frames on the glasses frame clock once it is locked to the fences; Choreographer's
        // timeline (below) only before that, without fences, or for a split callback (2D at 120 Hz).
        onClock = lateNow && glassesVsync.split <= 1 && frameClock.usable(start)
        // 0.5.19: plus the present fences' measured offset ([PresentFence]).
        // 0.5.21: and put on the glasses' own vsync grid from the fences ([PresentFence.snap]).
        // 0.5.24: the plan locked to the fences ([com.hayeco.flipspaces.core.spatial.PresentFence.lockNs]).
        if (onClock) frameClock.plan(start, clockBudgetNs())?.let { presentNs = it.poseNs + glassesNs }
        else if (lateNow) late.plan(start, fence.lockNs)?.let { presentNs = fence.snap(it.presentNs + fenceExtra(it)) + glassesNs }
        // 0.5.3, debug builds: a render thread stall on purpose (debug.STALL), for the watchdog.
        SpaceSession.debugStallMs.takeIf { it > 0 }?.let { ms ->
            SpaceSession.debugStallMs = 0L
            Log.w(WATCHDOG_TAG, "debug: the render thread stalls for $ms ms")
            Thread.sleep(ms)
        }
        // 0.5.3: panel images the GPU has finished reading go back to their displays.
        frames?.let(PanelImport::reap)
        reopenAfterStall()
        refreshMode(nowMs)
        applyViewport(viewport.withInput(displayWidth, displayHeight))
        while (true) (onGlQueue.poll() ?: break).invoke()
        // 0.5.19: the saved layout's wrap against the wraparound setting, once a session.
        if (!wrapSynced) {
            wrapSynced = true
            syncWrap()
        }
        drainCommands()
        drainDisplayEvents()
        logThermal()
        offHead.update(HeadTracker.tracking && HeadTracker.lyingStill, nowMs)?.let { Log.i(tag, it) }
        followGlassesScreen()
        syncPictureAdjust()
        // 0.5.26: why the Glasses warp turned itself off (or was refused), said on the glasses.
        GlassesWarp.takeOffNotice()?.let {
            spaceNotice = it
            spaceNoticeUntilMs = nowMs + WARP_OFF_NOTICE_MS
            Log.i(tag, "said on the glasses for ${WARP_OFF_NOTICE_MS / 1000} s: $it")
        }

        // 0.5.6: without a usable head pose this is exactly 0.5.4's head locked view; a failed
        // or garbled head stream never changes what is drawn (HeadView).
        if (!manualYaw.isFinite()) manualYaw = 0f
        // 0.5.10: input and hit tests use the pose at this frame's present time too.
        // 0.5.17: the tracking mode (Smooth Follow keeps the Space in front, [com.hayeco.flipspaces.core.spatial.FollowView]).
        // 1.1.0: while the hover duty test has the glasses hold the picture in the room, the Space draws head locked, so
        // it is not moved twice (their anchor and the Space's own head tracking).
        val hoverLocked = GlassesDisplayControl.hoverHeadLocked
        val live = if (hoverLocked) null else HeadTracker.renderPose(presentNs)
        if (ultrawide && live != null && (canvasPose == null || canvasRecentre)) {
            if (canvasRecentre) GlassesDisplayControl.recenter()
            canvasRecentre = false
            canvasPose = live
        }
        val tracked = if (ultrawide) canvasPose else live
        follow.mode = SpaceSession.follow
        follow.update(tracked, nowMs)
        var head = HeadView.pose(follow.relative(tracked), manualYaw)
        this.head = head
        launcher.followHead(Angles.of(head.forward).yawDeg)
        updateHome(nowMs)
        offerCalibration()
        // 0.5.26: the trackpad's moves spread over the refreshes ([com.hayeco.flipspaces.core.spatial.MoveSpreader]), so a
        // 60 Hz finger moves the cursor and a held window on every refresh of a 120 Hz Space.
        // 0.5.55: one list kept for every frame's signals, not a new one a frame.
        val incoming = incomingSignals
        incoming.clear()
        while (true) {
            val s = SpaceSession.signals.poll() ?: break
            if (calibrationTakes(s)) continue
            incoming += s
        }
        val refreshNs = displayPeriodNs.takeIf { it > 0L } ?: (pacer.snapped() * 1e6).toLong()
        for (s in moveSpreader.take(incoming, start, refreshNs)) {
            apply(pointer.handle(s, head, layout, { id -> panels[id]?.let { it.width to it.height } ?: (1280 to 720) }, headAt))
        }
        // 0.5.0: a hand that left the camera lets go of what it held and its cursor goes.
        val handsSeen = SpaceSession.hands
        for (side in pointer.activeHands) if (handsSeen[side]?.present != true) apply(pointer.dropHand(side, head, layout))
        // 0.5.2: with the frame's clock, so hand cursors move on every display frame.
        apply(pointer.frame(head, layout, nowMs))
        SpaceSession.heldPanelId = pointer.heldPanelId
        // 0.5.32: the head's turn speed to the glasses' duty on turns (it sends nothing unless its switch is on).
        GlassesDisplayControl.frame(HeadTracker.turnSpeedDegS, android.os.SystemClock.elapsedRealtime())
        // 0.5.32: the open windows, for the Menu's Arrange layouts by count.
        SpaceSession.windowCount = layout.panels.size
        // 0.5.37: Immersive's motion this frame, the others' presses held off while they are gone.
        stepImmersive()
        // 0.5.34 (vc54: Sharper turns on, nothing sent; 0.5.32 read these switches in the head cursor's draw, which a hand
        // up or a held window skips): the still lock's and the glasses display's switches read every 0.5 s, every frame's path.
        val settingsNowMs = android.os.SystemClock.uptimeMillis()
        if (settingsNowMs - settingsCheckedMs > 500) {
            settingsCheckedMs = settingsNowMs
            HeadTracker.setStillLock(store.stillLock)
            HeadTracker.setDrawSmoothing(store.drawSmoothing)
            val wideNow = StereoLayout.isUltrawide(viewport.surfaceWidth, viewport.surfaceHeight, GlassesDisplayControl.stereoState) && GlassesDisplayControl.ultrawideRead != 0
            if (wideNow != ultrawide) {
                ultrawide = wideNow
                canvasPose = null
                Log.i(tag, "the glasses are ${if (wideNow) "in Ultrawide: one flat canvas" else "out of Ultrawide"}")
                applyViewport(viewport, force = true)
            }
            steadyWindows()
            val trayButtons = com.hayeco.flipspaces.core.spatial.LauncherButton.values().filter { it != com.hayeco.flipspaces.core.spatial.LauncherButton.STEADY || com.hayeco.flipspaces.core.spatial.SteadyMode.offeredOn(store.model) }
            if (xr.buttons != trayButtons) xr.buttons = trayButtons
            GlassesDisplayControl.configure(context, spaceUp = true, turnDuty = store.turnDuty, picture32by9 = StereoLayout.isSbsMode(viewport.surfaceWidth, viewport.surfaceHeight))
            // 0.5.36: the trackpad cursor anchored in the room or following the head (the trackpad's chip).
            pointer.snapWindows = store.snapWindows
            // 1.1.15: Lock screens, from the trackpad's Lock button or its Menu.
            val locked = store.windowsLocked
            if (pointer.windowsLocked != locked) {
                apply(pointer.lockWindows(locked, layout))
                Log.i(tag, "Lock screens ${if (locked) "on" else "off"}")
            }
            PanelHost.newInstance = store.newInstance
            val follows = store.cursorFollowsHead
            if (pointer.cursorFollowsHead != follows) {
                pointer.cursorFollowsHead = follows
                Log.i(tag, "cursor follows the head: ${if (follows) "on" else "off (anchored in the room)"}")
            }
            // Unreleased: a mouse in use sets the hands aside while the cursor stays anchored (SpatialPointer.handsSetAside).
            val mouse = com.hayeco.flipspaces.pointer.MouseRoute.leads()
            if (pointer.mouseLeads != mouse) {
                pointer.mouseLeads = mouse
                Log.i(tag, "a mouse ${if (mouse) "leads the pointer" else "no longer leads the pointer"}: hands ${if (pointer.handsSetAside) "set aside (Cursor follows head is off)" else "work as before"}")
            }
        }
        SpaceSession.heldAdjusts = pointer.holdAdjusts
        SpaceSession.heldCurves = pointer.holdCurves
        syncLauncherFocus()
        SpaceSession.aim = SpaceAim(layout, head, frustum, viewport.forEye(stereo, activeAdjust()))
        recoverIfHostLost()
        openPending(nowMs)
        // 0.5.21: what each app asks for, for its window's shape and Wide view.
        checkAppShapes(nowMs)
        // 0.5.22: Wide view's per app override put back for every app no window shows.
        sweepOverrides(nowMs)
        checkTasks(nowMs)
        keepAppsOnWindows()
        updatePanelRates(nowMs)
        syncPanelRates(nowMs)

        // 0.5.60 (Mike: the left eye cut on its outer edge on the Flip): the test card, straight into the window with no
        // warp, in place of everything else; drawn whatever the window's size, so a window short of the display shows.
        if (TestCardSwitch.mode != com.hayeco.flipspaces.core.spatial.TestCardMode.OFF) {
            drawTestCard()
            lastFrameEndMs = SystemClock.uptimeMillis()
            return
        }
        if (testCardKey != null) {
            testCardKey = null
            Log.i(tag, "test card off")
        }
        // 0.5.14: until the window covers the display (DeX opened it 960x1080 in
        // 0.5.13), only black (clear on the glasses) is shown, never a squeezed portrait Space.
        if (!windowGate.draw(viewport.surfaceWidth, viewport.surfaceHeight, displayWidth, displayHeight, nowMs)) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (windowGate.heldFrames == 1) Log.i(tag, "first frame held: window ${viewport.surfaceWidth}x${viewport.surfaceHeight} of display ${displayWidth}x$displayHeight; nothing drawn until it covers the display")
            lastFrameEndMs = SystemClock.uptimeMillis()
            return
        }
        windowGate.line?.let { if (!windowGateLogged) { windowGateLogged = true; Log.i(tag, it) } }
        // 0.5.18: the prepare timer split in two, the window's clear
        // (the first command on the newly dequeued window buffer, which waits for SurfaceFlinger to
        // release it) and the panel copies; and the CPU time of that first call, which is where the
        // window buffer is dequeued.
        gpuTimer.beginFrame()
        gpuTimer.begin(0)
        val clearStart = System.nanoTime()
        if (seeThrough) GLES30.glClearColor(0f, 0f, 0f, 1f) else GLES30.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        dequeueWait.add((System.nanoTime() - clearStart) / 1e6)
        gpuTimer.end()
        // 0.5.15: panel copies, mipmaps and display resizes before the
        // sleep, so the budget times the eye passes only.
        gpuTimer.begin(1)
        // 0.5.26: the refresh this frame is for, the glasses' delay taken out (the window pictures' timestamps are the phone's).
        preparePanels(nowMs, copyDueNow(presentNs), presentNs - glassesNs)
        resizeDisplays(nowMs)
        gpuTimer.end()
        // 0.5.18: and submitted before the sleep: 0.5.17 recorded them here but the GPU only got them
        // with the eye passes after the pose was taken, so the clear's wait for the window buffer and
        // the copies (13.4 ms with 0 copies) were in every frame's draw time.
        if (lateNow) GLES30.glFlush()
        // 0.5.25: side by side, the per-eye late warp. The Space is drawn now into an eye
        // buffer for this frame's present (a whole frame to finish), or not at all while the one before is still on the
        // GPU; just before the deadline the newest buffer is turned onto the window by the newest pose.
        val warpEyes = lateWarpOn()
        // 0.5.28: the late warp starts 1 ms earlier while the phone is thermally light or worse.
        val pressure = if (warpEyes && thermalStatus >= ThermalGuard.LIGHT) WARP_PRESSURE_MS else 0f
        if (pressure != late.budget.pressureMs) {
            late.budget.pressureMs = pressure
            Log.i(tag, if (pressure > 0f) "late warp starts %.0f ms earlier: thermal ${ThermalGuard.label(thermalStatus)} (a slower GPU missed the warp's deadline in 0.5.27)".format(java.util.Locale.ROOT, pressure) else "late warp at its own budget again (thermal ${ThermalGuard.label(thermalStatus)}${if (warpEyes) "" else ", or the warp off"})")
        }
        // 0.5.55: while the late warp runs, the margin before the deadline is sized from the final
        // pass as measured ([com.hayeco.flipspaces.core.spatial.DrawBudget.passSized]); the late draw line says how.
        if (warpEyes != late.budget.passSized) {
            late.budget.passSized = warpEyes
            Log.i(tag, if (warpEyes) "late draw: the margin before the deadline is sized from the final pass now (its wake to GPU done p99 + %.1f ms, at least %.1f ms, never over the cap)".format(java.util.Locale.ROOT, late.budget.passMarginMs, late.budget.passFloorMs)
            else "late draw: the margin is the draws' p90 + %.1f ms again (no late warp)".format(java.util.Locale.ROOT, late.budget.marginMs))
        }
        val hint = edgeHintText(nowMs) ?: viewHint(nowMs)
        if (warpEyes) {
            eyeWarp.ensure(viewport.surfaceWidth, viewport.surfaceHeight)
            val newest = lateWarp.forWarp()
            val slot = lateWarp.begin(newest == null || eyeWarp.sceneDone(newest.index))
            if (slot != null) {
                // 0.5.57: on the clock, the scene for the glasses refresh the coming frame is posed for.
                val scenePresent = if (onClock) frameClock.plan(System.nanoTime(), clockBudgetNs())?.let { it.poseNs + glassesNs } ?: presentNs
                else late.plan(System.nanoTime(), fence.lockNs)?.let { fence.snap(it.presentNs + fenceExtra(it)) + glassesNs } ?: presentNs
                val sceneHead = if (hoverLocked || ultrawide) head else HeadView.pose(follow.relative(HeadTracker.renderPose(scenePresent, record = false)), manualYaw)
                this.head = sceneHead
                eyeWarp.bindScene(slot.index)
                if (seeThrough) GLES30.glClearColor(0f, 0f, 0f, 1f) else GLES30.glClearColor(0.05f, 0.06f, 0.08f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                // 0.5.26: the scene's animations at its own present ([frameClockMs]); its eye pass timed alone.
                frameClockMs = scenePresent / 1_000_000L
                // 0.5.59: the eye buffer holds each eye placed over its whole half, through
                // the widened projection, so the warp below has the strip beyond the moved picture too (0.5.27 to 0.5.58:
                // the unmoved picture, which the warp then put in a clipped viewport).
                drawEyes(frustum.projection(), Mat4.rotation(sceneHead.conjugate()), null, sceneHead, hint, frameClockMs)
                gpuTimer.end()
                eyeWarp.sceneSubmitted(slot.index)
                lateWarp.submitted(slot, sceneHead, scenePresent, System.nanoTime())
            }
        }
        // 0.5.12: wait until the frame's deadline less the measured draw budget, then
        // take the pose for the frame's present time; one pose for both eyes. Hands and hit tests
        // above used an earlier pose for the same frame.
        var plan: com.hayeco.flipspaces.core.spatial.DrawPlan? = null
        var sleptNs = 0L
        var clockPlan: com.hayeco.flipspaces.core.spatial.GlassesFrameClock.Plan? = null
        if (onClock) {
            // 0.5.57: the next glasses refresh after the last frame's; the late sleep to its wake (its
            // commit's lead less the draw budget), the pose for the refresh that commit feeds, stamped for that refresh.
            clockPlan = frameClock.plan(System.nanoTime(), clockBudgetNs())
            clockPlan?.let {
                plan = com.hayeco.flipspaces.core.spatial.DrawPlan.of(it)
                val before = System.nanoTime()
                sleepUntil(it.wakeNs)
                val woke = System.nanoTime()
                sleptNs = woke - before
                if (it.wakeNs > before) wakeLate.add((woke - it.wakeNs) / 1e6)
                presentNs = it.poseNs + glassesNs
                stampNs = it.stampNs
                frameClock.taken(it)
            }
        } else if (lateNow) {
            // 0.5.57: the 0.5.56 glasses refresh gate is gone; the frame clock above does its job once locked.
            frameClock.fallbackFrame()
            plan = late.plan(System.nanoTime(), fence.lockNs)
            plan?.let {
                // 0.5.25: a frame of a split callback (2D at 120 Hz from 60 Hz callbacks) is
                // stamped and posed for its own vsync on the glasses' fitted grid ([SplitGridStamp]); null keeps the plan
                // as before, always when the callback is not split (side by side at 60 Hz).
                val grid = splitGrid.place(it.presentNs + fenceExtra(it), it.periodNs, glassesVsync.split, fence::snap)
                val wake = it.wakeNs + (grid?.wakeShiftNs ?: 0L)
                val before = System.nanoTime()
                sleepUntil(wake)
                val woke = System.nanoTime()
                sleptNs = woke - before
                if (wake > before) wakeLate.add((woke - wake) / 1e6)
                presentNs = (grid?.presentNs ?: fence.snap(it.presentNs + fenceExtra(it))) + glassesNs
                stampNs = grid?.stampNs ?: it.stampNs
                // 0.5.19: the frame's stamp and the display present its pose is for, for the fence check.
                // 0.5.24: on the frame timeline's terms plus the offset, as the fence's model has it, whatever the lock.
                fence.record(stampNs, it.presentNs - it.shiftNs + fence.offsetNs)
            }
        }
        // 0.5.10: the pose taken again as late as possible, for the frame's present time.
        val poseNs = System.nanoTime()
        // 0.5.57: the frame's stamp, the refresh its pose is for and when the pose was taken, for what the fences say it showed.
        clockPlan?.let { frameClock.record(it.stampNs, it.poseNs, poseNs) }
        val trackedNow = if (ultrawide) canvasPose else HeadTracker.renderPose(presentNs)
        head = HeadView.pose(follow.relative(trackedNow), manualYaw)
        this.head = head
        // 0.5.21: while the glasses warp, this frame carries its render pose in its top rows.
        // 0.5.25 (a frame whose record does not parse drops the glasses out of scene mode 1): every
        // frame carries one, the last pose held on a frame with no head pose, rather than a frame with none.
        if (trackedNow != null) lastRecordPose = trackedNow
        val recordPose = trackedNow ?: lastRecordPose.takeIf { GlassesWarp.drawRecords && recordAlign.aligned }
        if (trackedNow == null && recordPose != null) recordsHeld++
        val warping = GlassesWarp.drawRecords && recordPose != null
        // 0.5.24: the 3D record tests run only on a side by side picture.
        GlassesWarp.pictureStereo = eyes.size > 1
        if (HeadTracker.tracking) {
            poseToPhotons.add((presentNs - poseNs) / 1e6)
            HeadTracker.newestSampleNs.takeIf { it > 0L }?.let { imuAge.add((poseNs - it) / 1e6) }
        }
        val projection = frustum.projection()
        // 0.5.13: the poses for the top and the bottom row's light time; the
        // picture is drawn through the top one and each vertex blended toward the bottom one by its
        // height ([RowScan]). The middle row keeps the pose above, which hit tests use.
        val periodNs = displayPeriodNs.takeIf { it > 0L } ?: (pacer.snapped() * 1e6).toLong()
        // 0.5.15: the per-row pose has its own setting in side by side, off until checked on the Aura.
        // 0.5.21: not while the glasses warp: they warp each 32 row band to its own light time from the
        // one pose in the record, so a per-row pose here would be corrected twice.
        val rows = if (!warping && store.rowPoseFor(stereo == StereoMode.SIDE_BY_SIDE) && HeadTracker.tracking) rowTimingNow(periodNs) else null
        val headTop = rows?.takeUnless { hoverLocked || ultrawide }?.let { HeadView.pose(follow.relative(HeadTracker.renderPose(presentNs + it.topNs, record = false)), manualYaw) }
        val headBottom = rows?.takeUnless { hoverLocked || ultrawide }?.let { HeadView.pose(follow.relative(HeadTracker.renderPose(presentNs + it.bottomNs, record = false)), manualYaw) }
        if (warpEyes) {
            // 0.5.25: the newest buffer turned onto the window by the newest pose, the top and the bottom row's when
            // the per-row pose is on (the row model at 60 Hz), each eye through its own homography.
            // 0.5.26: the newest scene only if the GPU has finished it, else the one
            // before; the warp never queues behind a scene still drawing.
            val src = lateWarp.forWarp { eyeWarp.sceneDone(it) }
            if (src != null) {
                val top = headTop ?: head
                val bottom = headBottom ?: head
                val hTop = com.hayeco.flipspaces.core.spatial.EyeWarp.homography(projection, src.pose, top)
                val hBottom = com.hayeco.flipspaces.core.spatial.EyeWarp.homography(projection, src.pose, bottom)
                val clear = if (seeThrough) WARP_CLEAR_SEE_THROUGH else WARP_CLEAR_DARK
                // 0.5.26: the warp pass has its own timer slot.
                gpuTimer.begin(WARP_PASS)
                // 0.5.59: each eye onto its whole half, the homographies on the projection its buffer was drawn with.
                // 0.5.55: into lists and eye rectangles kept from frame to frame (five lists and four arrays a frame before).
                warpEyeList.clear(); warpTopList.clear(); warpBottomList.clear(); warpDestList.clear()
                for (i in eyes.indices) {
                    val e = eyes[i]
                    while (warpRects.size <= i) warpRects += IntArray(4)
                    val rect = warpRects[i]
                    rect[0] = e.x; rect[1] = e.y; rect[2] = e.width; rect[3] = e.height
                    val placed = placements.getOrNull(i)
                    warpEyeList += rect
                    warpTopList += placed?.warpHomography(projection, src.pose, top) ?: hTop
                    warpBottomList += placed?.warpHomography(projection, src.pose, bottom) ?: hBottom
                    warpDestList += rect
                }
                eyeWarp.warp(src.index, warpEyeList, warpTopList, warpBottomList, clear, warpDestList)
                gpuTimer.end()
                warpAgeMs.add((presentNs - src.presentNs) / 1e6)
            }
        } else {
            val viewBottom = headBottom?.let { Mat4.rotation(it.conjugate()) }
            val view = Mat4.rotation((headTop ?: head).conjugate())
            frameClockMs = presentNs / 1_000_000L
            drawEyes(projection, view, viewBottom, head, hint, frameClockMs)
            gpuTimer.end()
        }
        // 0.5.61 (Mike: the left eye's "wall" is its picture's pixel 0): the soft fade to black on each eye's edges, the
        // last pass on the picture, after the late warp so it is fixed to the display; before the frame record's rows.
        drawEdgeFade()
        // 0.5.27: the edge check over the finished picture, when the Menu turned it on.
        drawEdgeCheck()
        // 0.5.68: the finished picture to a PNG when asked (the flag file); before the frame record's rows.
        snapshot.maybeTake(viewport.surfaceWidth, viewport.surfaceHeight)
        // 0.5.99: the finished left eye to the recording, then the red dot on the glasses only (drawn after the copy).
        recordFrame()
        if (warping) drawFrameRecord(recordPose!!, presentNs, start, nowMs) else if (recordAlign.aligned) endFrameRecords(nowMs, "the warp ended (${GlassesWarp.state})")
        framesSinceTiming++
        // 0.5.26: each window's new picture, for its pacing line, on the refresh it is for.
        for (p in panels.values) if (p.freshThisFrame) synchronized(p.src.pacing) { p.src.pacing.shownAt(presentNs) }
        gpuTimer.endFrame()
        if (eyes.size > 1) GLES30.glViewport(0, 0, viewport.surfaceWidth, viewport.surfaceHeight)

        // 0.5.12: when the GPU is done tells whether the frame made its deadline.
        // 0.5.26: found by the fence watcher, so the render thread does not wait for the GPU; on the render thread only
        // without EGL fences.
        plan?.let { p ->
            val ft = FrameTag(p, poseNs, presentNs)
            if (!gpuWatcher.submit(ft)) {
                gpuWatcher.refused?.let { if (!gpuWatchLogged) { gpuWatchLogged = true; Log.i(tag, "frame fences: $it; the render thread waits for each frame's GPU work as in 0.5.25") } }
                frameFinished(ft, gpuDone(p))
            }
        }
        // 0.5.16: each time the half frame cap lifts (or comes back), why.
        late.budget.takeCapEvent()?.let { Log.i(tag, it) }
        stampPresentTime(stampNs)
        // 0.5.58: the id this frame's swap gets, so its own present can be read back ([FrameTimestamps]).
        frameTimes.beforeSwap(clockPlan)
        perf.frame(System.nanoTime(), System.nanoTime() - start - sleptNs)?.let {
            // 0.5.15: the frame pacer's model only when it is the one in use.
            Log.i(tag, "perf: ${it.line()}${imagesLine()}; ${prepareCounts.line()}; ${timingModelLine()}")
            logTiming()
        }
        if (nowMs - pacerLoggedMs >= 2_000L) {
            pacerLoggedMs = nowMs
            SpaceSession.pacerLine = timingModelLine()
        }
        publish(nowMs)
        // 0.5.57: the present fences every 2 s for the glasses frame clock (0.5.19 to 0.5.56: with each 5 s timing line).
        askFence()
        lastFrameEndMs = SystemClock.uptimeMillis()
        // 0.5.55: the frame's own work, the late sleep left out, to the hint session.
        boost?.report(System.nanoTime() - start - sleptNs)
    }

    /**
     * The eye passes (0.5.9: one pass in mono; side by side, one pass per eye in its half, from that eye), into
     * whatever framebuffer is bound: the window, or since 0.5.25 an eye buffer of the late warp. [viewBottom] is the
     * bottom row's view for the per-row pose, null for one pose. 0.5.27: [adjusted] draws each side by side eye through
     * its placement's projection ([placements]); 0.5.59: always over its whole half, in the window and in the late
     * warp's eye buffer alike.
     */
    private fun drawEyes(baseProjection: Mat4, view: Mat4, viewBottom: Mat4?, head: Quat, hint: String?, nowMs: Long, adjusted: Boolean = true) {
        for ((i, eye) in eyes.withIndex()) {
            eyePass = i
            gpuTimer.end()
            gpuTimer.begin(2 + i)
            val placed = if (adjusted) placements.getOrNull(i) else null
            if (placed != null) GLES30.glViewport(placed.vx, placed.vy, placed.vw, placed.vh)
            else if (eyes.size > 1) GLES30.glViewport(eye.x, eye.y, eye.width, eye.height)
            val projection = placed?.let { it.crop() * baseProjection } ?: baseProjection
            val eyeShift = if (eye.offset == Vec3.ZERO) null else Mat4.translation(-eye.offset)
            val viewProj = if (eyeShift == null) projection * view else projection * eyeShift * view
            eyeProjection = projection
            eyeView = view
            eyeOffset = eye.offset
            rowFix = viewBottom?.let { b -> com.hayeco.flipspaces.core.spatial.RowScan.fix(viewProj, if (eyeShift == null) projection * b else projection * eyeShift * b).m }
            // 0.5.20 (Mike: the cursor behind the launcher): one eye's pass in [SpaceLayer.ORDER], the
            // cursor last, after every panel, menu and the launcher; [CursorDepth] puts it in front of
            // them in the room too, so both eyes see it on top.
            val rays = pointerRays()
            for (layer in com.hayeco.flipspaces.core.spatial.SpaceLayer.ORDER) when (layer) {
                com.hayeco.flipspaces.core.spatial.SpaceLayer.INFO -> drawInfo(viewProj, nowMs)
                // Back to front, in the order hit tests use: nearer panels, then the most recently
                // raised or focused, cover the others.
                com.hayeco.flipspaces.core.spatial.SpaceLayer.WINDOWS -> layout.drawOrder().forEach { drawPanel(it, viewProj, nowMs) }
                com.hayeco.flipspaces.core.spatial.SpaceLayer.CONTROL_PANEL -> if (controls.isOpen) drawControls(viewProj, rays)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.LAUNCHER -> drawLauncherTiles(viewProj, rays)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.HOME -> drawHome(viewProj, rays)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.CALIBRATION -> drawCalibration(viewProj, head)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.DELAY_CHECK -> drawDelayCheck(viewProj, head)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.LEVEL_CHECK -> drawLevelCheck(eyeShift?.let { projection * it } ?: projection)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.EDGE_HINT -> drawEdgeHint(viewProj, head, hint)
                com.hayeco.flipspaces.core.spatial.SpaceLayer.CURSOR -> drawCursor(head, viewProj)
            }
        }
        eyePass = 0
        rowFix = null
        eyeOffset = Vec3.ZERO
    }

    /** 0.5.26: the eye pass's projection, view and eye offset, for [comfortViewProj]. */
    private var eyeProjection = Mat4.identity()
    private var eyeView = Mat4.identity()
    private var eyeOffset = Vec3.ZERO

    /**
     * 0.5.26 (windows down to 0.3 m): the eye pass's [viewProj] for a thing [distanceM] away, its eye offset scaled for
     * a comfortable convergence closer than [com.hayeco.flipspaces.core.spatial.StereoComfort.COMFORT_M]; [viewProj]
     * itself in 2D and from that distance on. The per-row fix is kept (it differs by well under a millimetre).
     */
    private fun comfortViewProj(viewProj: Mat4, distanceM: Float): Mat4 {
        if (eyeOffset == Vec3.ZERO) return viewProj
        val k = com.hayeco.flipspaces.core.spatial.StereoComfort.eyeScale(distanceM)
        if (k >= 1f) return viewProj
        return eyeProjection * Mat4.translation(-(eyeOffset * k)) * eyeView
    }

    // ---- 0.5.25: side by side as steady as 2D ----

    /** 0.5.55: the warp pass's eye rectangles, homographies and viewports, kept from frame to frame. */
    private val warpRects = ArrayList<IntArray>(2)
    private val warpEyeList = ArrayList<IntArray>(2)
    private val warpTopList = ArrayList<FloatArray>(2)
    private val warpBottomList = ArrayList<FloatArray>(2)
    private val warpDestList = ArrayList<IntArray>(2)

    /** The per-eye late warp's buffers and which one each frame shows. */
    private val eyeWarp = EyeWarpGl()
    private val lateWarp = com.hayeco.flipspaces.core.spatial.LateWarpFrames()

    /** The shown scene's age at its present (its own present to this frame's), ms. */
    private val warpAgeMs = com.hayeco.flipspaces.core.spatial.RollingStats(600)
    private var warpWas = false
    private var warpWhy: String? = null
    private var delayLine: String? = null

    /** The glasses' delay to predict for in the mode in use ([com.hayeco.flipspaces.core.spatial.ModeGlassesDelay]), logged when it changes. */
    /** 0.5.48: what the glasses delay line was last built from. */
    private var delaySeen = false
    private var delayPredict = 0
    private var delayCalibrated = false
    private var delaySbs = false
    private var delayPeriod = 0L
    private var delayRows = 0
    private var delayRowPose = false

    private fun glassesDelayMs(): Double {
        val sbs = stereo == StereoMode.SIDE_BY_SIDE
        val period = displayPeriodNs.takeIf { it > 0L } ?: com.hayeco.flipspaces.core.spatial.ModeGlassesDelay.REF_PERIOD_NS
        // 0.5.28: with the per-row pose on, the rows are already placed in time, so no 4 ms more.
        val rowPose = store.rowPoseFor(sbs)
        val predict = store.predictMs
        val calibrated = store.delayCalibrated
        // 0.5.48: the line only when its inputs change (0.5.28 to 0.5.47 formatted it every frame, about 5 % of the thread).
        // 0.5.55: compared field by field; the key list boxed six values every frame.
        val rows = displayRows
        val same = delaySeen && predict == delayPredict && calibrated == delayCalibrated && sbs == delaySbs &&
            period == delayPeriod && rows == delayRows && rowPose == delayRowPose
        if (!same) {
            delaySeen = true
            delayPredict = predict
            delayCalibrated = calibrated
            delaySbs = sbs
            delayPeriod = period
            delayRows = rows
            delayRowPose = rowPose
            val line = com.hayeco.flipspaces.core.spatial.ModeGlassesDelay.line(predict, calibrated, sbs, period, displayRows, rowPose)
            if (line != delayLine) {
                delayLine = line
                Log.i(tag, line)
            }
        }
        return com.hayeco.flipspaces.core.spatial.ModeGlassesDelay.ms(predict, calibrated, sbs, period, displayRows, rowPose)
    }

    /**
     * The per-eye late warp runs this frame: side by side, the late draw on, head tracking, the setting on, and not
     * while the glasses warp (their own warp takes the record's pose; a second one here would be corrected twice).
     * A change starts the late draw's budget over (it times the warp pass alone while on) and is logged with why.
     */
    private fun lateWarpOn(): Boolean {
        val why = when {
            eyes.size < 2 -> "2D"
            !store.lateWarpSbs -> "off in the settings"
            !lateNow -> LATE_DRAW_OFF
            !HeadTracker.tracking -> "no head tracking"
            GlassesWarp.drawRecords -> "the glasses warp themselves"
            else -> null
        }
        // 0.5.27: a change from the late draw alone holds 5 frames, so the reset each change
        // makes cannot flip the warp on and off ([com.hayeco.flipspaces.core.spatial.LateWarpGate]).
        if (warpGate.update(why)) {
            val on = warpGate.on
            if (on != warpWas) {
                late.reset()
                lateWarp.reset()
            }
            warpWas = on
            warpWhy = warpGate.why
            Log.i(tag, if (on) "late warp on (side by side): the Space is drawn early into an eye buffer and turned by the newest pose just before each deadline; a scene still on the GPU is not waited for, the one before is turned instead"
            else "late warp off: ${warpGate.why}; side by side is drawn as in 0.5.24".takeIf { eyes.size > 1 } ?: "late warp off: 2D")
        }
        return warpGate.on
    }

    private val warpGate = com.hayeco.flipspaces.core.spatial.LateWarpGate(LATE_DRAW_OFF)

    /** 0.5.27: the edge check as last drawn (null: off), so each change is logged once. */
    private var edgeCheckDrawn: String? = null

    /** 0.5.27: the edge check's marks per eye and what they were made for, so they are worked out once. */
    private var edgeMarks: List<List<com.hayeco.flipspaces.core.spatial.EdgeCheck.Mark>> = emptyList()
    private var edgeMarksKey: String? = null
    private var edgeEyesLogged: String? = null

    // ---- 0.5.27: the per-eye picture adjust (Mike: in SBS the view is cut off on the left) ----

    /**
     * 0.5.59: no saved adjust ([com.hayeco.flipspaces.core.spatial.ActiveAdjust]), only the correction for the glasses' 3D distance added (0.5.29,
     * [com.hayeco.flipspaces.core.spatial.ScreenDistance]); applied only side by side ([activeAdjust]).
     */
    private var pictureAdjust = com.hayeco.flipspaces.core.spatial.PictureAdjust.NONE

    /** 0.5.29: the glasses' last status line used for the distance, its quad's centre offset, and the note said once. */
    private var distanceStatusSeen: com.hayeco.flipspaces.core.spatial.GlassesLogEvents.Event.Status? = null

    /** 0.5.59: the glasses' 3D distance seen on their status line in this session; null until one is seen. */
    private var distanceSeenM: Float? = null
    private var distanceNoteFor: Float? = null

    /** 0.5.29: notices said one after the other, each for [NOTE_LINE_MS] (the hint label holds one line). */
    private val noticeQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * 0.5.29: once for each distance, in the glasses' 3D mode, when it is not their default
     * 4 m: that 4 m gives the full, centred view, and where to set it on their own menu. Nothing is sent.
     */
    private fun distanceNote(m: Float) {
        if (distanceNoteFor == m) return
        distanceNoteFor = m
        val lines = com.hayeco.flipspaces.core.spatial.ScreenDistance.note(m) ?: return
        noticeQueue.addAll(lines)
        Log.i(tag, "said on the glasses: ${lines.joinToString(" / ")} (their own menu; nothing is sent)")
    }
    private var distanceLogged: String? = null
    private var adjustVersionSeen = -1
    private var adjustGlassesSeen: String? = null
    private var adjustLogged: String? = null
    private var glassesScreenLogged: String? = null

    /** Each eye's placement by the adjust; empty in 2D, null for an eye drawn as before. GL thread. */
    private var placements: List<com.hayeco.flipspaces.core.spatial.EyePlacement?> = emptyList()

    private fun activeAdjust(): com.hayeco.flipspaces.core.spatial.PictureAdjust =
        if (stereo == StereoMode.SIDE_BY_SIDE) pictureAdjust else com.hayeco.flipspaces.core.spatial.PictureAdjust.NONE

    /** Works out [placements] for the eyes in use: side by side (real 3D and "2D in 3D") only. */
    private fun placeEyes() {
        val sbs = stereo == StereoMode.SIDE_BY_SIDE && eyes.size > 1
        placements = if (!sbs || pictureAdjust.neutral) emptyList()
        else eyes.mapIndexed { i, e -> pictureAdjust.place(i, e, true)?.takeIf { !it.whole } }
    }

    /**
     * Each frame: a change saved by either trackpad ([EdgeCheckTest.adjustVersion]) or other glasses is read again,
     * logged once and applied to the eyes and the pointer. Also logs the glasses' own screen size and place from their
     * log when it changes, while their log is on.
     */
    private fun syncPictureAdjust() {
        com.hayeco.flipspaces.core.spatial.GlassesScreenLog.line(GlassesWarp.glassesFovFactor, GlassesWarp.glassesStatus)?.let {
            if (it != glassesScreenLogged) {
                glassesScreenLogged = it
                Log.i(tag, it)
            }
        }
        val version = EdgeCheckTest.adjustVersion
        val key = store.glasses?.key
        if (version == adjustVersionSeen && key == adjustGlassesSeen) return
        adjustVersionSeen = version
        adjustGlassesSeen = key
        // 0.5.59: the saved hand adjust has had no Menu since 0.5.36: logged, not
        // applied, and cleared for these glasses.
        val stored = store.pictureAdjust
        val saved = com.hayeco.flipspaces.core.spatial.ActiveAdjust.of(stored)
        if (!stored.neutral && saved.neutral) store.clearPictureAdjust()
        // 0.5.29: each eye centred for the glasses' 3D distance, in their 3D mode only.
        // 0.5.59 (fix 3): only a distance seen this session, else 4 m, and no screen centre offset.
        val distanceM = com.hayeco.flipspaces.core.spatial.ScreenDistance.inUse(distanceSeenM)
        screenDistanceM = distanceM
        val fix = if (store.distanceFix && stereo == StereoMode.SIDE_BY_SIDE && eyes.size > 1) {
            com.hayeco.flipspaces.core.spatial.ScreenDistance.correction(store.ipdMm, distanceM, store.screen(true).sizeFactor, com.hayeco.flipspaces.core.spatial.ScreenDistance.inUseFrom(distanceSeenM), store.model.tanPerPx1to1)
        } else null
        val fixLine = fix?.line() ?: "no correction for the glasses' 3D distance (${if (!store.distanceFix) "off on the Menu" else "not side by side"})"
        if (fixLine != distanceLogged) {
            distanceLogged = fixLine
            Log.i(tag, fixLine)
        }
        if (stereo == StereoMode.SIDE_BY_SIDE) distanceNote(distanceM)
        val next = com.hayeco.flipspaces.core.spatial.ScreenDistance.apply(saved, fix)
        val line = com.hayeco.flipspaces.core.spatial.ActiveAdjust.line(store.glasses?.label() ?: SpaceStore.UNNAMED_GLASSES, stored) +
            if (stereo == StereoMode.SIDE_BY_SIDE) " (applied side by side, eye halves ${eyes.firstOrNull()?.width ?: 0}x${eyes.firstOrNull()?.height ?: 0})" else " (kept for the glasses' 3D mode; 2D is drawn as it is)"
        if (line != adjustLogged) {
            adjustLogged = line
            Log.i(tag, line)
        }
        if (next == pictureAdjust) return
        pictureAdjust = next
        placeEyes()
        pointer.viewport = viewport.forEye(stereo, activeAdjust())
        for ((i, p) in placements.withIndex()) if (p != null) {
            Log.i(tag, "eye ${if (i == 0) "left" else "right"} picture %dx%d at %d, %d in its half; viewport %d,%d %dx%d (its whole half), picture NDC x %.3f..%.3f y %.3f..%.3f".format(
                java.util.Locale.ROOT, p.fullW, p.fullH, p.fullLeft, p.fullTop, p.vx, p.vy, p.vw, p.vh, p.ndcX0, p.ndcX1, p.ndcY0, p.ndcY1,
            ))
        }
    }

    /**
     * 0.5.27: the edge check ([com.hayeco.flipspaces.core.spatial.EdgeCheck]) on each
     * eye's picture, in white, straight into the window's framebuffer after everything else (and after the late warp),
     * so it marks the picture's own pixels whatever the head does. Scissored clears only: no shader, nothing sent.
     */
    private fun drawEdgeCheck() {
        if (!EdgeCheckTest.on) {
            if (edgeCheckDrawn != null) Log.i(tag, "edge check off")
            edgeCheckDrawn = null
            return
        }
        val tanX = kotlin.math.tan(frustum.horizontalFovDeg * com.hayeco.flipspaces.core.spatial.DEG / 2f)
        val tanY = kotlin.math.tan(frustum.verticalFovDeg * com.hayeco.flipspaces.core.spatial.DEG / 2f)
        val first = eyes.firstOrNull() ?: return
        val line = com.hayeco.flipspaces.core.spatial.EdgeCheck.line(first.width, first.height, tanX, tanY, eyes.size)
        if (line != edgeCheckDrawn) {
            edgeCheckDrawn = line
            Log.i(tag, line)
        }
        // 0.5.27: per eye, its big L or R, the numbered pixel ticks and the degree ticks. Worked out again only when
        // something they show changes.
        // 0.5.29 (the rectangle did not move while Mike tapped the adjust): the border and the
        // pixel ticks are drawn on the picture as placed (the adjust and the distance correction), like the degree ticks.
        val sbs = eyes.size > 1
        val only = EdgeCheckTest.eyes
        val key = "${eyes.joinToString { "${it.width}x${it.height}" }}/$tanX/$tanY/${activeAdjust().encode()}"
        if (key != edgeMarksKey) {
            edgeMarksKey = key
            edgeMarks = eyes.mapIndexed { i, eye ->
                val placed = placements.getOrNull(i)
                val label = if (sbs) (if (i == 0) 'L' else 'R') else null
                if (placed != null) {
                    com.hayeco.flipspaces.core.spatial.EdgeCheck.adjustedMarks(eye.width, eye.height, placed.fullLeft, placed.fullTop, placed.fullW, placed.fullH, tanX, tanY) +
                        (if (sbs) com.hayeco.flipspaces.core.spatial.PlacedEdgeCheck.pixelMarks(eye.width, eye.height, placed.fullLeft, placed.fullTop, placed.fullW, placed.fullH, label) else emptyList())
                } else {
                    com.hayeco.flipspaces.core.spatial.EdgeCheck.marks(eye.width, eye.height, tanX, tanY) +
                        (if (sbs) com.hayeco.flipspaces.core.spatial.EdgeCheck.pixelMarks(eye.width, eye.height, label) else emptyList())
                }
            }
            Log.i(tag, "edge check marks: " + eyes.indices.joinToString { i ->
                val p = placements.getOrNull(i)
                "${if (i == 0) "left" else "right"} eye's border ${if (p == null) "on its half's edges" else "on its picture, %dx%d at %d, %d in its half".format(java.util.Locale.ROOT, p.fullW, p.fullH, p.fullLeft, p.fullTop)}"
            })
        }
        if (sbs && only.name != edgeEyesLogged) {
            edgeEyesLogged = only.name
            Log.i(tag, "edge check on ${only.label}" + if (only != com.hayeco.flipspaces.core.spatial.EdgeEyes.BOTH) " (the other eye's half black)" else "")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        for ((i, eye) in eyes.withIndex()) {
            // 0.5.29: one eye at a time: the other eye's half is cleared black (see-through on the glasses).
            if (sbs && !only.shows(i)) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glScissor(eye.x, eye.y, eye.width, eye.height)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                continue
            }
            GLES30.glClearColor(1f, 1f, 1f, 1f)
            for (m in edgeMarks.getOrNull(i).orEmpty()) {
                // Marks are from the picture's top left; GL's scissor is from the bottom left.
                GLES30.glScissor(eye.x + m.x, eye.y + eye.height - m.y - m.h, m.w, m.h)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (seeThrough) GLES30.glClearColor(0f, 0f, 0f, 1f) else GLES30.glClearColor(0.05f, 0.06f, 0.08f, 1f)
    }

    /** 0.5.61: the edge fade's pass, its eye areas and widths kept from frame to frame, and the line last logged. GL thread. */
    private val edgeFadeGl = EdgeFadeGl()
    private val edgeFadeAreas = ArrayList<IntArray>(2)
    private val edgeFadeWidths = ArrayList<com.hayeco.flipspaces.core.spatial.EdgeFade.Widths>(2)
    private var edgeFadeMode: com.hayeco.flipspaces.core.spatial.EdgeFadeMode? = null
    private var edgeFadeW = -1
    private var edgeFadeH = -1
    private var edgeFadeCount = -1

    /**
     * 0.5.61 (Mike's test card session: the left eye's "wall" is exactly pixel 0 of its picture, which his left eye sees
     * past; black is see-through on the glasses): each eye's half of the window (the whole window for one picture) faded
     * to black over [com.hayeco.flipspaces.core.spatial.EdgeFadeMode]'s widths, wider on the outer edges. Drawn on the
     * window after the late warp, so the fade stays on the display's edges whatever the head does; the picture's
     * position and size are untouched. Never on the test card, which returns before this.
     */
    private fun drawEdgeFade() {
        val mode = EdgeFadeSwitch.mode(store)
        val w = viewport.surfaceWidth
        val h = viewport.surfaceHeight
        val count = if (eyes.size > 1) 2 else 1
        // 0.5.62 (allocation pass): compared field by field, no key string built every frame.
        if (mode != edgeFadeMode || w != edgeFadeW || h != edgeFadeH || count != edgeFadeCount) {
            edgeFadeMode = mode
            edgeFadeW = w
            edgeFadeH = h
            edgeFadeCount = count
            Log.i(tag, com.hayeco.flipspaces.core.spatial.EdgeFade.line(mode, w, h, count))
            edgeFadeAreas.clear()
            edgeFadeWidths.clear()
            val half = w / 2
            for (i in 0 until count) {
                edgeFadeAreas += if (count == 1) intArrayOf(0, 0, w, h) else if (i == 0) intArrayOf(0, 0, half, h) else intArrayOf(half, 0, w - half, h)
                edgeFadeWidths += com.hayeco.flipspaces.core.spatial.EdgeFade.widths(mode, i, count)
            }
        }
        if (mode == com.hayeco.flipspaces.core.spatial.EdgeFadeMode.OFF || w <= 0 || h <= 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        edgeFadeGl.fade(edgeFadeAreas, edgeFadeWidths)
        GLES30.glViewport(0, 0, w, h)
    }

    /** 0.5.60: the test card's mode and output size last drawn (null while off), and its marks for them. GL thread. */
    private var testCardKey: String? = null
    private var testCardMarks: List<com.hayeco.flipspaces.core.spatial.TestCard.Mark> = emptyList()

    /**
     * 0.5.60: called on the GL thread each time the test card shows (switched on, or its mode or the output's size
     * changed), after the renderer's own line; the Space logs its window and display from the UI thread.
     */
    @Volatile var onTestCardShown: (() -> Unit)? = null

    /**
     * 0.5.60 (Mike: the left eye's picture cut by a fixed vertical line on its outer edge with the Aura on the Flip):
     * [com.hayeco.flipspaces.core.spatial.TestCard] straight into the window's framebuffer: black, then each mark as a
     * scissored clear in its colour. No projection, no placement, no warp: the pixels are the window's own.
     */
    private fun drawTestCard() {
        val mode = TestCardSwitch.mode
        val w = viewport.surfaceWidth
        val h = viewport.surfaceHeight
        val key = "${mode.name}/${w}x$h"
        if (key != testCardKey) {
            testCardKey = key
            testCardMarks = com.hayeco.flipspaces.core.spatial.TestCard.marks(mode, w, h)
            Log.i(tag, com.hayeco.flipspaces.core.spatial.TestCard.line(mode, w, h))
            Log.i(tag, "test card: output surface ${w}x$h, display ${displayWidth}x$displayHeight (${if (w == displayWidth && h == displayHeight) "the surface covers the display" else "the surface does NOT cover the display"}), ${stereo.label}; " +
                "the scene's per-eye viewports: " + eyes.withIndex().joinToString { (i, e) ->
                    val p = placements.getOrNull(i)
                    "${if (eyes.size == 1) "one picture" else if (i == 0) "left eye" else "right eye"} %d,%d %dx%d (offset x %.4f m)".format(java.util.Locale.ROOT, e.x, e.y, e.width, e.height, e.offset.x) +
                        (p?.let { " placed %d,%d %dx%d, picture %dx%d at %d, %d".format(java.util.Locale.ROOT, it.vx, it.vy, it.vw, it.vh, it.fullW, it.fullH, it.fullLeft, it.fullTop) } ?: "")
                } + "; ${testCardMarks.size} marks; " + EdgeFadeSwitch.mode(store).let { f ->
                    "edge fade ${f.arg} (outer ${f.outerPx} px, nose ${f.innerPx} px, top and bottom ${f.edgePx} px) not drawn on the test card"
                })
            onTestCardShown?.invoke()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        var ink: com.hayeco.flipspaces.core.spatial.TestCard.Ink? = null
        for (m in testCardMarks) {
            if (m.ink != ink) {
                ink = m.ink
                when (m.ink) {
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.EDGE -> GLES30.glClearColor(1f, 0.15f, 0.15f, 1f)
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.INNER -> GLES30.glClearColor(1f, 1f, 0f, 1f)
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.TICK, com.hayeco.flipspaces.core.spatial.TestCard.Ink.NUMBER -> GLES30.glClearColor(1f, 1f, 1f, 1f)
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.NAME -> GLES30.glClearColor(0.3f, 0.9f, 1f, 1f)
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.CROSS -> GLES30.glClearColor(0.2f, 1f, 0.2f, 1f)
                    com.hayeco.flipspaces.core.spatial.TestCard.Ink.SPLIT -> GLES30.glClearColor(1f, 0.2f, 1f, 1f)
                }
            }
            // Marks are from the window's top left; GL's scissor is from the bottom left.
            GLES30.glScissor(m.x, h - m.y - m.h, m.w, m.h)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (seeThrough) GLES30.glClearColor(0f, 0f, 0f, 1f) else GLES30.glClearColor(0.05f, 0.06f, 0.08f, 1f)
    }

    /** 0.5.21: the record's pose in the glasses' frame, and the check of its yaw against theirs. */
    private val recordAlign = com.hayeco.flipspaces.core.spatial.GlassesFrameAlign()
    private val yawOffsetCheck = com.hayeco.flipspaces.core.spatial.YawOffsetCheck()
    private var recordFrames = 0L
    private var recordRecenters = 0
    private var recordTex = 0
    private var recordFbo = 0
    private var recordWidth = 0
    private var recordRgba = ByteArray(0)
    private var recordBuffer: java.nio.ByteBuffer? = null
    private var recordLoggedMs = 0L
    private var recordMisfit: String? = null
    private var recordTestLogged: com.hayeco.flipspaces.core.spatial.WarpRecordTest? = null

    /** 0.5.26: test D's one record, built on its first frame. */
    private var fixedTestRow: com.hayeco.flipspaces.core.spatial.FrameRecords.Row? = null

    /** 0.5.25: the last head pose a record carried, held for a frame with none, and how many frames were held so. */
    private var lastRecordPose: Quat? = null
    private var recordsHeld = 0L

    /**
     * 0.5.21: this frame's NORMAL record, drawn in the top two rows of the whole picture
     * (both eyes' halves in side by side) in the glasses' black and white code: the render pose in the
     * glasses' frame ([recordAlign]; aligned on the first frame after the glasses accept scene mode 1,
     * the joint recentre), each eye's FOV and the plane at the panels' depth. The row is uploaded to a
     * one row texture and blitted, nearest, onto rows H-2 and H-1 (GL's bottom-left origin).
     */
    private fun drawFrameRecord(tracked: Quat, presentNs: Long, startNs: Long, nowMs: Long) {
        val ref = HeadTracker.frameRef
        val recenters = GlassesWarp.recenters
        if (!recordAlign.aligned) {
            recordAlign.align(tracked, ref.jump, ref.epoch)
            yawOffsetCheck.start(nowMs, 0f)
            recordFrames = 0
            recordMisfit = null
            recordRecenters = recenters
            Log.i(GlassesWarp.TAG, "glasses warp: joint recentre: the record's yaw is 0 from this frame (Pocket XR's frame epoch ${ref.epoch}); pitch and roll from the shared gravity")
        } else if (recenters != recordRecenters) {
            // 0.5.25: the glasses' own log named a new recentre target (10279 is never sent): the record's yaw is zeroed with it.
            recordRecenters = recenters
            recordAlign.align(tracked, ref.jump, ref.epoch)
            yawOffsetCheck.start(nowMs, 0f)
            Log.i(GlassesWarp.TAG, "glasses warp: recentre $recenters (the glasses' new target): the record's yaw is 0 again from this frame, with the glasses' forward")
        }
        val realigns = recordAlign.realigns
        val pose = recordAlign.record(tracked, ref.jump, ref.epoch)
        if (recordAlign.realigns != realigns) Log.i(GlassesWarp.TAG, "glasses warp: Pocket XR's head fusion started over (epoch ${ref.epoch}); the record carries on from its last pose")
        yawOffsetCheck.sample(nowMs, Angles.of(pose.forward).yawDeg, HeadTracker.lyingStill)?.let { Log.i(GlassesWarp.TAG, it) }
        val w = viewport.surfaceWidth
        val h = viewport.surfaceHeight
        val tanX = kotlin.math.tan(frustum.horizontalFovDeg * com.hayeco.flipspaces.core.spatial.DEG / 2f)
        val tanY = kotlin.math.tan(frustum.verticalFovDeg * com.hayeco.flipspaces.core.spatial.DEG / 2f)
        val offsets = if (eyes.size > 1) eyes.map { it.offset } else emptyList()
        val submitNs = System.nanoTime()
        fun built(eyeOffsets: List<Vec3>) = com.hayeco.flipspaces.core.spatial.FrameRecords.build(
            pose, eyeOffsets, tanX, tanY, h, recordPlaneDepth(), recordFrames + 1,
            devicePoseTimeNs = GlassesWarp.toGlassesNs(presentNs),
            embeddedDeviceTimeNs = GlassesWarp.toGlassesNs(submitNs),
            sysPoseTimeNs = presentNs,
            startRenderTimeNs = startNs,
            submitTimeNs = GlassesWarp.toGlassesNs(submitNs),
        )
        recordFrames++
        // 0.5.24: a 3D record test draws its own record, or (test A) none at all.
        val test = GlassesWarp.recordTest
        val record: com.hayeco.flipspaces.core.spatial.FrameRecord
        val laid: com.hayeco.flipspaces.core.spatial.FrameRecords.Row
        val stereoRow: Boolean
        if (test != null) {
            GlassesWarp.testFrames++
            val mono = built(emptyList())
            val stereoRecord = built(offsets)
            // 0.5.26: test D builds its record on its first frame and draws those bytes after.
            if (test != recordTestLogged) fixedTestRow = null
            val row = if (test == com.hayeco.flipspaces.core.spatial.WarpRecordTest.FIXED) {
                fixedTestRow ?: com.hayeco.flipspaces.core.spatial.FrameRecords.testRow(test, mono, stereoRecord, w).also { fixedTestRow = it }
            } else {
                com.hayeco.flipspaces.core.spatial.FrameRecords.testRow(test, mono, stereoRecord, w)
            }
            if (test != recordTestLogged) {
                recordTestLogged = test
                Log.i(GlassesWarp.TAG, "glasses warp: ${test.title}: " + if (row == null) "no record is drawn in the ${w}x$h picture's top rows" else if (test == com.hayeco.flipspaces.core.spatial.WarpRecordTest.FIXED)
                    "the two eye record is ${row.bytes} bytes, built on this frame and drawn unchanged on every frame of the test, a copy at x = 0 of each eye's half, in rows ${h - 2} and ${h - 1} from the bottom" else
                    "the ${if (test == com.hayeco.flipspaces.core.spatial.WarpRecordTest.ONE_EYE) "single eye" else "two eye"} record is ${row.bytes} bytes and takes ${row.usedPx} of the $w pixels of the row, once from x = 0 (nothing at x = ${w / 2}), in rows ${h - 2} and ${h - 1} from the bottom")
            }
            if (row == null) return
            record = if (test == com.hayeco.flipspaces.core.spatial.WarpRecordTest.ONE_EYE) mono else stereoRecord
            laid = row
            stereoRow = test == com.hayeco.flipspaces.core.spatial.WarpRecordTest.FIXED
        } else {
            recordTestLogged = null
            // 0.5.23: the stereo record in its compact form, sized from one eye's width.
            record = com.hayeco.flipspaces.core.spatial.FrameRecords.forWire(built(offsets))
            stereoRow = offsets.isNotEmpty()
            laid = com.hayeco.flipspaces.core.spatial.FrameRecords.rowFor(record, w, stereoRow)
        }
        val row = laid.pixels
        if (row == null) {
            val why = "${if (stereoRow) "stereo" else "mono"} record ${laid.bytes} bytes over ${laid.capacity} (the ${laid.decodeWidth} px the glasses decode): not sent"
            if (recordMisfit != why) Log.w(GlassesWarp.TAG, "glasses warp: $why; frames go without it")
            recordMisfit = why
            return
        }
        if (recordTex == 0 || recordWidth != w) {
            if (recordTex != 0) GLES30.glDeleteTextures(1, intArrayOf(recordTex), 0)
            if (recordFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(recordFbo), 0)
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            recordTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, recordTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, 1)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glGenFramebuffers(1, ids, 0)
            recordFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, recordFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, recordTex, 0)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            recordWidth = w
            recordRgba = ByteArray(w * 4)
            recordBuffer = java.nio.ByteBuffer.allocateDirect(w * 4).order(java.nio.ByteOrder.nativeOrder())
            Log.i(GlassesWarp.TAG, "glasses warp: record row texture ${w}x1 for a ${w}x$h picture; the ${if (stereoRow) "stereo" else "mono"} record is ${laid.bytes} of ${laid.capacity} bytes and takes ${laid.usedPx} of the ${laid.decodeWidth} pixels the glasses decode" +
                (if (stereoRow) ", from x = 0 of each eye's half" else "") + ", in rows ${h - 2} and ${h - 1} from the bottom (the top two)")
        }
        com.hayeco.flipspaces.core.spatial.BwCode.rgba(row, recordRgba)
        val buf = recordBuffer!!
        buf.clear()
        buf.put(recordRgba)
        buf.flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, recordTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, recordFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, w, 1, 0, h - com.hayeco.flipspaces.core.spatial.BwCode.ROWS, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // 0.5.25: kept, so a drop out of scene mode 1 in the glasses' log names the records before it.
        GlassesWarp.recordDrawn(record.frameNumber, record.devicePoseTimeNs)
        if (nowMs - recordLoggedMs >= 10_000L) {
            recordLoggedMs = nowMs
            val (yaw, pitch, roll) = pose.yawPitchRoll()
            Log.i(GlassesWarp.TAG, "glasses warp: record %d: pose yaw %.2f pitch %.2f roll %.2f deg in the glasses' frame, %s, FOV tan %.4f x %.4f (top %.4f), plane %.2f m, device pose time %d ns".format(
                java.util.Locale.ROOT, record.frameNumber, yaw, pitch, roll, if (offsets.isEmpty()) "mono, one eye record" else "stereo, two eye records", tanX, tanY, record.eyes[0].fov.top, record.eyes[0].planePoint.length, record.devicePoseTimeNs,
            ))
        }
    }

    /** The glasses stopped warping (a restore): the next start aligns again. */
    private fun endFrameRecords(@Suppress("UNUSED_PARAMETER") nowMs: Long, why: String = "the glasses stopped warping") {
        // 0.5.24 (`68270.554 s` here against `69663.339 s` on the line before): on the warp's own
        // clock (elapsedRealtime), not the frame's (uptime), and said at every end, not only at the switch off.
        val t = SystemClock.elapsedRealtime()
        Log.i(GlassesWarp.TAG, "glasses warp at %d.%03d s: records stopped after %d frames (%d with the last pose held, no head pose that frame), %s (%s)".format(java.util.Locale.ROOT, t / 1000, t % 1000, recordFrames, recordsHeld, why, yawOffsetCheck.summary()))
        recordsHeld = 0L
        recordAlign.reset()
        recordTestLogged = null
    }

    /** 0.5.24: the Space is going (its surface or the activity): the records' end is said here when no frame will say it. */
    fun endRecordsAtExit(why: String) {
        if (recordAlign.aligned) endFrameRecords(0L, why)
        // 0.5.99: a recording under way is finished and kept as the Space stops.
        recorder.release()
        wasRecording = false
    }

    /**
     * 0.5.99: the recording's frame ([SpaceRecorder]): the left eye (the whole picture in 2D) copied to the encoder; then,
     * while recording, a small red dot near each eye's top right corner, on the glasses only. A recording that ends says
     * where it was saved, on the glasses.
     */
    private fun recordFrame() {
        recorder.wanted = SpaceSession.recordWanted
        val w = viewport.surfaceWidth
        val h = viewport.surfaceHeight
        val eyeW = if (eyes.size > 1) w / 2 else w
        val on = recorder.frame(0, 0, eyeW, h)
        if (on) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0.9f, 0.16f, 0.16f, 1f)
            val dot = (h * RECORD_DOT_SHARE).toInt().coerceAtLeast(6)
            val margin = (h * RECORD_DOT_MARGIN_SHARE).toInt()
            for (e in 0 until (if (eyes.size > 1) 2 else 1)) {
                GLES30.glScissor(e * eyeW + eyeW - margin - dot, h - margin - dot, dot, dot)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
        }
        if (wasRecording && !on) SpaceSession.recordSaved?.let { saved ->
            spaceNotice = saved
            spaceNoticeUntilMs = SystemClock.uptimeMillis() + WARP_OFF_NOTICE_MS
        }
        wasRecording = on
    }

    /** The warp plane's depth: the window nearest the gaze, else [com.hayeco.flipspaces.core.spatial.FrameRecords.DEFAULT_PLANE_M]. */
    private fun recordPlaneDepth(): Float {
        val f = head.forward
        return layout.panels.minByOrNull { p -> -(p.centre.normalized() dot f) }?.distanceM
            ?: com.hayeco.flipspaces.core.spatial.FrameRecords.DEFAULT_PLANE_M
    }

    /**
     * 0.5.12: the late draw runs while the vsyncs come from a display at the glasses' rate; else
     * the frame is drawn at its start as in 0.5.11. Why is logged each time it changes.
     */
    private fun lateDrawUsable(nowNs: Long): Boolean {
        val v = late.newest
        val hz = pacer.refreshHz
        // 0.5.13: a Choreographer at another rate is split into the glasses' vsyncs
        // ([GlassesVsync]); only a rate that is no whole fraction of theirs keeps the late draw off.
        // 0.5.19 ("late draw on ... 59/61/62 Hz" 13 times, and off once
        // for one callback): a Choreographer rate off the glasses' keeps the late draw on at the glasses'
        // rate unless it stays off for over [RATE_HOLD_NS] (1 s).
        val rateOff = v != null && hz > 0f && kotlin.math.abs(v.periodNs / 1e6 - 1000.0 / hz) > 0.03 * 1000.0 / hz
        val hard = when {
            !store.lateDraw -> "off in the settings"
            v == null && glassesVsync.reason == null -> "no vsync from Choreographer yet"
            v != null && nowNs - v.vsyncNs > 200_000_000L -> "Choreographer's vsyncs stopped"
            else -> null
        }
        // 0.5.20: the vsync route's refusal and a rate off the glasses' are
        // both held for [LateDrawGate.holdNs] (1 s); a held wobble is logged once, when it ends.
        val soft = glassesVsync.reason
            ?: if (rateOff) "the vsyncs run at %.0f Hz, the glasses at %.0f Hz".format(1e9 / v!!.periodNs, hz) else null
        val reason = lateGate.decide(nowNs, hard, soft, if (v != null) 1e9 / v.periodNs else 0.0)
        lateGate.takeWobbleLine()?.let {
            Log.i(tag, it)
            // 0.5.21: the timeline is back in phase: the fence offset goes to 0 at once.
            fence.wobble()
        }
        lateOffReason = reason
        // 0.5.19: the line names the glasses' rate while a short Choreographer wobble is held; 0.5.20:
        // and keeps its last text, so a held wobble writes no line.
        val shownPeriod = if ((rateOff || lateGate.holding) && hz > 0f) (1e9 / hz).toLong() else v?.periodNs ?: 0L
        // 0.5.23: while the Choreographer follows another display, the present error is
        // counted on the glasses' fitted vsync grid (the one each pose is predicted for), not on its timeline.
        late.glassesGrid = if ((rateOff || lateGate.holding) && fence.gridPeriod > 0L) glassesGridFn else null
        val line = when {
            reason != null -> "late draw off: $reason; the pose is predicted from frame start as in 0.5.11"
            lateGate.holding -> lateReason ?: "late draw on: each frame is drawn just before its deadline at %.0f Hz (held through a Choreographer wobble)".format(1e9 / shownPeriod.coerceAtLeast(1L))
            else -> "late draw on: each frame is drawn just before its deadline at %.0f Hz (%s)".format(1e9 / shownPeriod.coerceAtLeast(1L), glassesVsync.line(shownPeriod))
        }
        if (line != lateReason) {
            lateReason = line
            Log.i(tag, line)
        }
        return reason == null
    }

    /**
     * 0.5.57 (Mike's OK of 10/28): the Space's frames timed on the glasses' own refreshes
     * ([com.hayeco.flipspaces.core.spatial.GlassesFrameClock]): their period and phase locked to SurfaceFlinger's present
     * fences, one frame per refresh, each done a lead before the phone's usual commit and posed for the refresh that commit
     * feeds. It replaces 0.5.56's glasses refresh gate, which held Choreographer's early ticks a period and posed them about
     * 8 ms off. The same whether a phone screen is on or off and whatever the "Glasses set the frame clock (test)" switch
     * says: with the glasses as the pacesetter the commits simply sit still on their grid. GL thread.
     */
    private val frameClock = com.hayeco.flipspaces.core.spatial.GlassesFrameClock()

    /** 0.5.58: each frame's own present for [frameClock]'s depth, within a quarter second. GL thread. */
    private val frameTimes = FrameTimestamps(tag)

    /** 0.5.57: this frame is planned on [frameClock]. */
    private var onClock = false

    /** 0.5.57: the draw budget at the clock's period: 0.5.55's, the final pass's p99 plus its margin while the late warp runs. */
    private fun clockBudgetNs(): Long = late.budget.budgetNs(frameClock.period.toLong().takeIf { it > 0L } ?: displayPeriodNs.coerceAtLeast(1L))

    /** 0.5.23: a frame timeline present put on the glasses' own vsync grid, as the pose's present is. */
    private val glassesGridFn: (Long) -> Long = { fence.snap(it + fence.offsetNs) }

    /** 0.5.24: what the pose's present adds to the plan's: the fences' offset, unless the plan is locked to them already. */
    private fun fenceExtra(plan: com.hayeco.flipspaces.core.spatial.DrawPlan): Long = if (plan.shiftNs == 0L) fence.offsetNs else 0L

    /** 0.5.20: the late draw's on and off decision, with its wobble hold. */
    private val lateGate = com.hayeco.flipspaces.core.spatial.LateDrawGate(RATE_HOLD_NS)

    /** 0.5.12: sleeps to [ns] (System.nanoTime), the last 0.1 ms yielding so the wake is on time. */
    private fun sleepUntil(ns: Long) {
        while (true) {
            val left = ns - System.nanoTime()
            if (left <= 0L) return
            if (left > 300_000L) java.util.concurrent.locks.LockSupport.parkNanos(left - 100_000L) else Thread.yield()
        }
    }

    /** 0.5.12: waits for the GPU to finish this frame (a fence, at most a period past its deadline); when it did. */
    private fun gpuDone(plan: com.hayeco.flipspaces.core.spatial.DrawPlan): Long {
        val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (sync == 0L) {
            GLES30.glFinish()
            return System.nanoTime()
        }
        val timeout = (plan.deadlineNs + plan.periodNs - System.nanoTime()).coerceIn(0L, 50_000_000L)
        GLES30.glClientWaitSync(sync, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, timeout)
        GLES30.glDeleteSync(sync)
        return System.nanoTime()
    }

    /** 0.5.19: frames drawn since the last timing line, for the curved rectangles a frame. */
    private var framesSinceTiming = 0

    /** 0.5.19: the present model against SurfaceFlinger's present fences. GL thread. */
    private val fence = com.hayeco.flipspaces.core.spatial.PresentFence()

    /** 0.5.25: the split callback's frames on the glasses' fitted vsync grid. GL thread. */
    private val splitGrid = com.hayeco.flipspaces.core.spatial.SplitGridStamp()
    private var fenceAsked = false

    /**
     * 0.5.19: with every timing line in the late draw, SurfaceFlinger's frame table for the Space is read
     * through the panel service and matched to the frames drawn ([PresentFence]). Not with a display
     * delay measured on these glasses: that delay already holds whatever the fence would add.
     */
    private fun askFence() {
        if (!lateNow || fenceAsked || !PanelHost.ready) return
        // At most every 1.5 s (0.5.21: 5 s let the drift run 0.5 ms between tables);
        // each read is two dumpsys runs in the shell's process.
        // 0.5.57: every 2 s from the render loop, for the glasses frame clock; a table holds the last 128 frames (2.1 s at
        // 60 Hz), so every refresh the glasses show is seen once.
        val now = SystemClock.uptimeMillis()
        if (now - fenceAskedMs < FENCE_EVERY_MS) return
        fenceAskedMs = now
        // The model's offset is not followed with a display delay measured on these glasses; the clock still locks to the table.
        val follow = !store.delayCalibrated
        if (!follow && !fenceSkipLogged) {
            fenceSkipLogged = true
            Log.i(tag, "present fences not followed: the display delay was measured on these glasses (${store.predictMs} ms), which holds the scan-out's offset already; the glasses frame clock still locks to them")
        }
        fenceAsked = true
        PanelHost.presentTimes { text ->
            onGl {
                fenceAsked = false
                val t = com.hayeco.flipspaces.core.spatial.SfLatency.parse(text)
                val logNow = SystemClock.uptimeMillis() - fenceLoggedMs >= FENCE_LOG_MS
                if (t == null) {
                    if (logNow) {
                        fenceLoggedMs = SystemClock.uptimeMillis()
                        Log.i(tag, "present fences: no table (${text.take(160)})")
                    }
                    return@onGl
                }
                frameClock.onTable(t)
                if (!follow) return@onGl
                // 0.5.24 (a 16.67 ms grid on a 120 Hz display): read with the glasses' own mode period.
                fence.measure(t, displayPeriodNs)
                // 0.5.25: and, in 2D from a split callback, how many frames went on the glasses' grid.
                if (logNow) {
                    fenceLoggedMs = SystemClock.uptimeMillis()
                    Log.i(tag, fence.line() + if (splitGrid.placed > 0L) "; ${splitGrid.line()}" else "")
                }
            }
        }
    }

    private var fenceSkipLogged = false
    private var fenceAskedMs = 0L
    private var fenceLoggedMs = 0L

    /** 0.5.19: "; curved windows N, M curved rectangles a frame (K mesh vertices each)", or nothing with none curved. */
    private fun curvedLine(): String {
        val draws = GlKit.takeCurvedDraws()
        val frames = framesSinceTiming.coerceAtLeast(1)
        framesSinceTiming = 0
        val curved = layout.panels.count { PanelCurve.isCurved(it) }
        if (curved == 0 && draws == 0) return ""
        // 0.5.20 (29.6 a frame for one window against CHANGES' 12): the count
        // is every curved rectangle drawn in both eyes, a held window's 8 frame brackets and its guide
        // included, so it is given per eye too.
        return "; curved windows %d%s, %.1f curved rectangles a frame, %.1f per eye (body, controls, and while held its frame and guide; %d mesh vertices each)".format(
            java.util.Locale.ROOT, curved, if (layout.wrap != null) " (the Space wrapped at %.2f m)".format(java.util.Locale.ROOT, layout.wrap!!.radiusM) else "",
            draws.toDouble() / frames, draws.toDouble() / frames / eyes.size.coerceAtLeast(1), com.hayeco.flipspaces.core.spatial.CurveMesh().vertexCount,
        )
    }

    /** 0.5.12: the head timing and late draw lines, with every perf line. */
    private fun logTiming() {
        val t = com.hayeco.flipspaces.core.spatial.HeadTimingLine(
            refreshHz = pacer.refreshHz.takeIf { it > 0f } ?: (1000f / pacer.snapped()),
            lateDraw = lateNow,
            poseToPhotonsP50Ms = poseToPhotons.median(),
            poseToPhotonsP95Ms = poseToPhotons.percentile(0.95),
            imuAgeP50Ms = imuAge.median(),
            imuAgeP95Ms = imuAge.percentile(0.95),
            wakeLateP99Ms = wakeLate.percentile(0.99),
            glassesDelayMs = glassesDelayMs().toFloat(),
            calibrated = store.delayCalibrated,
            offReason = lateOffReason,
            glasses = glasses?.label(),
            vsyncRoute = glassesVsync.route.takeIf { it != com.hayeco.flipspaces.core.spatial.GlassesVsync.Route.NONE }?.name?.lowercase(),
        ).line()
        Log.i(tag, t)
        if (lateNow) {
            // 0.5.15: the present error is against Choreographer's expected present: the glasses (a
            // follower display) give SurfaceFlinger no present fences, so it is not a measured scan-out.
            // 0.5.19: plus the offset SurfaceFlinger's present fences measured ([PresentFence]).
            // 0.5.24: locked to the fences, the offset moves the whole plan and adds no latency.
            val lock = fence.lockNs
            Log.i(tag, late.line() + "; model pose to photons %.1f ms; present times are the frame timeline's expected present %+.2f ms from SurfaceFlinger's present fences (%d tables used)%s".format(
                java.util.Locale.ROOT, (late.modelPoseToPhotonsMs(glassesDelayMs()) ?: 0.0) + (if (lock == 0L) fence.offsetNs / 1e6 else 0.0), fence.offsetNs / 1e6, fence.used,
                if (lock != 0L) ", the late draw locked %+.2f ms to them".format(java.util.Locale.ROOT, lock / 1e6) else "",
            ))
            // 0.5.57: the glasses frame clock: its lock, the phone's commits, and what the glasses showed.
            // 0.5.58: with how each frame's present was read and SurfaceFlinger's pacesetter, so a
            // force left in place shows in minutes.py.
            Log.i(tag, frameClock.line(System.nanoTime()) + "; ${frameTimes.line()}; ${GlassesPacesetter.pacesetterNote(context)}")
            // 0.5.25: the per-eye late warp's frames, and how old the shown scene was at its present.
            if (warpWas) Log.i(tag, lateWarp.line() + "; the shown scene's age at its present p50 %.1f ms p99 %.1f ms; the budget above times the warp pass alone".format(java.util.Locale.ROOT, warpAgeMs.median(), warpAgeMs.percentile(0.99)))
        }
        // 0.5.16: the passes' own GPU time next to the pose to fence figure.
        Log.i(
            tag,
            gpuTimes.line(if (lateNow) late.budget.p50Ms else null, if (lateNow) late.budget.p99Ms else null) + (if (gpuTimer.dropped > 0) "; ${gpuTimer.dropped} results not in after 4 frames" else "") +
                "; the window buffer's dequeue (CPU, the first clear) p50 %.2f p99 %.2f ms; clear and copies flushed before the late sleep %s".format(java.util.Locale.ROOT, dequeueWait.median(), dequeueWait.percentile(0.99), if (lateNow) "yes" else "no (late draw off)") +
                // 0.5.19: the curved windows' share of the eye passes, for the draw cost on the glasses.
                curvedLine(),
        )
        gpuTimes.clear()
        // 0.5.18: the draw against the thermal status, for the next test.
        if (lateNow) Log.i(tag, com.hayeco.flipspaces.core.spatial.ThermalGuard.drawLine(thermalStatus, late.budget.p50Ms, late.budget.p99Ms, late.budget.missShare, ThermalGuard.panelFps(thermalStatus, false)))
        // 0.5.26 (Mike: "everything is a bit choppy"): the refreshes with a fresh frame, and each window's frames.
        logPacing()
        // 0.5.55: the render thread's priority now (the system may change it) and its hint session's reports.
        Log.i(tag, "render thread: priority %d; %s".format(java.util.Locale.ROOT, android.os.Process.getThreadPriority(android.os.Process.myTid()), boost?.takeReportLine() ?: "no hint session before Android 12"))
        // 0.5.26: the hand model leaves the GPU when the Space waits behind it ([com.hayeco.flipspaces.core.spatial.SpaceGpuGuard]).
        if (lateNow) {
            val waitP90 = (late.budget.p90Ms - gpuTimes.totalP50Ms).coerceAtLeast(0.0)
            spaceGpuGuard.window(late.budget.missShare, waitP90, SpaceSession.handModelOnGpu)?.let {
                Log.i(tag, it)
                SpaceSession.handsOffGpu = true
            }
        }
        compositionProbe.maybeRun(stereo == StereoMode.SIDE_BY_SIDE)
        SpaceSession.timingLine = t
        // 0.5.14: for the hand numbers' frame to pointer.
        if (poseToPhotons.count > 0) SpaceSession.poseToPhotonsP50Ms = poseToPhotons.median()
        pointer.handIntervalMs()?.let { SpaceSession.handIntervalMs = it.toDouble() }
    }

    // ---- 0.5.12: the display delay check ----

    private var delayYaw: Float? = null
    private var delayLabel: LabelTexture? = null
    private var delayLabelMs = -1

    /** A thin line fixed in the room where the head looked when the check began, and how to use it. */
    private fun drawDelayCheck(viewProj: Mat4, head: Quat) {
        if (!SpaceSession.delayCheck) {
            delayYaw = null
            return
        }
        val yaw = delayYaw ?: Angles.of(head.forward).yawDeg.also { delayYaw = it }
        val dir = Vec3.direction(yaw, 0f)
        val at = dir * 1.5f
        val right = (dir cross Vec3.UP).normalized()
        val up = right cross dir
        drawColor(viewProj * Mat4.basis(at, right, up, -dir, 0.004f, 0.45f), floatArrayOf(1f, 1f, 1f, 1f), circle = false)
        val tex = delayLabel ?: LabelTexture(1024, 128).also { delayLabel = it }
        if (delayLabelMs != store.predictMs) {
            delayLabelMs = store.predictMs
            tex.set(
                listOf("Display delay ${store.predictMs} ms: turn your head gently left and right", "Move the delay on the phone until the line stays put, then Save run"),
                Color.argb(220, 20, 24, 30), Color.WHITE, 30f, centred = true,
            )
        }
        drawLabel(tex.id, viewProj * Mat4.basis(at - up * 0.55f, right, up, -dir, 0.3f, 0.0375f), 0.95f)
    }

    // ---- 0.5.15: the level calibration ----

    private var levelLabel: LabelTexture? = null

    /**
     * A line across the middle of the view, fixed to the display (not the room), and how to use
     * it: the user tilts their head until it lies on a level edge at eye height and taps Save level
     * on the phone ([HeadTracker.takeLevelCalibration]). [eyeProj] is the eye's projection (with its
     * offset), so the line is drawn in head space.
     */
    private fun drawLevelCheck(eyeProj: Mat4) {
        if (!SpaceSession.levelCheck) return
        val saved = rowFix
        rowFix = null
        val at = Vec3(0f, 0f, -1.5f)
        drawColor(eyeProj * Mat4.basis(at, Vec3.RIGHT, Vec3.UP, Vec3(0f, 0f, 1f), 1.2f, 0.004f), floatArrayOf(0.3f, 1f, 0.45f, 1f), circle = false)
        // Ticks at the ends, so the line's ends show against the edge.
        for (x in listOf(-0.6f, 0.6f)) drawColor(eyeProj * Mat4.basis(Vec3(x, 0f, -1.5f), Vec3.RIGHT, Vec3.UP, Vec3(0f, 0f, 1f), 0.004f, 0.06f), floatArrayOf(0.3f, 1f, 0.45f, 1f), circle = false)
        val tex = levelLabel ?: LabelTexture(1024, 128).also {
            levelLabel = it
            it.set(
                listOf("Level: tilt your head until the green line lies on a level edge at eye height", "Hold still, then tap Save level on the phone"),
                Color.argb(220, 20, 24, 30), Color.WHITE, 30f, centred = true,
            )
        }
        drawLabel(tex.id, eyeProj * Mat4.basis(Vec3(0f, -0.25f, -1.5f), Vec3.RIGHT, Vec3.UP, Vec3(0f, 0f, 1f), 0.6f, 0.075f), 0.95f)
        rowFix = saved
    }

    /**
     * 0.5.15: the timing model in use for the perf and chain
     * lines. While the late draw runs, the frame pacer's "shown N ms after it starts, stamped N ms
     * ahead" is not what happens, so the late draw's own model is given instead.
     */
    private fun timingModelLine(): String {
        if (!lateNow) return pacer.line()
        val delay = glassesDelayMs()
        val model = late.modelPoseToPhotonsMs(delay)
        return String.format(
            java.util.Locale.ROOT,
            "late draw model: pose sampled %.1f ms before the latch deadline, pose to photons %.1f ms (budget + deadline to present + %.1f ms glasses delay)%s",
            late.budget.budgetNs(late.newest?.periodNs ?: 16_666_667L) / 1e6, model ?: 0.0, delay,
            if (warpWas) ", the pose taken for the late warp's pass" else "",
        )
    }

    /**
     * 0.5.10: tells SurfaceFlinger when this frame is meant to be seen. A frame that would show
     * after a newer one is due is dropped rather than shown late, so the queue never holds a frame
     * drawn for an older head pose. Logged once if the driver refuses it.
     */
    private fun stampPresentTime(presentNs: Long) {
        if (presentStampFailed) return
        val ok = runCatching {
            EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), presentNs)
        }.getOrDefault(false)
        if (!ok) {
            presentStampFailed = true
            Log.w(tag, "frames are not stamped with their present time (eglPresentationTimeANDROID refused, EGL error 0x${Integer.toHexString(EGL14.eglGetError())}); the pose is still predicted to it")
        }
    }

    /** 0.5.3: each panel's images for the perf line: taken off the display, replaced unread, held now, copied. */
    private fun imagesLine(): String = panels.values.joinToString("") { p ->
        val m = p.src.mailbox
        "; panel ${p.panel.id} images ${m.offered} taken, ${m.dropped} replaced, ${m.held} held, ${p.src.copies} copied"
    }

    // ---- 0.5.3: the watchdog's steps (SpaceWatchdog, FrameStallGuard) ----

    /** Watchdog thread: frames stopped. Nothing of ours may keep SurfaceFlinger waiting. */
    private fun shedForStall() {
        SpaceSession.stalled = true
        val f = frames ?: return
        val held = f.all.joinToString { s -> "${s.mailbox.held} held, last image ${SystemClock.uptimeMillis() - s.lastImageMs} ms ago" }
        f.shedAll()
        Log.w(WATCHDOG_TAG, "panels before the shed: ${held.ifEmpty { "none" }}")
    }

    /** Watchdog thread: still stalled. The panel displays go (PanelHost works on its own thread). */
    private fun releaseForStall() {
        val ids = openDisplays.toList()
        stallReleased += ids
        ids.forEach { closeDisplay(it) }
        Log.w(WATCHDOG_TAG, "released panel displays ${ids.ifEmpty { listOf("none") }}")
    }

    /** Watchdog thread: frames have run again for the backoff. */
    private fun recoverFromStall() {
        frames?.resumeAll()
        SpaceSession.stalled = false
        recovered = true
    }

    @Volatile private var recovered = false

    /** GL thread: panels whose displays the watchdog released reopen, through [openPending]. */
    private fun reopenAfterStall() {
        if (!recovered) return
        recovered = false
        if (stallReleased.isEmpty()) return
        for (p in panels.values) {
            val id = p.displayId ?: continue
            if (!stallReleased.remove(id)) continue
            p.state = PanelState.Waiting("Reopening after the Space stalled")
            p.surfaceFresh = false
            Log.i(WATCHDOG_TAG, "panel ${p.panel.id} ${p.panel.label}: reopening (its display $id was released in the stall)")
        }
        stallReleased.clear()
    }

    /**
     * 0.5.2: sends the panel display resizes the gate lets through, the last size. 0.5.8: one per
     * panel per 150 ms while the size keeps changing, each after the app drew at the size before.
     * The binder calls run on PanelHost's own thread, never this one.
     */
    /**
     * GL thread. A game has the glasses: each window's display is left with no surface, so Android turns it off and its
     * app stops as it would with the phone's screen off (video pauses, nothing draws), and the heat and GPU go to the
     * game. After the game each display gets its surface back and the app carries on where it was. Before, every
     * window kept drawing behind the game, to readers nobody read.
     */
    fun panelsForGame(paused: Boolean) {
        if (paused) {
            val nominal = if (displayPeriodNs > 0L) displayPeriodNs else 16_666_667L
            gameClock = com.hayeco.flipspaces.core.spatial.VblankLock(nominal)
            gameClockFed = 0L
            gameClockLoggedMs = 0L
        } else if (gameClock != null) {
            gameClock = null
            // Monado's pacer goes back to its own clock.
            runCatching { org.freedesktop.monado.ipc.PocketFeed.nativeVblank(0L, 0L) }
        }
        var n = 0
        for (p in panels.values) {
            val display = p.displayId ?: continue
            n++
            if (paused) {
                PanelHost.clearSurface(display) { err -> if (err != null) Log.w(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: not paused for the game: $err") }
            } else {
                PanelHost.setSurface(display, p.surface) { err -> if (err != null) problem = "Back from the game ${p.panel.label}: $err" }
            }
        }
        Log.i(PANEL_TAG, if (paused) "$n windows paused for the game (their displays off)" else "$n windows back from the game")
    }

    private fun resizeDisplays(nowMs: Long) {
        for ((id, size) in resizeGate.due(nowMs) { panels[it]?.drewAtSize ?: true }) {
            val p = panels[id] ?: continue
            val display = p.displayId ?: continue
            val (w, h) = size
            val dpi = resizeGate.appliedDpi(id)
            // 0.5.30: the size the app had before, for the relayout nudge ([com.hayeco.flipspaces.core.spatial.ResizeRelayout]).
            val before = com.hayeco.flipspaces.core.spatial.PanelDisplaySpec(p.width, p.height, p.dpi)
            if (w != p.width || h != p.height) p.resize(w, h)
            p.dpi = dpi
            val live = if (pointer.grabOn(id) != null) " (live, still held)" else ""
            val spec = com.hayeco.flipspaces.core.spatial.PanelDisplaySpec(w, h, dpi)
            Log.i(PANEL_TAG, "panel $id ${p.panel.label}: display $display resized to ${spec.line()}$live${if (wideView.isOn(p.panel.packageName)) ", Wide view on" else ""}${appDensity.smallestDp(p.panel.packageName)?.let { ", Fit video (its own %.0f dp smallest width)".format(java.util.Locale.ROOT, it) } ?: ""}")
            PanelHost.resize(display, w, h, dpi) { err -> if (err != null) problem = "Resize ${p.panel.label}: $err" }
            // 0.5.3: then onto the new reader at the new size (the host runs calls in order).
            PanelHost.setSurface(display, p.surface) { err -> if (err != null) problem = "Resize ${p.panel.label}: $err" }
            // 0.5.22: the new size is a configuration change for the app: read what it asks for after it.
            if (p.panel.isApp) shapeSoon(p, SHAPE_AFTER_LAUNCH_MS)
            // 0.5.23: after the release, that read also says whether the app took the size ([checkFit]).
            if (p.panel.isApp && live.isEmpty()) {
                p.fitArmed = true
                p.fitNudges = 0
            }
            // 0.5.30 (Mike: Twitch's player keeps its old size in a resized window): a released resize that only changed
            // the app's density (every resize with Fit video) is followed by a real size change, so it lays out again.
            if (p.panel.isApp) {
                if (live.isNotEmpty()) relayout.live(id)
                else if (relayout.released(id, before, spec, nowMs, fitVideo = appDensity.smallestDp(p.panel.packageName) != null)) {
                    Log.i(PANEL_TAG, "panel $id ${p.panel.label}: only its density changed (${before.line()} to ${spec.line()}); a relayout nudge follows in ${com.hayeco.flipspaces.core.spatial.ResizeRelayout.NUDGE_AFTER_MS} ms")
                }
            }
        }
        for ((id, spec) in relayout.due(nowMs) { grabbedNow(it) || resizeGate.pending(it) }) {
            val p = panels[id] ?: continue
            val display = p.displayId ?: continue
            Log.i(PANEL_TAG, "panel $id ${p.panel.label}: relayout nudge, display $display to ${spec.line()}")
            PanelHost.resize(display, spec.widthPx, spec.heightPx, spec.dpi) { err -> if (err != null) problem = "Relayout ${p.panel.label}: $err" }
        }
    }

    /** 0.5.30: the relayout nudge after a density only resize ([com.hayeco.flipspaces.core.spatial.ResizeRelayout]). */
    private val relayout = com.hayeco.flipspaces.core.spatial.ResizeRelayout()

    // ---- input and layout ----

    private fun apply(actions: List<SpaceAction>) {
        for (a in actions) when (a) {
            is SpaceAction.Touch -> touch(a)
            is SpaceAction.Layout -> setLayout(a.layout, a.commit)
            SpaceAction.Recentre -> recentre()
            is SpaceAction.TurnView -> if (a.deltaYawDeg.isFinite()) manualYaw = com.hayeco.flipspaces.core.spatial.wrapDegrees(manualYaw - a.deltaYawDeg)
            // Every press, hit or miss, with the nearest panel (a miss used to log nothing).
            is SpaceAction.Pressed -> {
                Log.i(tag, a.report.line())
                // 0.5.25: a press on nothing names the nearest control and how far it was.
                a.report.missLine?.let { Log.i(tag, it) }
                // 0.5.11: a pinch on nothing brings the tucked away Apps button up for a moment.
                // 0.5.36 (Mike: the Apps button that appears when looking down can be removed): never brought up.
                if (APPS_BUTTON && a.report.zone == null && !launcher.isOpen) {
                    val (yaw, pitch) = gaze()
                    if (launcher.homeHittable) {
                        // 0.5.21 (ten pinches on nothing with the Apps button shown,
                        // the launcher opened only 20 s later): with the button up, a pinch on nothing opens it.
                        Log.i(tag, "a pinch on nothing with the Apps button shown: the launcher opens")
                        toggleLauncher()
                    } else {
                        launcher.revealByPinch(yaw, pitch, frustum.verticalFovDeg, SystemClock.uptimeMillis())?.let { Log.i(tag, it) }
                    }
                }
            }
            SpaceAction.ToggleLauncher -> toggleLauncher()
            is SpaceAction.Claimed -> launcherPress(a.ray, a.hand)
            is SpaceAction.Launcher -> launcherInput(a)
            is SpaceAction.Hover -> panels[a.panelId]?.let { inject(it, a) }
            is SpaceAction.Wheel -> panels[a.panelId]?.let { inject(it, a) }
            is SpaceAction.Zoom -> panels[a.panelId]?.let { inject(it, a) }
            is SpaceAction.LongPress -> panels[a.panelId]?.let { inject(it, a) }
            is SpaceAction.Focus -> focus(a.panelId)
            is SpaceAction.Note -> Log.i(INPUT_TAG, a.line)
            // 0.5.28: said on the glasses, not only logged; the trackpad shows its one-tap Unwrap.
            is SpaceAction.Notice -> {
                spaceNotice = a.text
                spaceNoticeUntilMs = SystemClock.uptimeMillis() + POINTER_NOTICE_MS
                Log.i(tag, "said on the glasses for ${POINTER_NOTICE_MS / 1000} s: ${a.text}")
            }
            // Back closes the open launcher first, like Back on a phone's app drawer.
            is SpaceAction.Back -> when {
                // 0.5.37: Back leaves Immersive first.
                immersive.active && immersive.phase != com.hayeco.flipspaces.core.spatial.ImmersiveMode.Phase.EXITING -> immersivePressed(immersive.heroId ?: -1)
                controls.isOpen -> {
                    controls.close()
                    Log.i(tag, "control panel closed (Back)")
                }
                launcher.isOpen -> setLauncherOpen(false, "Back")
                else -> back(a.panelId)
            }
            // 0.5.17: the phone trackpad's window controls.
            is SpaceAction.Haptic -> SpaceSession.haptic(a.haptic)
            is SpaceAction.ClosePanel -> {
                Log.i(PANEL_TAG, "panel ${a.panelId}: closed from its title bar")
                removePanel(a.panelId)
            }
            is SpaceAction.RefreshPanel -> refreshPanel(a.panelId)
            is SpaceAction.SwitchAppPanel -> openSwitcher(a.panelId)
            is SpaceAction.WideViewPanel -> wideViewPressed(a.panelId)
            is SpaceAction.FitVideoPanel -> fitVideoPressed(a.panelId)
            is SpaceAction.ImmersivePanel -> immersivePressed(a.panelId)
            is SpaceAction.ThreeFinger -> threeFinger(a.direction)
        }
    }

    /** 0.5.17: a three finger swipe: up Recent apps (the launcher), down the Control panel, each hidden by the other; sideways Back. */
    private fun threeFinger(direction: com.hayeco.flipspaces.core.pointer.SwipeDirection) {
        val (yaw, pitch) = gaze()
        val route = com.hayeco.flipspaces.core.spatial.ThreeFingerRoute.route(direction, launcher.isOpen, controls.isOpen)
        // 0.5.22 (the Control panel's opening and closing left no line): every three finger swipe and what it did.
        Log.i(tag, "three finger swipe ${direction.name.lowercase()}: ${route.name.lowercase().replace('_', ' ')}${if (route == com.hayeco.flipspaces.core.spatial.SwipeRoute.OPEN_CONTROLS) " (Glasses warp (beta) is ${GlassesWarp.state})" else ""}")
        when (route) {
            com.hayeco.flipspaces.core.spatial.SwipeRoute.OPEN_LAUNCHER -> if (!launcher.isOpen) setLauncherOpen(true, "three fingers up")
            com.hayeco.flipspaces.core.spatial.SwipeRoute.CLOSE_LAUNCHER -> if (launcher.isOpen) setLauncherOpen(false, "three fingers")
            com.hayeco.flipspaces.core.spatial.SwipeRoute.OPEN_CONTROLS -> openControls("three finger swipe")
            com.hayeco.flipspaces.core.spatial.SwipeRoute.CLOSE_CONTROLS -> {
                controls.close()
                Log.i(tag, "control panel closed (three finger swipe)")
            }
            com.hayeco.flipspaces.core.spatial.SwipeRoute.BACK -> apply(listOf(SpaceAction.Back(null)))
            com.hayeco.flipspaces.core.spatial.SwipeRoute.NOTHING -> Unit
        }
    }

    /** 0.5.37: what the Control Center shows now. */
    private fun controlState() = com.hayeco.flipspaces.core.spatial.ControlCenterState(
        follow = SpaceSession.follow,
        windows = layout.panels.size,
        spatialMouse = SpaceSession.padSpatialMouse,
        spatialMouseAvailable = SpaceSession.padSpatialMouseAvailable,
        hands = SpaceSession.padHands,
        // 0.5.91: a model with a camera, or any glasses camera found on USB (a One Pro with the Eye, say).
        recording = SpaceSession.recording,
        handsAvailable = store.model.camera || com.hayeco.flipspaces.hands.GlassesCameraProvider.status.value !is com.hayeco.flipspaces.core.hands.ProviderStatus.Unavailable,
        cursorFollowsHead = store.cursorFollowsHead,
        snap = store.snapWindows,
        cursorSpeed = SpaceSession.padCursorSpeed,
        presets = presetsCache.map { it.name },
        page = controls.page,
        notes = com.hayeco.flipspaces.phone.PhoneNotifications.rows,
        noteAccess = noteAccess(),
        wifi = com.hayeco.flipspaces.phone.PhoneConnections.wifi,
        bt = com.hayeco.flipspaces.phone.PhoneConnections.bt,
        minute = System.currentTimeMillis() / 60_000L,
        // 1.1.15: a newer Pocket XR not put off with Later, as a line under the title.
        update = com.hayeco.flipspaces.UpdateChecker.banner.value?.version,
    )

    /** 0.5.46: whether notification access is on, read at most every 2 s (the card asks every frame). */
    private var noteAccessCached = false
    private var noteAccessReadMs = Long.MIN_VALUE / 2
    private fun noteAccess(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - noteAccessReadMs > 2_000L) {
            noteAccessReadMs = now
            noteAccessCached = com.hayeco.flipspaces.phone.PhoneNotifications.hasAccess(context)
        }
        return noteAccessCached
    }

    /** 0.5.46: the Control Center's pages' work off the GL thread (Shizuku commands wait on a process). */
    private val phoneWork = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "space-phone-work").apply { isDaemon = true } }

    private fun phoneTask(what: String, block: () -> String?) {
        phoneWork.execute {
            val err = runCatching { block() }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (err != null) {
                spaceNotice = "$what: ${err.lineSequence().first().take(80)}"
                spaceNoticeUntilMs = SystemClock.uptimeMillis() + 3_000L
            }
            Log.i(tag, "control center: $what${err?.let { " failed: $it" } ?: " done"}")
            noteAccessReadMs = Long.MIN_VALUE / 2
            com.hayeco.flipspaces.phone.PhoneConnections.refresh(context)
        }
    }

    /** 0.5.46: the phone's Wi-Fi or Bluetooth settings as a window in the Space (Settings' own pages). */
    private fun openPhoneSettings(page: com.hayeco.flipspaces.core.spatial.ControlPage) {
        val (label, activity) = if (page == com.hayeco.flipspaces.core.spatial.ControlPage.WIFI) {
            "Wi-Fi" to "com.android.settings.Settings\$WifiSettingsActivity"
        } else {
            "Bluetooth" to "com.android.settings.Settings\$BluetoothSettingsActivity"
        }
        addApp(AppEntry(label, "com.android.settings", activity), inFrontOfGaze = true)
    }

    /** 0.5.46: a notification pressed: its app as a window in the Space. */
    private fun openNoteApp(key: String) {
        val pkg = com.hayeco.flipspaces.phone.PhoneNotifications.packageOf(key) ?: return
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
        if (component == null) {
            spaceNotice = "That app has no window to open"
            spaceNoticeUntilMs = SystemClock.uptimeMillis() + 2_500L
            return
        }
        val label = com.hayeco.flipspaces.phone.PhoneNotifications.rows.firstOrNull { it.key == key }?.app ?: pkg
        addApp(AppEntry(label, component.packageName, component.className), inFrontOfGaze = true)
        Log.i(tag, "notification of $pkg opened its app as a window")
    }

    /** 0.5.45: the saved presets the Control Center offers, read from the store when it opens and after a save. */
    private var presetsCache: List<com.hayeco.flipspaces.core.spatial.WindowPreset> = emptyList()

    /** 0.5.45: opens the Control Center in front of the gaze, its presets read fresh. */
    private fun openControls(why: String, page: com.hayeco.flipspaces.core.spatial.ControlPage = com.hayeco.flipspaces.core.spatial.ControlPage.MAIN) {
        presetsCache = store.presets
        val (yaw, pitch) = gaze()
        controls.open(yaw, pitch, SystemClock.uptimeMillis(), page)
        noteAccessReadMs = Long.MIN_VALUE / 2
        com.hayeco.flipspaces.phone.PhoneConnections.refresh(context)
        ControlCenterPainter.iconOf = { pkg -> com.hayeco.flipspaces.phone.PhoneNotifications.icon(context, pkg) }
        Log.i(tag, "control panel opened at ${page.title} ($why)")
    }

    /** 0.5.37: a press on the open Control Center: its control's action. */
    private fun controlPress(ray: com.hayeco.flipspaces.core.spatial.Ray) {
        val l = com.hayeco.flipspaces.core.spatial.ControlCenterLayout.layout(controlState())
        val action = controls.press(ray, l)
        Log.i(tag, "control center press: $action")
        when (action) {
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.SetFollow -> setFollow(action.mode)
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.Arrange -> apply(pointer.arrange(action.kind, layout, focusedPanelId))
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.Recentre -> recentre()
            // 0.5.99: start or stop recording the Space ([SpaceRecorder]).
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ToggleRecord -> SpaceSession.recordWanted = !SpaceSession.recordWanted
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ToggleSpatialMouse -> SpaceSession.padRequests.add(SpaceSession.PadRequest.ToggleSpatialMouse)
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ToggleHands -> SpaceSession.padRequests.add(SpaceSession.PadRequest.ToggleHands)
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.Speed -> SpaceSession.padRequests.add(SpaceSession.PadRequest.Speed(action.delta))
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ToggleCursorFollowsHead -> store.cursorFollowsHead = !store.cursorFollowsHead
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ToggleSnap -> store.snapWindows = !store.snapWindows
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.OpenPreset -> presetsCache.getOrNull(action.index)?.let { openPreset(it) }
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.SavePreset -> {
                val saved = com.hayeco.flipspaces.core.spatial.WindowPresets.save(store.presets, layout, System.currentTimeMillis())
                if (saved != null) {
                    store.presets = saved
                    presetsCache = saved
                    spaceNotice = "Saved ${saved.first().name}: ${saved.first().apps}"
                    spaceNoticeUntilMs = SystemClock.uptimeMillis() + 2_500L
                    Log.i(tag, "preset ${saved.first().name} saved from the Control Center (${saved.first().layout.panels.size} windows)")
                }
            }
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.Page -> com.hayeco.flipspaces.phone.PhoneConnections.refresh(context)
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.TogglePower -> {
                val wifiPage = action.page == com.hayeco.flipspaces.core.spatial.ControlPage.WIFI
                val on = if (wifiPage) com.hayeco.flipspaces.phone.PhoneConnections.wifi.enabled else com.hayeco.flipspaces.phone.PhoneConnections.bt.enabled
                phoneTask("${action.page.title} ${if (on) "off" else "on"}") { com.hayeco.flipspaces.phone.PhoneConnections.setPower(context, wifiPage, !on) }
            }
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.OpenNote -> openNoteApp(action.key)
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.DismissNote -> com.hayeco.flipspaces.phone.PhoneNotifications.dismiss(action.key)
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.ClearNotes -> com.hayeco.flipspaces.phone.PhoneNotifications.clearAll()
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.GrantNotes -> phoneTask("Notification access") { com.hayeco.flipspaces.phone.PhoneNotifications.grant(context) }
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.GrantBluetooth -> phoneTask("Bluetooth access") { com.hayeco.flipspaces.phone.PhoneConnections.grantBluetooth(context) }
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.ForgetWifi -> phoneTask("Forget ${action.ssid}") { com.hayeco.flipspaces.phone.PhoneConnections.forget(action.id) }
            is com.hayeco.flipspaces.core.spatial.ControlCenterAction.OpenSettings -> openPhoneSettings(action.page)
            com.hayeco.flipspaces.core.spatial.ControlCenterAction.Closed, com.hayeco.flipspaces.core.spatial.ControlCenterAction.Nothing -> Unit
        }
    }

    private fun setFollow(mode: com.hayeco.flipspaces.core.spatial.FollowMode) {
        if (!mode.available) return
        SpaceSession.follow = mode
        com.hayeco.flipspaces.TrackpadPrefs(context).follow = mode
        Log.i(tag, "tracking mode: ${mode.label} (${mode.dof})")
    }

    /**
     * 0.5.25 (Mike: the Android XR launcher): the toggle opens it, closes it, or brings it back in front
     * when it is open but out of view.
     */
    private fun toggleLauncher() {
        val (yaw, pitch) = gaze()
        val nowMs = SystemClock.uptimeMillis()
        val what = xr.toggle(yaw, pitch, nowMs)
        if (xr.isOpen != launcher.isOpen) launcher.toggle(yaw, pitch)
        logLauncher(what)
    }

    /** 0.5.25: opens or closes the launcher ([xr] and [launcher] together), for [why]. */
    private fun setLauncherOpen(open: Boolean, why: String) {
        val (yaw, pitch) = gaze()
        val nowMs = SystemClock.uptimeMillis()
        if (open) xr.open(yaw, pitch, nowMs) else xr.close(nowMs)
        if (open) launcher.open(yaw, pitch) else launcher.close()
        logLauncher(if (open) "opened ($why)" else "closed ($why)")
    }

    /** 0.5.25: one line when the launcher opens or closes: its pages, apps, panel size and distance. */
    private fun logLauncher(what: String) {
        Log.i(tag, "launcher $what: ${xr.line()}")
    }

    /**
     * While the launcher is open the Space's own display has focus, so a keyboard types into
     * its search; when it closes the focused panel gets focus back. Checked once a frame, since
     * the launcher opens and closes from a gesture, its home button and the phone.
     * 0.5.25: and, every frame, the launcher's page settles, its long press and hover move on.
     */
    private fun syncLauncherFocus() {
        val nowMs = SystemClock.uptimeMillis()
        xr.frame(nowMs)
        if (xr.visible(nowMs)) xr.hover(pointerRays(), nowMs)
        if (launcher.isOpen == launcherWasOpen) return
        launcherWasOpen = launcher.isOpen
        val target = if (launcher.isOpen) spaceDisplayId else focusedPanelId?.let { panels[it]?.displayId } ?: -1
        if (target >= 0) PanelHost.focus(target)
    }

    private var launcherWasOpen = false

    /** The events [action] makes for [p]'s app, injected on its display. */
    private fun inject(p: PanelGl, action: SpaceAction) {
        val id = p.displayId ?: return
        // App closed, or the Back question is up: the app gets nothing until it is answered,
        // except a hover exit (0.4.5: a held back exit and the next enter aborted Android).
        val blocked = cardOf(p) != null
        val sent = if (action is SpaceAction.Hover) {
            action.copy(phase = p.hover.pass(action.phase, id, blocked) ?: return)
        } else {
            if (blocked) return
            action
        }
        // 1.1.15: a press on the app (a touch going down, a long press): its new screens are watched for on the phone.
        if ((sent is SpaceAction.Touch && sent.phase == com.hayeco.flipspaces.core.spatial.TouchPhase.DOWN) || sent is SpaceAction.LongPress) pressedApp(p)
        for (m in input.map(sent, p.width, p.height)) {
            if (m.startsGesture) p.downTime = SystemClock.uptimeMillis()
            val down = if (m.source == PanelMotion.SOURCE_MOUSE) 0L else p.downTime
            // Every scroll and its result (0.4.2: a dropped scroll used to leave no trace).
            val result = if (m.action == PanelMotion.ACTION_SCROLL) scrollLog(p, id, m) else null
            PanelHost.motion(id, m, down, result) { problem = "Input on ${p.panel.label}: $it" }
        }
    }

    /** Logs a scroll injection and, once the shell has it, its result (FlipSpaces/Input). */
    private fun scrollLog(p: PanelGl, displayId: Int, m: PanelMotion): (String?) -> Unit {
        val at = "%.0f,%.0f".format(m.xs[0], m.ys[0])
        val what = "scroll panel ${p.panel.id} ${p.panel.label} display $displayId at $at of ${p.width}x${p.height}: v ${m.vScroll}, h ${m.hScroll} notches"
        return { err -> if (err == null) Log.i(INPUT_TAG, "$what: injected") else Log.w(INPUT_TAG, "$what: $err") }
    }

    /**
     * The phone's trackpad was touched (0.4.2): Android moves focus to the phone's display on a
     * touch there, so give it back to the focused panel (or to the Space while the launcher is
     * open), so typing keeps going to the panel without pinching it again.
     */
    private fun keepFocus() {
        val target = PanelFocus.target(launcher.isOpen, spaceDisplayId, focusedPanelId?.let { panels[it]?.displayId })
        focusKeeper.onTrackpadTouch(SystemClock.uptimeMillis(), target)?.let { PanelHost.focus(it, "trackpad touch") }
    }

    /**
     * The pointer pressed panel [id]: it takes input focus, and its display top focus so the
     * phone's keyboard and a hardware keyboard type into its app.
     */
    private fun focus(id: Int) {
        val p = panels[id] ?: return
        if (focusedPanelId != id) Log.i(PANEL_TAG, "panel $id ${p.panel.label} has focus")
        focusedPanelId = id
        // The focused panel is the front one among equals, for drawing and hit tests alike.
        if (layout.panels.lastOrNull()?.id != id) setLayout(layout.raise(id), commit = false)
        p.displayId?.let { d -> PanelHost.focus(d) { err -> if (err != null) problem = "Focus on ${p.panel.label}: $err" } }
    }

    /**
     * The Back key for panel [named], or the focused or topmost one. 0.4.2: the display's tasks
     * are checked first; at the app's last activity Back would close it and leave the panel
     * empty, so the panel asks (Back anyway, Close panel) instead, until Mike once says Back anyway.
     */
    private fun back(named: Int?) {
        val id = KeyRouting.backTarget(named, focusedPanelId, layout.panels.map { it.id }) ?: return
        val p = panels[id] ?: return
        val display = p.displayId ?: return
        val closed = (p.state as? PanelState.Showing)?.appClosed == true
        if (closed || p.ask != null) return
        PanelHost.tasks(display) { tasks ->
            onGl {
                if (p.removed || p.displayId != display) return@onGl
                val stillClosed = (p.state as? PanelState.Showing)?.appClosed == true
                when (BackGuard.decide(tasks, stillClosed, p.backAllowed)) {
                    BackDecision.SEND -> sendBack(p, display)
                    BackDecision.ASK -> {
                        Log.i(PANEL_TAG, "Back on panel $id ${p.panel.label}: last activity ($tasks); asking before it closes the app")
                        p.ask = PanelCard.AskBack(p.panel.label)
                    }
                    BackDecision.NOTHING -> Log.i(PANEL_TAG, "Back on panel $id ${p.panel.label}: the app has closed ($tasks); nothing sent")
                }
            }
        }
    }

    /**
     * 0.5.21 (Mike: back, close and refresh buttons): the window's Refresh. Its app's launcher
     * activity is started again on its display with its task cleared, so what it shows is loaded
     * anew ([com.hayeco.flipspaces.core.spatial.PanelDisplays.refreshCommand]); the window stays put.
     */
    private fun refreshPanel(id: Int) {
        val p = panels[id] ?: return
        val display = p.displayId ?: return
        val label = p.panel.label
        val now = SystemClock.uptimeMillis()
        (p.state as? PanelState.Showing)?.let { showing ->
            p.state = showing.copy(appClosed = false)
            p.empty.launched(now)
            p.tasksCheckedMs = now
        }
        p.ask = null
        Log.i(PANEL_TAG, "panel $id $label: Refresh from its window controls")
        PanelHost.refresh(display, label, p.panel.packageName, p.panel.activityName) { err ->
            if (err != null) onGl { problem = "Refreshing $label: $err" }
        }
    }

    /**
     * 0.5.21: Wide view, the button under a window. On, the window turns landscape and
     * its display is 260 dpi with a smallest width of 600 dp or more, where Android 16 sets aside
     * the app's portrait lock; off, a portrait app goes back to its portrait window. Remembered per app;
     * the first time on shows a tip; an app that asks for landscape cannot have it off.
     */
    private fun wideViewPressed(id: Int) {
        val p = panels[id] ?: return
        val pkg = p.panel.packageName
        if (pkg.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val t = wideView.toggle(pkg, p.appShape, now)
        t.refused?.let { why ->
            Log.i(PANEL_TAG, "panel $id ${p.panel.label}: Wide view: $why (${p.appShape?.line() ?: "what it asks for not read yet"})")
            showNotice(p, why, now)
            return
        }
        if (!t.changed) return
        store.wideView = wideView.encode()
        // 0.5.22: on, one try of the per app override is allowed again; off, the app's own state goes back.
        if (t.on) synchronized(override) { override.wideViewOn(pkg) } else runOverride(synchronized(override) { override.wideViewOff(pkg) }, p)
        val shape = com.hayeco.flipspaces.core.spatial.WideView.shape(t.on, p.appShape)
        val next = com.hayeco.flipspaces.core.spatial.WideView.reshape(p.panel, shape)
        p.shapeSet = shape
        val spec = displaySpec(next, t.on, p.appShape)
        Log.i(PANEL_TAG, "panel $id ${p.panel.label}: Wide view ${if (t.on) "on" else "off"} (remembered for $pkg): the window %.1f by %.1f degrees, its display %s; %s".format(
            java.util.Locale.ROOT, next.widthDeg, next.heightDeg, spec.line(), p.appShape?.line() ?: "what the app asks for is read in a moment",
        ))
        if (t.tip) showNotice(p, com.hayeco.flipspaces.core.spatial.WideView.TIP, now)
        setLayout(layout.replace(next), commit = true)
        // Read what the app does at its new size once it has laid out again.
        p.shapeAskedMs = now - SHAPE_EVERY_MS + SHAPE_AFTER_TOGGLE_MS
        p.broadToggledMs = now
    }

    private fun showNotice(p: PanelGl, text: String, nowMs: Long) {
        p.notice = text
        p.noticeUntilMs = nowMs + com.hayeco.flipspaces.core.spatial.WideView.NOTICE_MS
    }

    /**
     * 0.5.21: what each app window's app asks Android for, every [SHAPE_EVERY_MS]
     * through the panel service. With Wide view off, an app that asks for portrait gets a 3 : 4 portrait
     * window (and one that asks for landscape a landscape one), once each time what it asks for changes, so
     * a resize Mike makes afterwards stands. Each change is logged, and after a Wide view press whether
     * Android still letterboxes the app ([com.hayeco.flipspaces.core.spatial.WideView.limits]).
     */
    private fun checkAppShapes(nowMs: Long) {
        // One `dumpsys activity activities` at a time, at least [SHAPE_GAP_MS] apart over every window.
        if (panels.values.any { it.shapePending } || nowMs - shapeAskedAnyMs < SHAPE_GAP_MS) return
        for (p in panels.values.sortedBy { it.shapeAskedMs }) {
            if (!p.panel.isApp) continue
            val display = (p.state as? PanelState.Showing)?.displayId ?: continue
            if (nowMs - p.shapeAskedMs < SHAPE_EVERY_MS) continue
            p.shapeAskedMs = nowMs
            shapeAskedAnyMs = nowMs
            p.shapePending = true
            PanelHost.appShape(display) { text ->
                onGl {
                    p.shapePending = false
                    if (p.removed) return@onGl
                    val shape = com.hayeco.flipspaces.core.spatial.AppShape.parse(text, display)?.takeIf { it.packageName == p.panel.packageName } ?: return@onGl
                    val before = p.appShape
                    p.appShape = shape
                    checkFit(p, display, shape)
                    val askChanged = before?.orientation != shape.orientation
                    if (askChanged || before?.letterboxed != shape.letterboxed) Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: ${shape.line()}")
                    val on = wideView.isOn(p.panel.packageName)
                    if (on && p.broadToggledMs > 0L && SystemClock.uptimeMillis() - p.broadToggledMs < SHAPE_EVERY_MS + SHAPE_AFTER_TOGGLE_MS) {
                        p.broadToggledMs = 0L
                        Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: with Wide view on, ${com.hayeco.flipspaces.core.spatial.WideView.limits(shape)}")
                        // 0.5.22: still letterboxed with the display's fix in: the per app override, once.
                        runOverride(synchronized(override) { override.afterWideViewOn(shape) }, p)
                    }
                    // 0.5.22: a portrait locked app that Android letterboxes keeps a display tall
                    // enough for a 320 dp column; its display follows when that changes.
                    if (before?.letterboxed != shape.letterboxed || before?.orientation != shape.orientation) setLayout(layout, commit = false)
                    // 0.5.24: a video fitted window stays landscape, as with Wide view on.
                    val want = com.hayeco.flipspaces.core.spatial.WideView.shape(on || appDensity.isVideo(p.panel.packageName), shape)
                    if (want != com.hayeco.flipspaces.core.spatial.WideView.Shape.FREE && want != p.shapeSet && (askChanged || p.shapeSet == null) && grabbedNow(p.panel.id).not()) {
                        p.shapeSet = want
                        val next = com.hayeco.flipspaces.core.spatial.WideView.reshape(p.panel, want)
                        if (next != p.panel) {
                            Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: it asks for ${shape.orientation.lowercase().replace('_', ' ')}, so its window turns ${want.name.lowercase()} (%.1f by %.1f degrees; Wide view under it turns a portrait app landscape)".format(java.util.Locale.ROOT, next.widthDeg, next.heightDeg))
                            setLayout(layout.replace(next), commit = true)
                        }
                    }
                }
            }
            return
        }
    }

    private fun grabbedNow(panelId: Int): Boolean = pointer.grabOn(panelId) != null

    /**
     * 0.5.23 (Mike: "a relayout on release if live resizing can't"): after a released resize, the app's own size and
     * density (its activity's configuration) against its display's. One that did not follow is nudged: its display
     * 2 px shorter and back, two configuration changes, then read again; at most [com.hayeco.flipspaces.core.spatial.PanelFit.MAX_NUDGES] times.
     */
    private fun checkFit(p: PanelGl, display: Int, shape: com.hayeco.flipspaces.core.spatial.AppShape) {
        if (!p.fitArmed || grabbedNow(p.panel.id) || resizeGate.pending(p.panel.id)) return
        val spec = com.hayeco.flipspaces.core.spatial.PanelDisplaySpec(p.width, p.height, p.dpi)
        val misfit = com.hayeco.flipspaces.core.spatial.PanelFit.misfit(spec, shape)
        if (misfit == null) {
            p.fitArmed = false
            if (p.fitNudges > 0) Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: the app fills its display ${spec.line()} after ${p.fitNudges} relayout nudge${if (p.fitNudges == 1) "" else "s"}")
            return
        }
        if (p.fitNudges >= com.hayeco.flipspaces.core.spatial.PanelFit.MAX_NUDGES) {
            p.fitArmed = false
            Log.w(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: after ${p.fitNudges} relayout nudges $misfit; left as it is")
            return
        }
        p.fitNudges++
        Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: after the resize $misfit: relayout nudge ${p.fitNudges} (display $display to ${spec.widthPx}x${spec.heightPx - 2} and back)")
        PanelHost.resize(display, spec.widthPx, spec.heightPx - 2, spec.dpi) { err -> if (err != null) problem = "Relayout ${p.panel.label}: $err" }
        PanelHost.resize(display, spec.widthPx, spec.heightPx, spec.dpi) { err -> if (err != null) problem = "Relayout ${p.panel.label}: $err" }
        shapeSoon(p, SHAPE_AFTER_LAUNCH_MS)
    }

    /**
     * 0.5.22 (Mike: "if that alone doesn't hold on One UI, also use ... am compat through Shizuku, and restore the
     * app's own setting"): Wide view's per app fallback ([com.hayeco.flipspaces.core.spatial.WideViewOverride]),
     * its pending list saved in the Space's settings.
     */
    private val override = com.hayeco.flipspaces.core.spatial.WideViewOverride(store.wideViewOverride)
    private var overrideSweptMs = 0L
    private var overrideSweeps = 0

    private fun saveOverride() {
        store.wideViewOverride = synchronized(override) { override.encode() }
    }

    /** Every pending app no window shows gets its own state back, checked every [SHAPE_GAP_MS] while the panel service is up. */
    private fun sweepOverrides(nowMs: Long) {
        if (nowMs - overrideSweptMs < SHAPE_GAP_MS || !PanelHost.ready) return
        overrideSweptMs = nowMs
        val showing = panels.values.filter { it.panel.isApp && !it.removed }.map { it.panel.packageName }.toSet()
        val why = if (overrideSweeps++ == 0) "the Space started with it still pending from before" else "no window shows it now"
        synchronized(override) { override.sweep(showing, why) }.forEach { runOverride(it, null) }
    }

    /** One override step through the panel service, logged; [p] is the window it is for (null for a sweep). */
    private fun runOverride(step: com.hayeco.flipspaces.core.spatial.WideViewOverride.Step, p: PanelGl?) {
        val who = p?.let { "panel ${it.panel.id} ${it.panel.label}" } ?: "Wide view"
        when (step) {
            is com.hayeco.flipspaces.core.spatial.WideViewOverride.Step.None -> if (p != null) Log.i(PANEL_TAG, "$who: Wide view override: ${step.why}")
            is com.hayeco.flipspaces.core.spatial.WideViewOverride.Step.ReadFirst -> {
                Log.i(PANEL_TAG, "$who: ${step.why}; reading its own state first")
                PanelHost.appCompat(step.packageName, "read") { reply ->
                    onGl {
                        Log.i(PANEL_TAG, "$who: its own ${com.hayeco.flipspaces.core.spatial.WideViewOverride.CHANGE_NAME} state: $reply")
                        runOverride(synchronized(override) { override.readDone(step.packageName, reply) }, p)
                    }
                }
            }
            is com.hayeco.flipspaces.core.spatial.WideViewOverride.Step.Apply -> {
                saveOverride()
                Log.i(PANEL_TAG, "$who: ${step.why}")
                PanelHost.appCompat(step.command.packageName, step.command.action) { reply ->
                    onGl {
                        val ok = reply.startsWith("done")
                        synchronized(override) { override.applyDone(step.command.packageName, ok) }
                        saveOverride()
                        Log.i(PANEL_TAG, "$who: ${step.command.argv().joinToString(" ")}: $reply")
                        // Android stopped the app to apply it: start it again on its display, and read it 2 s later.
                        val display = p?.displayId
                        if (ok && p != null && display != null && !p.removed) {
                            PanelHost.refresh(display, p.panel.label, p.panel.packageName, p.panel.activityName) { err ->
                                if (err != null) onGl { problem = "Restarting ${p.panel.label}: $err" }
                            }
                            val now = SystemClock.uptimeMillis()
                            p.shapeAskedMs = now - SHAPE_EVERY_MS + SHAPE_AFTER_TOGGLE_MS
                            p.broadToggledMs = now
                        }
                    }
                }
            }
            is com.hayeco.flipspaces.core.spatial.WideViewOverride.Step.Restore -> {
                Log.i(PANEL_TAG, "$who: ${step.why}")
                PanelHost.appCompat(step.command.packageName, step.command.action) { reply ->
                    onGl {
                        synchronized(override) { override.restoreDone(step, reply.startsWith("done")) }
                        saveOverride()
                        Log.i(PANEL_TAG, "$who: ${step.command.argv().joinToString(" ")}: $reply")
                    }
                }
            }
        }
    }

    /** 0.5.21: when any window's app shape was last asked for ([checkAppShapes]). */
    private var shapeAskedAnyMs = 0L

    /** 0.5.22: [p]'s app shape is read [inMs] from now (a launch, or its display's new size changing its configuration). */
    private fun shapeSoon(p: PanelGl, inMs: Long) {
        p.shapeAskedMs = SystemClock.uptimeMillis() - SHAPE_EVERY_MS + inMs
    }

    /**
     * 0.5.22: the display for [panel]: its app's size and shape, Wide view's density when [broad], and for a portrait
     * locked app Android letterboxes ([shape]) a display tall enough for a 320 dp column.
     */
    private fun displaySpec(panel: SpacePanel, broad: Boolean, shape: com.hayeco.flipspaces.core.spatial.AppShape?): com.hayeco.flipspaces.core.spatial.PanelDisplaySpec =
        com.hayeco.flipspaces.core.spatial.PanelDisplaySize.of(
            panel, broad, lockedPortrait = !broad && shape?.letterboxed == true && shape.asksPortrait,
            // 0.5.24: the app's own density setting (Fit video) wins.
            videoSmallestDp = appDensity.smallestDp(panel.packageName),
        )

    /**
     * 0.5.24 (Twitch crashed twice at launch on its remembered 1979 dp wide window): the saved
     * windows are capped to a sane size before they open ([com.hayeco.flipspaces.core.spatial.ReopenCap]), once a run.
     */
    private fun capReopened() {
        if (reopenCapped) return
        reopenCapped = true
        var next = layout
        for (p in layout.panels) {
            val (capped, why) = com.hayeco.flipspaces.core.spatial.ReopenCap.cap(p, wideView.isOn(p.packageName), appDensity.smallestDp(p.packageName))
            if (why == null) continue
            Log.i(PANEL_TAG, "panel ${p.id} ${p.label}: $why")
            next = next.replace(capped)
        }
        // 0.5.27: a Fit video window saved in another shape opens at 16:9 again ([com.hayeco.flipspaces.core.spatial.VideoFit.keep]).
        for (p in next.panels) {
            if (!appDensity.isVideo(p.packageName)) continue
            val fitted = com.hayeco.flipspaces.core.spatial.VideoFit.keep(p)
            if (fitted == p) continue
            Log.i(PANEL_TAG, "panel ${p.id} ${p.label}: Fit video is on, so its window opens at 16:9 (%.1f by %.1f degrees, was %.1f by %.1f)".format(java.util.Locale.ROOT, fitted.widthDeg, fitted.heightDeg, p.widthDeg, p.heightDeg))
            next = next.replace(fitted)
        }
        if (next !== layout) {
            layout = next
            store.saveLayout(next)
        }
    }

    /**
     * 0.5.24 (Mike: Twitch does not fill the window once a channel loads): Fit video under the
     * window. On: the window snaps to 16:9 at its height and the app gets a phone's width
     * ([com.hayeco.flipspaces.core.spatial.VideoFit.VIDEO_SMALLEST_DP]), remembered for the app. Off: its density
     * setting goes, and the window goes back to the shape it had before.
     */
    private fun fitVideoPressed(id: Int) {
        val p = panels[id] ?: return
        val pkg = p.panel.packageName
        if (pkg.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - p.fitToggledMs < com.hayeco.flipspaces.core.spatial.WideView.DEBOUNCE_MS) return
        p.fitToggledMs = now
        val on = !appDensity.isVideo(pkg)
        val next = if (on) {
            p.beforeFit = p.panel.widthDeg to p.panel.heightDeg
            appDensity.set(pkg, com.hayeco.flipspaces.core.spatial.VideoFit.VIDEO_SMALLEST_DP)
            com.hayeco.flipspaces.core.spatial.VideoFit.snap(p.panel)
        } else {
            appDensity.set(pkg, null)
            val back = p.beforeFit
            p.beforeFit = null
            if (back != null) p.panel.copy(widthDeg = back.first, heightDeg = back.second).sizedByUser() else p.panel
        }
        store.appDensity = appDensity.encode()
        val spec = displaySpec(next, wideView.isOn(pkg), p.appShape)
        Log.i(PANEL_TAG, "panel $id ${p.panel.label}: Fit video ${if (on) "on" else "off"} (remembered for $pkg): the window %.1f by %.1f degrees (%.3f wide for 1 tall), its display %s%s".format(
            java.util.Locale.ROOT, next.widthDeg, next.heightDeg, com.hayeco.flipspaces.core.spatial.PanelDisplaySize.aspect(next), spec.line(),
            if (on) "; a phone's width, so a video app lays out its player full screen" else "; the app's usual density again",
        ))
        showNotice(p, if (on) "Fit video: 16:9, phone layout" else "Fit video off", now)
        setLayout(layout.replace(next), commit = true)
        shapeSoon(p, SHAPE_AFTER_TOGGLE_MS)
    }

    private fun sendBack(p: PanelGl, display: Int) {
        Log.i(PANEL_TAG, "Back on panel ${p.panel.id} ${p.panel.label}")
        val down = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            PanelHost.key(display, action, KeyRouting.KEYCODE_BACK, 0, 0, down) { problem = "Back on ${p.panel.label}: $it" }
        }
    }

    /**
     * The card over [p]: App closed, or the Back question; null when the app shows. 1.1.9: an app that moved to a newer
     * window of its own ([PanelGl.movedTo]) says so instead of App closed, unless it crashed.
     */
    private fun cardOf(p: PanelGl): PanelCard? {
        if ((p.state as? PanelState.Showing)?.appClosed != true) return p.ask ?: p.switcher
        val crash = crashes[p.panel.id]
        return if (crash == null && p.movedTo != null) com.hayeco.flipspaces.core.spatial.SingleWindowApp.card(p.panel.label, p.movedSwitchOn) else PanelCard.AppClosed(p.panel.label, crash)
    }

    /** 0.5.19: how each panel's app ended when its exit record was a crash, for its card. */
    private val crashes = HashMap<Int, String>()

    /**
     * 0.5.19: the app of [p] left its display: its exit record, through the panel service. A crash or
     * ANR within the last half minute is said on the card, and the package is not started again by the
     * next Space start for [SpaceSession.CRASH_HOLD_MS] (vc39 launched the crashing app three times).
     */
    private fun askWhyClosed(p: PanelGl) {
        val pkg = p.panel.packageName
        PanelHost.exitReason(pkg) { reply ->
            val exit = com.hayeco.flipspaces.core.spatial.AppExit.fromLine(reply)
            // Only an exit of the last minute is this closing (the record's wall clock time).
            val recent = exit?.let {
                runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).parse(it.timestamp)?.time }.getOrNull()
            }?.let { kotlin.math.abs(System.currentTimeMillis() - it) < 60_000L } ?: false
            onGl {
                Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: the app's last exit: $reply${if (exit != null && !recent) " (not this closing: older than a minute)" else ""}")
                if (exit != null && exit.crashed && recent) {
                    crashes[p.panel.id] = "${exit.reasonName.lowercase().replaceFirstChar { it.uppercase() }}: ${exit.description.take(60)}"
                    SpaceSession.crashedApps[pkg] = SystemClock.uptimeMillis()
                    problem = "${p.panel.label} crashed on its panel; Reopen or Close it there."
                }
            }
        }
    }

    /** 0.5.85: the window whose Switch app opened the launcher's All apps; its pick goes into that window. */
    private var pickForPanel: Int? = null

    /** 0.5.85: the icons of the Switch app card's apps, loaded off the GL thread. */
    private val switcherIcons = ConcurrentHashMap<String, android.graphics.Bitmap>()

    /**
     * 0.5.85 (Mike: a Switch app button on each window, showing "the pinned apps and then another option to select any app"):
     * the card on panel [id] with the launcher's pinned apps (their icons loaded in the background), All apps and Cancel.
     */
    private fun openSwitcher(id: Int) {
        val p = panels[id] ?: return
        val pinned = xr.pinnedApps().filter { it.packageName != p.panel.packageName }
        p.switcher = PanelCard.SwitchApp(p.panel.label, pinned, PanelCurve.arcWidthM(p.panel) / p.panel.heightM)
        p.ask = null
        Log.i(PANEL_TAG, "panel $id ${p.panel.label}: Switch app with ${pinned.size} pinned apps")
        val pm = context.packageManager
        val missing = pinned.filter { !switcherIcons.containsKey(it.key) }
        if (missing.isNotEmpty()) Thread({
            for (app in missing) runCatching {
                val d = pm.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName))
                val b = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                d.setBounds(0, 0, 160, 160)
                d.draw(c)
                switcherIcons[app.key] = b
            }
        }, "switch-app-icons").start()
    }

    /**
     * 0.5.85: [app] into the window of [p]: same place, size, depth and curve, only its app changed. Started as its own
     * instance on the window's display (as Reopen, 0.5.83), and the window's layout saved with the new app.
     */
    private fun switchApp(p: PanelGl, app: com.hayeco.flipspaces.core.AppEntry) {
        val showing = p.state as? PanelState.Showing ?: return
        val now = SystemClock.uptimeMillis()
        val next = p.panel.copy(label = app.label, packageName = app.packageName, activityName = app.activityName)
        Log.i(PANEL_TAG, "panel ${p.panel.id}: ${p.panel.label} switched to ${app.label} (${app.packageName}) in the same window")
        p.panel = next
        p.state = showing.copy(appClosed = false)
        p.empty.launched(now)
        p.tasksCheckedMs = now
        setLayout(layout.replace(next), commit = true)
        PanelHost.relaunch(showing.displayId, app.label, app.packageName, app.activityName) { err ->
            if (err != null) onGl { problem = "Switching to ${app.label}: $err" }
        }
    }

    /** The App closed card's Reopen: the app started again in [p]'s window, as a new instance there ([PanelHost.relaunch]). */
    private fun reopen(p: PanelGl) {
        val label = p.panel.label
        val showing = p.state as? PanelState.Showing ?: return
        val now = SystemClock.uptimeMillis()
        // 0.5.19: Mike asked for it again: the crash is forgotten.
        crashes.remove(p.panel.id)
        SpaceSession.crashedApps.remove(p.panel.packageName)
        p.state = showing.copy(appClosed = false)
        p.empty.launched(now)
        p.tasksCheckedMs = now
        // 0.5.24: an app that closed is reopened at a sane size.
        val (capped, why) = com.hayeco.flipspaces.core.spatial.ReopenCap.cap(p.panel, wideView.isOn(p.panel.packageName), appDensity.smallestDp(p.panel.packageName))
        if (why != null) {
            Log.i(PANEL_TAG, "panel ${p.panel.id} $label: $why")
            setLayout(layout.replace(capped), commit = true)
        }
        PanelHost.relaunch(showing.displayId, label, p.panel.packageName, p.panel.activityName) { err ->
            if (err != null) onGl { problem = "Reopening $label: $err" }
        }
    }

    /** A press on a card's button (or beside it). */
    private fun onCard(p: PanelGl, card: PanelCard, action: CardAction?, button: com.hayeco.flipspaces.core.spatial.CardButton? = null) {
        val label = p.panel.label
        when (action) {
            CardAction.REOPEN -> reopen(p)
            CardAction.TURN_ON_AND_REOPEN -> {
                // 1.1.10: the Menu's Open a new instance turned on, then the app reopened here as a new instance.
                store.newInstance = true
                PanelHost.newInstance = true
                com.hayeco.flipspaces.SpacesState.setNewInstance(true)
                Log.i(PANEL_TAG, "panel ${p.panel.id} $label: Open a new instance turned on from its card; reopening")
                reopen(p)
            }
            CardAction.CLOSE_PANEL -> {
                Log.i(PANEL_TAG, "panel ${p.panel.id} $label: closed from its card")
                removePanel(p.panel.id)
            }
            CardAction.BACK_ANYWAY -> {
                p.ask = null
                p.backAllowed = true
                p.displayId?.let { sendBack(p, it) }
            }
            // 0.5.85: the Switch app card: an app tile puts that app in this window; All apps opens the launcher to pick one.
            CardAction.SWITCH_TO -> {
                p.switcher = null
                button?.app?.let { switchApp(p, it) }
            }
            CardAction.ALL_APPS -> {
                p.switcher = null
                pickForPanel = p.panel.id
                setLauncherOpen(true, "Switch app's All apps for ${p.panel.label}")
                xr.settleTo(1, SystemClock.uptimeMillis())
                spaceNotice = "Pick an app for ${p.panel.label}"
                spaceNoticeUntilMs = SystemClock.uptimeMillis() + 4_000L
            }
            CardAction.KEEP, null -> if (card is PanelCard.AskBack) {
                Log.i(PANEL_TAG, "panel ${p.panel.id} $label: Back not sent")
                p.ask = null
            } else if (card is PanelCard.SwitchApp) {
                p.switcher = null
            }
        }
    }

    /**
     * 0.5.13: what each panel display asks for, by display id, and whether Android
     * lets it change after creation (VirtualDisplayConfig sets it only at creation; [PanelHost.setRefreshRate]).
     */
    private val askedRates = ConcurrentHashMap<Int, Float>()
    @Volatile private var rateChangeRefused: String? = null
    private var ratesCheckedMs = 0L

    /**
     * Twice a second: each panel's display asks for its window's rate ([updatePanelRates]; 0.5.26 to 0.5.55 asked 60 for
     * every window). A window display is made at 60 Hz, so a window can always come back to it; where Android cannot change
     * a display's rate after it is made (the Flip7's Android has no such call) this is said once and the copies alone keep
     * to the lower rate.
     */
    private fun syncPanelRates(nowMs: Long) {
        if (nowMs - ratesCheckedMs < 500L || rateChangeRefused != null) return
        ratesCheckedMs = nowMs
        for (p in panels.values) {
            val d = p.displayId ?: continue
            if (p.state !is PanelState.Showing) continue
            val want = (p.rate?.fps ?: ThermalGuard.FULL_FPS).toFloat()
            if (askedRates[d] == want) continue
            askedRates[d] = want
            PanelHost.setRefreshRate(d, want) { err ->
                if (err != null && rateChangeRefused == null) {
                    rateChangeRefused = err
                    Log.i(PANEL_TAG, "panel displays keep the 60 Hz they were made with, so windows not in use are only copied at their lower rate: $err")
                }
            }
        }
    }

    /**
     * 0.5.56 (the Space waited 6 to 7 ms behind other GPU work while another app's window drew at 60 fps at a
     * larger size): each window's rate from [panelRates]: 60 under any cursor, under the gaze, held, or playing video (an app
     * with a playing media session, [PlayingMedia], or Fit video on); 30 once left alone for a second; 15 after 30 s unused.
     * Each change is logged.
     */
    private fun updatePanelRates(nowMs: Long) {
        val video = PlayingMedia.packages
        val gaze = com.hayeco.flipspaces.core.spatial.Ray(com.hayeco.flipspaces.core.spatial.Vec3.ZERO, head.forward)
        val held = pointer.heldPanelId
        for (p in panels.values) {
            val id = p.panel.id
            val panel = layout.panels.firstOrNull { it.id == id } ?: p.panel
            val inUse = held == id || pointer.hoverOn(id) != null || pointer.grabOn(id) != null ||
                com.hayeco.flipspaces.core.spatial.PanelHitTester.hit(gaze, panel) != null
            val playing = p.panel.packageName in video || appDensity.isVideo(p.panel.packageName)
            val r = panelRates.rate(id, inUse, playing, nowMs)
            val was = p.rate
            p.rate = r
            if (was == null || was.fps != r.fps) Log.i(PANEL_TAG, "panel $id ${p.panel.label}: drawn at ${r.fps} Hz (${r.why.words})")
        }
        if (nowMs - ratesRetainedMs > 5_000L) {
            ratesRetainedMs = nowMs
            panelRates.retain(panels.keys)
        }
    }

    /** 0.5.56: the windows' rates by use ([updatePanelRates]). GL thread. */
    private val panelRates = com.hayeco.flipspaces.core.spatial.PanelRatePolicy()
    private var ratesRetainedMs = 0L

    /**
     * Every [TASKS_CHECK_MS], asks the shell what runs on each showing panel's display: a
     * display left without a task (the app finished or moved itself to the back) turns the
     * panel into App closed, instead of a blank frame with no notice.
     */
    private fun checkTasks(nowMs: Long) {
        for (p in panels.values) {
            val showing = p.state as? PanelState.Showing ?: continue
            if (showing.appClosed || showing.paused || p.tasksPending || nowMs - p.tasksCheckedMs < TASKS_CHECK_MS) continue
            if (p.tasksCheckedMs == 0L) {
                // First check after the display came up: the launch is still settling.
                p.empty.launched(nowMs)
            }
            p.tasksCheckedMs = nowMs
            p.tasksPending = true
            val display = showing.displayId
            PanelHost.tasks(display) { tasks ->
                onGl {
                    p.tasksPending = false
                    val now = p.state as? PanelState.Showing ?: return@onGl
                    if (p.removed || now.displayId != display || now.appClosed) return@onGl
                    if (p.empty.sample(tasks, SystemClock.uptimeMillis())) {
                        val newer = com.hayeco.flipspaces.core.spatial.SingleWindowApp.movedTo(p.window(), panels.values.map { it.window() })
                        p.state = now.copy(appClosed = true)
                        p.ask = null
                        p.movedTo = null
                        Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: display $display has no task ($tasks); App closed")
                        problem = "${p.panel.label} closed on its panel; Reopen or Close it there."
                        askWhyClosed(p)
                        newer?.let { checkMovedTo(p, it.id) }
                    }
                }
            }
        }
    }

    /** 1.1.15: the windows' apps and their last presses, for [com.hayeco.flipspaces.core.spatial.LaunchDisplay]. */
    private fun windowApps(): List<com.hayeco.flipspaces.core.spatial.LaunchDisplay.WindowApp> = panels.values.mapNotNull { q ->
        val d = (q.state as? PanelState.Showing)?.displayId ?: return@mapNotNull null
        if (!q.panel.isApp || q.removed) return@mapNotNull null
        com.hayeco.flipspaces.core.spatial.LaunchDisplay.WindowApp(d, q.panel.packageName, q.pressedMs)
    }

    /** 1.1.15: what is on the phone's screen, its stray tasks moved back ([keepAppsOnWindows]). */
    private val strayTasks = com.hayeco.flipspaces.core.spatial.StrayTaskWatch(context.packageName)
    private var strayCheckedMs = 0L
    @Volatile private var strayPending = false

    /**
     * 1.1.15: a press on [p]'s app. The phone's tasks are read now, with the windows as they were before this press, so
     * what the user has open on the phone is known before the press can start anything there.
     */
    private fun pressedApp(p: PanelGl) {
        val before = windowApps()
        p.pressedMs = SystemClock.uptimeMillis()
        readPhoneTasks(before)
    }

    /**
     * 1.1.15 (John Davis: Gmail's and Outlook's Compose opened on the phone, not in the window): while a window was pressed
     * in the last [com.hayeco.flipspaces.core.spatial.LaunchDisplay.WINDOW_MS], the phone's tasks are read every
     * [com.hayeco.flipspaces.core.spatial.LaunchDisplay.CHECK_MS], and a new task of that window's app is moved onto its
     * display ([com.hayeco.flipspaces.core.spatial.StrayTaskWatch]).
     */
    private fun keepAppsOnWindows() {
        val now = SystemClock.uptimeMillis()
        if (now - strayCheckedMs < com.hayeco.flipspaces.core.spatial.LaunchDisplay.CHECK_MS) return
        val windows = windowApps()
        if (!com.hayeco.flipspaces.core.spatial.LaunchDisplay.watching(windows, now)) return
        readPhoneTasks(windows)
    }

    private fun readPhoneTasks(windows: List<com.hayeco.flipspaces.core.spatial.LaunchDisplay.WindowApp>) {
        if (strayPending || PanelHost.state.value != PanelHost.State.Ready) return
        strayPending = true
        strayCheckedMs = SystemClock.uptimeMillis()
        PanelHost.rootTasks(com.hayeco.flipspaces.core.spatial.LaunchDisplay.PHONE_DISPLAY) { phone ->
            onGl {
                strayPending = false
                if (phone == null) return@onGl
                for (m in strayTasks.check(phone, windows, SystemClock.uptimeMillis())) {
                    PanelHost.moveTask(m.taskId, m.displayId) { err ->
                        Log.i(PANEL_TAG, com.hayeco.flipspaces.core.spatial.LaunchDisplay.moveLine(m, err))
                        if (err == null) PanelHost.focus(m.displayId, "${m.packageName}'s new screen moved back from the phone")
                    }
                }
            }
        }
    }

    /**
     * 1.1.9 (a Galaxy S20 FE on Android 11): [p] went empty while a newer window of its app, [newerId], still shows
     * it. Its display's task list is read once more; with the app's task there, [p]'s card says the app moved and
     * offers only Close.
     */
    private fun checkMovedTo(p: PanelGl, newerId: Int) {
        val q = panels[newerId] ?: return
        val display = q.displayId ?: return
        PanelHost.tasks(display) { tasks ->
            onGl {
                val closed = (p.state as? PanelState.Showing)?.appClosed == true
                if (p.removed || q.removed || !closed || tasks == null || tasks.empty) return@onGl
                p.movedTo = newerId
                // 1.1.10: with Open a new instance off, the second window reused the app's task: the card names the switch.
                val switchOn = store.newInstance
                p.movedSwitchOn = switchOn
                if (switchOn) {
                    Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: its app moved to the newer window $newerId on display $display ($tasks) with Open a new instance on; one window only, so Close is offered, not Reopen")
                    problem = "${p.panel.label} can only show one window on this phone. It moved to your newest window."
                } else {
                    Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: its app moved to the newer window $newerId on display $display ($tasks) with Open a new instance off; Turn on and reopen is offered")
                    problem = "${p.panel.label} moved to your newer window. To keep both, turn on Open a new instance."
                }
            }
        }
    }

    /** A key from a keyboard that reached the Space while a panel has focus. */
    private fun panelKey(k: SpaceCommand.PanelKey) {
        val target = KeyRouting.target(launcher.isOpen, fullKeyboard = true, focusedPanelId = focusedPanelId, panelIds = panels.keys)
        val p = (target as? KeyTarget.Panel)?.let { panels[it.panelId] } ?: return
        val display = p.displayId ?: return
        PanelHost.key(display, k.action, k.keyCode, k.metaState, k.repeat, k.downTime) { problem = "Key on ${p.panel.label}: $it" }
    }

    /** Android paused, resumed or stopped a panel's display. */
    private fun drainDisplayEvents() {
        while (true) {
            val (id, event) = PanelHost.displayEvents.poll() ?: break
            val p = panels.values.firstOrNull { it.displayId == id } ?: continue
            val (next, dead) = PanelRecovery.onDisplayEvent(p.state, event, SystemClock.uptimeMillis())
            p.state = next
            Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: display $id ${event.name.lowercase()}; ${p.status}")
            if (dead != null) {
                openDisplays -= dead
                PanelHost.release(dead)
                // The next open takes a new frame source, with no frame yet.
                p.surfaceFresh = false
            }
        }
    }

    private fun logThermal() {
        val status = thermalStatus
        if (status == loggedThermal) return
        Log.i(tag, "thermal status ${ThermalGuard.label(loggedThermal)} -> ${ThermalGuard.label(status)}: panels at ${ThermalGuard.panelFps(status, false)} fps, the focused one at ${ThermalGuard.panelFps(status, true)} (${ThermalGuard.panelFps(status, true, video = true)} with Fit video)")
        loggedThermal = status
    }

    /** Opens a protected panel's app as a DeX window on the glasses and drops the panel. */
    private fun openInDex(p: PanelGl) {
        val activity = context as? android.app.Activity ?: return
        val app = AppEntry(p.panel.label, p.panel.packageName, p.panel.activityName)
        val display = spaceDisplayId
        Log.i(PANEL_TAG, "panel ${p.panel.id} ${app.label}: open as a DeX window on display $display (protected content)")
        activity.runOnUiThread {
            AppLauncher.openDexWindow(activity, app, display)?.let { problem = "Opening ${app.label} as a DeX window: ${it.message}" }
        }
        removePanel(p.panel.id)
    }

    /** Where the head looks now, as yaw and pitch. */
    private fun gaze(): Pair<Float, Float> = Angles.of(head.forward).let { it.yawDeg to it.pitchDeg }

    /**
     * A press the launcher claimed (0.5.25: from [hand]'s pointer, null the trackpad's): on the open
     * launcher it starts a tap, drag or long press there ([launcherInput] has the rest); closed, it was
     * on the Apps button, which opens it.
     */
    private fun launcherPress(ray: com.hayeco.flipspaces.core.spatial.Ray, hand: HandSide? = null) {
        if (controls.isOpen) return controlPress(ray)
        if (xr.isOpen) {
            xr.down(ray, launcherSource(hand), SystemClock.uptimeMillis())
            return
        }
        setLauncherOpen(true, "the Apps button")
    }

    /** 0.5.25: one pointer's id for the launcher's press: the trackpad 0, the left hand 1, the right 2. */
    private fun launcherSource(hand: HandSide?): Int = when (hand) {
        null -> 0
        HandSide.LEFT -> 1
        HandSide.RIGHT -> 2
    }

    /** 0.5.25: the claimed press's moves and release, a right click, a swipe or a wheel on the launcher. */
    private fun launcherInput(a: SpaceAction.Launcher) {
        if (!xr.isOpen) return
        launcherActions(xr.input(a.input, launcherSource(a.hand), SystemClock.uptimeMillis()))
    }

    /** 0.5.25: what the launcher asks for: an app into a window where it was, a button, App Info, a pin. */
    private fun launcherActions(actions: List<com.hayeco.flipspaces.core.spatial.XrLauncherAction>) {
        for (action in actions) when (action) {
            is com.hayeco.flipspaces.core.spatial.XrLauncherAction.Launch -> pickForPanel?.let { id ->
                // 0.5.85: the launcher was opened by a window's Switch app (All apps): the app goes into that window.
                pickForPanel = null
                panels[id]?.let { switchApp(it, action.app) } ?: addApp(action.app, inFrontOfGaze = true, at = action.at.yawDeg to action.at.pitchDeg)
            } ?: run {
                Log.i(tag, "launcher: ${action.app.label} (${action.app.packageName}) launched into a window where the launcher was, yaw %.1f, pitch %.1f".format(java.util.Locale.ROOT, action.at.yawDeg, action.at.pitchDeg))
                addApp(action.app, inFrontOfGaze = true, at = action.at.yawDeg to action.at.pitchDeg)
            }
            is com.hayeco.flipspaces.core.spatial.XrLauncherAction.Closed -> {
                pickForPanel = null
                launcher.close()
                logLauncher("closed (${action.why})")
            }
            is com.hayeco.flipspaces.core.spatial.XrLauncherAction.AppInfo -> openAppInfo(action.app)
            is com.hayeco.flipspaces.core.spatial.XrLauncherAction.Pinned -> {
                store.favourites = action.keys
                launcher.setFavourites(action.keys)
                Log.i(tag, "launcher: ${action.keys.size} apps pinned")
            }
            // 0.5.82: the search card pressed: the phone's trackpad opens its text field and keyboard ([SpaceSession.keyboardWanted]).
            com.hayeco.flipspaces.core.spatial.XrLauncherAction.WantsKeyboard -> {
                SpaceSession.keyboardWanted.incrementAndGet()
                logLauncher("search pressed: asking the phone for its keyboard")
            }
            com.hayeco.flipspaces.core.spatial.XrLauncherAction.PinLimit -> {
                spaceNotice = "Too many apps are pinned"
                spaceNoticeUntilMs = SystemClock.uptimeMillis() + PIN_NOTICE_MS
                Log.i(tag, "launcher: Pin App with ${com.hayeco.flipspaces.core.spatial.LauncherGrid.PIN_LIMIT} pinned: \"Too many apps are pinned\"")
            }
            is com.hayeco.flipspaces.core.spatial.XrLauncherAction.Button -> {
                Log.i(tag, "launcher: the ${action.button.label} button")
                when (action.button) {
                    com.hayeco.flipspaces.core.spatial.LauncherButton.RECENTRE -> recentre()
                    com.hayeco.flipspaces.core.spatial.LauncherButton.STEADY -> GlassesDisplayControl.steady(context, !GlassesDisplayControl.steadyOn)
                    com.hayeco.flipspaces.core.spatial.LauncherButton.VIEW -> SpaceActivity.alive()?.let { a -> a.runOnUiThread { a.switchView("the launcher's 3D / 2D button") } }
                        ?: Log.w(tag, "3D / 2D button: no Space activity to ask for the display mode")
                    // 0.5.45 (Mike: the Calibrate Hands button becomes Window Presets): the Control Center, whose bottom row is the presets.
                    // 0.5.46 (Mike: Window presets opened the same card as Control panel): Notifications in its place.
                    com.hayeco.flipspaces.core.spatial.LauncherButton.NOTIFICATIONS -> openControls("the launcher's Notifications button", com.hayeco.flipspaces.core.spatial.ControlPage.NOTIFICATIONS)
                    com.hayeco.flipspaces.core.spatial.LauncherButton.CONTROLS -> openControls("the launcher's button")
                }
            }
        }
    }

    /**
     * 0.5.25: the long press menu's App Info: the system's app details screen, opened as a window on the
     * glasses' display as the protected-content offer opens apps ([openInDex]); panels only start an
     * app's launcher activity.
     */
    private fun openAppInfo(app: AppEntry) {
        val activity = context as? android.app.Activity ?: return
        val display = spaceDisplayId
        Log.i(tag, "launcher: App Info for ${app.packageName} on display $display")
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", app.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = android.app.ActivityOptions.makeBasic().apply { if (display >= 0) launchDisplayId = display }
                activity.startActivity(intent, options.toBundle())
            } catch (e: RuntimeException) {
                problem = "App Info for ${app.label}: ${e.message}"
                Log.w(tag, "launcher: App Info for ${app.packageName} failed", e)
            }
        }
    }

    private fun touch(t: SpaceAction.Touch) {
        val p = panels[t.panelId] ?: return
        if (t.phase == TouchPhase.DOWN && p.state is PanelState.Failed) {
            // A pinch on a failed panel tries again from the first attempt.
            p.state = PanelRecovery.retryByHand(p.state, "Trying ${p.panel.label} again")
            Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: retry by hand")
            return
        }
        if (p.swallowing) {
            if (t.phase == TouchPhase.UP || t.phase == TouchPhase.CANCEL) p.swallowing = false
            return
        }
        cardOf(p)?.let { card ->
            if (t.phase == TouchPhase.DOWN) {
                p.swallowing = true
                onCard(p, card, card.hit(t.u, t.v), card.buttonAt(t.u, t.v))
            }
            return
        }
        if (t.phase == TouchPhase.DOWN && p.offer != null && t.v >= 1f - OFFER_HEIGHT) {
            // The protected app offer: the cross keeps the panel, the rest opens a DeX window.
            p.swallowing = true
            if (t.u >= 1f - OFFER_CROSS) {
                Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: kept as a panel after the protected offer")
                p.offer = null
                p.offerDismissed = true
            } else {
                openInDex(p)
            }
            return
        }
        inject(p, t)
    }

    /**
     * 0.5.19: the wraparound setting against the layout: wraps every window at their median distance,
     * or unwraps them to their own distance and curve. Logged and saved.
     */
    private fun syncWrap() {
        val want = SpaceSession.wrap
        if (want == (layout.wrap != null)) return
        val next = if (want) com.hayeco.flipspaces.core.spatial.SpaceWrap.on(layout) else com.hayeco.flipspaces.core.spatial.SpaceWrap.off(layout, SpaceSession.curveDefault)
        Log.i(tag, if (want) "the Space wrapped round the head: ${next.panels.size} windows fully curved at %.2f m".format(java.util.Locale.ROOT, next.wrap?.radiusM ?: 0f) else "the Space unwrapped: each window back at its own distance and curve")
        layout = next
        setLayout(next, commit = true)
    }

    private fun setLayout(next1: SpaceLayout, commit: Boolean) {
        // 0.5.27 (Mike: Twitch no longer filled its window): at the release of Mike's own resize, a Fit video window
        // goes back to 16:9 at its height, so its stream fills it edge to edge ([com.hayeco.flipspaces.core.spatial.VideoFit.keep]).
        var next0 = next1
        if (commit) for (p in next1.panels) {
            if (!appDensity.isVideo(p.packageName)) continue
            val fitted = com.hayeco.flipspaces.core.spatial.VideoFit.keep(p)
            if (fitted == p) continue
            Log.i(PANEL_TAG, "panel ${p.id} ${p.label}: resized with Fit video on, so its window keeps 16:9 (%.1f by %.1f degrees)".format(java.util.Locale.ROOT, fitted.widthDeg, fitted.heightDeg))
            next0 = next0.replace(fitted)
        }
        // 0.5.19: while wrapped, a window pushed or pulled takes the whole wrap with it, and a new one joins it.
        val next = if (layout.wrap != null || next0.wrap != null) com.hayeco.flipspaces.core.spatial.SpaceWrap.keep(layout, next0) else next0
        layout = next
        next.panels.forEach { panels[it.id]?.panel = it }
        // 0.5.8: the quad follows every frame of a stretch or corner drag; its display follows
        // through the gate while it goes on (live), and once more at the release's commit.
        if (commit) store.saveLayout(next)
        for (p in panels.values) {
            if (!commit && p.displayId == null) continue
            // 0.5.21: the app's own size in the window's shape, with Wide view's
            // density when it is on; Arrange, Reset and depth leave the app's size alone ([PanelDisplaySize]).
            val s = displaySpec(p.panel, wideView.isOn(p.panel.packageName), p.appShape)
            // 0.5.2: a showing panel's display, buffers and input size follow through the gate
            // (resizeDisplays); one not yet open just opens at the new size.
            if (p.displayId != null) {
                // 0.5.28: while held, only a change over 5 %; the release sets the final size.
                resizeGate.request(p.panel.id, s.widthPx, s.heightPx, s.dpi, live = !commit)
            } else {
                if (s.widthPx != p.width || s.heightPx != p.height) p.resize(s.widthPx, s.heightPx)
                p.dpi = s.dpi
            }
        }
    }

    private fun recentre() {
        HeadTracker.recentre()
        follow.recentre()
        // 0.5.40 (Mike, vc60: Recentre "pushes everything to the right"): the windows' middle in front, not the room's 0.
        manualYaw = com.hayeco.flipspaces.core.spatial.RecentreContent.yaw(layout.panels, focusedPanelId)
        Log.i(PANEL_TAG, "recentre: the head's heading is straight ahead, the view turned %.1f deg to the windows' middle".format(java.util.Locale.ROOT, manualYaw))
        pointer.centre()
        // With Glasses warp on, the glasses recenter too (nothing with it off).
        GlassesWarp.recentrePressed()
        // In Ultrawide the canvas comes round on the next frame, in the tracker's new frame.
        if (ultrawide) canvasRecentre = true
    }

    private fun drainCommands() {
        while (true) {
            when (val c = SpaceSession.commands.poll() ?: break) {
                is SpaceCommand.AddApp -> addApp(c.app, c.inFrontOfGaze)
                SpaceCommand.ToggleLauncher -> toggleLauncher()
                is SpaceCommand.LauncherApps -> {
                    launcher.setApps(c.apps)
                    xr.setApps(c.apps)
                    // Pinned once, for someone with none; emptying the row later is their choice.
                    if (!store.pinsSeeded && c.apps.isNotEmpty()) {
                        store.pinsSeeded = true
                        if (store.favourites.isEmpty()) {
                            val keys = com.hayeco.flipspaces.core.spatial.LauncherDefaults.pick(c.apps)
                            store.favourites = keys
                            launcher.setFavourites(keys)
                            xr.setPinned(keys)
                            Log.i(tag, "launcher: ${keys.size} apps pinned by default")
                        }
                    }
                }
                // 0.5.25: typing goes to the launcher's search card.
                is SpaceCommand.LauncherKey -> if (xr.isOpen) {
                    c.char?.let(xr::type) ?: xr.backspace()
                }
                is SpaceCommand.LauncherSearch -> xr.setQuery(c.query)
                is SpaceCommand.LauncherNav -> launcherActions(xr.key(c.key, SystemClock.uptimeMillis()))
                is SpaceCommand.PanelKey -> panelKey(c)
                SpaceCommand.TrackpadTouched -> keepFocus()
                is SpaceCommand.Remove -> removePanel(c.panelId)
                SpaceCommand.Recentre -> recentre()
                SpaceCommand.ClearLayout -> layout.panels.map { it.id }.forEach(::removePanel)
                SpaceCommand.CalibrateHands -> toggleCalibration()
                is SpaceCommand.Arrange -> apply(pointer.arrange(c.kind, layout, focusedPanelId))
                is SpaceCommand.OpenPreset -> openPreset(c.preset)
                SpaceCommand.WrapChanged -> syncWrap()
                is SpaceCommand.GlassesWarpChanged -> warpNotice(c.on, c.from)
                SpaceCommand.SettingsChanged -> {
                    // 0.5.9: field of view, stereo and eye distance rebuild the projection.
                    applyViewport(viewport, force = true)
                    pointer.settings = pointer.settings.copy(handsSteer = store.handsSteer)
                    seeThrough = store.seeThrough
                    HeadTracker.applySettings(context)
                }
            }
        }
    }

    /**
     * A new panel for [app]: in front of the gaze from the launcher, else at the next free spot
     * around the heading (the phone list and the glasses dock, as before 0.4.0).
     */
    private fun addApp(app: AppEntry, inFrontOfGaze: Boolean, at: Pair<Float, Float>? = null) {
        // 0.5.25 (Mike: the Android XR launcher): from the launcher, where the launcher was ([at]), fading in.
        if (at != null) windowOpenMs[-1] = SystemClock.uptimeMillis()
        // 0.5.21: an app that already has a window gets that one back, in
        // front of the gaze and focused, not a second window that would take its task from the first.
        // 0.5.37: unless the Menu's "Open a new instance" is on, when it opens again in a window of its own.
        if (!store.newInstance) layout.showing(app.packageName)?.let { open ->
            val (yaw, pitch) = at ?: gaze()
            val next = if (inFrontOfGaze) layout.bringTo(open.id, yaw, pitch) else layout.raise(open.id)
            setLayout(next, commit = true)
            focus(open.id)
            windowOpenMs.remove(-1)?.let { windowOpenMs[open.id] = it }
            launcher.clearSearch()
            Log.i(PANEL_TAG, "panel ${open.id} ${app.label} already open: brought ${if (inFrontOfGaze) "in front of the gaze" else "to the front"} instead of a second window")
            return
        }
        // 0.5.19: with the default curve from the trackpad's menu (a wrapped Space: the wrap's).
        val curve = SpaceSession.curveDefault
        val added = if (inFrontOfGaze) {
            val (yaw, pitch) = at ?: gaze()
            layout.addAt(app.label, app.packageName, app.activityName, yaw, pitch, curve)
        } else {
            val yaw = HeadTracker.renderPose(record = false)?.let { Angles.of(it.forward).yawDeg } ?: -manualYaw
            layout.add(app.label, app.packageName, app.activityName, aroundYaw = yaw, curve = curve)
        }
        if (added == null) {
            windowOpenMs.remove(-1)
            problem = "Six panels is the limit; close one first."
            return
        }
        setLayout(added.first, commit = true)
        windowOpenMs.remove(-1)?.let { windowOpenMs[added.second.id] = it }
        panels[added.second.id] = PanelGl(added.second, frames ?: return, wideView.isOn(app.packageName), appDensity.smallestDp(app.packageName))
        store.recents = launcher.noteOpened(app)
        // An app opened: the search is done with, wherever the app was opened from.
        launcher.clearSearch()
        Log.i(PANEL_TAG, "panel ${added.second.id} ${app.label} added${if (inFrontOfGaze) " in front of the gaze" else ""}")
    }

    /**
     * 0.5.37 (Mike: presets open "those exact app windows in the exact size and close/far they were at"): the windows open
     * now close, and each of [preset]'s opens at its saved place, size, distance and curve.
     */
    private fun openPreset(preset: com.hayeco.flipspaces.core.spatial.WindowPreset) {
        val f = frames ?: return
        for (id in layout.panels.map { it.id }) removePanel(id)
        var opened = 0
        for (sp in preset.layout.panels) {
            val added = layout.addAt(sp.label, sp.packageName, sp.activityName, sp.yawDeg, sp.pitchDeg, sp.curve) ?: break
            val placed = added.second.copy(
                yawDeg = sp.yawDeg, pitchDeg = sp.pitchDeg, distanceM = sp.distanceM, widthDeg = sp.widthDeg, heightDeg = sp.heightDeg,
                curve = sp.curve, appWidthDp = sp.appWidthDp, appHeightDp = sp.appHeightDp, displayAreaDeg2 = sp.displayAreaDeg2,
            )
            setLayout(added.first.replace(placed), commit = true)
            windowOpenMs[placed.id] = SystemClock.uptimeMillis()
            panels[placed.id] = PanelGl(placed, f, wideView.isOn(sp.packageName), appDensity.smallestDp(sp.packageName))
            opened++
        }
        spaceNotice = "${preset.name}: $opened windows"
        spaceNoticeUntilMs = SystemClock.uptimeMillis() + 2_500L
        Log.i(PANEL_TAG, "preset ${preset.name} opened: $opened of ${preset.layout.panels.size} windows (${preset.apps})")
    }

    /** 0.5.37 (Mike: an Immersive button): the movie screen motion and the room's dimming. */
    private val immersive = com.hayeco.flipspaces.core.spatial.ImmersiveMode()

    private fun immersivePressed(id: Int) {
        val now = SystemClock.uptimeMillis()
        if (immersive.active) {
            if (immersive.phase == com.hayeco.flipspaces.core.spatial.ImmersiveMode.Phase.EXITING) return
            immersive.exit(now)
            GlassesDisplayControl.immersiveDim(false)
            Log.i(PANEL_TAG, "immersive off: panel ${immersive.heroId} back, the others return")
            return
        }
        val (yaw, pitch) = gaze()
        if (!immersive.enter(layout, id, yaw, pitch, now, frustum.horizontalFovDeg, frustum.verticalFovDeg, fixedView = ultrawide)) return
        controls.close()
        if (launcher.isOpen) setLauncherOpen(false, "Immersive")
        focus(id)
        GlassesDisplayControl.immersiveDim(true)
        Log.i(PANEL_TAG, "immersive on: panel $id ${layout.panel(id)?.label} to the movie screen, ${layout.panels.size - 1} others fade away, the room dimmed")
    }

    private fun stepImmersive() {
        val now = frameClockMs
        if (immersive.active && immersive.heroId?.let { layout.panel(it) } == null) {
            // The movie's window was closed: everything back at once.
            immersive.exit(now - 10_000L)
            GlassesDisplayControl.immersiveDim(false)
        }
        immersive.step(layout, now)?.let { setLayout(it.layout, it.commit) }
        pointer.hiddenPanels = if (!immersive.active) emptySet() else layout.panels.map { it.id }.filter { immersive.hidden(it, now) }.toSet()
    }

    private fun removePanel(id: Int) {
        if (focusedPanelId == id) focusedPanelId = null
        resizeGate.forget(id)
        relayout.forget(id)
        panels.remove(id)?.let { p ->
            p.removed = true
            p.displayId?.let { closeDisplay(it) }
            p.release()
        }
        setLayout(layout.remove(id), commit = true)
    }

    /**
     * The panel service went away (it disconnected, Shizuku stopped or restarted) or came back
     * as a new connection: the open panels' display ids are dead. Forget them and let
     * [openPending] recreate and relaunch every saved panel on the new connection.
     */
    private fun recoverIfHostLost() {
        if (liveGeneration < 0) return
        if (PanelHost.ready && PanelHost.generation == liveGeneration) return
        val why = "The panel service restarted; reopening"
        val (next, dead) = PanelRecovery.onHostLost(panels.mapValues { it.value.state }, why)
        Log.w(PANEL_TAG, "panel service generation $liveGeneration lost; dead displays $dead; reopening ${next.size} panels")
        dead.forEach { openDisplays -= it }
        for ((id, state) in next) panels[id]?.let { p ->
            p.state = state
            p.surfaceFresh = false
        }
        liveGeneration = -1
        problem = "The panel service restarted; reopening the panels."
    }

    /**
     * Opens displays for panels that need one, once the panel service is up: new panels, and
     * failed ones whose backoff has passed (three tries in all, see [PanelRecovery]).
     */
    private fun openPending(nowMs: Long) {
        if (!PanelHost.ready) {
            val waiting = when (shizuku) {
                ShizukuStatus.READY -> "Starting the panel service"
                else -> "${shizuku.label}. Set it up on the phone: Pocket XR, Spatial windows."
            }
            panels.values.filter { it.state is PanelState.Waiting }.forEach { it.state = PanelState.Waiting(waiting) }
            return
        }
        val generation = PanelHost.generation
        // 0.5.19: in view first, the rest one at a time ([PanelOpenOrder]).
        if (openStartMs == 0L) openStartMs = nowMs
        val due = panels.values.filter { PanelRecovery.shouldOpen(it.state, PanelHost.ready, nowMs) }
        if (due.isEmpty()) return
        val near = due.associateWith { PanelCulling.visible(it.panel, head, frustum, com.hayeco.flipspaces.core.spatial.PanelOpenOrder.NEAR_MARGIN_DEG) }
        var opening = panels.values.count { it.state is PanelState.Opening }
        val nearWaiting = near.count { it.value }
        for (p in due) {
            val state = p.state
            // 0.5.19: an app that just crashed on its panel is not started again by a Space restart.
            val crashedAgo = SpaceSession.crashedApps[p.panel.packageName]?.let { nowMs - it }
            if (crashedAgo != null && crashedAgo < SpaceSession.CRASH_HOLD_MS) {
                if (p.panel.id !in crashLogged) {
                    crashLogged += p.panel.id
                    Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: its app crashed ${crashedAgo / 1000} s ago; not started again for ${SpaceSession.CRASH_HOLD_MS / 1000} s")
                    p.state = PanelState.Waiting("${p.panel.label} crashed ${crashedAgo / 1000} s ago; it opens again in a minute")
                }
                continue
            }
            if (!com.hayeco.flipspaces.core.spatial.PanelOpenOrder.mayOpen(near.getValue(p), opening, nearWaiting, nowMs - openStartMs)) {
                if (p.panel.id !in lazyLogged) {
                    lazyLogged += p.panel.id
                    Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label} waits out of view: it opens when it comes within %.0f degrees of the view, or alone %d s after the start".format(
                        java.util.Locale.ROOT, com.hayeco.flipspaces.core.spatial.PanelOpenOrder.NEAR_MARGIN_DEG, com.hayeco.flipspaces.core.spatial.PanelOpenOrder.BACKGROUND_AFTER_MS / 1000,
                    ))
                }
                continue
            }
            if (!near.getValue(p)) opening++
            val attempt = PanelRecovery.nextAttempt(state)
            if (!p.surfaceFresh) p.renewSurface()
            p.state = PanelState.Opening(attempt)
            if (attempt > 1) Log.i(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: try $attempt of ${PanelRecovery.MAX_ATTEMPTS}")
            // 0.5.11: the numbers behind a sharp panel, for the device check (PanelSharpness).
            Log.i(PANEL_TAG, com.hayeco.flipspaces.core.spatial.PanelSharpness.line(p.panel.label, p.width, p.height, p.panel.widthDeg, p.panel.heightDeg, frustum, p.dpi))
            // 0.5.13: 60 Hz for the panel in focus (or the first), 30 Hz for the others.
            val rate = ThermalGuard.requestedFps(thermalStatus, focused = focusedPanelId == null || focusedPanelId == p.panel.id)
            val openDpi = p.dpi
            PanelHost.open(p.panel.label, p.panel.packageName, p.panel.activityName, p.width, p.height, p.surface, attempt, rate, openDpi) { id, error, gen ->
                if (id != null) askedRates[id] = rate
                if (p.removed || p.state != PanelState.Opening(attempt)) {
                    // A panel closed or reset (service lost) meanwhile: its late display goes.
                    if (id != null) PanelHost.release(id)
                    return@open
                }
                if (gen != generation) {
                    // The call ran on a newer connection than it was queued for: start it over there.
                    if (id != null) PanelHost.release(id)
                    p.surfaceFresh = false
                    p.state = PanelState.Waiting("The panel service reconnected; reopening")
                    return@open
                }
                if (id == null) {
                    val err = error ?: "unknown error"
                    p.surfaceFresh = false
                    p.state = PanelRecovery.afterFailure(attempt, err, SystemClock.uptimeMillis())
                    Log.w(PANEL_TAG, "panel ${p.panel.id} ${p.panel.label}: ${p.status}")
                    problem = err
                    return@open
                }
                openDisplays += id
                liveGeneration = gen
                p.tasksCheckedMs = 0L
                p.state = PanelState.Showing(id, error)
                onGl {
                    resizeGate.opened(p.panel.id, p.width, p.height, openDpi)
                    // 0.5.21: a size or density asked for while it opened goes through the gate now.
                    val s = displaySpec(p.panel, wideView.isOn(p.panel.packageName), p.appShape)
                    resizeGate.request(p.panel.id, s.widthPx, s.heightPx, s.dpi)
                    // 0.5.22 (read 60 ms after the launch, Twitch had not asked for portrait yet):
                    // what the app asks for is first read 1.5 s after it is started.
                    shapeSoon(p, SHAPE_AFTER_LAUNCH_MS)
                }
                if (error != null) problem = error
            }
        }
    }

    /** 0.5.19: when panels first could open this session, and the panels whose wait was logged. */
    private var openStartMs = 0L
    private val lazyLogged = HashSet<Int>()
    private val crashLogged = HashSet<Int>()

    private fun closeDisplay(id: Int) {
        openDisplays -= id
        PanelHost.release(id)
    }

    /** Releases every panel display. Any thread; the activity calls it when it finishes. */
    fun shutdown() {
        // 0.5.55: the render thread's hint session ends with the Space.
        boost?.closeSession()
        openDisplays.toList().forEach { PanelHost.release(it) }
        openDisplays.clear()
        // 0.5.22: every app's own orientation override state back as the Space ends (the panel service stays
        // bound 5 s after a Space; what does not get through is put back when the next Space starts).
        synchronized(override) { override.sweep(emptySet(), "the Space ended") }.forEach { runOverride(it, null) }
        launcherGl.stop()
        snapshot.stop()
        PlayingMedia.stop()
        watchdog?.stop()
        watchdog = null
        // The displays are released first, so nothing is left rendering into the panel frames.
        frames?.shutdown()
        frames = null
        gpuWatcher.shutdown()
    }

    // ---- drawing ----

    /**
     * 0.5.15: each panel's copy, mipmap build and brightness sample, for
     * the whole frame, before the late draw's sleep and pose sample; the GPU starts on them during
     * the sleep. 0.5.14 did them inside the first eye's pass, so they counted in the draw budget
     * (draw p99 20 ms in SBS) and the copy's viewport reset sent the rest of the left eye's draws
     * over the whole side by side surface.
     */
    private fun preparePanels(nowMs: Long, copyDue: Boolean = true, presentNs: Long = 0L) {
        inView = 0
        for (panel in layout.drawOrder()) preparePanel(panel, nowMs, copyDue, presentNs)
        GLES30.glFlush()
    }

    /**
     * 0.5.26: whether this refresh may take new window pictures: always, except while the glasses warp on panels that
     * do not take the phone's rate evenly (60 into 90 Hz), where pictures are taken on every [CadenceGate.every]-th
     * refresh, so each is shown for the same number of the glasses' refreshes ([GlassesCadence]).
     */
    private fun copyDueNow(presentNs: Long): Boolean {
        val even = GlassesWarp.evenContentHz
        val period = displayPeriodNs.takeIf { it > 0L } ?: (pacer.snapped() * 1e6).toLong()
        val phoneHz = if (period > 0L) kotlin.math.round(1e9 / period).toInt() else 60
        val every = if (even != null) com.hayeco.flipspaces.core.spatial.CadenceGate.every(phoneHz, even) else 1
        if (every != cadenceEvery) {
            cadenceEvery = every
            Log.i(tag, if (every > 1) "window pictures paced at $even Hz while the glasses warp (one on every $every of the phone's $phoneHz Hz refreshes), so each is shown for the same number of the glasses' refreshes" else "window pictures taken on every refresh")
        }
        return com.hayeco.flipspaces.core.spatial.CadenceGate.due(presentNs, period, every)
    }

    private var cadenceEvery = 1

    private fun preparePanel(panel: SpacePanel, nowMs: Long, copyDue: Boolean = true, presentNs: Long = 0L) {
        val p = panels[panel.id] ?: return
        p.freshThisFrame = false
        if (frames == null) return
        val s = p.src
        if (s.released) return
        val t0 = System.nanoTime()
        // 0.5.3: the drain thread takes every image whatever happens here, keeping only the
        // newest; out of view nothing is copied, and the newest is copied on the panel's return.
        // 0.5.37: a window faded out by Immersive is not copied at all, so the movie has the GPU.
        val visible = PanelCulling.visible(panel, head, frustum) && immersive.alpha(panel.id, frameClockMs) > 0.01f
        p.wasVisible = visible
        if (!visible) {
            prepareCounts.copy(com.hayeco.flipspaces.core.spatial.PanelPrepare.Copy.OUT_OF_VIEW)
            perf.panel(panel.id, System.nanoTime() - t0, false)
            return
        }
        inView++
        // 0.5.28: at most 5 fps while the glasses lie off the head ([offHead]).
        // 0.5.31: a focused window with Fit video on steps down with the heat too.
        // 0.5.56: and to the window's rate by use ([updatePanelRates]): 30 or 15 when it is not in use and not playing video.
        val thermalFps = ThermalGuard.panelFps(thermalStatus, focused = panel.id == focusedPanelId, video = appDensity.isVideo(panel.packageName))
        val capFps = offHead.capFps(minOf(thermalFps, p.rate?.fps ?: ThermalGuard.FULL_FPS))
        // 0.5.17: copied only with a new image (PanelPrepare), counted for the perf line.
        var plan = com.hayeco.flipspaces.core.spatial.PanelPrepare.copy(s.mailbox.hasNew, visible, capFps, nowMs, s.lastCopyMs)
        if (!copyDue && plan == com.hayeco.flipspaces.core.spatial.PanelPrepare.Copy.COPIED) plan = com.hayeco.flipspaces.core.spatial.PanelPrepare.Copy.HELD_TO_CAP
        if (plan == com.hayeco.flipspaces.core.spatial.PanelPrepare.Copy.COPIED) {
            // The mailbox may refuse the lease (images still on the GPU): nothing new was copied then.
            if (!PanelImport.copy(s, nowMs, viewport.surfaceWidth, viewport.surfaceHeight, presentNs)) plan = com.hayeco.flipspaces.core.spatial.PanelPrepare.Copy.NO_NEW_IMAGE
            else if (s.blackCheckChain) prepareCounts.chain(s.texture.fullLevels, forBlackCheck = true)
        }
        prepareCounts.copy(plan)
        p.dropPrevious(nowMs)
        val d = p.drawn
        // 0.5.17: a chain only after a new copy, only for a panel shown smaller than its pixels, and
        // only down to the levels its shrink samples; near 1:1 one level and bilinear filtering.
        val levels = com.hayeco.flipspaces.core.spatial.PanelPrepare.mipLevels(PanelMips.texelsPerPixel(panel, p.width, frustum), d.texture.fullLevels)
        if (d.copies > 0) d.texture.prepare(levels).takeIf { it > 0 }?.let { prepareCounts.chain(it, forBlackCheck = false) }
        p.freshThisFrame = s.copies != p.copiesSeen
        p.copiesSeen = s.copies
        perf.panel(panel.id, System.nanoTime() - t0, p.freshThisFrame)
    }

    private fun drawPanel(placed: SpacePanel, eyeViewProj: Mat4, nowMs: Long) {
        // 0.5.25 (Mike: the Android XR launcher): a window launched from the launcher fades and grows in, and
        // every window ghosts while the launcher is open ([windowLook]).
        val (panel, windowAlpha) = windowLook(placed, nowMs)
        // 0.5.26: a close window side by side, at a comfortable convergence ([com.hayeco.flipspaces.core.spatial.StereoComfort]).
        val viewProj = comfortViewProj(eyeViewProj, panel.distanceM)
        val p = panels[panel.id] ?: return
        if (frames == null) return
        val s = p.src
        if (s.released) return
        // Culled again with the late pose; copies and counts were done in [preparePanels].
        if (!PanelCulling.visible(panel, head, frustum)) return
        // 0.5.9: the second eye draws the same panel again; checks happen once.
        val firstEye = eyePass == 0
        val mips = PanelMips.needsMips(panel, p.width, frustum)
        val d = p.drawn
        val read = if (d.copies > 0) 0 else -1
        // 0.5.0: lit by any pointer, the head gaze's or either hand's.
        val hover = pointer.hoverOn(panel.id)
        val grabZone = pointer.grabOn(panel.id)
        val grabbed = grabZone != null
        // 0.5.19: every part of the window on its surface, curved or flat ([PanelCurve]); a flat window's
        // rectangles are 0.5.18's quads exactly.
        val body = PanelCurve.body(panel)
        val showing = p.state as? PanelState.Showing
        val card = cardOf(p)
        // 0.5.69: with no card up its fade goes back to 0, so the next one fades in again.
        if (card == null && firstEye) p.cardLook.at(false, frameClockMs)
        if (card != null && card.wholeWindow) {
            drawCard(p, panel, card, viewProj)
        } else if (read >= 0 && p.hasFrame && showing != null && !showing.paused) {
            // 0.5.13: the footprint filter where level 0 is sampled (below the mips' 1.9 texels a pixel).
            // 0.5.15 took it out of side by side, where every panel is drawn twice.
            // 0.5.18: back in side by side too. The eye passes are 0.3 ms each (p50); the draw's time was the
            // window buffer wait, not the taps. One tap made text shimmer as the shown pose moved under a
            // pixel (StillHeadReplayTest: 11 % a vsync on a still table, 0.4 % with four taps).
            // 0.5.19: on a curved window too: the taps follow each pixel's own footprint on the mesh.
            // 0.5.20: the app's picture edge to edge, with rounded corners; no bar over it.
            // 0.5.23: the texture is the display's size, so its UVs are the whole of it; a picture from before a resize
            // (another shape) is cropped about its centre to the window's, never stretched ([com.hayeco.flipspaces.core.spatial.PanelUv]).
            fun matrixFor(t: PanelSource): FloatArray {
                val uv = com.hayeco.flipspaces.core.spatial.PanelUv.cover(t.texture.width, t.texture.height, com.hayeco.flipspaces.core.spatial.PanelDisplaySize.aspect(panel))
                return if (uv[0] == 0f && uv[1] == 0f && uv[2] == 1f && uv[3] == 1f) GlKit.IDENTITY else com.hayeco.flipspaces.core.spatial.PanelUv.matrix(uv)
            }
            // 0.5.45: after a resize the picture from before stays under the new one while that fades in ([PanelGl.dropPrevious]).
            val fade = p.fadeIn(frameClockMs)
            val old = p.fadingOut?.takeIf { fade < 1f && it.copies > 0 && !it.released }
            // 0.5.69 ("Light on glass"): the focused window's soft accent glow round its edge, under the picture.
            // 0.5.88: every window lit while all are held together.
            drawFocusHalo(p, panel, viewProj, windowAlpha, hover != null || pointer.allWindowsHeld, firstEye, hover?.frame == true || (grabZone == PanelZone.TITLE_BAR && pointer.heldPanelId != panel.id) || pointer.allWindowsHeld)
            if (old != null) drawOn(body, viewProj, old.texture.id, windowAlpha, matrixFor(old), footprint = false, round = bodyRounding(panel))
            val a = if (old != null) windowAlpha * (fade * fade * (3f - 2f * fade)) else windowAlpha
            drawOn(body, viewProj, d.texture.id, a, matrixFor(d), footprint = !mips && store.footprintFilter, round = bodyRounding(panel, p, windowAlpha))
            if (firstEye) checkProtected(p, nowMs)
            p.offer?.takeIf { card == null }?.let { offer ->
                p.banner.set(listOf(offer), Look.mix(Look.GLASS, Look.DANGER, 0.55f), Look.INK, 30f, badge = "\u2715")
                drawOn(offerRect(panel), viewProj, p.banner.id, 1f)
            }
            // 0.5.21: Wide view's tip, or why it cannot turn off, for 4 s.
            p.notice?.takeIf { card == null && p.offer == null && nowMs < p.noticeUntilMs }?.let { notice ->
                p.banner.set(listOf(notice), Look.GLASS_RAISED, Look.INK, 26f)
                drawOn(offerRect(panel), viewProj, p.banner.id, 1f)
            }
            card?.let { drawCard(p, panel, it, viewProj) }
        } else {
            p.placeholder.set(listOf(panel.label, "", p.status), Look.GLASS, Look.INK_2, 34f, centred = true)
            drawOn(body, viewProj, p.placeholder.id, windowAlpha, round = bodyRounding(panel, p, windowAlpha))
        }
        // 0.5.20 (Mike: no bars): the window's controls float apart from it, a gap above its top
        // edge ([WindowControls]): round Back, Reset and Close buttons and the Move pill, nothing
        // behind them. As 0.5.17's bar: shown while a pointer is on the window, then fading (shown
        // 1.8 s, then a 0.7 s fade), each lit while pointed at; on the window's curve when it is curved.
        // 0.5.75 (Mike: "when i put the cursor dot on a window and it auto-hides, the pill and other window buttons are still
        // showing"): with the head cursor on auto hide, the controls fade with the dot (its alpha from the cursor's pass,
        // [headCursorAlpha]); a hand's cursor, a held window, or the cursor set to always or off keep 0.5.17's own fade.
        val withDot = pointer.activeHands.isEmpty() && pointer.heldPanelId == null &&
            headCursor.mode == com.hayeco.flipspaces.core.spatial.HeadCursorMode.AUTO
        val barAlpha = pointer.barAlpha(panel.id) * (if (withDot) headCursorAlpha else 1f)
        if (barAlpha > 0.01f) {
            // 0.5.74: the layout and capsules once a frame, in the first eye's pass; the second eye reuses them.
            if (firstEye || p.controlLayout == null) {
                p.controlLayout = WindowControls.layout(panel).also { p.controlCapsules = WindowControls.capsules(panel, it) }
            }
            val l = p.controlLayout!!
            val a = barAlpha
            val arcW = PanelCurve.arcWidthM(panel)
            val rimM = rimWidthM(panel)
            // 0.5.69 (Mike: the loose round buttons; "Light on glass"): the round buttons on glass capsules, one per group
            // ([WindowControls.capsules]), each with the hairline rim. A button's glyph is drawn alone (white on nothing),
            // and its hover is a disc of light under it that eases in and out ([LookTween]), so a hover never paints or
            // uploads a texture again (0.5.20 to 0.5.68 re-uploaded the icon on every hover change).
            // 0.5.69 (Mike: "more amazing depth effects"): the controls float [Look.DEPTH_CONTROLS] in front of the window, and
            // a hovered button rises [Look.DEPTH_HOVER] further toward the eyes, real depth in side by side 3D ([lift]).
            for (cap in p.controlCapsules) {
                colorOn(
                    lift(PanelCurve.rect(panel, cap.u0, cap.v0, cap.u1, cap.v1), Look.DEPTH_CONTROLS), viewProj, look(Look.GLASS, a),
                    Rounding(cap.radiusM, (cap.u1 - cap.u0) * arcW, (cap.v1 - cap.v0) * panel.heightM, Look.alpha(Look.RIM, a), rimM),
                )
            }
            fun button(c: WindowControls.Control, tex: IconTexture, icon: IconTexture.Icon, light: Int = Look.ACCENT, enabled: Boolean = true) {
                val tween = p.controlLook(c)
                val hot = if (firstEye) tween.at(enabled && hover?.zone == c.zone, frameClockMs) else tween.value
                val r = lift(PanelCurve.control(panel, c, l), Look.DEPTH_CONTROLS + Look.DEPTH_HOVER * hot)
                if (hot > 0.01f) colorOn(r, viewProj, look(light, a * hot), Rounding(l.heightM / 2f, l.heightM, l.heightM))
                tex.set(icon, Color.TRANSPARENT, if (enabled) Look.INK else Look.INK_3)
                drawOn(r, viewProj, tex.id, a)
            }
            button(WindowControls.Control.BACK, p.back, IconTexture.Icon.BACK)
            if (panel.isApp) button(WindowControls.Control.SWITCH_APP, p.switchApp, IconTexture.Icon.SWITCH)
            button(WindowControls.Control.REFRESH, p.refresh, IconTexture.Icon.REFRESH)
            button(WindowControls.Control.RESET, p.reset, IconTexture.Icon.RESET)
            button(WindowControls.Control.CLOSE, p.close, IconTexture.Icon.CLOSE, light = Look.DANGER)
            // 0.5.21: Wide view, under the window: on, off, or (an app that asks for landscape) unavailable, dimmed.
            if (panel.isApp) {
                val broadOn = wideView.isOn(panel.packageName) || p.appShape?.asksLandscape == true
                val unavailable = !com.hayeco.flipspaces.core.spatial.WideView.canTurnOff(p.appShape)
                button(WindowControls.Control.WIDE_VIEW, p.broad, if (broadOn) IconTexture.Icon.BROAD_ON else IconTexture.Icon.BROAD_OFF, enabled = !unavailable)
                // 0.5.24: Fit video beside it, filled while the app has its video density.
                button(WindowControls.Control.FIT_VIDEO, p.fit, if (appDensity.isVideo(panel.packageName)) IconTexture.Icon.FIT_ON else IconTexture.Icon.FIT_OFF)
                // 0.5.37: Immersive, left of Wide view, filled while this window is the movie screen.
                button(WindowControls.Control.IMMERSIVE, p.immersive, if (immersive.heroId == panel.id) IconTexture.Icon.IMMERSIVE_ON else IconTexture.Icon.IMMERSIVE_OFF)
            }
            // The Move pill: glass with its rim, turning accent while pointed at or held; its grip brighter on the focused window.
            // 0.5.75: not for the grab band round the window, which lights the window's frame instead.
            val moveActive = (hover?.zone == PanelZone.TITLE_BAR && !hover.frame) || grabZone == PanelZone.TITLE_BAR
            val moveTween = p.controlLook(WindowControls.Control.MOVE)
            val moveHot = if (firstEye) moveTween.at(moveActive, frameClockMs) else moveTween.value
            val move = l.of(WindowControls.Control.MOVE)
            val pillW = (move.u1 - move.u0) * arcW
            colorOn(
                lift(PanelCurve.rect(panel, move.u0, move.v0, move.u1, move.v1), Look.DEPTH_CONTROLS + Look.DEPTH_HOVER * moveHot), viewProj,
                look(Look.mix(Look.GLASS, Look.ACCENT, moveHot), a),
                Rounding(l.heightM / 2f, pillW, l.heightM, Look.alpha(Look.RIM, a * (1f - moveHot)), rimM),
            )
            val gripU = (move.u1 - move.u0) * 0.2f
            val gripV = (move.v1 - move.v0) * 0.09f
            val grip = if (panel.id == focusedPanelId || moveHot > 0.5f) Look.INK else Look.INK_2
            colorOn(
                lift(PanelCurve.rect(panel, move.uCentre - gripU, move.vCentre - gripV, move.uCentre + gripU, move.vCentre + gripV), Look.DEPTH_CONTROLS + Look.DEPTH_HOVER * moveHot), viewProj,
                look(grip, a),
                Rounding(gripV * panel.heightM, 2f * gripU * arcW, 2f * gripV * panel.heightM),
            )
        }
        // 0.5.75 (Mike: the glow "does not make it obvious enough that its grabbable"): on the grab band the window gets a
        // crisp accent frame drawn in the band itself, where the cursor is, with a spot of light on it under the cursor.
        val frameLit = p.frameLook.value
        if (frameLit > 0.01f) drawGrabFrame(panel, viewProj, frameLit * windowAlpha, hover?.takeIf { it.frame })
        // 0.5.86: where the held window would fit between two others, shown as a glass outline of that size; taken on the drop.
        pointer.fitPreview?.takeIf { it.panelId == panel.id }?.let { f ->
            val ghost = panel.copy(yawDeg = f.yawDeg, pitchDeg = f.pitchDeg, widthDeg = f.widthDeg, heightDeg = f.heightDeg)
            val gw = PanelCurve.arcWidthM(ghost)
            colorOn(
                PanelCurve.body(ghost), viewProj, look(Look.ACCENT, 0.07f),
                Rounding(WindowControls.cornerRadiusM(ghost), gw, ghost.heightM, Look.alpha(Look.ACCENT, 0.7f), 2f * rimWidthM(ghost)),
            )
        }
        // 0.5.17: the held window's eight corner brackets (fading in 0.6 s, out 0.3 s) and its guide.
        // 0.5.88: and every window's while all are held together, the group's guide on the one nearest its centre.
        val held = pointer.heldPanelId == panel.id
        val frameAlpha = if (firstEye) p.holdFrame.update(held || pointer.allWindowsHeld, nowMs) else p.holdFrame.alpha
        if (frameAlpha > 0.01f) drawHoldFrame(panel, viewProj, frameAlpha)
        val guideText = if (held) pointer.holdGuide() else if (pointer.allGuidePanelId == panel.id) pointer.allGuide() else null
        if (guideText != null) guideText.let { text ->
            p.guide.set(listOf(text), Look.GLASS, Look.INK, 36f, centred = true)
            drawOn(PanelCurve.rect(panel, 0.35f, 1.02f, 0.65f, 1.09f), viewProj, p.guide.id, 1f)
        }
        // Resize handle, shown when the pointer is over the panel or resizing it. 0.5.20: an L just
        // outside the bottom right corner, apart from the window like its other controls.
        // 0.5.69: in the secondary ink, the accent while pointed at or held.
        // 1.1.15: Lock screens: no handle, and a small lock in its place says why the window will not move.
        if (pointer.windowsLocked) {
            if (hover != null) {
                val l = WindowControls.layout(panel)
                val size = l.heightM * 0.8f
                val du = size / PanelCurve.arcWidthM(panel).coerceAtLeast(1e-4f)
                val dv = size / panel.heightM.coerceAtLeast(1e-4f)
                val off = l.heightM * 0.25f
                val u0 = 1f + off / PanelCurve.arcWidthM(panel).coerceAtLeast(1e-4f)
                val v0 = 1f + off / panel.heightM.coerceAtLeast(1e-4f)
                p.lockIcon.set(IconTexture.Icon.LOCK, Color.TRANSPARENT, Look.INK_2)
                drawOn(lift(PanelCurve.rect(panel, u0 - du / 2f, v0 - dv / 2f, u0 + du / 2f, v0 + dv / 2f), Look.DEPTH_CONTROLS), viewProj, p.lockIcon.id, windowAlpha)
            }
        } else if (hover != null || grabbed) {
            val cornerActive = hover?.zone == PanelZone.CORNER || grabZone == PanelZone.CORNER
            val rgba = look(if (cornerActive) Look.ACCENT else Look.INK_2, 1f)
            PanelCurve.cornerBracket(panel).forEach { colorOn(it, viewProj, rgba) }
        }
    }

    /**
     * 0.5.75: the grab band's frame: a rounded outline [FRAME_LINE_PER_M] of the distance wide, in the middle of the band
     * ([com.hayeco.flipspaces.core.spatial.PanelHitTester.FRAME_INSET_DEG] + half [com.hayeco.flipspaces.core.spatial.PanelHitTester.FRAME_DEG]
     * out), and a soft spot of light where [at]'s ray meets it.
     */
    private fun drawGrabFrame(panel: SpacePanel, viewProj: Mat4, alpha: Float, at: com.hayeco.flipspaces.core.spatial.PanelHit?) {
        val bodyW = PanelCurve.arcWidthM(panel)
        val outDeg = com.hayeco.flipspaces.core.spatial.PanelHitTester.FRAME_INSET_DEG + com.hayeco.flipspaces.core.spatial.PanelHitTester.FRAME_DEG / 2f
        // 0.5.79 (Mike: the frame "looks like its overlapping on the pill and window buttons"): never further out than the
        // middle of the gap between the window and its button rows, so the line runs between them, not through them.
        val outM = minOf(outDeg / 57.29578f * panel.distanceM, 0.5f * WindowControls.layout(panel).gapM)
        val gu = outM / bodyW
        val gv = outM / panel.heightM
        val w = bodyW + 2f * outM
        val h = panel.heightM + 2f * outM
        colorOn(
            PanelCurve.rect(panel, -gu, -gv, 1f + gu, 1f + gv), viewProj, look(Look.ACCENT, 0f),
            // 0.5.78 (Mike: "very solid color and would be nicer if it blended in more since its overtaking the other windows"):
            // a thinner line of light, between white and the accent, at under half strength.
            Rounding(WindowControls.cornerRadiusM(panel) + outM, w, h, Look.alpha(Look.mix(Look.INK, Look.ACCENT, 0.55f), FRAME_LINE_ALPHA * alpha), FRAME_LINE_PER_M * panel.distanceM),
        )
        val hit = at ?: return
        drawDisc(viewProj, hit.point, FRAME_SPOT, look(Look.ACCENT, FRAME_SPOT_ALPHA * alpha), GlKit.CIRCLE_GLOW)
    }

    /** 0.5.69: [argb] (straight) with its alpha times [alpha], as drawColor takes it; a new array, as the 0.5.20 colours were. */
    private fun look(argb: Int, alpha: Float): FloatArray = Look.rgba(argb, alpha)

    /**
     * 0.5.69 (Mike: "more amazing depth effects"): [r] brought [share] of its distance nearer, scaled about the eyes' point
     * (the room's origin, where the head ray starts): every point slides along its own line of sight, so the shape keeps its
     * angular size and stays under the head ray's cursor (aiming is unchanged), and only its stereo depth changes.
     */
    private fun lift(r: PanelCurve.Rect, share: Float): PanelCurve.Rect {
        if (share <= 0f) return r
        val k = 1f - share
        val m = r.model.m.copyOf()
        for (col in 0..3) for (row in 0..2) m[col * 4 + row] *= k
        return r.copy(model = Mat4(m))
    }

    /** 0.5.69: the hairline rim's width on [panel]: about 1.5 of the glasses' pixels at its distance. */
    private fun rimWidthM(panel: SpacePanel): Float = RIM_PER_M * panel.distanceM

    /**
     * 0.5.69 ("Light on glass": on the see-through glasses a shadow is invisible, so focus is shown with light): the
     * focused window, when there is more than one, gets a soft accent glow [HALO_PER_M] of its distance wide round its
     * edge. One quad on the window's surface (curved with it), drawn before its picture; no blur pass.
     */
    private fun drawFocusHalo(p: PanelGl, panel: SpacePanel, viewProj: Mat4, windowAlpha: Float, hovered: Boolean, firstEye: Boolean, onFrame: Boolean = false) {
        val focused = panel.id == focusedPanelId && layout.panels.size > 1
        val focus = if (firstEye) p.focusLook.at(focused, frameClockMs) else p.focusLook.value
        if (firstEye) p.hoverLook.at(hovered, frameClockMs)
        // 0.5.75 (Mike: drag windows from their outsides, as in Android XR): on the grab band, or while dragged from it, the
        // glow brightens and the rim turns the accent ([bodyRounding]): the window's frame lights to say it can be taken.
        val frame = if (firstEye) p.frameLook.at(onFrame, frameClockMs) else p.frameLook.value
        if (focus < 0.01f && frame < 0.01f) return
        val bodyW = PanelCurve.arcWidthM(panel)
        val haloM = HALO_PER_M * panel.distanceM
        val gu = haloM / bodyW
        val gv = haloM / panel.heightM
        val r = PanelCurve.rect(panel, -gu, -gv, 1f + gu, 1f + gv)
        val prog = GlKit.color
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        GlKit.applyRound(prog, WindowControls.cornerRadiusM(panel), bodyW, panel.heightM)
        GlKit.applyHalo(prog, bodyW + 2f * haloM, panel.heightM + 2f * haloM, haloM)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, (viewProj * r.model).m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, GlKit.IDENTITY, 0)
        val c = look(Look.ACCENT, (HALO_ALPHA * focus + FRAME_HALO_ALPHA * frame).coerceAtMost(1f) * windowAlpha)
        GLES30.glUniform4f(prog.color, c[0], c[1], c[2], c[3])
        GLES30.glUniform1f(prog.circle, 2f)
        GlKit.drawShape(prog, r)
    }

    /**
     * Once a second, reads a few pixels of a small mip level: a panel that stays black while
     * its app keeps drawing is protected content (FLAG_SECURE or DRM), and gets the offer to
     * open as a DeX window. The service's window list says which of the two it is.
     */
    private fun checkProtected(p: PanelGl, nowMs: Long) {
        // 0.5.2: the frame thread samples the brightness about once a second (PanelFrameThread).
        val s = p.src
        if (p.offerDismissed || s.lumaSeq == p.lumaSeen) return
        p.lumaSeen = s.lumaSeq
        val wasLong = p.black.blackLongEnough
        val was = p.black.protected
        val frames = s.framesSinceLuma
        s.framesSinceLuma = 0
        val now = p.black.sample(s.luma, frames, nowMs)
        val label = p.panel.label
        if (p.black.blackLongEnough && !wasLong) {
            // Black for long enough: ask the window manager, so a still secure screen (a banking
            // app that draws nothing new) is caught too, not only video.
            p.displayId?.let { d ->
                PanelHost.secure(d) { packages ->
                    if (p.panel.packageName !in packages || p.offerDismissed) return@secure
                    Log.i(PANEL_TAG, "panel ${p.panel.id} $label: FLAG_SECURE window on display $d; offering a DeX window")
                    p.offer = ProtectedOffer.message(label, secure = true)
                    problem = "$label protects its screen; pinch its banner to open it as a DeX window."
                }
            }
        }
        if (now && !was && p.offer == null) {
            Log.i(PANEL_TAG, "panel ${p.panel.id} $label: black for ${p.black.blackForMs} ms while drawing; offering a DeX window")
            p.offer = ProtectedOffer.message(label, secure = false)
            problem = "$label looks protected on its panel; pinch its banner to open it as a DeX window."
        } else if (!p.black.blackLongEnough && p.offer != null) {
            p.offer = null
        }
    }

    /** The protected app banner along the bottom of a panel. */
    private fun offerRect(panel: SpacePanel): PanelCurve.Rect = PanelCurve.rect(panel, 0f, 1f - OFFER_HEIGHT, 1f, 1f)

    /**
     * 0.5.17: a held window's eight corner brackets in the held colour ([com.hayeco.flipspaces.core.Look.HELD]): two thin
     * bars at each corner, just outside the body.
     */
    private fun drawHoldFrame(panel: SpacePanel, viewProj: Mat4, alpha: Float) {
        val c = com.hayeco.flipspaces.core.spatial.HoldFrameFade.COLOR
        val rgba = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, alpha)
        val len = 0.08f
        // 0.5.19: across in fractions of the body's width along its surface (its arc when curved).
        val bodyW = PanelCurve.arcWidthM(panel)
        val tu = 0.006f * panel.heightM / bodyW * 2f
        val tv = 0.012f
        val lu = len * panel.heightM / bodyW
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val u = if (right) 1f + tu else -tu
            val v = if (bottom) 1f + tv else -tv
            val u0 = if (right) 1f + tu - lu else -tu
            val v0 = if (bottom) 1f + tv - len else -tv
            // Along the top or bottom edge, then down or up the side.
            colorOn(PanelCurve.rect(panel, minOf(u0, u0 + lu), minOf(v, v + if (bottom) -tv else tv), maxOf(u0, u0 + lu), maxOf(v, v + if (bottom) -tv else tv)), viewProj, rgba)
            colorOn(PanelCurve.rect(panel, minOf(u, u + if (right) -tu else tu), minOf(v0, v0 + len), maxOf(u, u + if (right) -tu else tu), maxOf(v0, v0 + len)), viewProj, rgba)
        }
    }

    /**
     * App closed over the whole panel, or the Back question, with their buttons.
     *
     * 0.5.69 (Mike: "the Back would close X grey popup in the window is so badly designed"): a glass dialog
     * ([PanelCard.box]) over the window, which dims behind it (black is see-through on the glasses, so the window fades
     * back), lifted [Look.DEPTH_DIALOG] toward the eyes ([lift]); a title in Inter Medium, a quieter line under it, and
     * pill buttons that light with the accent (red for closing) and rise on hover. It fades in and rises 2 % over
     * [Look.ENTER_MS]. Each line of text keeps its texture's shape whatever the window's, so it is never squashed.
     */
    private fun drawCard(p: PanelGl, panel: SpacePanel, card: PanelCard, viewProj: Mat4) {
        val firstEye = eyePass == 0
        val show = if (firstEye) p.cardLook.at(true, frameClockMs) else p.cardLook.value
        if (show < 0.01f) return
        val arcW = PanelCurve.arcWidthM(panel)
        val bodyH = panel.heightM
        val closed = card.wholeWindow
        val rimM = rimWidthM(panel)
        // The window behind: dimmed (an app that closed has no picture: its glass fills the window instead).
        if (!closed) colorOn(PanelCurve.body(panel), viewProj, look(0xFF000000.toInt(), 0.5f * show), bodyRounding(panel))
        val b = card.box
        val depth = Look.DEPTH_DIALOG * show
        val boxW = (b[2] - b[0]) * arcW
        val boxH = (b[3] - b[1]) * bodyH
        val radius = if (closed) WindowControls.cornerRadiusM(panel) else minOf(0.08f * boxH, 0.03f * panel.distanceM)
        val rise = Look.ENTER_SCALE + (1f - Look.ENTER_SCALE) * show
        colorOn(
            lift(PanelCurve.rect(panel, b[0], b[1], b[2], b[3]), depth), viewProj, look(if (closed) Look.GLASS else Look.GLASS_RAISED, show * rise),
            Rounding(radius, boxW, boxH, Look.alpha(Look.RIM_FOCUS, show), rimM),
        )
        // A line of text centred on the box at [v], [h] of the window's height tall, as wide as its texture's shape
        // allows, never wider than the box.
        fun line(tex: LabelTexture, texW: Int, texH: Int, v: Float, h: Float, alpha: Float) {
            var hM = h * bodyH
            var wM = hM * texW / texH
            val maxW = 0.94f * boxW
            if (wM > maxW) {
                hM *= maxW / wM
                wM = maxW
            }
            val uC = (b[0] + b[2]) / 2f
            val du = wM / arcW / 2f
            val dv = hM / bodyH / 2f
            drawOn(lift(PanelCurve.rect(panel, uC - du, v - dv, uC + du, v + dv), depth), viewProj, tex.id, alpha)
        }
        p.cardTitle.set(listOf(card.title), Color.TRANSPARENT, Look.INK, CARD_TITLE_PX * card.titleScale, centred = true, medium = true)
        line(p.cardTitle, CARD_LINE_W, CARD_LINE_H, card.titleV, card.titleH, show)
        card.detail?.let { d ->
            p.cardDetail.set(listOf(d), Color.TRANSPARENT, Look.INK_2, CARD_TITLE_PX * card.detailScale, centred = true)
            line(p.cardDetail, CARD_LINE_W, CARD_LINE_H, card.detailV, card.detailH, show)
        }
        val hit = pointer.hoverOn(panel.id)?.takeIf { it.zone == PanelZone.BODY }
        val tileCount = card.buttons.count { it.app != null }
        card.buttons.forEachIndexed { i, btn ->
            // 0.5.85: an app tile of the Switch app card: a glass square with the app's icon and name, rising on hover.
            val app = btn.app
            if (app != null) {
                val tile = p.cardTiles.getOrPut(i) { LabelTexture(256, 256) }
                val tw = p.cardTileLooks.getOrNull(i) ?: return@forEachIndexed
                val hotT = if (firstEye) tw.at(hit != null && btn.contains(hit.u, hit.v), frameClockMs) else tw.value
                val w = (btn.u1 - btn.u0) * arcW
                val h = (btn.v1 - btn.v0) * bodyH
                val r = lift(PanelCurve.rect(panel, btn.u0, btn.v0, btn.u1, btn.v1), depth + Look.DEPTH_HOVER * hotT)
                colorOn(r, viewProj, look(Look.mix(Look.GLASS_HOVER, Look.ACCENT_DEEP, hotT), show), Rounding(0.2f * w, w, h, Look.alpha(Look.mix(Look.RIM, Look.RIM_FOCUS, hotT), show), rimM))
                tile.set(listOf(app.label), Color.TRANSPARENT, Look.INK, 30f, centred = true, icon = switcherIcons[app.key], medium = true)
                drawOn(r, viewProj, tile.id, show)
                return@forEachIndexed
            }
            val pill = i - tileCount
            val tex = p.cardButtons.getOrNull(pill) ?: return@forEachIndexed
            val tween = p.cardButtonLooks[pill]
            val hot = if (firstEye) tween.at(hit != null && btn.contains(hit.u, hit.v), frameClockMs) else tween.value
            val w = (btn.u1 - btn.u0) * arcW
            val h = (btn.v1 - btn.v0) * bodyH
            val rest = if (btn.primary) Look.ACCENT_DEEP else Look.GLASS_HOVER
            val lit = if (btn.danger) Look.DANGER else Look.ACCENT
            val r = lift(PanelCurve.rect(panel, btn.u0, btn.v0, btn.u1, btn.v1), depth + Look.DEPTH_HOVER * hot)
            colorOn(r, viewProj, look(Look.mix(rest, lit, hot), show), Rounding(h / 2f, w, h, Look.alpha(Look.RIM, show * (1f - hot)), rimM))
            tex.set(listOf(btn.label), Color.TRANSPARENT, Look.INK, CARD_BUTTON_PX, centred = true, medium = true)
            // The label: half the pill's height, its texture's shape, centred on the pill.
            var hM = 0.62f * h
            var wM = hM * CARD_BUTTON_W / CARD_BUTTON_H
            if (wM > 0.9f * w) {
                hM *= 0.9f * w / wM
                wM = 0.9f * w
            }
            val uC = (btn.u0 + btn.u1) / 2f
            val vC = (btn.v0 + btn.v1) / 2f
            drawOn(
                lift(PanelCurve.rect(panel, uC - wM / arcW / 2f, vC - hM / bodyH / 2f, uC + wM / arcW / 2f, vC + hM / bodyH / 2f), depth + Look.DEPTH_HOVER * hot),
                viewProj, tex.id, show,
            )
        }
    }

    /**
     * The launcher's backdrop and tiles while it is open, then the home button, always. The
     * button is drawn last (0.4.3): drawn before the opaque backdrop it was hidden behind it
     * while the launcher was open, though a pinch on it still closed the launcher.
     */
    /** The Apps / Close home tile (0.5.20: its own layer of [com.hayeco.flipspaces.core.spatial.SpaceLayer]). */
    private fun drawHome(viewProj: Mat4, rays: List<com.hayeco.flipspaces.core.spatial.Ray>) {
        // 0.5.25 (Mike: the Android XR launcher): the open launcher has no Close button of its own (a tap on
        // empty space, Back, Home or three fingers close it), so the Apps button hides while it shows.
        if (xr.visible(frameClockMs)) return
        // 0.5.11: closed, the home button is drawn only while it is revealed, faded by its alpha.
        val alpha = if (launcher.isOpen) 1f else launcher.reveal.alpha
        if (alpha <= 0.01f) return
        homeTexture?.let { tex ->
            val home = launcher.homeTile()
            val hot = launcher.homeHittable && rays.any { home.hit(it) != null }
            tex.set(listOf(if (launcher.isOpen) "Close" else "Apps"), if (hot) Color.rgb(70, 120, 210) else Color.argb(220, 36, 42, 54), Color.WHITE, 40f, centred = true)
            drawLabel(tex.id, viewProj * home.asPanel().model(), 0.95f * alpha)
        }
    }

    /** 0.5.11: the Apps button's reveal: looking down, a pointer resting on it, or the open launcher keep it up. */
    private fun updateHome(nowMs: Long) {
        // 0.5.36 (Mike: remove the Apps button that appears on a look down): never revealed, so never drawn or pressed;
        // the launcher still opens from Home and three fingers up.
        if (!APPS_BUTTON) return
        val (yaw, pitch) = gaze()
        val home = launcher.homeTile()
        val hovered = launcher.reveal.shown && pointerRays().any { home.hit(it) != null }
        launcher.updateHome(yaw, pitch, frustum.verticalFovDeg, nowMs, hovered)?.let { Log.i(tag, it) }
    }

    /** The rays that aim now: each hand's while hands aim (0.5.0), else the head gaze's. */
    private fun pointerRays(): List<com.hayeco.flipspaces.core.spatial.Ray> {
        // 0.5.55: into one kept list (twice a frame, a new list and a filtered copy each time before).
        val out = rayList
        out.clear()
        for (side in pointer.activeHands) pointer.ray(head, side)?.let { out += it }
        if (out.isEmpty()) out += pointer.ray(head)
        return out
    }

    private val rayList = ArrayList<com.hayeco.flipspaces.core.spatial.Ray>(2)

    /**
     * 0.5.25 (Mike: "The app launcher must look exactly like, and work exactly like, the Android XR app
     * launcher, with a perfectly transparent background"): the launcher while it shows or fades out,
     * drawn by [LauncherGl]: no backdrop at all (0.4.2's opaque one is gone), icons, labels and the clock
     * floating in the room. The cursor's layer comes after this one, so it stays on top. [rays] hover it
     * in [syncLauncherFocus].
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawLauncherTiles(viewProj: Mat4, rays: List<com.hayeco.flipspaces.core.spatial.Ray>) {
        launcherGl.draw(xr, viewProj, frameClockMs, rowFix, if (store.view == com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D) "On" else "Off")
    }

    /**
     * 0.5.25: a window's look this frame: launched from the launcher, it fades in over 500 ms (alpha
     * cubic-bezier(0.4, 0, 0, 1)) and grows from 0.92 (cubic-bezier(0.4, 0.3, 0, 1)) about its centre
     * while the launcher shows, every window ghosts toward 15 %.
     */
    private fun windowLook(panel: SpacePanel, nowMs: Long): Pair<SpacePanel, Float> {
        var alpha = 1f
        var shown = panel
        windowOpenMs[panel.id]?.let { start ->
            val ms = nowMs - start
            if (ms >= com.hayeco.flipspaces.core.spatial.XrLauncher.WINDOW_OPEN_MS) {
                if (eyePass == 0) windowOpenMs.remove(panel.id)
            } else {
                val s = com.hayeco.flipspaces.core.spatial.XrLauncher.windowScale(ms)
                alpha = com.hayeco.flipspaces.core.spatial.XrLauncher.windowAlpha(ms)
                shown = panel.copy(widthDeg = panel.widthDeg * s, heightDeg = panel.heightDeg * s)
            }
        }
        val launcherAlpha = xr.alpha(nowMs)
        if (launcherAlpha > 0f) alpha *= 1f - (1f - com.hayeco.flipspaces.core.spatial.XrLauncher.GHOST_ALPHA) * launcherAlpha
        // 0.5.37: the others fading into the distance while one window is Immersive.
        alpha *= immersive.alpha(panel.id, nowMs)
        // 0.5.73 (the 0.5.72 snapshot: the windows' text showed through the Control Center card): while it is open every
        // window fades back to [CONTROL_DIM], easing in and out ([LookTween]), so the card reads and comes forward.
        val dim = controlDim.at(controls.isOpen, frameClockMs)
        if (dim > 0f) alpha *= 1f - (1f - CONTROL_DIM) * dim
        return shown to alpha
    }

    /**
     * 0.5.37: the Control Center card, painted again only when what it shows or the control under a pointer changes. It
     * opens with a fade and a rise from 96 % over [com.hayeco.flipspaces.core.spatial.ControlCenter.OPEN_MS], ease out.
     */
    private fun drawControls(viewProj: Mat4, rays: List<com.hayeco.flipspaces.core.spatial.Ray>) {
        val state = controlState()
        val l = com.hayeco.flipspaces.core.spatial.ControlCenterLayout.layout(state)
        val hotZone = rays.firstNotNullOfOrNull { controls.zoneAt(it, l) }?.takeIf { it.enabled }
        val w = com.hayeco.flipspaces.core.spatial.ControlCenterLayout.WIDTH_PX.toInt()
        val h = l.heightPx.toInt()
        val tex = controlTexture?.takeIf { it.width == w && it.height == h } ?: CanvasTexture(w, h).also {
            controlTexture?.release()
            controlTexture = it
        }
        // 0.5.71: painted with no control lit, so moving the pointer across the card paints and uploads nothing.
        tex.draw("$state/${com.hayeco.flipspaces.phone.PhoneNotifications.version}") { c -> ControlCenterPainter.paint(c, l, state, null) }
        val t = ((SystemClock.uptimeMillis() - controls.openedAtMs).toFloat() / com.hayeco.flipspaces.core.spatial.ControlCenter.OPEN_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        val scale = 0.96f + 0.04f * eased
        val p = controls.panel(l)
        val shown = p.copy(widthDeg = p.widthDeg * scale, heightDeg = p.heightDeg * scale)
        drawLabel(tex.id, viewProj * shown.model(), eased)
        // 0.5.71 ("Light on glass"; Mike: "more amazing depth effects"): the control under the pointer rises out of the card
        // toward the eyes, its own pixels drawn again from the card's texture on a lit glass plate, easing in over
        // [Look.HOVER_MS] and back down as the pointer leaves ([lift]: it keeps its place and size, only its depth changes).
        if (hotZone != null && hotZone.item != ccHotItem) {
            ccHotItem = hotZone.item
            ccHotZone = hotZone
            ccHotLook = LookTween()
        }
        val zone = ccHotZone ?: return
        val hotV = if (eyePass == 0) ccHotLook.at(hotZone?.item == ccHotItem, frameClockMs) else ccHotLook.value
        if (hotV < 0.01f) return
        val wPx = com.hayeco.flipspaces.core.spatial.ControlCenterLayout.WIDTH_PX
        val hPx = l.heightPx
        val pad = 4f
        val u0 = (zone.left - pad) / wPx
        val u1 = (zone.right + pad) / wPx
        val v0 = (zone.top - pad) / hPx
        val v1 = (zone.bottom + pad) / hPx
        val mPerPx = shown.widthM / wPx
        val zw = (zone.right - zone.left + 2f * pad)
        val zh = (zone.bottom - zone.top + 2f * pad)
        val radiusPx = when (zone.item) {
            com.hayeco.flipspaces.core.spatial.ControlItem.Close, com.hayeco.flipspaces.core.spatial.ControlItem.SpeedDown,
            com.hayeco.flipspaces.core.spatial.ControlItem.SpeedUp, com.hayeco.flipspaces.core.spatial.ControlItem.PageBack -> minOf(zw, zh) / 2f
            com.hayeco.flipspaces.core.spatial.ControlItem.Recentre, com.hayeco.flipspaces.core.spatial.ControlItem.Power,
            com.hayeco.flipspaces.core.spatial.ControlItem.NotesGrant, com.hayeco.flipspaces.core.spatial.ControlItem.BtGrant,
            com.hayeco.flipspaces.core.spatial.ControlItem.WifiAdd, com.hayeco.flipspaces.core.spatial.ControlItem.BtPair,
            com.hayeco.flipspaces.core.spatial.ControlItem.NotesClearAll -> zh / 2f
            else -> 30f
        }
        val r = lift(PanelCurve.rect(shown, u0, v0, u1, v1), CONTROL_LIFT * hotV)
        val round = Rounding(radiusPx * mPerPx, zw * mPerPx, zh * mPerPx)
        drawTexture(tex.id, viewProj * r.model, eased, subTexture(u0, v0, u1, v1), shape = r, round = round)
        colorOn(r, viewProj, look(Look.INK, 0.10f * hotV * eased), round.copy(rimArgb = Look.alpha(Look.RIM_FOCUS, hotV * eased), rimM = rimWidthM(shown)))
    }

    private val controlDim = LookTween(Look.ENTER_MS, Look.EXIT_MS)

    private var ccHotItem: com.hayeco.flipspaces.core.spatial.ControlItem? = null
    private var ccHotZone: com.hayeco.flipspaces.core.spatial.ControlZone? = null
    private var ccHotLook = LookTween()

    /** 0.5.71: the texture matrix that maps a quad onto the part [u0]..[u1] across, [v0]..[v1] down, of a bitmap's texture. */
    private fun subTexture(u0: Float, v0: Float, u1: Float, v1: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = u1 - u0
        m[5] = -(v1 - v0)
        m[10] = 1f
        m[12] = u0
        m[13] = v1
        m[15] = 1f
        return m
    }

    /**
     * 0.5.25 (Mike: the Android XR launcher): the launcher keeps its size (1450 x 950 dp at 1.0 m,
     * about 53 x 37 degrees) whatever the view; 0.4.2's shrink to fit is gone. Logged with the view's size
     * so a view smaller than the panel shows in the log.
     */
    private fun fitLauncher() {
        val (w, h) = com.hayeco.flipspaces.core.spatial.LauncherGrid.angularDeg()
        val key = "%.1fx%.1f".format(java.util.Locale.ROOT, frustum.horizontalFovDeg, frustum.verticalFovDeg)
        if (key == launcherFitLogged) return
        launcherFitLogged = key
        Log.i(tag, "launcher: %.1f by %.1f degrees at %.1f m in a view of %.1f by %.1f degrees%s".format(
            java.util.Locale.ROOT, w, h, com.hayeco.flipspaces.core.spatial.XrLauncher.DISTANCE_M, frustum.horizontalFovDeg, frustum.verticalFovDeg,
            if (w > frustum.horizontalFovDeg || h > frustum.verticalFovDeg) " (its edges are outside the view, as on a headset with a smaller view)" else "",
        ))
    }

    private var launcherFitLogged: String? = null

    /** The windows Steady mode last arranged, -1 before; a window opened or closed there arranges them again. */
    private var steadyCount = -1

    /**
     * Each settings beat: going into Ultrawide, the windows as they are saved and arranged on the canvas; coming out, put
     * back. The saved layout outlives the Space, which restarts with each change of display, and an unplug in Steady mode.
     */
    private fun steadyWindows() {
        val saved = store.steadyLayout
        if (ultrawide) {
            if (canvasPose == null) return
            if (saved != null && steadyCount < 0) steadyCount = layout.panels.size
            if (saved != null && steadyCount == layout.panels.size) return
            // Only when the windows are about to be arranged: Immersive is free to run once they are.
            if (immersive.active) {
                immersive.exit(frameClockMs - 10_000L)
                GlassesDisplayControl.immersiveDim(false)
                return
            }
            if (saved == null) store.steadyLayout = com.hayeco.flipspaces.core.spatial.LayoutCodec.encode(layout)
            val (yaw, pitch) = gaze()
            setLayout(com.hayeco.flipspaces.core.spatial.SteadyArrange.arrange(layout, yaw, pitch, frustum.horizontalFovDeg, frustum.verticalFovDeg), commit = true)
            steadyCount = layout.panels.size
            Log.i(PANEL_TAG, "Steady mode: $steadyCount windows arranged on the canvas")
        } else if (saved != null && (GlassesDisplayControl.ultrawideRead == 0 || (GlassesDisplayControl.ultrawideRead == null && !StereoLayout.isSbsMode(viewport.surfaceWidth, viewport.surfaceHeight)))) {
            setLayout(com.hayeco.flipspaces.core.spatial.SteadyArrange.restored(layout, com.hayeco.flipspaces.core.spatial.LayoutCodec.decode(saved)), commit = true)
            store.steadyLayout = null
            steadyCount = -1
            Log.i(PANEL_TAG, "Steady mode over: the windows are back as they were")
        }
    }

    private fun drawInfo(viewProj: Mat4, nowMs: Long) {
        val tex = info ?: return
        // 0.5.30 (Mike: remove the grey debug block at the bottom of the view): off unless Developer on the Menu turns it on.
        if (nowMs - infoCheckedMs > 500) {
            infoCheckedMs = nowMs
            infoShown = store.debugInfo
        }
        if (!infoShown) return
        if (nowMs - (infoUpdatedMs) > 500) {
            infoUpdatedMs = nowMs
            tex.set(infoLines(), Look.GLASS, Look.INK_2, 26f)
        }
        drawLabel(tex.id, viewProj * infoPanel.model(), 0.95f)
    }

    private var infoUpdatedMs = 0L
    private var infoCheckedMs = Long.MIN_VALUE / 2
    private var infoShown = false

    private fun infoLines(): List<String> = buildList {
        add("Pocket XR: ${mode?.mode?.label ?: "starting"}")
        add("Head tracking: ${HeadTracker.status.value.summary}")
        add("Panels: ${layout.panels.size} of 6. ${shizuku.label}.")
        add("Trackpad: swipe to move the cursor, tap to click, two finger tap: right click, two finger swipe: scroll, pinch: zoom. Tap a title bar to pick a window up: swipe moves it, two fingers the same way push it away (up) or pull it closer, pinch resizes, tap or Home drops it. Title bar Reset and Close. Three fingers up: recent apps, down: Control panel. Home: the launcher; hold Home: recentre the cursor.")
        add("A panel that stays black (FLAG_SECURE or DRM, such as Netflix) offers to open its app as a DeX window.")
        add("In view: $inView of ${layout.panels.size}. Thermal: ${ThermalGuard.label(thermalStatus)}, panels up to ${ThermalGuard.panelFps(thermalStatus, false)} fps (focused ${ThermalGuard.panelFps(thermalStatus, true)}).")
        perf.last?.let { add("Perf: ${it.line()}") }
        problem?.let { add("Last problem: $it") }
    }

    /**
     * The head gaze cursor while no hand aims (the trackpad's), else one cursor per hand
     * (0.5.0). A hand's cursor is Meta's: a ring of constant angular size 5 mm in front of what
     * it is over, filling with the pinch value and shrinking to 83% while pressed (over 80 ms,
     * back over 100 ms); the left hand's is a little dimmer.
     */
    private fun drawCursor(head: Quat, viewProj: Mat4) {
        val hands = pointer.activeHands
        if (hands.isEmpty()) {
            // 0.5.17: the touch ray reticle hides while the trackpad holds a window.
            if (pointer.heldPanelId != null) {
                if (eyePass == 0) pointer.hoverSuspended = false
                return
            }
            // 0.5.32 (Mike: the dot always showing even when not used): auto hide, always or off (the Menu's Head cursor).
            if (eyePass == 0) headCursorAlpha = headCursorAlpha()
            // 0.5.78 (Mike: the dot "gets lost in oblivion"): a ripple when the auto hidden dot comes back, and when a swipe
            // brings an out of view dot back ([com.hayeco.flipspaces.core.spatial.SpatialPointer.cursorReturns]), so the eye
            // catches where it is.
            if (eyePass == 0) {
                if (pointer.cursorReturns != seenReturns) {
                    seenReturns = pointer.cursorReturns
                    rippleStartMs = frameClockMs
                }
                if (lastDotAlpha < 0.05f && headCursorAlpha >= 0.05f) rippleStartMs = frameClockMs
                lastDotAlpha = headCursorAlpha
                // 0.5.78: a hidden dot hovers nothing ([com.hayeco.flipspaces.core.spatial.SpatialPointer.hoverSuspended]).
                pointer.hoverSuspended = headCursor.mode == com.hayeco.flipspaces.core.spatial.HeadCursorMode.AUTO &&
                    headCursorAlpha < 0.02f && !pointer.busy && !pointer.pressing
            }
            if (headCursorAlpha < 0.01f) return
            val ray = pointer.ray(head)
            val hover = pointer.hover
            // 0.5.70 (Mike: the cursor is one of the things he dislikes; "Light on glass"): a crisp white dot with a dark
            // hairline (it reads on a bright page as on the room), a soft light bloom round it, a thin ring growing in over
            // anything it can press, the accent while pressing or busy, and a ripple ring on each press.
            val active = pointer.busy || pointer.pressing
            val colour = if (active) Look.ACCENT else Look.INK
            // 0.5.17: the reticle: 2.5 m out over nothing, a hover ring tweening in over
            // 0.15 s from 0.6 of its size, 0.7 while pressed.
            val look = if (eyePass == 0) reticle.at(hover != null, pointer.pressing, frameClockMs).also { reticleLook = it } else reticleLook
            // 0.5.20: in front of whatever it covers, the launcher and menus included ([CursorDepth]).
            val at = cursorPoint(ray, hover?.distance, com.hayeco.flipspaces.core.spatial.ReticleAnim.DEFAULT_DISTANCE_M) * (1f - liftUnder(hover))
            // 0.5.26: on a close window, at the same comfortable convergence as the window.
            val vp = comfortViewProj(viewProj, at.length)
            val a = headCursorAlpha
            drawDisc(vp, at, CURSOR_GLOW * look.dotScale, Look.rgba(Look.INK, CURSOR_GLOW_ALPHA * a), GlKit.CIRCLE_GLOW)
            // 0.5.75: on a window's grab band the ring is the accent and a third bigger: it says "move" before the press.
            val onFrame = hover?.frame == true
            if (look.ringAlpha > 0.01f) drawDisc(vp, at, CURSOR_RING * look.ringScale * (if (onFrame) 1.35f else 1f), Look.rgba(if (onFrame) Look.ACCENT else colour, 0.9f * a * look.ringAlpha), GlKit.CIRCLE_RING, CURSOR_RING_INNER)
            drawRipple(vp, at, pointer.pressing, a)
            drawDisc(vp, at, CURSOR_DOT * look.dotScale, Look.rgba(colour, a), GlKit.CIRCLE_DOT, CURSOR_DOT_CORE)
            return
        }
        if (eyePass == 0) pointer.hoverSuspended = false
        // 0.5.26: the frame's own present time, so the dot's tweens step evenly ([frameClockMs]).
        val nowMs = frameClockMs
        val statuses = SpaceSession.hands
        // 0.5.11: one dot, the active hand's ([CursorChoice]); chosen once a frame, in the first eye's pass.
        if (eyePass == 0) {
            val speeds = hands.associateWith { side -> handSpeed(side, pointer.ray(head, side), nowMs) }
            cursorChoice.pick(hands, { pointer.busy(it) || statuses[it]?.pinched == true }, { speeds[it] ?: 0f })?.let { Log.i(HANDS_TAG, it) }
        }
        for (side in hands) {
            if (side != cursorChoice.active) continue
            val ray = pointer.ray(head, side) ?: continue
            val status = statuses[side]
            val pressed = status?.pinched == true || pointer.busy(side)
            val shrink = cursorShrink.getOrPut(side) { CursorShrink() }.at(pressed, nowMs)
            val hover = pointer.hover(side)
            val dim = if (side == HandSide.LEFT) 0.85f else 1f
            val progress = if (pressed) 1f else (status?.pinchProgress ?: 0f)
            // 0.5.46: the palm turned to the glasses: a pinch opens the launcher, so the dot turns green and grows.
            // 0.5.50: while that pinch is held, green fills the dot toward the recentre.
            val hold = status?.palmHold ?: 0f
            val menu = (status?.palmMenu == true && !pressed) || hold > 0f
            // 0.5.70 ("Light on glass"): the hand's cursor in the same language as the head's: a light ring with a bloom,
            // white over anything it can press, the accent while pinched, green for the palm menu; the pinch's progress
            // fills it from the middle as a dot, and each pinch sends a ripple out.
            val ringColour = when {
                menu -> Look.PALM
                pressed -> Look.ACCENT
                hover != null -> Look.INK
                else -> Look.INK_2
            }
            val at = cursorPoint(ray, hover?.distance, com.hayeco.flipspaces.core.spatial.CursorDepth.HAND_DEFAULT_M) * (1f - liftUnder(hover))
            val vp = comfortViewProj(viewProj, at.length)
            val size = if (menu) shrink * 1.6f else shrink
            drawDisc(vp, at, CURSOR_GLOW * size, look(Look.INK, CURSOR_GLOW_ALPHA * dim), GlKit.CIRCLE_GLOW)
            drawDisc(vp, at, size, look(ringColour, dim), GlKit.CIRCLE_RING, HAND_RING_INNER)
            drawRipple(vp, at, pressed, dim)
            if (hold > 0f) {
                // The green fill growing from the middle to the whole of the ring as the hold comes to the recentre.
                drawDisc(vp, at, size * HAND_RING_INNER * hold, look(Look.PALM, dim), GlKit.CIRCLE_DOT, CURSOR_DOT_CORE)
                continue
            }
            if (progress > 0.01f) {
                drawDisc(vp, at, size * HAND_RING_INNER * (0.35f + 0.65f * progress), look(Look.ACCENT, (0.3f + 0.7f * progress) * dim), GlKit.CIRCLE_DOT, CURSOR_DOT_CORE)
            }
        }
    }

    /**
     * 0.5.20: the cursor's point for [ray] ([com.hayeco.flipspaces.core.spatial.CursorDepth]): on the
     * hovered window, else on the launcher, menu or home tile that claims the ray, else [defaultM] out;
     * then never behind a window or an open launcher or menu on the eyes' line of sight.
     */
    private fun cursorPoint(ray: com.hayeco.flipspaces.core.spatial.Ray, hoverM: Float?, defaultM: Float): Vec3 {
        val menuOpen = controls.isOpen || launcher.isOpen
        // 0.5.39 (Mike, vc59: with the Control Center at 1.8 m "the cursor dot is hovering far in front of the panel"): over
        // the Control Center the dot sits on its surface where the ray meets it, not at the launcher's 1 m.
        if (controls.isOpen && !launcher.isOpen) {
            val c = controls.panel(com.hayeco.flipspaces.core.spatial.ControlCenterLayout.layout(controlState()))
            val t = com.hayeco.flipspaces.core.spatial.PanelCurve.intersect(ray, c)?.t
            // 0.5.71: brought as near as a lifted control, so in 3D it sits on the control that rises under it.
            if (t != null) return com.hayeco.flipspaces.core.spatial.CursorDepth.point(ray, t, null, ray.at(t).length, layout.panels, defaultM) * (1f - CONTROL_LIFT - 0.004f)
        }
        // 0.5.72: over the open launcher, as near as a hovered icon rises ([LauncherGl]), so in 3D it is never behind one.
        // 0.5.83 (Mike: the dot "wont directly go on" the search input): on what it points at: as near as a risen icon,
        // button or search card while one is under it, else just on the launcher's plane, not floating 3 % in front of it.
        val launcherLift = if (!launcher.isOpen) 0f else if (xr.hoverLifts) Look.DEPTH_CONTROLS + 0.004f else 0.004f
        val launcherM = com.hayeco.flipspaces.core.spatial.SpaceLauncher.DISTANCE_M * (1f - launcherLift)
        val claim = when {
            hoverM != null -> null
            menuOpen -> launcherM
            launcher.homeHittable && launcher.homeTile().hit(ray) != null -> launcherM + com.hayeco.flipspaces.core.spatial.SpaceLauncher.BACKDROP_BEHIND_M
            else -> null
        }
        // 0.5.45: just off a window (its bar, its buttons, the gaps between) the dot keeps to that window's depth.
        val outM = if (hoverM == null && claim == null) com.hayeco.flipspaces.core.spatial.CursorDepth.nearM(ray, layout.panels) ?: defaultM else defaultM
        return com.hayeco.flipspaces.core.spatial.CursorDepth.point(ray, hoverM, claim, if (menuOpen) launcherM else null, layout.panels, outM)
    }

    /**
     * 0.5.69: how far the thing under the cursor is lifted toward the eyes ([lift]): a window's control (and it is hovered,
     * so risen too) or a card's dialog. The dot is brought the same share nearer, and a little more, so in 3D it sits on
     * what it points at instead of behind it.
     */
    private fun liftUnder(hover: com.hayeco.flipspaces.core.spatial.PanelHit?): Float {
        val h = hover ?: return 0f
        val lifted = when {
            h.zone in CONTROL_ZONES -> Look.DEPTH_CONTROLS + Look.DEPTH_HOVER
            h.zone == PanelZone.BODY && panels[h.panel.id]?.let { cardOf(it) } != null -> Look.DEPTH_DIALOG + Look.DEPTH_HOVER
            else -> return 0f
        }
        return lifted + 0.004f
    }

    private val cursorChoice = com.hayeco.flipspaces.core.spatial.CursorChoice()
    private var reticleLook = com.hayeco.flipspaces.core.spatial.ReticleAnim.Look(1f, 0.6f, 0f)

    /** 0.5.32: the head cursor's auto hide; the mode read from the Menu's setting every 0.5 s, input seen by the pointer's count. */
    private val headCursor = com.hayeco.flipspaces.core.spatial.HeadCursorVisibility()
    private var headCursorAlpha = 0f
    private var headCursorInputs = -1L
    private var headCursorCheckedMs = Long.MIN_VALUE
    private var laserLostSaidMs = Long.MIN_VALUE / 2
    private var settingsCheckedMs = Long.MIN_VALUE / 2

    private fun headCursorAlpha(): Float {
        val nowMs = frameClockMs
        if (nowMs - headCursorCheckedMs > 500) {
            headCursorCheckedMs = nowMs
            val mode = com.hayeco.flipspaces.core.spatial.HeadCursorMode.of(store.headCursor)
            if (mode != headCursor.mode) {
                headCursor.mode = mode
                Log.i(tag, "head cursor: ${mode.label} (the Menu's Head cursor)")
            }
        }
        // 0.5.32: the Spatial Mouse's dot pinned at the view's edge for 2 s: a hint on the
        // glasses saying how to find it, once in 10 s at most.
        if (pointer.laserLost && nowMs - laserLostSaidMs > 10_000L) {
            laserLostSaidMs = nowMs
            spaceNotice = "Can't find the dot? Hold Home on the phone"
            spaceNoticeUntilMs = SystemClock.uptimeMillis() + 4_000L
            Log.i(tag, "Spatial Mouse: the phone points outside the view (the dot pinned at its edge for 2 s): hold Home shown on the glasses")
        }
        val inputs = pointer.inputs
        if (inputs != headCursorInputs) {
            if (headCursorInputs >= 0) headCursor.input(nowMs)
            headCursorInputs = inputs
        }
        return headCursor.alpha(nowMs, active = pointer.busy || pointer.pressing)
    }
    private val lastHandDir = java.util.EnumMap<HandSide, Pair<Vec3, Long>>(HandSide::class.java)

    /** 0.5.11: how fast [side]'s ray turns, deg/s, from one frame to the next. */
    private fun handSpeed(side: HandSide, ray: com.hayeco.flipspaces.core.spatial.Ray?, nowMs: Long): Float {
        val dir = ray?.dir ?: return 0f
        val last = lastHandDir[side]
        lastHandDir[side] = dir to nowMs
        if (last == null || nowMs <= last.second) return 0f
        val cos = (last.first.normalized() dot dir.normalized()).coerceIn(-1f, 1f)
        return kotlin.math.acos(cos) * 57.29578f * 1000f / (nowMs - last.second)
    }

    /** A disc [scale] times the cursor's size at [at] ([cursorPoint]), facing the eyes. */
    private fun drawDisc(viewProj: Mat4, at: Vec3, scale: Float, rgba: FloatArray) = drawDisc(viewProj, at, scale, rgba, 1f)

    /** 0.5.70: one of the cursor's round marks, [mode] a [GlKit] circle mode, [inner] the dot's core or the ring's inner share. */
    private fun drawDisc(viewProj: Mat4, at: Vec3, scale: Float, rgba: FloatArray, mode: Float, inner: Float = 0f) {
        val dir = if (at.length < 1e-6f) Vec3(0f, 0f, -1f) else at.normalized()
        val right = (dir cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
        val up = right cross dir
        // A constant angular size, about 0.8 degrees, as seen from the eyes.
        val size = (at - Vec3.ZERO).length * 0.014f * scale
        val prog = GlKit.color
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        GlKit.applyHalo(prog, inner * 2f, 0f, 0f)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, (viewProj * Mat4.basis(at, right, up, -dir, size, size)).m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, GlKit.IDENTITY, 0)
        GLES30.glUniform4f(prog.color, rgba[0], rgba[1], rgba[2], rgba[3])
        GLES30.glUniform1f(prog.circle, mode)
        GlKit.drawQuad()
    }

    /**
     * 0.5.70: a press sends a thin ring out from the cursor, growing from [CURSOR_RING] to [RIPPLE_TO] of the dot's size and
     * fading over [RIPPLE_MS] (ease out). A new press starts a new one; [pressed] is read in the first eye's pass.
     */
    private fun drawRipple(vp: Mat4, at: Vec3, pressed: Boolean, alpha: Float) {
        if (eyePass == 0) {
            if (pressed && !rippleWasPressed) rippleStartMs = frameClockMs
            rippleWasPressed = pressed
        }
        val t = (frameClockMs - rippleStartMs).toFloat() / RIPPLE_MS
        if (t < 0f || t >= 1f) return
        val e = Look.EASE_ENTER.at(t)
        drawDisc(vp, at, CURSOR_RING + (RIPPLE_TO - CURSOR_RING) * e, look(Look.ACCENT, 0.7f * (1f - t) * alpha), GlKit.CIRCLE_RING, RIPPLE_INNER)
    }

    private var rippleStartMs = Long.MIN_VALUE / 2
    private var rippleWasPressed = false
    private var seenReturns = 0
    private var lastDotAlpha = 0f


    /** The press shrink of one hand's cursor: 1 to 0.83 over 80 ms on press, back over 100 ms. */
    private class CursorShrink {
        private var value = 1f
        private var lastMs = 0L

        fun at(pressed: Boolean, nowMs: Long): Float {
            val dt = (nowMs - lastMs).coerceIn(0L, 100L)
            lastMs = nowMs
            val target = if (pressed) 0.83f else 1f
            val step = 0.17f * dt / (if (pressed) 80f else 100f)
            value = if (value > target) maxOf(target, value - step) else minOf(target, value + step)
            return value
        }
    }

    private val cursorShrink = java.util.EnumMap<HandSide, CursorShrink>(HandSide::class.java)

    // ---- hand calibration (0.5.0) ----

    /** The "pinch on the dots" calibration while it runs; null otherwise. GL thread. */
    private var calibration: HandCalibration? = null
    private var calibrationLabel: LabelTexture? = null
    private var calibrationNote: String? = null

    private fun toggleCalibration() {
        if (calibration != null) {
            calibration = null
            calibrationOffer = false
            HandFeed.endCalibration(null)
            Log.i(HANDS_TAG, "Hand calibration cancelled; the saved one stays")
            return
        }
        calibration = HandCalibration()
        calibrationNote = null
        HandFeed.beginCalibration()
        Log.i(HANDS_TAG, "Hand calibration started: ${HandCalibration.DEFAULT_TARGETS.size} dots")
    }

    /** The first hands in the Space were looked at for the calibration offer (0.5.1). */
    private var calibrationOfferChecked = false

    /** The running calibration is the one-time offer: an open palm skips it. */
    private var calibrationOffer = false

    /**
     * 0.5.1: Mike never found the calibration in 0.5.0 (none was ever saved). The first time the
     * Space sees a hand with no calibration saved, it starts the dots by itself, once ever.
     */
    private fun offerCalibration() {
        if (calibrationOfferChecked || calibration != null) return
        if (SpaceSession.hands.values.none { it.present }) return
        calibrationOfferChecked = true
        // Debug replays aim through the air box and have no use for it.
        if (HandFeed.forceImageMode) return
        val prefs = HandsPrefs(context)
        if (prefs.calibrationOffered || !prefs.calibration.isIdentity) return
        prefs.calibrationOffered = true
        toggleCalibration()
        calibrationOffer = true
        Log.i(HANDS_TAG, "First Space with hands and no calibration saved: offering the ${HandCalibration.DEFAULT_TARGETS.size} dot calibration once (an open palm skips it)")
    }

    /**
     * While calibrating, a hand's press is a sample on the current dot and never reaches a
     * panel; its release is swallowed too. True when [s] was taken.
     */
    private fun calibrationTakes(s: PointerSignal): Boolean {
        val c = calibration ?: return false
        val hand = s as? PointerSignal.ForHand ?: return false
        return when (val inner = hand.signal) {
            PointerSignal.ShowDock -> {
                // An open palm skips the offered calibration; Calibrate on the Hands screen brings it back.
                if (calibrationOffer) {
                    toggleCalibration()
                    problem = "Hand calibration skipped. Calibrate any time from Hands on the phone."
                }
                true
            }
            is PointerSignal.Down -> {
                val dir = hand.dir ?: inner.at?.let { at ->
                    val (x, y) = viewport.toSurface(at.x, at.y)
                    frustum.rayThrough(x, y)
                } ?: return true
                val origin = hand.origin ?: Vec3.ZERO
                c.record(origin, dir)
                Log.i(HANDS_TAG, "Hand calibration dot ${c.index} of ${c.targets.size}: ${hand.hand.label} hand ray %.1f, %.1f deg".format(Angles.of(dir).yawDeg, Angles.of(dir).pitchDeg))
                if (c.done) finishCalibration(c)
                true
            }
            is PointerSignal.Up, PointerSignal.Cancel, is PointerSignal.Scale -> true
            else -> false
        }
    }

    private fun finishCalibration(c: HandCalibration) {
        val r = c.result()
        Log.i(HANDS_TAG, "Hand ${r.line()}")
        if (!r.ok) {
            calibrationNote = "That was %.1f degrees off. Again, slowly: point, hold still, pinch.".format(r.rmsDeg)
            c.restart()
            return
        }
        val prefs = HandsPrefs(context)
        prefs.calibration = r.calibration
        prefs.calibrationRmsDeg = r.rmsDeg
        HandFeed.endCalibration(r.calibration)
        calibration = null
        calibrationOffer = false
        problem = "Hands calibrated: %.1f degrees".format(r.rmsDeg)
    }

    /** The current dot, fixed in front of the head, and what to do, while calibrating. */
    private fun drawCalibration(viewProj: Mat4, head: Quat) {
        val c = calibration ?: return
        val local = c.dotPosition() ?: return
        val at = head.rotate(local)
        val dir = at.normalized()
        val right = (dir cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
        val up = right cross dir
        val size = at.length * 0.03f
        drawColor(viewProj * Mat4.basis(at, right, up, -dir, size, size), floatArrayOf(1f, 1f, 1f, 0.95f), circle = true)
        drawColor(viewProj * Mat4.basis(at - dir * 0.001f, right, up, -dir, size * 0.45f, size * 0.45f), floatArrayOf(0.95f, 0.3f, 0.25f, 1f), circle = true)
        val tex = calibrationLabel ?: LabelTexture(1024, 128).also { calibrationLabel = it }
        val lines = listOfNotNull(
            if (calibrationOffer && c.index == 0) "Calibrate your hands once: point at the dot and pinch (1 of ${c.targets.size})" else "Point at the dot and pinch (${c.index + 1} of ${c.targets.size})",
            calibrationNote ?: if (calibrationOffer) "Open palm to skip" else null,
        )
        tex.set(lines, Look.GLASS, Look.INK, 34f, centred = true)
        val below = at - up * (size * 1.6f)
        drawLabel(tex.id, viewProj * Mat4.basis(below, right, up, -dir, at.length * 0.2f, at.length * 0.025f), 0.95f)
    }

    /** 0.5.8: the hint while a hand is near the bottom of the camera picture. */
    private val edgeHint = com.hayeco.flipspaces.core.hands.CameraEdgeHint()
    private var edgeHintLabel: LabelTexture? = null
    private var edgeHintShown = false

    /** 0.5.8: [CameraEdgeHint]'s line for this frame, logged when it shows or hides. */
    private fun edgeHintText(nowMs: Long): String? {
        val text = edgeHint.update(SpaceSession.hands.values, nowMs)
        if ((text != null) != edgeHintShown) {
            edgeHintShown = text != null
            Log.i(HANDS_TAG, if (text != null) "Hint shown: \"$text\" (a hand near the bottom of the camera picture)" else "Hint hidden: no hand near the bottom of the camera picture")
        }
        return text
    }

    /** 0.5.8: the hint, head locked a little below the centre of the view. */
    private fun drawEdgeHint(viewProj: Mat4, head: Quat, text: String?) {
        // 0.5.69 ("Light on glass": nothing pops): the line fades in and rises 2 % as it comes, and fades out with its
        // last text when it goes ([Look.ENTER_MS], [Look.EXIT_MS]).
        if (text != null) hintShown = text
        val fade = if (eyePass == 0) hintLook.at(text != null, frameClockMs) else hintLook.value
        val shown = hintShown ?: return
        if (fade < 0.01f) {
            if (text == null) hintShown = null
            return
        }
        val tex = edgeHintLabel ?: return
        val at = head.rotate(Vec3(0f, -0.3f - 0.02f * (1f - fade), -1f).normalized() * 1.5f)
        val dir = at.normalized()
        val right = (dir cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
        val up = right cross dir
        tex.set(listOf(shown), Look.GLASS, Look.INK, 40f, centred = true)
        drawLabel(tex.id, viewProj * Mat4.basis(at, right, up, -dir, 0.42f, 0.04f), 0.95f * fade)
    }

    private var hintShown: String? = null
    private val hintLook = LookTween(Look.ENTER_MS, Look.EXIT_MS)

    private fun drawLabel(texture: Int, mvp: Mat4, alpha: Float) = drawTexture(texture, mvp, alpha, GlKit.FLIP_Y)

    /** 0.5.13: [footprint] draws through the four-tap footprint filter (panel pictures at about 1.4 texels a pixel). */
    private fun drawTexture(texture: Int, mvp: Mat4, alpha: Float, texMatrix: FloatArray, footprint: Boolean = false, shape: PanelCurve.Rect? = null, round: Rounding? = null) {
        val prog = if (footprint) GlKit.footprint else GlKit.flat
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        round?.let {
            GlKit.applyRound(prog, it.radiusM, it.widthM, it.heightM)
            if (it.rimArgb ushr 24 != 0) GlKit.applyRim(prog, it.rimArgb, it.rimM)
        }
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, texMatrix, 0)
        // 0.5.68: a premultiplied tint ([GlKit.BLEND_SRC]).
        GLES30.glUniform4f(prog.tint, alpha, alpha, alpha, alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(prog.sampler, 0)
        GlKit.drawShape(prog, shape)
    }

    private fun drawColor(mvp: Mat4, rgba: FloatArray, circle: Boolean, shape: PanelCurve.Rect? = null, round: Rounding? = null) {
        val prog = GlKit.color
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        round?.let {
            GlKit.applyRound(prog, it.radiusM, it.widthM, it.heightM)
            if (it.rimArgb ushr 24 != 0) GlKit.applyRim(prog, it.rimArgb, it.rimM)
        }
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, Mat4.identity().m, 0)
        GLES30.glUniform4f(prog.color, rgba[0], rgba[1], rgba[2], rgba[3])
        GLES30.glUniform1f(prog.circle, if (circle) 1f else 0f)
        GlKit.drawShape(prog, shape)
    }

    /** 0.5.19: a window's rectangle ([PanelCurve.rect]), on its curve when it is curved: a label or picture. */
    private fun drawOn(r: PanelCurve.Rect, viewProj: Mat4, texture: Int, alpha: Float, texMatrix: FloatArray = GlKit.FLIP_Y, footprint: Boolean = false, round: Rounding? = null) =
        drawTexture(texture, viewProj * r.model, alpha, texMatrix, footprint, r, round)

    /** 0.5.19: a window's rectangle in one colour, on its curve. */
    private fun colorOn(r: PanelCurve.Rect, viewProj: Mat4, rgba: FloatArray, round: Rounding? = null) = drawColor(viewProj * r.model, rgba, circle = false, shape = r, round = round)

    /** 0.5.20: a rectangle's corner radius and its size along its surface, metres ([GlKit.applyRound]). */
    private data class Rounding(val radiusM: Float, val widthM: Float, val heightM: Float, val rimArgb: Int = 0, val rimM: Float = 0f)

    /** 0.5.20: the window body's rounded corners ([WindowControls.cornerRadiusM]). */
    private fun bodyRounding(panel: SpacePanel) = Rounding(WindowControls.cornerRadiusM(panel), PanelCurve.arcWidthM(panel), panel.heightM)

    /**
     * 0.5.69 ("Light on glass"): the body's corners and its hairline light rim, brighter while the window has focus (or,
     * half as much, while a pointer is on it), faded with the window ([alpha]).
     */
    private fun bodyRounding(panel: SpacePanel, p: PanelGl, alpha: Float): Rounding {
        val lit = maxOf(p.focusLook.value, 0.5f * p.hoverLook.value)
        val rim = Look.mix(Look.mix(Look.RIM, Look.RIM_FOCUS, lit), Look.ACCENT, 0.6f * p.frameLook.value)
        return Rounding(
            WindowControls.cornerRadiusM(panel), PanelCurve.arcWidthM(panel), panel.heightM,
            Look.alpha(rim, alpha), rimWidthM(panel) * (1f + 0.5f * p.frameLook.value),
        )
    }

    // ---- status ----

    private fun refreshMode(nowMs: Long) {
        if (nowMs - modeCheckedMs < 1000) return
        modeCheckedMs = nowMs
        shizuku = ShizukuBridge.status(context)
        if (shizuku == ShizukuStatus.READY && PanelHost.state.value !is PanelHost.State.Ready && PanelHost.state.value != PanelHost.State.Binding) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { PanelHost.connect(context) }
        }
        val head = HeadTracker.status.value
        // Inside the Space, panels without Shizuku stay as placeholders that explain why, so the
        // mode shown is about head tracking; the reasons still name what is missing.
        val choice = SpatialModeChooser.choose(head, shizuku)
        val next = ModeChoice(if (head.tracking) SpatialMode.SPATIAL else SpatialMode.HEAD_LOCKED, choice.reasons)
        if (modeGate.offer(next, nowMs)) {
            ModeLog.decided("Space", next, if (shizuku == ShizukuStatus.READY) "panels" else "placeholders until Shizuku is ready")
        }
        mode = next
    }

    private fun publish(nowMs: Long) {
        if (nowMs - publishedMs < 500) return
        publishedMs = nowMs
        SpaceSession.publish(
            SpaceSnapshot(
                running = true,
                launcherOpen = launcher.isOpen,
                focusedPanelId = focusedPanelId,
                mode = mode,
                panels = layout.panels.map { pl -> PanelInfo(pl.id, pl.label, panels[pl.id]?.status ?: "", panels[pl.id]?.displayId) },
                perf = perf.last?.line(),
                problem = problem,
            ),
        )
    }
}

private const val PANEL_TAG = "FlipSpaces/Panel"
private const val WATCHDOG_TAG = "FlipSpaces/Watchdog"

/** Hand calibration lines go with the other hand lines. */
private const val HANDS_TAG = "FlipSpacesHands"

/** Every scroll injected into a panel and its result (0.4.2). */
private const val INPUT_TAG = "FlipSpaces/Input"

/** How often each showing panel's display is checked for its app having closed. */
private const val TASKS_CHECK_MS = 1500L


/** Launcher tile texture pixels per degree. */
private const val TILE_PX_PER_DEG = 32f

/** 0.5.14: how long the "turn the glasses' 3D mode on / off" hint shows after the view changes. */
private const val VIEW_HINT_MS = 8_000L

/** 0.5.19: how long Choreographer's rate may be off the glasses' before the late draw stops. */
private const val RATE_HOLD_NS = 1_000_000_000L

/** 0.5.21: how often SurfaceFlinger's present fences are read ([PresentFence]). */
private const val FENCE_EVERY_MS = 2_000L

/** 0.5.57: the present fences' own line at most this often (the tables are read every [FENCE_EVERY_MS]). */
private const val FENCE_LOG_MS = 4_500L

/** 0.5.21: how often each app window's requested orientation is read, and how soon after a Wide view press. */
private const val SHAPE_EVERY_MS = 5_000L
private const val SHAPE_AFTER_TOGGLE_MS = 2_000L

/** 0.5.22: how long the Glasses warp switch's confirmation stays on the glasses. */
private const val WARP_NOTICE_MS = 3_000L

/** 0.5.36: the Apps button revealed by a look down or a pinch on nothing; off (Mike: it can be removed). */
private const val APPS_BUTTON = false

/** 0.5.26: why the Glasses warp turned itself off stays on the glasses this long. */
private const val WARP_OFF_NOTICE_MS = 8_000L

/** 0.5.99: the recording's red dot, as shares of the picture's height: its size and its distance from the corner. */
private const val RECORD_DOT_SHARE = 0.018f
private const val RECORD_DOT_MARGIN_SHARE = 0.11f

/** 0.5.95: how long a glasses model's setup hint stays up. */
private const val SETUP_HINT_MS = 10_000L

/** 0.5.29: each line of a queued notice (the glasses' 3D distance note). */
private const val NOTE_LINE_MS = 4_000L

/** 0.5.28: how much earlier the late warp starts while the phone is thermally light or worse ([com.hayeco.flipspaces.core.spatial.DrawBudget.pressureMs]). */
private const val WARP_PRESSURE_MS = 1f

/** 0.5.55: the late warp's clear colours, made once (see-through black, else the Space's dark grey). */
private val WARP_CLEAR_SEE_THROUGH = floatArrayOf(0f, 0f, 0f)
private val WARP_CLEAR_DARK = floatArrayOf(0.05f, 0.06f, 0.08f)

/** 0.5.28: a pointer's note on the glasses ([com.hayeco.flipspaces.core.spatial.SpaceAction.Notice], the wrapped Space's Curve). */
private const val POINTER_NOTICE_MS = 5_000L

/** 0.5.26: the late warp's pass in the gpu passes line. */
private const val WARP_PASS = 4

/** 0.5.27: the late warp's one reason that may flip frame to frame, held by [com.hayeco.flipspaces.core.spatial.LateWarpGate]. */
private const val LATE_DRAW_OFF = "the late draw is off"

/** 0.5.25: how long the launcher's "Too many apps are pinned" (its toast) stays. */
private const val PIN_NOTICE_MS = 2_000L

/** 0.5.22: the first read of what an app asks for, after its launch or a new display size. */
private const val SHAPE_AFTER_LAUNCH_MS = 1_500L

/** 0.5.21: the least time between two reads of what the apps ask for, over every window (each read is a dumpsys). */
private const val SHAPE_GAP_MS = 2_000L

/** How often a showing panel is checked for staying black (protected content). */
private const val PROTECTED_CHECK_MS = 1000L

/** 0.5.69: the hairline rim, about 1.5 of the glasses' pixels (0.026 deg each) per metre away. */
private const val RIM_PER_M = 0.0007f

/** 0.5.69: the focused window's glow, metres wide per metre away (about 0.7 deg), and its strength. */
private const val HALO_PER_M = 0.012f
private const val HALO_ALPHA = 0.32f

/** 0.5.75: the grab band's frame line, metres wide per metre away (about 3 of the glasses' pixels), and its spot's size. */
private const val FRAME_LINE_PER_M = 0.001f
private const val FRAME_SPOT = 9f

/** 0.5.78: the grab frame's line and spot strengths (0.5.76: 0.9 and 0.55 in the full accent, "very solid"). */
private const val FRAME_LINE_ALPHA = 0.42f
private const val FRAME_SPOT_ALPHA = 0.28f

/** 0.5.75: the glow's added strength while the pointer is on a window's grab band. */
private const val FRAME_HALO_ALPHA = 0.22f

/**
 * 0.5.70: the cursor's marks, in its 0.8 degree size: the dot ([CURSOR_DOT], its white core [CURSOR_DOT_CORE] of it inside
 * the dark hairline), the hover ring and its inner edge, the bloom and its strength, the hand's ring's inner edge, and the
 * press ripple's reach and time.
 */
/** 0.5.71: how far the Control Center's hovered control rises toward the eyes, a share of its distance (4.5 cm at its 1.8 m). */
private const val CONTROL_LIFT = 0.025f

/** 0.5.73: how bright the windows stay while the Control Center is open. */
private const val CONTROL_DIM = 0.3f
private const val CURSOR_DOT = 0.62f
private const val CURSOR_DOT_CORE = 0.74f
private const val CURSOR_RING = 1.45f
private const val CURSOR_RING_INNER = 0.84f
private const val CURSOR_GLOW = 2.6f
private const val CURSOR_GLOW_ALPHA = 0.16f
private const val HAND_RING_INNER = 0.74f
private const val RIPPLE_TO = 3.2f
private const val RIPPLE_INNER = 0.9f
private const val RIPPLE_MS = 380f


/** 0.5.69: the hit zones of a window's floating controls ([SpaceRenderer.liftUnder]). */
private val CONTROL_ZONES = WindowControls.Control.entries.map { it.zone }.toSet()

/** The protected app banner's height, and its keep-as-panel cross's width, as panel fractions. */
private const val OFFER_HEIGHT = 0.22f
private const val OFFER_CROSS = 0.12f

/** The App closed card's texture height, a whole panel's at 1024 px across. */
private const val CARD_HEIGHT_PX = 576

/** 0.5.69: a card's text line and pill button label textures, and their text sizes, px ([SpaceRenderer.drawCard]). */
private const val CARD_LINE_W = 1024
private const val CARD_LINE_H = 96
private const val CARD_TITLE_PX = 58f
private const val CARD_BUTTON_W = 512
private const val CARD_BUTTON_H = 96
private const val CARD_BUTTON_PX = 52f

/** 0.5.45: after a resize, the new picture waits for this many frames from the app ... */
private const val RESIZE_SETTLE_COPIES = 3

/** ... or this long after its first (ms), then fades in over the old one for [RESIZE_FADE_MS]. */
private const val RESIZE_SETTLE_MS = 250L
private const val RESIZE_FADE_MS = 180L
