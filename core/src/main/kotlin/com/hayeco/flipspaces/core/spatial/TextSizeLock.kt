package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.7 (Mike: "user shouldnt be allowed to press the resize buttons again until its reloaded from the actual resize
 * because i just tapped the text bigger and smaller resize buttons a lot and then it took a while for all the button
 * actions to catch up"): the trackpad Menu's Text size keys after a tap. Each tap is one numbered request ([tap]); the
 * keys stay off until the Space says that request's window laid out again at its new size ([settled], from
 * [TextSizeSettle]), or [timeoutMs] passed, so they never stay stuck. A tap while off is dropped, never queued.
 */
class TextSizeLock(val timeoutMs: Long = TIMEOUT_MS) {
    private var lastSeq = 0L
    private var waitingSeq = 0L
    private var tapMs = 0L

    /** Whether the keys are off at [nowMs], the Space having settled every request up to [settledSeq]. */
    fun locked(nowMs: Long, settledSeq: Long): Boolean {
        if (waitingSeq == 0L) return false
        if (settledSeq >= waitingSeq || nowMs - tapMs >= timeoutMs) {
            waitingSeq = 0L
            return false
        }
        return true
    }

    /** A tap at [nowMs]: its request number to send, or null while the keys are off (the tap is dropped). */
    fun tap(nowMs: Long, settledSeq: Long): Long? {
        if (locked(nowMs, settledSeq)) return null
        lastSeq = maxOf(lastSeq, settledSeq) + 1
        waitingSeq = lastSeq
        tapMs = nowMs
        return lastSeq
    }

    companion object {
        /** The keys come back on after this long, ms, whatever the Space said. */
        const val TIMEOUT_MS = 3000L
    }
}

/**
 * 1.2.7: the Text size requests the Space took off its queue in one frame, folded into one final size per app
 * ([finals]), so several taps lay the app out once at the last size instead of once for each.
 */
class TextSizeBatch {
    private val steps = LinkedHashMap<String, MutableList<Int>>()

    /** The highest request number in the batch, 0 with none numbered. */
    var seq = 0L
        private set

    val isEmpty: Boolean get() = steps.isEmpty() && seq == 0L

    /** A request of [step] steps (0 back to 100 %) for [packageName], or for no window (null), numbered [requestSeq]. */
    fun add(packageName: String?, step: Int, requestSeq: Long) {
        seq = maxOf(seq, requestSeq)
        if (!packageName.isNullOrEmpty()) steps.getOrPut(packageName) { ArrayList() } += step
    }

    /** Each app's final size from its size now ([percent]); apps that end where they started are left out. */
    fun finals(percent: (String) -> Int): Map<String, Pair<Int, Int>> {
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        for ((pkg, list) in steps) {
            val before = percent(pkg)
            val after = list.fold(before) { p, s -> if (s == 0) AppTextSize.DEFAULT else AppTextSize.step(p, s) }
            if (after != before) out[pkg] = before to after
        }
        return out
    }

    fun clear() {
        steps.clear()
        seq = 0L
    }
}

/**
 * 1.2.7: when a Text size change has really landed, for [TextSizeLock]. The Space waits on the window it changed
 * ([waitFor]): its display's resize sent ([sent]) and the app's first frame drawn after it, with no resize or relayout
 * nudge still due. A window with nothing to send (its size came out the same, or it has no display) settles at once;
 * past [maxWaitMs] it settles anyway.
 */
class TextSizeSettle(val maxWaitMs: Long = TextSizeLock.TIMEOUT_MS) {
    private var panelId: Int? = null
    private var seq = 0L
    private var sinceMs = 0L
    private var framesAtSend: Long? = null

    /** The request number waited on, 0 with none. */
    val waitingSeq: Long get() = if (panelId != null) seq else 0L

    /** Request [requestSeq] changed [panel]'s size at [nowMs] (a later one replaces it). */
    fun waitFor(panel: Int, requestSeq: Long, nowMs: Long) {
        panelId = panel
        seq = maxOf(seq, requestSeq)
        sinceMs = nowMs
        framesAtSend = null
    }

    /** [panel]'s display resize went out; [frames] is how many frames its app had given right after. */
    fun sent(panel: Int, frames: Long) {
        if (panel == panelId) framesAtSend = frames
    }

    /** [panel] closed: nothing more to wait for. */
    fun forget(panel: Int) {
        if (panel == panelId) framesAtSend = Long.MIN_VALUE
    }

    /**
     * The request number settled at [nowMs], or null while still waiting (or waiting on nothing). [due] says whether the
     * window still has a resize or nudge to send, [frames] how many frames its app has given (null with no display).
     */
    fun check(nowMs: Long, due: (Int) -> Boolean, frames: (Int) -> Long?): Long? {
        val id = panelId ?: return null
        val now = frames(id)
        val atSend = framesAtSend
        val done = when {
            nowMs - sinceMs >= maxWaitMs -> true
            atSend == Long.MIN_VALUE || now == null -> true
            due(id) -> false
            atSend == null -> true
            else -> now > atSend
        }
        if (!done) return null
        panelId = null
        framesAtSend = null
        return seq
    }
}
