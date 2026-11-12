package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.17: the phone trackpad's pages: 1 Spatial Mouse (the phone aims like a laser pointer),
 * 2 Vertical Trackpad, 3 Horizontal Trackpad. [pageType] is the number the page is stored as.
 */
enum class PadMode(private val labelKey: String, private val labelEnglish: String, val pageType: Int) {
    SPATIAL_MOUSE("core_pad_spatial_mouse", "Spatial Mouse", 1),
    VERTICAL("core_pad_vertical", "Vertical Trackpad", 2),
    HORIZONTAL("core_pad_horizontal", "Horizontal Trackpad", 3),
    ;

    val label: String get() = UiText.get(labelKey, labelEnglish)
}

/** A three finger swipe's direction: up Recent apps, down Control panel. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** The phone's Home button: press and release drop a held window, a click, the 1.0 s long press. */
enum class HomeEvent { PRESS, RELEASE, CLICK, LONG_PRESS }

/**
 * 0.5.17: what the phone trackpad sends besides presses ([PointerSignal.Down] with `pad`,
 * [PointerSignal.Up], [PointerSignal.SecondaryTap], [PointerSignal.Back], [PointerSignal.ShowDock]).
 * Pad units: the touch area's full width and full height are each 2.0, x right, y up.
 * The window and cursor logic that reads them is in core next to the Space
 * ([com.hayeco.flipspaces.core.spatial.SpatialPointer]).
 */
sealed interface PadSignal : PointerSignal {
    /**
     * One finger moved by [dx], [dy] pad units; [yawDeg], [pitchDeg] is the cursor's turn from the
     * trackpad's curve ([PadCurve]). A held window uses the raw pad units (about 57 degrees a unit).
     */
    data class Move(val dx: Float, val dy: Float, val yawDeg: Float, val pitchDeg: Float) : PadSignal

    /**
     * Two fingers on the pad: each finger's move this event ([ax], [ay] and [bx], [by], pad units)
     * and their distance apart now ([spread]). A held window reads these: the same way is push and
     * pull, apart or together is scale.
     */
    data class TwoFinger(val ax: Float, val ay: Float, val bx: Float, val by: Float, val spread: Float, val phase: Phase) : PadSignal

    /**
     * A scroll: [axisX], [axisY] are a thumbstick-like axis pair (4 axis units a pad
     * unit, clamped to -1..1, y negative when the fingers go up); [dx], [dy] the pad travel since
     * BEGIN (two fingers: their midpoint). [strip] marks the one finger scroll strip.
     * 0.5.21 (Mike: a thin, long scroll strip): [wheel] marks the thin strip ([ScrollStrip]), whose [dy]
     * is the page's travel (the finger's times the speed curve, then the coast after the lift), which the
     * Space turns into smooth wheel scrolling of the window under the cursor.
     * 1.1.10 (issue 35): [oneFinger] marks Spatial Mouse's one finger scroll ([TrackpadInterpreter.oneFingerScroll]): the
     * same travel and path as two fingers, and the Space holds the phone's aim still while it lasts.
     */
    data class Scroll(
        val axisX: Float,
        val axisY: Float,
        val dx: Float,
        val dy: Float,
        val phase: Phase,
        val strip: Boolean = false,
        val wheel: Boolean = false,
        val oneFinger: Boolean = false,
    ) : PadSignal

    /** A two finger pinch: the fingers' distance over the distance at its start. */
    data class Pinch(val factor: Float, val phase: Phase) : PadSignal

    /** A three finger swipe (|shift| over 0.04 pad units). */
    data class Swipe(val direction: SwipeDirection) : PadSignal

    data class Home(val event: HomeEvent) : PadSignal

    /** The page in use, sent when it changes and when the trackpad opens. */
    data class Mode(val mode: PadMode) : PadSignal

    /** Spatial Mouse: the phone's pointing direction (its top edge), yaw right positive and pitch up, degrees. */
    data class Aim(val yawDeg: Float, val pitchDeg: Float) : PadSignal

    /**
     * 0.5.17 cover screen: one of the cover layout's window buttons ([WindowAction]). [step] is the
     * size of a Further or Closer step from the button's auto repeat ([CoverRepeat]); 1 for the rest.
     */
    data class Window(val action: WindowAction, val step: Float = 1f) : PadSignal

    /**
     * 0.5.19: a slide along a Curve button by [delta] of the curve's range (0 flat to 1 full,
     * [com.hayeco.flipspaces.core.spatial.CurveControl.PER_MM]): it curves the window held to
     * curve, else the one under the cursor. [phase] END is the thumb lifting.
     */
    data class Curve(val delta: Float, val phase: Phase) : PadSignal
}

/**
 * 0.5.17 cover screen: the window buttons, for one thumb. Move and Resize pick up the window under
 * the cursor (else the front one) and toggle the hold; while held in Resize, one finger's vertical
 * swipe is closer and further and its sideways swipe smaller and larger (Spatial Mouse's rule).
 * Further and Closer step the held window, or the one under the cursor, without a pick up.
 * 0.5.19: Curve picks the window up to curve it: one finger's sideways swipe curves it (right more,
 * left less, flat snaps), Curve again or a tap drops it.
 */
enum class WindowAction { MOVE, RESIZE, FURTHER, CLOSER, CURVE }

/**
 * 0.5.18 (the cover's gestures, Further, Closer and typing left no log line, so none of them could
 * be counted): one line per pad gesture as it ends, and per window button
 * step. Feed it every signal the trackpad sends; [take] gives each finished line once.
 */
class PadGestureLog {
    private var scrollUpdates = 0
    private var scrollDx = 0f
    private var scrollDy = 0f
    private var scrollStrip = false
    private var scrollWheel = false
    private var pinchUpdates = 0
    private var pinchFactor = 1f
    private var twoUpdates = 0
    private var curveUpdates = 0
    private var curveTravel = 0f
    private val lines = ArrayList<String>()

    /** Lines for every gesture counted so far; the counts go on. */
    var gestures = 0
        private set

    fun add(s: PointerSignal) {
        when (s) {
            is PadSignal.Scroll -> when (s.phase) {
                Phase.BEGIN -> {
                    scrollUpdates = 0
                    scrollStrip = s.strip
                    scrollWheel = s.wheel
                }
                Phase.UPDATE -> {
                    scrollUpdates++
                    scrollDx = s.dx
                    scrollDy = s.dy
                }
                Phase.END -> finish(
                    "%s scroll: %d updates, travel %.2f, %.2f pad units".format(java.util.Locale.ROOT, if (scrollWheel) "thin strip (with its coast)" else if (scrollStrip) "strip" else "two finger", scrollUpdates, scrollDx, scrollDy),
                )
            }
            is PadSignal.Pinch -> when (s.phase) {
                Phase.BEGIN -> pinchUpdates = 0
                Phase.UPDATE -> {
                    pinchUpdates++
                    pinchFactor = s.factor
                }
                Phase.END -> finish("two finger pinch: %d updates, the fingers %.2f times as far apart".format(java.util.Locale.ROOT, pinchUpdates, pinchFactor))
            }
            is PadSignal.TwoFinger -> when (s.phase) {
                Phase.BEGIN -> twoUpdates = 0
                Phase.UPDATE -> twoUpdates++
                Phase.END -> finish("two fingers on a held window: %d updates, %.2f pad units apart at the end".format(java.util.Locale.ROOT, twoUpdates, s.spread))
            }
            is PadSignal.Swipe -> finish("three finger swipe ${s.direction.name.lowercase()}")
            // 0.5.19: a slide along the Curve button, one line at its lift.
            is PadSignal.Curve -> when (s.phase) {
                Phase.BEGIN -> {
                    curveUpdates = 0
                    curveTravel = 0f
                }
                Phase.UPDATE -> {
                    curveUpdates++
                    curveTravel += s.delta
                }
                Phase.END -> finish("Curve button slide: %d updates, %+.2f of the curve's range".format(java.util.Locale.ROOT, curveUpdates, curveTravel))
            }
            is PadSignal.Window -> finish(
                when (s.action) {
                    WindowAction.FURTHER, WindowAction.CLOSER -> "%s button, a step of %.2f".format(java.util.Locale.ROOT, s.action.name.lowercase().replaceFirstChar { it.uppercase() }, s.step)
                    else -> "${s.action.name.lowercase().replaceFirstChar { it.uppercase() }} button"
                },
            )
            PointerSignal.SecondaryTap -> finish("two finger tap (secondary click)")
            else -> Unit
        }
    }

    private fun finish(what: String) {
        gestures++
        lines += "cover gesture %d: %s".format(java.util.Locale.ROOT, gestures, what)
    }

    /** The lines finished since the last call. */
    fun take(): List<String> = ArrayList(lines).also { lines.clear() }
}
