package com.hayeco.flipspaces.core.spatial

import kotlin.math.sqrt

/**
 * 0.5.65: the glasses' own factory calibration, the JSON they answer control request 10015 with (about 225 KB on the
 * Aura), as our own captures of the glasses' USB network traffic show it. It is asked for when the control link opens.
 *
 * Two parts of it are used:
 * - [gyroMatrix]: `IMU.device_1.imu_intrinsics.gyro_calib_mat`, 3x3 row-major, applied to the raw gyro. On Mike's Aura
 *   one axis reads 0.39 % fast, about 0.35 deg over a 90 deg turn.
 * - [displayMount]: `display.target_q_left_display`, the displays' orientation in the IMU's frame, as the fusion's mount
 *   (display axes to IMU axes, [HeadFusion]). The JSON's camera axes (x right, y down, z forward) are turned to the
 *   fusion's (x right, y up, z back). Read as a Hamilton quaternion it gives pitch +8.0 deg, roll -0.5 deg, where Mike's
 *   hand level calibration found +10.65 and +0.74; read as JPL (transposed) the pitch sign flips. It also carries a 5.4 deg
 *   twist about the up axis that a level calibration cannot measure: without it a nod moves the picture about 9 % sideways.
 */
data class FactoryCalibration(
    val serial: String?,
    val version: Int?,
    val modified: String?,
    val gyroMatrix: FloatArray?,
    val displayMount: Quat?,
    /**
     * 1.2.13 (Discussion #81, a One Pro+ whose head read 30 to 45 degrees up and whose log said only "no display mount"):
     * why [displayMount] is null, the key missing or the mount refused with its angle; null when there is a mount.
     */
    val mountNote: String? = null,
) {
    /** The mount's roll, pitch (as [LevelCalibration] reads them) and twist about up, degrees. */
    fun mountAngles(): Triple<Float, Float, Float>? {
        val m = displayMount ?: return null
        val up = m.rotate(Vec3.UP)
        val right = m.rotate(Vec3.RIGHT)
        val roll = Math.toDegrees(kotlin.math.atan2(up.x.toDouble(), up.y.toDouble())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.atan2(up.z.toDouble(), up.y.toDouble())).toFloat()
        val twist = Math.toDegrees(kotlin.math.atan2(right.z.toDouble(), right.x.toDouble())).toFloat()
        return Triple(roll, pitch, twist)
    }

    fun line(): String {
        val g = gyroMatrix?.let { "gyro scale %.4f %.4f %.4f".format(java.util.Locale.ROOT, it[0], it[4], it[8]) } ?: "no gyro matrix"
        val a = mountAngles()?.let { "display mount roll %.2f pitch %.2f twist %.2f deg".format(java.util.Locale.ROOT, it.first, it.second, it.third) } ?: ("no display mount" + (mountNote?.let { " ($it)" } ?: ""))
        return "factory calibration of ${serial ?: "unknown glasses"} (version ${version ?: "?"}, ${modified ?: "no date"}): $g, $a"
    }

    override fun equals(other: Any?): Boolean = other is FactoryCalibration && serial == other.serial &&
        version == other.version && modified == other.modified && displayMount == other.displayMount &&
        (gyroMatrix?.contentEquals(other.gyroMatrix ?: FloatArray(0)) ?: (other.gyroMatrix == null))

    override fun hashCode(): Int = (serial?.hashCode() ?: 0) * 31 + (modified?.hashCode() ?: 0)

    companion object {
        /** The calibration in [json], or null when it has neither part. A wrong-sized matrix or a bad quaternion is left out. */
        fun parse(json: String): FactoryCalibration? {
            val root = JsonLite.parse(json) as? Map<*, *> ?: return null
            val imu = ((root["IMU"] as? Map<*, *>)?.get("device_1") as? Map<*, *>)
            val gyro = ((imu?.get("imu_intrinsics") as? Map<*, *>)?.get("gyro_calib_mat") as? List<*>)
                ?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { m -> m.size == 9 && m.all { it.isFinite() } && plausible(m) }
            val q = ((root["display"] as? Map<*, *>)?.get("target_q_left_display") as? List<*>)
                ?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 4 }
            val mount = q?.let { mountFrom(it[3], it[0], it[1], it[2]) }
            if (gyro == null && mount == null) return null
            val note = when {
                mount != null -> null
                q == null -> "the calibration has no display.target_q_left_display"
                else -> mountRefusal(q[3], q[0], q[1], q[2])
            }
            return FactoryCalibration(
                serial = root["FSN"] as? String,
                version = (root["glasses_version"] as? Number)?.toInt(),
                modified = root["last_modified_time"] as? String,
                gyroMatrix = gyro?.toFloatArray(),
                displayMount = mount,
                mountNote = note,
            )
        }

        /** The most a display mount may turn from the identity, degrees; a larger one is refused ([mountRefusal] says it). */
        const val MAX_MOUNT_DEG = 30f

        /** A gyro matrix near the identity (each term within 0.05 of it): anything else is not this calibration. */
        private fun plausible(m: List<Float>): Boolean = m.withIndex().all { (i, v) -> kotlin.math.abs(v - if (i % 4 == 0) 1f else 0f) < 0.05f }

        /**
         * The fusion's mount from the display quaternion (w, x, y, z), Hamilton: its rotation matrix's columns are the
         * display's camera axes in the IMU's frame; the display's right, up (minus camera y) and back (minus camera z)
         * become the mount's columns. Null unless that is a rotation within [MAX_MOUNT_DEG] of the identity.
         */
        fun mountFrom(w: Float, x: Float, y: Float, z: Float): Quat? =
            anyMountFrom(w, x, y, z)?.takeIf { it.angleTo(Quat.IDENTITY) < MAX_MOUNT_DEG }

        /**
         * 1.2.13 (Discussion #81): why [mountFrom] gave nothing for the display quaternion (w, x, y, z), with the refused
         * mount's angle, roll and pitch so the next log says whether the glasses' optics sit that steep.
         */
        fun mountRefusal(w: Float, x: Float, y: Float, z: Float): String {
            val m = anyMountFrom(w, x, y, z) ?: return "display.target_q_left_display is not a rotation"
            val (roll, pitch, _) = FactoryCalibration(null, null, null, null, m).mountAngles()!!
            return String.format(java.util.Locale.ROOT, "display.target_q_left_display refused: %.1f deg from level, more than %.0f (roll %.1f, pitch %.1f deg)", m.angleTo(Quat.IDENTITY), MAX_MOUNT_DEG, roll, pitch)
        }

        private fun anyMountFrom(w: Float, x: Float, y: Float, z: Float): Quat? {
            val n = sqrt(w * w + x * x + y * y + z * z)
            if (!(n > 0.5f && n < 1.5f)) return null
            val (qw, qx, qy, qz) = listOf(w / n, x / n, y / n, z / n)
            // Columns of R(q): camera x, y, z in IMU axes.
            val cx = Vec3(1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy + qz * qw), 2 * (qx * qz - qy * qw))
            val cy = Vec3(2 * (qx * qy - qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz + qx * qw))
            val cz = Vec3(2 * (qx * qz + qy * qw), 2 * (qy * qz - qx * qw), 1 - 2 * (qx * qx + qy * qy))
            return quatFromColumns(cx, -cy, -cz)
        }

        /** The rotation whose matrix has columns [a], [b], [c] (each a unit axis). */
        private fun quatFromColumns(a: Vec3, b: Vec3, c: Vec3): Quat {
            val m00 = a.x; val m10 = a.y; val m20 = a.z
            val m01 = b.x; val m11 = b.y; val m21 = b.z
            val m02 = c.x; val m12 = c.y; val m22 = c.z
            val tr = m00 + m11 + m22
            return if (tr > 0) {
                val s = sqrt(tr + 1f) * 2
                Quat(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2
                Quat((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2
                Quat((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2
                Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
            }.normalized()
        }
    }
}

/** Applies a 3x3 row-major [m] to [v]. */
fun applyMatrix(m: FloatArray, v: Vec3): Vec3 = Vec3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
)

/** 0.5.65: a small JSON reader for the glasses' calibration (the core module has no JSON library). Objects become maps. */
object JsonLite {
    fun parse(s: String): Any? = Reader(s).run { value().also { skipSpace() } }

    private class Reader(private val s: String) {
        private var i = 0

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpace()
            if (i >= s.length) throw IllegalArgumentException("end of JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad JSON at $i")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("bad JSON at $i")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (s[i] == '}') return out.also { i++ }
            while (true) {
                skipSpace()
                val k = str()
                skipSpace()
                if (s[i++] != ':') throw IllegalArgumentException("bad JSON at $i")
                out[k] = value()
                skipSpace()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("bad JSON at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipSpace()
            if (s[i] == ']') return out.also { i++ }
            while (true) {
                out += value()
                skipSpace()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("bad JSON at $i")
                }
            }
        }

        private fun str(): String {
            if (s[i] != '"') throw IllegalArgumentException("bad JSON at $i")
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') {
                    b.append(c)
                    continue
                }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> b.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
