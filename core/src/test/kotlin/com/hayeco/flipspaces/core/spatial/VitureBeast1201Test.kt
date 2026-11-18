package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.3 (a vivo V2562 on Android 17, Pocket XR 1.5.1): a VITURE Beast on USB 35CA:1201, not the 35CA:1211 seen before,
 * took the defaults of untested VITURE glasses, and with no Space opened yet the Send logs zip said "Unknown glasses".
 */
class VitureBeast1201Test {
    @Test fun bothBeastProductsAreTheBeast() {
        assertTrue(VitureDevices.isBeast(0x1211))
        assertTrue(VitureDevices.isBeast(0x1201))
        assertFalse(VitureDevices.isBeast(0x1301))
        assertFalse(VitureDevices.isBeast(0x1102))
        assertFalse(VitureDevices.isBeast(null))
    }

    @Test fun the1201BeastGetsTheBeastsDefaults() {
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, VitureDevices.defaultAxes(0x1201))
        assertTrue(VitureDevices.headTrackingByDefault(0x1201))
        assertTrue(VitureDevices.headTrackingOn(null, 0x1201))
        assertFalse(VitureDevices.headTrackingOn(false, 0x1201))
        assertEquals("VITURE head tracking", VitureDevices.switchLabel(0x1201))
        val off = HeadTrackingStatus.VitureNotYet(0x1201).summary
        assertTrue(off, off.contains("turned off"))
    }

    @Test fun the1201BeastCarriesTheMotionSensorAndItsMicrophoneDoesNot() {
        // Android's names from the tester's Glasses report; the microphone listed first, as after a replug.
        data class D(val pid: Int, val name: String)
        val picked = VitureDevices.pick(listOf(D(0x1102, "VITURE Microphone"), D(0x1201, "VITURE Beast XR Glasses")), { it.pid }, { it.name })
        assertEquals(0x1201, picked?.pid)
    }

    @Test fun vitureOnUsbIsNotUnknownGlasses() {
        // No Space opened yet: no saved display name, no XREAL device on USB.
        assertEquals(GlassesModel.UNKNOWN, GlassesModel.detect(null, null))
        assertEquals(GlassesModel.VITURE, GlassesModel.detect(null, null, vitureOnUsb = true))
        // The display's name still decides first, and XREAL's USB product before VITURE's vendor.
        assertEquals(GlassesModel.VITURE, GlassesModel.detect(GlassesId("VITURE Beast", "288")))
        assertEquals(GlassesModel.AURA, GlassesModel.detect(GlassesId("XREAL 1 Ultra", "16656"), null, vitureOnUsb = true))
        assertEquals(GlassesModel.ONE, GlassesModel.detect(null, GlassesModel.USB_ONE, vitureOnUsb = true))
    }
}
