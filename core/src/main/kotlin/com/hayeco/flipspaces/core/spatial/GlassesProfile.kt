package com.hayeco.flipspaces.core.spatial

import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.5.95 (Mike, XREAL One, 30 October: after a head turn the windows "keeps adjusting slowly like a adjust glide at
 * the end"; "it was the xreal ones Stabilizer setting in the glasses causing the issue because it was on. i turned
 * it off"): the One steadies its own picture against the head when its Stabilizer is on, on top of the Space's
 * head tracking, so the two fight and the picture glides back after each turn. Taken as the same on the One Pro
 * and 1S (the same family's menu), not confirmed.
 *
 * 1.1.8 (issue 31, a One Pro on a Retroid Nova and a Galaxy S24: body tracked windows "move the opposite direction at a
 * 1:1 rate" on each turn and nod, while "the Level locks in place"): the glasses were in their own Anchor mode. The level
 * line is drawn fixed to the display (SpaceRenderer.drawLevelCheck), so only the glasses moving the whole picture can
 * hold it still in the room; the windows, which the Space already turns against the head, then move twice. The hint
 * said only Stabilizer, so it names Follow mode too.
 */
const val ONE_STABILIZER = "Use Follow mode (not Anchor) with Stabilizer off on the glasses, or windows won't stay put"

/**
 * 0.5.91 (support for the One, One Pro and 1S): what the app knows of each XREAL
 * model, so nothing assumes the Aura where it is not one. Anything not confirmed for a model falls back safely: the
 * published field of view, no camera (hand tracking hidden), 2D until the glasses switch to 3D.
 *
 * What each model reported to Android is what decides it ([detect]): the display's EDID name and product id, else the
 * USB product id. Observed on Mike's glasses on 30 October 2026:
 * - Aura: display "XREAL 1 Ultra" (EDID product 16656), USB 3318:043A, with a UVC camera.
 * - One: display "XREAL One" (EDID product 16641), USB 3318:0438, 1920 x 1080 at 120 Hz in 2D and 3840 x 1080 at 60 Hz
 *   in its 3D mode; the same control link as the Aura (serial, 2D / 3D state and switch, duty, EDID index, 3D distance
 *   and dimming film all answered) and the same head tracking stream at 1000 Hz. No camera.
 * The One Pro and 1S have not been seen; they are matched by name only and marked untested.
 */
enum class GlassesModel(
    val label: String,
    /** A short key for saved settings that differ by model. */
    val key: String,
    /** Run on these glasses by the project, not only from published figures. */
    val tested: Boolean,
    /** A camera the app can read hands from. */
    val camera: Boolean,
    /** XREAL's published diagonal field of view, degrees, on 1920 x 1080 per eye; null when not published or not known. */
    val diagonalFovDeg: Float?,
    /**
     * 0.5.91: how the sensor's axes sit in the glasses, until the user picks one on the head tracking screen. The Aura's
     * is the default. 0.5.94: the One's is turned half round about the side axis, "x right, y down, z front": its
     * gravity reads along -y, and with 0.5.92's half turn about the forward axis it looked up when Mike looked down,
     * while its gyro and gravity agreed (0.5.93 turned only the gyro's x round, and they parted by 13 to 28 degrees).
     * The One Pro and 1S are taken as the One's (the same family), not confirmed.
     */
    val defaultAxes: AxisMap = AxisMap.DEFAULT,
    /**
     * 0.5.95: a setting on the glasses themselves that the Space needs, said on the glasses for the first few sessions
     * on that model ([SETUP_HINT_SESSIONS]); null when none is known. The app cannot read or change it.
     */
    val setupHint: String? = null,
) {
    AURA("XREAL Aura", "aura", tested = true, camera = true, diagonalFovDeg = null),
    ONE("XREAL One", "one", tested = true, camera = false, diagonalFovDeg = 50f, defaultAxes = AxisMap.XRIGHT_YDOWN_ZFRONT, setupHint = ONE_STABILIZER),
    ONE_PRO("XREAL One Pro", "onepro", tested = false, camera = false, diagonalFovDeg = 57f, defaultAxes = AxisMap.XRIGHT_YDOWN_ZFRONT, setupHint = ONE_STABILIZER),
    ONE_S("XREAL 1S", "1s", tested = false, camera = false, diagonalFovDeg = null, defaultAxes = AxisMap.XRIGHT_YDOWN_ZFRONT, setupHint = ONE_STABILIZER),
    // 1.1.0: the One family's axes. Every XREAL with this head tracking link but the Aura has them, and the Aura's axes
    // on a One family model turn the picture upside down (0.5.91).
    // 1.1.12: the Air, Air 2, Air 2 Pro and Air 2 Ultra (USB [AirImuProtocol.modelName]). Their HID reports are turned
    // into head axes as they are read ([AirImuProtocol.parse]), so the default axes; the One family's turned them half
    // round, the Space facing behind.
    AIR("XREAL Air", "air", tested = false, camera = false, diagonalFovDeg = 46f),
    OTHER_XREAL("XREAL glasses", "xreal", tested = false, camera = false, diagonalFovDeg = null, defaultAxes = AxisMap.XRIGHT_YDOWN_ZFRONT),
    // 1.2.6 (Discussion #24, a VITURE Beast showed as "Unknown glasses"): VITURE's glasses, by their display's name. Their
    // own settings (axes, screen size) are kept apart from other glasses'. The experimental reader turns its reports into
    // head axes itself ([VitureAxes]), so the default axes.
    VITURE("VITURE glasses", "viture", tested = false, camera = false, diagonalFovDeg = null),
    UNKNOWN("Unknown glasses", "unknown", tested = false, camera = false, diagonalFovDeg = null);

    /**
     * The glasses' screen at size 1.0, as tan per picture pixel of a 1920 x 1080 picture. The Aura's is its own
     * calibration ([GlassesScreen.TAN_PER_PX_1TO1]); the others' is their published diagonal across the picture, and
     * without one, the One's (the nearest model the project has run on).
     */
    val tanPerPx1to1: Float
        get() = if (this == AURA) GlassesScreen.TAN_PER_PX_1TO1 else tanPerPx(diagonalFovDeg ?: ONE.diagonalFovDeg!!)

    /**
     * 1.1.4 (issue 26, a One Pro: windows rolled as the head turned, while the level bar was right): the factory
     * calibration's display mount is used only where it was checked against a level calibration, the Aura. The One sends
     * none, and the One Pro's was never checked, so the level calibration's mount is used there.
     */
    val factoryMountChecked: Boolean get() = this == AURA

    /**
     * 1.2.6: an XREAL model, whose 3840 x 1080 mode is always their side by side 3D (or an Ultrawide their control link
     * says). Other glasses' wide mode may be one 32:9 canvas ([WideCanvas]).
     */
    val isXreal: Boolean get() = this != VITURE && this != UNKNOWN

    /** Whether [tanPerPx1to1] comes from this model's own figure. */
    val fovKnown: Boolean get() = this == AURA || diagonalFovDeg != null

    /** The default screen size in 2D: the Aura's measured 0.80; the others show the picture at their full view. */
    val default2D: GlassesScreen
        get() = if (this == AURA) GlassesScreen.AURA_2D else GlassesScreen(GlassesScreen.FULL, 1.0f, "the full view of the ${label}'s published figure")

    /** One line for the log and the Glasses report. */
    fun line(): String {
        val (w, h) = degreesAt(1f)
        return "%s (%s): %s, camera %s, view at 100 %% %.1f by %.1f degrees (%s)".format(
            java.util.Locale.ROOT, label, key, if (tested) "tested" else "not tested by the project", if (camera) "yes" else "no",
            w, h, if (fovKnown) if (this == AURA) "its own calibration" else "published $diagonalFovDeg degrees diagonal" else "not published: the One's 50 degrees",
        )
    }

    /** Degrees wide and tall of a 1920 x 1080 picture at screen size [factor]. */
    fun degreesAt(factor: Float): Pair<Float, Float> {
        val t = tanPerPx1to1 * factor
        return 2f * atan(t * 960f) * RAD to 2f * atan(t * 540f) * RAD
    }

    companion object {
        /** 0.5.95: how many sessions on a model its [setupHint] is said in. */
        const val SETUP_HINT_SESSIONS = 3

        const val USB_AURA = 0x043A
        const val USB_ONE = 0x0438
        const val EDID_AURA = "16656"
        const val EDID_ONE = "16641"

        /** Tan per pixel of a 1920 x 1080 picture whose diagonal spans [diagonalDeg]. */
        fun tanPerPx(diagonalDeg: Float): Float = tan(diagonalDeg * DEG / 2f) / (sqrt(1920f * 1920f + 1080f * 1080f) / 2f)

        /**
         * The model behind [id] (the display's EDID) or, when that names none, [usbProduct] on XREAL's vendor id.
         *
         * 1.1.0 (a One Pro on an Honor Magic V5: the picture upside down): the USB product decided only when the display
         * had no name at all. A phone that names the glasses' display itself (not from the EDID) made them UNKNOWN, and
         * UNKNOWN takes the Aura's axes. Now any glasses on XREAL's USB vendor id are at least [OTHER_XREAL].
         */
        fun detect(id: GlassesId?, usbProduct: Int? = null): GlassesModel {
            val name = id?.name?.trim().orEmpty()
            val lower = name.lowercase()
            return when {
                id?.productId == EDID_AURA || lower == "xreal 1 ultra" || lower.contains("aura") -> AURA
                id?.productId == EDID_ONE || lower == "xreal one" -> ONE
                lower.contains("one pro") -> ONE_PRO
                lower.contains("1s") || lower.contains("one s") -> ONE_S
                lower.contains("viture") -> VITURE
                usbProduct == USB_AURA -> AURA
                usbProduct == USB_ONE -> ONE
                usbProduct?.let(AirImuProtocol::modelName) != null || lower.contains("xreal air") || lower.contains("nreal air") -> AIR
                lower.contains("xreal") || lower.contains("nreal") || usbProduct != null -> OTHER_XREAL
                else -> UNKNOWN
            }
        }
    }
}
