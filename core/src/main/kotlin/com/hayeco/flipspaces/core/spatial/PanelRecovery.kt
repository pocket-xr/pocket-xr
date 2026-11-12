package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * Where one app panel's virtual display is. Before 0.4.0 a panel that failed once stayed failed
 * until the Space restarted, and a panel service restart left every panel pointing at a dead
 * display id. [PanelRecovery] moves panels between these states instead.
 */
sealed interface PanelState {
    /** The panel service is not connected yet; [why] says what it waits for. */
    data class Waiting(val why: String) : PanelState

    /** A create and launch is in flight; [attempt] counts from 1. */
    data class Opening(val attempt: Int) : PanelState

    /**
     * The display is up. [warning] is a launch error on a display that was created. [paused]
     * is true between Android's onPaused and onResumed callbacks for the display (0.4.1).
     * [appClosed] is true once no task is left on the display (0.4.2): the panel shows App
     * closed with Reopen and Close instead of a blank frame.
     */
    data class Showing(val displayId: Int, val warning: String? = null, val paused: Boolean = false, val appClosed: Boolean = false) : PanelState

    /** Attempt [attempt] failed with [error]; the next one starts at [retryAtMs]. */
    data class RetryWait(val attempt: Int, val error: String, val retryAtMs: Long) : PanelState

    /** Every attempt failed. A press on the panel starts again from attempt 1. */
    data class Failed(val attempts: Int, val error: String) : PanelState
}

/** Android's callbacks for a virtual display (`IVirtualDisplayCallback`), 0.4.1. */
enum class PanelDisplayEvent { PAUSED, RESUMED, STOPPED }

object PanelRecovery {
    /** Tries per panel before it shows as failed. */
    const val MAX_ATTEMPTS = 3

    /** Wait before attempt [nextAttempt] (2 or 3): 1 s, then 2 s. */
    fun backoffMs(nextAttempt: Int): Long = 1000L shl (nextAttempt - 2).coerceAtLeast(0)

    /** True when the renderer should start a create and launch for a panel in [state] now. */
    fun shouldOpen(state: PanelState, hostReady: Boolean, nowMs: Long): Boolean = hostReady && when (state) {
        is PanelState.Waiting -> true
        is PanelState.RetryWait -> nowMs >= state.retryAtMs
        else -> false
    }

    /** The attempt number to open with from [state]. */
    fun nextAttempt(state: PanelState): Int = when (state) {
        is PanelState.RetryWait -> state.attempt + 1
        else -> 1
    }

    /** After attempt [attempt] failed with [error] at [nowMs]: wait and retry, or give up. */
    fun afterFailure(attempt: Int, error: String, nowMs: Long): PanelState =
        if (attempt >= MAX_ATTEMPTS) {
            PanelState.Failed(attempt, error)
        } else {
            PanelState.RetryWait(attempt, error, nowMs + backoffMs(attempt + 1))
        }

    /**
     * The panel service went away (Shizuku stopped or restarted, or its process died): every
     * display it owned is gone. Returns the new states and the dead display ids to forget.
     * Failed panels get a fresh start too, since the failure may have been the dying service.
     */
    fun onHostLost(states: Map<Int, PanelState>, why: String): Pair<Map<Int, PanelState>, List<Int>> {
        val dead = states.values.mapNotNull { (it as? PanelState.Showing)?.displayId }
        return states.mapValues { PanelState.Waiting(why) } to dead
    }

    /**
     * Android paused, resumed or stopped the display behind a panel. Paused and resumed only
     * mark a showing panel (its app gets no frames while paused; the placeholder says so).
     * Stopped means the display is gone for good, so the panel opens again on a new one after
     * the first backoff; the second value is the dead display id to forget, if any.
     */
    fun onDisplayEvent(state: PanelState, event: PanelDisplayEvent, nowMs: Long): Pair<PanelState, Int?> {
        val showing = state as? PanelState.Showing ?: return state to null
        return when (event) {
            PanelDisplayEvent.PAUSED -> showing.copy(paused = true) to null
            PanelDisplayEvent.RESUMED -> showing.copy(paused = false) to null
            PanelDisplayEvent.STOPPED ->
                PanelState.RetryWait(1, UiText.format("spatial_panel_display_stopped", "Android stopped the panel display %1\$d", showing.displayId), nowMs + backoffMs(2)) to showing.displayId
        }
    }

    /** A press on a failed panel: start again. Other states are left alone. */
    fun retryByHand(state: PanelState, why: String): PanelState =
        if (state is PanelState.Failed) PanelState.Waiting(why) else state

    /** The line shown on the panel's placeholder and on the phone. */
    fun label(state: PanelState, app: String): String = when (state) {
        is PanelState.Waiting -> state.why
        is PanelState.Opening ->
            if (state.attempt == 1) UiText.format("spatial_panel_opening", "Opening %1\$s", app)
            else UiText.format("spatial_panel_opening_try", "Opening %1\$s (try %2\$d of %3\$d)", app, state.attempt, MAX_ATTEMPTS)
        is PanelState.Showing -> when {
            state.appClosed -> UiText.format("spatial_panel_app_closed", "%1\$s closed. Reopen or close the panel.", app)
            state.paused -> UiText.format("spatial_panel_paused", "Android paused %1\$s's display %2\$d; it shows again when Android resumes it", app, state.displayId)
            else -> state.warning ?: UiText.format("spatial_panel_showing", "Showing %1\$s (display %2\$d)", app, state.displayId)
        }
        is PanelState.RetryWait -> UiText.format("spatial_panel_try_failed", "Try %1\$d of %2\$d failed: %3\$s. Retrying.", state.attempt, MAX_ATTEMPTS, state.error)
        is PanelState.Failed -> UiText.format("spatial_panel_failed", "Failed after %1\$d tries: %2\$s. Pinch to try again.", state.attempts, state.error)
    }
}
