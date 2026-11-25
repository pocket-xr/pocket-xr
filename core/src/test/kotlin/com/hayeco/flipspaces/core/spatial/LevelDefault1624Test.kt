package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.24: the One Pro and One Pro+ start from their usual tilt until a level is saved (GlassesModel.defaultMountPitchDeg). */
class LevelDefault1624Test {
    @Test fun theOneProStartsFromItsTestersSavedLevel() {
        // thejoeba's One Pro (issue 30): "the IMU reads roll -2.21 pitch -30.44 deg while the display is level"
        assertEquals(-30.44f, GlassesModel.ONE_PRO.defaultMountPitchDeg, 0f)
        assertEquals(-40f, GlassesModel.ONE_PRO_PLUS.defaultMountPitchDeg, 0f)
        for (m in listOf(GlassesModel.AURA, GlassesModel.ONE, GlassesModel.VITURE, GlassesModel.UNKNOWN)) {
            assertEquals(m.name, 0f, m.defaultMountPitchDeg, 0f)
        }
    }

    @Test fun theDefaultsAreMountsTheLevelCalibrationWouldTake() {
        for (m in listOf(GlassesModel.ONE_PRO, GlassesModel.ONE_PRO_PLUS)) {
            val p = m.defaultMountPitchDeg
            assertTrue(m.name, LevelCalibration.plausible(0f, p))
            // The gravity such glasses read with the picture level gives the same mount back
            val (roll, pitch) = LevelCalibration.fromGravity(LevelCalibration.imuUp(0f, p) * 9.81f)
            assertEquals(0f, roll, 0.01f)
            assertEquals(p, pitch, 0.01f)
        }
    }

    @Test fun theLogSaysWhichIsUsed() {
        assertTrue(LevelCalibration.line(0f, -30.44f, false, GlassesModel.ONE_PRO).contains("XREAL One Pro's usual mount, pitch -30.44"))
        assertTrue(LevelCalibration.line(0f, 0f, false, GlassesModel.AURA).contains("taken as mounted level"))
        assertTrue(LevelCalibration.line(1f, -29f, true, GlassesModel.ONE_PRO).contains("roll 1.00 pitch -29.00"))
    }
}
