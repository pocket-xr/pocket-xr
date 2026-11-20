package com.hayeco.flipspaces.pcscreen

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PC screens: game controllers on the phone (a Bluetooth or USB gamepad, Joy-Cons paired with Android) passed to the PC
 * as Xbox-style gamepads, so PC games played in a PC screen see them. Up to four, numbered in the order they first press
 * something. Each change of a button, stick or trigger sends that controller's whole state (Moonlight's multi-controller
 * packet). Main thread.
 */
class PcScreenGamepad {
    private class Pad(val number: Int) {
        var buttons = 0
        var leftTrigger = 0
        var rightTrigger = 0
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        var hat = 0
        var keyTriggerL = false
        var keyTriggerR = false
    }

    private val pads = LinkedHashMap<Int, Pad>()

    /** True when [event] is a gamepad's button, handled here (not typed to the PC as a key). */
    fun key(event: KeyEvent): Boolean {
        if (!isGamepad(event.device, event.source)) return false
        val flag = flagOf(event.keyCode)
        val trigger = event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2
        if (flag == 0 && !trigger) return false
        if (event.action == KeyEvent.ACTION_MULTIPLE) return true
        val pad = pad(event.deviceId) ?: return true
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> pad.keyTriggerL = down
            KeyEvent.KEYCODE_BUTTON_R2 -> pad.keyTriggerR = down
            else -> pad.buttons = if (down) pad.buttons or flag else pad.buttons and flag.inv()
        }
        send(pad)
        return true
    }

    /** True when [event] is a gamepad's sticks, triggers or hat, handled here. */
    fun motion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val pad = pad(event.deviceId) ?: return true
        val dev = event.device
        pad.lx = stick(event, dev, MotionEvent.AXIS_X)
        pad.ly = -stick(event, dev, MotionEvent.AXIS_Y)
        // The right stick is Z/RZ on most Android gamepads, RX/RY on some.
        val hasZ = dev?.getMotionRange(MotionEvent.AXIS_Z) != null && dev.getMotionRange(MotionEvent.AXIS_RZ) != null
        pad.rx = stick(event, dev, if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        pad.ry = -stick(event, dev, if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)
        pad.leftTrigger = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)))
        pad.rightTrigger = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)))
        // The D-pad as a hat (most Bluetooth gamepads).
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        pad.hat = (if (hx < -0.5f) ControllerPacket.LEFT_FLAG else 0) or (if (hx > 0.5f) ControllerPacket.RIGHT_FLAG else 0) or
            (if (hy < -0.5f) ControllerPacket.UP_FLAG else 0) or (if (hy > 0.5f) ControllerPacket.DOWN_FLAG else 0)
        send(pad)
        return true
    }

    /** Every pad released, sticks centred (the stream ending or the window losing focus). */
    fun releaseAll() {
        for (pad in pads.values) {
            pad.buttons = 0; pad.leftTrigger = 0; pad.rightTrigger = 0
            pad.lx = 0; pad.ly = 0; pad.rx = 0; pad.ry = 0; pad.hat = 0
            pad.keyTriggerL = false; pad.keyTriggerR = false
            send(pad)
        }
    }

    private fun pad(deviceId: Int): Pad? = pads[deviceId] ?: run {
        if (pads.size >= MAX_PADS) return null
        Pad(pads.size).also {
            pads[deviceId] = it
            Log.i(TAG, "gamepad ${it.number + 1} on the PC: device $deviceId")
        }
    }

    private fun send(pad: Pad) {
        val mask = pads.values.fold(0) { m, p -> m or (1 shl p.number) }
        MoonBridge.sendMultiControllerInput(
            pad.number.toShort(), mask.toShort(), pad.buttons or pad.hat,
            (if (pad.keyTriggerL) 255 else pad.leftTrigger).toByte(), (if (pad.keyTriggerR) 255 else pad.rightTrigger).toByte(),
            pad.lx.toShort(), pad.ly.toShort(), pad.rx.toShort(), pad.ry.toShort(),
        )
    }

    private fun stick(event: MotionEvent, dev: InputDevice?, axis: Int): Int {
        val v = event.getAxisValue(axis)
        val flat = dev?.getMotionRange(axis, event.source)?.flat ?: 0.1f
        if (abs(v) <= maxOf(flat, 0.05f)) return 0
        return (v * 32767f).roundToInt().coerceIn(-32767, 32767)
    }

    private fun trigger(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    companion object {
        private const val TAG = "PcScreen"
        const val MAX_PADS = 4

        fun isGamepad(device: InputDevice?, source: Int): Boolean =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                (device?.sources ?: 0) and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD

        /** Android's gamepad key to the Xbox-style button Moonlight sends; 0 for none. */
        fun flagOf(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> ControllerPacket.A_FLAG
            KeyEvent.KEYCODE_BUTTON_B -> ControllerPacket.B_FLAG
            KeyEvent.KEYCODE_BUTTON_X -> ControllerPacket.X_FLAG
            KeyEvent.KEYCODE_BUTTON_Y -> ControllerPacket.Y_FLAG
            KeyEvent.KEYCODE_DPAD_UP -> ControllerPacket.UP_FLAG
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerPacket.DOWN_FLAG
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerPacket.LEFT_FLAG
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerPacket.RIGHT_FLAG
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerPacket.LB_FLAG
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerPacket.RB_FLAG
            KeyEvent.KEYCODE_BUTTON_START -> ControllerPacket.PLAY_FLAG
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> ControllerPacket.BACK_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerPacket.LS_CLK_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerPacket.RS_CLK_FLAG
            KeyEvent.KEYCODE_BUTTON_MODE -> ControllerPacket.SPECIAL_BUTTON_FLAG
            else -> 0
        }
    }
}
