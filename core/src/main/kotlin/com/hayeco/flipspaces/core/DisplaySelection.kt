package com.hayeco.flipspaces.core

/**
 * Mirrors the hidden android.view.Display TYPE_* constants (read through Display.getType where
 * the platform lets a normal app call it). [UNKNOWN] means the type could not be read.
 */
enum class DisplayType(val platformValue: Int, private val labelKey: String, private val labelEnglish: String) {
    UNKNOWN(0, "core_display_type_unknown", "unknown type"),
    INTERNAL(1, "core_display_type_internal", "built-in"),
    EXTERNAL(2, "core_display_type_external", "external wired (USB-C DisplayPort or HDMI)"),
    WIFI(3, "core_display_type_wifi", "wireless"),
    OVERLAY(4, "core_display_type_overlay", "overlay"),
    VIRTUAL(5, "core_display_type_virtual", "virtual");

    val label: String get() = UiText.get(labelKey, labelEnglish)

    companion object {
        fun fromPlatform(value: Int?): DisplayType = entries.firstOrNull { it.platformValue == value } ?: UNKNOWN
    }
}

/**
 * Mirrors android.hardware.display.DeviceProductInfo CONNECTION_TO_SINK_* (public since API 31).
 * [UNKNOWN] also covers a display with no product info at all.
 */
enum class SinkConnection(val platformValue: Int) {
    UNKNOWN(0),
    BUILT_IN(1),
    DIRECT(2),
    TRANSITIVE(3);

    companion object {
        fun fromPlatform(value: Int?): SinkConnection = entries.firstOrNull { it.platformValue == value } ?: UNKNOWN
    }
}

/** Where a display physically lives, as far as the app can tell. */
enum class DisplayOrigin(private val labelKey: String, private val labelEnglish: String) {
    /** Part of the phone: the main screen or the Flip's cover screen. Never the glasses. */
    BUILT_IN("core_display_origin_built_in", "built-in screen"),
    EXTERNAL("core_display_origin_external", "external display"),
    VIRTUAL("core_display_origin_virtual", "virtual display"),
    ;

    val label: String get() = UiText.get(labelKey, labelEnglish)
}

/** What the app knows about one android.view.Display, copied out so it can be unit tested. */
data class DisplayCandidate(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int = 160,
    val refreshRateHz: Float = 60f,
    val isDefault: Boolean = id == DEFAULT_DISPLAY_ID,
    /** Display.FLAG_PRESENTATION, or listed under DisplayManager.DISPLAY_CATEGORY_PRESENTATION. */
    val isPresentation: Boolean = false,
    val isPrivate: Boolean = false,
    /** Display.isValid and the display is not off. */
    val isOn: Boolean = true,
    /** Display.STATE_DOZE or STATE_DOZE_SUSPEND: an always-on screen such as the cover screen. */
    val isDozing: Boolean = false,
    val type: DisplayType = DisplayType.UNKNOWN,
    val sinkConnection: SinkConnection = SinkConnection.UNKNOWN,
    /** Already there when the app process first looked, with no hotplug event since. */
    val presentAtStart: Boolean = false,
    /** Reported through DisplayManager.DisplayListener.onDisplayAdded while the app was running. */
    val hotplugged: Boolean = false,
    /** From DisplayInfo (canHostTasks); null when the build does not report it. */
    val canHostTasks: Boolean? = null,
    /** FLAG_ALLOWS_CONTENT_MODE_SWITCH: the display can move between mirror and extended. */
    val allowsContentModeSwitch: Boolean? = null,
    /** Samsung DeX is running on this display; null on phones without DeX or when unreadable. */
    val desktopModeActive: Boolean? = null,
    /**
     * One of Pocket XR's own app panel displays (the panel service reported creating this id).
     * Never the glasses, whatever else it looks like (0.4.0).
     */
    val isOwnPanel: Boolean = false,
) {
    val hostingSignature: HostingSignature
        get() = HostingSignature(canHostTasks, desktopModeActive)

    val origin: DisplayOrigin
        get() = DisplaySelector.origin(this)

    /** Name and type, for example "XREAL Aura, external display (external wired (USB-C DisplayPort or HDMI))". */
    val kindLabel: String
        get() = if (type == DisplayType.UNKNOWN) {
            UiText.format("core_display_kind", "%1\$s, %2\$s", name, origin.label)
        } else {
            UiText.format("core_display_kind_type", "%1\$s, %2\$s (%3\$s)", name, origin.label, type.label)
        }

    val summary: String
        get() = UiText.format("core_display_summary", "%1\$s, %2\$dx%3\$d at %4\$d Hz (display %5\$d)", name, widthPx, heightPx, refreshRateHz.toInt(), id)

    companion object {
        const val DEFAULT_DISPLAY_ID = 0
    }
}

/** The glasses connection as the UI shows it. */
sealed interface GlassesState {
    /** Only the phone's own screens exist: glasses unplugged, or the phone is mirroring at a lower level. */
    data class NotConnected(val others: List<DisplayCandidate> = emptyList()) : GlassesState

    data class Connected(val display: DisplayCandidate, val others: List<DisplayCandidate>) : GlassesState
}

/** Picks the display that is most likely the XREAL glasses. */
object DisplaySelector {
    /** Lowercase name fragments that point at XREAL hardware or its USB-C DisplayPort output. */
    val glassesNameHints = listOf("xreal", "nreal", "aura", "beam")

    /** Lowercase name fragments phone makers use for their own panels, including Samsung's cover screen. */
    val builtInNameHints = listOf(
        "built-in", "builtin", "internal", "cover screen", "cover display", "sub screen", "subscreen",
        "sub display", "front screen", "rear screen", "flex window", "main screen", "inner screen",
    )

    /**
     * Classifies a display, strongest evidence first: the platform display type, the
     * DeviceProductInfo sink connection, name hints, a hotplug event, then the fallbacks
     * (dozing like an always-on panel, or present since app start with nothing pointing outside).
     */
    fun origin(display: DisplayCandidate): DisplayOrigin {
        if (display.isDefault) return DisplayOrigin.BUILT_IN
        if (isOwnPanel(display)) return DisplayOrigin.VIRTUAL
        when (display.type) {
            DisplayType.INTERNAL -> return DisplayOrigin.BUILT_IN
            DisplayType.EXTERNAL, DisplayType.WIFI -> return DisplayOrigin.EXTERNAL
            DisplayType.VIRTUAL, DisplayType.OVERLAY -> return DisplayOrigin.VIRTUAL
            DisplayType.UNKNOWN -> Unit
        }
        when (display.sinkConnection) {
            SinkConnection.BUILT_IN -> return DisplayOrigin.BUILT_IN
            SinkConnection.DIRECT, SinkConnection.TRANSITIVE -> return DisplayOrigin.EXTERNAL
            SinkConnection.UNKNOWN -> Unit
        }
        val name = display.name.lowercase()
        if (glassesNameHints.any { it in name }) return DisplayOrigin.EXTERNAL
        // Developer options' simulated secondary display ("Overlay #1"), for emulator testing.
        if (name.startsWith("overlay #")) return DisplayOrigin.VIRTUAL
        if (builtInNameHints.any { it in name }) return DisplayOrigin.BUILT_IN
        if (display.hotplugged) return DisplayOrigin.EXTERNAL
        if (display.isDozing || display.presentAtStart) return DisplayOrigin.BUILT_IN
        return DisplayOrigin.EXTERNAL
    }

    /**
     * Pocket XR's own app panel: reported by the panel service, or named like one. A panel
     * display is trusted, public, hotplugged and may carry the app's name, so without this it
     * could outscore or replace the glasses (0.4.0 guard).
     */
    fun isOwnPanel(display: DisplayCandidate): Boolean =
        display.isOwnPanel || com.hayeco.flipspaces.core.spatial.PanelDisplays.isPanelName(display.name)

    fun isEligible(display: DisplayCandidate): Boolean =
        !isOwnPanel(display) &&
            origin(display) != DisplayOrigin.BUILT_IN &&
            !display.isPrivate && display.isOn && display.widthPx > 0 && display.heightPx > 0

    /**
     * Returns the best external display, or null when there is none.
     * A still eligible [preferredId] (the one the user last used) always wins, so the target
     * does not jump when a second display such as a cast session appears.
     */
    fun select(displays: List<DisplayCandidate>, preferredId: Int? = null): DisplayCandidate? {
        val eligible = displays.filter(::isEligible)
        if (eligible.isEmpty()) return null
        eligible.firstOrNull { it.id == preferredId }?.let { return it }
        return eligible.sortedWith(
            compareByDescending<DisplayCandidate> { score(it) }
                .thenByDescending { it.widthPx.toLong() * it.heightPx }
                .thenBy { it.id },
        ).first()
    }

    fun state(displays: List<DisplayCandidate>, preferredId: Int? = null): GlassesState {
        val chosen = select(displays, preferredId) ?: return GlassesState.NotConnected(displays)
        return GlassesState.Connected(chosen, displays.filter { it.id != chosen.id })
    }

    internal fun score(display: DisplayCandidate): Int {
        val name = display.name.lowercase()
        var score = 0
        if (glassesNameHints.any { it in name }) score += 100
        if (origin(display) == DisplayOrigin.EXTERNAL) score += 20
        if (display.isPresentation) score += 10
        return score
    }
}
