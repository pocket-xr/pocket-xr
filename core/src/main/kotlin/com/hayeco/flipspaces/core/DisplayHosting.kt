package com.hayeco.flipspaces.core

/**
 * What the platform's own description of a display says about hosting apps. Display.toString
 * prints the hidden DisplayInfo, which carries the flag names and, on Android 16 QPR and later,
 * canHostTasks. Reading the text needs no hidden API access, so it works where reflection fails.
 */
data class DisplayInfoText(
    val canHostTasks: Boolean?,
    val flags: Set<String>,
    /** DisplayInfo's "type VIRTUAL" and so on, read when Display.getType is refused. */
    val type: DisplayType? = null,
    /** DisplayInfo's "owner com.android.shell (uid 2000)": set only for virtual displays. */
    val ownerPackage: String? = null,
) {
    val isTrusted: Boolean? get() = if (flags.isEmpty()) null else "FLAG_TRUSTED" in flags
    val allowsContentModeSwitch: Boolean? get() = if (flags.isEmpty()) null else "FLAG_ALLOWS_CONTENT_MODE_SWITCH" in flags

    companion object {
        private val canHostPattern = Regex("""canHostTasks\s*[=:]?\s*(true|false)""", RegexOption.IGNORE_CASE)
        private val flagPattern = Regex("""\bFLAG_[A-Z0-9_]+\b""")
        private val typePattern = Regex("""\btype (INTERNAL|EXTERNAL|WIFI|OVERLAY|VIRTUAL)\b""")
        private val ownerPattern = Regex("""\bowner ([A-Za-z0-9_.]+) \(uid""")

        fun parse(text: String?): DisplayInfoText {
            if (text.isNullOrBlank()) return DisplayInfoText(null, emptySet())
            val canHost = canHostPattern.find(text)?.groupValues?.get(1)?.lowercase()?.toBooleanStrictOrNull()
            val type = typePattern.find(text)?.groupValues?.get(1)?.let { name -> DisplayType.entries.firstOrNull { it.name == name } }
            return DisplayInfoText(
                canHost,
                flagPattern.findAll(text).map { it.value }.toSet(),
                type,
                ownerPattern.find(text)?.groupValues?.get(1),
            )
        }
    }
}

/** How the glasses display is being used by the system. */
enum class ContentMode(val label: String) {
    /** The display copies the phone and cannot host tasks for any app, including the shell. */
    MIRROR("mirror (copies the phone, cannot host apps)"),

    /** The display is its own screen and apps can open there. */
    EXTENDED("extended (apps can open here)"),

    /** Samsung DeX owns the display: an extended desktop with its own taskbar and windows. */
    DESKTOP("extended, Samsung DeX desktop"),

    /** Nothing has said either way yet; the first launch will tell. */
    UNKNOWN("not known yet");

    val canHostApps: Boolean get() = this == EXTENDED || this == DESKTOP
}

/** The signals a denial was recorded under. When they change, the denial no longer counts. */
data class HostingSignature(val canHostTasks: Boolean?, val desktopModeActive: Boolean?)

/** Android refused to open Pocket XR's own home screen on [displayId]. */
data class LaunchDenial(val displayId: Int, val message: String, val signature: HostingSignature)

data class HostingAssessment(
    val mode: ContentMode,
    /** Plain sentence saying which signal decided [mode]. */
    val evidence: String,
    /** The real error text of the denial behind a mirror verdict, if any. */
    val lastError: String? = null,
) {
    val dexOwnsDisplay: Boolean get() = mode == ContentMode.DESKTOP
}

/** Decides whether the glasses display is in mirror or extended (DeX) mode from what the app can see. */
object HostingAssessor {
    val MIRROR_MESSAGE: String
        get() = UiText.get("core_mirror_message", "The glasses are in mirror mode, so apps can't open on them. Turn on DeX (or the extended display mode) and try again.")

    /**
     * Strongest evidence first: Pocket XR's home already running there proves the display hosts
     * tasks; then the platform's canHostTasks; DeX running on the display; a recorded denial of
     * our own home screen; the mirror setting. Anything else is [ContentMode.UNKNOWN].
     */
    fun assess(
        display: DisplayCandidate,
        homeRunning: Boolean,
        denial: LaunchDenial?,
        mirrorSettingOn: Boolean? = null,
    ): HostingAssessment {
        val dex = display.desktopModeActive == true
        val extended = if (dex) ContentMode.DESKTOP else ContentMode.EXTENDED
        val activeDenial = denial?.takeIf { it.displayId == display.id && it.signature == display.hostingSignature }
        return when {
            homeRunning -> HostingAssessment(extended, "Pocket XR home is running on the glasses.")
            display.canHostTasks == true -> HostingAssessment(extended, "Android reports the glasses can host apps (canHostTasks=true).")
            dex -> HostingAssessment(ContentMode.DESKTOP, "Samsung DeX is running on the glasses.")
            display.canHostTasks == false -> HostingAssessment(
                ContentMode.MIRROR,
                "Android reports the glasses cannot host apps (canHostTasks=false).",
                activeDenial?.message,
            )
            activeDenial != null -> HostingAssessment(
                ContentMode.MIRROR,
                "Android refused to open Pocket XR's own screen on the glasses.",
                activeDenial.message,
            )
            mirrorSettingOn == true -> HostingAssessment(ContentMode.MIRROR, "The \"Mirror built-in display\" setting is on.")
            else -> HostingAssessment(ContentMode.UNKNOWN, "Android does not say whether the glasses can host apps.")
        }
    }
}

/** Space the system keeps for itself on a display, such as the DeX taskbar. */
data class DisplayInsets(val topPx: Int = 0, val bottomPx: Int = 0) {
    init {
        require(topPx >= 0 && bottomPx >= 0) { "Insets cannot be negative" }
    }

    companion object {
        val NONE = DisplayInsets()

        /** DeX's taskbar height, used until the home activity reports the real insets. */
        const val DEX_TASKBAR_DP = 48
    }
}

/** Screens that can switch the glasses from mirror to extended mode, best first. */
enum class SettingsTarget(private val labelKey: String, private val labelEnglish: String, private val hintKey: String, private val hintEnglish: String) {
    DEX("core_settings_dex", "Open Samsung DeX settings", "core_settings_dex_hint", "Turn on Samsung DeX, then come back."),
    EXTERNAL_DISPLAY("core_settings_external", "Open external display settings", "core_settings_external_hint", "Switch the glasses from mirror to extend, then come back."),
    SETTINGS_HOME("core_settings_home", "Open Settings", "core_settings_home_hint", "Go to Connected devices, Samsung DeX, turn it on, then come back.");

    val buttonLabel: String get() = UiText.get(labelKey, labelEnglish)
    val hint: String get() = UiText.get(hintKey, hintEnglish)

    companion object {
        fun forMirror(dexInstalled: Boolean): List<SettingsTarget> =
            if (dexInstalled) listOf(DEX, EXTERNAL_DISPLAY, SETTINGS_HOME) else listOf(EXTERNAL_DISPLAY, SETTINGS_HOME)
    }
}
