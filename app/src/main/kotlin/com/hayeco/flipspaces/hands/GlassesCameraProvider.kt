package com.hayeco.flipspaces.hands

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hayeco.flipspaces.core.hands.AirBox
import com.hayeco.flipspaces.core.hands.ByteArrayPool
import com.hayeco.flipspaces.core.hands.CameraDebugLine
import com.hayeco.flipspaces.core.hands.CameraLink
import com.hayeco.flipspaces.core.hands.DelegateChooser
import com.hayeco.flipspaces.core.hands.DetectionCycle
import com.hayeco.flipspaces.core.hands.DetectionView
import com.hayeco.flipspaces.core.hands.DetectionWindow
import com.hayeco.flipspaces.core.hands.FrameSample
import com.hayeco.flipspaces.core.hands.GlassesUsb
import com.hayeco.flipspaces.core.hands.HandCount
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.LatencyEstimator
import com.hayeco.flipspaces.core.hands.LatestFrameMailbox
import com.hayeco.flipspaces.core.hands.LinkState
import com.hayeco.flipspaces.core.hands.ProviderStatus
import com.hayeco.flipspaces.core.hands.TrackingStats
import com.hayeco.flipspaces.core.uvc.UvcFormatChooser
import com.hayeco.flipspaces.core.uvc.UvcVideoFrame
import com.hayeco.flipspaces.hands.uvc.FrameDecoder
import com.hayeco.flipspaces.hands.uvc.UsbLinkLostException
import com.hayeco.flipspaces.hands.uvc.UvcCameraSession
import com.hayeco.flipspaces.spatial.SpaceSession
import com.hayeco.flipspaces.pointer.PointerHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Provider (b): the glasses' own UVC camera, read over USB, into MediaPipe Hand Landmarker,
 * into [HandFeed] (core's hand recognizer) and the pointer. Runs inside [HandTrackingService].
 *
 * Three threads: the supervisor reads USB (UvcCameraSession) and reconnects when the glasses
 * drop off USB (core's CameraLink decides when); a detection thread takes only the newest frame
 * (LatestFrameMailbox), decodes it shrunk, turns it upright and cuts it square for the current
 * DetectionView (whole frame, zoomed, or the crop around a tracked hand, see core's
 * DetectionCycle) while MediaPipe still works on the previous frame, and hands it over as soon
 * as the previous result is in; MediaPipe's own thread turns results into pointer signals and
 * timing stats.
 */
object GlassesCameraProvider {
    private const val TAG = "FlipSpacesHands"
    private const val PREVIEW_EVERY_MS = 200L

    /**
     * 0.5.55: an unchanged "Camera" line every 30 s (it was every second). 1.1.15: it says whether the camera opened or
     * the same stream is still open (vc145 read as a restart every 30.5 s, see core's CameraLineGate).
     */
    private val cameraLine = com.hayeco.flipspaces.core.hands.CameraLineGate()

    /**
     * 1.1.15: "Hands on the NPU" as last set ([HandsPrefs.npuHands]); the detection thread applies a change between two
     * frames (core's NpuHandsSwitch). 1.1.14 read it only when hands started, so turning it off left the NPU running.
     */
    @Volatile
    var npuHandsWanted: Boolean = true

    /** Largest square bitmap handed to MediaPipe (core's DetectionWindow.maxOutputSide). */
    private const val MAX_INPUT_SIDE = 640

    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.Unavailable("Not checked yet"))
    val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    /** Formats and resolutions the camera offers, from its descriptors. */
    val report: StateFlow<String?> = _report.asStateFlow()

    private val _stats = MutableStateFlow<String?>(null)
    /** The live tracking line (fps, latency, confidence, drops) and the USB line, for the setup screen. */
    val stats: StateFlow<String?> = _stats.asStateFlow()

    private val _preview = MutableStateFlow<Preview?>(null)
    /** The latest camera frame (a few times a second) with the hand found in it, for the setup screen. */
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    data class Preview(val frame: Bitmap, val hand: HandFrame?)

    @Volatile
    private var box: AirBox? = null

    /** 0.5.0: the mirror switch is saved ([HandsPrefs]) and read back on first use. */
    var airBox: AirBox
        get() = box ?: AirBox(mirror = appContext?.let { HandsPrefs(it).mirror } ?: false).also { if (appContext != null) box = it }
        set(value) {
            box = value
            appContext?.let { HandsPrefs(it).mirror = value.mirror }
        }

    /** Set by [refresh] and [start], for [airBox]'s saved mirror. */
    @Volatile
    private var appContext: Context? = null

    /**
     * Degrees clockwise that turn the glasses camera's frames upright before MediaPipe sees
     * them. A UVC camera reports no sensor orientation, and the Aura's landscape MJPEG frames
     * are taken as upright, so it is 0 unless a device check shows them sideways.
     */
    @Volatile
    var rotationDegrees = 0
        set(value) {
            field = DetectionWindow.normalise(value)
        }

    /** 0.5.7: "Track two hands" as last set; the detection thread applies a change between frames. */
    // 0.5.37: on from the start (two hands and two hand resize always on).
    @Volatile
    var twoHands: Boolean = true

    /**
     * 0.5.4: no moving crop while a hand is tracked (core's DetectionCycle.steady). 0.5.14: every
     * camera frame, always the whole view: no search cycle, no idle skipping (DetectionCycle.everyFrame).
     * 0.5.20: DetectionCycle.glassesCamera, the whole view, every frame
     * while a hand is up, 15 a second after 2 s without one, and none while the glasses are off a head.
     */
    private val cycle = DetectionCycle.glassesCamera()

    /** 0.5.20: the not worn reason last logged, so the hand model's pause is said once each way. */
    @Volatile private var loggedNotWorn: String? = null

    /** 0.5.18: when the thermal status was last read for the hand model, and what it was. */
    @Volatile private var heatCheckedMs = 0L
    @Volatile private var heatStatus = 0

    @Volatile
    private var session: UvcCameraSession? = null

    @Volatile
    private var stopRequested = false
    private var thread: Thread? = null
    private var lastFailure: String? = null

    private enum class UsbEvent { ATTACHED, DETACHED, PERMISSION, STOP }

    private val events = LinkedBlockingQueue<UsbEvent>()
    private val pool = ByteArrayPool(maxKept = 4)
    private val tracking = TrackingStats(window = 30)
    private val latency = LatencyEstimator()

    /**
     * 0.5.2: GPU or CPU, whichever MediaPipe runs faster on (Mike's 0.5.1 GPU: 34 to 77 ms a frame).
     * 0.5.30 (Mike: hand tracking choppy and sluggish; vc49 tracked 4 to 5 fps on the CPU): the GPU by default. The
     * Space-first CPU trial is off, so the CPU is kept only when it is really faster; the Space's GPU guard
     * ([com.hayeco.flipspaces.core.spatial.SpaceGpuGuard]) alone moves the hand model off, on real Space frame misses.
     */
    private val delegates = DelegateChooser(spaceFirst = false)

    /** The delegate the chooser asked for, applied by the detection thread while it holds the slot. */
    @Volatile
    private var switchTo: Boolean? = null

    /** 0.5.29: the hand model's way back to the GPU after the Space's guard moved it. */
    private val gpuReturn = com.hayeco.flipspaces.core.spatial.HandGpuReturn.v0532()

    @Volatile
    private var trackingLine: String? = null

    /** 0.5.14: the runner of the session now, for its in-flight numbers on the tracking line. */
    @Volatile
    private var activeRunner: HandLandmarkerRunner? = null

    @Volatile
    private var usbLine: String? = null

    @Volatile
    private var previewFrame: PreviewFrame? = null

    private class PreviewFrame(val bitmap: Bitmap, val capturedAtMs: Long)

    /** A frame waiting for detection: still compressed, or already decoded (H.264 and HEVC). */
    private class Captured(val frame: UvcVideoFrame?, val decoded: Bitmap?, val capturedAtMs: Long, val decodeMs: Long) {
        fun recycle() {
            frame?.let { pool.recycle(it.data) }
        }
    }

    val running: Boolean get() = thread?.isAlive == true

    /** Works out what is missing before the camera can run. */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        if (running) return
        val device = GlassesUsbAccess.find(context)
        _status.value = when {
            device == null -> ProviderStatus.Unavailable(
                // 0.5.91: the One and One Pro have no camera; hand tracking needs glasses that have one, such as the Aura.
                "These glasses have no camera for hand tracking (the XREAL Aura has one). The trackpad and Spatial Mouse work as usual.",
            )
            context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                ProviderStatus.NeedsPermission("Camera permission (Android requires it before granting a USB camera)")
            !GlassesUsbAccess.hasPermission(context, device) -> ProviderStatus.NeedsPermission("USB access to ${GlassesUsbAccess.label(device)}")
            !HandModelStore.available(context) -> ProviderStatus.NeedsPermission("Download the MediaPipe hand model (about 7.5 MB, once)")
            lastFailure != null -> ProviderStatus.Failed(lastFailure!!)
            else -> ProviderStatus.Ready
        }
    }

    /** Opens the camera only to read its descriptors, then closes it. Blocking. */
    fun readFormats(context: Context): String {
        val device = GlassesUsbAccess.find(context) ?: return "No USB camera connected.".also { _report.value = it }
        val header = "${GlassesUsbAccess.label(device)}\n${GlassesUsbAccess.interfaces(device)}"
        val text = try {
            UvcCameraSession(GlassesUsbAccess.manager(context), device).use { s ->
                val camera = s.open()
                val formats = camera.streaming.flatMap { it.formats }
                val choice = UvcFormatChooser.choose(formats)
                "$header\n\n${camera.describe()}\n\nPocket XR would use: ${choice?.label ?: "nothing (no format it can decode)"}"
            }
        } catch (e: Exception) {
            "$header\n\nCould not read the camera: ${e.message}"
        }
        Log.i(TAG, text)
        _report.value = text
        return text
    }

    /** Starts the pipeline thread. Called by [HandTrackingService] once it is in the foreground. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        val app = context.applicationContext
        lastFailure = null
        stopRequested = false
        events.clear()
        _status.value = ProviderStatus.Running("Starting the glasses camera")
        thread = Thread({ supervise(app) }, "FlipSpacesHands").also { it.start() }
    }

    fun stop() {
        stopRequested = true
        events.offer(UsbEvent.STOP)
        session?.stop()
        thread?.join(2000)
        thread = null
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            // Only the glasses (or another camera) matter; a USB stick coming and going does not.
            if (device != null && !GlassesUsb.isXreal(device.vendorId) && !GlassesUsbAccess.hasVideo(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> events.offer(UsbEvent.ATTACHED)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    events.offer(UsbEvent.DETACHED)
                    // Wake the reader at once instead of waiting for its reads to fail.
                    session?.takeIf { device == null || it.deviceName == device.deviceName }?.stop()
                }
                GlassesUsbAccess.ACTION_PERMISSION -> events.offer(UsbEvent.PERMISSION)
            }
        }
    }

    private fun supervise(context: Context) {
        // 0.5.3: a normal priority (0.5.2 had display priority, ahead of SurfaceFlinger's clients).
        val usb = GlassesUsbAccess.manager(context)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(GlassesUsbAccess.ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // A fresh recognizer for the glasses display as it is now; the replay (debug) shares it.
        HandFeed.resize()
        HandFeed.recognizer(context)
        val link = CameraLink()
        var runner: HandLandmarkerRunner? = null
        tracking.reset()
        synchronized(latency) { latency.reset() }
        synchronized(cycle) { cycle.reset() }
        synchronized(delegates) { delegates.reset() }
        switchTo = null
        trackingLine = null
        usbLine = null
        cameraLine.reset()
        try {
            twoHands = HandsPrefs(context).twoHands
            val hands = HandCount.wanted(twoHands)
            // 0.5.43: the hand models on the NPU when this phone has one for them; MediaPipe then waits on the CPU behind it
            // (the NPU's checks and its fallback), so the GPU is the Space's.
            npuHandsWanted = HandsPrefs(context).npuHands
            val npu = if (npuHandsWanted) NpuHandEngine.create(context) else null.also { Log.i(TAG, "Hands on the NPU: off (Developer card)") }
            val mediaPipe = HandLandmarkerRunner(
                context,
                preferGpu = npu == null,
                numHands = hands,
                onResult = { onResult(context, it) },
                onError = { Log.w(TAG, "MediaPipe: $it") },
                npu = npu,
            ).also { runner = it; activeRunner = it }
            tracking.delegate = mediaPipe.delegate
            Log.i(TAG, "Hand Landmarker: ${CameraDebugLine.runsOn(mediaPipe.delegate)}, ${HandCount.line(hands)}")
            link.start()
            while (!stopRequested) {
                val now = SystemClock.uptimeMillis()
                when (val state = link.checkGiveUp(now)) {
                    // 0.5.0: unplugged for good is not a fault; hands start again with the glasses.
                    is LinkState.Failed -> return if (state.gaveUp) endQuietly(context, state.reason) else fail(context, state.reason)
                    LinkState.Stopped -> return
                    else -> Unit
                }
                if (!link.shouldConnect(now)) {
                    _status.value = ProviderStatus.Running(link.message)
                    when (events.poll(link.waitMs(now).coerceAtLeast(50), TimeUnit.MILLISECONDS)) {
                        UsbEvent.ATTACHED, UsbEvent.PERMISSION -> link.attached(SystemClock.uptimeMillis())
                        else -> Unit
                    }
                    continue
                }
                val device = GlassesUsbAccess.find(context)
                if (device == null) {
                    link.noDevice(now)
                    continue
                }
                if (!usb.hasPermission(device)) {
                    // A device that left USB and came back is a new device to Android, and access may be gone.
                    // 0.5.1: asked only once the plug-in prompt is out of the way (UsbPrompt), never on top of it.
                    UsbPrompt.askIfDue(context, device, "the hand camera")
                    link.needsPermission(now)
                    continue
                }
                link.connecting()
                _status.value = ProviderStatus.Running(link.message)
                try {
                    streamOnce(context, usb, device, link, mediaPipe)
                } catch (e: Exception) {
                    if (stopRequested) break
                    val at = SystemClock.uptimeMillis()
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "Glasses camera stopped", e)
                    when {
                        // Gone from the device list: the glasses left USB, whatever the read error said.
                        GlassesUsbAccess.find(context) == null -> link.detached(at)
                        e is UsbLinkLostException -> link.linkLost(reason, at)
                        else -> link.error(reason, at)
                    }
                    Log.i(TAG, "Camera link: ${link.message}")
                } finally {
                    HandFeed.reset()
                    synchronized(cycle) { cycle.reset() }
                }
            }
            _status.value = ProviderStatus.Ready
        } catch (e: Exception) {
            Log.w(TAG, "Glasses camera failed", e)
            fail(context, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { context.unregisterReceiver(usbReceiver) }
            PointerHub.handStatus(null, HandProviderKind.GLASSES_CAMERA)
            activeRunner = null
            runCatching { runner?.close() }
            session = null
        }
    }

    /** Opens the camera and streams until [stop], the glasses leave or the stream fails (throws). */
    private fun streamOnce(
        context: Context,
        usb: UsbManager,
        device: UsbDevice,
        link: CameraLink,
        runner: HandLandmarkerRunner,
    ) {
        val s = UvcCameraSession(usb, device)
        session = s
        if (stopRequested) return
        var decoder: FrameDecoder? = null
        var mailbox: LatestFrameMailbox<Captured>? = null
        var worker: Thread? = null
        try {
            val camera = s.open()
            _report.value = "${GlassesUsbAccess.label(device)}\n\n${camera.describe()}"
            val streaming = camera.streaming.firstOrNull { it.formats.isNotEmpty() }
                ?: throw IOException("The camera lists no video formats")
            val choice = UvcFormatChooser.choose(streaming.formats)
                ?: throw IOException("None of the camera's formats can be decoded: ${streaming.formats.joinToString { it.label }}")
            Log.i(TAG, "Streaming ${choice.label}")
            val frameDecoder = FrameDecoder.forChoice(choice).also { decoder = it }
            val box = LatestFrameMailbox<Captured> { stale ->
                stale.recycle()
                tracking.dropped()
            }.also { mailbox = it }
            val cameraId = "${device.deviceName} ${GlassesUsbAccess.label(device)} video interface ${streaming.interfaceNumber}"
            cameraLine.opened(SystemClock.uptimeMillis())
            worker = Thread({ detect(box, frameDecoder, runner, cameraId, context) }, "FlipSpacesDetect").also { it.start() }
            var first = true
            var brokenSeen = 0L
            s.stream(
                streaming,
                choice,
                allocate = pool::obtain,
                onFrame = { frame, at ->
                    tracking.captured(at)
                    if (first) {
                        first = false
                        val wasLost = link.reconnects
                        link.streaming(at)
                        // 0.5.19: how long a reconnect took, for the retry delay check.
                        if (link.reconnects != wasLost) Log.i(TAG, "Camera link back after ${link.lastReconnectMs ?: -1} ms (reconnect ${link.reconnects}, first retry after ${link.backoffMs.first()} ms)")
                        HandFeed.reset()
                        _status.value = ProviderStatus.Running("${choice.label}, ${CameraDebugLine.runsOn(runner.delegate)}")
                    }
                    if (frameDecoder.needsEveryFrame) {
                        // H.264 and HEVC frames depend on the ones before: decode all of them here, skip only decoded ones.
                        val t0 = SystemClock.uptimeMillis()
                        val bitmap = try {
                            frameDecoder.decode(frame)
                        } finally {
                            pool.recycle(frame.data)
                        }
                        if (bitmap != null) box.offer(Captured(null, bitmap, at, SystemClock.uptimeMillis() - t0))
                    } else {
                        box.offer(Captured(frame, null, at, 0))
                    }
                },
                onStats = { st ->
                    tracking.dropped(st.dropped - brokenSeen)
                    brokenSeen = st.dropped
                    val agreed = s.agreed
                    usbLine = "USB: ${choice.label}, ${st.frames} frames, ${st.dropped} broken, ${st.badHeaders} bad headers, " +
                        "${st.bytes / 1024} KiB in ${st.reads} reads, ${st.waits} empty waits, ${st.errors} errors, " +
                        "${box.dropped} stale skipped, ${link.reconnects} reconnects" +
                        (agreed?.let { "; camera agreed to format ${it.formatIndex} frame ${it.frameIndex}, ${"%.0f".format(it.fps)} fps, payload ${it.maxPayloadTransferSize} B" } ?: "")
                    publishStats()
                },
            )
            if (!stopRequested) throw UsbLinkLostException("The camera stream ended")
        } finally {
            mailbox?.close()
            worker?.join(1000)
            runCatching { decoder?.close() }
            runCatching { s.close() }
            session = null
        }
    }

    /**
     * The detection thread: newest frame, decode, upright square cut, MediaPipe, one at a time.
     * The next frame is decoded and cut while MediaPipe still works on the previous one, so a
     * frame costs the longer of the two instead of their sum (0.3.1 ran them one after the other
     * and reached 22 to 23 fps of 30 with 36 ms inference).
     */
    private fun detect(box: LatestFrameMailbox<Captured>, decoder: FrameDecoder, runner: HandLandmarkerRunner, cameraId: String, context: Context) {
        // 0.5.3: a normal priority again. 0.5.2 ran decode and MediaPipe at display priority,
        // ahead of the threads that hand SurfaceFlinger its buffers.
        val square = ViewCanvas()
        var lastPreview = 0L
        var pausedForStall = false
        var pausedForGame = false
        // 0.5.24: the palm search is on the runner's CPU landmarker now (no hand up, the Space drawing).
        var searchingOnCpu = false
        Log.i(TAG, "MediaPipe thresholds: ${runner.thresholds.describe()}; views ${cycle.views.joinToString { it.label }}, hand crop ${cycle.roi != null}, input up to $MAX_INPUT_SIDE px, ${runner.numHands} hand${if (runner.numHands == 1) "" else "s"}")
        while (!box.isClosed) {
            if (runner.needsCpuFallback) {
                runner.fallBackToCpu()
                tracking.delegate = runner.delegate
            }
            val item = box.take(200) ?: continue
            // 0.5.3: the Space's watchdog saw its frames stop: no GPU work from here until they
            // run again, and no GPU or CPU trial (with its graph rebuild) this session.
            if (SpaceSession.stalled) {
                if (!pausedForStall) {
                    pausedForStall = true
                    switchTo = null
                    synchronized(delegates) {
                        delegates.abandon("the Space stalled")
                        delegates.line?.let { Log.i(TAG, it) }
                    }
                    Log.w(TAG, "Hand tracking paused: the Space's frames stalled")
                }
                item.recycle()
                tracking.dropped()
                continue
            }
            if (pausedForStall) {
                pausedForStall = false
                Log.i(TAG, "Hand tracking resumed: the Space draws again")
            }
            // A game has the glasses: no hand is drawn or pressed there, so the model rests and the heat goes to the game.
            // PC VR is the exception: its hands go to SteamVR (8 November 2026: pinching did nothing there).
            // PC VR with both Joy-Cons in hand needs no hands either: about 0.6 W (9 November 2026, measured as the
            // phone's charge current with the hand model on and off). Setting one down wakes it.
            if (SpaceSession.gameOwnsGlasses && (!com.hayeco.flipspaces.pcvr.PcVrSession.isOpen || com.hayeco.flipspaces.openxr.OpenXrPads.bothHeld())) {
                if (!pausedForGame) {
                    pausedForGame = true
                    Log.i(TAG, "Hand model paused: a game has the glasses")
                }
                item.recycle()
                tracking.dropped()
                continue
            }
            if (pausedForGame) {
                pausedForGame = false
                HandFeed.reset()
                Log.i(TAG, "Hand model running again: the game closed")
            }
            // 0.5.18: from severe heat an empty picture is searched 10 times a second.
            val nowHeat = SystemClock.uptimeMillis()
            if (nowHeat - heatCheckedMs >= 1_000L) {
                heatCheckedMs = nowHeat
                val status = appContext?.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0
                val hot = com.hayeco.flipspaces.core.spatial.ThermalGuard.handsHot(status)
                // 0.5.31: the idle search's rate follows the heat, 4 a second severe, 2 critical.
                val interval = com.hayeco.flipspaces.core.spatial.ThermalGuard.idleSearchIntervalMs(status)
                val (was, wasInterval) = synchronized(cycle) {
                    (cycle.hot to cycle.hotIntervalMs).also {
                        cycle.hot = hot
                        if (interval != null) cycle.hotIntervalMs = interval
                    }
                }
                if (hot != was || (hot && interval != wasInterval)) {
                    Log.i(TAG, "thermal status ${com.hayeco.flipspaces.core.spatial.ThermalGuard.label(status)}: " + if (hot) "${com.hayeco.flipspaces.core.spatial.ThermalGuard.idleSearchLine(status)} (a hand in view is still tracked at the camera's rate)" else "the hand model searches every camera frame again")
                }
                heatStatus = status
            }
            // 0.5.20: off a head no press counts, so the hand model rests.
            val notWorn = com.hayeco.flipspaces.core.hands.PhoneHandling.shared.notWorn(SystemClock.uptimeMillis())
            if ((notWorn == null) != (loggedNotWorn == null)) {
                Log.i(TAG, if (notWorn != null) "Hand model paused: the glasses are not worn ($notWorn); the GPU is the Space's" else "Hand model running again: the glasses are worn (${synchronized(cycle) { cycle.notWornSkipped }} camera frames left unsent while they were off)")
            }
            loggedNotWorn = notWorn
            if (!synchronized(cycle) { cycle.notWorn = notWorn; cycle.shouldSend(item.capturedAtMs) }) {
                // No hand for a while: fewer palm detector runs, more GPU for the Space.
                item.recycle()
                tracking.dropped()
                continue
            }
            // 0.5.55: the prepared frame in three locals, not a Triple (and a boxed Long) a frame.
            var preparedImage: Bitmap? = null
            var preparedWindow: DetectionWindow? = null
            var preparedDecodeMs = 0L
            try {
                val t0 = SystemClock.uptimeMillis()
                val bitmap = item.decoded ?: try {
                    decoder.decode(item.frame!!)
                } finally {
                    item.recycle()
                }
                val decodeMs = item.decodeMs + (SystemClock.uptimeMillis() - t0)
                if (bitmap == null) continue
                val now = SystemClock.uptimeMillis()
                val rotation = rotationDegrees
                // 0.5.55: the preview's half size copy only while the setup screen shows it (it was made five times a
                // second all session, about 0.5 MB each, for nobody).
                if (now - lastPreview >= PREVIEW_EVERY_MS && _preview.subscriptionCount.value > 0) {
                    lastPreview = now
                    // Upright, like the landmarks the preview draws over it.
                    val m = Matrix().apply {
                        postScale(0.5f, 0.5f)
                        postRotate(rotation.toFloat())
                    }
                    previewFrame = PreviewFrame(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true), item.capturedAtMs)
                }
                val view = synchronized(cycle) { cycle.current }
                val window = square.place(bitmap, rotation, view)
                // 0.5.55: the Camera line only when what it says changes, or every 30 s. 1.1.15: led by "opened",
                // "view changed" or "still open" (core's CameraLineGate).
                val delegate = runner.delegate
                cameraLine.lead(now, window, delegate)?.let { lead -> Log.i(TAG, CameraDebugLine.line(cameraId, null, window, delegate, lead)) }
                preparedImage = square.image
                preparedWindow = window
                preparedDecodeMs = decodeMs
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped", e)
                continue
            }
            // 1.1.15: "Hands on the NPU" switched while hands run, applied between two frames; the frames keep flowing
            // (MediaPipe is built behind the NPU, and the NPU's models are built on their own thread).
            when (runner.applyNpu(npuHandsWanted) { NpuHandEngine.create(context) }) {
                com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.DROP -> {
                    tracking.delegate = runner.delegate
                    synchronized(delegates) { delegates.reset() }
                    // As at a start without the NPU: MediaPipe on the GPU, unless the Space's guard has it on the CPU.
                    switchTo = if (SpaceSession.handsOffGpu) null else true
                }
                com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.MEDIAPIPE_TO_CPU -> if (switchTo == null) switchTo = false
                com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.INSTALL -> {
                    tracking.delegate = runner.delegate
                    switchTo = null
                }
                else -> Unit
            }
            // 0.5.2: the delegate, and 0.5.7 the hand count, change only with nothing in flight
            // (0.5.14: up to two frames are, so this waits for both answers first).
            // 1.1.13: a delegate move builds the new landmarkers on their own thread while these keep tracking, and swaps
            // them in between two frames (vc51: 0.4 to 1.2 s without hand frames at each move when built here).
            switchTo?.let { gpu ->
                val done = runCatching { runner.moveTo(gpu) }.onFailure { Log.w(TAG, "MediaPipe delegate change failed", it) }.getOrDefault(true)
                if (done) {
                    switchTo = null
                    tracking.delegate = runner.delegate
                }
            }
            val handsChange = HandCount.change(runner.numHands, twoHands)
            if (handsChange != null) {
                if (runner.awaitIdle(1_000)) {
                    try {
                        // 0.5.7: "Track two hands" switched while hands run: MediaPipe rebuilt between frames.
                        handsChange?.let { n ->
                            runCatching { runner.useHands(n) }
                                .onSuccess { Log.i(TAG, "Track two hands turned ${if (n == 2) "on" else "off"}: MediaPipe now follows ${HandCount.line(n)}, without restarting hands") }
                                .onFailure { Log.w(TAG, "MediaPipe could not switch to $n hands", it) }
                        }
                    } finally {
                        runner.releaseAll()
                    }
                }
            }
            // 0.5.24: with no hand up, the palm search runs on a CPU landmarker while the Space draws,
            // so the GPU is the Space's; built once a steady Space is up. A change between the two waits for nothing in flight.
            // 0.5.26 ([com.hayeco.flipspaces.core.spatial.SpaceGpuGuard]): the Space missed its deadlines behind the hand
            // model's GPU work, so the hand model goes to the CPU for this camera session (one rebuild, between frames).
            // 0.5.31: from severe heat with the Space drawing, the hand model goes to the
            // CPU too, and stays there until the phone cools below severe.
            val heatOff = com.hayeco.flipspaces.core.spatial.HandGpuReturn.heatHolds(heatStatus, SpaceSession.active)
            if ((SpaceSession.handsOffGpu || heatOff) && runner.delegate == "GPU" && switchTo == null) {
                val forHeat = !SpaceSession.handsOffGpu
                val heatLabel = com.hayeco.flipspaces.core.spatial.ThermalGuard.label(heatStatus)
                synchronized(delegates) { delegates.abandon(if (forHeat) "the phone is $heatLabel hot while the Space draws" else "the Space missed its deadlines waiting for the GPU behind the hand model") }
                switchTo = false
                SpaceSession.handsOffGpu = true
                if (forHeat) gpuReturn.movedOffForHeat(item.capturedAtMs) else gpuReturn.movedOff(item.capturedAtMs)
                Log.i(TAG, if (forHeat) "Hand model to the CPU: thermal status $heatLabel, so the Space keeps the GPU until the phone cools below severe" else "Hand model to the CPU: the Space's frames come first on the GPU")
            }
            // 0.5.29 (4 to 5 fps on the CPU all session): with a hand up, back to the GPU
            // after a hold, so it is tracked at 30 fps; the Space's guard moves it off again if the Space really waits behind it.
            // 0.5.31: not while the phone is severe or hotter with the Space drawing.
            val handUp = synchronized(cycle) { cycle.hasHand }
            // 1.1.15: not while the NPU is wanted (MediaPipe waits on the CPU behind it).
            if (SpaceSession.handsOffGpu && runner.delegate == "CPU" && switchTo == null && !npuHandsWanted && gpuReturn.shouldReturn(item.capturedAtMs, true, handUp, heatStatus, SpaceSession.active)) {
                SpaceSession.handsOffGpu = false
                switchTo = true
                Log.i(TAG, gpuReturn.line())
            }
            SpaceSession.handModelOnGpu = runner.delegate == "GPU" && !searchingOnCpu
            val idleNow = synchronized(cycle) { cycle.idle(item.capturedAtMs) }
            if (idleNow && runner.delegate == "GPU" && SpaceSession.active && SpaceSession.steady && !SpaceSession.stalled && !runner.idleCpuTried) {
                if (runner.awaitIdle(1_000)) {
                    try {
                        runner.prepareIdleCpu()
                    } finally {
                        runner.releaseAll()
                    }
                }
            }
            val onCpu = DetectionCycle.IdleSearch.searchOnCpu(runner.delegate == "GPU", idleNow, SpaceSession.active, runner.idleCpuReady)
            if (onCpu != searchingOnCpu) {
                if (!runner.awaitIdle(1_000)) continue
                runner.releaseAll()
                searchingOnCpu = onCpu
                Log.i(TAG, if (onCpu) "No hand for 2 s: the palm search runs on the CPU, so the GPU is the Space's" else "Hand model back on the GPU (${if (idleNow) "the Space stopped drawing" else "a hand is up"})")
            }
            // Wait for MediaPipe only now: the frame above was prepared while it worked.
            var ready = false
            while (!box.isClosed && !ready) ready = runner.awaitReady(200)
            if (!ready) break
            var handedOver = false
            try {
                synchronized(cycle) { cycle.sent(item.capturedAtMs) }
                handedOver = true
                runner.submit(preparedImage!!, preparedWindow!!, item.capturedAtMs, preparedDecodeMs, idleOnCpu = searchingOnCpu)
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped", e)
            } finally {
                if (!handedOver) runner.release()
            }
        }
    }

    private fun onResult(context: Context, r: HandLandmarkerRunner.Result) {
        val hand = r.hand
        val (ahead, measured) = synchronized(latency) {
            latency.add(r.doneAtMs - r.capturedAtMs)
            latency.valueMs to latency.measuredMs
        }
        // 0.5.14: capture to landmark of frames with a hand, for the hand numbers line.
        if (hand != null) synchronized(HandFeed.metrics) { HandFeed.metrics.latency(r.doneAtMs - r.capturedAtMs) }
        // Capture time, not result time: smoothing and prediction need the hand's real timing.
        HandFeed.deliver(context, r.hands, r.capturedAtMs, ahead)
        r.window?.let { w ->
            synchronized(cycle) {
                val was = cycle.current
                if (cycle.record(w.view, hand, w.uprightWidth, w.uprightHeight, r.doneAtMs)) {
                    Log.i(TAG, "Detection view ${was.label} -> ${cycle.current.label} (zoom ${"%.1f".format(cycle.current.zoom)}), hand ${if (cycle.hasHand) "tracked" else "searching"} (${cycle.searchLabel})")
                }
            }
        }
        // 0.5.24: the idle search's CPU frames are not the runner's delegate, so they do not feed the delegate choice.
        // 1.1.15: nor the NPU's frames (1.1.14 timed them as MediaPipe on the CPU), nor any while the NPU is wanted.
        if (!r.idleSearch && !r.ranOnNpu && !npuHandsWanted) synchronized(delegates) {
            val was = delegates.line
            // 0.5.3: the trial waits for a steady Space (no graph rebuild at plug-in).
            val trial = SpaceSession.steady && !SpaceSession.stalled
            // 0.5.16: while the Space draws, its frames get the GPU first.
            delegates.record(r.delegateWasGpu, r.doneAtMs - r.submittedAtMs, trialAllowed = trial, spaceDraws = SpaceSession.active)?.let { switchTo = it }
            delegates.line?.takeIf { it != was }?.let { Log.i(TAG, it) }
        }
        val sample = FrameSample(
            captureToLandmarkMs = r.doneAtMs - r.capturedAtMs,
            decodeMs = r.decodeMs,
            inferenceMs = r.doneAtMs - r.submittedAtMs,
            handPresent = hand != null,
            score = hand?.score ?: 0f,
            // 0.5.25: what ran this inference, so the Tracking line says CPU during the idle search.
            ranOn = when {
                r.ranOnNpu -> com.hayeco.flipspaces.core.hands.TrackingStats.NPU
                r.idleSearch -> com.hayeco.flipspaces.core.hands.TrackingStats.CPU_SEARCH
                r.delegateWasGpu -> com.hayeco.flipspaces.core.hands.TrackingStats.GPU
                else -> com.hayeco.flipspaces.core.hands.TrackingStats.CPU
            },
        )
        tracking.record(sample, r.doneAtMs)?.let { summary ->
            trackingLine = summary.line()
            val view = synchronized(cycle) { cycle.current.label }
            Log.i(
                TAG,
                "Tracking: ${summary.line()}, view $view, predict ${ahead?.let { "%.0f ms".format(it) } ?: "default"}" +
                    (measured?.takeIf { ahead != null && it > ahead + 0.5f }?.let { " (latency %.0f ms, capped at %.0f)".format(it, com.hayeco.flipspaces.core.hands.LatencyEstimator.MAX_PREDICT_MS) } ?: "") +
                    "; ${activeRunner?.inFlightLine ?: ""}" +
                    // 0.5.18: the hand model's work against the heat.
                    "; thermal status ${com.hayeco.flipspaces.core.spatial.ThermalGuard.label(heatStatus)}, ${synchronized(cycle) { cycle.hotSkipped }} frames left unsent while hot with no hand" +
                    // 0.5.55: the NPU's palm detector, runs a second since the last line and its rate now (last, so the
                    // log scripts' fields before it read as they did).
                    (activeRunner?.palmSearchLine(r.doneAtMs)?.let { "; $it" } ?: ""),
            )
            publishStats()
        }
        val p = previewFrame
        if (p != null && p.capturedAtMs == r.capturedAtMs) {
            previewFrame = null
            _preview.value = Preview(p.bitmap, hand)
        }
    }

    private fun publishStats() {
        _stats.value = listOfNotNull(trackingLine?.let { "Live: $it" }, usbLine).joinToString("\n").ifEmpty { null }
    }

    fun markFailed(message: String) {
        lastFailure = message
        _status.value = ProviderStatus.Failed(message)
    }

    /** Ends the service without a failure, so the setup screen and auto start see hands as ready. */
    private fun endQuietly(context: Context, message: String) {
        Log.i(TAG, message)
        lastFailure = null
        _status.value = ProviderStatus.Ready
        HandTrackingService.stop(context)
    }

    private fun fail(context: Context, message: String) {
        lastFailure = message
        _status.value = ProviderStatus.Failed(message)
        HandTrackingService.stop(context)
    }

    /**
     * Draws each decoded frame, turned upright and cut square for a [DetectionView] (see core's
     * DetectionWindow), into a square bitmap for MediaPipe. Two bitmaps per square size take
     * turns, reused for every frame; areas outside the frame stay black.
     */
    private class ViewCanvas {
        private class Buffers(val bitmaps: List<Bitmap>, val canvases: List<Canvas>)

        private val bySide = HashMap<Int, Buffers>()
        private var lastSide = -1
        private var lastBuffers: Buffers? = null
        private var window: DetectionWindow? = null
        private var next = 0
        private val matrix = Matrix()
        private val values = FloatArray(9)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** 0.5.55: the square [place] made last (instead of a Pair a frame). */
        lateinit var image: Bitmap
            private set

        /** Cuts [src] for [view] into [image]; returns the cut's window (the same object while nothing changes). */
        fun place(src: Bitmap, rotation: Int, view: DetectionView): DetectionWindow {
            image = placeImage(src, rotation, view)
            return window!!
        }

        private fun placeImage(src: Bitmap, rotation: Int, view: DetectionView): Bitmap {
            var w = window
            if (w == null || w.width != src.width || w.height != src.height || w.rotation != rotation || w.view != view) {
                w = DetectionWindow(src.width, src.height, rotation, view, MAX_INPUT_SIDE)
                window = w
            }
            // The whole frame, already square, upright and small enough, goes as it is.
            if (rotation == 0 && w.outputSide == w.side && w.side == src.width && w.side == src.height && w.left == 0f && w.top == 0f) return src
            // 0.5.55: the last size's buffers straight away (the map's Int key was boxed twice a frame).
            val set = lastBuffers?.takeIf { lastSide == w.outputSide } ?: run {
                // The hand crop changes size as the hand does: keep only a few sizes of buffers.
                if (bySide.size > 6 && w.outputSide !in bySide) bySide.clear()
                bySide.getOrPut(w.outputSide) {
                    val bitmaps = List(2) { Bitmap.createBitmap(w.outputSide, w.outputSide, Bitmap.Config.ARGB_8888) }
                    Buffers(bitmaps, bitmaps.map { Canvas(it) })
                }.also {
                    lastSide = w.outputSide
                    lastBuffers = it
                }
            }
            val i = next
            next = 1 - next
            val m = w.sourceToSquare
            values[0] = m[0]; values[1] = m[1]; values[2] = m[2]
            values[3] = m[3]; values[4] = m[4]; values[5] = m[5]
            values[6] = 0f; values[7] = 0f; values[8] = 1f
            matrix.setValues(values)
            val canvas = set.canvases[i]
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(src, matrix, paint)
            return set.bitmaps[i]
        }
    }
}
