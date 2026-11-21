package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.1: the camera ray and the toned down shoulder ray; 0.5.2: the default aims through the
 * stable pinch point, so the geometry tests below ask for the pinch point itself.
 */
class CameraRayTest {
    private val camera = CameraModel(offset = Vec3.ZERO)
    private val pinchAim = HandRaySettings(aim = AimPoint.PINCH)

    /** A hand whose pinch point (thumb and index tips) sits at image point ([x], [y]). */
    private fun pinchAt(x: Float, y: Float, palm: Float = 0.1f, ratio: Float = 0.6f): HandFrame {
        val hands = SyntheticHands(1920, 1080, AirBox(0f, 0f, 1f, 1f), size = palm)
        val h = hands.hand(Point(960f, 540f), ratio, 0)
        val p = h.pinchPoint
        return HandFrame(h.landmarks.map { Landmark(it.x + x - p.x, it.y + y - p.y, it.z) }, 0, h.rightHand, h.score, h.world)
    }

    @Test
    fun theDefaultIsTheCameraRayThroughTheSteadyPinchPoint() {
        // 0.5.9: the steady pinch point, the stable pinch point's spot with less jitter (SteadyAnchor).
        val s = HandRaySettings()
        assertEquals(RayOrigin.CAMERA, s.origin)
        assertEquals(AimPoint.STEADY_PINCH, s.aim)
        assertTrue(s.line(), s.line().startsWith("camera ray through the steady pinch point"))
    }

    @Test
    fun thePinchPointIsHalfwayBetweenTheThumbAndIndexTips() {
        val h = pinchAt(0.4f, 0.3f)
        val t = h[HandFrame.THUMB_TIP]
        val i = h[HandFrame.INDEX_TIP]
        assertEquals((t.x + i.x) / 2f, h.pinchPoint.x, 1e-6f)
        assertEquals((t.y + i.y) / 2f, h.pinchPoint.y, 1e-6f)
        assertEquals(h.pinchPoint, h.point(AimPoint.PINCH))
    }

    @Test
    fun theCameraRayPointsWhereTheCameraSeesThePinch() {
        val b = HandRayBuilder(HandSide.RIGHT, camera, pinchAim)
        for ((x, y) in listOf(0.5f to 0.5f, 0.7f to 0.4f, 0.25f to 0.8f)) {
            val r = b.build(pinchAt(x, y), 0)
            val seen = Angles.of(camera.direction(Landmark(x, y)))
            assertEquals(seen.yawDeg, r.angles.yawDeg, 0.01f)
            assertEquals(seen.pitchDeg, r.angles.pitchDeg, 0.01f)
            assertEquals(camera.offset, r.origin)
        }
    }

    @Test
    fun handSizeDoesNotMoveTheCameraRay() {
        // 0.5.0's shoulder ray swung by up to 50 degrees as the size based depth moved between
        // its clamps; the camera ray has no depth in it.
        val near = HandRayBuilder(HandSide.RIGHT, camera, pinchAim).build(pinchAt(0.6f, 0.65f, palm = 0.3f), 0)
        val far = HandRayBuilder(HandSide.RIGHT, camera, pinchAim).build(pinchAt(0.6f, 0.65f, palm = 0.05f), 0)
        assertEquals(near.angles.yawDeg, far.angles.yawDeg, 0.01f)
        assertEquals(near.angles.pitchDeg, far.angles.pitchDeg, 0.01f)
    }

    @Test
    fun leftAndRightHandsAtOnePlaceAimAtOnePlace() {
        // 0.5.0 swung each hand's ray inward across the body; the camera ray has no side.
        val r = HandRayBuilder(HandSide.RIGHT, camera).build(pinchAt(0.3f, 0.6f), 0)
        val l = HandRayBuilder(HandSide.LEFT, camera).build(pinchAt(0.3f, 0.6f), 0)
        assertEquals(r.angles.yawDeg, l.angles.yawDeg, 1e-4f)
        assertTrue("left of the picture stays left: ${l.angles.yawDeg}", l.angles.yawDeg < 0f)
    }

    @Test
    fun aHandBelowTheMiddleAimsBelowTheMiddle() {
        // Mike's 0.5.0 pinches aimed 10 to 62 degrees above the hand; held low it must aim low.
        val r = HandRayBuilder(HandSide.RIGHT, camera).build(pinchAt(0.5f, 0.75f), 0)
        assertTrue("pitch ${r.angles.pitchDeg}", r.angles.pitchDeg < -5f)
    }

    @Test
    fun theCursorIsNearerTheCentreThanThe048Mapping() {
        // 0.4.8's air box (0.15 to 0.85) over the display and the camera ray through the Space's
        // 70 degree view put a hand at the same place in the picture within a few percent with
        // 0.5.0's 80 degree camera. 0.5.10's 68 degree camera (CameraAimTest) maps the picture's
        // angles 1:1, which is narrower than the air box: the same centre, a smaller spread.
        val w = 1920
        val h = 1080
        val image = HandGestureRecognizer(w, h, AirBox(), aim = AimPoint.PINCH)
        val ray = HandGestureRecognizer(w, h).apply {
            this.ray = HandRayBuilder(HandSide.RIGHT, camera, pinchAim)
            frustum = ViewFrustum(w, h, 70f)
        }
        for ((x, y) in listOf(0.5f to 0.5f, 0.3f to 0.4f, 0.7f to 0.6f)) {
            image.reset()
            ray.reset()
            val a = image.update(pinchAt(x, y), 0).status.cursor!!
            val b = ray.update(pinchAt(x, y), 0).status.cursor!!
            assertTrue("x $x: image ${a.x}, ray ${b.x}", abs(b.x - w / 2f) <= abs(a.x - w / 2f) + 1f && abs(a.x - b.x) < 0.12f * w)
            assertTrue("y $y: image ${a.y}, ray ${b.y}", abs(b.y - h / 2f) <= abs(a.y - h / 2f) + 1f && abs(a.y - b.y) < 0.12f * h)
        }
    }

    @Test
    fun theShoulderRayUsesAFixedDistanceAndASmallOffset() {
        val s = HandRaySettings.SHOULDER.copy(aim = AimPoint.PINCH)
        assertEquals(RayOrigin.SHOULDER, s.origin)
        assertEquals(0.45f, s.fixedDepthM!!, 0f)
        assertTrue(s.shoulderOut < 0.1f && s.shoulderDown < 0.1f && s.shoulderBack < 0.05f)
        val near = HandRayBuilder(HandSide.RIGHT, camera, s).build(pinchAt(0.6f, 0.65f, palm = 0.14f), 0)
        val far = HandRayBuilder(HandSide.RIGHT, camera, s).build(pinchAt(0.6f, 0.65f, palm = 0.08f), 0)
        assertEquals(0.45f, near.depthM, 0f)
        assertEquals(0.45f, far.depthM, 0f)
        // The hand's size in the picture does not move it (0.5.0's swung by up to 50 degrees).
        assertEquals(near.angles.yawDeg, far.angles.yawDeg, 0.01f)
        assertEquals(near.angles.pitchDeg, far.angles.pitchDeg, 0.01f)
        // Within a few degrees of where the camera sees the hand (0.5.0's was 10 to 62 above it).
        val seen = Angles.of(camera.direction(Landmark(0.6f, 0.65f)))
        assertTrue("shoulder ${near.angles}, seen $seen", abs(near.angles.pitchDeg - seen.pitchDeg) < 10f && abs(near.angles.yawDeg - seen.yawDeg) < 6f)
    }
}
