package com.hayeco.flipspaces.hands

import android.content.Context
import android.util.Log
import com.hayeco.flipspaces.SpacesState
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.hands.CameraModel
import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandGestureRecognizer
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.HandRayBuilder
import com.hayeco.flipspaces.core.hands.HandRaySettings
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.hands.HandUpdate
import com.hayeco.flipspaces.core.hands.HandsRecognizer
import com.hayeco.flipspaces.core.hands.PinchTrace
import com.hayeco.flipspaces.core.hands.RayCalibration
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.StereoLayout
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.pointer.PointerHub
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.SpaceSession
import com.hayeco.flipspaces.spatial.SpaceStore

/**
 * Where tracked hands enter the pointer, whoever tracked them: the glasses camera after
 * MediaPipe, or (debug builds) the hand replay. One [HandsRecognizer] for both, so a replay
 * runs through exactly the filter, pinch lock, recognizer and pointer the camera does. Logs
 * every pinch step under FlipSpacesHands and hands the signals to [PointerHub].
 *
 * 0.5.0: two hands, each with its own recognizer, and a hand ray with the saved camera
 * calibration instead of the air box. The head pose at each frame's capture time turns the ray's
 * neck when head tracking is live; when it is not, that is logged, and the rays move with the head.
 *
 * 0.5.1: the ray goes from the camera through the hand by default (0.4.8's near 1:1 mapping, in
 * angles); 0.5.0's shoulder ray is an option ([HandsPrefs.shoulderRay]). 0.5.2: through Meta's
 * stable pinch point on the knuckles, not the pinch point between the tips, so a pinch does not
 * move the cursor.
 */
object HandFeed {
    private const val TAG = "FlipSpacesHands"

    private var hands: HandsRecognizer? = null

    @Volatile
    private var lastStatus: HandStatus? = null

    /** 0.5.89: when [HandsRecognizer.bothPalms]'s reason was last logged. */
    private var bothPalmsLoggedMs = 0L

    /** The ray correction in use; identity while the Space's calibration takes its samples. */
    @Volatile
    private var calibration: RayCalibration? = null

    /** True while the Space's "pinch on the dots" calibration runs: rays are left uncorrected. */
    @Volatile
    var calibrating: Boolean = false
        private set

    /** Debug replays aim at display pixels through the air box, not a ray (see HandReplayPlayer). */
    @Volatile
    var forceImageMode: Boolean = false

    /** The last head tracking state logged for the hand rays, so a change is logged once. */
    @Volatile
    private var headLogged: String? = null

    /**
     * 0.5.14: jitter of a still hand, capture to landmark, frame to
     * pointer and pinch onset, one "Hand numbers" line every 10 s ([com.hayeco.flipspaces.core.hands.HandMetrics]).
     */
    val metrics = com.hayeco.flipspaces.core.hands.HandMetrics()

    /** The recognizer, made for the glasses display the first time (or after [resize]). */
    @Synchronized
    fun recognizer(context: Context): HandsRecognizer = hands ?: run {
        val display = DisplaySelector.select(DisplayMonitor(context).snapshot(), SpacesState.choices.value.preferredDisplayId)
        val store = SpaceStore(context)
        // 0.5.9: hands aim in one eye's picture: half of a 3840 x 1080 side by side display.
        // 0.5.14: a side by side display is split whatever the view (2D there is the same picture twice).
        val (w, h) = StereoLayout.eyeSize(
            StereoLayout.mode(display?.widthPx ?: 1920, display?.heightPx ?: 1080, true),
            display?.widthPx ?: 1920,
            display?.heightPx ?: 1080,
        )
        val prefs = HandsPrefs(context)
        val rayMode = !forceImageMode
        val fit = calibration ?: prefs.calibration.also { calibration = it }
        // 0.5.9: the glasses' real field of view (GlassesOptics), the same projection the Space draws with.
        // 0.5.26: the glasses' screen in the mode in use, as the Space's projection.
        val frustum = store.optics(StereoLayout.isSbsMode(display?.widthPx ?: 1920, display?.heightPx ?: 1080)).pictureFrustum(w, h)
        val camera = CameraModel()
        // 0.5.1: the camera ray by default (0.5.2: through the stable pinch point); 0.5.0's shoulder ray is an option.
        val settings = if (prefs.shoulderRay) HandRaySettings.SHOULDER else HandRaySettings()
        fun make(side: HandSide) = HandGestureRecognizer(w, h, GlassesCameraProvider.airBox).also { r ->
            r.frustum = frustum
            if (rayMode) r.ray = HandRayBuilder(side, camera, settings, calibration = if (calibrating) RayCalibration.IDENTITY else fit)
            r.headAt = { t -> HeadTracker.poseAt(t) }
            // 0.5.46: a pinch with the palm turned to the glasses opens the launcher (Meta Quest's).
            r.palmPinchOpensLauncher = true
            // 0.5.47 (Mike, vc67: the palm pinch was the wrong way round): the palm side swapped for the Aura.
            r.palmSideFlipped = true
            // 0.5.18: no hand presses while the phone is folded, opened or touched.
            r.phoneHandling = com.hayeco.flipspaces.core.hands.PhoneHandling.shared
        }
        duplicatesLogged = 0
        HandsRecognizer(make(HandSide.RIGHT), second = make(HandSide.LEFT)).also {
            hands = it
            Log.i(
                TAG,
                "Hand recognizer for ${w}x$h${display?.let { d -> " (display ${d.id})" } ?: " (no glasses display yet)"}, two hands, " +
                    if (rayMode) "${settings.line()}, ${camera.line()}, display %.1f by %.1f degrees, calibration ${fit.line()}".format(java.util.Locale.ROOT, frustum.horizontalFovDeg, frustum.verticalFovDeg) else "air box in the picture (image mode)",
            )
        }
    }

    /** How many duplicate hands were logged ([HandsRecognizer.duplicatesDropped]). */
    @Volatile
    private var duplicatesLogged = 0

    /** 0.5.11: how many label flips were logged ([HandsRecognizer.labelFlipsIgnored]). */
    @Volatile
    private var flipsLogged = 0

    /** The display size hands map onto, from [recognizer]. */
    fun displaySize(context: Context): Pair<Int, Int> = recognizer(context).primary.let { it.displayWidth to it.displayHeight }

    /**
     * One frame's hands (empty for none), captured at [capturedAtMs] on the uptime clock, with
     * [latencyMs] from capture to the cursor showing (null for the default prediction).
     */
    fun deliver(context: Context, frame: List<HandFrame>, capturedAtMs: Long, latencyMs: Float?): HandUpdate {
        val r = recognizer(context)
        val update = synchronized(this) {
            val box = GlassesCameraProvider.airBox
            r.primary.airBox = box
            r.second?.airBox = box
            // The Space stretches a panel itself when both hands hold it; the DeX cursor needs the old stretch.
            r.legacyStretch = !SpaceSession.active
            r.update(frame, capturedAtMs, latencyMs)
        }
        if (frame.isNotEmpty()) logHeadTracking()
        // 0.5.1: one hand found twice is dropped; logged the first time and then every 30th.
        val dropped = r.duplicatesDropped
        if (dropped > duplicatesLogged && (duplicatesLogged == 0 || dropped - duplicatesLogged >= 30)) {
            duplicatesLogged = dropped
            Log.i(TAG, "Dropped a second detection of the same hand (overlapping the first), $dropped so far")
        }
        // 0.5.89 (Mike: both hands up "didnt work" on 0.5.88): with two hands in view and no hold, why not, once a second.
        r.bothPalms.reason?.let { why ->
            if (capturedAtMs - bothPalmsLoggedMs >= 1000L) {
                bothPalmsLoggedMs = capturedAtMs
                Log.i(TAG, "Both hands seen, not holding the windows: $why")
            }
        }
        // 0.5.11: a followed hand keeps its side through MediaPipe's left/right flips (one cursor dot).
        val flips = r.labelFlipsIgnored
        if (flips > flipsLogged && (flipsLogged == 0 || flips - flipsLogged >= 30)) {
            flipsLogged = flips
            Log.i(TAG, "Hand kept on its side although MediaPipe named it the other hand (a pinch flips it), $flips so far: one cursor, not two")
        }
        // Closing onset, lock, press, drag unlock and release, with locked and raw positions.
        update.traces.forEach { Log.i(TAG, it.line() + rayNote(it, update)) }
        // 0.5.14: the hand numbers (the first hand's cursor; the raw palm landmark in camera pixels).
        synchronized(metrics) {
            metrics.traces(update.traces)
            val palm = frame.firstOrNull()?.palmPoint?.let { com.hayeco.flipspaces.core.pointer.Point(it.x * 1920f, it.y * 1080f) }
            metrics.frame(update.status.cursor.takeIf { update.status.present }, palm, update.status.pinched, capturedAtMs)
            val glide = (SpaceSession.handIntervalMs ?: 33.0) / 2.0
            metrics.line(capturedAtMs, SpaceSession.poseToPhotonsP50Ms, glide)?.let { Log.i(TAG, it) }
        }
        update.signals.forEach {
            val inner = (it as? PointerSignal.ForHand)?.signal ?: it
            when (inner) {
                PointerSignal.ShowDock -> Log.i(TAG, "Gesture: launcher (a pinch with the palm turned to the glasses, let go, or an open palm held)")
                PointerSignal.Recentre -> Log.i(TAG, "Gesture: recentre (a pinch with the palm turned to the glasses, held)")
                // 0.5.88: both hands up holding every window.
                is PointerSignal.AllWindows -> if (inner.phase != com.hayeco.flipspaces.core.pointer.Phase.UPDATE) Log.i(TAG, "Gesture: both hands up, all windows ${if (inner.phase == com.hayeco.flipspaces.core.pointer.Phase.BEGIN) "held" else "let go, distance x%.2f, size x%.2f".format(java.util.Locale.ROOT, inner.distance, inner.size)}")
                PointerSignal.Cancel -> Log.i(TAG, "Gesture: cancel (hand lost mid drag, or a stretch began)${(it as? PointerSignal.ForHand)?.let { f -> ", ${f.hand.label} hand" } ?: ""}")
                else -> Unit
            }
            PointerHub.submit(it, HandProviderKind.GLASSES_CAMERA)
        }
        SpaceSession.hands = update.hands
        SpaceSession.handsCapturedAtMs = capturedAtMs
        // PC VR: the hands go to SteamVR as well, with the Space's own ray and pinch.
        if (com.hayeco.flipspaces.pcvr.PcVrSession.isOpen) com.hayeco.flipspaces.pcvr.PcVrHands.store(frame, capturedAtMs, update.hands)
        // Only on a change: an unchanged "no hand" posted every frame redrew the pointer overlay
        // 25 times a second for nothing.
        if (update.status != lastStatus) {
            lastStatus = update.status
            PointerHub.handStatus(update.status, HandProviderKind.GLASSES_CAMERA)
        }
        return update
    }

    /** A press or release with its hand's ray, in degrees and metres, for the log. */
    private fun rayNote(trace: PinchTrace, update: HandUpdate): String {
        val hand = trace as? PinchTrace.Hand ?: return ""
        if (hand.trace !is PinchTrace.Press && hand.trace !is PinchTrace.Release) return ""
        return update.hands[hand.side]?.ray?.let { "; ${it.line()}" } ?: ""
    }

    /**
     * Logs whether hand rays are held in the world by head tracking, once per kind of state.
     * 0.5.1: a stream that connects but sends no sensor data (Mike's 0.5.0 log) is said in so many words.
     */
    private fun logHeadTracking() {
        val status = HeadTracker.status.value
        val kind = status.javaClass.simpleName
        if (headLogged == kind) return
        headLogged = kind
        when {
            status.tracking ->
                Log.i(TAG, "Hand rays: head tracking is live, so rays stay put in the world as the head turns")
            status is HeadTrackingStatus.NoReports ->
                Log.w(TAG, "Hand rays: head tracking has no sensor data (${status.summary}); the Space is head locked and the cursors move with the head")
            else ->
                Log.i(TAG, "Hand rays: no head tracking (${status.summary}), so rays move with the head")
        }
    }

    /** The Space's calibration starts: rays from now on are uncorrected. */
    fun beginCalibration() {
        calibrating = true
        applyCalibration(RayCalibration.IDENTITY)
    }

    /** The calibration ended with [result] (null: cancelled or refused, keep the old one). */
    fun endCalibration(result: RayCalibration?) {
        calibrating = false
        if (result != null) calibration = result
        applyCalibration(calibration ?: RayCalibration.IDENTITY)
    }

    private fun applyCalibration(c: RayCalibration) {
        synchronized(this) {
            val r = hands ?: return
            r.primary.ray?.calibration = c
            r.second?.ray?.calibration = c
        }
    }

    /** Hands went away for good (camera stopped, replay ended): drop any pinch state. */
    fun reset() {
        synchronized(this) { hands?.reset() }
        lastStatus = null
        SpaceSession.hands = emptyMap()
        PointerHub.handStatus(null, HandProviderKind.GLASSES_CAMERA)
    }

    /** The glasses display changed, or a hand setting did: the next frame makes a new recognizer. */
    @Synchronized
    fun resize() {
        hands = null
    }
}
