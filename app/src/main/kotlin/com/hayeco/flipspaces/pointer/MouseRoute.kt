package com.hayeco.flipspaces.pointer

import android.os.Handler
import android.os.Looper
import com.hayeco.flipspaces.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewTreeObserver
import com.hayeco.flipspaces.core.pointer.MouseReport
import com.hayeco.flipspaces.core.pointer.PhysicalMouse
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.spatial.SpaceSession

/**
 * A Bluetooth or USB mouse, on the phone or a Beam Pro, while the Space is up ([PhysicalMouse] maps it). Before this
 * nothing in the Space read a mouse: Android drew its own arrow on whichever display it was on, the Space's GL view
 * dropped its events, and on the phone a click on the trackpad's pad was taken as a finger's tap.
 *
 * Where the mouse's events land depends on the phone: on the display Android's arrow is on, so on the Space's window
 * (the glasses) or the trackpad's (the phone). Both feed this one route. A view [attach]ed here hides Android's arrow
 * over it and, while its window has focus, captures the pointer ([View.requestPointerCapture]), so the mouse's raw
 * motion comes to Pocket XR's pointer alone and Android's arrow never fights it or wanders off to another display.
 * Without focus (a window's app took it) the events still come uncaptured, with Android's relative motion.
 *
 * Main thread.
 */
object MouseRoute {
    private const val TAG = "FlipSpaces/Mouse"

    private val main = Handler(Looper.getMainLooper())
    private val mouse = PhysicalMouse()
    private var logged = false

    /** The views [attach]ed: the Space's GL view and the trackpad's key sink. */
    private val views = mutableListOf<java.lang.ref.WeakReference<View>>()

    private val wheelTick = object : Runnable {
        override fun run() {
            mouse.tick(android.os.SystemClock.uptimeMillis()).forEach(::submit)
            if (mouse.scrolling) main.postDelayed(this, mouse.wheelIdleMs)
        }
    }

    /** A mouse's event (captured or not); not one from a touchscreen, a stylus or a touchpad. */
    fun isMouse(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    /** Takes [event] into the Space's pointer when it is a mouse's and the Space is up; true when taken. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!SpaceSession.active || !isMouse(event)) return false
        val captured = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        // Captured, x and y are the motion; otherwise Android's relative axes carry it beside the arrow's place.
        val dx = if (captured) event.x else event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val dy = if (captured) event.y else event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        val scroll = event.actionMasked == MotionEvent.ACTION_SCROLL
        val report = MouseReport(
            buttons = event.buttonState,
            dx = if (scroll) 0f else dx,
            dy = if (scroll) 0f else dy,
            wheelV = if (scroll) event.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f,
            wheelH = if (scroll) event.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f,
            timeMs = event.eventTime,
        )
        if (!logged) {
            logged = true
            Log.i(TAG, "a mouse reached the Space (${if (captured) "captured" else "not captured"}): it moves the Space's pointer")
        }
        used = true
        // A mouse connected after the window took focus: the first event it sends uncaptured asks for the capture.
        if (!captured) views.mapNotNull { it.get() }.forEach(::capture)
        mouse.speed = SpaceSession.padCursorSpeed
        mouse.report(report).forEach(::submit)
        if (mouse.scrolling) {
            main.removeCallbacks(wheelTick)
            main.postDelayed(wheelTick, mouse.wheelIdleMs)
        }
        return true
    }

    /** The mouse let go of mid gesture (the Space closed, the capture lost): a held press is cancelled. */
    fun cancel() {
        main.removeCallbacks(wheelTick)
        mouse.cancel().forEach(::submit)
    }

    private fun submit(signal: PointerSignal) {
        if (SpaceSession.active) SpaceSession.submit(signal)
    }

    /** A mouse has reached the Space since it last went away ([leads]). */
    @Volatile private var used = false

    /**
     * The mouse leads the Space's pointer ([com.hayeco.flipspaces.core.spatial.SpatialPointer.mouseLeads]): it has moved
     * or clicked, and it is still connected. Once it goes it leads again only from its next event. Any thread.
     */
    fun leads(): Boolean {
        if (!used) return false
        if (!mouseConnected()) used = false
        return used
    }

    /** A mouse is connected: an external, non virtual input device that is a mouse. */
    fun mouseConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id)
        d != null && !d.isVirtual && d.supportsSource(InputDevice.SOURCE_MOUSE) && !d.supportsSource(InputDevice.SOURCE_TOUCHPAD)
    }

    /**
     * [view] takes the mouse: its events go to [onMotion], Android's arrow is hidden over it, and while the Space is up
     * and [view]'s window has focus the pointer is captured. [view] is made focusable, since captured events go to
     * the focused view.
     */
    fun attach(view: View) {
        views.removeAll { it.get() == null || it.get() === view }
        views += java.lang.ref.WeakReference(view)
        view.pointerIcon = PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_NULL)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnCapturedPointerListener { _, e -> onMotion(e) }
        view.setOnGenericMotionListener { _, e -> onMotion(e) }
        view.setOnTouchListener { _, e -> onMotion(e) }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) capture(view) else cancelIfHeld() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnWindowFocusChangeListener(focus)
                if (v.hasWindowFocus()) capture(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            }
        })
        if (view.isAttachedToWindow) view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
    }

    /** Captures the pointer for [view] when the Space is up, a mouse is connected and [view]'s window has focus. */
    fun capture(view: View) {
        if (!SpaceSession.active || !view.hasWindowFocus() || view.hasPointerCapture() || !mouseConnected()) return
        view.requestFocus()
        view.requestPointerCapture()
        Log.i(TAG, "pointer captured for the Space on display ${view.display?.displayId}: Android's own arrow is hidden while the mouse moves Pocket XR's pointer")
    }

    /** Gives the pointer back to Android (the Space closed). */
    fun release(view: View) {
        if (view.hasPointerCapture()) view.releasePointerCapture()
        cancelIfHeld()
    }

    private fun cancelIfHeld() {
        if (mouse.pressed || mouse.scrolling) cancel()
    }
}
