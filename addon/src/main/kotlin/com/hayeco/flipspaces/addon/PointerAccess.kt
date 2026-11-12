package com.hayeco.flipspaces.addon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.spatial.DexFullScreenButton
import com.hayeco.flipspaces.core.spatial.SeenButton
import com.hayeco.flipspaces.core.windows.WindowZone

/**
 * 1.1.0: the Pocket XR pointer, the accessibility service, now in the add-on. Pocket XR still runs the pointer
 * (its PointerSession: the controller, the glasses display, the dock's apps); this service does only what needs
 * accessibility, when Pocket XR asks over the link ([LinkService]):
 *
 * - Draws [CursorOverlayView] in a TYPE_ACCESSIBILITY_OVERLAY window on the glasses display, through
 *   createDisplayContext(...).createWindowContext(...) (API 30).
 * - Reads app window bounds on that display from getWindowsOnAllDisplays() (API 30).
 * - Performs taps and drags there with [GestureInjector], and Back.
 *
 * Every call arrives on the main thread ([LinkService] posts them).
 */
class PointerAccess : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var injector: GestureInjector

    private var overlay: CursorOverlayView? = null
    private var overlayManager: WindowManager? = null
    private var overlayDisplay = -1

    /** 0.5.19: an overlay refused for a token not valid yet is tried again once, after the display settles. */
    private var overlayRetried = false

    /** What the overlay shows, kept so a new overlay (another display, a retry) starts the same. */
    private var spaceUp = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        injector = GestureInjector(this) { problem ->
            LinkService.problem(problem)
            overlay?.flash(problem)
        }
        current = this
        LinkService.pointerChanged(true)
        Log.i(TAG, "pointer service on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        removeOverlay()
        main.removeCallbacksAndMessages(null)
        LinkService.pointerChanged(false)
        super.onDestroy()
    }

    fun overlayOn(displayId: Int) {
        if (displayId == overlayDisplay && overlay != null) return
        removeOverlay()
        overlayDisplay = displayId
        if (displayId >= 0) addOverlay(displayId)
    }

    fun cursor(x: Float, y: Float, zone: Int, pressed: Boolean) =
        overlay?.showCursor(Point(x, y), WindowZone.entries.getOrNull(zone), pressed)

    fun preview(bounds: Bounds?) = overlay?.showPreview(bounds)

    fun hand(status: CursorOverlayView.Hand?, source: String) = overlay?.showHand(status, source)

    fun flash(message: String, millis: Long) = overlay?.flash(message, millis)

    fun dock(items: Bundle) {
        val labels = items.getStringArray("labels").orEmpty()
        @Suppress("DEPRECATION")
        val icons = items.getParcelableArrayList<Bitmap>("icons")
        overlay?.showDock(labels.mapIndexed { i, label -> CursorOverlayView.DockItem(label, icons?.getOrNull(i)) })
    }

    fun spaceUp(up: Boolean) {
        spaceUp = up
        overlay?.spaceUp = up
    }

    fun tap(displayId: Int, at: Point) {
        injector.displayId = displayId
        injector.tap(at)
    }

    fun dragStart(displayId: Int, at: Point) {
        injector.displayId = displayId
        injector.start(at)
    }

    fun dragTo(at: Point) = injector.moveTo(at)

    fun dragEnd(at: Point) = injector.end(at)

    fun drag(displayId: Int, from: Point, to: Point) {
        injector.displayId = displayId
        injector.drag(from, to)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** App windows on [displayId], as [IAddon.windows] describes them. */
    fun windows(displayId: Int): Bundle {
        val rect = Rect()
        val list = runCatching { windowsOnAllDisplays.get(displayId).orEmpty() }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val ids = IntArray(list.size)
        val bounds = IntArray(list.size * 4)
        val layers = IntArray(list.size)
        val titles = arrayOfNulls<String>(list.size)
        list.forEachIndexed { i, w ->
            w.getBoundsInScreen(rect)
            ids[i] = w.id
            bounds[i * 4] = rect.left
            bounds[i * 4 + 1] = rect.top
            bounds[i * 4 + 2] = rect.right
            bounds[i * 4 + 3] = rect.bottom
            layers[i] = w.layer
            titles[i] = w.title?.toString()
        }
        return Bundle().apply {
            putIntArray("ids", ids)
            putIntArray("bounds", bounds)
            putIntArray("layers", layers)
            putStringArray("titles", titles)
        }
    }

    /**
     * 0.5.9: DeX's own full screen for the Space. Reads every window on [displayId] (the caption is
     * the desktop shell's window, not the app's), picks the caption's full screen button
     * ([DexFullScreenButton]) and clicks it; when the click action is refused, taps its centre with a
     * gesture on that display instead. The answer says what it did or what it saw.
     */
    fun pressDexFullScreen(displayId: Int): String {
        val windows = runCatching { windowsOnAllDisplays.get(displayId).orEmpty() }.getOrElse { return "not pressed: windows on display $displayId unreadable (${it.javaClass.simpleName}: ${it.message})" }
        if (windows.isEmpty()) return "not pressed: no windows readable on display $displayId"
        val rect = Rect()
        val seen = mutableListOf<Pair<SeenButton, AccessibilityNodeInfo>>()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val queue = ArrayDeque(listOf(root))
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++
                val label = (n.contentDescription ?: n.text ?: n.tooltipText)?.toString().orEmpty()
                if (label.isNotBlank()) {
                    n.getBoundsInScreen(rect)
                    seen += SeenButton(label, rect.left, rect.top, rect.right, rect.bottom, n.isClickable, w.title?.toString().orEmpty()) to n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        val buttons = seen.map { it.first }
        val pick = DexFullScreenButton.pick(buttons) ?: return "not pressed: no full screen button in DeX's title bar on display $displayId (saw ${DexFullScreenButton.describe(buttons)})"
        val node = seen.first { it.first === pick }.second
        // The node itself, else its nearest clickable parent (the caption may label an icon inside a button).
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) return "pressed DeX's \"${pick.label}\" button at ${pick.left},${pick.top} on display $displayId (window \"${pick.windowTitle}\")"
        val cx = (pick.left + pick.right) / 2f
        val cy = (pick.top + pick.bottom) / 2f
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .setDisplayId(displayId)
            .build()
        val sent = dispatchGesture(gesture, null, null)
        return if (sent) "tapped DeX's \"${pick.label}\" button at %.0f,%.0f on display $displayId (its click action was refused)".format(cx, cy)
        else "not pressed: DeX's \"${pick.label}\" button refused the click and the tap could not be sent"
    }

    private fun addOverlay(displayId: Int) {
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: run {
            Log.w(TAG, "no display $displayId for the cursor")
            return
        }
        try {
            val context = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = context.getSystemService(WindowManager::class.java)
            val view = CursorOverlayView(context, context.resources.displayMetrics.density)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "Pocket XR cursor"
            }
            wm.addView(view, params)
            overlay = view
            overlayManager = wm
            view.spaceUp = spaceUp
            LinkService.problem(null)
            overlayRetried = false
        } catch (e: WindowManager.BadTokenException) {
            // 0.5.19: the display was being replaced; quietly, once more after it settles.
            if (!overlayRetried) {
                overlayRetried = true
                Log.i(TAG, "cursor overlay on display $displayId not added yet (${e.message}); trying again once the display settles")
                main.postDelayed({ if (overlayDisplay == displayId && overlay == null) addOverlay(displayId) }, SETTLE_MS)
            } else {
                Log.w(TAG, "cursor overlay on display $displayId refused again: ${e.message}")
                LinkService.problem(getString(R.string.addon_cursor_failed, e.message.toString()))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay on display $displayId failed", e)
            LinkService.problem(getString(R.string.addon_cursor_failed, e.message.toString()))
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { overlayManager?.removeViewImmediate(view) }
        overlay = null
        overlayManager = null
    }

    companion object {
        private const val TAG = "PocketXrAddonPointer"

        /** 0.5.19: a refused overlay is tried again this long after. */
        private const val SETTLE_MS = 400L

        /** 0.5.9: at most this many nodes read per window when looking for DeX's full screen button. */
        private const val MAX_NODES = 400

        /** The bound service, while it is on. Main thread. */
        @Volatile var current: PointerAccess? = null
            private set
    }
}
