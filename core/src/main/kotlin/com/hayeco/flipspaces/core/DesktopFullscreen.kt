package com.hayeco.flipspaces.core

/** What to ask Android for when starting an activity on the glasses or DeX display. */
data class WindowRequest(
    val displayId: Int,
    /** Hidden WindowConfiguration windowing mode, or null to leave it to the system. */
    val windowingMode: Int?,
    /** Null means no launch bounds, so the window is not pinned to a freeform size. */
    val bounds: Bounds?,
) {
    /** What this attempt relies on, for the log. */
    val label: String
        get() = when {
            windowingMode != null -> "fullscreen windowing mode"
            bounds != null -> "launch bounds of the whole display (${bounds.width}x${bounds.height})"
            else -> "display id only (manifest layout and requestFullscreenMode)"
        }
}

/** What a running activity should do to fill its display. */
data class FullscreenPlan(
    /** Activity#requestFullscreenMode (Android 14+): turns a freeform window into a full screen one. */
    val requestFullscreen: Boolean,
    /** Hide status and navigation bars (and the DeX taskbar) with WindowInsetsController. */
    val hideSystemBars: Boolean,
    /**
     * 0.5.0: the fallback for a window still freeform: hide its caption bar as well (an inset
     * type since Android 11) and ask for a transparent caption background (Android 15).
     */
    val hideCaption: Boolean = false,
) {
    val isNone: Boolean get() = !requestFullscreen && !hideSystemBars && !hideCaption

    companion object {
        val NONE = FullscreenPlan(requestFullscreen = false, hideSystemBars = false)
    }
}

/**
 * Samsung DeX opens every activity in a freeform window, so the Spaces home showed up as a small
 * window on the DeX monitor. These rules decide how to make it fill the display. The phone's own
 * display (with DeX off) is never touched.
 */
object DesktopFullscreen {
    const val WINDOWING_MODE_UNDEFINED = 0
    const val WINDOWING_MODE_FULLSCREEN = 1
    const val WINDOWING_MODE_FREEFORM = 5
    const val DEFAULT_DISPLAY = 0
    const val SDK_FULLSCREEN_REQUEST = 34

    /** Full screen on [displayId] with no freeform bounds: the home surface and the Space. */
    fun fullscreenLaunch(displayId: Int): WindowRequest =
        WindowRequest(displayId, WINDOWING_MODE_FULLSCREEN, bounds = null)

    /** The home surface: full screen on [displayId] with no freeform bounds. */
    fun homeLaunch(displayId: Int): WindowRequest = fullscreenLaunch(displayId)

    /** The Space (SpaceActivity): full screen on [displayId] with no freeform bounds. */
    fun spaceLaunch(displayId: Int): WindowRequest = fullscreenLaunch(displayId)

    /**
     * The launches to try in order for a full screen start on [displayId]: fullscreen windowing
     * first; if Android refuses the hidden windowing mode (Android 16 and One UI 8 deny
     * ActivityOptions.setLaunchWindowingMode to apps, Mike's 0.3.5 log), launch bounds covering
     * the whole display when its size [display] is known, so a DeX freeform window opens at full
     * size; then the display id alone, which leaves it to the manifest layout (100% of the
     * display) and the activity's own requestFullscreenMode.
     */
    fun fullscreenAttempts(displayId: Int, display: Bounds? = null): List<WindowRequest> =
        listOfNotNull(
            fullscreenLaunch(displayId),
            display?.takeIf { !it.isEmpty }?.let { WindowRequest(displayId, windowingMode = null, bounds = it) },
            WindowRequest(displayId, windowingMode = null, bounds = null),
        )

    /**
     * Samsung's Configuration.semDesktopModeEnabled compared with SEM_DESKTOP_MODE_ENABLED, both
     * read by reflection. Null means the field is missing (not a Samsung build).
     */
    fun isDexDesktop(semDesktopModeEnabled: Int?, enabledValue: Int?): Boolean =
        semDesktopModeEnabled != null && enabledValue != null && semDesktopModeEnabled == enabledValue

    /**
     * True when [window] is visibly smaller than [display], or (0.5.0) when it is a [freeform]
     * window at any size. [slackPx] absorbs rounding so a window that already fills the display
     * is left alone. Mike's 0.4.8 Space opened as a 1920x1080 DeX freeform window, launch bounds
     * of the whole display, and only the size was compared, so it counted as full screen and
     * kept its caption bar and the DeX taskbar.
     */
    fun isWindowed(window: Bounds, display: Bounds, slackPx: Int = 8, freeform: Boolean = false): Boolean =
        freeform || window.width < display.width - slackPx || window.height < display.height - slackPx

    /**
     * The windowing mode in a Configuration's text (`mWindowingMode=freeform` inside its
     * winConfig), since WindowConfiguration itself is hidden from apps. Null when it says none.
     */
    fun windowingModeIn(configuration: String): Int? {
        val name = Regex("mWindowingMode=([a-z-]+)").find(configuration)?.groupValues?.get(1) ?: return null
        return when (name) {
            "fullscreen" -> WINDOWING_MODE_FULLSCREEN
            "freeform" -> WINDOWING_MODE_FREEFORM
            "undefined" -> WINDOWING_MODE_UNDEFINED
            "pinned" -> 2
            "multi-window" -> 6
            else -> null
        }
    }

    /**
     * [immersive] is for the home surface: it hides the bars too. Other activities are only
     * maximized, and only when they are on an external or DeX display.
     */
    fun plan(
        displayId: Int,
        dexDesktop: Boolean,
        windowed: Boolean,
        sdkInt: Int,
        immersive: Boolean,
        freeform: Boolean = false,
    ): FullscreenPlan {
        val desktopDisplay = displayId != DEFAULT_DISPLAY || dexDesktop
        if (!desktopDisplay) return FullscreenPlan.NONE
        return FullscreenPlan(
            requestFullscreen = windowed && sdkInt >= SDK_FULLSCREEN_REQUEST,
            hideSystemBars = immersive && displayId != DEFAULT_DISPLAY,
            hideCaption = immersive && freeform && displayId != DEFAULT_DISPLAY,
        )
    }
}

/** What the panel service does to hold the Space full screen in a root task of its own (0.5.0). */
enum class HoldStep {
    /** The Space's task is already in a full screen root task with nothing else: leave it. */
    ALREADY_HELD,

    /** The Space's task is a root task itself: create a full screen root task and move it in (0.4.7). */
    MOVE_ROOT,

    /**
     * The Space's task sits inside another root task that is not full screen (Mike's 0.4.8: the
     * DeX "Desk", freeform): move the Space's own task out into a new full screen root task and
     * clear its freeform mode and bounds, so DeX drops its caption and the taskbar.
     */
    MOVE_LEAF,
}

/** Which ITaskOrganizerController calls make and remove the Space's own task (0.5.1). */
enum class TaskApi(val create: String, val delete: String) {
    /** Android 17 (SDK 37): createTask(TaskCreationParams), deleteTask(WindowContainerToken). */
    CREATE_TASK("createTask", "deleteTask"),

    /** Android 15 and 16: createRootTask(displayId, windowingMode, launchCookie[, removeWithTaskOrganizer]), deleteRootTask. */
    CREATE_ROOT_TASK("createRootTask", "deleteRootTask"),

    /** Neither is there: the Space cannot be held in a task of its own. */
    NONE("", ""),
}

/**
 * 0.5.1: Android 17 replaced createRootTask and deleteRootTask with createTask(TaskCreationParams)
 * and deleteTask (as we observed on the Flip itself), and 0.5.0's hold failed with
 * "NoSuchMethodException createRootTask".
 * The API is picked by what the controller really has, each method as (name, parameter count),
 * so the next change logs as a clear line rather than "expected parameters".
 */
object TaskHoldApi {
    const val SDK_CREATE_TASK = 37

    fun pick(methods: List<Pair<String, Int>>, sdkInt: Int): TaskApi {
        val createTask = methods.any { it.first == "createTask" && it.second == 1 } && methods.any { it.first == "deleteTask" && it.second == 1 }
        val createRoot = methods.any { it.first == "createRootTask" && it.second >= 3 } && methods.any { it.first == "deleteRootTask" && it.second == 1 }
        return when {
            createTask && (sdkInt >= SDK_CREATE_TASK || !createRoot) -> TaskApi.CREATE_TASK
            createRoot -> TaskApi.CREATE_ROOT_TASK
            else -> TaskApi.NONE
        }
    }

    /** The log line for [api]: what is used, or every task method the controller has when none fits. */
    fun line(api: TaskApi, methods: List<Pair<String, Int>>, sdkInt: Int): String = when (api) {
        TaskApi.NONE -> "no task API to hold the Space on Android $sdkInt: the task organizer has " +
            methods.filter { it.first.contains("Task") }.distinct().joinToString { "${it.first}(${it.second})" }.ifEmpty { "no task methods" }
        else -> "${api.create} and ${api.delete} on Android $sdkInt"
    }
}

/** How the Space shows on the glasses (0.5.1). */
enum class SpaceSurface {
    /** The activity's own window: full screen, or held in a task of its own. */
    WINDOW,

    /**
     * A Presentation on the glasses display drawn over everything of the app's: not a task, so
     * desktop windowing cannot put it in the freeform Desk or give it a caption.
     */
    PRESENTATION,

    /** 0.5.22: the task hold moved the Space but the window still reads freeform: look again once a moment later. 0.5.23: no longer chosen ([SpaceSurfacePlan.choose]). */
    RECHECK,
}

/**
 * 0.5.1: when the system keeps the Space freeform even after the task hold (One UI 9 desktop
 * windowing), the Space is drawn in a Presentation instead. Only off the phone's own display,
 * and only once the hold has had its say ([holdSettled]), so a hold that works is not raced.
 */
object SpaceSurfacePlan {
    /**
     * 1.6.8: whether the Space's own surface is shorter than its display by about a caption's height, the
     * sign of a caption drawn inside the window (One UI 5 on Android 13 and older). The DeX taskbar or a
     * rounding of a pixel or two do not count: only 24 to 120 rows missing at the full width.
     * 1.6.28 (Crr66: "a small white bar in the space", surface 1920x1068): DeX's title bar can be made slimmer (its
     * three dots), and that one was 12 rows, so from 8 rows now. A rounding of a pixel or two still does not count, and
     * the activity still waits for the shortfall to stay.
     */
    fun captionInWindow(surfaceW: Int, surfaceH: Int, displayW: Int, displayH: Int): Boolean {
        if (surfaceW <= 0 || surfaceH <= 0 || displayW <= 0 || displayH <= 0) return false
        val missing = displayH - surfaceH
        return surfaceW >= displayW && missing in CAPTION_MIN_ROWS..CAPTION_MAX_ROWS
    }

    private const val CAPTION_MIN_ROWS = 8
    private const val CAPTION_MAX_ROWS = 120

    /**
     * 0.5.7: [windowOnly] when a full screen task was not seen on this display, so the Space stays
     * in the DeX window that is; [takesPresentation] false for a display that is not a public
     * presentation display, where Android refuses the Presentation (Mike's 0.5.6 display 54).
     */
    fun choose(
        displayId: Int,
        freeform: Boolean,
        holdSettled: Boolean,
        forced: Boolean = false,
        windowOnly: Boolean = false,
        takesPresentation: Boolean = true,
        holdMovedIt: Boolean = false,
        composed: Boolean = false,
        triedHere: Boolean = false,
        captionInWindow: Boolean = false,
    ): SpaceSurface = when {
        displayId == DesktopFullscreen.DEFAULT_DISPLAY -> SpaceSurface.WINDOW
        forced -> SpaceSurface.PRESENTATION
        windowOnly || !takesPresentation -> SpaceSurface.WINDOW
        // 0.5.23 (9 Presentation failures, each after SurfaceFlinger had already said the Space
        // was composed full screen, 900 ms before; the 0.5.22 recheck looked at the window's freeform flag, which
        // stays stale): proof the hold worked wins over that flag. No Presentation once SurfaceFlinger said
        // composed ([composed]), once the hold answered that it moved the Space ([holdMovedIt]), or a second time on
        // the same display ([triedHere]).
        // 1.6.8 (Crr66, Galaxy S20, Android 13): One UI 5 draws the DeX caption inside the app's own window
        // (its DecorView, "hasWindowDecorCaption=true"), so SurfaceFlinger finds no DeX layer and says
        // composed, while the white title bar stays over the Space (its surface 1920x1028 of 1920x1080).
        // A caption seen in the window ([captionInWindow]) wins over composed and the hold's answer.
        captionInWindow && freeform && holdSettled && !triedHere -> SpaceSurface.PRESENTATION
        composed || holdMovedIt || triedHere -> SpaceSurface.WINDOW
        freeform && holdSettled -> SpaceSurface.PRESENTATION
        else -> SpaceSurface.WINDOW
    }
}

/** 0.5.2: what an "open the Space" does when asked on a display. */
enum class SpaceStartStep {
    /** Not running there: start the activity (shell or app start, as before). */
    START,

    /**
     * Already running there: no second startActivity, which on One UI 9 moves the held task back
     * into the DeX Desk (freeform, with the taskbar and a light outline round it, Mike's 0.5.1);
     * the panel service brings its task to the front instead.
     */
    BRING_FORWARD,
}

/** What the Space does when its window's freeform state changes (0.5.2). */
enum class SlipStep {
    /** Nothing to do. */
    NONE,

    /** It was full screen and turned freeform: the DeX Desk took it back; hold it again first. */
    HOLD_AGAIN,

    /** Freeform with no hold to try (no panel service): judge the surface (Presentation fallback). */
    CHECK_SURFACE,
}

/**
 * 0.5.2, from Mike's 0.5.1 log: the createTask hold
 * worked (task 7899 into full screen task 7900, the DeX taskbar hidden), then a second start of
 * the Space from the main screen 8 s later moved the task back into the Desk as freeform, which
 * brought back the taskbar and the Desk's freeform outline (the thin light border).
 */
object SpaceStart {
    fun decide(runningOnDisplay: Boolean): SpaceStartStep =
        if (runningOnDisplay) SpaceStartStep.BRING_FORWARD else SpaceStartStep.START

    /**
     * The window is [freeform] now and was [wasFreeform]; [holdAvailable] when the panel service
     * is ready. A slip back into the Desk is held again before any Presentation is considered.
     */
    fun onWindowMode(wasFreeform: Boolean, freeform: Boolean, holdAvailable: Boolean, presentationShown: Boolean): SlipStep = when {
        !freeform || presentationShown -> SlipStep.NONE
        holdAvailable -> if (wasFreeform) SlipStep.NONE else SlipStep.HOLD_AGAIN
        else -> SlipStep.CHECK_SURFACE
    }
}

/**
 * 0.5.0: 0.4.7's hold said "already held" for any root task with child tasks, and on DeX that
 * was the freeform Desk, so the Space stayed freeform with a caption and the DeX taskbar
 * (Mike's 0.4.8 log: "already held in root task 7745", Task #7745 name=Desk mode=freeform).
 */
object SpaceHold {
    fun decide(rootHasChildTasks: Boolean, rootWindowingMode: Int?, rootIsOurs: Boolean): HoldStep = when {
        !rootHasChildTasks -> HoldStep.MOVE_ROOT
        rootIsOurs || rootWindowingMode == DesktopFullscreen.WINDOWING_MODE_FULLSCREEN -> HoldStep.ALREADY_HELD
        else -> HoldStep.MOVE_LEAF
    }
}
