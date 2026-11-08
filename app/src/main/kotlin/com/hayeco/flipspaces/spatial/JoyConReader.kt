package com.hayeco.flipspaces.spatial

import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads Joy-Cons from Linux's input devices, in the panel service, because the shell user may and the app may not.
 * Linux's hid-nintendo driver gives each Joy-Con two devices: buttons and stick, and a separate one for its motion
 * sensors, which Android's own input system does not pass on to apps.
 *
 * Each motion sample goes to [emit] with the latest buttons and stick, so a game gets everything at the sensors' rate,
 * and with the time its read returned: the three samples of one report come in one read, and JoyConClock spreads them
 * out again from that.
 */
class JoyConReader(private val emit: (hand: Int, readNs: Long, buttons: Int, stickX: Float, stickY: Float, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) -> Unit) {
    private class Hand {
        @Volatile var buttons = 0
        @Volatile var stickX = 0f
        @Volatile var stickY = 0f

        /** A Joy-Con that went to sleep holds no buttons. */
        fun release() {
            buttons = 0
            stickX = 0f
            stickY = 0f
        }
    }

    private val hands = arrayOf(Hand(), Hand())
    private val streams = java.util.concurrent.CopyOnWriteArrayList<FileInputStream>()
    private val opened: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var running = false

    /** Starts reading, and keeps looking for Joy-Cons that connect later. Returns what was found, for the log. */
    fun start(): String {
        if (running) return "already reading"
        running = true
        val first = scan()
        Thread({
            while (running) {
                try {
                    Thread.sleep(SCAN_EVERY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (running) scan()
            }
        }, "joycon-scan").apply {
            isDaemon = true
            start()
        }
        return first.ifEmpty { "no Joy-Cons yet" }
    }

    /** Opens any Joy-Con device not already open. */
    @Synchronized
    private fun scan(): String {
        val found = devices()
        val seen = ArrayList<String>()
        for (hand in 0..1) {
            val name = if (hand == 0) "Nintendo Switch Left Joy-Con" else "Nintendo Switch Right Joy-Con"
            found[name]?.let { node ->
                if (opened.add(node)) {
                    read(node, grab = true, urgent = false, onEnd = { hands[hand].release() }) { _, type, code, value -> controls(hand, type, code, value) }
                    seen += "$name $node"
                }
            }
            found["$name IMU"]?.let { node ->
                if (opened.add(node)) {
                    val motion = IntArray(6)
                    // Taken too, so Android's input reader is not woken 400 times a second for a device it never uses.
                    read(node, grab = true, urgent = true) { readNs, type, code, value -> motion(hand, readNs, motion, type, code, value) }
                    seen += "$name IMU $node"
                }
            }
        }
        if (seen.isNotEmpty()) Log.i(TAG, "reading ${seen.joinToString()}")
        return seen.joinToString()
    }

    fun stop() {
        running = false
        streams.forEach { runCatching { it.close() } }
        streams.clear()
        opened.clear()
    }

    /** Device name to /dev/input node, from getevent: One UI does not let the shell read /proc/bus/input/devices. */
    private fun devices(): Map<String, String> {
        val out = HashMap<String, String>()
        var node: String? = null
        val process = ProcessBuilder("getevent", "-p").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().forEachLine { line ->
            val text = line.trim()
            when {
                text.startsWith("add device") -> node = text.substringAfter(": ").trim()
                text.startsWith("name:") -> {
                    val n = node
                    if (n != null) out[text.substringAfter("name:").trim().trim('"')] = n
                }
            }
        }
        process.waitFor()
        return out
    }

    /**
     * Takes a device for this reader alone (EVIOCGRAB), so Android does not act on its buttons as well: Home on a
     * Joy-Con is the phone's Home and B is Back, which sent the game away. Closing the device gives it back.
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun grab(stream: FileInputStream, node: String) {
        val done = runCatching {
            val libcore = Class.forName("libcore.io.Libcore").getField("os").get(null)
            libcore.javaClass.getMethod("ioctlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType)
                .invoke(libcore, stream.fd, EVIOCGRAB)
        }
        Log.i(TAG, "$node ${if (done.isSuccess) "taken from Android" else "still goes to Android: ${done.exceptionOrNull()}"}")
    }

    private fun read(node: String, grab: Boolean, urgent: Boolean, onEnd: () -> Unit = {}, onEvent: (readNs: Long, type: Int, code: Int, value: Int) -> Unit) {
        Thread({
            // Motion at the head feed's priority: a sample that waits for a little core is a sample that lags.
            if (urgent) runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            val buffer = ByteArray(EVENT_BYTES * 64)
            val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            try {
                FileInputStream(node).use { stream ->
                    streams += stream
                    if (grab) grab(stream, node)
                    while (running) {
                        val got = stream.read(buffer)
                        if (got <= 0) break
                        val readNs = System.nanoTime()
                        var at = 0
                        while (at + EVENT_BYTES <= got) {
                            onEvent(readNs, view.getShort(at + 16).toInt() and 0xFFFF, view.getShort(at + 18).toInt() and 0xFFFF, view.getInt(at + 20))
                            at += EVENT_BYTES
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                if (running) Log.w(TAG, "$node stopped: ${e.message}")
            } finally {
                opened.remove(node)
                onEnd()
            }
        }, "joycon-read").apply {
            isDaemon = true
            start()
        }
    }

    private fun controls(hand: Int, type: Int, code: Int, value: Int) {
        val h = hands[hand]
        when (type) {
            EV_KEY -> {
                val bit = bitOf(hand, code)
                if (bit != 0) h.buttons = if (value != 0) h.buttons or bit else h.buttons and bit.inv()
            }
            EV_ABS -> when (code) {
                ABS_X, ABS_RX -> h.stickX = value / STICK_MAX
                ABS_Y, ABS_RY -> h.stickY = -value / STICK_MAX
            }
        }
    }

    private fun bitOf(hand: Int, code: Int): Int = if (hand == 0) {
        when (code) {
            BTN_DPAD_RIGHT -> PRIMARY
            BTN_DPAD_UP -> SECONDARY
            BTN_SELECT -> MENU
            BTN_THUMBL -> STICK
            BTN_TL2 -> TRIGGER
            BTN_TL -> SQUEEZE
            BTN_Z -> RECENTRE
            else -> 0
        }
    } else {
        when (code) {
            BTN_EAST -> PRIMARY
            BTN_SOUTH -> SECONDARY
            BTN_START -> MENU
            BTN_THUMBR -> STICK
            BTN_TR2 -> TRIGGER
            BTN_TR -> SQUEEZE
            BTN_MODE -> RECENTRE
            else -> 0
        }
    }

    private fun motion(hand: Int, readNs: Long, m: IntArray, type: Int, code: Int, value: Int) {
        when (type) {
            EV_ABS -> when (code) {
                ABS_RX -> m[0] = value
                ABS_RY -> m[1] = value
                ABS_RZ -> m[2] = value
                ABS_X -> m[3] = value
                ABS_Y -> m[4] = value
                ABS_Z -> m[5] = value
            }
            EV_SYN -> {
                val h = hands[hand]
                emit(hand, readNs, h.buttons, h.stickX, h.stickY, m[0] * GYRO_RAD, m[1] * GYRO_RAD, m[2] * GYRO_RAD, m[3] / ACCEL_PER_G, m[4] / ACCEL_PER_G, m[5] / ACCEL_PER_G)
            }
        }
    }

    companion object {
        private const val TAG = "PocketXrJoyCon"

        /** struct input_event on a 64 bit kernel: two longs of time, then type, code and value. */
        private const val EVENT_BYTES = 24
        private const val SCAN_EVERY_MS = 2_000L

        /** _IOW('E', 0x90, int). */
        private const val EVIOCGRAB = 0x40044590
        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_ABS = 3
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val BTN_SOUTH = 0x130
        private const val BTN_EAST = 0x131
        private const val BTN_Z = 0x135
        private const val BTN_TL = 0x136
        private const val BTN_TR = 0x137
        private const val BTN_TL2 = 0x138
        private const val BTN_TR2 = 0x139
        private const val BTN_SELECT = 0x13A
        private const val BTN_START = 0x13B
        private const val BTN_MODE = 0x13C
        private const val BTN_THUMBL = 0x13D
        private const val BTN_THUMBR = 0x13E
        private const val BTN_DPAD_UP = 0x220
        private const val BTN_DPAD_RIGHT = 0x223
        private const val STICK_MAX = 32767f

        /** The driver's scale: raw units per degree per second, and per g, checked against a measured 90 degree turn. */
        private const val GYRO_RAD = (Math.PI / 180.0 / 14247.0).toFloat()
        private const val ACCEL_PER_G = 4096f

        const val PRIMARY = 1
        const val SECONDARY = 2
        const val MENU = 4
        const val STICK = 8
        const val TRIGGER = 16
        const val SQUEEZE = 32
        const val RECENTRE = 64
    }
}
