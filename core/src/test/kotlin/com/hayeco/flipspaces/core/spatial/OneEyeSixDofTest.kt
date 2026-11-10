package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.SteadyMode.Glasses
import com.hayeco.flipspaces.core.spatial.SteadyMode.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.1: Steady mode in 6DoF on the One series with the XREAL Eye: offered when the Eye is seen, anchor left to the user. */
class OneEyeSixDofTest {
    private val video = UsbInterfaceInfo(14, 1, "UVC Camera")
    private val hid = UsbInterfaceInfo(3, 0, "HID")
    private fun usb(vararg ifaces: UsbInterfaceInfo, vendor: Int = 0x3318, product: Int = 0x0438) =
        GlassesReport.Usb(vendor, product, "XREAL One", ifaces.mapNotNull { it.name }, ifaces.toList())

    @Test
    fun theEyeIsACameraOnTheGlassesUsb() {
        assertTrue(XrealEye.seen(GlassesModel.ONE_PRO, listOf(usb(hid, video))))
        assertFalse("no camera, no Eye", XrealEye.seen(GlassesModel.ONE_PRO, listOf(usb(hid))))
        assertFalse("a camera from another maker", XrealEye.seen(GlassesModel.ONE, listOf(usb(video, vendor = 0x0C45))))
        assertFalse("the Aura has its own camera, not an Eye", XrealEye.seen(GlassesModel.AURA, listOf(usb(video))))
    }

    @Test
    fun offeredOnTheOneSeriesOnlyWithTheEye() {
        for (m in listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_PRO_PLUS, GlassesModel.ONE_S)) {
            assertTrue(m.name, SteadyMode.sixDofOn(m, eye = true))
            assertFalse(m.name, SteadyMode.sixDofOn(m, eye = false))
            assertFalse("the user sets 6DoF there", SteadyMode.appAnchor(m))
        }
        assertTrue(SteadyMode.sixDofOn(GlassesModel.AURA))
        assertTrue(SteadyMode.appAnchor(GlassesModel.AURA))
    }

    @Test
    fun theOneSeriesGoesWideWithoutTouchingTheAnchor() {
        fun g(stereo: Int? = 0, ultrawide: Int = 0, anchor: Int? = null, size: Float = 1.2f) = Glasses(stereo, ultrawide, anchor, size, 1.2f)
        // No anchor read is needed, and one that says on is left alone.
        assertEquals(Step.LEAVE_3D, SteadyMode.next(true, g(stereo = 1), sixDof = true, appAnchor = false))
        assertEquals(Step.ULTRAWIDE_ON, SteadyMode.next(true, g(anchor = 1), sixDof = true, appAnchor = false))
        assertEquals(Step.SCREEN_MAX, SteadyMode.next(true, g(ultrawide = 1, size = 1.0f), sixDof = true, appAnchor = false))
        assertEquals(Step.DONE, SteadyMode.next(true, g(ultrawide = 1), sixDof = true, appAnchor = false))
        assertEquals(Step.ULTRAWIDE_OFF, SteadyMode.next(false, g(ultrawide = 1, anchor = 1), sixDof = true, appAnchor = false))
        assertEquals(Step.DONE, SteadyMode.next(false, g(anchor = 1), sixDof = true, appAnchor = false))
    }

    @Test
    fun theReportSaysWhatWasSeen() {
        assertNotNull(XrealEye.reportLine(GlassesModel.ONE, listOf(usb(hid))))
        assertTrue(XrealEye.reportLine(GlassesModel.ONE, listOf(usb(hid, video)))!!.startsWith("XREAL Eye: seen"))
        assertNull(XrealEye.reportLine(GlassesModel.AURA, listOf(usb(video))))
    }
}
