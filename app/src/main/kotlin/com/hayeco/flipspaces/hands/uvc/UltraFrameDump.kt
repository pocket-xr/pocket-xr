package com.hayeco.flipspaces.hands.uvc

import android.content.Context
import com.hayeco.flipspaces.Log
import java.io.File

/**
 * 1.6.11: the XREAL Air 2 Ultra's first two raw camera frames of a hand tracking run, kept as hex text (64 bytes a
 * line) for Send logs, so the frame layout can be worked out from what the camera really sends. Only for the Ultra,
 * only after hand tracking ran with it; the files are replaced on each run and left out of the logs after two days.
 */
object UltraFrameDump {
    private const val DIR = "ultra-camera"

    private fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    fun save(context: Context, n: Int, data: ByteArray, size: Int) {
        val copy = data.copyOf(size)
        Thread({
            runCatching {
                val d = dir(context).apply { mkdirs() }
                val sb = StringBuilder(size * 3)
                sb.append("XREAL Air 2 Ultra camera frame $n, $size bytes, raw as received, 64 bytes a line\n")
                for (i in 0 until size) {
                    sb.append(HEX[(copy[i].toInt() shr 4) and 0xF]).append(HEX[copy[i].toInt() and 0xF])
                    sb.append(if (i % 64 == 63) '\n' else ' ')
                }
                File(d, "frame-$n.hex").writeText(sb.toString())
            }.onFailure { Log.w("FlipSpacesHands", "Ultra frame $n not kept for the logs: ${it.message}") }
        }, "UltraFrameDump").start()
    }

    fun recent(context: Context, maxAgeMs: Long): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".hex") && System.currentTimeMillis() - f.lastModified() < maxAgeMs }
            ?.sortedBy { it.name }.orEmpty()

    private val HEX = "0123456789abcdef".toCharArray()
}
