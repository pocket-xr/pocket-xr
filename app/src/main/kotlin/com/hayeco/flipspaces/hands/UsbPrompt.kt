package com.hayeco.flipspaces.hands

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hayeco.flipspaces.core.hands.GlassesUsb
import com.hayeco.flipspaces.core.hands.UsbAsk
import com.hayeco.flipspaces.core.hands.UsbAskFacts
import com.hayeco.flipspaces.core.hands.UsbAskStep

/**
 * 0.5.1: the one place that asks for the glasses camera's USB access, after Android's own
 * "Open Pocket XR to handle XREAL Aura?" plug-in prompt ([UsbAsk]). A plug-in is a new USB
 * device name (Android numbers each attach afresh); its time comes from USB_DEVICE_ATTACHED,
 * received for the whole process ([register]), or from the first sight of the device.
 */
object UsbPrompt {
    private const val TAG = "FlipSpacesHands"

    @Volatile private var plugDevice: String? = null
    @Volatile private var attachedAtMs: Long? = null
    @Volatile private var launchedByAttach = false
    @Volatile private var askedThisPlug = false
    @Volatile private var lastLine: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (!GlassesUsb.isXreal(device.vendorId) && !GlassesUsbAccess.hasVideo(device)) return
            // 1.1.12: Air family glasses stream head tracking over USB: the tracker looks at once.
            if (com.hayeco.flipspaces.core.spatial.AirImuProtocol.isAir(device.vendorId, device.productId)) {
                com.hayeco.flipspaces.spatial.HeadTracker.kick("Air family glasses plugged in on USB")
            }
            attached(device.deviceName)
        }
    }

    /** Hears every plug-in while the process lives (FlipSpacesApp). */
    fun register(context: Context) {
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "USB attach receiver: ${it.message}") }
    }

    /** The glasses (USB device [deviceName]) were plugged in now. */
    @Synchronized
    fun attached(deviceName: String) {
        if (deviceName == plugDevice && attachedAtMs != null) return
        newPlug(deviceName, SystemClock.uptimeMillis())
        Log.i(TAG, "Glasses plugged in on USB ($deviceName): the hand camera waits ${UsbAsk.ATTACH_PROMPT_MS / 1000} s for Android's plug-in prompt before asking for access")
    }

    /** MainActivity was opened by USB_DEVICE_ATTACHED for [deviceName]: its OK carried the grant. */
    @Synchronized
    fun openedByAttach(deviceName: String?) {
        if (launchedByAttach && (deviceName == null || deviceName == plugDevice)) return
        if (deviceName != null && deviceName != plugDevice) newPlug(deviceName, SystemClock.uptimeMillis())
        launchedByAttach = true
        Log.i(TAG, "Pocket XR was opened by the glasses' plug-in prompt: USB access comes with it, nothing more is asked")
    }

    private fun newPlug(deviceName: String, nowMs: Long) {
        plugDevice = deviceName
        attachedAtMs = nowMs
        launchedByAttach = false
        askedThisPlug = false
        lastLine = null
    }

    /**
     * Asks for access to [device] if [UsbAsk] says it is time, and logs the decision when it
     * changes. Returns the step, so a caller can wait and try again.
     */
    @Synchronized
    fun askIfDue(context: Context, device: UsbDevice, where: String): UsbAskStep {
        val now = SystemClock.uptimeMillis()
        // Not heard plugging in (the process started after it): the plug counts from first sight.
        if (device.deviceName != plugDevice) newPlug(device.deviceName, now)
        val facts = UsbAskFacts(
            granted = GlassesUsbAccess.hasPermission(context, device),
            attachedAtMs = attachedAtMs,
            launchedByAttach = launchedByAttach,
            askedThisPlug = askedThisPlug,
            nowMs = now,
        )
        val step = UsbAsk.decide(facts)
        if (step == UsbAskStep.ASK) {
            askedThisPlug = true
            GlassesUsbAccess.requestPermission(context, device)
        }
        val line = UsbAsk.line(step, facts)
        val key = "$step"
        if (key != lastLine) {
            lastLine = key
            Log.i(TAG, "$line (from $where)")
        }
        return step
    }

    /** The glasses were unplugged: the next plug-in starts over. */
    @Synchronized
    fun reset() {
        plugDevice = null
        attachedAtMs = null
        launchedByAttach = false
        askedThisPlug = false
        lastLine = null
    }

    /** RECORD_AUDIO, which makes Android offer "Always" on the plug-in prompt. */
    fun recordAudioGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
