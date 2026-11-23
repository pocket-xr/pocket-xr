package com.hayeco.flipspaces.core.uvc

/**
 * 1.6.9 (Sammy Santiago asked for hand tracking on the Air 2 Ultra): the XREAL Air 2 Ultra's two grey tracking
 * cameras, as an ordinary UVC camera on interfaces 3 and 4, streamed isochronously.
 *
 * What is known comes from sean9061/xreal-air2ultra-camera's docs/PROTOCOL.md and python/xreal_descramble.py
 * (MIT, Copyright (c) 2026 sean9061), measured on a retail Air 2 Ultra; its block order table was first found by
 * mazeasdamien/myXreal. Untested here: the project has no Air 2 Ultra.
 *
 * - The camera says its frames are 640x241 UYVY. They are really 640x482 bytes of 8-bit grey: rows 0 to 479 the
 *   picture, row 480 telemetry, row 481 padding.
 * - Frames alternate between the two cameras (about 60 a second, 30 of each). Byte 58 of the telemetry row says
 *   which: 0x20 for camera 0, 0x21 for camera 1. Arrival order is not reliable.
 * - The 307,200 picture bytes come as 128 blocks of 2,400 in a fixed order ([REORDER]) whose starting point moves
 *   from frame to frame. The first logical block starts in the lens's black border, so the raw block whose first 128
 *   bytes are darkest is block 0.
 * - Unscrambled, each 640-byte line is a column of the upright picture: the sensors are turned 90 degrees, and the
 *   two are turned 180 degrees from each other. The result is a 480 wide, 640 tall portrait picture.
 */
object XrealUltraCamera {
    const val VENDOR = 0x3318
    const val PRODUCT = 0x0426

    fun isUltra(vendorId: Int, productId: Int): Boolean = vendorId == VENDOR && productId == PRODUCT

    const val RAW_WIDTH = 640
    const val PICTURE_ROWS = 480
    const val FRAME_BYTES = RAW_WIDTH * 482
    const val PICTURE_BYTES = RAW_WIDTH * PICTURE_ROWS

    /** The unscrambled picture: portrait. */
    const val OUT_WIDTH = 480
    const val OUT_HEIGHT = 640

    const val CAMERA_0 = 0x20
    const val CAMERA_1 = 0x21
    private const val ID_AT = PICTURE_BYTES + 58

    private const val BLOCKS = 128
    private const val BLOCK = 2400
    private const val SYNC_BYTES = 128

    /** Where each logical block sits among the raw ones (from sean9061's MIT descrambler). */
    val REORDER = intArrayOf(
        119, 54, 21, 0, 108, 22, 51, 63, 93, 99, 67, 7, 32, 112, 52, 43,
        14, 35, 75, 116, 64, 71, 44, 89, 18, 88, 26, 61, 70, 56, 90, 79,
        87, 120, 81, 101, 121, 17, 72, 31, 53, 124, 127, 113, 111, 36, 48,
        19, 37, 83, 126, 74, 109, 5, 84, 41, 76, 30, 110, 29, 12, 115, 28,
        102, 105, 62, 103, 20, 3, 68, 49, 77, 117, 125, 106, 60, 69, 98, 9,
        16, 78, 47, 40, 2, 118, 34, 13, 50, 46, 80, 85, 66, 42, 123, 122,
        96, 11, 25, 97, 39, 6, 86, 1, 8, 82, 92, 59, 104, 24, 15, 73, 65,
        38, 58, 10, 23, 33, 55, 57, 107, 100, 94, 27, 95, 45, 91, 4, 114,
    )

    private val position = IntArray(BLOCKS).also { p -> REORDER.forEachIndexed { i, b -> p[b] = i } }

    /**
     * Which camera a frame is from ([CAMERA_0], [CAMERA_1]), or the byte found when it is neither, or -1 when the frame
     * is too short. 1.6.10: the camera calls its format YUY2, but the notes were measured on a Mac, which handed the
     * frames over as UYVY: the same bytes with each pair swapped. Sammy Santiago's Ultra sent whole frames in 1.6.9
     * that never read as camera 0 at the notes' place, so the id is looked for in both orders.
     */
    fun cameraOf(frame: ByteArray, size: Int): Int {
        if (size <= ID_AT + 1) return -1
        val direct = frame[ID_AT].toInt() and 0xFF
        if (direct == CAMERA_0 || direct == CAMERA_1) return direct
        val swapped = frame[ID_AT xor 1].toInt() and 0xFF
        if (swapped == CAMERA_0 || swapped == CAMERA_1) return swapped
        // 1.6.11: Sammy's Ultra (1.6.10 log) sends the camera as 0 or 1 at the next byte, with its frame counter at
        // byte 18 (the notes: 19) and the picture size (640, 480, 640, little endian) at bytes 51 to 56.
        if (newTelemetry(frame, size) && (swapped == 0 || swapped == 1)) return CAMERA_0 + swapped
        return direct
    }

    /** 1.6.11: the telemetry layout Sammy's Ultra sends: 640, 480 and 640 as 16-bit little endian from byte 51. */
    fun newTelemetry(frame: ByteArray, size: Int): Boolean {
        if (size < PICTURE_BYTES + SIZE_AT + SIZE_MARK.size) return false
        return SIZE_MARK.indices.all { (frame[PICTURE_BYTES + SIZE_AT + it].toInt() and 0xFF) == SIZE_MARK[it] }
    }

    private const val SIZE_AT = 51
    private val SIZE_MARK = intArrayOf(0x80, 0x02, 0xE0, 0x01, 0x80, 0x02)

    /**
     * 1.6.10: whether a frame's bytes come pair-swapped from the notes' order (see [cameraOf]); null when the telemetry
     * doesn't say (1.6.11: Sammy's layout), and the picture has to show it ([smoothness]).
     */
    fun pairSwapped(frame: ByteArray, size: Int): Boolean? {
        if (size <= ID_AT + 1) return false
        val direct = frame[ID_AT].toInt() and 0xFF
        if (direct == CAMERA_0 || direct == CAMERA_1) return false
        val swapped = frame[ID_AT xor 1].toInt() and 0xFF
        if (swapped == CAMERA_0 || swapped == CAMERA_1) return true
        return null
    }

    /**
     * 1.6.11: how much neighbouring pixels differ down the picture's columns (a sample of them). The camera's readout
     * lines are the picture's columns, so a picture read in the wrong byte order zigzags from one pixel to the next
     * down each column and scores far higher than the right one.
     */
    fun smoothness(picture: ByteArray): Long {
        var sum = 0L
        var x = 0
        while (x < OUT_WIDTH) {
            for (y in 0 until OUT_HEIGHT - 1) {
                sum += kotlin.math.abs((picture[y * OUT_WIDTH + x].toInt() and 0xFF) - (picture[(y + 1) * OUT_WIDTH + x].toInt() and 0xFF))
            }
            x += 8
        }
        return sum
    }

    /** 1.6.10: the telemetry row's first [n] bytes in hex, for the logs. */
    fun telemetryHex(frame: ByteArray, size: Int, n: Int = 64): String {
        if (size < PICTURE_BYTES + n) return "frame of $size bytes"
        return (0 until n).joinToString(" ") { "%02x".format(frame[PICTURE_BYTES + it].toInt() and 0xFF) }
    }

    /** The format to ask for: the one with a 640x241 frame (the doubled grey picture), else null. */
    fun choose(formats: List<UvcFormat>, maxFps: Double = 60.0): UvcChoice? {
        for (f in formats) {
            if (f.kind != UvcFormatKind.UNCOMPRESSED) continue
            val frame = f.frames.firstOrNull { it.width == RAW_WIDTH && it.width.toLong() * it.height * 2 == FRAME_BYTES.toLong() }
                ?: continue
            return UvcChoice(f, frame, UvcFormatChooser.interval(frame, maxFps))
        }
        return null
    }

    /** Index of the darkest raw block start: logical block 0. */
    fun syncBlock(raw: ByteArray): Int {
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (b in 0 until BLOCKS) {
            var sum = 0L
            val at = b * BLOCK
            for (i in at until at + SYNC_BYTES) sum += raw[i].toInt() and 0xFF
            if (sum < bestSum) {
                bestSum = sum
                best = b
            }
        }
        return best
    }

    /** Maps logical byte [n] of the readout to its place in the portrait picture, for [camera]. */
    private fun lut(camera0: Boolean): IntArray {
        val out = IntArray(PICTURE_BYTES)
        for (n in 0 until PICTURE_BYTES) {
            val r = n / RAW_WIDTH // the readout line: a column of the upright picture
            val c = n % RAW_WIDTH
            val y: Int
            val x: Int
            if (camera0) {
                y = c
                x = r
            } else {
                y = RAW_WIDTH - 1 - c
                x = PICTURE_ROWS - 1 - r
            }
            out[n] = y * OUT_WIDTH + x
        }
        return out
    }

    private val lut0 by lazy { lut(true) }
    private val lut1 by lazy { lut(false) }

    /**
     * Unscrambles a frame's picture into [out] ([OUT_WIDTH] x [OUT_HEIGHT] bytes, row by row). Returns false when the
     * frame is too short.
     */
    fun descramble(
        raw: ByteArray,
        size: Int,
        out: ByteArray,
        camera0: Boolean = cameraOf(raw, size) != CAMERA_1,
        swapped: Boolean = pairSwapped(raw, size) ?: false,
    ): Boolean {
        if (size < PICTURE_BYTES || out.size < PICTURE_BYTES) return false
        // Blocks start on even bytes, so a pair swap stays inside each block and a block's first 128 bytes add up the same.
        val align = position[syncBlock(raw)]
        val map = if (camera0) lut0 else lut1
        val flip = if (swapped) 1 else 0
        for (t in 0 until BLOCKS) {
            val from = REORDER[(align + t) % BLOCKS] * BLOCK
            val logical = t * BLOCK
            for (i in 0 until BLOCK) out[map[logical + i]] = raw[from + (i xor flip)]
        }
        return true
    }

    /** The opposite of [descramble], for tests: a portrait picture as the camera would send it, starting at [align]. */
    fun scramble(picture: ByteArray, align: Int, camera0: Boolean, cameraId: Int = if (camera0) CAMERA_0 else CAMERA_1): ByteArray {
        val raw = ByteArray(FRAME_BYTES)
        val map = if (camera0) lut0 else lut1
        for (t in 0 until BLOCKS) {
            val to = REORDER[(align + t) % BLOCKS] * BLOCK
            val logical = t * BLOCK
            for (i in 0 until BLOCK) raw[to + i] = picture[map[logical + i]]
        }
        raw[ID_AT] = cameraId.toByte()
        return raw
    }

    /** For tests: [raw] with every byte pair swapped, as a camera sending the other order would. */
    fun swapPairs(raw: ByteArray): ByteArray = ByteArray(raw.size) { raw[it xor 1] }
}
