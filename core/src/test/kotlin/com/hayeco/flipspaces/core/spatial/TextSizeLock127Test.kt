package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.7 (Mike: tapping Text size smaller and larger a lot, "it took a while for all the button actions to catch up since
 * it kept reloading the windows to those sizes"): the keys lock until the window has laid out again, with a time out,
 * and the Space folds several waiting taps into one size.
 */
class TextSizeLock127Test {
    @Test
    fun aTapLocksTheKeysUntilTheSpaceSettlesIt() {
        val lock = TextSizeLock()
        assertFalse(lock.locked(0, 0))
        val seq = lock.tap(1_000, 0)
        assertNotNull(seq)
        assertTrue(lock.locked(1_100, 0))
        // Taps while locked are dropped, not queued.
        assertNull(lock.tap(1_200, 0))
        assertNull(lock.tap(1_300, 0))
        assertTrue(lock.locked(1_400, 0))
        // The Space laid the window out at the new size.
        assertFalse(lock.locked(1_500, seq!!))
        val next = lock.tap(1_600, seq)
        assertEquals(seq + 1, next)
    }

    @Test
    fun anOlderSettleDoesNotUnlockANewerTap() {
        val lock = TextSizeLock()
        val first = lock.tap(0, 0)!!
        assertFalse(lock.locked(100, first))
        val second = lock.tap(200, first)!!
        assertTrue(lock.locked(300, first))
        assertFalse(lock.locked(400, second))
    }

    @Test
    fun theKeysComeBackAfterTheTimeoutWhateverTheSpaceSaid() {
        val lock = TextSizeLock()
        lock.tap(10_000, 0)
        assertTrue(lock.locked(10_000 + TextSizeLock.TIMEOUT_MS - 1, 0))
        assertFalse(lock.locked(10_000 + TextSizeLock.TIMEOUT_MS, 0))
        assertNotNull(lock.tap(10_000 + TextSizeLock.TIMEOUT_MS + 1, 0))
        assertEquals(3000L, TextSizeLock.TIMEOUT_MS)
    }

    @Test
    fun requestNumbersStayAboveWhatTheSpaceAlreadySettled() {
        // A trackpad screen opened again starts over, while the Space kept its count.
        val lock = TextSizeLock()
        assertEquals(42L, lock.tap(0, 41))
    }

    @Test
    fun severalWaitingTapsForOneAppMakeOneFinalSize() {
        val batch = TextSizeBatch()
        batch.add("com.android.chrome", 1, 1)
        batch.add("com.android.chrome", 1, 2)
        batch.add("com.android.chrome", -1, 3)
        batch.add("com.android.chrome", 1, 4)
        assertEquals(4L, batch.seq)
        assertEquals(mapOf("com.android.chrome" to (100 to 120)), batch.finals { 100 })
    }

    @Test
    fun aResetInTheBatchStartsFromOneHundred() {
        val batch = TextSizeBatch()
        batch.add("a", -1, 1)
        batch.add("a", 0, 2)
        batch.add("a", -1, 3)
        assertEquals(mapOf("a" to (130 to 90)), batch.finals { 130 })
    }

    @Test
    fun tapsThatCancelOutChangeNothingButStillSettle() {
        val batch = TextSizeBatch()
        batch.add("a", 1, 5)
        batch.add("a", -1, 6)
        assertTrue(batch.finals { 100 }.isEmpty())
        assertEquals(6L, batch.seq)
        batch.clear()
        assertTrue(batch.isEmpty)
    }

    @Test
    fun eachAppKeepsItsOwnSizeAndLimitsHold() {
        val batch = TextSizeBatch()
        batch.add("a", 1, 1)
        batch.add("b", -1, 2)
        batch.add("a", 1, 3)
        batch.add(null, 1, 4)
        val finals = batch.finals { if (it == "a") 120 else AppTextSize.MIN }
        assertEquals(mapOf("a" to (120 to AppTextSize.MAX)), finals)
        assertEquals(4L, batch.seq)
    }

    @Test
    fun theSpaceSettlesAfterTheResizeWentOutAndTheAppDrew() {
        val settle = TextSizeSettle()
        var due = true
        var frames = 10L
        settle.waitFor(7, 3, 0)
        assertEquals(3L, settle.waitingSeq)
        // The resize has not gone out yet.
        assertNull(settle.check(16, { due }, { frames }))
        due = false
        settle.sent(7, frames)
        // Sent, but the app has not drawn at the new size yet.
        assertNull(settle.check(32, { due }, { frames }))
        frames++
        assertEquals(3L, settle.check(48, { due }, { frames }))
        assertEquals(0L, settle.waitingSeq)
        assertNull(settle.check(64, { due }, { frames }))
    }

    @Test
    fun aSizeThatCameOutTheSameSettlesAtOnce() {
        val settle = TextSizeSettle()
        settle.waitFor(7, 1, 0)
        assertEquals(1L, settle.check(16, { false }, { 5L }))
    }

    @Test
    fun aWindowWithNoDisplayOrClosedSettlesAtOnce() {
        val settle = TextSizeSettle()
        settle.waitFor(7, 1, 0)
        assertEquals(1L, settle.check(16, { true }, { null }))
        settle.waitFor(8, 2, 100)
        settle.forget(8)
        assertEquals(2L, settle.check(116, { true }, { 0L }))
    }

    @Test
    fun anAppThatNeverDrawsSettlesAfterTheLongestWait() {
        val settle = TextSizeSettle()
        settle.waitFor(7, 1, 1_000)
        settle.sent(7, 0)
        assertNull(settle.check(1_000 + settle.maxWaitMs - 1, { false }, { 0L }))
        assertEquals(1L, settle.check(1_000 + settle.maxWaitMs, { false }, { 0L }))
    }

    @Test
    fun aNewerChangeWaitsAgainWithTheHigherNumber() {
        val settle = TextSizeSettle()
        settle.waitFor(7, 1, 0)
        settle.sent(7, 4)
        settle.waitFor(7, 2, 50)
        // The first send's frame does not count for the second change.
        assertNull(settle.check(60, { true }, { 5L }))
        settle.sent(7, 5)
        assertEquals(2L, settle.check(80, { false }, { 6L }))
    }
}
