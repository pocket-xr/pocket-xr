package com.hayeco.flipspaces.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.DisplayInfoText
import com.hayeco.flipspaces.core.DisplayType
import com.hayeco.flipspaces.core.SinkConnection
import com.hayeco.flipspaces.spatial.PanelHost
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Watches DisplayManager and reports every display as a [DisplayCandidate]. */
class DisplayMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    fun snapshot(): List<DisplayCandidate> {
        val displays = displayManager.displays
        val presentationIds = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .mapTo(HashSet()) { it.displayId }
        History.noteSnapshot(displays.map { it.displayId })
        return displays.map { toCandidate(it, it.displayId in presentationIds) }
    }

    fun display(id: Int): Display? = displayManager.getDisplay(id)

    /** Emits the current list, then again whenever a display is added, removed or changed. */
    fun displays(): Flow<List<DisplayCandidate>> = callbackFlow {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                History.noteHotplug(displayId)
                trySend(snapshot())
            }
            override fun onDisplayRemoved(displayId: Int) { trySend(snapshot()) }
            override fun onDisplayChanged(displayId: Int) { trySend(snapshot()) }
        }
        trySend(snapshot())
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { displayManager.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()

    @Suppress("DEPRECATION")
    private fun toCandidate(display: Display, inPresentationCategory: Boolean): DisplayCandidate {
        // getRealMetrics is deprecated for app windows, but for a whole external display it is
        // still the simplest way to read logical size and density without a window context.
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val info = DisplayInfoText.parse(runCatching { display.toString() }.getOrNull())
        return DisplayCandidate(
            id = display.displayId,
            name = display.name ?: appContext.getString(R.string.sys_display_fallback_name, display.displayId),
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = display.refreshRate,
            isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            isPresentation = display.flags and Display.FLAG_PRESENTATION != 0 || inPresentationCategory,
            isPrivate = display.flags and Display.FLAG_PRIVATE != 0,
            isOn = display.isValid && display.state != Display.STATE_OFF,
            isDozing = display.state == Display.STATE_DOZE || display.state == Display.STATE_DOZE_SUSPEND,
            type = readType(display)?.let(DisplayType::fromPlatform) ?: info.type ?: DisplayType.UNKNOWN,
            sinkConnection = SinkConnection.fromPlatform(readSinkConnection(display)),
            presentAtStart = History.presentAtStart(display.displayId),
            hotplugged = History.wasHotplugged(display.displayId),
            canHostTasks = info.canHostTasks ?: readCanHostTasks(display),
            allowsContentModeSwitch = info.allowsContentModeSwitch,
            desktopModeActive = if (display.displayId == Display.DEFAULT_DISPLAY) null else readDexActive(display),
            // 0.4.0 guard: a panel's virtual display is never the glasses.
            isOwnPanel = display.displayId in PanelHost.panelDisplayIds,
        )
    }

    /**
     * Display.canHostTasks is hidden (Android 16 QPR and later); used only when toString lacks
     * it. Android 16 on One UI 8 denies it to apps (74 hiddenapi denials in Mike's 0.3.5 log),
     * so after the first refusal it is not asked again and hosting is judged without it.
     */
    private fun readCanHostTasks(display: Display): Boolean? {
        if (canHostTasksDenied) return null
        return runCatching {
            Display::class.java.getMethod("canHostTasks").invoke(display) as? Boolean
        }.onFailure {
            canHostTasksDenied = true
            android.util.Log.i("FlipSpaces/Display", "Display.canHostTasks is not available to apps here (${it.javaClass.simpleName}); using DisplayInfo text only")
        }.getOrNull()
    }

    /**
     * Samsung's documented DeX check, run against the configuration of a context on that display:
     * semDesktopModeEnabled equals SEM_DESKTOP_MODE_ENABLED. Null on phones without these fields.
     */
    private fun readDexActive(display: Display): Boolean? = runCatching {
        val config = appContext.createDisplayContext(display).resources.configuration
        val type = config.javaClass
        type.getField("semDesktopModeEnabled").getInt(config) == type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(type)
    }.getOrNull()

    /** Display.getType is hidden; some builds still let apps read it, others refuse. Null when refused. */
    private fun readType(display: Display): Int? = runCatching {
        Display::class.java.getMethod("getType").invoke(display) as? Int
    }.getOrNull()

    private fun readSinkConnection(display: Display): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            display.deviceProductInfo?.connectionToSinkType
        } else {
            null
        }

    /**
     * Which displays existed when this process first looked and which arrived later. Kept for the
     * whole process, so a fold, unfold or activity restart does not make hotplugged glasses look
     * like they were there from the start.
     */
    companion object {
        /** Set once the hidden API policy refused Display.canHostTasks, for the whole process. */
        @Volatile
        private var canHostTasksDenied = false

        /**
         * A display arrived (0.4.3: also from the process wide plug-in watcher, so glasses
         * plugged in with no Pocket XR screen collecting [displays] still count as hotplugged).
         */
        fun noteHotplug(displayId: Int) = History.noteHotplug(displayId)
    }

    private object History {
        private var startIds: Set<Int>? = null
        private val hotplugIds = mutableSetOf<Int>()

        @Synchronized
        fun noteSnapshot(ids: List<Int>) {
            if (startIds == null) startIds = ids.toSet()
        }

        @Synchronized
        fun noteHotplug(id: Int) {
            hotplugIds += id
        }

        @Synchronized
        fun presentAtStart(id: Int): Boolean = startIds?.contains(id) == true && id !in hotplugIds

        @Synchronized
        fun wasHotplugged(id: Int): Boolean = id in hotplugIds
    }
}
