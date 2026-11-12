package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.UiText
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup

/** The picture over an intro page (the trackpad draws each one). */
/** 1.2.1: [ADDON] is the add-on's page, which the trackpad draws with the add-on's Install button under its lines. */
enum class IntroArt { POINT, SCROLL, THREE_FINGERS, AIM_PHONE, AIM_HANDHELD, GLASSES_MENU, ADDON }

/** One page of the trackpad's tutorial: a title, numbered steps, and a quieter [note] under them. */
data class IntroPage(val title: String, val items: List<String>, val art: IntroArt, val note: String? = null)

/**
 * The trackpad's tutorial, page by page. Every page can be skipped (the card's close mark, Next, or a swipe), and the
 * words fit a short landscape screen, where the picture is left out (1.1.2).
 *
 * 1.1.9 (Mike: fix the onboarding where testers got stuck on a setting): at first run, a glasses setup page first when
 * the plugged-in glasses have one ([GlassesSetup.forModel]; issue 31, a One Pro in Anchor mode), and a Spatial Mouse page
 * that says what it is and how to hold the device (issue 33: a Retroid Nova owner knew it from another app as "gesture",
 * and could not tell how to hold a landscape handheld).
 */
object IntroPages {
    /**
     * The gesture pages. 0.5.96 (open source audit): written in Pocket XR's own words for what its gestures do (the earlier
     * pages were another product's text).
     */
    val GESTURES: List<IntroPage> get() = listOf(
        // 1.2.3 (issue 53): the resize handle needs a held finger, which nothing said.
        IntroPage(
            UiText.get("core_intro_point_title", "Point and click"),
            listOf(
                UiText.get("core_intro_point_move", "Move one finger to move the dot"),
                UiText.get("core_intro_point_tap", "Tap to click"),
                UiText.get("core_intro_point_two_finger_tap", "Tap with two fingers for a long press"),
            ),
            IntroArt.POINT,
            note = RESIZE_LINE,
        ),
        IntroPage(
            UiText.get("core_intro_scroll_title", "Scroll and zoom"),
            listOf(
                UiText.get("core_intro_scroll_strip", "Run a finger along the strip at the edge to scroll"),
                UiText.get("core_intro_scroll_two_fingers", "Move two fingers to scroll"),
                UiText.get("core_intro_scroll_pinch", "Pinch with two fingers to zoom"),
            ),
            IntroArt.SCROLL,
        ),
        // 1.2.3 (issue 50): some phones keep three finger swipes for themselves; say what does the same without them.
        IntroPage(
            UiText.get("core_intro_three_title", "Three fingers"),
            listOf(
                UiText.get("core_intro_three_up", "Swipe up: your apps"),
                UiText.get("core_intro_three_down", "Swipe down: Control Center"),
                UiText.get("core_intro_three_sideways", "Swipe sideways: Back"),
            ),
            IntroArt.THREE_FINGERS,
            note = NO_THREE_FINGERS_LINE,
        ),
    )

    val SPATIAL_MOUSE_TITLE: String get() = UiText.get("core_intro_spatial_mouse_title", "Spatial Mouse (aim with your phone)")

    /** 1.2.3 (issue 53): how to resize, under the first page's steps. */
    val RESIZE_LINE: String
        get() = UiText.get("core_intro_resize", "To resize a window, put the dot on its corner handle. Hold a finger still until the dot fills, then drag.")

    /** 1.2.3 (issue 50): the Menu's row that opens the Control panel without a swipe. */
    val CONTROL_PANEL_ROW: String get() = UiText.get("core_open_control_center", "Open Control Center")

    /**
     * 1.2.3 (issue 50): under the three finger page, for phones that take the swipes for themselves. No full stop: as the
     * last page its note gets the exit line after it ([trackpad]).
     */
    val NO_THREE_FINGERS_LINE: String
        get() = UiText.format("core_intro_no_three_fingers", "If three fingers don't work: Home opens your apps, and the Menu has %1\$s", CONTROL_PANEL_ROW)

    /**
     * Spatial Mouse, held the way the device is: a phone upright, a landscape handheld ([TrackpadSettings.landscapeHandheld])
     * sideways like a game controller, its top edge tilted up and down to aim (it aims with the top edge at any rotation,
     * 1.1.9). 1.1.10 (issue 35): one short line on the one finger scroll while it is on ([ONE_FINGER_SCROLL_LINE]), as the
     * page's note: a short landscape card holds three steps.
     */
    fun spatialMouse(landscapeHandheld: Boolean, oneFingerScroll: Boolean = true): IntroPage = (if (landscapeHandheld) {
        IntroPage(
            SPATIAL_MOUSE_TITLE,
            listOf(
                UiText.get("core_intro_aim_pick", "Pick Spatial Mouse at the top of the trackpad"),
                UiText.get("core_intro_aim_hold_sideways", "Hold it sideways in both hands, like a game controller"),
                UiText.get("core_intro_aim_tilt", "Tilt the top edge up and down, and turn it left and right, to aim. Tap the screen to click"),
            ),
            IntroArt.AIM_HANDHELD,
        )
    } else {
        IntroPage(
            SPATIAL_MOUSE_TITLE,
            listOf(
                UiText.get("core_intro_aim_pick", "Pick Spatial Mouse at the top of the trackpad"),
                UiText.get("core_intro_aim_hold_upright", "Hold the phone upright, like a remote"),
                UiText.get("core_intro_aim_point", "Point its top edge where you want the dot. Tap the screen to click"),
            ),
            IntroArt.AIM_PHONE,
        )
    }).let { if (oneFingerScroll) it.copy(note = ONE_FINGER_SCROLL_LINE) else it }

    /** 1.1.10 (issue 35): the tutorial's and the Spatial Mouse page's line for the one finger scroll. */
    val ONE_FINGER_SCROLL_LINE: String get() = UiText.get("core_intro_one_finger_scroll", "Slide one finger on the pad to scroll")

    /** The glasses setup page for [model], or null when there is nothing to set on them. */
    fun glassesSetup(model: GlassesModel?): IntroPage? =
        GlassesSetup.forModel(model)?.let { IntroPage(it.title, it.steps, IntroArt.GLASSES_MENU, note = it.check) }

    /** 1.1.10: the label of the quit action, on the Menu's last row and on the ongoing notification. */
    val EXIT_LABEL: String get() = UiText.get("core_exit_pocket_xr", "Exit Pocket XR")

    /**
     * 1.1.10 (a tester: "an exit app button would be nice, as it stays active in the notification bar"): the tutorial's
     * last page says where Exit is (Mike's onboarding rule: a new control is named where the tutorial ends).
     */
    val EXIT_NOTE: String
        get() = UiText.format("core_intro_exit_note", "To quit, open the Menu and tap %1\$s at the end, or tap Exit on the Pocket XR notification.", EXIT_LABEL)

    /** 1.1.10: [EXIT_NOTE] in short, for under a page's own note (the one finger scroll line), so the note stays three lines. */
    val EXIT_NOTE_SHORT: String
        get() = UiText.format("core_intro_exit_note_short", "To quit, tap %1\$s at the end of the Menu or on the notification.", EXIT_LABEL)

    /**
     * The Menu's "How to use the trackpad": the gestures, and Spatial Mouse where the phone can aim ([spatialMouse]).
     * 1.1.10: the last page carries [EXIT_NOTE] ([EXIT_NOTE_SHORT] under its own note, if it has one).
     */
    fun trackpad(landscapeHandheld: Boolean, spatialMouse: Boolean, oneFingerScroll: Boolean = true): List<IntroPage> {
        val pages = if (spatialMouse) GESTURES + spatialMouse(landscapeHandheld, oneFingerScroll) else GESTURES
        val last = pages.last()
        return pages.dropLast(1) + last.copy(note = last.note?.let { "$it. $EXIT_NOTE_SHORT" } ?: EXIT_NOTE)
    }

    /**
     * 1.2.1 (Mike: tell new users about the optional add-on during setup): what the add-on gives, with its Install button
     * under the lines ([com.hayeco.flipspaces.core.AddonSetup]). Next skips it.
     */
    val ADDON: IntroPage get() = IntroPage(
        com.hayeco.flipspaces.core.AddonSetup.TITLE,
        com.hayeco.flipspaces.core.AddonSetup.ITEMS,
        IntroArt.ADDON,
        note = com.hayeco.flipspaces.core.AddonSetup.NOTE,
    )

    /**
     * First run: [trackpad]. 1.1.9 put the plugged-in glasses' setup page first; 1.1.16 (issue 12) moved it to the glasses
     * setup check, which opens by itself once these pages close, with a head turn check after the steps. 1.2.1: the
     * add-on's page ([ADDON]) before the last page, so the tutorial still ends on where Exit is.
     */
    fun firstRun(landscapeHandheld: Boolean, spatialMouse: Boolean, oneFingerScroll: Boolean = true): List<IntroPage> {
        val pages = trackpad(landscapeHandheld, spatialMouse, oneFingerScroll)
        return pages.dropLast(1) + ADDON + pages.last()
    }
}
