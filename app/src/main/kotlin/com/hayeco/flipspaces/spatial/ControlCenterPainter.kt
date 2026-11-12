package com.hayeco.flipspaces.spatial

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.spatial.ArrangeKind
import com.hayeco.flipspaces.core.spatial.ControlCenterLayout
import com.hayeco.flipspaces.core.spatial.ControlCenterState
import com.hayeco.flipspaces.core.spatial.ControlItem
import com.hayeco.flipspaces.core.spatial.ControlZone
import com.hayeco.flipspaces.core.spatial.FollowMode

/**
 * 0.5.37 (Mike: "the Control Panel design looks ugly, greatly improve it"): the Control Center card, painted with Android's
 * Canvas into one texture. A dark glass card with rounded corners; a segmented control for the tracking mode; the arrange
 * layouts as chips, each with a small drawing of its layout; the quick settings as toggle tiles in the accent blue when
 * on; the cursor speed as a bar between minus and plus; Recenter across the bottom. The control under the pointer is lit.
 */
/*
 * 0.5.71 ("Light on glass", docs/VISUAL-DIRECTION.md; Mike asked for the Control Center to get the same look): the tokens'
 * glass, rim and one accent ([Look]). The card is painted with no control lit: the renderer lifts the control under the
 * pointer out of the card in 3D ([SpaceRenderer.drawControls]), so a hover no longer repaints and uploads the whole card.
 */
internal object ControlCenterPainter {
    private fun t(id: Int, vararg args: Any): String = FlipSpacesApp.text(id, *args)

    private const val ACCENT = Look.ACCENT_DEEP
    private const val ACCENT_HOT = Look.ACCENT
    private const val WHITE = 0xFFFFFFFF.toInt()
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    // 0.5.68: the bundled Inter ([com.hayeco.flipspaces.ui.LookType]).
    private val bold: Typeface get() = com.hayeco.flipspaces.ui.LookType.medium
    private val regular: Typeface get() = com.hayeco.flipspaces.ui.LookType.regular

    private fun white(alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)

    /** 0.5.46: an app's icon by package, for the notifications (null draws its initial). */
    var iconOf: (String) -> android.graphics.Bitmap? = { null }

    fun paint(c: Canvas, l: ControlCenterLayout.Layout, s: ControlCenterState, hot: ControlItem?) {
        val w = ControlCenterLayout.WIDTH_PX
        val h = l.heightPx
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.SRC)
        // The glass: a dark, slightly blue body with a soft light falling from the top, and a hairline rim.
        val card = RectF(2f, 2f, w - 2f, h - 2f)
        // 0.5.73: nearly opaque, so the windows behind never read through it.
        glass.shader = LinearGradient(0f, 0f, 0f, h, Look.alpha(Look.GLASS_RAISED or 0xFF000000.toInt(), 0.97f), Look.alpha(Look.GLASS or 0xFF000000.toInt(), 0.97f), Shader.TileMode.CLAMP)
        c.drawRoundRect(card, 56f, 56f, glass)
        glass.shader = null
        stroke.color = Look.RIM_FOCUS
        stroke.strokeWidth = 2.5f
        c.drawRoundRect(card, 56f, 56f, stroke)

        for (hd in l.headings) {
            val title = hd.title
            text.typeface = if (title) bold else regular
            text.textSize = if (title) 40f else 24f
            text.color = if (title) WHITE else if (hd.message) white(0.78f) else white(0.55f)
            text.letterSpacing = if (title || hd.message) 0f else 0.04f
            c.drawText(hd.text, hd.x, hd.baseline, text)
        }
        text.letterSpacing = 0f

        val follows = l.zones.filter { it.item is ControlItem.Follow }
        if (follows.isNotEmpty()) {
            // The segmented control's track, then each segment.
            val track = RectF(follows.first().left, follows.first().top, follows.last().right, follows.last().bottom)
            fill.color = white(0.07f)
            c.drawRoundRect(track, 42f, 42f, fill)
        }
        for (z in l.zones) paintZone(c, z, s, hot == z.item, l)
    }

    private fun rect(z: ControlZone, inset: Float = 0f) = RectF(z.left + inset, z.top + inset, z.right - inset, z.bottom - inset)

    private fun centred(c: Canvas, s: String, cx: Float, baseline: Float, size: Float, color: Int, face: Typeface = bold) {
        text.typeface = face
        text.textSize = size
        text.color = color
        c.drawText(s, cx - text.measureText(s) / 2f, baseline, text)
    }

    private fun paintZone(c: Canvas, z: ControlZone, s: ControlCenterState, hot: Boolean, l: ControlCenterLayout.Layout) {
        val r = rect(z)
        val cx = r.centerX()
        val cy = r.centerY()
        val dim = if (z.enabled) 1f else 0.32f
        when (val item = z.item) {
            ControlItem.Close -> {
                fill.color = if (hot) white(0.22f) else white(0.10f)
                c.drawCircle(cx, cy, r.width() / 2f - 6f, fill)
                stroke.color = WHITE
                stroke.strokeWidth = 5f
                stroke.strokeCap = Paint.Cap.ROUND
                val k = r.width() * 0.16f
                c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
                c.drawLine(cx + k, cy - k, cx - k, cy + k, stroke)
            }
            is ControlItem.Follow -> {
                val selected = s.follow == item.mode
                val pill = rect(z, 6f)
                if (selected) {
                    fill.color = white(0.92f)
                    c.drawRoundRect(pill, 36f, 36f, fill)
                } else if (hot && z.enabled) {
                    fill.color = white(0.14f)
                    c.drawRoundRect(pill, 36f, 36f, fill)
                }
                val fg = if (selected) Color.rgb(18, 20, 28) else white(0.9f * dim)
                centred(c, item.mode.label, cx, cy - 2f, 26f, fg)
                centred(c, if (item.mode == FollowMode.SPACE_LOCKED && !item.mode.available) t(R.string.space_cc_6dof_unavailable) else item.mode.dof, cx, cy + 26f, 19f, if (selected) Color.argb(160, 18, 20, 28) else white(0.5f * dim), regular)
            }
            is ControlItem.Arrange -> {
                fill.color = if (hot) ACCENT else white(0.08f)
                c.drawRoundRect(r, 26f, 26f, fill)
                // A small drawing of the layout at the left, the name beside it.
                val icon = RectF(r.left + 20f, r.top + 16f, r.left + 20f + 56f, r.bottom - 16f)
                drawLayoutGlyph(c, item.kind, icon, if (hot) WHITE else white(0.75f))
                text.typeface = bold
                text.textSize = 25f
                text.color = WHITE
                c.drawText(item.label, icon.right + 20f, cy + 9f, text)
            }
            ControlItem.SpatialMouse, ControlItem.Hands, ControlItem.CursorFollowsHead, ControlItem.Snap, ControlItem.Wifi, ControlItem.Bluetooth -> {
                val on = when (item) {
                    ControlItem.SpatialMouse -> s.spatialMouse
                    ControlItem.Hands -> s.hands
                    ControlItem.CursorFollowsHead -> s.cursorFollowsHead
                    ControlItem.Wifi -> s.wifi.enabled
                    ControlItem.Bluetooth -> s.bt.enabled
                    else -> s.snap
                }
                fill.color = when {
                    on && hot -> ACCENT_HOT
                    on -> Color.argb((255 * dim).toInt(), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT))
                    hot && z.enabled -> white(0.16f)
                    else -> white(0.08f)
                }
                c.drawRoundRect(r, 30f, 30f, fill)
                // A round badge with the toggle's mark at the left.
                val bx = r.left + 46f
                fill.color = if (on) white(0.25f) else white(0.10f)
                c.drawCircle(bx, cy, 26f, fill)
                drawToggleGlyph(c, item, bx, cy, white(0.95f * dim))
                val name = when (item) {
                    ControlItem.SpatialMouse -> t(R.string.space_cc_spatial_mouse)
                    ControlItem.Hands -> t(R.string.space_cc_hand_tracking)
                    ControlItem.CursorFollowsHead -> t(R.string.space_cc_cursor_follows_head)
                    ControlItem.Wifi -> t(R.string.space_wifi)
                    ControlItem.Bluetooth -> t(R.string.space_bluetooth)
                    else -> t(R.string.space_cc_snap_windows)
                }
                text.typeface = bold
                text.textSize = 25f
                text.color = white(dim)
                c.drawText(name, bx + 42f, cy - 3f, text)
                text.typeface = regular
                text.textSize = 21f
                text.color = white(0.65f * dim)
                val status = when (item) {
                    // 0.5.46: what each is connected to, and a chevron: a press opens its page.
                    ControlItem.Wifi -> if (!s.wifi.available) t(R.string.space_cc_tap_for_details) else s.wifi.connected ?: if (s.wifi.enabled) t(R.string.space_cc_not_connected) else t(R.string.space_off)
                    ControlItem.Bluetooth -> if (!s.bt.permitted) t(R.string.space_cc_tap_for_details) else s.bt.devices.count { it.connected }.let { n -> if (!s.bt.enabled) t(R.string.space_off) else if (n == 0) t(R.string.space_cc_no_devices) else if (n == 1) s.bt.devices.first { it.connected }.name else FlipSpacesApp.plural(R.plurals.space_cc_devices_connected, n, n) }
                    // 0.5.91: hands need the glasses' camera (the Aura's); the One has none.
                    ControlItem.Hands -> if (!z.enabled) t(R.string.space_cc_no_camera) else if (on) t(R.string.space_on) else t(R.string.space_off)
                    else -> if (!z.enabled) t(R.string.space_cc_not_with_spatial_mouse) else if (on) t(R.string.space_on) else t(R.string.space_off)
                }
                c.drawText(ellipsized(status, r.right - bx - 90f), bx + 42f, cy + 25f, text)
                if (item == ControlItem.Wifi || item == ControlItem.Bluetooth) chevron(c, r.right - 30f, cy, white(0.7f))
            }
            ControlItem.SpeedDown, ControlItem.SpeedUp -> {
                fill.color = if (hot && z.enabled) white(0.22f) else white(0.10f)
                c.drawCircle(cx, cy, r.width() / 2f - 6f, fill)
                stroke.color = white(dim)
                stroke.strokeWidth = 5f
                stroke.strokeCap = Paint.Cap.ROUND
                val k = r.width() * 0.17f
                c.drawLine(cx - k, cy, cx + k, cy, stroke)
                if (item == ControlItem.SpeedUp) c.drawLine(cx, cy - k, cx, cy + k, stroke)
                if (item == ControlItem.SpeedUp) {
                    // The bar between the two buttons, filled to the speed, and the value over its end.
                    val b = l.speedBar
                    fill.color = white(0.14f)
                    c.drawRoundRect(RectF(b[0], b[1], b[2], b[3]), 7f, 7f, fill)
                    val x = b[0] + (b[2] - b[0]) * s.cursorSpeed / 100f
                    fill.color = ACCENT
                    c.drawRoundRect(RectF(b[0], b[1], x.coerceAtLeast(b[0] + 14f), b[3]), 7f, 7f, fill)
                    fill.color = WHITE
                    c.drawCircle(x, (b[1] + b[3]) / 2f, 15f, fill)
                    centred(c, "${s.cursorSpeed}", (b[0] + b[2]) / 2f, b[1] - 18f, 24f, white(0.7f), regular)
                }
            }
            ControlItem.Recentre -> {
                fill.color = if (hot) white(0.20f) else white(0.10f)
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                centred(c, t(R.string.space_cc_recenter_view), cx, cy + 9f, 25f, WHITE)
            }
            // 0.5.99: a red dot and Record; while recording, a red pill with a white square and Stop.
            ControlItem.Record -> {
                val rec = s.recording
                fill.color = when {
                    rec -> Color.rgb(214, 48, 48)
                    hot -> white(0.20f)
                    else -> white(0.10f)
                }
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                val mx = r.left + r.height() * 0.62f
                if (rec) {
                    fill.color = WHITE
                    c.drawRoundRect(RectF(mx - 10f, cy - 10f, mx + 10f, cy + 10f), 3f, 3f, fill)
                } else {
                    fill.color = Color.rgb(235, 64, 64)
                    c.drawCircle(mx, cy, 11f, fill)
                }
                centred(c, if (rec) t(R.string.space_cc_stop) else t(R.string.space_cc_record), cx + r.height() * 0.22f, cy + 9f, 25f, WHITE)
            }
            ControlItem.PageBack -> {
                fill.color = if (hot) white(0.22f) else white(0.10f)
                c.drawCircle(cx, cy, r.width() / 2f - 6f, fill)
                stroke.color = WHITE
                stroke.strokeWidth = 5f
                stroke.strokeCap = Paint.Cap.ROUND
                val k = r.width() * 0.13f
                c.drawLine(cx + k * 0.5f, cy - k, cx - k * 0.6f, cy, stroke)
                c.drawLine(cx - k * 0.6f, cy, cx + k * 0.5f, cy + k, stroke)
            }
            ControlItem.Power -> {
                // 0.5.46: the page's switch, a pill with its knob at the right while on.
                val on = if (s.page == com.hayeco.flipspaces.core.spatial.ControlPage.WIFI) s.wifi.enabled else s.bt.enabled
                fill.color = if (on) (if (hot) ACCENT_HOT else ACCENT) else if (hot) white(0.22f) else white(0.12f)
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                fill.color = WHITE
                val knobR = r.height() / 2f - 8f
                c.drawCircle(if (on) r.right - r.height() / 2f else r.left + r.height() / 2f, cy, knobR, fill)
                centred(c, if (on) t(R.string.space_on) else t(R.string.space_off), if (on) r.left + (r.width() - r.height()) / 2f else r.right - (r.width() - r.height()) / 2f, cy + 9f, 25f, WHITE)
            }
            is ControlItem.NoteOpen -> {
                val n = s.notes.getOrNull(item.index) ?: return
                fill.color = if (hot) white(0.14f) else white(0.07f)
                c.drawRoundRect(r, 26f, 26f, fill)
                // The app's icon in a circle at the left.
                val ix = r.left + 18f + 30f
                drawIcon(c, n.packageName, n.app, ix, cy, 30f)
                val tx = ix + 30f + 22f
                val right = r.right - 24f
                text.typeface = regular
                text.textSize = 20f
                text.color = white(0.6f)
                val ago = com.hayeco.flipspaces.core.spatial.ControlSheets.ago(n.postedMs, System.currentTimeMillis())
                val agoW = text.measureText(ago)
                c.drawText(ago, right - agoW, r.top + 30f, text)
                c.drawText(ellipsized(n.app, right - agoW - 24f - tx), tx, r.top + 30f, text)
                text.typeface = bold
                text.textSize = 26f
                text.color = WHITE
                c.drawText(ellipsized(n.title.ifBlank { n.app }, right - tx), tx, r.top + 62f, text)
                text.typeface = regular
                text.textSize = 22f
                text.color = white(0.75f)
                c.drawText(ellipsized(n.text.replace('\n', ' '), right - tx), tx, r.top + 89f, text)
            }
            is ControlItem.NoteDismiss -> {
                fill.color = if (hot) white(0.20f) else white(0.07f)
                c.drawRoundRect(r, 26f, 26f, fill)
                stroke.color = white(0.85f)
                stroke.strokeWidth = 4f
                stroke.strokeCap = Paint.Cap.ROUND
                val k = 12f
                c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
                c.drawLine(cx + k, cy - k, cx - k, cy + k, stroke)
            }
            ControlItem.NotesClearAll -> outlineButton(c, z, hot, t(R.string.space_cc_clear_all))
            ControlItem.NotesGrant -> filledButton(c, z, hot, t(R.string.space_cc_allow_notification_access))
            ControlItem.BtGrant -> filledButton(c, z, hot, t(R.string.space_cc_allow_bluetooth_access))
            ControlItem.WifiAdd -> filledButton(c, z, hot, if (s.wifi.available) t(R.string.space_cc_add_network) else t(R.string.space_cc_open_wifi_settings))
            ControlItem.BtPair -> filledButton(c, z, hot, t(R.string.space_cc_pair_device))
            is ControlItem.WifiForget -> outlineButton(c, z, hot, if (r.width() > 300f) t(R.string.space_cc_forget_named, item.ssid) else t(R.string.space_cc_forget))
            ControlItem.WifiCurrent -> {
                fill.color = white(0.07f)
                c.drawRoundRect(r, 26f, 26f, fill)
                val gx = r.left + 52f
                drawWifiGlyph(c, gx, cy + 12f, if (s.wifi.connected != null) s.wifi.bars else 0, if (s.wifi.connected != null) ACCENT else white(0.4f))
                text.typeface = bold
                text.textSize = 28f
                text.color = WHITE
                c.drawText(ellipsized(s.wifi.connected ?: if (s.wifi.enabled) t(R.string.space_cc_not_connected) else t(R.string.space_cc_wifi_off), r.right - gx - 70f), gx + 46f, cy - 2f, text)
                text.typeface = regular
                text.textSize = 21f
                text.color = white(0.65f)
                c.drawText(ellipsized(if (s.wifi.connected != null) s.wifi.detail else t(R.string.space_cc_pick_network), r.right - gx - 70f), gx + 46f, cy + 28f, text)
            }
            is ControlItem.WifiSaved -> {
                fill.color = white(0.05f)
                c.drawRoundRect(r, 22f, 22f, fill)
                val gx = r.left + 46f
                drawWifiGlyph(c, gx, cy + 10f, 4, white(0.45f))
                text.typeface = bold
                text.textSize = 25f
                text.color = white(0.9f)
                c.drawText(ellipsized(item.ssid, r.right - gx - 60f), gx + 40f, cy + 9f, text)
            }
            is ControlItem.BtDeviceRow -> {
                val d = s.bt.devices.firstOrNull { it.address == item.address }
                val on = d?.connected == true
                fill.color = if (hot) white(0.14f) else white(0.07f)
                c.drawRoundRect(r, 22f, 22f, fill)
                val gx = r.left + 44f
                fill.color = if (on) ACCENT else white(0.12f)
                c.drawCircle(gx, cy, 24f, fill)
                drawBtGlyph(c, gx, cy, WHITE)
                text.typeface = bold
                text.textSize = 25f
                text.color = WHITE
                c.drawText(ellipsized(item.name, r.right - gx - 70f), gx + 40f, cy - 3f, text)
                text.typeface = regular
                text.textSize = 20f
                text.color = if (on) Look.ACCENT else white(0.55f)
                c.drawText(if (on) t(R.string.space_cc_connected) else t(R.string.space_cc_paired), gx + 40f, cy + 24f, text)
            }
            is ControlItem.Preset -> {
                // 0.5.45: a saved preset, its name; a press opens its windows.
                fill.color = if (hot) ACCENT else white(0.08f)
                c.drawRoundRect(r, 26f, 26f, fill)
                drawLayoutGlyph(c, ArrangeKind.GRID, RectF(r.left + 20f, r.top + 18f, r.left + 62f, r.bottom - 18f), if (hot) WHITE else white(0.75f))
                text.typeface = bold
                text.textSize = 25f
                text.color = WHITE
                c.drawText(ellipsized(item.name, r.width() - 100f), r.left + 82f, cy + 9f, text)
            }
            ControlItem.SavePreset -> {
                // 0.5.45: save the windows open now; an outline, so it reads apart from the presets.
                if (hot && z.enabled) {
                    fill.color = white(0.14f)
                    c.drawRoundRect(r, 26f, 26f, fill)
                }
                stroke.color = white(0.35f * dim)
                stroke.strokeWidth = 2.5f
                c.drawRoundRect(rect(z, 1.5f), 26f, 26f, stroke)
                centred(c, if (s.presets.isEmpty()) t(R.string.space_cc_save_windows) else t(R.string.space_cc_save_current), cx, cy + 9f, 25f, white(dim))
            }
        }
    }

    /** 0.5.46: a pill filled in the accent with [label]. */
    private fun filledButton(c: Canvas, z: ControlZone, hot: Boolean, label: String) {
        val r = rect(z)
        fill.color = if (hot && z.enabled) ACCENT_HOT else ACCENT
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        centred(c, label, r.centerX(), r.centerY() + 9f, 25f, WHITE)
    }

    /** 0.5.46: an outlined pill with [label]. */
    private fun outlineButton(c: Canvas, z: ControlZone, hot: Boolean, label: String) {
        val r = rect(z)
        if (hot) {
            fill.color = white(0.14f)
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        }
        stroke.color = white(0.35f)
        stroke.strokeWidth = 2.5f
        c.drawRoundRect(rect(z, 1.5f), r.height() / 2f, r.height() / 2f, stroke)
        text.typeface = bold
        text.textSize = 24f
        centred(c, ellipsized(label, r.width() - 40f), r.centerX(), r.centerY() + 9f, 24f, WHITE)
    }

    private fun chevron(c: Canvas, x: Float, y: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 4f
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(x - 6f, y - 10f, x + 4f, y, stroke)
        c.drawLine(x + 4f, y, x - 6f, y + 10f, stroke)
    }

    /** 0.5.46: the notification's app icon in a circle, or its first letter on a colour from its name. */
    private fun drawIcon(c: Canvas, pkg: String, app: String, x: Float, y: Float, radius: Float) {
        val bmp = runCatching { iconOf(pkg) }.getOrNull()
        if (bmp != null) {
            c.drawBitmap(bmp, null, RectF(x - radius, y - radius, x + radius, y + radius), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return
        }
        val hue = (app.hashCode() and 0x7fffffff) % 360
        fill.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.75f))
        c.drawCircle(x, y, radius, fill)
        centred(c, app.take(1).uppercase(), x, y + 10f, 28f, WHITE)
    }

    /** 0.5.46: Wi-Fi's fan, [bars] of 4 lit, its point at [x], [y]. */
    private fun drawWifiGlyph(c: Canvas, x: Float, y: Float, bars: Int, color: Int) {
        stroke.strokeWidth = 4.5f
        stroke.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) {
            stroke.color = if (bars > i) color else Color.argb(70, 255, 255, 255)
            val rr = 9f * i + 4f
            c.drawArc(RectF(x - rr, y - rr, x + rr, y + rr), 225f, 90f, false, stroke)
        }
        fill.color = if (bars > 0) color else Color.argb(70, 255, 255, 255)
        c.drawCircle(x, y, 4f, fill)
    }

    /** 0.5.46: the Bluetooth rune, centred on [x], [y]. */
    private fun drawBtGlyph(c: Canvas, x: Float, y: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 3.5f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val h = 13f
        val w = 7f
        val path = android.graphics.Path().apply {
            moveTo(x - w, y - h / 2f)
            lineTo(x + w, y + h / 2f)
            lineTo(x, y + h)
            lineTo(x, y - h)
            lineTo(x + w, y - h / 2f)
            lineTo(x - w, y + h / 2f)
        }
        c.drawPath(path, stroke)
    }

    /** [s] cut to [maxW] pixels at the current text size, with an ellipsis when cut. */
    private fun ellipsized(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && text.measureText(s.take(n) + "\u2026") > maxW) n--
        return s.take(n) + "\u2026"
    }

    /** A small drawing of [kind]'s layout in [box]: rounded rectangles where its windows go. */
    private fun drawLayoutGlyph(c: Canvas, kind: ArrangeKind, box: RectF, color: Int) {
        fill.color = color
        val w = box.width()
        val h = box.height()
        fun r(x0: Float, y0: Float, x1: Float, y1: Float) =
            c.drawRoundRect(RectF(box.left + x0 * w, box.top + y0 * h, box.left + x1 * w, box.top + y1 * h), 4f, 4f, fill)
        when (kind) {
            ArrangeKind.CENTER -> r(0.15f, 0.2f, 0.85f, 0.8f)
            ArrangeKind.THEATER -> r(0f, 0.15f, 1f, 0.85f)
            ArrangeKind.SIDE_BY_SIDE -> { r(0f, 0.25f, 0.46f, 0.75f); r(0.54f, 0.25f, 1f, 0.75f) }
            ArrangeKind.ACROSS -> { r(0f, 0.3f, 0.3f, 0.7f); r(0.35f, 0.3f, 0.65f, 0.7f); r(0.7f, 0.3f, 1f, 0.7f) }
            ArrangeKind.STACKED -> { r(0.2f, 0f, 0.8f, 0.46f); r(0.2f, 0.54f, 0.8f, 1f) }
            ArrangeKind.MAIN_SIDE -> { r(0f, 0.15f, 0.62f, 0.85f); r(0.7f, 0.15f, 1f, 0.46f); r(0.7f, 0.54f, 1f, 0.85f) }
            ArrangeKind.GRID, ArrangeKind.COLLECT -> { r(0f, 0f, 0.46f, 0.46f); r(0.54f, 0f, 1f, 0.46f); r(0f, 0.54f, 0.46f, 1f); r(0.54f, 0.54f, 1f, 1f) }
            ArrangeKind.MAIN_STRIP -> { r(0.1f, 0f, 0.9f, 0.6f); r(0f, 0.7f, 0.3f, 1f); r(0.35f, 0.7f, 0.65f, 1f); r(0.7f, 0.7f, 1f, 1f) }
            ArrangeKind.CIRCLE -> { r(0.3f, 0.15f, 0.7f, 0.85f); r(0f, 0.3f, 0.24f, 0.7f); r(0.76f, 0.3f, 1f, 0.7f) }
            ArrangeKind.WRAPAROUND -> {
                stroke.color = color
                stroke.strokeWidth = 4f
                c.drawOval(RectF(box.left + 4f, box.top + 6f, box.right - 4f, box.bottom - 6f), stroke)
                r(0.38f, 0f, 0.62f, 0.3f)
            }
            // 0.5.88: two windows inside one outline, held together.
            ArrangeKind.ADJUST_ALL -> {
                stroke.color = color
                stroke.strokeWidth = 3f
                c.drawRoundRect(RectF(box.left + 2f, box.top + 2f, box.right - 2f, box.bottom - 2f), 8f, 8f, stroke)
                r(0.14f, 0.28f, 0.46f, 0.72f)
                r(0.54f, 0.28f, 0.86f, 0.72f)
            }
        }
    }

    /** The toggle tiles' marks: a phone for Spatial Mouse, an open hand, a head with a dot, a magnet like snap. */
    private fun drawToggleGlyph(c: Canvas, item: ControlItem, x: Float, y: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 4f
        stroke.strokeCap = Paint.Cap.ROUND
        fill.color = color
        when (item) {
            // 0.5.46: Wi-Fi's fan and the Bluetooth rune.
            ControlItem.Wifi -> drawWifiGlyph(c, x, y + 10f, 4, color)
            ControlItem.Bluetooth -> drawBtGlyph(c, x, y, color)
            ControlItem.SpatialMouse -> {
                c.drawRoundRect(RectF(x - 9f, y - 15f, x + 9f, y + 15f), 5f, 5f, stroke)
                c.drawCircle(x, y - 22f + 4f, 0f, fill)
                c.drawLine(x + 13f, y - 18f, x + 19f, y - 24f, stroke)
            }
            ControlItem.Hands -> {
                for (i in -2..1) c.drawLine(x + i * 7f + 3f, y + 2f, x + i * 7f + 3f, y - 14f + kotlin.math.abs(i + 0.5f) * 3f, stroke)
                c.drawLine(x - 14f, y + 6f, x - 6f, y - 2f, stroke)
                c.drawRoundRect(RectF(x - 12f, y, x + 14f, y + 16f), 6f, 6f, stroke)
            }
            ControlItem.CursorFollowsHead -> {
                c.drawCircle(x, y, 13f, stroke)
                c.drawCircle(x, y, 4f, fill)
            }
            else -> {
                c.drawRoundRect(RectF(x - 15f, y - 10f, x - 2f, y + 10f), 3f, 3f, stroke)
                c.drawRoundRect(RectF(x + 2f, y - 10f, x + 15f, y + 10f), 3f, 3f, fill)
            }
        }
    }
}
