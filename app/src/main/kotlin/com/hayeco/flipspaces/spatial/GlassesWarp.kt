package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.ControlFramer
import com.hayeco.flipspaces.core.spatial.GlassesControl
import com.hayeco.flipspaces.core.spatial.GlassesWarpController
import com.hayeco.flipspaces.core.spatial.WarpRecordTest
import com.hayeco.flipspaces.platform.ShizukuBridge

/**
 * 0.5.21: runs [GlassesWarpController] for the Space. It holds the link to the glasses'
 * control port (169.254.2.1:52999, the app's own socket or the Shizuku helper in duplex mode), feeds
 * the controller the time, the switch, whether the Space is in front and running, and what the
 * glasses send, and tells the renderer whether to draw the frame record ([drawRecords]).
 *
 * The switch ("Glasses warp (beta)", [SpaceStore.glassesWarp]) is off by default; while it is off
 * no link is opened and nothing is sent. Every command, reply and restore goes to the log under
 * [TAG], with its time.
 */
object GlassesWarp {
    const val TAG = "FlipSpaces/Warp"
    private const val TICK_MS = 10L
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 200
    private const val USB_CHECK_MS = 250L

    private val lock = Object()
    private var app: Context? = null
    private var loop: Thread? = null
    @Volatile private var link: GlassesNetwork.Duplex? = null
    @Volatile private var opening = false
    private var switchOn = false
    private var foreground = false
    private var spaceRunning = false

    private val controller = GlassesWarpController(object : GlassesWarpController.Io {
        override fun send(bytes: ByteArray): Boolean {
            val l = link ?: return false
            return try {
                l.write(bytes)
                true
            } catch (e: Exception) {
                Log.w(TAG, "glasses warp: write failed (${e.javaClass.simpleName}: ${e.message}); closing the link")
                drop(l)
                false
            }
        }

        override fun phoneNs(): Long = System.nanoTime()

        override fun log(line: String) {
            Log.i(TAG, line)
        }

        // 0.5.23: a reset, a lost link or a new glasses display turns the switch off,
        // remembered, so nothing starts again until Mike presses it on.
        override fun paused(why: String) {
            switchOn = false
            pausedWhy = why
            app?.let { SpaceStore(it).glassesWarp = false }
            // 0.5.26: said on the glasses too (the Space shows it, head locked, for a few seconds).
            offNotice = when (why) {
                com.hayeco.flipspaces.core.spatial.WarpRestore.SCENE_DROPPED.label -> OFF_DROPPED
                com.hayeco.flipspaces.core.spatial.WarpRestore.RECORD_COVERED.label -> OFF_COVERED
                else -> FlipSpacesApp.text(R.string.sys_warp_off_why, why).take(60)
            }
        }

        override fun testFrames(): Long = testFrames

        // 0.5.24: a 3D record test ended; the switch is off and the Menu asks what the glasses showed.
        override fun testEnded(test: WarpRecordTest, line: String) {
            switchOn = false
            awaitingAnswer = test
            lastTestLine = line
            app?.let { SpaceStore(it).glassesWarp = false }
        }

        // 0.5.24: the glasses' own log, received the way the control link went.
        override fun openGlassesLog() {
            app?.let { GlassesLogReceiver.open(it, linkRoute) }
        }

        override fun glassesLogReady(): Boolean = GlassesLogReceiver.ready

        override fun glassesLogError(): String? = GlassesLogReceiver.error

        override fun closeGlassesLog(why: String) = GlassesLogReceiver.close(why)

        override fun glassesLogSummary(): String = GlassesLogReceiver.summary()

        // 0.5.25: the records drawn just before the glasses dropped out of scene mode 1.
        override fun recentRecords(): String = synchronized(records) {
            if (records.isEmpty()) return@synchronized "no record drawn yet"
            val now = SystemClock.elapsedRealtime()
            "the last records the phone drew: " + records.joinToString { r ->
                "%d (present %.3f s on the glasses' clock, drawn %d ms ago)".format(java.util.Locale.ROOT, r.frame, r.glassesPresentNs / 1e9, now - r.drawnMs)
            } + if (now - records.last().drawnMs > RECORD_GAP_MS) "; none for ${now - records.last().drawnMs} ms before this line (a stall on the phone)" else ""
        }
    })

    /**
     * 0.5.26: why the switch just turned itself off, for the Space to say on the glasses ([takeOffNotice]); one line of
     * about 44 characters.
     */
    @Volatile private var offNotice: String? = null
    val OFF_DROPPED: String get() = FlipSpacesApp.text(R.string.sys_warp_off_dropped)
    val OFF_COVERED: String get() = FlipSpacesApp.text(R.string.sys_warp_off_covered)
    val REFUSED_2D: String get() = FlipSpacesApp.text(R.string.sys_warp_refused_2d)
    val REFUSED_2D_SHORT: String get() = FlipSpacesApp.text(R.string.sys_warp_refused_2d_short)

    /** The Space's frame: a notice to show now, once. */
    fun takeOffNotice(): String? = offNotice?.also { offNotice = null }

    /** 0.5.26: the glasses' last status line in their own log this warp (their screen's quad, rates), or null. */
    val glassesStatus: com.hayeco.flipspaces.core.spatial.GlassesLogEvents.Event.Status? get() = controller.lastStatus

    /** 0.5.27: the screen size factor the glasses last named in their log, or null before any. */
    val glassesFovFactor: Float? get() = controller.screenFactor

    /** 0.5.26: the content rate that paces evenly on the glasses' panels while they warp, or null ([GlassesWarpController.evenContentHz]). */
    val evenContentHz: Int? get() = controller.evenContentHz

    /** 0.5.25: one record drawn: its number, its present on the glasses' clock, and when (elapsedRealtime). */
    private class Drawn(val frame: Long, val glassesPresentNs: Long, val drawnMs: Long)

    private val records = ArrayDeque<Drawn>()
    private const val RECORDS_KEPT = 6
    private const val RECORD_GAP_MS = 50L

    /** 0.5.25: the renderer drew record [frame], for the present [glassesPresentNs] on the glasses' clock. GL thread. */
    fun recordDrawn(frame: Long, glassesPresentNs: Long) {
        synchronized(records) {
            records.addLast(Drawn(frame, glassesPresentNs, SystemClock.elapsedRealtime()))
            while (records.size > RECORDS_KEPT) records.removeFirst()
        }
    }

    /**
     * 0.5.26: something of DeX's ([what]) is composed over the record's rows while the glasses
     * warp: the warp ends now (scene mode 0) and the switch pauses, said on the glasses. Main thread.
     */
    fun recordCovered(context: Context, what: String) {
        synchronized(lock) {
            app = context.applicationContext
            controller.recordCovered(what, SystemClock.elapsedRealtime())
        }
    }

    /** 0.5.25: one line of the glasses' own log while the warp has it on ([GlassesWarpController.glassesLogLine]). Any thread. */
    fun glassesLogLine(text: String) {
        if (!text.contains("[Flinger]")) return
        synchronized(lock) { controller.glassesLogLine(text, SystemClock.elapsedRealtime()) }
    }

    /** 0.5.24: the route the open control link took, for the glasses' log listener. */
    @Volatile private var linkRoute: GlassesNetwork.Route? = null

    /** 0.5.24: whether this app session has read the saved switch yet ([GlassesWarpController.switchAtSessionStart]). */
    private var sessionLoaded = false

    /** 0.5.24: the switch as it is in this app session; the first read turns a saved on into off. */
    fun switchOn(context: Context): Boolean = synchronized(lock) {
        loadSession(context)
        switchOn
    }

    private fun loadSession(context: Context) {
        if (sessionLoaded) return
        sessionLoaded = true
        app = context.applicationContext
        val store = SpaceStore(context)
        val remembered = store.glassesWarp
        switchOn = GlassesWarpController.switchAtSessionStart(remembered)
        if (remembered) store.glassesWarp = false
        GlassesWarpController.sessionStartLine(remembered)?.let { Log.i(TAG, it) }
    }

    /** 0.5.24: DeX's chrome over the Space as the last composition check read it, said when the warp turns on. */
    @Volatile var dexChrome: String? = null

    /**
     * 0.5.25 (in 2D-104 DeX's title bar and border were still composed 1 s after the hold, 40 of
     * 1080 rows, over the record's top two): the last check found DeX's chrome over the Space's top rows. The switch is
     * refused while it is, and the check is asked for again ([recheckChrome], set by the Space).
     */
    @Volatile var chromeOverRecord = false
    @Volatile var recheckChrome: (() -> Unit)? = null

    private fun chromeNote(): String = dexChrome?.let { "; DeX chrome composed with the Space at the last check: $it" } ?: ""

    /** 0.5.24: the 3D record test running now (frames draw its record), or null. */
    val recordTest: WarpRecordTest? get() = controller.test

    /** 0.5.24: frames drawn for the running test, counted by the renderer. */
    @Volatile var testFrames = 0L

    /** 0.5.24: whether the Space's picture is side by side 3D (3840x1080), set by the renderer each frame. */
    @Volatile var pictureStereo = false

    /** 0.5.24: the test that ended and waits for Mike's word on what the glasses showed, and its line. */
    @Volatile var awaitingAnswer: WarpRecordTest? = null
        private set
    @Volatile var lastTestLine: String? = null
        private set

    /**
     * 0.5.24: a 3D record test chosen on the Menu. Null when it started; else why not (2D, the
     * switch on, no Space), which is also logged. Not remembered: it is one 30 s run.
     */
    fun startTest(context: Context, test: WarpRecordTest, from: String): String? = synchronized(lock) {
        loadSession(context)
        app = context.applicationContext
        Log.i(TAG, "${test.title} chosen from $from${chromeNote()}")
        testFrames = 0L
        awaitingAnswer = null
        val now = SystemClock.elapsedRealtime()
        if (!controller.startTest(test, pictureStereo, now)) {
            return@synchronized when {
                !pictureStereo -> FlipSpacesApp.text(R.string.sys_warp_test_needs_3d)
                switchOn -> FlipSpacesApp.text(R.string.sys_warp_turn_off_first)
                else -> FlipSpacesApp.text(R.string.sys_warp_open_space_first)
            }
        }
        switchOn = true
        pausedWhy = null
        ensureLoop()
        null
    }

    /** 0.5.24: Mike's word on what the glasses showed during [awaitingAnswer], logged with the test's line. */
    fun testAnswer(answer: String, from: String) {
        val t = awaitingAnswer ?: return
        awaitingAnswer = null
        Log.i(TAG, "glasses warp: ${t.title}: Mike says the glasses showed: $answer (from $from); the test's own line: ${lastTestLine ?: "none"}")
    }

    /**
     * 0.5.24: the glasses display the Space was on went. Whether the glasses are still on USB says
     * which: unplugged (nothing is sent) or their DisplayPort dropped with the cable in (scene mode 0 on the open link).
     */
    fun displayRemoved(context: Context, displayId: Int) {
        val usb = glassesOnUsb(context)
        synchronized(lock) {
            if (controller.phase == GlassesWarpController.Phase.IDLE) return
            Log.i(TAG, "glasses warp: display $displayId removed; the glasses ${if (usb) "are still on USB" else "are not on USB any more (unplugged)"}")
            controller.displayGone(displayId, unplugged = !usb, SystemClock.elapsedRealtime())
        }
    }

    /** 0.5.24: an XREAL device is on the phone's USB now. */
    private fun glassesOnUsb(context: Context): Boolean = runCatching {
        context.getSystemService(android.hardware.usb.UsbManager::class.java).deviceList.values.any { com.hayeco.flipspaces.core.hands.GlassesUsb.isXreal(it.vendorId) }
    }.getOrDefault(true)

    private var usbCheckedMs = 0L

    /** 0.5.23: why the switch turned itself off, shown on the Menu and the Control panel until it is pressed again. */
    @Volatile private var pausedWhy: String? = null

    /** Whether frames carry the NORMAL record now (the glasses accepted scene mode 1 and were set to LSR). */
    val drawRecords: Boolean get() = controller.drawRecords

    /**
     * The glasses' recentres to a new target, read from their own log (0.5.25; 10279 is never sent); the renderer zeroes
     * the record's yaw again when it changes.
     */
    val recenters: Int get() = synchronized(lock) { controller.recenters }

    /** Mike pressed Recentre: with the switch on and the glasses warping, the glasses recenter too (logged). */
    fun recentrePressed() = synchronized(lock) { controller.recentrePressed(SystemClock.elapsedRealtime()) }

    /** The glasses' time for a phone time (System.nanoTime), from the heartbeat clock. */
    fun toGlassesNs(phoneNs: Long): Long = synchronized(lock) { controller.clock.toGlasses(phoneNs) }

    /** A short state for the Control panel's switch. */
    val state: String
        get() = when {
            // 0.5.29: a drop by the glasses says so plainly, so it does not look like the Menu turned it off.
            !switchOn && pausedWhy == com.hayeco.flipspaces.core.spatial.WarpRestore.SCENE_DROPPED.label ->
                com.hayeco.flipspaces.core.spatial.WarpDropNote.menu(controller.lastDropHeldMs, controller.dropsThisSession)
            !switchOn -> pausedWhy?.let { "Off (paused: $it)" } ?: awaitingAnswer?.let { "Off (${it.title} done)" } ?: "Off"
            controller.test != null -> "${controller.test?.title}: " + when (controller.phase) {
                GlassesWarpController.Phase.ACTIVE -> "running"
                GlassesWarpController.Phase.RESTORING -> "ending"
                else -> "starting"
            }
            else -> when (controller.phase) {
                GlassesWarpController.Phase.IDLE -> if (spaceRunning) "On, waiting" else "On"
                GlassesWarpController.Phase.CONNECTING, GlassesWarpController.Phase.SYNCING -> "On, connecting"
                GlassesWarpController.Phase.ENTERING -> "On, asking the glasses"
                GlassesWarpController.Phase.ACTIVE -> "On, warping"
                GlassesWarpController.Phase.RESTORING -> "On, restoring"
            }
        }

    /**
     * The Space came to the front (onStart) on [displayId]: the switch is read from the store. 0.5.23: a different
     * glasses display from the one scene mode 1 went to pauses the switch first ([GlassesWarpController.glassesDisplay]).
     */
    fun spaceStarted(context: Context, displayId: Int) {
        synchronized(lock) {
            app = context.applicationContext
            // 0.5.24: the saved switch is read once a session, and a saved on starts off ([loadSession]).
            loadSession(context)
            foreground = true
            spaceRunning = true
            // First: a new display may pause the switch (it then reads off here), before anything could start.
            controller.glassesDisplay(displayId, SystemClock.elapsedRealtime())
            push()
            ensureLoop()
        }
    }

    /** The Space left the front (onStop): Pocket XR is in the background on the glasses. */
    fun spaceStopped() = synchronized(lock) {
        foreground = false
        push()
    }

    /**
     * The Space is gone (onDestroy): scene mode 0 goes out now on the open link, if scene mode 1 was
     * sent; the loop keeps going until the glasses answer or the controller gives up.
     */
    fun spaceEnded() = synchronized(lock) {
        spaceRunning = false
        foreground = false
        controller.shutdown(SystemClock.elapsedRealtime())
        ensureLoop()
    }

    /**
     * The switch, from the Control panel or the Menu; remembered. 0.5.25: null when done, else why it was refused (DeX's
     * chrome over the record's rows), which is also logged; nothing is sent then.
     */
    fun setSwitch(context: Context, on: Boolean, from: String): String? {
        // 0.5.26: the glasses never enter scene mode 1 from a 2D (1920x1080 at 120 Hz) input,
        // so in 2D nothing is sent and no record is drawn; what Mike saw working in 2D is the Space's own 2D tracking.
        if (on && spaceRunning && !pictureStereo) {
            Log.i(TAG, "glasses warp switch on from $from refused: the picture is 2D (1920x1080), and the glasses never enter scene mode 1 from a 2D input (0.5.25's glasses log: scene:0, dp_in:0 for 3 minutes); nothing is sent")
            offNotice = REFUSED_2D_SHORT
            return REFUSED_2D
        }
        if (on && chromeOverRecord && spaceRunning) {
            Log.i(TAG, "glasses warp switch on from $from refused: DeX's title bar or border is composed over the Space's top rows, where the record goes${chromeNote()}; the check is asked for again, and nothing is sent")
            recheckChrome?.invoke()
            return FlipSpacesApp.text(R.string.sys_warp_dex_title_bar)
        }
        val store = SpaceStore(context)
        store.glassesWarp = on
        Log.i(TAG, "glasses warp switch ${if (on) "on" else "off"} from $from (remembered for this session; every session starts with it off)${if (on) chromeNote() else ""}")
        synchronized(lock) {
            // 0.5.32: no LSR (the glasses' scene mode left as it is) and the bounded way back in after a drop, both on unless Developer turns them off.
            controller.sendLsr = !store.warpNoLsr
            controller.reenterAfterDrop = store.warpReenter
            if (on) Log.i(TAG, "glasses warp: ${if (controller.sendLsr) "LSR after scene mode 1 (0.5.31's way)" else "no LSR, the glasses' mode left as it is"}; ${if (controller.reenterAfterDrop) "back in ${GlassesWarpController.REENTER_SETTLE_MS / 1000.0} s after a drop, paused at the ${GlassesWarpController.MAX_DROPS_IN_WINDOW}rd drop in ${GlassesWarpController.DROP_WINDOW_MS / 1000} s" else "paused at the first drop (0.5.26's way)"}")
            app = context.applicationContext
            sessionLoaded = true
            switchOn = on
            pausedWhy = null
            awaitingAnswer = null
            push()
            ensureLoop()
        }
        return null
    }

    private fun push() = controller.setConditions(switchOn, foreground, spaceRunning, SystemClock.elapsedRealtime())

    private fun ensureLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({ run() }, "glasses-warp").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            var openNow = false
            var closeNow: GlassesNetwork.Duplex? = null
            // 0.5.24: while restoring, glasses gone from USB end the tries (no scene mode 0 into an empty port).
            val restoring = controller.phase == GlassesWarpController.Phase.RESTORING
            val unplugged = restoring && now - usbCheckedMs >= USB_CHECK_MS && app?.let { usbCheckedMs = now; !glassesOnUsb(it) } == true
            synchronized(lock) {
                if (unplugged) controller.glassesUnplugged(now)
                controller.tick(now)
                if (controller.wantsLink && link == null && !opening) {
                    opening = true
                    openNow = true
                }
                if (!controller.wantsLink && link != null) {
                    closeNow = link
                    link = null
                }
                if (!controller.wantsLink && !spaceRunning && !opening && link == null) {
                    loop = null
                    return
                }
            }
            closeNow?.let { runCatching { it.close() } }
            if (openNow) Thread({ open() }, "glasses-warp-open").apply { isDaemon = true; start() }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 0.5.32: opens a control link to the glasses on the first route that answers, blocking; the link, how it went and
     * the route, or null with what was tried. Shared with [GlassesDisplayControl].
     */
    internal fun openControl(context: Context?): Triple<GlassesNetwork.Duplex?, String, GlassesNetwork.Route?> {
        var opened: GlassesNetwork.Duplex? = null
        var how = ""
        var route: GlassesNetwork.Route? = null
        val tried = mutableListOf<String>()
        try {
            if (context != null) {
                val scan = GlassesNetwork.scan(context)
                val ready = ShizukuBridge.status(context) == ShizukuStatus.READY
                val routes = scan.routes(ready, emptySet()).filter { r -> scan.networks.any { it.iface == r.iface && it.reachable } || scan.kernel.any { it.first == r.iface } }
                for (r in routes) {
                    try {
                        // 1.1.8: the glasses' address on the route's link (169.254.1.1 on an ECM only phone).
                        opened = r.openDuplex(context, r.host, GlassesControl.PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                        how = "${r.label}, from ${opened.local}"
                        route = r
                        break
                    } catch (e: Exception) {
                        tried += "${r.label}: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }
        } catch (e: Exception) {
            tried += "${e.javaClass.simpleName}: ${e.message}"
        }
        return Triple(opened, if (opened != null) how else if (tried.isEmpty()) "no route to ${GlassesControl.HOST}" else tried.joinToString("; "), route)
    }

    /** 0.5.32: whether the warp holds (or is opening) the control link, so [GlassesDisplayControl] keeps off it. */
    val linkWanted: Boolean get() = synchronized(lock) { controller.wantsLink || link != null || opening }

    /** 1.1.0: the glasses' duty as read while they warped the Space's frames (the motion blur test); null before. */
    val dutyInWarp: Int? get() = controller.dutyInWarp

    /** 1.1.0: the motion blur test's switch: the warp reads the duty in scene mode 1 while it is on. */
    fun readDuty(on: Boolean) {
        controller.readDuty = on
    }

    /** Opens the link on the first route that answers; blocking, on its own thread. */
    private fun open() {
        val (opened, how, route) = openControl(app)
        val tried = if (opened == null) listOf(how) else emptyList()
        if (opened != null) linkRoute = route
        val now = SystemClock.elapsedRealtime()
        val l = opened
        synchronized(lock) {
            opening = false
            if (l == null) {
                controller.linkClosed(now, if (tried.isEmpty()) "no route to ${GlassesControl.HOST}" else tried.joinToString("; "))
                return
            }
            if (!controller.wantsLink) {
                runCatching { l.close() }
                return
            }
            link = l
            controller.linkOpened(now, how)
        }
        read(l ?: return)
    }

    private fun read(l: GlassesNetwork.Duplex) {
        val framer = ControlFramer()
        val buf = ByteArray(4096)
        var why = "closed"
        try {
            while (link === l) {
                val n = l.read(buf)
                if (n < 0) {
                    why = "the glasses closed the connection"
                    break
                }
                if (n == 0) continue
                val msgs = framer.append(buf, n)
                val now = SystemClock.elapsedRealtime()
                val ns = System.nanoTime()
                synchronized(lock) { msgs.forEach { controller.received(it, now, ns) } }
            }
        } catch (e: Exception) {
            why = "${e.javaClass.simpleName}: ${e.message}"
        }
        drop(l, why)
    }

    private fun drop(l: GlassesNetwork.Duplex, why: String = "a write failed") {
        val mine = synchronized(lock) {
            if (link === l) {
                link = null
                controller.linkClosed(SystemClock.elapsedRealtime(), why)
                true
            } else false
        }
        if (mine) runCatching { l.close() }
    }
}
