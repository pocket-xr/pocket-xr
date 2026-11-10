package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.LogBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 1.2.8, Discussion #24: a Galaxy Z Flip 7 with VITURE Beast glasses. No head tracking, 3840 x 1080 drawn as two copies,
 * 3D only after the glasses' own menu, and the logs said nothing of the glasses' USB device.
 */
class Discussion24Test {
    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    // ---- B: the VITURE reader ----

    @Test fun theStartCommandMatchesTheWriteUpsExampleFrame() {
        // viture-v2's PROTOCOL.md: "10 00 01 03 02 00 03 00 01 02", the IMU control with payload 01 02.
        val example = VitureImuProtocol.frame(VitureImuProtocol.MSG_IMU_CONTROL, byteArrayOf(1, 2))
        assertArrayEquals(hex("10 00 01 03 02 00 03 00 01 02"), example)
        assertArrayEquals(hex("10 00 01 03 02 00 04 00 02 02"), VitureImuProtocol.startRaw())
        assertArrayEquals(hex("10 00 01 03 02 00 00 00 00 00"), VitureImuProtocol.stop())
        val back = VitureImuProtocol.readFrame(example.copyOf(64))
        assertNotNull(back)
        assertEquals(VitureImuProtocol.MSG_IMU_CONTROL, back!!.msgId)
        assertArrayEquals(byteArrayOf(1, 2), back.payload)
    }

    @Test fun aRawSampleIsReadAndABadFrameIsNot() {
        val p = VitureImuEncoder.raw(4242, Vec3(0.1f, -0.2f, 0.3f), Vec3(0f, 1f, 0f))
        val f = VitureImuProtocol.readFrame(p)!!
        assertEquals(VitureImuProtocol.MSG_RAW, f.msgId)
        val s = VitureImuProtocol.parseRaw(f.payload)!!
        assertEquals(4242L, s.counter)
        assertEquals(-0.2f, s.gyro.y, 1e-6f)
        assertEquals(1f, s.accelG.y, 1e-6f)
        // A wrong checksum, a wrong preamble and a length past the packet are each refused.
        assertNull(VitureImuProtocol.readFrame(p.copyOf().also { it[20] = (it[20] + 1).toByte() }))
        assertNull(VitureImuProtocol.readFrame(p.copyOf().also { it[0] = 0x11 }))
        assertNull(VitureImuProtocol.readFrame(p, 20))
        // XREAL's Air report, which starts 01 02, is not a VITURE frame.
        assertNull(VitureImuProtocol.readFrame(AirImuEncoder.report(1L, Vec3.ZERO, Vec3(0f, 0f, 1f))))
    }

    @Test fun theFramerGivesHeadReportsInMetresASecondSquared() {
        val framer = VitureImuFramer()
        val out = ArrayList<ImuReport>()
        assertTrue(framer.append(VitureImuEncoder.raw(1, Vec3(0f, 0.5f, 0f), Vec3(0f, 1f, 0f)), 64, 1_000_000L, out))
        assertEquals(9.81f, out[0].accel.y, 1e-4f)
        assertEquals(0.5f, out[0].gyro.y, 1e-6f)
        // A pose frame is counted, not used.
        assertFalse(framer.append(VitureImuProtocol.frame(VitureImuProtocol.MSG_POSE, ByteArray(24)).copyOf(64), 64, 2_000_000L, out))
        assertEquals(1, out.size)
        assertEquals(1L, framer.counts.imu)
        assertEquals(1L, framer.counts.unknownType)
    }

    @Test fun theAxisSwitchesAreTurnsNotMirrors() {
        val v = Vec3(1f, 2f, 3f)
        assertEquals(v, VitureAxes().apply(v))
        // Flip: half a turn about up. Gravity on up stays on up; looking up (about x) changes sign.
        assertEquals(Vec3(-1f, 2f, -3f), VitureAxes(flipUpDown = true).apply(v))
        // Swap: the sensor's z becomes up, so a turn about the sensor's z is a head turn (yaw).
        assertEquals(Vec3(1f, 3f, -2f), VitureAxes(swapTurnTilt = true).apply(v))
        for (a in listOf(VitureAxes(true, false), VitureAxes(false, true), VitureAxes(true, true))) {
            val x = a.apply(Vec3(1f, 0f, 0f))
            val y = a.apply(Vec3(0f, 1f, 0f))
            val z = a.apply(Vec3(0f, 0f, 1f))
            // Right handed after the switch: x cross y is z.
            assertTrue(a.line(), ((x cross y) - z).length < 1e-6f)
        }
        assertTrue(VitureAxes(true, true).line().contains("flipped") && VitureAxes(true, true).line().contains("swapped"))
    }

    @Test fun theClockLearnsTheRealRateFromTheCounter() {
        val c = VitureClock(nominalHz = 120f)
        val period = 1_000_000_000L / 240 // the glasses really send 240 a second
        var t = 0L
        for (n in 0..200) t = c.toNs(1000L + n, 5_000_000_000L + n * period)
        assertTrue(c.learned)
        assertTrue("$t", kotlin.math.abs(t - (5_000_000_000L + 200 * period)) < 1_000L)
        assertTrue(c.line(), c.line().contains("240.0 Hz"))
        // A counter that goes back starts over at the phone's time.
        assertEquals(9_000_000_000L, c.toNs(3, 9_000_000_000L))
        assertFalse(c.learned)
    }

    @Test fun vitureGlassesAreSaidPlainlyAndRoutedToTheirOwnReader() {
        val hid = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_HID, id = 0)
        assertEquals(ImuTransport.VITURE_HID, HeadImuTransport.choose(0x35CA, 0x1301, listOf(hid)))
        assertEquals(ImuTransport.UNKNOWN, HeadImuTransport.choose(0x18D1, 0x1301, listOf(hid)))
        val beast = GlassesReport.Usb(0x35CA, 0x1201, "VITURE Beast", emptyList(), listOf(hid))
        assertEquals(beast, HeadImuTransport.vitureGlasses(listOf(GlassesReport.Usb(0x046D, 1, "Keyboard", emptyList(), listOf(hid)), beast)))
        val off = HeadTrackingStatus.VitureNotYet(0x1201).summary
        assertTrue(off, off.startsWith("Head tracking isn't supported yet on VITURE glasses"))
        assertFalse(off.contains("NCM"))
        assertFalse(HeadTrackingStatus.VitureNotYet(0x1201).tracking)
        assertTrue(HeadTrackingStatus.VitureNotYet(0x1201, experimentalOn = true).summary.contains("Record motion"))
        assertEquals(GlassesModel.VITURE, GlassesModel.detect(GlassesId("VITURE Beast", "288")))
        assertFalse(GlassesModel.VITURE.isXreal)
        assertTrue(GlassesModel.ONE.isXreal)
        assertEquals(AxisMap.DEFAULT, GlassesModel.VITURE.defaultAxes)
    }

    // ---- A: Send logs and the motion recording ----

    @Test fun theGlassesReportListsEveryUsbDeviceWithItsEndpoints() {
        val hid = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_HID, 0, null, id = 3, protocol = 0, endpoints = listOf("IN interrupt 64 B", "OUT interrupt 64 B"))
        val r = GlassesReport(
            appVersion = "1.2.8", phone = "samsung SM-F766U1", android = "17 (API 37)",
            displays = listOf(GlassesReport.Display("VITURE Beast", "800", listOf("3840x1080@60"), "3840x1080@60")),
            usb = listOf(GlassesReport.Usb(0x35CA, 0x1201, "VITURE Beast", emptyList(), listOf(hid))),
            controlLink = "not open", serial = null, stereoState = null, edidIndex = null,
            headTracking = "x", headTrackingWorks = false, calibration = "none", hands = "not available", timeUtc = "2026-11-09 16:15",
            hidDescriptors = listOf("interface 3: 2 bytes: 06 00"),
        )
        val t = r.text()
        assertTrue(t, t.contains("USB devices (every one Android lists):"))
        assertTrue(t, t.contains("35CA:1201 \"VITURE Beast\": HID [VITURE glasses]"))
        assertTrue(t, t.contains("#3 HID [subclass 0, protocol 0; endpoints IN interrupt 64 B / OUT interrupt 64 B]"))
        assertTrue(t, t.contains("Head tracking link: USB HID, VITURE's own protocol"))
        assertTrue(t, t.contains("- interface 3: 2 bytes: 06 00"))
        assertTrue(t, t.contains("\"VITURE Beast\" (EDID product 800): now 3840x1080@60"))
        assertEquals(GlassesModel.VITURE, r.model)
    }

    @Test fun theRecordingHoldsTimedReportsAndItsDescriptorsCanBeReadBack() {
        val start = 1_000_000_000L
        val reports = listOf(
            HidRecording.Report(start + 1_500_000L, 3, byteArrayOf(0x10, 0x00, 0x09, 0x73)),
            HidRecording.Report(start + 9_250_000L, 3, byteArrayOf(0x10, 0x00, 0x09, 0x73)),
        )
        val text = HidRecording.text(
            device = "35CA:1201 \"VITURE Beast\"", appVersion = "1.2.8", timeUtc = "2026-11-09 16:20",
            descriptors = listOf(HidRecording.Descriptor(3, byteArrayOf(0x06, 0x00, 0xFF.toByte())), HidRecording.Descriptor(4, null, "claim refused")),
            sent = listOf(start + 5_000_000_000L to "VITURE raw IMU start"),
            reports = reports, startNs = start, endNs = start + 20_000_000_000L,
        )
        assertTrue(text, text.contains("1.500 if3 4: 10 00 09 73"))
        assertTrue(text, text.contains("9.250 if3 4: 10 00 09 73"))
        assertTrue(text, text.contains("at 5000.000 ms: VITURE raw IMU start"))
        assertTrue(text, text.contains("2 HID input reports in 20.0 s: interface 3 2 (0 a second, 4 bytes)"))
        assertEquals(listOf("interface 3: 3 bytes: 06 00 FF", "interface 4: not read (claim refused)"), HidRecording.descriptorsIn(text))
        assertEquals("no HID input reports in 20.0 s", HidRecording.summary(emptyList(), 20.0))
    }

    @Test fun theRecordingPicksVitureFirstThenAnyNonXrealHidDevice() {
        val hid = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_HID)
        val audio = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_AUDIO)
        val xreal = GlassesReport.Usb(0x3318, 0x0424, "XREAL Air", emptyList(), listOf(hid))
        val other = GlassesReport.Usb(0x1234, 0x0001, "Other glasses", emptyList(), listOf(hid))
        val viture = GlassesReport.Usb(0x35CA, 0x1201, "VITURE Beast", emptyList(), listOf(audio))
        assertEquals(viture, GlassesVendors.recordable(listOf(xreal, other, viture)))
        assertEquals(other, GlassesVendors.recordable(listOf(xreal, other)))
        assertNull(GlassesVendors.recordable(listOf(xreal)))
        assertEquals("VITURE", GlassesVendors.fromDisplayName("VITURE Beast"))
        assertTrue(GlassesVendors.line(viture).endsWith("[VITURE glasses]"))
    }

    @Test fun sendLogsCarriesTheExtraFilesScrubbed() {
        val out = ByteArrayOutputStream()
        val info = LogBundle.Info("1.2.8", 158, "samsung SM-F766U1", "17", "VITURE glasses", "2026-11-09 16:15")
        LogBundle.write(out, info, "diag", emptyList(), extras = listOf("glasses-report.txt" to "mail me at someone@example.com", "motion/hid.txt" to "1.000 if3 4: 10 00"))
        val names = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.keys.toString(), names.keys.containsAll(listOf("head-tracking.txt", "glasses-report.txt", "motion/hid.txt", "about.txt")))
        assertFalse(names["glasses-report.txt"]!!.contains("someone@example.com"))
        assertTrue(names["motion/hid.txt"]!!.contains("1.000 if3 4: 10 00"))
        assertTrue(names["about.txt"]!!.contains("Also: glasses-report.txt, motion/hid.txt"))
    }

    // ---- C: the wide mode ----

    private val wide = listOf(Triple(3840, 1080, 60f))

    @Test fun anUnknownGlassesWideModeIsOneCanvasUnless3dIsOn() {
        val p = ViewModeChoice.plan(GlassesView.FLAT_2D, wide, wide = WideKind.BY_VIEW, glassesMenuOnly = true)
        assertTrue(p.met)
        assertTrue(p.canvas)
        assertFalse(p.sameForBothEyes)
        assertEquals(1, ViewModeChoice.eyes(p, 3840, 1080, 63f).size)
        // 3D on: side by side, two eyes.
        val p3 = ViewModeChoice.plan(GlassesView.SBS_3D, wide, wide = WideKind.BY_VIEW, glassesMenuOnly = true)
        assertFalse(p3.canvas)
        assertEquals(StereoMode.SIDE_BY_SIDE, p3.draws)
        assertEquals(2, ViewModeChoice.eyes(p3, 3840, 1080, 63f).size)
        // XREAL keeps its 2D in 3D: the same picture in both halves, as up to 1.2.5.
        val x = ViewModeChoice.plan(GlassesView.FLAT_2D, wide)
        assertFalse(x.canvas)
        assertTrue(x.sameForBothEyes)
        assertEquals(2, ViewModeChoice.eyes(x, 3840, 1080, 63f).size)
    }

    @Test fun theBeastsEdidSaysWhichWideModeItIsIn() {
        assertEquals(WideKind.CANVAS, WideCanvas.kind(GlassesModel.VITURE, "800"))
        assertEquals(WideKind.SIDE_BY_SIDE, WideCanvas.kind(GlassesModel.VITURE, "544"))
        assertEquals(WideKind.BY_VIEW, WideCanvas.kind(GlassesModel.VITURE, "288"))
        assertEquals(WideKind.BY_VIEW, WideCanvas.kind(GlassesModel.UNKNOWN, null))
        assertEquals(WideKind.SIDE_BY_SIDE, WideCanvas.kind(GlassesModel.ONE, "800"))
        // In their 3D, 2D is the same picture in both halves (it looked right in the tester's 544 session).
        val in3d = ViewModeChoice.plan(GlassesView.FLAT_2D, wide, wide = WideKind.SIDE_BY_SIDE, glassesMenuOnly = true)
        assertTrue(in3d.sameForBothEyes)
        // In ultrawide, 3D cannot be drawn from the phone: one canvas and the glasses' menu named.
        val uw = ViewModeChoice.plan(GlassesView.SBS_3D, wide, wide = WideKind.CANVAS, glassesMenuOnly = true)
        assertTrue(uw.canvas)
        assertEquals(DepthPress.GLASSES_MENU, uw.needsGlasses)
    }

    @Test fun theRendererDrawsOneCanvasOnlyWhereItShould() {
        // XREAL with its link: as before.
        assertTrue(WideCanvas.isCanvas(3840, 1080, 0, 1, WideKind.SIDE_BY_SIDE, GlassesView.SBS_3D))
        assertFalse(WideCanvas.isCanvas(3840, 1080, 1, 0, WideKind.SIDE_BY_SIDE, GlassesView.FLAT_2D))
        // XREAL without a link: side by side.
        assertFalse(WideCanvas.isCanvas(3840, 1080, null, null, WideKind.SIDE_BY_SIDE, GlassesView.FLAT_2D))
        // Other glasses.
        assertTrue(WideCanvas.isCanvas(3840, 1080, null, null, WideKind.BY_VIEW, GlassesView.FLAT_2D))
        assertFalse(WideCanvas.isCanvas(3840, 1080, null, null, WideKind.BY_VIEW, GlassesView.SBS_3D))
        assertTrue(WideCanvas.isCanvas(3840, 1080, null, null, WideKind.CANVAS, GlassesView.SBS_3D))
        assertFalse(WideCanvas.isCanvas(1920, 1080, null, null, WideKind.CANVAS, GlassesView.FLAT_2D))
    }

    // ---- D: the Depth button ----

    @Test fun depthOnGlassesThePhoneCannotSwitchNamesTheirMenu() {
        val ask = DepthPress.Result.AskGlasses("Turn on 3D in your glasses menu first")
        assertEquals(ask, DepthPress.decide(GlassesView.FLAT_2D, sbsOffered = false, wide = WideKind.BY_VIEW, canSwitchGlasses = false))
        assertEquals(ask, DepthPress.decide(GlassesView.FLAT_2D, sbsOffered = true, wide = WideKind.CANVAS, canSwitchGlasses = false))
        assertEquals(DepthPress.Result.Toggle, DepthPress.decide(GlassesView.FLAT_2D, sbsOffered = true, wide = WideKind.BY_VIEW, canSwitchGlasses = false))
        assertEquals(DepthPress.Result.Toggle, DepthPress.decide(GlassesView.SBS_3D, sbsOffered = false, wide = WideKind.BY_VIEW, canSwitchGlasses = false))
        // XREAL with its link switches the glasses itself.
        assertEquals(DepthPress.Result.Toggle, DepthPress.decide(GlassesView.FLAT_2D, sbsOffered = false, wide = WideKind.SIDE_BY_SIDE, canSwitchGlasses = true))
        assertEquals(DepthPress.GLASSES_MENU, ViewModeChoice.plan(GlassesView.SBS_3D, listOf(Triple(1920, 1080, 60f)), glassesMenuOnly = true).needsGlasses)
        assertFalse(DepthPress.GLASSES_MENU.contains('—'))
    }

    // ---- E: launcher icons ----

    @Test fun iconsAreBakedAtTheGlassesOwnPixelSize() {
        // The Beast's log: 1920 px across 44.2 degrees; a 108 dp icon at 1 m, 1440 dp a metre, 10 % larger on hover.
        val px = LauncherIconRaster.px(108f, 1920, 44.2f)
        assertEquals(196, px)
        assertEquals(0, px % 4)
        // The ultrawide canvas keeps the panel's angle per pixel, so the icon is the same size there.
        val wideFov = 2f * kotlin.math.atan(kotlin.math.tan(44.2f * DEG / 2f) * 2f) * RAD
        assertEquals(px, LauncherIconRaster.px(108f, 3840, wideFov))
        assertEquals(LauncherIconRaster.MIN_PX, LauncherIconRaster.px(108f, 0, 44f))
        assertEquals(LauncherIconRaster.MAX_PX, LauncherIconRaster.px(108f, 20000, 30f))
    }
}
