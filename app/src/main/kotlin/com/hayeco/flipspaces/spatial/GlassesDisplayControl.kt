package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.spatial.ControlFramer
import com.hayeco.flipspaces.core.spatial.GlassesDisplayCommands
import java.util.concurrent.LinkedBlockingQueue

/**
 * 0.5.32: the glasses' duty on turns and the 72 Hz 3D try ([GlassesDisplayCommands]) on a control link of their own,
 * open only while a Space is up, one of their Developer switches is on (or a 72 Hz change is under way), and the glasses
 * warp does not hold the link ([GlassesWarp.linkWanted]): the glasses never see two links. Writes go through a queue to
 * the link's thread, so the render thread never waits on the socket.
 */
object GlassesDisplayControl {
    private const val TAG = "FlipSpaces/Display"
    private const val TICK_MS = 50L

    private val lock = Any()
    private val outbox = LinkedBlockingQueue<ByteArray>()

    private val commands = GlassesDisplayCommands(object : GlassesDisplayCommands.Io {
        override fun send(bytes: ByteArray): Boolean = link != null && outbox.offer(bytes)
        override fun log(line: String) {
            Log.i(TAG, line)
        }

        // 0.5.36 (Mike: the 3D distance should follow the glasses' own setting; its Menu stepper is gone): saved where the
        // Space reads it, as the Menu's stepper did, and each eye is centred for it from the next frame.
        override fun depth(metres: Float) {
            val c = app ?: return
            SpaceStore(c).setGlassesDistance(metres, "read from the glasses")
            EdgeCheckTest.adjustVersion++
        }

        // 0.5.91: the glasses on now said their serial: their own calibration (or none yet) goes to the fusion.
        override fun serial(serial: String) {
            val c = app ?: return
            if (GlassesCalibrationStore.select(c, serial)) HeadTracker.factoryCalibrationArrived(c)
            if (GlassesCalibrationStore.last(c) == null) calibrationRead = false
        }

        // 0.5.65 (research Y2): the glasses' factory calibration, kept per serial and handed to the fusion.
        override fun calibration(serial: String?, json: String) {
            calibrationRead = true
            val c = app ?: return
            GlassesCalibrationStore.save(c, serial, json)
            HeadTracker.factoryCalibrationArrived(c)
        }
    })

    /** 0.5.65: the calibration was read on this run of the app; the link is not held open for it again. */
    @Volatile private var calibrationRead = false

    /** 0.5.65 (research Y1): a 2D / 3D change to make on the glasses: true 3D side by side, false 2D, null none. */
    @Volatile private var stereoWant: Boolean? = null

    /** The picture is 32:9, so the glasses are in 3D or Ultrawide; each change is read fresh, as Android shows the two alike. */
    @Volatile private var pictureWide = false
    @Volatile private var stereoReadWanted = false

    /** 3D was asked for on this plug-in; when the Space last ran. See [requestStereo]. */
    @Volatile private var stereoAsked = false
    @Volatile private var spaceSeenMs = Long.MIN_VALUE / 2
    private const val NEW_PLUG_MS = 10_000L

    /** When the glasses last dropped 3D soon after taking it, and how many times 3D was asked again for that on this plug-in. */
    @Volatile private var stereoFellBackMs = Long.MIN_VALUE / 2
    @Volatile private var stereoFallbacks = 0
    private const val FELL_BACK_MS = 3_000L
    private const val FALLBACK_ASKS = 2
    @Volatile private var stereoTries = 0

    /** 0.5.65: the state was read again for the current request. */
    @Volatile private var stereoFresh = false

    /** 0.5.65: the state the glasses would not take; not asked again until the other state is wanted. */
    @Volatile private var stereoRefused: Boolean? = null
    private const val STEREO_TRIES = 3

    /**
     * 0.5.65 (research Y1): the Space wants [sbs] and Android does not offer it: ask the glasses with the command XREAL's
     * runtime sends. The link opens for it; once the glasses answer with that state the request is done.
     *
     * 3D is asked for once a plug-in, then the glasses' mode is the user's: a mode change re-plugs the display, and asking
     * again on the way to Ultrawide switches it straight back off (6 November 2026). A Space gone for [NEW_PLUG_MS] is a new plug-in.
     */
    fun requestStereo(context: Context, sbs: Boolean) {
        app = context.applicationContext
        // A display came up: a wait left from glasses that refused before must not hold this plug-in back (30 s, 6 November 2026).
        openBackoffMs = 0L
        nextOpenMs = 0L
        if (stereoWant == sbs || stereoRefused == sbs) return
        if (sbs && (steadyWant == true || (link != null && commands.ultrawide == 1))) return
        val now = SystemClock.elapsedRealtime()
        // The Aura can take 3D just after plug-in and drop it by itself 1 to 5 s later (6 November 2026): that is asked again.
        val fellBack = sbs && now - stereoFellBackMs < FELL_BACK_MS && stereoFallbacks < FALLBACK_ASKS
        if (fellBack) {
            stereoFallbacks++
            Log.i(TAG, "the glasses dropped 3D by themselves: asking again ($stereoFallbacks of $FALLBACK_ASKS)")
        } else if (sbs && stereoAsked && now - spaceSeenMs < NEW_PLUG_MS) {
            Log.i(TAG, "3D was asked for on this plug-in: the glasses' mode is left as the user set it")
            return
        }
        if (sbs && !stereoAsked) {
            stereoFallbacks = 0
            commands.followReset()
        }
        if (sbs) stereoAsked = true
        stereoWant = sbs
        stereoAskedMs = SystemClock.elapsedRealtime()
        stereoLinkSeen = link != null
        stereoRefused = null
        stereoTries = 0
        stereoFresh = false
        Log.i(TAG, "the glasses are asked for ${if (sbs) "3D side by side" else "2D"} (the Space's view needs it)")
        ensureLoop()
    }

    /**
     * 1.2.3 (issue 58): the user turned on something that needs 3D (Side by side 3D), so the next [requestStereo] asks the
     * glasses again even though 3D was asked for on this plug-in already. One ask, from the user's own action.
     */
    fun userWantsStereo() {
        stereoAsked = false
        stereoRefused = null
        stereoWant = null
        Log.i(TAG, "3D is asked for again: the user turned on Side by side 3D")
    }

    @Volatile private var link: GlassesNetwork.Duplex? = null
    @Volatile private var opening = false
    @Volatile private var loop: Thread? = null
    @Volatile private var app: Context? = null

    @Volatile private var spaceUp = false
    @Volatile private var turnDutyOn = false
    @Volatile private var want72 = false
    @Volatile private var want60 = false

    /**
     * 1.1.0: the hover duty test's Developer switch ([com.hayeco.flipspaces.core.spatial.HoverDutyTest]). Kept in memory
     * only, so it is off at every start of the app and the glasses are never left hovering by a saved setting.
     */
    @Volatile var hoverTestWanted = false
        private set
    @Volatile private var hoverTestApplied = false

    /** 1.1.0: the glasses were asked to hover, so the Space draws head locked (their anchor holds the picture). */
    val hoverHeadLocked: Boolean get() = link != null && commands.hover.headLocked

    /** The glasses' own 2D / 3D state as last read (10273), to tell Ultrawide from 3D; null with no link. */
    val stereoState: Int? get() = if (link != null) commands.stereo else null

    /** 1.2.8: the control link is open, so Pocket XR can switch the glasses' 2D / 3D itself (XREAL's only). */
    val linkOpen: Boolean get() = link != null

    /** Whether the glasses said they are in Ultrawide (the Aura), null with no answer: 3D reads as 2D for a moment after a change. */
    val ultrawideRead: Int? get() = if (link != null) commands.ultrawide else null

    /** A Steady mode change under way: true on, false off, null none. See [com.hayeco.flipspaces.core.spatial.SteadyMode]. */
    @Volatile private var steadyWant: Boolean? = null

    /**
     * 1.4.0: this Steady mode is the 6DoF kind (Settings' Steady mode in 6DoF, on by default on the Aura): Spatial Anchor goes on with
     * it, and stays on. Taken when Steady mode is turned on, kept until it is turned off.
     */
    @Volatile private var steadySixDof = false

    /** 1.4.1: Pocket XR sets the 6DoF hold itself (the Aura); false on the One series, where the user does in the glasses' menu. */
    @Volatile private var steadyAppAnchor = true

    private fun sixDofWanted(): Boolean = app?.let {
        val store = SpaceStore(it)
        val eye = com.hayeco.flipspaces.core.spatial.XrealEye.seen(store.model, GlassesUsbList.read(it))
        store.steadySixDof && com.hayeco.flipspaces.core.spatial.SteadyMode.sixDofOn(store.model, eye)
    } == true

    @Volatile private var linkOpenedMs = 0L

    /** The glasses on this link are ones whose menu settings Pocket XR reads. */
    @Volatile private var auraLink = false
    private const val SETTINGS_FRESH_MS = 2_000L
    private const val SPACE_WAIT_MS = 3_000L

    /** Spatial Anchor was checked on this link, for normal tracking. */
    private var anchorChecked = false

    /** The trackpad's key and the launcher's button: the change asked for, else what the glasses say. */
    val steadyOn: Boolean get() = steadyWant ?: (link != null && commands.ultrawide == 1)

    fun steady(context: Context, on: Boolean) {
        app = context.applicationContext
        steadyWant = on
        // Coming in, the setting decides; going out, the kind it was decides, so a 6DoF Steady mode puts Spatial Anchor off.
        if (on) {
            steadySixDof = sixDofWanted()
            steadyAppAnchor = com.hayeco.flipspaces.core.spatial.SteadyMode.appAnchor(SpaceStore(context).model)
            if (steadySixDof && !steadyAppAnchor) Log.i(TAG, "Steady mode in 6DoF on ${SpaceStore(context).model.label} with the XREAL Eye: the user turns 6DoF on in the glasses' menu; Pocket XR leaves their anchor alone")
        }
        // The user's own change: a drop seen after this is the glasses' own.
        if (!on) steadyHeld = false
        lastUltrawide = null
        steadyUsed = true
        auraLink = true
        steadyStartedMs = SystemClock.elapsedRealtime()
        stereoWant = null
        // Coming out, 3D is asked for again as on a new plug-in.
        if (!on) stereoAsked = false
        synchronized(lock) { commands.steadyStart() }
        Log.i(TAG, "Steady mode ${if (on) "on" else "off"}${if (steadySixDof) " (6DoF: the glasses' Spatial Anchor ${if (on) "on" else "off"})" else ""}")
        ensureLoop()
    }

    private fun auraOn(): Boolean = app?.let { com.hayeco.flipspaces.core.spatial.SteadyMode.managedOn(SpaceStore(it).model) } == true

    /** 1.3.3: Steady mode is on and done, so Ultrawide going off without Pocket XR asking is the glasses' button. */
    @Volatile private var steadyHeld = false
    private var lastUltrawide: Int? = null
    private var lastDropMs: Long? = null

    /** Steady mode was used this run: other glasses then have their settings read too, so they come back from it properly. */
    @Volatile private var steadyUsed = false
    @Volatile private var steadyStartedMs = 0L
    private const val STEADY_GIVE_UP_MS = 25_000L

    /** Recentre in Ultrawide, render thread. */
    fun recenter() {
        if (link == null) return
        synchronized(lock) { commands.recenter() }
    }

    /** The Space's settings beat (every 0.5 s): whether a Space is up and the turn duty switch. */
    fun configure(context: Context, spaceUp: Boolean, turnDuty: Boolean, picture32by9: Boolean = false) {
        app = context.applicationContext
        if (this.turnDutyOn != turnDuty) Log.i(TAG, "lower persistence on turns: ${if (turnDuty) "on" else "off"}")
        this.spaceUp = spaceUp
        this.turnDutyOn = turnDuty
        if (spaceUp) {
            spaceSeenMs = SystemClock.elapsedRealtime()
            if (pictureWide != picture32by9) {
                pictureWide = picture32by9
                stereoReadWanted = true
                // A 32:9 picture is 3D or Ultrawide already: nothing more is asked of the glasses.
                if (picture32by9) {
                    // The link stays through the re-plug: 3D taken just now fell back to 2D when it closed (6 November 2026).
                    if (stereoWant == true) {
                        stereoHoldUntilMs = SystemClock.elapsedRealtime() + STEREO_HOLD_MS
                        stereoTookMs = SystemClock.elapsedRealtime()
                    }
                    stereoWant = null
                }
            }
        }
        if (wanted()) ensureLoop()
    }

    /** Each frame, render thread: the head's turn speed, for the turn duty. */
    fun frame(turnSpeedDegS: Float, nowMs: Long) {
        if (!turnDutyOn || link == null) return
        synchronized(lock) { commands.frame(turnSpeedDegS, nowMs) }
    }

    /** 0.5.37: Immersive on dims the lenses' film to its darkest; off puts the level back (sent at once on the open link). */
    fun immersiveDim(on: Boolean) {
        if (link == null) {
            Log.i(TAG, "Immersive ${if (on) "on" else "off"}: no display control link, so the lenses' dimming stays as it is")
            return
        }
        synchronized(lock) { commands.immersiveDim(on) }
    }

    /** The Developer card's "Try 3D at 72 Hz": done once the link is up and the glasses' EDID index is read. */
    fun request72(context: Context) {
        app = context.applicationContext
        want72 = true
        want60 = false
        Log.i(TAG, "3D at 72 Hz asked for on the Developer card")
        ensureLoop()
    }

    /** The Developer card's "Back to 60 Hz". */
    fun request60(context: Context) {
        app = context.applicationContext
        want72 = false
        want60 = true
        ensureLoop()
    }

    /** The glasses display's mode as Android offers it (a new display after the EDID change's re-plug). */
    fun displayMode(width: Int, height: Int, hz: Float) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        // 0.5.91: the glasses back in 2D soon after taking 3D, for the log (the One's case before the link was held).
        if (stereoTookMs != 0L && width < height * 3 && now - stereoTookMs < STEREO_HOLD_MS) {
            Log.i(TAG, "the glasses went back to 2D (${width}x$height at ${hz.toInt()} Hz) %.1f s after taking 3D side by side; the link was ${if (link != null) "open" else "closed"}".format(java.util.Locale.ROOT, (now - stereoTookMs) / 1000f))
            stereoTookMs = 0L
            stereoFellBackMs = now
        }
        commands.displayMode(width, height, hz, now)
    }

    /** 0.5.91: the control link for the Glasses report: open or not, and what the glasses last answered. */
    val reportLink: String get() = when {
        link != null -> "open"
        commands.serial != null -> "answered this run, closed now (the Space is not up)"
        openBackoffMs > 0 -> "not opened: the glasses did not answer (tried again every ${openBackoffMs / 1000} s)"
        else -> "not tried yet (it opens with the Space)"
    }
    /**
     * 1.1.9: the glasses' own panel duty as read when the link opened (10065, already read for Sharper turns), and whether
     * a duty of Pocket XR's own could be in it (the hover duty test or Sharper turns), for
     * [com.hayeco.flipspaces.core.spatial.GlassesSetup.anchorLikely]. Nothing new is sent for it.
     */
    val glassesDuty: Int? get() = commands.turnDuty.normal
    val dutyOurs: Boolean get() = hoverTestWanted || turnDutyOn || commands.turnDuty.lowered

    val reportSerial: String? get() = commands.serial
    val reportStereo: Int? get() = commands.stereo
    val reportEdid: Int? get() = commands.edid

    val statusLine: String get() = when {
        commands.at72 -> "3D at 72 Hz"
        commands.tryingSinceMs != null -> "trying 72 Hz"
        else -> "60 Hz (EDID index ${commands.edid ?: "not read"})"
    }

    // A 72 Hz try keeps the link through the display's re-plug (the Space ends and comes back), so its revert can go out.
    // 0.5.65: also while a 2D / 3D change waits, and once per run of the app for the factory calibration.
    // 0.5.91: and for [STEREO_HOLD_MS] after the glasses took a 2D / 3D change ([stereoHoldUntilMs]).
    private fun wanted(): Boolean = ((spaceUp && (turnDutyOn || want72 || want60 || !calibrationRead || hoverTestWanted || pictureWide)) || stereoWant != null || steadyWant != null || commands.tryingSinceMs != null ||
        SystemClock.elapsedRealtime() < stereoHoldUntilMs) && !GlassesWarp.linkWanted

    /**
     * 0.5.91 (Mike's XREAL One, 30 October: it took 10274 = 1, came back as 3840 x 1080 at 60 Hz, and fell back to 2D
     * 1.5 s later; switched with its own buttons it stayed in 3D): the display re-plugs on a 2D / 3D change, the Space
     * ends with it, and the link closed then ("no longer wanted"). The One seems to drop a mode its link asked for
     * when that link goes, so the link is held through the re-plug until the Space is back.
     */
    @Volatile private var stereoHoldUntilMs = 0L

    /** 0.5.91: when the current 2D / 3D request was made; with no link for [STEREO_GIVE_UP_MS] it is dropped. */
    @Volatile private var stereoAskedMs = 0L

    /** 0.5.98: the link answered since the current 2D / 3D request, so the glasses have one and it is waited for. */
    @Volatile private var stereoLinkSeen = false
    private const val STEREO_GIVE_UP_MS = 30_000L

    /**
     * 0.5.91 (glasses with no control link): a link that does not open is tried again
     * after [openBackoffMs] (1 s, doubling to 30 s), not every 50 ms with a network scan each time.
     */
    @Volatile private var nextOpenMs = 0L
    @Volatile private var openBackoffMs = 0L
    private const val STEREO_HOLD_MS = 20_000L

    /** 0.5.91: when the glasses last took a 3D change asked for here, to say if they leave it by themselves soon after. */
    @Volatile private var stereoTookMs = 0L

    private fun ensureLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({ run() }, "glasses-display").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val want = wanted()
            val l = link
            when {
                // 0.5.91: no link for 30 s: the 2D / 3D request goes (the Space's hint asks for the glasses' own button).
                // 0.5.98 (Mike, Aura: "it no longer boots into full sbs mode automatically"): only for glasses whose link
                // never answered since the request. The Aura's link goes during its own 3D re-plug and comes back after
                // more than 30 s; 0.5.91 gave up then and marked 3D refused, so it was never asked again that run.
                want && l == null && stereoWant != null && !stereoLinkSeen && now - stereoAskedMs > STEREO_GIVE_UP_MS -> {
                    Log.i(TAG, "no display control link for ${STEREO_GIVE_UP_MS / 1000} s: the ${if (stereoWant == true) "3D" else "2D"} request is dropped; the glasses' own button switches it")
                    stereoWant = null
                }
                want && l == null && !opening && now >= nextOpenMs -> {
                    opening = true
                    Thread({ open() }, "glasses-display-open").apply { isDaemon = true; start() }
                }
                !want && l != null -> {
                    // The glasses' own duty back before the link goes (the warp taking it, the Space ending, the switch off).
                    synchronized(lock) { commands.restoreAll() }
                    flush(l)
                    close(l, "no longer wanted")
                }
                l != null -> {
                    synchronized(lock) {
                        if (stereoReadWanted) {
                            stereoReadWanted = false
                            commands.readStereo()
                            // Not just after a new link's own read: the first seconds are when the glasses have most to do.
                            if (auraLink && now - linkOpenedMs > SETTINGS_FRESH_MS) commands.readSettings()
                        }
                        if (spaceUp && auraLink && !hoverTestWanted && steadyWant == null) commands.keepFollow(now)
                        // Spatial Anchor hurts head tracking, so it goes off once per link. Only the Aura reads it.
                        // 1.4.0: not while Steady mode holds in 6DoF: there the glasses' Spatial Anchor is what holds the
                        // canvas, and a re-plug (Ultrawide, the screen size) opens a new link. So it waits for the Ultrawide
                        // read too, and leaves Spatial Anchor on in Ultrawide with Steady mode in 6DoF set.
                        if (!anchorChecked && commands.spatialAnchor != null && commands.ultrawide != null) {
                            anchorChecked = true
                            val sixDofHold = (steadySixDof && steadyHeld) || (commands.ultrawide == 1 && sixDofWanted())
                            if (commands.spatialAnchor == 1 && steadyWant == null && auraOn() && !sixDofHold) commands.spatialAnchorOff()
                            else if (commands.spatialAnchor == 1 && sixDofHold) Log.i(TAG, "Spatial Anchor left on: Steady mode in 6DoF holds the canvas")
                        }
                        steadyWant?.let { on ->
                            val step = commands.steady(on, now, steadySixDof, steadyAppAnchor)
                            if (step == com.hayeco.flipspaces.core.spatial.SteadyMode.Step.DONE) {
                                steadyWant = null
                                steadyHeld = on
                                // 1.4.1: on the One series the user turns the glasses' 6DoF on and off; the glasses say when.
                                if (steadySixDof && !steadyAppAnchor) GlassesNotice.post(if (on) com.hayeco.flipspaces.core.spatial.SteadyMode.TURN_ON_6DOF else com.hayeco.flipspaces.core.spatial.SteadyMode.TURN_OFF_6DOF, 10_000L)
                                if (!on) steadySixDof = false
                                Log.i(TAG, "Steady mode ${if (on) "on" else "off"}: done (Ultrawide ${commands.ultrawide}, screen size ${commands.screenSize}, Spatial Anchor ${commands.spatialAnchor})")
                            } else if (commands.stillTries > GlassesDisplayCommands.STILL_TRIES || now - steadyStartedMs > STEADY_GIVE_UP_MS) {
                                steadyWant = null
                                steadyHeld = false
                                if (!on) steadySixDof = false
                                // Back to the usual view on glasses that would not go.
                                stereoAsked = false
                                if (on) app?.let { requestStereo(it, true) }
                                Log.i(TAG, "Steady mode ${if (on) "on" else "off"}: gave up at step $step")
                            }
                        }
                        // 1.3.3: Ultrawide went off without Pocket XR asking (the glasses' button pressed twice): back in once, and
                        // out properly if it happens again soon ([com.hayeco.flipspaces.core.spatial.SteadyMode.dropped]).
                        val uw = commands.ultrawide
                        if (steadyWant == null && steadyHeld && lastUltrawide == 1 && uw == 0) {
                            val what = com.hayeco.flipspaces.core.spatial.SteadyMode.dropped(now, lastDropMs)
                            lastDropMs = now
                            steadyStartedMs = SystemClock.elapsedRealtime()
                            commands.steadyStart()
                            if (what == com.hayeco.flipspaces.core.spatial.SteadyMode.Dropped.BACK_IN) {
                                steadyWant = true
                                Log.i(TAG, "Steady mode: the glasses left Ultrawide by themselves (their button?); putting it back")
                            } else {
                                steadyWant = false
                                steadyHeld = false
                                stereoWant = null
                                stereoAsked = false
                                Log.i(TAG, "Steady mode: the glasses left Ultrawide again within ${com.hayeco.flipspaces.core.spatial.SteadyMode.SECOND_DROP_MS / 1000} s; ending Steady mode properly")
                            }
                        }
                        if (uw != null) lastUltrawide = uw
                        // 1.1.0: the hover duty test follows its switch (off puts the glasses back in follow).
                        if (hoverTestApplied != hoverTestWanted) {
                            hoverTestApplied = hoverTestWanted
                            commands.hoverTest(hoverTestWanted, now)
                        }
                        commands.tick(now)
                        if (want72 && commands.edid != null) {
                            want72 = false
                            commands.try72(now)
                        }
                        if (want60) {
                            want60 = false
                            if (!commands.back60()) Log.i(TAG, "back to 60 Hz: nothing to do, 72 Hz was not set")
                        }
                        // 0.5.65: the 2D / 3D change, once the state is read; done when the glasses answer with it.
                        // Follow goes first: asked after 3D, it was answered but the glasses stayed in hover and windows moved twice.
                        val spaceUnknown = spaceUp && auraLink && (commands.followNeeded || commands.followSettling(now) || (commands.ultrawide == null && now - linkOpenedMs < SPACE_WAIT_MS))
                        stereoWant?.takeUnless { commands.spaceSettling || spaceUnknown }?.let { sbs ->
                            if (!stereoFresh) {
                                stereoFresh = true
                                commands.readStereo()
                            }
                            val now3d = commands.stereo
                            when {
                                now3d == null -> Unit
                                (now3d == 1) == sbs -> {
                                    stereoWant = null
                                    stereoHoldUntilMs = now + STEREO_HOLD_MS
                                    if (sbs) stereoTookMs = now
                                    Log.i(TAG, "the glasses are in ${if (sbs) "3D side by side" else "2D"} (state $now3d); the link is held ${STEREO_HOLD_MS / 1000} s through the display's re-plug")
                                }
                                stereoTries >= STEREO_TRIES -> {
                                    stereoWant = null
                                    stereoRefused = sbs
                                    Log.i(TAG, "the glasses stayed in state $now3d after $STEREO_TRIES asks; not asking again until the view changes")
                                }
                                else -> if (commands.setStereo(sbs, now)) stereoTries++
                            }
                        }
                    }
                    flush(l)
                }
                !want && l == null && !opening -> {
                    loop = null
                    return
                }
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun flush(l: GlassesNetwork.Duplex) {
        while (true) {
            val b = outbox.poll() ?: return
            try {
                l.write(b)
            } catch (e: Exception) {
                close(l, "write failed: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
    }

    private fun open() {
        val (opened, how, _) = GlassesWarp.openControl(app)
        opening = false
        if (opened == null) {
            openBackoffMs = (openBackoffMs * 2).coerceIn(1000L, 30_000L)
            nextOpenMs = SystemClock.elapsedRealtime() + openBackoffMs
            Log.i(TAG, "display control link not opened: $how; tried again in ${openBackoffMs / 1000} s")
            return
        }
        openBackoffMs = 0L
        if (stereoWant != null) stereoLinkSeen = true
        if (!wanted()) {
            runCatching { opened.close() }
            return
        }
        link = opened
        linkOpenedMs = SystemClock.elapsedRealtime()
        auraLink = auraOn() || steadyUsed
        Log.i(TAG, "display control link open ($how)")
        synchronized(lock) { commands.linkOpened(menuSettings = auraLink) }
        read(opened)
    }

    private fun read(l: GlassesNetwork.Duplex) {
        val framer = ControlFramer()
        val buf = ByteArray(4096)
        try {
            while (link === l) {
                val n = l.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val msgs = framer.append(buf, n)
                synchronized(lock) { msgs.forEach { commands.received(it) } }
            }
        } catch (e: Exception) {
            Log.i(TAG, "display control link read ended: ${e.javaClass.simpleName}: ${e.message}")
        }
        close(l, "read ended")
    }

    private fun close(l: GlassesNetwork.Duplex, why: String) {
        if (link === l) {
            link = null
            outbox.clear()
            Log.i(TAG, "display control link closed ($why)")
            // 1.1.0: a hover duty test ends with its link (the glasses drop the link's modes; a replug resets them), and
            // starts again on the next link while its switch is on.
            synchronized(lock) { commands.hover.stop() }
            hoverTestApplied = false
            anchorChecked = false
        }
        runCatching { l.close() }
    }
}
