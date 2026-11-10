package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hayeco.flipspaces.core.spatial.VitureReconnect.Step

/**
 * 1.2.10, Discussion #24, second report (a VITURE Beast on 1.2.8): tracking did not come back after the switch, an axis
 * change or a replug, the USB prompt showed only once, the view started upside down, and launcher icons came in mixed
 * shapes.
 */
class Discussion24SecondReportTest {
    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    // ---- A: the reconnect state machine ----

    private val beast = VitureReconnect.Device("/dev/bus/usb/002/003", permitted = true)

    @Test fun enableAsksOnceThenOpensThenKeepsReading() {
        val r = VitureReconnect()
        assertEquals(Step.IDLE, r.next(beast.copy(permitted = false)))
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
        // The prompt is up: not asked again while it waits.
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(beast.copy(permitted = false)))
        assertEquals(Step.OPEN, r.next(beast))
        r.opened(beast.name)
        assertEquals(Step.READ, r.next(beast))
    }

    @Test fun disableClosesAndEnableAgainAsksAgain() {
        val r = VitureReconnect()
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(beast.copy(permitted = false)))
        r.setEnabled(false)
        assertEquals(Step.IDLE, r.next(beast.copy(permitted = false)))
        // 1.2.8 asked once per device name for the life of the process; the switch turned on asks again.
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))

        assertEquals(Step.OPEN, r.next(beast))
        r.opened(beast.name)
        r.setEnabled(false)
        assertEquals(Step.CLOSE, r.next(beast))
        r.closed()
        assertEquals(Step.IDLE, r.next(beast))
        r.setEnabled(true)
        assertEquals(Step.OPEN, r.next(beast))
    }

    @Test fun anAxisChangeAppliesLiveAndNeverReopens() {
        val r = VitureReconnect()
        // Nothing open: kept for the open.
        assertFalse(r.axesChanged(VitureAxes(flipUpDown = true)))
        r.setEnabled(true)
        assertEquals(Step.OPEN, r.next(beast))
        r.opened(beast.name)
        assertTrue(r.axesChanged(VitureAxes(swapTurnTilt = true)))
        assertEquals(VitureAxes(swapTurnTilt = true), r.axes)
        assertEquals(Step.READ, r.next(beast))
        assertTrue(r.axesChanged(VitureAxes()))
        assertEquals(Step.READ, r.next(beast))
    }

    @Test fun detachClosesAndTheNextPlugInAsksAgain() {
        val r = VitureReconnect()
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
        assertEquals(Step.OPEN, r.next(beast))
        r.opened(beast.name)
        r.detached()
        assertEquals(Step.CLOSE, r.next(null))
        r.closed()
        assertEquals(Step.IDLE, r.next(null))
        // Plugged in again, under the same name (Android reused it) and without access: asked again.
        r.attached()
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
        assertEquals(Step.OPEN, r.next(beast))
    }

    @Test fun attachUnderANewNameClosesTheOldLinkAndAsks() {
        val r = VitureReconnect()
        r.setEnabled(true)
        r.opened(beast.name)
        val again = VitureReconnect.Device("/dev/bus/usb/002/007", permitted = false)
        assertEquals(Step.CLOSE, r.next(again))
        r.closed()
        assertEquals(Step.ASK, r.next(again))
        assertEquals(Step.WAIT_FOR_ANSWER, r.next(again))
    }

    @Test fun lostPermissionClosesThenAsksAgain() {
        val r = VitureReconnect()
        r.setEnabled(true)
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
        assertEquals(Step.OPEN, r.next(beast))
        r.opened(beast.name)
        assertEquals(Step.CLOSE, r.next(beast.copy(permitted = false)))
        r.closed()
        // Asked for this very name before, but access was had and lost since: asked again, not left waiting.
        assertEquals(Step.ASK, r.next(beast.copy(permitted = false)))
    }

    // ---- A: the Beast, not its microphone ----

    private data class Dev(val pid: Int, val name: String?)

    @Test fun theBeastIsPickedOverItsMicrophone() {
        val mic = Dev(0x1102, "VITURE Microphone")
        val glasses = Dev(0x1211, "VITURE Beast XR Glasses")
        // After a replug Android listed the microphone first; 1.2.8 opened its interface 2 and waited for nothing.
        assertEquals(glasses, VitureDevices.pick(listOf(mic, glasses), { it.pid }, { it.name }))
        assertEquals(glasses, VitureDevices.pick(listOf(glasses, mic), { it.pid }, { it.name }))
        assertNull(VitureDevices.pick(listOf(mic), { it.pid }, { it.name }))
        assertFalse(VitureDevices.carriesImu(0x1999, "Some VITURE Microphone"))
        assertTrue(VitureDevices.carriesImu(0x1301, "VITURE Pro XR Glasses"))
        assertTrue(VitureDevices.carriesImu(0x1301, null))
    }

    @Test fun aMotionRecordingReadsTheBeastNotItsMicrophone() {
        val hid = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_HID, 0, null, id = 5, protocol = 0, endpoints = listOf("IN interrupt 64 B", "OUT interrupt 64 B"))
        val mic = GlassesReport.Usb(0x35CA, 0x1102, "VITURE Microphone", emptyList(), listOf(hid))
        val glasses = GlassesReport.Usb(0x35CA, 0x1211, "VITURE Beast XR Glasses", emptyList(), listOf(hid))
        assertEquals(glasses, GlassesVendors.recordable(listOf(mic, glasses)))
        // Only the microphone: still recordable, so a recording says what it holds.
        assertEquals(mic, GlassesVendors.recordable(listOf(mic)))
    }

    // ---- A: a command after the answer the glasses still hold ----

    /**
     * The Beast as its logs showed it: a command is refused while an input report is unread, and each accepted command
     * leaves one answer (`10 00 01 23 01 00 00 00 00`, message 0x2301) behind until it is read.
     */
    private class Beast(var held: Int = 0, val streaming: Boolean = false, val outWorks: Boolean = true, val controlWorks: Boolean = false) : VitureHidCommands.Pipe {
        val answer = byteArrayOf(0x10, 0x00, 0x01, 0x23, 0x01, 0x00, 0x00, 0x00, 0x00)
        var commands = 0
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (held > 0) {
                held--
                answer.copyInto(buf)
                return 64
            }
            if (streaming) {
                VitureImuEncoder.raw(1, Vec3.ZERO, Vec3(0f, -1f, 0f)).copyInto(buf)
                return 64
            }
            return -1
        }
        override fun write(packet: ByteArray, timeoutMs: Int): Boolean {
            if (!outWorks || held > 0) return false
            commands++
            held++
            return true
        }
        override fun setReport(packet: ByteArray, timeoutMs: Int): Boolean {
            if (!controlWorks) return false
            commands++
            held++
            return true
        }
    }

    @Test fun theStartIsAcceptedAfterTheStopsAnswerIsRead() {
        // The first session's stop left its answer unread; 1.2.8 wrote the start straight away and it was refused.
        val glasses = Beast(held = 1)
        assertFalse(glasses.write(VitureImuProtocol.startRaw().copyOf(64), 250))
        val r = VitureHidCommands.send(glasses, VitureImuProtocol.startRaw(), 250)
        assertTrue(r.line(), r.accepted)
        assertEquals(1, r.tries)
        assertEquals(1, r.drained)
        assertTrue(VitureHidCommands.streamWillCome(r))
        // The start's own answer was read too, so the stop at close is taken, and the next start after it.
        assertEquals(0, glasses.held)
        assertTrue(VitureHidCommands.send(glasses, VitureImuProtocol.stop(), 250).accepted)
        assertTrue(VitureHidCommands.send(glasses, VitureImuProtocol.startRaw(), 250).accepted)
        assertEquals(3, glasses.commands)
    }

    @Test fun aRunningStreamIsTakenEvenIfTheStartIsNot() {
        val glasses = Beast(streaming = true, outWorks = false)
        val r = VitureHidCommands.send(glasses, VitureImuProtocol.startRaw(), 250)
        assertFalse(r.accepted)
        assertTrue(r.rawSeen > 0)
        assertTrue(VitureHidCommands.streamWillCome(r))
        assertTrue(r.line(), r.line().contains("already running"))
    }

    @Test fun theControlPipeIsTriedWhenTheInterruptOutRefuses() {
        val glasses = Beast(outWorks = false, controlWorks = true)
        val r = VitureHidCommands.send(glasses, VitureImuProtocol.startRaw(), 250)
        assertTrue(r.accepted)
        assertTrue(r.viaControl)
        assertEquals(VitureHidCommands.TRIES + 1, r.tries)
        val none = VitureHidCommands.send(Beast(outWorks = false), VitureImuProtocol.startRaw(), 250)
        assertFalse(VitureHidCommands.streamWillCome(none))
    }

    @Test fun theAxisSwitchesApplyToTheNextSample() {
        val framer = VitureImuFramer(VitureAxes())
        val out = ArrayList<ImuReport>()
        framer.append(VitureImuEncoder.raw(1, Vec3(0f, 0f, 0.5f), Vec3(0f, -1f, 0f)), 64, 1_000_000L, out)
        assertEquals(-9.81f, out[0].accel.y, 0.01f)
        framer.axes = VitureAxes(flipUpDown = true)
        framer.append(VitureImuEncoder.raw(2, Vec3(0f, 0f, 0.5f), Vec3(0f, -1f, 0f)), 64, 9_000_000L, out)
        assertEquals(-0.5f, out[1].gyro.z, 1e-6f)
    }

    // ---- B: the Beast's default layout ----

    /** The tracking line's accelerometer at rest, upright, as the Beast's sensor sends it (m/s2). */
    private val beastAtRest = Vec3(0.04f, -9.94f, -0.74f)

    @Test fun theBeastStartsInItsOwnLayoutWithTheSwitchesOff() {
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, VitureDevices.defaultAxes(VitureDevices.BEAST))
        assertEquals(VitureAxes(), VitureDevices.defaultSwitches(VitureDevices.BEAST))
        // Other VITURE models keep the default axes and the switches.
        assertEquals(AxisMap.DEFAULT, VitureDevices.defaultAxes(0x1301))
        assertEquals(AxisMap.DEFAULT, VitureDevices.defaultAxes(null))
        // Gravity's reaction lands on head up: the view the right way up from the start.
        val up = VitureDevices.defaultAxes(VitureDevices.BEAST).apply(beastAtRest)
        assertTrue("$up", up.y / up.length > 0.99f)
        assertTrue(VitureDevices.defaultAxes(VitureDevices.BEAST) in AxisMap.suggest(beastAtRest))
    }

    @Test fun theDefaultIsTheTestersWorkingSetupWithTheSwitchesOff() {
        // What worked for the tester: Swap turn and tilt on, then the layout "x right, y front, z up".
        val worked = { v: Vec3 -> AxisMap.XRIGHT_ZUP_YFRONT.apply(VitureAxes(swapTurnTilt = true).apply(v)) }
        val beastDefault = VitureDevices.defaultAxes(VitureDevices.BEAST)
        for (v in listOf(beastAtRest, Vec3(1f, 2f, 3f), Vec3(-0.3f, 0.7f, -1.1f), Vec3(0f, 0f, 1f))) {
            val a = worked(v)
            val b = beastDefault.apply(v)
            assertEquals(a.x, b.x, 1e-6f)
            assertEquals(a.y, b.y, 1e-6f)
            assertEquals(a.z, b.z, 1e-6f)
        }
        // That layout alone, with the switches off, puts gravity on the back axis: the view tipped a quarter turn.
        val alone = AxisMap.XRIGHT_ZUP_YFRONT.apply(beastAtRest)
        assertTrue("$alone", kotlin.math.abs(alone.y) / alone.length < 0.1f)
        // As sent, with the default layout: upside down, as the tester first saw it; Flip up and down keeps it so.
        assertTrue(AxisMap.DEFAULT.apply(beastAtRest).y < -9f)
        assertTrue(AxisMap.DEFAULT.apply(VitureAxes(flipUpDown = true).apply(beastAtRest)).y < -9f)
    }

    // ---- C: one icon shape ----

    private fun bitmap(w: Int, h: Int, color: Int, inside: (Int, Int) -> Boolean): IntArray =
        IntArray(w * h) { i -> if (inside(i % w, i / w)) color else 0 }

    private val opaqueBlue = LauncherIconShape.argb(255, 30, 90, 200)
    private val opaqueWhite = LauncherIconShape.argb(255, 255, 255, 255)
    private val opaqueBlack = LauncherIconShape.argb(255, 0, 0, 0)

    @Test fun aSquarePackIconFillsTheShape() {
        val px = bitmap(100, 100, opaqueBlue) { x, y -> x in 10..89 && y in 10..89 }
        val fit = LauncherIconShape.legacy(px, 100, 100)!!
        assertEquals(LauncherIconShape.Treatment.FILL, fit.treatment)
        assertEquals(10, fit.left)
        assertEquals(90, fit.right)
        assertEquals(1f, fit.coverage, 1e-6f)
        // Its transparent margin is trimmed: it covers the mask's box exactly.
        val d = LauncherIconShape.destination(fit, 1f, 1f, 98f)
        assertEquals(1f, d[0], 1e-4f)
        assertEquals(99f, d[2], 1e-4f)
        // Behind it its own edge colour, not white.
        assertEquals(opaqueBlue, LauncherIconShape.backdrop(fit))
    }

    @Test fun aRoundPackIconFillsTooWithItsEdgeColourInTheCorners() {
        val px = bitmap(100, 100, opaqueBlue) { x, y -> (x - 49.5f) * (x - 49.5f) + (y - 49.5f) * (y - 49.5f) <= 45f * 45f }
        val fit = LauncherIconShape.legacy(px, 100, 100)!!
        assertEquals(LauncherIconShape.Treatment.FILL, fit.treatment)
        assertTrue(fit.coverage in 0.75f..0.8f)
        assertEquals(opaqueBlue, LauncherIconShape.backdrop(fit))
    }

    @Test fun aGlyphIsFittedOnAPlateThatIsNeverWhite() {
        // A thin cross: most of its box is transparent.
        val cross = bitmap(100, 100, opaqueWhite) { x, y -> x in 45..54 || y in 45..54 }
        val fit = LauncherIconShape.legacy(cross, 100, 100)!!
        assertEquals(LauncherIconShape.Treatment.PLATE, fit.treatment)
        val d = LauncherIconShape.destination(fit, 0f, 0f, 100f)
        assertEquals(LauncherIconShape.PLATE_GLYPH * 100f, d[2] - d[0], 1e-3f)
        assertEquals(50f, (d[0] + d[2]) / 2f, 1e-3f)
        for (glyph in listOf(opaqueWhite, opaqueBlack, opaqueBlue)) {
            val plate = LauncherIconShape.plateColor(glyph)
            val bright = maxOf(LauncherIconShape.red(plate), LauncherIconShape.green(plate), LauncherIconShape.blue(plate))
            assertEquals(255, LauncherIconShape.alpha(plate))
            assertTrue("plate ${Integer.toHexString(plate)}", bright in 90..120)
            assertNotEquals(opaqueWhite, plate)
        }
        // A wide wordmark is a glyph too, even when solid.
        val wide = bitmap(100, 100, opaqueBlue) { x, y -> x in 0..99 && y in 30..69 }
        assertEquals(LauncherIconShape.Treatment.PLATE, LauncherIconShape.legacy(wide, 100, 100)!!.treatment)
        assertNull(LauncherIconShape.legacy(IntArray(16), 4, 4))
        assertNotNull(LauncherIconShape.legacy(bitmap(4, 4, opaqueBlue) { _, _ -> true }, 4, 4))
    }
}
