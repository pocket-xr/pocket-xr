package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1.6.37 (Crr66: "Can the space have a wallpaper? So you have a virtual room"; Mike: "build it and make sure it's extremely
 * well made and designed"): what is drawn behind the windows. The glasses show only light, so black is the real room;
 * a wallpaper covers the room with a scene of Pocket XR's own, or a picture of yours, far away at infinity. It is off
 * ([Scene.NONE]) until chosen, and the windows sit in front of it as always.
 *
 * The scenes are painted here, in code, as a whole sphere (an equirectangular picture, [Painter]): nothing is bundled or
 * downloaded, every pixel is Pocket XR's own, and a scene looks the same on every phone. They are dark on purpose: the
 * windows stay the brightest thing in view, the glasses run cooler, and the room behind is hidden without glare.
 */
object Wallpaper {
    /** What is behind the windows. [key] is what is saved; never renamed. */
    enum class Scene(val key: String) {
        /** Nothing: the real room through the glasses (black is see-through). */
        NONE("none"),

        /** A clear night far from town: hills, the Milky Way and stars. */
        NIGHT_SKY("night_sky"),

        /** The last light after sunset over layered hills, a few first stars. */
        DUSK("dusk"),

        /** The northern lights over a snowfield. */
        AURORA("aurora"),

        /** A quiet dark studio: one smooth curve of floor and wall, a warm light ahead. For films. */
        STUDIO("studio"),

        /** A picture of yours: a 360 panorama all around, any other picture as a big screen ahead. */
        PICTURE("picture"),
        ;

        /** One of Pocket XR's own scenes, painted by [Painter]. */
        val painted: Boolean get() = this != NONE && this != PICTURE

        companion object {
            fun of(key: String?): Scene = entries.firstOrNull { it.key == key } ?: NONE

            /** The order the phone shows them in. */
            val SHOWN = listOf(NONE, NIGHT_SKY, DUSK, AURORA, STUDIO, PICTURE)
        }
    }

    /** Brightness in percent: the scene's light scaled down to this. Dimmed by default, so the windows stand out. */
    const val MIN_BRIGHTNESS = 10
    const val MAX_BRIGHTNESS = 100
    const val DEFAULT_BRIGHTNESS = 70

    fun brightness(percent: Int): Int = percent.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

    /** A picture that is not a panorama is a screen ahead this many degrees wide, by default and at most. */
    const val MIN_PICTURE_DEG = 60
    const val MAX_PICTURE_DEG = 160
    const val DEFAULT_PICTURE_DEG = 110

    fun pictureDeg(deg: Int): Int = deg.coerceIn(MIN_PICTURE_DEG, MAX_PICTURE_DEG)

    /** A picture whose width is twice its height, within this, is a 360 by 180 degree panorama. */
    private const val PANORAMA_MIN = 1.9f
    private const val PANORAMA_MAX = 2.1f

    fun isPanorama(width: Int, height: Int): Boolean =
        height > 0 && (width.toFloat() / height) in PANORAMA_MIN..PANORAMA_MAX

    /**
     * Where a picture that is not a panorama sits: centred ahead, [widthDeg] wide and as tall as its shape asks, in
     * degrees of yaw and pitch, never past [MAX_PICTURE_HEIGHT_DEG] (a tall portrait is then narrower instead).
     */
    data class Frame(val widthDeg: Float, val heightDeg: Float)

    const val MAX_PICTURE_HEIGHT_DEG = 120f

    fun frame(width: Int, height: Int, widthDeg: Int): Frame {
        val aspect = if (height > 0) width.toFloat() / height else 1f
        var w = pictureDeg(widthDeg).toFloat()
        var h = w / aspect
        if (h > MAX_PICTURE_HEIGHT_DEG) {
            h = MAX_PICTURE_HEIGHT_DEG
            w = h * aspect
        }
        return Frame(w, h)
    }

    /** The settings, as saved. */
    data class Settings(
        val scene: Scene = Scene.NONE,
        val brightness: Int = DEFAULT_BRIGHTNESS,
        val pictureDeg: Int = DEFAULT_PICTURE_DEG,
    ) {
        val on: Boolean get() = scene != Scene.NONE
    }

    /** The log line for [s], with what the picture is when there is one. */
    fun line(s: Settings, picture: String? = null): String = when (s.scene) {
        Scene.NONE -> "wallpaper: none (the room through the glasses)"
        Scene.PICTURE -> "wallpaper: your picture (${picture ?: "none chosen"}), brightness ${s.brightness} %" +
            if (picture?.contains("panorama") == true) "" else ", ${s.pictureDeg} degrees wide"
        else -> "wallpaper: ${s.scene.key}, painted by Pocket XR, brightness ${s.brightness} %"
    }

    /**
     * Paints the scenes. Every pixel is a direction (x right, y up, z back, as the Space's own), so a scene has no seam
     * where its picture wraps and no pinch at the poles: everything is drawn from 3D noise on the direction itself.
     * The colours are sRGB, 0 to 1, before the brightness.
     */
    object Painter {
        /** The size the glasses get: wide enough for the smooth scenes (gradients and soft light; stars are apart). */
        const val WIDTH = 2048
        const val HEIGHT = 1024

        /** Bumped whenever a scene's look changes, so a phone's cached copy is painted again. */
        const val VERSION = 1

        /**
         * The least light a scene has anywhere. The glasses show black as the real room, so a scene darker than this
         * would let the room show through its ground and break the place it makes.
         */
        const val FLOOR = 0.022f

        /**
         * The colour of [scene] at the equirectangular point [u] (0 to 1, left to right, wrapping) and [v] (0 top): red,
         * green, blue, and in index 3 how clear the sky is for stars there (0 behind hills or below the horizon).
         */
        fun sample(scene: Scene, u: Float, v: Float, out: FloatArray = FloatArray(4)): FloatArray {
            val yaw = (u - 0.5f) * 2f * PI.toFloat()
            val pitch = (0.5f - v) * PI.toFloat()
            val c = cos(pitch)
            return colour(scene, sin(yaw) * c, sin(pitch), -cos(yaw) * c, out)
        }

        /** As [sample], toward the unit direction ([x], [y], [z]). Stars are not in it: see [Stars]. */
        fun colour(scene: Scene, x: Float, y: Float, z: Float, out: FloatArray = FloatArray(4)): FloatArray {
            out[3] = 0f
            when (scene) {
                Scene.NIGHT_SKY -> nightSky(x, y, z, out)
                Scene.DUSK -> dusk(x, y, z, out)
                Scene.AURORA -> aurora(x, y, z, out)
                Scene.STUDIO -> studio(x, y, z, out)
                Scene.NONE, Scene.PICTURE -> set(out, 0f, 0f, 0f)
            }
            for (k in 0..3) out[k] = out[k].coerceIn(0f, 1f)
            return out
        }

        /**
         * How many stars a scene has ([Stars]): bright ones in cells [density] to a radian, [fill] of which hold one, and
         * fainter ones as many again in a finer grid. [gain] 0: no stars.
         */
        data class StarField(val density: Float, val fill: Float, val faintDensity: Float, val faintFill: Float, val gain: Float)

        fun starField(scene: Scene): StarField = when (scene) {
            Scene.NIGHT_SKY -> StarField(160f, 0.06f, 330f, 0.014f, 1f)
            Scene.DUSK -> StarField(160f, 0.02f, 330f, 0.004f, 0.6f)
            Scene.AURORA -> StarField(160f, 0.035f, 330f, 0.008f, 0.8f)
            else -> StarField(0f, 0f, 0f, 0f, 0f)
        }

        /**
         * A scene's stars, as points: x, y, z (a unit direction) and brightness (0 to 1) for each, four floats a star.
         * They are drawn apart from the scene's picture, as points at the glasses' own resolution: in the 2048 wide
         * picture a star would be a blur 8 pixels of the glasses across. Made once a scene from the same integer hash
         * on every phone: each cell of a grid [StarField.density] to a radian whose star falls within half a cell of
         * the sphere holds one, if its hash is under [StarField.fill].
         */
        class Stars(val points: FloatArray) {
            val count: Int get() = points.size / 4
        }

        private val catalogs = java.util.concurrent.ConcurrentHashMap<Scene, Stars>()

        fun stars(scene: Scene): Stars = catalogs.getOrPut(scene) {
            val f = starField(scene)
            if (f.gain <= 0f) Stars(FloatArray(0))
            else {
                val out = ArrayList<Float>()
                starLayer(f.density, f.fill, f.gain, out)
                starLayer(f.faintDensity, f.faintFill, f.gain * 0.5f, out)
                Stars(out.toFloatArray())
            }
        }

        private fun starLayer(density: Float, fill: Float, gain: Float, out: MutableList<Float>) {
            val d = density.toInt()
            val zs = HashSet<Int>()
            for (ix in -d - 1..d) for (iy in -d - 1..d) {
                val cx = ix + 0.5f
                val cy = iy + 0.5f
                val r2 = density * density - cx * cx - cy * cy
                if (r2 < -2f * density) continue
                val zc = sqrt(max(r2, 0f))
                zs.clear()
                for (c in listOf(floor(zc).toInt(), floor(-zc).toInt())) for (k in -1..1) zs += c + k
                for (iz in zs) {
                    val h = hash(ix.toFloat(), iy.toFloat(), iz.toFloat())
                    if (h >= fill) continue
                    val px = ix + 0.2f + 0.6f * hash(iy.toFloat(), iz.toFloat(), ix + 101f)
                    val py = iy + 0.2f + 0.6f * hash(iz.toFloat(), ix.toFloat(), iy + 211f)
                    val pz = iz + 0.2f + 0.6f * hash(ix.toFloat(), iz.toFloat(), iy + 307f)
                    val len = sqrt(px * px + py * py + pz * pz)
                    if (abs(len - density) >= 0.5f) continue
                    // Brightness falls steeply: most stars are faint, a few stand out.
                    val b = ((1f - h / fill).pow(2.5f) * 0.94f + 0.06f) * gain
                    out += px / len
                    out += py / len
                    out += pz / len
                    out += b
                }
            }
        }

        /**
         * How bright a star of [brightness] looks drawn over pixels [pixelRad] radians across: its light is the same
         * however it is drawn, so spread over the big pixels of a preview it is dimmer. On the glasses, its full value.
         */
        fun starPeak(brightness: Float, pixelRad: Float): Float =
            brightness * min(1f, (STAR_POINT_RAD / max(pixelRad, 1e-6f)).let { it * it })

        /** The size up to which a star keeps its full brightness, radians (about a pixel of the glasses). */
        const val STAR_POINT_RAD = 0.0005f

        /**
         * The whole picture, [width] by [height], as opaque ARGB with 8 bits a colour, stars in: the phone's previews.
         */
        fun argb(scene: Scene, width: Int, height: Int): IntArray {
            val c = FloatArray(4)
            val rgb = FloatArray(width * height * 3)
            val clear = FloatArray(width * height)
            for (j in 0 until height) {
                val v = (j + 0.5f) / height
                for (i in 0 until width) {
                    sample(scene, (i + 0.5f) / width, v, c)
                    val o = j * width + i
                    rgb[o * 3] = c[0]
                    rgb[o * 3 + 1] = c[1]
                    rgb[o * 3 + 2] = c[2]
                    clear[o] = c[3]
                }
            }
            // Each star added to the pixel it falls in, where the sky is clear.
            val pts = stars(scene).points
            val pix = 2f * PI.toFloat() / width
            for (k in 0 until pts.size / 4) {
                val (u, v) = uv(pts[k * 4], pts[k * 4 + 1], pts[k * 4 + 2])
                val i = (u * width).toInt().coerceIn(0, width - 1)
                val j = (v * height).toInt().coerceIn(0, height - 1)
                val o = j * width + i
                val l = starPeak(pts[k * 4 + 3], pix) * clear[o]
                rgb[o * 3] += l
                rgb[o * 3 + 1] += l
                rgb[o * 3 + 2] += l * 1.05f
            }
            return IntArray(width * height) { o ->
                val i = o % width
                val j = o / width
                (0xFF shl 24) or (to8(rgb[o * 3], i, j, 0) shl 16) or (to8(rgb[o * 3 + 1], i, j, 1) shl 8) or to8(rgb[o * 3 + 2], i, j, 2)
            }
        }

        /**
         * The whole picture for the glasses, [width] by [height], 4 half floats a pixel (GL_RGBA16F): the dark gradients
         * would show steps at 8 bits on the glasses' panels. Alpha is where stars may show. [rows] as in [argb].
         */
        fun rgba16f(scene: Scene, width: Int, height: Int, rows: IntRange = 0 until height, into: ShortArray = ShortArray(width * height * 4)): ShortArray {
            val c = FloatArray(4)
            for (j in rows) {
                val v = (j + 0.5f) / height
                for (i in 0 until width) {
                    sample(scene, (i + 0.5f) / width, v, c)
                    val o = (j * width + i) * 4
                    for (k in 0..3) into[o + k] = half(c[k])
                }
            }
            return into
        }

        /** [f] (0 to 1) as an IEEE half float's bits, rounded to nearest. */
        fun half(f: Float): Short {
            if (f <= 0f) return 0
            val bits = java.lang.Float.floatToRawIntBits(f)
            val exp = ((bits ushr 23) and 0xFF) - 127 + 15
            val mant = bits and 0x7FFFFF
            return when {
                exp <= 0 -> (((mant or 0x800000) ushr (14 - exp)) + 1 ushr 1).toShort()
                exp >= 31 -> 0x7C00.toShort()
                else -> (((exp shl 10) or (mant ushr 13)) + ((mant ushr 12) and 1)).toShort()
            }
        }

        /** 8 bits with a little ordered dither, so a preview's dark gradient shows no bands. */
        private fun to8(c: Float, i: Int, j: Int, ch: Int): Int {
            val d = (BAYER[((j + ch) and 3) * 4 + ((i + ch * 2) and 3)] + 0.5f) / 16f - 0.5f
            return (c * 255f + d).roundToInt().coerceIn(0, 255)
        }

        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f)

        // ---- the scenes ----

        private fun nightSky(x: Float, y: Float, z: Float, out: FloatArray) {
            val az = atan2(x, -z)
            val hills = 0.03f + 0.07f * fbm(cos(az) * 2.2f, sin(az) * 2.2f, 4.1f, 4) + 0.015f * fbm(cos(az) * 9f, sin(az) * 9f, 1.3f, 3)
            // Sky: a faint teal glow at the horizon into a deep blue overhead.
            mix3(0.055f, 0.085f, 0.125f, 0.017f, 0.024f, 0.058f, smooth(0f, 0.7f, y), out)
            // The Milky Way: a soft band arching over the sky ahead, broken up by dark dust lanes.
            val n = dot(x, y, z, 0.8f, 0.52f, 0.3f)
            val band = exp(-n * n / 0.03f)
            val clouds = smooth(0.35f, 0.8f, fbm(x * 5f, y * 5f, z * 5f, 5))
            val lanes = 1f - 0.8f * smooth(0.5f, 0.8f, fbm(x * 13f + 3f, y * 13f, z * 13f, 4)) * exp(-n * n / 0.006f)
            val glow = band * (0.3f + 0.7f * clouds) * lanes
            add(out, 0.075f * glow, 0.072f * glow, 0.095f * glow)
            out[3] = smooth(0f, 0.25f, y - hills)
            // The hills: dark blue grey, never black (the room would show through), a lighter haze along their tops.
            if (y < hills) {
                val haze = smooth(hills - 0.04f, hills, y)
                val ground = 0.028f + 0.006f * fbm(x * 20f, y * 20f, z * 20f, 3)
                mix3(ground * 0.85f, ground * 0.95f, ground * 1.3f, 0.045f, 0.06f, 0.08f, haze * 0.7f, out)
            }
        }

        private fun dusk(x: Float, y: Float, z: Float, out: FloatArray) {
            val az = atan2(x, -z)
            // The sun went down to the left of ahead; its glow is strongest there and low.
            val toSun = cos(az + 0.9f)
            // Sky bands from the horizon up: amber, rose, violet, then night.
            when {
                y < 0.06f -> mix3(0.42f, 0.21f, 0.11f, 0.30f, 0.13f, 0.15f, smooth(0f, 0.06f, y), out)
                y < 0.25f -> mix3(0.30f, 0.13f, 0.15f, 0.11f, 0.07f, 0.17f, smooth(0.06f, 0.25f, y), out)
                else -> mix3(0.11f, 0.07f, 0.17f, 0.018f, 0.022f, 0.06f, smooth(0.25f, 0.85f, y), out)
            }
            // The horizon dims away from the sun, and its glow spreads around it.
            val away = 0.4f + 0.6f * (0.5f + 0.5f * toSun)
            val low = 1f - smooth(0f, 0.45f, y)
            scale(out, 1f - low * (1f - away))
            val g = exp(-(1f - toSun) * 7f) * exp(-max(y, 0f) * 6f)
            add(out, 0.26f * g, 0.11f * g, 0.035f * g)
            // Thin clouds, lit warm from below on the sun's side.
            if (y > 0.02f && y < 0.32f) {
                val c = fbm(cos(az) * 3f, y * 22f, sin(az) * 3f, 5)
                val cloud = smooth(0.52f, 0.72f, c) * smooth(0.02f, 0.08f, y) * (1f - smooth(0.18f, 0.32f, y))
                val lit = 0.5f + 0.5f * toSun
                mix3(out[0], out[1], out[2], 0.26f + 0.22f * lit, 0.10f + 0.07f * lit, 0.12f, cloud * 0.5f, out)
            }
            out[3] = smooth(0.35f, 0.85f, y)
            // Three layers of hills, the far ones in haze, the middle one lit a little along its top.
            val far = 0.04f + 0.08f * fbm(cos(az) * 2.4f, sin(az) * 2.4f, 2.0f, 5)
            val mid = 0.01f + 0.06f * fbm(cos(az) * 3.6f, sin(az) * 3.6f, 7.0f, 5)
            val near = -0.03f + 0.06f * fbm(cos(az) * 5.3f, sin(az) * 5.3f, 11.0f, 4)
            // The hills keep some light away from the sun too: black would let the room show through.
            val lit = max(away, 0.75f)
            if (y < far) mix3(out[0], out[1], out[2], 0.10f * lit, 0.055f * lit, 0.10f * lit, 0.85f, out)
            if (y < mid) {
                val rim = smooth(mid - 0.008f, mid, y)
                mix3(0.045f * lit, 0.028f * lit, 0.058f * lit, 0.08f * lit, 0.045f * lit, 0.07f * lit, rim, out)
            }
            if (y < near) {
                val ground = 0.024f + 0.008f * smooth(-0.4f, near, y)
                set(out, ground * 1.1f, ground * 0.8f, ground * 1.35f)
            }
        }

        private fun aurora(x: Float, y: Float, z: Float, out: FloatArray) {
            val az = atan2(x, -z)
            // Polar night: deep blue overhead, a cold glow at the horizon.
            mix3(0.03f, 0.055f, 0.075f, 0.012f, 0.022f, 0.045f, smooth(0f, 0.75f, abs(y)), out)
            out[3] = if (y > 0f) smooth(0f, 0.15f, y) else 0f
            // Curtains, mostly ahead: a bright lower edge that wavers, rays rising from it, green into violet at the top.
            val ahead = smooth(-0.6f, 0.4f, cos(az))
            val a = curtain(az, abs(y)) * ahead * if (y > 0f) 1f else 0.3f
            if (a > 0f) {
                val top = smooth(0.06f, 0.45f, abs(y) - curtainBase(az))
                add(out, (0.05f + 0.20f * top) * a, (0.50f - 0.40f * top) * a, (0.22f + 0.12f * top) * a)
                // A bright aurora outshines the faint stars behind it.
                out[3] *= 1f - 0.7f * min(a * 2f, 1f)
            }
            // The snowfield: a dim, bluish white with soft drifts, holding the aurora's light near the horizon.
            if (y < 0f) {
                val snow = 0.04f + 0.018f * fbm(x * 6f, 0f, z * 6f, 4)
                mix3(out[0], out[1], out[2], snow * 0.85f, snow, snow * 1.25f, 0.75f - 0.35f * smooth(0f, 0.08f, -y), out)
            }
        }

        private fun curtainBase(az: Float): Float = 0.1f + 0.045f * sin(az * 2.3f + 1.1f) + 0.09f * fbm(cos(az) * 3.5f, sin(az) * 3.5f, 3.3f, 4)

        private fun curtain(az: Float, y: Float): Float {
            val h = y - curtainBase(az)
            if (h < -0.12f) return 0f
            // Sharp and brightest at the lower edge, fading slowly upward, with a faint glow just under it.
            val edge = smooth(-0.012f, 0.004f, h) + if (h < 0f) 0.12f * exp(h * 30f) else 0f
            val body = exp(-max(h, 0f) * 3.2f)
            // Vertical rays: narrow streaks of different strength along the curtain.
            val r = fbm(cos(az) * 60f, sin(az) * 60f, 5.5f, 2)
            val rays = 0.25f + 1.2f * r * r
            // The curtain folds: its strength rises and falls along it.
            val fold = 0.4f + 0.6f * smooth(0.3f, 0.7f, fbm(cos(az) * 4f, sin(az) * 4f, 9.1f, 3))
            return edge * body * rays * fold
        }

        private fun studio(x: Float, y: Float, z: Float, out: FloatArray) {
            val az = atan2(x, -z)
            val front = 0.5f + 0.5f * cos(az)
            // One smooth curve from floor to wall to ceiling (a cyclorama: no corner line), lightest just below eye level,
            // where the floor turns up, and toward ahead.
            val t = (y + 0.06f) / 0.38f
            val l = 0.022f + 0.06f * exp(-t * t) * (0.4f + 0.6f * front * front)
            set(out, l * 1.06f, l, l * 0.94f)
            // A warm spotlight on the wall ahead, above and behind the windows.
            val spot = exp(-(1f - dot(x, y, z, 0f, 0.3f, -1f)) / 0.012f)
            add(out, 0.05f * spot, 0.04f * spot, 0.028f * spot)
            // Its pool on the floor ahead.
            if (y < 0f) {
                val dz = z + 0.6f
                val pool = exp(-(x * x + dz * dz) / 0.07f) * smooth(0.15f, 0.5f, -y)
                add(out, 0.035f * pool, 0.027f * pool, 0.018f * pool)
            }
            // A very slight grain, so the large dark areas do not look flat.
            val g = (fbm(x * 90f, y * 90f, z * 90f, 2) - 0.5f) * 0.005f
            add(out, g, g, g)
        }

        // ---- stars and noise ----

        /** 0 to 1 from integer-valued [x], [y] and [z] (an integer hash: cheap, and the same on every phone and GPU). */
        fun hash(x: Float, y: Float, z: Float): Float {
            var h = x.toInt() * 374761393 + y.toInt() * 668265263 + z.toInt() * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }

        private fun noise(x: Float, y: Float, z: Float): Float {
            val ix = floor(x)
            val iy = floor(y)
            val iz = floor(z)
            val fx = x - ix
            val fy = y - iy
            val fz = z - iz
            val ux = fx * fx * (3f - 2f * fx)
            val uy = fy * fy * (3f - 2f * fy)
            val uz = fz * fz * (3f - 2f * fz)
            val x00 = lerp(hash(ix, iy, iz), hash(ix + 1f, iy, iz), ux)
            val x10 = lerp(hash(ix, iy + 1f, iz), hash(ix + 1f, iy + 1f, iz), ux)
            val x01 = lerp(hash(ix, iy, iz + 1f), hash(ix + 1f, iy, iz + 1f), ux)
            val x11 = lerp(hash(ix, iy + 1f, iz + 1f), hash(ix + 1f, iy + 1f, iz + 1f), ux)
            return lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz)
        }

        /** Fractal noise, 0 to 1, over [octaves]. */
        fun fbm(x: Float, y: Float, z: Float, octaves: Int): Float {
            var sum = 0f
            var amp = 0.5f
            var f = 1f
            var norm = 0f
            repeat(octaves) {
                sum += amp * noise(x * f, y * f, z * f)
                norm += amp
                amp *= 0.5f
                f *= 2.03f
            }
            return sum / norm
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun smooth(e0: Float, e1: Float, v: Float): Float {
            val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun dot(x: Float, y: Float, z: Float, a: Float, b: Float, c: Float): Float {
            val l = sqrt(a * a + b * b + c * c)
            return (x * a + y * b + z * c) / l
        }

        private fun mix3(r0: Float, g0: Float, b0: Float, r1: Float, g1: Float, b1: Float, t: Float, out: FloatArray) {
            out[0] = lerp(r0, r1, t)
            out[1] = lerp(g0, g1, t)
            out[2] = lerp(b0, b1, t)
        }

        private fun add(out: FloatArray, r: Float, g: Float, b: Float) {
            out[0] += r
            out[1] += g
            out[2] += b
        }

        private fun set(out: FloatArray, r: Float, g: Float, b: Float) {
            out[0] = r
            out[1] = g
            out[2] = b
        }

        private fun scale(out: FloatArray, k: Float) {
            out[0] *= k
            out[1] *= k
            out[2] *= k
        }

        /** The equirectangular point (u, v) of the unit direction ([x], [y], [z]); [sample]'s inverse. */
        fun uv(x: Float, y: Float, z: Float): Pair<Float, Float> {
            val yaw = atan2(x, -z)
            val pitch = asin(y.coerceIn(-1f, 1f))
            return Pair(yaw / (2f * PI.toFloat()) + 0.5f, 0.5f - pitch / PI.toFloat())
        }
    }
}
