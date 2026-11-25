package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 0.5.20 (Mike: "when I select apps in the app launcher, the cursor is drawn behind the launcher
 * panel"): the cursor is drawn last in each eye ([SpaceLayer.ORDER]) and placed in the room in front
 * of whatever it covers ([CursorDepth]), so in side by side 3D both eyes see it on top too.
 */
class CursorDepthTest {
    private val ipdM = 0.063f
    private val eyes = listOf(Vec3(-ipdM / 2f, 0f, 0f), Vec3(ipdM / 2f, 0f, 0f))
    private val launcherM = SpaceLauncher.DISTANCE_M

    /** The distance from [eye] through [p] to the sphere of [radius] round the head, and to [p] itself. */
    private fun throughEye(eye: Vec3, p: Vec3, radius: Float): Pair<Float, Float> {
        val dir = (p - eye).normalized()
        val toSphere = CursorDepth.sphere(Ray(eye, dir), radius)!!
        return toSphere to (p - eye).length
    }

    @Test
    fun theCursorIsDrawnAfterEveryPanel() {
        val order = SpaceLayer.ORDER
        val cursor = order.indexOf(SpaceLayer.CURSOR)
        assertEquals("the cursor is drawn last", order.size - 1, cursor)
        for (panel in listOf(SpaceLayer.WINDOWS, SpaceLayer.CONTROL_PANEL, SpaceLayer.LAUNCHER, SpaceLayer.HOME, SpaceLayer.EDGE_HINT)) {
            assertTrue("$panel before the cursor", order.indexOf(panel) < cursor)
        }
        // The launcher over the windows, as before.
        assertTrue(order.indexOf(SpaceLayer.WINDOWS) < order.indexOf(SpaceLayer.LAUNCHER))
        assertEquals(SpaceLayer.values().size, order.toSet().size)
    }

    /** Mike's case: the launcher open, the trackpad reticle (the head's ray) over a tile; nothing hovered. */
    @Test
    fun overTheLauncherTheReticleIsInFrontOfItInBothEyes() {
        val windows = listOf(SpacePanel(1, "Twitch", "tv", "a", 0f, 0f, distanceM = 1.4f))
        for ((yaw, pitch) in listOf(0f to 0f, 12f to -8f, -20f to 5f)) {
            val ray = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))
            // 0.5.19: no hover, so 2.5 m out: behind the 1.0 m launcher.
            val old = ray.at(ReticleAnim.DEFAULT_DISTANCE_M - 0.005f)
            val new = CursorDepth.point(ray, hoverM = null, claimM = launcherM, openM = launcherM, panels = windows, defaultM = ReticleAnim.DEFAULT_DISTANCE_M)
            println("reticle over the launcher at %.0f, %.0f: 0.5.19 %.3f m from the eyes, 0.5.20 %.3f m (launcher %.2f m)".format(Locale.ROOT, yaw, pitch, old.length, new.length, launcherM))
            assertTrue(old.length > launcherM)
            assertTrue(new.length < launcherM)
            for (eye in eyes) {
                val (sphere, cursor) = throughEye(eye, new, launcherM)
                assertTrue("eye $eye: the cursor at $cursor before the launcher at $sphere", cursor < sphere)
            }
            // Its direction from the eyes is the ray's: it is where the tile under it is.
            assertEquals(1f, new.normalized() dot ray.dir.normalized(), 1e-5f)
        }
    }

    @Test
    fun aHandsDotOverTheLauncherIsOnTheLauncherAndInFront() {
        // The right hand 25 cm right, 30 cm down and 30 cm out, pointing up and left at a tile.
        val hand = Vec3(0.25f, -0.3f, -0.3f)
        val tile = Vec3.direction(-5f, 5f) * launcherM
        val ray = Ray(hand, (tile - hand).normalized())
        val p = CursorDepth.point(ray, hoverM = null, claimM = launcherM, openM = launcherM, panels = emptyList(), defaultM = CursorDepth.HAND_DEFAULT_M)
        val old = ray.at(CursorDepth.HAND_DEFAULT_M - 0.005f)
        println("hand dot over the launcher: 0.5.19 %.3f m from the eyes, %.1f deg off the tile; 0.5.20 %.3f m, %.2f deg off".format(
            Locale.ROOT, old.length, Math.toDegrees(kotlin.math.acos((old.normalized() dot tile.normalized()).toDouble())), p.length,
            Math.toDegrees(kotlin.math.acos((p.normalized() dot tile.normalized()).toDouble().coerceAtMost(1.0))),
        ))
        assertTrue(p.length < launcherM)
        // On the tile it points at, as seen from the eyes (0.5.19: degrees off it).
        assertTrue((p.normalized() dot tile.normalized()) > kotlin.math.cos(Math.toRadians(0.5)).toFloat())
        for (eye in eyes) {
            val (sphere, cursor) = throughEye(eye, p, launcherM)
            assertTrue(cursor < sphere)
        }
    }

    @Test
    fun overAWindowTheCursorIsOnItsSurfaceFlatCurvedOrWrapped() {
        for (curve in listOf(0f, 0.5f, 1f)) {
            val w = SpacePanel(1, "Twitch", "tv", "a", 10f, 0f, distanceM = 1.4f, curve = curve)
            for (u in listOf(0.1f, 0.5f, 0.9f)) {
                val target = PanelCurve.vertex(PanelCurve.rect(w, u - 0.01f, 0.49f, u + 0.01f, 0.51f), 0f, 0f)
                val ray = Ray(Vec3.ZERO, target.normalized())
                val hit = PanelHitTester.hit(ray, w)!!
                val p = CursorDepth.point(ray, hit.distance, null, null, listOf(w), ReticleAnim.DEFAULT_DISTANCE_M)
                assertEquals("curve $curve u $u", hit.distance - CursorDepth.IN_FRONT_M, p.length, 1e-4f)
            }
        }
        // The whole Space wrapped round the head: every window at one radius, the cursor on it.
        val wrapped = SpaceLayout(listOf(
            SpacePanel(1, "A", "a", "a", -30f, 0f, distanceM = 1.4f),
            SpacePanel(2, "B", "b", "a", 25f, 0f, distanceM = 2f),
        )).let { SpaceWrap.on(it) }
        for (pnl in wrapped.panels) {
            val ray = Ray(Vec3.ZERO, pnl.centreDir)
            val hit = PanelHitTester.nearest(ray, wrapped.panels)!!
            val p = CursorDepth.point(ray, hit.distance, null, null, wrapped.panels, ReticleAnim.DEFAULT_DISTANCE_M)
            assertTrue(p.length < hit.distance)
        }
    }

    @Test
    fun aHandsDotOverNothingIsNeverBehindAWindowTheEyesSeeItOver() {
        // A near window (0.6 m) the eyes look through to where a hand ray ends 2 m out, past its side.
        val near = SpacePanel(1, "Clock", "c", "a", 0f, 0f, distanceM = 0.6f, widthDeg = 30f, heightDeg = 20f)
        val hand = Vec3(0.3f, -0.25f, -0.2f)
        // The hand points past the window's right edge; 2 m along it is behind the window from the eyes.
        val end = Vec3.direction(5f, 2f) * 2f
        val ray = Ray(hand, (end - hand).normalized())
        assertTrue(PanelHitTester.hit(ray, near) == null)
        val p = CursorDepth.point(ray, null, null, null, listOf(near), CursorDepth.HAND_DEFAULT_M)
        val seen = PanelHitTester.hit(Ray(Vec3.ZERO, p.normalized()), near)!!
        assertTrue("cursor ${p.length} m, window ${seen.distance} m", p.length < seen.distance)
    }

    @Test
    fun overTheHomeTileTheCursorIsInFrontOfIt() {
        val home = SpaceLauncher.DISTANCE_M + SpaceLauncher.BACKDROP_BEHIND_M
        val ray = Ray(Vec3.ZERO, Vec3.direction(0f, -30f))
        val p = CursorDepth.point(ray, null, claimM = home, openM = null, panels = emptyList(), defaultM = ReticleAnim.DEFAULT_DISTANCE_M)
        assertEquals(home - CursorDepth.IN_FRONT_M, p.length, 1e-4f)
    }
}
