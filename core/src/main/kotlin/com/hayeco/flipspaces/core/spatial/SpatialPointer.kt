package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.HomeEvent
import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.SwipeDirection
import com.hayeco.flipspaces.core.pointer.WindowAction
import com.hayeco.flipspaces.core.pointer.CoverWindowStep
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import kotlin.math.abs
import kotlin.math.sqrt

enum class TouchPhase { DOWN, MOVE, UP, CANCEL }

/** A mouse pointer entering, moving over and leaving a panel's content (0.4.1). */
enum class HoverPhase { ENTER, MOVE, EXIT }

/** What the Space should do in response to pointer input. */
sealed interface SpaceAction {
    /** A touch on a panel's app, at [u], [v] (0..1 across and down the panel). */
    data class Touch(val panelId: Int, val phase: TouchPhase, val u: Float, val v: Float) : SpaceAction

    /** The layout changed. [commit] is true when a grab ended: save it, resize displays. */
    data class Layout(val layout: SpaceLayout, val commit: Boolean) : SpaceAction

    /** Make where the user faces now the new forward. */
    data object Recentre : SpaceAction

    /** Turn the view by hand (no head tracking): a two finger drag over empty space. */
    data class TurnView(val deltaYawDeg: Float) : SpaceAction

    /** Every press, with what it hit and the nearest panel, for the log. */
    data class Pressed(val report: PressReport) : SpaceAction

    /** Open palm or three finger tap: open or close the launcher (0.4.0; it used to recentre). */
    data object ToggleLauncher : SpaceAction

    /** A press [SpatialPointer.pressFilter] took, along [ray] (the launcher's). 0.5.25: [hand] says which pointer pressed. */
    data class Claimed(val ray: Ray, val hand: HandSide? = null) : SpaceAction

    /**
     * 0.5.25 (Mike: the Android XR launcher): the rest of a claimed press (its moves and release), a
     * right click, a two finger swipe or a wheel over the open launcher, from [hand]'s pointer (null:
     * the trackpad's).
     */
    data class Launcher(val input: LauncherInput, val hand: HandSide? = null) : SpaceAction

    /** The pointer over a panel's content without pressing (0.4.1): mouse hover in its app. */
    data class Hover(val panelId: Int, val phase: HoverPhase, val u: Float, val v: Float) : SpaceAction

    /**
     * A two finger trackpad drag over a panel's content (0.4.1): mouse wheel scrolling at [u],
     * [v]. [dx], [dy] are glasses pixels of finger movement since BEGIN, as the signal had them.
     * 0.5.21: [smooth] (the thin scroll strip) sends fractions of a notch each update, not whole notches.
     */
    data class Wheel(val panelId: Int, val u: Float, val v: Float, val dx: Float, val dy: Float, val phase: Phase, val smooth: Boolean = false) : SpaceAction

    /** A trackpad pinch over a panel's content (0.4.1): a two finger pinch inside its app around [u], [v]. */
    data class Zoom(val panelId: Int, val u: Float, val v: Float, val factor: Float, val phase: Phase) : SpaceAction

    /** A two finger trackpad tap over a panel's content (0.4.1): a long press there. */
    data class LongPress(val panelId: Int, val u: Float, val v: Float) : SpaceAction

    /** The Back key for panel [panelId] (its Back button), or for the focused panel when null. */
    data class Back(val panelId: Int?) : SpaceAction

    /** A press landed on panel [panelId] (0.4.1): it takes input and keyboard focus. */
    data class Focus(val panelId: Int) : SpaceAction

    /** 0.5.11: a line for the input log (a hand scroll's start and its fling at the release). */
    data class Note(val line: String) : SpaceAction

    /** 0.5.28: a short note said on the glasses (head locked below the view, a few seconds) and logged. */
    data class Notice(val text: String) : SpaceAction

    /** 0.5.17: a buzz on the phone: a window picked up, a bar button reached. */
    data class Haptic(val haptic: PadHaptic) : SpaceAction

    /** 0.5.17: the title bar's Close: close panel [panelId]. */
    data class ClosePanel(val panelId: Int) : SpaceAction

    /** 0.5.21 (Mike: back, close and refresh buttons): reload the app on panel [panelId] ([PanelDisplays.refreshCommand]). */
    data class RefreshPanel(val panelId: Int) : SpaceAction

    /** 0.5.85 (Mike: an app switcher button on each window): Switch app pressed on panel [panelId]. */
    data class SwitchAppPanel(val panelId: Int) : SpaceAction

    /** 0.5.21: Wide view pressed under panel [panelId] ([WideView]). */
    data class WideViewPanel(val panelId: Int) : SpaceAction

    /** 0.5.24 (Mike: Twitch's channel does not fill the window): Fit video pressed under panel [panelId] ([VideoFit]). */
    data class FitVideoPanel(val panelId: Int) : SpaceAction

    /** 0.5.37: the window's Immersive button ([ImmersiveMode]). */
    data class ImmersivePanel(val panelId: Int) : SpaceAction

    /** 0.5.17: a three finger swipe ([ThreeFingerRoute]: up Recent apps, down the Control panel, sideways Back). */
    data class ThreeFinger(val direction: SwipeDirection) : SpaceAction
}

/** How each source steers the ray. */
data class PointerSettings(
    /** Hands move the ray; when false, hands only click: "look and pinch", head gaze aims. */
    val handsSteer: Boolean = true,
    /** 0.5.66: Spatial Mouse's dot stops inside the visible view and the phone's reference slides past it ([SpatialPointer]). */
    val mouseEdgeSlip: Boolean = true,
    /**
     * How long before a hand frame's capture time (end of its USB transfer) its picture was
     * taken: exposure, readout and transfer. The head pose of that earlier moment places the hand.
     */
    val handCameraDelayMs: Long = 20,
    /** Trackpad degrees per glasses pixel of finger movement. */
    val trackpadDegPerPx: Float = 0.04f,
    /** Trackpad scroll: panel pixels per glasses pixel of finger movement. */
    val scrollGain: Float = 1.5f,
    /** Degrees of view turn per glasses pixel of a two finger drag over empty space. */
    val turnDegPerPx: Float = 0.08f,
    /** How far outside a panel (degrees) a press still lands on it. */
    val pressMarginDeg: Float = 1.5f,
    /** Pointer moves over a panel's content send mouse hover events to its app (0.4.1). */
    val hoverEvents: Boolean = true,
    /** Two finger trackpad drags over a panel scroll it with mouse wheel events; false: a touch drag, as before 0.4.1. */
    val wheelScroll: Boolean = true,
    /** A trackpad pinch over a panel's content zooms inside its app; false: it scales the panel, as before 0.4.1. */
    val pinchZoomsApps: Boolean = true,
    /** 0.5.2: hand cursors move every display frame between hand frames ([AimInterpolator]). */
    val interpolateHands: Boolean = true,
    /** 0.5.9: a two hand stretch also moves the panel with the midpoint of the hands, as on Quest and Android XR. */
    val twoHandMove: Boolean = true,
    /** 0.5.10: pushing both hands away sends the panel back, pulling them in brings it closer ([TwoHandDepth]). */
    val twoHandDepth: Boolean = true,
    /**
     * 0.5.12: a hand's touch on a panel's content holds where it went down for this
     * long (ms) unless the ray moves more than [clickFreezeDeg] in the room, so the touch point stays
     * frozen just after a press. The hand's own wobble as the fingers close then never turns a tap
     * into a drag; once the hold ends, the touch follows the ray from where it was held, so the
     * wobble is absorbed rather than replayed as a jump. 0 turns it off.
     */
    val clickFreezeMs: Long = 200,
    val clickFreezeDeg: Float = 3f,
)

/**
 * One pointer in the Space: a ray from the user's head. By default it points where the head
 * points (head gaze); the trackpad nudges it relative to the head, so it keeps following the
 * head. A press on a panel body becomes a touch in that panel's app, a press on the title bar
 * grabs the panel to move it, a press on the corner handle resizes it.
 *
 * Hands: the camera is on the glasses, so a hand's position is relative to the head at the
 * moment the frame was taken. When the hand signal carries that moment and the head pose for it
 * is known ([handle]'s headAt), the hand's ray is put in the world with that pose and stays
 * there as the head turns, so turning the head with a still hand leaves the cursor on the same
 * spot of the panels. Without head tracking it stays relative to the head, as before 0.3.2.
 *
 * 0.5.0: each hand ([PointerSignal.ForHand]) has its own ray (from its own origin, the body
 * anchored hand ray, when the signal carries one), hover, press and grab, as on Quest and
 * Android XR; the trackpad and head gaze keep the main pointer, which the properties without
 * a hand ([ray], [hover], [busy], [offsetYaw], ...) describe. A hand's press grabs for that
 * hand only and ends only on its release or loss. A press of the second hand on the panel the
 * first one holds turns both into a stretch of that panel ([stretch]). Mouse hover goes to an
 * app from one pointer at a time: the one that last pressed, else the first over it.
 */
class SpatialPointer(var frustum: ViewFrustum, settings: PointerSettings = PointerSettings()) {
    /**
     * Maps hand positions (display pixels) onto [frustum]'s surface; null when they already match,
     * as they do once the Space fills the display.
     */
    var viewport: SpaceViewport? = null

    var settings: PointerSettings = settings
        set(value) {
            // Look and pinch: the ray goes back to where the head points.
            if (field.handsSteer && !value.handsSteer) {
                centre()
                hands.clear()
            }
            field = value
        }

    private sealed interface Grab
    private data class Touching(val panelId: Int, var u: Float, var v: Float) : Grab {
        /** 0.5.11, hand scrolls: where the touch went down, whether it has moved past the slop, its recent moves (u, v, ms). */
        val startU = u
        val startV = v
        var scrolled = false

        /** 0.5.46: the ray's and the touch's place (u, v, u, v) where the hand's scroll began, for [HAND_SCROLL_GAIN]. */
        var scrollFrom: FloatArray? = null
        val moves = ArrayDeque<Triple<Float, Float, Long>>()

        /** 0.5.12: the press's ray in the room and when its hold ends, while held ([PointerSettings.clickFreezeMs]). */
        var freezeDir: Vec3? = null
        var freezeUntilMs = 0L

        /** 0.5.12: the ray's recent directions while held, to tell a settled hand from one already dragging. */
        val heldRays = ArrayDeque<Pair<Vec3, Long>>()

        /** 0.5.12: the drift absorbed during the hold, taken off every later point (panel units). */
        var shiftU = 0f
        var shiftV = 0f
    }
    private data class Moving(val panelId: Int, val dYaw: Float, val dPitch: Float) : Grab
    private data class Resizing(val panelId: Int) : Grab

    /**
     * 1.1.15: a press on the resize handle, not yet a resize ([CornerDrag]): the press's ray [dir], when it went down, and
     * where it clicks the app ([u], [v]; [touched] when a touch went down there).
     */
    private data class CornerPress(val panelId: Int, val dir: Vec3, val sinceMs: Long, val u: Float, val v: Float, val touched: Boolean) : Grab
    private data class Scaling(val panelId: Int, val start: SpacePanel) : Grab
    private data class Scrolling(val panelId: Int, val u: Float, val v: Float, val pxW: Int, val pxH: Int) : Grab
    private data class Turning(var lastDx: Float) : Grab
    private data class Wheeling(val panelId: Int, val u: Float, val v: Float) : Grab
    private data class Zooming(val panelId: Int, val u: Float, val v: Float, var factor: Float = 1f) : Grab

    /** 0.5.0: one hand's part of a two hand stretch of [panelId] (see [Stretch]). */
    private data class Stretching(val panelId: Int) : Grab

    /**
     * 0.5.17: a window the trackpad holds: picked up by a click (or a
     * press) on its title bar, held until a tap or the Home button drops it. [yaw], [pitch] are
     * where it is going; the drawn panel eases there ([PadWindowMath.faceStep]).
     */
    private class Holding(val panelId: Int, val sinceFrame: Int, val sinceMs: Long, var yaw: Float, var pitch: Float) : Grab {
        /** 0.5.86: the fit between two windows is being shown ([fitPreview]), for its one buzz. */
        var fitShown = false

        /** The press that picked it up is still down: its release drops unless it was a click. */
        var pickPress = true
        var pickMoved = 0f

        /** A press while held (a tap or the long press): when, and how far the finger has moved since. */
        var pressMs: Long? = null
        var pressMoved = 0f

        /** Two fingers while held: their travel, the decision (true: push and pull, false: scale), the spread at the start. */
        var twoAx = 0f
        var twoAy = 0f
        var twoBx = 0f
        var twoBy = 0f
        var twoPush: Boolean? = null
        var twoSpread = 0f
        var scaleStart: SpacePanel? = null

        /** The scroll strip's travel last seen, for its push and pull. */
        var stripDy = 0f

        /** 0.5.27: this gesture moved the window in depth; logged once at its end. */
        var depthMoved = false

        /** 0.5.28: the window's distance when this gesture's depth move began. */
        var depthFrom = 0f

        /** Spatial Mouse: where the window is from the ray, kept while the phone aims it. */
        var mouseOffset: Pair<Float, Float>? = null

        /**
         * 0.5.42 (Mike: resizing, "it's as if there's an earthquake happening and the window starts shaking only while I'm
         * doing the resizing"): the held window is being resized or pushed, not moved. Its snap ([WindowSnap]) depends on
         * its size, so each step of a resize moved the snap's spot, and the window was chased after it every frame. While
         * this is set it stays where it is; a move clears it.
         */
        var sizing = false
        var sizingMs = 0L

        /** 0.5.17 cover screen: held by the Resize button, one finger resizes and pushes instead of moving. */
        var adjust = false

        /** 0.5.19: held by a Curve button, one finger's sideways swipe curves the window ([CurveControl]); [curveRaw] is its unsnapped value. */
        var curve = false
        var curveRaw = 0f
    }

    /** 0.5.17: a scroll from the trackpad as a touch drag on the panel, so the app flings after the lift. */
    private data class PadScrolling(val panelId: Int, val u0: Float, val v0: Float, var u: Float, var v: Float) : Grab

    /**
     * 0.5.41 (Mike: "two finger scroll on the trackpad doesn't work well"): the two finger scroll as the window's smooth
     * wheel at the cursor, page travel [PAD_WHEEL_PX_PER_UNIT] a pad unit with no end to it (0.5.40 dragged a touch, at
     * most 1.5 of the window from where it started, so long pages took several swipes, and a short rest could read as a
     * long press). It keeps to the axis it started on ([axis]), and the page coasts on after the fingers lift ([Coast]).
     */
    private class PadWheelScroll(val panelId: Int, val u: Float, val v: Float) : Grab {
        /** 0 not decided, 1 across, 2 up and down. */
        var axis = 0
        var x = 0f
        var y = 0f
        val samples = ArrayDeque<Triple<Long, Float, Float>>()
    }

    /** 0.5.21: the thin scroll strip scrolling the window under the cursor with its wheel, at [u], [v]. */
    private data class PadWheeling(val panelId: Int, val u: Float, val v: Float) : Grab

    /** 0.5.17: a two finger scroll over empty space turning the view (no head tracking), [lastDx] pad units. */
    private data class PadTurning(var lastDx: Float) : Grab

    /** A press on a button (a panel's Back): nothing to drag, nothing to release. */
    private data object Button : Grab

    /** A press the [pressFilter] took. 0.5.25: its moves and release go to the launcher ([SpaceAction.Launcher]). */
    private data object Claim : Grab

    /** 0.5.25: a two finger swipe or wheel over the open launcher, which pages it. */
    private data object LauncherScroll : Grab

    /** One pointer's aim and grab: [main] for the trackpad and head gaze, one per hand (0.5.0). */
    private class Aim(val hand: HandSide?) {
        /** Offset of the ray from the head's forward, in degrees. */
        var offsetYaw = 0f
        var offsetPitch = 0f

        /** The ray's direction in world space, while hands aim with head compensation. */
        var worldAim: Vec3? = null

        /** Where the ray starts, world space (with head compensation) or head frame (without). */
        var worldOrigin: Vec3? = null
        var headOrigin: Vec3? = null
        var grab: Grab? = null
        var hover: PanelHit? = null

        /** 0.5.2: a hand's aim between its frames, eased every display frame ([AimInterpolator]). */
        val interp = AimInterpolator()

        /** The interpolator holds world directions (head tracking) rather than head frame ones. */
        var interpWorld = false

        /** 0.5.10: the hand's apparent palm size from its last signal, for [TwoHandDepth]. */
        var size: Float? = null
    }

    /** 0.5.2: the render clock of the last [frame], for the hand interpolation. */
    private var clockMs = 0L

    private val main = Aim(null)
    private val hands = java.util.EnumMap<HandSide, Aim>(HandSide::class.java)

    /**
     * 0.5.0: both hands holding one panel: its size at the start and the hands' angle apart then.
     * 0.5.9: also where the panel's centre was from the two hands' midpoint ([dYaw], [dPitch]),
     * so the panel moves with the midpoint while it scales ([StretchMath.midpoint]).
     */
    private data class Stretch(
        val panelId: Int,
        val start: SpacePanel,
        val startDeg: Float,
        var factor: Float = 1f,
        val dYaw: Float = 0f,
        val dPitch: Float = 0f,
        /** 0.5.10: the push and pull, when both hands' sizes were known at the start. */
        val depth: TwoHandDepth? = null,
    )

    private var stretch: Stretch? = null

    /** 0.5.9: the stretch in progress also moves the panel with the hands' midpoint. */
    private var stretchMoves = false

    /** The head pose of the last [frame] or [handle], for the other hand's ray in a stretch. */
    private var lastHead = Quat.IDENTITY

    /** Offset of the main ray from the head's forward, in degrees. */
    val offsetYaw: Float get() = main.offsetYaw
    val offsetPitch: Float get() = main.offsetPitch

    /** The main ray in world space, while hands aim with head compensation; null otherwise. */
    val worldAim: Vec3? get() = main.worldAim

    /**
     * Asked with the ray of every press before the panels are: true takes the press (the
     * launcher, while it is open or the ray is on its home button), which then comes back as
     * [SpaceAction.Claimed] and never touches a panel.
     */
    var pressFilter: ((Ray) -> Boolean)? = null

    /** The panel and zone under the main ray at the last update, for drawing the hover state. */
    val hover: PanelHit? get() = main.hover

    /** The panel whose app was last sent a hover event, and where; null after an exit. */
    private var hovering: Touching? = null

    /** The pointer whose hover the apps get (0.5.0); null for whichever comes first. */
    private var hoverOwner: Aim? = null

    val busy: Boolean get() = main.grab != null
    val grabbedPanelId: Int? get() = grabbedPanelId(main)
    val grabZone: PanelZone? get() = grabZone(main)

    /** The hands that aim in the Space now (0.5.0), right first. */
    val activeHands: List<HandSide> get() = hands.keys.sortedByDescending { it == HandSide.RIGHT }

    /** [hand]'s hover, for drawing its cursor. */
    fun hover(hand: HandSide): PanelHit? = hands[hand]?.hover

    /** 0.5.14: the first aiming hand's measured interval between samples (ms), for the hand numbers; null without one. */
    fun handIntervalMs(): Float? = activeHands.firstOrNull()?.let { hands[it]?.interp?.takeIf { i -> i.ready }?.intervalMs }

    /** True while [hand] holds a press or a grab. */
    fun busy(hand: HandSide): Boolean = hands[hand]?.grab != null

    /** The panel any pointer holds and in which zone, for drawing it lit; the main pointer first. */
    fun grabOn(panelId: Int): PanelZone? =
        (listOf(main) + hands.values).firstOrNull { grabbedPanelId(it) == panelId }?.let { grabZone(it) }

    /** Any pointer's hover over [panelId], the main pointer first. */
    fun hoverOn(panelId: Int): PanelHit? =
        (listOf(main) + hands.values).firstNotNullOfOrNull { a -> a.hover?.takeIf { it.panel.id == panelId } }

    /** 0.5.17: the phone trackpad's page, from [PadSignal.Mode]. */
    var padMode: PadMode = PadMode.VERTICAL
        private set

    /** 0.5.17: frames drawn, for the drop check's two frames after a pick up (no drop on the pick up's own frames). */
    private var frames = 0
    private var lastFrameMs = Long.MIN_VALUE

    /** 0.5.17: Reset and the arrange actions, by panel. */
    private val tweens = LinkedHashMap<Int, PanelTween>()

    /** 0.5.17: the title bars' fade ([BarFade]). */
    private val bars = BarFade()

    /** 0.5.17: the main pointer's zone at the last frame, for the hover buzz on a bar button. */
    private var lastHoverZone: PanelZone? = null

    /** 0.5.17: the held window's guide ("FARTHER", "LARGER", ...) and when it was last changed. */
    private var guide: String? = null
    private var guideMs = Long.MIN_VALUE / 2

    /** 0.5.17, Spatial Mouse: the phone's angles and the head's when the ray was last recentred. */
    private data class MouseRef(val phoneYaw: Float, val phonePitch: Float, val headYaw: Float, val headPitch: Float)
    private var mouseRef: MouseRef? = null

    /** 1.1.10 (issue 35): the phone's angles at the last aim, and whether a one finger scroll holds the aim still. */
    private var lastPhoneAim: Pair<Float, Float>? = null

    /** 1.1.10 (issue 35): true while Spatial Mouse's one finger scroll lasts; the dot stays on what it scrolls ([AimHold]). */
    var aimHeld = false
        private set

    /** 0.5.17: the window the trackpad holds, or null. */
    val heldPanelId: Int? get() = (main.grab as? Holding)?.panelId

    /** 0.5.17: the main pointer holds a press down (the long press, a corner drag): the reticle's press scale. */
    val pressing: Boolean get() = main.grab is Touching || main.grab is Resizing || main.grab is CornerPress

    /** 0.5.17: the title bar's alpha for [panelId] now ([BarFade]). */
    fun barAlpha(panelId: Int): Float = bars.alpha(panelId, clockMs)

    /** 0.5.17: the held window's guide for [PadWindowMath.GUIDE_MS] after a change, else null. */
    fun holdGuide(): String? = guide?.takeIf { heldPanelId != null && clockMs - guideMs <= PadWindowMath.GUIDE_MS }

    private fun grabbedPanelId(a: Aim): Int? = when (val g = a.grab) {
        is Holding -> g.panelId
        is PadScrolling -> g.panelId
        is PadWheelScroll -> g.panelId
        is Touching -> g.panelId
        is Moving -> g.panelId
        is Resizing -> g.panelId
        is CornerPress -> g.panelId
        is Scaling -> g.panelId
        is Scrolling -> g.panelId
        is Wheeling -> g.panelId
        is Zooming -> g.panelId
        is Stretching -> g.panelId
        else -> null
    }

    private fun grabZone(a: Aim): PanelZone? = when (a.grab) {
        is Moving, is Holding -> PanelZone.TITLE_BAR
        is Resizing, is Scaling, is Stretching -> PanelZone.CORNER
        is Touching, is Scrolling, is Wheeling, is Zooming, is PadScrolling, is PadWheelScroll -> PanelZone.BODY
        else -> null
    }

    /**
     * The display pixel (the space hand positions arrive in) that aims at [u], [v] of [panel]
     * from [head], or null when that spot is behind or off the view. The inverse of a hand
     * MoveTo without head compensation, for the debug replay's panel coordinates.
     */
    fun displayPixelOf(panel: SpacePanel, u: Float, v: Float, head: Quat): Point? {
        val spot = PanelCurve.point(panel, u - 0.5f, 0.5f - v)
        val (sx, sy) = frustum.pixelOf(head.conjugate().rotate(spot.normalized())) ?: return null
        val (x, y) = viewport?.toInput(sx, sy) ?: (sx to sy)
        return Point(x, y)
    }

    fun ray(head: Quat): Ray = ray(main, head)

    /** [hand]'s ray, or null while that hand is not aiming. */
    fun ray(head: Quat, hand: HandSide): Ray? = hands[hand]?.let { ray(it, head) }

    private fun ray(a: Aim, head: Quat): Ray {
        syncOffset(a, head)
        val origin = a.worldOrigin ?: a.headOrigin?.let { head.rotate(it) } ?: Vec3.ZERO
        return Ray(origin, head.rotate(Vec3.direction(a.offsetYaw, a.offsetPitch)))
    }

    /**
     * With a world aim, the offset is wherever that world ray is from the head now, kept in view. 0.5.41 (Mike: with
     * Cursor follows head off, looking down moved the dot down, "trapped within the viewing window"): an anchored trackpad
     * cursor ([anchoredCursor]) is not kept in view; it stays where it is in the room, out of sight while the head looks away.
     */
    private fun syncOffset(a: Aim, head: Quat) {
        val w = a.worldAim ?: return
        val angles = Angles.of(head.conjugate().rotate(w))
        a.offsetYaw = angles.yawDeg
        a.offsetPitch = angles.pitchDeg
        if (!anchoredCursor(a)) clampOffset(a)
    }

    /** 0.5.41: the trackpad's cursor anchored in the room ([cursorFollowsHead] off, not Spatial Mouse while the phone aims). */
    private fun anchoredCursor(a: Aim): Boolean = a === main && !cursorFollowsHead && !phoneAims

    /**
     * Unreleased (a tester's Bluetooth mouse looked dead): on Spatial Mouse the phone's aim set the pointer on every sensor
     * update, so a mouse move was overwritten at once. Now a physical mouse wins: for [MOUSE_TAKES_OVER_MS] after it moved
     * ([mouseMovedMs]) the phone's aim is left out and the mouse moves the pointer as on the other pages; after, the phone
     * aims again from where the pointer is ([mouseRefFromPointer]), with no jump back.
     */
    val mouseTookOver: Boolean
        get() = padMode == PadMode.SPATIAL_MOUSE && mouseMovedMs != Long.MIN_VALUE && clockMs - mouseMovedMs < MOUSE_TAKES_OVER_MS

    /** Spatial Mouse, and the phone aims (no mouse moved lately, [mouseTookOver]). */
    private val phoneAims: Boolean get() = padMode == PadMode.SPATIAL_MOUSE && !mouseTookOver

    /** The render clock when a physical mouse last moved the pointer ([PointerSignal.MoveBy]). */
    private var mouseMovedMs = Long.MIN_VALUE

    /** The next phone aim takes its reference from where the pointer is, not the head's forward (the mouse moved it). */
    private var mouseRefFromPointer = false

    /** 0.5.41: [a]'s offset is outside the view now. */
    private fun outsideView(a: Aim): Boolean =
        abs(a.offsetYaw) > frustum.horizontalFovDeg / 2f || abs(a.offsetPitch) > frustum.verticalFovDeg / 2f

    /**
     * 0.5.41: after a trackpad move, kept in view, except an anchored cursor that was out of view before it: that one
     * moves where it is in the room, so a resting finger does not pull it to the view's edge.
     */
    private fun clampAfterMove(wasOutside: Boolean) {
        if (!(anchoredCursor(main) && wasOutside)) {
            outsideTravel = Pair(0f, 0f)
            clampOffset(main)
            return
        }
        // 0.5.78 (Mike: "its still easy for me to lose my cursor dot as it gets lost in oblivion"): an anchored
        // cursor out of view comes back once a swipe's net travel since it went out passes [BRING_BACK_DEG]: to the nearest
        // edge of the comfortable view ([MOUSE_EDGE_MARGIN_H], [MOUSE_EDGE_MARGIN_V] in), and [cursorReturns] counts it so the
        // Space can show where. Net, not total: a resting finger's jitter cancels out and never pulls it (0.5.41's rule).
        if (kotlin.math.hypot(outsideTravel.first, outsideTravel.second) < BRING_BACK_DEG) return
        outsideTravel = Pair(0f, 0f)
        val h = (frustum.horizontalFovDeg / 2f - MOUSE_EDGE_MARGIN_H).coerceAtLeast(1f)
        val v = (frustum.verticalFovDeg / 2f - MOUSE_EDGE_MARGIN_V).coerceAtLeast(1f)
        main.offsetYaw = main.offsetYaw.coerceIn(-h, h)
        main.offsetPitch = main.offsetPitch.coerceIn(-v, v)
        cursorReturns++
    }

    /** 0.5.86: where and at what size a held window would fit between two others on the drop ([WindowSnap.Snapped.fitWidthDeg]). */
    data class FitPreview(val panelId: Int, val yawDeg: Float, val pitchDeg: Float, val widthDeg: Float, val heightDeg: Float)

    /** 0.5.86: the held window's fit, shown by the Space while held; null when it would not fit anywhere. */
    var fitPreview: FitPreview? = null
        private set

    /** 0.5.78: a swipe's net travel (yaw, pitch degrees) since the anchored cursor went out of view. */
    private var outsideTravel = Pair(0f, 0f)

    /**
     * 0.5.78: set by the Space while the head cursor's dot is auto hidden (no hand aims, nothing held or pressed): the main
     * pointer then hovers nothing ([frame]). Any input shows the dot again, and the Space clears it.
     */
    var hoverSuspended = false

    /** 0.5.78: how many times an out of view cursor was brought back by a swipe ([clampAfterMove]); the Space ripples on each. */
    var cursorReturns = 0
        private set

    private fun travelOutside(wasOutside: Boolean, dYaw: Float, dPitch: Float) {
        outsideTravel = if (wasOutside) Pair(outsideTravel.first + dYaw, outsideTravel.second + dPitch) else Pair(0f, 0f)
    }

    /** Recentres the main ray on the head's forward. */
    fun centre() {
        main.worldAim = null
        main.worldOrigin = null
        main.headOrigin = null
        main.offsetYaw = 0f
        main.offsetPitch = 0f
        anchorIfFixed(lastHead)
    }

    /**
     * 0.5.36 (Mike: a way to stop the cursor following the head when needed): off, the trackpad's cursor is anchored in the
     * room. Turning the head no longer carries it; the trackpad moves it from where it is. Spatial Mouse is anchored
     * anyway (the phone aims). On again, it follows the head from where it is.
     */
    var cursorFollowsHead = true
        set(value) {
            if (field == value) return
            field = value
            if (padMode == PadMode.SPATIAL_MOUSE || main.grab != null) return
            if (value) {
                syncOffset(main, lastHead)
                main.worldAim = null
            } else {
                syncOffset(main, lastHead)
                anchorIfFixed(lastHead)
            }
        }

    /**
     * Unreleased (Mike, 1.1.17-mouse-test: "after clicking in a window and typing, [the cursor] moves to another window if
     * i look toward another window even though i have head cursor setting off"): a physical mouse has moved or clicked
     * the pointer, and is still connected. Set by the Space. The Aura's camera saw his hands on the keyboard, so a hand
     * cursor took the place of the mouse's pointer (its hover too, [follow]), aimed through a camera wider than the view,
     * so it partly turned with the head; and its stray pinches pressed other windows, which took the keyboard.
     */
    var mouseLeads = false

    /**
     * With [cursorFollowsHead] off and the mouse leading ([mouseLeads]), the hands are set aside: no hand cursor, hover,
     * press or gesture, so only the mouse or the trackpad moves the pointer and the keys stay with the window last
     * clicked. With Cursor follows head on, or the mouse gone, the hands work as before.
     */
    val handsSetAside: Boolean get() = mouseLeads && !cursorFollowsHead

    /** 0.5.37: dragged windows snap to their neighbours ([WindowSnap]); the Menu's and the Control Center's switch. */
    var snapWindows = true

    /** 0.5.37: the window the held one last snapped to, for one buzz a snap. */
    private var snappedTo: Int? = null

    /** 1.1.15: Lock screens ([WindowLock]): no window moves or resizes from the pointer. Set with [lockWindows]. */
    var windowsLocked = false
        private set

    /**
     * 1.1.15: Lock screens on or off. Turning it on lets go of any window being moved, resized or held where it is now (a
     * resize handle press still waiting to be a drag stays a click), and of every window held together.
     */
    fun lockWindows(locked: Boolean, layout: SpaceLayout): List<SpaceAction> {
        val out = mutableListOf<SpaceAction>()
        if (locked == windowsLocked) return out
        windowsLocked = locked
        if (locked) {
            allHold?.let { allEnd(it, layout, out, cancel = false) }
            for (a in listOf(main) + hands.values) {
                if (a.grab is Moving || a.grab is Resizing || a.grab is Scaling || a.grab is Holding || a.grab is Stretching) release(a, latest(layout, out), out, cancel = false)
            }
            tweens.clear()
        }
        out += SpaceAction.Note("Lock screens ${if (locked) "on: windows stay where they are" else "off: windows move and resize again"}")
        return out
    }

    /** 1.1.15: the notice for a move or resize tried while locked. */
    private fun lockedNotice(out: MutableList<SpaceAction>, what: String) {
        out += SpaceAction.Notice(WindowLock.NOTICE)
        out += SpaceAction.Note("$what: Lock screens is on, nothing moved")
    }

    /** 0.5.36: with [cursorFollowsHead] off, the trackpad cursor's head relative aim fixed in the room as it is now. */
    private fun anchorIfFixed(head: Quat) {
        if (cursorFollowsHead || phoneAims) return
        main.worldAim = head.rotate(Vec3.direction(main.offsetYaw, main.offsetPitch))
        main.worldOrigin = null
        main.headOrigin = null
    }

    /**
     * Call every frame with the latest head pose: the head moves the rays even without input, so
     * a grabbed panel follows and a held touch drags.
     */
    /**
     * 1.2.5: a hand's room aim straight from its own ray, filtered once per camera frame and eased every display frame
     * by the app, in place of the pixel round trip, dead zone, buffer and prediction of the eased aim ([applyInterp]).
     * Null for a hand: the eased aim as before. See the app's DirectAimSmoother for why.
     */
    @Volatile var directAim: ((HandSide) -> Vec3?)? = null

    fun frame(head: Quat, layout: SpaceLayout, nowMs: Long = clockMs): List<SpaceAction> {
        lastHead = head
        clockMs = maxOf(clockMs, nowMs)
        val out = mutableListOf<SpaceAction>()
        // 0.5.78 (Mike: with Cursor follows head off, "its as if theres an invisible one because i can still hover select
        // things with head gaze"): the anchored cursor is pinned in the room from the start, not only after its first move
        // (until then its aim was head relative, so a hidden dot hovered along the gaze); and while the Space has the dot
        // hidden ([hoverSuspended]) it hovers nothing, as nothing is shown there.
        if (anchoredCursor(main) && main.worldAim == null) anchorIfFixed(head)
        if (handsSetAside) setHandsAside(layout, out)
        if (hoverSuspended && main.grab == null) hoverTo(main, null, out) else follow(main, ray(main, head), layout, out)
        for (a in hands.values.toList()) {
            // The direct hand aim, when the app gives one, takes the place of the eased aim.
            val direct = a.hand?.let { directAim?.invoke(it) }
            if (direct != null) a.worldAim = direct else applyInterp(a)
            follow(a, ray(a, head), layout, out)
        }
        padFrame(layout, out)
        return out
    }

    /** The newest layout in [out], else [layout]: each change builds on the one before it. */
    private fun latest(layout: SpaceLayout, out: List<SpaceAction>): SpaceLayout =
        out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.layout ?: layout

    /**
     * 0.5.17, every frame: the held window eases to where the finger sent it (Slerp dt x 12), Reset
     * and arrange tweens run, the title bars fade, and the main pointer reaching a bar button buzzes.
     */
    private fun padFrame(layout: SpaceLayout, out: MutableList<SpaceAction>) {
        frames++
        val dtS = if (lastFrameMs == Long.MIN_VALUE) 0f else ((clockMs - lastFrameMs).coerceIn(0L, 200L) / 1000f)
        lastFrameMs = clockMs
        coastFrame(dtS, out)
        throwFrame(out)
        // 0.5.86: no fit to show with nothing held.
        if (main.grab !is Holding) fitPreview = null
        (main.grab as? Holding)?.let { g ->
            val l = latest(layout, out)
            val p = l.panel(g.panelId)
            if (p == null) {
                main.grab = null
            } else {
                // 0.5.37 (Mike: snap a dragged window to the one it nears): where it settles among the others, a buzz on each new snap.
                val target = if (snapWindows && !g.sizing) WindowSnap.snap(p, g.yaw, g.pitch, l.panels, sticky = snappedTo) else WindowSnap.Snapped(g.yaw, g.pitch, if (g.sizing) snappedTo else null)
                if (target.toId != snappedTo) {
                    snappedTo = target.toId
                    if (target.snapped) out += SpaceAction.Haptic(PadHaptic.HOVER_BUTTON)
                }
                // 0.5.84 (Mike: a window dragged between two at their depth resizes to fit). 0.5.86 (Mike: it was "tweaking out
                // ... flickers quickly between resizing and its original size"): resizing the held window each frame fought the
                // Space's own size rules, which put it back the next frame. Now the fit is only shown ([fitPreview], an outline
                // of the fitted size in the space) while held, with one buzz, and taken on the drop.
                val fitW = target.fitWidthDeg
                fitPreview = fitW?.let { FitPreview(p.id, target.yawDeg, target.pitchDeg, it, target.fitHeightDeg ?: p.heightDeg) }
                if (fitW != null && !g.fitShown) out += SpaceAction.Haptic(PadHaptic.HOVER_BUTTON)
                g.fitShown = fitW != null
                if (abs(wrapDegrees(target.yawDeg - p.yawDeg)) > 1e-3f || abs(target.pitchDeg - p.pitchDeg) > 1e-3f) {
                    val (y, pt) = PadWindowMath.faceStep(p.yawDeg, p.pitchDeg, target.yawDeg, target.pitchDeg, dtS)
                    out += SpaceAction.Layout(l.replace(p.copy(yawDeg = y, pitchDeg = pt)), commit = false)
                }
            }
        }
        if (tweens.isNotEmpty()) {
            for ((id, t) in tweens.entries.toList()) {
                val l = latest(layout, out)
                if (l.panel(id) == null) {
                    tweens.remove(id)
                    continue
                }
                val done = t.done(clockMs)
                out += SpaceAction.Layout(l.replace(t.at(clockMs).copy(id = id)), commit = done)
                if (done) tweens.remove(id)
            }
        }
        val pointed = (listOf(main) + hands.values).flatMap { listOfNotNull(it.hover?.panel?.id, grabbedPanelId(it)) }
        bars.update(pointed, layout.panels.map { it.id }, clockMs)
        val zone = main.hover?.zone
        if (zone != lastHoverZone && (zone == PanelZone.BACK || zone == PanelZone.REFRESH || zone == PanelZone.SWITCH_APP || zone == PanelZone.RESET || zone == PanelZone.CLOSE || zone == PanelZone.WIDE_VIEW || zone == PanelZone.FIT_VIDEO || zone == PanelZone.IMMERSIVE)) {
            out += SpaceAction.Haptic(PadHaptic.HOVER_BUTTON)
        }
        lastHoverZone = zone
    }

    /**
     * 0.5.17: the arrange actions ([Arrange]) over 0.5 s; a held window is dropped first.
     * [mainId] is Circle's main window (the focused one).
     */
    fun arrange(kind: ArrangeKind, layout: SpaceLayout, mainId: Int?): List<SpaceAction> {
        if (kind == ArrangeKind.ADJUST_ALL) return holdAll(layout)
        val out = mutableListOf<SpaceAction>()
        if (windowsLocked) {
            lockedNotice(out, kind.label)
            return out
        }
        if (main.grab is Holding) release(main, layout, out, cancel = false)
        val l = latest(layout, out)
        val headYaw = Angles.of(lastHead.forward).yawDeg
        val targets = ArrangeLayouts.place(kind, l.panels, headYaw, mainId)
        for (t in targets) l.panel(t.id)?.let { tweens[t.id] = PanelTween(it, t, clockMs) }
        out += SpaceAction.Note("${kind.label}: ${targets.size} windows over ${PadWindowMath.TWEEN_MS} ms")
        return out
    }

    // ---- 0.5.88: every window held together ([AllWindows]) ----

    private class AllHold(val start: SpaceLayout, val byHands: Boolean, val guidePanelId: Int?) {
        var change = AllWindows.Change()
        var stripDy = 0f
        var twoAx = 0f
        var twoAy = 0f
        var twoBx = 0f
        var twoBy = 0f
        var twoPush: Boolean? = null
        var twoSpread = 1f
        var twoSize = 1f
    }

    private var allHold: AllHold? = null
    private var allGuideText: String? = null
    private var allGuideMs = 0L

    /** 0.5.88: true while every window is held together, by both hands or from the trackpad. */
    val allWindowsHeld: Boolean get() = allHold != null

    /** 0.5.88: the window that shows the group's guide (FARTHER, LARGER ...), the one nearest the group's centre. */
    val allGuidePanelId: Int? get() = allHold?.guidePanelId

    /** 0.5.88: the group's guide while it is fresh. */
    fun allGuide(): String? = allGuideText?.takeIf { allHold != null && clockMs - allGuideMs <= PadWindowMath.GUIDE_MS }

    /**
     * 0.5.88 (Mike: "there should also be a simple way to do this with trackpad and spatial mouse cursor dot as well"):
     * the Menu's or the Control Center's Adjust all, or both hands held up ([byHands]), takes hold of every window. From
     * the trackpad the held window's own gestures then act on them all: a finger moved (or the phone pointed, with the
     * Spatial Mouse) turns the group round the user, two fingers moved up or down or the scroll strip push and pull it,
     * two fingers spread or pinched resize it, a tap lets go where they are and Back puts them back.
     */
    fun holdAll(layout: SpaceLayout, byHands: Boolean = false): List<SpaceAction> {
        val out = mutableListOf<SpaceAction>()
        allHold?.let { allEnd(it, layout, out, cancel = false) }
        // 1.1.15: Lock screens: nothing is held; both hands up says nothing (they may only be resting).
        if (windowsLocked) {
            if (!byHands) lockedNotice(out, "Adjust all")
            return out
        }
        if (layout.panels.isEmpty()) {
            if (!byHands) out += SpaceAction.Notice("Open a window first.")
            return out
        }
        release(main, layout, out, cancel = true)
        for (a in hands.values.toList()) release(a, layout, out, cancel = true)
        tweens.clear()
        coast?.let { endCoast(it, out) }
        val l = latest(layout, out)
        val (cy, cp) = AllWindows.centre(l.panels)
        val guide = l.panels.minByOrNull { abs(wrapDegrees(it.yawDeg - cy)) + abs(it.pitchDeg - cp) }?.id
        allHold = AllHold(l, byHands, guide)
        allGuideText = null
        out += SpaceAction.Haptic(PadHaptic.HOVER_BUTTON)
        if (!byHands) {
            val turn = if (padMode == PadMode.SPATIAL_MOUSE) "point the phone" else "move a finger"
            out += SpaceAction.Notice("All windows held: $turn to turn them, two fingers up or down to push or pull, spread or pinch to resize. Tap to let go, Back to undo.")
        }
        out += SpaceAction.Note("all ${l.panels.size} windows held together (${if (byHands) "both hands up" else "Adjust all"})")
        return out
    }

    /** 0.5.88: lets go of every window: where they are, or with [cancel] back where they were. */
    fun releaseAll(layout: SpaceLayout, cancel: Boolean): List<SpaceAction> {
        val out = mutableListOf<SpaceAction>()
        allHold?.let { allEnd(it, layout, out, cancel) }
        return out
    }

    private fun allEnd(h: AllHold, layout: SpaceLayout, out: MutableList<SpaceAction>, cancel: Boolean) {
        if (cancel) h.change = AllWindows.Change()
        allApply(h, layout, out, commit = true)
        allHold = null
        allGuideText = null
        out += SpaceAction.Haptic(PadHaptic.HOVER_BUTTON)
        out += SpaceAction.Note(AllWindows.line(if (cancel) "all windows put back" else "all windows let go", h.start, AllWindows.apply(h.start, h.change)))
    }

    /** The group at [h]'s change, laid over the newest layout (a window closed meanwhile stays closed). */
    private fun allApply(h: AllHold, layout: SpaceLayout, out: MutableList<SpaceAction>, commit: Boolean) {
        val moved = AllWindows.apply(h.start, h.change)
        val l = latest(layout, out)
        val next = SpaceLayout(l.panels.map { p -> moved.panel(p.id) ?: p }, if (h.start.wrap != null) moved.wrap else l.wrap)
        out += SpaceAction.Layout(next, commit = commit)
    }

    /** [c] with its distance and size kept to what every window of [h] can take. */
    private fun allClamp(h: AllHold, c: AllWindows.Change): AllWindows.Change {
        val d = c.distance.coerceIn(AllWindows.distanceRange(h.start.panels))
        val k = AllWindows.angularRange(h.start.panels, AllWindows.centre(h.start.panels).second)
        return c.copy(distance = d, size = c.size.coerceIn(k.start * d, k.endInclusive * d))
    }

    private fun allSet(h: AllHold, c: AllWindows.Change, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val next = allClamp(h, c)
        val was = h.change
        if (next == was) return
        h.change = next
        val k = next.size / next.distance
        val kWas = was.size / was.distance
        when {
            next.distance > was.distance * 1.0005f -> allGuideText = "FARTHER"
            next.distance < was.distance / 1.0005f -> allGuideText = "CLOSER"
            k > kWas * 1.0005f -> allGuideText = "LARGER"
            k < kWas / 1.0005f -> allGuideText = "SMALLER"
        }
        allGuideMs = clockMs
        allApply(h, layout, out, commit = false)
    }

    /** Both hands' gesture ([com.hayeco.flipspaces.core.hands.BothPalms]): their factors since it began. */
    private fun allWindows(s: PointerSignal.AllWindows, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> out += holdAll(latest(layout, out), byHands = true)
            Phase.UPDATE, Phase.END -> {
                val h = allHold?.takeIf { it.byHands } ?: return
                allSet(h, h.change.copy(distance = s.distance, size = s.size), layout, out)
                if (s.phase == Phase.END) allEnd(h, layout, out, cancel = false)
            }
        }
    }

    /** A push or pull of [dy] pad units, at a held window's pace ([PadWindowMath.pushPull]) from the group's middle distance. */
    private fun allPush(h: AllHold, dy: Float, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val ref = h.start.panels.sortedBy { it.distanceM }.getOrNull(h.start.panels.size / 2) ?: return
        val now = ref.distanceM * h.change.distance
        val next = PadWindowMath.pushPull(ref.copy(distanceM = now), dy).distanceM
        allSet(h, h.change.copy(distance = next / ref.distanceM), layout, out)
    }

    /** True when [signal] was the held group's (see [holdAll]); hands holding it leave every other input as it is. */
    private fun allSignal(h: AllHold, signal: PointerSignal, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>): Boolean {
        if (h.byHands || signal is PointerSignal.ForHand || signal is PointerSignal.AllWindows) return false
        when (signal) {
            is PointerSignal.Down -> allEnd(h, layout, out, cancel = false)
            is PointerSignal.Up -> Unit
            PointerSignal.Back -> allEnd(h, layout, out, cancel = true)
            PointerSignal.ShowDock -> {
                allEnd(h, layout, out, cancel = false)
                return false
            }
            is PointerSignal.Scroll -> if (signal.phase == Phase.BEGIN) {
                h.stripDy = 0f
            } else {
                allPush(h, signal.dy - h.stripDy, layout, out)
                h.stripDy = signal.dy
            }
            is PointerSignal.Scale -> if (signal.phase == Phase.BEGIN) {
                h.twoSize = h.change.size
            } else {
                allSet(h, h.change.copy(size = h.twoSize * signal.factor), layout, out)
            }
            is PadSignal.Move, is PadSignal.Aim -> {
                val before = Angles.of(ray(main, head).dir)
                pad(signal as PadSignal, head, layout, out)
                allTurn(h, before, head, layout, out)
            }
            is PointerSignal.MoveBy -> {
                val before = Angles.of(ray(main, head).dir)
                allHold = null
                handle(main, signal, head, layout, { 0 to 0 }, null, out)
                allHold = h
                allTurn(h, before, head, layout, out)
            }
            is PadSignal.TwoFinger -> when (signal.phase) {
                Phase.BEGIN -> {
                    h.twoAx = 0f; h.twoAy = 0f; h.twoBx = 0f; h.twoBy = 0f
                    h.twoPush = null
                    h.twoSpread = signal.spread
                    h.twoSize = h.change.size
                }
                Phase.UPDATE -> {
                    h.twoAx += signal.ax; h.twoAy += signal.ay; h.twoBx += signal.bx; h.twoBy += signal.by
                    if (h.twoPush == null) h.twoPush = PadWindowMath.sameWay(h.twoAx, h.twoAy, h.twoBx, h.twoBy)
                    when (h.twoPush) {
                        true -> allPush(h, (signal.ay + signal.by) / 2f, layout, out)
                        false -> allSet(h, h.change.copy(size = h.twoSize * signal.spread / h.twoSpread.coerceAtLeast(1e-3f)), layout, out)
                        null -> Unit
                    }
                }
                Phase.END -> h.twoPush = null
            }
            is PadSignal.Scroll -> if (signal.strip || signal.wheel) {
                if (signal.phase == Phase.BEGIN) h.stripDy = 0f
                val dy = signal.dy - h.stripDy
                h.stripDy = signal.dy
                if (dy != 0f) allPush(h, dy, layout, out)
            }
            is PadSignal.Pinch, is PadSignal.Window, is PadSignal.Curve, PointerSignal.SecondaryTap -> Unit
            is PadSignal.Swipe -> allEnd(h, layout, out, cancel = true)
            is PadSignal.Home -> when (signal.event) {
                HomeEvent.CLICK -> allEnd(h, layout, out, cancel = false)
                HomeEvent.LONG_PRESS -> return false
                else -> Unit
            }
            else -> return false
        }
        return true
    }

    /** The group turned as far as the cursor turned since [before]. */
    private fun allTurn(h: AllHold, before: Angles, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val after = Angles.of(ray(main, head).dir)
        val dy = wrapDegrees(after.yawDeg - before.yawDeg)
        val dp = after.pitchDeg - before.pitchDeg
        if (abs(dy) < 1e-4f && abs(dp) < 1e-4f) return
        allSet(h, h.change.copy(yawDeg = h.change.yawDeg + dy, pitchDeg = h.change.pitchDeg + dp), layout, out)
    }

    /** 0.5.2: puts [a]'s eased aim for now in place of its last sample. */
    private fun applyInterp(a: Aim) {
        val v = a.interp.at(clockMs) ?: return
        if (a.interpWorld) {
            a.worldAim = v
        } else {
            val angles = Angles.of(v)
            a.offsetYaw = angles.yawDeg
            a.offsetPitch = angles.pitchDeg
            clampOffset(a)
        }
    }

    /**
     * 0.5.0: [hand] left the camera: its press or grab is cancelled, its hover exits, and it
     * stops aiming until it sends a position again.
     */
    fun dropHand(hand: HandSide, head: Quat, layout: SpaceLayout): List<SpaceAction> {
        val a = hands[hand] ?: return emptyList()
        val out = mutableListOf<SpaceAction>()
        release(a, layout, out, cancel = true)
        if (hoverOwner === a) {
            hoverTo(a, null, out)
            hoverOwner = null
        }
        hands.remove(hand)
        return out
    }

    /** Unreleased ([handsSetAside]): every aiming hand let go of as if it left the camera, and a hold of every window by the hands ended where it is. */
    private fun setHandsAside(layout: SpaceLayout, out: MutableList<SpaceAction>) {
        allHold?.takeIf { it.byHands }?.let { allEnd(it, layout, out, cancel = false) }
        if (hands.isEmpty()) return
        for (side in hands.keys.toList()) out += dropHand(side, lastHead, latest(layout, out))
        out += SpaceAction.Note("a mouse moves the pointer with Cursor follows head off: hand cursors and pinches set aside until the mouse goes or Cursor follows head is on")
    }

    /**
     * [headAt] gives the head pose at a time on the hand camera's clock, or null when head
     * tracking is not live; it places hand signals that carry a time in the world.
     */
    fun handle(
        signal: PointerSignal,
        head: Quat,
        layout: SpaceLayout,
        panelPixels: (Int) -> Pair<Int, Int>,
        headAt: ((Long) -> Quat?)? = null,
    ): List<SpaceAction> {
        lastHead = head
        inputs++
        val out = mutableListOf<SpaceAction>()
        handle(main, signal, head, layout, panelPixels, headAt, out)
        return out
    }

    /**
     * 0.5.32 (Mike: a way to recenter the Spatial Mouse's dot when it is lost, with a long press of Home): the phone has
     * pointed outside the view, the dot pinned at its edge,
     * for [LASER_LOST_MS]. A Home hold ([HomeEvent.LONG_PRESS]) or a mode change clears it.
     */
    val laserLost: Boolean
        get() = padMode == PadMode.SPATIAL_MOUSE && laserPinnedSinceMs != Long.MIN_VALUE && clockMs - laserPinnedSinceMs >= LASER_LOST_MS

    private var laserPinnedSinceMs = Long.MIN_VALUE

    /** 0.5.32: trackpad and pointer signals handled so far, for the head cursor's auto hide ([HeadCursorVisibility]). */
    @Volatile var inputs = 0L
        private set

    private fun handle(
        a: Aim,
        signal: PointerSignal,
        head: Quat,
        layout: SpaceLayout,
        panelPixels: (Int) -> Pair<Int, Int>,
        headAt: ((Long) -> Quat?)?,
        out: MutableList<SpaceAction>,
        origin: Vec3? = null,
        dir: Vec3? = null,
    ) {
        // Unreleased: the mouse leads with Cursor follows head off ([handsSetAside]): a hand's signals go nowhere.
        if (handsSetAside && (signal is PointerSignal.ForHand || signal is PointerSignal.AllWindows)) {
            setHandsAside(layout, out)
            return
        }
        // 0.5.88: while every window is held from the trackpad, its gestures move them all ([allSignal]).
        allHold?.let { h -> if (allSignal(h, signal, head, layout, out)) return }
        when (signal) {
            is PointerSignal.AllWindows -> allWindows(signal, layout, out)
            is PointerSignal.ForHand -> {
                // Look and pinch: hands only click, along the head's gaze, so they share the main pointer.
                if (!settings.handsSteer) return handle(main, signal.signal, head, layout, panelPixels, headAt, out)
                val hand = hands.getOrPut(signal.hand) { Aim(signal.hand) }
                signal.size?.let { hand.size = it }
                handle(hand, signal.signal, head, layout, panelPixels, headAt, out, signal.origin, signal.dir)
            }
            is PointerSignal.MoveBy -> {
                // Unreleased: on Spatial Mouse a mouse's move takes the pointer from the phone for a while ([mouseTookOver]).
                if (padMode == PadMode.SPATIAL_MOUSE && (signal.dx != 0f || signal.dy != 0f)) {
                    mouseMovedMs = clockMs
                    mouseRef = null
                    mouseRefFromPointer = true
                    laserPinnedSinceMs = Long.MIN_VALUE
                }
                // The trackpad nudges relative to the head, from wherever the ray is now.
                syncOffset(main, head)
                val wasOutside = outsideView(main)
                main.worldAim = null
                main.worldOrigin = null
                main.headOrigin = null
                main.offsetYaw += signal.dx * settings.trackpadDegPerPx
                main.offsetPitch -= signal.dy * settings.trackpadDegPerPx
                travelOutside(wasOutside, signal.dx * settings.trackpadDegPerPx, -signal.dy * settings.trackpadDegPerPx)
                clampAfterMove(wasOutside)
                anchorIfFixed(head)
                follow(main, ray(main, head), layout, out)
            }
            is PointerSignal.MoveTo -> {
                if (settings.handsSteer) aimAt(a, signal.x, signal.y, signal.timeMs, headAt, origin, dir, held = signal.held)
                follow(a, ray(a, head), layout, out)
            }
            is PointerSignal.Down -> {
                // 0.5.17: a press while the trackpad holds a window is the drop's tap; the app under it gets nothing.
                (a.grab as? Holding)?.let { g ->
                    g.pressMs = clockMs
                    g.pressMoved = 0f
                    return
                }
                // A press lands exactly where it was aimed (the lock), never on an eased aim.
                signal.at?.let { if (settings.handsSteer) aimAt(a, it.x, it.y, signal.timeMs, headAt, origin, dir, held = true) }
                a.interp.snap(clockMs)
                if (a.interp.ready) applyInterp(a)
                release(a, layout, out, cancel = true)
                if (!startStretch(a, head, layout, out)) press(a, ray(a, head), layout, out, pad = signal.pad)
            }
            is PointerSignal.Up -> {
                (a.grab as? Holding)?.let { g ->
                    holdUp(a, g, layout, out)
                    return
                }
                // 0.5.11: a hand's scroll ends where it is going, with its speed, so the app flings;
                // the recognizer's release point (where the cursor was before the fingers opened)
                // would pull the touch back and stop the fling dead.
                val scroll = (a.grab as? Touching)?.takeIf { a.hand != null && it.scrolled }
                if (scroll != null) {
                    // 0.5.45: a flick's touch is thrown on for a few frames and lifted by [throwFrame]; a stop lifts here.
                    if (endScroll(a, scroll, layout, out)) a.grab = null else release(a, layout, out, cancel = false)
                    return
                }
                signal.at?.let { if (settings.handsSteer) aimAt(a, it.x, it.y, signal.timeMs, headAt, origin, dir, held = true) }
                a.interp.snap(clockMs)
                if (a.interp.ready) applyInterp(a)
                follow(a, ray(a, head), layout, out)
                release(a, layout, out, cancel = false)
            }
            is PointerSignal.Scroll -> scroll(signal, head, layout, panelPixels, out)
            is PointerSignal.Scale -> scale(a, signal, head, layout, out)
            PointerSignal.ShowDock -> {
                release(a, layout, out, cancel = true)
                out += SpaceAction.ToggleLauncher
            }
            // 0.5.50 (Mike: the palm pinch held recentres, as on Meta Quest).
            PointerSignal.Recentre -> {
                release(a, layout, out, cancel = true)
                out += SpaceAction.Recentre
            }
            PointerSignal.SecondaryTap -> {
                // 0.5.17: no right click on a held window.
                if (main.grab is Holding) return
                release(main, layout, out, cancel = true)
                // 0.5.25: on the open launcher a right click is its long press menu.
                val r = ray(main, head)
                if (underLauncher(r)) {
                    out += SpaceAction.Launcher(LauncherInput.Secondary(r))
                    return
                }
                val hit = panelHit(r, layout, settings.pressMarginDeg)
                if (hit != null && hit.zone == PanelZone.BODY) {
                    hoverTo(main, null, out)
                    out += SpaceAction.Focus(hit.panel.id)
                    out += SpaceAction.LongPress(hit.panel.id, hit.u, hit.v)
                }
            }
            PointerSignal.Back -> {
                release(main, layout, out, cancel = true)
                out += SpaceAction.Back(PanelHitTester.nearest(ray(main, head), layout.panels)?.panel?.id)
            }
            PointerSignal.Cancel -> release(a, layout, out, cancel = true)
            is PadSignal -> pad(signal, head, layout, out)
        }
    }

    // ---- 0.5.17: the phone trackpad ----

    private fun pad(s: PadSignal, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val held = main.grab as? Holding
        when (s) {
            is PadSignal.Move -> {
                if (held != null) return holdMove(held, s, layout, out)
                // Spatial Mouse: the phone aims; the pad gives taps and swipes.
                if (padMode == PadMode.SPATIAL_MOUSE) return
                syncOffset(main, head)
                val wasOutside = outsideView(main)
                main.worldAim = null
                main.worldOrigin = null
                main.headOrigin = null
                main.offsetYaw += s.yawDeg
                main.offsetPitch += s.pitchDeg
                travelOutside(wasOutside, s.yawDeg, s.pitchDeg)
                clampAfterMove(wasOutside)
                anchorIfFixed(head)
                follow(main, ray(main, head), layout, out)
            }
            is PadSignal.TwoFinger -> if (held != null) holdTwo(held, s, layout, out)
            is PadSignal.Scroll -> when {
                // 1.1.10 (issue 35): the aim is held from the one finger scroll's start to its end, whatever it landed on.
                s.oneFinger && padMode == PadMode.SPATIAL_MOUSE && held == null -> {
                    aimHeld = s.phase != Phase.END
                    padScroll(s, head, layout, out)
                }
                held != null -> if (s.strip) {
                    if (s.phase == Phase.BEGIN) held.stripDy = 0f
                    val dy = s.dy - held.stripDy
                    held.stripDy = s.dy
                    if (dy != 0f) depth(held, dy, layout, out)
                    if (s.phase == Phase.END) depthDone(held, layout, out, "the scroll strip")
                }
                s.wheel -> stripWheel(s, head, layout, out)
                else -> padScroll(s, head, layout, out)
            }
            is PadSignal.Pinch -> if (held == null) padPinch(s, head, layout, out)
            is PadSignal.Swipe -> {
                release(main, layout, out, cancel = true)
                out += SpaceAction.ThreeFinger(s.direction)
            }
            is PadSignal.Home -> when (s.event) {
                // Home's press and its release always drop a held window.
                HomeEvent.PRESS, HomeEvent.RELEASE -> if (held != null) release(main, layout, out, cancel = false)
                HomeEvent.CLICK -> out += SpaceAction.ToggleLauncher
                HomeEvent.LONG_PRESS -> {
                    centre()
                    mouseRef = null
                    mouseRefFromPointer = false
                    laserPinnedSinceMs = Long.MIN_VALUE
                    out += SpaceAction.Note("Home held 1 s: the cursor is recentred in front")
                    follow(main, ray(main, head), layout, out)
                }
            }
            is PadSignal.Mode -> {
                if (padMode != s.mode) {
                    padMode = s.mode
                    mouseRef = null
                    mouseRefFromPointer = false
                    aimHeld = false
                    if (s.mode != PadMode.SPATIAL_MOUSE) centre()
                }
            }
            is PadSignal.Aim -> if (padMode == PadMode.SPATIAL_MOUSE) mouseAim(s, head, layout, out)
            is PadSignal.Window -> window(s, held, head, layout, out)
            is PadSignal.Curve -> curveSlide(s, held, head, layout, out)
        }
    }

    /** 0.5.17 cover screen: true while the held window is held to resize, so one finger resizes and pushes ([WindowAction.RESIZE]). */
    val holdAdjusts: Boolean get() = (main.grab as? Holding)?.adjust == true

    /** 0.5.19: true while the held window is held to curve ([WindowAction.CURVE]). */
    val holdCurves: Boolean get() = (main.grab as? Holding)?.curve == true

    /**
     * 0.5.19: [p]'s curve moved by [delta] of its range from [raw] (unsnapped), with the step and flat
     * buzzes ([CurveControl]); the new raw value.
     *
     * 0.5.28: while the whole Space is wrapped, its windows' curve is the
     * wrap's, so Curve bends the wrap (every window at once) instead of being refused with only a log
     * line. Said once a gesture on the glasses and in the log ([wrapTold]).
     */
    private fun curveBy(p: SpacePanel, raw: Float, delta: Float, l: SpaceLayout, commit: Boolean, out: MutableList<SpaceAction>, how: String): Float? {
        val w = l.wrap
        if (w != null) {
            if (!wrapTold) {
                wrapTold = true
                out += SpaceAction.Notice(WRAP_CURVE_NOTICE)
                out += SpaceAction.Note("window ${p.id} ${p.label}: the whole Space is wrapped, so Curve bends the wrap (%d %% now; the Menu's wraparound row or the trackpad's note turns it off)".fmt(kotlin.math.round(w.curve * 100).toInt()))
            }
            val c = CurveControl.move(raw, delta)
            if (c.curve != w.curve) {
                out += SpaceAction.Layout(SpaceWrap.curveTo(l, c.curve), commit = commit)
                setGuide("WRAP " + CurveControl.guide(c.curve))
            }
            c.haptic?.let {
                out += SpaceAction.Haptic(it)
                out += SpaceAction.Note("the whole Space's wrap curve %d %% (%s)".fmt(com.hayeco.flipspaces.core.spatial.PanelCurve.percent(c.curve), how))
            }
            return c.raw
        }
        val c = CurveControl.move(raw, delta)
        if (c.curve != p.curve) {
            out += SpaceAction.Layout(l.replace(p.copy(curve = c.curve)), commit = commit)
            setGuide(CurveControl.guide(c.curve))
        }
        c.haptic?.let {
            out += SpaceAction.Haptic(it)
            out += SpaceAction.Note("window ${p.id} ${p.label} curve %d %% (%s)".fmt(com.hayeco.flipspaces.core.spatial.PanelCurve.percent(c.curve), how))
        }
        return c.raw
    }

    /** 0.5.19: a slide along a Curve button: the window held to curve, else the one under the cursor (each step saved). */
    private fun curveSlide(s: PadSignal.Curve, held: Holding?, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        if (windowsLocked) {
            if (s.phase == Phase.BEGIN) lockedNotice(out, "Curve button slide")
            return
        }
        if (s.phase != Phase.UPDATE || s.delta == 0f) {
            if (s.phase == Phase.BEGIN) {
                slideRaw = null
                wrapTold = false
            }
            return
        }
        val l = latest(layout, out)
        if (held != null && held.curve) {
            val p = l.panel(held.panelId) ?: return
            curveBy(p, held.curveRaw, s.delta, l, commit = false, out, "Curve button slide")?.let { held.curveRaw = it }
            return
        }
        val p = (held?.panelId ?: windowTarget(head, l)?.id)?.let { l.panel(it) } ?: return
        val raw = slideRaw?.takeIf { it.first == p.id }?.second ?: p.curve
        curveBy(p, raw, s.delta, l, commit = held == null, out, "Curve button slide")?.let { slideRaw = p.id to it }
    }

    /** 0.5.19: a slide's unsnapped curve for the window it is on, between its updates. */
    private var slideRaw: Pair<Int, Float>? = null

    /** 0.5.28: the wrapped Space's Curve note was said for this gesture (a Curve press or a slide's start resets it). */
    private var wrapTold = false

    /** 0.5.28: the held window's curve guide; "WRAP CURVE 100 %" while the whole Space is wrapped. */
    private fun curveGuide(layout: SpaceLayout, curve: Float): String = (if (layout.wrap != null) "WRAP " else "") + CurveControl.guide(curve)

    /**
     * 0.5.17 cover screen: the window buttons ([WindowAction]). Move and Resize toggle a hold of
     * the window under the cursor (else the front one): pressed again in the same mode they drop
     * it, in the other mode they switch. Further and Closer step the held window, or the one under
     * the cursor, by [CoverWindowStep] without picking it up.
     */
    private fun window(s: PadSignal.Window, held: Holding?, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        // 1.1.15: Lock screens: the window buttons pick nothing up and step nothing.
        if (windowsLocked) return lockedNotice(out, "${s.action.name.lowercase()} window button")
        when (s.action) {
            WindowAction.MOVE, WindowAction.RESIZE, WindowAction.CURVE -> {
                val adjust = s.action == WindowAction.RESIZE
                // 0.5.19: Curve holds the window to curve it with a sideways swipe.
                val curve = s.action == WindowAction.CURVE
                val what = when {
                    curve -> "curve"
                    adjust -> "resize and push"
                    else -> "move"
                }
                if (held != null) {
                    if (held.adjust == adjust && held.curve == curve) {
                        release(main, layout, out, cancel = false)
                    } else {
                        held.adjust = adjust
                        held.curve = curve
                        latest(layout, out).panel(held.panelId)?.let { held.curveRaw = it.curve }
                        if (curve) {
                            wrapTold = false
                            setGuide(curveGuide(layout, held.curveRaw))
                        }
                        out += SpaceAction.Note("window ${held.panelId} held to $what (window button)")
                    }
                    return
                }
                val target = windowTarget(head, layout) ?: run {
                    out += SpaceAction.Note("cover ${s.action.name.lowercase()} button: no window to pick up")
                    return
                }
                release(main, layout, out, cancel = true)
                hoverTo(main, null, out)
                tweens.remove(target.id)
                val raised = latest(layout, out).raise(target.id)
                out += SpaceAction.Layout(raised, commit = false)
                out += SpaceAction.Focus(target.id)
                out += SpaceAction.Haptic(PadHaptic.PICKUP)
                out += SpaceAction.Note("window ${target.id} ${target.label} picked up to $what (window button)")
                main.grab = Holding(target.id, frames, clockMs, target.yawDeg, target.pitchDeg).also {
                    it.pickPress = false
                    it.adjust = adjust
                    it.curve = curve
                    it.curveRaw = target.curve
                }
                if (curve) {
                    wrapTold = false
                    setGuide(curveGuide(layout, target.curve))
                }
            }
            WindowAction.FURTHER, WindowAction.CLOSER -> {
                val l = latest(layout, out)
                val p = (held?.panelId ?: windowTarget(head, l)?.id)?.let { l.panel(it) } ?: return
                val further = s.action == WindowAction.FURTHER
                val d = PadWindowMath.clampDistance(p.distanceM, CoverWindowStep.distance(p.distanceM, further, s.step))
                // 0.5.18 (Further and Closer left no line): one per step.
                if (d == p.distanceM) {
                    out += SpaceAction.Note("window ${p.id} ${p.label} ${if (further) "further" else "closer"}: already at %.2f m, the limit".format(java.util.Locale.ROOT, d))
                    return
                }
                if (held != null) setGuide(if (further) "FARTHER" else "CLOSER")
                out += SpaceAction.Layout(l.replace(PadWindowMath.atDistance(p, d)), commit = held == null)
                out += SpaceAction.Note(
                    "window ${p.id} ${p.label} %s %.0f %% to %.2f m (%s)".format(
                        java.util.Locale.ROOT, if (further) "further" else "closer", kotlin.math.abs(d / p.distanceM - 1f) * 100, d, if (held != null) "held" else "under the cursor",
                    ),
                )
            }
        }
    }

    /** The window the cover buttons act on: the one under the cursor, else the front one. */
    private fun windowTarget(head: Quat, layout: SpaceLayout): SpacePanel? =
        main.hover?.panel?.id?.let { layout.panel(it) }
            ?: panelHit(ray(main, head), layout, settings.pressMarginDeg)?.panel
            ?: layout.panels.lastOrNull()

    /** The finger moved while a window is held: it orbits about 57 degrees a pad unit, or follows the laser's swipes in Spatial Mouse. */
    private fun holdMove(g: Holding, s: PadSignal.Move, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val step = kotlin.math.hypot(s.dx, s.dy)
        if (g.pickPress) g.pickMoved += step
        if (g.pressMs != null) g.pressMoved += step
        // 0.5.19: held by Curve: sideways is the curve, right more, half the pad's width its whole range.
        if (g.curve) {
            if (abs(s.dx) <= abs(s.dy) * 0.5f) return
            val l = latest(layout, out)
            val p = l.panel(g.panelId) ?: return
            curveBy(p, g.curveRaw, s.dx * CurveControl.PER_PAD_UNIT, l, commit = false, out, "held, a swipe")?.let { g.curveRaw = it }
            return
        }
        // 0.5.17 cover screen, held by Resize: one finger up and down pushes and pulls (a d^2
        // rule), sideways scales (right is larger), decided move by move.
        if (g.adjust) {
            if (PadWindowMath.mouseSwipeIsDepth(s.dx, s.dy)) return depth(g, s.dy, layout, out)
            val l = latest(layout, out)
            val p = l.panel(g.panelId) ?: return
            sizing(g, p)
            val s0 = PadWindowMath.scaleOf(p)
            val next = PadWindowMath.scaled(p, (s0 + PadWindowMath.mouseScaleStep(s.dx)) / s0, frustum)
            if (next != p) {
                setGuide(if (next.widthDeg > p.widthDeg) "LARGER" else "SMALLER")
                out += SpaceAction.Layout(l.replace(next), commit = false)
            }
            return
        }
        if (padMode == PadMode.SPATIAL_MOUSE) {
            if (clockMs - g.sinceMs < PadWindowMath.MOUSE_IGNORE_MS) return
            val l = latest(layout, out)
            val p = l.panel(g.panelId) ?: return
            sizing(g, p)
            if (PadWindowMath.mouseSwipeIsDepth(s.dx, s.dy)) {
                val d = PadWindowMath.clampDistance(p.distanceM, p.distanceM + PadWindowMath.limitStep(PadWindowMath.mouseDepthStep(s.dy), p.distanceM))
                if (d != p.distanceM) {
                    setGuide(if (d > p.distanceM) "FARTHER" else "CLOSER")
                    out += SpaceAction.Layout(l.replace(PadWindowMath.atDistance(p, d)), commit = false)
                }
            } else {
                val s0 = PadWindowMath.scaleOf(p)
                val next = PadWindowMath.scaled(p, (s0 + PadWindowMath.mouseScaleStep(s.dx)) / s0, frustum)
                if (next != p) {
                    setGuide(if (next.widthDeg > p.widthDeg) "LARGER" else "SMALLER")
                    out += SpaceAction.Layout(l.replace(next), commit = false)
                }
            }
            return
        }
        val (yaw, pitch) = PadWindowMath.orbit(g.yaw, g.pitch, s.dx, s.dy)
        g.sizing = false
        g.yaw = yaw
        g.pitch = pitch
    }

    /** 0.5.42: [g]'s window is being resized or pushed: held where it is, unsnapped, from now until it is moved. */
    private fun sizing(g: Holding, p: SpacePanel) {
        g.sizingMs = clockMs
        if (g.sizing) return
        g.sizing = true
        g.yaw = p.yawDeg
        g.pitch = p.pitchDeg
    }

    /** Two fingers while held: the same way pushes and pulls (up is further), apart or together scales. */
    private fun holdTwo(g: Holding, s: PadSignal.TwoFinger, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> {
                g.twoAx = 0f; g.twoAy = 0f; g.twoBx = 0f; g.twoBy = 0f
                g.twoPush = null
                g.twoSpread = s.spread
                g.scaleStart = null
            }
            Phase.UPDATE -> {
                g.twoAx += s.ax; g.twoAy += s.ay; g.twoBx += s.bx; g.twoBy += s.by
                if (g.twoPush == null) g.twoPush = PadWindowMath.sameWay(g.twoAx, g.twoAy, g.twoBx, g.twoBy)
                when (g.twoPush) {
                    true -> depth(g, (s.ay + s.by) / 2f, layout, out)
                    false -> {
                        val l = latest(layout, out)
                        val p = l.panel(g.panelId) ?: return
                        sizing(g, p)
                        val start = g.scaleStart ?: p.also { g.scaleStart = it }
                        val next = PadWindowMath.scaled(start, s.spread / g.twoSpread.coerceAtLeast(1e-3f), frustum)
                        if (next != p) {
                            setGuide(if (next.widthDeg > p.widthDeg) "LARGER" else "SMALLER")
                            out += SpaceAction.Layout(l.replace(next), commit = false)
                        }
                    }
                    null -> Unit
                }
            }
            Phase.END -> {
                g.twoPush = null
                g.scaleStart = null
                depthDone(g, layout, out, "two fingers")
            }
        }
    }

    /**
     * Push and pull a held window by [dy] pad units: dz = dy x 3.0 x d^2 x dt x 10, 0.3 to 15 m (0.5.26). 0.5.27: gentler
     * past 1.4 m and at most 10 % a move ([PadWindowMath.farDamping]); the window keeps its size in the room.
     */
    private fun depth(g: Holding, dy: Float, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        val l = latest(layout, out)
        val p = l.panel(g.panelId) ?: return
        sizing(g, p)
        val next = PadWindowMath.pushPull(p, dy)
        if (next.distanceM == p.distanceM) return
        setGuide(if (next.distanceM > p.distanceM) "FARTHER" else "CLOSER")
        if (!g.depthMoved) g.depthFrom = p.distanceM
        g.depthMoved = true
        out += SpaceAction.Layout(l.replace(next), commit = false)
    }

    /**
     * 0.5.27: one line for a gesture that moved the held window in depth, at its end ([how] did it). 0.5.28 (a
     * pinch at 15 m logged `pushed to 15.00 m` with no change of distance): only when the
     * distance changed by more than [DEPTH_LOG_M] over the gesture.
     */
    private fun depthDone(g: Holding, layout: SpaceLayout, out: MutableList<SpaceAction>, how: String) {
        if (!g.depthMoved) return
        g.depthMoved = false
        val p = latest(layout, out).panel(g.panelId) ?: return
        if (abs(p.distanceM - g.depthFrom) <= DEPTH_LOG_M) return
        out += SpaceAction.Note(PadWindowMath.depthLine(p, how))
    }

    private fun setGuide(text: String) {
        guide = text
        guideMs = clockMs
    }

    /** The release of a press while a window is held: the pick up's own press, or the drop's tap. */
    private fun holdUp(a: Aim, g: Holding, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        if (g.pickPress) {
            g.pickPress = false
            // Press, drag, release: a press held 0.3 s or moved drops at its release; a click keeps it held.
            if (clockMs - g.sinceMs >= PadWindowMath.DROP_TAP_MS || g.pickMoved > PadWindowMath.DROP_MOVE) release(a, layout, out, cancel = false)
            return
        }
        val pressed = g.pressMs ?: return
        g.pressMs = null
        if (PadWindowMath.dropsOnTap(clockMs - pressed, g.pressMoved, frames - g.sinceFrame)) release(a, layout, out, cancel = false)
    }

    /** A scroll (two fingers, or the strip) not on a held window: a touch drag on the panel under the cursor, 1.5 panel sizes a pad unit. */
    private fun padScroll(s: PadSignal.Scroll, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> {
                release(main, layout, out, cancel = true)
                val r = ray(main, head)
                if (underLauncher(r)) {
                    // 0.5.25: over the open launcher a two finger swipe pages it, the page following the fingers.
                    main.grab = LauncherScroll
                    out += SpaceAction.Launcher(LauncherInput.Swipe(0f, 0f, Phase.BEGIN))
                    return
                }
                val hit = panelHit(r, layout)
                // 0.5.78 (Mike: "im trying to use scroll on trackpad but its not working"): what each scroll lands on, logged.
                out += SpaceAction.Note(scrollNote(if (s.oneFinger) "one finger scroll (Spatial Mouse)" else "two finger scroll", hit, r))
                coast?.let { endCoast(it, out) }
                main.grab = when {
                    hit != null && hit.zone == PanelZone.BODY -> {
                        out += SpaceAction.Focus(hit.panel.id)
                        out += SpaceAction.Wheel(hit.panel.id, hit.u, hit.v, 0f, 0f, Phase.BEGIN, smooth = true)
                        PadWheelScroll(hit.panel.id, hit.u, hit.v)
                    }
                    // 1.1.10 (issue 35): one finger over empty space does nothing; two fingers there turn the view as before.
                    hit == null && s.oneFinger -> Button
                    hit == null -> PadTurning(0f)
                    else -> Button
                }
            }
            Phase.UPDATE, Phase.END -> when (val g = main.grab) {
                is PadWheelScroll -> {
                    if (g.axis == 0 && kotlin.math.hypot(s.dx, s.dy) >= AXIS_DECIDE_UNITS) g.axis = if (abs(s.dx) > abs(s.dy) * AXIS_BIAS) 1 else 2
                    g.x = if (g.axis == 1) s.dx * PAD_WHEEL_PX_PER_UNIT else 0f
                    // Natural, as the strip: the fingers going up show what is below.
                    g.y = if (g.axis == 2) -s.dy * PAD_WHEEL_PX_PER_UNIT else 0f
                    g.samples.addLast(Triple(clockMs, g.x, g.y))
                    while (g.samples.size > 2 && clockMs - g.samples.first().first > COAST_SAMPLE_MS) g.samples.removeFirst()
                    out += SpaceAction.Wheel(g.panelId, g.u, g.v, g.x, g.y, Phase.UPDATE, smooth = true)
                    if (s.phase == Phase.END) {
                        main.grab = null
                        startCoast(g, out)
                    }
                }
                is PadScrolling -> {
                    g.u = g.u0 + s.dx * SCROLL_DRAG
                    g.v = g.v0 - s.dy * SCROLL_DRAG
                    out += SpaceAction.Touch(g.panelId, if (s.phase == Phase.END) TouchPhase.UP else TouchPhase.MOVE, g.u, g.v)
                    if (s.phase == Phase.END) main.grab = null
                }
                is PadTurning -> {
                    val along = OffWindowScroll.padAlong(s.dx, s.dy)
                    out += SpaceAction.TurnView((along - g.lastDx) * PadWindowMath.ORBIT_DEG_PER_UNIT)
                    g.lastDx = along
                    if (s.phase == Phase.END) main.grab = null
                }
                // 0.5.25: one page width a pad unit, y down as on the panel.
                LauncherScroll -> {
                    out += SpaceAction.Launcher(LauncherInput.Swipe(s.dx * LAUNCHER_DP_PER_PAD_UNIT, -s.dy * LAUNCHER_DP_PER_PAD_UNIT, s.phase))
                    if (s.phase == Phase.END) main.grab = null
                }
                Claim, Button -> if (s.phase == Phase.END) main.grab = null
                else -> Unit
            }
        }
    }

    /** 0.5.41: the page coasting on after a two finger scroll: its wheel travel and speed (px a second) on each axis. */
    private class Coast(val panelId: Int, val u: Float, val v: Float, var x: Float, var y: Float, var vx: Float, var vy: Float)

    private var coast: Coast? = null

    /** The fingers' speed over their last [COAST_SAMPLE_MS] carries on, if fast enough to be a flick. */
    private fun startCoast(g: PadWheelScroll, out: MutableList<SpaceAction>) {
        val first = g.samples.firstOrNull()
        val last = g.samples.lastOrNull()
        val dt = if (first != null && last != null) (last.first - first.first) / 1000f else 0f
        val vx = if (dt > 0.008f) (last!!.second - first!!.second) / dt else 0f
        val vy = if (dt > 0.008f) (last!!.third - first!!.third) / dt else 0f
        if (kotlin.math.hypot(vx, vy) < COAST_MIN_PX_S) {
            out += SpaceAction.Wheel(g.panelId, g.u, g.v, g.x, g.y, Phase.END, smooth = true)
            return
        }
        coast = Coast(g.panelId, g.u, g.v, g.x, g.y, vx.coerceIn(-COAST_MAX_PX_S, COAST_MAX_PX_S), vy.coerceIn(-COAST_MAX_PX_S, COAST_MAX_PX_S))
    }

    /** Each frame: the coast moves on and slows ([COAST_TAU_S]), ending under [COAST_STOP_PX_S]. */
    private fun coastFrame(dtS: Float, out: MutableList<SpaceAction>) {
        val c = coast ?: return
        if (dtS <= 0f) return
        c.x += c.vx * dtS
        c.y += c.vy * dtS
        val k = kotlin.math.exp(-dtS / COAST_TAU_S)
        c.vx *= k
        c.vy *= k
        out += SpaceAction.Wheel(c.panelId, c.u, c.v, c.x, c.y, Phase.UPDATE, smooth = true)
        if (kotlin.math.hypot(c.vx, c.vy) < COAST_STOP_PX_S) endCoast(c, out)
    }

    private fun endCoast(c: Coast, out: MutableList<SpaceAction>) {
        out += SpaceAction.Wheel(c.panelId, c.u, c.v, c.x, c.y, Phase.END, smooth = true)
        coast = null
    }

    /**
     * 0.5.21: the thin scroll strip, not on a held window: the window under the cursor scrolls smoothly
     * with its mouse wheel, [STRIP_NOTCHES_PER_UNIT] notches a pad unit of page travel, at the cursor's
     * point (the cursor and the hover stay where they are). Natural, as the two finger scroll: the
     * finger going up shows what is below. Elsewhere nothing.
     */
    /** 0.5.78: a scroll's start for the log: the window body it scrolls, or what the cursor was on instead. */
    private fun scrollNote(kind: String, hit: PanelHit?, r: Ray): String {
        val a = Angles.of(r.dir)
        val at = "the cursor at yaw %.1f, pitch %.1f".format(java.util.Locale.ROOT, a.yawDeg, a.pitchDeg)
        return when {
            hit == null -> "$kind: no window under $at, nothing scrolled"
            hit.zone != PanelZone.BODY -> "$kind: $at is on ${hit.panel.label}'s ${hit.zone}${if (hit.frame) " (grab band)" else ""}, not its picture, nothing scrolled"
            else -> "$kind: ${hit.panel.label} (panel ${hit.panel.id}) at u %.2f, v %.2f".format(java.util.Locale.ROOT, hit.u, hit.v)
        }
    }

    private fun stripWheel(s: PadSignal.Scroll, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> {
                release(main, layout, out, cancel = true)
                val r = ray(main, head)
                // 0.5.25: over the open launcher the thin strip is its wheel (2.5 units a page).
                if (underLauncher(r)) {
                    main.grab = LauncherScroll
                    out += SpaceAction.Launcher(LauncherInput.Wheel(0f, Phase.BEGIN))
                    return
                }
                val hit = panelHit(r, layout)
                out += SpaceAction.Note(scrollNote("scroll strip", hit, r))
                main.grab = if (hit != null && hit.zone == PanelZone.BODY) {
                    out += SpaceAction.Focus(hit.panel.id)
                    out += SpaceAction.Wheel(hit.panel.id, hit.u, hit.v, 0f, 0f, Phase.BEGIN, smooth = true)
                    PadWheeling(hit.panel.id, hit.u, hit.v)
                } else {
                    Button
                }
            }
            Phase.UPDATE, Phase.END -> when (val g = main.grab) {
                is PadWheeling -> {
                    out += SpaceAction.Wheel(g.panelId, g.u, g.v, 0f, -s.dy * STRIP_NOTCHES_PER_UNIT * WHEEL_PX_PER_NOTCH, s.phase, smooth = true)
                    if (s.phase == Phase.END) main.grab = null
                }
                // The finger going up shows what is below: the next page.
                LauncherScroll -> {
                    out += SpaceAction.Launcher(LauncherInput.Wheel(s.dy * STRIP_NOTCHES_PER_UNIT, s.phase))
                    if (s.phase == Phase.END) main.grab = null
                }
                Button -> if (s.phase == Phase.END) main.grab = null
                else -> Unit
            }
        }
    }

    /** A pinch not on a held window: a zoom inside the app under the cursor; elsewhere nothing. */
    private fun padPinch(s: PadSignal.Pinch, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> {
                release(main, layout, out, cancel = true)
                val hit = panelHit(ray(main, head), layout) ?: return
                if (hit.zone != PanelZone.BODY || !settings.pinchZoomsApps) return
                hoverTo(main, null, out)
                main.grab = Zooming(hit.panel.id, hit.u, hit.v)
                out += SpaceAction.Zoom(hit.panel.id, hit.u, hit.v, s.factor, Phase.BEGIN)
            }
            Phase.UPDATE, Phase.END -> {
                val z = main.grab as? Zooming ?: return
                z.factor = s.factor
                out += SpaceAction.Zoom(z.panelId, z.u, z.v, s.factor, s.phase)
                if (s.phase == Phase.END) main.grab = null
            }
        }
    }

    /** Spatial Mouse: the phone's turn since the last recentre, from where the head looked then, is the ray in the room. */
    private fun mouseAim(s: PadSignal.Aim, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        if (!s.yawDeg.isFinite() || !s.pitchDeg.isFinite()) return
        val last = lastPhoneAim
        lastPhoneAim = Pair(s.yawDeg, s.pitchDeg)
        // Unreleased: a physical mouse moved lately; it has the pointer, and the phone's aim is left out.
        if (mouseTookOver) return
        // 1.1.10 (issue 35): while one finger scrolls, the phone's turn slides the reference instead of the dot, so the
        // dot stays on what it scrolls and, after, moves on from there with the phone (no jump).
        mouseRef?.let { r ->
            if (aimHeld && last != null) {
                val (py, pp) = AimHold.slide(r.phoneYaw, r.phonePitch, last.first, last.second, s.yawDeg, s.pitchDeg)
                mouseRef = r.copy(phoneYaw = py, phonePitch = pp)
            }
        }
        // After the mouse had the pointer, the phone's reference is where the pointer is now, so it picks up from there.
        val from = if (mouseRefFromPointer) ray(main, head).dir else head.forward
        if (mouseRef == null && mouseRefFromPointer) (main.grab as? Holding)?.mouseOffset = null
        mouseRefFromPointer = false
        var ref = mouseRef ?: Angles.of(from).let { MouseRef(s.yawDeg, s.pitchDeg, it.yawDeg, it.pitchDeg) }.also { mouseRef = it }
        var yaw = wrapDegrees(ref.headYaw + wrapDegrees(s.yawDeg - ref.phoneYaw))
        var pitch = (ref.headPitch + s.pitchDeg - ref.phonePitch).coerceIn(-89f, 89f)
        main.worldAim = Vec3.direction(yaw, pitch)
        // 0.5.32: where the phone points, seen from the head; outside the view the dot stays pinned at its edge.
        var seen = Angles.of(head.conjugate().rotate(main.worldAim!!))
        // 0.5.66 (Mike: pointing too far into empty space, the cursor got lost and could not be found): edge slip, as a
        // mouse at a screen's edge. Past the visible part of the view ([MOUSE_EDGE_MARGIN_H] and [MOUSE_EDGE_MARGIN_V] in from the edges:
        // the edge fade, and a comfortable view ending at about 1650 of 1920 px), the dot stops and the phone's
        // reference slides by the overshoot, so turning the phone back brings the dot back at once instead of only after
        // the whole overshoot is undone. Not while a window is held: the phone carries it round the room as before.
        if (settings.mouseEdgeSlip && main.grab !is Holding) {
            val hLim = (frustum.horizontalFovDeg / 2f - MOUSE_EDGE_MARGIN_H).coerceAtLeast(1f)
            val vLim = (frustum.verticalFovDeg / 2f - MOUSE_EDGE_MARGIN_V).coerceAtLeast(1f)
            val overYaw = seen.yawDeg - seen.yawDeg.coerceIn(-hLim, hLim)
            val overPitch = seen.pitchDeg - seen.pitchDeg.coerceIn(-vLim, vLim)
            if (overYaw != 0f || overPitch != 0f) {
                ref = ref.copy(phoneYaw = wrapDegrees(ref.phoneYaw + overYaw), phonePitch = ref.phonePitch + overPitch)
                mouseRef = ref
                yaw = wrapDegrees(ref.headYaw + wrapDegrees(s.yawDeg - ref.phoneYaw))
                pitch = (ref.headPitch + s.pitchDeg - ref.phonePitch).coerceIn(-89f, 89f)
                main.worldAim = Vec3.direction(yaw, pitch)
                seen = Angles.of(head.conjugate().rotate(main.worldAim!!))
            }
        }
        val outside = kotlin.math.abs(seen.yawDeg) > frustum.horizontalFovDeg / 2f || kotlin.math.abs(seen.pitchDeg) > frustum.verticalFovDeg / 2f
        laserPinnedSinceMs = if (!outside) Long.MIN_VALUE else if (laserPinnedSinceMs == Long.MIN_VALUE) clockMs else laserPinnedSinceMs
        main.worldOrigin = null
        main.headOrigin = null
        (main.grab as? Holding)?.let { g ->
            val p = latest(layout, out).panel(g.panelId) ?: return@let
            // 0.5.42: while the pad resizes it, the phone's aim does not move it; after, the aim takes it on from there.
            if (g.sizing) {
                g.mouseOffset = null
                if (clockMs - g.sizingMs < SIZING_AIM_HOLD_MS) return@let
                g.sizing = false
            }
            val off = g.mouseOffset ?: Pair(wrapDegrees(p.yawDeg - yaw), p.pitchDeg - pitch).also { g.mouseOffset = it }
            g.yaw = wrapDegrees(yaw + off.first)
            g.pitch = (pitch + off.second).coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH)
        }
        follow(main, ray(main, head), layout, out)
    }

    /**
     * Aims [a] at glasses pixel ([inputX], [inputY]) as seen from the head at [timeMs] when that
     * pose is known (a world ray), else from the head as it is now. A hand ray's own [dir] and
     * [origin] (head frame at [timeMs]) take the place of the pixel's ray from the head centre.
     */
    private fun aimAt(
        a: Aim,
        inputX: Float,
        inputY: Float,
        timeMs: Long?,
        headAt: ((Long) -> Quat?)?,
        origin: Vec3? = null,
        dir: Vec3? = null,
        held: Boolean = false,
    ) {
        val headDir = dir ?: run {
            val (x, y) = viewport?.toSurface(inputX, inputY) ?: (inputX to inputY)
            frustum.rayThrough(x, y)
        }
        val then = if (timeMs != null && headAt != null) headAt(timeMs - settings.handCameraDelayMs) else null
        if (then != null) {
            a.worldAim = then.rotate(headDir).normalized()
            a.worldOrigin = origin?.let { then.rotate(it) }
            a.headOrigin = null
            interpolate(a, a.worldAim!!, world = true, timeMs, held)
            return
        }
        a.worldAim = null
        a.worldOrigin = null
        a.headOrigin = origin
        val angles = Angles.of(headDir)
        a.offsetYaw = angles.yawDeg
        a.offsetPitch = angles.pitchDeg
        clampOffset(a)
        interpolate(a, headDir, world = false, timeMs, held)
    }

    /**
     * 0.5.2: feeds a hand's new aim to its interpolator and shows the eased aim. A sample with no
     * hand time (tests, replays without it) or a held one is shown exactly, as before 0.5.2.
     */
    private fun interpolate(a: Aim, dir: Vec3, world: Boolean, timeMs: Long?, held: Boolean) {
        if (a.hand == null || !settings.interpolateHands) return
        if (a.interpWorld != world) a.interp.reset()
        a.interpWorld = world
        a.interp.sample(dir, timeMs ?: clockMs, clockMs, held = held || timeMs == null)
        applyInterp(a)
    }

    private fun clampOffset(a: Aim) {
        val h = frustum.horizontalFovDeg / 2f
        val v = frustum.verticalFovDeg / 2f
        a.offsetYaw = a.offsetYaw.coerceIn(-h, h)
        a.offsetPitch = a.offsetPitch.coerceIn(-v, v)
    }

    /** True when the launcher (open, or its home button) is in front along [ray]: it takes the ray. */
    private fun underLauncher(ray: Ray): Boolean = pressFilter?.invoke(ray) == true

    /** The frontmost panel along [ray], or null when the launcher is in front of it. */
    private fun panelHit(ray: Ray, layout: SpaceLayout, marginDeg: Float = 0f): PanelHit? =
        if (underLauncher(ray)) null else WindowLock.view(PanelHitTester.nearest(ray, if (hiddenPanels.isEmpty()) layout.panels else layout.panels.filter { it.id !in hiddenPanels }, marginDeg), windowsLocked)

    /** 0.5.37: windows that take no presses now (the others while one is Immersive). */
    @Volatile var hiddenPanels: Set<Int> = emptySet()

    /**
     * 0.5.0: [a] pressed on the panel the other hand holds: both hands now stretch it, the
     * other hand's touch is cancelled (its move or resize kept), and the panel scales by how far
     * apart the two rays point now over at the start.
     */
    private fun startStretch(a: Aim, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>): Boolean {
        // 1.1.15: Lock screens: the second hand's pinch is a press of its own, never a stretch.
        if (windowsLocked) return false
        val hand = a.hand ?: return false
        val other = hands.entries.firstOrNull { it.key != hand }?.value ?: return false
        val held = grabbedPanelId(other) ?: return false
        if (other.grab is Stretching || other.grab is Zooming || other.grab is Scaling) return false
        val ray = ray(a, head)
        if (underLauncher(ray)) return false
        val hit = PanelHitTester.nearest(ray, layout.panels, settings.pressMarginDeg) ?: return false
        if (hit.panel.id != held) return false
        val otherDir = ray(other, head).dir
        val startDeg = StretchMath.startDeg(ray.dir, otherDir) ?: return false
        release(other, layout, out, cancel = true)
        val panel = (out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.layout ?: layout).panel(held) ?: return false
        // 0.5.9: the panel keeps its place relative to the hands' midpoint, as on Quest and Android XR.
        val mid = StretchMath.midpoint(ray.dir, otherDir)?.let { Angles.of(it) }
        // 0.5.10: and its distance follows both hands pushing away or pulling in.
        val depth = TwoHandDepth().takeIf { settings.twoHandDepth && it.start(a.size, other.size, clockMs) }
        stretch = if (settings.twoHandMove && mid != null) {
            Stretch(held, panel, startDeg, dYaw = wrapDegrees(panel.yawDeg - mid.yawDeg), dPitch = panel.pitchDeg - mid.pitchDeg, depth = depth)
        } else {
            Stretch(held, panel, startDeg, depth = depth)
        }
        stretchMoves = settings.twoHandMove && mid != null
        a.grab = Stretching(held)
        other.grab = Stretching(held)
        a.hover = hit
        out += SpaceAction.Focus(held)
        return true
    }

    /**
     * 0.5.2: ends the two hand stretch for both hands: one commit of [latest] when [commit] (the
     * panel is still there), never a second one when the other hand lets go or leaves.
     */
    private fun endStretch(latest: SpaceLayout, out: MutableList<SpaceAction>, commit: Boolean) {
        if (commit) out += SpaceAction.Layout(latest, commit = true)
        stretch = null
        hands.values.filter { it.grab is Stretching }.forEach { it.grab = null }
    }

    private fun press(a: Aim, ray: Ray, layout: SpaceLayout, out: MutableList<SpaceAction>, pad: Boolean = false) {
        if (pressFilter?.invoke(ray) == true) {
            a.grab = Claim
            a.hover = null
            out += SpaceAction.Claimed(ray, a.hand)
            return
        }
        val hit = WindowLock.view(PanelHitTester.nearest(ray, layout.panels, settings.pressMarginDeg), windowsLocked)
        out += SpaceAction.Pressed(PressReport.of(ray, hit, layout.panels))
        if (hit == null) return
        // The pointer that pressed is the one whose hover the apps get from now on.
        val wasOwner = hoverOwner
        if (wasOwner != null && wasOwner !== a) hoverTo(wasOwner, null, out)
        hoverOwner = a
        hoverTo(a, null, out)
        a.hover = hit
        val raised = layout.raise(hit.panel.id)
        if (raised != layout) out += SpaceAction.Layout(raised, commit = false)
        out += SpaceAction.Focus(hit.panel.id)
        a.grab = when (hit.zone) {
            PanelZone.BODY -> {
                // One touch stream per panel: another pointer's touch there ends first.
                (listOf(main) + hands.values).filter { it !== a && (it.grab as? Touching)?.panelId == hit.panel.id }
                    .forEach { release(it, layout, out, cancel = true) }
                liftThrow(hit.panel.id, out)
                out += SpaceAction.Touch(hit.panel.id, TouchPhase.DOWN, hit.u, hit.v)
                Touching(hit.panel.id, hit.u, hit.v).also {
                    if (a.hand != null && settings.clickFreezeMs > 0) {
                        it.freezeDir = ray.dir.normalized()
                        it.freezeUntilMs = clockMs + settings.clickFreezeMs
                    }
                }
            }
            // 1.1.15: Lock screens: the Move pill moves nothing (the grab band was taken out above).
            PanelZone.TITLE_BAR -> if (windowsLocked) {
                lockedNotice(out, "window ${hit.panel.id} ${hit.panel.label}'s Move pill")
                Button
            } else if (pad && a === main) {
                // 0.5.17: the trackpad picks the window up, with a 1 ms buzz.
                tweens.remove(hit.panel.id)
                out += SpaceAction.Haptic(PadHaptic.PICKUP)
                out += SpaceAction.Note("window ${hit.panel.id} ${hit.panel.label} picked up from the trackpad")
                Holding(hit.panel.id, frames, clockMs, hit.panel.yawDeg, hit.panel.pitchDeg).also { h ->
                    // Spatial Mouse: the window keeps where it was from the phone's ray at the pick up.
                    val r = Angles.of(ray.dir)
                    h.mouseOffset = Pair(wrapDegrees(hit.panel.yawDeg - r.yawDeg), hit.panel.pitchDeg - r.pitchDeg)
                }
            } else {
                val angles = Angles.of(ray.dir)
                Moving(hit.panel.id, wrapDegrees(hit.panel.yawDeg - angles.yawDeg), hit.panel.pitchDeg - angles.pitchDeg)
            }
            // 1.1.15: the handle waits for a clear drag ([CornerDrag]); until then the press is a click on the app there.
            PanelZone.CORNER -> {
                val click = CornerDrag.clickAt(hit.u, hit.v, PanelHitTester.FRAME_INSET_DEG / hit.panel.widthDeg, PanelHitTester.FRAME_INSET_DEG / hit.panel.heightDeg)
                if (click != null) {
                    liftThrow(hit.panel.id, out)
                    out += SpaceAction.Touch(hit.panel.id, TouchPhase.DOWN, click.first, click.second)
                }
                CornerPress(hit.panel.id, ray.dir.normalized(), clockMs, click?.first ?: hit.u, click?.second ?: hit.v, touched = click != null)
            }
            PanelZone.BACK -> {
                out += SpaceAction.Back(hit.panel.id)
                Button
            }
            PanelZone.REFRESH -> {
                out += SpaceAction.RefreshPanel(hit.panel.id)
                Button
            }
            PanelZone.SWITCH_APP -> {
                out += SpaceAction.SwitchAppPanel(hit.panel.id)
                Button
            }
            // 0.5.17: the window's Reset (in front, 0.2 m below the eyes, a 0.5 s tween) and Close.
            PanelZone.RESET -> if (windowsLocked) {
                lockedNotice(out, "window ${hit.panel.id} ${hit.panel.label}'s Reset")
                Button
            } else {
                val p = latest(layout, out).panel(hit.panel.id) ?: hit.panel
                tweens[p.id] = PanelTween(p, PadWindowMath.resetPose(p, Angles.of(lastHead.forward).yawDeg), clockMs)
                out += SpaceAction.Note("window ${p.id} ${p.label} reset in front")
                Button
            }
            PanelZone.CLOSE -> {
                out += SpaceAction.ClosePanel(hit.panel.id)
                Button
            }
            // 0.5.21: Wide view under the window ([WideView]).
            PanelZone.WIDE_VIEW -> {
                out += SpaceAction.WideViewPanel(hit.panel.id)
                Button
            }
            // 0.5.24: Fit video, next to Wide view ([VideoFit]).
            PanelZone.FIT_VIDEO -> {
                out += SpaceAction.FitVideoPanel(hit.panel.id)
                Button
            }
            // 0.5.37: Immersive, left of Wide view.
            PanelZone.IMMERSIVE -> {
                out += SpaceAction.ImmersivePanel(hit.panel.id)
                Button
            }
        }
    }

    /**
     * Mouse hover for the panel content under [a]'s ray: an exit from the panel it leaves, an
     * enter on the one it reaches, and moves while it stays (only when it moved a little, since
     * [frame] runs every frame). Only the [hoverOwner] (or, with none, the first pointer over a
     * panel's content) sends them.
     */
    private fun hoverTo(a: Aim, hit: PanelHit?, out: MutableList<SpaceAction>) {
        val owner = hoverOwner
        if (owner != null && owner !== a) return
        val over = hit?.takeIf { settings.hoverEvents && it.zone == PanelZone.BODY }
        val was = hovering
        if (was != null && (over == null || over.panel.id != was.panelId)) {
            out += SpaceAction.Hover(was.panelId, HoverPhase.EXIT, was.u, was.v)
            hovering = null
            // A hand that leaves the panel lets the next pointer over one hover it (0.5.0).
            if (a.hand != null) hoverOwner = null
        }
        if (over == null) return
        if (a.hand != null) hoverOwner = a
        val now = hovering
        if (now == null) {
            hovering = Touching(over.panel.id, over.u, over.v)
            out += SpaceAction.Hover(over.panel.id, HoverPhase.ENTER, over.u, over.v)
        } else if (abs(over.u - now.u) > HOVER_STEP || abs(over.v - now.v) > HOVER_STEP) {
            now.u = over.u
            now.v = over.v
            out += SpaceAction.Hover(over.panel.id, HoverPhase.MOVE, over.u, over.v)
        }
    }

    private fun follow(a: Aim, ray: Ray, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (val g = a.grab) {
            is Touching -> {
                val panel = layout.panel(g.panelId) ?: return
                // 0.5.19: along the window's surface, curved or flat ([PanelCurve.intersect]).
                val s = PanelCurve.intersect(ray, panel) ?: return
                var u = s.x + 0.5f
                var v = 0.5f - s.y
                // 0.5.12: the click freeze. Held while young and close to the press's ray.
                val held = g.freezeDir
                if (held != null) {
                    val dir = ray.dir.normalized()
                    val moved = angleDeg(held, dir)
                    g.heldRays.addLast(dir to clockMs)
                    while (g.heldRays.size > 2 && g.heldRays.first().second < clockMs - FREEZE_SPEED_MS * 2) g.heldRays.removeFirst()
                    if (clockMs < g.freezeUntilMs && moved <= settings.clickFreezeDeg) {
                        a.hover = PanelHitTester.hit(ray, panel)
                        return
                    }
                    g.freezeDir = null
                    // Ended by time with the hand settled: the wobble so far is absorbed. Ended by a
                    // real move, or with the ray still moving (a slow drag): the touch goes where the ray is.
                    val then = g.heldRays.firstOrNull { it.second <= clockMs - FREEZE_SPEED_MS }
                    val speed = if (then == null) 0f else angleDeg(then.first, dir) * 1000f / (clockMs - then.second).coerceAtLeast(1L)
                    if (moved <= settings.clickFreezeDeg && speed < FREEZE_SETTLED_DEG_S) {
                        g.shiftU = u - g.u
                        g.shiftV = v - g.v
                    }
                    g.heldRays.clear()
                }
                u -= g.shiftU
                v -= g.shiftV
                // 0.5.46 (Mike, vc66: "flick-scroll is still bad"; his upward flicks carried the hand off the top of the
                // camera): once a hand's touch is a scroll, it moves [HAND_SCROLL_GAIN] times the ray's travel from where
                // the scroll began, so a page scrolls further for a shorter, quicker hand move. Before that (a tap, the
                // click freeze, a slider's first move) it follows 1:1.
                if (a.hand != null) {
                    val from = g.scrollFrom
                    if (from != null) {
                        u = from[2] + (u - from[0]) * HAND_SCROLL_GAIN
                        v = from[3] + (v - from[1]) * HAND_SCROLL_GAIN
                    }
                }
                if (u != g.u || v != g.v) {
                    val rawU = if (g.scrollFrom == null) u else null
                    val rawV = if (g.scrollFrom == null) v else null
                    g.u = u
                    g.v = v
                    out += SpaceAction.Touch(g.panelId, TouchPhase.MOVE, u, v)
                    if (a.hand != null) {
                        noteScrollMove(a, g, panel, out)
                        // The scroll began on this move: the gain counts from here (ray and touch alike).
                        if (g.scrolled && g.scrollFrom == null && rawU != null && rawV != null) g.scrollFrom = floatArrayOf(rawU, rawV, u, v)
                    }
                }
                a.hover = PanelHitTester.hit(ray, panel)
            }
            is Moving -> {
                val panel = layout.panel(g.panelId) ?: return
                val angles = Angles.of(ray.dir)
                val moved = SpaceLayout.moveTo(panel, Vec3.direction(angles.yawDeg + g.dYaw, (angles.pitchDeg + g.dPitch).coerceIn(-89f, 89f)))
                if (moved != panel) out += SpaceAction.Layout(layout.replace(moved), commit = false)
                a.hover = null
            }
            // 1.1.15: a press on the handle turns into a resize once held and dragged far enough ([CornerDrag]); the app's
            // touch under it is cancelled then. Until then nothing moves, and the app's button under it keeps its press.
            is CornerPress -> {
                val panel = layout.panel(g.panelId) ?: return
                val moved = angleDeg(g.dir, ray.dir.normalized())
                if (windowsLocked || !CornerDrag.starts(clockMs - g.sinceMs, moved)) {
                    a.hover = PanelHitTester.hit(ray, panel)
                    return
                }
                if (g.touched) out += SpaceAction.Touch(g.panelId, TouchPhase.CANCEL, g.u, g.v)
                out += SpaceAction.Note("window ${panel.id} ${panel.label}: resize handle dragged %.1f deg after %d ms, resizing".fmt(moved, clockMs - g.sinceMs))
                a.grab = Resizing(g.panelId)
                follow(a, ray, layout, out)
            }
            is Resizing -> {
                val panel = layout.panel(g.panelId) ?: return
                val resized = SpaceLayout.resizeTo(panel, ray)
                if (resized != panel) out += SpaceAction.Layout(layout.replace(resized), commit = false)
                a.hover = null
            }
            // 0.5.17: the reticle hides while a window is held; nothing under it is hovered.
            is Holding -> a.hover = null
            is Stretching -> {
                a.hover = null
                val s = stretch
                // 0.5.2: a stretch left over with no stretch state, or whose panel has gone
                // (closed or released while held), ends here instead of scaling nothing.
                if (s == null || s.panelId != g.panelId || layout.panel(g.panelId) == null) {
                    endStretch(layout, out, commit = s != null && layout.panel(g.panelId) != null)
                    return
                }
                val holding = hands.values.filter { it.grab is Stretching }
                val rays = holding.map { ray(it, lastHead) }
                if (rays.size < 2) return
                // 0.5.10: the push first, so a push's narrower rays are not read as a shrink.
                val depth = s.depth
                depth?.update(holding[0].size, holding[1].size, clockMs)
                val apart = depth?.separationFactor() ?: 1f
                // A degenerate or NaN ray (a zero direction from a lost landmark) keeps the last size.
                val factor = StretchMath.factor(rays[0].dir, rays[1].dir, s.startDeg)?.let { (it * apart).coerceIn(StretchMath.MIN_FACTOR, StretchMath.MAX_FACTOR) } ?: s.factor
                val sized = SpaceLayout.scale(s.start, factor)
                val scaled = if (depth != null) TwoHandDepth.atDistance(sized, depth.distance(s.start.distanceM)) else sized
                // 0.5.9: and its centre follows the hands' midpoint (it keeps facing the user).
                val next = if (stretchMoves) {
                    StretchMath.midpoint(rays[0].dir, rays[1].dir)?.let { mid ->
                        val a = Angles.of(mid)
                        SpaceLayout.moveTo(scaled, Vec3.direction(a.yawDeg + s.dYaw, (a.pitchDeg + s.dPitch).coerceIn(-89f, 89f)))
                    } ?: scaled.copy(yawDeg = layout.panel(g.panelId)?.yawDeg ?: scaled.yawDeg, pitchDeg = layout.panel(g.panelId)?.pitchDeg ?: scaled.pitchDeg)
                } else {
                    scaled
                }
                val current = layout.panel(g.panelId) ?: return
                if (abs(factor - s.factor) < 0.002f && abs(wrapDegrees(next.yawDeg - current.yawDeg)) < MOVE_STEP_DEG &&
                    abs(next.pitchDeg - current.pitchDeg) < MOVE_STEP_DEG && abs(next.distanceM - current.distanceM) < 0.002f
                ) {
                    return
                }
                s.factor = factor
                out += SpaceAction.Layout(layout.replace(next), commit = false)
            }
            // 0.5.25: a press the launcher took follows its ray (a drag pages it, a hold is its long press).
            Claim -> {
                a.hover = panelHit(ray, layout)
                out += SpaceAction.Launcher(LauncherInput.Move(ray), a.hand)
            }
            null -> {
                // A hand aiming in the Space takes hover from the head gaze (0.5.0).
                if (a === main && hands.isNotEmpty()) {
                    a.hover = null
                    if (hoverOwner == null || hoverOwner === main) {
                        hoverOwner = null
                        hoverTo(main, null, out)
                    }
                    return
                }
                // Under the open launcher (or its home button) the panel behind is neither hot
                // nor hovered (0.4.2: its title bar used to light up through the tiles).
                a.hover = panelHit(ray, layout)
                hoverTo(a, a.hover, out)
            }
            else -> a.hover = panelHit(ray, layout)
        }
    }

    /**
     * 0.5.11: a hand's touch on a panel's content is a scroll once it has moved [SCROLL_SLOP_DEG]
     * of the panel from where it went down (more than Android's touch slop, so the app scrolls and
     * never takes it for a tap). The touch follows the hand's ray 1:1 on the panel; the recent
     * moves are kept for the fling at the release.
     */
    private fun noteScrollMove(a: Aim, g: Touching, panel: SpacePanel, out: MutableList<SpaceAction>) {
        g.moves.addLast(Triple(g.u, g.v, clockMs))
        while (g.moves.size > 2 && g.moves.first().third < clockMs - FLING_WINDOW_MS - FLING_SPAN_MS) g.moves.removeFirst()
        if (!g.scrolled) {
            val du = (g.u - g.startU) * panel.widthDeg
            val dv = (g.v - g.startV) * panel.heightDeg
            if (du * du + dv * dv >= SCROLL_SLOP_DEG * SCROLL_SLOP_DEG) {
                g.scrolled = true
                out += SpaceAction.Note("hand scroll on panel ${g.panelId} (${a.hand?.label} hand): the touch follows the hand %.1f times, %.1f deg from where it went down".fmt(HAND_SCROLL_GAIN, sqrt(du * du + dv * dv)))
            }
        }
    }

    /**
     * 0.5.11: the scroll's speed, panel units a ms; zero when it was not moving.
     *
     * 0.5.45 (Mike, vc65: "scrolling web pages using hand tracking doesn't work well at all"; his flick released "at
     * 6 deg/s" after the hand moved 20 degrees in under a second): the fingers open over the last frames before the
     * release and the hand slows as they do, so 0.5.11's last 80 ms read a flick as a stop. Now the fastest
     * [FLING_SPAN_MS] stretch of the last [FLING_WINDOW_MS], as long as the newest moves still go its way, and a scroll
     * counts as stopped only after [FLING_STALE_MS] without a move.
     */
    private fun scrollVelocity(g: Touching): Pair<Float, Float> {
        val last = g.moves.lastOrNull() ?: return 0f to 0f
        if (clockMs - last.third > FLING_STALE_MS) return 0f to 0f
        val moves = g.moves.filter { it.third >= last.third - FLING_WINDOW_MS }
        var best = 0f to 0f
        var bestSq = 0f
        for (i in moves.indices) {
            val a = moves[i]
            val b = moves.lastOrNull { it.third <= a.third + FLING_SPAN_MS && it.third > a.third } ?: continue
            val dt = (b.third - a.third).toFloat()
            if (dt < FLING_SPAN_MS / 2f) continue
            val vu = (b.first - a.first) / dt
            val vv = (b.second - a.second) / dt
            if (vu * vu + vv * vv > bestSq) {
                bestSq = vu * vu + vv * vv
                best = vu to vv
            }
        }
        // The newest stretch must not run against it (a hand that came back before letting go).
        val tail = moves.firstOrNull { it.third >= last.third - FLING_SPAN_MS }
        if (tail != null && tail !== last) {
            val du = last.first - tail.first
            val dv = last.second - tail.second
            if (du * best.first + dv * best.second < 0f) return 0f to 0f
        }
        return best
    }

    /**
     * The scroll's release. 0.5.11 sent one last move along its speed, then the lift. 0.5.45: the app's own velocity
     * tracker reads the touch's last 100 ms, which were the hand slowing as the fingers opened, so a flick lifted
     * slow and barely flung. A moving scroll is now thrown on along its speed ([scrollVelocity]) for [THROW_MS] of
     * frames ([throwFrame]) and lifted there, so the app flings at the speed the hand had. True when thrown (the lift
     * comes later); false when it had stopped and the caller lifts it now.
     */
    private fun endScroll(a: Aim, g: Touching, layout: SpaceLayout, out: MutableList<SpaceAction>): Boolean {
        val panel = layout.panel(g.panelId)
        val (vu, vv) = scrollVelocity(g)
        val speed = if (panel == null) 0f else sqrt((vu * panel.widthDeg).let { it * it } + (vv * panel.heightDeg).let { it * it }) * 1000f
        out += SpaceAction.Note(
            "hand scroll on panel ${g.panelId} (${a.hand?.label} hand) released at %.0f deg/s: ".fmt(speed) +
                if (speed > 0f) "thrown on along its speed for %d ms, so the app flings".fmt(THROW_MS) else "the hand had stopped, no fling",
        )
        if (speed <= 0f) return false
        liftThrow(g.panelId, out)
        throws += Throw(g.panelId, g.u, g.v, vu, vv, clockMs, clockMs + THROW_MS)
        return true
    }

    /** 0.5.45: a released scroll's touch carried on along its speed ([endScroll]) until [endMs], then lifted. */
    private class Throw(val panelId: Int, var u: Float, var v: Float, val vu: Float, val vv: Float, var lastMs: Long, val endMs: Long)

    private val throws = mutableListOf<Throw>()

    /** 0.5.45: a new touch on [panelId] lifts its thrown one first. */
    private fun liftThrow(panelId: Int, out: MutableList<SpaceAction>) {
        throws.removeAll { t -> (t.panelId == panelId).also { if (it) out += SpaceAction.Touch(t.panelId, TouchPhase.UP, t.u, t.v) } }
    }

    /** 0.5.45: each frame, the thrown touches move on along their speed; those past their time lift. */
    private fun throwFrame(out: MutableList<SpaceAction>) {
        if (throws.isEmpty()) return
        val it = throws.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val to = minOf(clockMs, t.endMs)
            val dt = (to - t.lastMs).toFloat()
            if (dt > 0f) {
                t.u += t.vu * dt
                t.v += t.vv * dt
                t.lastMs = to
                out += SpaceAction.Touch(t.panelId, TouchPhase.MOVE, t.u, t.v)
            }
            if (clockMs >= t.endMs) {
                out += SpaceAction.Touch(t.panelId, TouchPhase.UP, t.u, t.v)
                it.remove()
            }
        }
    }

    private fun release(a: Aim, layout: SpaceLayout, out: MutableList<SpaceAction>, cancel: Boolean) {
        when (val g = a.grab) {
            is Touching -> out += SpaceAction.Touch(g.panelId, if (cancel) TouchPhase.CANCEL else TouchPhase.UP, g.u, g.v)
            // 1.1.15: a handle press let go before it was a drag: the click on the app ends where it went down.
            is CornerPress -> if (g.touched) out += SpaceAction.Touch(g.panelId, if (cancel) TouchPhase.CANCEL else TouchPhase.UP, g.u, g.v)
            is Moving, is Resizing, is Scaling -> {
                val latest = out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.layout ?: layout
                out += SpaceAction.Layout(latest, commit = true)
            }
            is Stretching -> {
                // Either hand opening (or leaving the camera) ends the stretch for both, once.
                val latest = out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.layout ?: layout
                endStretch(latest, out, commit = stretch != null && latest.panel(g.panelId) != null)
            }
            is Scrolling -> out += SpaceAction.Touch(g.panelId, TouchPhase.CANCEL, g.u, g.v)
            is PadScrolling -> out += SpaceAction.Touch(g.panelId, if (cancel) TouchPhase.CANCEL else TouchPhase.UP, g.u, g.v)
            // 0.5.21: a wheel has nothing held down to let go of.
            is PadWheeling -> Unit
            is PadWheelScroll -> out += SpaceAction.Wheel(g.panelId, g.u, g.v, g.x, g.y, Phase.END, smooth = true)
            is Holding -> {
                // The drop: where it was going, saved; the cursor comes back on the window.
                val l = latest(layout, out)
                val p = l.panel(g.panelId)
                if (p != null) {
                    // 0.5.39 (Mike, vc59: "when I release the window it doesn't actually snap to where it showed it was going
                    // to snap"): 0.5.37 dropped it where the finger sent it, not at the snap it was easing to. Now the snap.
                    val target = if (snapWindows && !cancel && !g.sizing) WindowSnap.snap(p, g.yaw, g.pitch, l.panels, sticky = snappedTo) else WindowSnap.Snapped(g.yaw, g.pitch, null)
                    snappedTo = null
                    // 0.5.84: dropped in a space it fits: that size, and the app laid out anew for it; else its own size.
                    val fitW = target.fitWidthDeg
                    fitPreview = null
                    val placed = if (fitW != null) {
                        p.copy(yawDeg = target.yawDeg, pitchDeg = target.pitchDeg, widthDeg = fitW, heightDeg = target.fitHeightDeg ?: p.heightDeg).sizedByUser()
                    } else {
                        p.copy(yawDeg = target.yawDeg, pitchDeg = target.pitchDeg)
                    }
                    out += SpaceAction.Layout(l.replace(placed), commit = true)
                    if (a === main && padMode != PadMode.SPATIAL_MOUSE) {
                        val at = Angles.of(lastHead.conjugate().rotate(placed.centreDir))
                        main.worldAim = null
                        main.worldOrigin = null
                        main.headOrigin = null
                        main.offsetYaw = at.yawDeg
                        main.offsetPitch = at.pitchDeg
                        clampOffset(main)
                    }
                    out += SpaceAction.Note("window ${p.id} ${p.label} dropped at yaw %.1f, pitch %.1f, %.2f m, curve %.0f %%%s".fmt(placed.yawDeg, placed.pitchDeg, p.distanceM, p.curve * 100, target.toId?.let { ", snapped to window $it" } ?: ""))
                }
            }
            is PadTurning -> Unit
            // Lift both fingers where they are: an app zoom ends, it is not undone.
            is Zooming -> out += SpaceAction.Zoom(g.panelId, g.u, g.v, g.factor, Phase.END)
            // 0.5.25: the launcher's press ends: a tap, the end of a drag, or nothing after its long press.
            Claim -> out += SpaceAction.Launcher(LauncherInput.Release(cancel), a.hand)
            is Turning, is Wheeling, LauncherScroll, Button, null -> Unit
        }
        a.grab = null
    }

    private fun scroll(
        s: PointerSignal.Scroll,
        head: Quat,
        layout: SpaceLayout,
        panelPixels: (Int) -> Pair<Int, Int>,
        out: MutableList<SpaceAction>,
    ) {
        when (s.phase) {
            Phase.BEGIN -> {
                release(main, layout, out, cancel = true)
                val r = ray(main, head)
                if (underLauncher(r)) {
                    // The open launcher is in front: the panels behind it neither scroll nor turn.
                    // 0.5.25: the swipe pages the launcher instead, a glasses pixel a dp.
                    main.grab = LauncherScroll
                    out += SpaceAction.Launcher(LauncherInput.Swipe(0f, 0f, Phase.BEGIN))
                    return
                }
                val hit = panelHit(r, layout)
                main.grab = if (hit != null && hit.zone == PanelZone.BODY && settings.wheelScroll) {
                    out += SpaceAction.Wheel(hit.panel.id, hit.u, hit.v, 0f, 0f, Phase.BEGIN)
                    Wheeling(hit.panel.id, hit.u, hit.v)
                } else if (hit != null && hit.zone == PanelZone.BODY) {
                    val (w, h) = panelPixels(hit.panel.id)
                    liftThrow(hit.panel.id, out)
                    out += SpaceAction.Touch(hit.panel.id, TouchPhase.DOWN, hit.u, hit.v)
                    Scrolling(hit.panel.id, hit.u, hit.v, w, h)
                } else {
                    Turning(0f)
                }
            }
            Phase.UPDATE, Phase.END -> {
                when (val g = main.grab) {
                    is Scrolling -> {
                        val u = g.u + s.dx * settings.scrollGain / g.pxW
                        val v = g.v + s.dy * settings.scrollGain / g.pxH
                        out += SpaceAction.Touch(g.panelId, if (s.phase == Phase.END) TouchPhase.UP else TouchPhase.MOVE, u, v)
                        if (s.phase == Phase.END) main.grab = null
                    }
                    is Wheeling -> {
                        out += SpaceAction.Wheel(g.panelId, g.u, g.v, s.dx, s.dy, s.phase)
                        if (s.phase == Phase.END) main.grab = null
                    }
                    is Turning -> {
                        val along = OffWindowScroll.along(s.dx, s.dy)
                        out += SpaceAction.TurnView((along - g.lastDx) * settings.turnDegPerPx)
                        g.lastDx = along
                        if (s.phase == Phase.END) main.grab = null
                    }
                    LauncherScroll -> {
                        out += SpaceAction.Launcher(LauncherInput.Swipe(s.dx, s.dy, s.phase))
                        if (s.phase == Phase.END) main.grab = null
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun scale(a: Aim, s: PointerSignal.Scale, head: Quat, layout: SpaceLayout, out: MutableList<SpaceAction>) {
        when (s.phase) {
            Phase.BEGIN -> {
                release(a, layout, out, cancel = true)
                val hit = panelHit(ray(a, head), layout) ?: return
                if (hit.zone == PanelZone.BODY && !s.twoHands && settings.pinchZoomsApps) {
                    hoverTo(a, null, out)
                    a.grab = Zooming(hit.panel.id, hit.u, hit.v)
                    out += SpaceAction.Zoom(hit.panel.id, hit.u, hit.v, s.factor, Phase.BEGIN)
                    return
                }
                // 1.1.15: Lock screens: a two hand pinch does not scale the window.
                if (windowsLocked) return lockedNotice(out, "window ${hit.panel.id} ${hit.panel.label} scale")
                a.grab = Scaling(hit.panel.id, hit.panel)
            }
            Phase.UPDATE, Phase.END -> {
                (a.grab as? Zooming)?.let { z ->
                    z.factor = s.factor
                    out += SpaceAction.Zoom(z.panelId, z.u, z.v, s.factor, s.phase)
                    if (s.phase == Phase.END) a.grab = null
                    return
                }
                val g = a.grab as? Scaling ?: return
                val scaled = SpaceLayout.scale(g.start, s.factor)
                val next = layout.replace(scaled)
                out += SpaceAction.Layout(next, commit = s.phase == Phase.END)
                if (s.phase == Phase.END) a.grab = null
            }
        }
    }
}

/**
 * 0.5.2: the two hand stretch's numbers, safe against what tracking throws at them: a zero or
 * NaN direction (a lost landmark), hands that start almost on one line, and a factor that would
 * take the panel far past its limits in one frame. Mike's 0.5.1 two hand resize ended in a
 * frozen Space and a SurfaceFlinger reset.
 */
object StretchMath {
    /** The smallest angle (degrees) two hands start a stretch at, so a tiny start does not blow up the factor. */
    const val MIN_START_DEG = 2f

    /** The factor never goes past these in one stretch; the panel's own limits clamp it further. */
    const val MIN_FACTOR = 0.2f
    const val MAX_FACTOR = 5f

    /** Degrees between two directions, or null when either is zero or not a number. */
    fun apartDeg(p: Vec3, q: Vec3): Float? {
        val lp = p.length
        val lq = q.length
        if (!lp.isFinite() || !lq.isFinite() || lp < 1e-6f || lq < 1e-6f) return null
        val cos = ((p dot q) / (lp * lq)).takeIf { it.isFinite() } ?: return null
        return kotlin.math.acos(cos.coerceIn(-1f, 1f)) * RAD
    }

    /** The stretch's starting angle, at least [MIN_START_DEG]; null for unusable rays. */
    fun startDeg(p: Vec3, q: Vec3): Float? = apartDeg(p, q)?.coerceAtLeast(MIN_START_DEG)

    /**
     * 0.5.9: the direction halfway between the two hands' rays (their normalised sum), which a
     * two hand stretch moves the panel with; null for unusable rays or rays pointing apart.
     */
    fun midpoint(p: Vec3, q: Vec3): Vec3? {
        val lp = p.length
        val lq = q.length
        if (!lp.isFinite() || !lq.isFinite() || lp < 1e-6f || lq < 1e-6f) return null
        val sum = p * (1f / lp) + q * (1f / lq)
        val l = sum.length
        return if (l.isFinite() && l > 1e-3f) sum * (1f / l) else null
    }

    /** How much the panel scales for hands now [p], [q] apart over [startDeg]; null to keep the last size. */
    fun factor(p: Vec3, q: Vec3, startDeg: Float): Float? {
        if (!startDeg.isFinite() || startDeg <= 0f) return null
        val now = apartDeg(p, q) ?: return null
        val f = now / startDeg
        return if (f.isFinite()) f.coerceIn(MIN_FACTOR, MAX_FACTOR) else null
    }
}

/** 0.5.9: the smallest move (degrees) of a stretched panel's centre that sends a new layout. */
private const val MOVE_STEP_DEG = 0.02f

/**
 * 0.5.66: how far inside the view's edges Spatial Mouse's dot stops, degrees: just clear of the soft edge fade (96 px
 * outer, about 2.6 deg; 48 px top and bottom, about 1.3 deg). Kept small: a window's title bar near the top of the view
 * must stay in reach (a 3 deg top margin put it out of reach in the tests), and the slip, not the margin, is what brings
 * the dot back at once.
 */
/** 0.5.78: a swipe's net travel that brings an out of view anchored trackpad cursor back ([SpatialPointer.cursorReturns]). */
const val BRING_BACK_DEG = 3f

const val MOUSE_EDGE_MARGIN_H = 3f
const val MOUSE_EDGE_MARGIN_V = 1.5f

/** 0.5.28: a gesture's depth move is logged only past this, metres (1 cm). */
const val DEPTH_LOG_M = 0.01f

/** 0.5.28: said on the glasses when Curve bends a wrapped Space (the trackpad shows a one-tap Unwrap with it). */
const val WRAP_CURVE_NOTICE = "Wraparound on: Curve bends the whole Space. Unwrap: tap the note on the trackpad"

/** 0.5.12: at the click freeze's end, the ray's speed is judged over this long (ms); under [FREEZE_SETTLED_DEG_S] the hand has settled. */
private const val FREEZE_SPEED_MS = 48L
private const val FREEZE_SETTLED_DEG_S = 4f

/** 0.5.12: the angle between two unit directions, degrees. */
private fun angleDeg(a: Vec3, b: Vec3): Float = Math.toDegrees(kotlin.math.acos((a dot b).coerceIn(-1f, 1f).toDouble())).toFloat()

/** 0.5.11: how far (degrees on the panel) a hand's touch moves before it is a scroll: past Android's 8 dp slop on any panel. */
private const val SCROLL_SLOP_DEG = 0.6f

/** 0.5.46: a hand's touch moves this many times its ray's travel on the window ([SpatialPointer] follow). */
private const val HAND_SCROLL_GAIN = 1.6f

/** 0.5.11: the moves a scroll's release speed is measured over. 0.5.45: 200 (was 80), the fastest [FLING_SPAN_MS] in it. */
private const val FLING_WINDOW_MS = 200L

/** 0.5.45: the stretch of moves one release speed is taken over, two hand frames. */
private const val FLING_SPAN_MS = 66L

/** 0.5.11: a scroll whose last move is older than this at the release had stopped: no fling. 0.5.45: 120 (was 50), the opening fingers' frames. */
private const val FLING_STALE_MS = 120L

/** 0.5.45: how long a released scroll's touch is thrown on along its speed before it lifts (the app's tracker reads ~100 ms). */
private const val THROW_MS = 70L

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

/** 0.5.17: a trackpad scroll drags the touch 1.5 panel sizes a pad unit, tuned on the phone and glasses. */
const val SCROLL_DRAG = 1.5f

/** 0.5.42: Spatial Mouse: how long after a resize step the phone's aim leaves the held window where it is. */
const val SIZING_AIM_HOLD_MS = 400L

/** 0.5.41: the two finger scroll's page travel a pad unit (wheel px), as the scroll strip's at full speed. */
const val PAD_WHEEL_PX_PER_UNIT = 1600f

/** 0.5.41: the two finger scroll keeps to one axis once the fingers moved this far (pad units), across only when clearly so. */
const val AXIS_DECIDE_UNITS = 0.03f
const val AXIS_BIAS = 1.5f

/** 0.5.41: the coast after a two finger scroll: speed from the last 80 ms, slowing with a 0.35 s time constant. */
const val COAST_SAMPLE_MS = 80L
const val COAST_TAU_S = 0.35f
const val COAST_MIN_PX_S = 300f
const val COAST_STOP_PX_S = 40f
const val COAST_MAX_PX_S = 12000f

/**
 * 0.5.21: the thin scroll strip's wheel: notches a pad unit of page travel (a full strip slide, 2 units,
 * is 16 notches, about 1000 dp of a list at Android's 64 dp a notch), in [PanelInputMapper]'s glasses
 * pixels a notch.
 */
const val STRIP_NOTCHES_PER_UNIT = 8f
const val WHEEL_PX_PER_NOTCH = 40f

/** 0.5.25: a two finger swipe over the launcher moves its pages one page width (1450 dp) a pad unit, half the pad. */
const val LAUNCHER_DP_PER_PAD_UNIT = LauncherGrid.PANEL_W

/** Smallest change in u or v (about one pixel of a 1280 px panel) that sends another hover move. */
private const val HOVER_STEP = 0.0008f

/** 0.5.32: how long the Spatial Mouse dot stays pinned at the view's edge before the glasses say how to recentre it. */
private const val LASER_LOST_MS = 2_000L

/** Unreleased: how long after a physical mouse's last move Spatial Mouse leaves the pointer to it ([SpatialPointer.mouseTookOver]). */
const val MOUSE_TAKES_OVER_MS = 1_500L
