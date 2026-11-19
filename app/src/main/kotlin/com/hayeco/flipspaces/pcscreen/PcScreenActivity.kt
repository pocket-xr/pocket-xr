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
import android.view.ViewGroup
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
 * PC screens: a PC's screen on a Space panel, opened like an app, each in its own process ([PcScreenSlots]):
 * moonlight-common-c keeps one connection per process. Setup is meant to need almost nothing: PCs on the Wi-Fi are
 * listed ([PcScreenFinder]), a tap pairs, a PC that already knows the phone connects with no PIN, a paired PC screen
 * connects as soon as it opens, an asleep PC is woken ([PcScreenWake]), and trouble is said in plain words
 * ([PcScreenTrouble]).
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
    private lateinit var setup: View
    private lateinit var addressField: EditText
    private lateinit var foundTitle: TextView
    private lateinit var foundList: LinearLayout
    private val finder by lazy { PcScreenFinder(this) { showFound(it) } }

    /** The wake relay at home, found by mDNS and remembered for away ([WakeRelay]). */
    private val relayFinder by lazy { WakeRelayFinder(this) }
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

    /** The trackpad's Win stand-in is down: keys meanwhile go with Win held. */
    private var winHeld = false
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
        root.addView(setup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
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
        finder.start()
        relayFinder.start()
        // Paired before: straight to the PC's screen, nothing to tap.
        if (store.paired && savedInstanceState == null) connect()
    }

    /** The PCs found on the Wi-Fi, each a button; one already used by another PC screen says which. */
    /** The hosts last found, for the PC's name when one is chosen. */
    private var lastHosts: List<PcScreenFinder.Host> = emptyList()

    /** Saves the PC's Windows name (mDNS gives it) for the host at [address], for away from home ([PcScreenRemote]). */
    private fun notePcName(address: String) {
        val h = lastHosts.firstOrNull { PcScreenAddress.parse(it.typed) == PcScreenAddress.parse(address) } ?: return
        store.pcName = h.service
    }

    /** This PC screen's PC name, or the first PC screen's (the same PC). */
    private fun pcName(): String? = store.pcName ?: (0 until PcScreenSlots.COUNT).firstNotNullOfOrNull { PcScreenStore(this, it).pcName }

    private fun showFound(hosts: List<PcScreenFinder.Host>) {
        lastHosts = hosts
        foundTitle.text = getString(R.string.pc_screen_none_found)
        foundTitle.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
        foundList.removeAllViews()
        val usedBy = (0 until PcScreenSlots.COUNT).filter { it != slot }
            .associateBy { PcScreenAddress.parse(PcScreenStore(this, it).address) }
        // One button per PC (Mike, 19 Nov 2026: three Apollo instances listed as three PCs was "very confusing"): the
        // PC's instances grouped by address, and the one this PC screen should use picked behind the button.
        val mine = PcScreenAddress.parse(store.address)
        for ((pc, instances) in PcScreenPick.byPc(hosts)) {
            val pick = PcScreenPick.instanceFor(slot, instances) { usedBy[PcScreenAddress.parse(it.typed)] == null }
            val detail = when {
                pick == null -> getString(R.string.pc_screen_pc_full, PcScreenSlots.COUNT)
                store.paired && instances.any { PcScreenAddress.parse(it.typed) == mine } -> getString(R.string.pc_screen_pc_paired)
                else -> getString(R.string.pc_screen_pc_tap)
            }
            foundList.addView(kit.pcRow(pc, detail, pick != null) {
                if (pick != null) choose(pick.typed) else say(getString(R.string.pc_screen_pc_full_detail, pc))
            }, fullWidth(top = if (foundList.childCount == 0) 0f else 10f))
        }
        refreshSetup()
        // A second or third PC screen with nothing saved yet: a found host no other screen uses, that already knows
        // this phone (the PC setup copies the pairing to every Apollo instance), is taken and connected at once.
        if (!store.paired && store.address.isEmpty() && !autoTried) {
            val free = PcScreenPick.byPc(hosts).flatMap { (_, inst) ->
                listOfNotNull(PcScreenPick.instanceFor(slot, inst) { usedBy[PcScreenAddress.parse(it.typed)] == null })
            }
            if (free.isNotEmpty()) {
                autoTried = true
                Thread({
                    val h = free.firstOrNull { knownPairing(it.typed) }
                    if (h != null) main.post {
                        store.pcName = h.service
                        addressField.setText(h.typed)
                        say(getString(R.string.pc_screen_reused, h.name))
                        connect()
                    } else {
                        // Tried too early (one host found so far): try again on the next host found.
                        main.post { autoTried = false }
                    }
                }, "PcScreen-auto").start()
            }
        }
    }

    private var autoTried = false

    /**
     * A found PC tapped: connect if it already knows this phone, else pair with a PIN. One another PC screen uses is
     * refused: two PC screens on one Apollo show the same picture (Mike's phone, 19 Nov 2026).
     */
    private fun choose(address: String) {
        sharedWith(address)?.let { other ->
            say(getString(R.string.pc_screen_host_taken, other + 1))
            return
        }
        addressField.setText(address)
        notePcName(address)
        pair()
    }

    /**
     * This phone is already paired with the host at [address] under a certificate another PC screen saved (Apollo's
     * instances share one; the PC setup copies the paired phones to each): saved for this PC screen too, with no PIN.
     * Blocking: a worker thread.
     */
    private fun knownPairing(address: String): Boolean {
        val certs = (0 until PcScreenSlots.COUNT).mapNotNull { PcScreenStore(this, it).serverCert }
            .distinctBy { it.encoded.contentHashCode() }
        for (cert in certs) {
            val ok = runCatching {
                val http = NvHTTP(tuple(address), 0, store.uniqueId, cert, crypto)
                val info = http.getServerInfo(true)
                if (http.getPairState(info) != PairingManager.PairState.PAIRED) return@runCatching false
                store.savePairing(address, http.getHttpsPort(info), cert, macOf(info))
                Log.i(TAG, "pairing: $address already knows this phone; saved without a PIN")
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun macOf(info: String): String? = Regex("<mac>([^<]*)</mac>").find(info)?.groupValues?.get(1)?.trim()

    /** The PC helper on the PC at [address], if it answers there. Blocking. */
    private fun helperAt(address: String): Pair<com.hayeco.flipspaces.pcvr.PcVrHelper, com.hayeco.flipspaces.pcvr.PcVrHelper.Pc>? {
        val helper = com.hayeco.flipspaces.pcvr.PcVrHelper(this)
        val pc = runCatching { helper.find() }.getOrNull() ?: return null
        val host = PcScreenAddress.parse(address)?.first
        if (host != null && pc.address.hostAddress != host && runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull() != pc.address.hostAddress) return null
        return helper to pc
    }

    /** Pairing: the PC helper types [pin] for the user; if there is none, or it cannot, the PIN is shown to type. Blocking. */
    private fun pinThroughHelper(address: String, pin: String) {
        val found = helperAt(address)
        val why: String? = when {
            found == null -> "no PC helper"
            else -> {
                val (helper, pc) = found
                var allowed = helper.knows(pc)
                if (!allowed) {
                    say(getString(R.string.pc_screen_helper_allow, pc.name))
                    allowed = helper.pair(pc)
                }
                if (!allowed) "not allowed on the PC"
                else helper.apolloPin(pc, pin, PcScreenAddress.parse(address)?.second ?: PcScreenAddress.DEFAULT_PORT)
            }
        }
        Log.i(TAG, "pairing through the PC helper: ${why ?: "PIN typed for the user"}")
        if (why != null && !store.paired) {
            say(getString(R.string.pc_screen_enter_pin, pin) + "\n" + webPage(address) + "\n\n" + getString(R.string.pc_screen_helper_tip))
        }
    }

    /** "Set up my PC": through the PC helper when it answers, else the helper's download link to send to the PC. */
    private fun setUpPc() {
        say(getString(R.string.pc_screen_setup_looking))
        Thread({
            val address = store.address.ifEmpty { lastHosts.firstOrNull()?.typed ?: "" }
            val helper = com.hayeco.flipspaces.pcvr.PcVrHelper(this)
            val pc = runCatching { helper.find() }.getOrNull()
            if (pc == null) {
                say(getString(R.string.pc_screen_setup_no_helper))
                main.post {
                    runCatching {
                        startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.pc_screen_setup_share_subject))
                            putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.pc_screen_setup_share_text, com.hayeco.flipspaces.pcvr.PcVrHelper.DOWNLOAD_URL))
                        }, getString(R.string.pc_screen_setup_share_subject)))
                    }
                }
                return@Thread
            }
            if (!helper.knows(pc)) {
                say(getString(R.string.pc_screen_helper_allow, pc.name))
                if (!helper.pair(pc)) return@Thread say(getString(R.string.pc_screen_setup_refused, pc.name))
            }
            say(getString(R.string.pc_screen_setup_asked, pc.name))
            if (!helper.screensSetup(pc)) say(getString(R.string.pc_screen_setup_refused, pc.name))
            else say(getString(R.string.pc_screen_setup_running, pc.name))
            Log.i(TAG, "set up my PC: through the helper on ${pc.name} ($address)")
        }, "PcScreen-setup").start()
    }

    /** Apollo's web page for this host: its port plus one, over HTTPS. */
    private fun webPage(address: String): String {
        val (host, port) = PcScreenAddress.parse(address) ?: (address to PcScreenAddress.DEFAULT_PORT)
        return "https://$host:${port + 1}"
    }

    /** What went wrong, in words that say what to do. */
    private fun trouble(kind: PcScreenTrouble, fallback: String): String {
        val address = store.address
        val name = PcScreenAddress.parse(address)?.first ?: address
        return when (kind) {
            PcScreenTrouble.UNREACHABLE -> getString(R.string.pc_screen_trouble_unreachable, name) + "\n\n" + getString(
                when {
                    !PcScreenRemote.tailscaleInstalled(this) -> R.string.pc_screen_away_install
                    !PcScreenRemote.vpnUp(this) -> R.string.pc_screen_away_turn_on
                    else -> R.string.pc_screen_away_check_pc
                },
            )
            PcScreenTrouble.NO_PERMISSION -> getString(R.string.pc_screen_trouble_permission, webPage(address))
            PcScreenTrouble.NOT_PAIRED -> getString(R.string.pc_screen_trouble_unpaired)
            PcScreenTrouble.OTHER -> fallback
        }
    }

    private val kit by lazy { PcScreenSetupKit(this) }
    private lateinit var connectButton: View
    private lateinit var forgetLink: View

    /**
     * The page before a stream (Mike, 19 Nov 2026: "really ugly"): Pocket XR's look, one column. Its title and a line
     * under it; a status card while there is something to say; the PCs found, one row each; Connect once paired; Set up
     * my PC and Away from home; the picture settings and adding a PC by address folded away; Forget this PC as a link.
     */
    private fun buildSetup(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(24f), kit.dp(28f), kit.dp(24f), kit.dp(28f))
            // Header.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(MonitorGlyph(context, com.hayeco.flipspaces.core.Look.ACCENT), LinearLayout.LayoutParams(kit.dp(44f), kit.dp(44f)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(kit.dp(14f), 0, 0, 0)
                    addView(kit.text(if (slot == 0) getString(R.string.pc_screen_title) else getString(R.string.pc_screen_title_n, slot + 1),
                        com.hayeco.flipspaces.core.Look.TYPE_HEADLINE - 2, medium = true))
                    addView(kit.text(getString(R.string.pc_screen_subtitle), com.hayeco.flipspaces.core.Look.TYPE_BODY, com.hayeco.flipspaces.core.Look.INK_2).apply {
                        setPadding(0, kit.dp(6f), 0, 0)
                    })
                })
            })
            // Status, while there is something to say.
            status = kit.text("", com.hayeco.flipspaces.core.Look.TYPE_BODY + 1, com.hayeco.flipspaces.core.Look.INK).apply {
                background = kit.shape(com.hayeco.flipspaces.core.Look.HELD_GLASS, com.hayeco.flipspaces.core.Look.RADIUS_M, 0x335A93FF)
                setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(14f))
                visibility = View.GONE
            }
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(20f) })
            // The PCs found ([PcScreenFinder]): a tap pairs, or connects when the PC already knows the phone.
            addView(kit.section(getString(R.string.pc_screen_your_pcs)))
            foundTitle = kit.text(getString(R.string.pc_screen_looking), com.hayeco.flipspaces.core.Look.TYPE_BODY, com.hayeco.flipspaces.core.Look.INK_2).apply {
                setPadding(kit.dp(4f), 0, kit.dp(4f), kit.dp(10f))
            }
            addView(foundTitle)
            foundList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(foundList)
            connectButton = kit.primary(getString(R.string.pc_screen_connect)) { connect() }
            addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(12f) })
            // Setting the PC up, and away from home.
            addView(kit.section(getString(R.string.pc_screen_section_setup)))
            addView(kit.secondary(getString(R.string.pc_screen_setup_button)) { setUpPc() }, fullWidth())
            addView(kit.secondary(getString(R.string.pc_screen_away_button)) { PcScreenRemote.openTailscale(context) }, fullWidth(top = 10f))
            // Picture settings, folded away.
            val picture = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(4f), kit.dp(6f), kit.dp(4f), kit.dp(6f))
            }
            fillPicture(picture)
            addView(kit.foldable(getString(R.string.pc_screen_section_picture), picture), fullWidth(top = 22f))
            // A PC by address, folded away.
            val byAddress = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(4f), kit.dp(12f), kit.dp(4f), kit.dp(6f))
                addressField = EditText(context).apply {
                    hint = getString(R.string.pc_screen_address_hint)
                    setText(store.address)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setTextColor(com.hayeco.flipspaces.core.Look.INK)
                    setHintTextColor(com.hayeco.flipspaces.core.Look.INK_3)
                    typeface = com.hayeco.flipspaces.ui.LookType.regular
                    background = kit.shape(com.hayeco.flipspaces.core.Look.PAD, com.hayeco.flipspaces.core.Look.RADIUS_M, com.hayeco.flipspaces.core.Look.RIM)
                    setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(14f))
                }
                addView(addressField)
                addView(kit.secondary(getString(R.string.pc_screen_pair)) { pair() }, fullWidth(top = 10f))
            }
            addView(kit.foldable(getString(R.string.pc_screen_section_address), byAddress), fullWidth(top = 10f))
            forgetLink = kit.link(getString(R.string.pc_screen_forget), com.hayeco.flipspaces.core.Look.DANGER) {
                store.forget()
                addressField.setText("")
                autoTried = false
                say(getString(R.string.pc_screen_forgotten))
                finder.stop()
                finder.start()
            }
            addView(forgetLink, fullWidth(top = 14f))
        }
        refreshSetup()
        // One column, at most 560 dp wide, centred, scrolling when the window is short.
        // Centred in the window's own width (the display's, used before, is wider than the window: the page was cut off on
        // the left, 19 Nov 2026), at most 560 dp.
        return android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
                if (r - l == or - ol) return@addOnLayoutChangeListener
                val side = maxOf(kit.dp(24f), ((r - l) - kit.dp(560f)) / 2)
                if (column.paddingLeft != side) v.post { column.setPadding(side, column.paddingTop, side, column.paddingBottom) }
            }
        }
    }

    private fun fullWidth(top: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(top) }

    /** Codec, smoothness and quality, each a row of choices; a choice fills the box again with it lit. */
    private fun fillPicture(box: LinearLayout) {
        box.removeAllViews()
        val auto = getString(R.string.pc_screen_auto)
        box.addView(kit.setting(getString(R.string.pc_screen_codec), kit.segmented(listOf(auto, "H.264", "AV1"), PcScreenStore.CODECS.indexOf(store.codec)) {
            store.codec = PcScreenStore.CODECS[it]; fillPicture(box)
        }))
        box.addView(kit.setting(getString(R.string.pc_screen_smoothness), kit.segmented(listOf(auto, "60 Hz", "120 Hz"), PcScreenQuality.FPS.indexOf(store.fps)) {
            store.fps = PcScreenQuality.FPS[it]; fillPicture(box)
        }))
        box.addView(kit.setting(getString(R.string.pc_screen_quality_name), kit.segmented(
            listOf(auto, getString(R.string.pc_screen_quality_sharp), getString(R.string.pc_screen_quality_saver)), PcScreenQuality.QUALITY.indexOf(store.quality),
        ) {
            store.quality = PcScreenQuality.QUALITY[it]; fillPicture(box)
        }))
    }

    /** Connect and Forget show only once this PC screen has a PC. */
    private fun refreshSetup() {
        if (!::connectButton.isInitialized) return
        val paired = store.paired
        connectButton.visibility = if (paired) View.VISIBLE else View.GONE
        forgetLink.visibility = if (paired) View.VISIBLE else View.GONE
        // The name as the PC gives it ("Mike"), from the PCs found; the saved one is lower case (serverinfo's, lowered).
        val shown = lastHosts.firstOrNull { PcScreenAddress.parse(it.typed)?.first == PcScreenAddress.parse(store.address)?.first }
            ?.let { PcScreenPick.pcName(it.service) }
            ?: pcName()?.let { PcScreenPick.pcName(it).replaceFirstChar { c -> c.titlecase() } }
        (connectButton as? TextView)?.text = shown?.let { getString(R.string.pc_screen_connect_to, it) } ?: getString(R.string.pc_screen_connect)
    }

    private fun say(text: String) {
        main.post {
            status.text = text
            status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            refreshSetup()
        }
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
        Thread({
            try {
                if (knownPairing(address)) {
                    say(getString(R.string.pc_screen_reused, PcScreenAddress.parse(address)?.first ?: address))
                    main.post { connect() }
                    return@Thread
                }
                say(getString(R.string.pc_screen_pairing, PcScreenAddress.parse(address)?.first ?: address))
                // The PC helper types the PIN into Apollo for the user (one Yes on the PC the first time); without it,
                // the PIN to type in Apollo's web page.
                Thread({ pinThroughHelper(address, pin) }, "PcScreen-helper").start()
                val http = NvHTTP(tuple(address), 0, store.uniqueId, null, crypto)
                val info = http.getServerInfo(true)
                val details = http.getComputerDetails(info)
                val result = http.pairingManager.pair(info, pin)
                Log.i(TAG, "pair with ${details.name} at $address: $result")
                val cert = http.pairingManager.pairedCert
                if (result == PairingManager.PairState.PAIRED && cert != null) {
                    // The MAC (for Wake-on-LAN) only comes over HTTPS to a paired phone.
                    val secure = runCatching { NvHTTP(tuple(address), details.httpsPort, store.uniqueId, cert, crypto).getServerInfo(true) }.getOrNull()
                    store.savePairing(address, details.httpsPort, cert, secure?.let { macOf(it) })
                    if (store.pcName == null) store.pcName = details.name
                    val other = sharedWith(address)
                    if (other == null) {
                        say(getString(R.string.pc_screen_paired_with, details.name))
                        main.post { connect() }
                    } else {
                        say(getString(R.string.pc_screen_paired_shared, details.name, other + 1))
                    }
                } else {
                    say(getString(R.string.pc_screen_pair_failed, result.toString()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "pair: $e")
                say(trouble(PcScreenTrouble.of(e), getString(R.string.pc_screen_pair_failed, e.message ?: e.toString())))
            }
        }, "PcScreen-pair").start()
    }

    private fun connect() {
        if (!store.paired) return say(getString(R.string.pc_screen_pair_first))
        Log.i(TAG, "connect: PC screen ${slot + 1}, host ${store.address}${sharedWith(store.address)?.let { ", also PC screen ${it + 1}'s host" } ?: ""}")
        wantStream = true
        main.removeCallbacks(reconnect)
        // The page stays up, saying what happens (connecting, waking the PC), until the picture starts
        // ([connectionStarted]); hidden at once it left a black window while the PC was woken (19 Nov 2026).
        surface.visibility = View.VISIBLE
        // The stream starts once the surface exists (surfaceChanged), at the window's own size. Again after a reconnect
        // wait: the surface was hidden and comes back.
        if (surface.holder.surface.isValid && connection == null && !starting) startStream(surface.holder, surface.width, surface.height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (connection != null || starting || surface.visibility != View.VISIBLE) return
        startStream(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    /**
     * A stream is being set up (its connect thread has not made the connection yet). The phone test of PC screen 2
     * (19 Nov 2026) showed two surfaceChanged calls 100 ms apart starting two streams, two decoders and two
     * connections in one process. Main thread.
     */
    private var starting = false

    /** Counts stream starts and stops: a connect thread whose start was stopped meanwhile drops its connection. */
    @Volatile private var generation = 0

    /** The address this stream uses: the home one, or the PC's Tailscale name away from home ([route]). */
    @Volatile private var activeAddress: String? = null

    /**
     * Home or away: the home address when the PC answers there; else, with a VPN up (Tailscale), the PC's Tailscale
     * name with the same port; else the home address (and the usual wake and trouble steps). Blocking.
     */
    private fun route(home: String): String {
        if (PcScreenRemote.answers(home, 1500)) return home
        moved(home)?.let { return it }
        val away = PcScreenRemote.awayAddress(home, pcName()) ?: return home
        val vpn = PcScreenRemote.vpnUp(this)
        val ok = vpn && PcScreenRemote.answers(away, 4000)
        Log.i(TAG, "route: home $home does not answer; away $away ${if (!vpn) "not tried (no VPN up)" else if (ok) "answers" else "does not answer"}")
        return if (ok) away else home
    }

    /**
     * The saved Apollo does not answer, but the same PC has another, free for this PC screen, found on the Wi-Fi: the PC
     * setup was run again and gave this screen's Apollo another port (Mike's PC, 19 Nov 2026: PC screen 2 kept trying
     * 49089 while its Apollo was at 48989). Taken and saved; null if there is none. Blocking.
     */
    private fun moved(home: String): String? {
        val host = PcScreenAddress.parse(home)?.first ?: return null
        val usedBy = (0 until PcScreenSlots.COUNT).filter { it != slot }.map { PcScreenAddress.parse(PcScreenStore(this, it).address) }.toSet()
        val instances = PcScreenPick.byPc(lastHosts).firstOrNull { (_, list) -> list.any { it.address == host } }?.second ?: return null
        val pick = PcScreenPick.instanceFor(slot, instances) { PcScreenAddress.parse(it.typed) !in usedBy } ?: return null
        if (pick.typed == home || !PcScreenRemote.answers(pick.typed, 1500)) return null
        // Its own HTTPS port too (the old one's stayed: 49084 for 48989's Apollo); the pairing is checked after, and
        // paired again by itself if this Apollo does not know the phone.
        val https = runCatching { NvHTTP(tuple(pick.typed), 0, store.uniqueId, null, crypto).let { it.getHttpsPort(it.getServerInfo(true)) } }.getOrNull() ?: return null
        val cert = store.serverCert ?: return null
        Log.i(TAG, "route: $home does not answer; this PC's Apollo for PC screen ${slot + 1} is now at ${pick.typed} (HTTPS $https): using it")
        store.savePairing(pick.typed, https, cert, store.mac)
        main.post { addressField.setText(pick.typed) }
        return pick.typed
    }

    private fun startStream(holder: SurfaceHolder, viewWidth: Int, viewHeight: Int) {
        val home = store.address
        val cert = store.serverCert ?: return
        starting = true
        val gen = ++generation
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
        say(getString(R.string.pc_screen_connecting, home))
        Thread({
            try {
                // Home or away, waking the PC first (at home, or through the wake relay) when it does not answer.
                val (address, info) = serverInfoWaking(home, cert, gen)
                activeAddress = address
                runCatching { NvHTTP(tuple(address), 0, store.uniqueId, cert, crypto).getHttpsPort(info) }.getOrNull()
                    ?.takeIf { it > 0 && it != store.httpsPort }?.let { https ->
                        Log.i(TAG, "HTTPS port now $https (was ${store.httpsPort})")
                        store.savePairing(store.address, https, cert, store.mac)
                    }
                val http = NvHTTP(tuple(address), store.httpsPort, store.uniqueId, cert, crypto)
                // Apollo makes a virtual display of the stream's size (VirtualDisplayCapable, its driver ready): the
                // stream takes the window's own shape, so it fills the window. Elsewhere 1920 x 1080, the usual
                // desktop, which the host sends without stretching.
                // The PC's name for away from home, learnt at home if it was paired before names were kept.
                if (store.pcName == null && address == home) xml(info, "hostname")?.let { store.pcName = it }
                // Apollo says what this phone may do: without launching, or the mouse and keyboard, say how to fix it
                // before trying (a refused launch is a bare 403).
                // The PC no longer knows this phone (Apollo reinstalled, or the phone removed from it): serverinfo came
                // over plain HTTP, with Permission 0 (Mike's PC, 19 Nov 2026: "Apollo is blocking this phone" after a
                // fresh Apollo). Pair again by itself, the PC helper typing the PIN.
                if (xml(info, "PairStatus") != "1") {
                    Log.i(TAG, "the PC does not know this phone any more: pairing again")
                    main.post {
                        stopStream()
                        store.unpair()
                        surface.visibility = View.GONE
                        setup.visibility = View.VISIBLE
                        addressField.setText(home)
                        pair()
                    }
                    return@Thread
                }
                xml(info, "Permission")?.toLongOrNull()?.let { perm ->
                    Log.i(TAG, "permission 0x${java.lang.Long.toHexString(perm)}")
                    if (perm and PERMS_NEEDED != PERMS_NEEDED) {
                        showSetup(trouble(PcScreenTrouble.NO_PERMISSION, ""))
                        return@Thread
                    }
                }
                val vd = xml(info, "VirtualDisplayCapable") == "true" && xml(info, "VirtualDisplayDriverReady") != "false"
                val (w, h) = if (vd) PcScreenShape.forWindow(windowW, windowH) else (STREAM_W to STREAM_H)
                shapedToWindow = vd
                streamW = w
                streamH = h
                main.post { root.requestLayout() }
                // 120 frames a second where every codec offered decodes this size at 120 (Auto), else 60: a picture
                // is half as old when the Space takes it.
                // Auto: 120 only while no other PC screen streams. Two 120 Hz AV1 streams starting together made the
                // Flip 7's decoder refuse five times before it took one (phone log, 19 Nov 2026).
                val others = PcScreenAudio.othersStreaming(applicationContext, slot)
                val fps = PcScreenQuality.fps(store.fps, others == 0 && PcScreenDecoder.decodes(formats, w, h, 120))
                val kbps = PcScreenQuality.bitrateKbps(w, h, fps, store.quality)
                val apps = http.appList
                val app = apps.firstOrNull { it.appName.equals("Desktop", ignoreCase = true) } ?: apps.firstOrNull()
                    ?: throw IllegalStateException("the host lists no apps")
                val config = StreamConfiguration.Builder()
                    .setApp(app)
                    .setResolution(w, h)
                    .setRefreshRate(fps)
                    .setClientRefreshRateX100(fps * 100)
                    .setBitrate(kbps)
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
                // A reconnect after the stream was lost resumes the session instead (NvConnection resumes a running
                // session of the same app): its virtual display, and the PC's windows on it, stay as they were.
                val resuming = lostAtMs > 0
                val running = runCatching { http.getCurrentGame(http.getServerInfo(true)) }.getOrDefault(0)
                if (running != 0 && !resuming) {
                    val quit = runCatching { http.quitApp() }
                    Log.i(TAG, "a session was running (app $running): ended it for a fresh one: ${quit.exceptionOrNull() ?: "done"}")
                }
                Log.i(TAG, "connecting to $address, app ${app.appName}, ${w}x$h@$fps (${store.fps}), ${kbps / 1000} Mbps (${store.quality}), $others other PC screens streaming, window ${windowW}x$windowH, virtual display $vd, codec ${store.codec}, formats 0x${Integer.toHexString(formats)}${if (resuming) ", resuming after a drop (try $attempt)" else ""}")
                if (gen != generation) {
                    Log.i(TAG, "connect: stopped before it connected; not connecting")
                    return@Thread
                }
                connection = NvConnection(applicationContext, tuple(address), store.httpsPort, store.uniqueId, config, crypto, cert)
                    .also {
                        // One PC screen plays the PC's sound: the latest one Apollo sends it to ([PcScreenAudio]).
                        it.start(PcScreenAudio(applicationContext, slot, AndroidAudioRenderer(applicationContext, false)), d, this)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "connect: $e")
                val kind = PcScreenTrouble.of(e)
                if (wantStream && kind.canRetry()) main.post { lost("connect: $e") }
                else showSetup(trouble(kind, getString(R.string.pc_screen_connect_failed, e.message ?: e.toString())))
            } finally {
                main.post { if (gen == generation) starting = false }
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
        wantStream = false
        lostAtMs = 0
        main.removeCallbacks(reconnect)
        val wasStreaming = connection != null || sessionStarted
        stopStream()
        sessionStarted = false
        if ((!wasStreaming && !force) || !store.paired) return
        val address = activeAddress ?: store.address
        val cert = store.serverCert ?: return
        val port = store.httpsPort
        val id = store.uniqueId
        Thread({
            val r = runCatching { NvHTTP(tuple(address), port, id, cert, crypto).quitApp() }
            Log.i(TAG, "session ended on the PC: ${r.exceptionOrNull() ?: r.getOrNull()}")
        }, "PcScreen-quit").start()
    }

    /**
     * The address to use and its serverinfo, waking the PC first if it does not answer and its MAC is known: a magic
     * packet on this network ([PcScreenWake]), and the wake relay at home asked to send one there ([WakeRelay]), which is
     * what wakes it from away. Then up to 90 s for it to come up, choosing home or away ([route]) again each time.
     * Blocking: the connect thread.
     */
    private fun serverInfoWaking(home: String, cert: java.security.cert.X509Certificate, gen: Int): Pair<String, String> {
        // The HTTPS port asked of the Apollo each time (0: NvHTTP reads it from the plain serverinfo), never the saved one:
        // a saved port went stale when the PC setup gave this screen's Apollo another (19 Nov 2026: 49084 for 48989).
        fun info(address: String, likelyOnline: Boolean) = NvHTTP(tuple(address), 0, store.uniqueId, cert, crypto).getServerInfo(likelyOnline)
        val firstAddress = route(home)
        val first = try {
            return firstAddress to info(firstAddress, true)
        } catch (e: Exception) {
            if (PcScreenTrouble.of(e) != PcScreenTrouble.UNREACHABLE || PcScreenWake.macBytes(store.mac) == null) throw e
            e
        }
        val host = PcScreenAddress.parse(home)?.first ?: home
        val mac = store.mac!!
        say(getString(R.string.pc_screen_waking, pcName()?.let { PcScreenPick.pcName(it).replaceFirstChar { c -> c.titlecase() } } ?: host))
        fun wake() {
            val here = PcScreenWake.send(mac, host)
            val relay = WakeRelay.ask(this, mac, host)
            Log.i(TAG, "the PC does not answer: Wake-on-LAN here ${if (here) "sent" else "not sent"}, wake relay ${if (relay) "sent it" else if (WakeRelay.known(this) == null) "none known" else "did not answer"}")
        }
        wake()
        val until = android.os.SystemClock.uptimeMillis() + 90_000
        var round = 0
        while (android.os.SystemClock.uptimeMillis() < until && gen == generation) {
            Thread.sleep(3000)
            val address = route(home)
            val got = runCatching { info(address, false) }.getOrNull()
            if (got != null) {
                Log.i(TAG, "the PC is awake, at $address")
                return address to got
            }
            if (++round % 5 == 0) wake()
        }
        throw first
    }

    /**
     * Reconnect: the user asked for the stream ([connect]) and has not closed it. A stream lost without being asked to
     * end (Wi-Fi gone, the phone asleep, the PC busy) is tried again ([lost]), resuming its session, until
     * [PcScreenQuality.GIVE_UP_MS] has passed. Main thread.
     */
    private var wantStream = false
    private var lostAtMs = 0L
    private var attempt = 0
    private val reconnect = Runnable { if (wantStream) connect() }

    private fun PcScreenTrouble.canRetry() = this == PcScreenTrouble.UNREACHABLE || this == PcScreenTrouble.OTHER

    /** The stream went without being asked to: try again after a wait, or give up and say so. Main thread. */
    private fun lost(why: String) {
        stopStream()
        if (!wantStream) return
        val now = android.os.SystemClock.uptimeMillis()
        if (lostAtMs == 0L) lostAtMs = now
        val delay = PcScreenQuality.reconnectDelayMs(attempt, now - lostAtMs)
        surface.visibility = View.GONE
        setup.visibility = View.VISIBLE
        if (delay == null) {
            Log.i(TAG, "reconnect: gave up after ${(now - lostAtMs) / 1000} s ($why)")
            wantStream = false
            lostAtMs = 0
            attempt = 0
            status.text = getString(R.string.pc_screen_lost)
            return
        }
        attempt++
        Log.i(TAG, "reconnect: try $attempt in ${delay / 1000} s ($why)")
        val name = pcName()?.let { PcScreenPick.pcName(it).replaceFirstChar { c -> c.titlecase() } } ?: (PcScreenAddress.parse(store.address)?.first ?: store.address)
        // A stream that never started (two PC screens starting together failed once: "video stream establishment (-1)")
        // is still connecting; one that ran was lost.
        status.text = getString(if (sessionStarted) R.string.pc_screen_reconnecting else R.string.pc_screen_connecting_again, name)
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
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
        // The trackpad's Win key ([com.hayeco.flipspaces.ShortcutKeys]): Android keeps a real Win (Meta) key for itself and
        // never passed it on (phone test, 19 Nov 2026), so the trackpad sends a stand-in, which goes to the PC as Win.
        if (event.keyCode == com.hayeco.flipspaces.ShortcutKeys.WIN_STAND_IN && event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) {
            winHeld = event.action == android.view.KeyEvent.ACTION_DOWN
            val mods = (if (winHeld) com.limelight.nvstream.input.KeyboardPacket.MODIFIER_META.toInt() else 0).toByte()
            val action = if (winHeld) com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN else com.limelight.nvstream.input.KeyboardPacket.KEY_UP
            MoonBridge.sendKeyboardInput(VK_LWIN, action, mods, 0.toByte())
            return true
        }
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
        if (event.isMetaPressed || winHeld) mods = mods or com.limelight.nvstream.input.KeyboardPacket.MODIFIER_META.toInt()
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
        generation++
        starting = false
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
        finder.stop()
        relayFinder.stop()
        PcScreenSurfaces.listener = null
        endSession()
        super.onDestroy()
    }

    // NvConnectionListener
    override fun stageStarting(stage: String) = say(getString(R.string.pc_screen_stage, stage))
    override fun stageComplete(stage: String) = Unit
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        Log.w(TAG, "stage $stage failed: ports $portFlags, error $errorCode")
        val kind = PcScreenTrouble.ofCode(errorCode)
        if (wantStream && kind.canRetry()) main.post { lost("stage $stage failed ($errorCode)") }
        else showSetup(trouble(kind, getString(R.string.pc_screen_connect_failed, "$stage ($errorCode)")))
    }
    override fun connectionStarted() {
        streaming = true
        sessionStarted = true
        main.post {
            setup.visibility = View.GONE
            if (lostAtMs > 0) Log.i(TAG, "reconnect: back after ${(android.os.SystemClock.uptimeMillis() - lostAtMs) / 1000} s, try $attempt")
            lostAtMs = 0
            attempt = 0
        }
        Log.i(TAG, "streaming")
    }
    override fun connectionTerminated(errorCode: Int) {
        Log.i(TAG, "ended: $errorCode, ${decoder?.shown ?: 0} shown, ${decoder?.dropped ?: 0} dropped")
        // Ended by the PC on purpose (0): say so. Lost (Wi-Fi, sleep, no video): reconnect.
        if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) main.post { lost("ended $errorCode") }
        else showSetup(getString(R.string.pc_screen_ended, errorCode))
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
        const val VK_LWIN: Short = 0x5b

        /** Apollo's permission bits a PC screen needs: launch apps (0x4000000), mouse (0x800) and keyboard (0x1000). */
        const val PERMS_NEEDED = 0x4000000L or 0x800L or 0x1000L
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
