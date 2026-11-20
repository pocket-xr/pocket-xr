package com.hayeco.flipspaces.pcscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcScreenQualityTest {
    @Test fun autoIs120WhereThePhoneDecodesIt() {
        assertEquals(120, PcScreenQuality.fps("auto", decodes120 = true))
        assertEquals(60, PcScreenQuality.fps("auto", decodes120 = false))
        assertEquals(60, PcScreenQuality.fps("60", decodes120 = true))
        assertEquals(120, PcScreenQuality.fps("120", decodes120 = false))
    }

    @Test fun fortyMegabitsAt1080p60() = assertEquals(40_000, PcScreenQuality.bitrateKbps(1920, 1080, 60, "auto"))

    @Test fun moreFramesAndPixelsMoreBitsButNotDouble() {
        val at120 = PcScreenQuality.bitrateKbps(1920, 1080, 120, "auto")
        assertTrue(at120 in 55_000..65_000)
        assertTrue(PcScreenQuality.bitrateKbps(1784, 976, 60, "auto") < 40_000)
    }

    @Test fun sharpAndSaver() {
        assertEquals(64_000, PcScreenQuality.bitrateKbps(1920, 1080, 60, "sharp"))
        assertEquals(20_000, PcScreenQuality.bitrateKbps(1920, 1080, 60, "saver"))
    }

    @Test fun clamped() {
        assertEquals(PcScreenQuality.MAX_KBPS, PcScreenQuality.bitrateKbps(3840, 2160, 120, "sharp"))
        assertEquals(PcScreenQuality.MIN_KBPS, PcScreenQuality.bitrateKbps(640, 360, 60, "saver"))
    }

    @Test fun reconnectBacksOffThenGivesUp() {
        assertEquals(1000L, PcScreenQuality.reconnectDelayMs(0, 0))
        assertEquals(8000L, PcScreenQuality.reconnectDelayMs(3, 10_000))
        assertEquals(10_000L, PcScreenQuality.reconnectDelayMs(9, 100_000))
        assertNull(PcScreenQuality.reconnectDelayMs(20, PcScreenQuality.GIVE_UP_MS))
    }
}
