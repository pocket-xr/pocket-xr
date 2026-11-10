package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSkeletonTest {
    @Test
    fun basisRoundTrips() {
        val q = Quat.fromYawPitch(30f, -20f).normalized()
        val back = HandSkeleton.fromBasis(q.rotate(Vec3(1f, 0f, 0f)), q.rotate(Vec3(0f, 1f, 0f)), q.rotate(Vec3(0f, 0f, 1f)))
        assertTrue("angle ${q.angleTo(back)}", q.angleTo(back) < 1e-3f)
    }

    /**
     * A flat right hand, fingers up, its back to the camera, 0.4 m ahead: index on the left of the picture. Its joints
     * point along the fingers, the back of the hand faces the camera (+z, toward the wearer), and it sits 0.4 m ahead.
     */
    @Test
    fun flatRightHandFromBehind() {
        val camera = CameraModel(offset = Vec3(0f, 0f, 0f))
        // World landmarks in metres around the hand, x right, y down, all at one depth.
        val world = Array(HandFrame.COUNT) { Landmark(0f, 0f, 0f) }
        fun finger(base: Int, x: Float) {
            for (k in 0 until 4) world[base + k] = Landmark(x, -0.09f - 0.025f * k, 0f)
        }
        world[0] = Landmark(0f, 0f, 0f)
        for (k in 0 until 4) world[1 + k] = Landmark(-0.03f - 0.015f * k, -0.03f - 0.02f * k, 0f)
        finger(5, -0.03f); finger(9, -0.01f); finger(13, 0.01f); finger(17, 0.03f)
        // The image: the same points seen at 0.4 m through the camera's field of view.
        val halfX = kotlin.math.tan(Math.toRadians(camera.hfovDeg / 2.0)).toFloat()
        val halfY = kotlin.math.tan(Math.toRadians(camera.vfovDeg / 2.0)).toFloat()
        val image = world.map { Landmark(0.5f + it.x / 0.4f / (2f * halfX), 0.5f + it.y / 0.4f / (2f * halfY), 0f) }
        val hand = HandFrame(image, 0L, rightHand = true, world = world.toList())

        val out = FloatArray(HandSkeleton.JOINTS * HandSkeleton.STRIDE)
        assertTrue(HandSkeleton.fill(hand, camera, out))
        fun q(j: Int) = Quat(out[j * 7 + 3], out[j * 7], out[j * 7 + 1], out[j * 7 + 2])
        fun p(j: Int) = Vec3(out[j * 7 + 4], out[j * 7 + 5], out[j * 7 + 6])

        // Index proximal (7) points up its finger: its -z is up.
        val forward = q(7).rotate(Vec3(0f, 0f, -1f))
        assertTrue("index points $forward", forward.y > 0.95f)
        // The back of the hand (+y of the wrist) faces the wearer.
        val back = q(1).rotate(Vec3(0f, 1f, 0f))
        assertTrue("back faces $back", back.z > 0.95f)
        // About 0.4 m ahead, the index tip above the wrist.
        assertEquals(-0.4f, p(1).z, 0.05f)
        assertTrue(p(10).y > p(1).y + 0.1f)
    }
}
