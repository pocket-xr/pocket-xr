package com.hayeco.flipspaces

import android.content.Context
import com.hayeco.flipspaces.core.pointer.PadDirection
import com.hayeco.flipspaces.core.pointer.TrackpadSettings
import com.hayeco.flipspaces.core.spatial.FollowMode

/**
 * 0.5.17: the trackpad's settings, saved as plain numbers: the pad type as its page number, the
 * cursor speed 0..100, the scrollbar side -1 / 0 / 1 and the landscape direction 3 / 4; plus the
 * tracking mode and whether the usage instructions were seen. The keys never change, so saved
 * settings survive updates.
 */
class TrackpadPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trackpad", Context.MODE_PRIVATE)

    /**
     * 1.1.2: the phone is a landscape handheld such as a Retroid, read once. 1.1.9 (issue 33): its screen landscape by
     * nature, or game controls built in ([TrackpadSettings.landscapeHandheld]); a Retroid Nova's screen is portrait by
     * nature, run landscape by its system, so only its controls tell.
     */
    val landscapeDevice: Boolean by lazy {
        val natural = runCatching {
            val d = context.applicationContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
            TrackpadSettings.landscapeByNature(m.widthPixels, m.heightPixels, d.rotation)
        }.getOrDefault(false)
        val gamepad = runCatching {
            android.view.InputDevice.getDeviceIds().any { id ->
                val d = android.view.InputDevice.getDevice(id)
                d != null && !d.isVirtual && !d.isExternal && d.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD)
            }
        }.getOrDefault(false)
        android.util.Log.i("FlipSpaces/Trackpad", "landscape handheld: landscape by nature $natural, built in game controls $gamepad")
        TrackpadSettings.landscapeHandheld(natural, gamepad)
    }

    var settings: TrackpadSettings
        get() = TrackpadSettings(
            mode = TrackpadSettings.modeOf(prefs.getInt(KEY_TYPE, -1), landscapeDevice),
            cursorSpeed = TrackpadSettings.speedOf(prefs.getInt(KEY_SPEED, -1)),
            stripSide = TrackpadSettings.stripOf(prefs.getInt(KEY_STRIP, -1)),
            direction = TrackpadSettings.directionOf(prefs.getInt(KEY_DIRECTION, PadDirection.DEFAULT.stored)),
            gestures = prefs.getBoolean(KEY_GESTURES, false),
            coverControls = TrackpadSettings.stripOf(prefs.getInt(KEY_COVER_CONTROLS, -1)),
            scrollSpeed = TrackpadSettings.scrollSpeedOf(prefs.getInt(KEY_SCROLL_SPEED, -1), TrackpadSettings.DEFAULT_SCROLL_SPEED),
            stripSpeed = TrackpadSettings.scrollSpeedOf(prefs.getInt(KEY_STRIP_SPEED, -1), TrackpadSettings.DEFAULT_STRIP_SPEED),
            oneFingerScroll = prefs.getBoolean(KEY_ONE_FINGER_SCROLL, true),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_TYPE, TrackpadSettings.storedOf(value.mode))
                .putInt(KEY_SPEED, value.cursorSpeed.coerceIn(0, 100))
                .putInt(KEY_STRIP, TrackpadSettings.storedOf(value.stripSide))
                .putInt(KEY_DIRECTION, value.direction.stored)
                .putBoolean(KEY_GESTURES, value.gestures)
                .putInt(KEY_COVER_CONTROLS, TrackpadSettings.storedOf(value.coverControls))
                .putInt(KEY_SCROLL_SPEED, value.scrollSpeed.coerceIn(0, 100))
                .putInt(KEY_STRIP_SPEED, value.stripSpeed.coerceIn(0, 100))
                .putBoolean(KEY_ONE_FINGER_SCROLL, value.oneFingerScroll)
                .apply()
        }

    var follow: FollowMode
        get() = FollowMode.entries.firstOrNull { it.name == prefs.getString(KEY_FOLLOW, null) && it.available } ?: FollowMode.BODY_ANCHOR
        set(value) {
            prefs.edit().putString(KEY_FOLLOW, value.name).apply()
        }

    var introShown: Boolean
        get() = prefs.getBoolean(KEY_INTRO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INTRO, value).apply()
        }

    /**
     * 1.1.9: the glasses models ([com.hayeco.flipspaces.core.spatial.GlassesModel.key]) whose setup page was shown here,
     * or that have none, so a new model plugged in for the first time gets its page once.
     */
    var glassesSetupSeen: Set<String>
        get() = prefs.getStringSet(KEY_GLASSES_SEEN, null)?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_GLASSES_SEEN, value.toSet()).apply()
        }

    /**
     * 1.1.16 (issue 12): the glasses setup check's state per model ([com.hayeco.flipspaces.core.spatial.GlassesCheckMemory]),
     * with 1.1.9's [glassesSetupSeen] models counted as skipped.
     */
    var glassesCheck: Map<String, com.hayeco.flipspaces.core.spatial.CheckState>
        get() = com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.merge(
            com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.decode(prefs.getStringSet(KEY_GLASSES_CHECK, null)?.toSet() ?: emptySet()),
            glassesSetupSeen,
        )
        set(value) {
            prefs.edit().putStringSet(KEY_GLASSES_CHECK, com.hayeco.flipspaces.core.spatial.GlassesCheckMemory.encode(value)).apply()
        }

    /** 0.5.19: the curve new windows open with. 0.5.36: 0 flat to [com.hayeco.flipspaces.core.spatial.PanelCurve.MAX], the Menu's slider. */
    var curveDefault: Float
        get() = prefs.getFloat(KEY_CURVE, 0f).takeIf { it.isFinite() }?.coerceIn(0f, com.hayeco.flipspaces.core.spatial.PanelCurve.MAX) ?: 0f
        set(value) {
            prefs.edit().putFloat(KEY_CURVE, value.coerceIn(0f, com.hayeco.flipspaces.core.spatial.PanelCurve.MAX)).apply()
        }

    /** 0.5.19: the whole Space wrapped round the head as one cylinder. 0.5.36: retired, always off. */
    var wrap: Boolean
        get() = false
        set(value) {
            prefs.edit().putBoolean(KEY_WRAP, value).apply()
        }

    /**
     * 0.5.55: the trackpad drawn at the panel's own pixels where Samsung's size compatibility
     * scales its window ([NativeFrame]); on by default, off on the Menu's Developer card to draw in the window as before.
     */
    var nativeBuffer: Boolean
        get() = prefs.getBoolean(KEY_NATIVE_BUFFER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NATIVE_BUFFER, value).apply()
        }

    /** 0.5.19: the curve settings into [com.hayeco.flipspaces.spatial.SpaceSession], where the Space reads them. */
    fun applyCurve() {
        com.hayeco.flipspaces.spatial.SpaceSession.curveDefault = curveDefault
        com.hayeco.flipspaces.spatial.SpaceSession.wrap = wrap
    }

    fun update(change: (TrackpadSettings) -> TrackpadSettings): TrackpadSettings = change(settings).also { settings = it }

    /** Unreleased: the Menu's Google Play testers card ([com.hayeco.flipspaces.core.PlayTesters]), under its own keys. */
    var playTesters: com.hayeco.flipspaces.core.PlayTesters.State
        get() = com.hayeco.flipspaces.core.PlayTesters.State(
            groupOpened = prefs.getBoolean(KEY_PLAY_TESTERS_GROUP, false),
            snoozedUntil = prefs.getLong(KEY_PLAY_TESTERS_SNOOZE, 0L),
            done = prefs.getBoolean(KEY_PLAY_TESTERS_DONE, false),
        )
        set(value) {
            prefs.edit()
                .putBoolean(KEY_PLAY_TESTERS_GROUP, value.groupOpened)
                .putLong(KEY_PLAY_TESTERS_SNOOZE, value.snoozedUntil)
                .putBoolean(KEY_PLAY_TESTERS_DONE, value.done)
                .apply()
        }

    companion object {
        private const val KEY_TYPE = "TrackpadType"
        private const val KEY_SPEED = "TrackpadSpeed"
        private const val KEY_STRIP = "TrackpadSliderPosition"
        private const val KEY_DIRECTION = "TouchpadLandscapeOrientation"
        private const val KEY_GESTURES = "Gestures"
        private const val KEY_FOLLOW = "FollowMode"
        private const val KEY_INTRO = "IntroShown"
        private const val KEY_CURVE = "CurveDefault"
        private const val KEY_WRAP = "CurveWrap"
        private const val KEY_NATIVE_BUFFER = "NativeBuffer"
        private const val KEY_GLASSES_SEEN = "GlassesSetupSeen"
        private const val KEY_GLASSES_CHECK = "GlassesCheckState"

        /** 0.5.17 cover screen: the window rail's side, stored like the scrollbar's (0 left, else right). */
        private const val KEY_COVER_CONTROLS = "CoverControlsSide"

        /** 0.5.83: the two finger scroll's and the thin strip's speeds. */
        private const val KEY_SCROLL_SPEED = "ScrollSpeed"
        private const val KEY_STRIP_SPEED = "StripSpeed"

        /** 1.1.10 (issue 35): one finger scrolls on Spatial Mouse. */
        private const val KEY_ONE_FINGER_SCROLL = "OneFingerScroll"

        /** Unreleased: the Google Play testers card. */
        private const val KEY_PLAY_TESTERS_GROUP = "PlayTestersGroupOpened"
        private const val KEY_PLAY_TESTERS_SNOOZE = "PlayTestersSnoozedUntil"
        private const val KEY_PLAY_TESTERS_DONE = "PlayTestersDone"
    }
}
