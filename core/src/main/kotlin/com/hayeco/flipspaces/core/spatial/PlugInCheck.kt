package com.hayeco.flipspaces.core.spatial

/**
 * The plug-in check of 0.4.3: what the process wide display listener does when the displays
 * change, every branch named so each one is a FlipSpaces/Mode line. 0.4.2 returned silently on
 * most of them (no glasses, main screen in front, mirror only, already opened), so a plug-in
 * that opened nothing left nothing in the log.
 *
 * [glassesDisplayId] is null when no display is the glasses. [spaceOnGlasses] is a Space alive
 * on that display; [spaceOffGlasses] a Space alive on another display, which after an unplug is
 * one Android moved to the phone's screen. [alreadyOpened] is the display and surface already
 * claimed since the last unplug ([PlugIn.key]).
 */
data class PlugInFacts(
    val glassesDisplayId: Int?,
    val spaceOnGlasses: Boolean,
    val spaceOffGlasses: Boolean,
    val opener: PlugInOpener,
    val mirrorOnly: Boolean,
    val surface: GlassesSurface,
    val alreadyOpened: Boolean,
)

enum class PlugInBranch {
    /** No glasses and no Space: forget what was opened, so the next plug-in opens again. */
    NO_GLASSES,

    /** The glasses went away with a Space up: close it instead of letting Android move it to the phone. */
    UNPLUGGED_CLOSE_SPACE,

    /**
     * The Space is already up on the glasses. It stays, whatever the mode would now be: with
     * Shizuku down it shows placeholders and reopens its panels when the service is back
     * (0.4.1's behaviour; 0.4.2 replaced it with the flat home here).
     */
    SPACE_KEPT,

    /** The phone's main screen is in front and runs its own plug-in step. */
    MAIN_SCREEN,

    /** Android only mirrors to this display: an attempt would be denied. */
    MIRROR_ONLY,

    /** This display and surface were opened since the glasses were plugged in. */
    ALREADY_OPENED,

    OPEN_FROM_FRONT_SCREEN,
    OPEN_FROM_POINTER_SERVICE,
    OPEN_WITH_SHELL,

    /** Nothing can start an activity now; the next Pocket XR screen to come up opens them. */
    NOT_OPENED,
}

object PlugInCheck {
    fun decide(f: PlugInFacts): PlugInBranch = when {
        f.glassesDisplayId == null -> if (f.spaceOffGlasses || f.spaceOnGlasses) PlugInBranch.UNPLUGGED_CLOSE_SPACE else PlugInBranch.NO_GLASSES
        f.spaceOnGlasses -> PlugInBranch.SPACE_KEPT
        f.opener == PlugInOpener.MAIN_SCREEN -> PlugInBranch.MAIN_SCREEN
        f.mirrorOnly -> PlugInBranch.MIRROR_ONLY
        f.alreadyOpened -> PlugInBranch.ALREADY_OPENED
        else -> when (f.opener) {
            PlugInOpener.FRONT_SCREEN -> PlugInBranch.OPEN_FROM_FRONT_SCREEN
            PlugInOpener.HOST_SERVICE -> PlugInBranch.OPEN_FROM_POINTER_SERVICE
            PlugInOpener.SHELL -> PlugInBranch.OPEN_WITH_SHELL
            PlugInOpener.MAIN_SCREEN, PlugInOpener.NONE -> PlugInBranch.NOT_OPENED
        }
    }

    /** True for the branches that open something, and so claim the display and surface. */
    fun opens(branch: PlugInBranch): Boolean =
        branch == PlugInBranch.OPEN_FROM_FRONT_SCREEN || branch == PlugInBranch.OPEN_FROM_POINTER_SERVICE || branch == PlugInBranch.OPEN_WITH_SHELL

    /** The FlipSpaces/Mode line for [branch]. */
    fun line(branch: PlugInBranch, f: PlugInFacts): String {
        val d = f.glassesDisplayId
        return when (branch) {
            PlugInBranch.NO_GLASSES -> "plug-in check: no glasses display; nothing to open"
            PlugInBranch.UNPLUGGED_CLOSE_SPACE -> "plug-in check: glasses unplugged; closing the Space"
            PlugInBranch.SPACE_KEPT -> "plug-in check, display $d: the Space is already up; kept (the mode now would be ${f.surface.name})"
            PlugInBranch.MAIN_SCREEN -> "plug-in check, display $d: the main screen is in front and opens the glasses itself"
            PlugInBranch.MIRROR_ONLY -> "plug-in check, display $d: Android only mirrors to it; not opened"
            PlugInBranch.ALREADY_OPENED -> "plug-in check, display $d: ${f.surface.name} already opened since plug-in"
            PlugInBranch.OPEN_FROM_FRONT_SCREEN -> "plug-in check, display $d: opening ${f.surface.name} from the screen in front"
            PlugInBranch.OPEN_FROM_POINTER_SERVICE -> "plug-in check, display $d: opening ${f.surface.name} from the pointer service"
            PlugInBranch.OPEN_WITH_SHELL -> "plug-in check, display $d: opening ${f.surface.name} with the shell through Shizuku"
            PlugInBranch.NOT_OPENED -> "plug-in check, display $d: not opened: no Pocket XR screen in front, the pointer service is off and Shizuku is not ready"
        }
    }

    /**
     * The shell command that opens [surface] on [displayId] (0.4.3): the Space itself, full
     * screen, or for the flat home the glasses' entry, which opens it. 0.4.2 always started the
     * entry, a trampoline that had to start the Space again itself.
     */
    fun shellCommand(displayId: Int, surface: GlassesSurface, spaceComponent: String, entryComponent: String): List<String> {
        val activity = when (surface) {
            GlassesSurface.SPACE -> spaceComponent
            GlassesSurface.FLAT_HOME -> entryComponent
        }
        val fullscreen = if (surface == GlassesSurface.SPACE) listOf("--windowingMode", "1") else emptyList()
        return listOf("am", "start", "--user", "current", "--display", displayId.toString()) + fullscreen +
            listOf("-f", "0x10000000", "-n", activity)
    }

    /**
     * True when `am start` reported the activity started. 0.4.4: am's own lines decide. An
     * error line (Error, Exception, java.lang., Permission Denial) is a failure; otherwise a
     * "Starting: Intent" line is a start whatever the exit code, which Shizuku's remote process
     * does not always pass on (0.4.3 logged a failure for a Space that had started, then
     * started the entry over it). Without either, exit code 0 is a start. A "Warning: Activity
     * not started" line about a task brought to the front is a start too: the activity is up.
     */
    fun shellStarted(exitCode: Int, output: String): Boolean {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val error = lines.any { it.startsWith("Error") || it.startsWith("Exception") || it.startsWith("java.lang.") || "Permission Denial" in it }
        if (error) return false
        return exitCode == 0 || lines.any { it.startsWith("Starting: Intent") }
    }

    /**
     * What the shell start of the Space came to (0.4.4): am's report and whether the Space is
     * up on the glasses shortly after. A Space that is up is started whatever am said; only
     * when am reported a failure and no Space came up is the entry tried.
     */
    fun shellOutcome(amStarted: Boolean, spaceUp: Boolean): ShellOutcome = when {
        spaceUp && amStarted -> ShellOutcome.STARTED
        spaceUp -> ShellOutcome.STARTED_DESPITE_REPORT
        amStarted -> ShellOutcome.REPORTED_NOT_UP_YET
        else -> ShellOutcome.FAILED
    }
}

enum class ShellOutcome {
    STARTED,

    /** am reported a failure but the Space is up: logged as started, no fallback. */
    STARTED_DESPITE_REPORT,

    /** am reported a start and the Space is not up yet: logged, no fallback (a second start would land over it). */
    REPORTED_NOT_UP_YET,

    /** am reported a failure and no Space came up: the glasses' entry is tried instead. */
    FAILED,
}

/**
 * Display events for Pocket XR's own panel displays are not the glasses (0.4.3). When Shizuku
 * dies its panel displays go with it, and each removal used to run the plug-in check while
 * Shizuku was down, which replaced the Space with the flat home. A panel display is one whose
 * id the panel service reported (kept for the whole process, since a removed display's name
 * can no longer be read) or one named like a panel.
 */
object PanelDisplayFilter {
    fun isOwnPanel(displayId: Int, name: String?, reportedIds: Collection<Int>): Boolean =
        displayId in reportedIds || PanelDisplays.isPanelName(name)
}

/**
 * Binding the panel service again when Shizuku gives no answer (0.4.3): Shizuku's service
 * starter can exit (status 1 seen on the first restart) and the connection never comes, which
 * left 0.4.2 in Binding for good. After each try it waits 1, 2, 4 and 8 s, then 30 s for every
 * later try, and binds again while no service has connected.
 */
class BindRetry {
    var tries = 0
        private set

    /** A bind was just sent: how long to wait for its connection before the next try. */
    fun tried(): Long {
        tries++
        return delayMs(tries)
    }

    fun connected() {
        tries = 0
    }

    companion object {
        const val STEADY_MS = 30_000L
        private val FIRST_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

        /** The wait after try [tryNumber] (1 based). */
        fun delayMs(tryNumber: Int): Long = FIRST_MS.getOrNull(tryNumber - 1) ?: STEADY_MS
    }
}

/**
 * What happens when the panel service is back after Shizuku stopped (0.4.3). With the Space
 * still up its own recovery reopens the panels; if the Space went away while Shizuku was down
 * (it was up then) and the glasses are still plugged in, it is opened again, which restores its
 * saved panels.
 */
enum class ServiceReturn { NO_GLASSES, SPACE_UP, REOPEN_SPACE, NOTHING_TO_RESTORE }

object PanelServiceReturn {
    fun decide(glassesPlugged: Boolean, spaceOnGlasses: Boolean, spaceWasUpWhenLost: Boolean, surface: GlassesSurface): ServiceReturn = when {
        !glassesPlugged -> ServiceReturn.NO_GLASSES
        spaceOnGlasses -> ServiceReturn.SPACE_UP
        spaceWasUpWhenLost && surface == GlassesSurface.SPACE -> ServiceReturn.REOPEN_SPACE
        else -> ServiceReturn.NOTHING_TO_RESTORE
    }
}

/**
 * When the Space closes for an unplug (0.4.4). 0.4.3 closed it once Android had moved it to the
 * phone's screen (a configuration change) or after the plug-in check's settle, so it showed on
 * the phone for about half a second. Now the Space watches its own display and closes, hidden
 * and with no animation, the moment that display is removed; the move to the phone stays as
 * the fallback.
 */
object SpaceUnplug {
    const val PHONE_DISPLAY = 0

    /** Opened on the glasses: any real display but the phone's. */
    fun openedOffPhone(openedOn: Int?): Boolean = openedOn != null && openedOn >= 0 && openedOn != PHONE_DISPLAY

    /** [removedId] was just removed: close if it is the display the Space opened on, off the phone. */
    fun closeOnRemoved(openedOn: Int?, removedId: Int): Boolean = openedOffPhone(openedOn) && removedId == openedOn

    /** The Space now shows on [nowOn]: close if it was opened on the glasses and is now on the phone. */
    fun closeOnMove(openedOn: Int?, nowOn: Int?): Boolean = openedOffPhone(openedOn) && nowOn == PHONE_DISPLAY

    /**
     * Whether the Space just opened on [openedOn] is held in a root task of its own there
     * (0.4.7). The 0.4.5 retest still saw the Space on the phone for a moment on unplug: Android
     * moves a removed display's tasks to the phone before the app hears of the removal, so
     * closing from the display listener is always a frame late. 0.4.6 set the display's remove
     * content mode to destroy, but the 0.4.6 retest found Android 15 never reads that setting
     * on removal (`DisplayContent.shouldDestroyContentOnRemove` asks only the display's own
     * flag). What it does destroy is a root task created by a task organizer, with all its
     * tasks; the panel service, as the shell (MANAGE_ACTIVITY_TASKS), creates one on the
     * glasses and moves the Space's task into it, so the Space goes with the display.
     */
    fun holdOnOpen(openedOn: Int?): Boolean = openedOffPhone(openedOn)

    /**
     * Whether the holding root task is deleted when the Space closes (0.4.7): when it was on the
     * glasses and they are still there, so no empty root task is left behind. Unplugged, Android
     * has already removed it with the display.
     */
    fun releaseOnClose(openedOn: Int?, displayStillThere: Boolean): Boolean = openedOffPhone(openedOn) && displayStillThere

    /**
     * 0.5.21 (after the unplug an empty "FlipSpaces Space" root task, #9280, sat
     * on display 0): when the glasses are gone, Android moved the holding root task to the phone's display
     * rather than removing it, so the Space's close sweeps the phone's display for it.
     */
    fun sweepPhoneOnClose(openedOn: Int?, displayStillThere: Boolean): Boolean = openedOffPhone(openedOn) && !displayStillThere
}
