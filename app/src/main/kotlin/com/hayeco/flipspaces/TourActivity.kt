package com.hayeco.flipspaces

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.hands.GlassesUsb
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup
import com.hayeco.flipspaces.core.tutorial.Tour
import com.hayeco.flipspaces.platform.AddonInstaller
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.spatial.SpaceStore
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.tour.TourScreen
import com.hayeco.flipspaces.ui.tour.TourState
import kotlinx.coroutines.delay

/**
 * 1.5.0 (Mike, 15 November 2026: a tutorial new users see after installing, and can reopen whenever they want): the
 * tour, full screen on the phone (docs/TUTORIAL.md). The trackpad opens it once on a new install ([Tour.onTrackpadOpen]);
 * the Menu's and Settings' "Tour of Pocket XR" open it at any time. It reads what this phone has now (glasses, Shizuku,
 * the add-on) and never waits for any of it: every step has Skip, Back and Next.
 */
class TourActivity : ComponentActivity() {
    private val monitor by lazy { DisplayMonitor(this) }
    private val displaysFlow by lazy { monitor.displays() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A dark page whatever the phone's theme: light icons in the status bar.
        val bars = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        if (savedInstanceState == null) {
            // However it was opened, it has been seen now: the offer to someone updating is not made after this.
            TourPrefs(this).apply { memory = Tour.afterOpenedByUser(memory) }
            Log.i(TAG, "tour opened")
        }
        val first = intent?.getStringExtra(EXTRA_STEP)?.let { name -> Tour.STEPS.firstOrNull { it.name == name } } ?: Tour.STEPS.first()
        val spatialMouse = getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        setContent {
            FlipSpacesTheme {
                var step by rememberSaveable { mutableStateOf(first) }
                val displays by displaysFlow.collectAsStateWithLifecycle(monitor.snapshot())
                val choices by SpacesState.choices.collectAsStateWithLifecycle()
                val glasses = DisplaySelector.state(displays, choices.preferredDisplayId) as? GlassesState.Connected

                // Shizuku and the add-on are asked again when the screen comes back (from their own apps or Android's
                // settings) and, on the steps that show them, about once a second.
                var asked by remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    AddonInstaller.resumed(this@TourActivity)
                    asked++
                }
                LaunchedEffect(step) {
                    while (step == Tour.Step.SETUP || step == Tour.Step.DONE) {
                        delay(POLL_MS)
                        asked++
                    }
                }
                DisposableEffect(Unit) {
                    val changed: () -> Unit = { asked++ }
                    AddonInstaller.listen(changed)
                    onDispose { AddonInstaller.unlisten(changed) }
                }
                val shizuku = remember(asked) { debugShizuku() ?: ShizukuBridge.status(this@TourActivity) }
                val addon = remember(asked) { AddonInstaller.step(this@TourActivity) }
                val model = remember(asked, glasses?.display?.id) { debugModel() ?: knownModel() }
                // This copy's own Space on the glasses, asked again with the rest: another copy's Space does not count.
                // Without Shizuku the button puts the flat home on the glasses, not the Space; that display then counts as
                // opened too (the step still lists Shizuku), so a tap that worked never looks as if it did nothing.
                var openedOn by rememberSaveable { mutableStateOf<Int?>(null) }
                val spaceUp = remember(asked, glasses?.display?.id, openedOn) {
                    debugSpace() ?: (glasses != null && (
                        com.hayeco.flipspaces.spatial.SpaceActivity.runningOn(glasses.display.id) != null ||
                            com.hayeco.flipspaces.spatial.SpaceSession.active || openedOn == glasses.display.id
                        ))
                }
                val state = TourState(
                    glassesConnected = debugConnected() ?: (glasses != null),
                    glassesName = glasses?.display?.kindLabel.takeIf { debugConnected() != false },
                    model = model,
                    shizuku = shizuku,
                    spaceUp = spaceUp,
                    addon = addon,
                    spatialMouse = spatialMouse,
                    reducedMotion = animationsRemoved(this@TourActivity),
                )
                BackHandler(enabled = !Tour.isFirst(step)) { step = Tour.move(step, -1) }
                TourScreen(
                    step = step,
                    state = state,
                    onStep = { step = it },
                    onClose = {
                        Log.i(TAG, "tour closed at ${step.name}")
                        finish()
                    },
                    onShizuku = { action -> shizukuAction(action); asked++ },
                    onAddon = { action -> AddonInstaller.run(this@TourActivity, action); asked++ },
                    onOpenSpace = {
                        // As Pocket XR Settings' "Show on glasses" does; what Android answers is logged, and the step
                        // shows by itself whether the Space came up.
                        val failure = glasses?.let { SpaceRouting.openOnGlasses(this@TourActivity, it.display, "the tour") }
                        Log.i(TAG, "tour: open the Space: ${failure ?: if (glasses != null) "asked" else "no glasses"}")
                        failure?.let { android.widget.Toast.makeText(this@TourActivity, it, android.widget.Toast.LENGTH_LONG).show() }
                        if (failure == null) openedOn = glasses?.display?.id
                        asked++
                    },
                )
            }
        }
    }

    /** The XREAL glasses on USB now, else the glasses the Space last ran on; null when none are known. */
    private fun knownModel(): GlassesModel? {
        val plugged = runCatching {
            val usb = getSystemService(android.hardware.usb.UsbManager::class.java)
            usb?.deviceList?.values?.firstOrNull { GlassesUsb.isXreal(it.vendorId) }?.let { GlassesSetup.plugged(it.productId, it.productName) }
        }.getOrNull()
        return plugged ?: SpaceStore(this).model.takeIf { it != GlassesModel.UNKNOWN }
    }

    private fun shizukuAction(action: Tour.ShizukuAction) {
        Log.i(TAG, "tour: Shizuku $action")
        when (action) {
            Tour.ShizukuAction.GET -> open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_PAGE)))
            Tour.ShizukuAction.OPEN -> packageManager.getLaunchIntentForPackage(ShizukuBridge.PACKAGE)?.let { open(it) }
                ?: open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_PAGE)))
            Tour.ShizukuAction.ALLOW -> ShizukuBridge.requestPermission()
            Tour.ShizukuAction.NONE -> Unit
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "tour: nothing opens $intent")
        }
    }

    // The tour's states for a screenshot on an emulator, where there are no glasses and no Shizuku. Debug builds only.
    private fun debugShizuku(): ShizukuStatus? =
        if (BuildConfig.DEBUG) intent?.getStringExtra("debug_shizuku")?.let { name -> ShizukuStatus.entries.firstOrNull { it.name == name } } else null

    private fun debugModel(): GlassesModel? =
        if (BuildConfig.DEBUG) intent?.getStringExtra("debug_model")?.let { name -> GlassesModel.entries.firstOrNull { it.name == name } } else null

    private fun debugSpace(): Boolean? =
        if (BuildConfig.DEBUG && intent?.hasExtra("debug_space") == true) intent.getBooleanExtra("debug_space", false) else null

    private fun debugConnected(): Boolean? =
        if (BuildConfig.DEBUG && intent?.hasExtra("debug_connected") == true) intent.getBooleanExtra("debug_connected", false) else null

    companion object {
        private const val TAG = "FlipSpaces/Tour"
        private const val POLL_MS = 800L

        /** Shizuku's own download page, which the README's setup links to. */
        const val SHIZUKU_PAGE = "https://shizuku.rikka.app/download/"

        /** The step to open at ([Tour.Step]'s name); the first without it. */
        const val EXTRA_STEP = "com.hayeco.flipspaces.extra.TOUR_STEP"

        /** Android's "Remove animations" (or an animator scale of 0): the tour shows stills. */
        fun animationsRemoved(context: Context): Boolean =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * What the phone remembers about the tour ([Tour.Memory]), in the trackpad's preferences so Save settings to a file
 * carries it to a new install. "IntroShown" is the old first-run flag, kept under its key.
 */
class TourPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trackpad", Context.MODE_PRIVATE)

    var memory: Tour.Memory
        get() = Tour.Memory(
            arrival = Tour.Arrival.entries.firstOrNull { it.name == prefs.getString(KEY_ARRIVAL, null) },
            gestureCardsShown = prefs.getBoolean(KEY_INTRO, false),
            seen = prefs.getBoolean(KEY_SEEN, false),
            offerAnswered = prefs.getBoolean(KEY_OFFER, false),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_ARRIVAL, value.arrival?.name)
                .putBoolean(KEY_INTRO, value.gestureCardsShown)
                .putBoolean(KEY_SEEN, value.seen)
                .putBoolean(KEY_OFFER, value.offerAnswered)
                .apply()
        }

    companion object {
        private const val KEY_INTRO = "IntroShown"
        private const val KEY_ARRIVAL = "TourArrival"
        private const val KEY_SEEN = "TourSeen"
        private const val KEY_OFFER = "TourOfferAnswered"
    }
}
