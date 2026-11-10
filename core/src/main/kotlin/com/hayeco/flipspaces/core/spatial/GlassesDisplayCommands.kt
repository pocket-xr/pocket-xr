package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.32 (Mike chose "lower persistence on turns" and "72 Hz 3D mode"): the two display commands the glasses take over
 * the control link, on a link of their own that is open only while the glasses warp's is not (the glasses never see
 * two). Each is behind its own Developer switch, off by default.
 *
 * - Duty: read once (10065) when the link opens; [TurnDuty] then lowers it on a fast turn and puts the glasses' own
 *   value back. The glasses overwrite it themselves on any brightness or mode change, so a
 *   lowered duty can be lost early; it is put back at every [restoreAll] all the same.
 * - EDID: read once (10078). [try72] asks for 3840x1080 at 72 Hz (index 6); the display re-plugs. If no 72 Hz mode is
 *   seen ([displayMode]) within [TRY_MS], index 5 (60 Hz) goes back by itself. Nothing seen so far shows the
 *   Aura offers index 6, so a refusal or no change is the likely answer.
 */
class GlassesDisplayCommands(private val io: Io) {
    interface Io {
        fun send(bytes: ByteArray): Boolean
        fun log(line: String)

        /** 0.5.36: the glasses' 3D screen distance as they answered it, metres (a change, or the first read). */
        fun depth(metres: Float) {}

        /** 0.5.65: the glasses' factory calibration JSON (10015) and their serial (10025, null if not answered yet). */
        fun calibration(serial: String?, json: String) {}

        /**
         * 0.5.91 (Mike's One got the Aura's calibration: the JSON read once per run of the app, and `last.json` loaded
         * whatever glasses were on): the glasses' serial as answered, so the app's side uses that pair's calibration.
         */
        fun serial(serial: String) {}
    }

    /** 0.5.65: the glasses' serial, as answered (10025); null before. */
    @Volatile var serial: String? = null
        private set

    /** 0.5.65: the glasses' 2D / 3D state as last answered (10273): 1 is 3D side by side, 0 is 2D; null before. */
    @Volatile var stereo: Int? = null
        private set

    private var stereoSentMs = Long.MIN_VALUE / 2
    private var calibrationJson: String? = null

    /** 0.5.91: the serial [calibrationJson] was read from (null when the serial was not answered first). */
    private var calibrationSerial: String? = null

    /**
     * 0.5.65: asks the glasses for 3D side by side ([sbs]) or 2D with the command seen in our captures of the glasses'
     * USB network traffic (10274), then reads the state back. Sent at most once per [STEREO_RETRY_MS], so glasses that refuse are not asked
     * on every beat. False when nothing was sent.
     */
    /** 0.5.65: forgets the 2D / 3D state and reads it again (the glasses' own button may have changed it). */
    fun readStereo() {
        stereo = null
        rpc(ControlWire::stereoGet, "2D / 3D state read (10273)")
    }

    fun setStereo(sbs: Boolean, nowMs: Long): Boolean {
        if (nowMs - stereoSentMs < STEREO_RETRY_MS) return false
        stereoSentMs = nowMs
        rpc({ ControlWire.stereoSet(it, sbs) }, if (sbs) "3D side by side (10274 = 1)" else "2D (10274 = 0)")
        rpc(ControlWire::stereoGet, "2D / 3D state read back (10273)")
        return true
    }

    /** 0.5.36: the glasses' spatial screen depth, metres, as last read (10400); null before. */
    @Volatile var depthM: Float? = null
        private set
    private var depthAskedMs = Long.MIN_VALUE / 2
    private var lastDepthRead: Float? = null

    val turnDuty = TurnDuty()

    /** 1.1.0: the hover duty test (Developer), off unless [hoverTest] turns it on. */
    val hover = HoverDutyTest()

    /** 1.1.0: the Developer switch for the hover duty test: on starts it, off puts the glasses back in follow. */
    fun hoverTest(on: Boolean, nowMs: Long) {
        if (on && !hover.active && hover.phase != HoverDutyTest.Phase.REFUSED) perform(hover.start())
        if (!on) perform(hover.stop())
        io.log("display control: hover duty test: ${hover.line()}")
    }

    /** A follow for hover left behind is on its way: a 3D request waits for its answer, as hover refuses 3D. */
    @Volatile var spaceSettling = false
        private set
    private var settleSentMs = 0L

    /**
     * Only for hover left behind: the glasses keep it through a re-plug, so an app that was killed or crashed leaves them
     * in it (6 November 2026, the Aura: a 1920 wide picture at duty 30 %, and windows moved twice). Never on every new
     * link: a space mode drops the glasses out of Ultrawide.
     */
    fun resetSpace(nowMs: Long) {
        spaceSettling = true
        settleSentMs = nowMs
        rpc({ ControlWire.spaceMode(it, GlassesControl.SPACE_FOLLOW) }, "space mode follow (10284 = 1), hover left over from before")
    }

    /** The glasses' menu settings as answered on this link, null before. Ultrawide and Spatial Anchor are 0 off, 1 on. */
    @Volatile var ultrawide: Int? = null
        private set
    @Volatile var screenSize: Float? = null
        private set
    @Volatile var screenSizeMax: Float? = null
        private set
    @Volatile var spatialAnchor: Int? = null
        private set
    @Volatile var spaceMode: Int? = null
        private set
    private var settingsAskedMs = Long.MIN_VALUE / 2
    private var stillSentMs = Long.MIN_VALUE / 2

    /** Spatial Anchor offs sent for this change: glasses where it does not hold are not held up by it. */
    private var anchorTries = 0

    /** Sets sent for the current Steady mode change; past [STILL_TRIES] it gives up. */
    @Volatile var stillTries = 0
        private set

    /** Ultrawide is read last: when it says on, the range beside it is already Ultrawide's own. */
    fun readSettings() {
        rpc({ ControlWire.settingGet(it, GlassesControl.SCREEN_SIZE_RANGE) }, "screen size range read (10396)")
        rpc({ ControlWire.settingGet(it, GlassesControl.SCREEN_SIZE_GET) }, "screen size read (10397)")
        rpc({ ControlWire.settingGet(it, GlassesControl.SPATIAL_ANCHOR_GET) }, "Spatial Anchor read (10404)")
        rpc({ ControlWire.settingGet(it, GlassesControl.SPACE_MODE_GET) }, "space mode read (10421)")
        rpc({ ControlWire.settingGet(it, GlassesControl.ULTRAWIDE_GET) }, "Ultrawide read (10277)")
    }

    /** A new Steady mode change starts: its tries count from nothing. */
    fun steadyStart() {
        stillTries = 0
        anchorTries = 0
        stillSentMs = Long.MIN_VALUE / 2
    }

    /**
     * 1.4.0: Spatial Anchor on, for Steady mode in 6DoF: the same set as [spatialAnchorOff] with 1. Read back, as its
     * value's field is inferred.
     */
    fun spatialAnchorOn() {
        rpc({ ControlWire.spatialAnchorSet(it, true) }, "Spatial Anchor on (10405 = 1), Steady mode in 6DoF")
        rpc({ ControlWire.settingGet(it, GlassesControl.SPATIAL_ANCHOR_GET) }, "Spatial Anchor read back (10404)")
    }

    /** Spatial Anchor off, for normal tracking too. Read back, as its value's field is inferred. */
    fun spatialAnchorOff() {
        rpc({ ControlWire.spatialAnchorSet(it, false) }, "Spatial Anchor off (10405 = 0)")
        rpc({ ControlWire.settingGet(it, GlassesControl.SPATIAL_ANCHOR_GET) }, "Spatial Anchor read back (10404)")
    }

    /**
     * One beat of a Steady mode change: at most one set each [STILL_RETRY_MS], so each is read back before the next goes
     * out and the display's re-plug has time to pass. Answers the step the glasses are on.
     */
    fun steady(on: Boolean, nowMs: Long, sixDof: Boolean = false): SteadyMode.Step {
        // Past its tries, Spatial Anchor counts as set the way this change wants it, so the change can finish.
        val anchorWanted = if (on && sixDof) 1 else 0
        val anchor = if (anchorTries >= ANCHOR_TRIES) anchorWanted else spatialAnchor
        val step = SteadyMode.next(on, SteadyMode.Glasses(stereo, ultrawide, anchor, screenSize, screenSizeMax), sixDof)
        if (nowMs - settingsAskedMs >= STILL_READ_MS) {
            settingsAskedMs = nowMs
            rpc(ControlWire::stereoGet, "2D / 3D state read (10273)")
            readSettings()
        }
        if (step == SteadyMode.Step.WAIT || step == SteadyMode.Step.DONE || nowMs - stillSentMs < STILL_RETRY_MS) return step
        stillSentMs = nowMs
        stillTries++
        when (step) {
            SteadyMode.Step.ANCHOR_OFF -> {
                anchorTries++
                spatialAnchorOff()
            }
            SteadyMode.Step.ANCHOR_ON -> {
                anchorTries++
                spatialAnchorOn()
            }
            SteadyMode.Step.LEAVE_3D -> rpc({ ControlWire.stereoSet(it, false) }, "2D (10274 = 0), on the way to Ultrawide")
            SteadyMode.Step.ULTRAWIDE_ON -> rpc({ ControlWire.ultrawideSet(it, true) }, "Ultrawide on (10278 = 1)")
            SteadyMode.Step.ULTRAWIDE_OFF -> rpc({ ControlWire.ultrawideSet(it, false) }, "Ultrawide off (10278 = 0)")
            SteadyMode.Step.SCREEN_MAX -> screenSizeMax?.let { max ->
                rpc({ ControlWire.screenSizeSet(it, max) }, "screen size %.2f, Ultrawide's largest (10398)".format(java.util.Locale.ROOT, max))
            }
            else -> Unit
        }
        return step
    }

    @Volatile private var followTries = 0
    private var followSentMs = Long.MIN_VALUE / 2
    private var followReadDue = false

    /**
     * Ultrawide puts the Aura in hover and leaves it there when it goes, through a re-plug too, and windows then move twice
     * (7 November 2026: the space mode read 1, then 0 as Ultrawide came on). A follow can be answered and still not hold, so
     * this reads the mode back, and until it says follow, 3D waits ([followNeeded]).
     */
    val followNeeded: Boolean get() = ultrawide == 0 && spaceMode == GlassesControl.SPACE_HOVER && followTries < FOLLOW_TRIES

    /**
     * A follow went out moments ago. Ultrawide off, follow and 3D 3 seconds apart left the Aura's picture and its own menu
     * blank once (7 November 2026), so 3D gives the glasses this long to finish.
     */
    fun followSettling(nowMs: Long): Boolean = nowMs - followSentMs < FOLLOW_SETTLE_MS

    /** A new plug-in: the tries count from nothing. */
    fun followReset() {
        followTries = 0
    }

    private fun sizeUnanswered() {
        screenSize = 1f
        screenSizeMax = 1f
    }

    /** Each beat with normal tracking wanted: follow sent while the glasses say hover, and read back. */
    fun keepFollow(nowMs: Long) {
        if (ultrawide != 0 || spaceMode != GlassesControl.SPACE_HOVER) return
        val since = nowMs - followSentMs
        if (followReadDue && since >= FOLLOW_READ_MS) {
            followReadDue = false
            rpc({ ControlWire.settingGet(it, GlassesControl.SPACE_MODE_GET) }, "space mode read back (10421)")
        }
        if (followTries >= FOLLOW_TRIES || since < FOLLOW_RETRY_MS) return
        followTries++
        followSentMs = nowMs
        followReadDue = true
        resetSpace(nowMs)
    }

    /** Recentre in Ultrawide: the canvas comes in front of the head. The glasses animate it as a slide. */
    fun recenter() {
        rpc(ControlWire::recenter, "recenter (10279), the Ultrawide canvas")
    }

    private fun perform(steps: List<HoverDutyTest.Step>) {
        for (step in steps) when (step) {
            is HoverDutyTest.Step.ReadDuty -> rpc(ControlWire::dutyGet, step.what)
            is HoverDutyTest.Step.SetSpace -> rpc({ ControlWire.spaceMode(it, step.mode) }, step.what)
        }
    }

    /** The EDID index the glasses answered, null before. */
    @Volatile var edid: Int? = null
        private set

    /** A 72 Hz try under way: when it started, else null. */
    @Volatile var tryingSinceMs: Long? = null
        private set

    /** The 72 Hz mode was seen after a try. */
    @Volatile var at72 = false
        private set

    private var counter = 0
    private val pending = HashMap<Int, String>()

    private fun rpc(build: (Int) -> ByteArray, what: String): Boolean {
        val id = ControlWire.rpcId(counter++)
        pending[id and 0x7FFFFFFF] = what
        val ok = io.send(build(id))
        io.log("display control: sent $what${if (ok) "" else " (write failed)"}")
        return ok
    }

    /** The link opened: read the duty and the EDID index, and with [menuSettings] (the Aura) the settings of its menu. */
    fun linkOpened(menuSettings: Boolean = false) {
        // What the last link read may be out of date: Ultrawide is told from 3D by this read.
        stereo = null
        ultrawide = null
        screenSize = null
        screenSizeMax = null
        spatialAnchor = null
        spaceMode = null
        // 0.5.65: read first, in the order our captures of the glasses' USB network traffic show: the serial, the 2D / 3D
        // state and the factory calibration.
        rpc(ControlWire::serialGet, "serial read (10025)")
        rpc(ControlWire::stereoGet, "2D / 3D state read (10273)")
        if (calibrationJson == null) rpc(ControlWire::calibrationGet, "factory calibration read (10015)")
        rpc(ControlWire::dutyGet, "duty read (10065)")
        rpc(ControlWire::edidGet, "EDID index read (10078)")
        rpc(ControlWire::depthGet, "3D screen distance read (10400)")
        rpc(ControlWire::ecCount, "dimming film level count read (10021)")
        rpc(ControlWire::ecGet, "dimming film level read (10022)")
        if (menuSettings) readSettings()
    }

    /** 0.5.37: the film's level count and level as the glasses answered (null before), and the level to restore. */
    @Volatile var ecCount: Int? = null
        private set
    @Volatile var ecLevel: Int? = null
        private set
    private var ecRestore: Int? = null

    /** 0.5.37: Immersive on: the room dimmed to the darkest level (the level before kept); off: that level back. */
    fun immersiveDim(on: Boolean) {
        if (on) {
            if (ecRestore == null) ecRestore = ecLevel ?: EC_DEFAULT
            val darkest = (ecCount ?: EC_LEVELS) - 1
            rpc({ ControlWire.ecSet(it, darkest) }, "dimming film level $darkest, the darkest (10023, Immersive on; ${ecRestore} before)")
        } else {
            val back = ecRestore ?: return
            ecRestore = null
            rpc({ ControlWire.ecSet(it, back) }, "dimming film level $back back (10023, Immersive off)")
        }
    }

    fun received(m: ControlMessage) {
        if (m !is ControlMessage.Rpc || !m.isReply) return
        val what = pending.remove(m.rpcId and 0x7FFFFFFF) ?: return
        val text = if (m.type == GlassesControl.CALIBRATION_GET) m.valueBytes?.let { ", ${it.size} bytes" } else m.valueText?.let { ", \"$it\"" }
        io.log("display control: $what answered, result ${m.result ?: "absent"}${m.value?.let { ", value $it" } ?: ""}${text ?: ""}")
        when (m.type) {
            GlassesControl.SERIAL_GET -> if (m.result == 0L) m.valueText?.takeIf { it.isNotBlank() }?.let { s ->
                val now = s.trim()
                serial = now
                io.serial(now)
                // 0.5.91: other glasses than the ones the calibration was read from: theirs is read now.
                if (calibrationJson != null && calibrationSerial != now) {
                    calibrationJson = null
                    rpc(ControlWire::calibrationGet, "factory calibration read (10015) for glasses $now (the one held was ${calibrationSerial ?: "unnamed"}'s)")
                }
            }
            // An empty answer is 0 (proto3 leaves zero out): 2D.
            GlassesControl.STEREO_GET -> if (m.result == 0L) stereo = (m.value ?: 0L).toInt()
            GlassesControl.STEREO_SET -> if (m.result != 0L) io.log("display control: the glasses did not take the 2D / 3D change (result ${m.result})")
            GlassesControl.CALIBRATION_GET -> if (m.result == 0L) m.valueText?.takeIf { it.startsWith("{") }?.let {
                calibrationJson = it
                calibrationSerial = serial
                io.calibration(serial, it)
            }
            GlassesControl.DUTY_GET -> if (m.result == 0L) m.value?.toInt()?.takeIf { it in 1..100 }?.let {
                // 1.1.0: during the hover duty test the reads are its own, never the turn duty's normal.
                if (hover.active) {
                    perform(hover.duty(it, lastTickMs))
                    io.log("display control: hover duty test: ${hover.line()}")
                } else if (!turnDuty.lowered) {
                    turnDuty.normal = it
                }
            }
            GlassesControl.SPACE_MODE -> {
                spaceSettling = false
                perform(hover.spaceAnswer(m.result))
                if (m.result != null && m.result != 0L) io.log("display control: the glasses did not take the space mode (result ${m.result}); hover duty test: ${hover.line()}")
            }
            GlassesControl.ULTRAWIDE_GET -> if (m.result == 0L) ultrawide = (m.value ?: 0L).toInt()
            // The Aura has answered NaN just after a mode change: such a value is not kept.
            GlassesControl.SCREEN_SIZE_GET -> if (m.result == 0L) m.valueFloat?.takeIf { it.isFinite() }?.let { screenSize = it } else sizeUnanswered()
            GlassesControl.SCREEN_SIZE_RANGE -> if (m.result == 0L) m.field3Float?.takeIf { it.isFinite() && it in SCREEN_SIZE_LOWEST..SCREEN_SIZE_HIGHEST }?.let { screenSizeMax = it } else sizeUnanswered()
            // Glasses that refuse these have nothing here to change, and the rest of Steady mode goes on without them.
            GlassesControl.SPATIAL_ANCHOR_GET -> spatialAnchor = if (m.result == 0L) (m.value ?: 0L).toInt() else 0
            GlassesControl.SPACE_MODE_GET -> if (m.result == 0L) {
                spaceMode = (m.value ?: 0L).toInt()
                if (spaceMode == GlassesControl.SPACE_FOLLOW) followTries = 0
            }
            GlassesControl.ULTRAWIDE_SET, GlassesControl.SPATIAL_ANCHOR_SET, GlassesControl.SCREEN_SIZE_SET ->
                if (m.result != 0L) io.log("display control: the glasses did not take $what (result ${m.result})")
            GlassesControl.EDID_GET -> if (m.result == 0L) edid = m.value?.toInt()
            GlassesControl.EC_COUNT -> if (m.result == 0L) m.value?.toInt()?.takeIf { it in 2..16 }?.let { ecCount = it }
            GlassesControl.EC_GET -> if (m.result == 0L) m.value?.toInt()?.takeIf { it in 0..15 }?.let { ecLevel = it }
            GlassesControl.DEPTH_GET -> if (m.result == 0L) {
                // 0.5.37 (vc57: 1.00 m then 10.00 m, the glasses' range ends): every field logged, and a value is used only
                // once the same one has come back twice in a row.
                io.log("display control: 3D screen distance reply fields: ${m.fieldsText ?: "none"}")
                val d = m.valueFloat ?: m.value?.toFloat()
                if (d != null && d.isFinite() && d in DEPTH_MIN_M..DEPTH_MAX_M) {
                    val same = lastDepthRead?.let { kotlin.math.abs(it - d) <= 0.01f } == true
                    lastDepthRead = d
                    if (same && (depthM == null || kotlin.math.abs(depthM!! - d) > 0.01f)) {
                        depthM = d
                        // 0.5.38 (vc58: still 1 m, 10 m and empty answers): logged only, never applied, until it is decoded.
                        io.log("display control: the glasses' 3D screen distance reads %.2f m (the same twice; logged, not applied)".format(java.util.Locale.ROOT, d))
                        if (APPLY_DEPTH) io.depth(d)
                    }
                } else {
                    io.log("display control: the 3D screen distance answer was not usable (${m.valueFloat ?: m.value}); the distance stays as it was")
                }
            }
            GlassesControl.EDID_SET -> if (m.result != 0L && tryingSinceMs != null) {
                tryingSinceMs = null
                io.log("display control: the glasses did not take EDID index ${GlassesControl.EDID_3D_72} (result ${m.result}); nothing changed, 60 Hz stays")
            }
        }
    }

    /** Each frame with the head's turn speed while the turn duty switch is on. */
    fun frame(turnSpeedDegS: Float, nowMs: Long) {
        // 1.1.0: the hover duty test reads the glasses' own duty, so nothing writes one while it runs.
        if (hover.active) return
        turnDuty.update(turnSpeedDegS, nowMs)?.let { d ->
            rpc({ ControlWire.dutySet(it, d) }, "duty $d % (10066, ${if (d == turnDuty.low) "a fast turn" else "the glasses' own, the head still"})")
            // 0.5.34: read back, so the log says what the glasses hold now (they may overwrite a host's duty).
            rpc(ControlWire::dutyGet, "duty read back (10065)")
        }
    }

    /** Asks for 3D at 72 Hz; false (logged) when the glasses' index is not known to be 60 Hz 3D. */
    fun try72(nowMs: Long): Boolean {
        // 0.5.35 (vc55: EDID index 6 made the Aura reboot): never again.
        io.log("display control: 72 Hz is off for good (it rebooted the glasses in vc55); nothing sent")
        if (!ALLOW_EDID_CHANGE) return false
        val e = edid
        if (e != GlassesControl.EDID_3D_60) {
            io.log("display control: 72 Hz not tried: the glasses' EDID index is ${e ?: "not read yet"}, not ${GlassesControl.EDID_3D_60} (3840x1080 at 60, their 3D)")
            return false
        }
        tryingSinceMs = nowMs
        at72 = false
        return rpc({ ControlWire.edidSet(it, GlassesControl.EDID_3D_72) }, "EDID index ${GlassesControl.EDID_3D_72}, 3840x1080 at 72 Hz (10079); back to ${GlassesControl.EDID_3D_60} by itself if no 72 Hz mode comes within ${TRY_MS / 1000} s")
    }

    /** The glasses display's mode as Android offers it now (after a re-plug). */
    fun displayMode(width: Int, height: Int, hz: Float, nowMs: Long) {
        if (tryingSinceMs == null) return
        if (width == 3840 && height == 1080 && hz in 71f..73f) {
            tryingSinceMs = null
            at72 = true
            edid = GlassesControl.EDID_3D_72
            io.log("display control: 3840x1080 at %.1f Hz offered after the EDID change: 3D at 72 Hz".format(java.util.Locale.ROOT, hz))
        }
    }

    /** 1.1.0: the last [tick]'s time, for replies that arrive between ticks. */
    @Volatile private var lastTickMs = 0L

    fun tick(nowMs: Long) {
        lastTickMs = nowMs
        if (spaceSettling && nowMs - settleSentMs >= SETTLE_MS) {
            spaceSettling = false
            io.log("display control: no answer to the follow in ${SETTLE_MS / 1000} s; carrying on")
        }
        perform(hover.tick(nowMs))
        // 0.5.36: the glasses' 3D distance read again every [DEPTH_EVERY_MS], so a change in their menu is followed.
        // 0.5.55: off ([REREAD_DEPTH]); nothing uses the answer (APPLY_DEPTH is false) and the
        // Menu's distance comes from its saved value, so it was 6 messages a minute to the glasses for nothing.
        if (REREAD_DEPTH && nowMs - depthAskedMs >= DEPTH_EVERY_MS) {
            depthAskedMs = nowMs
            rpc(ControlWire::depthGet, "3D screen distance read again (10400)")
        }
        val since = tryingSinceMs ?: return
        if (nowMs - since >= TRY_MS) {
            tryingSinceMs = null
            io.log("display control: no 72 Hz mode in ${TRY_MS / 1000} s; EDID index ${GlassesControl.EDID_3D_60} (60 Hz) back")
            rpc({ ControlWire.edidSet(it, GlassesControl.EDID_3D_60) }, "EDID index ${GlassesControl.EDID_3D_60} back (10079)")
        }
    }

    /** Back to 60 Hz on request (the Developer card), if 72 was set or tried. */
    fun back60(): Boolean {
        if (!ALLOW_EDID_CHANGE) return false
        if (!at72 && tryingSinceMs == null) return false
        tryingSinceMs = null
        at72 = false
        return rpc({ ControlWire.edidSet(it, GlassesControl.EDID_3D_60) }, "EDID index ${GlassesControl.EDID_3D_60} back on request (10079)")
    }

    /** Before the link closes (the warp taking the link, the Space ending, the switch off): the glasses' own duty back. */
    fun restoreAll() {
        turnDuty.restore()?.let { d -> rpc({ ControlWire.dutySet(it, d) }, "duty $d % back, the glasses' own (10066)") }
        // 1.1.0: the glasses back in follow if the hover duty test asked for hover.
        perform(hover.stop())
        // 0.5.37: the lenses' dimming back too, if Immersive left them dimmed (the Space ending in Immersive).
        immersiveDim(false)
    }

    companion object {
        const val TRY_MS = 10_000L

        /** During a Steady mode change: how often the settings are read, and the least time between two sets. */
        const val STILL_READ_MS = 1_000L
        const val STILL_RETRY_MS = 3_000L
        const val STILL_TRIES = 8
        const val ANCHOR_TRIES = 2

        /** Follow: how long before it is read back, how long before it is sent again, and how many times. */
        const val FOLLOW_READ_MS = 1_500L
        const val FOLLOW_RETRY_MS = 3_000L
        const val FOLLOW_TRIES = 3
        const val FOLLOW_SETTLE_MS = 3_000L

        /** Any screen size outside this is not believed, whatever range the glasses answer. */
        const val SCREEN_SIZE_LOWEST = 0.5f
        const val SCREEN_SIZE_HIGHEST = 1.5f

        /** How long a 3D request waits for the answer to [resetSpace]. */
        const val SETTLE_MS = 5_000L

        /** 0.5.65: at most one 2D / 3D change every 10 s. */
        const val STEREO_RETRY_MS = 10_000L

        /** 0.5.37: the Aura's dimming film, if the glasses did not answer: 5 levels, 2 by default. */
        const val EC_LEVELS = 5
        const val EC_DEFAULT = 2

        /** 0.5.36: how often the 3D screen distance is read again, and the glasses' own range of it. */
        const val DEPTH_EVERY_MS = 10_000L

        /** 0.5.55: the 10 s re-read is not sent; the one read when the link opens stays (logged only). */
        const val REREAD_DEPTH = false
        const val DEPTH_MIN_M = 0.5f
        const val DEPTH_MAX_M = 12f

        /** 0.5.35: EDID changes are never sent (vc55: index 6 made the Aura reboot). */
        const val ALLOW_EDID_CHANGE = false

        /** 0.5.38: the 10400 read is not trusted yet (vc57 and vc58 gave 1 m, 10 m and nothing), so it changes nothing. */
        const val APPLY_DEPTH = false
    }
}
