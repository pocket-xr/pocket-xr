package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/** 1.2.3 (issue 54): the Air's display mode packets, framed as the protocol notes say. Untested on glasses. */
class AirDisplayMode123Test {
    private fun u16(p: ByteArray, at: Int) = (p[at].toInt() and 0xFF) or ((p[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(p: ByteArray, at: Int) = u16(p, at).toLong() or (u16(p, at + 2).toLong() shl 16)

    @Test
    fun theSetModeCommandIsFramedAsTheProtocolSays() {
        val p = AirDisplayMode.setMode(AirDisplayMode.MODE_3D_SBS)
        assertEquals(64, p.size)
        assertEquals(0xFD, p[0].toInt() and 0xFF)
        assertEquals(1 + 17, u16(p, 5))
        assertEquals(0x1337L, u32(p, 7))
        assertEquals(0L, u32(p, 11))
        assertEquals(0x08, u16(p, 15))
        for (i in 17..21) assertEquals(0, p[i].toInt())
        assertEquals(3, p[22].toInt())
        // The CRC covers the length through the payload's end.
        assertEquals(CRC32().apply { update(p, 5, 18) }.value, u32(p, 1))
        for (i in 23 until 64) assertEquals(0, p[i].toInt())
    }

    @Test
    fun getModeHasNoPayload() {
        val p = AirDisplayMode.getMode()
        assertEquals(17, u16(p, 5))
        assertEquals(0x07, u16(p, 15))
        assertEquals(CRC32().apply { update(p, 5, 17) }.value, u32(p, 1))
    }

    @Test
    fun onlyTwoDAndSideBySideAreEverSent() {
        AirDisplayMode.setMode(AirDisplayMode.MODE_2D)
        for (other in listOf(0, 2, 4, 8, 9, 11, 255)) {
            assertTrue(runCatching { AirDisplayMode.setMode(other) }.isFailure)
        }
    }

    @Test
    fun answersAreFoundByTheirCommandId() {
        val answer = AirDisplayMode.command(AirDisplayMode.CMD_GET_MODE, byteArrayOf(0, 3))
        val payload = AirDisplayMode.answer(answer, answer.size, AirDisplayMode.CMD_GET_MODE)!!
        assertArrayEquals(byteArrayOf(0, 3), payload)
        assertEquals(3, AirDisplayMode.modeOf(payload))
        assertNull(AirDisplayMode.answer(answer, answer.size, AirDisplayMode.CMD_SET_MODE))
        // An IMU report (0x01 0x02 ...) is not a control answer.
        assertNull(AirDisplayMode.answer(ByteArray(64).also { it[0] = 1; it[1] = 2 }, 64, AirDisplayMode.CMD_GET_MODE))
        assertTrue(AirDisplayMode.setTaken(byteArrayOf(0)))
        assertFalse(AirDisplayMode.setTaken(byteArrayOf(1)))
        assertFalse(AirDisplayMode.setTaken(ByteArray(0)))
    }

    @Test
    fun theControlInterfaceAndTheToggle() {
        assertEquals(4, AirDisplayMode.controlInterface(AirImuProtocol.PID_AIR_2))
        assertEquals(4, AirDisplayMode.controlInterface(AirImuProtocol.PID_AIR))
        assertEquals(0, AirDisplayMode.controlInterface(AirImuProtocol.PID_AIR_2_ULTRA))
        assertEquals(AirDisplayMode.MODE_3D_SBS, AirDisplayMode.toggled(1))
        assertEquals(AirDisplayMode.MODE_3D_SBS, AirDisplayMode.toggled(11))
        assertEquals(AirDisplayMode.MODE_2D, AirDisplayMode.toggled(3))
        assertEquals(AirDisplayMode.MODE_2D, AirDisplayMode.toggled(9))
        assertEquals("2D, 60 Hz", AirDisplayMode.label(1))
        assertTrue(AirDisplayMode.label(42).contains("not known"))
    }
}
