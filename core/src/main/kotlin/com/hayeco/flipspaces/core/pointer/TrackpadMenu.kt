package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.UiText

/**
 * 1.2.6 (Mike: the trackpad's Menu "has become congested and feels like too much"): what the Menu shows and in what
 * order, apart from the views that draw it ([com.hayeco.flipspaces.PadScreen] in the app). docs/design/menu-reorganization.md
 * has the audit and the reasons.
 *
 * Top down: the Ko-fi pill and the update row ([com.hayeco.flipspaces.core.Support.menuTop]), then a grid of quick tiles
 * for what is used every session, the focused window's own settings, four named sections, and Exit at the end. Rows
 * that do not apply to this phone, screen or moment are left out rather than shown greyed. No setting's stored key
 * changes here: this only orders and filters.
 */
object TrackpadMenu {
    /** The Menu's parts, top down. [title] is null for the parts drawn without a heading. */
    enum class Section(
        private val titleKey: String?,
        private val titleEnglish: String?,
        private val summaryKey: String?,
        private val summaryEnglish: String,
        val opensByDefault: Boolean,
        val collapsible: Boolean,
    ) {
        QUICK(null, null, null, "", true, false),
        THIS_WINDOW("core_menu_this_window", "This window", null, "", true, false),
        SPACE("core_menu_space", "Space", "core_menu_space_summary", "Lock screens, Arrange, presets", true, true),
        TRACKPAD("core_menu_trackpad", "Trackpad and controls", "core_menu_trackpad_summary", "Speeds, scrolling, cursor, game controller", false, true),
        WINDOWS_GLASSES("core_menu_windows_glasses", "Windows and glasses", "core_menu_windows_glasses_summary", "Snap, new instances, curve, glasses check", false, true),
        HELP("core_menu_help", "Help and more", "core_menu_help_summary", "How to use, reset, developer settings", false, true),
        END(null, null, null, "", true, false),
        ;

        val title: String? get() = titleEnglish?.let { UiText.get(titleKey!!, it) }
        val summary: String get() = summaryKey?.let { UiText.get(it, summaryEnglish) } ?: summaryEnglish

        /** Where the section's open or closed state is kept (the "trackpad" preferences), for the collapsible ones. */
        val key: String get() = "menu_open_${name.lowercase()}"
    }

    /** Every Menu item, in its section's order. */
    enum class Item(val section: Section) {
        // Quick tiles: used every session.
        STEADY(Section.QUICK),
        POCKET_MODE(Section.QUICK),
        GAME_KEYS(Section.QUICK),
        CONTROL_CENTER(Section.QUICK),
        RECORD(Section.QUICK),
        UNLOCK(Section.QUICK),
        PHONE_HOME(Section.QUICK),
        SETTINGS(Section.QUICK),

        // The focused window's app.
        TEXT_SIZE(Section.THIS_WINDOW),
        // 1.3.3: one tap to a common shape ([com.hayeco.flipspaces.core.spatial.WindowShape]).
        SHAPE(Section.THIS_WINDOW),
        SIDE_BY_SIDE(Section.THIS_WINDOW),
        SIDE_BY_SIDE_FLAT(Section.THIS_WINDOW),

        SPACE_TRACKING(Section.SPACE),
        LOCK_SCREENS(Section.SPACE),
        // 1.4.6 (John Davis): a small head turn slides the screens ([com.hayeco.flipspaces.core.spatial.HeadScroll]).
        HEAD_SCROLL(Section.SPACE),
        ARRANGE(Section.SPACE),
        ADJUST_ALL(Section.SPACE),
        PRESETS(Section.SPACE),

        CURSOR_SPEED(Section.TRACKPAD),
        SCROLL_SPEED(Section.TRACKPAD),
        STRIP_SPEED(Section.TRACKPAD),
        ONE_FINGER_SCROLL(Section.TRACKPAD),
        STRIP_SIDE(Section.TRACKPAD),
        COVER_BUTTONS_SIDE(Section.TRACKPAD),
        COVER_STRIP_SIDE(Section.TRACKPAD),
        SIDEWAYS_DIRECTION(Section.TRACKPAD),
        CURSOR_FOLLOWS_HEAD(Section.TRACKPAD),
        KEEPS_SCREEN_ON(Section.TRACKPAD),
        GAME_CONTROLLER(Section.TRACKPAD),
        TOUCH_SPOTS(Section.TRACKPAD),
        CLEAR_TOUCH_SPOTS(Section.TRACKPAD),

        SNAP_WINDOWS(Section.WINDOWS_GLASSES),
        NEW_INSTANCE(Section.WINDOWS_GLASSES),
        NEW_WINDOWS_CURVE(Section.WINDOWS_GLASSES),
        GLASSES_CHECK(Section.WINDOWS_GLASSES),
        AIR_DISPLAY(Section.WINDOWS_GLASSES),
        AIR_DISPLAY_MODE(Section.WINDOWS_GLASSES),

        HOW_TO(Section.HELP),
        // 1.3.3: settings to a file and back, to move between the GitHub and Google Play builds.
        BACKUP_SETTINGS(Section.HELP),
        RESTORE_SETTINGS(Section.HELP),
        RESET(Section.HELP),
        DEVELOPER(Section.HELP),
        DEVELOPER_CARD(Section.HELP),

        EXIT(Section.END),
    }

    /** 1.3.4: the Space's own controls that only the Control Center on the glasses shows. */
    val CONTROL_CENTER_ONLY = setOf(Item.SPACE_TRACKING, Item.SNAP_WINDOWS, Item.CURSOR_FOLLOWS_HEAD)

    /** What the Menu needs to know to choose its rows. */
    data class State(
        val cover: Boolean = false,
        val spaceActive: Boolean = false,
        val phoneLocked: Boolean = false,
        val steadyOffered: Boolean = false,
        val gameKeysOn: Boolean = false,
        val recording: Boolean = false,
        val controllerMode: GameControllerMode = GameControllerMode.OFF,
        val textSizeShown: Boolean = false,
        val shapeShown: Boolean = false,
        val sideBySideShown: Boolean = false,
        val sideBySideOn: Boolean = false,
        val windowCount: Int = 0,
        val glassesKnown: Boolean = false,
        val airOffered: Boolean = false,
        val airOn: Boolean = false,
        val directionApplies: Boolean = false,
        val developerShown: Boolean = false,
    )

    /** Whether [item] shows in [s]. */
    fun shows(item: Item, s: State): Boolean = when (item) {
        // Steady mode is the glasses' own; only glasses that have it.
        Item.STEADY -> s.steadyOffered
        // These act on the Space; without it they could only say "Open the Space first". A mode that is on stays, so
        // it can be turned off.
        Item.POCKET_MODE, Item.CONTROL_CENTER -> s.spaceActive
        Item.GAME_KEYS -> !s.cover && (s.spaceActive || s.gameKeysOn)
        Item.RECORD -> s.spaceActive || s.recording
        Item.UNLOCK -> s.phoneLocked
        Item.PHONE_HOME, Item.SETTINGS -> true
        Item.TEXT_SIZE -> s.textSizeShown
        Item.SHAPE -> s.shapeShown
        Item.SIDE_BY_SIDE -> s.sideBySideShown
        Item.SIDE_BY_SIDE_FLAT -> s.sideBySideShown && s.sideBySideOn
        // 1.3.4 (a tester: "a lot of redundancy in the menus / UIs (ie multiple ways to do the same thing)"): Tracking,
        // Snap windows and Cursor follows head are the Control Center's on the glasses, so the Menu leaves them out.
        // Arrange and presets stay in both (Mike). [CONTROL_CENTER_ONLY] keeps the list; their rows stay in the app.
        Item.SPACE_TRACKING, Item.SNAP_WINDOWS, Item.CURSOR_FOLLOWS_HEAD -> false
        Item.LOCK_SCREENS, Item.PRESETS, Item.HEAD_SCROLL -> true
        Item.ARRANGE, Item.ADJUST_ALL -> s.windowCount > 0
        Item.CURSOR_SPEED, Item.SCROLL_SPEED, Item.STRIP_SPEED, Item.ONE_FINGER_SCROLL -> true
        Item.STRIP_SIDE -> !s.cover
        Item.COVER_BUTTONS_SIDE, Item.COVER_STRIP_SIDE -> s.cover
        Item.SIDEWAYS_DIRECTION -> !s.cover && s.directionApplies
        Item.KEEPS_SCREEN_ON -> true
        Item.GAME_CONTROLLER -> !s.cover && (s.spaceActive || s.controllerMode != GameControllerMode.OFF)
        Item.TOUCH_SPOTS, Item.CLEAR_TOUCH_SPOTS -> !s.cover && s.controllerMode == GameControllerMode.TOUCH
        Item.NEW_INSTANCE, Item.NEW_WINDOWS_CURVE -> true
        Item.GLASSES_CHECK -> s.glassesKnown
        Item.AIR_DISPLAY -> s.airOffered
        Item.AIR_DISPLAY_MODE -> s.airOffered && s.airOn
        Item.HOW_TO, Item.RESET, Item.DEVELOPER, Item.BACKUP_SETTINGS, Item.RESTORE_SETTINGS -> true
        Item.DEVELOPER_CARD -> s.developerShown
        Item.EXIT -> true
    }

    /** The items [s] shows, top down. */
    fun items(s: State): List<Item> = Item.entries.filter { shows(it, s) }

    /** The sections with something to show in [s], each with its items, top down. */
    fun sections(s: State): List<Pair<Section, List<Item>>> =
        items(s).groupBy { it.section }.toList().sortedBy { it.first.ordinal }

    /** How many quick tiles go across. */
    const val TILES_ACROSS = 4
}
