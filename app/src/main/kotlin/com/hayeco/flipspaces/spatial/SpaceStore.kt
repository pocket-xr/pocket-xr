package com.hayeco.flipspaces.spatial

import android.content.Context
import com.hayeco.flipspaces.core.spatial.AxisMap
import com.hayeco.flipspaces.core.spatial.LayoutCodec
import com.hayeco.flipspaces.core.spatial.SpaceLayout

/** Saved Space layout and settings. */
class SpaceStore(context: Context) {
    /** 1.2.10: for the USB device list (VITURE defaults by product); the application, never a screen. */
    private val appContext: Context = context.applicationContext

    /**
     * 0.5.9: the glasses' optics for the field of view setting.
     *
     * 0.5.26 (Mike: "2D doesn't use the Aura's full field of view"): the optics of the glasses'
     * screen in the mode in use ([screen]): their own 1:1 calibration times the screen size they show the picture at,
     * not XREAL's 70 degree figure. [sbs] is the glasses' 3D mode (a 3840 x 1080 picture, "2D in 3D" too).
     */
    fun optics(sbs: Boolean = false): com.hayeco.flipspaces.core.spatial.GlassesOptics =
        com.hayeco.flipspaces.core.spatial.GlassesOptics.screen(model, screen(sbs).sizeFactor)

    /**
     * 0.5.91: the model of the glasses the Space last opened on ([glasses], from their
     * EDID), so the optics, the screen sizes and hand tracking follow the glasses, not the Aura.
     */
    val model: com.hayeco.flipspaces.core.spatial.GlassesModel
        get() = com.hayeco.flipspaces.core.spatial.GlassesModel.detect(glasses, xrealUsbProduct())

    private val usb = context.applicationContext.getSystemService(android.hardware.usb.UsbManager::class.java)
    private var usbProduct: Int? = null
    private var usbReadAtMs = Long.MIN_VALUE / 2

    /**
     * 1.1.0: the product id of the XREAL glasses on USB, for [model] when the display's name does not say which they
     * are (an Honor Magic V5 named a One Pro's display itself). Read again at most every [USB_READ_MS].
     */
    private fun xrealUsbProduct(): Int? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - usbReadAtMs < USB_READ_MS) return usbProduct
        usbReadAtMs = now
        usbProduct = runCatching {
            usb?.deviceList?.values?.firstOrNull { com.hayeco.flipspaces.core.hands.GlassesUsb.isXreal(it.vendorId) }?.productId
        }.getOrNull()
        return usbProduct
    }

    /** 0.5.91: the Aura keeps its keys from 0.5.26; every other model has its own screen sizes. */
    private fun screenKey(sbs: Boolean): String = perModel(if (sbs) KEY_SCREEN_3D else KEY_SCREEN_2D)

    /** 0.5.26: the glasses' screen in their 2D mode or their 3D mode ([sbs]), as saved. */
    fun screen(sbs: Boolean): com.hayeco.flipspaces.core.spatial.GlassesScreen {
        // 0.5.27: their 3D mode at 100 % by default, the full view.
        // 0.5.91: 2D's default is the model's (the Aura's measured 0.80; the full view on the others).
        val d = if (sbs) com.hayeco.flipspaces.core.spatial.GlassesScreen.DEFAULT_3D else model.default2D
        val key = screenKey(sbs)
        if (!prefs.contains(key)) return d
        val from = prefs.getString(key + "_from", null) ?: "set on the phone"
        return d.copy(sizeFactor = prefs.getFloat(key, d.sizeFactor), from = from)
    }

    /**
     * 0.5.27: the per-eye picture adjust ([com.hayeco.flipspaces.core.spatial.PictureAdjust]) for the glasses the Space
     * last ran on ([glasses]), saved under their key; nothing saved is no adjust.
     */
    var pictureAdjust: com.hayeco.flipspaces.core.spatial.PictureAdjust
        get() = com.hayeco.flipspaces.core.spatial.PictureAdjust.decode(prefs.getString(pictureAdjustKey(), null))
        set(value) = prefs.edit().putString(pictureAdjustKey(), value.encode()).apply()

    /** 0.5.59: the saved adjust for these glasses removed (it has had no Menu since 0.5.36). */
    fun clearPictureAdjust() = prefs.edit().remove(pictureAdjustKey()).apply()

    private fun pictureAdjustKey(): String = KEY_PICTURE_ADJUST + (glasses?.key ?: UNNAMED_GLASSES)

    /** 0.5.27: whether the size for [sbs] was set (on the phone or from the glasses' log), not only the default. */
    fun screenSet(sbs: Boolean): Boolean = prefs.contains(screenKey(sbs))

    /** 0.5.26: the glasses' screen size factor in 2D or 3D ([sbs]); [from] says where it came from (the setting, or their log). */
    fun setScreen(sbs: Boolean, factor: Float, from: String) {
        val key = screenKey(sbs)
        prefs.edit()
            .putFloat(key, factor.coerceIn(com.hayeco.flipspaces.core.spatial.GlassesScreen.MIN_FACTOR, com.hayeco.flipspaces.core.spatial.GlassesScreen.MAX_FACTOR))
            .putString(key + "_from", from)
            .apply()
    }

    private val prefs = context.applicationContext.getSharedPreferences("space", Context.MODE_PRIVATE)

    /**
     * 0.5.101 (Mike: each settings screen "a way to reset to Pocket XR default settings"): Spatial windows' settings back to
     * their defaults: the screen sizes of the glasses in use, 2D or 3D, the eye distance, the hand pointer and the plain
     * display test. The layout, presets, the glasses' calibration and their head axes are kept.
     */
    fun resetSpatialSettings() {
        val e = prefs.edit()
        for (sbs in listOf(false, true)) {
            e.remove(screenKey(sbs)).remove(screenKey(sbs) + "_from")
        }
        e.remove(KEY_VIEW).remove(KEY_IPD).remove(KEY_HANDS_STEER).remove(KEY_PLAIN_DISPLAY)
            .remove(KEY_SEE_THROUGH).remove(KEY_DEX_FULL_SCREEN).remove(KEY_WINDOW_GLOW)
            .remove(KEY_WINDOW_GLOW_COLOR).apply()
    }

    // 0.5.36 (Mike: Further/Closer and Curve should act on the selected window only): the wraparound is retired, so a
    // layout saved wrapped comes back unwrapped, each window at its own distance and curve.
    fun layout(): SpaceLayout = LayoutCodec.decode(prefs.getString(KEY_LAYOUT, null)).let { l ->
        if (l.wrap == null) l else com.hayeco.flipspaces.core.spatial.SpaceWrap.off(l, SpaceSession.curveDefault).also { saveLayout(it) }
    }

    fun saveLayout(layout: SpaceLayout) {
        prefs.edit().putString(KEY_LAYOUT, LayoutCodec.encode(layout)).apply()
    }

    // 0.5.91 (Mike's One: "everything is upside down"): per model, its default until one is picked; the Aura keeps its key.
    // 1.2.10 (Discussion #24): VITURE glasses' default by the USB product plugged in, so the Beast starts the right way up.
    var axisMap: AxisMap
        get() = prefs.getString(perModel(KEY_AXES), null)?.let { name -> AxisMap.entries.firstOrNull { it.name == name } } ?: defaultAxes()
        set(value) = prefs.edit().putString(perModel(KEY_AXES), value.name).remove(perModel(KEY_AXES_GRAVITY)).apply()

    /**
     * 1.2.13 (Discussion #81, glasses whose axes are a quarter or half turn off): the layout the gravity check picked
     * ([com.hayeco.flipspaces.core.spatial.GravityAxes]), saved per model as a pick on the head tracking screen is, with
     * its line so the Glasses report says why. A pick by hand afterwards clears the line.
     */
    fun setAxesByGravity(axes: AxisMap, line: String) {
        prefs.edit().putString(perModel(KEY_AXES), axes.name).putString(perModel(KEY_AXES_GRAVITY), line).apply()
    }

    /** 1.2.13: the model's own default layout, what the gravity check prefers when it fits. */
    val modelAxes: AxisMap get() = defaultAxes()

    /** 1.2.13: where [axisMap] came from, in words, for the Glasses report. */
    fun axesFrom(): String {
        val gravity = prefs.getString(perModel(KEY_AXES_GRAVITY), null)
        return when {
            gravity != null -> "set by the gravity check: $gravity"
            prefs.contains(perModel(KEY_AXES)) -> "picked on the head tracking screen; the ${model.label}'s default is ${defaultAxes().label}"
            else -> "the ${model.label}'s default"
        }
    }

    private fun defaultAxes(): AxisMap {
        val m = model
        if (m != com.hayeco.flipspaces.core.spatial.GlassesModel.VITURE) return m.defaultAxes
        return com.hayeco.flipspaces.core.spatial.VitureDevices.defaultAxes(VitureImuLink.find(appContext)?.productId)
    }

    /** 0.5.95: how many sessions the model's setup hint ([com.hayeco.flipspaces.core.spatial.GlassesModel.setupHint]) was said in. */
    // 1.1.8 (issue 31): a new key, so the hint that now names Follow mode is said again on glasses that heard the old one.
    var setupHintSaid: Int
        get() = prefs.getInt("setup_hint2_said_" + model.key, 0)
        set(value) = prefs.edit().putInt("setup_hint2_said_" + model.key, value).apply()

    /** 0.5.91: [key] for the glasses' model: the Aura keeps the key it always had, every other model has its own. */
    private fun perModel(key: String): String {
        val m = model
        return if (m == com.hayeco.flipspaces.core.spatial.GlassesModel.AURA) key else key + "_" + m.key
    }

    /**
     * Diagonal field of view of the glasses, degrees. Aura is quoted at 70. 0.5.9: the diagonal of
     * the glasses' 1920 x 1200 panel ([com.hayeco.flipspaces.core.spatial.GlassesOptics]), not of the Space's surface.
     * 0.5.26: no longer the projection ([optics] takes the glasses' screen); kept for older saves only.
     */
    var diagonalFov: Float
        get() = prefs.getFloat(KEY_FOV, 70f)
        set(value) = prefs.edit().putFloat(KEY_FOV, value.coerceIn(30f, 100f)).apply()

    /**
     * Black is see-through on the glasses; dark grey hid the room. 0.5.101 (Mike's settings audit): always black, the
     * switch is gone (grey only looked like a haze on the glasses).
     */
    val seeThrough: Boolean get() = true

    /** Hands steer the ray; when off, look at a target and pinch anywhere to click. */
    var handsSteer: Boolean
        get() = prefs.getBoolean(KEY_HANDS_STEER, true)
        set(value) = prefs.edit().putBoolean(KEY_HANDS_STEER, value).apply()

    /**
     * 0.5.9: draw side by side 3D (one half per eye) when the glasses send a 3840 x 1080 mode, and
     * ask for that mode when the glasses offer it ([com.hayeco.flipspaces.core.spatial.StereoLayout]).
     */
    var stereo: Boolean
        get() = view == com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D
        set(value) {
            view = if (value) com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D else com.hayeco.flipspaces.core.spatial.GlassesView.FLAT_2D
        }

    /**
     * 0.5.14: 3D side by side or 2D on the glasses, remembered. A new key, so every phone starts in
     * side by side (Mike's decision) whatever the 0.5.9 setting was.
     */
    var view: com.hayeco.flipspaces.core.spatial.GlassesView
        // 0.5.27 (Mike: the full field of view by default): a new key, so every phone starts
        // in 2D, which in the glasses' 3D mode is "2D in 3D", the same picture in both eyes at the glasses' full view
        // and the steadiest picture (the phone's late warp). The launcher's 3D / 2D button still switches, remembered.
        // 0.5.107 (Mike: "Yes, default to 3D", after a reset left his Aura in 2D at plug-in): 3D side by side by default on
        // glasses whose 3D switch Pocket XR drives itself and has seen work (the Aura and the One); 2D on the others.
        get() = prefs.getString(KEY_VIEW, null)?.let { com.hayeco.flipspaces.core.spatial.GlassesView.parse(it) } ?: defaultView

        set(value) = prefs.edit().putString(KEY_VIEW, value.name).apply()

    /** 0.5.107: the view with nothing saved: 3D on the Aura and the One, 2D on glasses not seen switching. */
    val defaultView: com.hayeco.flipspaces.core.spatial.GlassesView
        get() = if (model == com.hayeco.flipspaces.core.spatial.GlassesModel.AURA || model == com.hayeco.flipspaces.core.spatial.GlassesModel.ONE) {
            com.hayeco.flipspaces.core.spatial.GlassesView.SBS_3D
        } else {
            com.hayeco.flipspaces.core.spatial.GlassesView.FLAT_2D
        }

    /** 0.5.9: the distance between the eyes for side by side 3D, millimetres. */
    var ipdMm: Float
        get() = prefs.getFloat(KEY_IPD, com.hayeco.flipspaces.core.spatial.StereoLayout.DEFAULT_IPD_MM)
        set(value) = prefs.edit().putFloat(KEY_IPD, value.coerceIn(com.hayeco.flipspaces.core.spatial.StereoLayout.MIN_IPD_MM, com.hayeco.flipspaces.core.spatial.StereoLayout.MAX_IPD_MM)).apply()

    /** 1.2.2: the focused window's glow, 0 (off) to 100 % (the look it always had) ([com.hayeco.flipspaces.core.spatial.WindowGlow]). */
    var windowGlowPercent: Int
        get() = com.hayeco.flipspaces.core.spatial.WindowGlow.clamp(prefs.getInt(KEY_WINDOW_GLOW, com.hayeco.flipspaces.core.spatial.WindowGlow.DEFAULT_PERCENT))
        set(value) = prefs.edit().putInt(KEY_WINDOW_GLOW, com.hayeco.flipspaces.core.spatial.WindowGlow.clamp(value)).apply()

    /** 1.2.2: the focused window's glow colour, opaque ARGB, the accent blue unless changed ([com.hayeco.flipspaces.core.spatial.WindowGlow.PRESETS]). */
    var windowGlowColor: Int
        get() = com.hayeco.flipspaces.core.spatial.WindowGlow.color(prefs.getInt(KEY_WINDOW_GLOW_COLOR, com.hayeco.flipspaces.core.spatial.WindowGlow.DEFAULT_COLOR))
        set(value) = prefs.edit().putInt(KEY_WINDOW_GLOW_COLOR, com.hayeco.flipspaces.core.spatial.WindowGlow.color(value)).apply()

    /**
     * 0.5.9: press DeX's own full screen button on the Space's window, hiding the taskbar and the title bar. 0.5.101 (Mike's
     * settings audit): always; off only left the Space in a DeX window with a taskbar, so its switch is gone.
     */
    val dexFullScreen: Boolean get() = true

    /**
     * 0.5.9, experimental: run the glasses as a plain display with DeX off for the Space's session
     * ([com.hayeco.flipspaces.core.spatial.PlainDisplayPlan]); the settings are put back afterwards.
     */
    var plainDisplay: Boolean
        get() = prefs.getBoolean(KEY_PLAIN_DISPLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAIN_DISPLAY, value).apply()

    /**
     * 0.5.10: the glasses' own display delay added to the measured pipeline (the frame pacer's two
     * frames). Up to 0.5.9 this was the whole prediction (20 ms); a new key, so the old value does
     * not come back as an extra 20 ms on top of the pipeline.
     */
    var predictMs: Int
        get() = prefs.getInt(KEY_PREDICT_EXTRA, 8)
        set(value) = prefs.edit().putInt(KEY_PREDICT_EXTRA, value.coerceIn(0, 40)).apply()

    /**
     * 0.5.11: the gyro bias the static detector last learned (rad/s, head frame), so a new session
     * starts with it instead of learning it from scratch. Null before the first.
     */
    // 0.5.91: the biases, like the axes and mount, are kept per model (the One started from the Aura's).
    var gyroBias: com.hayeco.flipspaces.core.spatial.Vec3?
        get() = vec(perModel(KEY_GYRO_BIAS))
        set(value) = putVec(perModel(KEY_GYRO_BIAS), value)

    /**
     * 0.5.15: the bias the static detector last measured, and a worn bias
     * saved only while it is near that one ([com.hayeco.flipspaces.core.spatial.BiasSavePolicy]).
     * New keys: 0.5.14 saved its walking worn bias to [gyroBias] every minute (Mike's last one was
     * 0.77 deg/s off on y), so that value is not used as a seed again.
     */
    var staticGyroBias: com.hayeco.flipspaces.core.spatial.Vec3?
        get() = vec(perModel(KEY_GYRO_BIAS_STATIC))
        set(value) = putVec(perModel(KEY_GYRO_BIAS_STATIC), value)

    var wornGyroBias: com.hayeco.flipspaces.core.spatial.Vec3?
        get() = vec(perModel(KEY_GYRO_BIAS_WORN))
        set(value) = putVec(perModel(KEY_GYRO_BIAS_WORN), value)

    private fun vec(key: String): com.hayeco.flipspaces.core.spatial.Vec3? =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 && it.all { v -> v.isFinite() } }
            ?.let { com.hayeco.flipspaces.core.spatial.Vec3(it[0], it[1], it[2]) }

    private fun putVec(key: String, value: com.hayeco.flipspaces.core.spatial.Vec3?) =
        prefs.edit().putString(key, value?.let { "${it.x},${it.y},${it.z}" }).apply()

    /**
     * 0.5.15: the IMU to display mount from the level calibration
     * ([com.hayeco.flipspaces.core.spatial.LevelCalibration]), degrees; 0 until one is saved.
     */
    // 0.5.91: per model (the One was tilted by the Aura's 10.65 degree pitch mount); the Aura keeps its keys.
    var mountRollDeg: Float
        get() = prefs.getFloat(perModel(KEY_MOUNT_ROLL), 0f)
        set(value) = prefs.edit().putFloat(perModel(KEY_MOUNT_ROLL), value).apply()

    var mountPitchDeg: Float
        get() = prefs.getFloat(perModel(KEY_MOUNT_PITCH), 0f)
        set(value) = prefs.edit().putFloat(perModel(KEY_MOUNT_PITCH), value).apply()

    val levelCalibrated: Boolean get() = prefs.contains(perModel(KEY_MOUNT_ROLL))

    fun clearLevel() {
        prefs.edit().remove(perModel(KEY_MOUNT_ROLL)).remove(perModel(KEY_MOUNT_PITCH)).apply()
    }

    /**
     * 0.5.12: the display delay calibration's last runs (ms), and whether [predictMs]
     * is a calibrated value rather than 0.5.11's 8 ms guess.
     */
    var delayRuns: List<Float>
        get() = com.hayeco.flipspaces.core.spatial.DisplayDelayCalibration.parse(prefs.getString(KEY_DELAY_RUNS, null))
        set(value) = prefs.edit().putString(KEY_DELAY_RUNS, com.hayeco.flipspaces.core.spatial.DisplayDelayCalibration.format(value)).apply()

    var delayCalibrated: Boolean
        get() = prefs.getBoolean(KEY_DELAY_CALIBRATED, false)
        set(value) = prefs.edit().putBoolean(KEY_DELAY_CALIBRATED, value).apply()

    /**
     * 0.5.13: the calibration's runs and results per glasses
     * ([com.hayeco.flipspaces.core.spatial.GlassesDelayProfiles]), and the glasses the Space last
     * ran on, which the head tracking screen saves runs for.
     */
    var delayProfiles: com.hayeco.flipspaces.core.spatial.GlassesDelayProfiles
        get() = com.hayeco.flipspaces.core.spatial.GlassesDelayProfiles.decode(prefs.getString(KEY_DELAY_PROFILES, null))
        set(value) = prefs.edit().putString(KEY_DELAY_PROFILES, value.encode()).apply()

    var glasses: com.hayeco.flipspaces.core.spatial.GlassesId?
        get() = prefs.getString(KEY_GLASSES, null)?.let { com.hayeco.flipspaces.core.spatial.GlassesId.fromKey(it) }
        set(value) = prefs.edit().putString(KEY_GLASSES, value?.key).apply()

    /** Which glasses [predictMs] was calibrated on (their key); null for 0.5.12's unkeyed value. */
    private var calibratedFor: String?
        get() = prefs.getString(KEY_CALIBRATED_FOR, null)
        set(value) = prefs.edit().putString(KEY_CALIBRATED_FOR, value).apply()

    /**
     * The Space opened on [id]: their saved delay is used if they have one. A delay calibrated on
     * other glasses is not, and the 8 ms guess comes back. Returns the log line, or null when
     * nothing changed.
     */
    fun useCalibrationFor(id: com.hayeco.flipspaces.core.spatial.GlassesId?): String? {
        if (id == null) return "display delay: the glasses display gives no EDID name, so no calibration is looked up (${predictMs} ms in use)"
        glasses = id
        val saved = delayProfiles.calibratedMs(id)
        return when {
            saved != null -> {
                predictMs = kotlin.math.round(saved).toInt()
                delayCalibrated = true
                calibratedFor = id.key
                "display delay: %.1f ms, calibrated for ${id.label()}".format(java.util.Locale.ROOT, saved)
            }
            delayCalibrated && calibratedFor != id.key -> {
                predictMs = com.hayeco.flipspaces.core.spatial.DisplayDelayCalibration.GUESS_MS
                delayCalibrated = false
                "display delay: nothing calibrated for ${id.label()} yet (the saved value was for ${calibratedFor?.let { com.hayeco.flipspaces.core.spatial.GlassesId.fromKey(it)?.label() } ?: "glasses not named"}); the 8 ms guess until a calibration is saved"
            }
            else -> null
        }
    }

    /** 0.5.13: one calibration run for the glasses in use; three agreeing runs set and save the delay for them. */
    fun saveDelayRun(): Pair<com.hayeco.flipspaces.core.spatial.DisplayDelayCalibration.Result?, String> {
        val id = glasses ?: com.hayeco.flipspaces.core.spatial.GlassesId(UNNAMED_GLASSES, "")
        val profiles = delayProfiles
        val out = profiles.record(id, predictMs.toFloat())
        delayProfiles = profiles
        delayRuns = profiles.runs(id)
        val r = out.first
        if (r != null && r.repeatable) {
            predictMs = r.wholeMs
            delayCalibrated = true
            calibratedFor = id.key
        }
        return out
    }

    fun clearDelayRuns() {
        val id = glasses ?: com.hayeco.flipspaces.core.spatial.GlassesId(UNNAMED_GLASSES, "")
        val profiles = delayProfiles
        profiles.clearRuns(id)
        delayProfiles = profiles
        delayRuns = emptyList()
    }

    /** A delay set by hand: it is no longer the calibrated one for these glasses. */
    fun delaySetByHand() {
        delayCalibrated = false
        val id = glasses ?: return
        val profiles = delayProfiles
        profiles.forget(id)
        delayProfiles = profiles
    }

    /** 0.5.12: draw each frame just before its deadline; false draws at frame start as 0.5.11 did. */
    var lateDraw: Boolean
        get() = prefs.getBoolean(KEY_LATE_DRAW, true)
        set(value) = prefs.edit().putBoolean(KEY_LATE_DRAW, value).apply()

    /** 0.5.21: Wide view's per app memory, as [com.hayeco.flipspaces.core.spatial.WideViewStore] text (the apps it is on for). */
    var wideView: String?
        get() = prefs.getString(KEY_WIDE_VIEW, null)
        set(value) = prefs.edit().putString(KEY_WIDE_VIEW, value).apply()

    /**
     * 0.5.22: the packages whose own OVERRIDE_ANY_ORIENTATION_TO_USER state Pocket XR must still put back,
     * as [com.hayeco.flipspaces.core.spatial.WideViewOverride] text, so a crash or reboot leaves nothing behind.
     */
    var wideViewOverride: String?
        get() = prefs.getString(KEY_WIDE_VIEW_OVERRIDE, null)
        set(value) = prefs.edit().putString(KEY_WIDE_VIEW_OVERRIDE, value).apply()

    /** 0.5.24: each app's own density setting (Fit video), as [com.hayeco.flipspaces.core.spatial.AppDensityStore] text. */
    var appDensity: String?
        get() = prefs.getString(KEY_APP_DENSITY, null)
        set(value) = prefs.edit().putString(KEY_APP_DENSITY, value).apply()

    /** 1.2.3 (issue 57): each app's own text size, as [com.hayeco.flipspaces.core.spatial.AppTextSizeStore] text. */
    var appTextSize: String?
        get() = prefs.getString(KEY_APP_TEXT_SIZE, null)
        set(value) = prefs.edit().putString(KEY_APP_TEXT_SIZE, value).apply()

    /** 0.5.21: the "Glasses warp (beta)" switch, off until Mike turns it on; remembered. */
    var glassesWarp: Boolean
        get() = prefs.getBoolean(KEY_GLASSES_WARP, false)
        set(value) = prefs.edit().putBoolean(KEY_GLASSES_WARP, value).apply()

    /**
     * 0.5.13: a pose per row (top and bottom row, blended per vertex); false is one
     * pose for the whole picture, for glasses found to light all rows at once.
     */
    var rowPose: Boolean
        get() = prefs.getBoolean(KEY_ROW_POSE, true)
        set(value) = prefs.edit().putBoolean(KEY_ROW_POSE, value).apply()

    /**
     * 0.5.15: the per-row pose in side by side 3D, off until it is checked
     * on the Aura. At 60 Hz it shears the picture by about 1.6 degrees top to bottom at 100 deg/s,
     * which reads as a tilt on every turn if the Aura does not scan out row by row. [rowPose] stays
     * the 2D setting.
     *
     * 0.5.25 (at 60 Hz the rows light over about 15 ms, so a turning head shears the
     * windows by about 0.15 degrees at 10 deg/s, which 2D corrects): on by default, with the 60 Hz row model. A new
     * key, so the 0.5.15 default off is not carried over; the head tracking screen still turns it off.
     */
    // 0.5.36 (Mike: always on, users wouldn't turn it off; its Menu row is gone): always true.
    var rowPoseSbs: Boolean
        get() = true
        set(value) = prefs.edit().putBoolean(KEY_ROW_POSE_SBS, value).apply()

    /**
     * 0.5.29: the glasses' 3D screen distance, metres (their default 4 m until set on the
     * Menu or read from their log), and where it came from.
     */
    val glassesDistanceM: Float
        get() = prefs.getFloat(KEY_DISTANCE_3D, com.hayeco.flipspaces.core.spatial.ScreenDistance.DEFAULT_M)
    val glassesDistanceFrom: String
        get() = prefs.getString(KEY_DISTANCE_3D + "_from", null) ?: "their default"

    fun setGlassesDistance(m: Float, from: String) {
        prefs.edit()
            .putFloat(KEY_DISTANCE_3D, m.coerceIn(com.hayeco.flipspaces.core.spatial.ScreenDistance.MIN_M, com.hayeco.flipspaces.core.spatial.ScreenDistance.MAX_M))
            .putString(KEY_DISTANCE_3D + "_from", from)
            .apply()
    }

    /** 0.5.30: the Space's debug info panel below the view (Developer on the Menu); off by default. */
    var debugInfo: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_INFO, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_INFO, value).apply()

    /** 0.5.32: the still head's lock ([com.hayeco.flipspaces.core.spatial.RestLock]); on by default, off on the Developer card for an A/B. */
    var stillLock: Boolean
        get() = prefs.getBoolean(KEY_STILL_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_STILL_LOCK, value).apply()

    /**
     * 0.5.65: the glasses switched to the view the Space wants (3D side by side or 2D) over their control
     * link at plug-in; on by default.
     */
    var autoGlassesView: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GLASSES_VIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_GLASSES_VIEW, value).apply()

    /**
     * 1.2.8 (Discussion #24): the experimental VITURE reader ([com.hayeco.flipspaces.core.spatial.VitureImuProtocol]),
     * off by default: it claims the glasses' HID interface and sends a command no VITURE glasses have taken from Pocket XR yet.
     *
     * 1.2.12 (Discussion #24, third report): with nothing saved, on for the VITURE Beast and off on other models
     * ([com.hayeco.flipspaces.core.spatial.VitureDevices.headTrackingOn]). The switch saves on and off alike, under the
     * key it always had, so a Beast user who turned it off keeps it off.
     */
    var vitureHeadTracking: Boolean
        get() = com.hayeco.flipspaces.core.spatial.VitureDevices.headTrackingOn(
            if (prefs.contains(KEY_VITURE_HEAD_TRACKING)) prefs.getBoolean(KEY_VITURE_HEAD_TRACKING, false) else null,
            vitureProduct,
        )
        set(value) = prefs.edit().putBoolean(KEY_VITURE_HEAD_TRACKING, value).apply()

    /**
     * 1.2.12: the USB product of the VITURE glasses plugged in, else of the last ones seen (null before any), so the
     * switch keeps the Beast's default and name while they are unplugged.
     */
    val vitureProduct: Int?
        get() {
            val saved = prefs.getInt(KEY_VITURE_PRODUCT, -1).takeIf { it >= 0 }
            val now = VitureImuLink.find(appContext)?.productId ?: return saved
            if (now != saved) prefs.edit().putInt(KEY_VITURE_PRODUCT, now).apply()
            return now
        }

    /**
     * 1.2.8: the VITURE reader's two axis switches ([com.hayeco.flipspaces.core.spatial.VitureAxes]). 1.2.10: until set,
     * the model's ([com.hayeco.flipspaces.core.spatial.VitureDevices.defaultSwitches]), both off.
     */
    var vitureAxes: com.hayeco.flipspaces.core.spatial.VitureAxes
        get() {
            val d = com.hayeco.flipspaces.core.spatial.VitureDevices.defaultSwitches(VitureImuLink.find(appContext)?.productId)
            return com.hayeco.flipspaces.core.spatial.VitureAxes(prefs.getBoolean("viture_flip_up_down", d.flipUpDown), prefs.getBoolean("viture_swap_turn_tilt", d.swapTurnTilt))
        }
        set(value) = prefs.edit().putBoolean("viture_flip_up_down", value.flipUpDown).putBoolean("viture_swap_turn_tilt", value.swapTurnTilt).apply()

    /** 0.5.65: the glasses' factory gyro matrix and display mount in the fusion (Developer); on by default. */
    var factoryCalibration: Boolean
        get() = prefs.getBoolean(KEY_FACTORY_CALIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_FACTORY_CALIBRATION, value).apply()

    /**
     * 1.2.13 (Discussion #81): Recenter follows head tilt, on by default. A recentre with the head more than 15 degrees
     * up or down puts the windows where it looks; off keeps them on the horizon ([com.hayeco.flipspaces.core.spatial.FusionSettings.recentreFollowsTilt]).
     */
    var recentreFollowsTilt: Boolean
        get() = prefs.getBoolean(KEY_RECENTRE_FOLLOWS_TILT, true)
        set(value) = prefs.edit().putBoolean(KEY_RECENTRE_FOLLOWS_TILT, value).apply()

    /**
     * 1.4.0 (docs/6DOF-PLAN.md): Steady mode in 6DoF on the XREAL Aura, off by default. Steady mode then keeps the glasses'
     * Spatial Anchor on, so the wide screen stays put as the head leans and moves ([com.hayeco.flipspaces.core.spatial.SteadyMode]).
     */
    var steadySixDof: Boolean
        get() = prefs.getBoolean(KEY_STEADY_SIX_DOF, false)
        set(value) = prefs.edit().putBoolean(KEY_STEADY_SIX_DOF, value).apply()

    /** 0.5.64: the drawn orientation's One Euro filter (Developer); on by default. */
    var drawSmoothing: Boolean
        get() = prefs.getBoolean(KEY_DRAW_SMOOTHING, true)
        set(value) = prefs.edit().putBoolean(KEY_DRAW_SMOOTHING, value).apply()

    /** 1.2.3 (issue 58): the apps with Side by side 3D on, as [com.hayeco.flipspaces.core.spatial.SideBySideStore] text. */
    var sideBySide: String?
        get() = prefs.getString(KEY_SIDE_BY_SIDE, null)
        set(value) = prefs.edit().putString(KEY_SIDE_BY_SIDE, value).apply()

    /** 1.2.3 (issue 59): each game's touch spots, as [com.hayeco.flipspaces.core.pointer.TouchSpotStore] text. */
    var touchSpots: String?
        get() = prefs.getString(KEY_TOUCH_SPOTS, null)
        set(value) = prefs.edit().putString(KEY_TOUCH_SPOTS, value).apply()

    /** 1.2.3 (issue 58): 2D while moving for side by side windows; off by default. */
    var sideBySideFlat: Boolean
        get() = prefs.getBoolean(KEY_SIDE_BY_SIDE_FLAT, false)
        set(value) = prefs.edit().putBoolean(KEY_SIDE_BY_SIDE_FLAT, value).apply()

    /** 1.1.14: Settings' Head tracking switch ([HeadTracker.setEnabled]); on by default. Off, the Space is head locked. */
    var headTracking: Boolean
        get() = prefs.getBoolean(KEY_HEAD_TRACKING, true)
        set(value) = prefs.edit().putBoolean(KEY_HEAD_TRACKING, value).apply()

    /** 1.2.3 (issue 54): the Menu's "Air display mode (untested)" switch; off by default. */
    var airDisplayMode: Boolean
        get() = prefs.getBoolean(KEY_AIR_DISPLAY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_AIR_DISPLAY_MODE, value).apply()

    /** 0.5.32: the glasses warp without LSR, keeping scene mode 1 (Developer); on by default. */
    var warpNoLsr: Boolean
        get() = prefs.getBoolean(KEY_WARP_NO_LSR, true)
        set(value) = prefs.edit().putBoolean(KEY_WARP_NO_LSR, value).apply()

    /** 0.5.32: the warp goes back in after the glasses drop out of it, at most 3 drops in 30 s (Developer); on by default. */
    var warpReenter: Boolean
        get() = prefs.getBoolean(KEY_WARP_REENTER, true)
        set(value) = prefs.edit().putBoolean(KEY_WARP_REENTER, value).apply()

    /** 0.5.37 (Mike: save preset app windows): the saved presets, newest first ([com.hayeco.flipspaces.core.spatial.WindowPresets]). */
    var presets: List<com.hayeco.flipspaces.core.spatial.WindowPreset>
        get() = com.hayeco.flipspaces.core.spatial.WindowPresets.decode(prefs.getString(KEY_PRESETS, null))
        set(value) = prefs.edit().putString(KEY_PRESETS, com.hayeco.flipspaces.core.spatial.WindowPresets.encode(value)).apply()

    /** 0.5.37 (Mike: the "Open a new instance if the app is already running" switch in the Menu): off by default. */
    /** 0.5.101: the trackpad Menu's switches kept here (new instance, snap, cursor follows head, keep screen on) to their defaults. */
    fun resetTrackpadSettings() {
        prefs.edit().remove(KEY_NEW_INSTANCE).remove(KEY_SNAP_WINDOWS).remove(KEY_CURSOR_FOLLOWS_HEAD).remove(KEY_TRACKPAD_KEEPS_SCREEN_ON).apply()
    }

    var newInstance: Boolean
        get() = prefs.getBoolean(KEY_NEW_INSTANCE, false)
        set(value) = prefs.edit().putBoolean(KEY_NEW_INSTANCE, value).apply()

    /**
     * 1.1.15 (John Davis: a "lock screens" option): Lock screens ([com.hayeco.flipspaces.core.spatial.WindowLock]), off by
     * default; saved, so the windows are still locked after the app or the phone restarts.
     */
    var windowsLocked: Boolean
        get() = prefs.getBoolean(com.hayeco.flipspaces.core.spatial.WindowLock.KEY, false)
        set(value) = prefs.edit().putBoolean(com.hayeco.flipspaces.core.spatial.WindowLock.KEY, value).apply()

    /** 0.5.37 (Mike: snap a dragged window to the one it nears, with a setting to turn it off): on by default. */
    var snapWindows: Boolean
        get() = prefs.getBoolean(KEY_SNAP_WINDOWS, true)
        set(value) = prefs.edit().putBoolean(KEY_SNAP_WINDOWS, value).apply()

    /** 0.5.36: the trackpad cursor follows the head (on, the default) or stays anchored in the room (off). */
    var cursorFollowsHead: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_FOLLOWS_HEAD, true)
        set(value) = prefs.edit().putBoolean(KEY_CURSOR_FOLLOWS_HEAD, value).apply()

    /**
     * 0.5.51 (Mike: the cover screen never goes dark, and turns back on when he uses Pocket XR): whether the trackpad
     * keeps the phone's screen awake and wakes it when it comes up. Off, the screen sleeps on its
     * own timeout and stays dark while the glasses are used with the hands.
     */
    /** 0.5.52: window displays on the shell's virtual device (Developer test; [PanelHost.onVirtualDevice]). */
    var panelsOnVirtualDevice: Boolean
        get() = prefs.getBoolean(KEY_PANELS_ON_VIRTUAL_DEVICE, false)
        set(value) = prefs.edit().putBoolean(KEY_PANELS_ON_VIRTUAL_DEVICE, value).apply()

    var trackpadKeepsScreenOn: Boolean
        get() = prefs.getBoolean(KEY_TRACKPAD_KEEPS_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_TRACKPAD_KEEPS_SCREEN_ON, value).apply()

    /** 0.5.54: "Glasses set the frame clock (test)" ([GlassesPacesetter]); off by default until it is tried in full SBS. */
    var glassesSetFrameClock: Boolean
        get() = prefs.getBoolean(KEY_GLASSES_SET_FRAME_CLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_GLASSES_SET_FRAME_CLOCK, value).apply()


    /** 0.5.32: the glasses' duty lowered on fast turns ([com.hayeco.flipspaces.core.spatial.TurnDuty]; Developer); off by default, a new command. */
    // 0.5.36 (Mike: sharper turns "should always be default enabled"; its Menu row is gone): always true.
    var turnDuty: Boolean
        get() = true
        set(value) = prefs.edit().putBoolean(KEY_TURN_DUTY, value).apply()

    /** 0.5.32: the head cursor's mode ([com.hayeco.flipspaces.core.spatial.HeadCursorMode] key); auto hide by default. */
    // 0.5.36 (Mike: users will always want auto hide; its Menu row is gone): always auto hide.
    var headCursor: String
        get() = com.hayeco.flipspaces.core.spatial.HeadCursorMode.AUTO.key
        set(value) = prefs.edit().putString(KEY_HEAD_CURSOR, value).apply()

    /** 0.5.29: each eye's picture centred for the glasses' 3D distance ([com.hayeco.flipspaces.core.spatial.ScreenDistance]); on by default. */
    // 0.5.36: always on (its Menu row is gone; the distance follows the glasses' own setting).
    var distanceFix: Boolean
        get() = true
        set(value) = prefs.edit().putBoolean(KEY_DISTANCE_FIX, value).apply()

    /**
     * 0.5.25: the per-eye late warp in side by side 3D ([com.hayeco.flipspaces.core.spatial.LateWarpFrames]):
     * the Space drawn early into eye buffers, turned by the newest pose just before the deadline. On by default; off
     * draws side by side as 0.5.24 did.
     */
    var lateWarpSbs: Boolean
        get() = prefs.getBoolean(KEY_LATE_WARP_SBS, true)
        set(value) = prefs.edit().putBoolean(KEY_LATE_WARP_SBS, value).apply()

    /** 0.5.61: the soft fade to black on each eye's edges ([com.hayeco.flipspaces.core.spatial.EdgeFadeMode]); Soft by default. */
    var edgeFade: com.hayeco.flipspaces.core.spatial.EdgeFadeMode
        get() = com.hayeco.flipspaces.core.spatial.EdgeFadeMode.saved(prefs.getString(KEY_EDGE_FADE, null))
        set(value) = prefs.edit().putString(KEY_EDGE_FADE, value.arg).apply()

    /** 0.5.15: the per-row pose setting for the view in use. */
    fun rowPoseFor(sbs: Boolean): Boolean = if (sbs) rowPoseSbs else rowPose

    /** 0.5.13: panel pictures through four footprint taps instead of one. */
    var footprintFilter: Boolean
        get() = prefs.getBoolean(KEY_FOOTPRINT, true)
        set(value) = prefs.edit().putBoolean(KEY_FOOTPRINT, value).apply()

    /** 0.5.12: ask the glasses for their fastest refresh at the picture size in use. */
    var fastestRefresh: Boolean
        get() = prefs.getBoolean(KEY_FASTEST_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_FASTEST_REFRESH, value).apply()

    /** Launcher favourites, AppEntry keys in order. */
    var favourites: List<String>
        get() = prefs.getString(KEY_FAVOURITES, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_FAVOURITES, value.joinToString("\n")).apply()

    /** The windows as the user had them before Steady mode, [LayoutCodec] text, null outside it. */
    var steadyLayout: String?
        get() = prefs.getString(KEY_STEADY_LAYOUT, null)
        set(value) = prefs.edit().putString(KEY_STEADY_LAYOUT, value).apply()

    /** The launcher's default pins were offered ([com.hayeco.flipspaces.core.spatial.LauncherDefaults]). */
    /** The PC VR tile was pinned once, for someone who had pins already; unpinning it later is their choice. */
    var pcVrPinned: Boolean
        get() = prefs.getBoolean("pcvr_pinned", false)
        set(value) = prefs.edit().putBoolean("pcvr_pinned", value).apply()

    var pinsSeeded: Boolean
        get() = prefs.getBoolean(KEY_PINS_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_PINS_SEEDED, value).apply()

    /** Apps opened from the Space, most recent first, for the launcher's recents strip. */
    var recents: List<String>
        get() = prefs.getString(KEY_RECENTS, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_RECENTS, value.joinToString("\n")).apply()

    companion object {
        /** 1.1.0: how often [xrealUsbProduct] reads the USB devices again. */
        private const val USB_READ_MS = 5_000L

        /**
         * 0.5.13: the glasses behind [display], from its EDID (Android 12+); null when
         * Android does not say.
         */
        fun glassesId(display: android.view.Display): com.hayeco.flipspaces.core.spatial.GlassesId? {
            if (android.os.Build.VERSION.SDK_INT < 31) return null
            val info = display.deviceProductInfo ?: return null
            val name = info.name?.takeIf { it.isNotBlank() } ?: return null
            return com.hayeco.flipspaces.core.spatial.GlassesId(name, info.productId.orEmpty())
        }

        /** Runs saved before the glasses were known (the head tracking screen without a Space yet). */
        const val UNNAMED_GLASSES = "glasses not named yet"

        private const val KEY_ROW_POSE = "row_pose"
        private const val KEY_ROW_POSE_SBS = "row_pose_sbs_0525"
        private const val KEY_DISTANCE_3D = "glasses_distance_3d_0529"
        private const val KEY_DISTANCE_FIX = "distance_fix_0529"
        private const val KEY_DEBUG_INFO = "debug_info_0530"
        private const val KEY_HEAD_CURSOR = "head_cursor_0532"
        private const val KEY_STILL_LOCK = "still_lock_0532"
        private const val KEY_DRAW_SMOOTHING = "draw_smoothing_0564"
        private const val KEY_HEAD_TRACKING = "head_tracking_1114"
        // 1.2.8's key, kept so a saved on or off carries over.
        private const val KEY_VITURE_HEAD_TRACKING = "viture_head_tracking"
        private const val KEY_VITURE_PRODUCT = "viture_product_1212"
        private const val KEY_TOUCH_SPOTS = "touch_spots_123"
        private const val KEY_AIR_DISPLAY_MODE = "air_display_mode_123"
        private const val KEY_SIDE_BY_SIDE = "side_by_side_123"
        private const val KEY_SIDE_BY_SIDE_FLAT = "side_by_side_flat_123"
        private const val KEY_AUTO_GLASSES_VIEW = "auto_glasses_view_0565"
        private const val KEY_FACTORY_CALIBRATION = "factory_calibration_0565"
        private const val KEY_RECENTRE_FOLLOWS_TILT = "recentre_follows_tilt_1213"
        private const val KEY_STEADY_SIX_DOF = "steady_six_dof_140"
        // 0.5.96: the key keeps its saved name, so the setting carries over.
        private const val KEY_WARP_NO_LSR = "warp_nebula_0532"
        private const val KEY_WARP_REENTER = "warp_reenter_0532"
        private const val KEY_TURN_DUTY = "turn_duty_0532"
        private const val KEY_CURSOR_FOLLOWS_HEAD = "cursor_follows_head_0536"
        private const val KEY_TRACKPAD_KEEPS_SCREEN_ON = "trackpad_keeps_screen_on_0551"
        private const val KEY_PANELS_ON_VIRTUAL_DEVICE = "panels_on_virtual_device_0552"
        private const val KEY_GLASSES_SET_FRAME_CLOCK = "glasses_set_frame_clock_0554"
        private const val KEY_SNAP_WINDOWS = "snap_windows_0537"
        private const val KEY_NEW_INSTANCE = "new_instance_0537"
        private const val KEY_PRESETS = "window_presets_0537"
        private const val KEY_LATE_WARP_SBS = "late_warp_sbs_0525"
        private const val KEY_EDGE_FADE = "edge_fade_0561"
        private const val KEY_GYRO_BIAS_STATIC = "head_gyro_bias_static_0515"
        private const val KEY_GYRO_BIAS_WORN = "head_gyro_bias_worn_0515"
        private const val KEY_MOUNT_ROLL = "imu_mount_roll_deg"
        private const val KEY_MOUNT_PITCH = "imu_mount_pitch_deg"
        private const val KEY_FOOTPRINT = "footprint_filter"
        private const val KEY_DELAY_PROFILES = "display_delay_profiles"
        private const val KEY_GLASSES = "glasses_id"
        private const val KEY_CALIBRATED_FOR = "display_delay_calibrated_for"
        private const val KEY_GYRO_BIAS = "head_gyro_bias"
        private const val KEY_DELAY_RUNS = "display_delay_runs"
        private const val KEY_DELAY_CALIBRATED = "display_delay_calibrated"
        private const val KEY_LATE_DRAW = "late_draw"
        private const val KEY_GLASSES_WARP = "glasses_warp_beta"
        private const val KEY_WIDE_VIEW = "broad_view_apps"
        private const val KEY_WIDE_VIEW_OVERRIDE = "broad_view_override_pending"
        private const val KEY_APP_DENSITY = "app_density"
        private const val KEY_APP_TEXT_SIZE = "app_text_size_123"
        private const val KEY_FASTEST_REFRESH = "fastest_refresh"
        private const val KEY_FAVOURITES = "launcher_favourites"
        private const val KEY_PINS_SEEDED = "launcher_pins_seeded"
        private const val KEY_STEADY_LAYOUT = "steady_layout"
        private const val KEY_RECENTS = "launcher_recents"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_AXES = "axes"
        private const val KEY_AXES_GRAVITY = "axes_gravity"
        private const val KEY_FOV = "fov"
        private const val KEY_SCREEN_2D = "glasses_screen_2d_0526"
        private const val KEY_SCREEN_3D = "glasses_screen_3d_0526"
        private const val KEY_SEE_THROUGH = "see_through"
        private const val KEY_HANDS_STEER = "hands_steer"
        private const val KEY_PREDICT_EXTRA = "predict_extra_ms"
        private const val KEY_VIEW = "glasses_view_0527"
        private const val KEY_PICTURE_ADJUST = "picture_adjust_0527|"
        private const val KEY_IPD = "ipd_mm"
        private const val KEY_DEX_FULL_SCREEN = "dex_full_screen"
        private const val KEY_PLAIN_DISPLAY = "plain_display"
        private const val KEY_WINDOW_GLOW = "window_glow_122"
        private const val KEY_WINDOW_GLOW_COLOR = "window_glow_color_122"
    }
}
