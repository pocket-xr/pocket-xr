package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.3: the log file's watchdog (a vivo's logcat printed its buffer and then nothing). */
class LogStallWatchTest {
    private fun watch() = LogStallWatch(quietMs = 30_000, answerMs = 5_000, maxMisses = 2)

    @Test
    fun aBusyLogNeverChecks() {
        val w = watch()
        w.started(0)
        for (t in 0L..120_000L step 1_000L) {
            w.onLine("11-18 20:02:41.151 1 1 I FlipSpaces/Mode: line at $t", t)
            assertEquals(LogStallWatch.Step.Wait, w.tick(t))
        }
    }

    @Test
    fun aQuietLogSendsACheckAndAWorkingLogcatAnswersIt() {
        val w = watch()
        w.started(0)
        assertEquals(LogStallWatch.Step.Wait, w.tick(29_000))
        val step = w.tick(30_000)
        assertTrue(step is LogStallWatch.Step.SendCheck)
        val line = (step as LogStallWatch.Step.SendCheck).line
        assertTrue(line.startsWith(LogStallWatch.CHECK_PREFIX))
        // logcat hands the check line back, with its own prefix.
        w.onLine("11-18 20:03:11.000 1 2 I FlipSpaces/LogFile: $line", 31_000)
        assertNull(w.waitingFor)
        assertEquals(LogStallWatch.Step.Wait, w.tick(36_000))
    }

    @Test
    fun aCheckThatNeverComesBackIsAStallThenNoLines() {
        val w = watch()
        w.started(0)
        assertTrue(w.tick(30_000) is LogStallWatch.Step.SendCheck)
        assertEquals(LogStallWatch.Step.Wait, w.tick(34_000))
        val stalled = w.tick(35_000)
        assertTrue(stalled is LogStallWatch.Step.Stalled)
        assertEquals(35_000L, (stalled as LogStallWatch.Step.Stalled).quietForMs)
        // The next way of reading brings nothing either.
        assertTrue(w.tick(65_000) is LogStallWatch.Step.SendCheck)
        assertTrue(w.tick(70_000) is LogStallWatch.Step.NoLines)
        // Said once only, then it keeps checking quietly.
        assertTrue(w.tick(100_000) is LogStallWatch.Step.SendCheck)
        assertEquals(LogStallWatch.Step.Wait, w.tick(105_000))
    }

    @Test
    fun aCheckAnsweredAfterAStallResetsTheMisses() {
        val w = watch()
        w.started(0)
        w.tick(30_000)
        assertTrue(w.tick(35_000) is LogStallWatch.Step.Stalled)
        val again = w.tick(65_000) as LogStallWatch.Step.SendCheck
        w.onLine("x ${again.line}", 66_000)
        // A later stall is a first miss again, not NoLines.
        w.tick(96_000)
        assertTrue(w.tick(101_000) is LogStallWatch.Step.Stalled)
    }
}
