package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcScreenAddressTest {
    @Test fun plainHostTakesTheDefaultPort() = assertEquals("192.168.1.20" to 47989, PcScreenAddress.parse(" 192.168.1.20 "))
    @Test fun hostWithPort() = assertEquals("192.168.1.20" to 48989, PcScreenAddress.parse("192.168.1.20:48989"))
    @Test fun nameWithPort() = assertEquals("pc.local" to 48989, PcScreenAddress.parse("pc.local:48989"))
    @Test fun bareIpv6() = assertEquals("fe80::1" to 47989, PcScreenAddress.parse("fe80::1"))
    @Test fun bracketedIpv6WithPort() = assertEquals("fe80::1" to 48989, PcScreenAddress.parse("[fe80::1]:48989"))
    @Test fun bracketedIpv6() = assertEquals("fe80::1" to 47989, PcScreenAddress.parse("[fe80::1]"))
    @Test fun emptyIsNull() = assertNull(PcScreenAddress.parse("  "))
    @Test fun badPortIsNull() = assertNull(PcScreenAddress.parse("192.168.1.20:abc"))
    @Test fun portOutOfRangeIsNull() = assertNull(PcScreenAddress.parse("192.168.1.20:70000"))
    @Test fun noHostIsNull() = assertNull(PcScreenAddress.parse(":48989"))
}
