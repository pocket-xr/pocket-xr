package com.hayeco.flipspaces.pcvr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene shown while the PC and SteamVR get ready: a calm, dark room with a soft blue horizon (close to SteamVR's own,
 * so the hand-over feels like the lights coming up, not a cut), and one card in front of you that says what is happening.
 *
 * It is fixed in the world, so turning your head looks around it instead of dragging a frozen picture along. If you turn
 * well away, the card drifts back in front of you. When the stream is steady it fades out over the live picture.
 *
 * All calls on the GL thread.
 */
class PcVrLoading {
    enum class Phase { FINDING_PC, STARTING, RECONNECTING, LEAVING, STALLED }

    /** Each Joy-Con: not reporting, reporting, or rested (its drift measured this session). */
    enum class Pad { NONE, AWAKE, RESTED }

    private var program = 0
    private var cardTex = 0
    private var cardKey = ""
    private var startNs = 0L
    private var anchorYaw = Float.NaN
    private val anchor = FloatArray(4)
    private val view = FloatArray(9)
    private val bitmap = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    /** After the GL context is made. */
    fun create(vertex: String) {
        program = link(vertex, FRAGMENT)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        cardTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cardTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        cardKey = ""
        startNs = System.nanoTime()
    }

    /**
     * Draws the scene into each eye's half of the bound surface ([width] x [height]), at [alpha] over what is already
     * there. [head] is the head now (x y z w), [tans] each eye's view (left, right, up, down tangents, left eye first).
     * [quad] is bound by the caller: a strip with position at 0 and uv at 1.
     */
    fun draw(
        nowNs: Long, head: FloatArray, tans: FloatArray, width: Int, height: Int, alpha: Float,
        phase: Phase, waitedS: Float, left: Pad, right: Pad, progress: Float = -1f,
        words: Pair<String, String>? = null,
    ) {
        if (program == 0 || alpha <= 0f) return
        updateCard(phase, waitedS, left, right, words)
        follow(head)
        relative(anchor, head, view)
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cardTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "card"), 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "view"), 1, false, view, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "time"), ((nowNs - startNs) / 1e9).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "alpha"), alpha)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "progress"), progress)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "cardHalf"), CARD_HALF_TAN, CARD_HALF_TAN * CARD_H / CARD_W)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "cardY"), CARD_Y_TAN)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "spinAt"), 0.5f, 1f - SPIN_Y / CARD_H)
        val half = width / 2
        for (eye in 0..1) {
            GLES30.glViewport(eye * half, 0, half, height)
            val o = eye * 4
            GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "tans"), tans[o], tans[o + 1], tans[o + 2], tans[o + 3])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** The card sits where you were looking; turned well away, it eases back in front of you. Yaw only. */
    private fun follow(head: FloatArray) {
        // The head's forward (-z) turned into the world, flattened: its yaw.
        val x = head[0]; val y = head[1]; val z = head[2]; val w = head[3]
        val fx = -(2 * (x * z + y * w))
        val fz = -(1 - 2 * (x * x + y * y))
        val yaw = atan2(-fx, -fz)
        if (anchorYaw.isNaN()) anchorYaw = yaw
        var d = yaw - anchorYaw
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        if (abs(d) > FOLLOW_AFTER_RAD) anchorYaw += (abs(d) - FOLLOW_AFTER_RAD) * (if (d > 0) 1f else -1f) * FOLLOW_RATE
        anchor[0] = 0f; anchor[1] = sin(anchorYaw / 2); anchor[2] = 0f; anchor[3] = cos(anchorYaw / 2)
    }

    /** [words]: the title and line while the PC is being found, from the PC helper; null for the usual ones. */
    private fun updateCard(phase: Phase, waitedS: Float, left: Pad, right: Pad, words: Pair<String, String>?) {
        val slow = phase == Phase.FINDING_PC && waitedS > SLOW_FIND_S
        val key = "$phase $slow $left $right $words"
        if (key == cardKey) return
        cardKey = key
        val (title, line) = when (phase) {
            Phase.FINDING_PC -> words ?: if (slow) FlipSpacesApp.text(R.string.sys_pcvr_still_looking) to FlipSpacesApp.text(R.string.sys_pcvr_still_looking_line)
            else FlipSpacesApp.text(R.string.sys_pcvr_connecting) to FlipSpacesApp.text(R.string.sys_pcvr_few_seconds)
            Phase.STARTING -> FlipSpacesApp.text(R.string.sys_pcvr_starting_steamvr) to FlipSpacesApp.text(R.string.sys_pcvr_almost_there)
            Phase.RECONNECTING -> FlipSpacesApp.text(R.string.sys_pcvr_reconnecting) to FlipSpacesApp.text(R.string.sys_pcvr_reconnecting_line)
            Phase.LEAVING -> FlipSpacesApp.text(R.string.sys_pcvr_leaving) to FlipSpacesApp.text(R.string.sys_pcvr_leaving_line)
            Phase.STALLED -> FlipSpacesApp.text(R.string.sys_pcvr_stalled) to FlipSpacesApp.text(R.string.sys_pcvr_stalled_line)
        }
        paintCard(title, line, left, right, phase != Phase.LEAVING && phase != Phase.STALLED)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cardTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        Log.i(TAG, "PC VR loading: $title; Joy-Cons left $left, right $right")
    }

    private fun paintCard(title: String, line: String, left: Pad, right: Pad, joyCons: Boolean) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF(4f, 4f, CARD_W - 4f, CARD_H - 4f)
        paint.color = Color.argb(225, 12, 18, 34)
        canvas.drawRoundRect(r, 48f, 48f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.argb(70, 150, 190, 255)
        canvas.drawRoundRect(r, 48f, 48f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val cx = CARD_W / 2f

        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 30f
        paint.letterSpacing = 0.25f
        paint.color = Color.rgb(127, 168, 255)
        canvas.drawText(FlipSpacesApp.text(R.string.sys_pcvr_name), cx, 92f, paint)
        paint.letterSpacing = 0f

        paint.textSize = 66f
        paint.color = Color.WHITE
        canvas.drawText(title, cx, 372f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 34f
        paint.color = Color.rgb(170, 180, 200)
        canvas.drawText(line, cx, 428f, paint)

        if (!joyCons) return
        paint.color = Color.argb(40, 150, 190, 255)
        canvas.drawRect(120f, 478f, CARD_W - 120f, 480f, paint)

        val both = left == Pad.RESTED && right == Pad.RESTED
        paint.textSize = 30f
        paint.color = if (both) Color.rgb(140, 220, 170) else Color.rgb(170, 180, 200)
        canvas.drawText(FlipSpacesApp.text(if (both) R.string.sys_pcvr_joycons_ready else R.string.sys_pcvr_joycons_rest), cx, 530f, paint)
        pill(cx - 120f, 588f, FlipSpacesApp.text(R.string.sys_pcvr_pad_left), left, paint)
        pill(cx + 120f, 588f, FlipSpacesApp.text(R.string.sys_pcvr_pad_right), right, paint)
    }

    private fun pill(cx: Float, cy: Float, name: String, pad: Pad, paint: Paint) {
        val r = RectF(cx - 100f, cy - 30f, cx + 100f, cy + 30f)
        paint.style = Paint.Style.FILL
        paint.color = when (pad) {
            Pad.RESTED -> Color.argb(60, 90, 200, 130)
            Pad.AWAKE -> Color.argb(40, 150, 190, 255)
            Pad.NONE -> Color.argb(18, 255, 255, 255)
        }
        canvas.drawRoundRect(r, 30f, 30f, paint)
        paint.textSize = 28f
        paint.color = when (pad) {
            Pad.RESTED -> Color.rgb(150, 235, 180)
            Pad.AWAKE -> Color.rgb(200, 215, 240)
            Pad.NONE -> Color.rgb(110, 118, 135)
        }
        val mark = when (pad) {
            Pad.RESTED -> "✓ "
            Pad.AWAKE -> ""
            Pad.NONE -> ""
        }
        canvas.drawText(if (pad == Pad.NONE) FlipSpacesApp.text(R.string.sys_pcvr_pad_off, name) else mark + name, cx, cy + 10f, paint)
    }

    private fun link(vsSrc: String, fsSrc: String): Int {
        fun shader(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.w(TAG, "loading shader: ${GLES30.glGetShaderInfoLog(it)}")
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, shader(GLES30.GL_VERTEX_SHADER, vsSrc))
            GLES30.glAttachShader(it, shader(GLES30.GL_FRAGMENT_SHADER, fsSrc))
            GLES30.glLinkProgram(it)
        }
    }

    companion object {
        private const val TAG = "PocketXrAlvr"
        private const val CARD_W = 1200
        private const val CARD_H = 640
        /** The card's half width as a view tangent (about 40 degrees across), and its centre a little below eye level. */
        private const val CARD_HALF_TAN = 0.36f
        private const val CARD_Y_TAN = -0.03f
        /** The spinner's centre, pixels from the card's top. */
        private const val SPIN_Y = 205f
        private const val SLOW_FIND_S = 20f
        private val FOLLOW_AFTER_RAD = (35.0 * PI / 180.0).toFloat()
        /** Share of the excess turn taken back each frame. */
        private const val FOLLOW_RATE = 0.04f

        /** inverse(a) * b as a 3x3 matrix, column major: view directions into the card's frame. */
        fun relative(a: FloatArray, b: FloatArray, out: FloatArray) {
            val ax = -a[0]; val ay = -a[1]; val az = -a[2]; val aw = a[3]
            val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
            val x = aw * bx + ax * bw + ay * bz - az * by
            val y = aw * by - ax * bz + ay * bw + az * bx
            val z = aw * bz + ax * by - ay * bx + az * bw
            val w = aw * bw - ax * bx - ay * by - az * bz
            out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y + z * w); out[2] = 2 * (x * z - y * w)
            out[3] = 2 * (x * y - z * w); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z + x * w)
            out[6] = 2 * (x * z + y * w); out[7] = 2 * (y * z - x * w); out[8] = 1 - 2 * (x * x + y * y)
        }

        const val FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D card;
            uniform mat3 view;
            uniform vec4 tans;
            uniform vec2 cardHalf;
            uniform float cardY;
            uniform vec2 spinAt;
            uniform float time;
            uniform float alpha;
            uniform float progress;
            out vec4 color;
            void main() {
                vec3 d = normalize(view * vec3(mix(tans.x, tans.y, vUv.x), mix(tans.w, tans.z, vUv.y), -1.0));
                float e = d.y;
                // Night sky above, a soft blue horizon that breathes, a dark floor with a faint grid fading away.
                vec3 col = mix(vec3(0.030, 0.050, 0.110), vec3(0.010, 0.014, 0.032), smoothstep(0.0, 0.7, e));
                float breathe = 0.85 + 0.15 * sin(time * 1.2);
                col += vec3(0.08, 0.26, 0.78) * exp(-abs(e) * 9.0) * 0.55 * breathe;
                if (e < -0.002) {
                    vec2 p = d.xz * (1.6 / -e) * 0.5;
                    vec2 g = abs(fract(p) - 0.5) / max(fwidth(p), vec2(1e-4));
                    float line = 1.0 - min(min(g.x, g.y), 1.0);
                    col += vec3(0.15, 0.35, 0.80) * line * 0.16 * exp(-length(p) * 0.25);
                }
                if (d.z < -1e-3) {
                    vec2 t = d.xy / -d.z;
                    vec2 c = vec2((t.x + cardHalf.x) / (2.0 * cardHalf.x), (t.y - cardY + cardHalf.y) / (2.0 * cardHalf.y));
                    if (all(greaterThanEqual(c, vec2(0.0))) && all(lessThanEqual(c, vec2(1.0)))) {
                        vec4 k = texture(card, vec2(c.x, 1.0 - c.y));
                        col = mix(col, k.rgb, k.a);
                        vec2 s = (c - spinAt) * vec2(cardHalf.x / cardHalf.y, 1.0);
                        float r = length(s);
                        float band = 1.0 - smoothstep(0.004, 0.010, abs(r - 0.075));
                        if (progress < 0.0) {
                            // A turning arc: something is happening.
                            float a = fract(atan(s.y, s.x) / 6.2831853 + time * 0.7);
                            col += vec3(0.45, 0.70, 1.0) * band * smoothstep(0.0, 0.8, a) * k.a;
                        } else {
                            // A ring filling clockwise from the top: how long is left to hold.
                            float a = fract(0.25 - atan(s.y, s.x) / 6.2831853);
                            col += mix(vec3(0.16, 0.22, 0.32), vec3(0.45, 0.70, 1.0), step(a, progress)) * band * k.a;
                        }
                    }
                }
                color = vec4(col, alpha);
            }
        """
    }
}
