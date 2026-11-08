package com.hayeco.flipspaces.spatial

import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.SpaceLayout
import com.hayeco.flipspaces.core.spatial.SpaceViewport
import com.hayeco.flipspaces.core.spatial.SpatialPointer
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface SpaceCommand {
    /**
     * Opens [app] as a panel. [inFrontOfGaze] centres it where the user looks (the Space's
     * launcher); otherwise it takes the next free spot around the heading (phone and dock).
     */
    data class AddApp(val app: AppEntry, val inFrontOfGaze: Boolean = false) : SpaceCommand

    /** Open or close the Space's launcher (the phone's button, or the home button's key). */
    data object ToggleLauncher : SpaceCommand

    /** 0.5.37: open a saved window preset (the Menu): the windows now close, the preset's open exactly as saved. */
    data class OpenPreset(val preset: com.hayeco.flipspaces.core.spatial.WindowPreset) : SpaceCommand

    /** The apps the launcher lists, loaded off the GL thread. */
    data class LauncherApps(val apps: List<AppEntry>) : SpaceCommand

    /** A key typed while the launcher is open: [char], or null for backspace. */
    data class LauncherKey(val char: Char?) : SpaceCommand

    /** Search text from the phone's Spatial windows screen. */
    data class LauncherSearch(val query: String) : SpaceCommand

    /** 0.5.25 (Mike: the Android XR launcher): a keyboard's arrow, Enter or Esc while the launcher is open. */
    data class LauncherNav(val key: com.hayeco.flipspaces.core.spatial.LauncherKey) : SpaceCommand

    /**
     * A key from a hardware keyboard that reached the Space while a panel has focus (0.4.1):
     * it goes on to that panel's app. KeyEvent action, code, meta state, repeat and down time.
     */
    data class PanelKey(val action: Int, val keyCode: Int, val metaState: Int, val repeat: Int, val downTime: Long) : SpaceCommand
    /**
     * The phone's trackpad was touched (0.4.2): Android may have moved focus to the phone, so
     * the focused panel (or the open launcher's Space) gets it back.
     */
    data object TrackpadTouched : SpaceCommand
    data class Remove(val panelId: Int) : SpaceCommand
    data object Recentre : SpaceCommand
    data object ClearLayout : SpaceCommand
    /** Field of view, background or pointer settings changed in SpaceStore. */
    data object SettingsChanged : SpaceCommand

    /** 0.5.0: start the "pinch on the dots" hand calibration in the Space, or stop it if it runs. */
    data object CalibrateHands : SpaceCommand

    /** 0.5.17: the arrange actions from the trackpad's drawer ([com.hayeco.flipspaces.core.spatial.Arrange]). */
    data class Arrange(val kind: com.hayeco.flipspaces.core.spatial.ArrangeKind) : SpaceCommand

    /** 0.5.19: the wraparound turned on or off ([SpaceSession.wrap]): the Space wraps or unwraps its windows. */
    data object WrapChanged : SpaceCommand

    /**
     * 0.5.22 (the Glasses warp switch was never turned on): the switch was turned
     * [on] or off from the trackpad's Menu ([from]); the Space shows a short confirmation on the glasses.
     */
    data class GlassesWarpChanged(val on: Boolean, val from: String) : SpaceCommand
}

data class PanelInfo(val id: Int, val label: String, val status: String, val displayId: Int?)

data class SpaceSnapshot(
    val running: Boolean = false,
    val launcherOpen: Boolean = false,
    /** The panel the pointer last pressed, which keys go to (0.4.1). */
    val focusedPanelId: Int? = null,
    val mode: ModeChoice? = null,
    val panels: List<PanelInfo> = emptyList(),
    val perf: String? = null,
    val problem: String? = null,
)

/**
 * The Space as last drawn, so the debug replay can aim at a panel spot: the display pixel a
 * hand must point at to reach [u], [v] of a panel (the topmost when [panelId] is null).
 */
class SpaceAim(val layout: SpaceLayout, val head: Quat, val frustum: ViewFrustum, val viewport: SpaceViewport) {
    fun pixelOf(panelId: Int?, u: Float, v: Float): Point? {
        val panel = (if (panelId != null) layout.panel(panelId) else layout.panels.lastOrNull()) ?: return null
        return SpatialPointer(frustum).also { it.viewport = viewport }.displayPixelOf(panel, u, v, head)
    }
}

/**
 * Between the phone and the Space on the glasses: commands from the phone screen and pointer
 * signals from the trackpad and hands queue here and are taken on the GL thread each frame; the
 * renderer publishes what it shows back as [state].
 */
object SpaceSession {
    /** 0.5.12: the display delay check is on: the Space shows a line fixed in the room. */
    @Volatile var delayCheck: Boolean = false

    /** 0.5.15: the level calibration is on: the Space shows a line fixed to the display. */
    @Volatile var levelCheck: Boolean = false

    /** 0.5.12: the head timing line ([com.hayeco.flipspaces.core.spatial.HeadTimingLine]), for the diagnostics screen. */
    @Volatile var timingLine: String? = null

    /** 0.5.10: the Space's frame pacing in words ([com.hayeco.flipspaces.core.spatial.FramePacer.line]), for the head chain line. */
    @Volatile var pacerLine: String? = null

    /** 0.5.14: the Space's pose to photons p50 (ms), for the hand numbers' frame to pointer; null until measured. */
    @Volatile var poseToPhotonsP50Ms: Double? = null

    /** 0.5.14: the hand cursor's measured interval between samples (ms), whose half is the glide's delay. */
    @Volatile var handIntervalMs: Double? = null

    internal val commands = ConcurrentLinkedQueue<SpaceCommand>()
    internal val signals = ConcurrentLinkedQueue<PointerSignal>()

    /** 0.5.17: buzzes the Space asks the phone's trackpad for (a window picked up, a bar button reached). */
    val haptics = ConcurrentLinkedQueue<com.hayeco.flipspaces.core.pointer.PadHaptic>()

    /** 0.5.82: counts the launcher's asks for the phone's keyboard (its search card pressed); the trackpad answers each. */
    val keyboardWanted = java.util.concurrent.atomic.AtomicInteger()

    fun haptic(h: com.hayeco.flipspaces.core.pointer.PadHaptic) {
        if (haptics.size < 16) haptics += h
    }

    /**
     * 0.5.17: the tracking mode (Smooth Follow, Body Anchor, Space locked), set from the
     * trackpad's drawer or the Space's Control panel; the renderer reads it every frame.
     */
    @Volatile var follow: com.hayeco.flipspaces.core.spatial.FollowMode = com.hayeco.flipspaces.core.spatial.FollowMode.BODY_ANCHOR

    /** 0.5.17: the window the trackpad holds in the Space, or null (published every frame). */
    @Volatile var heldPanelId: Int? = null
        internal set

    /** 0.5.17 cover screen: the held window is held to resize and push (the cover's Resize button), not to move. */
    @Volatile var heldAdjusts: Boolean = false
        internal set

    /** 0.5.19: the held window is held to curve (a Curve button). */
    @Volatile var heldCurves: Boolean = false
        internal set

    /** 0.5.19: the curve new windows open with (0 flat to 1), from the trackpad's menu; the renderer reads it at each new window. */
    @Volatile var curveDefault: Float = 0f

    /** 0.5.19: the whole Space wrapped round Mike as one cylinder, from the trackpad's menu ([com.hayeco.flipspaces.core.spatial.SpaceWrap]). */
    @Volatile var wrap: Boolean = false

    /**
     * 0.5.37: the trackpad's state as the Control Center shows it (the trackpad publishes it every tick): Spatial Mouse in
     * use (and available), hand tracking running, cursor speed 0 to 100.
     */
    @Volatile var padSpatialMouse: Boolean = false
    @Volatile var padSpatialMouseAvailable: Boolean = true
    @Volatile var padHands: Boolean = false
    @Volatile var padCursorSpeed: Int = 50

    /**
     * 0.5.99 (Mike: record what the glasses show, as the Aura's puck does): the Control Center's or the Menu's Record
     * ([recordWanted]); whether the Space is recording ([recording], for the buttons and the trackpad); and the last
     * saved video's line, for a notice ([recordSaved]). The Space's renderer does the recording ([SpaceRecorder]).
     */
    @Volatile var recordWanted: Boolean = false
    @Volatile var recording: Boolean = false
    @Volatile var recordSaved: String? = null

    /** 0.5.37: what the Control Center asks of the trackpad; the trackpad takes them on its next tick. */
    sealed interface PadRequest {
        data object ToggleSpatialMouse : PadRequest
        data object ToggleHands : PadRequest
        data class Speed(val delta: Int) : PadRequest
    }

    val padRequests = java.util.concurrent.ConcurrentLinkedQueue<PadRequest>()

    /** 0.5.32: how many windows the Space has open, for the Menu's Arrange layouts ([com.hayeco.flipspaces.core.spatial.ArrangeLayouts.forCount]). */
    @Volatile var windowCount: Int = 0

    /** 0.5.19: packages that crashed on a panel, and when (uptime), across Space starts. */
    val crashedApps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 0.5.19: a crashed app is not started again by a Space start for this long. */
    const val CRASH_HOLD_MS = 60_000L

    /**
     * 0.5.0: each tracked hand's status (present, pinch value, pressed, ray), from the hand
     * feed's thread, for the Space's per hand cursors.
     */
    @Volatile var hands: Map<com.hayeco.flipspaces.core.pointer.HandSide, com.hayeco.flipspaces.core.hands.HandStatus> = emptyMap()

    /** The last drawn frame's aim helper, null while no Space is drawing. */
    @Volatile var aim: SpaceAim? = null
        internal set

    /** True while the Space is on screen: pointer signals then go to it, not to DeX windows. */
    @Volatile var active: Boolean = false
        internal set(value) {
            val changed = field != value
            field = value
            if (changed) activeListeners.forEach { it(value) }
        }

    /** 0.5.3: the Space's watchdog saw its frames stop; GPU hand tracking pauses until they run again. */
    @Volatile var stalled: Boolean = false
        internal set

    /**
     * An OpenXR game has the glasses and the Space stops drawing on purpose. The watchdog then expects no frames (before,
     * every game start was taken for a stall and closed the windows after 4 s), and the hand model rests.
     */
    @Volatile var gameOwnsGlasses: Boolean = false

    /** 0.5.3, debug builds: the render thread sleeps this long once at its next frame (a stall for the watchdog). */
    @Volatile var debugStallMs: Long = 0L

    /** 0.5.26: the hand model runs on the GPU now (set by the camera's provider). */
    @Volatile var handModelOnGpu: Boolean = false

    /**
     * 0.5.26 ([com.hayeco.flipspaces.core.spatial.SpaceGpuGuard]): the Space missed its deadlines waiting for a GPU busy
     * with the hand model; the hand model moves to the CPU for the rest of its camera session.
     */
    @Volatile var handsOffGpu: Boolean = false

    /** 0.5.3: the Space has drawn steadily for a while (the MediaPipe CPU trial waits for it). */
    @Volatile var steady: Boolean = false
        internal set

    /**
     * Told on the thread that changed [active] (the main thread) each time it changes (0.4.3),
     * so the trackpad can take focus again once the Space closes.
     */
    val activeListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    private val _state = MutableStateFlow(SpaceSnapshot())
    val state: StateFlow<SpaceSnapshot> = _state.asStateFlow()

    fun send(command: SpaceCommand) {
        commands += command
    }

    fun submit(signal: PointerSignal) {
        if (signals.size < 256) signals += signal
    }

    internal fun publish(snapshot: SpaceSnapshot) {
        _state.value = snapshot
    }
}
