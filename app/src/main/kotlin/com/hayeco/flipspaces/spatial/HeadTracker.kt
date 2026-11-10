package com.hayeco.flipspaces.spatial

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.AirImuProtocol
import com.hayeco.flipspaces.core.spatial.BiasSavePolicy
import com.hayeco.flipspaces.core.spatial.ImuReportSource
import com.hayeco.flipspaces.core.spatial.FramerCounts
import com.hayeco.flipspaces.core.spatial.FusionSettings
import com.hayeco.flipspaces.core.spatial.HeadChain
import com.hayeco.flipspaces.core.spatial.HeadFusion
import com.hayeco.flipspaces.core.spatial.HeadHold
import com.hayeco.flipspaces.core.spatial.HeadPredictor
import com.hayeco.flipspaces.core.spatial.ImuClock
import com.hayeco.flipspaces.core.spatial.ImuReadGaps
import com.hayeco.flipspaces.core.spatial.HeadPose
import com.hayeco.flipspaces.core.spatial.HeadPoseHistory
import com.hayeco.flipspaces.core.spatial.GlassesReach
import com.hayeco.flipspaces.core.spatial.HeadRetryBackoff
import com.hayeco.flipspaces.core.spatial.HeadRouteKind
import com.hayeco.flipspaces.core.spatial.HeadView
import com.hayeco.flipspaces.core.spatial.HeadImuTransport
import com.hayeco.flipspaces.core.spatial.PhoneSystem
import com.hayeco.flipspaces.core.spatial.HeadRoutePlan
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.ImuReportType
import com.hayeco.flipspaces.core.spatial.OneImuFramer
import com.hayeco.flipspaces.core.spatial.OneImuProtocol
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.RateMeter
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.VitureReconnect
import com.hayeco.flipspaces.platform.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Live numbers for the diagnostics screen, about ten times a second. */
data class HeadLive(
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    /** Angular velocity in head axes with the learned bias removed, rad/s. */
    val gyro: Vec3,
    /** Accelerometer as the glasses report it, before the axis mapping. */
    val rawAccel: Vec3,
    val rateHz: Double,
    val counts: FramerCounts,
    val still: Boolean,
    val gyroBias: Vec3,
)

/**
 * Reads the glasses' IMU stream (XREAL One family protocol, docs/SPATIAL.md 1.1) on its own
 * thread and fuses it into a head pose. Anything that fails is reported in [status] with the
 * exact error, and the tracker keeps retrying; nothing here throws to the caller, so without the
 * stream the Space simply runs head locked.
 */
object HeadTracker {
    private const val TAG = "FlipSpaces/Head"

    /** 0.5.62: the head reader thread's id once it runs (0 before), for the render thread's hint session. */
    @Volatile
    var headReaderTid = 0
        private set
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 1000
    private const val NO_REPORT_SECONDS = 4

    /**
     * 0.5.49: the head thread's rest between reads of the glasses' reports, ns (they come every 1 ms); 0 reads each as it
     * comes. Measured on the Flip at severe heat with the release build: 3 ms rests took the thread from 43 % of a core
     * (the debug build, no rest) to 18 %, 1.5 ms to 21 %, but the gap between reads grew to p95 9.9 and 7.4 ms (at most 21
     * and 26 ms) against 1.5 ms (at most 6.1) with no rest: a hot phone schedules a sleeping thread late, and the newest
     * pose ages with it. Most of the saving was the release build's, so off until a glasses run shows it is worth it.
     */
    private const val READ_BATCH_NS = 0L

    /** 1.2.3: the glasses' reports a read waits for in the kernel ([GlassesNetwork.Link.wakeAfter]); 1 is each as it comes. */
    private const val WAKE_REPORTS = 2

    /** 0.5.53: the app's own socket is tried this many more times before the shell helper, this far apart ([openRoute]). */
    private const val DIRECT_RETRIES = 3
    private const val DIRECT_RETRY_MS = 1000L

    /** 0.5.48: the head thread refreshes its streaming status this often at most, ms. */
    private const val STATUS_EVERY_MS = 500L

    /** 0.5.6: how often a wait looks again at the glasses' network, to retry at once when it changes. */
    private const val NETWORK_LOOK_MS = 3000L

    /** 1.1.10: how often the USB list is read again while HID glasses are plugged in. */
    private const val HID_RECHECK_MS = 10_000L

    private val _status = MutableStateFlow<HeadTrackingStatus>(HeadTrackingStatus.Off)
    val status: StateFlow<HeadTrackingStatus> = _status.asStateFlow()

    private val _live = MutableStateFlow<HeadLive?>(null)
    val live: StateFlow<HeadLive?> = _live.asStateFlow()

    /** What the last network scan saw, for the diagnostics screen. */
    private val _networks = MutableStateFlow<List<String>>(emptyList())
    val networks: StateFlow<List<String>> = _networks.asStateFlow()

    /** The last TCP connect attempt and how it ended. */
    private val _connectResult = MutableStateFlow<String?>(null)
    val connectResult: StateFlow<String?> = _connectResult.asStateFlow()

    /** 0.5.5: each route tried in the last round and how it ended, for the diagnostics screen. */
    private val _attempts = MutableStateFlow<List<String>>(emptyList())
    val attempts: StateFlow<List<String>> = _attempts.asStateFlow()

    /** 0.5.5: the route the stream is open on, null while there is none. */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    /**
     * 0.5.6: in plain words whether the glasses answered on 169.254.2.1:52998 in the last round
     * ("The glasses do not answer on ..."), for the diagnostics screen; null before a try.
     */
    private val _answer = MutableStateFlow<String?>(null)
    val answer: StateFlow<String?> = _answer.asStateFlow()

    /** What the USB Ethernet bring-up saw and tried (GlassesEthernetLink), step by step. */
    private val _ethernet = MutableStateFlow<List<String>>(emptyList())
    val ethernet: StateFlow<List<String>> = _ethernet.asStateFlow()

    @Volatile private var pose: HeadPose? = null

    /**
     * 0.5.10: the newest fused pose on the phone's clock, smoothed rate and acceleration, for the
     * prediction to each frame's present time ([renderPose]). Guarded by itself.
     */
    private val predictor = HeadPredictor()
    @Volatile private var lastSampleHostNs = 0L

    /**
     * 0.5.12: the newest IMU sample's time on the phone's clock (System.nanoTime,
     * mapped from the glasses' own timestamps by [ImuClock]), so the renderer can log how old it is
     * when the draw pose is taken. 0 before the first sample.
     */
    val newestSampleNs: Long get() = lastSampleHostNs

    /** 0.5.10: what [renderPose] predicted last, ms ahead of the newest sample, for the chain line. */
    @Volatile private var lastPredictMs = 0f

    /** 0.5.20: the head's own motion in the sway and tremor bands, from every sample (reader thread). */
    private val tremorBands = com.hayeco.flipspaces.core.spatial.TremorBands()

    /** 0.5.21: the last 10 s of raw gyro, written out while worn and still ([com.hayeco.flipspaces.core.spatial.GyroRing]). */
    private val gyroRing = com.hayeco.flipspaces.core.spatial.GyroRing()

    /** Writes the ring to files/gyro/ on a thread of its own, keeping the newest [com.hayeco.flipspaces.core.spatial.GyroRing.KEEP_FILES]. */
    private fun writeGyroRing(peakToPeak: Float) {
        // 0.5.49: in the app's external files (beside the logs), so they can be pulled from the release build, which
        // run-as cannot read.
        val dir = appContext?.let { java.io.File(it.getExternalFilesDir(null) ?: it.filesDir, "gyro") } ?: return
        val note = String.format(java.util.Locale.ROOT, "a worn still head, still check %.4f rad/s, written at %d ms uptime", peakToPeak, SystemClock.uptimeMillis())
        // 0.5.55: the ring as it is now, written out as text on the writer's thread: 0.5.21 to 0.5.54 formatted all its
        // 12000 samples here on the head thread, a few MB of strings once a minute. The file is the same.
        val ring = gyroRing.copy()
        Thread({
            runCatching {
                val text = ring.dump(note)
                dir.mkdirs()
                val f = java.io.File(dir, "still-${System.currentTimeMillis()}.txt")
                f.writeText(text)
                dir.listFiles()?.sortedByDescending { it.name }?.drop(com.hayeco.flipspaces.core.spatial.GyroRing.KEEP_FILES)?.forEach { it.delete() }
                Log.i(TAG, "raw gyro: the last 10 s of a worn still head (still check %.4f rad/s) written to %s".format(java.util.Locale.ROOT, peakToPeak, f.absolutePath))
            }.onFailure { Log.w(TAG, "raw gyro not written: ${it.message}") }
        }, "gyro-ring").start()
    }

    /** 0.5.20: the frames' horizons (centre row), for the chain line's p50 and p95. Guarded by [predictor]. */
    private val predictAhead = com.hayeco.flipspaces.core.spatial.RollingStats(600)

    /** Recent poses on SystemClock.uptimeMillis, the hand camera's clock, for [poseAt]. */
    private val history = HeadPoseHistory()
    @Volatile private var settings = FusionSettings()
    private val recentreRequested = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)

    private var thread: Thread? = null

    /** 1.1.14: the tracker's thread now, for the Head tracking switch's tests. */
    internal val worker: Thread? @Synchronized get() = thread

    /**
     * 1.1.14: Off only interrupts the tracker's thread, which could then write one last status over TurnedOff. Each
     * thread has a generation ([threadGeneration]); [stop] ends it, and the statuses it writes after that are dropped.
     */
    private val generation = com.hayeco.flipspaces.core.spatial.TrackerGeneration()
    private val threadGeneration = ThreadLocal<Long>()

    /** 1.1.14: the running tracker thread's generation (null when stopped), for the tests. */
    internal val liveGeneration: Long? get() = generation.current

    /** A status from the tracker's thread: dropped if that thread's generation was stopped. */
    private fun report(st: HeadTrackingStatus) {
        val gen = threadGeneration.get()
        if (gen == null) _status.value = st else reportFrom(gen, st)
    }

    /** 1.1.14: [st] from the tracker thread of [gen]; false, and the status left as it is, if that thread was stopped. */
    internal fun reportFrom(gen: Long, st: HeadTrackingStatus): Boolean = generation.write(gen) { _status.value = st }
    private var openLink: GlassesNetwork.Link? = null
    private var users = 0
    private var appContext: Context? = null

    /**
     * 0.5.10: interfaces where Android refused the app's own socket (EPERM, EACCES), for as long as
     * the app runs. 0.5.5 kept them per tracker thread, so every restart of the tracker tried the
     * app's socket again first (Mike's log: "app socket bound to the network failed: EPERM").
     */
    private val refusedDirect: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 0.5.10: the tracker keeps running this long after its last user lets go, so a Space restart does not drop the stream. */
    private const val RELEASE_GRACE_MS = 15_000L
    private val graceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopAfterGrace = Runnable {
        synchronized(this) {
            if (users == 0) {
                Log.i(TAG, "head tracking: no user for ${RELEASE_GRACE_MS / 1000} s; stopping the stream")
                stop()
            }
        }
    }

    /** Starts the tracker for one more user (the Space, the diagnostics screen). */
    @Synchronized
    fun acquire(context: Context) {
        graceHandler.removeCallbacks(stopAfterGrace)
        users++
        // 1.2.12 (Discussion #24): a VITURE USB prompt missed on this plug-in is asked again now a screen is up.
        synchronized(vitureReconnect) { vitureReconnect.screenOpened() }
        val store = SpaceStore(context)
        settings = fromStore(store, context)
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        appContext = app
        // 1.1.14: Settings' Head tracking switch is off: the user is counted, nothing is read from the glasses.
        if (!store.headTracking) {
            _status.value = HeadTrackingStatus.TurnedOff
            return
        }
        startThread(app)
    }

    /**
     * 1.1.14 (a tester: tracking "a little jittery", and is there a way to turn it on and off): Settings' Head tracking
     * switch, saved. Off stops the stream now and the Space draws head locked; on starts it again if anything holds the
     * tracker.
     */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        val store = SpaceStore(context)
        if (store.headTracking == on) return
        store.headTracking = on
        Log.i(TAG, "head tracking turned ${if (on) "on" else "off"} in Settings")
        appContext = context.applicationContext
        if (!on) {
            graceHandler.removeCallbacks(stopAfterGrace)
            stop()
        } else {
            // Not TurnedOff any more: the screens said "turned off in Settings" until the new thread wrote a status.
            _status.value = HeadTrackingStatus.Off
            if (users > 0 && thread?.isAlive != true) {
                settings = fromStore(store, context)
                startThread(context.applicationContext)
            }
        }
    }

    /**
     * 1.2.8: the reader started again for its users, for a setting it reads only when it opens the glasses (the VITURE
     * reader's axis switches). Nothing happens while it is not running.
     */
    @Synchronized
    fun restart(context: Context, why: String) {
        if (thread?.isAlive != true) return
        Log.i(TAG, "head tracking: starting the reader again ($why)")
        stop()
        val store = SpaceStore(context)
        if (users > 0 && store.headTracking) {
            settings = fromStore(store, context)
            startThread(context.applicationContext)
        }
    }

    private fun startThread(app: Context) {
        _ethernet.value = emptyList()
        _attempts.value = emptyList()
        _answer.value = null
        watchNetworks(app)
        graceHandler.removeCallbacks(beatTick)
        graceHandler.post(beatTick)
        val gen = generation.start()
        thread = Thread({
            threadGeneration.set(gen)
            run(app)
        }, "head-tracker").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 0.5.6: try again now with the backoff back at its first step: the glasses were plugged in,
     * their network changed, or "Try again" was tapped. Any thread.
     */
    fun kick(why: String) {
        synchronized(wake) {
            kicked = why
            wake.notifyAll()
        }
    }

    /** A network event: look at the network now; only a real change resets the backoff ([waitFor]). */
    private fun nudge() {
        synchronized(wake) {
            nudged = true
            wake.notifyAll()
        }
    }

    private val wake = Object()
    private var kicked: String? = null
    private var nudged = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Best effort: Android may not tell this app about the glasses' restricted networks; [waitFor] looks too. */
    private fun watchNetworks(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = nudge()
            override fun onLost(network: Network) = nudge()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = nudge()
        }
        // 1.1.1 (an S20 FE on Android 11 crashed at plug-in: "Transport type 8 is out of range"): TRANSPORT_USB is
        // Android 12 (API 31) and up; on 11 the builder throws. The request is built inside the guard too.
        runCatching {
            val builder = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (Build.VERSION.SDK_INT >= 31) builder.addTransportType(NetworkCapabilities.TRANSPORT_USB)
            val request = builder
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.i(TAG, "network callback not available (${it.javaClass.simpleName}: ${it.message}); network changes are found by looking every ${NETWORK_LOOK_MS / 1000} s") }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        // 0.5.10: not at once: a Space that stops and starts again (a mode switch, a reload) keeps one stream.
        if (users == 0 && thread?.isAlive == true) {
            graceHandler.removeCallbacks(stopAfterGrace)
            graceHandler.postDelayed(stopAfterGrace, RELEASE_GRACE_MS)
        } else if (users == 0) {
            stop()
        }
    }

    /** 1.1.10: Exit Pocket XR ([com.hayeco.flipspaces.AppExit]): stops now, whoever holds the tracker, with no grace. */
    @Synchronized
    fun stopForExit() {
        users = 0
        graceHandler.removeCallbacks(stopAfterGrace)
        stop()
    }

    @Synchronized
    private fun stop() {
        graceHandler.removeCallbacks(beatTick)
        thread?.interrupt()
        runCatching { openLink?.close() }
        GlassesNetwork.ShellHelper.close()
        networkCallback?.let { cb -> runCatching { appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        thread = null
        pose = null
        synchronized(predictor) { predictor.reset() }
        lastSampleHostNs = 0L
        history.clear()
        val off = if (appContext?.let { SpaceStore(it).headTracking } == false) HeadTrackingStatus.TurnedOff else HeadTrackingStatus.Off
        generation.stop { _status.value = off }
        _live.value = null
        // Ethernet goes back to how it was once the bring-up (if any) has stopped.
        appContext?.let { GlassesEthernetLink.restore(it, ::ethernetLog) }
    }

    private fun ethernetLog(line: String) {
        _ethernet.value = (_ethernet.value + line).takeLast(60)
    }

    val tracking: Boolean get() = _status.value.tracking

    /**
     * 0.5.21: the poses' frame: [epoch] counts new fusions (a reset, a new tracker
     * thread), [jump] is the product of every recentre's turn since that fusion began, so the
     * glasses' record can undo Pocket XR's recentres ([com.hayeco.flipspaces.core.spatial.GlassesFrameAlign]).
     */
    data class FrameRef(val epoch: Int, val jump: Quat)

    @Volatile var frameRef = FrameRef(0, Quat.IDENTITY)
        private set

    /** 0.5.21: whether the glasses lie still now (the fusion's static detector), for the yaw offset check. */
    val lyingStill: Boolean get() = pose?.still == true

    /**
     * 0.5.10: the pose at [presentNs] (System.nanoTime), the time the frame being drawn reaches the
     * eyes ([com.hayeco.flipspaces.core.spatial.FramePacer]): the newest sample, on the phone's clock
     * ([ImuClock]), turned on by the smoothed gyro rate to that time. During a pause in the stream
     * (a reconnect) the last pose holds for up to [HeadHold.HOLD_MS], unpredicted, instead of the
     * view snapping to head locked; after that, or with no pose, null, and (0.5.6) null for anything
     * that is not a finite rotation, so a bad stream draws exactly the head locked view ([HeadView]).
     */
    fun renderPose(presentNs: Long = System.nanoTime(), record: Boolean = true): Quat? {
        val sampleNs = lastSampleHostNs
        val since = if (sampleNs == 0L) Long.MAX_VALUE else (System.nanoTime() - sampleNs) / 1_000_000L
        val q = synchronized(predictor) {
            when (HeadHold.use(since, predictor.orientation != null)) {
                // 0.5.15: the chain line's horizon is the frame's own
                // (centre row) pose; the per-row poses pass record = false (0.5.14 logged the bottom
                // row's, 8 ms high at 60 Hz).
                HeadHold.Use.PREDICT -> predictor.predict(presentNs).also {
                    if (record) {
                        lastPredictMs = (presentNs - predictor.sampleNs) / 1e6f
                        predictAhead.add(lastPredictMs.toDouble())
                    }
                }
                HeadHold.Use.HOLD -> predictor.orientation
                HeadHold.Use.NONE -> null
            }
        }
        // 0.5.32 (Mike, vc52: the windows still follow the head's natural tremor): the still head's lock. The frame's
        // own pose (record) moves it on; the rows' and the late warp's poses take the same hold or correction.
        val usable = HeadView.usable(q)
        // 0.5.64: the drawn orientation shook with small movements on Mike's Aura until it went through a One Euro
        // filter; the still lock then holds a pose that is already free of the tremor.
        val smoothed = synchronized(drawSmoothing) {
            when {
                usable == null -> null.also { drawSmoothing.free() }
                record -> drawSmoothing.update(usable, presentNs)
                else -> drawSmoothing.apply(usable)
            }
        }
        synchronized(restLock) {
            if (smoothed == null) {
                restLock.free()
                return null
            }
            return if (record) restLock.update(smoothed, presentNs) else restLock.apply(smoothed)
        }
    }

    /** 0.5.64: the drawn orientation's One Euro filter ([com.hayeco.flipspaces.core.spatial.DrawSmoothing]). */
    private val drawSmoothing = com.hayeco.flipspaces.core.spatial.DrawSmoothing()

    /** 0.5.64: draw smoothing on or off (the Menu's Developer card; on by default). */
    fun setDrawSmoothing(on: Boolean) {
        synchronized(drawSmoothing) {
            if (drawSmoothing.enabled != on) Log.i(TAG, "draw smoothing: ${if (on) "on" else "off"}")
            drawSmoothing.enabled = on
            if (!on) drawSmoothing.free()
        }
    }

    /** 0.5.32: the head's turn speed, deg/s (the predictor's peak held [turnSpeed]), for the glasses' duty on turns. */
    val turnSpeedDegS: Float get() = synchronized(predictor) { predictor.turnSpeed } * (180f / Math.PI.toFloat())

    /** 0.5.32: the still head's lock ([com.hayeco.flipspaces.core.spatial.RestLock]); its switch is on the Menu's Developer card. */
    private val restLock = com.hayeco.flipspaces.core.spatial.RestLock()

    /** 0.5.32: the still lock on or off (the Menu's Developer card; on by default). */
    fun setStillLock(on: Boolean) {
        synchronized(restLock) {
            if (restLock.enabled != on) Log.i(TAG, "still lock: ${if (on) "on" else "off"}")
            restLock.enabled = on
            if (!on) restLock.free()
        }
    }

    /**
     * The (unpredicted) pose at [uptimeMs] on SystemClock.uptimeMillis, the clock hand frames are
     * stamped with, so a hand seen by the glasses' camera is placed with the head pose of that
     * moment. Null without live tracking or when the time is out of the kept range.
     */
    fun poseAt(uptimeMs: Long): Quat? {
        if (lastSampleHostNs == 0L) return null
        return HeadView.usable(history.poseAt(uptimeMs))
    }

    /**
     * 0.5.16: one recentre per press. Requests within 300 ms of the last
     * accepted one are dropped and logged, whichever way they came (the Space's command, the phone,
     * the glasses' key), so nothing relevels twice.
     */
    fun recentre(why: String = "the Space") {
        val ok = synchronized(recentreGate) { recentreGate.accept(SystemClock.uptimeMillis()) }
        if (ok) {
            recentreRequested.set(true)
            synchronized(restLock) { restLock.free() }
            synchronized(drawSmoothing) { drawSmoothing.free() }
        }
        else Log.i(TAG, "recentre from $why dropped: one already requested within ${recentreGate.windowMs} ms (one press)")
    }

    private val recentreGate = com.hayeco.flipspaces.core.spatial.RecentreRoute.Debounce()

    /** 1.2.13 (Discussion #81): Settings' Recenter follows head tilt; the fusion takes it for the next recentre. */
    fun setRecentreFollowsTilt(context: Context, on: Boolean) {
        SpaceStore(context).recentreFollowsTilt = on
        Log.i(TAG, "recenter follows head tilt turned ${if (on) "on" else "off"} in Settings")
        applySettings(context)
    }

    fun applySettings(context: Context) {
        val store = SpaceStore(context)
        val next = fromStore(store, context)
        if (next.axes != settings.axes) resetRequested.set(true)
        settings = next
        val line = when {
            next.factoryMount != null || next.gyroMatrix != null -> "fusion uses the glasses' factory calibration: " + (GlassesCalibrationStore.last(context)?.line() ?: "") +
                if (next.factoryMount == null && GlassesCalibrationStore.last(context)?.displayMount != null) "; its display mount is not used on the ${store.model.label} (checked only on the Aura), the level calibration's is" else ""
            store.factoryCalibration -> "fusion uses the level calibration's mount: no factory calibration read yet"
            else -> "fusion uses the level calibration's mount: factory calibration switched off"
        }
        if (line != loggedFactory) {
            loggedFactory = line
            Log.i(TAG, line)
        }
    }

    /**
     * The saved settings the fusion uses; 0.5.15: with the level calibration's mount. 0.5.65: with the
     * glasses' factory gyro matrix and display mount when the switch is on and a calibration has been read.
     */
    private fun fromStore(store: SpaceStore, context: Context): FusionSettings {
        val cal = if (store.factoryCalibration) GlassesCalibrationStore.last(context) else null
        return settings.copy(
            axes = store.axisMap, predictSeconds = store.predictMs / 1000f, mountRollDeg = store.mountRollDeg, mountPitchDeg = store.mountPitchDeg,
            recentreFollowsTilt = store.recentreFollowsTilt,
            factoryMount = cal?.displayMount?.takeIf { store.model.factoryMountChecked }, gyroMatrix = cal?.gyroMatrix,
        )
    }

    /** 0.5.65: the control link read the glasses' calibration: the fusion takes it from the next settings beat. */
    fun factoryCalibrationArrived(context: Context) {
        applySettings(context)
    }

    private var loggedFactory: String? = null

    @Volatile private var lastReportAtNs = 0L

    /** 0.5.12: the gaps between reads that brought reports, and the reports a read: bursts or a stream. Reader thread. */
    private val readGaps = ImuReadGaps()
    private var loggedLearn: String? = null
    private var loggedOldBias: String? = null
    private var loggedYawGuard: String? = null
    private var loggedPoolCap: String? = null

    /** 0.5.19: when the glasses' wear was last told to the hand press gate. */
    private var lastWornMs = 0L
    private var lastStillLogMs = 0L

    /** 0.5.11: the bias updates already saved to [SpaceStore.gyroBias]. Reader thread. */
    private var savedBiasEvents = 0
    private var savedWornMinutes = 0

    /**
     * 0.5.11: one line on how the anchor is held, for the device check: no smoothing or dead zone on
     * the pose, the static detector's verdict, the bias and how often it moved.
     */
    private fun stillLine(fusion: HeadFusion): String {
        val b = fusion.gyroBias
        return String.format(
            java.util.Locale.ROOT,
            "head anchor: every rotation applied in full (no dead zone, no smoothing on the pose, prediction on from the first degree); glasses %s; gyro bias %.5f, %.5f, %.5f rad/s, learned %d times while static, %.0f s from gravity and %d yaw windows while worn; gravity skipped on %d samples (accelerating)",
            if (fusion.isStatic) "static" else "moving", b.x, b.y, b.z, fusion.biasEvents, fusion.wornLearnS, fusion.wornYawUpdates, fusion.gravitySkipped,
        )
    }

    private fun run(context: Context) {
        // 0.5.12: the IMU reader is the head of the render chain, so it runs at the
        // display's priority: each read is taken as it lands rather than when the scheduler gets to it.
        // 0.5.62: vc79's head reads still starved under load, so this thread asks for more. Urgent audio (-19) first, urgent display (-8) if that is refused; the
        // render thread's hint session takes this thread in too (RenderThreadBoost), which keeps it on the big cores.
        val prio = HeadReaderPriority.raise()
        Log.i(TAG, prio)
        headReaderTid = android.os.Process.myTid()
        val fusion = HeadFusion(settings)
        frameRef = FrameRef(frameRef.epoch + 1, Quat.IDENTITY)
        // 0.5.11: the bias learned in an earlier session, so start up does not have to learn it.
        // 0.5.15: the last static measurement (and a worn bias saved near it); 0.5.14's key held
        // the worn bias it saved while walking, so it is not used.
        val store = SpaceStore(context)
        val staticSeed = store.staticGyroBias
        if (staticSeed != null) {
            val worn = store.wornGyroBias?.takeIf { BiasSavePolicy.decide(it, staticSeed, staticThisSession = false).worn != null }
            val seed = worn ?: staticSeed
            fusion.seedBias(seed, staticSeed)
            Log.i(TAG, "head fusion: gyro bias from the last session %.5f, %.5f, %.5f rad/s (%s; static %.5f, %.5f, %.5f)".format(
                java.util.Locale.ROOT, seed.x, seed.y, seed.z, if (worn != null) "worn, near the static one" else "static", staticSeed.x, staticSeed.y, staticSeed.z,
            ))
            // 0.5.17: 0.5.14's key is not offered once a static bias is saved
            // (HeadFusion.offerOldBias): the static one supersedes it, and is often that key confirmed.
            // A static bias off along up is corrected by the rested yaw windows instead.
            store.gyroBias?.let { k ->
                Log.i(TAG, "head fusion: 0.5.14's saved bias %.5f, %.5f, %.5f rad/s not offered: the saved static bias supersedes it (%.5f, %.5f, %.5f from it); its yaw part is checked by the rested yaw windows (guard %.4f, %.4f once %d agree)".format(
                    java.util.Locale.ROOT, k.x, k.y, k.z, staticSeed.x - k.x, staticSeed.y - k.y, staticSeed.z - k.z,
                    settings.wornYawAnchorMax, settings.wornYawAnchorGrown, settings.wornYawAgreeCount,
                ))
            }
        } else {
            // 0.5.16: 0.5.14's key is offered, and taken (its yaw part with
            // it) once the x and z learned worn this session confirm it; a walked one never is.
            val old = store.gyroBias
            old?.let(fusion::offerOldBias)
            Log.i(TAG, "head fusion: no static gyro bias saved yet" + (old?.let { b -> " (0.5.14's saved bias %.5f, %.5f, %.5f rad/s is offered: taken once this session's worn x and z come within %.4f rad/s of it)".format(java.util.Locale.ROOT, b.x, b.y, b.z, settings.oldBiasConfirm) } ?: "") + "; learning it while worn, or on a table")
        }
        Log.i(TAG, com.hayeco.flipspaces.core.spatial.LevelCalibration.line(settings.mountRollDeg, settings.mountPitchDeg, store.levelCalibrated))
        // Once per start: the glasses were found as eth interfaces and handled one way or another.
        var ethernetTried = false
        // 0.5.6: 3 s after a failed round, doubling up to 60 s; back to 3 s on plug-in or a network change.
        val backoff = HeadRetryBackoff()
        // 0.5.6: interfaces the kernel said have no way to the glasses (ENETUNREACH, EHOSTUNREACH),
        // left alone until the network changes.
        val noRoute = mutableSetOf<String>()
        var lastPrint: String? = null
        while (!Thread.currentThread().isInterrupted) {
            takeKick()?.let { why ->
                Log.i(TAG, "head tracking: trying again now ($why)")
                backoff.reset()
                noRoute.clear()
            }
            // 1.1.12 (an Air 2 Pro user: "head tracking is not working even though it shows 3dof"): the Air family
            // streams its IMU on USB HID and has no USB network, so it is read there; up to 1.1.11 only the One family's
            // network was read and the Space stayed head locked on an Air.
            val air = AirImuLink.find(context)
            if (air != null) {
                if (!streamAir(air, context, fusion, backoff)) return
                continue
            }
            // 1.2.8 (Discussion #24, a VITURE Beast: no head tracking, and the status blamed a missing USB network): VITURE
            // glasses have no USB network. With the experimental reader on, their HID is read; otherwise the status says
            // plainly that they are not supported yet, and nothing is opened on them.
            val viture = VitureImuLink.find(context)
            val vitureOn = SpaceStore(context).vitureHeadTracking
            synchronized(vitureReconnect) { vitureReconnect.setEnabled(vitureOn) }
            if (viture != null) {
                val store = SpaceStore(context)
                if (vitureOn) {
                    if (!streamViture(viture, context, fusion, backoff, store.vitureAxes)) return
                } else {
                    val st = HeadTrackingStatus.VitureNotYet(viture.productId)
                    if (_status.value != st) Log.i(TAG, "head tracking: ${st.summary}")
                    report(st)
                    _answer.value = null
                    if (!waitFor(HID_RECHECK_MS, GlassesNetwork.scan(context).fingerprint, context)) return
                }
                continue
            }
            // 1.1.10 (USB 3318:0442 on a Pixel 8a: "No USB network to the glasses ... One UI may not bring up the glasses'
            // NCM or ECM interface"): other glasses whose IMU is on USB HID have no USB network to find. Nothing is opened
            // on them; the status says so, and the USB list is read again every [HID_RECHECK_MS] or on a kick (plug-in).
            val hid = HeadImuTransport.hidGlasses(GlassesUsbList.read(context))
            if (hid != null) {
                val st = HeadTrackingStatus.HidGlasses(hid.vendorId, hid.productId, hid.infos.map { it.label() })
                if (_status.value != st) Log.i(TAG, "head tracking: ${st.summary}")
                report(st)
                _answer.value = null
                if (!waitFor(HID_RECHECK_MS, GlassesNetwork.scan(context).fingerprint, context)) return
                continue
            }
            if (backoff.failures == 0) report(HeadTrackingStatus.Searching)
            var scan = GlassesNetwork.scan(context)
            if (scan.empty && !ethernetTried) {
                val outcome = GlassesEthernetLink.bringUp(context, ::ethernetLog)
                if (outcome == GlassesEthernetLink.Outcome.INTERRUPTED) return
                // No interface yet: the glasses may be plugged in later, so look again next round.
                ethernetTried = outcome != GlassesEthernetLink.Outcome.NO_INTERFACE
                scan = GlassesNetwork.scan(context)
            }
            if (lastPrint != null && scan.fingerprint != lastPrint) {
                Log.i(TAG, "head tracking: the glasses' network changed; trying again with the backoff reset")
                backoff.reset()
                noRoute.clear()
            }
            lastPrint = scan.fingerprint
            _networks.value = scan.seen
            if (scan.empty) {
                report(HeadTrackingStatus.NoNetwork(scan.seen, PhoneSystem.name(Build.MANUFACTURER)))
                if (!waitFor(backoff.nextDelayMs(), scan.fingerprint, context)) return
                continue
            }
            // 0.5.6: only networks with a route to the glasses are tried; with none, nothing is
            // tried until the network changes (Mike's eth1 said ENETUNREACH every 3 s).
            val reachable = scan.networks.filter { it.reachable && it.iface !in noRoute }.map { it.iface } +
                scan.kernel.map { it.first }.filter { it !in noRoute }
            if (reachable.isEmpty()) {
                // 1.1.8: with the addresses, so a report says which link it was ("eth0 169.254.1.10/24").
                val names = scan.networks.map { n -> (listOf(n.iface) + n.addresses.filter { '.' in it }).joinToString(" ") } +
                    scan.kernel.map { "${it.first} ${it.second.hostAddress} (no Android network)" }
                report(HeadTrackingStatus.NoRoute(names))
                _answer.value = "No route to ${OneImuProtocol.HOST} on ${names.joinToString(", ")}: nothing is tried until the glasses' network changes or they are plugged in again."
                Log.i(TAG, "head tracking: no route to ${OneImuProtocol.HOST} on ${names.joinToString(", ")}; waiting for a network change")
                if (!waitFor(Long.MAX_VALUE, scan.fingerprint, context)) return
                continue
            }
            val shizuku = ShizukuBridge.status(context)
            val routes = scan.routes(shizuku == ShizukuStatus.READY, refusedDirect).filter { it.iface in reachable }
            if (routes.isEmpty()) {
                report(HeadTrackingStatus.NoNetwork(scan.seen, PhoneSystem.name(Build.MANUFACTURER)))
                if (!waitFor(backoff.nextDelayMs(), scan.fingerprint, context)) return
                continue
            }
            val tried = mutableListOf<String>()
            var why: String? = null
            for (route in routes) {
                report(HeadTrackingStatus.Connecting(route.label))
                val link = try {
                    openRoute(route, context)
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return
                    val error = "${e.javaClass.simpleName}: ${e.message}"
                    if (route.kind == HeadRouteKind.DIRECT && HeadRoutePlan.bindRefused(error)) refusedDirect += route.iface
                    if (GlassesReach.noRoute(error)) noRoute += route.iface
                    val plain = GlassesReach.explain(error)
                    val explained = HeadRoutePlan.explain(error, scan.vpn, shizuku) ?: plain
                    if (plain != null && (why == null || GlassesReach.noAnswer(error))) why = plain
                    tried += "${route.label}: $error"
                    _attempts.value = tried.toList()
                    _connectResult.value = "Failed over ${route.label}: $error" + (explained?.let { "\n$it" } ?: "")
                    Log.i(TAG, "connect over ${route.label} failed: $error" + (explained?.let { " ($it)" } ?: ""))
                    report(HeadTrackingStatus.ConnectFailed(route.label, error + (explained?.let { ". $it" } ?: "")))
                    continue
                }
                tried += "${route.label}: connected"
                _attempts.value = tried.toList()
                _route.value = route.label
                _answer.value = "The glasses answered on ${route.host}:${OneImuProtocol.STREAM_PORT} over ${route.label}."
                _connectResult.value = "Connected over ${route.label} (${link.local})"
                Log.i(TAG, "head tracking route: connected over ${route.label} (${link.local})")
                openLink = link
                sourceLine = "TCP ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} over ${route.label}"
                // 0.5.10: the fusion, the recentre and the pose carry on across a reconnect: the
                // first report's time step is not plausible and is skipped, then the gyro goes on
                // from where it was (0.5.6 to 0.5.9 reset here, and forward became wherever the
                // head pointed, a snap on every drop).
                val opened = SystemClock.elapsedRealtime()
                val streamed = stream(link, route.label, fusion)
                runCatching { link.close() }
                openLink = null
                sourceLine = null
                _route.value = null
                Log.i(TAG, "head tracking stream over ${route.label} ended after ${(SystemClock.elapsedRealtime() - opened) / 1000.0} s: ${_status.value.summary}; the last pose holds for up to ${HeadHold.HOLD_MS / 1000} s while it reconnects")
                if (Thread.currentThread().isInterrupted) return
                // A stream that worked starts the backoff over; one that never sent a report does not.
                if (streamed) backoff.reset()
                why = if (streamed) "The stream stopped." else "The glasses accepted the connection but sent no head tracking reports."
                break
            }
            if (why != null) _answer.value = why
            val delay = backoff.nextDelayMs()
            val last = _status.value
            val reason = why ?: (last as? HeadTrackingStatus.ConnectFailed)?.let { "Could not connect over ${it.network}." } ?: "Could not connect."
            report(HeadTrackingStatus.RetryLater(reason, (delay / 1000).toInt()))
            Log.i(TAG, "head tracking: next try in ${delay / 1000} s (failed round ${backoff.failures}; ${GlassesNetwork.ShellHelper.started} shell helpers started so far)")
            if (!waitFor(delay, scan.fingerprint, context)) return
        }
    }

    /** 1.1.12: the Air glasses USB access was asked for (their device name), so Android's prompt shows once a plug-in. */
    private var askedUsbFor: String? = null

    // ---- 1.2.10 (Discussion #24, second report): the experimental VITURE reader's USB life ----

    /** When the VITURE reader asks for access, opens, keeps reading or lets go. Locked on itself. */
    private val vitureReconnect = VitureReconnect()

    /** The open VITURE link, for the axis switches; null with none open. */
    @Volatile private var vitureLink: VitureImuLink? = null

    /** Closes the open VITURE link (the stop, its answer, the interface released) and marks it closed. Reader thread. */
    private fun closeViture() {
        val link = synchronized(vitureReconnect) {
            vitureReconnect.closed()
            vitureLink.also { vitureLink = null }
        }
        runCatching { link?.close() }
    }

    /**
     * The head tracking screen's VITURE head tracking switch, saved. On asks for USB access again if it is missing and
     * tries now; off closes the open stream and releases the glasses. 1.2.12: saved either way, so off stays off and on
     * stays on through unplugging, the app's restart and updates.
     */
    fun setVitureEnabled(context: Context, on: Boolean) {
        SpaceStore(context).vitureHeadTracking = on
        Log.i(TAG, "VITURE head tracking ${if (on) "on" else "off"} (the head tracking screen)")
        synchronized(vitureReconnect) { vitureReconnect.setEnabled(on) }
        if (on) kick("VITURE head tracking on") else restart(context, "VITURE head tracking off")
    }

    /**
     * The VITURE axis switches, saved and applied to the open stream from its next sample: the reader is not stopped
     * (1.2.8 started it again, and the glasses then refused the start). The fusion starts over in the new axes, as a
     * new sensor axis layout does.
     */
    fun setVitureAxes(context: Context, axes: com.hayeco.flipspaces.core.spatial.VitureAxes) {
        SpaceStore(context).vitureAxes = axes
        val live = synchronized(vitureReconnect) {
            vitureLink?.axes = axes
            vitureReconnect.axesChanged(axes)
        }
        if (live) resetRequested.set(true)
        Log.i(TAG, "${axes.line()} (the head tracking screen)" + if (live) "; applied to the open stream" else "; taken when the glasses open")
    }

    private val vitureUsbWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java) ?: return
            if (!com.hayeco.flipspaces.core.spatial.VitureImuProtocol.isViture(device.vendorId)) return
            if (!com.hayeco.flipspaces.core.spatial.VitureDevices.carriesImu(device.productId, device.productName)) return
            val name = VitureImuLink.glassesName(device)
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    synchronized(vitureReconnect) { vitureReconnect.attached(SystemClock.uptimeMillis()) }
                    kick("$name plugged in on USB")
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    synchronized(vitureReconnect) { vitureReconnect.detached() }
                    Log.i(TAG, "head tracking: $name unplugged; the reader lets it go and waits for the plug-in")
                    kick("$name unplugged")
                }
                com.hayeco.flipspaces.hands.GlassesUsbAccess.ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "head tracking: USB access for $name ${if (granted) "given" else "not given"} in Android's prompt")
                    if (granted) kick("USB access given for $name")
                }
            }
        }
    }

    /** Hears VITURE glasses come and go, and the USB prompt's answer, while the process lives (FlipSpacesApp). */
    fun registerUsbWatch(context: Context) {
        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(com.hayeco.flipspaces.hands.GlassesUsbAccess.ACTION_PERMISSION)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(context.applicationContext, vitureUsbWatch, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "VITURE USB watch: ${it.message}") }
    }

    /**
     * 1.1.12: one round on Air family glasses ([AirImuLink]): USB access, the IMU's HID interface, its stream until it
     * ends, then the backoff's wait. False when the tracker was stopped.
     */
    private fun streamAir(device: android.hardware.usb.UsbDevice, context: Context, fusion: HeadFusion, backoff: HeadRetryBackoff): Boolean {
        val name = AirImuLink.glassesName(device)
        val print = GlassesNetwork.scan(context).fingerprint
        if (!AirImuLink.manager(context).hasPermission(device)) {
            report(HeadTrackingStatus.UsbPermission(name))
            if (askedUsbFor != device.deviceName) {
                askedUsbFor = device.deviceName
                Log.i(TAG, "head tracking: $name found on USB without access to it; asking Android (its USB prompt)")
                runCatching { com.hayeco.flipspaces.hands.GlassesUsbAccess.requestPermission(context, device) }
                    .onFailure { Log.w(TAG, "head tracking: the USB prompt for $name failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
            return waitFor(1000L, print, context)
        }
        val link = try {
            AirImuLink.open(context, device, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) return false
            val error = "${e.javaClass.simpleName}: ${e.message}"
            val delay = backoff.nextDelayMs()
            report(HeadTrackingStatus.UsbFailed(name, error))
            _attempts.value = listOf("$name over USB HID: $error")
            Log.w(TAG, "head tracking: $name over USB HID failed: $error; next try in ${delay / 1000} s")
            return waitFor(delay, print, context)
        }
        _attempts.value = listOf("${link.label}: opened, start command sent")
        _route.value = link.label
        sourceLine = link.label
        Log.i(TAG, "head tracking route: ${link.label}, the IMU's start command sent")
        val opened = SystemClock.elapsedRealtime()
        val streamed = try {
            stream(link, link.label, fusion)
        } finally {
            runCatching { link.close() }
            sourceLine = null
            _route.value = null
        }
        Log.i(TAG, "head tracking stream over ${link.label} ended after ${(SystemClock.elapsedRealtime() - opened) / 1000.0} s: ${_status.value.summary}; ${link.timestampLine}")
        if (Thread.currentThread().isInterrupted) return false
        if (streamed) backoff.reset()
        val delay = backoff.nextDelayMs()
        val why = if (streamed) "The stream stopped." else "The $name took the start command but sent no head tracking reports."
        _answer.value = why
        report(HeadTrackingStatus.RetryLater(why, (delay / 1000).toInt()))
        return waitFor(delay, print, context)
    }

    /**
     * 1.2.8: one round on VITURE glasses with the experimental reader on ([VitureImuLink]), as [streamAir] does for the Air
     * family. A round that gets no motion at all says plainly that these glasses are not supported yet.
     */
    private fun streamViture(device: android.hardware.usb.UsbDevice, context: Context, fusion: HeadFusion, backoff: HeadRetryBackoff, axes: com.hayeco.flipspaces.core.spatial.VitureAxes): Boolean {
        val name = VitureImuLink.glassesName(device)
        val print = GlassesNetwork.scan(context).fingerprint
        // 1.2.10 (Discussion #24, second report): USB access checked before every open, and asked for again after a
        // plug-in, the switch turned on or access lost ([VitureReconnect]); 1.2.8 asked once per device name for good.
        val permitted = VitureImuLink.manager(context).hasPermission(device)
        val step = synchronized(vitureReconnect) { vitureReconnect.next(VitureReconnect.Device(device.deviceName, permitted), SystemClock.uptimeMillis()) }
        when (step) {
            // 1.2.12: Android's "Open Pocket XR" plug-in prompt for the Beast (usb_glasses.xml) grants access with its OK.
            VitureReconnect.Step.WAIT_FOR_PLUG_IN_PROMPT -> {
                report(HeadTrackingStatus.UsbPermission(name))
                return waitFor(1000L, print, context)
            }
            VitureReconnect.Step.ASK -> {
                report(HeadTrackingStatus.UsbPermission(name))
                Log.i(TAG, "head tracking: $name found on USB without access to it; asking Android (its USB prompt) for the experimental VITURE reader")
                runCatching { com.hayeco.flipspaces.hands.GlassesUsbAccess.requestPermission(context, device) }
                    .onFailure { Log.w(TAG, "head tracking: the USB prompt for $name failed: ${it.javaClass.simpleName}: ${it.message}") }
                return waitFor(1000L, print, context)
            }
            VitureReconnect.Step.WAIT_FOR_ANSWER -> {
                report(HeadTrackingStatus.UsbPermission(name))
                return waitFor(1000L, print, context)
            }
            VitureReconnect.Step.IDLE -> return waitFor(1000L, print, context)
            // A link left open from an earlier round (it should not be): closed, then the next round opens afresh.
            VitureReconnect.Step.CLOSE, VitureReconnect.Step.READ -> {
                closeViture()
                return true
            }
            VitureReconnect.Step.OPEN -> Unit
        }
        val link = try {
            VitureImuLink.open(context, device, axes, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) return false
            val error = "${e.javaClass.simpleName}: ${e.message}"
            val delay = backoff.nextDelayMs()
            report(HeadTrackingStatus.UsbFailed(name, error))
            _attempts.value = listOf("$name over USB HID (experimental VITURE reader): $error")
            Log.w(TAG, "head tracking: $name over USB HID failed: $error; next try in ${delay / 1000} s")
            return waitFor(delay, print, context)
        }
        synchronized(vitureReconnect) {
            vitureReconnect.opened(device.deviceName)
            vitureLink = link
            // Switches changed while it opened are taken now.
            link.axes = SpaceStore(context).vitureAxes
        }
        _attempts.value = listOf("${link.label}: opened, the raw stream start sent")
        _route.value = link.label
        sourceLine = link.label
        Log.i(TAG, "head tracking route: ${link.label}, the raw stream ${link.startLine}; ${link.axes.line()}")
        val opened = SystemClock.elapsedRealtime()
        val streamed = try {
            stream(link, link.label, fusion)
        } finally {
            closeViture()
            sourceLine = null
            _route.value = null
        }
        Log.i(TAG, "head tracking stream over ${link.label} ended after ${(SystemClock.elapsedRealtime() - opened) / 1000.0} s: ${_status.value.summary}; ${link.clockLine}; ${link.counts}")
        if (Thread.currentThread().isInterrupted) return false
        if (streamed) backoff.reset()
        val delay = backoff.nextDelayMs()
        if (streamed) {
            _answer.value = "The stream stopped."
            report(HeadTrackingStatus.RetryLater("The stream stopped.", (delay / 1000).toInt()))
        } else {
            _answer.value = HeadTrackingStatus.VITURE_HINT
            report(HeadTrackingStatus.VitureNotYet(device.productId, experimentalOn = true))
        }
        return waitFor(delay, print, context)
    }

    // ---- 1.1.12: the once a second PocketXR-Tracking line ----

    private const val TRACK_TAG = "PocketXR-Tracking"
    private const val BEAT_MS = 1000L
    private val beat = com.hayeco.flipspaces.core.spatial.TrackingBeat()

    /** IMU samples fused since the process started (reader thread writes, the beat reads). */
    @Volatile private var fusedSamples = 0L

    /** Where the open stream's reports come from, null with none open. */
    @Volatile private var sourceLine: String? = null

    /** The newest accelerometer as the fusion takes it in, ten times a second. */
    @Volatile private var lastAccel: Vec3? = null

    /** The last line, for the Glasses report. */
    @Volatile var lastTrackingLine: String? = null
        private set

    /** 1.2.13 (Discussion #81): the newest rest's accelerometer (sensor axes) and the gravity check's line, for the Glasses report. */
    @Volatile var lastRestGravity: Vec3? = null
        private set
    @Volatile var lastAxesCheck: String? = null
        private set

    /** 1.2.13: the axis layout, where it came from and gravity at the last rest, one line for the Glasses report. */
    fun axesReport(context: Context): String {
        val store = SpaceStore(context)
        return com.hayeco.flipspaces.core.spatial.GravityAxes.reportLine(store.axisMap, store.axesFrom(), lastRestGravity, lastAxesCheck)
    }

    /**
     * 1.2.13 (Discussion #81, a level head read as looking up; for axes a quarter or half turn off): the fusion's newest rest reading
     * against the axis layout in use ([com.hayeco.flipspaces.core.spatial.GravityAxes]). When gravity says another layout,
     * it is saved for the model, the gyro bias is carried over into it, and the fusion starts again on it. Reader thread.
     */
    private fun checkAxes(fusion: HeadFusion) {
        val rest = fusion.takeRestGravity() ?: return
        val c = appContext ?: return
        val store = SpaceStore(c)
        val verdict = com.hayeco.flipspaces.core.spatial.GravityAxes.check(rest, settings.axes, store.modelAxes)
        lastRestGravity = rest.raw
        lastAxesCheck = verdict.line
        Log.i(TAG, verdict.line)
        val next = verdict.axes ?: return
        val old = settings.axes
        fun carry(b: Vec3) = com.hayeco.flipspaces.core.spatial.GravityAxes.carry(b, old, next)
        val bias = fusion.gyroBias.takeIf { it != Vec3.ZERO }?.let(::carry)
        val static = fusion.staticBias?.let(::carry)
        store.setAxesByGravity(next, verdict.line)
        store.staticGyroBias?.let { store.staticGyroBias = carry(it) }
        store.wornGyroBias?.let { store.wornGyroBias = carry(it) }
        settings = settings.copy(axes = next)
        fusion.settings = settings
        fusion.reset()
        bias?.let { fusion.seedBias(it, static) }
        frameRef = FrameRef(frameRef.epoch + 1, Quat.IDENTITY)
        history.clear()
        synchronized(predictor) { predictor.reset() }
        lastSampleHostNs = 0L
    }

    private val beatTick = object : Runnable {
        override fun run() {
            runCatching { logBeat() }.onFailure { Log.w(TRACK_TAG, "tracking line failed: ${it.javaClass.simpleName}: ${it.message}") }
            graceHandler.postDelayed(this, BEAT_MS)
        }
    }

    private fun logBeat() {
        val c = appContext ?: return
        val facts = com.hayeco.flipspaces.core.spatial.TrackingFacts(
            glasses = glassesSeen(c), source = sourceLine, follow = SpaceSession.follow, status = _status.value,
            samples = fusedSamples, pose = pose?.orientation, accel = lastAccel, axes = settings.axes,
        )
        val line = beat.line(facts, System.nanoTime())
        lastTrackingLine = line
        Log.i(TRACK_TAG, line)
    }

    /** The glasses' model from their display and every XREAL device on USB, with whether Pocket XR may use it. */
    private fun glassesSeen(c: Context): String {
        val manager = AirImuLink.manager(c)
        // 1.2.8 (Discussion #24): VITURE's devices too; their line said "no XREAL device on USB" and nothing more.
        val usb = runCatching { manager.deviceList.values.filter { com.hayeco.flipspaces.core.spatial.GlassesVendors.name(it.vendorId) != null } }.getOrDefault(emptyList())
        val devices = if (usb.isEmpty()) "no XREAL or VITURE device on USB" else usb.joinToString(", ") { d ->
            val air = AirImuProtocol.isAir(d.vendorId, d.productId)
            "%s USB %04X:%04X%s, USB access %s".format(
                java.util.Locale.ROOT, (if (air) AirImuProtocol.modelName(d.productId) else null) ?: d.productName ?: com.hayeco.flipspaces.core.spatial.GlassesVendors.name(d.vendorId), d.vendorId, d.productId,
                if (air) " (Air family, IMU on HID interface ${AirImuProtocol.imuInterface(d.productId)})" else "",
                if (runCatching { manager.hasPermission(d) }.getOrDefault(false)) "yes" else "no",
            )
        }
        return "display says ${SpaceStore(c).model.label}; $devices"
    }

    /** Poses from before a recentre or a new mount are in the old frame: the prediction starts from the new one. Reader thread. */
    private fun restartPrediction(fusion: HeadFusion) {
        pose = fusion.pose()
        val now = System.nanoTime()
        synchronized(predictor) {
            predictor.reset()
            pose?.let { predictor.add(it.orientation, it.angularVelocity, now) }
        }
        lastSampleHostNs = now
        history.clear()
    }

    // ---- 0.5.15: the level calibration ----

    private val levelRequested = AtomicBoolean(false)
    private val _level = MutableStateFlow<String?>(null)

    /** The last level calibration's result in plain words, for the head tracking screen. */
    val level: StateFlow<String?> = _level.asStateFlow()

    /** Takes the level calibration on the next IMU read: the display is held level now. Any thread. */
    fun takeLevelCalibration() {
        if (!tracking) {
            _level.value = "Not saved: head tracking is not running."
            return
        }
        levelRequested.set(true)
    }

    /** Forgets the level calibration: the IMU is taken as level with the display again. */
    fun clearLevelCalibration(context: Context) {
        SpaceStore(context).clearLevel()
        applySettings(context)
        _level.value = "Level calibration cleared."
        Log.i(TAG, "level calibration: cleared")
    }

    /** Reader thread: reads the averaged gravity, saves it as the mount when it is steady and plausible. */
    private fun takeLevel(fusion: HeadFusion) {
        val r = fusion.levelReading()
        val c = appContext
        val msg = when {
            r == null -> "Not saved: hold your head still for a moment first (gravity was not steady or the head was turning)."
            // 0.5.16: pitch up to 25 degrees (the Aura's IMU sits about 14.5 pitched), roll up to 10. 1.1.7: pitch up to 40 (a One Pro).
            !com.hayeco.flipspaces.core.spatial.LevelCalibration.plausible(r.first, r.second) ->
                "Not saved: the glasses read roll %.1f, pitch %.1f degrees: %s; is the line really on a level edge at eye height?".format(
                    java.util.Locale.ROOT, r.first, r.second, com.hayeco.flipspaces.core.spatial.LevelCalibration.refusal(r.first, r.second),
                )
            c == null -> "Not saved: no app context."
            else -> {
                val store = SpaceStore(c)
                store.mountRollDeg = r.first
                store.mountPitchDeg = r.second
                settings = settings.copy(mountRollDeg = r.first, mountPitchDeg = r.second)
                fusion.settings = settings
                restartPrediction(fusion)
                "Saved: the IMU reads roll %.2f, pitch %.2f degrees while the display is level; applied from now on.".format(java.util.Locale.ROOT, r.first, r.second)
            }
        }
        Log.i(TAG, "level calibration: $msg")
        _level.value = msg
    }

    private fun takeKick(): String? = synchronized(wake) { kicked.also { kicked = null } }

    /**
     * 0.5.6: waits up to [ms], looking at the glasses' network every [NETWORK_LOOK_MS]: a kick
     * or a changed network ends the wait early. False when the tracker was stopped.
     */
    private fun waitFor(ms: Long, print: String, context: Context): Boolean {
        val end = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + ms
        try {
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return true
                synchronized(wake) {
                    if (kicked == null && !nudged) wake.wait(minOf(left, NETWORK_LOOK_MS))
                    nudged = false
                    if (kicked != null) return true
                }
                if (Thread.currentThread().isInterrupted) return false
                if (GlassesNetwork.scan(context).fingerprint != print) return true
            }
        } catch (_: InterruptedException) {
            return false
        }
    }

    /** Reads until the stream breaks. True when at least one IMU report came. */
    /**
     * 0.5.53 (Mike, vc73: the windows' motion grew choppier as the phone heated): opens [route]; the app's own socket is
     * tried again [DIRECT_RETRIES] times, [DIRECT_RETRY_MS] apart, when it only timed out or was refused (the glasses still
     * coming up), before the shell helper is tried. In vc73 one such failure 0.4 s before the glasses answered left the whole
     * session on the helper: every report through a second process, 21 % of a core (`top`). A bind refusal (the VPN case the
     * helper is for) goes to the helper at once.
     */
    private fun openRoute(route: GlassesNetwork.Route, context: Context): GlassesNetwork.Link {
        var tries = 0
        while (true) {
            try {
                return route.open(context, route.host, OneImuProtocol.STREAM_PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                val error = "${e.javaClass.simpleName}: ${e.message}"
                if (route.kind != HeadRouteKind.DIRECT || HeadRoutePlan.bindRefused(error) || tries >= DIRECT_RETRIES || Thread.currentThread().isInterrupted) throw e
                tries++
                Log.i(TAG, "connect over ${route.label} failed ($error); the glasses may still be coming up, so the app's own socket again in $DIRECT_RETRY_MS ms (try ${tries + 1} of ${DIRECT_RETRIES + 1})")
                Thread.sleep(DIRECT_RETRY_MS)
            }
        }
    }

    // ---- 0.5.55: the app's garbage collection, once per chain line (every 10 s) ----

    private var gcCount = 0L
    private var gcBlocking = 0L
    private var gcMs = 0L
    private var gcBytes = 0L
    private var gcAtNs = 0L

    /** ART's figure [name] (Debug.getRuntimeStat, API 23), or -1 when it is not there. */
    private fun gcStat(name: String): Long = runCatching { android.os.Debug.getRuntimeStat(name)?.toLongOrNull() }.getOrNull() ?: -1L

    /** Takes the GC figures now as the start of the next [gcLine]. Reader thread. */
    private fun gcMark() {
        gcCount = gcStat("art.gc.gc-count")
        gcBlocking = gcStat("art.gc.blocking-gc-count")
        gcMs = gcStat("art.gc.gc-time")
        gcBytes = gcStat("art.gc.bytes-allocated")
        gcAtNs = System.nanoTime()
    }

    /**
     * 0.5.55 (Mike: one GC every 16 s, 1.5 MB/s allocated): the whole app's collections, their time and the bytes it
     * allocated since the last line, for the next log pull to show what the head path's cut did. A few strings every
     * 10 s, like the lines around it.
     */
    private fun gcLine(): String {
        val count = gcStat("art.gc.gc-count")
        val blocking = gcStat("art.gc.blocking-gc-count")
        val ms = gcStat("art.gc.gc-time")
        val bytes = gcStat("art.gc.bytes-allocated")
        val s = (System.nanoTime() - gcAtNs) / 1e9
        val line = if (count < 0 || gcCount < 0 || bytes < 0 || gcBytes < 0 || s <= 0.0) {
            "head gc: not available on this Android"
        } else {
            String.format(
                java.util.Locale.ROOT,
                "head gc: %d collections (%d blocking), %d ms in them, %.2f MB allocated a second over the last %.1f s (the whole app)",
                count - gcCount, if (blocking >= 0 && gcBlocking >= 0) blocking - gcBlocking else 0L, if (ms >= 0 && gcMs >= 0) ms - gcMs else 0L,
                (bytes - gcBytes) / 1e6 / s, s,
            )
        }
        gcCount = count
        gcBlocking = blocking
        gcMs = ms
        gcBytes = bytes
        gcAtNs = System.nanoTime()
        return line
    }

    /** 1.1.12: the One family's TCP stream as a report source. */
    private class NetworkSource(private val link: GlassesNetwork.Link) : ImuReportSource {
        private val framer = OneImuFramer()

        init {
            // 1.2.3 (Mike's short lags and audio pops came with heat; simpleperf, worn: 37 % of this thread was the
            // socket, its cost the 1000 wake-ups a second, not the poll): a read wakes once [WAKE_REPORTS] reports
            // are waiting, so half the wake-ups. The newest pose is then up to one report (1 ms) older, which the
            // prediction to the frame's time covers; the reports keep their own times ([ImuClock]).
            val frame = OneImuProtocol.HEADER_BYTES + OneImuProtocol.REPORT_BYTES
            link.wakeAfter((WAKE_REPORTS - 1) * frame)?.let { Log.i(TAG, "head reads: $it ($WAKE_REPORTS reports of $frame bytes)") }
        }
        private val buf = ByteArray(16 * 1024)
        override val counts: FramerCounts get() = framer.counts
        override val noReportsHint: String = HeadTrackingStatus.ONE_FAMILY_HINT

        override fun read(out: ArrayList<com.hayeco.flipspaces.core.spatial.ImuReport>): Int {
            val n = link.read(buf)
            if (n > 0) framer.append(buf, n, out) else out.clear()
            return n
        }
    }

    private fun stream(link: GlassesNetwork.Link, label: String, fusion: HeadFusion): Boolean = stream(NetworkSource(link), label, fusion)

    private fun stream(source: ImuReportSource, label: String, fusion: HeadFusion): Boolean {
        val rate = RateMeter()
        val reads = RateMeter()
        val clock = ImuClock()
        var lastChainMs = SystemClock.elapsedRealtime()
        val startMs = SystemClock.elapsedRealtime()
        var lastImuMs = startMs
        var lastLiveMs = 0L
        var lastStatusMs = 0L
        var everStreamed = false
        // 0.5.55: one list each for the reports and the fused poses of a read, kept for the stream (two new lists a read).
        val reports = ArrayList<com.hayeco.flipspaces.core.spatial.ImuReport>(64)
        val fused = ArrayList<HeadPose>(64)
        // 0.5.55: the GC figures at the last chain line, for the "head gc:" line ([gcLine]).
        gcMark()
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                source.read(reports)
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) return everStreamed
                if (everStreamed) report(HeadTrackingStatus.Lost(label, "${e.javaClass.simpleName}: ${e.message}"))
                else report(HeadTrackingStatus.ConnectFailed(label, "read failed: ${e.javaClass.simpleName}: ${e.message}"))
                return everStreamed
            }
            if (n < 0) {
                report(if (everStreamed) HeadTrackingStatus.Lost(label, "the glasses closed the connection")
                else HeadTrackingStatus.NoReports(label, ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt(), source.counts.bytes, source.counts, source.noReportsHint))
                return everStreamed
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (resetRequested.getAndSet(false)) {
                fusion.reset()
                frameRef = FrameRef(frameRef.epoch + 1, Quat.IDENTITY)
                history.clear()
                synchronized(predictor) { predictor.reset() }
                lastSampleHostNs = 0L
            }
            fusion.settings = settings
            if (n > 0) {
                // 0.5.55: into the kept lists, walked by index (no iterator), and the poses only tested with
                // [HeadView.isUsable] (usable made a normalized Quat that was thrown away, twice a sample).
                fused.clear()
                for (ri in 0 until reports.size) {
                    val r = reports[ri]
                    if (r.type != ImuReportType.IMU) continue
                    fusion.update(r)?.let { fused += it }
                }
                val imu = fused.size
                fusedSamples += imu
                if (imu > 0) {
                    // 0.5.6: a pose that is not a finite rotation is dropped, never drawn ([HeadView]).
                    pose = fused.last().takeIf { HeadView.isUsable(it.orientation) } ?: pose
                    // 0.5.10: every report on the phone's clock from the glasses' own timestamps
                    // ([ImuClock]), not from when its read arrived, so bursts do not shake the pose.
                    val arrivedNs = System.nanoTime()
                    for (pi in 0 until fused.size) {
                        val p = fused[pi]
                        if (!HeadView.isUsable(p.orientation)) continue
                        val hostNs = clock.toHost(p.timestampNs, arrivedNs)
                        history.add(hostNs / 1_000_000L, p.orientation, p.angularVelocity)
                        synchronized(predictor) { predictor.add(p.orientation, p.angularVelocity, hostNs) }
                        tremorBands.add(hostNs, p.angularVelocity)
                        gyroRing.add(hostNs, p.angularVelocity)
                        lastSampleHostNs = hostNs
                    }
                    reads.tick(arrivedNs)
                    readGaps.add(arrivedNs, imu)
                    // 0.5.12: once a second while the glasses lie still, and at each bias learning event.
                    fusion.lastLearnLine?.let { if (it != loggedLearn) { loggedLearn = it; Log.i(TAG, it) } }
                    // 0.5.16: the start gate's result the moment it passes, and which way.
                    fusion.takeStartGateEvent()?.let { Log.i(TAG, it) }
                    // 1.2.13 (Discussion #81): the start's gravity checks the axis layout.
                    checkAxes(fusion)
                    // 0.5.16: the old saved bias confirmed: it is the static bias
                    // from now on, saved so the next session starts seeded.
                    fusion.oldBiasConfirmedLine?.let { line ->
                        if (line != loggedOldBias) {
                            loggedOldBias = line
                            Log.i(TAG, line)
                            fusion.staticBias?.let { b -> appContext?.let { c -> SpaceStore(c).staticGyroBias = b } }
                        }
                    }
                    // 0.5.17: once, when rested yaw windows agreed and the yaw guard grew.
                    fusion.yawGuardLine?.let { line ->
                        if (line != loggedYawGuard) {
                            loggedYawGuard = line
                            Log.i(TAG, line)
                        }
                    }
                    // 0.5.19: once, when the pool's cap near the static bias lifts.
                    fusion.poolCapLine?.let { line ->
                        if (line != loggedPoolCap) {
                            loggedPoolCap = line
                            Log.i(TAG, line)
                        }
                    }
                    // 0.5.19: whether the glasses are on a head, for the hand press gate.
                    val wornNow = SystemClock.uptimeMillis()
                    if (wornNow - lastWornMs >= 100L) {
                        lastWornMs = wornNow
                        // 1.2.13: against gravity, so a recentre that followed a tilted head does not hide or fake a tilt.
                        val (pitch, roll) = fusion.levelPitchRoll()
                        com.hayeco.flipspaces.core.hands.PhoneHandling.shared.glasses(roll, pitch, fusion.isStatic, wornNow)
                    }
                    val nowStill = SystemClock.elapsedRealtime()
                    if (fusion.lyingStill && nowStill - lastStillLogMs >= 1_000L) {
                        lastStillLogMs = nowStill
                        Log.i(TAG, fusion.stillnessLine())
                        // 0.5.20: with the head held still while worn, the tremor itself.
                        if (!fusion.isStatic && tremorBands.ready) Log.i(TAG, tremorBands.line())
                        // 0.5.21: the raw gyro of a worn still head, to a file for the next replay.
                        if (gyroRing.due(nowStill, !fusion.isStatic, fusion.stillPeakToPeak)) writeGyroRing(fusion.stillPeakToPeak)
                    }
                    val nowChain = SystemClock.elapsedRealtime()
                    if (nowChain - lastChainMs >= 10_000L) {
                        lastChainMs = nowChain
                        val waited = clock.waited(fused.last().timestampNs, arrivedNs) / 1e6f
                        val (p50, p95) = synchronized(predictor) {
                            (if (predictAhead.count > 0) predictAhead.median().toFloat() else lastPredictMs) to
                                (if (predictAhead.count > 0) predictAhead.percentile(0.95).toFloat() else lastPredictMs)
                        }
                        Log.i(TAG, HeadChain(rate.rate, reads.rate, waited, p50, SpaceSession.pacerLine ?: "no Space frames", p95).line())
                        // 0.5.18: the still head mode and the gravity pull held to a slow ramp, every 10 s.
                        // 0.5.20: it follows only glasses lying still (the gyro's RMS under 15 Hz).
                        val (share, weight) = synchronized(predictor) { predictor.predictionShare to predictor.stillWeight }
                        val lying = synchronized(predictor) { predictor.lyingRms }
                        Log.i(TAG, "head still mode: prediction at %.0f %% now (still weight %.2f, gyro under 15 Hz %.4f rad/s RMS, lying still under %.4f); %d gravity samples held to the still head's slow ramp so far".format(java.util.Locale.ROOT, share * 100, weight, lying, predictor.lyingRmsEnter, fusion.correctionsLimited))
                        // 0.5.20: what the tremor predictor learned and does at today's horizon.
                        val tremorLine = synchronized(predictor) {
                            val (byTremor, byFallback) = predictor.takeTremorCounts()
                            predictor.tremor?.let { t ->
                                t.line(p50.toDouble()) + "; %d frames by it, %d by 0.5.19's fallback (a gap in the stream); learned weights at %.0f %% now (head speed %.3f rad/s)".format(java.util.Locale.ROOT, byTremor, byFallback, predictor.tremorShare * 100, predictor.turnSpeed)
                            }
                        }
                        tremorLine?.let { Log.i(TAG, it) }
                        // 0.5.30: the still blend (the prediction scaled by the share of it that came true).
                        val (bGain, bShare) = synchronized(predictor) { predictor.blendGain to predictor.blendShare }
                        Log.i(TAG, "still blend: %.0f %% of the predicted turn came true over the last %.0f ms, prediction at %.0f %% now (1 on any move)".format(java.util.Locale.ROOT, bGain * 100, predictor.blendTauMs, bShare * 100))
                        // 0.5.32: how much of the last 10 s the still lock held, and its lets go.
                        Log.i(TAG, synchronized(restLock) { restLock.line() })
                        // 0.5.64: how far the drawn orientation trailed the head over the last 10 s.
                        Log.i(TAG, synchronized(drawSmoothing) { drawSmoothing.line() })
                        Log.i(TAG, stillLine(fusion))
                        // 0.5.14: the start gate's result and how much of the
                        // last 10 s gravity was used, so a session with gravity off shows at once.
                        Log.i(TAG, "head gravity: ${fusion.startGateLine()}; ${fusion.gravityShareLine()}")
                        Log.i(TAG, "head reads: ${readGaps.line()}")
                        // 0.5.55: the app's garbage collections since the last chain line, with these lines.
                        Log.i(TAG, gcLine())
                        // 0.5.15: yaw, pitch and roll every 10 s, and what gravity says.
                        Log.i(TAG, fusion.attitudeLine())
                        // 0.5.16: the yaw bias and the yaw drift, measured every 10 s.
                        Log.i(TAG, fusion.yawLine())
                        // 0.5.14: a bias learned while worn is saved too (each minute of learning), so
                        // the next session starts from it instead of 0. 0.5.15: a static measurement
                        // is saved as such; a worn bias only while it is near the last static one.
                        val wornMinutes = (fusion.wornLearnS / 60f).toInt() + fusion.wornYawUpdates
                        if (fusion.biasEvents != savedBiasEvents || wornMinutes != savedWornMinutes) {
                            savedBiasEvents = fusion.biasEvents
                            savedWornMinutes = wornMinutes
                            val save = BiasSavePolicy.decide(fusion.gyroBias, fusion.staticBias, fusion.staticThisSession)
                            appContext?.let { c ->
                                val s = SpaceStore(c)
                                save.static?.let { s.staticGyroBias = it }
                                s.wornGyroBias = save.worn
                            }
                            Log.i(TAG, save.line)
                        }
                    }
                }
                if (imu > 0) {
                    lastImuMs = nowMs
                    lastReportAtNs = SystemClock.elapsedRealtimeNanos()
                    rate.tick(lastReportAtNs, imu)
                    if (!everStreamed) Log.i(TAG, "first IMU report over $label")
                    everStreamed = true
                }
            }
            if (levelRequested.getAndSet(false)) takeLevel(fusion)
            if (recentreRequested.getAndSet(false)) {
                // 0.5.15: roll and pitch relevelled too (when steady), and every recentre logged.
                val before = fusion.pose().orientation
                Log.i(TAG, fusion.recentre())
                // 0.5.21: the turn the recentre gave the frame, for the glasses' record.
                val turn = (fusion.pose().orientation * before.conjugate()).normalized()
                frameRef = frameRef.copy(jump = (turn * frameRef.jump).normalized())
                restartPrediction(fusion)
                // 1.2.13 (Discussion #81): a steady recentre is a head looking ahead, so its gravity checks the layout.
                checkAxes(fusion)
            }
            val silentS = (nowMs - lastImuMs) / 1000
            if (silentS >= NO_REPORT_SECONDS) {
                // 0.5.22: a stream that worked and went quiet is lost, not "no sensor reports".
                report(com.hayeco.flipspaces.core.spatial.HeadSilence.status(label, everStreamed, silentS, ((nowMs - startMs) / 1000).toInt(), source.counts.bytes, source.counts, source.noReportsHint))
                if (silentS >= NO_REPORT_SECONDS * 3) return everStreamed // reconnect
            } else if (everStreamed && (_status.value !is HeadTrackingStatus.Streaming || nowMs - lastStatusMs >= STATUS_EVERY_MS)) {
                // 0.5.48 (Mike, vc68: windows shaky when looking around, the phone at severe heat): the status at most
                // twice a second. 0.5.6 to 0.5.47 made a new one, with its formatted line, on every report: a fifth of
                // this thread's time (simpleperf on the Flip), at 1000 reports a second.
                lastStatusMs = nowMs
                val st = HeadTrackingStatus.Streaming(label, rate.rate)
                if (_status.value != st) report(st)
            }
            // 0.5.49 (Mike: "batch the glasses' motion reads"): rest [READ_BATCH_NS] before the next read, so it takes
            // the reports that came meanwhile in one go (off: see [READ_BATCH_NS]). 0.5.6 to 0.5.48 read each 1 ms report as it came: 1000
            // wake-ups, socket reads and polls a second, a third of this thread (simpleperf on the Flip). The reports keep
            // their own times ([ImuClock]: the least waited sample sets the offset), so only the newest pose is up to
            // that much older, which the prediction to the frame's time covers.
            if (n > 0 && everStreamed && READ_BATCH_NS > 0L) java.util.concurrent.locks.LockSupport.parkNanos(READ_BATCH_NS)
            if (nowMs - lastLiveMs >= 100) {
                lastLiveMs = nowMs
                lastAccel = fusion.lastRawAccel
                val p = pose
                if (p != null) {
                    val (y, pi, r) = p.orientation.yawPitchRoll()
                    _live.value = HeadLive(y, pi, r, p.angularVelocity, fusion.lastRawAccel, rate.rate, source.counts, p.still, p.gyroBias)
                } else {
                    _live.value = HeadLive(0f, 0f, 0f, Vec3.ZERO, Vec3.ZERO, rate.rate, source.counts, false, Vec3.ZERO)
                }
            }
        }
        return everStreamed
    }
}
