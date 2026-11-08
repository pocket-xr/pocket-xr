package com.hayeco.flipspaces.core.spatial

/**
 * The virtual display behind each app panel. Flag values are the framework's
 * `DisplayManager.VIRTUAL_DISPLAY_FLAG_*` constants (several are hidden, so they are written out
 * here); the set follows docs/SPATIAL.md section 5 and scrcpy's `--new-display`.
 */
object PanelDisplays {
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_PRESENTATION = 1 shl 1
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14

    /**
     * Every panel display's name starts with this, so display selection can tell Pocket XR's
     * own panels from the glasses (DisplaySelector.isOwnPanel).
     */
    const val NAME_PREFIX = "Pocket XR: "

    fun displayName(label: String): String = NAME_PREFIX + label

    /**
     * The test rig's stand-in glasses (debug builds). Named like a window display so that releases before this one,
     * installed beside the test copy, leave it alone; this code alone takes it for glasses.
     */
    val RIG_NAME = displayName("rig")

    /** One of Pocket XR's own window displays, by name. */
    fun isPanelName(name: String?): Boolean = name != null && name.startsWith(NAME_PREFIX) && name != RIG_NAME

    /**
     * Density of every panel display: 1280 px wide is 853 dp, a small tablet. 0.5.11: 330 (was 240)
     * with the panels' 1.375 times more pixels a degree, so apps lay out the same 853 dp across
     * (1760 px) and draw every glyph with 1.375 times the pixels ([PanelSharpness]).
     */
    const val DENSITY_DPI = 330

    /** `WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY`: the keyboard shows on the phone. */
    const val IME_POLICY_FALLBACK_DISPLAY = 1

    /** Flags for a panel display on Android [sdk]; trusted flags only exist from 13 (33). */
    fun flags(sdk: Int): Int {
        var f = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (sdk >= 33) f = f or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        if (sdk >= 34) f = f or FLAG_OWN_FOCUS
        return f
    }

    private val safeName = Regex("""[A-Za-z0-9_.$]+""")

    /** `am start` for an app on a panel display, run as the shell through Shizuku. */
    fun launchCommand(displayId: Int, packageName: String, activityName: String, newInstance: Boolean = false): List<String> {
        require(safeName.matches(packageName) && safeName.matches(activityName)) { "Unexpected characters in $packageName/$activityName" }
        return listOf(
            "am", "start", "--user", "current", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            // NEW_TASK | RESET_TASK_IF_NEEDED; 0.5.37: with MULTIPLE_TASK (0x08000000) for a new instance of a running app.
            // EXCLUDE_FROM_RECENTS (0x00800000): a window lives on the glasses, not in the phone's recent apps. Each
            // Space start used to leave a card per window there, so every plug-in added another set.
            "-f", if (newInstance) "0x18A00000" else "0x10A00000",
            "-n", "$packageName/$activityName",
        )
    }

    /**
     * 0.5.21, the window's Refresh button: the app's launcher activity started again on its display
     * with NEW_TASK | CLEAR_TASK, so its task is cleared and the activity made anew, which reloads
     * what it shows. The app's process stays (no force stop), and its other windows are not touched.
     */
    fun refreshCommand(displayId: Int, packageName: String, activityName: String): List<String> {
        require(safeName.matches(packageName) && safeName.matches(activityName)) { "Unexpected characters in $packageName/$activityName" }
        return listOf(
            "am", "start", "--user", "current", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-f", "0x10808000", // NEW_TASK | CLEAR_TASK | EXCLUDE_FROM_RECENTS
            "-n", "$packageName/$activityName",
        )
    }
}
