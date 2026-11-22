package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class VitureGen1ProtocolTest {
    private val check = "123456789".toByteArray()

    @Test fun crcMatchesTheStandardCheckValues() {
        // CRC-16/CCITT-FALSE (start 0xFFFF) and CRC-16/XMODEM (start 0) of "123456789"
        assertEquals(0x29B1, VitureGen1Protocol.crc16(check, 0, check.size, 0xFFFF))
        assertEquals(0x31C3, VitureGen1Protocol.crc16(check, 0, check.size, 0x0000))
    }

    @Test fun lumaIsFirstGenerationAndTheBeastIsNot() {
        assertTrue(VitureGen1Protocol.isGen1(0x1131))
        assertTrue(VitureGen1Protocol.isGen1(0x101D))
        assertFalse(VitureGen1Protocol.isGen1(0x1211))
        assertFalse(VitureGen1Protocol.isGen1(0x1201))
        assertFalse(VitureGen1Protocol.isGen1(null))
    }

    @Test fun webxrStartIsLaidOutAsItsWriteUpBuildsIt() {
        val p = VitureGen1Protocol.imuOn(0, VitureGen1Protocol.Variant.WEBXR)
        assertEquals(64, p.size)
        assertEquals(0xFF, p[0].toInt() and 0xFF)
        assertEquals(0xFE, p[1].toInt() and 0xFF)
        assertEquals(14, p[4].toInt()) // 8 + 2 + 2 + 1 data + 1 end marker
        assertEquals(0, p[5].toInt())
        assertEquals(0x15, p[14].toInt())
        assertEquals(1, p[18].toInt())
        assertEquals(3, p[19].toInt())
        val crc = VitureGen1Protocol.crc16(p, 4, 16, 0xFFFF)
        assertEquals(crc ushr 8, p[2].toInt() and 0xFF)
        assertEquals(crc and 0xFF, p[3].toInt() and 0xFF)
    }

    @Test fun cStartIsLaidOutAsItsWriteUpBuildsIt() {
        val p = VitureGen1Protocol.imuOn(0, VitureGen1Protocol.Variant.C)
        assertEquals(13, p[4].toInt()) // 0x0c + 1 data byte, no end marker
        assertEquals(1, p[18].toInt())
        assertEquals(0, p[19].toInt())
        val crc = VitureGen1Protocol.crc16(p, 4, 15, 0)
        assertEquals(crc and 0xFF, p[2].toInt() and 0xFF)
        assertEquals(crc ushr 8, p[3].toInt() and 0xFF)
    }

    @Test fun stopSendsZero() {
        assertEquals(0, VitureGen1Protocol.imuOff(3, VitureGen1Protocol.Variant.WEBXR)[18].toInt())
        assertEquals(3, VitureGen1Protocol.imuOff(3, VitureGen1Protocol.Variant.WEBXR)[16].toInt())
    }

    @Test fun anglesAreBigEndianFloats() {
        val a = VitureGen1Protocol.angles(VitureGen1Encoder.imu(1.5f, -20f, 90f), 64)
        assertNotNull(a)
        assertEquals(1.5f, a!!.x, 0f)
        assertEquals(-20f, a.y, 0f)
        assertEquals(90f, a.z, 0f)
    }

    @Test fun onlyMotionPacketsHoldAngles() {
        val p = VitureGen1Encoder.imu(1f, 2f, 3f)
        p[1] = 0xFD.toByte()
        assertNull(VitureGen1Protocol.angles(p, 64))
        assertNull(VitureGen1Protocol.angles(VitureGen1Encoder.imu(1f, 2f, 3f), 20))
    }

    @Test fun levelPoseReadsGravityUp() {
        val f = VitureGen1Framer()
        val out = ArrayList<ImuReport>()
        assertTrue(f.append(VitureGen1Encoder.imu(0f, 0f, 0f), 64, 0L, out))
        val a = out.single().accel
        assertEquals(0f, a.x, 1e-4f)
        assertEquals(AirImuProtocol.G, a.y, 1e-4f)
        assertEquals(0f, a.z, 1e-4f)
        assertEquals(Vec3.ZERO, out.single().gyro)
    }

    @Test fun lookingDownTiltsGravityTowardTheFront() {
        // a1 is the up and down angle (a turn about x): gravity's reaction leaves the head's y by 30 degrees, toward its
        // z, and keeps its size; which way along z is for a tester's glasses to show
        val out = ArrayList<ImuReport>()
        VitureGen1Framer().append(VitureGen1Encoder.imu(0f, 30f, 0f), 64, 0L, out)
        val a = out.single().accel
        assertEquals(AirImuProtocol.G, kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z), 1e-3f)
        assertEquals(AirImuProtocol.G * kotlin.math.cos(PI / 6).toFloat(), a.y, 1e-3f)
        assertEquals(0f, a.x, 1e-4f)
    }

    @Test fun turningLeftReadsAsATurnAboutUp() {
        val f = VitureGen1Framer(nominalHz = 100f)
        val out = ArrayList<ImuReport>()
        // a2 is the left and right angle: 1 degree a sample at 100 Hz is 100 deg/s about y
        for (i in 0..5) f.append(VitureGen1Encoder.imu(0f, 0f, i.toFloat()), 64, i * 10_000_000L, out)
        val g = out.last().gyro
        assertEquals(Math.toRadians(100.0).toFloat(), g.y, 0.02f)
        assertTrue(abs(g.x) < 1e-3f && abs(g.z) < 1e-3f)
    }

    @Test fun answersAreCountedNotTakenAsMotion() {
        val f = VitureGen1Framer()
        val p = ByteArray(64)
        p[0] = 0xFF.toByte()
        p[1] = 0xFD.toByte()
        assertFalse(f.append(p, 64, 0L, ArrayList()))
        assertEquals(1L, f.counts.unknownType)
        assertEquals(0L, f.counts.imu)
    }

    @Test fun turnRateOfNoTurnIsZero() {
        assertEquals(Vec3.ZERO, VitureGen1Framer.turnRate(Quat.IDENTITY, Quat.IDENTITY, 0.01f))
    }
}
