package com.hayeco.flipspaces.core

/**
 * 1.5.5: how many Pocket XR screens are started (showing) now, counted from the activities' onStart and onStop.
 *
 * Android lets an app start a camera foreground service only while one of its screens is showing, and from Android 15
 * never from a start that comes from BOOT_COMPLETED. Google Play flagged that path (boot, the glasses ready service,
 * a screen opening on the glasses, hand tracking): hand tracking now asks for its camera service only while
 * [showing] is true. Never goes below zero, so a stop without its start (a screen created before the count began)
 * cannot hide a later one. Thread safe.
 */
class ScreenCount {
    private var started = 0

    @Synchronized
    fun onStart() {
        started++
    }

    @Synchronized
    fun onStop() {
        if (started > 0) started--
    }

    val showing: Boolean @Synchronized get() = started > 0

    val count: Int @Synchronized get() = started
}
