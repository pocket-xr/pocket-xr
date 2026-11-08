package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VblankLockTest {
    private val nominal = 16_666_667L
    private val real = 16_660_000L

    @Test
    fun measuresTheRealPeriodNotTheNominalOne() {
        val lock = VblankLock(nominal)
        var t = 1_000_000_000L
        repeat(200) {
            lock.add(t)
            t += real
        }
        assertTrue("period ${lock.periodNs}", abs(lock.periodNs - real) < 2_000L)
        assertEquals(t - real, lock.lastNs)
    }

    @Test
    fun aMissedRefreshDoesNotBendThePeriod() {
        val lock = VblankLock(nominal)
        var t = 5_000_000_000L
        for (i in 0 until 200) {
            if (i % 7 != 3) lock.add(t)
            t += real
        }
        assertTrue("period ${lock.periodNs}", abs(lock.periodNs - real) < 2_000L)
        assertEquals(0L, lock.rejected)
    }

    @Test
    fun aTimeOffTheRefreshStartsAgain() {
        val lock = VblankLock(nominal)
        var t = 0L
        repeat(60) {
            lock.add(t)
            t += real
        }
        lock.add(t + real / 2)
        assertEquals(1L, lock.rejected)
        assertEquals(0L, lock.periodNs)
    }

    @Test
    fun noPeriodUntilEnoughRefreshes() {
        val lock = VblankLock(nominal)
        repeat(10) { lock.add(it * real) }
        assertEquals(0L, lock.periodNs)
    }

    private fun abs(v: Long) = if (v < 0) -v else v
}
