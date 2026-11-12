package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.46 (Mike: "replace the Windows presets circle button with a Notifications button so users can see their phone
 * notifications in an extremely well designed way"; "the Control Panel should have a Bluetooth and Wifi button that shows
 * the user active wifi and bluetooth connections when pressed and a way for them to add/remove bluetooth devices and wifi
 * networks"): the Control Center's other pages. Each is laid out on the same card ([ControlCenterLayout.WIDTH_PX] wide)
 * with Back at the top left and Close at the top right, so the Control Center's hit tests, cursor depth and drawing
 * serve them unchanged.
 */
enum class ControlPage(private val key: String, private val english: String) {
    MAIN("spatial_control_center", "Control Center"),
    NOTIFICATIONS("spatial_page_notifications", "Notifications"),
    WIFI("spatial_page_wifi", "Wi-Fi"),
    BLUETOOTH("spatial_page_bluetooth", "Bluetooth"),
    ;

    /** The page's title as shown, in the phone's language. */
    val title: String get() = UiText.get(key, english)
}

/** One phone notification as the Notifications page shows it. */
data class NoteRow(
    val key: String,
    val app: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedMs: Long,
    val clearable: Boolean,
)

/** A saved Wi-Fi network ([id] is the phone's network id, for Forget). */
data class WifiNet(val id: Int, val ssid: String)

/** What the Wi-Fi page shows; [available] false when the phone could not be asked (no Shizuku). */
data class WifiState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val connected: String? = null,
    /** 0 to 4 bars. */
    val bars: Int = 0,
    val detail: String = "",
    val saved: List<WifiNet> = emptyList(),
)

data class BtDevice(val address: String, val name: String, val connected: Boolean)

/** What the Bluetooth page shows; [permitted] false until Pocket XR may read the paired devices. */
data class BtState(
    val permitted: Boolean = false,
    val enabled: Boolean = false,
    val devices: List<BtDevice> = emptyList(),
)

object ControlSheets {
    const val NOTE_ROW_H = 100f
    const val NOTES_SHOWN = 5
    const val LIST_ROW_H = 72f
    const val ROW_GAP = 10f
    const val SAVED_SHOWN = 4
    const val DEVICES_SHOWN = 6
    const val POWER_W = 220f
    const val BUTTON_W = 360f

    fun layout(s: ControlCenterState): ControlCenterLayout.Layout {
        val zones = mutableListOf<ControlZone>()
        val headings = mutableListOf<ControlHeading>()
        val pad = ControlCenterLayout.PAD
        val w = ControlCenterLayout.WIDTH_PX
        val titleH = ControlCenterLayout.TITLE_H
        val headingH = ControlCenterLayout.HEADING_H
        val gap = ControlCenterLayout.GAP
        var y = pad
        // Back, the page's title, its power switch (Wi-Fi, Bluetooth) and Close.
        zones += ControlZone(ControlItem.PageBack, pad, y, pad + titleH, y + titleH)
        headings += ControlHeading(s.page.title, pad + titleH + 20f, y + titleH * 0.7f, title = true)
        zones += ControlZone(ControlItem.Close, w - pad - titleH, y, w - pad, y + titleH)
        val powerRight = w - pad - titleH - gap
        when (s.page) {
            ControlPage.WIFI -> if (s.wifi.available) zones += ControlZone(ControlItem.Power, powerRight - POWER_W, y + 4f, powerRight, y + titleH - 4f)
            ControlPage.BLUETOOTH -> if (s.bt.permitted) zones += ControlZone(ControlItem.Power, powerRight - POWER_W, y + 4f, powerRight, y + titleH - 4f)
            else -> Unit
        }
        y += titleH + gap + 6f
        val inner = w - 2 * pad
        fun button(item: ControlItem, left: Float, width: Float = BUTTON_W, enabled: Boolean = true) {
            zones += ControlZone(item, left, y, left + width, y + ControlCenterLayout.ROW_H, enabled)
        }
        fun message(vararg lines: String) {
            for (l in lines) {
                headings += ControlHeading(l, pad, y + headingH * 0.62f, message = true)
                y += headingH
            }
            y += gap
        }
        when (s.page) {
            ControlPage.MAIN -> Unit
            ControlPage.NOTIFICATIONS -> {
                if (!s.noteAccess) {
                    message(
                        UiText.get("spatial_notes_need_access", "Pocket XR needs notification access to show your phone's notifications here."),
                        UiText.get("spatial_notes_allow_once", "Allow it once and they appear on this page as they arrive."),
                    )
                    button(ControlItem.NotesGrant, pad, 480f)
                    y += ControlCenterLayout.ROW_H
                } else if (s.notes.isEmpty()) {
                    message(UiText.get("spatial_notes_caught_up", "You're all caught up: no notifications on your phone."))
                } else {
                    s.notes.take(NOTES_SHOWN).forEachIndexed { i, n ->
                        val dismissW = if (n.clearable) NOTE_ROW_H else 0f
                        zones += ControlZone(ControlItem.NoteOpen(i, n.key), pad, y, pad + inner - dismissW - (if (n.clearable) gap else 0f), y + NOTE_ROW_H)
                        if (n.clearable) zones += ControlZone(ControlItem.NoteDismiss(i, n.key), pad + inner - dismissW, y, pad + inner, y + NOTE_ROW_H)
                        y += NOTE_ROW_H + ROW_GAP
                    }
                    y += gap
                    val more = s.notes.size - NOTES_SHOWN
                    if (more > 0) headings += ControlHeading(UiText.format("spatial_notes_more", "%1\$d more on your phone", more), pad, y + ControlCenterLayout.ROW_H * 0.6f, message = true)
                    if (s.notes.any { it.clearable }) button(ControlItem.NotesClearAll, pad + inner - 300f, 300f)
                    y += ControlCenterLayout.ROW_H
                }
            }
            ControlPage.WIFI -> {
                if (!s.wifi.available) {
                    message(UiText.get("spatial_wifi_needs_shizuku", "Wi-Fi needs Shizuku running to be read and changed from the Space."), UiText.get("spatial_wifi_add_in_settings", "Add networks in the phone's Wi-Fi settings, opened here as a window."))
                    button(ControlItem.WifiAdd, pad, 480f)
                    y += ControlCenterLayout.ROW_H
                } else {
                    headings += ControlHeading(UiText.get("spatial_wifi_connected", "Connected"), pad, y + headingH * 0.62f)
                    y += headingH
                    zones += ControlZone(ControlItem.WifiCurrent, pad, y, pad + inner, y + 88f, enabled = false)
                    y += 88f + ControlCenterLayout.SECTION_GAP
                    headings += ControlHeading(UiText.get("spatial_wifi_saved_networks", "Saved networks"), pad, y + headingH * 0.62f)
                    y += headingH
                    val others = s.wifi.saved.filter { it.ssid != s.wifi.connected }
                    if (others.isEmpty()) {
                        message(UiText.get("spatial_wifi_no_other_saved", "No other saved networks."))
                    } else {
                        others.take(SAVED_SHOWN).forEach { n ->
                            zones += ControlZone(ControlItem.WifiSaved(n.id, n.ssid), pad, y, pad + inner - 200f - gap, y + LIST_ROW_H, enabled = false)
                            zones += ControlZone(ControlItem.WifiForget(n.id, n.ssid), pad + inner - 200f, y, pad + inner, y + LIST_ROW_H)
                            y += LIST_ROW_H + ROW_GAP
                        }
                        if (others.size > SAVED_SHOWN) headings += ControlHeading(UiText.format("spatial_wifi_more_in_settings", "%1\$d more in Wi-Fi settings", others.size - SAVED_SHOWN), pad, y + headingH * 0.6f, message = true)
                        y += if (others.size > SAVED_SHOWN) headingH else 0f
                    }
                    y += gap
                    button(ControlItem.WifiAdd, pad, 480f)
                    if (s.wifi.connected != null) {
                        s.wifi.saved.firstOrNull { it.ssid == s.wifi.connected }?.let { button(ControlItem.WifiForget(it.id, it.ssid), pad + 480f + gap, 420f) }
                    }
                    y += ControlCenterLayout.ROW_H
                }
            }
            ControlPage.BLUETOOTH -> {
                if (!s.bt.permitted) {
                    message(UiText.get("spatial_bt_need_permission", "Pocket XR needs permission to see your phone's Bluetooth devices."))
                    button(ControlItem.BtGrant, pad, 480f)
                    y += ControlCenterLayout.ROW_H
                } else {
                    val connected = s.bt.devices.count { it.connected }
                    headings += ControlHeading(if (connected == 0) UiText.get("spatial_bt_paired", "Paired devices") else UiText.format("spatial_bt_paired_connected", "Paired devices, %1\$d connected", connected), pad, y + headingH * 0.62f)
                    y += headingH
                    if (s.bt.devices.isEmpty()) {
                        message(if (s.bt.enabled) UiText.get("spatial_bt_none_paired", "No paired devices yet.") else UiText.get("spatial_bt_off", "Bluetooth is off."))
                    } else {
                        val shown = s.bt.devices.sortedByDescending { it.connected }.take(DEVICES_SHOWN)
                        val colW = (inner - gap) / 2f
                        shown.forEachIndexed { i, d ->
                            val left = pad + (i % 2) * (colW + gap)
                            val top = y + (i / 2) * (LIST_ROW_H + ROW_GAP)
                            zones += ControlZone(ControlItem.BtDeviceRow(d.address, d.name), left, top, left + colW, top + LIST_ROW_H)
                        }
                        val rows = (shown.size + 1) / 2
                        y += rows * (LIST_ROW_H + ROW_GAP)
                        if (s.bt.devices.size > DEVICES_SHOWN) {
                            headings += ControlHeading(UiText.format("spatial_bt_more_in_settings", "%1\$d more in Bluetooth settings", s.bt.devices.size - DEVICES_SHOWN), pad, y + headingH * 0.6f, message = true)
                            y += headingH
                        }
                    }
                    y += gap
                    button(ControlItem.BtPair, pad, 480f)
                    y += ControlCenterLayout.ROW_H
                }
            }
        }
        y += pad
        return ControlCenterLayout.Layout(zones, headings, y, floatArrayOf(0f, 0f, 0f, 0f))
    }

    /** "now", "5 min", "2 h", "3 d": how long ago [postedMs] was at [nowMs]. */
    fun ago(postedMs: Long, nowMs: Long): String {
        val s = ((nowMs - postedMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> UiText.get("spatial_ago_now", "now")
            s < 3600 -> UiText.format("spatial_ago_min", "%1\$d min", s / 60)
            s < 86_400 -> UiText.format("spatial_ago_h", "%1\$d h", s / 3600)
            else -> UiText.format("spatial_ago_d", "%1\$d d", s / 86_400)
        }
    }

    /** Bars (0 to 4) for an RSSI in dBm, as Android's own steps. */
    fun bars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    /** The current network and its RSSI from `cmd wifi status`, or null when not connected. */
    fun parseStatus(out: String): Triple<Boolean, String?, Int?> {
        val enabled = out.contains("Wifi is enabled")
        val ssid = Regex("Wifi is connected to \"(.*)\"").find(out)?.groupValues?.get(1)
        val rssi = Regex("RSSI: (-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return Triple(enabled, ssid, rssi)
    }

    /** The saved networks from `cmd wifi list-networks` (one per id; the phone lists each security type). */
    fun parseNetworks(out: String): List<WifiNet> = out.lines().drop(1).mapNotNull { line ->
        val m = Regex("^\\s*(\\d+)\\s+(.+?)\\s{2,}\\S+\\s*$").find(line) ?: return@mapNotNull null
        WifiNet(m.groupValues[1].toInt(), m.groupValues[2].trim())
    }.distinctBy { it.id }
}
