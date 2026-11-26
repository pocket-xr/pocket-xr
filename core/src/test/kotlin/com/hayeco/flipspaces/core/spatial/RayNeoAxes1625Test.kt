package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A RayNeo GT's axes, from its Send logs (XREAL Beam Pro, 25 and 26 Nov 2026): x front, y left, z up (1.6.25), and from
 * 1.6.31 the gyro's x and z turned to match the accelerometer ([RayNeoProtocol.parse]).
 */
class RayNeoAxes1625Test {
    private val axes = GlassesModel.RAYNEO.defaultAxes

    @Test fun upIsTheSensorsZ() {
        assertEquals(AxisMap.XFRONT_YLEFT_ZUP, axes)
        // Recentred looking ahead: 0.53, -0.05, 9.79 m/s2
        val level = axes.apply(Vec3(0.53f, -0.05f, 9.79f))
        assertTrue("$level", level.y / level.length > 0.99f)
        // Looking up: 9.56, 0.11, -2.30 m/s2, world up along the head's front (-z in head axes)
        val up = axes.apply(Vec3(9.56f, 0.11f, -2.30f))
        assertTrue("$up", -up.z / up.length > 0.95f)
    }

    @Test fun theGtsTurnsLieAlongGravityOnceItsGyroIsTurned() {
        // Fast turns from the 20 s motion recording, raw (accel m/s2, gyro deg/s): the rotation axis is about 31 degrees
        // from the sensor's z, gravity 16 to 23. Turned by the GT's 20 degrees, they agree better.
        val samples = listOf(
            floatArrayOf(3.90f, 0.77f, 9.06f, 21.7f, -1.6f, 34.2f),
            floatArrayOf(2.78f, 0.82f, 9.67f, -44.4f, 13.2f, -70.1f),
            floatArrayOf(3.74f, -0.31f, 8.82f, -37.4f, -7.9f, -62.3f),
        )
        // Over all 1557 fast turns of the recording the mean |cos| goes from 0.954 to 0.976; over these three, likewise up.
        val f = RayNeoFramer(gyroPitchDeg = RayNeoProtocol.gyroPitchDeg(RayNeoProtocol.VENDOR_GT))
        var rawSum = 0f
        var turnedSum = 0f
        for (v in samples) {
            val raw = RayNeoProtocol.parse(RayNeoEncoder.imu(v[0], v[1], v[2], v[3], v[4], v[5]), 64)!!
            val out = ArrayList<ImuReport>()
            f.append(RayNeoEncoder.imu(v[0], v[1], v[2], v[3], v[4], v[5]), 64, 0L, out)
            fun cos(w: Vec3) = abs(raw.accel dot w) / (raw.accel.length * w.length)
            rawSum += cos(raw.gyro)
            turnedSum += cos(out[0].gyro)
            assertTrue("${cos(out[0].gyro)}", cos(out[0].gyro) > 0.96f)
        }
        assertTrue("turned $turnedSum raw $rawSum", turnedSum > rawSum)
        // The Air 4 Pro's vendor: nothing turned.
        assertEquals(0f, RayNeoProtocol.gyroPitchDeg(RayNeoProtocol.VENDOR))
    }

    @Test fun gravityNeverTurnsTheKnownLayout() {
        assertTrue(GlassesModel.RAYNEO.layoutKnown)
        val v = GravityAxes.check(GravityAxes.Rest(Vec3(9.56f, 0.11f, -2.30f), "recentre"), axes, axes, knownLayout = true)
        assertEquals(null, v.axes)
    }
}
