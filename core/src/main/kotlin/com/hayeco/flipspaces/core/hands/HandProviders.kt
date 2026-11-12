package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.UiText

/** Sources of pointer input, in the order Pocket XR prefers them. */
enum class HandProviderKind(private val labelKey: String, private val labelEnglish: String) {
    XREAL_SDK("core_provider_xreal_sdk", "XREAL SDK hand tracking"),
    GLASSES_CAMERA("core_provider_glasses_camera", "Glasses camera + MediaPipe"),
    TRACKPAD("core_provider_trackpad", "Phone trackpad"),
    ;

    val label: String get() = UiText.get(labelKey, labelEnglish)
}

sealed interface ProviderStatus {
    /** Cannot work on this phone and glasses, with the reason. */
    data class Unavailable(val reason: String) : ProviderStatus

    /** Could work once the user allows [what]. */
    data class NeedsPermission(val what: String) : ProviderStatus

    /** Ready to start. */
    data object Ready : ProviderStatus

    /** Running; [detail] says what it is doing (format, frame rate). */
    data class Running(val detail: String) : ProviderStatus

    /** Tried and failed, with Android's or the device's own error text. */
    data class Failed(val error: String) : ProviderStatus
}

object ProviderChooser {
    /**
     * The provider to drive the cursor: the user's choice if it can run, otherwise the first in
     * [HandProviderKind] order that is running or ready. The trackpad needs nothing, so it is
     * the answer when nothing else works.
     */
    fun choose(statuses: Map<HandProviderKind, ProviderStatus>, preferred: HandProviderKind? = null): HandProviderKind {
        fun usable(kind: HandProviderKind) = statuses[kind].let { it is ProviderStatus.Ready || it is ProviderStatus.Running }
        if (preferred != null && usable(preferred)) return preferred
        return HandProviderKind.entries.firstOrNull { it != HandProviderKind.TRACKPAD && usable(it) } ?: HandProviderKind.TRACKPAD
    }
}

/** USB ids of the glasses, from Mike's Flip 7 (lsusb style vendor:product). */
object GlassesUsb {
    const val XREAL_VENDOR = 0x3318
    const val AURA_PRODUCT = 0x043A

    fun isXreal(vendorId: Int): Boolean = vendorId == XREAL_VENDOR

    fun isAura(vendorId: Int, productId: Int): Boolean = vendorId == XREAL_VENDOR && productId == AURA_PRODUCT
}

/**
 * 0.5.7: how many hands MediaPipe follows, from "Track two hands". The switch takes effect while
 * hands run: the detection thread rebuilds the landmarker between two frames when the count it
 * runs with is not the one wanted, so two hand resize works as soon as the switch is on.
 */
object HandCount {
    fun wanted(twoHands: Boolean): Int = if (twoHands) 2 else 1

    /** The count to rebuild MediaPipe with, or null when [running] is already the one wanted. */
    fun change(running: Int, twoHands: Boolean): Int? = wanted(twoHands).takeIf { it != running }

    fun line(hands: Int): String =
        if (hands == 1) "one hand (palm detector only when the hand is lost)" else "two hands (palm detector on every frame with one hand in view)"
}
