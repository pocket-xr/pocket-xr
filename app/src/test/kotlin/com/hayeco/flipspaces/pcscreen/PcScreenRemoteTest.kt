package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcScreenRemoteTest {
    @Test fun windowsNameToTailscaleName() {
        assertEquals("mike", PcScreenRemote.magicName("Mike"))
        assertEquals("mike", PcScreenRemote.magicName("Mike(2)"))
        assertEquals("mike", PcScreenRemote.magicName("MIKE screen 3"))
        assertEquals("gaming-pc", PcScreenRemote.magicName("Gaming PC"))
        assertNull(PcScreenRemote.magicName("  "))
    }

    @Test fun awayAddressKeepsThePort() {
        assertEquals("mike", PcScreenRemote.awayAddress("192.168.1.11", "Mike"))
        assertEquals("mike:49089", PcScreenRemote.awayAddress("192.168.1.11:49089", "Mike(2)"))
        assertNull(PcScreenRemote.awayAddress("192.168.1.11", null))
    }
}
