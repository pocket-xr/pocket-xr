package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText
import kotlin.math.abs

/**
 * 0.5.37 (Mike: "the Control Panel design looks ugly, greatly improve it; Arrange is outdated since it doesn't give the
 * arrangement options; Circle should be in the arrange options; add useful quick settings such as cursor speed, Spatial
 * Mouse or hand tracking"): the Control Center, one card drawn as a single texture (the app's painter) instead of 0.5.17's
 * separate label tiles. This is its pure model: what it shows for a [ControlCenterState], where each control sits on the
 * card ([ControlCenterLayout], canvas pixels), and what a press does ([ControlCenter.press]).
 */
data class ControlCenterState(
    val follow: FollowMode = FollowMode.BODY_ANCHOR,
    val windows: Int = 0,
    val spatialMouse: Boolean = false,
    val spatialMouseAvailable: Boolean = true,
    val hands: Boolean = false,
    /** 0.5.91: the glasses have a camera to read hands from ([GlassesModel.camera]); without one the tile is dimmed. */
    val handsAvailable: Boolean = true,
    /** 0.5.99: the Space is recording itself ([ControlItem.Record] shows Stop). */
    val recording: Boolean = false,
    val cursorFollowsHead: Boolean = true,
    val snap: Boolean = true,
    /** The trackpad's cursor speed, 0 to 100. */
    val cursorSpeed: Int = 50,
    /** 0.5.45: the saved window presets' names, newest first ([WindowPresets]); the card shows [ControlCenterLayout.PRESETS_SHOWN]. */
    val presets: List<String> = emptyList(),
    /** 0.5.46: which page the card shows ([ControlSheets]), and what the other pages show. */
    val page: ControlPage = ControlPage.MAIN,
    val notes: List<NoteRow> = emptyList(),
    val noteAccess: Boolean = false,
    val wifi: WifiState = WifiState(),
    val bt: BtState = BtState(),
    /** 0.5.46: the minute now, so the notifications' "5 min" is painted again as time passes. */
    val minute: Long = 0,
    /** 1.1.15: a newer Pocket XR on GitHub Releases the user has not put off with Later; a line under the title. */
    val update: String? = null,
)

sealed interface ControlItem {
    data class Follow(val mode: FollowMode) : ControlItem
    data class Arrange(val kind: ArrangeKind, val label: String) : ControlItem
    data object Recentre : ControlItem

    /** 0.5.99: record what the glasses show (the left eye) to a video, or stop. */
    data object Record : ControlItem
    data object Close : ControlItem
    data object SpatialMouse : ControlItem
    data object Hands : ControlItem
    data object CursorFollowsHead : ControlItem
    data object Snap : ControlItem
    data object SpeedDown : ControlItem
    data object SpeedUp : ControlItem

    /** 0.5.45: open the saved preset at [index] (newest first). */
    data class Preset(val index: Int, val name: String) : ControlItem

    /** 0.5.45: save the windows open now as a new preset. */
    data object SavePreset : ControlItem

    /** 0.5.46: the main page's Wi-Fi and Bluetooth tiles, which open their pages ([ControlSheets]). */
    data object Wifi : ControlItem
    data object Bluetooth : ControlItem

    /** 0.5.46: the other pages' controls. */
    data object PageBack : ControlItem
    data object Power : ControlItem
    data class NoteOpen(val index: Int, val key: String) : ControlItem
    data class NoteDismiss(val index: Int, val key: String) : ControlItem
    data object NotesClearAll : ControlItem
    data object NotesGrant : ControlItem
    data object WifiCurrent : ControlItem
    data class WifiSaved(val id: Int, val ssid: String) : ControlItem
    data class WifiForget(val id: Int, val ssid: String) : ControlItem
    data object WifiAdd : ControlItem
    data class BtDeviceRow(val address: String, val name: String) : ControlItem
    data object BtPair : ControlItem
    data object BtGrant : ControlItem
}

/** A control's place on the card, canvas pixels from the top left. */
data class ControlZone(val item: ControlItem, val left: Float, val top: Float, val right: Float, val bottom: Float, val enabled: Boolean = true) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** A section heading on the card; 0.5.46: [title] for a page's title, [message] for a line of explanation. */
data class ControlHeading(val text: String, val x: Float, val baseline: Float, val title: Boolean = false, val message: Boolean = false)

/** What a press asks the Space to do. */
sealed interface ControlCenterAction {
    data class SetFollow(val mode: FollowMode) : ControlCenterAction
    data class Arrange(val kind: ArrangeKind) : ControlCenterAction
    data object Recentre : ControlCenterAction
    data object Closed : ControlCenterAction
    data object ToggleSpatialMouse : ControlCenterAction
    data object ToggleHands : ControlCenterAction
    data object ToggleCursorFollowsHead : ControlCenterAction
    data object ToggleSnap : ControlCenterAction

    /** 0.5.99: start or stop recording the Space. */
    data object ToggleRecord : ControlCenterAction
    data class Speed(val delta: Int) : ControlCenterAction
    data class OpenPreset(val index: Int) : ControlCenterAction
    data object SavePreset : ControlCenterAction

    /** 0.5.46: the card turned to [page] (its data is read again). */
    data class Page(val page: ControlPage) : ControlCenterAction
    data class TogglePower(val page: ControlPage) : ControlCenterAction
    data class OpenNote(val key: String) : ControlCenterAction
    data class DismissNote(val key: String) : ControlCenterAction
    data object ClearNotes : ControlCenterAction
    data object GrantNotes : ControlCenterAction
    data class ForgetWifi(val id: Int, val ssid: String) : ControlCenterAction

    /** 0.5.46: the phone's Wi-Fi or Bluetooth settings as a window in the Space (adding networks, pairing). */
    data class OpenSettings(val page: ControlPage) : ControlCenterAction
    data object GrantBluetooth : ControlCenterAction
    data object Nothing : ControlCenterAction
}

/**
 * The card's layout in canvas pixels: [WIDTH_PX] wide, its height from what it shows.
 *
 * 0.5.45 (Mike, vc65: "the Control Center panel is still too big and should fit entirely in my view without me having to
 * look up and down"): 0.5.37's single column was 1100 px wide and about 1450 tall, 40 by 53 degrees in a 45 by 26 degree
 * view. Now two columns side by side on a wide card: the title row with Recenter and Close; at the left the tracking
 * mode and the arrange layouts; at the right the quick settings and the cursor speed; across the bottom the window
 * presets. 1600 px over [ControlCenter.WIDTH_DEG] 38 degrees, at most about 17 degrees tall (ControlCenter0545Test).
 */
object ControlCenterLayout {
    const val WIDTH_PX = 1600f
    const val PAD = 40f
    const val GAP = 14f
    const val COLUMN_GAP = 36f
    const val TITLE_H = 64f
    const val HEADING_H = 46f
    const val SEGMENT_H = 78f
    const val CHIP_H = 66f
    const val TILE_H = 88f
    const val ROW_H = 66f
    const val SECTION_GAP = 20f

    /** 0.5.45: the Recenter pill's width, in the title row. */
    const val RECENTRE_W = 280f

    /** 0.5.99: the Record pill's width. */
    const val RECORD_W = 230f

    /** 0.5.45: how many saved presets the card offers (the newest), beside Save. */
    const val PRESETS_SHOWN = 3

    /** Speed steps for the [ControlItem.SpeedDown] and [ControlItem.SpeedUp] buttons. */
    const val SPEED_STEP = 10

    data class Layout(val zones: List<ControlZone>, val headings: List<ControlHeading>, val heightPx: Float, val speedBar: FloatArray)

    /**
     * The arrange choices: the layouts for the windows open, Circle always among them from two windows. 0.5.88: and last,
     * Adjust all, which holds every window to push, pull, resize and turn together.
     */
    fun arrangeChoices(windows: Int): List<ArrangeLayouts.Choice> {
        val base = ArrangeLayouts.forCount(windows)
        if (windows < 1) return base
        val adjust = ArrangeLayouts.Choice(ArrangeKind.ADJUST_ALL, ArrangeKind.ADJUST_ALL.label)
        if (windows < 2 || base.any { it.kind == ArrangeKind.CIRCLE }) return base + adjust
        return base + ArrangeLayouts.Choice(ArrangeKind.CIRCLE, ArrangeKind.CIRCLE.label) + adjust
    }

    fun layout(s: ControlCenterState): Layout {
        if (s.page != ControlPage.MAIN) return ControlSheets.layout(s)
        val zones = mutableListOf<ControlZone>()
        val headings = mutableListOf<ControlHeading>()
        val inner = WIDTH_PX - 2 * PAD
        val colW = (inner - COLUMN_GAP) / 2f
        val leftX = PAD
        val rightX = PAD + colW + COLUMN_GAP
        // Title, with Recenter and Close at the right.
        var y = PAD
        headings += ControlHeading(UiText.get("spatial_control_center", "Control Center"), PAD, y + TITLE_H * 0.7f, title = true)
        zones += ControlZone(ControlItem.Close, WIDTH_PX - PAD - TITLE_H, y, WIDTH_PX - PAD, y + TITLE_H)
        zones += ControlZone(ControlItem.Recentre, WIDTH_PX - PAD - TITLE_H - GAP - RECENTRE_W, y + 4f, WIDTH_PX - PAD - TITLE_H - GAP, y + TITLE_H - 4f)
        // 0.5.99: Record, left of Recenter view.
        val recordRight = WIDTH_PX - PAD - TITLE_H - GAP - RECENTRE_W - GAP
        zones += ControlZone(ControlItem.Record, recordRight - RECORD_W, y + 4f, recordRight, y + TITLE_H - 4f)
        y += TITLE_H + GAP
        // 1.1.15: a newer Pocket XR, one line under the title; it is installed from the phone.
        s.update?.let { v ->
            headings += ControlHeading(UiText.format("spatial_update_from_phone", "%1\$s. Update from Pocket XR on the phone.", com.hayeco.flipspaces.core.UpdateCheck.bannerLine(v)), PAD, y + HEADING_H * 0.62f, message = true)
            y += HEADING_H
        }
        val top = y

        // Left column: tracking, one segmented control; then the arrange layouts, two a row.
        var ly = top
        headings += ControlHeading(UiText.get("spatial_tracking", "Tracking"), leftX, ly + HEADING_H * 0.62f)
        ly += HEADING_H
        val modes = FollowMode.entries
        val segW = colW / modes.size
        modes.forEachIndexed { i, m ->
            zones += ControlZone(ControlItem.Follow(m), leftX + i * segW, ly, leftX + (i + 1) * segW, ly + SEGMENT_H, enabled = m.available)
        }
        ly += SEGMENT_H + SECTION_GAP
        val choices = arrangeChoices(s.windows)
        headings += ControlHeading(if (s.windows == 1) UiText.get("spatial_arrange_one_window", "Arrange 1 window") else UiText.format("spatial_arrange_n_windows", "Arrange %1\$d windows", s.windows), leftX, ly + HEADING_H * 0.62f)
        ly += HEADING_H
        if (choices.isEmpty()) {
            ly += CHIP_H * 0.5f
        } else {
            val chipW = (colW - GAP) / 2f
            choices.forEachIndexed { i, c ->
                val t = ly + (i / 2) * (CHIP_H + GAP)
                val l = leftX + (i % 2) * (chipW + GAP)
                zones += ControlZone(ControlItem.Arrange(c.kind, c.label), l, t, l + chipW, t + CHIP_H)
            }
            val rows = (choices.size + 1) / 2
            ly += rows * CHIP_H + (rows - 1) * GAP
        }

        // Right column: the quick settings, a 2 x 3 grid of toggles (0.5.46: Wi-Fi and Bluetooth last); then the cursor speed.
        var ry = top
        headings += ControlHeading(UiText.get("spatial_quick_settings", "Quick settings"), rightX, ry + HEADING_H * 0.62f)
        ry += HEADING_H
        val tileW = (colW - GAP) / 2f
        val toggles = listOf(
            ControlZone(ControlItem.SpatialMouse, 0f, 0f, 0f, 0f, enabled = s.spatialMouseAvailable),
            ControlZone(ControlItem.Hands, 0f, 0f, 0f, 0f, enabled = s.handsAvailable),
            ControlZone(ControlItem.CursorFollowsHead, 0f, 0f, 0f, 0f, enabled = !s.spatialMouse),
            ControlZone(ControlItem.Snap, 0f, 0f, 0f, 0f),
            ControlZone(ControlItem.Wifi, 0f, 0f, 0f, 0f),
            ControlZone(ControlItem.Bluetooth, 0f, 0f, 0f, 0f),
        )
        toggles.forEachIndexed { i, z ->
            val l = rightX + (i % 2) * (tileW + GAP)
            val t = ry + (i / 2) * (TILE_H + GAP)
            zones += z.copy(left = l, top = t, right = l + tileW, bottom = t + TILE_H)
        }
        val tileRows = (toggles.size + 1) / 2
        ry += tileRows * TILE_H + (tileRows - 1) * GAP + SECTION_GAP
        headings += ControlHeading(UiText.get("spatial_cursor_speed", "Cursor speed"), rightX, ry + HEADING_H * 0.62f)
        ry += HEADING_H
        val rightEnd = rightX + colW
        zones += ControlZone(ControlItem.SpeedDown, rightX, ry, rightX + ROW_H, ry + ROW_H, enabled = s.cursorSpeed > 0)
        zones += ControlZone(ControlItem.SpeedUp, rightEnd - ROW_H, ry, rightEnd, ry + ROW_H, enabled = s.cursorSpeed < 100)
        val bar = floatArrayOf(rightX + ROW_H + GAP * 1.5f, ry + ROW_H / 2f - 7f, rightEnd - ROW_H - GAP * 1.5f, ry + ROW_H / 2f + 7f)
        ry += ROW_H

        // Across the bottom: the newest presets and Save.
        y = maxOf(ly, ry) + SECTION_GAP
        headings += ControlHeading(UiText.get("spatial_window_presets", "Window presets"), PAD, y + HEADING_H * 0.62f)
        y += HEADING_H
        val slots = PRESETS_SHOWN + 1
        val chipW = (inner - (slots - 1) * GAP) / slots
        s.presets.take(PRESETS_SHOWN).forEachIndexed { i, name ->
            val l = PAD + i * (chipW + GAP)
            zones += ControlZone(ControlItem.Preset(i, name), l, y, l + chipW, y + ROW_H)
        }
        val saveL = PAD + PRESETS_SHOWN * (chipW + GAP)
        zones += ControlZone(ControlItem.SavePreset, saveL, y, saveL + chipW, y + ROW_H, enabled = s.windows > 0)
        y += ROW_H + PAD
        return Layout(zones, headings, y, bar)
    }
}

/**
 * 0.5.37: the Control Center's place and state in the Space: opened in front of the gaze at [DISTANCE_M], [WIDTH_DEG]
 * wide; its height follows the layout. Hit tests map a ray to card pixels.
 */
class ControlCenter {
    var isOpen = false
        private set
    var yaw = 0f
        private set
    var pitch = 0f
        private set

    /** When it last opened, for its opening motion. */
    var openedAtMs = 0L
        private set

    /** 0.5.46: the page shown ([ControlSheets]). */
    var page = ControlPage.MAIN
        private set

    fun open(gazeYaw: Float, gazePitch: Float, nowMs: Long, page: ControlPage = ControlPage.MAIN) {
        isOpen = true
        this.page = page
        yaw = wrapDegrees(gazeYaw)
        pitch = gazePitch.coerceIn(-SpaceLauncher.MAX_ANCHOR_PITCH, SpaceLauncher.MAX_ANCHOR_PITCH)
        openedAtMs = nowMs
    }

    fun close() {
        isOpen = false
    }

    fun heightDeg(layout: ControlCenterLayout.Layout): Float = layout.heightPx / ControlCenterLayout.WIDTH_PX * WIDTH_DEG

    /** The card as a panel (for drawing and the cursor's depth). */
    fun panel(layout: ControlCenterLayout.Layout): SpacePanel =
        SpacePanel(-3, "", "", "", yaw, pitch, DISTANCE_M, WIDTH_DEG, heightDeg(layout))

    /** Where [ray] meets the card, in canvas pixels, or null off it. */
    fun at(ray: Ray, layout: ControlCenterLayout.Layout): Pair<Float, Float>? {
        if (!isOpen) return null
        val a = Angles.of(ray.dir)
        val h = heightDeg(layout)
        val dx = wrapDegrees(a.yawDeg - yaw)
        val dy = a.pitchDeg - pitch
        if (abs(dx) > WIDTH_DEG / 2f || abs(dy) > h / 2f) return null
        val x = (dx / WIDTH_DEG + 0.5f) * ControlCenterLayout.WIDTH_PX
        val y = (0.5f - dy / h) * layout.heightPx
        return x to y
    }

    fun covers(ray: Ray, layout: ControlCenterLayout.Layout): Boolean = at(ray, layout) != null

    /** The control under [ray], if any. */
    fun zoneAt(ray: Ray, layout: ControlCenterLayout.Layout): ControlZone? =
        at(ray, layout)?.let { (x, y) -> layout.zones.firstOrNull { it.contains(x, y) } }

    private fun turn(to: ControlPage): ControlCenterAction {
        page = to
        return ControlCenterAction.Page(to)
    }

    /** A press along [ray]: the control's action; off the card it closes (Closed); on the card between controls, nothing. */
    fun press(ray: Ray, layout: ControlCenterLayout.Layout): ControlCenterAction {
        if (!isOpen) return ControlCenterAction.Nothing
        if (at(ray, layout) == null) {
            close()
            return ControlCenterAction.Closed
        }
        val z = zoneAt(ray, layout) ?: return ControlCenterAction.Nothing
        if (!z.enabled) return ControlCenterAction.Nothing
        return when (val item = z.item) {
            is ControlItem.Follow -> ControlCenterAction.SetFollow(item.mode)
            is ControlItem.Arrange -> {
                close()
                ControlCenterAction.Arrange(item.kind)
            }
            ControlItem.Recentre -> {
                close()
                ControlCenterAction.Recentre
            }
            // 0.5.99: the card closes, so it is not in the recording.
            ControlItem.Record -> {
                close()
                ControlCenterAction.ToggleRecord
            }
            ControlItem.Close -> {
                close()
                ControlCenterAction.Closed
            }
            ControlItem.SpatialMouse -> ControlCenterAction.ToggleSpatialMouse
            ControlItem.Hands -> ControlCenterAction.ToggleHands
            ControlItem.CursorFollowsHead -> ControlCenterAction.ToggleCursorFollowsHead
            ControlItem.Snap -> ControlCenterAction.ToggleSnap
            ControlItem.SpeedDown -> ControlCenterAction.Speed(-ControlCenterLayout.SPEED_STEP)
            ControlItem.SpeedUp -> ControlCenterAction.Speed(ControlCenterLayout.SPEED_STEP)
            is ControlItem.Preset -> {
                close()
                ControlCenterAction.OpenPreset(item.index)
            }
            ControlItem.SavePreset -> ControlCenterAction.SavePreset
            ControlItem.Wifi -> turn(ControlPage.WIFI)
            ControlItem.Bluetooth -> turn(ControlPage.BLUETOOTH)
            ControlItem.PageBack -> turn(ControlPage.MAIN)
            ControlItem.Power -> ControlCenterAction.TogglePower(page)
            is ControlItem.NoteOpen -> {
                close()
                ControlCenterAction.OpenNote(item.key)
            }
            is ControlItem.NoteDismiss -> ControlCenterAction.DismissNote(item.key)
            ControlItem.NotesClearAll -> ControlCenterAction.ClearNotes
            ControlItem.NotesGrant -> ControlCenterAction.GrantNotes
            ControlItem.WifiCurrent, is ControlItem.WifiSaved -> ControlCenterAction.Nothing
            is ControlItem.WifiForget -> ControlCenterAction.ForgetWifi(item.id, item.ssid)
            ControlItem.WifiAdd -> {
                close()
                ControlCenterAction.OpenSettings(ControlPage.WIFI)
            }
            is ControlItem.BtDeviceRow, ControlItem.BtPair -> {
                close()
                ControlCenterAction.OpenSettings(ControlPage.BLUETOOTH)
            }
            ControlItem.BtGrant -> ControlCenterAction.GrantBluetooth
        }
    }

    companion object {
        // 0.5.38 (Mike: "looks great but it's too close to me"): 1.8 m, the same 40 deg wide.
        const val DISTANCE_M = 1.8f

        /** 0.5.45: 38 degrees (was 40) of the glasses' 45, on the wider two column card ([ControlCenterLayout]). */
        const val WIDTH_DEG = 38f

        /** The opening motion: a fade and a rise from 96 % over this long, ease out. */
        const val OPEN_MS = 220L
    }
}
