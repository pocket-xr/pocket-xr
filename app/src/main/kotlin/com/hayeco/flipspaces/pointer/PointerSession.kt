package com.hayeco.flipspaces.pointer

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.GlassesHomeActivity
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.SpacesLauncher
import com.hayeco.flipspaces.SpacesState
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.hands.HandPose
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.DockLayout
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.PressKind
import com.hayeco.flipspaces.core.windows.WindowChrome
import com.hayeco.flipspaces.core.windows.WindowFrame
import com.hayeco.flipspaces.core.windows.WindowLimits
import com.hayeco.flipspaces.platform.AddonLink
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.platform.WindowingProbe
import com.hayeco.flipspaces.spatial.SpaceSession
import kotlin.math.roundToInt

/**
 * Pocket XR pointer: turns pointer signals from any provider (hands, trackpad) into a cursor on the glasses and real
 * touches there.
 *
 * 1.1.0: no longer an accessibility service itself. The add-on's pointer service (the `addon` module, reached through
 * [AddonLink]) draws the cursor window, reads the windows and performs the touches; this session runs while it is on
 * and keeps everything else in Pocket XR, as before:
 *
 * - Finds the glasses display with the same DisplaySelector as the launcher.
 * - Has the add-on draw the cursor window on that display.
 * - Reads app window bounds on that display through the add-on (getWindowsOnAllDisplays, API 30).
 * - Runs core's PointerController and has the add-on perform its actions: taps, live drags, and one-shot drags on
 *   DeX's title bars and corners to move and resize windows.
 * - Fills the dock and acts on a pick ([DockLayout] says where the add-on drew each icon).
 */
class PointerSession(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val monitor = DisplayMonitor(context)
    private val installedApps = InstalledApps(context)

    private var glasses: DisplayCandidate? = null
    private var controller: PointerController? = null

    private var windowsCache: List<WindowFrame> = emptyList()
    private var windowsAtMs = 0L
    private var source = HandProviderKind.TRACKPAD
    private var catalog: List<AppEntry> = emptyList()

    /** The dock's items while it is up, and where the add-on drew them. */
    private class DockItem(val label: String, val icon: Bitmap?, val onPick: () -> Unit)
    private var dock: List<DockItem> = emptyList()
    private var dockBoxes: List<DockLayout.Box> = emptyList()
    private var density = 1f

    /**
     * 0.5.19 ("Overlay on display 35 failed", token null, as the glasses' 3D
     * switch replaced the display): a display change is acted on once the changes have settled for
     * [SETTLE_MS]; a removal still at once, so the cursor never outlives its display.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSoon()
        override fun onDisplayRemoved(displayId: Int) {
            main.removeCallbacks(settled)
            refreshDisplay()
        }
        override fun onDisplayChanged(displayId: Int) = refreshSoon()
    }

    private val settled = Runnable { refreshDisplay() }

    private fun refreshSoon() {
        main.removeCallbacks(settled)
        main.postDelayed(settled, SETTLE_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (!c.busy) return
            perform(c.tick(SystemClock.uptimeMillis()))
            main.postDelayed(this, TICK_MS)
        }
    }

    private val hideDock = Runnable { showDockItems(emptyList()) }

    /** 0.5.0: the Space draws its own hand cursors; the cursor window hides its own while it is up. */
    private val spaceListener: (Boolean) -> Unit = { up ->
        main.post {
            AddonLink.call(Unit) { it.spaceUp(up) }
            // 0.5.16: the full screen window no longer sits over the Space.
            Log.i(TAG, if (up) "Cursor window hidden while the Space is up (shown only for a banner, the dock or a preview)" else "Cursor window shown: the Space is down")
        }
    }

    /** The add-on's pointer service came on. Main thread. */
    fun start() {
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        SpaceSession.activeListeners += spaceListener
        AddonLink.call(Unit) { it.spaceUp(SpaceSession.active) }
        refreshDisplay()
        Thread { catalog = AppCatalog.prepare(installedApps.load(), context.packageName) }.start()
    }

    /** It went off: nothing reaches the glasses any more. Main thread. */
    fun stop() {
        SpaceSession.activeListeners -= spaceListener
        runCatching { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        main.removeCallbacksAndMessages(null)
        glasses = null
        controller = null
        dock = emptyList()
        PointerHub.displayChanged(null)
    }

    fun onSignal(signal: PointerSignal, from: HandProviderKind) {
        val c = controller ?: run {
            if (signal is PointerSignal.Down || (signal as? PointerSignal.ForHand)?.signal is PointerSignal.Down) PointerHub.report(context.getString(R.string.sys_pointer_no_display))
            return
        }
        source = from
        perform(c.handle(signal, SystemClock.uptimeMillis(), windows()))
        if (c.busy) {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, TICK_MS)
        }
    }

    fun onHandStatus(status: HandStatus?, from: HandProviderKind) {
        AddonLink.call(Unit) {
            it.hand(status != null, status?.present == true, status?.pinchProgress ?: 0f, status?.pose == HandPose.OPEN_PALM, from.label)
        }
    }

    private fun perform(actions: List<PointerAction>) {
        val displayId = glasses?.id ?: return
        for (action in actions) {
            when (action) {
                is PointerAction.Cursor -> AddonLink.call(Unit) { it.cursor(action.at.x, action.at.y, action.hover?.zone?.ordinal ?: -1, action.pressed) }
                is PointerAction.Tap -> tap(action)
                is PointerAction.DragStart -> AddonLink.call(Unit) { it.dragStart(displayId, action.at.x, action.at.y) }
                is PointerAction.DragTo -> AddonLink.call(Unit) { it.dragTo(action.at.x, action.at.y) }
                is PointerAction.DragEnd -> AddonLink.call(Unit) { it.dragEnd(action.at.x, action.at.y) }
                is PointerAction.Preview -> AddonLink.call(Unit) { it.preview(true, action.bounds.left, action.bounds.top, action.bounds.right, action.bounds.bottom) }
                PointerAction.ClearPreview -> AddonLink.call(Unit) { it.preview(false, 0, 0, 0, 0) }
                is PointerAction.Commit -> {
                    Log.i(TAG, "${action.hit.zone} ${action.hit.window.title}: ${action.hit.window.bounds} -> ${action.bounds}, drag ${action.from} -> ${action.to}")
                    AddonLink.call(Unit) { it.drag(displayId, action.from.x, action.from.y, action.to.x, action.to.y) }
                    windowsAtMs = 0
                }
                PointerAction.ShowDock -> showDock()
                PointerAction.Back -> AddonLink.call(false) { it.back() }.also { Log.i(TAG, "Back from ${source.label}: $it") }
                is PointerAction.PressDecided -> {
                    val what = when (action.kind) {
                        PressKind.TAP -> "tap"
                        PressKind.LONG_PRESS -> "long press"
                        PressKind.DRAG -> "drag"
                    }
                    val by = if (action.pinch) "pinch (${when (action.rightHand) { true -> "right"; false -> "left"; null -> "unknown" }} hand)" else "press"
                    Log.i(TAG, "Press from ${source.label}: $by held ${action.heldMs} ms, tap limit ${action.limitMs} ms: $what")
                }
            }
        }
    }

    private fun tap(action: PointerAction.Tap) {
        val displayId = glasses?.id ?: return
        if (dock.isNotEmpty()) {
            val item = DockLayout.indexAt(dockBoxes, action.at)?.let { dock.getOrNull(it) }
            val onDock = DockLayout.contains(dockBoxes, action.at, density)
            showDockItems(emptyList())
            main.removeCallbacks(hideDock)
            if (item != null) {
                item.onPick()
                return
            }
            if (onDock) return
        }
        val home = if (GlassesHomeActivity.runningOn(displayId) != null) "Pocket XR home is on it" else "Pocket XR home is not on it"
        Log.i(TAG, "Tap from ${source.label} at ${action.at.x.roundToInt()},${action.at.y.roundToInt()} on display $displayId ($home)")
        AddonLink.call(Unit) { it.tap(displayId, action.at.x, action.at.y) }
    }

    /** App windows on the glasses, topmost layer last, cached for [WINDOWS_TTL_MS] or until a window move. */
    private fun windows(): List<WindowFrame> {
        val id = glasses?.id ?: return emptyList()
        val now = SystemClock.uptimeMillis()
        if (now - windowsAtMs < WINDOWS_TTL_MS) return windowsCache
        val b = AddonLink.call(Bundle()) { it.windows(id) }
        val ids = b.getIntArray("ids") ?: IntArray(0)
        val bounds = b.getIntArray("bounds") ?: IntArray(0)
        val layers = b.getIntArray("layers") ?: IntArray(0)
        val titles = b.getStringArray("titles") ?: arrayOfNulls(0)
        windowsCache = ids.indices
            .filter { i -> bounds.size >= i * 4 + 4 && layers.size > i }
            .map { i -> WindowFrame(ids[i], Bounds(bounds[i * 4], bounds[i * 4 + 1], bounds[i * 4 + 2], bounds[i * 4 + 3]), titles.getOrNull(i), layers[i]) }
            .filter { !it.bounds.isEmpty }
        windowsAtMs = now
        return windowsCache
    }

    /**
     * 0.5.9: DeX's own full screen for the Space, pressed by the add-on's pointer service on [displayId]. The answer
     * says what it did or what it saw. Blocking (a call into the add-on).
     */
    fun pressDexFullScreen(displayId: Int): String =
        AddonLink.call("not pressed: the add-on did not answer") { it.pressDexFullScreen(displayId) }

    private fun refreshDisplay() {
        val chosen = DisplaySelector.select(monitor.snapshot(), SpacesState.choices.value.preferredDisplayId)
        if (chosen?.id == glasses?.id && chosen?.widthPx == glasses?.widthPx && chosen?.heightPx == glasses?.heightPx && controller != null) return
        glasses = chosen
        dock = emptyList()
        PointerHub.displayChanged(chosen?.id)
        if (chosen == null) {
            controller = null
            AddonLink.call(Unit) { it.overlayOn(-1) }
            return
        }
        val bounds = Bounds(0, 0, chosen.widthPx, chosen.heightPx)
        val scale = chosen.densityDpi.coerceAtLeast(120) / 160f
        fun px(dp: Int) = (dp * scale).roundToInt()
        val insets = SpacesState.hosting.value.insets[chosen.id]
        controller = PointerController(
            display = bounds,
            chrome = WindowChrome.forDensity(chosen.densityDpi),
            limits = WindowLimits(
                minWidthPx = px(220),
                minHeightPx = px(160),
                topInsetPx = insets?.topPx ?: 0,
                bottomInsetPx = insets?.bottomPx ?: px(48),
                keepVisiblePx = px(80),
                edgeGuardPx = px(4),
            ),
        )
        // The display's own density: what the add-on's cursor window draws the dock at.
        density = chosen.densityDpi / 160f
        AddonLink.call(Unit) { it.overlayOn(chosen.id) }
        controller?.cursor?.let { c -> AddonLink.call(Unit) { it.cursor(c.x, c.y, -1, false) } }
    }

    private fun flash(message: String) = AddonLink.call(Unit) { it.flash(message, 3500) }

    private fun showDockItems(items: List<DockItem>) {
        dock = items
        val display = glasses
        dockBoxes = if (display == null) emptyList() else DockLayout.boxes(items.size, display.widthPx, display.heightPx, density)
        val bundle = Bundle().apply {
            putStringArray("labels", items.map { it.label }.toTypedArray())
            putParcelableArrayList("icons", ArrayList(items.map { it.icon }))
        }
        AddonLink.call(Unit) { it.dock(bundle) }
    }

    /** Open palm or three finger tap: Spaces home, the apps placed so far, then the first apps A to Z. */
    private fun showDock() {
        val display = glasses ?: return
        Thread {
            val placed = SpacesState.choices.value.occupied.values.toList()
            val apps = (placed + catalog).distinctBy { it.key }.take(DOCK_APPS)
            val items = listOf(DockItem(context.getString(R.string.sys_pointer_home), null) { openHome(display) }) +
                apps.map { app -> DockItem(app.label, installedApps.icon(app, ICON_PX)) { launch(app, display) } }
            main.post {
                showDockItems(items)
                main.removeCallbacks(hideDock)
                main.postDelayed(hideDock, DOCK_MS)
            }
        }.start()
    }

    private fun openHome(display: DisplayCandidate) {
        val failure = AppLauncher.openGlassesHome(context, display.id)
        flash(failure?.let { context.getString(R.string.sys_pointer_home_failed, it.message.toString()) } ?: context.getString(R.string.sys_pointer_home))
    }

    private fun launch(app: AppEntry, display: DisplayCandidate) {
        val home = GlassesHomeActivity.runningOn(display.id)
        val message = if (home != null) {
            SpacesLauncher.launchFromHome(home, app, display, FreeformSetup.evaluate(WindowingProbe.read(context)))
        } else {
            val failure = AppLauncher.openGlassesHome(context, display.id)
            if (failure == null) {
                SpacesState.queue(SpacesState.PendingLaunch(app, display.id, SystemClock.elapsedRealtime()))
                context.getString(R.string.sys_pointer_opening_home_then, app.label)
            } else {
                context.getString(R.string.sys_pointer_open_failed, app.label, failure.message.toString())
            }
        }
        flash(message)
    }

    companion object {
        private const val TAG = "FlipSpacesPointer"
        private const val TICK_MS = 30L

        /** 0.5.19: display changes settle this long before the cursor window follows them. */
        private const val SETTLE_MS = 400L
        private const val WINDOWS_TTL_MS = 250L
        private const val DOCK_MS = 8000L
        private const val DOCK_APPS = 9

        /** 1.1.0: dock icons cross to the add-on at this size, so ten of them stay well under a binder call's 1 MB. */
        private const val ICON_PX = 96
    }
}
