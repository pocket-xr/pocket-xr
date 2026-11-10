package com.hayeco.flipspaces.spatial

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.spatial.DpRect
import com.hayeco.flipspaces.core.spatial.LauncherButton
import com.hayeco.flipspaces.core.spatial.LauncherGrid
import com.hayeco.flipspaces.core.spatial.LauncherIconShape
import com.hayeco.flipspaces.core.spatial.LauncherItem
import com.hayeco.flipspaces.core.spatial.LauncherMenuItem
import com.hayeco.flipspaces.core.spatial.LauncherMenuLook
import com.hayeco.flipspaces.core.spatial.Mat4
import com.hayeco.flipspaces.core.spatial.PhoneStatus
import com.hayeco.flipspaces.core.spatial.XrLauncher
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.LookTween
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 0.5.25 (Mike: "The app launcher must look exactly like, and work exactly like, the Android XR app
 * launcher, with a perfectly transparent background"): draws [XrLauncher] on its panel plane. Nothing
 * behind it: no backing, plate, blur, border or scrim; icons, labels and the clock float in the room.
 * Each app's own icon is drawn in the phone's icon shape (adaptive
 * icons' layers cut by its mask, 1.2.10: older and icon pack icons fitted to it, never on white); every glyph here (the indicator's
 * pin, the menu's info and pin icons, the buttons, the magnifier) is drawn with Canvas strokes, no copied
 * artwork. Text and icons are baked into textures at [PX_PER_DP] and only uploaded (no framebuffer or
 * viewport changes, so it is safe inside the eye passes). GL thread only, except the icon loader.
 */
internal class LauncherGl(private val context: Context) {
    /** Baked textures by content, least recently used first; ids belong to [glContext]. */
    private val textures = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean {
            if (size <= MAX_TEXTURES) return false
            GlKit.deleteTexture(eldest.value)
            return true
        }
    }
    private var glContext: EGLContext? = null

    private val icons = ConcurrentHashMap<String, Bitmap>()
    private val iconsLoading = ConcurrentHashMap.newKeySet<String>()
    private val iconLoader = Executors.newSingleThreadExecutor { Thread(it, "launcher-icons") }

    // 0.5.68: the bundled Inter ([com.hayeco.flipspaces.ui.LookType]) in place of the system sans-serif.
    private val medium: Typeface get() = com.hayeco.flipspaces.ui.LookType.medium
    private val regular: Typeface get() = com.hayeco.flipspaces.ui.LookType.regular

    /** New textures this frame may still bake ([UPLOADS_PER_FRAME]); the rest come on the next frames. */
    private var uploadsLeft = 0
    private var budgetMs = -1L

    private var clockKeyMs = -1L
    private var dateText = ""
    private var clockText = ""

    /** Draws the launcher for this eye; [rowFix] is the renderer's per-row pose fix (null: one pose). */
    fun draw(l: XrLauncher, viewProj: Mat4, nowMs: Long, rowFix: FloatArray?, viewLabel: String) {
        if (!l.visible(nowMs)) return
        checkContext()
        // Both eyes of a frame share one budget, so they draw the same set.
        if (nowMs != budgetMs) {
            budgetMs = nowMs
            uploadsLeft = UPLOADS_PER_FRAME
        }
        val panelAlpha = l.alpha(nowMs)
        if (panelAlpha <= 0.002f) return
        val s = l.scale(nowMs)
        val at = l.placement
        fun mvp(r: DpRect) = viewProj * at.model(r, s)

        // The home tray's date and clock go with page 0's look.
        val tray = l.trayLook()
        if (tray.alpha > 0.01f) {
            updateClock(nowMs)
            // 0.5.100: the date line also carries the phone's status: Wi-Fi, cell bars, battery percent and bar.
            val dateRect = tray.apply(DpRect(LauncherGrid.PANEL_W / 2f - STATUS_W / 2f, LauncherGrid.DATE_Y - 16f, STATUS_W, 32f))
            val clockRect = tray.apply(DpRect(LauncherGrid.PANEL_W / 2f - 300f, LauncherGrid.CLOCK_Y - 60f, 600f, 120f))
            if (dateRect.cx <= tray.clipRight) {
                texture(mvp(dateRect), dateStatusTexture(dateText, status.current()), panelAlpha * tray.alpha, rowFix)
                texture(mvp(clockRect), text("clock", clockText, 600f, 120f, LauncherGrid.CLOCK_SP, LauncherGrid.TRAY_TEXT_COLOR, regular), panelAlpha * tray.alpha, rowFix)
            }
        }

        val focus = l.focusKey
        val pressedKey = l.hovered
        for (p in l.placed()) {
            val a = panelAlpha * p.alpha
            if (a <= 0.002f) continue
            when (val item = p.item) {
                is LauncherItem.App -> {
                    // Keyboard focus, a 16 dp rounded near-black cell.
                    if (item.key == focus) color(mvp(p.rect), LauncherGrid.FOCUS_COLOR, a, LauncherGrid.FOCUS_RADIUS * p.scale, p.rect, rowFix)
                    // Hover scales the icon alone to 1.1.
                    val iconScale = l.iconScale(item.key, nowMs)
                    val icon = p.icon.scaled(iconScale)
                    // 0.5.72 (Mike: "more amazing depth effects", and the same look for the menu the palm and pinch open): the
                    // hovered icon also rises toward the eyes, real depth in side by side 3D, with a soft bloom of light behind it;
                    // its label rises half as far. The layout and the transparent background stay as in 0.5.25.
                    val hov = ((iconScale - 1f) / ICON_HOVER_GROWTH).coerceIn(0f, 1f)
                    if (hov > 0.01f) glow(lifted(viewProj, at.model(icon.scaled(ICON_GLOW), s), Look.DEPTH_CONTROLS * hov), Look.INK, a * ICON_GLOW_ALPHA * hov, rowFix)
                    iconTexture(item.app)?.let { texture(lifted(viewProj, at.model(icon, s), Look.DEPTH_CONTROLS * hov), it, a, rowFix) }
                    val label = XrLauncher.labelOf(item.app)
                    texture(lifted(viewProj, at.model(p.label, s), Look.DEPTH_CONTROLS * hov / 2f), labelTexture(label), a, rowFix)
                }
                is LauncherItem.Button -> {
                    // The ripple's hover and focus layer (white). 0.5.72: eased, and the button rises and glows.
                    val lit = item.key in pressedKey || item.key == focus
                    // Asked by both eyes with the frame's time, it steps once ([LookTween]).
                    val hov = buttonLooks.getOrPut(item.key) { LookTween() }.at(lit, nowMs)
                    val lift = Look.DEPTH_HOVER * 2f * hov
                    if (hov > 0.01f) glow(lifted(viewProj, at.model(p.rect.scaled(ICON_GLOW), s), lift), Look.INK, a * ICON_GLOW_ALPHA * hov, rowFix)
                    // PC VR: a soft blue glow that slowly breathes behind it, so it reads as Pocket XR's own doorway.
                    if (item.button == LauncherButton.PC_VR) {
                        val breath = 0.5f + 0.5f * kotlin.math.sin(nowMs * (2.0 * Math.PI / PcVrButtonLook.PC_VR_BREATH_MS)).toFloat()
                        glow(lifted(viewProj, at.model(p.rect.scaled(PcVrButtonLook.PC_VR_GLOW_SCALE), s), lift), PcVrButtonLook.PC_VR_GLOW, a * (PcVrButtonLook.PC_VR_GLOW_MIN + PcVrButtonLook.PC_VR_GLOW_RANGE * breath), rowFix)
                    }
                    texture(lifted(viewProj, at.model(p.rect, s), lift), buttonTexture(item.button, viewLabel), a, rowFix)
                    if (hov > 0.01f) color(lifted(viewProj, at.model(p.rect, s), lift), Color.WHITE, a * BUTTON_HOVER_ALPHA * hov, p.rect.w / 2f, p.rect, rowFix)
                }
            }
        }

        // Search found nothing: say so where the grid would be.
        if (l.query.isNotEmpty() && l.matches().isEmpty() && l.searchAlpha() > 0.01f) {
            val r = DpRect(LauncherGrid.PANEL_W / 2f - 300f, LauncherGrid.MARGIN_TOP + 60f, 600f, 40f)
            texture(mvp(r), text("none", "No apps found", 600f, 40f, LauncherGrid.LABEL_SP, LauncherGrid.LABEL_COLOR, medium), panelAlpha * l.searchAlpha(), rowFix)
        }

        // The search card on the All apps pages.
        val searchA = l.searchAlpha() * panelAlpha
        if (searchA > 0.01f) {
            val r = LauncherGrid.search()
            // 0.5.83 (Mike: the search input "needs depth design update"): a raised glass field, half a control's lift off
            // the grid at rest, rising the rest of the way on hover with a soft bloom; its rim brightens on hover and turns
            // the accent while it holds a search.
            val hov = searchLook.at(l.searchHovered, nowMs)
            val lift = Look.DEPTH_CONTROLS * (0.5f + 0.5f * hov)
            val m = lifted(viewProj, at.model(r, s), lift)
            if (hov > 0.01f) glow(lifted(viewProj, at.model(r.scaled(1.25f), s), lift), Look.INK, searchA * 0.12f * hov, rowFix)
            val rim = when {
                l.query.isNotEmpty() -> Look.ACCENT
                else -> Look.mix(Look.RIM, Look.RIM_FOCUS, hov)
            }
            color(m, SEARCH_COLOR, searchA * (SEARCH_FILL_ALPHA + 0.15f * hov), r.h / 2f, r, rowFix, rim = Look.alpha(rim, searchA))
            texture(m, searchTexture(l.query), searchA, rowFix)
        }

        // The page indicator.
        val dots = LauncherGrid.dots(l.pageCount())
        dots.forEachIndexed { i, r ->
            val a = panelAlpha * l.dotAlpha(i)
            if (i == 0) texture(mvp(r), pinTexture(), a, rowFix) else color(mvp(r), INDICATOR_COLOR, a, r.w / 2f, r, rowFix)
        }

        // The long press menu.
        l.menu?.let { m ->
            val ma = m.alpha(nowMs) * panelAlpha
            if (ma > 0.002f) {
                // 0.5.72: the long press menu's card in the tokens' glass rim, lifted off the grid.
                color(lifted(viewProj, at.model(m.card, s), Look.DEPTH_DIALOG), LauncherMenuLook.COLOR, ma, LauncherMenuLook.RADIUS, m.card, rowFix, rim = Look.alpha(Look.RIM_FOCUS, ma))
                m.items.forEachIndexed { i, item ->
                    val row = m.row(i)
                    if (l.menuHoverRow == i) color(lifted(viewProj, at.model(row, s), Look.DEPTH_DIALOG), LauncherGrid.LABEL_COLOR, ma * LauncherMenuLook.HOVER_ALPHA, row.h / 2f, row, rowFix)
                    texture(lifted(viewProj, at.model(row, s), Look.DEPTH_DIALOG), menuRowTexture(item), ma, rowFix)
                }
            }
        }

        // A round button's tooltip after 700 ms.
        l.tooltip(nowMs)?.let { b ->
            val button = l.placed().firstOrNull { (it.item as? LauncherItem.Button)?.button == b } ?: return@let
            val w = tooltipWidthDp(b.label)
            val r = DpRect(button.rect.cx - w / 2f, button.rect.bottom + 8f, w, 32f)
            color(mvp(r), LauncherMenuLook.COLOR, panelAlpha * 0.95f, 8f, r, rowFix)
            texture(mvp(r), text("tip:${b.label}", b.label, w, 32f, LauncherMenuLook.TEXT_SP, Color.WHITE, regular), panelAlpha, rowFix)
        }
        GLES30.glBlendFunc(GlKit.BLEND_SRC, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** The Space ends (any thread): the icon loader stops; the textures go with the GL context. */
    fun stop() {
        iconLoader.shutdownNow()
        status.stop()
    }

    /** 0.5.100: the phone's battery, cell and Wi-Fi, read off the GL thread. */
    private val status = PhoneStatusReader(context)

    /**
     * 0.5.100 (Mike: "battery percentage with a battery bar in a well designed way", cell bars, and Wi-Fi when on it):
     * the date, a soft dot, then Wi-Fi's fan, the cell bars, the percent and the battery, centred as one line in the
     * tray's text colour. Bars the signal does not reach are faint; the battery's fill is green while charging (with a
     * bolt) and red when low. Baked again only when the date or something shown changes ([PhoneStatus.key]).
     */
    private fun dateStatusTexture(date: String, s: PhoneStatus): Int =
        baked("datestatus:$date:${s.key}", STATUS_W, 32f) { c, px ->
            val ink = LauncherGrid.TRAY_TEXT_COLOR
            val faint = Color.argb(77, Color.red(ink), Color.green(ink), Color.blue(ink))
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regular
                textSize = LauncherGrid.DATE_SP * px
                color = ink
            }
            val pctText = s.batteryPct?.let { "$it%" }
            val pctPaint = TextPaint(text).apply { textSize = LauncherGrid.DATE_SP * 0.9f * px }
            val gap = 10f * px
            val sep = 30f * px
            val wifiW = 22f * px
            val cellW = 21f * px
            val battW = 29f * px
            var w = text.measureText(date)
            val icons = mutableListOf<Float>()
            if (s.wifiBars != null) icons += wifiW
            if (s.cellBars != null) icons += cellW
            if (pctText != null) icons += pctPaint.measureText(pctText)
            if (s.batteryPct != null) icons += battW
            if (icons.isNotEmpty()) w += sep + icons.sum() + gap * (icons.size - 1)

            val h = 32f * px
            val mid = h / 2f
            val iconH = 15f * px
            val base = mid + iconH / 2f
            var x = (STATUS_W * px - w) / 2f
            val fm = text.fontMetrics
            c.drawText(date, x, mid - (fm.ascent + fm.descent) / 2f, text)
            x += text.measureText(date)
            if (icons.isEmpty()) return@baked
            c.drawCircle(x + sep / 2f, mid, 2f * px, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faint })
            x += sep
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)

            s.wifiBars?.let { bars ->
                // A fan of three arcs over a dot, opening upward.
                val cx = x + wifiW / 2f
                val cy = base - 0.5f * px
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.4f * px
                    strokeCap = Paint.Cap.ROUND
                }
                fill.color = ink
                c.drawCircle(cx, cy - 1f * px, 1.9f * px, fill)
                for (i in 1..PhoneStatus.WIFI_BARS) {
                    val r = (1.9f + 4.6f * i) * px
                    arc.color = if (i <= bars) ink else faint
                    c.drawArc(RectF(cx - r, cy - 1f * px - r, cx + r, cy - 1f * px + r), 225f, 90f, false, arc)
                }
                x += wifiW + gap
            }
            s.cellBars?.let { bars ->
                // Four bars rising left to right.
                val barW = 3.6f * px
                val step = (cellW - barW) / (PhoneStatus.CELL_BARS - 1)
                for (i in 0 until PhoneStatus.CELL_BARS) {
                    val bh = iconH * (i + 1) / PhoneStatus.CELL_BARS
                    fill.color = if (i < bars) ink else faint
                    c.drawRoundRect(RectF(x + i * step, base - bh, x + i * step + barW, base), 1.2f * px, 1.2f * px, fill)
                }
                x += cellW + gap
            }
            pctText?.let {
                val pm = pctPaint.fontMetrics
                c.drawText(it, x, mid - (pm.ascent + pm.descent) / 2f, pctPaint)
                x += pctPaint.measureText(it) + gap
            }
            if (s.batteryPct != null) {
                // The body's outline, its terminal, and the fill inside.
                val bodyW = battW - 3f * px
                val bodyH = 14f * px
                val top = mid - bodyH / 2f
                val body = RectF(x, top, x + bodyW, top + bodyH)
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f * px
                    color = Color.argb(150, Color.red(ink), Color.green(ink), Color.blue(ink))
                }
                c.drawRoundRect(body, 4f * px, 4f * px, outline)
                fill.color = outline.color
                c.drawRoundRect(RectF(body.right + 1.2f * px, mid - 3f * px, body.right + 3f * px, mid + 3f * px), 1f * px, 1f * px, fill)
                val inset = 2.6f * px
                val inner = RectF(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset)
                fill.color = when {
                    s.charging -> Look.PALM
                    s.batteryLow -> Look.DANGER
                    else -> ink
                }
                val fw = inner.width() * s.batteryFill.coerceAtLeast(0.06f)
                c.drawRoundRect(RectF(inner.left, inner.top, inner.left + fw, inner.bottom), 1.8f * px, 1.8f * px, fill)
                if (s.charging) {
                    // A bolt over the middle, white with a dark edge so it reads on the fill and on the empty part.
                    val bx = body.centerX()
                    val bolt = Path().apply {
                        moveTo(bx + 1.2f * px, top - 1.5f * px)
                        lineTo(bx - 3.4f * px, mid + 0.8f * px)
                        lineTo(bx - 0.2f * px, mid + 0.8f * px)
                        lineTo(bx - 1.2f * px, top + bodyH + 1.5f * px)
                        lineTo(bx + 3.4f * px, mid - 0.8f * px)
                        lineTo(bx + 0.2f * px, mid - 0.8f * px)
                        close()
                    }
                    c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1.6f * px
                        strokeJoin = Paint.Join.ROUND
                        color = Look.PAGE
                    })
                    fill.color = Color.WHITE
                    c.drawPath(bolt, fill)
                }
            }
        }

    // ---- drawing ----

    /** A premultiplied bitmap texture (what GLUtils uploads), so its edges blend without a dark fringe. */
    private fun texture(mvp: Mat4, id: Int, alpha: Float, rowFix: FloatArray?) {
        if (id == 0) return
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val prog = GlKit.flat
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, GlKit.FLIP_Y, 0)
        GLES30.glUniform4f(prog.tint, alpha, alpha, alpha, alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glUniform1i(prog.sampler, 0)
        GlKit.drawQuad()
    }

    /** A flat colour over [r], its corners rounded by [radiusDp] (half the height: a pill; half a square: a circle). */
    private fun color(mvp: Mat4, argb: Int, alpha: Float, radiusDp: Float, r: DpRect, rowFix: FloatArray?, rim: Int = 0) {
        GLES30.glBlendFunc(GlKit.BLEND_SRC, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val prog = GlKit.color
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        GlKit.applyRound(prog, radiusDp, r.w, r.h)
        // 0.5.72: the tokens' hairline rim, about 1.5 of the glasses' pixels at the launcher's 1 m (1 dp is 0.7 mm there).
        if (rim ushr 24 != 0) GlKit.applyRim(prog, rim, 1f)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, GlKit.IDENTITY, 0)
        GLES30.glUniform4f(prog.color, Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f, Color.alpha(argb) / 255f * alpha)
        GLES30.glUniform1f(prog.circle, 0f)
        GlKit.drawQuad()
    }

    /** 0.5.72: a soft bloom of light ([GlKit.CIRCLE_GLOW]) in the quad of [mvp]. */
    private fun glow(mvp: Mat4, argb: Int, alpha: Float, rowFix: FloatArray?) {
        GLES30.glBlendFunc(GlKit.BLEND_SRC, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val prog = GlKit.color
        GLES30.glUseProgram(prog.id)
        GlKit.applyRow(prog, rowFix)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, GlKit.IDENTITY, 0)
        GLES30.glUniform4f(prog.color, Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f, Color.alpha(argb) / 255f * alpha)
        GLES30.glUniform1f(prog.circle, GlKit.CIRCLE_GLOW)
        GlKit.drawQuad()
        GLES30.glUniform1f(prog.circle, 0f)
    }

    /**
     * 0.5.72: [viewProj] times [model] brought [share] of its distance nearer, scaled about the eyes' point (the room's origin)
     * as the windows' controls are (SpaceRenderer.lift): the same place and size, only nearer in 3D.
     */
    private fun lifted(viewProj: Mat4, model: Mat4, share: Float): Mat4 {
        if (share <= 0f) return viewProj * model
        val k = 1f - share
        val m = model.m.copyOf()
        for (col in 0..3) for (row in 0..2) m[col * 4 + row] *= k
        return viewProj * Mat4(m)
    }

    /** 0.5.83: the search card's hover, eased. */
    private val searchLook = LookTween()

    /** 0.5.72: each round button's hover, eased. */
    private val buttonLooks = HashMap<String, LookTween>()

    // ---- textures ----

    /** A new EGL context (the surface was recreated): every texture id went with the old one. */
    private fun checkContext() {
        val now = EGL14.eglGetCurrentContext()
        if (now == glContext) return
        glContext = now
        // The ids belong to the old context: forget them without deleting.
        val ids = textures.keys.toList()
        ids.forEach { textures.remove(it) }
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    /** The texture baked for [key], drawing it with [paint] into a [wDp] x [hDp] transparent bitmap the first time. */
    private fun baked(key: String, wDp: Float, hDp: Float, paint: (Canvas, Float) -> Unit): Int {
        textures[key]?.let { return it }
        if (uploadsLeft <= 0) return 0
        uploadsLeft--
        val bmp = Bitmap.createBitmap((wDp * PX_PER_DP).toInt().coerceAtLeast(1), (hDp * PX_PER_DP).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        paint(Canvas(bmp), PX_PER_DP)
        val id = upload(bmp)
        bmp.recycle()
        textures[key] = id
        return id
    }

    /** One line of text centred in a [wDp] x [hDp] box. */
    private fun text(kind: String, value: String, wDp: Float, hDp: Float, sp: Float, argb: Int, face: Typeface): Int =
        baked("$kind:$value:$argb", wDp, hDp) { c, px ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textSize = sp * px
                color = argb
                textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            val y = hDp * px / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(TextUtils.ellipsize(value, p, wDp * px, TextUtils.TruncateAt.END).toString(), wDp * px / 2f, y, p)
        }

    /**
     * The label, 160 dp wide, 16 sp medium, 22 sp lines, centred, at most two lines with an end
     * ellipsis, near-white with no shadow or outline.
     */
    private fun labelTexture(label: String): Int =
        baked("label:$label", LauncherGrid.LABEL_W, LauncherGrid.LABEL_LINE_SP * LauncherGrid.LABEL_LINES) { c, px ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = medium
                textSize = LauncherGrid.LABEL_SP * px
                color = LauncherGrid.LABEL_COLOR
            }
            val lineH = LauncherGrid.LABEL_LINE_SP * px
            val natural = p.fontMetrics.let { it.descent - it.ascent }
            val layout = StaticLayout.Builder.obtain(label, 0, label.length, p, (LauncherGrid.LABEL_W * px).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(LauncherGrid.LABEL_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(lineH - natural, 1f)
                .build()
            c.save()
            c.translate(0f, (lineH - natural) / 2f)
            layout.draw(c)
            c.restore()
        }

    /**
     * 1.2.8 (Discussion #24: icons "pixelated on the edges"): the size app icons are baked at, their size in the glasses' own
     * pixels ([com.hayeco.flipspaces.core.spatial.LauncherIconRaster], set by the renderer for each view). A new size
     * drops the icons baked at the old one. GL thread.
     */
    var iconPx: Int = (LauncherGrid.ICON * PX_PER_DP).toInt()
        set(value) {
            if (value == field || value <= 0) return
            field = value
            textures.keys.filter { it.startsWith("icon:") }.forEach { k -> textures.remove(k)?.let(GlKit::deleteTexture) }
            icons.values.forEach { it.recycle() }
            icons.clear()
            iconsLoading.clear()
        }

    /** The app's icon in the phone's icon shape, once loaded off the GL thread; null until then. */
    private fun iconTexture(app: AppEntry): Int? {
        val size = iconPx
        val key = "icon:$size:${app.key}"
        val loadKey = "${app.key}@$size"
        textures[key]?.let { return it }
        val bmp = if (uploadsLeft > 0) icons.remove(loadKey) else null
        if (bmp != null) {
            uploadsLeft--
            val id = upload(bmp)
            bmp.recycle()
            iconsLoading.remove(loadKey)
            textures[key] = id
            return id
        }
        if (!iconLoader.isShutdown && iconsLoading.add(loadKey)) {
            iconLoader.execute {
                runCatching { roundIcon(app, size) }.getOrNull()?.let { icons[loadKey] = it } ?: iconsLoading.remove(loadKey)
            }
        }
        return null
    }

    /**
     * 1.2.8: the activity's icon from the app's own resources at the highest density (xxxhdpi) when the phone's density
     * gives one smaller than [px], so a bitmap icon is not scaled up; else what Android gives (a theme's or icon pack's
     * replacement included).
     */
    private fun sourceIcon(app: AppEntry, px: Int): Drawable {
        val pm = context.packageManager
        val cn = ComponentName(app.packageName, app.activityName)
        val d = pm.getActivityIcon(cn)
        if (d is AdaptiveIconDrawable || d.intrinsicWidth >= px) return d
        return runCatching {
            val info = pm.getActivityInfo(cn, 0)
            pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(info.iconResource, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull()?.takeIf { it.intrinsicWidth > d.intrinsicWidth } ?: d
    }

    /**
     * 1.2.10 (Discussion #24, second report: "some square, some icon pack icons sitting in a white circle"): every icon in
     * the phone's own icon shape ([iconShape]). An adaptive icon's background and foreground (108 x 108 dp layers whose
     * middle 72 dp is the visible icon) are drawn 1.5 times the shape and cut to it. A legacy or icon pack bitmap is no
     * longer put on a white disc: [LauncherIconShape] says whether it is already a solid shape, scaled to cover the shape
     * with its own edge colour behind it, or a glyph, fitted on a dimmed plate in its own hue. Either way it is drawn
     * straight from the drawable at its final size, not from a copy scaled again.
     *
     * 1.2.8 (Discussion #24: edges "pixelated"): the shape is cut with an anti-aliased mask (DST_IN), not clipPath, whose
     * edge is not anti-aliased; it stops a pixel short of the bitmap's edge so the texture's border is transparent and
     * filtering has nothing opaque to smear there; and the bitmap is [size] square, the icon's size on the glasses.
     */
    private fun roundIcon(app: AppEntry, size: Int): Bitmap? {
        val d: Drawable = sourceIcon(app, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val shape = iconShape(size)
        val box = ICON_EDGE_PX
        val side = size - 2f * ICON_EDGE_PX
        if (d is AdaptiveIconDrawable) {
            val inset = size / 4
            for (layer in listOf(d.background, d.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(c)
            }
        } else {
            (d as? android.graphics.drawable.BitmapDrawable)?.setFilterBitmap(true)
            // Measured at the icon's own size, then drawn again at its place, so nothing is scaled twice.
            val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(probe))
            val px = IntArray(size * size)
            probe.getPixels(px, 0, size, 0, 0, size, size)
            probe.recycle()
            val fit = LauncherIconShape.legacy(px, size, size) ?: return bmp
            c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LauncherIconShape.backdrop(fit) })
            val dst = LauncherIconShape.destination(fit, box, box, side)
            val k = (dst[2] - dst[0]) / fit.width
            d.setBounds(
                Math.round(dst[0] - fit.left * k), Math.round(dst[1] - fit.top * k),
                Math.round(dst[0] + (size - fit.left) * k), Math.round(dst[1] + (size - fit.top) * k),
            )
            d.draw(c)
        }
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        c.drawPath(shape, mask)
        return bmp
    }

    /**
     * 1.2.10: the phone's adaptive icon shape (its icon mask, as its own launcher cuts icons) a pixel inside a [size]
     * square; a circle if the phone gives none.
     */
    private fun iconShape(size: Int): Path {
        val inner = (size - 2f * ICON_EDGE_PX).toInt().coerceAtLeast(1)
        val p = runCatching {
            val a = AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), ColorDrawable(Color.TRANSPARENT))
            a.setBounds(0, 0, inner, inner)
            Path(a.iconMask).takeUnless { it.isEmpty }
        }.getOrNull() ?: Path().apply { addCircle(inner / 2f, inner / 2f, inner / 2f, Path.Direction.CW) }
        p.offset(ICON_EDGE_PX, ICON_EDGE_PX)
        return p
    }

    /** A round button, 94 dp: the dark surface at 0.75, a 2 dp gradient rim, a white glyph in 32 dp padding. */
    private fun buttonTexture(b: LauncherButton, viewLabel: String): Int {
        val still = if (GlassesDisplayControl.steadyOn) "On" else "Off"
        val pcvr = com.hayeco.flipspaces.pcvr.PcVrTileStatus.state
        return baked("button:${b.name}:$viewLabel:$still:$pcvr", LauncherGrid.BUTTON, LauncherGrid.BUTTON) { c, px ->
            val sz = LauncherGrid.BUTTON * px
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(191, 0x1D, 0x20, 0x24) }
            c.drawCircle(sz / 2f, sz / 2f, sz / 2f - px, fill)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * px
                shader = LinearGradient(0f, 0f, sz, sz, intArrayOf(Color.argb(64, 0xF2, 0xDA, 0xFF), Color.argb(26, 0x5A, 0x3D, 0x58), Color.argb(26, 0xF2, 0xDA, 0xFF)), null, Shader.TileMode.CLAMP)
            }
            c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 2f * px, rim)
            val g = Glyph(c, sz / 2f, sz / 2f, (LauncherGrid.BUTTON - 64f) * px)
            when (b) {
                LauncherButton.RECENTRE -> g.crosshair()
                LauncherButton.STEADY -> g.twoWords("Steady", still, regular)
                // 0.5.45: "Depth" over its state, On or Off (was "3D" or "2D", what a press switched to).
                LauncherButton.VIEW -> g.twoWords("Depth", viewLabel, regular)
                LauncherButton.NOTIFICATIONS -> g.bell()
                LauncherButton.CONTROLS -> g.sliders()
                LauncherButton.PC_VR -> {
                    g.headset()
                    // How the PC is: a small dot at the top right, green when SteamVR is ready, amber while it starts.
                    val dot = when (pcvr) {
                        com.hayeco.flipspaces.pcvr.PcVrTileStatus.State.READY -> Color.rgb(0x5B, 0xD9, 0x8B)
                        com.hayeco.flipspaces.pcvr.PcVrTileStatus.State.STARTING -> Color.rgb(0xF2, 0xB8, 0x4B)
                        else -> null
                    }
                    if (dot != null) {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot }
                        c.drawCircle(sz * 0.74f, sz * 0.26f, 6f * px, p)
                    }
                }
            }
        }
    }

    /** Page 0's indicator, a 12 x 16 dp push pin, drawn as a filled outline. */
    private fun pinTexture(): Int = baked("pin", LauncherGrid.PIN_W, LauncherGrid.PIN_H) { c, px ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INDICATOR_COLOR }
        pinPath(c, p, 0f, 0f, px, px)
    }

    /** The search card's magnifier, then the query (or the hint) and, with a query, a clear cross. */
    private fun searchTexture(query: String): Int = baked("search:$query", LauncherGrid.SEARCH_W, LauncherGrid.SEARCH_H) { c, px ->
        val h = LauncherGrid.SEARCH_H * px
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * px
            strokeCap = Paint.Cap.ROUND
            color = LauncherGrid.LABEL_COLOR
        }
        // The magnifier, 24 dp, 20 dp from the left.
        val mx = 20f * px + 10f * px
        val my = h / 2f - 2f * px
        c.drawCircle(mx, my, 7f * px, stroke)
        c.drawLine(mx + 5f * px, my + 5f * px, mx + 11f * px, my + 11f * px, stroke)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regular
            textSize = LauncherGrid.LABEL_SP * px
            color = if (query.isEmpty()) Color.argb(178, 0xC4, 0xC6, 0xCF) else LauncherGrid.LABEL_COLOR
        }
        val fm = p.fontMetrics
        val textLeft = 56f * px
        val textRight = (LauncherGrid.SEARCH_W - if (query.isEmpty()) 24f else LauncherGrid.SEARCH_H) * px
        val shown = if (query.isEmpty()) "Search apps" else TextUtils.ellipsize(query, p, textRight - textLeft, TextUtils.TruncateAt.START).toString()
        c.drawText(shown, textLeft, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        if (query.isNotEmpty()) {
            val cx = (LauncherGrid.SEARCH_W - LauncherGrid.SEARCH_H / 2f) * px
            val r = 6f * px
            c.drawLine(cx - r, h / 2f - r, cx + r, h / 2f + r, stroke)
            c.drawLine(cx + r, h / 2f - r, cx - r, h / 2f + r, stroke)
        }
    }

    /** One menu row's outline icon (24 dp, 20 dp in from the card) and its white 14 sp label. */
    private fun menuRowTexture(item: LauncherMenuItem): Int {
        val w = LauncherMenuLook.WIDTH - 2 * LauncherMenuLook.ROW_MARGIN
        return baked("menu:${item.name}", w, LauncherMenuLook.ROW_H) { c, px ->
            val iconLeft = (LauncherMenuLook.PAD_LEFT - LauncherMenuLook.ROW_MARGIN) * px
            val cy = LauncherMenuLook.ROW_H * px / 2f
            val icon = LauncherMenuLook.ICON * px
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * px
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
            }
            when (item) {
                LauncherMenuItem.APP_INFO -> {
                    val cx = iconLeft + icon / 2f
                    c.drawCircle(cx, cy, 9f * px, stroke)
                    c.drawLine(cx, cy - 1f * px, cx, cy + 5f * px, stroke)
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    c.drawCircle(cx, cy - 4.5f * px, 1.3f * px, dot)
                }
                LauncherMenuItem.PIN, LauncherMenuItem.UNPIN -> {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    // The 12 x 16 pin drawn at 1.25 times in the 24 dp box.
                    val k = 1.25f * px
                    pinPath(c, fill, iconLeft + (icon - 12f * k) / 2f, cy - 8f * k, k, k)
                    if (item == LauncherMenuItem.UNPIN) c.drawLine(iconLeft + 2f * px, cy - 10f * px, iconLeft + icon - 2f * px, cy + 10f * px, stroke)
                }
            }
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regular
                textSize = LauncherMenuLook.TEXT_SP * px
                color = Color.WHITE
            }
            val fm = p.fontMetrics
            c.drawText(item.label, iconLeft + icon + LauncherMenuLook.ICON_GAP * px, cy - (fm.ascent + fm.descent) / 2f, p)
        }
    }

    private fun tooltipWidthDp(label: String): Float {
        val p = Paint().apply {
            typeface = regular
            textSize = LauncherMenuLook.TEXT_SP
        }
        return p.measureText(label) + 24f
    }

    private fun updateClock(nowMs: Long) {
        val minute = System.currentTimeMillis() / 60_000L
        if (minute == clockKeyMs) return
        clockKeyMs = minute
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        dateText = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(now)
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        clockText = java.text.SimpleDateFormat(pattern, locale).format(now)
    }

    /** A white glyph in a [box] px square centred on ([cx], [cy]). */
    private object PcVrButtonLook {
        /** PC VR's breathing glow: one slow breath, a soft blue, low and never gone. */
        const val PC_VR_BREATH_MS = 3_200.0
        const val PC_VR_GLOW_SCALE = 1.55f
        const val PC_VR_GLOW_MIN = 0.10f
        const val PC_VR_GLOW_RANGE = 0.14f
        val PC_VR_GLOW = Color.rgb(0x4C, 0x8D, 0xFF)
    }

    private class Glyph(val c: Canvas, val cx: Float, val cy: Float, val box: Float) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = box * 0.09f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        /** Recentre: a ring with four ticks and a centre dot. */
        fun crosshair() {
            val r = box * 0.3f
            c.drawCircle(cx, cy, r, stroke)
            c.drawLine(cx, cy - box / 2f, cx, cy - r * 0.55f, stroke)
            c.drawLine(cx, cy + r * 0.55f, cx, cy + box / 2f, stroke)
            c.drawLine(cx - box / 2f, cy, cx - r * 0.55f, cy, stroke)
            c.drawLine(cx + r * 0.55f, cy, cx + box / 2f, cy, stroke)
            c.drawCircle(cx, cy, box * 0.07f, fill)
        }

        /** Calibrate hands: the calibration's dots, one in the middle and one in each corner. */
        fun dots() {
            val d = box * 0.4f
            for ((x, y) in listOf(0f to 0f, -d to -d, d to -d, -d to d, d to d)) c.drawCircle(cx + x, cy + y, box * 0.09f, fill)
            c.drawCircle(cx, cy, box * 0.22f, stroke)
        }

        /** Control panel: two sliders, as Quick Settings' glyph. */
        fun sliders() {
            val y1 = cy - box * 0.22f
            val y2 = cy + box * 0.22f
            c.drawLine(cx - box / 2f, y1, cx + box / 2f, y1, stroke)
            c.drawLine(cx - box / 2f, y2, cx + box / 2f, y2, stroke)
            c.drawCircle(cx + box * 0.2f, y1, box * 0.12f, fill)
            c.drawCircle(cx - box * 0.2f, y2, box * 0.12f, fill)
        }

        /** The 3D / 2D button: what a press switches to, in words. */
        /** 0.5.45: [top] in a small regular face over [bottom] in bold, both centred. */
        fun twoWords(top: String, bottom: String, face: Typeface) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textSize = box * 0.34f
                color = Color.argb(200, 255, 255, 255)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(top, cx, cy - box * 0.06f, p)
            p.typeface = Typeface.create(face, Typeface.BOLD)
            p.textSize = box * 0.42f
            p.color = Color.WHITE
            c.drawText(bottom, cx, cy + box * 0.40f, p)
        }

        /** 0.5.46, Notifications: a bell with its clapper. */
        /** A headset seen from the front: the visor with two lenses, and the strap at the sides. */
        fun headset() {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val w = box
            val h = box * 0.5f
            val r = h * 0.3f
            c.drawRoundRect(cx - w / 2f + w * 0.1f, cy - h / 2f, cx + w / 2f - w * 0.1f, cy + h / 2f, r, r, fill)
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1D, 0x20, 0x24) }
            c.drawCircle(cx - w * 0.17f, cy, h * 0.22f, cut)
            c.drawCircle(cx + w * 0.17f, cy, h * 0.22f, cut)
            val nose = android.graphics.Path().apply {
                moveTo(cx - w * 0.07f, cy + h / 2f)
                lineTo(cx, cy + h * 0.18f)
                lineTo(cx + w * 0.07f, cy + h / 2f)
                close()
            }
            c.drawPath(nose, cut)
            val strap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255) }
            c.drawRoundRect(cx - w / 2f, cy - h * 0.2f, cx - w / 2f + w * 0.1f, cy + h * 0.2f, 2f, 2f, strap)
            c.drawRoundRect(cx + w / 2f - w * 0.1f, cy - h * 0.2f, cx + w / 2f, cy + h * 0.2f, 2f, 2f, strap)
        }

        fun bell() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = box * 0.07f
                color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val w = box * 0.36f
            val top = cy - box * 0.40f
            val rim = cy + box * 0.22f
            val path = android.graphics.Path().apply {
                moveTo(cx - w, rim)
                quadTo(cx - w * 0.75f, rim - box * 0.08f, cx - w * 0.75f, cy - box * 0.05f)
                cubicTo(cx - w * 0.75f, top, cx + w * 0.75f, top, cx + w * 0.75f, cy - box * 0.05f)
                quadTo(cx + w * 0.75f, rim - box * 0.08f, cx + w, rim)
                close()
            }
            c.drawPath(path, p)
            c.drawLine(cx, top - box * 0.04f, cx, top + box * 0.02f, p)
            p.style = Paint.Style.FILL
            c.drawCircle(cx, rim + box * 0.11f, box * 0.07f, p)
        }

        /** 0.5.45, Window presets: three window outlines, one in front of two. */
        fun presets() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = box * 0.07f
                color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
            }
            val w = box * 0.42f
            val h = box * 0.30f
            val rr = box * 0.06f
            c.drawRoundRect(android.graphics.RectF(cx - box * 0.5f, cy - box * 0.38f, cx - box * 0.5f + w, cy - box * 0.38f + h), rr, rr, p)
            c.drawRoundRect(android.graphics.RectF(cx + box * 0.5f - w, cy - box * 0.38f, cx + box * 0.5f, cy - box * 0.38f + h), rr, rr, p)
            p.style = Paint.Style.FILL
            c.drawRoundRect(android.graphics.RectF(cx - w * 0.6f, cy - box * 0.05f, cx + w * 0.6f, cy - box * 0.05f + h * 1.2f), rr, rr, p)
        }

        fun word(text: String, face: Typeface) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(face, Typeface.BOLD)
                textSize = box * 0.62f
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
        }
    }

    companion object {
        /** 0.5.100: the date and status line's box, dp. */
        const val STATUS_W = 760f

        /** Texture pixels a dp: the glasses show about 1.5 px a dp at 1 m, so 2 with mipmaps stays sharp. */
        const val PX_PER_DP = 2f

        /** 1.2.8: the transparent ring around a baked app icon, so its texture's border is clear. */
        const val ICON_EDGE_PX = 1f
        const val MAX_TEXTURES = 160

        /** At most this many textures baked a frame (a new page's 15 icons and labels come over a few frames). */
        const val UPLOADS_PER_FRAME = 8

        /** The active dot's colour. */
        val INDICATOR_COLOR = Color.rgb(0xE1, 0xE2, 0xE8)

        /** The search card's dark fill, at 0.75. */
        val SEARCH_COLOR = Color.rgb(0x1D, 0x20, 0x24)
        const val SEARCH_FILL_ALPHA = 0.75f

        /** The button ripple's hover layer. */
        const val BUTTON_HOVER_ALPHA = 0.12f

        /** 0.5.72: the hover growth of an icon (to 1.1), so its hover can be read back from its scale. */
        const val ICON_HOVER_GROWTH = 0.1f

        /** 0.5.72: the bloom behind a hovered icon or button, its size against the icon's, and its strength. */
        const val ICON_GLOW = 1.7f
        const val ICON_GLOW_ALPHA = 0.22f

        /** A 12 x 16 push pin at ([x], [y]), [kx] and [ky] px a unit: a cap, a tapered body, a bar and the needle. */
        fun pinPath(c: Canvas, p: Paint, x: Float, y: Float, kx: Float, ky: Float) {
            val path = Path()
            path.addRoundRect(RectF(x + 3f * kx, y, x + 9f * kx, y + 2f * ky), 0.6f * kx, 0.6f * ky, Path.Direction.CW)
            path.moveTo(x + 4f * kx, y + 2f * ky)
            path.lineTo(x + 8f * kx, y + 2f * ky)
            path.lineTo(x + 9f * kx, y + 8f * ky)
            path.lineTo(x + 3f * kx, y + 8f * ky)
            path.close()
            path.addRoundRect(RectF(x + 1.5f * kx, y + 8f * ky, x + 10.5f * kx, y + 10f * ky), 0.6f * kx, 0.6f * ky, Path.Direction.CW)
            path.addRect(RectF(x + 5.4f * kx, y + 10f * ky, x + 6.6f * kx, y + 16f * ky), Path.Direction.CW)
            c.drawPath(path, p)
        }
    }
}
