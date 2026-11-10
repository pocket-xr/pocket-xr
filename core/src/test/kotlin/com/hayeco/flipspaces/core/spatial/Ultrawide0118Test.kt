package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ultrawide told from 3D, its canvas, and the Menu's Steady mode. */
class Ultrawide0118Test {
    @Test fun ultrawideIsA32By9PictureThatSaysItIs2D() {
        assertTrue(StereoLayout.isUltrawide(3840, 1080, 0))
        assertFalse("3D", StereoLayout.isUltrawide(3840, 1080, 1))
        assertFalse("not read yet", StereoLayout.isUltrawide(3840, 1080, null))
        assertFalse("plain 2D", StereoLayout.isUltrawide(1920, 1080, 0))
    }

    @Test fun theCanvasKeepsThePanelsAnglePerPixel() {
        val optics = GlassesOptics.screen(GlassesModel.AURA, 1f)
        val canvas = optics.ultrawideFrustum(3840, 1080)
        val panel = optics.pictureFrustum(1920, 1080)
        assertEquals("as tall as the view", panel.verticalFovDeg, canvas.verticalFovDeg, 0.1f)
        assertTrue("much wider than the view", canvas.horizontalFovDeg > panel.horizontalFovDeg * 1.6f)
    }

    private class Sink : GlassesDisplayCommands.Io {
        val sent = ArrayList<ByteArray>()
        override fun send(bytes: ByteArray): Boolean = sent.add(bytes)
        override fun log(line: String) {}
    }

    private fun type(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    private fun answer(c: GlassesDisplayCommands, io: Sink, type: Int, body: ByteArray) {
        val request = io.sent.last { type(it) == type }
        val id = request.copyOfRange(6, 10).also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val pb = ProtoWriter().message(4, body).bytes()
        c.received(ControlFramer().append(ControlWire.header(type, 4 + pb.size) + id + pb).single())
    }

    @Test fun aNewLinkOnlyReadsTheMenuSettings() {
        val io = Sink()
        GlassesDisplayCommands(io).linkOpened(menuSettings = true)
        val types = io.sent.map(::type)
        assertTrue(types.containsAll(listOf(10277, 10396, 10397, 10404, 10421)))
        assertFalse("no set is sent", types.any { it in listOf(10278, 10398, 10405) })
    }

    @Test fun otherGlassesAreNotAskedForTheAurasSettings() {
        val io = Sink()
        GlassesDisplayCommands(io).linkOpened()
        assertFalse(io.sent.map(::type).any { it in listOf(10277, 10396, 10397, 10404, 10421) })
    }

    @Test fun theSettingsAnswersAreKeptAndGoWithTheLink() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ProtoWriter().varint(2, 1).bytes())
        answer(c, io, GlassesControl.SCREEN_SIZE_GET, ProtoWriter().float(2, 1.2f).bytes())
        answer(c, io, GlassesControl.SCREEN_SIZE_RANGE, ProtoWriter().float(2, 0.85f).float(3, 1.2f).bytes())
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ByteArray(0))
        assertEquals(1, c.ultrawide)
        assertEquals(1.2f, c.screenSize!!, 0.001f)
        assertEquals(1.2f, c.screenSizeMax!!, 0.001f)
        assertEquals("proto3 leaves a zero out", 0, c.spatialAnchor)
        c.linkOpened(menuSettings = true)
        assertEquals(null, c.ultrawide)
        assertEquals(null, c.screenSizeMax)
    }

    @Test fun aNaNScreenSizeIsNotKept() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.SCREEN_SIZE_GET, ProtoWriter().float(2, Float.NaN).bytes())
        assertEquals(null, c.screenSize)
    }

    @Test fun steadyOnGoesStepByStep() {
        fun g(stereo: Int = 0, wide: Int = 0, anchor: Int = 0, size: Float? = 0.8f, max: Float? = 1.3f) = SteadyMode.Glasses(stereo, wide, anchor, size, max)
        assertEquals(SteadyMode.Step.ANCHOR_OFF, SteadyMode.next(true, g(stereo = 1, anchor = 1)))
        assertEquals(SteadyMode.Step.LEAVE_3D, SteadyMode.next(true, g(stereo = 1)))
        assertEquals(SteadyMode.Step.ULTRAWIDE_ON, SteadyMode.next(true, g()))
        assertEquals(SteadyMode.Step.SCREEN_MAX, SteadyMode.next(true, g(wide = 1, size = 0.9f, max = 1.2f)))
        assertEquals(SteadyMode.Step.DONE, SteadyMode.next(true, g(wide = 1, size = 1.2f, max = 1.2f)))
        assertEquals("nothing read yet", SteadyMode.Step.WAIT, SteadyMode.next(true, SteadyMode.Glasses()))
        assertEquals("the range not read", SteadyMode.Step.WAIT, SteadyMode.next(true, g(wide = 1, max = null)))
    }

    @Test fun steadyOffOnlyTurnsUltrawideOff() {
        assertEquals(SteadyMode.Step.ULTRAWIDE_OFF, SteadyMode.next(false, SteadyMode.Glasses(ultrawide = 1)))
        assertEquals(SteadyMode.Step.DONE, SteadyMode.next(false, SteadyMode.Glasses(ultrawide = 0)))
        assertEquals(SteadyMode.Step.WAIT, SteadyMode.next(false, SteadyMode.Glasses()))
    }

    @Test fun theAuraAndTheOneSeriesAreOfferedIt() {
        for (m in listOf(GlassesModel.AURA, GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_S)) assertTrue("$m", SteadyMode.offeredOn(m))
        assertFalse(SteadyMode.offeredOn(GlassesModel.AIR))
        assertFalse(SteadyMode.offeredOn(null))
    }

    @Test fun onlyTheAuraIsManagedWithoutAsking() {
        assertTrue(SteadyMode.managedOn(GlassesModel.AURA))
        assertFalse(SteadyMode.managedOn(GlassesModel.ONE_S))
    }

    @Test fun oneSetGoesOutThenWaitsToBeReadBack() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.STEREO_GET, ByteArray(0))
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ByteArray(0))
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ByteArray(0))
        c.steadyStart()
        assertEquals(SteadyMode.Step.ULTRAWIDE_ON, c.steady(true, 10_000L))
        c.steady(true, 10_500L)
        assertEquals("one set in 3 s", 1, io.sent.count { type(it) == 10278 })
        c.steady(true, 13_100L)
        assertEquals(2, io.sent.count { type(it) == 10278 })
    }

    @Test fun theCoverMakesRoomForSteadyBesideKeyboard() {
        val l = com.hayeco.flipspaces.core.pointer.CoverLayoutMath.flip7(steady = true)
        val still = l.buttons.getValue(com.hayeco.flipspaces.core.pointer.CoverButton.STEADY)
        val keys = l.buttons.getValue(com.hayeco.flipspaces.core.pointer.CoverButton.KEYBOARD)
        assertTrue(still.right <= keys.left)
        assertEquals(still.width, keys.width, 0.01f)
        assertEquals(emptyList<String>(), com.hayeco.flipspaces.core.pointer.CoverLayoutMath.problems(l))
    }

    @Test fun theLauncherOnlyShowsSteadyWhenAsked() {
        assertFalse(XrLauncher().buttons.contains(LauncherButton.STEADY))
        assertEquals(LauncherButton.RECENTRE, LauncherButton.values()[LauncherButton.STEADY.ordinal - 1])
    }

    @Test fun theCheckSaysToLeaveSteadyFirst() {
        val r = GlassesCheck.result(SetupFamily.AURA, SetupHost.PHONE, null, GlassesCheck.Answers(null), steady = true)!!
        assertEquals(GlassesCheck.Fix.STEADY_ON, r.fix)
    }

    @Test fun theAuraIsToldToReconnectWhenWindowsMoveWrong() {
        val turn = GlassesCheck.Turn(20f, 20f)
        for (m in listOf(GlassesCheck.Moved.TOO_FAR, GlassesCheck.Moved.SLID_BACK)) {
            assertEquals(GlassesCheck.Fix.AURA_RECONNECT, GlassesCheck.fixFor(SetupFamily.AURA, SetupHost.PHONE, turn, m, FollowMode.BODY_ANCHOR))
        }
    }

    @Test fun immersiveKeepsItsButtonsInsideSteadyModesCanvas() {
        val window = SpacePanel(id = 1, label = "Video", packageName = "com.example.video", activityName = "Main", yawDeg = 0f, pitchDeg = 0f, distanceM = 2f, widthDeg = 40f, heightDeg = 22.5f)
        val viewH = 26f
        val c = ImmersiveMode.cinema(window, 10f, 8f, 79f, viewH, fixedView = true)
        val room = 2f * c.distanceM * kotlin.math.tan(ImmersiveMode.FIT * viewH * DEG / 2f)
        val all = c.heightM * (1f + WindowControls.aboveShare(c) + WindowControls.belowShare(c))
        assertTrue("$all m in $room m", all <= room * 1.01f)
        assertEquals("centred on the canvas", 8f, c.pitchDeg, 0.01f)
    }

    @Test fun immersiveIsUnchangedOutsideSteadyMode() {
        val window = SpacePanel(id = 1, label = "Video", packageName = "com.example.video", activityName = "Main", yawDeg = 0f, pitchDeg = 0f, distanceM = 2f, widthDeg = 40f, heightDeg = 22.5f)
        val plain = ImmersiveMode.cinema(window, 0f, 0f, 52f, 30f)
        val fixed = ImmersiveMode.cinema(window, 0f, 0f, 52f, 30f, fixedView = true)
        assertTrue(fixed.heightDeg < plain.heightDeg)
    }

    @Test fun theLauncherFitsSteadyModesCanvas() {
        val l = XrLauncher().apply { fitViewHeightDeg = 26f }
        l.open(10f, 5f, 0L)
        assertTrue(LauncherGrid.angularDeg(l.placement.distanceM).second <= 26f)
        assertEquals("centred on the canvas", 5f, l.placement.pitchDeg, 0.01f)
    }

    @Test fun theLauncherIsUnchangedOutsideSteadyMode() {
        val l = XrLauncher()
        l.open(0f, 0f, 0L)
        assertEquals(XrLauncher.DISTANCE_M, l.placement.distanceM, 0.001f)
        assertEquals(-XrLauncher.BELOW_GAZE_DEG, l.placement.pitchDeg, 0.01f)
    }

    private fun app(name: String) = com.hayeco.flipspaces.core.AppEntry(name, name, "Main")

    @Test fun fourAppsArePinnedByDefaultFavouringWellKnownOnes() {
        val apps = listOf("a.one", "b.two", "com.google.android.youtube", "c.three", "com.android.chrome", "d.four").map(::app)
        assertEquals(
            listOf("com.android.chrome/Main", "com.google.android.youtube/Main", "a.one/Main", "b.two/Main"),
            LauncherDefaults.pick(apps),
        )
    }

    @Test fun fewerAppsPinWhatThereIs() {
        assertEquals(listOf("a.one/Main"), LauncherDefaults.pick(listOf(app("a.one"))))
        assertEquals(emptyList<String>(), LauncherDefaults.pick(emptyList()))
    }

    private fun windows(n: Int) = SpaceLayout((1..n).map { SpacePanel(id = it, label = "App $it", packageName = "app.$it", activityName = "Main", yawDeg = it * 50f, pitchDeg = 20f, widthDeg = 50f, heightDeg = 30f) })

    @Test fun theAurasShortCanvasKeepsEveryWindowInOneRow() {
        for (n in 1..PanelLimits.MAX_PANELS) assertEquals(listOf(n), SteadyArrange.rows(n, 79f, 26f))
    }

    @Test fun aTallerCanvasStacksWindows() {
        assertEquals(listOf(2, 2), SteadyArrange.rows(4, 79f, 60f))
        assertEquals(listOf(3, 2), SteadyArrange.rows(5, 79f, 60f))
        assertEquals(listOf(3, 3), SteadyArrange.rows(6, 79f, 60f))
    }

    @Test fun noWindowIsVeryWideAndThin() {
        for (n in 4..PanelLimits.MAX_PANELS) for (p in SteadyArrange.arrange(windows(n), 0f, 0f, 79f, 26f).panels) {
            assertTrue("$n windows: ${p.widthDeg} by ${p.heightDeg}", p.widthDeg / p.heightDeg < 2f)
        }
    }

    @Test fun aFirstUltrawideDropGoesBackIn() {
        assertEquals(SteadyMode.Dropped.BACK_IN, SteadyMode.dropped(100_000L, null))
        assertEquals(SteadyMode.Dropped.BACK_IN, SteadyMode.dropped(100_000L, 100_000L - SteadyMode.SECOND_DROP_MS - 1L))
    }

    @Test fun aSecondDropSoonAfterLeavesSteadyMode() =
        assertEquals(SteadyMode.Dropped.LEAVE, SteadyMode.dropped(100_000L, 90_000L))

    @Test fun noSteadyWindowIsWiderThanAScreen() {
        for (n in 1..PanelLimits.MAX_PANELS) for (p in SteadyArrange.arrange(windows(n), 0f, 0f, 79f, 26f).panels) {
            assertTrue("$n windows: shape ${PanelDisplaySize.aspect(p)}", PanelDisplaySize.aspect(p) <= SteadyArrange.WIDEST_SHAPE + 0.01f)
        }
    }

    @Test fun aLoneSteadyWindowStaysCentred() {
        val p = SteadyArrange.arrange(windows(1), 30f, 4f, 79f, 26f).panels.single()
        assertEquals(30f, p.yawDeg, 0.01f)
        assertTrue("narrower than the canvas: ${p.widthDeg}", p.widthDeg < 79f * 0.7f)
    }

    @Test fun everyWindowAndItsButtonsFitTheCanvas() {
        val viewW = 79f
        val viewH = 26f
        for (n in 1..PanelLimits.MAX_PANELS) {
            val l = SteadyArrange.arrange(windows(n), 30f, 4f, viewW, viewH)
            assertEquals(n, l.panels.size)
            for (p in l.panels) {
                val above = p.heightDeg * WindowControls.aboveShare(p)
                val below = p.heightDeg * WindowControls.belowShare(p)
                assertTrue("$n: top", p.pitchDeg + p.heightDeg / 2f + above <= 4f + viewH / 2f)
                assertTrue("$n: bottom", p.pitchDeg - p.heightDeg / 2f - below >= 4f - viewH / 2f)
                assertTrue("$n: sides", kotlin.math.abs(wrapDegrees(p.yawDeg - 30f)) + p.widthDeg / 2f <= viewW / 2f)
            }
            for (a in l.panels) for (b in l.panels) if (a.id < b.id) {
                val apartX = kotlin.math.abs(wrapDegrees(a.yawDeg - b.yawDeg)) >= (a.widthDeg + b.widthDeg) / 2f
                val apartY = kotlin.math.abs(a.pitchDeg - b.pitchDeg) >= (a.heightDeg + b.heightDeg) / 2f
                assertTrue("$n: ${a.id} and ${b.id} overlap", apartX || apartY)
            }
        }
    }

    @Test fun leavingSteadyPutsEachWindowBack() {
        val before = windows(3)
        val steady = SteadyArrange.arrange(before, 0f, 0f, 79f, 26f)
        val opened = steady.copy(panels = steady.panels.drop(1) + steady.panels[0].copy(id = 9))
        val back = SteadyArrange.restored(opened, before)
        assertEquals(before.panel(2), back.panel(2))
        assertEquals(before.panel(3), back.panel(3))
        assertEquals("opened in Steady mode: left where it is", opened.panel(9), back.panel(9))
        assertEquals("closed in Steady mode: stays closed", null, back.panel(1))
    }

    private fun inHover(c: GlassesDisplayCommands, io: Sink, ultrawide: Int) {
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.SPACE_MODE_GET, ByteArray(0))
        answer(c, io, GlassesControl.ULTRAWIDE_GET, if (ultrawide == 1) ProtoWriter().varint(2, 1).bytes() else ByteArray(0))
    }

    @Test fun hoverLeftByUltrawideIsPutBackToFollowBefore3D() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        inHover(c, io, ultrawide = 0)
        assertTrue("3D waits", c.followNeeded)
        c.keepFollow(10_000L)
        assertEquals(1, io.sent.count { type(it) == 10284 })
        c.keepFollow(11_600L)
        answer(c, io, GlassesControl.SPACE_MODE_GET, ProtoWriter().varint(2, 1).bytes())
        assertFalse("read back as follow", c.followNeeded)
        c.keepFollow(20_000L)
        assertEquals("nothing more", 1, io.sent.count { type(it) == 10284 })
    }

    @Test fun aFollowThatDoesNotHoldIsTriedAFewTimesThenLeft() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        inHover(c, io, ultrawide = 0)
        for (i in 0..9) c.keepFollow(10_000L + i * 3_100L)
        assertEquals(GlassesDisplayCommands.FOLLOW_TRIES, io.sent.count { type(it) == 10284 })
        assertFalse("3D is not held up for ever", c.followNeeded)
    }

    @Test fun ultrawideIsNeverSentASpaceMode() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        inHover(c, io, ultrawide = 1)
        assertFalse(c.followNeeded)
        c.keepFollow(10_000L)
        assertEquals(0, io.sent.count { type(it) == 10284 })
    }

    @Test fun aSpatialAnchorThatWillNotGoOffDoesNotStopSteadyMode() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.STEREO_GET, ByteArray(0))
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ByteArray(0))
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ProtoWriter().varint(2, 1).bytes())
        c.steadyStart()
        assertEquals(SteadyMode.Step.ANCHOR_OFF, c.steady(true, 10_000L))
        assertEquals(SteadyMode.Step.ANCHOR_OFF, c.steady(true, 13_100L))
        assertEquals(SteadyMode.Step.ULTRAWIDE_ON, c.steady(true, 16_200L))
    }

    @Test fun glassesThatRefuseTheSizeStillFinish() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        val request = io.sent.last { type(it) == GlassesControl.SCREEN_SIZE_RANGE }
        val id = request.copyOfRange(6, 10).also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val pb = ProtoWriter().message(4, ProtoWriter().varint(1, 5004).bytes()).bytes()
        c.received(ControlFramer().append(ControlWire.header(GlassesControl.SCREEN_SIZE_RANGE, 4 + pb.size) + id + pb).single())
        answer(c, io, GlassesControl.STEREO_GET, ByteArray(0))
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ProtoWriter().varint(2, 1).bytes())
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ByteArray(0))
        c.steadyStart()
        assertEquals(SteadyMode.Step.DONE, c.steady(true, 10_000L))
    }

    @Test fun threeDWaitsAMomentAfterAFollow() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        assertFalse("nothing sent yet", c.followSettling(10_000L))
        inHover(c, io, ultrawide = 0)
        c.keepFollow(10_000L)
        assertTrue(c.followSettling(12_900L))
        assertFalse(c.followSettling(13_100L))
    }
}
