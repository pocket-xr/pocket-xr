package com.hayeco.flipspaces.hands

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * MediaPipe's hand landmarker model (Apache 2.0, published by Google). It is not on Maven, so
 * Pocket XR downloads it once from Google's model bucket into app storage. A copy placed in
 * app/src/main/assets is used instead if present.
 */
object HandModelStore {
    const val FILE_NAME = "hand_landmarker.task"
    const val URL_STRING =
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    private const val MIN_BYTES = 1_000_000L

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun inAssets(context: Context): Boolean =
        runCatching { context.assets.open(FILE_NAME).close() }.isSuccess

    fun available(context: Context): Boolean = inAssets(context) || file(context).length() >= MIN_BYTES

    /** The model as a direct buffer, which is what MediaPipe's setModelAssetBuffer takes. */
    fun load(context: Context): ByteBuffer {
        if (inAssets(context)) {
            val bytes = context.assets.open(FILE_NAME).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        }
        val f = file(context)
        if (f.length() < MIN_BYTES) throw IOException("Hand model not downloaded yet")
        return f.inputStream().channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    }

    /** Blocking download. Returns null on success, else the error text. */
    fun download(context: Context): String? {
        val target = file(context)
        val temp = File(context.filesDir, "$FILE_NAME.part")
        return try {
            val connection = URL(URL_STRING).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != 200) return context.getString(com.hayeco.flipspaces.R.string.hands_download_failed_http, connection.responseCode, connection.responseMessage.orEmpty())
                connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (temp.length() < MIN_BYTES) return temp.length().toInt().let { n -> context.resources.getQuantityString(com.hayeco.flipspaces.R.plurals.hands_download_too_small, n, n) }
            if (!temp.renameTo(target)) return context.getString(com.hayeco.flipspaces.R.string.hands_could_not_save)
            null
        } catch (e: IOException) {
            context.getString(com.hayeco.flipspaces.R.string.hands_download_failed, e.message.toString())
        } finally {
            temp.delete()
        }
    }
}
