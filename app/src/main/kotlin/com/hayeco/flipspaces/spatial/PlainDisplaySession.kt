package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.SpacesLauncher
import com.hayeco.flipspaces.SpacesState
import com.hayeco.flipspaces.core.ContentMode
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.PlainDisplayPlan
import com.hayeco.flipspaces.core.spatial.PlainDisplayPlan.Stage
import com.hayeco.flipspaces.core.spatial.PlainDisplayPlan.Stop
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.ShizukuBridge

/**
 * 0.5.9, experimental, off by default ("Plain glasses display without DeX"): for the Space's
 * session the glasses run as a plain external display with DeX off, so the Space can fill them
 * with no DeX taskbar, title bar or border ([PlainDisplayPlan]).
 *
 * On plug-in, through Shizuku: the settings' values are read and saved (in preferences, so a crash
 * still puts them back), DeX is switched off and mirroring off, and every change is logged. Then
 * the glasses must come back within [PlainDisplayPlan.DISPLAY_WAIT_MS] as a display apps can open
 * on that DeX does not own; the shell starts the Space there full screen and SpaceActivity's
 * SurfaceFlinger check must see it. Any step that does not hold puts the settings back and the
 * plug-in check opens the Space in DeX as before, so the glasses are never left on nothing for
 * long. The settings are also put back when the Space closes, when the glasses unplug, and when
 * the app starts with a session left over. Main thread, except the shell calls.
 */
object PlainDisplaySession {
    private const val TAG = "FlipSpaces/Mode"
    private const val PREFS = "plain_display"
    private const val KEY_PENDING = "pending_restore"
    private const val POLL_MS = 300L

    private val main = Handler(Looper.getMainLooper())

    @Volatile var stage: Stage = Stage.IDLE
        private set

    /** Bumped for each session; a Space started by one carries it ([claim]), so only its close ends that session. */
    @Volatile var token = 0
        private set

    /** The glasses display the session started the Space on. */
    @Volatile var displayId: Int? = null
        private set

    /** Tried once per plug-in (cleared by [unplugged]): a failed try does not loop. */
    private var triedThisPlug = false

    private var onFallback: (() -> Unit)? = null
    private var waitStartMs = 0L

    /** True while the session is switching or waiting: the plug-in check stays out of its way. */
    val busy: Boolean get() = stage == Stage.SWITCHING || stage == Stage.WAITING_FOR_DISPLAY || stage == Stage.STARTING || stage == Stage.RESTORING

    /** Should the plug-in check hand this plug-in to the session? */
    fun wanted(context: Context): Boolean =
        !triedThisPlug && stage == Stage.IDLE && SpaceStore(context).plainDisplay && ShizukuBridge.status(context) == ShizukuStatus.READY

    /**
     * Starts the session for the glasses on [glassesId]. [fallback] runs (main thread) if it
     * stops without the Space showing: the plug-in check then opens the Space in DeX.
     */
    fun begin(context: Context, glassesId: Int, fallback: () -> Unit) {
        val app = context.applicationContext
        triedThisPlug = true
        stage = Stage.SWITCHING
        token++
        onFallback = fallback
        Log.i(TAG, "plain display session (experimental) for the glasses on display $glassesId: switching DeX off so the Space can fill them; every change below is put back when the Space closes or the glasses unplug")
        Thread {
            val read = PlainDisplayPlan.CHANGES.associate { c ->
                val r = ShizukuBridge.shell(listOf("settings", "get", c.table, c.key))
                "${c.table}/${c.key}" to if (r.exitCode == 0) r.output.trim() else null
            }
            val changes = PlainDisplayPlan.withBefore(read)
            if (PlainDisplayPlan.alreadyPlain(changes)) {
                main.post { Log.i(TAG, "plain display session: the settings already have the session's values (${changes.joinToString { it.line() }}); nothing to change") }
            } else {
                // Saved before the first change, so a crash in between still restores.
                prefs(app).edit().putString(KEY_PENDING, PlainDisplayPlan.encode(changes)).commit()
                for (c in changes) {
                    if (c.before == c.value) continue
                    val r = ShizukuBridge.shell(c.putCommand())
                    if (r.exitCode != 0) {
                        main.post { stop(app, Stop.SHELL_FAILED, "settings put ${c.table} ${c.key}: exit ${r.exitCode}, ${r.output.trim()}") }
                        return@Thread
                    }
                    main.post { Log.i(TAG, "plain display session: changed ${c.line()} (to be put back)") }
                }
            }
            main.post {
                if (stage != Stage.SWITCHING) return@post
                stage = Stage.WAITING_FOR_DISPLAY
                waitStartMs = SystemClock.uptimeMillis()
                poll(app)
            }
        }.start()
    }

    /** Waits for the glasses to be a display apps can open on that DeX does not own, then starts the Space there. */
    private fun poll(app: Context) {
        if (stage != Stage.WAITING_FOR_DISPLAY) return
        val connected = DisplaySelector.state(DisplayMonitor(app).snapshot(), SpacesState.choices.value.preferredDisplayId) as? GlassesState.Connected
        val display = connected?.display
        val assessment = display?.let { SpacesLauncher.assess(app, it) }
        if (display != null && assessment != null && PlainDisplayPlan.usable(assessment.mode == ContentMode.EXTENDED, assessment.mode == ContentMode.DESKTOP)) {
            stage = Stage.STARTING
            displayId = display.id
            Log.i(TAG, "plain display session: the glasses are display ${display.id}, ${assessment.mode.label} (${assessment.evidence}); starting the Space there full screen")
            val space = "${app.packageName}/${SpaceActivity::class.java.name}"
            Thread {
                val r = ShizukuBridge.shell(listOf("am", "start", "--user", "current", "--display", display.id.toString(), "--windowingMode", "1", "-f", "0x10000000", "-n", space))
                main.post {
                    Log.i(TAG, "plain display session: shell start of the Space on display ${display.id}: exit ${r.exitCode}, ${r.output.trim().ifEmpty { "no output" }}")
                    if (stage == Stage.STARTING) stage = Stage.CHECKING
                    // The Space's own check reports back ([seen], [notSeen]); if it never starts, time out.
                    main.postDelayed({ if (stage == Stage.CHECKING && SpaceActivity.runningOn(display.id) == null) stop(app, Stop.SPACE_NOT_SEEN, "the Space did not start on display ${display.id}") }, 5_000)
                }
            }.start()
            return
        }
        if (SystemClock.uptimeMillis() - waitStartMs >= PlainDisplayPlan.DISPLAY_WAIT_MS) {
            stop(app, Stop.DISPLAY_NEVER_USABLE, display?.let { "display ${it.id}: ${assessment?.mode?.label} (${assessment?.evidence})" } ?: "no glasses display")
            return
        }
        main.postDelayed({ poll(app) }, POLL_MS)
    }

    /** A Space starting on [displayId] while the session starts one belongs to it: returns its token, else 0. */
    fun claim(displayId: Int): Int =
        if ((stage == Stage.STARTING || stage == Stage.CHECKING) && this.displayId == displayId) token else 0

    /** The session's Space was seen on the glasses: the session holds until the Space closes or they unplug. */
    fun seen(owner: Int, detail: String) {
        if (owner == 0 || owner != token || stage != Stage.CHECKING) return
        stage = Stage.ACTIVE
        Log.i(TAG, "plain display session: the glasses show the Space full screen on display $displayId with no DeX ($detail)")
    }

    /** The session's Space was not seen: put DeX back; the plug-in check opens the Space in DeX. */
    fun notSeen(context: Context, owner: Int, detail: String) {
        if (owner == 0 || owner != token) return
        stop(context.applicationContext, Stop.SPACE_NOT_SEEN, detail)
    }

    /** The session's Space closed (not for a restore): put the settings back. */
    fun spaceClosed(context: Context, owner: Int) {
        if (owner == 0 || owner != token || stage == Stage.IDLE || stage == Stage.RESTORING) return
        stop(context.applicationContext, Stop.SPACE_CLOSED, "display $displayId", fallback = false)
    }

    /** The glasses are gone: put the settings back, and the next plug-in may try again. */
    fun unplugged(context: Context) {
        triedThisPlug = false
        if (stage != Stage.IDLE && stage != Stage.RESTORING) stop(context.applicationContext, Stop.UNPLUGGED, "", fallback = false)
    }

    /** At start, or when Shizuku is back: a session left over from a process that stopped is put back. */
    fun restoreLeftOver(context: Context) {
        val app = context.applicationContext
        if (stage != Stage.IDLE || prefs(app).getString(KEY_PENDING, null) == null) return
        if (ShizukuBridge.status(app) != ShizukuStatus.READY) {
            Log.w(TAG, "plain display session: settings from an earlier session wait to be put back until Shizuku is ready")
            return
        }
        stop(app, Stop.LEFT_OVER, "", fallback = false)
    }

    /** Puts every saved setting back, logs each, and runs the fallback when [fallback]. */
    private fun stop(app: Context, why: Stop, detail: String, fallback: Boolean = true) {
        if (stage == Stage.RESTORING) return
        stage = Stage.RESTORING
        val changes = PlainDisplayPlan.decode(prefs(app).getString(KEY_PENDING, null))
        Log.w(TAG, "plain display session ends: ${why.label}${if (detail.isEmpty()) "" else " ($detail)"}; putting back ${changes.size} setting(s)")
        Thread {
            val failed = mutableListOf<String>()
            for (c in changes.reversed()) {
                val r = ShizukuBridge.shell(c.restoreCommand())
                val ok = r.exitCode == 0
                if (!ok) failed += "${c.table} ${c.key}"
                main.post { Log.i(TAG, "plain display session: put back ${c.table} ${c.key} to ${c.before ?: "unset"}${if (ok) "" else " FAILED: exit ${r.exitCode}, ${r.output.trim()}"}") }
            }
            if (failed.isEmpty()) prefs(app).edit().remove(KEY_PENDING).commit()
            main.post {
                if (failed.isNotEmpty()) Log.w(TAG, "plain display session: ${failed.joinToString()} could not be put back; kept to retry when Shizuku is next ready")
                stage = Stage.IDLE
                displayId = null
                val next = onFallback
                onFallback = null
                if (fallback && next != null) {
                    Log.i(TAG, "plain display session: DeX is back; the Space opens in DeX as before")
                    next()
                }
            }
        }.start()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
