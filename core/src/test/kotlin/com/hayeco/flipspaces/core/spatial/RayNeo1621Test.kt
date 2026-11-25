package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.21: RayNeo glasses' motion (USB 1BBB:AF50), as the RayNeo SDK by verncat writes the protocol. */
class RayNeo1621Test {
    @Test fun startAndStopCommands() {
        val on = RayNeoProtocol.imuOn()
        assertEquals(64, on.size)
        assertArrayEquals(byteArrayOf(0x66, 0x01, 0x00), on.copyOf(3))
        assertTrue(on.drop(3).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(0x66, 0x02, 0x00), RayNeoProtocol.imuOff().copyOf(3))
    }

    @Test fun aMotionReportInG() {
        val s = RayNeoProtocol.parse(RayNeoEncoder.imu(0f, 1f, 0f, 90f, 0f, -45f, tick = 1234), 64)!!
        assertEquals(9.80665f, s.accel.y, 1e-4f)
        assertEquals(Math.PI.toFloat() / 2f, s.gyro.x, 1e-5f)
        assertEquals(-Math.PI.toFloat() / 4f, s.gyro.z, 1e-5f)
        assertEquals(1234L, s.tick)
    }

    @Test fun aMotionReportInMetresASecondSquared() {
        val s = RayNeoProtocol.parse(RayNeoEncoder.imu(0.2f, 9.7f, 0.5f, 0f, 0f, 0f), 64)!!
        assertEquals(9.7f, s.accel.y, 1e-5f)
    }

    @Test fun otherReportsAreNotMotion() {
        val p = RayNeoEncoder.imu(0f, 1f, 0f, 0f, 0f, 0f)
        p[1] = 0xC8.toByte()
        assertNull(RayNeoProtocol.parse(p, 64))
        assertEquals(0xC8, RayNeoProtocol.type(p, 64))
        assertNull(RayNeoProtocol.parse(ByteArray(64), 64))
        assertNull(RayNeoProtocol.parse(RayNeoEncoder.imu(0f, 1f, 0f, 0f, 0f, 0f), 20))
    }

    @Test fun theFramerCountsAndTurnsReports() {
        val f = RayNeoFramer()
        val out = ArrayList<ImuReport>()
        assertTrue(f.append(RayNeoEncoder.imu(0f, 1f, 0f, 10f, 0f, 0f), 64, 5L, out))
        assertFalse(f.append(ByteArray(64), 64, 6L, out))
        assertEquals(1, out.size)
        assertEquals(5L, out[0].timestampNs)
        assertEquals(1L, f.counts.imu)
        assertEquals(1L, f.counts.badLength)
    }

    @Test fun theModelIsNamedByItsUsbVendor() {
        assertEquals(GlassesModel.RAYNEO, GlassesModel.detect(GlassesId("SmartGlasses", "980"), null, rayneoOnUsb = true))
        assertEquals(GlassesModel.RAYNEO, GlassesModel.detect(GlassesId("RayNeo Air 4 Pro", ""), null))
        assertFalse(GlassesModel.RAYNEO.isXreal)
        assertFalse(GlassesModel.RAYNEO.tested)
    }
}
