package com.hayeco.flipspaces.addon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hayeco.flipspaces.core.pointer.Point

/**
 * Performs touches on the glasses display through AccessibilityService.dispatchGesture with
 * GestureDescription.Builder.setDisplayId (API 30). Taps are one short stroke. Drags are chains
 * of continued strokes (StrokeDescription.continueStroke, API 26): each segment is dispatched
 * when the previous one completes, and the finger stays down between them until a segment with
 * willContinue = false lifts it. Live drags coalesce cursor moves into the next segment.
 */
class GestureInjector(private val service: AccessibilityService, private val onProblem: (String) -> Unit) {
    var displayId: Int = 0

    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: Point? = null
    private var dispatching = false
    private val pending = ArrayDeque<Segment>()

    /** Bumped whenever a chain is dropped, so callbacks from an old chain are ignored. */
    private var generation = 0

    /** One piece of a drag: go to [to] over [durationMs], and lift at the end if [lift]. */
    private data class Segment(val to: Point, val durationMs: Long, val lift: Boolean)

    val busy: Boolean get() = dispatching || stroke != null

    /**
     * One tap: a [TAP_MS] stroke on a one point path (moveTo plus a zero length lineTo, so the
     * path is never empty) on [displayId]. Logs the dispatch and Android's result callback
     * under FlipSpacesGesture.
     */
    fun tap(at: Point) {
        if (busy) cancelChain("A tap arrived during a drag")
        val x = at.x.safe()
        val y = at.y.safe()
        val where = "%.0f,%.0f on display %d".format(java.util.Locale.ROOT, x, y, displayId)
        val stroke = try {
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x, y) }, 0, TAP_MS)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Tap at $where: invalid stroke: ${e.message}")
            onProblem(service.getString(R.string.addon_gesture_tap_failed, e.message.toString()))
            return
        }
        Log.i(TAG, "Tap at $where: dispatching a $TAP_MS ms stroke")
        dispatch(stroke, "tap at $where") { Log.i(TAG, "Tap at $where: completed") }
    }

    /**
     * One complete drag from [from] to [to]: press and hold, travel, then rest and lift. Used to
     * commit a window move or resize on DeX's title bar or corner.
     */
    fun drag(from: Point, to: Point, holdMs: Long = 140, travelMs: Long = 380) {
        if (busy) cancelChain("A window move arrived during a drag")
        start(from, holdMs)
        pending += Segment(to, travelMs, lift = false)
        pending += Segment(to, 90, lift = true)
        pump()
    }

    /** Finger down at [at] for a live drag that follows [moveTo] until [end]. */
    fun start(at: Point, holdMs: Long = 40) {
        if (busy) cancelChain("A new drag started before the last one ended")
        val path = Path().apply { moveTo(at.x.safe(), at.y.safe()) }
        val first = GestureDescription.StrokeDescription(path, 0, holdMs, true)
        stroke = first
        last = at
        dispatch(first, "drag start") { pump() }
    }

    fun moveTo(at: Point) {
        if (stroke == null) return
        // Only the newest target matters while a segment is in flight.
        val lastPending = pending.lastOrNull()
        if (lastPending != null && !lastPending.lift) pending.removeLast()
        pending += Segment(at, SEGMENT_MS, lift = false)
        pump()
    }

    fun end(at: Point) {
        if (stroke == null) return
        pending += Segment(at, SEGMENT_MS, lift = true)
        pump()
    }

    private fun pump() {
        if (dispatching) return
        val current = stroke ?: return
        val segment = pending.removeFirstOrNull() ?: return
        val from = last ?: segment.to
        val path = Path().apply {
            moveTo(from.x.safe(), from.y.safe())
            if (segment.to != from) lineTo(segment.to.x.safe(), segment.to.y.safe())
        }
        val next = current.continueStroke(path, 0, segment.durationMs, !segment.lift)
        last = segment.to
        if (segment.lift) {
            stroke = null
        } else {
            stroke = next
        }
        dispatch(next, if (segment.lift) "drag end" else "drag move") {
            if (stroke != null) pump() else reset()
        }
    }

    private fun dispatch(s: GestureDescription.StrokeDescription, what: String, onDone: () -> Unit) {
        val gesture = try {
            GestureDescription.Builder().addStroke(s).setDisplayId(displayId).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not build the $what gesture: ${e.message}")
            onProblem(service.getString(R.string.addon_gesture_build_failed, what, e.message.toString()))
            reset()
            return
        }
        dispatching = true
        val mine = generation
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription) {
                    if (mine != generation) return
                    dispatching = false
                    onDone()
                }

                override fun onCancelled(description: GestureDescription) {
                    if (mine != generation) return
                    dispatching = false
                    Log.w(TAG, "$what cancelled on display $displayId")
                    onProblem(service.getString(R.string.addon_gesture_cancelled, what))
                    reset()
                }
            },
            main,
        )
        if (!accepted) {
            Log.w(TAG, "$what refused by dispatchGesture on display $displayId")
            dispatching = false
            onProblem(service.getString(R.string.addon_gesture_refused, what, displayId))
            reset()
        }
    }

    private fun cancelChain(reason: String) {
        Log.i(TAG, reason)
        reset()
    }

    private fun reset() {
        generation++
        dispatching = false
        stroke = null
        last = null
        pending.clear()
    }

    /** Gesture paths may not have negative coordinates. */
    private fun Float.safe(): Float = coerceAtLeast(0f)

    companion object {
        private const val TAG = "FlipSpacesGesture"
        private const val TAP_MS = 60L
        private const val SEGMENT_MS = 24L
    }
}
