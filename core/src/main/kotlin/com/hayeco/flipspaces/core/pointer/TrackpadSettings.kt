package com.hayeco.flipspaces.core.pointer

/**
 * 0.5.17: the trackpad's haptic table. On the phone every impulse is a one shot of [ms] at the
 * default amplitude: short, light buzzes that confirm a touch without getting in the way. No haptic
 * on tap, drag, push and pull, resize, drop or reset.
 */
enum class PadHaptic(val ms: Long, val what: String, val cooldown: Boolean = true) {
    STRIP(10, "scroll strip touched"),
    BACK(10, "Back pressed"),
    MODE(10, "mode changed"),
    UNLOCK(10, "unlock hold"),
    PICKUP(1, "window picked up"),
    HOVER_BUTTON(10, "hover over a window bar button"),

    /** 0.5.17 cover screen: a cover layout button pressed (a Pocket XR extra, the same 10 ms). */
    COVER_BUTTON(10, "cover screen button pressed"),

    /** 0.5.32: Home held, the cursor (Spatial Mouse's laser) recentred in front. */
    RECENTRE(25, "cursor recentred", cooldown = false),

    /** 0.5.19: a 10 % step of a window's curve (and reaching full), a tick that ignores the cooldown so each step is felt. */
    CURVE_STEP(6, "curve step", cooldown = false),

    /** 0.5.19: a window's curve snapped flat, a firmer tick. */
    CURVE_FLAT(25, "curve snapped flat", cooldown = false),

    /** 0.5.21: the thin scroll strip's tick, every 0.1 of page travel ([StripSpeed]); felt through the cooldown, as the curve's steps. */
    SCROLL_TICK(4, "scroll strip tick", cooldown = false),
}

/**
 * 0.5.17: the haptic cooldown: at most one impulse every [COOLDOWN_MS] 500 ms; the rest are
 * dropped, so a burst of events never turns into a long buzz. 0.5.19: the curve's step ticks
 * ([PadHaptic.cooldown] false) are always played and do not start the cooldown.
 */
class HapticGate(private val cooldownMs: Long = COOLDOWN_MS) {
    private var lastMs = Long.MIN_VALUE / 2

    /** The one shot length to play for [haptic] at [nowMs], or null inside the cooldown. */
    fun play(haptic: PadHaptic, nowMs: Long): Long? {
        if (!haptic.cooldown) return haptic.ms
        if (nowMs - lastMs < cooldownMs) return null
        lastMs = nowMs
        return haptic.ms
    }

    companion object {
        const val COOLDOWN_MS = 500L
    }
}

/**
 * The Horizontal Trackpad's direction: Default is landscape left, Inverted landscape right.
 * [stored] is the saved value (3 or 4); [screenOrientation] is Android's.
 */
enum class PadDirection(val stored: Int, val screenOrientation: Int, val label: String) {
    DEFAULT(3, 0, "Default"),
    INVERTED(4, 8, "Inverted"),
}

/**
 * 0.5.17: the trackpad's settings and their defaults: the page (the Vertical Trackpad, since
 * Spatial Mouse needs the phone aimed), Cursor Speed 0..100 (50), the scroll strip's side (right), the Horizontal
 * Trackpad's direction (Default) and gestures (hands, off).
 */
data class TrackpadSettings(
    val mode: PadMode = PadMode.VERTICAL,
    val cursorSpeed: Int = PadCurve.DEFAULT_SPEED,
    val stripSide: StripSide = StripSide.RIGHT,
    val direction: PadDirection = PadDirection.DEFAULT,
    val gestures: Boolean = false,
    /** 0.5.17 cover screen: which side the window buttons rail is on (right for a right thumb). */
    val coverControls: StripSide = StripSide.RIGHT,
    /**
     * 0.5.83 (Mike: "two finger scroll and trackpad scroll speed should be adjustable in settings. two finger
     * scrolling is too quick right now"): the two finger scroll's and the thin strip's speeds, 0..100 as Cursor Speed,
     * through [scrollFactor]. Two fingers default slower than before (0.66 of 0.5.82's), the strip as it was.
     */
    val scrollSpeed: Int = DEFAULT_SCROLL_SPEED,
    val stripSpeed: Int = DEFAULT_STRIP_SPEED,
    /** 1.1.10 (issue 35): one finger on the pad scrolls on Spatial Mouse ([TrackpadInterpreter.oneFingerScroll]); on by default. */
    val oneFingerScroll: Boolean = true,
) {
    companion object {
        const val DEFAULT_SCROLL_SPEED = 35
        const val DEFAULT_STRIP_SPEED = 50

        /** 0.5.83: a scroll speed 0..100 as a factor on the scroll's travel: 50 is 1, each 25 doubles or halves (0.25 to 4). */
        fun scrollFactor(speed: Int): Float = Math.pow(2.0, (speed.coerceIn(0, 100) - 50) / 25.0).toFloat()

        /** 0.5.83: a stored scroll speed, or [default] when unset or out of range. */
        fun scrollSpeedOf(stored: Int, default: Int): Int = if (stored in 0..100) stored else default

        /**
         * The stored pad type -1..3: 0 and 1 are Spatial Mouse, 2 and 3 the trackpads; unset (-1) is the default, the Vertical
         * Trackpad. 1.1.2 (a Retroid Nova owner): on a device whose screen is landscape by nature ([landscapeDevice]), a
         * handheld, the default is the Horizontal Trackpad, so the screen does not turn on its side. A chosen page is kept.
         */
        fun modeOf(stored: Int, landscapeDevice: Boolean = false): PadMode = when (stored) {
            2 -> PadMode.VERTICAL
            3 -> PadMode.HORIZONTAL
            0, 1 -> PadMode.SPATIAL_MOUSE
            else -> if (landscapeDevice) PadMode.HORIZONTAL else PadMode.VERTICAL
        }

        /**
         * 1.1.2: whether a screen is landscape by nature: [width] by [height] as it is now, at [rotation] quarter turns from its
         * natural way up (Display.getRotation).
         */
        fun landscapeByNature(width: Int, height: Int, rotation: Int): Boolean =
            if (rotation % 2 == 0) width > height else height > width

        /**
         * 1.1.9 (issue 33): a handheld held landscape: a screen landscape by nature ([landscapeByNature]), or game controls
         * built into the device. The Retroid Nova is the second: its panel is portrait by nature (Display.getRotation 0 is
         * portrait) and its system runs it landscape at a quarter turn, so the 1.1.8 check alone said "a phone".
         */
        fun landscapeHandheld(landscapeByNature: Boolean, builtInGamepad: Boolean): Boolean = landscapeByNature || builtInGamepad

        /**
         * Whether [mode]'s page is laid out landscape on the open phone. 1.1.8 (issue 33, a Retroid Nova: "Gesture seems to
         * expect a portrait orientation"): Spatial Mouse on a [landscapeDevice] too. 1.1.9: there it keeps the screen's
         * own way up ([screenOrientation]), so its page follows [landscapeNow], the screen as it is.
         */
        fun landscapePage(mode: PadMode, landscapeDevice: Boolean, landscapeNow: Boolean = landscapeDevice): Boolean =
            mode == PadMode.HORIZONTAL || (mode == PadMode.SPATIAL_MOUSE && landscapeDevice && landscapeNow)

        /**
         * The Android screen orientation [mode]'s page locks on the open phone (-1 none, 0 landscape, 1 portrait, 8 landscape
         * turned). The Horizontal Trackpad takes its [direction]; the Vertical Trackpad is portrait. Spatial Mouse is portrait
         * on a phone. 1.1.9 (issue 33): on a [landscapeDevice] it locks nothing, so the screen stays the way the system and
         * the user hold it; the aim follows the screen's rotation ([com.hayeco.flipspaces.core.spatial.PhoneAim]), so the
         * top edge aims whichever way up. 1.1.8 locked the natural way up, which on a Nova is portrait.
         */
        fun screenOrientation(mode: PadMode, direction: PadDirection, landscapeDevice: Boolean): Int = when {
            mode == PadMode.HORIZONTAL -> direction.screenOrientation
            mode == PadMode.SPATIAL_MOUSE && landscapeDevice -> -1
            else -> 1
        }

        /**
         * 1.2.3 (issue 56: "The Default / Inverted choice in the Menu shows no effect"): whether the Menu's Sideways trackpad
         * direction does anything for [mode]. Only the Horizontal Trackpad's page takes the direction ([screenOrientation]),
         * so the Menu shows the choice there and nowhere else.
         */
        fun directionApplies(mode: PadMode): Boolean = mode == PadMode.HORIZONTAL

        fun storedOf(mode: PadMode): Int = mode.pageType

        /** Scrollbar side stored -1 / 0 / 1: 0 is left, anything else (unset included) right. */
        fun stripOf(stored: Int): StripSide = if (stored == 0) StripSide.LEFT else StripSide.RIGHT

        fun storedOf(side: StripSide): Int = if (side == StripSide.LEFT) 0 else 1

        fun directionOf(stored: Int): PadDirection = if (stored == PadDirection.INVERTED.stored) PadDirection.INVERTED else PadDirection.DEFAULT

        fun speedOf(stored: Int): Int = if (stored in 0..100) stored else PadCurve.DEFAULT_SPEED
    }
}

/**
 * 0.5.17: the pad's signals for the DeX cursor (when no Space is up, [PointerController] drives
 * DeX windows with pixel moves). The cursor turn becomes [PX_PER_DEG] glasses pixels a degree
 * (Pocket XR's old 0.04 degrees a pixel), a scroll's pad travel [SCROLL_PX] pixels a pad unit
 * (1.5 window heights a unit on a 540 px window), y down on the display.
 */
object PadDex {
    const val PX_PER_DEG = 25f
    const val SCROLL_PX = 810f

    fun convert(s: PadSignal): List<PointerSignal> = when (s) {
        is PadSignal.Move -> listOf(PointerSignal.MoveBy(s.yawDeg * PX_PER_DEG, -s.pitchDeg * PX_PER_DEG))
        is PadSignal.Scroll -> listOf(PointerSignal.Scroll(s.dx * SCROLL_PX, -s.dy * SCROLL_PX, s.phase))
        is PadSignal.Pinch -> listOf(PointerSignal.Scale(s.factor, s.phase))
        is PadSignal.Swipe -> when (s.direction) {
            SwipeDirection.UP -> listOf(PointerSignal.ShowDock)
            SwipeDirection.LEFT, SwipeDirection.RIGHT -> listOf(PointerSignal.Back)
            SwipeDirection.DOWN -> emptyList()
        }
        is PadSignal.Home -> if (s.event == HomeEvent.CLICK) listOf(PointerSignal.ShowDock) else emptyList()
        is PadSignal.TwoFinger, is PadSignal.Mode, is PadSignal.Aim, is PadSignal.Window, is PadSignal.Curve -> emptyList()
    }
}
