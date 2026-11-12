package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.sqrt

/**
 * What the app on a panel asks Android for, read from `dumpsys activity activities` (the panel
 * service runs it as the shell): its top activity's requested orientation, its resize mode, and
 * whether Android letterboxes it. 0.5.21: Twitch asks for sensorPortrait at run time, another app
 * for portrait in its manifest; both were letterboxed on the landscape panels.
 */
data class AppShape(
    val packageName: String,
    /** `requestedOrientation=SCREEN_ORIENTATION_...` without the prefix, e.g. PORTRAIT, SENSOR_PORTRAIT, UNSPECIFIED. */
    val orientation: String,
    /** `resizeMode=RESIZE_MODE_...` without the prefix, e.g. RESIZEABLE, UNRESIZEABLE; empty when not listed. */
    val resizeMode: String,
    val letterboxed: Boolean,
    val letterboxReason: String,
    /** 0.5.22: the app's target SDK, from the panel service's header line ([AppTarget]); null when not read. */
    val targetSdk: Int? = null,
    /** 0.5.22: what the app's display ignores, from the panel service's header line ([PanelDisplayCompat.State]). */
    val display: PanelDisplayCompat.State? = null,
    /** 0.5.23: the size and density the app's activity is laid out at (its CurrentConfiguration), null when not listed ([PanelFit]). */
    val boundsW: Int? = null,
    val boundsH: Int? = null,
    val densityDpi: Int? = null,
) {
    val asksPortrait: Boolean get() = orientation in PORTRAIT
    val asksLandscape: Boolean get() = orientation in LANDSCAPE
    val resizeable: Boolean get() = !resizeMode.startsWith("UNRESIZEABLE")

    fun line(): String = "$packageName asks for ${orientation.lowercase().replace('_', ' ')}, resize mode ${resizeMode.lowercase().replace('_', ' ').ifEmpty { "not listed" }}, " +
        (if (letterboxed) "letterboxed (${letterboxReason.ifEmpty { "no reason given" }})" else "not letterboxed") +
        (targetSdk?.let { ", targets SDK $it" } ?: "") + (display?.let { "; ${it.line()}" } ?: "")

    companion object {
        val PORTRAIT = setOf("PORTRAIT", "SENSOR_PORTRAIT", "REVERSE_PORTRAIT", "USER_PORTRAIT")
        val LANDSCAPE = setOf("LANDSCAPE", "SENSOR_LANDSCAPE", "REVERSE_LANDSCAPE", "USER_LANDSCAPE")

        private val DISPLAY = Regex("""^Display #(\d+)""")
        private val HIST = Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{\S+ \S+ ([A-Za-z0-9_.]+)/""")

        /**
         * The top activity on display [displayId] in a `dumpsys activity activities` text, or null. 0.5.22: the
         * panel service's header lines before the dump add the app's target SDK and the display's state.
         */
        fun parse(dump: String?, displayId: Int): AppShape? {
            val shape = parseActivity(dump, displayId) ?: return null
            return shape.copy(targetSdk = AppTarget.parseHeader(dump, shape.packageName), display = PanelDisplayCompat.parse(dump, displayId))
        }

        private fun parseActivity(dump: String?, displayId: Int): AppShape? {
            if (dump.isNullOrBlank()) return null
            var inDisplay = false
            var pkg: String? = null
            var orientation = ""
            var resize = ""
            var boxed = false
            var reason = ""
            var bounds: MatchResult? = null
            for (line in dump.lineSequence()) {
                DISPLAY.find(line)?.let { m ->
                    if (pkg != null) return done(pkg, orientation, resize, boxed, reason, bounds)
                    inDisplay = m.groupValues[1].toInt() == displayId
                    return@let
                }
                if (!inDisplay) continue
                val hist = HIST.find(line)
                if (hist != null) {
                    if (pkg != null) return done(pkg, orientation, resize, boxed, reason, bounds)
                    pkg = hist.groupValues[1]
                    continue
                }
                if (pkg == null) continue
                val t = line.trim()
                when {
                    t.startsWith("requestedOrientation=") -> orientation = t.substringAfter("SCREEN_ORIENTATION_", t.substringAfter('='))
                    t.startsWith("resizeMode=") -> resize = t.substringAfter("RESIZE_MODE_", t.substringAfter('='))
                    t.startsWith("areBoundsLetterboxed=") -> boxed = t.substringAfter('=') == "true"
                    t.startsWith("letterboxReason=") -> reason = t.substringAfter('=')
                    t.startsWith("CurrentConfiguration=") && bounds == null -> bounds = CONFIG.find(t)
                    t.startsWith("* Task{") -> return done(pkg, orientation, resize, boxed, reason, bounds)
                }
            }
            return pkg?.let { done(it, orientation, resize, boxed, reason, bounds) }
        }

        /** 0.5.23: the activity's density and bounds in its CurrentConfiguration line ([PanelFit]). */
        private val CONFIG = Regex("""\s(\d+)dpi\s.*?mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

        private fun done(pkg: String, o: String, r: String, b: Boolean, why: String, bounds: MatchResult? = null): AppShape {
            val g = bounds?.groupValues
            return AppShape(
                pkg, o.ifEmpty { "UNSPECIFIED" }, r, b, why,
                boundsW = g?.let { it[4].toInt() - it[2].toInt() },
                boundsH = g?.let { it[5].toInt() - it[3].toInt() },
                densityDpi = g?.get(1)?.toInt(),
            )
        }
    }
}

/**
 * 0.5.21 (Mike: "for apps that lock themselves to portrait or aren't resizable"): Wide view, a button on each window
 * that gives an app a landscape display, so phone-shaped apps can fill a wide window. 0.5.96 (open source audit):
 * renamed from the name another product uses for its similar feature, with its messages written fresh.
 *
 * Pocket XR's windows start landscape and resize freely ([PanelDisplaySize]), so Wide view is:
 * - **Off** (the default): an app that asks for portrait ([AppShape.asksPortrait]) gets a portrait window of
 *   [PORTRAIT_ASPECT] (3 : 4), the same area, so it is not a phone column between black bars; every other app keeps the
 *   window's own shape.
 * - **On**: the window is landscape (16 : 9 when it was portrait, else its own landscape shape) and its display is at
 *   [DPI] 260 with a smallest width of at least [MIN_SMALLEST_DP] 600 dp. On that large screen class Android 16 sets
 *   aside the portrait lock, the aspect limits and the resize restriction of apps that target Android 16 (SDK 36), so
 *   they fill the landscape window. Resizing the window keeps both rules.
 * - **Remembered per app**: on or off is kept per package. The first time it is turned on a tip ([TIP]) says some apps
 *   may not lay out well; an app whose manifest is landscape only cannot have it turned off ([CANNOT_DISABLE]).
 *
 * What it cannot change ([limits]): apps that target under SDK 36 and lock portrait, games, and apps that opt out of
 * Android 16's large screen rule stay letterboxed on the wide display; stock Android has no call to force them.
 */
object WideView {
    const val DPI = 260
    const val MIN_SMALLEST_DP = 600f
    const val PORTRAIT_ASPECT = 900f / 1200f
    const val LANDSCAPE_ASPECT = 16f / 9f
    const val DEBOUNCE_MS = 1000L

    /** How long the tip stays on the window. */
    const val NOTICE_MS = 4000L

    val LABEL: String get() = UiText.get("spatial_wide_view", "Wide view")
    // 0.5.96: written fresh (the audit found the earlier two were another product's wording).
    val TIP: String get() = UiText.get("spatial_wide_view_tip", "Wide view gives this app a landscape screen. Some apps don't lay out well; tap again to turn it off.")
    val CANNOT_DISABLE: String get() = UiText.get("spatial_wide_view_cannot_disable", "This app only runs in landscape, so Wide view stays on.")

    /** The window's shape for Wide view [on] and what the app asks for (null: not read yet). */
    enum class Shape { FREE, PORTRAIT, LANDSCAPE }

    fun shape(on: Boolean, app: AppShape?): Shape = when {
        on -> Shape.LANDSCAPE
        app?.asksLandscape == true -> Shape.LANDSCAPE
        app?.asksPortrait == true -> Shape.PORTRAIT
        else -> Shape.FREE
    }

    /** Whether the button can be turned off for the app: not for one that asks for landscape. */
    fun canTurnOff(app: AppShape?): Boolean = app?.asksLandscape != true

    /**
     * [panel] in [shape]: the same angular area in a portrait 3 : 4 or a landscape 16 : 9 when its
     * shape is the other way; a window already that way round keeps its own shape. The app takes the
     * new shape ([SpacePanel.sizedByUser]).
     */
    fun reshape(panel: SpacePanel, shape: Shape): SpacePanel {
        val portrait = panel.heightDeg > panel.widthDeg
        val aspect = when (shape) {
            Shape.FREE -> return panel
            Shape.PORTRAIT -> if (portrait) return panel else PORTRAIT_ASPECT
            Shape.LANDSCAPE -> if (!portrait) return panel else LANDSCAPE_ASPECT
        }
        val area = panel.widthDeg * panel.heightDeg
        var w = sqrt(area * aspect)
        var h = sqrt(area / aspect)
        // Within the window limits, the shape kept.
        val k = minOf(1f, PanelLimits.MAX_WIDTH_DEG / w, PanelLimits.MAX_HEIGHT_DEG / h)
        w *= k
        h *= k
        val up = maxOf(1f, PanelLimits.MIN_SIZE_DEG / w, PanelLimits.MIN_SIZE_DEG / h)
        w *= up
        h *= up
        return panel.copy(widthDeg = w, heightDeg = h).sizedByUser()
    }

    /**
     * Why Wide view may leave an app letterboxed, for the log 2 s after Wide view turned on. 0.5.22 (the
     * 0.5.21 line blamed the app's SDK, a game or an opt out, while Twitch targets 36): the
     * reason named from the app's target SDK and what its display ignores ([PanelDisplayCompat]).
     */
    fun limits(app: AppShape): String {
        if (!app.letterboxed) return "it fills the window (not letterboxed)"
        val sdk = app.targetSdk?.let { "targets SDK $it" } ?: "target SDK not read"
        val d = app.display
        val reason = app.letterboxReason.ifEmpty { "no reason given" }
        return when {
            d == null -> "Android still letterboxes it ($reason; it $sdk); the panel service did not say what its display ignores (an older service?)"
            !d.anyOn -> "Android still letterboxes it ($reason; it $sdk): its display ignores neither orientation requests nor activity size restrictions, so Android's large screen rule does not reach it"
            d.sizeRestrictions == PanelDisplayCompat.Setting.ON -> "Android still letterboxes it ($reason; it $sdk) although its display ignores activity size restrictions" +
                if (app.asksPortrait) "; Android's per app orientation override is tried next" else ""
            (app.targetSdk ?: 0) < 36 -> "Android still letterboxes it ($reason): it $sdk, under Android 16's large screen rule, and its display ignores orientation requests only" +
                if (app.asksPortrait) "; Android's per app orientation override is tried next" else ""
            else -> "Android still letterboxes it ($reason; it $sdk) with its display ignoring orientation requests (a game or an app that opts out)" +
                if (app.asksPortrait) "; Android's per app orientation override is tried next" else ""
        }
    }
}

/**
 * Wide view's per app memory: the packages it is on for, and whether the first time tip was shown.
 * Stored as text (one package a line, `tip` for the tip), so core needs no preferences.
 */
class WideViewStore(text: String? = null) {
    private val on = LinkedHashSet<String>()
    var tipShown = false
        private set
    private val lastToggleMs = HashMap<String, Long>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { if (it == TIP_LINE) tipShown = true else on += it }
    }

    fun isOn(packageName: String): Boolean = packageName in on

    /**
     * A press of the button for [packageName] at [nowMs]: the new state, or null when it changed nothing
     * (inside the 1 s debounce, or an app that cannot have it off). [tip] is true the first time it goes on.
     */
    fun toggle(packageName: String, app: AppShape?, nowMs: Long): Toggle {
        val last = lastToggleMs[packageName]
        if (last != null && nowMs - last < WideView.DEBOUNCE_MS) return Toggle(isOn(packageName), changed = false, tip = false, refused = null)
        val turningOff = isOn(packageName) || app?.asksLandscape == true
        if (turningOff && !WideView.canTurnOff(app)) return Toggle(true, changed = false, tip = false, refused = WideView.CANNOT_DISABLE)
        lastToggleMs[packageName] = nowMs
        return if (isOn(packageName)) {
            on -= packageName
            Toggle(false, changed = true, tip = false, refused = null)
        } else {
            on += packageName
            val tip = !tipShown
            tipShown = true
            Toggle(true, changed = true, tip = tip, refused = null)
        }
    }

    data class Toggle(val on: Boolean, val changed: Boolean, val tip: Boolean, val refused: String?)

    fun encode(): String = buildString {
        if (tipShown) append(TIP_LINE).append('\n')
        on.forEach { append(it).append('\n') }
    }

    companion object {
        private const val TIP_LINE = "tip"
    }
}
