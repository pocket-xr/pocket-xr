package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import kotlin.math.abs

/** What a launcher tile is. */
sealed interface TileKind {
    /** The search box; shows [query]. A press clears it. */
    data class Search(val query: String) : TileKind

    /** An app in the grid; its top right corner toggles [favourite]. */
    data class App(val app: AppEntry, val favourite: Boolean) : TileKind

    /** A favourite in the top row. */
    data class Favourite(val app: AppEntry) : TileKind

    /** An open panel in the recents strip; the top right corner closes it. */
    data class OpenPanel(val panelId: Int, val label: String) : TileKind

    /** A recently used app that is not open now, in the recents strip. */
    data class Recent(val app: AppEntry) : TileKind

    data object PreviousPage : TileKind
    data object NextPage : TileKind
    data object Recentre : TileKind

    /** 0.5.14: the 3D / 2D switch; shows what a press switches to. */
    data object ViewMode : TileKind

    /** The home button: opens and closes the launcher. */
    data object Home : TileKind
}

/**
 * One tile of the curved launcher. Every tile faces the user from its own direction, so the
 * grid curves around the head; sizes are in degrees, the units panels use.
 */
data class LauncherTile(
    val kind: TileKind,
    val yawDeg: Float,
    val pitchDeg: Float,
    val widthDeg: Float,
    val heightDeg: Float,
    val distanceM: Float = SpaceLauncher.DISTANCE_M,
) {
    /** The tile as a panel, for drawing with the panel quads. */
    fun asPanel(id: Int = -1): SpacePanel =
        SpacePanel(id, "", "", "", yawDeg, pitchDeg, distanceM, widthDeg, heightDeg)

    /** Where the ray hits the tile in 0..1 across and down, or null for a miss. */
    fun hit(ray: Ray): Pair<Float, Float>? {
        val a = Angles.of(ray.dir)
        val dx = wrapDegrees(a.yawDeg - yawDeg)
        val dy = a.pitchDeg - pitchDeg
        if (abs(dx) > widthDeg / 2f || abs(dy) > heightDeg / 2f) return null
        return Pair(dx / widthDeg + 0.5f, 0.5f - dy / heightDeg)
    }
}

/** What a press on the launcher asks the Space to do. */
sealed interface LauncherAction {
    /** Open [app] as a panel in front of the gaze. */
    data class OpenApp(val app: AppEntry) : LauncherAction

    /** Bring an open panel in front of the gaze. */
    data class FocusPanel(val panelId: Int) : LauncherAction

    data class ClosePanel(val panelId: Int) : LauncherAction

    /** The favourites changed: save [keys]. */
    data class Favourites(val keys: List<String>) : LauncherAction

    data object Recentre : LauncherAction

    /** 0.5.14: switch between 3D side by side and 2D, and remember it. */
    data object ToggleView : LauncherAction

    /** Handled inside the launcher (search cleared, page turned, opened or closed). */
    data object Handled : LauncherAction

    /** The press missed everything the launcher shows; the launcher closed. */
    data object Dismissed : LauncherAction
}

/**
 * The Space's own launcher: a curved grid of apps opened by the
 * home button or an open palm, with search and favourites along the top and a recents strip of
 * the open panels (then recently used apps) along the bottom. A pinch on an app opens it as a
 * panel in front of the gaze. Pure model: the renderer draws [tiles] and passes presses in.
 *
 * The home button is body locked: it sits below the horizon and follows the head's heading once
 * the head has turned more than [HOME_FOLLOW_DEG] away, so it is always a short look down away.
 *
 * 0.5.11: tucked away ([HomeReveal]). Mike's "the Apps square is always in the way": at -14
 * degrees it sat inside the bottom of the 37 degree tall view wherever he looked, and a low hand's
 * pinch landed on it. Now it is hidden (not drawn, not hit) until the head looks down past
 * [HomeReveal.SHOW_BELOW_DEG] or a pinch lands on nothing; it fades in at the bottom edge of the
 * view and hides again [HomeReveal.LINGER_MS] after it was last needed.
 */
class SpaceLauncher(val columns: Int = 5, val rows: Int = 3) {
    var isOpen = false
        private set

    /** Direction the open launcher is centred on: where the user looked when it opened. */
    var anchorYaw = 0f
        private set
    var anchorPitch = 0f
        private set

    var query = ""
        private set

    var page = 0
        private set

    private var apps: List<AppEntry> = emptyList()
    private var favouriteKeys: List<String> = emptyList()

    /** Most recent first, by AppEntry.key. */
    private var recentKeys: List<String> = emptyList()

    /** Heading of the home button. */
    var homeYaw = 0f
        private set

    /** Apps as AppCatalog.prepare gives them (sorted, Pocket XR left out). */
    fun setApps(list: List<AppEntry>) {
        apps = list
        page = page.coerceIn(0, pageCount() - 1)
    }

    fun setFavourites(keys: List<String>) {
        favouriteKeys = keys.distinct().take(MAX_FAVOURITES)
    }

    val favourites: List<String> get() = favouriteKeys

    fun setRecents(keys: List<String>) {
        recentKeys = keys.distinct().take(MAX_RECENTS)
    }

    val recents: List<String> get() = recentKeys

    /** Notes an app the user opened, for the recents strip. Returns the new list to save. */
    fun noteOpened(app: AppEntry): List<String> {
        recentKeys = (listOf(app.key) + recentKeys.filterNot { it == app.key }).take(MAX_RECENTS)
        return recentKeys
    }

    fun open(gazeYaw: Float, gazePitch: Float) {
        isOpen = true
        anchorYaw = wrapDegrees(gazeYaw)
        anchorPitch = gazePitch.coerceIn(-MAX_ANCHOR_PITCH, MAX_ANCHOR_PITCH)
    }

    /** Closes the launcher and clears its search (0.4.2: the search stayed after an app opened). */
    fun close() {
        isOpen = false
        clearSearch()
    }

    fun clearSearch() = setQuery("")

    /**
     * How much the open launcher is shrunk so all of it, from the search row to the home button
     * under the recents strip, fits the visible field of view (0.4.2: on the 1920x1080 view its
     * top row was clipped). 1 when it fits as designed. Text keeps its angular size, so search
     * stays readable; only the tiles and gaps shrink.
     */
    var scale = 1f
        private set

    /** Fits the open launcher inside a view of [verticalFovDeg] by [horizontalFovDeg], with [MARGIN_DEG] on each side. */
    fun fitTo(verticalFovDeg: Float, horizontalFovDeg: Float) {
        val v = (verticalFovDeg - 2 * MARGIN_DEG) / designHeight
        val h = (horizontalFovDeg - 2 * MARGIN_DEG) / designWidth
        scale = minOf(1f, v, h).coerceAtLeast(MIN_SCALE)
    }

    private val tile get() = TILE_DEG * scale
    private val gap get() = GAP_DEG * scale
    private val step get() = STEP_DEG * scale
    private val stripH get() = STRIP_DEG * scale
    private val arrow get() = ARROW_DEG * scale
    private val homeSize get() = HOME_SIZE_DEG * scale

    /** The open launcher's height and width in degrees, home button and page arrows included. */
    val heightDeg: Float get() = designHeight * scale
    val widthDeg: Float get() = designWidth * scale

    /** Search row, gap, the grid's rows, recents strip, gap and home button, at scale 1. */
    private val designHeight: Float get() = STRIP_DEG + GAP_DEG + rows * STEP_DEG + STRIP_DEG + GAP_DEG + HOME_SIZE_DEG

    /** The grid's columns and a page arrow with its gap on each side, at scale 1. */
    private val designWidth: Float get() = columns * STEP_DEG - GAP_DEG + 2 * (GAP_DEG + ARROW_DEG)

    fun toggle(gazeYaw: Float, gazePitch: Float) = if (isOpen) close() else open(gazeYaw, gazePitch)

    fun setQuery(text: String) {
        query = text.take(MAX_QUERY)
        page = 0
    }

    fun type(c: Char) = setQuery(query + c)

    fun backspace() = setQuery(query.dropLast(1))

    /** Apps matching the search, in catalog order. */
    fun matches(): List<AppEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return apps
        return apps.filter { needle in it.label.lowercase() || needle in it.packageName.lowercase() }
    }

    val pageSize: Int get() = columns * rows

    fun pageCount(): Int = maxOf(1, (matches().size + pageSize - 1) / pageSize)

    /** Every frame: the home button follows the head's heading lazily. */
    fun followHead(headYaw: Float) {
        // 0.5.11: never while it shows, so it does not jump away from a pointer; it is placed when it appears.
        if (reveal.shown) return
        if (abs(wrapDegrees(headYaw - homeYaw)) > HOME_FOLLOW_DEG) homeYaw = wrapDegrees(headYaw)
    }

    /** 0.5.11: when the closed launcher's home button shows ([HomeReveal]). */
    val reveal = HomeReveal()

    /** 0.5.11: the closed home button's pitch, set where the view's bottom edge was when it appeared. */
    var homePitch = HOME_PITCH
        private set

    /**
     * 0.5.11, every frame: looks after the home button's reveal. [hovered] is true while a pointer
     * is on it. Returns a line for the log when it appears or hides, else null.
     */
    fun updateHome(headYaw: Float, headPitch: Float, verticalFovDeg: Float, nowMs: Long, hovered: Boolean): String? {
        val wasShown = reveal.shown
        val line = reveal.update(headPitch, nowMs, hovered || isOpen)
        if (!wasShown && reveal.shown) placeHome(headYaw, headPitch, verticalFovDeg)
        return line
    }

    /** 0.5.11: a pinch landed on nothing: the home button appears for a moment. */
    fun revealByPinch(headYaw: Float, headPitch: Float, verticalFovDeg: Float, nowMs: Long): String? {
        val wasShown = reveal.shown
        val line = reveal.pinched(nowMs)
        if (!wasShown) placeHome(headYaw, headPitch, verticalFovDeg)
        return line
    }

    /** At the bottom edge of the view, never across its middle. */
    private fun placeHome(headYaw: Float, headPitch: Float, verticalFovDeg: Float) {
        homeYaw = wrapDegrees(headYaw)
        homePitch = HomeReveal.pitchFor(headPitch, verticalFovDeg, HOME_SIZE_DEG)
    }

    /** 0.5.11: the home button can be hit: the launcher is open, or it has faded in enough to be seen. */
    val homeHittable: Boolean get() = isOpen || reveal.hittable

    /**
     * The home button. Closed, it sits below the horizon on the lazily followed heading; open,
     * it moves under the recents strip, where it closes the launcher.
     */
    fun homeTile(): LauncherTile {
        if (!isOpen) return LauncherTile(TileKind.Home, homeYaw, homePitch, HOME_SIZE_DEG, HOME_SIZE_DEG)
        val below = gridTop() - rows * step - stripH - gap - homeSize / 2f
        return LauncherTile(TileKind.Home, anchorYaw, below, homeSize, homeSize)
    }

    /**
     * The open launcher's whole area (0.4.2), as a tile to draw an opaque backdrop with: the
     * panels behind never show through or between its tiles. Null while closed.
     */
    fun backdrop(): LauncherTile? = if (!isOpen) {
        null
    } else {
        LauncherTile(TileKind.Home, anchorYaw, anchorPitch, widthDeg + 2 * gap, heightDeg + 2 * gap, DISTANCE_M + BACKDROP_BEHIND_M)
    }

    /**
     * The grid's top edge. The launcher is centred on [anchorPitch] as a whole: the search row
     * above the grid and the recents strip and home button below it (0.4.0 centred the grid
     * only, which pushed the search row up out of view).
     */
    private fun gridTop(): Float = anchorPitch + heightDeg / 2f - stripH - gap

    /** Column centres, left to right, as yaw offsets from the anchor. */
    private fun columnYaw(col: Int): Float = (col - (columns - 1) / 2f) * step

    /**
     * The tiles of the open launcher, top to bottom: the search box, favourites and recentre
     * along the top, the app grid (with page arrows when there is more than one page), then
     * the recents strip of [openPanels] followed by recently used apps that are not open.
     * Empty while closed. The home button is separate ([homeTile]), always shown.
     */
    fun tiles(openPanels: List<SpacePanel>): List<LauncherTile> {
        if (!isOpen) return emptyList()
        val out = mutableListOf<LauncherTile>()
        val gridWidth = columns * step - gap
        val gridTop = gridTop()
        // Top row: search over the two left columns, favourites, recentre on the right.
        val topPitch = gridTop + gap + stripH / 2f
        val searchWidth = 2 * step - gap
        out += LauncherTile(TileKind.Search(query), anchorYaw + columnYaw(0) - tile / 2f + searchWidth / 2f, topPitch, searchWidth, stripH)
        val byKey = apps.associateBy { it.key }
        val favs = favouriteKeys.mapNotNull { byKey[it] }.take(columns - 3)
        favs.forEachIndexed { i, app ->
            out += LauncherTile(TileKind.Favourite(app), anchorYaw + columnYaw(2 + i), topPitch, tile, stripH)
        }
        out += LauncherTile(TileKind.Recentre, anchorYaw + columnYaw(columns - 1), topPitch, tile, stripH)

        // The grid.
        val shown = matches().drop(page * pageSize).take(pageSize)
        shown.forEachIndexed { i, app ->
            val row = i / columns
            val col = i % columns
            out += LauncherTile(
                TileKind.App(app, app.key in favouriteKeys),
                anchorYaw + columnYaw(col),
                gridTop - step / 2f - row * step,
                tile,
                tile,
            )
        }
        if (pageCount() > 1) {
            val arrowYaw = gridWidth / 2f + gap + arrow / 2f
            val arrowPitch = gridTop - rows * step / 2f
            if (page > 0) out += LauncherTile(TileKind.PreviousPage, anchorYaw - arrowYaw, arrowPitch, arrow, tile)
            if (page < pageCount() - 1) out += LauncherTile(TileKind.NextPage, anchorYaw + arrowYaw, arrowPitch, arrow, tile)
        }

        // Recents strip: open panels, most recently raised first, then recent apps not open.
        val stripPitch = gridTop - rows * step - stripH / 2f
        val openKeys = openPanels.map { "${it.packageName}/${it.activityName}" }.toSet()
        val strip = openPanels.reversed().map { TileKind.OpenPanel(it.id, it.label) } +
            recentKeys.filter { it !in openKeys }.mapNotNull { byKey[it] }.map { TileKind.Recent(it) }
        strip.take(columns).forEachIndexed { i, kind ->
            out += LauncherTile(kind, anchorYaw + columnYaw(i), stripPitch, tile, stripH)
        }
        // 0.5.14: the 3D / 2D switch, right of the Close button under the strip: one look down and a pinch.
        out += viewTile()
        return out
    }

    /** 0.5.14: the 3D / 2D switch beside the open launcher's Close button. */
    fun viewTile(): LauncherTile {
        val home = homeTile()
        return LauncherTile(TileKind.ViewMode, home.yawDeg + step, home.pitchDeg, homeSize, homeSize)
    }

    /** The tile under [ray] and where on it, the home button included. */
    fun hit(ray: Ray, openPanels: List<SpacePanel>): Pair<LauncherTile, Pair<Float, Float>>? {
        val home = homeTile()
        if (homeHittable) home.hit(ray)?.let { return home to it }
        for (t in tiles(openPanels)) t.hit(ray)?.let { return t to it }
        return null
    }

    /** True when the ray is on something the launcher shows (so a press there is the launcher's). */
    fun covers(ray: Ray, openPanels: List<SpacePanel>): Boolean = hit(ray, openPanels) != null

    /**
     * A press (pinch, trackpad tap) along [ray] from a head looking at [gazeYaw], [gazePitch].
     * Null when the launcher is closed and the press missed the home button: the press is not
     * the launcher's and goes to the panels.
     */
    fun press(ray: Ray, openPanels: List<SpacePanel>, gazeYaw: Float, gazePitch: Float): LauncherAction? {
        val found = hit(ray, openPanels)
        if (found == null) {
            if (!isOpen) return null
            close()
            return LauncherAction.Dismissed
        }
        val (tile, at) = found
        val corner = at.first > 1f - BADGE_FRACTION && at.second < BADGE_FRACTION
        return when (val k = tile.kind) {
            TileKind.Home -> {
                toggle(gazeYaw, gazePitch)
                LauncherAction.Handled
            }
            is TileKind.Search -> {
                setQuery("")
                LauncherAction.Handled
            }
            is TileKind.App -> if (corner) {
                LauncherAction.Favourites(toggleFavourite(k.app))
            } else {
                close()
                LauncherAction.OpenApp(k.app)
            }
            is TileKind.Favourite -> {
                close()
                LauncherAction.OpenApp(k.app)
            }
            is TileKind.OpenPanel -> if (corner) {
                LauncherAction.ClosePanel(k.panelId)
            } else {
                close()
                LauncherAction.FocusPanel(k.panelId)
            }
            is TileKind.Recent -> {
                close()
                LauncherAction.OpenApp(k.app)
            }
            TileKind.PreviousPage -> {
                page = (page - 1).coerceAtLeast(0)
                LauncherAction.Handled
            }
            TileKind.NextPage -> {
                page = (page + 1).coerceAtMost(pageCount() - 1)
                LauncherAction.Handled
            }
            TileKind.Recentre -> {
                close()
                LauncherAction.Recentre
            }
            TileKind.ViewMode -> {
                close()
                LauncherAction.ToggleView
            }
        }
    }

    private fun toggleFavourite(app: AppEntry): List<String> {
        favouriteKeys = if (app.key in favouriteKeys) {
            favouriteKeys - app.key
        } else {
            (favouriteKeys + app.key).takeLast(MAX_FAVOURITES)
        }
        return favouriteKeys
    }

    companion object {
        const val DISTANCE_M = 1.0f
        const val TILE_DEG = 8f
        const val GAP_DEG = 1.2f
        const val STEP_DEG = TILE_DEG + GAP_DEG
        const val STRIP_DEG = 5f
        const val ARROW_DEG = 3.5f

        /** Top right corner of a tile, as a fraction of its width and height: the star or the close badge. */
        const val BADGE_FRACTION = 0.3f
        const val HOME_PITCH = -14f
        const val HOME_SIZE_DEG = 4.5f
        const val HOME_FOLLOW_DEG = 25f
        const val MAX_ANCHOR_PITCH = 35f
        const val MAX_FAVOURITES = 8
        const val MAX_RECENTS = 8
        const val MAX_QUERY = 40

        /** Free view kept above, below and beside the open launcher, degrees. */
        const val MARGIN_DEG = 1.5f

        /** The launcher is never shrunk below this, so tiles stay big enough to pinch. */
        const val MIN_SCALE = 0.4f

        /** The backdrop sits this far behind the tiles. */
        const val BACKDROP_BEHIND_M = 0.02f
    }
}

/**
 * 0.5.11: when the closed launcher's home button ("Apps") shows. Hidden by default; it appears
 * when the head looks down past [SHOW_BELOW_DEG] (and stays while it is below [HIDE_ABOVE_DEG], so
 * it does not flicker at the edge) or when a pinch lands on nothing, and hides [LINGER_MS] after the
 * last of those or of a pointer resting on it. It fades in over [FADE_IN_MS] and out over
 * [FADE_OUT_MS]; it can be pressed only once it is at least [HIT_ALPHA] visible, so a pinch never
 * lands on a button that is not there.
 */
class HomeReveal {
    var alpha = 0f
        private set
    private var target = false
    private var lookingDown = false
    private var lastUseMs = Long.MIN_VALUE / 2
    private var lastMs = Long.MIN_VALUE

    /** Showing or fading in. */
    val shown: Boolean get() = target

    val hittable: Boolean get() = target && alpha >= HIT_ALPHA

    /** Why it shows now, for the log. */
    var reason = ""
        private set

    fun update(headPitch: Float, nowMs: Long, inUse: Boolean): String? {
        lookingDown = if (lookingDown) headPitch < -HIDE_ABOVE_DEG else headPitch < -SHOW_BELOW_DEG
        if (lookingDown || inUse) lastUseMs = nowMs
        var line: String? = null
        if (!target && lookingDown) {
            target = true
            reason = "looking down %.0f degrees".fmt(-headPitch)
            line = "Apps button shown: $reason"
        } else if (target && nowMs - lastUseMs >= LINGER_MS) {
            target = false
            line = "Apps button hidden: not used for ${LINGER_MS / 1000} s"
        }
        fade(nowMs)
        return line
    }

    fun pinched(nowMs: Long): String? {
        lastUseMs = nowMs
        if (target) return null
        target = true
        reason = "a pinch on nothing"
        return "Apps button shown: $reason"
    }

    private fun fade(nowMs: Long) {
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceIn(0L, 100L)
        lastMs = nowMs
        alpha = if (target) (alpha + dt / FADE_IN_MS).coerceAtMost(1f) else (alpha - dt / FADE_OUT_MS).coerceAtLeast(0f)
    }

    companion object {
        const val SHOW_BELOW_DEG = 15f
        const val HIDE_ABOVE_DEG = 8f
        const val LINGER_MS = 3_000L
        const val FADE_IN_MS = 200f
        const val FADE_OUT_MS = 500f
        const val HIT_ALPHA = 0.6f

        /** The view's bottom edge plus a margin, the button's centre half its size above it. */
        const val EDGE_MARGIN_DEG = 1.5f

        /** Never higher than this below the view's centre: off the middle of the view. */
        const val MIN_BELOW_CENTRE_DEG = 10f

        fun pitchFor(headPitch: Float, verticalFovDeg: Float, sizeDeg: Float): Float {
            val edge = headPitch - verticalFovDeg / 2f + EDGE_MARGIN_DEG + sizeDeg / 2f
            return minOf(edge, headPitch - MIN_BELOW_CENTRE_DEG).coerceAtLeast(-80f)
        }
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)
