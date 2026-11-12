package com.hayeco.flipspaces

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.ContentMode
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.GlassesReady
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.spatial.LaunchSource
import com.hayeco.flipspaces.core.spatial.PlugIn
import com.hayeco.flipspaces.core.spatial.SpaceRouter
import com.hayeco.flipspaces.platform.DesktopWindow
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.GlassesPresentation
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.platform.SettingsShortcuts
import com.hayeco.flipspaces.platform.WindowingProbe
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.PhoneScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone side: glasses status, mirror or extended mode, freeform setup, and launching apps onto
 * the glasses. Since 0.4.0 plugging the glasses in opens the Space full screen on them unless
 * the mode is flat (SpaceRouting), and the app list opens apps as panels whenever it can.
 * 0.4.2: the same plug-in step runs from any Pocket XR screen or the background
 * ([GlassesAutoOpen]); this screen keeps its own, with the basic surface fallback.
 */
class MainActivity : ComponentActivity() {
    private lateinit var monitor: DisplayMonitor
    private lateinit var installedApps: InstalledApps
    private var presentation: GlassesPresentation? = null
    private val displaysFlow by lazy { monitor.displays() }

    /** Opened on a DeX display: maximized once, then left to the user. */
    private var maximizeRequested = false

    /** 0.5.37: a launch from the app's icon (or a recents return to it) goes to the trackpad, and this screen closes. */
    private fun opensTrackpad(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_MAIN && i?.action != null) return false
        startActivity(Intent(this, TrackpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.1.10: after Exit Pocket XR, a plug-in's USB prompt opens nothing; the icon brings Pocket XR back.
        if (AppExit.exited && intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            finish()
            return
        }
        AppExit.reopened(this)
        // 0.5.37 (Mike: reopening the app showed the old developer screen; "I'd rather have the Pocket XR trackpad always
        // appear when I open the app"): the app's icon opens the trackpad. The glasses' USB prompt still comes here.
        if (opensTrackpad(intent)) return
        enableEdgeToEdge()
        monitor = DisplayMonitor(this)
        installedApps = InstalledApps(this)

        setContent {
            FlipSpacesTheme {
                val displays by displaysFlow.collectAsStateWithLifecycle(monitor.snapshot())
                val choices by SpacesState.choices.collectAsStateWithLifecycle()
                val hosting by SpacesState.hosting.collectAsStateWithLifecycle()
                val laterMessage by SpacesState.messages.collectAsStateWithLifecycle()
                val banner by SpacesState.banner.collectAsStateWithLifecycle()
                var windowing by remember { mutableStateOf(FreeformSetup.evaluate(WindowingProbe.read(this))) }
                var allApps by remember { mutableStateOf(emptyList<AppEntry>()) }
                var query by remember { mutableStateOf("") }
                var message by remember { mutableStateOf<String?>(null) }
                var resumes by remember { mutableIntStateOf(0) }
                val glasses = DisplaySelector.state(displays, choices.preferredDisplayId)
                val connected = glasses as? GlassesState.Connected
                val assessment = remember(connected, hosting, resumes) {
                    connected?.let { SpacesLauncher.assess(this, it.display) }
                }
                val settingsTarget = remember(resumes) { SettingsShortcuts.best(this) }
                val surface = remember(resumes, connected?.display?.id) { SpaceRouter.surfaceFor(SpaceRouting.choice(this)) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    resumes++
                    windowing = FreeformSetup.evaluate(WindowingProbe.read(this@MainActivity))
                    lifecycleScope.launch {
                        allApps = withContext(Dispatchers.IO) { installedApps.load() }
                    }
                }

                LaunchedEffect(laterMessage) {
                    laterMessage?.let {
                        message = it
                        SpacesState.messageShown()
                    }
                }

                // Put the Space (or in flat mode the home surface) on the glasses as soon as they
                // show up, unless Android already says the display only mirrors (the attempt
                // would just be denied).
                LaunchedEffect(connected?.display?.id, assessment?.mode, surface) {
                    if (connected == null) {
                        GlassesAutoOpen.reset()
                        SpacesState.clearDenial()
                        SpacesState.setBanner(null)
                        dismissPresentation()
                    } else if (SpaceActivity.runningOn(connected.display.id) != null) {
                        // 0.4.3: a Space already up stays (with Shizuku down it shows placeholders), never swapped for the flat home.
                        Log.i("FlipSpaces/Mode", "main screen plug-in step, display ${connected.display.id}: the Space is already up; kept")
                    } else if (assessment?.mode != ContentMode.MIRROR && GlassesAutoOpen.claim(PlugIn.key(connected.display.id, surface))) {
                        // Shared with GlassesAutoOpen, which opens the glasses from any other
                        // Pocket XR screen or the background: each display and surface once.
                        message = openHome(connected, "plug-in")
                    }
                }

                PhoneScreen(
                    glasses = glasses,
                    hosting = assessment,
                    settingsTarget = settingsTarget,
                    apps = AppCatalog.prepare(allApps, packageName, query),
                    query = query,
                    message = message,
                    banner = banner.takeIf { connected != null },
                    onFix = { SpaceRouting.fix(this, it) },
                    iconLoader = installedApps::icon,
                    onQueryChange = { query = it },
                    onMessageShown = { message = null },
                    onOpenHome = { connected?.let { message = openHome(it, "phone button") } },
                    onOpenSettings = { target -> message = SettingsShortcuts.open(this, target) },
                    onRetry = {
                        SpacesState.clearDenial()
                        resumes++
                        connected?.let { message = openHome(it, "retry") }
                    },
                    onLaunch = { app ->
                        message = SpaceRouting.launch(this, app, connected?.display, windowing, LaunchSource.PHONE_LIST)
                    },
                    onOpenHands = { startActivity(Intent(this, HandsActivity::class.java)) },
                    onOpenTrackpad = { startActivity(Intent(this, TrackpadActivity::class.java)) },
                    onOpenTrackpadSettings = { startActivity(Intent(this, TrackpadActivity::class.java).putExtra(TrackpadActivity.EXTRA_MENU, true)) },
                    onOpenSpatial = { startActivity(Intent(this, SpatialActivity::class.java)) },
                    onOpenHeadTracking = { startActivity(Intent(this, HeadTrackingActivity::class.java)) },
                    onGlassesReport = {
                        val file = com.hayeco.flipspaces.spatial.GlassesReportFile.share(this)
                        message = if (file != null) getString(R.string.home_glasses_report_saved, file.name) else getString(R.string.home_glasses_report_not_saved)
                    },
                    onSendLogs = { SendLogs.send(this, this) },
                    onGlassesCheck = { startActivity(Intent(this, TrackpadActivity::class.java).putExtra(TrackpadActivity.EXTRA_GLASSES_CHECK, true)) },
                )
            }
        }
    }

    private var notificationsAskedHere = false

    /** 0.4.6: the answer to the notification permission; a yes posts the ready notification again. */
    private val notificationsAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i("FlipSpaces/Mode", "notification permission ${if (granted) "granted" else "denied"} on the main screen")
        if (granted) GlassesReadyService.start(this, GlassesReady.From.PHONE_SCREEN)
    }

    /**
     * 0.4.6: asks for POST_NOTIFICATIONS so the glasses ready notification shows
     * ([GlassesReady.askForNotifications]); once, and once more after a single denial. At most
     * once per main screen, since closing the prompt resumes it.
     */
    private fun askForNotifications() {
        if (notificationsAskedHere) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ask = GlassesReady.askForNotifications(
            sdkInt = Build.VERSION.SDK_INT,
            granted = checkSelfPermission(GlassesReady.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            askedBefore = prefs.getBoolean(NOTIFICATIONS_ASKED, false),
            showRationale = shouldShowRequestPermissionRationale(GlassesReady.POST_NOTIFICATIONS),
        )
        if (!ask) return
        notificationsAskedHere = true
        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
        notificationsAsk.launch(GlassesReady.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        GlassesAutoOpen.resumed(this)
        // 1.0.1: if the last run crashed, offer to send the report.
        CrashCatcher.offer(this)
        // 0.5.97: a newer Pocket XR on GitHub Releases. 1.1.15: at most every 12 hours, also at app start and in the background.
        UpdateChecker.checkIfDue(this)
        // 0.4.4: always allowed from a screen in front, should the app start's try have been refused.
        GlassesReadyService.start(this, GlassesReady.From.PHONE_SCREEN)
        askForNotifications()
        // 0.5.0: hands start by themselves once turned on; opened by the glasses' USB prompt this
        // also carries the USB grant ("Always open Pocket XR" keeps it).
        val byAttach = intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
        // 0.5.1: opened by the plug-in prompt, whose OK grants USB access: the camera never asks on top of it.
        if (byAttach) {
            val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            com.hayeco.flipspaces.hands.UsbPrompt.openedByAttach(device?.deviceName)
        }
        val where = if (byAttach) "the glasses' USB prompt" else "the phone screen"
        com.hayeco.flipspaces.hands.HandInputManager.autoStart(this, where)
        // A no-op on the phone; on a DeX display this turns the freeform window full screen.
        if (!maximizeRequested) DesktopWindow.fill(this, immersive = false, maximize = true)
        maximizeRequested = true
    }

    /** 0.5.24: singleTop: a USB prompt's intent comes here; onResume reads it as before. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 0.5.37: the icon again while this screen is on top: the trackpad.
        opensTrackpad(intent)
    }

    override fun onPause() {
        GlassesAutoOpen.paused(this)
        super.onPause()
    }

    override fun onDestroy() {
        dismissPresentation()
        super.onDestroy()
    }

    /**
     * Opens the Space on the glasses, or in flat mode the glasses home activity. If Android
     * refuses both, shows a Presentation instead so the glasses still show something, and
     * returns the real error.
     */
    private fun openHome(connected: GlassesState.Connected, where: String): String? {
        val error = SpaceRouting.openOnGlasses(this, connected.display, where) ?: return null
        val display = monitor.display(connected.display.id) ?: return error
        dismissPresentation()
        presentation = try {
            GlassesPresentation(this, display, "Pocket XR\n" + getString(R.string.home_keep_phone_app_open)).also { it.show() }
        } catch (e: RuntimeException) {
            return getString(R.string.home_basic_surface_failed, error, e.message)
        }
        return getString(R.string.home_basic_surface_instead, error)
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    companion object {
        private const val PREFS = "main"
        private const val NOTIFICATIONS_ASKED = "notifications_asked"

        /** 0.5.87: the trackpad Menu's Pocket XR Settings: this screen itself, not the trackpad redirect. */
        const val ACTION_SETTINGS = "com.hayeco.flipspaces.SETTINGS"
    }
}
