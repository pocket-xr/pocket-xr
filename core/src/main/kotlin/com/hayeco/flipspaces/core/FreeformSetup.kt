package com.hayeco.flipspaces.core

/** One Settings.Global value that a one-time adb command can set. */
data class GlobalSetting(val key: String, val value: Int, val why: String) {
    val adbCommand: String get() = "adb shell settings put global $key $value"
}

/**
 * The windowing switches as read on the phone. A null setting means the app could not read it
 * (hidden settings can be blocked for normal apps), so it is treated as not set.
 */
data class WindowingEnvironment(
    val sdkInt: Int,
    val hasFreeformFeature: Boolean,
    val enableFreeformSupport: Int?,
    val forceResizableActivities: Int?,
    val forceDesktopModeOnExternalDisplays: Int?,
)

sealed interface FreeformStatus {
    /** Windows on the glasses honour launch bounds. */
    data object Ready : FreeformStatus

    /**
     * Something is off. [freeformOff] is true when freeform itself is off, which means launch
     * bounds are ignored and apps open full screen on the glasses.
     */
    data class NeedsSetup(
        val missing: List<GlobalSetting>,
        val freeformOff: Boolean,
        val uncertain: Boolean,
    ) : FreeformStatus {
        val commands: List<String> get() = FreeformSetup.commandsFor(missing)
    }
}

object FreeformSetup {
    const val ANDROID_15 = 35

    val enableFreeform = GlobalSetting(
        "enable_freeform_support", 1,
        "Turns on freeform windows so apps can open at a set size and position.",
    )
    val forceResizable = GlobalSetting(
        "force_resizable_activities", 1,
        "Lets apps that do not declare themselves resizable open in a window.",
    )
    val desktopOnExternal = GlobalSetting(
        "force_desktop_mode_on_external_displays", 1,
        "Makes the glasses a desktop style display with freeform windows instead of a mirror.",
    )

    fun evaluate(env: WindowingEnvironment): FreeformStatus {
        val freeformOn = env.hasFreeformFeature || env.enableFreeformSupport == 1
        val missing = buildList {
            if (!freeformOn) add(enableFreeform)
            if (env.forceResizableActivities != 1) add(forceResizable)
            if (env.sdkInt >= ANDROID_15 && env.forceDesktopModeOnExternalDisplays != 1) add(desktopOnExternal)
        }
        if (missing.isEmpty()) return FreeformStatus.Ready
        val uncertain = missing.any {
            when (it) {
                enableFreeform -> env.enableFreeformSupport == null
                forceResizable -> env.forceResizableActivities == null
                else -> env.forceDesktopModeOnExternalDisplays == null
            }
        }
        return FreeformStatus.NeedsSetup(missing, freeformOff = !freeformOn, uncertain = uncertain)
    }

    /** Commands to run once from a computer, ending with a reboot so the window manager rereads them. */
    fun commandsFor(settings: List<GlobalSetting>): List<String> =
        if (settings.isEmpty()) emptyList() else settings.map { it.adbCommand } + "adb reboot"

    /** Every setting Pocket XR uses, for the docs and the copy-all button. */
    val all: List<GlobalSetting> = listOf(enableFreeform, forceResizable, desktopOnExternal)

    /**
     * 1.5.4 (a vivo V2562 hides these switches in Developer options): Pocket XR can write them itself through Shizuku,
     * with the same rights as adb. [current] is each key's value as `settings get global` printed it (null when it
     * could not be read); the ones not already on are returned.
     */
    fun toTurnOn(current: Map<String, String?>): List<GlobalSetting> =
        all.filter { current[it.key]?.trim() != it.value.toString() }

    fun getCommand(setting: GlobalSetting): List<String> = listOf("settings", "get", "global", setting.key)

    fun putCommand(setting: GlobalSetting): List<String> =
        listOf("settings", "put", "global", setting.key, setting.value.toString())
}
