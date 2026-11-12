package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * 0.4.2 fixes from the 0.4.1 emulator test, as plain Kotlin: where mouse events may land on a
 * panel's display, when a panel's app has closed, when Back would close it, which display keeps
 * input focus while the trackpad is in use, when the mode is worth a log line, and who opens
 * the Space when the glasses are plugged in.
 */

/**
 * The part of a panel display where an app window is sure to be, in display pixels from each
 * edge. Mouse hover and wheel events are clamped into it: on the display's top rows (the strip
 * under the title bar) Android found no window ("hovering pointer is not in any windows") and
 * dropped the scroll. Taps and drags are not clamped, so the very edge can still be touched.
 */
data class ContentInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** [x], [y] moved inside the content area of a [width] by [height] display. */
    fun clamp(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        val x0 = left.toFloat()
        val x1 = width - 1f - right
        val y0 = top.toFloat()
        val y1 = height - 1f - bottom
        val cx = if (x1 >= x0) x.coerceIn(x0, x1) else (width - 1f) / 2f
        val cy = if (y1 >= y0) y.coerceIn(y0, y1) else (height - 1f) / 2f
        return Pair(cx, cy)
    }

    fun contains(x: Float, y: Float, width: Int, height: Int): Boolean =
        x >= left && x <= width - 1f - right && y >= top && y <= height - 1f - bottom

    companion object {
        /** Top: 32 dp at the panels' 240 dpi (48 px) or 5% of the height; 8 px elsewhere. */
        fun forPanel(width: Int, height: Int): ContentInsets =
            ContentInsets(left = EDGE_PX, top = maxOf(TOP_PX, height / 20), right = EDGE_PX, bottom = EDGE_PX)

        const val TOP_PX = 48
        const val EDGE_PX = 8
    }
}

/** What runs on a panel's display, from the shell's task list; null fields are unknown. */
data class DisplayTasks(
    /** Root tasks on the display. */
    val tasks: Int,
    /** Of those, the ones Android reports visible. */
    val visibleTasks: Int,
    /** Activities in the top task. */
    val topActivities: Int,
) {
    /** No task, or none visible: the app finished or moved itself to the back. */
    val empty: Boolean get() = tasks == 0 || visibleTasks == 0

    /** The app is down to its last activity: Back there would close it. */
    val atLastActivity: Boolean get() = tasks == 1 && topActivities <= 1

    companion object {
        /** [values] as the service returns them (tasks, visible, top activities); null when the service could not tell. */
        fun of(values: IntArray?): DisplayTasks? =
            if (values == null || values.size < 3 || values.any { it < 0 }) null else DisplayTasks(values[0], values[1], values[2])
    }
}

/**
 * Notices a panel whose app has closed (0.4.2): its display is still there, so the panel used to
 * stay blank. The display must be empty at [confirmChecks] checks in a row, and never in the
 * first [graceMs] after a launch, while the app's first activity is still on its way.
 */
class EmptyPanelDetector(private val graceMs: Long = 3000, private val confirmChecks: Int = 2) {
    private var launchedMs = Long.MIN_VALUE
    private var emptyChecks = 0

    /** 1.1.9: when the app was last (re)launched here, or null before the first launch or check ([SingleWindowApp]). */
    val launchedAtMs: Long? get() = launchedMs.takeIf { it != Long.MIN_VALUE }

    /** The app was (re)launched at [nowMs]. */
    fun launched(nowMs: Long) {
        launchedMs = nowMs
        emptyChecks = 0
    }

    /** One check of the display; true when the app is now taken as closed. Unknown ([tasks] null) never counts. */
    fun sample(tasks: DisplayTasks?, nowMs: Long): Boolean {
        if (tasks == null) return false
        if (launchedMs != Long.MIN_VALUE && nowMs - launchedMs < graceMs) {
            emptyChecks = 0
            return false
        }
        emptyChecks = if (tasks.empty) emptyChecks + 1 else 0
        return emptyChecks >= confirmChecks
    }
}

/**
 * 1.1.9 (a Galaxy S20 FE on Android 11: a second Chrome window, and the first said "Reopen / Close"; Reopen only moved
 * Chrome back and emptied the other): an app that keeps one task (Chrome on Android 11) moves to the newest window it is
 * started on, and the window it left goes empty. A window that went empty while a newer window of the same app is still
 * showing is taken as that, so its card says so and offers only Close.
 */
object SingleWindowApp {
    /** A window as the Space knows it: its app, when its app was last launched there (null: not yet stamped), and whether it shows the app. */
    data class Window(val id: Int, val packageName: String, val launchedMs: Long?, val showing: Boolean)

    /**
     * The window [empty]'s app moved to: another window of the same app, still showing, launched after [empty] (a window
     * not stamped yet has only just opened, so it counts as newer). The newest, or null when there is none.
     */
    fun movedTo(empty: Window, others: List<Window>): Window? {
        if (empty.packageName.isEmpty()) return null
        val since = empty.launchedMs ?: return null
        return others
            .filter { it.id != empty.id && it.packageName == empty.packageName && it.showing && (it.launchedMs ?: Long.MAX_VALUE) > since }
            .maxByOrNull { it.launchedMs ?: Long.MAX_VALUE }
    }

    /**
     * 1.1.10 (Mike's onboarding rule: say which setting fixes it): the card for a window whose app moved to a newer window.
     * With the Menu's [NewInstanceSwitch] off ([newInstanceOn] false, as it was when the app moved), the second window
     * reused the app's one task, so the card names the switch and offers to turn it on and reopen. Only with it on, when
     * the app still moved, does the card say the app can only show one window on this phone.
     */
    fun card(label: String, newInstanceOn: Boolean): PanelCard =
        if (newInstanceOn) PanelCard.MovedToNewerWindow(label) else PanelCard.MovedSwitchOff(label)
}

/** 1.1.10: the Menu's switch for a second window of an app already open ([SingleWindowApp.card]). */
object NewInstanceSwitch {
    /** The switch's name, as the Menu row and the card say it. */
    val NAME: String get() = UiText.get("spatial_new_instance_name", "Open a new instance")

    val LABEL: String get() = UiText.get("spatial_new_instance_label", "Open a new instance if the app is already running")

    /** The Menu row's quieter line under [LABEL]. */
    val SUBTITLE: String get() = UiText.get("spatial_new_instance_subtitle", "Lets you open two windows of the same app, like two Chrome windows")
}

/** What the `<` button or a three finger swipe does on a panel (0.4.2). */
enum class BackDecision {
    /** Send KEYCODE_BACK to the app. */
    SEND,

    /** The app is at its last activity: ask first, Back there would close it. */
    ASK,

    /** The app has already closed: nothing to send. */
    NOTHING,
}

object BackGuard {
    /**
     * [allowedLast] is true once Mike chose "Back anyway" on this panel: after that Back goes
     * straight to the app (single activity apps such as Chrome handle Back themselves), and if
     * it does close, the panel shows App closed with Reopen.
     */
    fun decide(tasks: DisplayTasks?, appClosed: Boolean, allowedLast: Boolean): BackDecision = when {
        appClosed -> BackDecision.NOTHING
        tasks == null -> BackDecision.SEND
        tasks.empty -> BackDecision.NOTHING
        tasks.atLastActivity && !allowedLast -> BackDecision.ASK
        else -> BackDecision.SEND
    }
}

/** A button on a panel card, in 0..1 panel coordinates (u across, v down). */
data class CardButton(
    val kind: CardAction,
    val label: String,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    /** 0.5.69: the card's main answer, in the accent; [danger] lights red on hover (closing something). */
    val primary: Boolean = false,
    val danger: Boolean = false,
    /** 0.5.85: an app tile of [PanelCard.SwitchApp]: the app it switches the window to ([CardAction.SWITCH_TO]). */
    val app: com.hayeco.flipspaces.core.AppEntry? = null,
) {
    fun contains(u: Float, v: Float): Boolean = u in u0..u1 && v in v0..v1
}

enum class CardAction {
    REOPEN, CLOSE_PANEL, BACK_ANYWAY, KEEP, SWITCH_TO, ALL_APPS,

    /** 1.1.10: turn the Menu's [NewInstanceSwitch] on, then reopen the app in this window as a new instance. */
    TURN_ON_AND_REOPEN,
}

/**
 * A card over a panel (0.4.2): App closed covers the whole panel with Reopen and Close; the
 * Back question is a band along the bottom with Back anyway and Close panel. [v0] is where the
 * card starts down the panel (0 for the whole panel).
 */
sealed interface PanelCard {
    val lines: List<String>
    val v0: Float
    val buttons: List<CardButton>

    /** The button at [u], [v], or null; a press elsewhere on an Ask card keeps the app as it is. */
    fun hit(u: Float, v: Float): CardAction? = buttonAt(u, v)?.kind

    /** 0.5.85: the button itself (an app tile carries its app). */
    fun buttonAt(u: Float, v: Float): CardButton? = buttons.firstOrNull { it.contains(u, v) }

    /**
     * The height of the card's texture for a whole panel texture [fullHeightPx] high, so the
     * card keeps the panel's aspect over its band (0.4.3: the Back question's band used the
     * whole panel's texture, and its line came out squashed to 30% of its height). Even.
     */
    fun textureHeight(fullHeightPx: Int): Int = (kotlin.math.round(fullHeightPx * (1f - v0) / 2f).toInt() * 2).coerceAtLeast(2)

    /*
     * 0.5.69 (Mike, of the Back question: "the Back would close X grey popup in the window is so badly
     * designed"): a card is a glass dialog, not a grey band: [box] (u0, v0, u1, v1) on the window, which dims behind it,
     * with a [title], a quieter [detail] line under it, and pill buttons. Each text line's centre and height are shares of
     * the window's height. The buttons keep their hit areas inside the box.
     */
    val box: FloatArray get() = floatArrayOf(0f, v0, 1f, 1f)
    val title: String get() = lines.first()
    val detail: String? get() = lines.getOrNull(1)
    val titleV: Float
    val titleH: Float
    val detailV: Float
    val detailH: Float

    /** 1.1.9: the card fills the whole window (the app has left it), as App closed does. */
    val wholeWindow: Boolean get() = false

    /** 1.1.9: the title's and the detail's text size, a share of the usual, so a long line fits its one line. */
    val titleScale: Float get() = 1f
    val detailScale: Float get() = 1f

    /**
     * 0.5.19 (a game crashed natively 10 s after each launch and its panel just went to "App closed"): [crash] says how it ended when its exit record was a crash or ANR.
     */
    data class AppClosed(val label: String, val crash: String? = null) : PanelCard {
        override val lines = listOfNotNull(label, if (crash != null) UiText.get("spatial_card_app_crashed", "The app closed: it crashed") else UiText.get("spatial_card_app_closed", "App closed"), crash)
        override val v0 = 0f
        override val buttons = listOf(
            CardButton(CardAction.REOPEN, UiText.get("spatial_card_reopen", "Reopen"), 0.29f, 0.60f, 0.49f, 0.72f, primary = true),
            CardButton(CardAction.CLOSE_PANEL, UiText.get("spatial_card_close", "Close"), 0.51f, 0.60f, 0.71f, 0.72f, danger = true),
        )
        override val detail: String? get() = lines.drop(1).joinToString("  ·  ").ifEmpty { null }
        override val titleV = 0.38f
        override val titleH = 0.085f
        override val detailV = 0.47f
        override val detailH = 0.05f
        override val wholeWindow: Boolean get() = true
    }

    /**
     * 1.1.9: the window [label]'s app left for a newer window of its own ([SingleWindowApp]). Reopen would only move it
     * back and empty the other, so the card says what happened and offers one button, to close this window.
     */
    data class MovedToNewerWindow(val label: String) : PanelCard {
        override val lines = listOf(
            UiText.format("spatial_card_one_window_only", "%1\$s can only show one window on this phone.", label),
            UiText.get("spatial_card_moved_newest", "It moved to your newest window."),
        )
        override val v0 = 0f
        override val buttons = listOf(
            CardButton(CardAction.CLOSE_PANEL, UiText.get("spatial_card_close_this_window", "Close this window"), 0.33f, 0.60f, 0.67f, 0.72f, primary = true),
        )
        override val titleV = 0.38f
        override val titleH = 0.085f
        override val detailV = 0.47f
        override val detailH = 0.05f
        override val wholeWindow: Boolean get() = true
        override val titleScale: Float get() = fitScale(title)
        override val detailScale: Float get() = fitScale(detail.orEmpty())
    }

    /**
     * 1.1.10: as [MovedToNewerWindow], but the Menu's [NewInstanceSwitch] was off, so the second window reused the app's
     * one task. The card names the switch, and Turn on and reopen turns it on and opens the app here as a new instance.
     */
    data class MovedSwitchOff(val label: String) : PanelCard {
        override val lines = listOf(
            UiText.format("spatial_card_moved_newer", "%1\$s moved to your newer window.", label),
            UiText.format("spatial_card_keep_both", "To keep both, turn on %1\$s.", NewInstanceSwitch.NAME),
        )
        override val v0 = 0f
        override val buttons = listOf(
            CardButton(CardAction.TURN_ON_AND_REOPEN, UiText.get("spatial_card_turn_on_reopen", "Turn on and reopen"), 0.21f, 0.60f, 0.49f, 0.72f, primary = true),
            CardButton(CardAction.CLOSE_PANEL, UiText.get("spatial_card_close_this_window", "Close this window"), 0.51f, 0.60f, 0.79f, 0.72f, danger = true),
        )
        override val titleV = 0.38f
        override val titleH = 0.085f
        override val detailV = 0.47f
        override val detailH = 0.05f
        override val wholeWindow: Boolean get() = true
        override val titleScale: Float get() = fitScale(title)
        override val detailScale: Float get() = fitScale(detail.orEmpty())
    }

    companion object {
        /**
         * 1.1.9: about this many characters fit a card line at the usual size (a 1024 pixel line texture, 58 px Inter);
         * a longer line is drawn smaller rather than wrapped onto a second line the texture cuts off.
         */
        const val LINE_CHARS = 30

        /** The text size share that fits [text] on one card line. */
        fun fitScale(text: String): Float = if (text.length <= LINE_CHARS) 1f else LINE_CHARS.toFloat() / text.length
    }

    /**
     * 0.5.85 (Mike: Switch app "should show pinned apps when pressed and then a way to choose from all apps ... it will be
     * more compacted"): a card on the window with the launcher's pinned apps as tiles (up to [MAX_APPS], [COLUMNS] a row),
     * All apps and Cancel. [aspect] is the window's width over its height, so the tiles come out square.
     */
    data class SwitchApp(val label: String, val apps: List<com.hayeco.flipspaces.core.AppEntry>, val aspect: Float) : PanelCard {
        override val lines = listOf(UiText.get("spatial_card_switch_app", "Switch app"))
        override val v0 = 0.1f
        override val box = floatArrayOf(0.1f, 0.1f, 0.9f, 0.92f)
        override val title = UiText.get("spatial_card_switch_app", "Switch app")
        override val detail: String? = if (apps.isEmpty()) UiText.get("spatial_card_no_apps_pinned", "No apps pinned. Pin apps in the launcher, or pick from All apps.") else UiText.get("spatial_card_pinned_or_all", "Pinned apps, or pick from All apps")
        override val titleV = 0.2f
        override val titleH = 0.07f
        override val detailV = 0.28f
        override val detailH = 0.04f
        override val buttons: List<CardButton> = run {
            val shown = apps.take(MAX_APPS)
            val cols = minOf(COLUMNS, maxOf(1, shown.size))
            val rows = (shown.size + COLUMNS - 1) / COLUMNS
            // Square tiles in the band from v 0.34 to 0.74, as wide as fits.
            val bandH = 0.38f
            // Square in metres: tileV = tileU x aspect; 70 % of the width across, the band's height down, whichever is tighter.
            val a = maxOf(aspect, 0.1f)
            val tileU = minOf(0.7f / (cols + 0.18f * (cols - 1)), bandH / (maxOf(rows, 1) + 0.14f * (maxOf(rows, 1) - 1)) / a)
            val tileV = tileU * a
            val gapU = tileU * 0.18f
            val gapV = tileV * 0.14f
            val tiles = shown.mapIndexed { i, app ->
                val r = i / COLUMNS
                val inRow = minOf(COLUMNS, shown.size - r * COLUMNS)
                val c = i % COLUMNS
                val rowW = inRow * tileU + (inRow - 1) * gapU
                val u0 = 0.5f - rowW / 2f + c * (tileU + gapU)
                val v0 = 0.34f + r * (tileV + gapV)
                CardButton(CardAction.SWITCH_TO, app.label, u0, v0, u0 + tileU, v0 + tileV, app = app)
            }
            tiles + listOf(
                CardButton(CardAction.ALL_APPS, UiText.get("spatial_card_all_apps", "All apps"), 0.26f, 0.79f, 0.49f, 0.88f, primary = true),
                CardButton(CardAction.KEEP, UiText.get("spatial_card_cancel", "Cancel"), 0.51f, 0.79f, 0.74f, 0.88f),
            )
        }

        companion object {
            const val MAX_APPS = 10
            const val COLUMNS = 5
        }
    }

    data class AskBack(val label: String) : PanelCard {
        override val lines = listOf(UiText.format("spatial_card_back_would_close_line", "Back would close %1\$s.", label))
        override val v0 = 0.56f
        override val box = floatArrayOf(0.2f, 0.56f, 0.8f, 0.95f)
        override val title = UiText.format("spatial_card_back_would_close", "Back would close %1\$s", label)
        override val detail = UiText.get("spatial_card_back_anyway_question", "Go back anyway, or close this window?")
        override val buttons = listOf(
            CardButton(CardAction.BACK_ANYWAY, UiText.get("spatial_card_go_back", "Go back"), 0.24f, 0.80f, 0.49f, 0.91f),
            CardButton(CardAction.CLOSE_PANEL, UiText.get("spatial_card_close_window", "Close window"), 0.51f, 0.80f, 0.76f, 0.91f, danger = true),
        )
        override val titleV = 0.645f
        override val titleH = 0.06f
        override val detailV = 0.72f
        override val detailH = 0.042f
    }
}

/**
 * Which display has input and IME focus (0.4.2): the focused panel's, or the Space's own while
 * the launcher is open. A touch on the phone used to pull focus back to the phone (display 0),
 * so typing went nowhere until the panel was pinched again.
 */
object PanelFocus {
    fun target(launcherOpen: Boolean, spaceDisplayId: Int, focusedPanelDisplayId: Int?): Int? = when {
        launcherOpen -> spaceDisplayId.takeIf { it >= 0 }
        else -> focusedPanelDisplayId
    }

    /** The trackpad's window may take focus only while no Space is up (flat mode drives DeX windows). */
    fun trackpadFocusable(spaceActive: Boolean): Boolean = !spaceActive

    /**
     * The trackpad shows its key sink, a 1 px window that takes keys but not touches, whenever
     * its own window may not take focus (0.4.8). Without one the phone has a focused app and no
     * focused window, and a key sent to the phone (Back) waits 5 s and raises an ANR.
     */
    fun trackpadKeySink(spaceActive: Boolean): Boolean = !trackpadFocusable(spaceActive)
}

/** What a key that reached the trackpad's key sink does (0.4.8). */
enum class PhoneKeyRoute {
    /** Back for the Space, the same as a three finger swipe: closes the launcher, or Back on the focused panel. */
    SPACE_BACK,
    /** Taken and dropped: a Back down, a repeat, or a Back up that was cancelled or had no down. */
    CONSUME,
    /** Not taken: Android finishes the key as unhandled, at once, with no wait. */
    PASS,
}

/**
 * Phone Back while the Space is up (0.4.8): one Back for the Space per press, sent when the key
 * comes up, so a held Back sends one and a cancelled one (a Back gesture let go) sends none.
 * Every other key passes.
 */
class PhoneBack {
    private var downTime: Long? = null

    /** The route for a key event: [action] is `KeyEvent.ACTION_DOWN` or `ACTION_UP`, the rest the event's own. */
    fun key(keyCode: Int, action: Int, repeatCount: Int, eventDownTime: Long, canceled: Boolean): PhoneKeyRoute {
        if (keyCode != KeyRouting.KEYCODE_BACK) return PhoneKeyRoute.PASS
        return when (action) {
            ACTION_DOWN -> {
                if (repeatCount == 0) downTime = eventDownTime
                PhoneKeyRoute.CONSUME
            }
            ACTION_UP -> {
                val pressed = downTime == eventDownTime
                downTime = null
                if (pressed && !canceled) PhoneKeyRoute.SPACE_BACK else PhoneKeyRoute.CONSUME
            }
            else -> PhoneKeyRoute.CONSUME
        }
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}

/** The trackpad's status line (0.4.4: worked out each draw, and redrawn when the Space opens or closes). */
enum class TrackpadLine { DRIVING_SPACE, POINTER_OFF, NO_GLASSES, HOLDING, NONE }

object TrackpadStatus {
    /**
     * 0.4.3 only re-ran the focus flag when the Space closed and never redrew, so "Driving the
     * Space" stayed until the next touch.
     */
    fun line(spaceActive: Boolean, pointerConnected: Boolean, glassesFound: Boolean, holding: Boolean): TrackpadLine = when {
        spaceActive -> TrackpadLine.DRIVING_SPACE
        !pointerConnected -> TrackpadLine.POINTER_OFF
        !glassesFound -> TrackpadLine.NO_GLASSES
        holding -> TrackpadLine.HOLDING
        else -> TrackpadLine.NONE
    }
}

/**
 * 0.5.74 (Mike: "Turn on the flip pointer services first" "keeps appearing during the load time when i plug my
 * glasses in and keeps popping up until the glasses enter full sbs mode"): which [TrackpadLine] the trackpad should toast
 * now, if any. A plug-in starts the app's process fresh, and Android binds the pointer accessibility service a few seconds
 * later, while the Space starts and restarts on its way to side by side; the line flipped each time, and every flip was a
 * toast. Now a line is said only once it has held for [settleMs]; the warnings (pointer off, no glasses) not in the first
 * [graceMs] after the trackpad starts, and the same warning at most once a [repeatMs].
 */
class TrackpadStatusToast(
    private val startMs: Long,
    private val settleMs: Long = 3_000L,
    private val graceMs: Long = 15_000L,
    private val repeatMs: Long = 30_000L,
) {
    private var pending: TrackpadLine? = null
    private var pendingSinceMs = 0L
    private var said: TrackpadLine? = null
    private val warnedAtMs = HashMap<TrackpadLine, Long>()

    /** The line to toast at [nowMs] given this moment's [line], or null for none. */
    fun offer(line: TrackpadLine, nowMs: Long): TrackpadLine? {
        if (line != pending) {
            pending = line
            pendingSinceMs = nowMs
            return null
        }
        if (line == said || nowMs - pendingSinceMs < settleMs) return null
        val warning = line == TrackpadLine.POINTER_OFF || line == TrackpadLine.NO_GLASSES
        // A warning in the grace is held back, not said: one still standing after it is said then.
        if (warning && nowMs - startMs < graceMs) return null
        said = line
        if (!warning) return line
        val last = warnedAtMs[line]
        if (last != null && nowMs - last < repeatMs) return null
        warnedAtMs[line] = nowMs
        return line
    }
}

/**
 * Gives the focus target back after the phone's trackpad is touched, at most once per
 * [minGapMs] for the same display, so a burst of touches is one focusTopTask call.
 */
class FocusKeeper(private val minGapMs: Long = 400) {
    private var lastMs = 0L
    private var lastTarget: Int? = null

    /** The display to focus now after a trackpad touch at [nowMs], or null. */
    fun onTrackpadTouch(nowMs: Long, target: Int?): Int? {
        if (target == null) return null
        if (target == lastTarget && nowMs - lastMs < minGapMs) return null
        lastMs = nowMs
        lastTarget = target
        return target
    }
}

/**
 * Logs the Space's mode only when it really changes (0.4.2): FlipSpaces/Mode had 150 lines
 * while the head tracking reason flapped (Searching, Connecting, a byte count in NoReports) and
 * the mode stayed HEAD_LOCKED. A new mode is logged at once; a new set of reasons, compared with
 * the numbers taken out, only once it has held for [debounceMs].
 */
class ModeLogGate(private val debounceMs: Long = 5000) {
    private data class Key(val mode: SpatialMode, val reasons: Set<String>)

    private var logged: Key? = null
    private var pending: Key? = null
    private var pendingSince = 0L

    /** True when [choice] at [nowMs] should be written to the log. */
    fun offer(choice: ModeChoice, nowMs: Long): Boolean {
        val key = Key(choice.mode, choice.reasons.map(::reasonKey).toSet())
        val last = logged
        if (last == null || key.mode != last.mode) {
            logged = key
            pending = null
            return true
        }
        if (key == last) {
            pending = null
            return false
        }
        if (key != pending) {
            pending = key
            pendingSince = nowMs
            return false
        }
        if (nowMs - pendingSince < debounceMs) return false
        logged = key
        pending = null
        return true
    }

    companion object {
        private val numbers = Regex("""\d+([.,]\d+)?""")

        /** A reason with its numbers (seconds, byte counts, rates) replaced, so only its kind counts. */
        fun reasonKey(reason: String): String = reason.replace(numbers, "#")
    }
}

/** Who opens the glasses' surface when they are plugged in (0.4.2). */
enum class PlugInOpener {
    /** The phone's main screen is in front: it opens them, with its own fallback surface. */
    MAIN_SCREEN,

    /** Another Pocket XR screen is in front (trackpad, Spatial windows, hands): it opens them. */
    FRONT_SCREEN,

    /** Nothing of Pocket XR in front; the pointer accessibility service is bound and may start activities. */
    HOST_SERVICE,

    /** Nothing in front, no bound service: the shell starts the glasses' entry through Shizuku. */
    SHELL,

    /** Nothing can start an activity now; logged, and the next Pocket XR screen to come up opens them. */
    NONE,
}

object PlugIn {
    fun opener(mainInFront: Boolean, otherInFront: Boolean, hostService: Boolean, shizukuReady: Boolean): PlugInOpener = when {
        mainInFront -> PlugInOpener.MAIN_SCREEN
        otherInFront -> PlugInOpener.FRONT_SCREEN
        hostService -> PlugInOpener.HOST_SERVICE
        shizukuReady -> PlugInOpener.SHELL
        else -> PlugInOpener.NONE
    }

    /** One opening per display and surface: a reconnect opens them once, a switch from flat to Space once more. */
    fun key(displayId: Int, surface: GlassesSurface): String = "$displayId/$surface"
}
