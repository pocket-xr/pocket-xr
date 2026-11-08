package com.hayeco.flipspaces.spatial

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.BindRetry
import com.hayeco.flipspaces.core.spatial.DisplayTasks
import com.hayeco.flipspaces.core.spatial.PanelDisplayEvent
import com.hayeco.flipspaces.core.spatial.PanelDisplays
import com.hayeco.flipspaces.core.spatial.PanelLoss
import com.hayeco.flipspaces.core.spatial.PanelMotion
import com.hayeco.flipspaces.core.spatial.PanelServiceLoss
import com.hayeco.flipspaces.platform.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Pocket XR's side of the panel host: binds the Shizuku user service ([PanelHostService]) and
 * makes its calls on one background thread, so the GL and main threads never wait on Binder.
 * Results come back through callbacks on that thread.
 *
 * 0.4.0: every state change is one logcat line (FlipSpaces/PanelHost), every virtual display
 * create, release and failure another (FlipSpaces/Panel). Each connection has a [generation]:
 * when the service disconnects or Shizuku restarts, the generation moves on, every display id
 * of the old one is dead, and the Space reopens its panels on the new connection. Shizuku's
 * binder listeners reconnect as soon as Shizuku is back.
 *
 * 0.4.1: any panel input ([motion], [key]), top focus for a panel ([focus]), Android's pause,
 * resume and stop of each display ([displayEvents]) and the FLAG_SECURE check ([secure]).
 * The service's interface changed, so its version did too: Shizuku replaces a running 0.4.0
 * service instead of binding it.
 *
 * 0.4.2: the tasks on a panel display ([tasks]) and a relaunch on the same display
 * ([relaunch]); every motion's result can come back ([motion]'s result), for the
 * FlipSpaces/Input log; [focus] can be quiet, for the trackpad's repeated refocus. Service
 * version 3.
 *
 * 0.4.3: a bind that never connects (Shizuku's service starter exited) is tried again after 1,
 * 2, 4 and 8 s, then every 30 s ([BindRetry]), each try one FlipSpaces/PanelHost line; every
 * display id the service ever reported stays in [reportedDisplayIds], so the display listener
 * can tell our panel displays' removals from the glasses'; closing the Space releases every
 * panel display before the service is unbound, and [onReady] tells the plug-in watcher when the
 * service is back.
 *
 * 0.4.4: every loss of the service ([PanelServiceLoss]) removes Shizuku's record of it before
 * the next bind (a service that died while starting kept a dead record that the next binds
 * waited on for 45 s), and when only Shizuku dies, the old service's displays are released
 * through it and it is told to exit (they used to stay until its process was killed).
 */
object PanelHost {
    /** 0.5.37: open a new instance of an app already running (the Menu's switch; the Space sets it from the store). */
    @Volatile var newInstance = false

    private const val TAG = "FlipSpaces/PanelHost"
    private const val PANEL_TAG = "FlipSpaces/Panel"
    /**
     * 6 in 0.5.3 (setDisplaySurface), so a service left running from 0.5.2 is replaced. 8 in 0.5.14 (startSpace).
     * 9 in 0.5.21 (refresh, appShape and the phone display's sweep), so a service left running from 0.5.20 is replaced.
     * 10 in 0.5.22 (panel displays that ignore orientation and size restrictions, appShape's header lines, appCompat).
     * 11 in 0.5.23 (the Space task's leash let go of at its release, the layer check after it).
     * 12 in 0.5.24 (the panel service held bound until its last layer check).
     * 13 in 0.5.25 (the last layer check removes a Space task of its own still left,
     * and the service's lines go to the app through IPanelEvents.onLogLines).
     */
    // 0.5.37: 14, launchNew (a new instance of a running app); a new version restarts the user service with this code.
    // 1.1.15: 16, rootTaskLines and moveTaskToDisplay (an app's new screen that landed on the phone, back to its window).
    // 1.2.3: 17, the virtual gamepad (gamepadOpen, gamepadInput, gamepadClose) and injectTouches (issue 59).
    private const val SERVICE_VERSION = 17

    /**
     * 0.5.52: window displays on the shell's virtual device (Developer: "Windows stay awake when the phone sleeps (test)"),
     * so they have a display group of their own; a refusal falls back to the plain virtual display.
     */
    @Volatile var onVirtualDevice = false

    /** 0.5.21: how long the service stays bound after a Space closes, for one that comes straight back. */
    private const val KEEP_BOUND_MS = 5_000L

    sealed interface State {
        data object Off : State
        data class Unavailable(val status: ShizukuStatus) : State
        data object Binding : State
        data object Ready : State
        data class Failed(val error: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Moves on with every connection; display ids from an older generation are dead. */
    @Volatile var generation = 0
        private set

    /**
     * Display ids of the panels the service created and has not released, so display selection
     * never takes one for the glasses (DisplayCandidate.isOwnPanel).
     */
    val panelDisplayIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Every display id the service reported in this process, released or not (0.4.3). Android
     * does not reuse display ids while it runs, and a removed display's name can no longer be
     * read, so this is how a removal is known to be a panel's.
     */
    val reportedDisplayIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Called on the main thread each time the service becomes Ready (0.4.3). */
    val onReady = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Android's pause, resume and stop of panel displays, taken by the Space each frame. */
    val displayEvents = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, PanelDisplayEvent>>()

    private val events = object : IPanelEvents.Stub() {
        override fun onDisplayEvent(displayId: Int, event: Int) {
            val e = PanelDisplayEvent.entries.getOrNull(event) ?: return
            Log.i(PANEL_TAG, "display $displayId ${e.name.lowercase()} by Android")
            displayEvents += displayId to e
        }

        /** 0.5.25: the service's own lines, into the app's log (and so the recorder's). Binder thread. */
        override fun onLogLines(lines: Array<out String>?) {
            lines?.forEach { Log.i(com.hayeco.flipspaces.core.spatial.PanelLogRelay.TAG, com.hayeco.flipspaces.core.spatial.PanelLogRelay.appLine(it)) }
        }
    }

    private fun setState(next: State, why: String) {
        val before = _state.value
        _state.value = next
        if (before != next) Log.i(TAG, "state ${before.name()} -> ${next.name()}: $why (generation $generation)")
        if (before != next && next == State.Ready) main.post { onReady.forEach { it() } }
    }

    private fun State.name(): String = when (this) {
        State.Off -> "Off"
        is State.Unavailable -> "Unavailable(${status.name})"
        State.Binding -> "Binding"
        State.Ready -> "Ready"
        is State.Failed -> "Failed($error)"
    }

    @Volatile private var appContext: Context? = null
    private var listening = false

    /** Shizuku came back (started again after a stop or reboot): bind again if a Space wants panels. */
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        appContext?.let { ctx -> main.post { if (wanted) connect(ctx) } }
    }

    /** Shizuku stopped: the panel process may live on with its displays, so they are released through it. */
    private val binderDead = Shizuku.OnBinderDeadListener {
        lost(PanelLoss.SHIZUKU_DIED, "Shizuku stopped")
    }

    /** True between connect and disconnect: a Space wants the panel service. */
    @Volatile private var wanted = false

    /**
     * The old connection is gone: forget it, so the next connect starts a new generation, and
     * clean up after it as [PanelServiceLoss] says (0.4.4).
     */
    private fun lost(loss: PanelLoss, why: String) {
        val h = host
        host = null
        val dead = panelDisplayIds.toList()
        panelDisplayIds.clear()
        val plan = PanelServiceLoss.plan(loss, dead, oldServiceAlive = h != null)
        if (plan.removeRecord) dropRecord(why)
        if (h != null && (plan.releaseDisplays.isNotEmpty() || plan.stopOldService)) {
            calls.execute {
                for (id in plan.releaseDisplays) {
                    runCatching { h.releaseDisplay(id) }
                        .onSuccess { Log.i(PANEL_TAG, "released display $id through the old panel service ($why)") }
                        .onFailure { Log.w(PANEL_TAG, "release display $id after $why failed: ${it.javaClass.simpleName}: ${it.message}") }
                }
                // destroy() exits the process, so the call itself usually fails with a dead binder.
                if (plan.stopOldService) runCatching { h.destroy() }
                Log.i(TAG, "old panel service told to exit ($why)")
            }
        } else if (dead.isNotEmpty()) {
            Log.w(PANEL_TAG, "displays $dead died with the panel service ($why)")
        }
        setState(State.Failed("The panel service stopped ($why). Reconnecting."), why)
    }

    /**
     * Unbinds with remove = true, so Shizuku forgets its record of the service (and stops it if
     * it still runs); the next bind then starts a fresh one instead of waiting on a dead record
     * (0.4.4). Needs the Shizuku server; without it the record went with it.
     */
    private fun dropRecord(why: String) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        val context = appContext ?: return
        runCatching { Shizuku.unbindUserService(serviceArgs(context), connection, true) }
            .onSuccess { Log.i(TAG, "removed Shizuku's record of the panel service ($why)") }
            .onFailure { Log.w(TAG, "removing Shizuku's record of the panel service failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private val calls = Executors.newSingleThreadExecutor { Thread(it, "panel-host") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var host: IPanelHost? = null

    /** The same arguments each time: Shizuku keys its record of the service by them. */
    private fun serviceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PanelHostService::class.java.name))
            .daemon(false)
            .processNameSuffix("panels")
            .debuggable(false)
            .version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                // A service that died while starting: drop its record so the next try starts afresh.
                lost(PanelLoss.NO_BINDER, "Shizuku returned no live panel service")
                return
            }
            generation++
            cancelRetry()
            if (retry.tries > 1) Log.i(TAG, "bind try ${retry.tries} connected")
            retry.connected()
            val h = IPanelHost.Stub.asInterface(binder)
            host = h
            calls.execute { runCatching { h.setEvents(events) }.onFailure { Log.w(TAG, "display callbacks not set: ${it.javaClass.simpleName}: ${it.message}") } }
            setState(State.Ready, "panel service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Already dropped (the old service told to exit after Shizuku died), or an older
            // service's disconnect while a new one answers: nothing to clean up, and removing
            // the record now could drop a new bind's.
            val h = host
            if (h == null || h.asBinder().pingBinder()) {
                Log.i(TAG, "a disconnect for an older panel service; ignored")
                return
            }
            lost(PanelLoss.SERVICE_DIED, "service disconnected: Shizuku stopped or restarted, or its process died")
        }
    }

    /** Binds the shell-side service if Shizuku is ready. Main thread. */
    fun connect(context: Context) {
        appContext = context.applicationContext
        onVirtualDevice = SpaceStore(context).panelsOnVirtualDevice
        wanted = true
        if (!listening) {
            listening = true
            runCatching {
                Shizuku.addBinderReceivedListener(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
            }
        }
        val status = ShizukuBridge.status(context)
        if (status != ShizukuStatus.READY) {
            setState(State.Unavailable(status), status.label)
            return
        }
        // 0.5.21: a service still bound from the Space before is used at once.
        main.removeCallbacks(stopRunnable)
        if (host?.asBinder()?.pingBinder() == false) lost(PanelLoss.SERVICE_DIED, "the service binder is dead")
        if (host != null && _state.value != State.Ready) {
            setState(State.Ready, "panel service still bound from the Space before (generation $generation)")
            return
        }
        // A try is waiting for its connection or its retry: the backoff decides when to bind again.
        if (host != null || _state.value == State.Binding || retryPending) return
        setState(State.Binding, "binding the Shizuku user service")
        bind(context)
    }

    private val retry = BindRetry()
    private var retryPending = false
    private val retryRunnable = Runnable { retryBind() }

    /** One bind try; the next one follows after [BindRetry]'s wait unless the service connects first. Main thread. */
    private fun bind(context: Context) {
        val a = serviceArgs(context)
        val wait = retry.tried()
        Log.i(TAG, "bind try ${retry.tries}: asking Shizuku for the panel service; next try in ${wait / 1000} s if it does not connect")
        try {
            Shizuku.bindUserService(a, connection)
        } catch (e: Exception) {
            setState(State.Failed("Shizuku would not start the panel service: ${e.javaClass.simpleName}: ${e.message}"), "bindUserService threw (try ${retry.tries})")
        }
        retryPending = true
        main.postDelayed(retryRunnable, wait)
    }

    /** No connection since the last try: bind again (0.4.3; 0.4.2 stayed in Binding for good). */
    private fun retryBind() {
        retryPending = false
        val context = appContext ?: return
        if (!wanted || host != null) return
        val status = ShizukuBridge.status(context)
        if (status != ShizukuStatus.READY) {
            Log.i(TAG, "bind try ${retry.tries} got no service and Shizuku is ${status.name}; waiting for Shizuku")
            retry.connected()
            setState(State.Unavailable(status), status.label)
            return
        }
        Log.w(TAG, "bind try ${retry.tries} got no service in ${BindRetry.delayMs(retry.tries) / 1000} s; trying again")
        // From the second try, remove the stale record first (0.4.4; 0.4.3 unbound with
        // remove = false, which kept a dead one), so Shizuku starts the service again instead of
        // waiting on it. After the first try's 1 s the service may still be starting.
        if (PanelServiceLoss.plan(PanelLoss.BIND_TIMED_OUT, emptyList(), oldServiceAlive = false, timedOutTry = retry.tries).removeRecord) {
            dropRecord("bind try ${retry.tries} got no service")
        }
        setState(State.Binding, "binding the Shizuku user service again (try ${retry.tries + 1})")
        bind(context)
    }

    private fun cancelRetry() {
        main.removeCallbacks(retryRunnable)
        retryPending = false
    }

    /**
     * Releases every panel display, then unbinds and stops the service (0.4.3: the Space's own
     * releases used to run after the host was dropped here, so they found no service and the
     * displays stayed until the service process went). Main thread.
     */
    /** 0.5.24: when the Space's root task was last released (its layer checks run up to [com.hayeco.flipspaces.core.SpaceTasks.LAYER_HOLD_MS] after). */
    @Volatile private var lastSpaceReleaseMs = -1_000_000L

    fun disconnect() {
        wanted = false
        cancelRetry()
        retry.connected()
        val h = host
        val left = panelDisplayIds.toList()
        panelDisplayIds.clear()
        // On the calls thread, after any release the Space queued before closing.
        calls.execute {
            if (h != null) for (id in left) {
                runCatching { h.releaseDisplay(id) }
                    .onSuccess { Log.i(PANEL_TAG, "released display $id (the Space closed)") }
                    .onFailure { Log.w(PANEL_TAG, "release display $id failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        // 0.5.21 (at the glasses' own 2D to 3D switch the service went Ready to
        // Off and the next Space's first bind try got no service in 1 s, both starts): the service stays
        // bound [KEEP_BOUND_MS] for a Space that comes straight back; then it is unbound and stopped.
        main.removeCallbacks(stopRunnable)
        if (h != null) {
            // 0.5.24 (the service exited 4.8 s after a release, before its 5 s layer check): with
            // displays released, it stays bound until their last layer check has run.
            val sinceRelease = android.os.SystemClock.uptimeMillis() - lastSpaceReleaseMs
            val checks = sinceRelease < com.hayeco.flipspaces.core.SpaceTasks.LAYER_HOLD_MS
            val keep = if (!checks) KEEP_BOUND_MS else maxOf(KEEP_BOUND_MS, com.hayeco.flipspaces.core.SpaceTasks.LAYER_HOLD_MS - sinceRelease)
            main.postDelayed(stopRunnable, keep)
            setState(State.Off, "the Space closed; the panel service stays bound ${keep / 1000} s for a Space that comes straight back${if (checks) " and for the released Space's layer checks" else ""}")
        } else {
            stopRunnable.run()
            setState(State.Off, "the Space closed")
        }
    }

    /** 0.5.21: the unbind after [KEEP_BOUND_MS] with no Space back. Main thread. */
    private val stopRunnable = Runnable {
        if (wanted) return@Runnable
        val a = appContext?.let { serviceArgs(it) }
        val had = host != null
        host = null
        // On the calls thread, after the releases above.
        calls.execute {
            main.post {
                // A new Space that came up meanwhile keeps the service.
                if (a != null && !wanted) runCatching { Shizuku.unbindUserService(a, connection, true) }
            }
        }
        if (had) Log.i(TAG, "the panel service unbound: no Space came back")
    }

    val ready: Boolean get() = host != null

    /**
     * Creates a panel display and launches the app on it. [done] gets the display id or an
     * error, and the generation the call ran on (a result from an older one is stale).
     * [attempt] is only for the log. [refreshRate] is what the display asks for.
     */
    fun open(
        label: String,
        packageName: String,
        activityName: String,
        width: Int,
        height: Int,
        surface: Surface,
        attempt: Int,
        refreshRate: Float,
        dpi: Int = PanelDisplays.DENSITY_DPI,
        done: (id: Int?, error: String?, generation: Int) -> Unit,
    ) {
        calls.execute {
            val gen = generation
            val h = host ?: return@execute done(null, "The panel service is not connected", gen).also {
                Log.w(PANEL_TAG, "create for $label (try $attempt) failed: the panel service is not connected")
            }
            try {
                val name = PanelDisplays.displayName(label)
                // 0.5.52: on the virtual device when the Developer switch is on, without the own group flag (a device display's
                // group is the device's); the plain display if Android refuses.
                var id = if (onVirtualDevice) h.createDisplayOnDevice(name, width, height, dpi, PanelDisplays.flags(Build.VERSION.SDK_INT) and PanelDisplays.FLAG_OWN_DISPLAY_GROUP.inv(), surface, refreshRate) else -1
                if (onVirtualDevice) Log.i(PANEL_TAG, if (id >= 0) "display $id for $label made on the virtual device" else "virtual device display for $label refused (${h.lastError()}); a plain display instead")
                if (id < 0) id = h.createDisplay(name, width, height, dpi, PanelDisplays.flags(Build.VERSION.SDK_INT), surface, refreshRate)
                if (id < 0) {
                    val err = "Creating the panel display failed: ${h.lastError()}"
                    Log.w(PANEL_TAG, "create for $label (try $attempt) failed: $err")
                    return@execute done(null, err, gen)
                }
                panelDisplayIds += id
                reportedDisplayIds += id
                Log.i(PANEL_TAG, "created display $id for $label ${width}x$height at $dpi dpi, $refreshRate Hz (try $attempt, generation $gen)")
                // 0.5.37: a new instance when the Menu's switch is on.
                val launchError = h.launchNew(id, packageName, activityName, newInstance)
                Log.i(PANEL_TAG, "launch $label on display $id: ${launchError ?: "launched"}; ${h.describeDisplay(id)}")
                done(id, launchError?.let { "Android would not start $label on its panel: $it" }, gen)
            } catch (e: Exception) {
                val err = "Panel service call failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(PANEL_TAG, "create for $label (try $attempt) failed: $err")
                done(null, err, gen)
            }
        }
    }

    /** 0.5.21: [dpi] too, which a window's size and Wide view set ([com.hayeco.flipspaces.core.spatial.PanelDisplaySize]). */
    fun resize(displayId: Int, width: Int, height: Int, dpi: Int = PanelDisplays.DENSITY_DPI, done: (String?) -> Unit = {}) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            done(runCatching { h.resizeDisplay(displayId, width, height, dpi) }.getOrElse { it.message })
        }
    }

    /** 0.5.3: points the display at [surface] (after a resize); [done] gets null or the error. */
    fun setSurface(displayId: Int, surface: Surface, done: (String?) -> Unit = {}) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            done(runCatching { h.setDisplaySurface(displayId, surface) }.getOrElse { it.message })
        }
    }

    fun release(displayId: Int) {
        panelDisplayIds -= displayId
        // The host as it is now: a disconnect right after (the Space closing) must not drop it first.
        val h = host
        calls.execute {
            if (h == null) {
                Log.i(PANEL_TAG, "release display $displayId: no service, already gone")
                return@execute
            }
            runCatching { h.releaseDisplay(displayId) }
                .onSuccess { Log.i(PANEL_TAG, "released display $displayId") }
                .onFailure { Log.w(PANEL_TAG, "release display $displayId failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * One panel input event (0.4.1) at display pixels, injected after [PanelMotion.delayMs].
     * [downTime] is the gesture's start on the uptime clock (0 for mouse events). Errors go to
     * [problem] on the main thread.
     */
    fun motion(displayId: Int, m: PanelMotion, downTime: Long, result: ((String?) -> Unit)? = null, problem: (String) -> Unit) {
        val send = Runnable {
            calls.execute {
                val h = host ?: return@execute result?.invoke("The panel service is not connected") ?: Unit
                val err = runCatching {
                    h.injectMotion(displayId, m.action, m.xs.toFloatArray(), m.ys.toFloatArray(), m.source, m.vScroll, m.hScroll, downTime)
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                result?.invoke(err)
                if (err != null) main.post { problem(err) }
            }
        }
        if (m.delayMs > 0) main.postDelayed(send, m.delayMs) else send.run()
    }

    /** A key event for the app on [displayId] (0.4.1): a keyboard's keys, or Back. */
    fun key(displayId: Int, action: Int, keyCode: Int, metaState: Int, repeat: Int, downTime: Long, problem: (String) -> Unit) {
        calls.execute {
            val h = host ?: return@execute
            val err = runCatching { h.injectKey(displayId, action, keyCode, metaState, repeat, downTime) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (err != null) main.post { problem(err) }
        }
    }

    /** 1.2.3 (issue 59): the virtual gamepad up; [done] gets null or why not, on the main thread. */
    fun gamepadOpen(done: (String?) -> Unit) {
        calls.execute {
            val h = host
            if (h == null) {
                main.post { done("The panel service is not connected (Shizuku)") }
                return@execute
            }
            val err = runCatching { h.gamepadOpen() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            main.post { done(err) }
        }
    }

    /** 1.2.3: one report of the virtual gamepad, in order with the others. */
    fun gamepadInput(report: ByteArray) {
        calls.execute { runCatching { host?.gamepadInput(report) } }
    }

    fun gamepadClose() {
        calls.execute { runCatching { host?.gamepadClose() } }
    }

    /** 1.2.3 (issue 59): touches with their own pointer ids on [displayId]. */
    fun touches(displayId: Int, action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, downTime: Long, problem: (String) -> Unit) {
        calls.execute {
            val h = host ?: return@execute
            val err = runCatching { h.injectTouches(displayId, action, ids, xs, ys, downTime) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (err != null) main.post { problem(err) }
        }
    }

    /** Gives [displayId] top focus (0.4.1), so typing and the phone's keyboard reach its app. */
    fun focus(displayId: Int, why: String? = null, done: (String?) -> Unit = {}) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            val err = runCatching { h.focusDisplay(displayId) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            val reason = why?.let { " ($it)" } ?: ""
            if (err != null) Log.w(PANEL_TAG, "focus display $displayId$reason: $err") else Log.i(PANEL_TAG, "focus display $displayId$reason")
            done(err)
        }
    }

    /** 0.5.13: [displayId] asks for [hz] from now on; [done] gets null or why not. */
    fun setRefreshRate(displayId: Int, hz: Float, done: (String?) -> Unit = {}) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            val err = runCatching { h.setDisplayRefreshRate(displayId, hz) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            Log.i(PANEL_TAG, "display $displayId refresh request %.0f Hz: ${err ?: "taken"}".format(hz))
            done(err)
        }
    }

    /** What runs on [displayId] (0.4.2); null when the service could not tell. */
    fun tasks(displayId: Int, done: (DisplayTasks?) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done(null)
            done(DisplayTasks.of(runCatching { h.displayTasks(displayId) }.getOrNull()))
        }
    }

    /** 1.1.15: the root tasks on [displayId] ([LaunchDisplay.parse]); null when the service could not tell. */
    fun rootTasks(displayId: Int, done: (List<com.hayeco.flipspaces.core.spatial.LaunchDisplay.PhoneTask>?) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done(null)
            done(runCatching { h.rootTaskLines(displayId) }.getOrNull()?.let { com.hayeco.flipspaces.core.spatial.LaunchDisplay.parse(it) })
        }
    }

    /** 1.1.15: moves root task [taskId] onto [displayId]; [done] gets null or why not. */
    fun moveTask(taskId: Int, displayId: Int, done: (String?) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            done(runCatching { h.moveTaskToDisplay(taskId, displayId) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" })
        }
    }

    /** Starts the panel's app again on its display (0.4.2, the App closed card's Reopen). */
    fun relaunch(displayId: Int, label: String, packageName: String, activityName: String, done: (String?) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            // 0.5.83 (Mike: Reopen "sometimes it opens the app on my phone instead of in that floating window"): an app whose
            // task Android moved to the phone (often why its window shows App closed) was brought to the front there by a
            // plain NEW_TASK start, whatever display was asked for. Reopen starts it as a new instance on the window's display
            // (0.5.37's New instance, NEW_TASK | MULTIPLE_TASK): the window gets the app; a copy left on the phone stays there.
            val err = runCatching { h.launchNew(displayId, packageName, activityName, true) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            Log.i(PANEL_TAG, "reopen $label on display $displayId (a new instance there): ${err ?: "launched"}")
            done(err)
        }
    }

    /** 0.5.21: the window's Refresh: the app's activity started anew on [displayId] with its task cleared. */
    fun refresh(displayId: Int, label: String, packageName: String, activityName: String, done: (String?) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done("The panel service is not connected")
            val err = runCatching { h.refresh(displayId, packageName, activityName) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            Log.i(PANEL_TAG, "refresh $label on display $displayId: ${err ?: "started anew"}")
            done(err)
        }
    }

    /** Packages with a FLAG_SECURE window on [displayId] (0.4.1); empty when none or unknown. */
    fun secure(displayId: Int, done: (List<String>) -> Unit) {
        calls.execute {
            val h = host ?: return@execute done(emptyList())
            done(runCatching { h.secureWindows(displayId)?.toList() ?: emptyList() }.getOrDefault(emptyList()))
        }
    }

    /**
     * Holds the Space's task on [displayId] in a root task of its own, which Android removes
     * with the display, or deletes that root task again (0.4.7,
     * [com.hayeco.flipspaces.core.spatial.SpaceUnplug.holdOnOpen]); logged either way, with
     * what the service did. The service is taken now, so a call made just before [disconnect]
     * still reaches it.
     */
    fun holdSpace(displayId: Int, hold: Boolean, why: String, done: ((String) -> Unit)? = null) {
        val service = host
        // 0.5.24: a release starts the service's layer checks; it is held bound until the last.
        if (!hold) lastSpaceReleaseMs = android.os.SystemClock.uptimeMillis()
        val what = if (hold) "hold the Space on display $displayId" else "release the Space's root task on display $displayId"
        calls.execute {
            val h = service ?: run {
                Log.w(TAG, "$what ($why): the panel service is not connected")
                done?.invoke("failed: the panel service is not connected")
                return@execute
            }
            val reply = runCatching { h.holdSpace(displayId, hold, SpaceActivity::class.java.name) }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            if (reply.startsWith("failed")) Log.w(TAG, "$what ($why): $reply") else Log.i(TAG, "$what ($why): $reply")
            done?.invoke(reply)
        }
    }

    /**
     * 0.5.14: waits up to [timeoutMs] for the service to be connected.
     * Off the main thread only (the connection arrives on it).
     */
    fun awaitService(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (host == null) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
        return true
    }

    /**
     * 0.5.14: starts the Space on [displayId] as the shell with launch bounds [bounds] (the whole
     * display), on the calling thread. Null when Android started it, else why not.
     */
    fun startSpace(displayId: Int, component: String, bounds: com.hayeco.flipspaces.core.Bounds): String? {
        val h = host ?: return "the panel service is not connected"
        return runCatching { h.startSpace(displayId, component, bounds.left, bounds.top, bounds.right, bounds.bottom) }
            .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 0.5.7: SurfaceFlinger's answer on whether the Space is composed on [displayId] (see IPanelHost.spaceOnScreen). */
    fun spaceOnScreen(displayId: Int, done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.spaceOnScreen(displayId, SpaceActivity::class.java.name) }.getOrElse { "unknown: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "unknown: the panel service is not connected"
            done(reply)
        }
    }

    /** 0.5.19: SurfaceFlinger's `--latency` table for the Space's layer (see IPanelHost.presentTimes). */
    fun presentTimes(done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.presentTimes(SpaceActivity::class.java.name) }.getOrElse { "unknown: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "unknown: the panel service is not connected"
            done(reply)
        }
    }

    /** 0.5.21 (Wide view): the display's part of `dumpsys activity activities` (see IPanelHost.appShape). */
    fun appShape(displayId: Int, done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.appShape(displayId) }.getOrElse { "unknown: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "unknown: the panel service is not connected"
            done(reply)
        }
    }

    /** 0.5.22 (Wide view's per app fallback): read or set OVERRIDE_ANY_ORIENTATION_TO_USER for [packageName] (see IPanelHost.appCompat). */
    fun appCompat(packageName: String, action: String, done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.appCompat(packageName, action) }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "failed: the panel service is not connected"
            done(reply)
        }
    }

    /** 0.5.19: why [packageName]'s process last ended (see IPanelHost.exitReason). */
    fun exitReason(packageName: String, done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.exitReason(packageName) }.getOrElse { "unknown: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "unknown: the panel service is not connected"
            done(reply)
        }
    }

    /** 0.5.7: the Space back from its full screen task into its DeX window on [displayId] (see IPanelHost.windowSpace). */
    fun windowSpace(displayId: Int, why: String, done: (String) -> Unit) {
        val service = host
        calls.execute {
            val reply = service?.let { h ->
                runCatching { h.windowSpace(displayId, SpaceActivity::class.java.name) }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "failed: the panel service is not connected"
            val what = "Space back into its DeX window on display $displayId ($why)"
            if (reply.startsWith("failed")) Log.w(TAG, "$what: $reply") else Log.i(TAG, "$what: $reply")
            done(reply)
        }
    }
}
