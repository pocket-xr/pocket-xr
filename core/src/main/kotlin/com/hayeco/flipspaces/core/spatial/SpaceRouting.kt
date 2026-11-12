package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.UiText

/** What to put on the glasses when they come up (plug-in, or the glasses' secondary home). */
enum class GlassesSurface {
    /** The Space, full screen: Spatial or Head locked. */
    SPACE,

    /** The flat DeX home, with a banner that says why the Space is not available. */
    FLAT_HOME,
}

/** The one-tap fix a flat mode banner offers. */
enum class FlatFix(private val key: String, private val english: String) {
    OPEN_SHIZUKU("spatial_fix_open_shizuku", "Open Shizuku"),
    ALLOW_SHIZUKU("spatial_fix_allow_shizuku", "Allow Pocket XR in Shizuku"),
    GET_SHIZUKU("spatial_fix_get_shizuku", "Get Shizuku"),
    ;

    /** The button's words, in the phone's language. */
    val buttonLabel: String get() = UiText.get(key, english)
}

/** Why the glasses show the flat DeX home instead of the Space, and the fix (null: none to offer). */
data class FlatBanner(val title: String, val reason: String, val fix: FlatFix?) {
    companion object {
        /** The banner for [shizuku]; null when Shizuku is ready (nothing to fix). */
        fun of(shizuku: ShizukuStatus): FlatBanner? = when (shizuku) {
            ShizukuStatus.READY -> null
            ShizukuStatus.NOT_RUNNING -> FlatBanner(
                UiText.get("spatial_flat_not_running", "Flat mode: Shizuku is not running"),
                UiText.get("spatial_flat_not_running_reason", "App panels need Shizuku. Open Shizuku on the phone and tap Start (needed after each reboot)."),
                FlatFix.OPEN_SHIZUKU,
            )
            ShizukuStatus.NO_PERMISSION -> FlatBanner(
                UiText.get("spatial_flat_no_permission", "Flat mode: Pocket XR is not allowed in Shizuku"),
                UiText.get("spatial_flat_no_permission_reason", "Shizuku is running but has not allowed Pocket XR yet. Allow it in the Shizuku prompt on the phone."),
                FlatFix.ALLOW_SHIZUKU,
            )
            ShizukuStatus.NOT_INSTALLED -> FlatBanner(
                UiText.get("spatial_flat_not_installed", "Flat mode: Shizuku is not installed"),
                UiText.get("spatial_flat_not_installed_reason", "App panels need Shizuku from Google Play, started with Wireless debugging."),
                FlatFix.GET_SHIZUKU,
            )
        }
    }
}

/** Where one app launch goes. */
enum class AppRoute {
    /** The Space is up: the app becomes a panel (SpaceCommand.AddApp). */
    PANEL,

    /** The Space is possible but not open: open it, with the app queued as its first new panel. */
    OPEN_SPACE_THEN_PANEL,

    /** Flat mode: a DeX window through the M1 launch route. */
    DEX_WINDOW,

    /** No glasses display to open anything on. */
    NO_GLASSES,
}

/** Where a launch came from, for the log line. */
enum class LaunchSource { PHONE_LIST, GLASSES_DOCK, SPATIAL_SCREEN, SPACE_LAUNCHER, RECENTS, QUEUED }

/**
 * The mode decisions of 0.4.0-launcher in one place: what the glasses show when they come up,
 * and where each app launch goes. DeX windows only ever come from flat mode.
 */
object SpaceRouter {
    fun surfaceFor(choice: ModeChoice): GlassesSurface =
        if (choice.mode == SpatialMode.FLAT) GlassesSurface.FLAT_HOME else GlassesSurface.SPACE

    fun route(choice: ModeChoice, glassesConnected: Boolean, spaceRunning: Boolean): AppRoute = when {
        !glassesConnected -> AppRoute.NO_GLASSES
        choice.mode == SpatialMode.FLAT -> AppRoute.DEX_WINDOW
        spaceRunning -> AppRoute.PANEL
        else -> AppRoute.OPEN_SPACE_THEN_PANEL
    }

    /** The banner shown with the flat home; null outside flat mode. */
    fun bannerFor(choice: ModeChoice, shizuku: ShizukuStatus): FlatBanner? =
        if (choice.mode == SpatialMode.FLAT) FlatBanner.of(shizuku) else null
}

/** One mode decision, written as a single logcat line (tag FlipSpaces/Mode). */
data class ModeDecision(val where: String, val choice: ModeChoice, val outcome: String) {
    fun line(): String {
        val reasons = choice.reasons.ifEmpty { listOf("everything needed is there") }.joinToString(" | ")
        return "$where: ${choice.mode.name} -> $outcome; reasons: $reasons"
    }
}
