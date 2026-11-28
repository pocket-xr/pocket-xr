package com.hayeco.flipspaces.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.Wallpaper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executors

/**
 * 1.6.37: makes the wallpaper ([Wallpaper]) off the render thread, for [WallpaperGl] to upload. A scene of Pocket XR's own
 * is painted once on all but one of the phone's cores and kept in the app's cache (painted again only when its look
 * changes, [Wallpaper.Painter.VERSION]); your picture is the copy [savePicture] made when you chose it.
 */
object WallpaperLoader {
    private const val TAG = "FlipSpaces/Space"

    /** What the loader made: nothing, a painted scene, or a picture. [gain] is the brightness, 0 to 1. */
    sealed class Loaded(val line: String, val gain: Float) {
        class None(line: String) : Loaded(line, 0f)

        class Painted(val scene: Wallpaper.Scene, val width: Int, val height: Int, val pixels: ShortBuffer, val stars: FloatArray, line: String, gain: Float) : Loaded(line, gain)

        class Picture(val bitmap: Bitmap, val panorama: Boolean, val frame: Wallpaper.Frame, val ambient: FloatArray, line: String, gain: Float) : Loaded(line, gain)
    }

    /** The newest result; [WallpaperGl] uploads it when it changes. Null until the first [request]. */
    @Volatile var loaded: Loaded? = null
        private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "PocketXR-wallpaper").also { it.priority = Thread.MIN_PRIORITY + 1 } }
    @Volatile private var wanted: Wallpaper.Settings? = null

    /** The painted scenes kept in memory while used, so the brightness can change without painting again. */
    private var painted: Loaded.Painted? = null
    private var picture: Pair<Long, Bitmap>? = null

    /** Makes [settings]' wallpaper, unless it is what is shown already; the newest request wins. */
    fun request(context: Context, settings: Wallpaper.Settings) {
        if (settings == wanted) return
        wanted = settings
        val app = context.applicationContext
        worker.execute {
            if (wanted != settings) return@execute
            val made = runCatching { make(app, settings) }.getOrElse { e ->
                Loaded.None("wallpaper: ${settings.scene.key} not made (${e.javaClass.simpleName}: ${e.message}); the room shows instead")
            }
            if (wanted == settings) {
                loaded = made
                Log.i(TAG, made.line)
            }
        }
    }

    private fun make(context: Context, s: Wallpaper.Settings): Loaded {
        val gain = Wallpaper.brightness(s.brightness) / 100f
        return when {
            s.scene == Wallpaper.Scene.NONE -> Loaded.None(Wallpaper.line(s))
            s.scene == Wallpaper.Scene.PICTURE -> pictureOf(context, s, gain)
            else -> {
                val p = painted?.takeIf { it.scene == s.scene } ?: paint(context, s.scene)
                painted = p
                Loaded.Painted(p.scene, p.width, p.height, p.pixels, p.stars, Wallpaper.line(s) + "; ${p.line}", gain)
            }
        }
    }

    /** The scene's half float picture, from the cache when it was painted before at this [Wallpaper.Painter.VERSION]. */
    private fun paint(context: Context, scene: Wallpaper.Scene): Loaded.Painted {
        val w = Wallpaper.Painter.WIDTH
        val h = Wallpaper.Painter.HEIGHT
        val bytes = w * h * 4 * 2
        val file = File(File(context.cacheDir, "wallpaper"), "${scene.key}-v${Wallpaper.Painter.VERSION}.rgba16f")
        val start = SystemClock.uptimeMillis()
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val how: String
        if (file.length() == bytes.toLong()) {
            file.inputStream().channel.use { ch -> while (buf.hasRemaining() && ch.read(buf) >= 0) Unit }
            buf.flip()
            how = "from the cache"
        } else {
            val shorts = ShortArray(w * h * 4)
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
            val pool = Executors.newFixedThreadPool(threads)
            val band = (h + threads - 1) / threads
            (0 until threads).map { t ->
                pool.submit { Wallpaper.Painter.rgba16f(scene, w, h, t * band until minOf(h, (t + 1) * band), shorts) }
            }.forEach { it.get() }
            pool.shutdown()
            buf.asShortBuffer().put(shorts)
            buf.position(0)
            runCatching {
                file.parentFile?.mkdirs()
                // Only one scene is kept: the others are painted again if chosen.
                file.parentFile?.listFiles()?.filter { it.name.endsWith(".rgba16f") }?.forEach { it.delete() }
                file.outputStream().channel.use { ch -> ch.write(buf.duplicate()) }
            }
            how = "painted on $threads threads"
        }
        val stars = Wallpaper.Painter.stars(scene).points
        val ms = SystemClock.uptimeMillis() - start
        return Loaded.Painted(scene, w, h, buf.asShortBuffer(), stars, "${w}x$h half float, ${stars.size / 4} stars, $how in $ms ms", 1f)
    }

    private fun pictureOf(context: Context, s: Wallpaper.Settings, gain: Float): Loaded {
        val file = pictureFile(context)
        if (!file.exists()) return Loaded.None(Wallpaper.line(s) + "; no picture saved, so the room shows")
        val bmp = picture?.takeIf { it.first == file.lastModified() }?.second
            ?: BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return Loaded.None(Wallpaper.line(s) + "; the saved picture could not be read")
        picture = file.lastModified() to bmp
        val panorama = Wallpaper.isPanorama(bmp.width, bmp.height)
        val frame = Wallpaper.frame(bmp.width, bmp.height, s.pictureDeg)
        val what = "${bmp.width}x${bmp.height}, " + if (panorama) "a 360 panorama all around" else "a screen ahead %.0f by %.0f degrees".format(java.util.Locale.ROOT, frame.widthDeg, frame.heightDeg)
        return Loaded.Picture(bmp, panorama, frame, ambientOf(bmp), Wallpaper.line(s, what), gain)
    }

    /** The picture's average colour, darker: the light around a framed picture, so it seems to light the space. */
    private fun ambientOf(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var r = 0f
        var g = 0f
        var b = 0f
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = small.getPixel(x, y)
            r += (p shr 16 and 255) / 255f
            g += (p shr 8 and 255) / 255f
            b += (p and 255) / 255f
        }
        if (small !== bmp) small.recycle()
        val k = AMBIENT / 256f
        // Never black: the room would show through around the picture.
        val floor = Wallpaper.Painter.FLOOR
        return floatArrayOf(maxOf(r * k, floor), maxOf(g * k, floor), maxOf(b * k, floor))
    }

    /** How much of the picture's average colour lights the space around it. */
    private const val AMBIENT = 0.3f

    fun pictureFile(context: Context): File = File(File(context.filesDir, "wallpaper"), "picture.jpg")

    /**
     * Copies the picture at [uri] (the photo picker's) into the app's own files, at most [MAX_SIDE] pixels on its longer
     * side (its orientation applied), so the Space can read it at any time. Off the main thread. Returns its size and
     * whether it is a panorama, for the phone's line, or throws.
     */
    fun savePicture(context: Context, uri: Uri): Pair<Int, Int> {
        val max = MAX_SIDE
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val k = minOf(1f, max.toFloat() / maxOf(w, h))
            if (k < 1f) decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
        val file = pictureFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "picture.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        tmp.renameTo(file)
        val size = bmp.width to bmp.height
        bmp.recycle()
        // The next request reads the new file.
        picture = null
        wanted = null
        return size
    }

    /** Your picture's longest side, pixels: a panorama of 4096 by 2048 is 11 pixels a degree, a screen ahead about 37. */
    const val MAX_SIDE = 4096
}
