package com.hayeco.flipspaces.core.uvc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrealUltraCameraTest {
    /** A portrait picture with a black first readout line (the lens border) and texture elsewhere. */
    private fun picture(camera0: Boolean): ByteArray {
        val p = ByteArray(XrealUltraCamera.PICTURE_BYTES)
        for (y in 0 until XrealUltraCamera.OUT_HEIGHT) for (x in 0 until XrealUltraCamera.OUT_WIDTH) {
            p[y * XrealUltraCamera.OUT_WIDTH + x] = (40 + (x * 7 + y * 13) % 200).toByte()
        }
        // Readout line 0 is the picture's column x = 0 for camera 0, x = 479 for camera 1: black, as the vignette is.
        val col = if (camera0) 0 else XrealUltraCamera.OUT_WIDTH - 1
        for (y in 0 until XrealUltraCamera.OUT_HEIGHT) p[y * XrealUltraCamera.OUT_WIDTH + col] = 0
        return p
    }

    @Test fun theTableIsAPermutation() {
        assertEquals((0 until 128).toList(), XrealUltraCamera.REORDER.sorted())
    }

    @Test fun descrambleUndoesTheCamerasOrderAtAnyPhase() {
        for (camera0 in listOf(true, false)) {
            val pic = picture(camera0)
            for (align in listOf(0, 1, 37, 127)) {
                val raw = XrealUltraCamera.scramble(pic, align, camera0)
                val out = ByteArray(XrealUltraCamera.PICTURE_BYTES)
                assertTrue(XrealUltraCamera.descramble(raw, raw.size, out))
                assertArrayEquals("camera0=$camera0 align=$align", pic, out)
            }
        }
    }

    @Test fun theTelemetryRowSaysWhichCamera() {
        val raw0 = XrealUltraCamera.scramble(picture(true), 5, true)
        val raw1 = XrealUltraCamera.scramble(picture(false), 5, false)
        assertEquals(XrealUltraCamera.CAMERA_0, XrealUltraCamera.cameraOf(raw0, raw0.size))
        assertEquals(XrealUltraCamera.CAMERA_1, XrealUltraCamera.cameraOf(raw1, raw1.size))
        assertEquals(-1, XrealUltraCamera.cameraOf(raw0, 1000))
    }

    @Test fun pairSwappedFramesReadTheSame() {
        for (camera0 in listOf(true, false)) {
            val pic = picture(camera0)
            val raw = XrealUltraCamera.swapPairs(XrealUltraCamera.scramble(pic, 77, camera0))
            assertEquals(true, XrealUltraCamera.pairSwapped(raw, raw.size))
            assertEquals(if (camera0) XrealUltraCamera.CAMERA_0 else XrealUltraCamera.CAMERA_1, XrealUltraCamera.cameraOf(raw, raw.size))
            val out = ByteArray(XrealUltraCamera.PICTURE_BYTES)
            assertTrue(XrealUltraCamera.descramble(raw, raw.size, out))
            assertArrayEquals("camera0=$camera0", pic, out)
        }
        val plain = XrealUltraCamera.scramble(picture(true), 3, true)
        assertEquals(false, XrealUltraCamera.pairSwapped(plain, plain.size))
    }

    /** A frame as Sammy's Ultra sends it: pair-swapped, camera 0 or 1 at byte 59, the size mark at 51. */
    private fun sammyFrame(camera0: Boolean): ByteArray {
        val raw = XrealUltraCamera.swapPairs(XrealUltraCamera.scramble(picture(camera0), 21, camera0, cameraId = 0))
        val t = XrealUltraCamera.PICTURE_BYTES
        raw[t + 58] = 0
        raw[t + 59] = if (camera0) 0 else 1
        intArrayOf(0x80, 0x02, 0xE0, 0x01, 0x80, 0x02).forEachIndexed { i, b -> raw[t + 51 + i] = b.toByte() }
        return raw
    }

    @Test fun sammysTelemetryGivesTheCamera() {
        val a = sammyFrame(true)
        val b = sammyFrame(false)
        assertTrue(XrealUltraCamera.newTelemetry(a, a.size))
        assertEquals(XrealUltraCamera.CAMERA_0, XrealUltraCamera.cameraOf(a, a.size))
        assertEquals(XrealUltraCamera.CAMERA_1, XrealUltraCamera.cameraOf(b, b.size))
        assertNull(XrealUltraCamera.pairSwapped(a, a.size))
    }

    @Test fun theRightByteOrderIsTheSmootherPicture() {
        val raw = sammyFrame(true)
        val right = ByteArray(XrealUltraCamera.PICTURE_BYTES)
        val wrong = ByteArray(XrealUltraCamera.PICTURE_BYTES)
        XrealUltraCamera.descramble(raw, raw.size, right, camera0 = true, swapped = true)
        XrealUltraCamera.descramble(raw, raw.size, wrong, camera0 = true, swapped = false)
        assertArrayEquals(picture(true), right)
        assertTrue(XrealUltraCamera.smoothness(right) < XrealUltraCamera.smoothness(wrong))
    }

    @Test fun anUnknownIdIsReportedAsFound() {
        val raw = XrealUltraCamera.scramble(picture(true), 3, true, cameraId = 0x42)
        assertEquals(0x42, XrealUltraCamera.cameraOf(raw, raw.size))
    }

    @Test fun choosesThe640x241Frame() {
        val frames = listOf(UvcFrame(1, 640, 241, 166666L, listOf(166666L)))
        val f = UvcFormat(1, UvcFormatKind.UNCOMPRESSED, "UYVY", 16, frames)
        val c = XrealUltraCamera.choose(listOf(f))
        assertNotNull(c)
        assertEquals(241, c!!.frame.height)
        assertEquals(166666L, c.intervalUnits)
        assertNull(XrealUltraCamera.choose(listOf(f.copy(frames = listOf(UvcFrame(1, 640, 480, 166666L, listOf(166666L)))))))
    }

    @Test fun isoEndpointsCarryTheirExtraTransactions() {
        // wMaxPacketSize 0x1400: 1024 bytes and 2 extra transactions, 3072 a microframe
        val ep = UvcEndpoint(0x81, bulk = false, maxPacketSize = 0x1400 and 0x7FF, iso = true, rawMaxPacketSize = 0x1400)
        assertEquals(3072, ep.payloadPerInterval)
        val s = UvcStreamingInterface(
            4, emptyList(),
            listOf(
                UvcAltSetting(0, null),
                UvcAltSetting(1, UvcEndpoint(0x81, false, 512, iso = true, rawMaxPacketSize = 512)),
                UvcAltSetting(2, ep),
            ),
            0x81,
        )
        assertEquals(2, s.isoAltFor(2000)!!.alternate)
        assertEquals(1, s.isoAltFor(400)!!.alternate)
        assertEquals(2, s.isoAltFor(9000)!!.alternate)
        assertNull(s.bulkEndpoint)
    }
}
