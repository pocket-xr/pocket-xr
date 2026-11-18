package com.hayeco.flipspaces.hands

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.Landmark
import com.hayeco.flipspaces.core.hands.DetectionWindow
import com.hayeco.flipspaces.core.hands.HandThresholds
import com.hayeco.flipspaces.core.hands.PalmIdleRate
import com.hayeco.flipspaces.core.hands.PalmSearch
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * MediaPipe Hand Landmarker (com.google.mediapipe:tasks-vision) in live stream mode: frames go
 * in with detectAsync, results come back on MediaPipe's thread. [numHands] hands are tracked
 * (0.5.4: one by default, see [HandsPrefs.twoHands]).
 *
 * In live stream mode MediaPipe runs its palm detector only when it has no hand to follow: once
 * a hand is found, the next frame's hand region comes from this frame's landmarks, and the palm
 * detector runs again only when the tracked hand's presence or tracking confidence drops below
 * the thresholds set here, or while fewer than [numHands] hands are tracked: with two, one hand
 * in view pays for the palm detector on every frame (Mike's logs: about 30 ms a frame with no
 * hand, the palm detector alone, and 45 to 62 ms with one hand, both models). That needs frames close together in time with increasing capture
 * timestamps, which is why the pipeline feeds the newest frame as soon as the previous result
 * is in.
 *
 * 0.5.8, two hands: two landmarkers, one following one hand and one following two, and
 * [PalmSearch] picks which gets each frame. While fewer than two hands are tracked the one hand
 * landmarker gets the frames (its palm detector off while it tracks) and the two hand one gets
 * one frame in four to look for the second hand; once it sees two it gets every frame. So the
 * palm detector no longer runs on every frame with one hand in view (Mike's 0.5.6 log: 15 fps,
 * about 30 dropped a second).
 *
 * The GPU delegate is tried first; if MediaPipe cannot build it, or it fails on its first
 * frames, the runner falls back to the CPU (XNNPACK). 0.5.14: up to [MAX_IN_FLIGHT] images are
 * in flight ([awaitReady] takes a slot, [submit] sends; a result frees it); a graph rebuild waits
 * for none ([awaitIdle]).
 */
class HandLandmarkerRunner(
    private val context: Context,
    preferGpu: Boolean,
    val thresholds: HandThresholds = HandThresholds(),
    numHands: Int = 1,
    private val onResult: (Result) -> Unit,
    private val onError: (String) -> Unit,
    /**
     * 0.5.43: the hand models on the Exynos 2500's NPU ([NpuHandEngine]); while it runs, frames go to it, not MediaPipe,
     * and [delegate] reads "NPU". Null, or a failure of it, is MediaPipe as before.
     */
    npu: NpuHandEngine? = null,
) : Closeable {
    @Volatile private var npu: NpuHandEngine? = npu
    val npuActive: Boolean get() = npu != null
    private val npuThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "FlipSpacesNpuHands") }
    private val npuPendingCount = java.util.concurrent.atomic.AtomicInteger()
    private val npuPending: Int get() = npuPendingCount.get()

    /** 0.5.43: frames also sent to MediaPipe to check the NPU's hands against (timestamp to the NPU's hands). */
    private val compare = java.util.concurrent.ConcurrentHashMap<Long, List<HandFrame>>()
    private var npuHandFrames = 0L
    private var compareSum = 0.0
    private var compareCount = 0
    private var compareMissed = 0

    /**
     * One detection: the hand in the upright frame's coordinates, and its timings. [window] is
     * the cut the frame was sent with (null if MediaPipe's result could not be matched to it).
     */
    class Result(
        /** Up to two hands, MediaPipe's order; empty when none was found. */
        val hands: List<HandFrame>,
        val capturedAtMs: Long,
        val submittedAtMs: Long,
        val decodeMs: Long,
        val doneAtMs: Long,
        val window: DetectionWindow?,
        /** 0.5.2: the frame ran on the GPU delegate (for [com.hayeco.flipspaces.core.hands.DelegateChooser]). */
        val delegateWasGpu: Boolean = false,
        /** 0.5.24: the frame went to the idle search's CPU landmarker ([prepareIdleCpu]). */
        val idleSearch: Boolean = false,
        /** 1.1.15: the NPU's hand models ran the frame (1.1.14 counted it as a CPU frame). */
        val ranOnNpu: Boolean = false,
    ) {
        /** The first hand, for the detection crop and the preview. */
        val hand: HandFrame? get() = hands.firstOrNull()
    }

    private class InFlight(val capturedAtMs: Long, val submittedAtMs: Long, val decodeMs: Long, val window: DetectionWindow, val gpu: Boolean, val use: PalmSearch.Use, val idle: Boolean = false)

    /**
     * 0.5.24: a one hand landmarker on the CPU for the palm search while no hand is up
     * ([com.hayeco.flipspaces.core.hands.DetectionCycle.IdleSearch]), built once a session ([prepareIdleCpu]), never
     * rebuilt; null until then, or when MediaPipe could not build it (the search then stays on the GPU).
     */
    @Volatile private var idleCpu: HandLandmarker? = null
    @Volatile var idleCpuTried = false
        private set
    val idleCpuReady: Boolean get() = idleCpu != null

    /** Builds the idle search's CPU landmarker, once. Call from the feeding thread while it holds every slot. */
    fun prepareIdleCpu(): Boolean {
        if (idleCpuTried) return idleCpu != null
        idleCpuTried = true
        val t0 = SystemClock.uptimeMillis()
        idleCpu = try {
            create(Delegate.CPU, 1)
        } catch (e: Exception) {
            Log.w(TAG, "MediaPipe CPU landmarker for the idle search unavailable; the search stays on the GPU", e)
            null
        }
        if (idleCpu != null) Log.i(TAG, "MediaPipe CPU landmarker for the idle palm search built in ${SystemClock.uptimeMillis() - t0} ms (once this session; the GPU one is kept for a hand)")
        return idleCpu != null
    }

    /** Hands MediaPipe follows; 0.5.7: changed while running with [useHands]. */
    @Volatile
    var numHands: Int = numHands
        private set

    /**
     * 0.5.14: up to two frames in flight ([InFlightTable]), so the next
     * frame's upload and conversion overlap the GPU's work on the last; 0.5.13 had one, and its
     * 40 ms a frame turned the camera's 30 fps into 23. If MediaPipe drops frames sent that way,
     * the table goes back to one.
     */
    private val table = com.hayeco.flipspaces.core.hands.InFlightTable<InFlight>(capacity = MAX_IN_FLIGHT)
    private val slot = Semaphore(MAX_IN_FLIGHT)
    private var lastTimestamp = 0L

    /** 0.5.14: frames MediaPipe is working on now, and the table's fallback note (logged once). */
    val inFlightNow: Int get() = table.size()
    val inFlightLimit: Int get() = table.capacity
    val inFlightLine: String get() = "MediaPipe frames in flight: up to ${table.capacity}, ${table.sent} sent, ${table.answered} answered, ${table.dropped} dropped" + (table.fallback?.let { " ($it)" } ?: "")
    @Volatile private var fallbackLogged = false

    @Volatile
    private var resultsSeen = 0L

    @Volatile
    private var gpuErrors = 0

    /** "GPU" or "CPU", for the stats line; 0.5.43: "NPU" while [npuActive]. */
    @Volatile
    var delegate: String = "CPU"
        get() = if (npu != null) "NPU" else field
        private set

    /** 1.1.13: the landmarkers built for one delegate and hand count: the one hand follower and the two hand searcher. */
    private class Landmarkers(val follower: HandLandmarker, val searcher: HandLandmarker?, val delegate: String) {
        fun close() {
            runCatching { follower.close() }
            runCatching { searcher?.close() }
        }
    }

    /** 1.1.13: a delegate move's new landmarkers, built on [buildThread] while these keep tracking ([moveTo]). */
    private val swap = com.hayeco.flipspaces.core.hands.DelegateSwap<Landmarkers> { it.close() }
    private val buildThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "FlipSpacesHandModelBuild") }

    @Volatile private var landmarker: HandLandmarker

    /** 0.5.8: the two hand landmarker, in two hand mode only (see [PalmSearch]). */
    @Volatile private var searcher: HandLandmarker?
    private val palmSearch = PalmSearch()

    init {
        val built = buildSet(preferGpu, numHands)
        landmarker = built.follower
        searcher = built.searcher
        delegate = built.delegate
    }

    /** 0.5.8: the two hand landmarker on [delegate]; null (one hand only) if MediaPipe cannot build it. */
    private fun makeSearcher(delegate: String): HandLandmarker? = try {
        create(if (delegate == "GPU") Delegate.GPU else Delegate.CPU, 2)
    } catch (e: Exception) {
        Log.w(TAG, "MediaPipe two hand landmarker unavailable, following one hand", e)
        null
    }

    /** True when the GPU delegate failed on its first frames; call [fallBackToCpu] from the feeding thread. */
    val needsCpuFallback: Boolean get() = delegate == "GPU" && resultsSeen == 0L && gpuErrors >= GPU_ERRORS_BEFORE_FALLBACK

    /** The one hand follower on [gpu], or on the CPU when MediaPipe cannot build the GPU one, and the delegate it got. */
    private fun buildFollower(gpu: Boolean): Pair<HandLandmarker, String> {
        if (gpu) {
            try {
                return create(Delegate.GPU, 1) to "GPU"
            } catch (e: Exception) {
                Log.w(TAG, "MediaPipe GPU delegate unavailable, using CPU", e)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "MediaPipe GPU delegate unavailable, using CPU", e)
            }
        }
        return create(Delegate.CPU, 1) to "CPU"
    }

    /** 0.5.8: the landmarkers for [hands] on [gpu]. 1.1.13: touches nothing in use, so it can run on [buildThread]. */
    private fun buildSet(gpu: Boolean, hands: Int): Landmarkers {
        val (follower, name) = buildFollower(gpu)
        return Landmarkers(follower, if (hands >= 2) makeSearcher(name) else null, name)
    }

    /** Rebuilds the landmarkers for [numHands] on [gpu] in place. Call from the feeding thread while it holds every slot. */
    private fun buildAll(gpu: Boolean) {
        Landmarkers(landmarker, searcher, delegate).close()
        install(buildSet(gpu, numHands))
    }

    /** Puts [built] in use. Call from the feeding thread while it holds every slot (nothing in flight). */
    private fun install(built: Landmarkers) {
        landmarker = built.follower
        searcher = built.searcher
        delegate = built.delegate
        synchronized(palmSearch) { palmSearch.reset() }
        // 0.5.42 (vc55 to vc61: on the CPU the hand model's "inference" read 90 to 140 ms, two frames in flight, so each
        // frame waited behind the other's work; the Space misses 17 to 46 % of its frames with the model on the GPU and a
        // hand up, so the CPU is where it runs then): one frame at a time on the CPU, so a frame's wait is only its own
        // work; two on the GPU as before.
        table.useCapacity(if (delegate == "GPU") MAX_IN_FLIGHT else 1)
    }

    private fun create(d: Delegate, hands: Int): HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(HandModelStore.load(context)).setDelegate(d).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(hands)
            .setMinHandDetectionConfidence(thresholds.detection)
            .setMinHandPresenceConfidence(thresholds.presence)
            .setMinTrackingConfidence(thresholds.tracking)
            .setResultListener { result: HandLandmarkerResult, _ -> deliver(result) }
            .setErrorListener { e ->
                if (delegate == "GPU" && resultsSeen == 0L) gpuErrors++
                // Free a slot only for an image still counted as in flight (not one given up on).
                if (table.removeOldest() != null) slot.release()
                onError(e.message ?: e.javaClass.simpleName)
            }
            .build(),
    )

    /** Rebuilds the landmarker on the CPU after the GPU delegate failed. */
    fun fallBackToCpu() {
        Log.w(TAG, "MediaPipe GPU delegate failed on its first frames; switching to CPU")
        buildAll(gpu = false)
        gpuErrors = 0
    }

    /**
     * 0.5.2: moves the landmarkers to [gpu] or the CPU ([com.hayeco.flipspaces.core.hands.DelegateChooser]).
     * 1.1.13 ([com.hayeco.flipspaces.core.hands.DelegateSwap]): the new ones are built on [buildThread] while these keep
     * tracking, then swapped in between two frames; the old ones close on [buildThread]. Call from the feeding thread on
     * every frame while the move is wanted, without holding a slot. True when the move is done, not needed, or its build
     * failed; false while the new landmarkers are still being built (or the frames in flight did not finish in time).
     */
    fun moveTo(gpu: Boolean): Boolean {
        // 0.5.43: the NPU does not change; MediaPipe stays on the CPU behind it (for the checks and the fallback).
        if (npu != null || (delegate == "GPU") == gpu) {
            swap.cancel()
            return true
        }
        val target = com.hayeco.flipspaces.core.hands.DelegateSwap.Target(gpu, numHands)
        if (swap.isReady) {
            if (!awaitIdle(1_000)) return false
            try {
                val built = swap.take(numHands) ?: return false
                val old = Landmarkers(landmarker, searcher, delegate)
                install(built)
                buildThread.execute { old.close() }
                // Results from the new delegate count as its first frames again.
                resultsSeen = 0
                gpuErrors = 0
                Log.i(TAG, "MediaPipe now on the ${built.delegate}, swapped in between two frames; the ${old.delegate} tracked until then")
                return true
            } finally {
                releaseAll()
            }
        }
        if (swap.failed == target) return true
        if (swap.begin(target)) {
            val t0 = SystemClock.uptimeMillis()
            val tracking = delegate
            buildThread.execute {
                val built = try {
                    buildSet(target.gpu, target.hands)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaPipe for ${target.label} could not be built; it stays on the $tracking", e)
                    null
                }
                if (built != null) Log.i(TAG, "MediaPipe for ${target.label} built in ${SystemClock.uptimeMillis() - t0} ms on its own thread while the $tracking kept tracking")
                swap.finished(target, built)
            }
        }
        return false
    }

    /** 1.1.15: NPU models built on [buildThread] for "Hands on the NPU" switched on while hands run, until swapped in. */
    @Volatile private var npuBuilt: NpuHandEngine? = null
    @Volatile private var npuBuilding = false
    @Volatile private var npuBuildFailed = false

    /**
     * 1.1.15 ([com.hayeco.flipspaces.core.hands.NpuHandsSwitch]): applies "Hands on the NPU" ([wanted]) to the running
     * hands. Call from the feeding thread between two frames, without holding a slot; [build] makes the NPU's models (null
     * when this phone has none) and runs on [buildThread] while the frames keep going to MediaPipe. Returns the step taken;
     * on [com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.MEDIAPIPE_TO_CPU] the caller moves MediaPipe ([moveTo]).
     */
    fun applyNpu(wanted: Boolean, build: () -> NpuHandEngine?): com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step {
        val step = com.hayeco.flipspaces.core.hands.NpuHandsSwitch.step(
            wanted = wanted,
            npuActive = npu != null,
            built = npuBuilt != null,
            building = npuBuilding,
            failed = npuBuildFailed,
            mediaPipeOnGpu = delegate == "GPU",
        )
        when (step) {
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.KEEP -> if (!wanted) npuBuildFailed = false
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.MEDIAPIPE_TO_CPU -> Unit
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.DISCARD -> npuBuilt?.let { built ->
                npuBuilt = null
                buildThread.execute { runCatching { built.close() } }
            }
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.DROP -> {
                // The NPU's frame in flight holds a slot until its hands are delivered: none is lost.
                if (!awaitIdle(1_000)) return com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.KEEP
                try {
                    val engine = npu
                    npu = null
                    resultsSeen = 0
                    gpuErrors = 0
                    engine?.let { buildThread.execute { runCatching { it.close() } } }
                    Log.i(TAG, "Hands on the NPU: off now (Developer card): MediaPipe on the $delegate takes the frames from this one, nothing more goes to the NPU (no frames, no checks)")
                } finally {
                    releaseAll()
                }
            }
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.BUILD -> {
                npuBuilding = true
                val t0 = SystemClock.uptimeMillis()
                buildThread.execute {
                    val engine = try {
                        build()
                    } catch (e: Exception) {
                        Log.w(TAG, "Hands on the NPU could not be built; MediaPipe keeps tracking", e)
                        null
                    }
                    if (engine != null) Log.i(TAG, "Hands on the NPU: built in ${SystemClock.uptimeMillis() - t0} ms on its own thread while MediaPipe on the $delegate kept tracking")
                    npuBuildFailed = engine == null
                    // Hands stopped while it was built: nobody will swap it in.
                    if (closed) runCatching { engine?.close() } else npuBuilt = engine
                    npuBuilding = false
                }
            }
            com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.INSTALL -> {
                if (!awaitIdle(1_000)) return com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step.KEEP
                try {
                    val behind = delegate
                    npu = npuBuilt
                    npuBuilt = null
                    swap.cancel()
                    Log.i(TAG, "Hands on the NPU: on now (Developer card), swapped in between two frames; MediaPipe on the $behind stays behind it for the checks")
                } finally {
                    releaseAll()
                }
            }
        }
        return step
    }

    /**
     * 0.5.7: rebuilds the landmarker for [hands] hands on the same delegate ("Track two hands"
     * switched while hands run). Call from the feeding thread while it holds the slot.
     */
    fun useHands(hands: Int) {
        if (hands == numHands) return
        val gpu = delegate == "GPU"
        numHands = hands
        buildAll(gpu)
    }

    /**
     * Waits until the previous image's result is in, up to [timeoutMs]. On true the caller holds
     * the slot and must call [submit] or [release]. A result MediaPipe never delivered frees the
     * slot after [LOST_RESULT_MS].
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        // 0.5.43: on the NPU, one frame at a time (its work is a few ms; a second would only wait).
        if (npu != null && npuPending > 0) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (npuPending > 0 && SystemClock.uptimeMillis() < deadline) Thread.sleep(1)
            if (npuPending > 0) return false
        }
        // 0.5.14: after a fallback to one in flight, a second slot is not taken while one frame is out.
        if (table.capacity < MAX_IN_FLIGHT && table.size() >= table.capacity) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (table.size() >= table.capacity && SystemClock.uptimeMillis() < deadline) Thread.sleep(2)
            if (table.size() >= table.capacity) return expireLost()
        }
        if (slot.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return true
        return expireLost()
    }

    /** Frames MediaPipe never answered free their slots after [LOST_RESULT_MS]; true when one was freed for the caller. */
    private fun expireLost(): Boolean {
        val n = table.expire(SystemClock.uptimeMillis(), LOST_RESULT_MS) { it.submittedAtMs }
        if (n == 0) return false
        Log.w(TAG, "MediaPipe never returned a result for $n frame(s); moving on")
        // One of the freed slots goes to the caller, the rest back to the pool.
        repeat(n - 1) { slot.release() }
        return true
    }

    fun release() = slot.release()

    /**
     * 0.5.14: waits until nothing is in flight (a delegate or hand count change rebuilds the graph);
     * the caller holds every slot until [releaseAll]. False on timeout (nothing held then).
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        if (slot.tryAcquire(MAX_IN_FLIGHT, timeoutMs, TimeUnit.MILLISECONDS)) return true
        return false
    }

    fun releaseAll() = slot.release(MAX_IN_FLIGHT)

    /**
     * Sends [square] (a frame cut square by [window]) captured at [capturedAtMs]. Needs the
     * slot from [awaitReady]. The bitmap can be reused once this returns: MediaPipe copies it.
     */
    fun submit(square: Bitmap, window: DetectionWindow, capturedAtMs: Long, decodeMs: Long, idleOnCpu: Boolean = false) {
        val ts = maxOf(capturedAtMs, lastTimestamp + 1)
        lastTimestamp = ts
        npu?.let { engine -> return submitNpu(engine, square, window, ts, capturedAtMs, decodeMs) }
        // 0.5.24: the idle palm search on the CPU landmarker, when asked for and built.
        val cpu = if (idleOnCpu) idleCpu else null
        val two = searcher
        val use = if (two == null || cpu != null) PalmSearch.Use.ONE else synchronized(palmSearch) { palmSearch.next() }
        table.add(ts, InFlight(capturedAtMs, SystemClock.uptimeMillis(), decodeMs, window, cpu == null && delegate == "GPU", use, idle = cpu != null))
        try {
            (cpu ?: if (use == PalmSearch.Use.TWO && two != null) two else landmarker).detectAsync(BitmapImageBuilder(square).build(), ts)
        } catch (e: RuntimeException) {
            table.cancel(ts)
            slot.release()
            throw e
        }
    }

    /**
     * 0.5.43: [square] through the NPU on its own thread; the slot frees when its hands are delivered. Every
     * [COMPARE_EVERY]th frame with a hand also goes to MediaPipe on the CPU, and the two are compared in the log. A
     * failure turns the NPU off for the session and the frames go to MediaPipe.
     */
    private fun submitNpu(engine: NpuHandEngine, square: Bitmap, window: DetectionWindow, ts: Long, capturedAtMs: Long, decodeMs: Long) {
        val submittedAt = SystemClock.uptimeMillis()
        // The caller reuses its bitmap once this returns: the NPU's thread gets a copy. 0.5.55: into one of two bitmaps
        // kept for it, not a new 1.6 MB one a frame (50 MB a second of garbage at 30 fps).
        val frame = npuCopy(square)
        npuPendingCount.incrementAndGet()
        npuThread.execute {
            try {
                val hands = try {
                    engine.process(frame, numHands, thresholds, ts)
                } catch (e: Throwable) {
                    Log.w(TAG, "Hands on the NPU failed (${e.javaClass.simpleName}: ${e.message}); MediaPipe runs from the next frame", e)
                    npu = null
                    runCatching { engine.close() }
                    emptyList()
                }
                if (hands.isNotEmpty() && npu != null && ++npuHandFrames % COMPARE_EVERY == 0L) compareWithMediaPipe(frame, ts, hands)
                val now = SystemClock.uptimeMillis()
                onResult(Result(hands.map { window.toUpright(it) }, capturedAtMs, submittedAt, decodeMs, now, window, delegateWasGpu = false, ranOnNpu = true))
            } finally {
                npuPendingCount.decrementAndGet()
                slot.release()
            }
        }
    }

    /**
     * 0.5.55: two bitmaps take turns as the NPU's copy of the frame (one frame is on the NPU at a time, [awaitReady]; the
     * other turn covers a check frame MediaPipe is given). The pixels are copied as they are, so the NPU sees the same
     * image [Bitmap.copy] gave it.
     */
    private val npuFrames = arrayOfNulls<Bitmap>(2)
    private var npuFrameNext = 0
    private var npuPixels: java.nio.ByteBuffer? = null

    /** [square]'s pixels in the next of [npuFrames]; called on the feeding thread only. */
    private fun npuCopy(square: Bitmap): Bitmap {
        if (square.config != Bitmap.Config.ARGB_8888) return square.copy(Bitmap.Config.ARGB_8888, false)
        val i = npuFrameNext
        npuFrameNext = 1 - i
        var b = npuFrames[i]
        if (b == null || b.width != square.width || b.height != square.height) {
            b = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
            npuFrames[i] = b
        }
        val bytes = square.byteCount
        var buf = npuPixels
        if (buf == null || buf.capacity() < bytes) {
            buf = java.nio.ByteBuffer.allocate(bytes)
            npuPixels = buf
        }
        buf!!.clear()
        square.copyPixelsToBuffer(buf)
        buf.rewind()
        b!!.isPremultiplied = square.isPremultiplied
        b.copyPixelsFromBuffer(buf)
        b.setHasAlpha(square.hasAlpha())
        return b
    }

    /**
     * 0.5.55: the palm detector's measured rate since the last call and the rate it is set to, for the end of the
     * Tracking line; null while MediaPipe runs. Called from the thread results are delivered on (the NPU's).
     */
    fun palmSearchLine(nowMs: Long): String? {
        val e = npu ?: return null
        val runs = e.palmRuns
        // The first line has nothing to measure against: its rate reads as unknown.
        val line = PalmIdleRate.summary(runs - palmLineRuns, if (palmLineAtMs == 0L) 0L else nowMs - palmLineAtMs, e.palmRate.label)
        palmLineRuns = runs
        palmLineAtMs = nowMs
        return line
    }

    private var palmLineRuns = 0L
    private var palmLineAtMs = 0L

    /** Sends [frame] to MediaPipe too; [deliver] sets its hands against [npuHands] and logs the gap. */
    private fun compareWithMediaPipe(frame: Bitmap, ts: Long, npuHands: List<HandFrame>) {
        compare[ts] = npuHands
        runCatching { landmarker.detectAsync(BitmapImageBuilder(frame).build(), ts) }.onFailure { compare.remove(ts) }
    }

    /** The NPU's hands against MediaPipe's for the same frame: the mean landmark gap, in pixels of the frame sent. */
    private fun logCompare(npuHands: List<HandFrame>, mp: List<HandFrame>) {
        val n = npuHands.firstOrNull()
        val m = n?.let { first -> mp.minByOrNull { com.hayeco.flipspaces.core.hands.NpuHands.meanDistance(first.landmarks, it.landmarks) } }
        if (n == null || m == null) {
            compareMissed++
            Log.i(TAG, "Hands on the NPU check: the NPU saw ${npuHands.size} hand(s), MediaPipe ${mp.size} ($compareMissed such misses)")
            return
        }
        val gapPx = com.hayeco.flipspaces.core.hands.NpuHands.meanDistance(n.landmarks, m.landmarks) * CHECK_PX
        compareSum += gapPx
        compareCount++
        val e = npu
        Log.i(TAG, String.format(java.util.Locale.ROOT,
            "Hands on the NPU check: mean landmark gap to MediaPipe %.1f px of %d (mean %.1f over %d checks), handedness %s; NPU palm %.1f ms, landmarks %.1f ms, palm detector ran %d of %d frames",
            gapPx, CHECK_PX.toInt(), compareSum / compareCount, compareCount, if (n.rightHand == m.rightHand) "the same" else "different",
            e?.lastPalmMs ?: 0.0, e?.lastLandmarkMs ?: 0.0, e?.palmRuns ?: 0L, e?.frames ?: 0L))
    }

    @Volatile private var closed = false

    override fun close() {
        closed = true
        runCatching { npuThread.shutdown() }
        runCatching { npu?.close() }
        npu = null
        runCatching { npuBuilt?.close() }
        npuBuilt = null
        // 1.1.13: a move's build still running is closed by its own thread when it finishes.
        swap.cancel()
        runCatching { buildThread.shutdown() }
        landmarker.close()
        runCatching { searcher?.close() }
        runCatching { idleCpu?.close() }
        idleCpu = null
    }

    private fun deliver(result: HandLandmarkerResult) {
        // 0.5.43: a check frame for the NPU, not a frame of the feed.
        compare.remove(result.timestampMs())?.let { npuHands ->
            logCompare(npuHands, toHands(result))
            return
        }
        // 0.5.14: this result's frame, and any sent before it that MediaPipe dropped, free their slots.
        val (meta, freed) = table.takeCounting(result.timestampMs())
        if (freed > 0) slot.release(freed)
        table.fallback?.let { if (!fallbackLogged) { fallbackLogged = true; Log.w(TAG, it) } }
        resultsSeen++
        val now = SystemClock.uptimeMillis()
        val hands = toHands(result).map { meta?.window?.toUpright(it) ?: it }
        if (meta != null && searcher != null && !meta.idle) {
            val changed = synchronized(palmSearch) { palmSearch.result(meta.use, hands.size) }
            if (changed) Log.i(TAG, if (palmSearch.both) "Two hands found: the two hand landmarker follows both, palm detector off" else "Fewer than two hands: one hand followed, the second looked for every ${palmSearch.searchEvery} frames")
        }
        onResult(Result(hands, meta?.capturedAtMs ?: result.timestampMs(), meta?.submittedAtMs ?: now, meta?.decodeMs ?: 0, now, meta?.window, meta?.gpu ?: (delegate == "GPU"), idleSearch = meta?.idle == true))
    }

    /** Every hand MediaPipe found (two at most, see setNumHands), in its order. */
    private fun toHands(result: HandLandmarkerResult): List<HandFrame> = result.landmarks().indices.mapNotNull { toHand(result, it) }

    private fun toHand(result: HandLandmarkerResult, i: Int): HandFrame? {
        val points = result.landmarks().getOrNull(i) ?: return null
        if (points.size != HandFrame.COUNT) return null
        val category = result.handedness().getOrNull(i)?.firstOrNull()
        // Metres around the hand's centre: pinch is measured on these (core's HandFrame.pinchRatio).
        val world = result.worldLandmarks().getOrNull(i)?.takeIf { it.size == HandFrame.COUNT }
        return HandFrame(
            landmarks = points.map { Landmark(it.x(), it.y(), it.z()) },
            timestampMs = result.timestampMs(),
            rightHand = category?.categoryName()?.equals("Right", ignoreCase = true),
            score = category?.score() ?: 1f,
            world = world?.map { Landmark(it.x(), it.y(), it.z()) },
        )
    }

    companion object {
        /** 0.5.14: frames sent to MediaPipe before the first answer is in (0.5.13: 1). */
        const val MAX_IN_FLIGHT = 2

        /**
         * 0.5.43: one frame with a hand in this many is also run by MediaPipe, to check the NPU against. 0.5.55: 150 (was
         * 30): a check line every 5 s with a hand up, not every second, and a fifth of MediaPipe's 50 ms CPU runs for it.
         */
        const val COMPARE_EVERY = 150L

        /** 0.5.43: the check's gap is read in pixels of a frame this many wide (the frames sent are up to 640). */
        const val CHECK_PX = 640f
        private const val TAG = "FlipSpacesHands"
        private const val GPU_ERRORS_BEFORE_FALLBACK = 3
        private const val LOST_RESULT_MS = 1000L
    }
}
