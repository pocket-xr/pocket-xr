package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.8: the short hint the Space shows while a hand is near the bottom of the glasses camera's
 * picture ([HandStatus.nearBottom]). Mike's 0.5.6 two hand log had his right hand at y 894 to 982
 * of 1080 with the pinches lost there; the camera sees 80 by 51 degrees, so a hand held low
 * leaves it at the bottom. The hint comes up once a hand has been low for [showAfterMs] (a hand
 * passing through does not flash it) and goes [hideAfterMs] after no hand is low any more.
 */
class CameraEdgeHint(
    private val showAfterMs: Long = 200,
    private val hideAfterMs: Long = 800,
) {
    private var lowSinceMs: Long? = null
    private var lastLowMs = Long.MIN_VALUE / 2
    private var showing = false

    /** The hint to show at [nowMs] for this frame's [hands], or null. */
    fun update(hands: Collection<HandStatus>, nowMs: Long): String? {
        val low = hands.any { it.present && it.nearBottom }
        if (low) {
            if (lowSinceMs == null) lowSinceMs = nowMs
            lastLowMs = nowMs
            if (nowMs - lowSinceMs!! >= showAfterMs) showing = true
        } else {
            lowSinceMs = null
            if (showing && nowMs - lastLowMs >= hideAfterMs) showing = false
        }
        return if (showing) TEXT else null
    }

    companion object {
        val TEXT: String get() = UiText.get("core_hand_low_hint", "Hand low in the camera: raise it a little")
    }
}
