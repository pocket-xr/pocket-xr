package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.UiText

/** What [UsbAsk.decide] says about asking for the glasses camera's USB access. */
enum class UsbAskStep {
    /** Access is there: nothing to ask. */
    GRANTED,

    /** Pocket XR was opened by the plug-in prompt, which grants access with it: never ask over it. */
    GRANT_FROM_ATTACH,

    /** The glasses were plugged in moments ago and Android's "Open Pocket XR" prompt may still be up: wait. */
    WAIT_FOR_ATTACH_PROMPT,

    /** Asked once since this plug-in: Android's answer stands until the next one. */
    ALREADY_ASKED,

    /** Ask now (UsbManager.requestPermission). */
    ASK,
}

/** What [UsbAsk.decide] looks at. Times are on one clock (uptime), milliseconds. */
data class UsbAskFacts(
    val granted: Boolean,
    /** When these glasses were plugged in (or first seen this plug), null when unknown. */
    val attachedAtMs: Long?,
    /** MainActivity was started by USB_DEVICE_ATTACHED for this plug-in. */
    val launchedByAttach: Boolean,
    val askedThisPlug: Boolean,
    val nowMs: Long,
)

/**
 * 0.5.1, the two popups at every plug-in.
 * Android shows "Open Pocket XR to handle XREAL Aura?" for the USB_DEVICE_ATTACHED filter, and
 * 0.5.0's hand camera asked for USB access itself while that prompt was still up, so a second
 * "Allow Pocket XR to access XREAL Aura?" came on top. Now the camera never asks while the
 * plug-in prompt can still be answered ([ATTACH_PROMPT_MS]), never asks when that prompt opened
 * Pocket XR (its OK grants access), and asks at most once a plug-in.
 *
 * Neither prompt offered "Always" because the glasses have a microphone and Pocket XR lacked
 * RECORD_AUDIO; with it granted the plug-in prompt has the "Always open" box, and once ticked
 * Android opens Pocket XR with access on every plug-in and shows no prompt at all.
 */
object UsbAsk {
    /** How long after a plug-in the "Open Pocket XR" prompt is left to be answered first. */
    const val ATTACH_PROMPT_MS = 12_000L

    fun decide(f: UsbAskFacts): UsbAskStep = when {
        f.granted -> UsbAskStep.GRANTED
        f.launchedByAttach -> UsbAskStep.GRANT_FROM_ATTACH
        f.askedThisPlug -> UsbAskStep.ALREADY_ASKED
        f.attachedAtMs != null && f.nowMs - f.attachedAtMs < ATTACH_PROMPT_MS -> UsbAskStep.WAIT_FOR_ATTACH_PROMPT
        else -> UsbAskStep.ASK
    }

    /** How long until [decide] could say ASK, 0 when it can now. */
    fun waitMs(f: UsbAskFacts): Long =
        if (decide(f) == UsbAskStep.WAIT_FOR_ATTACH_PROMPT) (f.attachedAtMs!! + ATTACH_PROMPT_MS - f.nowMs).coerceAtLeast(0L) else 0L

    fun line(step: UsbAskStep, f: UsbAskFacts): String = "USB access for the glasses camera: " + when (step) {
        UsbAskStep.GRANTED -> "granted"
        UsbAskStep.GRANT_FROM_ATTACH -> "Pocket XR was opened by the plug-in prompt, so its OK grants access; not asking again"
        UsbAskStep.WAIT_FOR_ATTACH_PROMPT -> "waiting up to ${(waitMs(f) + 999) / 1000} s for the \"Open Pocket XR\" plug-in prompt before asking"
        UsbAskStep.ALREADY_ASKED -> "asked once this plug-in; waiting for the answer"
        UsbAskStep.ASK -> "asking (the plug-in prompt was not answered with OK)"
    }

    /**
     * The setup screen's hint. With RECORD_AUDIO Android shows the "Always" box on the single
     * plug-in prompt; without it the box is hidden, so the hint asks for that first.
     */
    fun hint(recordAudioGranted: Boolean): String = if (recordAudioGranted) {
        UiText.get("core_usb_hint_always", "When you plug the glasses in, Android shows one prompt, \"Open Pocket XR to handle XREAL Aura?\". Tick \"Always open Pocket XR when XREAL Aura is connected\" and tap OK. After that Pocket XR opens with the glasses and no prompt shows again.")
    } else {
        UiText.get("core_usb_hint_microphone", "Android only offers \"Always\" on the plug-in prompt once Pocket XR has the microphone permission, because the glasses have a microphone. Allow it here, then tick Always on the next plug-in.")
    }
}
