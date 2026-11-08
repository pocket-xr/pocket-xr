package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.55 (Mike's log: a GC every 16 s, 1.5 MB/s allocated, 58 pauses over 4 ms in 15 minutes): the head path's
 * allocations were cut with kept arrays, plain floats and one step sums ([HeadMath]). Nothing may move: every pose,
 * prediction and figure must be the same to the bit. Each changed piece is run here against the code it replaced
 * (copied below as it was in 0.5.54) or against the expression it stands for, on Mike's own raw gyro where it matters.
 */
class HeadAlloc0555Test {

    // ---- HeadMath against the Vec3 and Quat expressions it replaces ----

    private fun bits(f: Float) = java.lang.Float.floatToIntBits(f)

    private fun same(msg: String, a: Vec3, b: Vec3) {
        assertEquals("$msg x", bits(a.x), bits(b.x))
        assertEquals("$msg y", bits(a.y), bits(b.y))
        assertEquals("$msg z", bits(a.z), bits(b.z))
    }

    private fun same(msg: String, a: Quat?, b: Quat?) {
        if (a == null || b == null) {
            assertEquals(msg, a, b)
            return
        }
        assertEquals("$msg w", bits(a.w), bits(b.w))
        assertEquals("$msg x", bits(a.x), bits(b.x))
        assertEquals("$msg y", bits(a.y), bits(b.y))
        assertEquals("$msg z", bits(a.z), bits(b.z))
    }

    private val specials = floatArrayOf(0f, -0f, 1e-12f, -1e-10f, 3e-5f, 0.5f, -1f, 9.81f, 1e3f, -2.5e4f)

    private fun f(r: Random): Float = when (r.nextInt(6)) {
        0 -> specials[r.nextInt(specials.size)]
        1 -> (r.nextGaussian() * 1e-4).toFloat()
        2 -> (r.nextGaussian() * 10).toFloat()
        else -> (r.nextGaussian()).toFloat()
    }

    private fun v(r: Random) = Vec3(f(r), f(r), f(r))
    private fun q(r: Random): Quat = when (r.nextInt(4)) {
        0 -> Quat(f(r), f(r), f(r), f(r))
        1 -> Quat.IDENTITY
        else -> Quat(f(r), f(r), f(r), f(r)).normalized()
    }

    @Test
    fun headMathIsTheExpressionsToTheBit() {
        val r = Random(555)
        repeat(200_000) { i ->
            val a = v(r)
            val b = v(r)
            val c = v(r)
            val s = f(r)
            same("toward $i", a + (b - a) * s, HeadMath.toward(a, b, s))
            same("addScaled $i", a + b * s, HeadMath.addScaled(a, b, s))
            same("addDiffScaled $i", a + (b - c) * s, HeadMath.addDiffScaled(a, b, c, s))
            same("diffScaled $i", (a - b) * s, HeadMath.diffScaled(a, b, s))
            assertEquals("distance $i", bits((a - b).length), bits(HeadMath.distance(a, b)))
            val d = a - b
            assertEquals("distanceSq $i", bits(d dot d), bits(HeadMath.distanceSq(a, b)))
            same("scaledCross $i", (a * s) cross b, HeadMath.scaledCross(a, s, b))
            val p = q(r)
            val p2 = q(r)
            val p3 = q(r)
            same("rotate $i", p.rotate(a), HeadMath.rotate(p, a))
            same("rotateByConjugate $i", p.conjugate().rotate(a), HeadMath.rotateByConjugate(p, a))
            same("mul3 $i", (p * p2 * p3).normalized(), HeadMath.mul3Normalized(p, p2, p3))
            // Rates from none to fast, and the steps the head path uses (1 ms, a frame, 1 s for a whole turn).
            val omega = when (r.nextInt(5)) {
                0 -> Vec3.ZERO
                1 -> Vec3(1e-10f, 0f, -1e-10f)
                else -> a
            }
            val dt = when (r.nextInt(4)) {
                0 -> 0.001f
                1 -> 0.1f
                2 -> 1f
                else -> s
            }
            same("turnedBy $i", (p * Quat.fromGyro(omega, dt)).normalized(), HeadMath.turnedBy(p, omega, dt))
        }
        // Not finite stays as it was too.
        val nan = Vec3(Float.NaN, 0f, 1f)
        same("turnedBy NaN", (Quat.IDENTITY * Quat.fromGyro(nan, 0.001f)).normalized(), HeadMath.turnedBy(Quat.IDENTITY, nan, 0.001f))
        same("rotate NaN", Quat(Float.NaN, 0f, 0f, 1f).rotate(nan), HeadMath.rotate(Quat(Float.NaN, 0f, 0f, 1f), nan))
    }

    @Test
    fun axisMapAndUsableAreUnchanged() {
        val r = Random(7)
        repeat(5_000) {
            val x = v(r)
            for (m in AxisMap.entries) same("$m", refApply(m, x), m.apply(x))
            val p = q(r)
            assertEquals(HeadView.usable(p) != null, HeadView.isUsable(p))
        }
        assertEquals(false, HeadView.isUsable(null))
        assertEquals(false, HeadView.isUsable(Quat(Float.NaN, 0f, 0f, 0f)))
    }

    /** 0.5.54's AxisMap.apply, on the preset's own (private) indices and signs. */
    private fun refApply(m: AxisMap, v: Vec3): Vec3 {
        fun field(name: String): Any = AxisMap::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(m)
        val a = floatArrayOf(v.x, v.y, v.z)
        return Vec3(a[field("ix") as Int] * (field("sx") as Float), a[field("iy") as Int] * (field("sy") as Float), a[field("iz") as Int] * (field("sz") as Float))
    }

    // ---- Mike's raw gyro ----

    private fun ring(name: String): Pair<LongArray, Array<Vec3>> {
        val rows = HeadAlloc0555Test::class.java.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(Regex("\\s+")) }
        return LongArray(rows.size) { rows[it][0].toLong() } to Array(rows.size) { i -> Vec3(rows[i][1].toFloat(), rows[i][2].toFloat(), rows[i][3].toFloat()) }
    }

    /**
     * The ring with a head turn laid on it (up to 1.5 rad/s, so the prior takes over from the learned weights), a 10 ms
     * gap (the tremor predictor starts over) and a 150 ms one (the whole predictor does).
     */
    private fun replay(name: String): Pair<LongArray, Array<Vec3>> {
        val (t, g) = ring(name)
        val ts = ArrayList<Long>()
        val gs = ArrayList<Vec3>()
        for (i in t.indices) {
            if (i in 6000 until 6010 || i in 8000 until 8150) continue
            val turn = if (i in 4000 until 4600) (1.5 * sin(PI * (i - 4000) / 600.0)).toFloat() else 0f
            ts += t[i]
            gs += Vec3(g[i].x, g[i].y + turn, g[i].z)
        }
        return ts.toLongArray() to gs.toTypedArray()
    }

    @Test
    fun tremorPredictorIsUnchangedOnMikesGyro() {
        val (t, g) = replay("vc42-still-160726.txt")
        // 1.2.3: worn() learns from every 4th sample; this pins the every-sample path to 0.5.54 to the bit.
        val now = TremorPredictor(blend = true, learnEvery = 1)
        val old = RefTremor()
        assertEquals(0.0, now.bandWeight, 0.0)
        val out = FloatArray(3)
        for (i in t.indices) {
            val learn = i % 500 < 450
            now.add(t[i], g[i], learnNow = learn)
            old.add(t[i], g[i], learnNow = learn)
            if (i % 5 != 0) continue
            for (ahead in doubleArrayOf(2.0, 4.0, 17.5, 30.0, 60.0, 100.0)) {
                for (share in doubleArrayOf(1.0, 0.3, 0.0)) {
                    val a = old.turn(ahead, share)
                    val b = now.turn(ahead, share)
                    if (a == null || b == null) {
                        assertEquals("turn at $i", a, b)
                        assertEquals(false, now.turnInto(ahead, share, out))
                        continue
                    }
                    same("turn at $i, $ahead ms, share $share", a, b)
                    assertTrue(now.turnInto(ahead, share, out))
                    same("turnInto at $i", a, Vec3(out[0], out[1], out[2]))
                }
                val wa = old.weightsFor(ahead)
                val wb = now.weightsFor(ahead)
                for (k in wa.indices) assertEquals("weights at $i, $ahead ms, tap $k", wa[k].toRawBits(), wb[k].toRawBits())
            }
        }
        assertTrue("the fit ran", now.solves > 20)
        assertEquals(old.solves, now.solves)
        for (k in 0 until 64) assertEquals(old.prior(0.025)[k].toRawBits(), now.prior(0.025)[k].toRawBits())
    }

    @Test
    fun headPredictorIsUnchangedOnMikesGyro() {
        for (name in listOf("vc42-still-160901.txt", "vc55-still-1793062639605.txt")) {
            val (t, g) = replay(name)
            val now = HeadPredictor(tremor = TremorPredictor(blend = true, learnEvery = 1))
            val old = RefPredictor()
            var q = Quat.IDENTITY
            for (i in t.indices) {
                q = (q * Quat.fromGyro(g[i], 0.001f)).normalized()
                now.add(q, g[i], t[i])
                old.add(q, g[i], t[i])
                assertEquals("$name still weight at $i", bits(old.stillWeight), bits(now.stillWeight))
                assertEquals("$name blend share at $i", bits(old.blendShare), bits(now.blendShare))
                assertEquals("$name blend gain at $i", bits(old.blendGain), bits(now.blendGain))
                assertEquals("$name turn speed at $i", bits(old.turnSpeed), bits(now.turnSpeed))
                assertEquals("$name lying rms at $i", bits(old.lyingRms), bits(now.lyingRms))
                same("$name rate at $i", old.rate, now.angularVelocity)
                if (i % 3 != 0) continue
                for (aheadMs in longArrayOf(0L, 5L, 23L, 41L, 120L)) {
                    val target = t[i] + aheadMs * 1_000_000L
                    same("$name pose at $i + $aheadMs ms", old.predict(target), now.predict(target))
                }
            }
        }
    }

    @Test
    fun staticDetectorIsUnchanged() {
        val (t, g) = ring("vc52-still-183712.txt")
        val settings = FusionSettings()
        val now = StaticDetector()
        val old = RefStatic()
        val r = Random(42)
        // A worn head, then the glasses on a table (the bias and a little noise), a knock, and the table again.
        val n = t.size + 9000
        var verdicts = 0
        for (i in 0 until n) {
            val worn = i < t.size
            val dt = if (worn && i > 0) ((t[i] - t[i - 1]) / 1e9f) else 0.001f
            val gyro = if (worn) g[i] else Vec3(-0.0037f + (r.nextGaussian() * 3e-4).toFloat(), 0.0012f + (r.nextGaussian() * 3e-4).toFloat(), 0.002f + (r.nextGaussian() * 3e-4).toFloat())
            val knock = if (i in t.size + 5000 until t.size + 5100) 3f else 0f
            val accel = Vec3(
                (0.02 * sin(i * 0.37) + r.nextGaussian() * 0.003).toFloat() + knock,
                (9.81 + 0.01 * kotlin.math.cos(i * 0.11)).toFloat(),
                (0.015 * sin(i * 0.05)).toFloat(),
            )
            val a = old.add(gyro, accel, dt, settings)
            val b = now.add(gyro, accel, dt, settings)
            if (a == null || b == null) assertEquals("verdict at $i", a, b) else same("verdict at $i", a, b)
            if (b != null) verdicts++
            assertEquals("static at $i", old.static, now.static)
            assertEquals("gate at $i", bits(old.gate), bits(now.gate))
            assertEquals("peak to peak at $i", bits(old.lastPeakToPeak), bits(now.lastPeakToPeak))
            assertEquals("accel spread at $i", bits(old.lastAccelSpread), bits(now.lastAccelSpread))
            assertEquals("still since at $i", bits(old.stillSinceS), bits(now.stillSinceS))
            if (i == t.size + 7000) {
                old.reset()
                now.reset()
            }
        }
        assertTrue("the table was found static and measured", verdicts > 0)
    }

    @Test
    fun framerIntoOneListGivesTheSameReportsAndCounts() {
        val r = Random(3)
        val stream = java.io.ByteArrayOutputStream()
        var imu = 0
        var mag = 0
        for (i in 0 until 400) {
            when (r.nextInt(10)) {
                0 -> stream.write(byteArrayOf(0x28, 0x36, 0, 0, 0, 9, 1, 2, 3))
                1 -> stream.write(byteArrayOf(5, 6, 7))
                2 -> {
                    stream.write(OneImuEncoder.frame(OneImuProtocol.TYPE_MAGNETOMETER, i * 1_000_000L, mag = v(r)))
                    mag++
                }
                else -> {
                    stream.write(OneImuEncoder.frame(OneImuProtocol.TYPE_IMU, i * 1_000_000L, gyro = v(r), accel = v(r), magic0 = OneImuProtocol.MAGIC_0[r.nextInt(2)]))
                    imu++
                }
            }
        }
        val bytes = stream.toByteArray()
        val fresh = OneImuFramer()
        val kept = OneImuFramer()
        val list = ArrayList<ImuReport>()
        val all = ArrayList<ImuReport>()
        var pos = 0
        while (pos < bytes.size) {
            val size = minOf(bytes.size - pos, 1 + r.nextInt(700))
            val chunk = bytes.copyOfRange(pos, pos + size)
            val a = fresh.append(chunk, size)
            val b = kept.append(chunk, size, list)
            assertEquals(a, b)
            assertEquals(fresh.counts, kept.counts)
            all += b
            pos += size
        }
        assertEquals(imu, all.count { it.type == ImuReportType.IMU })
        assertEquals(mag, all.count { it.type == ImuReportType.MAGNETOMETER })
        val c = kept.counts
        assertEquals(imu.toLong(), c.imu)
        assertEquals(mag.toLong(), c.magnetometer)
        assertEquals((imu + mag).toLong(), c.reports)
        assertEquals(bytes.size.toLong(), c.bytes)
    }

    @Test
    fun gyroRingDueAndItsCopyAreUnchanged() {
        val (t, g) = ring("vc45-still-211821-sbs114.txt")
        val ring = GyroRing()
        val note = "a worn still head, still check 0.0500 rad/s, written at 1 ms uptime"
        for (i in t.indices) {
            ring.add(t[i], g[i])
            if (i % 250 != 0 && i != t.size - 1) continue
            val s = ring.samples()
            val expected = s.size >= 2 && (s.last().first - s.first().first) >= (ring.seconds * 0.9 * 1e9).toLong()
            // A copy keeps the ring's last dump time, so each check starts as the ring would.
            assertEquals("due at $i", expected, ring.copy().due(1_000_000L, true, 0.05f))
        }
        assertEquals(ring.dump(note), ring.copy().dump(note))
        assertTrue(ring.due(1_000_000L, true, 0.05f))
    }

    // ---- 0.5.54's code, as it was, for the comparisons above ----

    /** 0.5.54's StaticDetector. */
    private class RefStatic(val chunkS: Float = 0.1f) {
        private class Chunk {
            var min = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var max = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var sum = Vec3.ZERO
            var accMin = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var accMax = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var accSum = 0.0
            var n = 0
            var s = 0f
        }

        private val chunks = ArrayDeque<Chunk>()
        private var current = Chunk()
        private var lp: Vec3? = null
        private var lpAccel: Vec3? = null
        var static = false
        var gate = -1f
        var lastPeakToPeak = 0f
        var lastAccelSpread = 0f
        private var nowS = 0f
        var stillSinceS = -1f
        private val quietWindows = RollingStats(32)
        private var quietFailed = 0

        fun add(gyro: Vec3, accel: Vec3, dt: Float, settings: FusionSettings): Vec3? {
            val accelNorm = accel.length
            val k = (dt / (0.0032f + dt)).coerceIn(0f, 1f)
            val f = lp?.let { it + (gyro - it) * k } ?: gyro
            lp = f
            val ka = (dt / (0.016f + dt)).coerceIn(0f, 1f)
            val fa = lpAccel?.let { it + (accel - it) * ka } ?: accel
            lpAccel = fa
            val c = current
            c.min = Vec3(minOf(c.min.x, f.x), minOf(c.min.y, f.y), minOf(c.min.z, f.z))
            c.max = Vec3(maxOf(c.max.x, f.x), maxOf(c.max.y, f.y), maxOf(c.max.z, f.z))
            c.sum = c.sum + gyro
            c.accMin = Vec3(minOf(c.accMin.x, fa.x), minOf(c.accMin.y, fa.y), minOf(c.accMin.z, fa.z))
            c.accMax = Vec3(maxOf(c.accMax.x, fa.x), maxOf(c.accMax.y, fa.y), maxOf(c.accMax.z, fa.z))
            c.accSum += accelNorm
            c.n++
            c.s += dt
            nowS += dt
            if (gate < 0f) gate = settings.staticPeakToPeak
            if (static && (spread(c.max - c.min) >= gate * settings.staticExitShare ||
                    spread(c.accMax - c.accMin) >= settings.staticAccel * accelNorm.coerceAtLeast(1e-6f))
            ) {
                static = false
            }
            if (c.s < chunkS) return null
            chunks.addLast(c)
            current = Chunk()
            val chunkMoved = spread(c.accMax - c.accMin) >= settings.staticAccel * (c.accSum / c.n).toFloat() ||
                spread(c.max - c.min) >= settings.staticGateMax * 2f
            if (chunkMoved) stillSinceS = -1f else if (stillSinceS < 0f) stillSinceS = nowS - c.s
            val keep = kotlin.math.ceil(settings.staticWindowS / chunkS).toInt().coerceAtLeast(1)
            while (chunks.size > keep) chunks.removeFirst()
            if (chunks.size < keep) {
                static = false
                return null
            }
            var mn = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var mx = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var sum = Vec3.ZERO
            var n = 0
            var firstSum = Vec3.ZERO
            var firstN = 0
            val half = chunks.size / 2
            var aMin = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var aMax = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var aSum = 0.0
            for ((i, ch) in chunks.withIndex()) {
                if (i < half) {
                    firstSum = firstSum + ch.sum
                    firstN += ch.n
                }
                mn = Vec3(minOf(mn.x, ch.min.x), minOf(mn.y, ch.min.y), minOf(mn.z, ch.min.z))
                mx = Vec3(maxOf(mx.x, ch.max.x), maxOf(mx.y, ch.max.y), maxOf(mx.z, ch.max.z))
                sum = sum + ch.sum
                n += ch.n
                aMin = Vec3(minOf(aMin.x, ch.accMin.x), minOf(aMin.y, ch.accMin.y), minOf(aMin.z, ch.accMin.z))
                aMax = Vec3(maxOf(aMax.x, ch.accMax.x), maxOf(aMax.y, ch.accMax.y), maxOf(aMax.z, ch.accMax.z))
                aSum += ch.accSum
            }
            val aMean = (aSum / n).toFloat()
            lastPeakToPeak = spread(mx - mn)
            lastAccelSpread = if (aMean > 1e-6f) spread(aMax - aMin) / aMean else 1f
            val accelSteady = aMean > 1e-6f && spread(aMax - aMin) < settings.staticAccel * aMean
            if (settings.adaptiveStaticGate && accelSteady && lastPeakToPeak >= gate && lastPeakToPeak < settings.staticGateMax * 2f) {
                quietWindows.add(lastPeakToPeak.toDouble())
                quietFailed++
                if (quietFailed >= settings.staticGateWindows) {
                    gate = (quietWindows.percentile(0.99).toFloat() * 1.5f).coerceIn(gate, settings.staticGateMax)
                    quietFailed = 0
                }
            } else if (!accelSteady || lastPeakToPeak < gate) {
                quietFailed = 0
            }
            val p2p = gate
            static = mx.x - mn.x < p2p && mx.y - mn.y < p2p && mx.z - mn.z < p2p && accelSteady
            if (!static) return null
            val a = firstSum * (1f / firstN.coerceAtLeast(1))
            val b = (sum - firstSum) * (1f / (n - firstN).coerceAtLeast(1))
            val d = a - b
            val h = settings.staticHalves
            return if (abs(d.x) < h && abs(d.y) < h && abs(d.z) < h) sum * (1f / n) else null
        }

        private fun spread(v: Vec3) = maxOf(v.x, v.y, v.z)

        fun reset() {
            chunks.clear()
            current = Chunk()
            lp = null
            lpAccel = null
            static = false
            stillSinceS = -1f
            quietWindows.clear()
            quietFailed = 0
        }
    }

    /** 0.5.54's TremorPredictor.worn() (band weight 0, so its band check and band rows never ran), as it was. */
    private class RefTremor {
        val taps = 64
        val tapMs = 2.0
        val horizonsMs = doubleArrayOf(4.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0, 40.0, 46.0, 52.0, 60.0)
        val memoryS = 2.0
        val loading = 0.01
        val priorMs = 24.0
        val solveEveryMs = 100.0
        val maxGapMs = 4.0
        val exactOrder = 2
        private val nh = horizonsMs.size
        private val cap = 256
        private val tNs = LongArray(cap)
        private val wx = DoubleArray(cap)
        private val wy = DoubleArray(cap)
        private val wz = DoubleArray(cap)
        private val ix = DoubleArray(cap)
        private val iy = DoubleArray(cap)
        private val iz = DoubleArray(cap)
        private val sx = DoubleArray(cap)
        private val sy = DoubleArray(cap)
        private val sz = DoubleArray(cap)
        private var n = 0L
        private var filled = 0
        private var stepMs = 1.0
        private var m = 2
        private val feat = Array(3) { Array(cap) { DoubleArray(taps) } }
        private val featOk = BooleanArray(cap)
        private val r = Array(taps) { DoubleArray(taps) }
        private val rh = Array(nh) { DoubleArray(taps) }
        private var learned = 0.0
        private var sinceSolveMs = 0.0
        private val weights = Array(nh) { DoubleArray(taps) }
        private val priors = Array(nh) { DoubleArray(taps) }
        var solves = 0
        private val blendNum = DoubleArray(nh)
        private val blendDen = DoubleArray(nh)
        private val blendN = DoubleArray(nh)
        private val bpX1 = DoubleArray(3)
        private val bpX2 = DoubleArray(3)
        private val bpY1 = DoubleArray(3)
        private val bpY2 = DoubleArray(3)
        private var bpRate = 0.0
        private var bpB0 = 0.0
        private var bpA1 = 0.0
        private var bpA2 = 0.0
        private var bandRatePower = 0.0
        var bandPowerRad2 = 0.0

        init {
            for (i in 0 until nh) {
                prior(horizonsMs[i] / 1000.0).copyInto(priors[i])
                priors[i].copyInto(weights[i])
            }
        }

        private fun back(k: Int): Double = (k * m + (m - 1) / 2.0) * stepMs / 1000.0

        fun prior(h: Double): DoubleArray {
            val p = (priorMs / tapMs).roundToInt().coerceIn(4, taps)
            val s = DoubleArray(p) { -back(it) }
            val g = Array(3) { i -> DoubleArray(3) { j -> if (i == j && i >= exactOrder) 1.0 else 0.0 } }
            for (k in 0 until p) {
                val b = doubleArrayOf(1.0, s[k], s[k] * s[k])
                for (i in 0 until exactOrder) for (j in 0 until exactOrder) g[i][j] += b[i] * b[j]
            }
            val inv = inverse3(g)
            val t = doubleArrayOf(h, h * h / 2, if (exactOrder >= 3) h * h * h / 3 else 0.0)
            val u = DoubleArray(3) { i -> (0 until 3).sumOf { j -> t[j] * inv[j][i] } }
            return DoubleArray(taps) { k -> if (k >= p) 0.0 else u[0] + u[1] * s[k] + u[2] * s[k] * s[k] }
        }

        private fun inverse3(m: Array<DoubleArray>): Array<DoubleArray> {
            val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
            val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
            val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            return arrayOf(
                doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
                doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
                doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
            )
        }

        val ready: Boolean get() = filled >= taps * m + 1

        fun clear() {
            filled = 0
            for (i in featOk.indices) featOk[i] = false
        }

        private fun slot(j: Long) = ((j % cap + cap) % cap).toInt()

        fun add(hostNs: Long, omega: Vec3, learnNow: Boolean = true) {
            if (filled > 0) {
                val last = tNs[slot(n - 1)]
                val gapMs = (hostNs - last) / 1e6
                if (gapMs <= 0.0) return
                if (gapMs > maxGapMs) clear() else stepMs += (gapMs - stepMs) * 0.01
            }
            val i = slot(n)
            val prev = slot(n - 1)
            val dt = if (filled > 0) (hostNs - tNs[prev]) / 1e9 else stepMs / 1000.0
            tNs[i] = hostNs
            wx[i] = omega.x.toDouble()
            wy[i] = omega.y.toDouble()
            wz[i] = omega.z.toDouble()
            val base = filled > 0
            ix[i] = (if (base) ix[prev] else 0.0) + wx[i] * dt
            iy[i] = (if (base) iy[prev] else 0.0) + wy[i] * dt
            iz[i] = (if (base) iz[prev] else 0.0) + wz[i] * dt
            sx[i] = (if (base) sx[prev] else 0.0) + wx[i]
            sy[i] = (if (base) sy[prev] else 0.0) + wy[i]
            sz[i] = (if (base) sz[prev] else 0.0) + wz[i]
            if (filled == 0) m = (tapMs / stepMs).roundToInt().coerceAtLeast(1)
            n++
            if (filled < cap) filled++
            featOk[i] = false
            if (!ready) return
            features(i)
            if (!learnNow) return
            learnStep(i, dt)
        }

        private fun features(i: Int) {
            val fx = feat[0][i]
            val fy = feat[1][i]
            val fz = feat[2][i]
            val newest = n - 1
            for (k in 0 until taps) {
                val end = slot(newest - k * m)
                val start = slot(newest - (k + 1) * m)
                fx[k] = (sx[end] - sx[start]) / m
                fy[k] = (sy[end] - sy[start]) / m
                fz[k] = (sz[end] - sz[start]) / m
            }
            featOk[i] = true
        }

        private fun learnStep(i: Int, dt: Double) {
            trackBand(dt)
            val a = exp(-dt / memoryS)
            for (p in 0 until taps) {
                val rp = r[p]
                val x0 = feat[0][i][p]
                val x1 = feat[1][i][p]
                val x2 = feat[2][i][p]
                for (q in 0..p) rp[q] = rp[q] * a + x0 * feat[0][i][q] + x1 * feat[1][i][q] + x2 * feat[2][i][q]
            }
            learned = learned * a + 1.0
            val newest = n - 1
            for (h in 0 until nh) {
                for (q in 0 until taps) rh[h][q] *= a
                val back = (horizonsMs[h] / stepMs).roundToInt()
                if (back >= filled - 1) continue
                val j = slot(newest - back)
                if (!featOk[j]) continue
                val tx = ix[i] - ix[j]
                val ty = iy[i] - iy[j]
                val tz = iz[i] - iz[j]
                val fx = feat[0][j]
                val fy = feat[1][j]
                val fz = feat[2][j]
                val row = rh[h]
                for (q in 0 until taps) row[q] += fx[q] * tx + fy[q] * ty + fz[q] * tz
                blendStep(h, a, fx, fy, fz, tx, ty, tz)
            }
            sinceSolveMs += dt * 1000
            if (sinceSolveMs >= solveEveryMs && learned > 200) {
                sinceSolveMs = 0.0
                solve()
            }
        }

        private fun blendStep(h: Int, a: Double, fx: DoubleArray, fy: DoubleArray, fz: DoubleArray, tx: Double, ty: Double, tz: Double) {
            if (solves == 0) return
            val c = weights[h]
            val p = priors[h]
            var lx = 0.0; var ly = 0.0; var lz = 0.0
            var px = 0.0; var py = 0.0; var pz = 0.0
            for (k in 0 until taps) {
                lx += c[k] * fx[k]; ly += c[k] * fy[k]; lz += c[k] * fz[k]
                px += p[k] * fx[k]; py += p[k] * fy[k]; pz += p[k] * fz[k]
            }
            val dx = lx - px; val dy = ly - py; val dz = lz - pz
            blendNum[h] = blendNum[h] * a + (tx - px) * dx + (ty - py) * dy + (tz - pz) * dz
            blendDen[h] = blendDen[h] * a + dx * dx + dy * dy + dz * dz
            blendN[h] = blendN[h] * a + 1.0
        }

        fun learnedShareAt(h: Int): Double {
            if (blendN[h] < TremorPredictor.BLEND_MIN_SAMPLES || blendDen[h] <= 1e-30) return 1.0
            return (blendNum[h] / blendDen[h]).coerceIn(0.0, 1.0)
        }

        fun learnedShare(aheadMs: Double): Double {
            val h = aheadMs.coerceIn(0.0, horizonsMs.last())
            if (h <= horizonsMs[0]) return learnedShareAt(0)
            var i = 0
            while (i + 1 < nh && horizonsMs[i + 1] < h) i++
            val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
            return learnedShareAt(i) * (1 - f) + learnedShareAt(i + 1) * f
        }

        private fun trackBand(dt: Double) {
            val fs = 1000.0 / stepMs
            if (abs(fs - bpRate) > 1.0) {
                bpRate = fs
                val fc = sqrt(TremorPredictor.BAND_LOW_HZ * TremorPredictor.BAND_HIGH_HZ)
                val w0 = 2 * PI * fc / fs
                val alpha = sin(w0) / (2 * fc / (TremorPredictor.BAND_HIGH_HZ - TremorPredictor.BAND_LOW_HZ))
                val a0 = 1 + alpha
                bpB0 = alpha / a0
                bpA1 = -2 * kotlin.math.cos(w0) / a0
                bpA2 = (1 - alpha) / a0
            }
            val i = slot(n - 1)
            val v = doubleArrayOf(wx[i], wy[i], wz[i])
            var p = 0.0
            for (ax in 0 until 3) {
                val y = bpB0 * v[ax] - bpB0 * bpX2[ax] - bpA1 * bpY1[ax] - bpA2 * bpY2[ax]
                bpX2[ax] = bpX1[ax]
                bpX1[ax] = v[ax]
                bpY2[ax] = bpY1[ax]
                bpY1[ax] = y
                p += y * y
            }
            val k = 1 - exp(-dt / memoryS)
            bandRatePower += (p - bandRatePower) * k
            val wc = 2 * PI * sqrt(TremorPredictor.BAND_LOW_HZ * TremorPredictor.BAND_HIGH_HZ)
            bandPowerRad2 = bandRatePower / (wc * wc)
        }

        private fun solve() {
            val a = Array(taps) { p -> DoubleArray(taps) { q -> if (q <= p) r[p][q] else r[q][p] } }
            var trace = 0.0
            for (p in 0 until taps) trace += a[p][p]
            val mu = loading * trace / taps + 1e-12
            for (p in 0 until taps) a[p][p] += mu
            val extra: DoubleArray? = null
            val w = fit(a, mu, extra) ?: return
            for (h in 0 until nh) w[h].copyInto(weights[h])
            solves++
        }

        private fun fit(a: Array<DoubleArray>, mu: Double, extra: DoubleArray?): Array<DoubleArray>? {
            val l = cholesky(a) ?: return null
            val cs = arrayOf(DoubleArray(taps) { 1.0 }, DoubleArray(taps) { -back(it) }, DoubleArray(taps) { back(it) * back(it) })
            val us = Array(3) { solveWith(l, cs[it]) }
            val g = Array(3) { i -> DoubleArray(3) { j -> if (i < exactOrder && j < exactOrder) dot(cs[i], us[j]) else if (i == j) 1.0 else 0.0 } }
            val gi = inverse3(g)
            if (gi.any { row -> row.any { !it.isFinite() } }) return null
            return Array(nh) { h ->
                val hs = horizonsMs[h] / 1000.0
                val b = DoubleArray(taps) { rh[h][it] + mu * priors[h][it] + (extra?.get(it) ?: 0.0) }
                val x = solveWith(l, b)
                val want = doubleArrayOf(hs, hs * hs / 2, hs * hs * hs / 3)
                val e = DoubleArray(3) { if (it < exactOrder) want[it] - dot(cs[it], x) else 0.0 }
                val y = DoubleArray(3) { i -> (0 until 3).sumOf { j -> gi[i][j] * e[j] } }
                DoubleArray(taps) { q ->
                    val v = x[q] + us[0][q] * y[0] + us[1][q] * y[1] + us[2][q] * y[2]
                    if (!v.isFinite()) return null
                    v
                }
            }
        }

        fun weightsFor(aheadMs: Double): DoubleArray {
            val h = aheadMs.coerceIn(0.0, horizonsMs.last())
            if (h <= horizonsMs[0]) {
                val f = h / horizonsMs[0]
                val p = prior(h / 1000.0)
                return DoubleArray(taps) { p[it] + (weights[0][it] - priors[0][it]) * f * f }
            }
            var i = 0
            while (i + 1 < nh && horizonsMs[i + 1] < h) i++
            val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
            return DoubleArray(taps) { weights[i][it] * (1 - f) + weights[i + 1][it] * f }
        }

        private fun priorsFor(h: Double): DoubleArray {
            if (h <= horizonsMs[0]) return prior(h / 1000.0)
            var i = 0
            while (i + 1 < nh && horizonsMs[i + 1] < h) i++
            val f = (h - horizonsMs[i]) / (horizonsMs[i + 1] - horizonsMs[i])
            return DoubleArray(taps) { priors[i][it] * (1 - f) + priors[i + 1][it] * f }
        }

        fun turn(aheadMs: Double, learnedShare: Double = 1.0): Vec3? {
            if (!ready) return null
            val i = slot(n - 1)
            if (!featOk[i]) return null
            val learned = weightsFor(aheadMs)
            val share = learnedShare * this.learnedShare(aheadMs)
            val c = if (share >= 1.0) learned else {
                val h = aheadMs.coerceIn(0.0, horizonsMs.last())
                val exact = prior(h / 1000.0)
                val interp = priorsFor(h)
                DoubleArray(taps) { exact[it] + (learned[it] - interp[it]) * share }
            }
            var x = 0.0
            var y = 0.0
            var z = 0.0
            val fx = feat[0][i]
            val fy = feat[1][i]
            val fz = feat[2][i]
            for (k in 0 until taps) {
                x += c[k] * fx[k]
                y += c[k] * fy[k]
                z += c[k] * fz[k]
            }
            return Vec3(x.toFloat(), y.toFloat(), z.toFloat())
        }

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun cholesky(a: Array<DoubleArray>): Array<DoubleArray>? {
            val k = a.size
            val l = Array(k) { DoubleArray(k) }
            for (i in 0 until k) {
                for (j in 0..i) {
                    var s = a[i][j]
                    for (p in 0 until j) s -= l[i][p] * l[j][p]
                    if (i == j) {
                        if (s <= 0.0 || !s.isFinite()) return null
                        l[i][i] = sqrt(s)
                    } else {
                        l[i][j] = s / l[j][j]
                    }
                }
            }
            return l
        }

        private fun solveWith(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val k = b.size
            val y = DoubleArray(k)
            for (i in 0 until k) {
                var s = b[i]
                for (p in 0 until i) s -= l[i][p] * y[p]
                y[i] = s / l[i][i]
            }
            val x = DoubleArray(k)
            for (i in k - 1 downTo 0) {
                var s = y[i]
                for (p in i + 1 until k) s -= l[p][i] * x[p]
                x[i] = s / l[i][i]
            }
            return x
        }
    }

    /** 0.5.54's HeadPredictor() with its defaults (One Euro, still head, the worn tremor predictor, the still blend), as it was. */
    private class RefPredictor {
        val rateTauMs = 3f
        val accelTauMs = 10f
        val accelWeight = 1f
        val maxAheadMs = 80f
        val maxAccel = 400f
        val restRate = 0f
        val restRamp = 0f
        val activityMs = 300f
        val accelFullRate = 0.3f
        val euroMinHz = 6f
        val euroBetaRate = 40000f
        val euroRateFloor = 0.002f
        val euroBetaAccel = 40f
        val euroAccelFloor = 2f
        val stillShare = 0.05f
        val stillRiseMs = 300f
        val lyingRmsEnter = 0.0018f
        val lyingRmsExit = 0.0024f
        val tremorFullBelow = 0.08f
        val tremorNoneAbove = 0.3f
        val blendRefMs = 30f
        val blendTauMs = 160f
        val blendReleaseLow = 3f
        val blendReleaseHigh = 6f
        val blendSpeedLow = 0.1f
        val blendSpeedHigh = 0.25f
        val tremor = RefTremor()

        private val blendT = LongArray(256)
        private val blendP = Array(256) { DoubleArray(3) }
        private val blendI = Array(256) { DoubleArray(3) }
        private var blendHead = 0
        private var blendTail = 0
        private val integral = DoubleArray(3)
        private var blendMade = 0.0
        private var blendPred = 0.0
        private var blendWeight = 0.0
        private var blendNow = 0.0
        private var blendCount = 0
        var blendGain = 1f
        var blendShare = 1f

        private fun blendClear() {
            blendHead = 0
            blendTail = 0
            integral.fill(0.0)
            blendMade = 0.0
            blendPred = 0.0
            blendWeight = 0.0
            blendNow = 0.0
            blendCount = 0
            blendGain = 1f
            blendShare = 1f
        }

        private fun blendStep(omega: Vec3, hostNs: Long, dtMs: Float) {
            val t = tremor
            val dt = dtMs / 1000.0
            integral[0] += omega.x * dt
            integral[1] += omega.y * dt
            integral[2] += omega.z * dt
            val refNs = (blendRefMs * 1e6f).toLong()
            val k = dtMs / (blendTauMs + dtMs).toDouble()
            while (blendTail != blendHead && blendT[blendTail] + refNs <= hostNs) {
                val p = blendP[blendTail]
                val i0 = blendI[blendTail]
                var made = 0.0
                var pp = 0.0
                for (a in 0 until 3) {
                    made += (integral[a] - i0[a]) * p[a]
                    pp += p[a] * p[a]
                }
                blendMade += (made - blendMade) * k
                blendPred += (pp - blendPred) * k
                blendWeight += (1.0 - blendWeight) * k
                blendCount++
                blendTail = (blendTail + 1) % 256
            }
            blendGain = if (blendCount < 200 || blendPred <= 1e-18) 1f else (blendMade / blendPred).coerceIn(0.0, 1.0).toFloat()
            val p = t.turn(blendRefMs.toDouble(), tremorShare.toDouble())
            if (p == null) {
                blendNow = 0.0
                return
            }
            val slot = blendHead
            blendT[slot] = hostNs
            blendP[slot][0] = p.x.toDouble()
            blendP[slot][1] = p.y.toDouble()
            blendP[slot][2] = p.z.toDouble()
            integral.copyInto(blendI[slot])
            blendHead = (blendHead + 1) % 256
            if (blendHead == blendTail) blendTail = (blendTail + 1) % 256
            blendNow = (p dot p).toDouble()
            blendShare = blendShareNow()
        }

        private fun blendShareNow(): Float {
            val rms = if (blendWeight > 0.0) sqrt((blendPred / blendWeight).toFloat()) else 0f
            val z = if (rms > 0f) sqrt(blendNow.toFloat()) / rms else Float.MAX_VALUE
            fun smooth(x: Float): Float {
                val c = x.coerceIn(0f, 1f)
                return c * c * (3f - 2f * c)
            }
            val release = maxOf(
                smooth((z - blendReleaseLow) / (blendReleaseHigh - blendReleaseLow)),
                smooth((turnSpeed - blendSpeedLow) / (blendSpeedHigh - blendSpeedLow)),
            )
            return blendGain + (1f - blendGain) * release
        }

        private var lyingMean = Vec3.ZERO
        private var lyingRate = Vec3.ZERO
        private var lyingVar = 1e-4f
        val lyingRms: Float get() = sqrt(lyingVar)
        var turnSpeed = 0f
        private var streamMs = 0f

        val tremorShare: Float get() {
            val x = ((turnSpeed - tremorFullBelow) / (tremorNoneAbove - tremorFullBelow)).coerceIn(0f, 1f)
            return 1f - x * x * (3f - 2f * x)
        }

        private fun rateTau(): Float {
            val speed = (euroBetaRate * (fastRate.length - euroRateFloor).coerceAtLeast(0f)) +
                (euroBetaAccel * (fastAccel - euroAccelFloor).coerceAtLeast(0f))
            val openHz = 1000f / (2f * Math.PI.toFloat() * rateTauMs)
            val hz = (euroMinHz + speed).coerceIn(euroMinHz, openHz)
            return 1000f / (2f * Math.PI.toFloat() * hz)
        }

        private var fastRate = Vec3.ZERO
        private var fastAccel = 0f
        var stillWeight = 0f
        private var stillSlide = 0f
        private var stillWanted = false
        val predictionShare: Float get() = 1f - stillWeight * (1f - stillShare)
        var orientation: Quat? = null
        var sampleNs = 0L
        var rate = Vec3.ZERO
        private var accel = Vec3.ZERO
        private var activity = 0f

        fun add(q: Quat, omega: Vec3, hostNs: Long) {
            val prev = orientation
            val dtMs = if (prev == null) 0f else (hostNs - sampleNs) / 1e6f
            if (prev == null || dtMs <= 0f || dtMs > 100f) {
                rate = omega
                accel = Vec3.ZERO
                activity = omega.length
                fastRate = omega
                fastAccel = 0f
                stillSlide = 0f
                stillWanted = false
                stillWeight = 0f
                lyingMean = omega
                lyingRate = omega
                turnSpeed = omega.length
                lyingVar = 1e-4f
                streamMs = 0f
                tremor.clear()
                blendClear()
            } else {
                val kf = dtMs / (rateTauMs + dtMs)
                val nextFast = fastRate + (omega - fastRate) * kf
                val a0 = (nextFast - fastRate).length * (1000f / dtMs)
                fastAccel += (a0 - fastAccel) * (dtMs / (accelTauMs + dtMs))
                fastRate = nextFast
                val k = dtMs / (rateTau() + dtMs)
                val next = rate + (omega - rate) * k
                val a = (next - rate) * (1000f / dtMs)
                val ka = dtMs / (accelTauMs + dtMs)
                accel = accel + (a - accel) * ka
                if (accel.length > maxAccel) accel = accel * (maxAccel / accel.length)
                rate = next
                activity = maxOf(rate.length, activity * exp(-dtMs / activityMs))
                streamMs += dtMs
                turnSpeed = maxOf(omega.length, turnSpeed * exp(-dtMs / 100f))
                lyingRate += (omega - lyingRate) * (dtMs / (10.6f + dtMs))
                val kl = dtMs / (100f + dtMs)
                lyingMean += (lyingRate - lyingMean) * kl
                val d = lyingRate - lyingMean
                lyingVar += (minOf(d dot d, 16f * lyingRmsExit * lyingRmsExit) - lyingVar) * kl
                lyingStep(dtMs)
            }
            tremor.add(hostNs, omega, learnNow = stillWeight < 0.5f && streamMs >= 500f)
            if (prev != null && dtMs > 0f && dtMs <= 100f) blendStep(omega, hostNs, dtMs)
            orientation = q
            sampleNs = hostNs
        }

        private fun lyingStep(dtMs: Float) {
            val rms = lyingRms
            if (rms <= lyingRmsEnter) stillWanted = true else if (rms >= lyingRmsExit) stillWanted = false
            stillSlide = if (stillWanted) {
                stillSlide + (1f - stillSlide) * (dtMs / (stillRiseMs + dtMs))
            } else {
                stillSlide * (1f - dtMs / (20f + dtMs))
            }
            val x = (((fastRate - lyingMean).length - 0.005f) / 0.005f).coerceIn(0f, 1f)
            stillWeight = minOf(stillSlide, 1f - x * x * (3f - 2f * x))
        }

        fun predict(targetNs: Long): Quat? {
            val q = orientation ?: return null
            val dt = ((targetNs - sampleNs) / 1e9f).coerceIn(0f, maxAheadMs / 1000f)
            if (dt <= 0f) return q
            val share = predictionShare
            val turn = tremor.turn(dt * 1000.0, tremorShare.toDouble())
            if (turn != null) {
                val blend = blendShare
                return (q * Quat.fromGyro(turn * (share * blend), 1f)).normalized()
            }
            val ramp = if (restRamp <= 0f) (if (activity >= restRate) 1f else 0f) else ((activity - restRate) / restRamp).coerceIn(0f, 1f)
            if (ramp <= 0f) return q
            val accelShare = if (accelFullRate <= 0f) 1f else (activity / accelFullRate).coerceIn(0f, 1f)
            val step = (rate * dt + accel * (accelWeight * accelShare * dt * dt / 2f)) * (ramp * share)
            return (q * Quat.fromGyro(step, 1f)).normalized()
        }
    }
}
