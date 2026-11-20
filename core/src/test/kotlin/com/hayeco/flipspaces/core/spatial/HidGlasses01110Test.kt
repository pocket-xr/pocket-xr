package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.IntroPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.10: XREAL-made glasses on USB 3318:0442 (a Pixel 8a, Android 17) had no head tracking, and the diagnostics blamed One
 * UI for a missing NCM / ECM interface. 0x0442 is a HID glasses id, so the network path cannot apply.
 */
class HidGlasses01110Test {
    private val hid = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_HID)
    private val ncm = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_CDC, UsbInterfaceInfo.CDC_SUBCLASS_NCM, "NCM")
    private val ecm = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_CDC, UsbInterfaceInfo.CDC_SUBCLASS_ECM)
    private val audio = UsbInterfaceInfo(UsbInterfaceInfo.USB_CLASS_AUDIO, 1, "uac1")

    @Test fun product0442IsHidAndTheOneFamilyStaysOnTheNetwork() {
        assertEquals(ImuTransport.USB_HID, HeadImuTransport.choose(0x3318, 0x0442, listOf(hid, audio)))
        assertEquals(ImuTransport.USB_HID, HeadImuTransport.choose(0x3318, 0x0442, emptyList()))
        for (pid in listOf(0x0435, 0x0436, 0x0437, 0x0438, 0x0439, 0x043A, 0x043D, 0x043E)) {
            assertEquals("%04X".format(pid), ImuTransport.USB_NETWORK, HeadImuTransport.choose(0x3318, pid, listOf(hid, ncm, ecm)))
            // Even when the phone lists no CDC interface, the One family's ids stay on the network path.
            assertEquals("%04X".format(pid), ImuTransport.USB_NETWORK, HeadImuTransport.choose(0x3318, pid, listOf(hid)))
        }
    }

    @Test fun anUnknownXrealIdGoesByItsInterfaces() {
        assertEquals(ImuTransport.USB_HID, HeadImuTransport.choose(0x3318, 0x0499, listOf(hid, audio)))
        assertEquals(ImuTransport.USB_NETWORK, HeadImuTransport.choose(0x3318, 0x0499, listOf(hid, ncm)))
        assertEquals(ImuTransport.USB_NETWORK, HeadImuTransport.choose(0x3318, 0x0499, listOf(UsbInterfaceInfo.named("CDC Network Control Model (NCM)"))))
        assertEquals(ImuTransport.UNKNOWN, HeadImuTransport.choose(0x3318, 0x0499, listOf(audio)))
        // Another vendor is never routed.
        assertEquals(ImuTransport.UNKNOWN, HeadImuTransport.choose(0x18D1, 0x0442, listOf(hid)))
    }

    @Test fun theModelIsOtherXrealAndOnlyHidGlassesAreFoundAsHid() {
        assertEquals("1.6.0: 0x0442 is the XBX A01+, Air family", GlassesModel.AIR, GlassesModel.detect(null, 0x0442))
        assertEquals(GlassesModel.ONE, GlassesModel.detect(null, 0x0438))
        val helen = GlassesReport.Usb(0x3318, 0x0442, "XREAL", emptyList(), listOf(hid, audio))
        val one = GlassesReport.Usb(0x3318, 0x0438, "XREAL One", listOf("NCM"), listOf(hid, ncm, ecm))
        val keyboard = GlassesReport.Usb(0x046D, 0xC31C, "Keyboard", emptyList(), listOf(hid))
        assertEquals(helen, HeadImuTransport.hidGlasses(listOf(keyboard, one, helen)))
        assertNull(HeadImuTransport.hidGlasses(listOf(keyboard, one)))
    }

    @Test fun theReportListsHidInterfacesAndTheLink() {
        val r = GlassesReport(
            appVersion = "1.1.10", phone = "Google Pixel 8a", android = "17 (API 37)",
            displays = emptyList(), usb = listOf(GlassesReport.Usb(0x3318, 0x0442, "XREAL", emptyList(), listOf(hid, audio))),
            controlLink = "not open", serial = null, stereoState = null, edidIndex = null,
            headTracking = "x", headTrackingWorks = false, calibration = "none", hands = "not available", timeUtc = "2030-01-01 12:00",
        )
        val t = r.text()
        assertTrue(t, t.contains("3318:0442 \"XREAL\": HID, uac1 (audio)"))
        assertTrue(t, t.contains("Head tracking link: USB HID"))
    }

    @Test fun theNoNetworkLineNamesThePhonesOwnSystem() {
        assertEquals("One UI", PhoneSystem.name("samsung"))
        assertEquals("One UI", PhoneSystem.name("Samsung"))
        assertEquals("Android", PhoneSystem.name("Google"))
        assertEquals("Android", PhoneSystem.name(null))
        val pixel = HeadTrackingStatus.NoNetwork(listOf("wlan0 wifi"), PhoneSystem.name("Google")).summary
        assertFalse(pixel, pixel.contains("One UI"))
        assertTrue(pixel, pixel.contains("Android may not have brought up"))
        val galaxy = HeadTrackingStatus.NoNetwork(listOf("wlan0 wifi"), PhoneSystem.name("samsung")).summary
        assertTrue(galaxy, galaxy.contains("One UI may not have brought up"))
    }

    @Test fun hidGlassesAreNotBlamedOnAMissingNetwork() {
        val s = HeadTrackingStatus.HidGlasses(0x3318, 0x0442, listOf("HID", "uac1 (audio)"))
        assertTrue(s.summary, s.summary.contains("3318:0442"))
        assertTrue(s.summary, s.summary.contains("USB HID"))
        assertFalse(s.summary, s.summary.contains("NCM"))
        assertFalse(s.summary, s.summary.contains("One UI"))
        assertFalse(s.tracking)
        assertEquals(SpatialMode.HEAD_LOCKED, SpatialModeChooser.choose(s, com.hayeco.flipspaces.core.ShizukuStatus.READY).mode)
    }

    @Test fun theCopiedDiagnosticsAreWhole() {
        val longStatus = "No USB network to the glasses. Android lists: " + (1..80).joinToString("; ") { "wlan$it wifi fe80::1:2:3:$it,172.29.120.$it (netId $it)" } + ". The end."
        val attempts = (1..50).map { "route $it: " + "x".repeat(300) + " end $it" }
        val d = HeadDiagnosticsText(
            appVersion = "1.1.10", phone = "Google Pixel 8a", android = "17", timeUtc = "2030-01-01 12:00",
            status = longStatus, tracking = false, route = null, answer = "The glasses do not answer.",
            networks = (1..40).map { "net $it" }, ethernet = emptyList(), connectResult = "Failed over eth0: timeout",
            attempts = attempts, reports = "none yet", reportCounts = null, orientation = null,
            axes = "x right, y down, z front", predictMs = 8, delayCalibrated = false, levelCalibrated = false,
            timing = "t".repeat(2000), soc = "Google Tensor G3", dynamicSensors = emptyList(),
            usb = listOf("3318:0442 \"XREAL\": HID, uac1 (audio); head tracking link: USB HID, like XREAL's Air family"),
        )
        val t = d.text()
        assertTrue(t.contains(longStatus))
        attempts.forEach { assertTrue(it, t.contains(it)) }
        (1..40).forEach { assertTrue(t.contains("- net $it\n")) }
        assertTrue(t.contains("t".repeat(2000)))
        assertTrue(t.contains("head tracking link: USB HID"))
        assertTrue(t.contains("Google Tensor G3"))
        assertFalse(t.contains("..."))
        assertFalse(t.contains("\u2026"))
    }

    @Test fun theTutorialsLastPageSaysWhereExitIs() {
        for (spatial in listOf(true, false)) for (handheld in listOf(true, false)) {
            val pages = IntroPages.trackpad(handheld, spatial)
            assertNotNull(pages.last().note)
            assertTrue(pages.last().note!!.contains(IntroPages.EXIT_LABEL))
            pages.dropLast(1).forEach { assertFalse(it.note.orEmpty().contains(IntroPages.EXIT_LABEL)) }
        }
        assertTrue(IntroPages.EXIT_NOTE.length <= 130)
        assertFalse(IntroPages.EXIT_NOTE.contains('\u2014') || IntroPages.EXIT_NOTE.contains('\u2013'))
    }
}
