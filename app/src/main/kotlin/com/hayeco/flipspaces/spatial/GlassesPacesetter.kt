package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.display.DisplayManager
import com.hayeco.flipspaces.Log
import android.view.Display
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.GlassesClock
import com.hayeco.flipspaces.platform.ShizukuBridge
import java.util.concurrent.Executors

/**
 * 0.5.54, the Menu switch "Glasses set the frame clock (test)" ([SpaceStore.glassesSetFrameClock], off by default):
 * makes the glasses SurfaceFlinger's pacesetter with `sfdo force-pacesetter <id>` through Shizuku's shell, and undoes it
 * with `sfdo reset-forced-pacesetter`. [GlassesClock.Planner] decides when; every force and reset is logged with its
 * result and the glasses' mode at that moment, and each force is checked in `dumpsys SurfaceFlinger`. Nothing is sent to
 * the glasses and no display mode changes. The id is read each time from the glasses' Display, never assumed.
 * 0.5.58: on the Developer card only, OFF checks the phone really has the clock back ([reset]),
 * and the pacesetter goes in the 5 s clock line ([pacesetterNote]).
 *
 * Callers are on the main thread; the shell commands run in order on one background thread.
 */
object GlassesPacesetter {
    private const val TAG = "FlipSpaces/Clock"

    /** A moment for SurfaceFlinger to apply the force before the dump is read. */
    private const val CHECK_AFTER_MS = 300L

    /** 0.5.58: the pacesetter read for the 5 s clock line at most this often. */
    private const val NOTE_EVERY_MS = 5_000L

    private val planner = GlassesClock.Planner()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FlipSpacesClock").apply { isDaemon = true } }

    /** The last force or reset's outcome, for the Menu. */
    @Volatile
    var lastResult: String? = null
        private set

    /** The plug-in check ran ([com.hayeco.flipspaces.GlassesAutoOpen]): [displayId] is the glasses' display, or null. */
    fun displayCheck(context: Context, displayId: Int?) {
        val glasses = displayId?.let { glasses(context, it) }
        apply(context, synchronized(planner) { planner.displayCheck(SpaceStore(context).glassesSetFrameClock, glasses) }, glasses)
    }

    fun spaceStarted(context: Context, displayId: Int) {
        val glasses = glasses(context, displayId)
        apply(context, synchronized(planner) { planner.spaceStarted(SpaceStore(context).glassesSetFrameClock, glasses) }, glasses)
    }

    fun spaceEnded(context: Context, displayId: Int) {
        apply(context, synchronized(planner) { planner.spaceEnded() }, glasses(context, displayId))
    }

    /** The Menu switch changed; [displayId] is the glasses' display now, or null. */
    fun switchChanged(context: Context, on: Boolean, displayId: Int?) {
        SpaceStore(context).glassesSetFrameClock = on
        val glasses = displayId?.let { glasses(context, it) }
        Log.i(TAG, "Glasses set the frame clock (test): switch ${if (on) "ON" else "OFF"} (glasses ${glasses?.modeLine() ?: "not connected"}; ${ShizukuBridge.status(context).label})")
        apply(context, synchronized(planner) { planner.switchChanged(on, glasses) }, glasses)
    }

    /** For the Menu: why the switch cannot work now (no Shizuku), or null. */
    fun unavailable(context: Context): String? = ShizukuBridge.status(context).takeIf { it != ShizukuStatus.READY }?.label

    private fun apply(context: Context, step: GlassesClock.Step, glasses: GlassesClock.Glasses?) {
        val app = context.applicationContext
        when (step) {
            GlassesClock.Step.None -> Unit
            is GlassesClock.Step.Force -> worker.execute { force(app, step) }
            is GlassesClock.Step.Reset -> {
                // 0.5.58: the phone screen in use, for the give back when the reset leaves the glasses as the pacesetter.
                val phone = phoneScreenId(app)
                worker.execute { reset(app, step.why, glasses, phone) }
            }
        }
    }

    /** 0.5.58: told on the main thread what a switch OFF ended in (the phone has the clock back, or why not), for a toast. */
    @Volatile
    var onGiveBack: ((String) -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 0.5.58: SurfaceFlinger's pacesetter as last read ([pacesetterNote]), and when. */
    @Volatile
    private var note: String = "pacesetter not read yet"
    @Volatile
    private var noteReadMs = 0L
    @Volatile
    private var noteAsked = false

    /**
     * 0.5.58: for the 5 s clock line, SurfaceFlinger's pacesetter (the glasses or a phone screen,
     * forced or not), read with Shizuku's shell at most every [NOTE_EVERY_MS]. Returns the last reading at once; any thread.
     * It only reads `dumpsys SurfaceFlinger`: nothing is set.
     */
    fun pacesetterNote(context: Context): String {
        val now = android.os.SystemClock.uptimeMillis()
        if (!noteAsked && now - noteReadMs >= NOTE_EVERY_MS) {
            val app = context.applicationContext
            if (ShizukuBridge.status(app) != ShizukuStatus.READY) return "pacesetter not read (${ShizukuBridge.status(app).label})"
            noteAsked = true
            worker.execute {
                try {
                    val glassesId = com.hayeco.flipspaces.GlassesAutoOpen.glassesDisplayId()?.let { glasses(app, it) }?.let { it.physicalId ?: idFromDump(it) }
                    note = GlassesClock.pacesetterNote(GlassesClock.parsePacesetter(ShizukuBridge.shell(GlassesClock.CHECK_COMMAND).output), glassesId)
                } catch (t: Throwable) {
                    note = "pacesetter not read (${t.javaClass.simpleName})"
                } finally {
                    noteReadMs = android.os.SystemClock.uptimeMillis()
                    noteAsked = false
                }
            }
        }
        return note
    }

    private fun force(context: Context, step: GlassesClock.Step.Force) {
        val g = step.glasses
        val shizuku = ShizukuBridge.status(context)
        if (shizuku != ShizukuStatus.READY) {
            synchronized(planner) { planner.forceFailed(g) }
            lastResult = "not forced: ${shizuku.label}"
            Log.w(TAG, "force pacesetter (${step.why}): not run, ${shizuku.label}; mode ${g.modeLine()}")
            return
        }
        val id = g.physicalId ?: idFromDump(g)
        if (id == null) {
            synchronized(planner) { planner.forceFailed(g) }
            lastResult = "not forced: the glasses' SurfaceFlinger id could not be read"
            Log.w(TAG, "force pacesetter (${step.why}): not run, no SurfaceFlinger id for display ${g.displayId} (${g.name}); mode ${g.modeLine()}")
            return
        }
        val result = ShizukuBridge.shell(GlassesClock.forceCommand(id))
        Thread.sleep(CHECK_AFTER_MS)
        val check = ShizukuBridge.shell(GlassesClock.CHECK_COMMAND)
        val seen = GlassesClock.parsePacesetter(check.output)
        val confirm = GlassesClock.confirmLine(g, id, seen)
        lastResult = if (seen.pacesetter == id) "the glasses set the clock (${g.modeLine()})" else "forced, but SurfaceFlinger's pacesetter is not the glasses"
        Log.i(TAG, "force pacesetter (${step.why}): sfdo force-pacesetter ${GlassesClock.idText(id)} exit ${result.exitCode}${result.output.trim().takeIf { it.isNotEmpty() }?.let { ", \"$it\"" } ?: ""}; $confirm; mode ${g.modeLine()} on display ${g.displayId}")
    }

    /**
     * The reset, then (0.5.58) the give back: vc78's `reset-forced-pacesetter` cleared the force but
     * left the glasses as the pacesetter for 3.6 minutes with the switch OFF. While the dump still says the glasses, the
     * phone screen in use ([phoneId]) is forced and reset in turn, once, and read again; if that fails too, the Menu says to
     * unplug the glasses or turn the cover off and on.
     */
    private fun reset(context: Context, why: String, glasses: GlassesClock.Glasses?, phoneId: Long?) {
        val mode = glasses?.modeLine() ?: "the glasses are not connected"
        val shizuku = ShizukuBridge.status(context)
        if (shizuku != ShizukuStatus.READY) {
            lastResult = "not reset: ${shizuku.label}; ${GlassesClock.STUCK_ADVICE}"
            Log.w(TAG, "reset pacesetter ($why): not run, ${shizuku.label}; mode $mode")
            tell(lastResult!!)
            return
        }
        val glassesId = glasses?.let { it.physicalId ?: idFromDump(it) }
        var seen = resetAndRead(why, mode)
        var step = GlassesClock.afterReset(seen, glassesId, phoneId, triedPhone = false)
        if (step is GlassesClock.GiveBack.ForcePhone) {
            val forced = ShizukuBridge.shell(GlassesClock.forceCommand(step.phoneId))
            Log.i(TAG, "give back ($why): the glasses are still the pacesetter after the reset; sfdo force-pacesetter ${GlassesClock.idText(step.phoneId)} (the phone screen in use) exit ${forced.exitCode}${forced.output.trim().takeIf { it.isNotEmpty() }?.let { ", \"$it\"" } ?: ""}")
            Thread.sleep(GlassesClock.PHONE_FORCE_HOLD_MS)
            seen = resetAndRead("$why, after forcing the phone screen", mode)
            step = GlassesClock.afterReset(seen, glassesId, phoneId, triedPhone = true)
        }
        val line = when (step) {
            is GlassesClock.GiveBack.Done -> step.line
            is GlassesClock.GiveBack.Stuck -> step.line
            is GlassesClock.GiveBack.ForcePhone -> "the give back did not finish"
        }
        lastResult = line
        note = GlassesClock.pacesetterNote(seen, glassesId)
        noteReadMs = android.os.SystemClock.uptimeMillis()
        if (step is GlassesClock.GiveBack.Done) Log.i(TAG, "give back ($why): $line") else Log.w(TAG, "give back ($why): $line")
        tell(line)
    }

    private fun resetAndRead(why: String, mode: String): GlassesClock.Pacesetter {
        val result = ShizukuBridge.shell(GlassesClock.RESET_COMMAND)
        val check = ShizukuBridge.shell(GlassesClock.CHECK_COMMAND)
        val seen = GlassesClock.parsePacesetter(check.output)
        Log.i(TAG, "reset pacesetter ($why): sfdo reset-forced-pacesetter exit ${result.exitCode}${result.output.trim().takeIf { it.isNotEmpty() }?.let { ", \"$it\"" } ?: ""}; now pacesetterDisplayId ${seen.pacesetter?.let(GlassesClock::idText) ?: "not found"}, forcedPacesetterDisplayId ${seen.forced?.let(GlassesClock::idText) ?: "nullopt"}; mode $mode")
        return seen
    }

    private fun tell(line: String) {
        val l = onGiveBack ?: return
        main.post { l(line) }
    }

    /** 0.5.58: SurfaceFlinger's id of the phone screen in use (the cover when folded), from its Display's address; or null. */
    private fun phoneScreenId(context: Context): Long? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val inUse = runCatching { com.hayeco.flipspaces.CoverTrackpad.reading().inUseDisplayId }.getOrNull()
        val d = inUse?.let { dm.getDisplay(it) } ?: dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        if (d.displayId == com.hayeco.flipspaces.GlassesAutoOpen.glassesDisplayId()) return null
        return physicalId(d)
    }

    /** The glasses' SurfaceFlinger id by name from `dumpsys SurfaceFlinger --display-ids`, when the Display's address could not be read. */
    private fun idFromDump(g: GlassesClock.Glasses): Long? {
        val dump = ShizukuBridge.shell(GlassesClock.DISPLAY_IDS_COMMAND).output
        return GlassesClock.physicalIdFromDump(dump, g.name)?.also { Log.i(TAG, "the glasses' SurfaceFlinger id ${GlassesClock.idText(it)} read from dumpsys by name (${g.name})") }
    }

    private fun glasses(context: Context, displayId: Int): GlassesClock.Glasses? {
        val d = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val mode = d.mode
        return GlassesClock.Glasses(displayId, d.name ?: "", mode.physicalWidth, mode.physicalHeight, mode.refreshRate, physicalId(d))
    }

    /** DisplayAddress.Physical of [d] (hidden from the SDK: read by reflection), or null; then the dump by name. */
    private fun physicalId(d: Display): Long? = runCatching {
        val address = Display::class.java.getMethod("getAddress").invoke(d) ?: return@runCatching null
        if (address.javaClass.name != "android.view.DisplayAddress\$Physical") return@runCatching null
        address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as? Long
    }.getOrNull()
}
