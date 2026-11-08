package com.hayeco.flipspaces.openxr

import android.content.Context
import android.util.Log
import com.hayeco.flipspaces.core.spatial.JoyConArm
import com.hayeco.flipspaces.core.spatial.JoyConClock
import com.hayeco.flipspaces.core.spatial.JoyConPose
import com.hayeco.flipspaces.core.spatial.JoyConStick
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.JoyConReader
import org.freedesktop.monado.ipc.PocketFeed

/**
 * Joy-Cons as a game's controllers. Each motion sample from the panel service gets its true time ([JoyConClock]), turns
 * the Joy-Con's orientation ([JoyConPose]), places the hand on an arm ([JoyConArm]) and goes to Monado with its turn
 * rate and speed, so Monado can predict it.
 *
 * Nothing needs setting up: each Joy-Con lines up with the head the first time it is held level and still, and keeps
 * itself lined up after. Capture on the left and Home on the right line it up at once. The gyro drift each Joy-Con
 * learns while it lies still is kept for next time.
 */
object OpenXrPads {
    private const val TAG = "PocketXrOpenXr"
    private const val PREFS = "joycons"
    private const val LOG_EVERY_NS = 30_000_000_000L

    private class Pad(val right: Boolean) {
        val clock = JoyConClock()
        val pose = JoyConPose(right)
        val arm = JoyConArm(right)
        var started = false
        var recentreHeld = false
        var restored = false
        var lastLogNs = 0L
        var lagSumNs = 0L
        var lagCount = 0
        val name get() = if (right) "right" else "left"
    }

    @Volatile private var pads = arrayOf(Pad(false), Pad(true))
    @Volatile private var app: Context? = null

    /** A game is starting: fresh Joy-Cons, with the drift each learned last time. */
    fun start(context: Context) {
        app = context.applicationContext
        pads = arrayOf(Pad(false), Pad(true))
    }

    /** The game ended: keep what each Joy-Con learned about its drift. */
    fun stop() {
        val context = app ?: return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (pad in pads) synchronized(pad) {
            if (!pad.pose.biasLearned) return@synchronized
            val b = pad.pose.biasRadS
            edit.putString("bias_${pad.name}", "${b.x},${b.y},${b.z}")
            Log.i(TAG, "Joy-Con ${pad.name}: drift %.2f degrees a second kept for next time".format(java.util.Locale.ROOT, Math.toDegrees(b.length.toDouble())))
        }
        edit.apply()
    }

    /** Where both hands sit against the arm model, metres (x right, y up, z back), kept in the "joycons" preferences. */
    fun armOffset(context: Context): Vec3 {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("arm_offset", null)
        val parts = saved?.split(',')?.mapNotNull { it.toFloatOrNull() }
        return if (parts != null && parts.size == 3) Vec3(parts[0], parts[1], parts[2]) else JoyConArm.DEFAULT_OFFSET
    }

    /** How far apart the hands sit (1 is Daydream's shoulder width), kept in the "joycons" preferences. */
    fun armSpread(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("arm_spread", JoyConArm.DEFAULT_SPREAD)

    fun setArmSpread(context: Context, spread: Float) {
        val s = spread.coerceIn(0f, 2f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("arm_spread", s).apply()
        for (pad in pads) synchronized(pad) { pad.arm.spread = s }
        Log.i(TAG, "Joy-Con hands spread: %.2f of shoulder width".format(java.util.Locale.ROOT, s))
    }

    /** Moves both hands, now and for later games. */
    fun setArmOffset(context: Context, offset: Vec3) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("arm_offset", "${offset.x},${offset.y},${offset.z}").apply()
        for (pad in pads) synchronized(pad) { pad.arm.offset = offset }
        Log.i(TAG, "Joy-Con hands moved: %.2f right, %.2f up, %.2f back (metres)".format(java.util.Locale.ROOT, offset.x, offset.y, offset.z))
    }

    private fun restore(pad: Pad) {
        pad.restored = true
        app?.let {
            pad.arm.offset = armOffset(it)
            pad.arm.spread = armSpread(it)
        }
        val saved = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("bias_${pad.name}", null) ?: return
        val parts = saved.split(',').mapNotNull { it.toFloatOrNull() }
        if (parts.size == 3) pad.pose.restoreBias(Vec3(parts[0], parts[1], parts[2]))
    }

    fun onSample(hand: Int, readNs: Long, buttons: Int, stickX: Float, stickY: Float, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val pad = pads.getOrNull(hand) ?: return
        val arrivedNs = System.nanoTime()
        synchronized(pad) {
            if (!pad.restored) restore(pad)
            if (!pad.started) {
                pad.started = true
                Log.i(TAG, "Joy-Con ${pad.name} is reporting")
            }
            val stamp = pad.clock.sample(readNs)
            val dt = stamp.dt
            pad.pose.update(gx, gy, gz, ax, ay, az, dt)

            val head = HeadTracker.gamePose(stamp.sampleNs) ?: Quat.IDENTITY
            val recentre = buttons and JoyConReader.RECENTRE != 0
            if (recentre && !pad.recentreHeld) pad.pose.recentre(head) else pad.pose.align(head, dt)
            pad.recentreHeld = recentre

            val q = pad.pose.orientation
            val w = pad.pose.angularVelocity
            val p = pad.arm.update(q, w.length, head, dt)
            val v = pad.arm.velocity

            val (sx, sy) = JoyConStick.shape(stickX, stickY)
            val trigger = buttons and JoyConReader.TRIGGER != 0
            val stickTouched = JoyConStick.touched(stickX, stickY) || buttons and JoyConReader.STICK != 0
            val primary = buttons and JoyConReader.PRIMARY != 0
            val secondary = buttons and JoyConReader.SECONDARY != 0
            // A Joy-Con has no touch sensors: a pressed button counts as touched, and with the thumb on nothing else it
            // rests, so games draw a hand holding a controller rather than a pointing one.
            var touches = 0
            if (primary) touches = touches or 0x10
            if (secondary) touches = touches or 0x20
            if (trigger) touches = touches or 0x40
            if (stickTouched) touches = touches or 0x80
            if (!primary && !secondary && !stickTouched) touches = touches or 0x100

            PocketFeed.nativeController(
                hand, stamp.sampleNs,
                p.x, p.y, p.z,
                q.x, q.y, q.z, q.w,
                w.x, w.y, w.z,
                v.x, v.y, v.z,
                (buttons and 0x0F) or touches,
                if (trigger) 1f else 0f,
                if (buttons and JoyConReader.SQUEEZE != 0) 1f else 0f,
                sx, sy,
            )

            pad.lagSumNs += arrivedNs - readNs
            pad.lagCount++
            if (arrivedNs - pad.lastLogNs > LOG_EVERY_NS) {
                pad.lastLogNs = arrivedNs
                val c = pad.clock
                Log.i(
                    TAG,
                    "Joy-Con %s: report every %.2f ms, %d reports, %d gaps, service to app %.2f ms, %s, drift %.2f degrees a second%s".format(
                        java.util.Locale.ROOT, pad.name, c.periodNs / 1e6, c.reports, c.gaps, pad.lagSumNs / 1e6 / pad.lagCount.coerceAtLeast(1),
                        if (pad.pose.isAligned) "lined up" else "not lined up yet",
                        Math.toDegrees(pad.pose.biasRadS.length.toDouble()), if (pad.pose.atRest) ", resting" else "",
                    ),
                )
                pad.lagSumNs = 0
                pad.lagCount = 0
            }
        }
    }
}
