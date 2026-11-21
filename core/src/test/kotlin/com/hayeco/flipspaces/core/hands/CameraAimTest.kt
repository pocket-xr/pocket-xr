package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.DEG
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/**
 * 0.5.10: "with my RIGHT hand the ray cursor sits to the RIGHT of where my hand is aiming; with
 * my LEFT hand it sits to the LEFT" (Mike, 0.5.9 on the Aura). Hands point at dots spread over the
 * view, left and right, up and down: each hand sits on the line from the eyes to its dot, 0.45 m
 * out, and the glasses' camera (1 cm above and in front of the eyes) sees it. The cursor is where the
 * hand ray meets the dot's distance, as seen from the eyes.
 */
class CameraAimTest {
    /** Where [camera] (the real one) sees a head frame point, as a normalised image point. */
    private fun project(camera: CameraModel, p: Vec3): Landmark {
        val v = p - camera.offset
        val halfX = tan(camera.hfovDeg * DEG / 2f)
        val halfY = tan(camera.vfovDeg * DEG / 2f)
        return Landmark(0.5f + (v.x / -v.z) / (2f * halfX), 0.5f - (v.y / -v.z) / (2f * halfY))
    }

    /** A hand whose every landmark is at [p]: its steady anchor is exactly there. */
    private fun handAt(p: Landmark) = HandFrame(List(21) { p }, 0L)

    private data class Miss(val target: Angles, val yawErr: Float, val pitchErr: Float)

    /** For every dot: the cursor's yaw and pitch minus the dot's, degrees, with [assumed] reading a [real] camera. */
    private fun misses(real: CameraModel, assumed: CameraModel, side: HandSide): List<Miss> {
        val out = mutableListOf<Miss>()
        for (yaw in listOf(-25f, -15f, -5f, 0f, 5f, 15f, 25f)) {
            for (pitch in listOf(-14f, 0f, 14f)) {
                val dot = Vec3.direction(yaw, pitch) * DOT_M
                val hand = Vec3.direction(yaw, pitch) * HAND_M
                val b = HandRayBuilder(side, assumed)
                val r = b.build(handAt(project(real, hand)), 0L)
                // Where the ray reaches the dot's distance, seen from the eyes.
                val t = solveSphere(r.origin, r.dir, DOT_M)
                val seen = Angles.of(r.origin + r.dir * t)
                val want = Angles.of(dot)
                out += Miss(want, wrapDegrees(seen.yawDeg - want.yawDeg), seen.pitchDeg - want.pitchDeg)
            }
        }
        return out
    }

    private fun solveSphere(o: Vec3, d: Vec3, radius: Float): Float {
        val b = 2f * (o dot d)
        val c = (o dot o) - radius * radius
        return (-b + kotlin.math.sqrt(b * b - 4f * c)) / 2f
    }

    @Test
    fun theOld80DegreeCameraPutsEachHandsCursorOutward() {
        val real = CameraModel()
        val old = CameraModel(hfovDeg = CameraModel.OLD_HFOV_DEG, vfovDeg = CameraModel.verticalFor(CameraModel.OLD_HFOV_DEG, 16f / 9f))
        for (side in HandSide.entries) {
            val m = misses(real, old, side)
            val right = m.filter { it.target.yawDeg >= 15f }
            val left = m.filter { it.target.yawDeg <= -15f }
            // Right of the dot on the right, left of it on the left, more the further out: a gain error.
            assertTrue(right.all { it.yawErr > 1.5f })
            assertTrue(left.all { it.yawErr < -1.5f })
            val at25 = m.filter { abs(it.target.yawDeg) == 25f }.maxOf { abs(it.yawErr) }
            val at5 = m.filter { abs(it.target.yawDeg) == 5f }.maxOf { abs(it.yawErr) }
            assertTrue("grows outward: $at5 at 5, $at25 at 25", at25 > at5 * 3f)
            println("80 degree camera model, $side hand: %.1f deg off at 25 deg, %.1f at 5 deg".format(at25, at5))
        }
    }

    @Test
    fun theAuraDefaultsLandOnTheDotsForBothHands() {
        val real = CameraModel()
        for (side in HandSide.entries) {
            val m = misses(real, CameraModel(), side)
            val worst = m.maxOf { hypot(it.yawErr, it.pitchErr) }
            println("Aura defaults, $side hand: worst miss %.2f deg over %d dots".format(worst, m.size))
            // Left and right: on the dots. Up and down: the camera sits 1 cm above the eyes, which a
            // hand at 0.45 m and a dot at 1.5 m see about 0.9 degrees apart; no gain error either way.
            val yaw = m.maxOf { abs(it.yawErr) }
            println("Aura defaults, $side hand: worst left-right miss %.2f deg".format(yaw))
            assertTrue("$side: yaw $yaw deg", yaw < 0.5f)
            assertTrue("$side: $worst deg", worst < 1.2f)
            val pitchSpread = m.maxOf { it.pitchErr } - m.minOf { it.pitchErr }
            assertTrue("$side: pitch error spread $pitchSpread (a gain error would grow with pitch)", pitchSpread < 0.6f)
        }
    }

    @Test
    fun theGeometryLineSaysWhatIsUsed() {
        val line = CameraModel().line()
        assertTrue(line, line.startsWith("camera 68 by 42 degrees"))
    }

    private companion object {
        const val DOT_M = 1.5f
        const val HAND_M = 0.45f
    }
}
