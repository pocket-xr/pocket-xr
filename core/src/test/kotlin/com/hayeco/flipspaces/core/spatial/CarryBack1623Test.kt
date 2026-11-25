package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.6.23: biases saved under an older gravity check's layout, carried back to a tested model's own (Mike's Aura, 25 Nov 2026). */
class CarryBack1623Test {
    /** Measured on the table on 25 Nov 2026, in the Aura's own layout. */
    private val measured = Vec3(0.00007f, -0.00379f, -0.00386f)

    /** The static bias the session had been seeded with, in the old pick's axes. */
    private val saved = Vec3(-0.00007f, 0.00409f, -0.00286f)

    private val own = GlassesModel.AURA.defaultAxes
    private val pick = AxisMap.XLEFT_YDOWN_ZBACK

    @Test
    fun theSavedBiasWasTheMeasuredOneInTheOldPicksAxes() {
        // x and y turned round, as the pick does; the rest is the gyro's own drift between sessions.
        val inPick = GravityAxes.carry(measured, own, pick)
        assertEquals(saved.x, inPick.x, 0.0002f)
        assertEquals(saved.y, inPick.y, 0.0004f)
        assertEquals(saved.z, inPick.z, 0.0011f)
    }

    @Test
    fun carriedBackItIsNearTheTruthAlongUp() {
        val back = GravityAxes.carryBack(saved, pick, own)!!
        // Before: 0.0079 rad/s off on y (0.38 deg/s of creep along up when worn level); after, within 0.0004.
        assertEquals(measured.y, back.y, 0.0004f)
        assertEquals(measured.x, back.x, 0.0002f)
    }

    @Test
    fun nothingChangesWithoutAPick() {
        assertEquals(saved, GravityAxes.carryBack(saved, null, own))
        assertEquals(saved, GravityAxes.carryBack(saved, own, own))
        assertNull(GravityAxes.carryBack(null, pick, own))
    }
}
