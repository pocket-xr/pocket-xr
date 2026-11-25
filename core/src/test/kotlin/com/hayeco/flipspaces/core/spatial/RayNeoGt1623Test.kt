package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.23: the RayNeo GT (USB 3941:AF50, a new user's XREAL Beam Pro), read as the Air 4 Pro is. */
class RayNeoGt1623Test {
    @Test fun theGtIsRayNeo() {
        assertTrue(RayNeoProtocol.isRayNeo(0x3941))
        assertTrue(RayNeoProtocol.isRayNeo(0x1BBB))
        assertFalse(RayNeoProtocol.isRayNeo(0x3318))
        assertEquals("RayNeo GT", RayNeoProtocol.modelName(0x3941, 0xAF50))
        assertEquals("RayNeo Air 4 Pro or Air 3S Pro", RayNeoProtocol.modelName(0x1BBB, 0xAF50))
        assertEquals("RayNeo", GlassesVendors.name(0x3941))
    }

    @Test fun itsDisplayAndUsbNameTheModel() {
        // The user's report: display "SmartGlasses" (EDID product 980), USB 3941:AF50.
        assertEquals(GlassesModel.RAYNEO, GlassesModel.detect(GlassesId("SmartGlasses", "980"), null, rayneoOnUsb = true))
    }
}
