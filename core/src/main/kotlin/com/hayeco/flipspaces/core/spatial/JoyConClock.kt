package com.hayeco.flipspaces.core.spatial

/**
 * When each Joy-Con motion sample really happened.
 *
 * A Joy-Con sends a report about every 15 ms (11.25 on some phones) with three motion samples in it, oldest first. Linux
 * hands the three on together, so they reach the reader in one read with one arrival time. Weighting each sample by
 * the time since the one before, as arrival times give it, put nearly all the turn on the first sample of each report:
 * the gyro counted at about 66 Hz, not 200. And stamping a pose with its arrival time made it look younger than it was,
 * so Monado predicted it too little.
 *
 * So the reports' arrivals are put on a steady clock (the earliest arrivals are the truest, so a report that comes in
 * early pulls the clock back at once, a late one only a little), the three samples are spread a third of a report
 * apart behind it, and all of it is moved back by [linkDelayNs], the radio's own delay, which no clock on the phone can
 * see.
 */
class JoyConClock(
    private val linkDelayNs: Long = LINK_DELAY_NS,
    private val samplesPerReport: Int = 3,
) {
    /** The report period as measured, nanoseconds. */
    var periodNs = START_PERIOD_NS
        private set

    /** Reports seen, and the gaps that looked like lost reports, for the log. */
    var reports = 0L
        private set
    var gaps = 0L
        private set

    private var lastReadNs = Long.MIN_VALUE
    private var reportNs = Long.MIN_VALUE
    private var index = 0
    private var lastSampleNs = Long.MIN_VALUE

    /** A sample's time and the time since the one before, seconds (0 for the first, or after a gap). */
    data class Stamp(val sampleNs: Long, val dt: Float)

    /** One sample that arrived in a read that returned at [readNs] (System.nanoTime). */
    fun sample(readNs: Long): Stamp {
        if (lastReadNs == Long.MIN_VALUE || readNs - lastReadNs > SAME_READ_NS) {
            newReport(readNs)
        } else if (++index >= samplesPerReport) {
            // Two reports in one read (the reader was held up): the second is a report later.
            index = 0
            reports++
            reportNs += periodNs
        }
        lastReadNs = readNs
        val step = periodNs / samplesPerReport
        val newest = samplesPerReport - 1
        val at = reportNs - linkDelayNs - (newest - index) * step
        val since = at - lastSampleNs
        val dt = when {
            lastSampleNs == Long.MIN_VALUE || since > MAX_DT_NS -> 0f
            // The clock stepped back under it: the sample still covers its share of time.
            since <= 0L -> step / 1e9f
            else -> since / 1e9f
        }
        // A sample never goes back in time: Monado drops ones that do.
        val stamp = if (lastSampleNs != Long.MIN_VALUE && at <= lastSampleNs) lastSampleNs + 1 else at
        lastSampleNs = stamp
        return Stamp(stamp, dt)
    }

    private fun newReport(readNs: Long) {
        index = 0
        reports++
        if (reportNs == Long.MIN_VALUE) {
            reportNs = readNs
            return
        }
        val gap = readNs - lastReadNs
        // The period, from reports that came one after another.
        if (gap in MIN_PERIOD_NS..MAX_PERIOD_NS) periodNs += ((gap - periodNs) * PERIOD_GAIN).toLong()
        val expected = reportNs + periodNs
        reportNs = when {
            // Lost reports or a pause: start again from this one.
            readNs - expected > periodNs * 3 / 2 || readNs < reportNs -> {
                gaps++
                readNs
            }
            // Early: nearer the truth than the clock was.
            readNs <= expected -> readNs
            // Late: mostly delay, only a little of it the clock's.
            else -> expected + ((readNs - expected) * LATE_GAIN).toLong()
        }
    }

    companion object {
        /** Radio and Bluetooth stack, report to reader. A starting value until it is measured on glasses. */
        const val LINK_DELAY_NS = 8_000_000L
        const val START_PERIOD_NS = 15_000_000L
        private const val MIN_PERIOD_NS = 5_000_000L
        private const val MAX_PERIOD_NS = 30_000_000L
        private const val PERIOD_GAIN = 0.02
        private const val LATE_GAIN = 0.05
        /** Events closer than this came in one read. */
        private const val SAME_READ_NS = 1_000_000L
        private const val MAX_DT_NS = 100_000_000L
    }
}
