package com.hayeco.flipspaces.pcscreen

import com.limelight.nvstream.http.HostHttpResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class PcScreenWakeTest {
    @Test fun macWithColons() = assertArrayEquals(
        byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte()),
        PcScreenWake.macBytes("aa:bb:01:02:03:ff"),
    )

    @Test fun macWithDashes() = assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), PcScreenWake.macBytes("01-02-03-04-05-06"))
    @Test fun zeroMacIsNone() = assertNull(PcScreenWake.macBytes("00:00:00:00:00:00"))
    @Test fun badMacIsNone() = assertNull(PcScreenWake.macBytes("01:02:03"))

    @Test fun magicPacketIsSixFfThenTheMacSixteenTimes() {
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val p = PcScreenWake.magicPacket(mac)
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test fun troubleFromErrors() {
        assertEquals(PcScreenTrouble.NO_PERMISSION, PcScreenTrouble.of(HostHttpResponseException(403, "Permission denied")))
        assertEquals(PcScreenTrouble.NOT_PAIRED, PcScreenTrouble.of(HostHttpResponseException(401, "Certificate verification failed")))
        assertEquals(PcScreenTrouble.UNREACHABLE, PcScreenTrouble.of(IOException("wrapped", ConnectException("refused"))))
        assertEquals(PcScreenTrouble.OTHER, PcScreenTrouble.of(IllegalStateException("x")))
    }
}
