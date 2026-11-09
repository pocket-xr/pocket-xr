package com.hayeco.flipspaces.hands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.hayeco.flipspaces.core.hands.AutoStartFacts
import com.hayeco.flipspaces.core.hands.AutoStartStep
import com.hayeco.flipspaces.core.hands.HandAutoStart
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.ProviderChooser
import com.hayeco.flipspaces.core.hands.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The HandInput layer: every source of pointer input, its status, and which one drives the
 * cursor. Providers are chosen at run time with core's ProviderChooser:
 *
 * (a) XREAL SDK: never available here. docs/HAND-TRACKING.md found no public XREAL SDK that
 *     runs on a Galaxy Z Flip 7 with these glasses in a normal Android app (it is Unity only,
 *     supports Beam Pro and Galaxy S24+ phones, and gives hand data only to its own full screen
 *     app), so this provider reports why and is skipped.
 * (b) Glasses camera: the standard UVC camera in the glasses, read over USB, plus MediaPipe.
 * (c) Trackpad: the Flip's main screen; always available, needs no hand data.
 *
 * All providers feed the same PointerHub, so hands and trackpad can be used together.
 */
object HandInputManager {
    const val XREAL_SDK_REASON =
        "No public XREAL SDK supports this phone with these glasses: the SDK is Unity only, lists Beam Pro and " +
            "Galaxy S24 and later (no Z Flip), and gives hand data only to its own full screen app. See docs/HAND-TRACKING.md."

    private val xreal = MutableStateFlow<ProviderStatus>(ProviderStatus.Unavailable(XREAL_SDK_REASON))

    private val _trackpadOpen = MutableStateFlow(false)
    val trackpadOpen: StateFlow<Boolean> = _trackpadOpen.asStateFlow()

    private val _preferred = MutableStateFlow<HandProviderKind?>(null)
    val preferred: StateFlow<HandProviderKind?> = _preferred.asStateFlow()

    val statuses: Flow<Map<HandProviderKind, ProviderStatus>> =
        combine(xreal, GlassesCameraProvider.status, _trackpadOpen) { sdk, camera, pad ->
            mapOf(
                HandProviderKind.XREAL_SDK to sdk,
                HandProviderKind.GLASSES_CAMERA to camera,
                HandProviderKind.TRACKPAD to if (pad) ProviderStatus.Running("Trackpad open on the Flip") else ProviderStatus.Ready,
            )
        }

    fun active(statuses: Map<HandProviderKind, ProviderStatus>): HandProviderKind =
        ProviderChooser.choose(statuses, _preferred.value)

    fun prefer(kind: HandProviderKind?) {
        _preferred.value = kind
    }

    fun trackpadOpened(open: Boolean) {
        _trackpadOpen.value = open
    }

    fun refresh(context: Context) {
        GlassesCameraProvider.refresh(context)
    }

    /**
     * Stop in the notification (0.5.0): "not now". Cleared by [GlassesAutoOpen.reset] when the
     * glasses are unplugged, so the next plug-in starts hands again. Main thread.
     */
    @Volatile
    var pausedThisPlug = false

    /**
     * 0.5.0: starts hands by themselves once Mike has turned them on. Called where a Pocket XR activity is visible, the only time Android lets the
     * camera service start: the Space's onStart on the glasses, the glasses home and the phone's
     * screens. Logs what it decided under FlipSpacesHands.
     */
    fun autoStart(context: Context, where: String, visible: Boolean = true) {
        refresh(context)
        val device = GlassesUsbAccess.find(context)
        val facts = AutoStartFacts(
            wanted = HandsPrefs(context).autoStart,
            pausedThisPlug = pausedThisPlug,
            cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            usbPresent = device != null,
            usbGranted = device != null && GlassesUsbAccess.hasPermission(context, device),
            modelReady = HandModelStore.available(context),
            running = HandTrackingService.isRunning(),
            visible = visible,
        )
        val step = HandAutoStart.decide(facts)
        val line = HandAutoStart.line(step, where)
        if (line != lastAutoLine) {
            lastAutoLine = line
            Log.i(TAG, line)
        }
        when (step) {
            // 0.5.1: no USB access yet starts the service too; it asks itself, after Android's
            // plug-in prompt (UsbPrompt), instead of a second popup on top of that prompt.
            AutoStartStep.Start, AutoStartStep.AskUsb -> runCatching { HandTrackingService.start(context) }
                .onFailure { Log.w(TAG, "Hands auto start from $where: Android refused the camera service: ${it.message}") }
            is AutoStartStep.NeedsSetup, is AutoStartStep.Nothing -> Unit
        }
    }

    /**
     * PC VR: hands start with it, whether or not they were ever turned on, without turning on the Space's own auto
     * start ([HandsPrefs.autoStart] stays as it is). True when this call started them, so PC VR can stop them again.
     */
    fun startForPcVr(context: Context): Boolean {
        if (HandTrackingService.isRunning()) return false
        refresh(context)
        val device = GlassesUsbAccess.find(context)
        val facts = AutoStartFacts(
            wanted = true,
            pausedThisPlug = false,
            cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            usbPresent = device != null,
            usbGranted = device != null && GlassesUsbAccess.hasPermission(context, device),
            modelReady = HandModelStore.available(context),
            running = false,
            visible = true,
        )
        val step = HandAutoStart.decide(facts)
        Log.i(TAG, "Hands for PC VR: ${HandAutoStart.line(step, "PC VR")}")
        return when (step) {
            AutoStartStep.Start, AutoStartStep.AskUsb -> runCatching { HandTrackingService.start(context) }
                .onFailure { Log.w(TAG, "Hands for PC VR: Android refused the camera service: ${it.message}") }.isSuccess
            is AutoStartStep.NeedsSetup, is AutoStartStep.Nothing -> false
        }
    }

    private var lastAutoLine: String? = null
    private const val TAG = "FlipSpacesHands"

    /** Starts the chosen hand provider if it is ready. The trackpad starts when its screen opens. */
    fun startHands(context: Context): String? {
        refresh(context)
        return when (val s = GlassesCameraProvider.status.value) {
            ProviderStatus.Ready, is ProviderStatus.Failed -> {
                HandTrackingService.start(context)
                // 0.5.0: from now on hands start by themselves with the glasses.
                HandsPrefs(context).autoStart = true
                pausedThisPlug = false
                null
            }
            is ProviderStatus.Running -> null
            is ProviderStatus.NeedsPermission -> "Hand tracking needs: ${s.what}"
            is ProviderStatus.Unavailable -> s.reason
        }
    }

    fun stopHands(context: Context) {
        HandTrackingService.stop(context)
    }

    /** Stop from the notification: hands stay off until the glasses are plugged in again. */
    fun pauseUntilNextPlugIn(context: Context) {
        pausedThisPlug = true
        Log.i(TAG, "Hands stopped from the notification; they start again with the next plug-in")
        HandTrackingService.stop(context)
    }
}
