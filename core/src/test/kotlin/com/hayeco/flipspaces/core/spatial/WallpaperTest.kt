package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** 1.6.37 (Crr66: a wallpaper behind the windows, "so you have a virtual room"). */
class WallpaperTest {
    private val painted = Wallpaper.Scene.entries.filter { it.painted }

    @Test fun offUntilChosenAndSavedByKey() {
        assertFalse(Wallpaper.Settings().on)
        Wallpaper.Scene.entries.forEach { assertEquals(it, Wallpaper.Scene.of(it.key)) }
        assertEquals(Wallpaper.Scene.NONE, Wallpaper.Scene.of("gone in a later version"))
        assertEquals(Wallpaper.Scene.NONE, Wallpaper.Scene.of(null))
        assertEquals(Wallpaper.Scene.entries.toSet(), Wallpaper.Scene.SHOWN.toSet())
    }

    @Test fun noSeamWhereThePictureWraps() {
        // The left and right edges are the same direction behind you: the colours must meet.
        for (scene in painted) for (v in listOf(0.2f, 0.45f, 0.5f, 0.55f, 0.8f)) {
            val a = Wallpaper.Painter.sample(scene, 0.0001f, v)
            val b = Wallpaper.Painter.sample(scene, 0.9999f, v)
            for (c in 0..2) assertEquals("$scene at v $v", a[c], b[c], 0.02f)
        }
    }

    @Test fun noPinchAtThePoles() {
        // Every u at the top row is (nearly) the same point overhead.
        for (scene in painted) {
            val ref = Wallpaper.Painter.sample(scene, 0f, 0.0005f)
            for (u in listOf(0.1f, 0.33f, 0.5f, 0.77f)) {
                val c = Wallpaper.Painter.sample(scene, u, 0.0005f)
                for (k in 0..2) assertEquals("$scene overhead", ref[k], c[k], 0.03f)
            }
        }
    }

    @Test fun darkEnoughThatTheWindowsStayBrightest() {
        // At full brightness the scenes' broad areas stay dim; only stars may be bright, and they are tiny.
        for (scene in painted) {
            val px = Wallpaper.Painter.argb(scene, 256, 128)
            var sum = 0.0
            var bright = 0
            for (p in px) {
                val l = (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255.0
                sum += l
                if (l > 0.5) bright++
            }
            val mean = sum / px.size
            assertTrue("$scene mean light $mean", mean < 0.12)
            assertTrue("$scene bright pixels $bright", bright < px.size / 200)
        }
    }

    @Test fun eachSceneLooksItsOwn() {
        // Not all the same picture: each scene's average colour differs from every other's.
        val means = painted.associateWith { s ->
            val px = Wallpaper.Painter.argb(s, 128, 64)
            FloatArray(3) { c -> px.sumOf { (it shr (16 - 8 * c)) and 255 }.toFloat() / px.size }
        }
        for (a in painted) for (b in painted) if (a != b) {
            val d = (0..2).maxOf { abs(means.getValue(a)[it] - means.getValue(b)[it]) }
            assertTrue("$a and $b look alike ($d)", d > 1.5f)
        }
    }

    @Test fun halfFloatsForTheGlasses() {
        assertEquals(0x3C00, Wallpaper.Painter.half(1f).toInt() and 0xFFFF)
        assertEquals(0x3800, Wallpaper.Painter.half(0.5f).toInt() and 0xFFFF)
        assertEquals(0, Wallpaper.Painter.half(0f).toInt())
        // A dark value keeps its precision (the reason for half floats): within 0.1 % of itself.
        val v = 0.0123f
        val h = Wallpaper.Painter.half(v).toInt() and 0xFFFF
        val back = Math.scalb(1f + (h and 0x3FF) / 1024f, (h shr 10) - 15)
        assertEquals(v, back, v * 0.001f)
        val p = Wallpaper.Painter.rgba16f(Wallpaper.Scene.DUSK, 64, 32)
        assertEquals(64 * 32 * 4, p.size)
    }

    @Test fun neverBlackSoTheRoomDoesNotShowThrough() {
        // Black is see-through on the glasses: every scene keeps a little light everywhere, its ground too.
        for (scene in painted) {
            val c = FloatArray(4)
            for (j in 0 until 64) for (i in 0 until 128) {
                Wallpaper.Painter.sample(scene, (i + 0.5f) / 128, (j + 0.5f) / 64, c)
                val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
                assertTrue("$scene at $i,$j is $l", l > Wallpaper.Painter.FLOOR * 0.75f)
            }
        }
    }

    @Test fun starsAreAFewHundredPointsInView() {
        // The night sky ahead, a view about 50 by 28 degrees: hundreds of stars, a few tens of them bright.
        val pts = Wallpaper.Painter.stars(Wallpaper.Scene.NIGHT_SKY).points
        var inView = 0
        var bright = 0
        for (k in 0 until pts.size / 4) {
            val x = pts[k * 4]
            val y = pts[k * 4 + 1]
            val z = pts[k * 4 + 2]
            if (z >= 0f) continue
            if (kotlin.math.abs(x / -z) < 0.466f && kotlin.math.abs(y / -z) < 0.25f) {
                inView++
                if (pts[k * 4 + 3] > 0.5f) bright++
            }
        }
        assertTrue("stars in view $inView", inView in 200..3000)
        assertTrue("bright stars in view $bright", bright in 10..400)
        // Every star is a unit direction with a brightness, and the scenes without stars have none.
        for (k in 0 until pts.size / 4) {
            val l = pts[k * 4] * pts[k * 4] + pts[k * 4 + 1] * pts[k * 4 + 1] + pts[k * 4 + 2] * pts[k * 4 + 2]
            assertEquals(1f, l, 1e-4f)
            assertTrue(pts[k * 4 + 3] in 0f..1f)
        }
        assertEquals(0, Wallpaper.Painter.stars(Wallpaper.Scene.STUDIO).count)
        // Spread over a preview's big pixels a star is dimmer; on the glasses, its full value.
        assertEquals(0.8f, Wallpaper.Painter.starPeak(0.8f, 0.0004f), 1e-6f)
        assertTrue(Wallpaper.Painter.starPeak(0.8f, 0.02f) < 0.001f)
    }

    @Test fun panoramasAndFramedPictures() {
        assertTrue(Wallpaper.isPanorama(8000, 4000))
        assertTrue(Wallpaper.isPanorama(4096, 2048))
        assertFalse(Wallpaper.isPanorama(4000, 3000))
        assertFalse(Wallpaper.isPanorama(1920, 1080))
        // A 16:9 photo at the default width is a wide screen ahead.
        val f = Wallpaper.frame(1920, 1080, Wallpaper.DEFAULT_PICTURE_DEG)
        assertEquals(110f, f.widthDeg, 0.01f)
        assertEquals(110f * 9f / 16f, f.heightDeg, 0.01f)
        // A tall portrait is held to the height limit and narrows instead.
        val tall = Wallpaper.frame(1000, 2000, 120)
        assertEquals(Wallpaper.MAX_PICTURE_HEIGHT_DEG, tall.heightDeg, 0.01f)
        assertEquals(60f, tall.widthDeg, 0.01f)
        assertEquals(Wallpaper.MAX_PICTURE_DEG, Wallpaper.pictureDeg(500))
        assertEquals(Wallpaper.MIN_BRIGHTNESS, Wallpaper.brightness(0))
    }

    @Test fun uvIsTheInverseOfTheSample() {
        for ((u, v) in listOf(0.5f to 0.5f, 0.25f to 0.4f, 0.8f to 0.7f)) {
            val yaw = (u - 0.5f) * 2f * Math.PI.toFloat()
            val pitch = (0.5f - v) * Math.PI.toFloat()
            val c = kotlin.math.cos(pitch)
            val (u2, v2) = Wallpaper.Painter.uv(kotlin.math.sin(yaw) * c, kotlin.math.sin(pitch), -kotlin.math.cos(yaw) * c)
            assertEquals(u, u2, 1e-4f)
            assertEquals(v, v2, 1e-4f)
        }
        // Ahead (-z) is the middle of the picture.
        val (u, v) = Wallpaper.Painter.uv(0f, 0f, -1f)
        assertEquals(0.5f, u, 1e-5f)
        assertEquals(0.5f, v, 1e-5f)
    }

    /**
     * Not a check: with PXR_WALLPAPER_PNG set to a folder, each scene is saved there as a PNG (the whole sphere, and a
     * crop of what the glasses would show ahead) to look at by eye.
     */
    @Test fun savePreviewsWhenAsked() {
        val dir = System.getenv("PXR_WALLPAPER_PNG") ?: return
        java.io.File(dir).mkdirs()
        val w = Wallpaper.Painter.WIDTH
        val h = Wallpaper.Painter.HEIGHT
        for (scene in painted) {
            val px = Wallpaper.Painter.argb(scene, w, h)
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "${scene.key}.png"))
            // What the glasses see looking ahead and a little up: a pinhole view 50 by 28 degrees, brightened 3x to judge
            // the dark scenes on a monitor.
            val vw = 960
            val vh = 540
            val view = java.awt.image.BufferedImage(vw, vh, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val tx = kotlin.math.tan(Math.toRadians(25.0)).toFloat()
            val ty = kotlin.math.tan(Math.toRadians(14.0)).toFloat()
            val pitch = Math.toRadians(8.0).toFloat()
            val c = FloatArray(4)
            val buf = FloatArray(vw * vh * 3)
            val clear = FloatArray(vw * vh)
            for (j in 0 until vh) for (i in 0 until vw) {
                val x = ((i + 0.5f) / vw * 2f - 1f) * tx
                val y = (1f - (j + 0.5f) / vh * 2f) * ty
                // Pitch the ray up about x.
                val yy = y * kotlin.math.cos(pitch) + kotlin.math.sin(pitch)
                val zz = -kotlin.math.cos(pitch) + y * kotlin.math.sin(pitch)
                val l = kotlin.math.sqrt(x * x + yy * yy + zz * zz)
                Wallpaper.Painter.colour(scene, x / l, yy / l, zz / l, c)
                val o = j * vw + i
                buf[o * 3] = c[0]; buf[o * 3 + 1] = c[1]; buf[o * 3 + 2] = c[2]
                clear[o] = c[3]
            }
            // The stars as the glasses draw them: soft points at their exact place.
            val pts = Wallpaper.Painter.stars(scene).points
            val pixRad = 2f * tx / vw
            for (k in 0 until pts.size / 4) {
                val sx = pts[k * 4]
                val sy = pts[k * 4 + 1]
                val sz = pts[k * 4 + 2]
                // Into the view's frame (pitched up).
                val vy = sy * kotlin.math.cos(pitch) - sz * kotlin.math.sin(pitch)
                val vz = sz * kotlin.math.cos(pitch) + sy * kotlin.math.sin(pitch)
                if (vz >= 0f) continue
                val fx = (sx / -vz / tx + 1f) / 2f * vw
                val fy = (1f - vy / -vz / ty) / 2f * vh
                val peak = Wallpaper.Painter.starPeak(pts[k * 4 + 3], pixRad)
                for (j in fy.toInt() - 2..fy.toInt() + 2) for (i in fx.toInt() - 2..fx.toInt() + 2) {
                    if (i !in 0 until vw || j !in 0 until vh) continue
                    val dx = i + 0.5f - fx
                    val dy = j + 0.5f - fy
                    val o = j * vw + i
                    val e = peak * kotlin.math.exp(-(dx * dx + dy * dy) / 0.6f) * clear[o]
                    buf[o * 3] += e; buf[o * 3 + 1] += e; buf[o * 3 + 2] += e * 1.05f
                }
            }
            fun ch(v: Float) = (max(0f, v) * 3f * 255f).toInt().coerceIn(0, 255)
            for (o in 0 until vw * vh) view.setRGB(o % vw, o / vw, (ch(buf[o * 3]) shl 16) or (ch(buf[o * 3 + 1]) shl 8) or ch(buf[o * 3 + 2]))
            javax.imageio.ImageIO.write(view, "png", java.io.File(dir, "${scene.key}-ahead-x3.png"))
        }
    }
}
