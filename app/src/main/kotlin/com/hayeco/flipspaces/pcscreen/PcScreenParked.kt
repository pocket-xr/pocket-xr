package com.hayeco.flipspaces.pcscreen

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener

/**
 * PC screens: a stream held while the Space reopens.
 *
 * Switching the glasses between 2D and 3D gives Android a new glasses display, so the Space closes and opens again and
 * the PC screen's window is made anew. Each switch used to end the session on the PC and start a fresh one: three or
 * four reconnects as the Space opened (Mike's phone, 20 Nov 2026, 11:00:11 to 11:00:26: the Space asked for 3D, the
 * Aura dropped it after 2.7 s, the Space asked again).
 *
 * Now the closing window hands its stream here ([park]): the connection, its sound and its decoder keep running,
 * pictures still decoded but not shown, on a stand-in surface. The next PC screen window in this process takes it
 * ([take]) and moves the pictures onto its own surface. With no window back within [HOLD_MS], the session ends on the PC
 * as before. One PC screen per process ([PcScreenSlots]), so one held stream at most. Main thread.
 */
internal object PcScreenParked {
    const val HOLD_MS = 10_000L

    class Stream(
        val connection: NvConnection,
        val decoder: PcScreenDecoder,
        val listener: PcScreenListener,
        val activeAddress: String?,
        val relativeMouse: Boolean,
        val mouseFactor: Float,
        val placeOnEnter: Boolean,
        val streamW: Int,
        val streamH: Int,
        val shapedToWindow: Boolean,
        /** Stops the connection and ends the session on the PC (a thread of its own). */
        val end: () -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private var held: Stream? = null
    private var texture: SurfaceTexture? = null
    private var standIn: Surface? = null

    private val timeout = Runnable {
        val s = held ?: return@Runnable
        held = null
        Log.i("PcScreen", "held stream: no PC screen window came back in ${HOLD_MS / 1000} s: ending the session")
        s.end()
    }

    /** Holds [s]; false (nothing held) when its decoder cannot move to the stand-in surface. */
    fun park(s: Stream): Boolean {
        drop()
        val tex = SurfaceTexture(false)
        val surf = Surface(tex)
        s.decoder.showing = false
        if (!s.decoder.retarget(surf)) {
            s.decoder.showing = true
            surf.release()
            tex.release()
            return false
        }
        texture = tex
        standIn = surf
        held = s
        s.listener.target = null
        // The PC ending it meanwhile: nothing to hand on.
        s.listener.whileHeld = { code ->
            main.post {
                if (held === s) {
                    Log.i("PcScreen", "held stream: ended by the PC meanwhile ($code)")
                    held = null
                    main.removeCallbacks(timeout)
                    Thread({ runCatching { s.connection.stop() }; main.post { releaseStandIn() } }, "PcScreen-stop").start()
                }
            }
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, HOLD_MS)
        return true
    }

    /** The held stream, for a new PC screen window; null if none. */
    fun take(): Stream? {
        val s = held ?: return null
        held = null
        main.removeCallbacks(timeout)
        s.listener.whileHeld = null
        return s
    }

    /** The stand-in surface, once the decoder has moved off it (or the held stream is gone). */
    fun releaseStandIn() {
        standIn?.release()
        texture?.release()
        standIn = null
        texture = null
    }

    private fun drop() {
        val s = held ?: return
        held = null
        main.removeCallbacks(timeout)
        s.end()
    }
}

/**
 * The connection's listener, passed on to whichever PC screen window has the stream now ([target]); while the stream is
 * held with no window, only its end is passed on ([whileHeld]). Called on moonlight-common-c's threads.
 */
internal class PcScreenListener : NvConnectionListener {
    @Volatile var target: NvConnectionListener? = null
    @Volatile var whileHeld: ((Int) -> Unit)? = null

    override fun stageStarting(stage: String) { target?.stageStarting(stage) }
    override fun stageComplete(stage: String) { target?.stageComplete(stage) }
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) { target?.stageFailed(stage, portFlags, errorCode) }
    override fun connectionStarted() { target?.connectionStarted() }
    override fun connectionTerminated(errorCode: Int) {
        val t = target
        if (t != null) t.connectionTerminated(errorCode) else whileHeld?.invoke(errorCode)
    }
    override fun connectionStatusUpdate(connectionStatus: Int) { target?.connectionStatusUpdate(connectionStatus) }
    override fun displayMessage(message: String) { target?.displayMessage(message) }
    override fun displayTransientMessage(message: String) { target?.displayTransientMessage(message) }
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) { target?.rumble(controllerNumber, lowFreqMotor, highFreqMotor) }
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) { target?.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger) }
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) { target?.setHdrMode(enabled, hdrMetadata) }
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) { target?.setMotionEventState(controllerNumber, motionType, reportRateHz) }
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) { target?.setControllerLED(controllerNumber, r, g, b) }
}
