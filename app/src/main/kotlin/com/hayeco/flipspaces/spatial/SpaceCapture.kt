package com.hayeco.flipspaces.spatial

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import com.hayeco.flipspaces.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.5.68 (the visual redesign, docs/VISUAL-DIRECTION.md): `adb shell screencap` of the glasses' display comes back black
 * apart from DeX's corner icons, so the Space's look could not be reviewed off the glasses. This saves the finished
 * frame, both eyes as the glasses get them, to a PNG:
 *
 *     adb shell touch /sdcard/Android/data/com.hayeco.flipspaces/files/snapshot
 *     adb pull /sdcard/Android/data/com.hayeco.flipspaces/files/snapshots/
 *
 * A daemon thread looks for the flag file once a second (no file check on the render thread); the render thread then
 * reads the frame back once (one stalled frame, only when asked) and the PNG is written off it.
 */
internal class SpaceCapture(context: Context) {
    private val dir: File? = context.getExternalFilesDir(null)
    private val flag: File? = dir?.let { File(it, "snapshot") }

    @Volatile private var requested = false
    @Volatile private var running = true

    init {
        if (flag != null) Thread({
            while (running) {
                if (!requested && flag.exists()) {
                    flag.delete()
                    requested = true
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "space-snapshot").apply { isDaemon = true }.start()
    }

    /** Render thread, after the frame's last pass and before its swap: reads the window's [width] by [height] when asked. */
    fun maybeTake(width: Int, height: Int) {
        if (!requested) return
        requested = false
        val out = dir ?: return
        if (width <= 0 || height <= 0) return
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        Thread({ write(out, buf, width, height) }, "space-snapshot-write").start()
    }

    fun stop() {
        running = false
    }

    private fun write(out: File, buf: ByteBuffer, width: Int, height: Int) {
        try {
            // GL's rows run bottom up; a bitmap's top down.
            val row = width * 4
            val flipped = ByteBuffer.allocateDirect(buf.capacity()).order(ByteOrder.nativeOrder())
            val line = ByteArray(row)
            for (y in height - 1 downTo 0) {
                buf.position(y * row)
                buf.get(line)
                flipped.put(line)
            }
            flipped.rewind()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(flipped)
            val shots = File(out, "snapshots").apply { mkdirs() }
            val file = File(shots, "space-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            Log.i(TAG, "Space snapshot ${width}x$height: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Space snapshot failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "FlipSpaces"
    }
}
