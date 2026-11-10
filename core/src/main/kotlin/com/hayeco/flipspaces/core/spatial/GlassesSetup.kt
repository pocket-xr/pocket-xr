package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.9 (issue 31, a One Pro in its own Anchor mode: windows moved twice as far; Mike: when testers get stuck on
 * something that only needs a setting changed, fix the onboarding so new users never hit it): what to set on the glasses
 * themselves, as a page of the trackpad's tutorial. Shown at first run when the plugged-in glasses are known, again the
 * first time a new model is plugged in, from the Menu's "Glasses setup", and from the Anchor card ([anchorLikely]).
 *
 * Only what applies to the model ([GlassesModel.setupHint]): the One family has a page; the Aura and glasses the app does
 * not know have none, so nothing is shown for them.
 *
 * 1.1.16 (issue 12, an XREAL 1S on a Beam Pro: windows turned faster than the room and were cut off past an angle;
 * thejoeba spotted that the glasses' own Anchor or Follow setting was the cause): the steps say which button to press and
 * where the setting sits, in XREAL's words for the One, One Pro and 1S (tutorials.xreal.com, One series: X once switches
 * Follow and Anchor, X twice opens the menu, Stabilizer is under Spatial Screen, under Display on older firmware). The
 * host is part of it too ([SetupHost]): on a Beam Pro, or with XREAL's own app on the phone, the page says plainly what
 * can also move the picture and what to close. The head turn check after the steps is [GlassesCheck].
 */
data class GlassesSetupPage(
    val title: String,
    /** What to set on the glasses, 0 to 3 steps; empty when nothing on these glasses needs setting. */
    val steps: List<String>,
    /** A quick self-check under the steps. */
    val check: String,
    /** What on the host can also move the picture and what to do about it ([SetupHost.notes]); empty on a plain phone. */
    val hostNotes: List<String> = emptyList(),
    val family: SetupFamily = SetupFamily.OTHER,
) {
    /** Something to show: a step on the glasses or a line about the host. */
    val hasAdvice: Boolean get() = steps.isNotEmpty() || hostNotes.isNotEmpty()
}

/** The groups of glasses whose own settings matter to the Space the same way. */
enum class SetupFamily {
    /** One, One Pro and 1S: their own Follow and Anchor modes and Stabilizer, set with the X button. */
    ONE_SERIES,
    /** Air, Air 2, Air 2 Pro and Air 2 Ultra: no Anchor mode of their own; only an XREAL app on the host can add one. */
    AIR,
    /** The Aura: Pocket XR sets its display itself. */
    AURA,
    /** Glasses the project does not know. */
    OTHER,
}

/**
 * The device the glasses are plugged into, as far as it matters to the setup: a Beam Pro, and XREAL's own apps installed
 * on it ([xrealApps], their names), which can drive the glasses' picture and head tracking while they are open. Android
 * does not tell an app which other apps are running, so the page says what to close if it is open.
 */
data class SetupHost(val beamPro: Boolean = false, val xrealApps: List<String> = emptyList()) {
    /** The lines the setup page shows for this host; none on a phone without XREAL's apps. */
    fun notes(family: SetupFamily): List<String> {
        val out = ArrayList<String>()
        if (beamPro) {
            out += BEAM_PRO_NOTE
            if (family == SetupFamily.ONE_SERIES) out += BEAM_PRO_ONE_NOTE
        }
        // On a Beam Pro XREAL's apps are its own system, which the lines above cover.
        if (!beamPro && xrealApps.isNotEmpty()) out += appsNote(xrealApps)
        return out
    }

    companion object {
        val PHONE = SetupHost()

        /** XREAL's package prefixes (old Nreal names too). */
        private val XREAL_PACKAGES = listOf("com.xreal.", "ai.xreal.", "com.nreal.", "ai.nreal.", "io.xreal.")

        /**
         * The host from Android's [manufacturer], [brand] and [model], and the launchable apps installed ([installed],
         * package to name). An Android device made by XREAL is a Beam or Beam Pro; its model name is checked too.
         */
        fun detect(manufacturer: String?, brand: String?, model: String?, installed: Map<String, String>): SetupHost {
            val maker = listOfNotNull(manufacturer, brand).joinToString(" ").lowercase()
            val beam = maker.contains("xreal") || maker.contains("nreal") || model.orEmpty().lowercase().contains("beam")
            val apps = installed.filterKeys { p -> XREAL_PACKAGES.any { p.startsWith(it) } }.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            return SetupHost(beam, apps)
        }

        const val BEAM_PRO_NOTE =
            "On a Beam Pro: XREAL's own spatial view can also turn the picture with your head, so windows move twice. Close it, then open the Space from Pocket XR."
        const val BEAM_PRO_ONE_NOTE =
            "The Beam Pro may switch your glasses back to Anchor. If the check fails, set Follow again on the glasses."

        fun appsNote(apps: List<String>): String =
            "${apps.joinToString(", ")} ${if (apps.size == 1) "is" else "are"} on this phone. It does no harm while closed. If it is open, close it from Recents before you open the Space: it can drive the glasses' picture and head tracking too."
    }
}

object GlassesSetup {
    /**
     * The XREAL buttons and menu in XREAL's words (tutorials.xreal.com, One series: buttons, Stabilizer), each short
     * enough for a short landscape card (95 characters).
     */
    const val STEP_FOLLOW = "Press X on the glasses once to switch mode. Pick Follow mode, not Anchor"
    const val STEP_STABILIZER = "Press X twice to open the glasses' menu. In Spatial Screen, turn Stabilizer off"
    const val STEP_MENU = "In the menu, + and - move, X picks, X twice goes back. Older firmware: Stabilizer is in Display"

    /**
     * The family of the glasses: the model, else the USB product on XREAL's vendor id. The One series' network link
     * products ([HeadImuTransport.NETWORK_PRODUCTS]: One Pro 0x0435 and 0x0436, One 0x0437 and 0x0438, 1S 0x043D and
     * 0x043E, One Pro+ 0x0444) tell the family when the name does not; the model itself is left to [GlassesModel.detect].
     */
    fun family(model: GlassesModel?, usbProduct: Int? = null): SetupFamily = when (model) {
        GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_PRO_PLUS, GlassesModel.ONE_S -> SetupFamily.ONE_SERIES
        GlassesModel.AIR -> SetupFamily.AIR
        GlassesModel.AURA -> SetupFamily.AURA
        else -> if (usbProduct in ONE_SERIES_PRODUCTS) SetupFamily.ONE_SERIES else SetupFamily.OTHER
    }

    val ONE_SERIES_PRODUCTS: Set<Int> = setOf(0x0435, 0x0436, 0x0437, 0x0438, 0x043D, 0x043E, 0x0444)

    /** The steps on the glasses for [family]: only the One series has settings of its own that fight the Space. */
    fun steps(family: SetupFamily): List<String> = when (family) {
        SetupFamily.ONE_SERIES -> listOf(STEP_FOLLOW, STEP_STABILIZER, STEP_MENU)
        SetupFamily.AIR, SetupFamily.AURA, SetupFamily.OTHER -> emptyList()
    }

    /**
     * The setup page for [model] on [host], or null when there is nothing to set or close. [usbProduct] tells the One
     * series by its USB product when the name does not.
     */
    fun forModel(model: GlassesModel?, host: SetupHost = SetupHost.PHONE, usbProduct: Int? = null): GlassesSetupPage? =
        page(model, host, usbProduct).takeIf { it.hasAdvice }

    /** The page for [model] even when it has no advice, for the check from the Menu or Settings. */
    fun page(model: GlassesModel?, host: SetupHost = SetupHost.PHONE, usbProduct: Int? = null): GlassesSetupPage {
        val family = family(model, usbProduct)
        val name = when {
            model != null && model != GlassesModel.OTHER_XREAL && model != GlassesModel.UNKNOWN -> model.label
            family == SetupFamily.ONE_SERIES -> "XREAL One series"
            else -> "glasses"
        }
        val steps = steps(family)
        return GlassesSetupPage(
            title = if (steps.isEmpty()) "Check your $name" else "Set up your $name",
            steps = steps,
            check = when {
                family == SetupFamily.AURA -> AURA_CHECK
                steps.isEmpty() -> NOTHING_TO_SET
                else -> ONE_CHECK
            },
            hostNotes = host.notes(family),
            family = family,
        )
    }

    const val ONE_CHECK = "Check: look left and right. Windows should stay put. If they move twice as far or get cut off, the glasses are in Anchor mode."
    const val NOTHING_TO_SET = "Nothing needs setting on these glasses. The check below makes sure the windows stay put."
    const val AURA_CHECK = "Pocket XR sets these glasses for you. The check below makes sure the windows stay put."

    /**
     * The model of the XREAL glasses on USB now, or null when none are plugged in: [usbProduct] is the USB product id on
     * XREAL's vendor id, [usbName] the device's own product name ("XREAL One Pro"), read before the Space opens and
     * before the display's EDID name is known. A One Pro's product id is not known to the project, so its name tells.
     */
    fun plugged(usbProduct: Int?, usbName: String?): GlassesModel? {
        if (usbProduct == null) return null
        val name = usbName?.trim()?.takeIf { it.isNotEmpty() }
        return GlassesModel.detect(name?.let { GlassesId(it, "") }, usbProduct)
    }

    /** Whether to show [model]'s page now it is plugged in: it has one and that model was never set up here ([seenKeys]). */
    fun showOnPlug(model: GlassesModel?, seenKeys: Set<String>): Boolean =
        model != null && forModel(model) != null && model.key !in seenKeys

    /**
     * The glasses' own panel duty (10065, read once when the display control link opens) at or below this, percent, is
     * their world lock table: 16 to 32 % in their own 3DoF modes ([TurnDuty]; 30 % measured in the Aura's hover, 2
     * November 2026), against about 90 % in head lock. A One family pair in Anchor mode holds its picture in the room
     * the same way, so it is taken to read low too. That is inferred, not measured on a One, so the card says "may be".
     * The Aura has also read 30 % in follow, in 2D at 120 Hz (6 November 2026), so a low duty can be a false alarm.
     */
    const val WORLD_LOCK_DUTY_MAX = 40

    /**
     * Whether [model]'s glasses are likely in their own Anchor mode: the duty they answered ([dutyPercent], null when not
     * read) is in the world lock range, and nothing of Pocket XR's set it ([dutyOurs]: the hover duty test or Sharper
     * turns). Only the duty already read is used; no other control message is sent to find out.
     */
    fun anchorLikely(model: GlassesModel?, dutyPercent: Int?, dutyOurs: Boolean): Boolean =
        family(model) == SetupFamily.ONE_SERIES && !dutyOurs && dutyPercent != null && dutyPercent in 1..WORLD_LOCK_DUTY_MAX

    /** The card's words on the phone when [anchorLikely]. */
    const val ANCHOR_CARD = "Your glasses may be in Anchor mode, so windows move twice as far as your head. Press X on the glasses once for Follow mode, and turn Stabilizer off."
    const val ANCHOR_CARD_OK = "Glasses setup"
    const val ANCHOR_CARD_LATER = "Not now"
}
