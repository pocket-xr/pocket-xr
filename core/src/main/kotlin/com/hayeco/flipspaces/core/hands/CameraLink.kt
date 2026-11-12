package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.UiText

/** Where the glasses camera connection is, for [CameraLink]. */
sealed interface LinkState {
    data object Idle : LinkState

    /** Opening the camera; [attempt] counts tries since it last streamed. */
    data class Connecting(val attempt: Int) : LinkState

    /** Frames are arriving. */
    data class Streaming(val sinceMs: Long) : LinkState

    /**
     * Not streaming, will try again at [retryAtMs]. [detached] means the glasses are off USB,
     * so the next try waits for them to come back (or a slow poll in case the broadcast is missed).
     */
    data class Waiting(val reason: String, val retryAtMs: Long, val attempt: Int, val detached: Boolean = false, val sinceMs: Long = 0) : LinkState

    /**
     * [gaveUp] (0.5.0): the glasses stayed unplugged past [CameraLink.giveUpDetachedMs]. Not a
     * fault: the service ends quietly and hands start again by themselves with the glasses.
     */
    data class Failed(val reason: String, val gaveUp: Boolean = false) : LinkState

    data object Stopped : LinkState
}

/**
 * Decides when to (re)open the glasses camera. The glasses can drop off USB for a second or two
 * (a loose cable, a brownout), and the camera must come back by itself with a message that says
 * what happened, not a generic camera error. Plain Kotlin: the app feeds it USB events and the
 * outcome of each try, and asks [shouldConnect] and [waitMs].
 *
 * - Before the camera has ever streamed, [maxFailuresBeforeStreaming] failed tries end in
 *   [LinkState.Failed] with the camera's own error (a real setup problem).
 * - After it has streamed, failures and lost links always retry with [backoffMs].
 * - Detached glasses are waited for, up to [giveUpDetachedMs].
 */
class CameraLink(
    val backoffMs: List<Long> = listOf(250, 500, 1000, 2000),
    val maxFailuresBeforeStreaming: Int = 3,
    val attachSettleMs: Long = 400,
    val detachedPollMs: Long = 1500,
    val giveUpDetachedMs: Long = 5 * 60_000L,
) {
    var state: LinkState = LinkState.Idle
        private set

    /** Failed tries since the last good stream. */
    var failures = 0
        private set

    var everStreamed = false
        private set

    /** How many times the link was lost after streaming and came back. */
    var reconnects = 0
        private set

    private var lostSinceStreaming = false

    fun start(): LinkState {
        failures = 0
        everStreamed = false
        lostSinceStreaming = false
        return set(LinkState.Connecting(1))
    }

    /** The app is about to open the camera. */
    fun connecting(): LinkState = set(LinkState.Connecting(failures + 1))

    /**
     * 0.5.19 (the link dropped once at a Space restart and was back 253 ms
     * later): how long the last reconnect took, from the loss to the first frame again, ms (null: none yet).
     */
    var lastReconnectMs: Long? = null
        private set
    private var lostAtMs: Long? = null

    /** The first frame arrived. */
    fun streaming(nowMs: Long): LinkState {
        if (lostSinceStreaming) {
            reconnects++
            lastReconnectMs = lostAtMs?.let { nowMs - it }
        }
        lostAtMs = null
        lostSinceStreaming = false
        failures = 0
        everStreamed = true
        return set(LinkState.Streaming(nowMs))
    }

    /** Streaming stopped without the glasses leaving USB (no frames, transfer errors). */
    fun linkLost(reason: String, nowMs: Long): LinkState {
        if (state is LinkState.Stopped) return state
        if (everStreamed) lostSinceStreaming = true
        if (lostAtMs == null) lostAtMs = nowMs
        failures++
        if (!everStreamed && failures >= maxFailuresBeforeStreaming) return set(LinkState.Failed(reason))
        return set(LinkState.Waiting(UiText.format("core_camera_link_lost", "Camera link lost (%1\$s). Reconnecting.", reason), nowMs + backoff(), failures, sinceMs = nowMs))
    }

    /** Opening or negotiating failed with [reason]. */
    fun error(reason: String, nowMs: Long): LinkState {
        if (state is LinkState.Stopped) return state
        failures++
        if (!everStreamed && failures >= maxFailuresBeforeStreaming) return set(LinkState.Failed(reason))
        return set(LinkState.Waiting(UiText.format("core_camera_trying_again", "%1\$s. Trying again.", reason), nowMs + backoff(), failures, sinceMs = nowMs))
    }

    /** The glasses left USB (detach broadcast, or the device vanished from the device list). */
    fun detached(nowMs: Long): LinkState {
        if (state is LinkState.Stopped || state is LinkState.Failed) return state
        if (everStreamed) lostSinceStreaming = true
        val since = (state as? LinkState.Waiting)?.takeIf { it.detached }?.sinceMs ?: nowMs
        return set(LinkState.Waiting(DETACHED, nowMs + detachedPollMs, failures, detached = true, sinceMs = since))
    }

    /** No camera in the device list when trying to connect. */
    fun noDevice(nowMs: Long): LinkState =
        if (!everStreamed && state !is LinkState.Waiting) set(LinkState.Failed(NOT_CONNECTED)) else detached(nowMs)

    /** A USB device was attached; if it is the glasses, try soon (after it settles). */
    fun attached(nowMs: Long): LinkState {
        val s = state
        if (s is LinkState.Waiting) return set(s.copy(retryAtMs = nowMs + attachSettleMs, detached = false))
        return s
    }

    /** The glasses came back but Android reset USB access, which needs Mike to accept a prompt. */
    fun needsPermission(nowMs: Long): LinkState =
        set(LinkState.Waiting(NEEDS_PERMISSION, nowMs + detachedPollMs, failures, sinceMs = nowMs))

    fun stop(): LinkState = set(LinkState.Stopped)

    fun shouldConnect(nowMs: Long): Boolean = when (val s = state) {
        is LinkState.Connecting -> true
        is LinkState.Waiting -> nowMs >= s.retryAtMs
        else -> false
    }

    /** How long the app may sleep before asking again (it also wakes on USB events). */
    fun waitMs(nowMs: Long): Long = when (val s = state) {
        is LinkState.Waiting -> (s.retryAtMs - nowMs).coerceIn(0, detachedPollMs)
        else -> 0
    }

    /** Call while waiting detached: gives up after [giveUpDetachedMs]. */
    fun checkGiveUp(nowMs: Long): LinkState {
        val s = state
        if (s is LinkState.Waiting && s.detached && nowMs - s.sinceMs >= giveUpDetachedMs) {
            return set(
                LinkState.Failed(
                    UiText.format("core_camera_gave_up", "The glasses stayed disconnected for %1\$d minutes, so hand tracking stopped. It starts again by itself when the Space opens with the glasses.", giveUpDetachedMs / 60_000),
                    gaveUp = true,
                ),
            )
        }
        return s
    }

    /** What the setup screen and notification should say. */
    val message: String
        get() = when (val s = state) {
            LinkState.Idle -> UiText.get("core_camera_not_started", "Not started")
            is LinkState.Connecting -> if (s.attempt <= 1) UiText.get("core_camera_opening", "Opening the glasses camera") else UiText.format("core_camera_opening_try", "Opening the glasses camera (try %1\$d)", s.attempt)
            is LinkState.Streaming -> UiText.get("core_camera_streaming", "Streaming")
            is LinkState.Waiting -> s.reason
            is LinkState.Failed -> s.reason
            LinkState.Stopped -> UiText.get("core_camera_stopped", "Stopped")
        }

    private fun backoff(): Long = backoffMs[(failures - 1).coerceIn(0, backoffMs.lastIndex)]

    private fun set(s: LinkState): LinkState {
        state = s
        return s
    }

    companion object {
        val DETACHED: String get() = UiText.get("core_camera_detached", "The glasses disconnected from USB. Waiting for them to come back; hand tracking resumes by itself.")
        val NOT_CONNECTED: String get() = UiText.get("core_camera_not_connected", "The glasses camera is not connected")
        val NEEDS_PERMISSION: String get() = UiText.get("core_camera_needs_permission", "The glasses reconnected but Android reset USB access to them. Accept the USB prompt on the Flip to resume.")
    }
}
