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
class PcScreenActivity : Activity(), NvConnectionListener, SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { PcScreenStore(this) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { visibility = View.GONE }
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // The picture keeps the stream's shape (16:9), centred with black bars, never stretched to the panel's.
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val (fw, fh) = PcScreenFit.fit(r - l, b - t, STREAM_W, STREAM_H)
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
                text = getString(R.string.pc_screen_title)
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

    private fun tuple(address: String) = ComputerDetails.AddressTuple(address, NvHTTP.DEFAULT_HTTP_PORT)

    private fun pair() {
        val address = addressField.text.toString().trim()
        if (address.isEmpty()) return say(getString(R.string.pc_screen_address_needed))
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
                    say(getString(R.string.pc_screen_paired_with, details.name))
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
        val w = STREAM_W
        val h = STREAM_H
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
                Log.i(TAG, "connecting to $address, app ${app.appName}, ${w}x$h@60, codec ${store.codec}, formats 0x${Integer.toHexString(formats)}")
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
    private fun endSession() {
        val wasStreaming = connection != null
        stopStream()
        if (!wasStreaming || !store.paired) return
        val address = store.address
        val cert = store.serverCert ?: return
        val port = store.httpsPort
        val id = store.uniqueId
        Thread({
            val r = runCatching { NvHTTP(tuple(address), port, id, cert, crypto).quitApp() }
            Log.i(TAG, "session ended on the PC: ${r.exceptionOrNull() ?: r.getOrNull()}")
        }, "PcScreen-quit").start()
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
        const val TAG = "PcScreen"
        const val BITRATE_KBPS = 40_000
        const val STREAM_W = 1920
        const val STREAM_H = 1080
    }
}
