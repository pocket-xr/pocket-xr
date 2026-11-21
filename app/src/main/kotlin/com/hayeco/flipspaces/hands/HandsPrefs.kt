package com.hayeco.flipspaces.hands

import android.content.Context

/**
 * Saved hand settings (0.5.0). Before, nothing about
 * hands survived a process start: Mike pressed Set up and Start hands every time, and the
 * mirror switch were gone.
 *
 * 1.6.2: the hand aim calibration (pinch on the dots) is gone. Older versions saved it under "ray_calibration",
 * "ray_calibration_rms" and "calibration_offered"; those keys may still be in the file but nothing reads them.
 */
class HandsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hands", Context.MODE_PRIVATE)

    /** Start hands by themselves with the glasses; set the first time Start hands works. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 0.5.43: the hand models on the Exynos 2500's NPU ([NpuHandEngine]); on by default, Developer card. */
    var npuHands: Boolean
        get() = prefs.getBoolean(KEY_NPU_HANDS, true)
        set(value) = prefs.edit().putBoolean(KEY_NPU_HANDS, value).apply()

    /**
     * The air box's mirror switch, for a camera that faces the user. 0.5.101 (Mike's settings audit): always off, its switch
     * is gone: the glasses' camera faces out, so a mirrored picture is never right for it.
     */
    var mirror: Boolean
        get() = false
        set(value) = prefs.edit().putBoolean(KEY_MIRROR, value).apply()

    /**
     * 0.5.1: aim with 0.5.0's shoulder ray (true) instead of the camera ray through the pinch
     * point (false, the default). A new key, so 0.5.0's "hand_ray" (on by default) does not carry
     * the shoulder ray over.
     */
    var shoulderRay: Boolean
        get() = prefs.getBoolean(KEY_SHOULDER_RAY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOULDER_RAY, value).apply()

    /**
     * 0.5.4: MediaPipe looks for two hands (true) or one (false, the default). With two, its palm
     * detector runs on every frame while only one hand is in view, which held Mike's 0.5.3 tracking
     * to 17 to 21 fps with 45 to 50 ms a frame; with one it runs only when the hand is lost.
     * 0.5.7: takes effect at once, also while hands run ([GlassesCameraProvider.twoHands]).
     */
    // 0.5.37 (Mike: Track two hands and two hand resize should be on by default and the setting removed): always on.
    var twoHands: Boolean
        get() = true
        set(value) {
            prefs.edit().putBoolean(KEY_TWO_HANDS, value).apply()
            GlassesCameraProvider.twoHands = value
        }

    /** The "tick Always open Pocket XR" hint was shown once. */
    var usbHintShown: Boolean
        get() = prefs.getBoolean(KEY_USB_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_USB_HINT, value).apply()

    /** 0.5.1: RECORD_AUDIO was asked for once by itself (the button stays for later). */
    var micAsked: Boolean
        get() = prefs.getBoolean(KEY_MIC_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_ASKED, value).apply()

    private companion object {
        const val KEY_AUTO_START = "auto_start"
        const val KEY_MIRROR = "mirror"
        const val KEY_SHOULDER_RAY = "shoulder_ray"
        const val KEY_USB_HINT = "usb_hint_shown"
        const val KEY_MIC_ASKED = "mic_asked"
        const val KEY_TWO_HANDS = "two_hands"
        const val KEY_NPU_HANDS = "npu_hands"
    }
}
