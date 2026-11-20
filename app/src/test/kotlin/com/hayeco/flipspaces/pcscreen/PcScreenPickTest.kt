package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcScreenPickTest {
    private fun host(service: String, port: Int, address: String = "192.168.1.11") = PcScreenFinder.Host(service, address, port, service)
    private val mike = listOf(host("Mike(3)", 49989), host("Mike(2)", 49089), host("Mike", 47989))

    @Test fun threeInstancesAreOnePc() {
        val pcs = PcScreenPick.byPc(mike + host("Den", 47989, "192.168.1.20"))
        assertEquals(listOf("Mike", "Den"), pcs.map { it.first })
        assertEquals(listOf(47989, 49089, 49989), pcs[0].second.map { it.port })
    }

    @Test fun namesWithoutNumbers() {
        assertEquals("Mike", PcScreenPick.pcName("Mike(2)"))
        assertEquals("MIKE", PcScreenPick.pcName("MIKE screen 3"))
    }

    @Test fun namesWithoutTheNetworkSuffix() {
        assertEquals("Halcyons-Air", PcScreenPick.pcName("Halcyons-Air.mynetworksettings.com"))
        assertEquals("192.168.1.9", PcScreenPick.pcName("192.168.1.9"))
    }

    @Test fun screenOneTakesTheMainApollo() =
        assertEquals(47989, PcScreenPick.instanceFor(0, PcScreenPick.byPc(mike)[0].second) { true }?.port)

    @Test fun screensTwoAndThreeTakeTheExtraOnesFirst() {
        val inst = PcScreenPick.byPc(mike)[0].second
        assertEquals(49089, PcScreenPick.instanceFor(1, inst) { true }?.port)
        assertEquals(49989, PcScreenPick.instanceFor(2, inst) { it.port != 49089 }?.port)
    }

    @Test fun noneFreeIsNull() = assertNull(PcScreenPick.instanceFor(1, PcScreenPick.byPc(mike)[0].second) { false })
}
