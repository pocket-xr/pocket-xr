package com.hayeco.flipspaces.core

/**
 * The glasses ready service of 0.4.4: a quiet foreground service that keeps Pocket XR's process
 * awake, so the display added event for the glasses reaches its plug-in watcher. In the 0.4.3
 * retest Android held that event back while Pocket XR was idle in the background (a cached
 * process), so with the pointer service off nothing ran the plug-in check and Android's own
 * home took the glasses.
 *
 * Its type is connectedDevice: it is there for a connected device, the glasses, and Android 15
 * still lets it start from BOOT_COMPLETED (it refuses camera, dataSync and media types there).
 * Android 14 asks for FOREGROUND_SERVICE_CONNECTED_DEVICE plus one of its listed permissions;
 * CHANGE_NETWORK_STATE is the one Pocket XR has a use for (the glasses' USB network).
 */
object GlassesReady {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    val NOTIFICATION_TEXT: String get() = UiText.get("core_glasses_ready_notification", "Pocket XR is ready for your glasses")

    /** Manifest permissions the connectedDevice type needs on Android 14 and 15. */
    val PERMISSIONS = listOf(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    )

    /** Broadcasts that start it. Both are allowed to start a foreground service from the background. */
    fun startsOn(action: String?): Boolean = action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED

    /** Where a start came from, for the FlipSpaces/Mode line. */
    enum class From { BOOT, UPDATE, APP_START, PHONE_SCREEN }

    /**
     * From the app's start a foreground service may be refused (Android 12 and later refuse one
     * from the background, and a process Shizuku started is in the background); a phone screen
     * in front, boot and an update are always allowed. A refusal is logged, not retried: the
     * next phone screen or boot starts it.
     */
    fun mayBeRefused(from: From): Boolean = from == From.APP_START

    /** `Manifest.permission.POST_NOTIFICATIONS`, asked for at run time from Android 13 (API 33). */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val NOTIFICATIONS_ASKED_FROM_SDK = 33

    /**
     * 0.4.6: whether the main screen asks for [POST_NOTIFICATIONS]. The 0.4.5 retest found the
     * glasses ready notification never showed on Android 13 and later: nothing asked for the
     * permission (only the hands screen did, for its own service), so Android hid it. The
     * service still runs without it; the notification is how Mike sees that and can stop it.
     *
     * Asked when it is not granted and either never asked before or Android says to explain
     * ([showRationale], true after one denial). After a second denial Android shows nothing
     * more, and neither does this: no prompt on every visit to the main screen.
     */
    fun askForNotifications(sdkInt: Int, granted: Boolean, askedBefore: Boolean, showRationale: Boolean): Boolean =
        sdkInt >= NOTIFICATIONS_ASKED_FROM_SDK && !granted && (!askedBefore || showRationale)
}
