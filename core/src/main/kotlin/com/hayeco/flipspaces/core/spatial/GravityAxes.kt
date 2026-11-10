package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.acos

/**
 * 1.2.13 (Discussion #81, gegonut's XREAL One Pro+ on a Galaxy Z Fold 6: "When looking straight ahead it's as if I'm
 * looking straight up. When I recenter, all of the windows appear straight down"): a pitch off by exactly 90 degrees is
 * gravity on the sensor axis the layout takes for front or back. Every layout the app had for an XREAL model puts gravity
 * on the sensor's y, so a pair whose sensor reads it along z could not come out right.
 *
 * The safety net: at rest, at the start and on each recentre, the averaged accelerometer in the sensor's own axes is
 * checked against the layout in use. When gravity is far from that layout's up and close to another layout's, that
 * layout is picked: the model's own default if it fits, else the one the smallest turn from the layout in use (a pitch
 * error is mended about the side axis, so right stays right). Glasses lying still on a table are left alone at the start
 * ([check]'s `tableStill`): they may lie any way up.
 */
object GravityAxes {
    /** Gravity within about 25 degrees of a layout's up fits it ([AxisMap.suggest] uses the same). */
    const val FIT_COS = 0.9f

    /** The layout in use is kept while gravity is within 60 degrees of its up (a head looking down or up a little). */
    const val KEEP_COS = 0.5f

    /** A mean this far from 1 g (share) is not gravity alone: nothing is judged. */
    const val SIZE_TOLERANCE = 0.3f

    /** A rest reading of the accelerometer, in the sensor's own axes, and when it was taken. */
    data class Rest(val raw: Vec3, val why: String, val tableStill: Boolean = false)

    /** What [check] decided: [axes] to switch to, or null to keep; [line] for the log and the Glasses report. */
    data class Verdict(val axes: AxisMap?, val line: String)

    /** Degrees between gravity's reaction ([raw], sensor axes) and [axes]' up. */
    fun angleFromUp(raw: Vec3, axes: AxisMap): Float {
        val n = raw.length
        if (n < 1e-3f) return Float.NaN
        return acos((axes.apply(raw).y / n).coerceIn(-1f, 1f)) * RAD
    }

    /** How alike two layouts are: the cosine sum of their axes (3 the same, -1 a half turn apart). */
    fun likeness(a: AxisMap, b: AxisMap): Float {
        var s = 0f
        for (e in BASIS) s += a.apply(e) dot b.apply(e)
        return s
    }

    /**
     * The layout [rest] says, given [inUse] and the model's default [preferred]; null in [Verdict.axes] keeps [inUse].
     */
    fun check(rest: Rest, inUse: AxisMap, preferred: AxisMap): Verdict {
        val raw = rest.raw
        val n = raw.length
        val vector = String.format(Locale.ROOT, "%.2f, %.2f, %.2f m/s2", raw.x, raw.y, raw.z)
        val head = "gravity check (${rest.why}): sensor $vector"
        if (n < StartGate.GRAVITY * (1f - SIZE_TOLERANCE) || n > StartGate.GRAVITY * (1f + SIZE_TOLERANCE)) {
            return Verdict(null, "$head, not 1 g, so not judged; axes ${inUse.label} kept")
        }
        val off = angleFromUp(raw, inUse)
        if (inUse.apply(raw).y / n >= KEEP_COS) {
            return Verdict(null, String.format(Locale.ROOT, "%s, %.0f deg from up on %s: kept", head, off, inUse.label))
        }
        val fits = AxisMap.entries.filter { it.apply(raw).y / n >= FIT_COS }
        if (fits.isEmpty()) {
            return Verdict(null, String.format(Locale.ROOT, "%s, %.0f deg from up on %s, and on no layout's axis: kept", head, off, inUse.label))
        }
        if (rest.tableStill) {
            return Verdict(null, String.format(Locale.ROOT, "%s, %.0f deg from up on %s; the glasses lie still (not worn?), so kept; %s would fit", head, off, inUse.label, fits.joinToString(" or ") { it.label }))
        }
        val pick = if (preferred in fits) preferred else fits.maxWith(compareBy<AxisMap>({ likeness(it, inUse) }, { likeness(it, preferred) }))
        return Verdict(pick, String.format(Locale.ROOT, "%s, %.0f deg from up on %s: axes now %s (%.0f deg from up)", head, off, inUse.label, pick.label, angleFromUp(raw, pick)))
    }

    /** [b] (head axes of [from]) in the head axes of [to]: a gyro bias kept across a layout change. */
    fun carry(b: Vec3, from: AxisMap, to: AxisMap): Vec3 = to.apply(from.unapply(b))

    /** One line for the Glasses report: the layout, where it came from, and the last rest's gravity. */
    fun reportLine(axes: AxisMap, from: String, rest: Vec3?, lastCheck: String?): String = buildString {
        append(axes.label).append(" (").append(from).append(')')
        if (rest != null) {
            append(String.format(Locale.ROOT, "; gravity at the last rest, sensor axes %.2f, %.2f, %.2f m/s2, %.0f deg from this layout's up", rest.x, rest.y, rest.z, angleFromUp(rest, axes)))
        } else {
            append("; no rest reading yet")
        }
        lastCheck?.let { append("; last ").append(it) }
    }

    private val BASIS = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
}
