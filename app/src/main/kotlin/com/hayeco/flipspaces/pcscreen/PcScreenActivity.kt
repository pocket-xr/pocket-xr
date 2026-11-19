package com.hayeco.flipspaces.pcscreen

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.R
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge

/**
 * PC screens, first proof: one PC screen, opened on a Space panel like an app, in its own process (`:pcscreen`).
 * moonlight-common-c keeps one connection per process, so each further screen will need a process of its own.
 *
 * What it does:
 * - pairs with a Sunshine or Apollo host by address and PIN;
 * - streams its "Desktop" app (or the first app the host lists);
 * - decodes into this window's SurfaceView with [PcScreenDecoder];
 * - sends the panel's pointer as an absolute mouse, with left and right clicks and the scroll wheel.
 *
 * Not yet: the keyboard, several screens, and decoding straight into the panel's own picture, which skips one
 * composite.
 */
open class PcScreenActivity : Activity(), NvConnectionListener, SurfaceHolder.Callback {
    /** Which PC screen this is ([PcScreenSlots]): 0 here, 1 and 2 in [PcScreen2Activity] and [PcScreen3Activity]. */
    protected open val slot: Int get() = 0
    private val TAG by lazy { if (slot == 0) BASE_TAG else "$BASE_TAG${slot + 1}" }
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { PcScreenStore(this, slot) }
    private val crypto by lazy { AndroidCryptoProvider(applicationContext) }

    private lateinit var root: FrameLayout
    private lateinit var setup: LinearLayout
    private lateinit var addressField: EditText
    private lateinit var status: TextView
    private lateinit var surface: SurfaceView

    private var connection: NvConnection? = null
    private var decoder: PcScreenDecoder? = null
    @Volatile private var streaming = false
    private var heldButton: Byte = 0

    /** The stream's size: the window's own when the PC makes a virtual display ([PcScreenShape]), else 1920 x 1080. */
    @Volatile private var streamW = STREAM_W
    @Volatile private var streamH = STREAM_H

    /** The PC made this stream a virtual display of its size, so a resized window gets a fresh stream at its shape. */
    @Volatile private var shapedToWindow = false

    private var lastKeyLogMs = 0L
    private val keys by lazy { com.limelight.binding.input.KeyboardTranslator() }
    private val reshape = Runnable {
        if (connection != null && shapedToWindow && PcScreenShape.reshapeNeeded(root.width, root.height, streamW, streamH)) {
            Log.i(TAG, "window now ${root.width}x${root.height}: a fresh stream at its shape")
            // startStream ends the running session itself before the new one (endSession's own quit could land after
            // the new launch and end that instead).
            stopStream()
            startStream(surface.holder, surface.width, surface.height)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { visibility = View.GONE }
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // The picture keeps the stream's shape (16:9), centred with black bars, never stretched to the panel's.
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val (fw, fh) = PcScreenFit.fit(r - l, b - t, streamW, streamH)
            // A window resized by more than a little, once it has settled for a second: a stream at its new shape.
            main.removeCallbacks(reshape)
            if (shapedToWindow) main.postDelayed(reshape, 1000)
            val lp = surface.layoutParams as FrameLayout.LayoutParams
            if (lp.width != fw || lp.height != fh) {
                lp.width = fw
                lp.height = fh
                surface.post { surface.layoutParams = lp }
            }
        }
        setup = buildSetup()
        root.addView(setup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        homeDisplay = display?.displayId ?: android.view.Display.INVALID_DISPLAY
        // The Space's surface arriving or going while streaming: the stream starts again on the new one.
        PcScreenSurfaces.listener = {
            main.post {
                if (connection != null && PcScreenSurfaces.current == null) {
                    // The Space took its surface back: the PC screen's panel is closing (or the glasses went). The
                    // session ends with it ([endSession]), not moved onto this window.
                    Log.i(TAG, "the Space's surface was taken back: ending the session")
                    endSession()
                } else if (connection != null && surface.holder.surface.isValid) {
                    Log.i(TAG, "the Space's surface changed: starting the stream again")
                    stopStream()
                    startStream(surface.holder, surface.width, surface.height)
                }
            }
        }
        say(getString(if (store.paired) R.string.pc_screen_paired else R.string.pc_screen_intro))
    }

    private fun buildSetup(): LinearLayout {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = if (slot == 0) getString(R.string.pc_screen_title) else getString(R.string.pc_screen_title_n, slot + 1)
                textSize = 24f
                setTextColor(Color.WHITE)
            })
            addressField = EditText(context).apply {
                hint = getString(R.string.pc_screen_address_hint)
                setText(store.address)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                // The theme's light field hid the white text (emulator test, 18 Nov 2026).
                setBackgroundColor(Color.rgb(40, 40, 40))
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            addView(addressField)
            addView(Button(context).apply {
                text = getString(R.string.pc_screen_pair)
                setOnClickListener { pair() }
            })
            // Codec: Auto (the best the PC and phone share), H.264 or AV1.
            addView(Button(context).apply {
                fun label() = "Codec: " + when (store.codec) { "h264" -> "H.264"; "av1" -> "AV1"; else -> "Auto" }
                text = label()
                setOnClickListener {
                    store.codec = PcScreenStore.CODECS[(PcScreenStore.CODECS.indexOf(store.codec) + 1) % PcScreenStore.CODECS.size]
                    text = label()
                }
            })
            addView(Button(context).apply {
                text = getString(R.string.pc_screen_connect)
                setOnClickListener { connect() }
            })
            status = TextView(context).apply {
                setTextColor(Color.LTGRAY)
                textSize = 16f
            }
            addView(status)
        }
    }

    private fun say(text: String) {
        main.post { status.text = text }
    }

    /** The host and its HTTP port, as typed ([PcScreenAddress]): a second Apollo instance has its own port. */
    private fun tuple(address: String): ComputerDetails.AddressTuple {
        val (host, port) = PcScreenAddress.parse(address) ?: (address.trim() to PcScreenAddress.DEFAULT_PORT)
        return ComputerDetails.AddressTuple(host, port)
    }

    /** Another PC screen saved with the same host and port: both cannot stream from one host at once. */
    private fun sharedWith(address: String): Int? {
        val mine = PcScreenAddress.parse(address) ?: return null
        return (0 until PcScreenSlots.COUNT).firstOrNull { other ->
            other != slot && PcScreenAddress.parse(PcScreenStore(this, other).address) == mine
        }
    }

    private fun pair() {
        val address = addressField.text.toString().trim()
        if (address.isEmpty()) return say(getString(R.string.pc_screen_address_needed))
        if (PcScreenAddress.parse(address) == null) return say(getString(R.string.pc_screen_address_bad))
        store.address = address
        val pin = PairingManager.generatePinString()
        say(getString(R.string.pc_screen_enter_pin, pin))
        Thread({
            try {
                val http = NvHTTP(tuple(address), 0, store.uniqueId, null, crypto)
                val info = http.getServerInfo(true)
                val details = http.getComputerDetails(info)
                val result = http.pairingManager.pair(info, pin)
                Log.i(TAG, "pair with ${details.name} at $address: $result")
                val cert = http.pairingManager.pairedCert
                if (result == PairingManager.PairState.PAIRED && cert != null) {
                    store.savePairing(address, details.httpsPort, cert)
                    val other = sharedWith(address)
                    say(if (other == null) getString(R.string.pc_screen_paired_with, details.name)
                        else getString(R.string.pc_screen_paired_shared, details.name, other + 1))
                } else {
                    say(getString(R.string.pc_screen_pair_failed, result.toString()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "pair: $e")
                say(getString(R.string.pc_screen_pair_failed, e.message ?: e.toString()))
            }
        }, "PcScreen-pair").start()
    }

    private fun connect() {
        if (!store.paired) return say(getString(R.string.pc_screen_pair_first))
        Log.i(TAG, "connect: PC screen ${slot + 1}, host ${store.address}${sharedWith(store.address)?.let { ", also PC screen ${it + 1}'s host" } ?: ""}")
        setup.visibility = View.GONE
        surface.visibility = View.VISIBLE
        // The stream starts once the surface exists (surfaceChanged), at the window's own size.
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (connection != null || surface.visibility != View.VISIBLE) return
        startStream(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    private fun startStream(holder: SurfaceHolder, viewWidth: Int, viewHeight: Int) {
        val address = store.address
        val cert = store.serverCert ?: return
        // The stream's size: 1920 x 1080. The phone test (18 Nov 2026) asked for the panel's shape, 1976 x 1080, while
        // Apollo captured a 1920 x 1080 desktop: the host stretched it and the phone shrank it again, a little soft.
        // A desktop's own 16:9 is sent as it is, and the panel shows it at its shape ([PcScreenFit]).
        val windowW = root.width
        val windowH = root.height
        val formats = PcScreenDecoder.formatsFor(store.codec)
        // Straight into the Space's reader when the Space has handed one over (PcScreenLink): no composite of this
        // window's display in between. Else into this window, as before (the Space not running, or an older one).
        val panelSurface = PcScreenSurfaces.current?.takeIf { it.isValid }
        val d = PcScreenDecoder(panelSurface ?: holder.surface).also { decoder = it }
        Log.i(TAG, "decoding into ${if (panelSurface != null) "the Space's panel directly" else "this window"}")
        say(getString(R.string.pc_screen_connecting, address))
        Thread({
            try {
                val http = NvHTTP(tuple(address), store.httpsPort, store.uniqueId, cert, crypto)
                // Apollo makes a virtual display of the stream's size (VirtualDisplayCapable, its driver ready): the
                // stream takes the window's own shape, so it fills the window. Elsewhere 1920 x 1080, the usual
                // desktop, which the host sends without stretching.
                val info = http.getServerInfo(true)
                val vd = xml(info, "VirtualDisplayCapable") == "true" && xml(info, "VirtualDisplayDriverReady") != "false"
                val (w, h) = if (vd) PcScreenShape.forWindow(windowW, windowH) else (STREAM_W to STREAM_H)
                shapedToWindow = vd
                streamW = w
                streamH = h
                main.post { root.requestLayout() }
                val apps = http.appList
                val app = apps.firstOrNull { it.appName.equals("Desktop", ignoreCase = true) } ?: apps.firstOrNull()
                    ?: throw IllegalStateException("the host lists no apps")
                val config = StreamConfiguration.Builder()
                    .setApp(app)
                    .setResolution(w, h)
                    .setRefreshRate(60)
                    .setClientRefreshRateX100(6000)
                    .setBitrate(BITRATE_KBPS)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO)
                    // Moonlight's default colours, said to the host and to the decoder alike (PcScreenDecoder).
                    // Apollo: a virtual display of this size on the PC, so the PC screen works with the monitors off.
                    .setVirtualDisplay(true)
                    .setColorSpace(MoonBridge.COLORSPACE_REC_709)
                    .setColorRange(MoonBridge.COLOR_RANGE_LIMITED)
                    .setSupportedVideoFormats(formats)
                    .build()
                // A fresh session each time, so Apollo makes this stream's virtual display: rejoining a session it
                // started on a real monitor kept streaming that monitor (Mike's phone, 18 Nov 2026). One PC screen
                // for now; several will each have their own host instance.
                val running = runCatching { http.getCurrentGame(http.getServerInfo(true)) }.getOrDefault(0)
                if (running != 0) {
                    val quit = runCatching { http.quitApp() }
                    Log.i(TAG, "a session was running (app $running): ended it for a fresh one: ${quit.exceptionOrNull() ?: "done"}")
                }
                Log.i(TAG, "connecting to $address, app ${app.appName}, ${w}x$h@60 (window ${windowW}x$windowH, virtual display $vd), codec ${store.codec}, formats 0x${Integer.toHexString(formats)}")
                connection = NvConnection(applicationContext, tuple(address), store.httpsPort, store.uniqueId, config, crypto, cert)
                    .also { it.start(AndroidAudioRenderer(applicationContext, false), d, this) }
            } catch (e: Exception) {
                Log.w(TAG, "connect: $e")
                showSetup(getString(R.string.pc_screen_connect_failed, e.message ?: e.toString()))
            }
        }, "PcScreen-connect").start()
    }

    /**
     * Stops the stream and ends the session on the PC, so Apollo removes this stream's virtual display. Stopping alone
     * left the session running for a later resume: after Mike unplugged the glasses (18 Nov 2026), the virtual display
     * stayed on the PC and his windows were hidden on it once the monitors were back on. Called when the PC screen's
     * window goes (its panel closed, the Space or glasses gone) and when the Space takes its surface back.
     */
    private fun endSession(force: Boolean = false) {
        val wasStreaming = connection != null || sessionStarted
        stopStream()
        sessionStarted = false
        if ((!wasStreaming && !force) || !store.paired) return
        val address = store.address
        val cert = store.serverCert ?: return
        val port = store.httpsPort
        val id = store.uniqueId
        Thread({
            val r = runCatching { NvHTTP(tuple(address), port, id, cert, crypto).quitApp() }
            Log.i(TAG, "session ended on the PC: ${r.exceptionOrNull() ?: r.getOrNull()}")
        }, "PcScreen-quit").start()
    }

    /** A session was launched on the PC by this window and not yet ended. */
    private var sessionStarted = false

    private fun xml(info: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(info)?.groupValues?.get(1)?.trim()?.lowercase()

    // Keyboards: a Bluetooth or USB keyboard on the phone, and keys Pocket XR sends to the window, go to the PC as its
    // own keys (Moonlight's KeyboardTranslator), with Shift, Ctrl, Alt and Win held as they are; a character with no
    // key of its own is typed as text. Back from a mouse or the phone's buttons stays Pocket XR's.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Which keys arrive, from what (a phone test of the trackpad's keyboard): at most one line a second.
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastKeyLogMs > 1000) {
            lastKeyLogMs = nowMs
            Log.i(TAG, "key ${android.view.KeyEvent.keyCodeToString(event.keyCode)} action ${event.action} device ${event.deviceId} source 0x${Integer.toHexString(event.source)} flags 0x${Integer.toHexString(event.flags)} streaming $streaming")
        }
        if (!streaming) return super.dispatchKeyEvent(event)
        if ((event.flags and android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) return super.dispatchKeyEvent(event)
        val fromKeyboard = (event.source and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD &&
            event.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && !fromKeyboard) return super.dispatchKeyEvent(event)
        val code = keys.translate(event.keyCode, event.deviceId)
        if (code.toInt() == 0) {
            val ch = event.unicodeChar
            if (event.action == android.view.KeyEvent.ACTION_DOWN && ch != 0) MoonBridge.sendUtf8Text(String(Character.toChars(ch)))
            else if (event.action == android.view.KeyEvent.ACTION_MULTIPLE && event.characters != null) MoonBridge.sendUtf8Text(event.characters)
            return true
        }
        var mods = 0
        if (event.isShiftPressed) mods = mods or com.limelight.nvstream.input.KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) mods = mods or com.limelight.nvstream.input.KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) mods = mods or com.limelight.nvstream.input.KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) mods = mods or com.limelight.nvstream.input.KeyboardPacket.MODIFIER_META.toInt()
        val flags = if (keys.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED.toInt()
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                MoonBridge.sendKeyboardInput(code, com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN, mods.toByte(), flags.toByte())
            }
            android.view.KeyEvent.ACTION_UP ->
                MoonBridge.sendKeyboardInput(code, com.limelight.nvstream.input.KeyboardPacket.KEY_UP, mods.toByte(), flags.toByte())
        }
        return true
    }

    private fun stopStream() {
        streaming = false
        connection?.let { c -> Thread({ c.stop() }, "PcScreen-stop").start() }
        connection = null
        decoder = null
    }

    private fun showSetup(message: String) {
        main.post {
            stopStream()
            surface.visibility = View.GONE
            setup.visibility = View.VISIBLE
            status.text = message
        }
    }

    // The panel's pointer: an absolute mouse over the stream.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!streaming) return super.dispatchTouchEvent(event)
        mouse(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!streaming) return super.dispatchGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> position(event)
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) MoonBridge.sendMouseHighResScroll((v * 120).toInt().toShort())
                val hz = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (hz != 0f) MoonBridge.sendMouseHighResHScroll((hz * 120).toInt().toShort())
            }
            else -> return false
        }
        return true
    }

    private fun mouse(event: MotionEvent) {
        position(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                heldButton = if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) MouseButtonPacket.BUTTON_RIGHT
                else MouseButtonPacket.BUTTON_LEFT
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, heldButton)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (heldButton != 0.toByte()) {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, heldButton)
                heldButton = 0
            }
        }
    }

    private fun position(event: MotionEvent) {
        val w = surface.width.coerceAtLeast(1)
        val h = surface.height.coerceAtLeast(1)
        MoonBridge.sendMousePosition(
            (event.x - surface.left).toInt().coerceIn(0, w - 1).toShort(), (event.y - surface.top).toInt().coerceIn(0, h - 1).toShort(),
            w.toShort(), h.toShort(),
        )
    }

    /** The display the PC screen opened on: its panel's. */
    private var homeDisplay = android.view.Display.INVALID_DISPLAY

    /**
     * Unplugging the glasses removes the panel's display, and Android moves the PC screen to the phone's own screen
     * instead of closing it (Mike's phone, 18 Nov 2026): the stream stopped there, but the session stayed open on the
     * PC with its virtual display. A PC screen that leaves its panel's display ends the session and closes.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = display?.displayId ?: return
        if (homeDisplay != android.view.Display.INVALID_DISPLAY && now != homeDisplay) {
            Log.i(TAG, "moved from display $homeDisplay to $now (its panel's display went): ending the session and closing")
            endSession(force = true)
            finish()
        }
    }

    override fun onDestroy() {
        PcScreenSurfaces.listener = null
        endSession()
        super.onDestroy()
    }

    // NvConnectionListener
    override fun stageStarting(stage: String) = say(getString(R.string.pc_screen_stage, stage))
    override fun stageComplete(stage: String) = Unit
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        Log.w(TAG, "stage $stage failed: ports $portFlags, error $errorCode")
        showSetup(getString(R.string.pc_screen_connect_failed, "$stage ($errorCode)"))
    }
    override fun connectionStarted() {
        streaming = true
        sessionStarted = true
        Log.i(TAG, "streaming")
    }
    override fun connectionTerminated(errorCode: Int) {
        Log.i(TAG, "ended: $errorCode, ${decoder?.shown ?: 0} shown, ${decoder?.dropped ?: 0} dropped")
        showSetup(getString(R.string.pc_screen_ended, errorCode))
    }
    override fun connectionStatusUpdate(connectionStatus: Int) = Unit
    override fun displayMessage(message: String) = say(message)
    override fun displayTransientMessage(message: String) = Unit
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) = Unit
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) = Unit
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) = Unit
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) = Unit
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) = Unit

    private companion object {
        const val BASE_TAG = "PcScreen"
        const val BITRATE_KBPS = 40_000
        const val STREAM_W = 1920
        const val STREAM_H = 1080
    }
}

/** The second PC screen, in its own process (`:pcscreen2`), with its own saved PC ([PcScreenSlots]). */
class PcScreen2Activity : PcScreenActivity() {
    override val slot: Int get() = 1
}

/** The third PC screen, in its own process (`:pcscreen3`). */
class PcScreen3Activity : PcScreenActivity() {
    override val slot: Int get() = 2
}
