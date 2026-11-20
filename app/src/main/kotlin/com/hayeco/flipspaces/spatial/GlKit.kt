package com.hayeco.flipspaces.spatial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small OpenGL ES 3 helpers for the Space: one quad, three programs, text textures, mipmapped panel copies.
 *
 * 0.5.68 (Mike: the Space should look finished; the survey found text with a dark fringe): every program writes
 * premultiplied colour, and the Space blends ONE, ONE_MINUS_SRC_ALPHA ([BLEND_SRC]). Canvas bitmaps reach GL
 * premultiplied (GLUtils uploads them as they are), so with the straight SRC_ALPHA blend of 0.5.20 to 0.5.67 each label's
 * soft edge was multiplied by its alpha twice, darker and thinner. A texture's tint is premultiplied too: (a, a, a, a)
 * fades it. [FRAG_COLOR] takes a straight colour and premultiplies it itself.
 */
internal object GlKit {
    /**
     * 0.5.13: [uMvp] is drawn through the top row's pose; [uRowFix] turns its clip
     * position into the bottom row's, and each vertex takes the share of it its height on the
     * screen calls for ([com.hayeco.flipspaces.core.spatial.RowScan.blend]). [uRowBlend] 0 is one pose.
     */
    /*
     * 0.5.19: [uArc] bends the mesh round a window's cylinder ([com.hayeco.flipspaces.core.spatial.PanelCurve.Rect]):
     * x radius in metres (0: the flat quad, as before), y and z the arc angles at the left and right
     * edge. The texture coordinate stays the mesh's own, so texels run evenly along the arc.
     */
    private const val VERTEX = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uTex;
uniform mat4 uRowFix;
uniform float uRowBlend;
uniform vec4 uArc;
layout(location = 0) in vec2 aPos;
out vec2 vUv;
out vec2 vLocal;
void main() {
    vLocal = aPos;
    vUv = (uTex * vec4(aPos + 0.5, 0.0, 1.0)).xy;
    vec3 local = vec3(aPos, 0.0);
    if (uArc.x > 0.0) {
        float th = mix(uArc.y, uArc.z, aPos.x + 0.5);
        local = vec3(uArc.x * sin(th), aPos.y, -uArc.x * cos(th));
    }
    vec4 top = uMvp * vec4(local, 1.0);
    vec4 bottom = uRowFix * top;
    float w = clamp((1.0 - top.y / top.w) * 0.5, 0.0, 1.0) * uRowBlend;
    gl_Position = mix(top, bottom, w);
}
"""

    /**
     * 0.5.13: four bilinear taps on a rotated grid inside the pixel's footprint,
     * taken from the derivatives of the texture coordinate
     * ([com.hayeco.flipspaces.core.spatial.PanelSampler.FOOTPRINT_OFFSETS]).
     */
    /*
     * 0.5.20 (Mike: no bars; the window "only the app's content edge to edge with rounded corners"):
     * [uRound] rounds a rectangle's corners. x and y are the corner radius as a share of the
     * rectangle's width and height (the same metres both ways, so the corners are round on a
     * curved window too), z > 0.5 turns it on. The edge is smoothed over one pixel (fwidth).
     * (0.5.69: replaced by [uShape]'s distance below.)
     */
    /*
     * 0.5.69 ("Light on glass", docs/VISUAL-DIRECTION.md): the rounded rectangle as a signed distance in the shape's own
     * units (metres on a window's surface, dp in the launcher): [uShape] is its half width, half height and corner
     * radius, w > 0.5 on. That gives the distance to the edge everywhere, not only in the corners, so [uRim], a
     * premultiplied colour, draws a hairline light rim [uRimW] wide just inside the edge: on the see-through glasses
     * a shadow is black, so it shows nothing, and edges and depth are drawn with light. [shaped] takes and gives
     * premultiplied colour. highp: a rim is about a millimetre on a window half a metre wide.
     */
    private const val ROUND = """
uniform highp vec4 uShape;
uniform vec4 uRim;
uniform highp float uRimW;
highp float sdRound(highp vec2 p, highp vec2 b, highp float r) {
    highp vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
vec4 shaped(vec4 c, highp vec2 local) {
    if (uShape.w < 0.5) return c;
    highp float d = sdRound(local * uShape.xy * 2.0, uShape.xy, uShape.z);
    highp float aa = max(fwidth(d), 1e-6);
    float inside = 1.0 - smoothstep(-aa, aa, d);
    float rim = uRim.a > 0.0 ? smoothstep(-uRimW - aa, -uRimW + aa, d) : 0.0;
    c = c * (1.0 - rim * uRim.a) + uRim * rim;
    return c * inside;
}
"""

    private const val FRAG_2D_FOOTPRINT = """#version 300 es
precision highp float;
uniform sampler2D uSampler;
uniform vec4 uTint;
in vec2 vUv;
in vec2 vLocal;
out vec4 outColor;
$ROUND
void main() {
    vec2 dx = dFdx(vUv);
    vec2 dy = dFdy(vUv);
    vec4 c = texture(uSampler, vUv + dx * -0.375 + dy * 0.125)
        + texture(uSampler, vUv + dx * 0.125 + dy * 0.375)
        + texture(uSampler, vUv + dx * 0.375 + dy * -0.125)
        + texture(uSampler, vUv + dx * -0.125 + dy * -0.375);
    outColor = shaped(c * 0.25 * uTint, vLocal);
}
"""

    private const val FRAG_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uSampler;
uniform vec4 uTint;
in vec2 vUv;
in vec2 vLocal;
out vec4 outColor;
$ROUND
void main() { outColor = texture(uSampler, vUv) * uTint; }
"""

    private const val FRAG_2D = """#version 300 es
precision mediump float;
uniform sampler2D uSampler;
uniform vec4 uTint;
in vec2 vUv;
in vec2 vLocal;
out vec4 outColor;
$ROUND
void main() { outColor = shaped(texture(uSampler, vUv) * uTint, vLocal); }
"""

    /*
     * 0.5.69: [uCircle] 2 is a halo: the quad is the shape grown to [uHalo]'s half size, and light falls off over
     * [uHalo].z outside the shape's edge ([uShape]), none inside it. The focused window's glow: one quad, no blur pass.
     * 0.5.70 (Mike: the cursor is one of the things he dislikes): round marks for the cursor, the quad's inscribed circle
     * anti-aliased over a pixel ([CIRCLE_GLOW], [CIRCLE_DOT], [CIRCLE_RING]); 1 is 0.5.20's disc, kept for the calibration dot.
     */
    private const val FRAG_COLOR = """#version 300 es
precision mediump float;
uniform vec4 uColor;
uniform float uCircle;
uniform highp vec4 uHalo;
in vec2 vLocal;
out vec4 outColor;
$ROUND
void main() {
    if (uCircle > 2.5) {
        float d = length(vLocal) * 2.0;
        float aa = max(fwidth(d), 1e-4);
        float a;
        vec3 rgb = uColor.rgb;
        if (uCircle < 3.5) {
            // Glow: light falling off to the edge.
            float f = clamp(1.0 - d, 0.0, 1.0);
            a = f * f;
        } else if (uCircle < 4.5) {
            // Dot: the colour inside uHalo.x of the radius, a dark hairline round it, so it reads on a bright page too.
            float core = 1.0 - smoothstep(uHalo.x - aa, uHalo.x + aa, d);
            float edge = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, d);
            rgb = mix(vec3(0.0), uColor.rgb, core);
            a = edge * mix(0.55, 1.0, core);
        } else {
            // Ring: between uHalo.x of the radius and its edge.
            a = (1.0 - smoothstep(1.0 - aa, 1.0 + aa, d)) * smoothstep(uHalo.x - aa, uHalo.x + aa, d);
        }
        a *= uColor.a;
        outColor = vec4(rgb * a, a);
    } else if (uCircle > 1.5) {
        highp float d = sdRound(vLocal * uHalo.xy * 2.0, uShape.xy, uShape.z);
        highp float aa = max(fwidth(d), 1e-6);
        float fall = 1.0 - smoothstep(0.0, uHalo.z, d);
        float a = uColor.a * fall * fall * smoothstep(-aa, aa, d);
        outColor = vec4(uColor.rgb * a, a);
    } else if (uCircle > 0.5) {
        float d = length(vLocal) * 2.0;
        if (d > 1.0) discard;
        float edge = smoothstep(0.6, 0.75, d);
        outColor = vec4(mix(uColor.rgb, vec3(0.0), edge * 0.6) * uColor.a, uColor.a);
    } else {
        outColor = shaped(vec4(uColor.rgb * uColor.a, uColor.a), vLocal);
    }
}
"""

    /** 0.5.70: the cursor's round marks in [color] ([applyHalo]'s x is the dot's core or the ring's inner share of the radius). */
    const val CIRCLE_GLOW = 3f
    const val CIRCLE_DOT = 4f
    const val CIRCLE_RING = 5f

    /** 0.5.68: the Space's blend source factor: every program writes premultiplied colour. */
    const val BLEND_SRC = GLES30.GL_ONE

    /** Maps quad uv to bitmap uv: bitmaps are stored top row first. */
    val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    class Program(val id: Int) {
        val mvp = GLES30.glGetUniformLocation(id, "uMvp")
        val tex = GLES30.glGetUniformLocation(id, "uTex")
        val tint = GLES30.glGetUniformLocation(id, "uTint")
        val color = GLES30.glGetUniformLocation(id, "uColor")
        val circle = GLES30.glGetUniformLocation(id, "uCircle")
        val sampler = GLES30.glGetUniformLocation(id, "uSampler")
        val rowFix = GLES30.glGetUniformLocation(id, "uRowFix")
        val rowBlend = GLES30.glGetUniformLocation(id, "uRowBlend")
        val arc = GLES30.glGetUniformLocation(id, "uArc")
        val round = GLES30.glGetUniformLocation(id, "uShape")
        val rim = GLES30.glGetUniformLocation(id, "uRim")
        val rimW = GLES30.glGetUniformLocation(id, "uRimW")
        val halo = GLES30.glGetUniformLocation(id, "uHalo")
    }

    /**
     * 0.5.20: rounds the next shape's corners by [radiusM] for a rectangle [widthM] by [heightM] (call after [applyRow]).
     * 0.5.69: any units, the same for all three (the launcher's are dp); the radius is kept within half the shorter side.
     */
    fun applyRound(prog: Program, radiusM: Float, widthM: Float, heightM: Float) {
        if (prog.round < 0) return
        if (radiusM <= 0f || widthM <= 0f || heightM <= 0f) {
            GLES30.glUniform4f(prog.round, 0f, 0f, 0f, 0f)
        } else {
            GLES30.glUniform4f(prog.round, widthM / 2f, heightM / 2f, radiusM.coerceAtMost(minOf(widthM, heightM) / 2f), 1f)
        }
    }

    /** 0.5.69: the next rounded shape's light rim: [argb] (straight; premultiplied here) [widthM] wide inside its edge. */
    fun applyRim(prog: Program, argb: Int, widthM: Float) {
        if (prog.rim < 0) return
        val a = (argb ushr 24) / 255f
        GLES30.glUniform4f(prog.rim, ((argb shr 16) and 0xFF) / 255f * a, ((argb shr 8) and 0xFF) / 255f * a, (argb and 0xFF) / 255f * a, a)
        GLES30.glUniform1f(prog.rimW, widthM)
    }

    /** 0.5.69: [prog] is [color]: the next draw is a halo [widthM] wide round the rounded shape, on a quad grown to [grownW] by [grownH]. */
    fun applyHalo(prog: Program, grownW: Float, grownH: Float, widthM: Float) {
        if (prog.halo >= 0) GLES30.glUniform4f(prog.halo, grownW / 2f, grownH / 2f, widthM, 0f)
    }

    lateinit var oes: Program
    lateinit var flat: Program
    lateinit var color: Program

    /** 0.5.13: panel pictures through the footprint filter. */
    lateinit var footprint: Program
    private var quadVbo = 0
    private var quadVertices = 4

    /** 0.5.19: the curved windows' mesh ([com.hayeco.flipspaces.core.spatial.CurveMesh]), shared vertices and indices. */
    private var curveVbo = 0
    private var curveIbo = 0
    private var curveIndices = 0

    /**
     * 0.5.13: the program in use gets [fix] (column major) as its row fix, or one
     * pose when null. Call after glUseProgram. 0.5.19: and a flat shape (no arc); [drawShape] sets one.
     */
    fun applyRow(prog: Program, fix: FloatArray?) {
        GLES30.glUniformMatrix4fv(prog.rowFix, 1, false, fix ?: IDENTITY, 0)
        GLES30.glUniform1f(prog.rowBlend, if (fix != null) 1f else 0f)
        GLES30.glUniform4f(prog.arc, 0f, 0f, 0f, 0f)
        if (prog.round >= 0) GLES30.glUniform4f(prog.round, 0f, 0f, 0f, 0f)
        if (prog.rim >= 0) GLES30.glUniform4f(prog.rim, 0f, 0f, 0f, 0f)
    }

    /** 0.5.19: [rect]'s arc on [prog] (in use), then its mesh: the curved mesh when it is curved, else the quad. */
    fun drawShape(prog: Program, rect: com.hayeco.flipspaces.core.spatial.PanelCurve.Rect?) {
        if (rect == null || !rect.curved) {
            drawQuad()
            return
        }
        GLES30.glUniform4f(prog.arc, rect.radius, rect.theta0, rect.theta1, 0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, curveVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, curveIbo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, curveIndices, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glUniform4f(prog.arc, 0f, 0f, 0f, 0f)
        curvedDraws++
    }

    /** 0.5.19: curved rectangles drawn since the last [takeCurvedDraws], for the gpu passes line. */
    private var curvedDraws = 0

    fun takeCurvedDraws(): Int = curvedDraws.also { curvedDraws = 0 }

    /** The most anisotropic filtering the GPU offers (GL_EXT_texture_filter_anisotropic), 1 without it. */
    var maxAnisotropy = 1f
        private set

    /** Fills the whole target: the quad's -0.5..0.5 scaled to clip space's -1..1. */
    val FULL_TARGET = floatArrayOf(2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** Call from onSurfaceCreated: every GL object is per context. */
    fun init() {
        oes = Program(link(VERTEX, FRAG_OES))
        flat = Program(link(VERTEX, FRAG_2D))
        color = Program(link(VERTEX, FRAG_COLOR))
        footprint = Program(link(VERTEX, FRAG_2D_FOOTPRINT))
        // 0.5.13: the quad in 8 strips, so the per-row blend follows the screen.
        val verts = com.hayeco.flipspaces.core.spatial.RowScan.mesh()
        quadVertices = verts.size / 2
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).also { it.position(0) }
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        // 0.5.19: the curved windows' mesh, 64 columns by the row scan's 8 rows.
        val mesh = com.hayeco.flipspaces.core.spatial.CurveMesh()
        val cv = ByteBuffer.allocateDirect(mesh.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.positions).also { it.position(0) }
        val ci = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(mesh.indices).also { it.position(0) }
        val bufs = IntArray(2)
        GLES30.glGenBuffers(2, bufs, 0)
        curveVbo = bufs[0]
        curveIbo = bufs[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, curveVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.positions.size * 4, cv, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, curveIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, ci, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        curveIndices = mesh.indices.size
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        maxAnisotropy = if (GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true) {
            FloatArray(1).also { GLES30.glGetFloatv(MAX_TEXTURE_MAX_ANISOTROPY_EXT, it, 0) }[0].coerceAtLeast(1f)
        } else {
            1f
        }
    }

    const val TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    private const val MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF

    fun drawQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, quadVertices)
    }

    fun newOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("GL link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("GL shader failed: ${GLES30.glGetShaderInfoLog(s)}")
        return s
    }
}

/**
 * 0.5.20: a window control's round button ([com.hayeco.flipspaces.core.spatial.WindowControls]): a
 * filled disc with a glyph drawn here with strokes (no font, no copied artwork): a chevron for Back,
 * a circular arrow for Reset, a cross for Close. Transparent round it, so the button floats apart
 * from the window. Re-uploaded only when the look changes. GL thread only.
 */
internal class IconTexture(private val size: Int = 128) {
    /**
     * 0.5.21: Wide view's three looks: a rounded landscape
     * screen with a play triangle in it, a check at its lower right when on, a circle slash when off; the
     * unavailable one (an app that asks for landscape) is the on look, dimmed by the caller.
     */
    enum class Icon { BACK, REFRESH, RESET, CLOSE, BROAD_ON, BROAD_OFF, FIT_ON, FIT_OFF, IMMERSIVE_ON, IMMERSIVE_OFF, SWITCH, LOCK }

    var id = 0
        private set
    private var key: String? = null
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * 0.5.69: a [background] with no alpha draws the glyph alone (the window's capsule and hover light are drawn under it
     * by the renderer, so a hover no longer changes this texture); a badge then clears the glyph under it instead.
     */
    fun set(icon: Icon, background: Int, foreground: Int) {
        val k = "$icon/$background/$foreground"
        if (k == key && id != 0) return
        key = k
        val s = size.toFloat()
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.SRC)
        fill.color = background
        canvas.drawCircle(s / 2f, s / 2f, s / 2f - 1f, fill)
        stroke.color = foreground
        stroke.strokeWidth = s * 0.085f
        val path = android.graphics.Path()
        when (icon) {
            Icon.BACK -> {
                path.moveTo(s * 0.57f, s * 0.30f)
                path.lineTo(s * 0.37f, s * 0.50f)
                path.lineTo(s * 0.57f, s * 0.70f)
                canvas.drawPath(path, stroke)
            }
            Icon.CLOSE -> {
                canvas.drawLine(s * 0.35f, s * 0.35f, s * 0.65f, s * 0.65f, stroke)
                canvas.drawLine(s * 0.65f, s * 0.35f, s * 0.35f, s * 0.65f, stroke)
            }
            Icon.LOCK -> {
                // 1.1.15: Lock screens: a padlock, its shackle an arch over a filled body with a keyhole.
                stroke.strokeWidth = s * 0.07f
                canvas.drawArc(android.graphics.RectF(s * 0.36f, s * 0.2f, s * 0.64f, s * 0.5f), 180f, 180f, false, stroke)
                canvas.drawLine(s * 0.36f, s * 0.35f, s * 0.36f, s * 0.47f, stroke)
                canvas.drawLine(s * 0.64f, s * 0.35f, s * 0.64f, s * 0.47f, stroke)
                fill.color = foreground
                canvas.drawRoundRect(android.graphics.RectF(s * 0.28f, s * 0.45f, s * 0.72f, s * 0.78f), s * 0.06f, s * 0.06f, fill)
                fill.color = android.graphics.Color.TRANSPARENT
                fill.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                canvas.drawCircle(s / 2f, s * 0.58f, s * 0.045f, fill)
                fill.xfermode = null
            }
            Icon.SWITCH -> {
                // 0.5.85: Switch app: two arrows passing, one right along the top, one left along the bottom.
                stroke.strokeWidth = s * 0.07f
                canvas.drawLine(s * 0.3f, s * 0.4f, s * 0.7f, s * 0.4f, stroke)
                canvas.drawLine(s * 0.6f, s * 0.3f, s * 0.7f, s * 0.4f, stroke)
                canvas.drawLine(s * 0.6f, s * 0.5f, s * 0.7f, s * 0.4f, stroke)
                canvas.drawLine(s * 0.7f, s * 0.6f, s * 0.3f, s * 0.6f, stroke)
                canvas.drawLine(s * 0.4f, s * 0.5f, s * 0.3f, s * 0.6f, stroke)
                canvas.drawLine(s * 0.4f, s * 0.7f, s * 0.3f, s * 0.6f, stroke)
            }
            Icon.RESET -> {
                // 0.5.21: a crosshair (put the window back in front); the circular arrow is Refresh's now.
                val r = s * 0.2f
                canvas.drawCircle(s / 2f, s / 2f, r, stroke)
                canvas.drawLine(s / 2f, s * 0.2f, s / 2f, s * 0.36f, stroke)
                canvas.drawLine(s / 2f, s * 0.64f, s / 2f, s * 0.8f, stroke)
                canvas.drawLine(s * 0.2f, s / 2f, s * 0.36f, s / 2f, stroke)
                canvas.drawLine(s * 0.64f, s / 2f, s * 0.8f, s / 2f, stroke)
                fill.color = foreground
                canvas.drawCircle(s / 2f, s / 2f, s * 0.045f, fill)
            }
            Icon.REFRESH -> {
                // Three quarters of a circle, open at the top right, with its arrowhead there.
                val r = s * 0.2f
                val oval = android.graphics.RectF(s / 2f - r, s / 2f - r, s / 2f + r, s / 2f + r)
                canvas.drawArc(oval, -60f, 290f, false, stroke)
                val a = Math.toRadians(-60.0)
                val ex = (s / 2f + r * kotlin.math.cos(a)).toFloat()
                val ey = (s / 2f + r * kotlin.math.sin(a)).toFloat()
                fill.color = foreground
                val head = android.graphics.Path()
                head.moveTo(ex + s * 0.11f, ey - s * 0.02f)
                head.lineTo(ex - s * 0.04f, ey - s * 0.10f)
                head.lineTo(ex - s * 0.02f, ey + s * 0.08f)
                head.close()
                canvas.drawPath(head, fill)
            }
            Icon.BROAD_ON, Icon.BROAD_OFF -> {
                // The landscape screen and its play triangle.
                stroke.strokeWidth = s * 0.06f
                val screen = android.graphics.RectF(s * 0.22f, s * 0.31f, s * 0.74f, s * 0.63f)
                canvas.drawRoundRect(screen, s * 0.06f, s * 0.06f, stroke)
                fill.color = foreground
                val play = android.graphics.Path()
                play.moveTo(s * 0.42f, s * 0.39f)
                play.lineTo(s * 0.56f, s * 0.47f)
                play.lineTo(s * 0.42f, s * 0.55f)
                play.close()
                canvas.drawPath(play, fill)
                // The badge at the lower right: a disc in the button's colour, then a check or a circle slash.
                fill.color = background
                val bx = s * 0.71f
                val by = s * 0.66f
                if (android.graphics.Color.alpha(background) == 0) fill.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                canvas.drawCircle(bx, by, s * 0.13f, fill)
                fill.xfermode = null
                stroke.strokeWidth = s * 0.05f
                if (icon == Icon.BROAD_ON) {
                    val check = android.graphics.Path()
                    check.moveTo(bx - s * 0.07f, by)
                    check.lineTo(bx - s * 0.015f, by + s * 0.055f)
                    check.lineTo(bx + s * 0.075f, by - s * 0.05f)
                    canvas.drawPath(check, stroke)
                } else {
                    canvas.drawCircle(bx, by, s * 0.075f, stroke)
                    canvas.drawLine(bx - s * 0.053f, by - s * 0.053f, bx + s * 0.053f, by + s * 0.053f, stroke)
                }
            }
            Icon.FIT_ON, Icon.FIT_OFF -> {
                // 0.5.24: Fit video: a 16:9 frame with its four corners pointing out; on, the frame is filled.
                stroke.strokeWidth = s * 0.055f
                val l = s * 0.24f
                val t = s * 0.36f
                val r = s * 0.76f
                val b = t + (r - l) * 9f / 16f
                if (icon == Icon.FIT_ON) {
                    fill.color = foreground
                    canvas.drawRoundRect(android.graphics.RectF(l + s * 0.05f, t + s * 0.05f, r - s * 0.05f, b - s * 0.05f), s * 0.02f, s * 0.02f, fill)
                }
                val k = s * 0.08f
                for ((x, y, dx, dy) in listOf(floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f))) {
                    canvas.drawLine(x, y, x + dx * k, y, stroke)
                    canvas.drawLine(x, y, x, y + dy * k, stroke)
                }
            }
            Icon.IMMERSIVE_ON, Icon.IMMERSIVE_OFF -> {
                // 0.5.37: Immersive: a wide, gently curved cinema screen with a play mark; on, the screen is filled.
                stroke.strokeWidth = s * 0.05f
                val l = s * 0.2f
                val r = s * 0.8f
                val t = s * 0.34f
                val b = s * 0.64f
                val screen = android.graphics.Path().apply {
                    moveTo(l, t)
                    quadTo(s * 0.5f, t + s * 0.05f, r, t)
                    lineTo(r, b)
                    quadTo(s * 0.5f, b + s * 0.05f, l, b)
                    close()
                }
                if (icon == Icon.IMMERSIVE_ON) {
                    fill.color = foreground
                    canvas.drawPath(screen, fill)
                } else {
                    canvas.drawPath(screen, stroke)
                }
                val play = android.graphics.Path().apply {
                    moveTo(s * 0.46f, s * 0.42f)
                    lineTo(s * 0.57f, s * 0.49f)
                    lineTo(s * 0.46f, s * 0.56f)
                    close()
                }
                fill.color = if (icon == Icon.IMMERSIVE_ON) background or 0xFF000000.toInt() else foreground
                canvas.drawPath(play, fill)
            }
        }
        if (id == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            id = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    fun release() {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
        key = null
    }
}

/**
 * 0.5.37: anything painted with Android's Canvas ([draw]'s block) into a bitmap of [width] by [height] and uploaded as a
 * texture, again only when [draw]'s key changes (the Control Center card). GL thread only.
 */
internal class CanvasTexture(val width: Int, val height: Int) {
    var id = 0
        private set
    private var key: String? = null
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    fun draw(k: String, block: (Canvas) -> Unit) {
        if (k == key && id != 0) return
        key = k
        block(canvas)
        if (id == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            id = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    fun release() {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
        key = null
    }
}

/**
 * Text drawn with Android's Canvas into a bitmap and uploaded as a texture. Re-uploaded only
 * when the text changes. GL thread only.
 */
internal class LabelTexture(private val width: Int, private val height: Int) {
    var id = 0
        private set
    private var key: String? = null
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = com.hayeco.flipspaces.ui.LookType.regular }

    /**
     * [icon] (an app icon, launcher tiles) is drawn centred in the top part with the text under
     * it; [badge] is a short mark in the top right corner (a star, a close cross).
     */
    fun set(
        lines: List<String>,
        background: Int,
        foreground: Int,
        textPx: Float,
        centred: Boolean = false,
        icon: Bitmap? = null,
        badge: String? = null,
        medium: Boolean = false,
        oneLine: Boolean = false,
    ) {
        val k = "$background/$foreground/$textPx/$centred/${icon?.let { System.identityHashCode(it) }}/$badge/$medium/$oneLine/" + lines.joinToString("\n")
        if (k == key && id != 0) return
        key = k
        // 0.5.69: Inter Medium for titles and buttons ([com.hayeco.flipspaces.ui.LookType]).
        paint.typeface = if (medium) com.hayeco.flipspaces.ui.LookType.medium else com.hayeco.flipspaces.ui.LookType.regular
        canvas.drawColor(background, android.graphics.PorterDuff.Mode.SRC)
        paint.color = foreground
        paint.textSize = textPx
        // [oneLine]: each line drawn smaller rather than wrapped onto a second line the texture cuts off (a card button
        // read "Turn on and" for "Turn on and reopen", Mike's glasses, 19 Nov 2026).
        @Suppress("NAME_SHADOWING")
        val textPx = if (!oneLine || lines.isEmpty()) textPx else {
            val widest = lines.maxOf { paint.measureText(it) }
            val room = width - 2 * textPx * 0.8f
            if (widest <= room) textPx else textPx * room / widest
        }
        paint.textSize = textPx
        val lineH = textPx * 1.3f
        var top = 0f
        if (icon != null) {
            val side = minOf(width, height) * 0.5f
            val left = (width - side) / 2f
            top = height * 0.1f
            canvas.drawBitmap(icon, null, android.graphics.RectF(left, top, left + side, top + side), paint)
            top += side
        }
        if (badge != null) {
            val badgePx = textPx * 1.1f
            paint.textSize = badgePx
            canvas.drawText(badge, width - paint.measureText(badge) - badgePx * 0.4f, badgePx * 1.1f, paint)
            paint.textSize = textPx
        }
        var y = when {
            icon != null -> top + (height - top - lineH * lines.size) / 2f + textPx
            centred -> (height - lineH * lines.size) / 2f + textPx
            else -> textPx * 1.2f
        }
        val margin = textPx * 0.8f
        for (line in lines) {
            for (wrapped in wrap(line, width - 2 * margin)) {
                val x = if (centred) (width - paint.measureText(wrapped)) / 2f else margin
                canvas.drawText(wrapped, x, y, paint)
                y += lineH
                if (y > height) break
            }
        }
        if (id == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            id = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    private fun wrap(line: String, maxWidth: Float): List<String> {
        if (paint.measureText(line) <= maxWidth) return listOf(line)
        val out = mutableListOf<String>()
        var current = ""
        for (word in line.split(' ')) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(next) > maxWidth && current.isNotEmpty()) {
                out += current
                current = word
            } else {
                current = next
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    fun release() {
        GlKit.deleteTexture(id)
        id = 0
        key = null
    }
}

/**
 * A panel's frames as an ordinary mipmapped texture (0.4.1). An app's frames arrive in an
 * external OES texture, which cannot have mipmaps, so a far panel's text shimmered (each
 * screen pixel sampled one texel of many). Each new frame is drawn once into this texture's
 * level 0 through a framebuffer; when the panel is shown smaller than its pixels the levels
 * below are rebuilt with glGenerateMipmap and sampled trilinearly, with anisotropic filtering
 * where the GPU has it for panels seen at an angle. GL thread only.
 */
internal class PanelMipTexture {
    var id = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    private var levels = 1
    private var fbo = 0

    /** The framebuffer level 0 is drawn through (in the context that made it). */
    val framebuffer: Int get() = fbo
    private var readFbo = 0
    private var minFilter = 0
    private var readBuffer: ByteBuffer? = null

    /** (Re)allocates for a [w] by [h] panel; a resize starts a new, empty texture. */
    fun ensure(w: Int, h: Int) {
        if (id != 0 && w == width && h == height) return
        release()
        width = w
        height = h
        levels = com.hayeco.flipspaces.core.spatial.PanelMips.levels(w, h)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (GlKit.maxAnisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GlKit.TEXTURE_MAX_ANISOTROPY_EXT, minOf(4f, GlKit.maxAnisotropy))
        minFilter = 0
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, id, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        builtLevels = 1
        maxLevel = -1
    }

    /**
     * Draws the latched OES frame ([oesTexture] with its [texMatrix]) upright into level 0,
     * then puts the viewport back to the scene's [sceneWidth] by [sceneHeight].
     */
    fun copyFrom(oesTexture: Int, texMatrix: FloatArray, sceneWidth: Int, sceneHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        val prog = GlKit.oes
        GLES30.glUseProgram(prog.id)
        GLES30.glUniformMatrix4fv(prog.mvp, 1, false, GlKit.FULL_TARGET, 0)
        GLES30.glUniformMatrix4fv(prog.tex, 1, false, texMatrix, 0)
        GLES30.glUniform4f(prog.tint, 1f, 1f, 1f, 1f)
        // The copy into level 0 is flat: no per-row blend.
        GlKit.applyRow(prog, null)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(prog.sampler, 0)
        GlKit.drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, sceneWidth, sceneHeight)
        GLES30.glEnable(GLES30.GL_BLEND)
        // A new level 0: the chain below it is stale.
        builtLevels = 1
    }

    /** The texture's whole chain, level 0 included. */
    val fullLevels: Int get() = levels

    /**
     * Before drawing: rebuild the mip chain if the panel is shown smaller than its pixels
     * ([mips]) and it is stale, and pick trilinear or plain linear filtering to match.
     */
    fun prepare(mips: Boolean) {
        prepare(if (mips) levels else 1)
    }

    /**
     * 0.5.17: before drawing, with the levels the panel's shrink needs
     * ([com.hayeco.flipspaces.core.spatial.PanelPrepare.mipLevels]): 1 is level 0 alone with
     * bilinear filtering, no chain; more builds the chain only down to those levels (the texture's
     * max level), and only when the copy's chain does not reach them yet. Returns the levels built
     * now, 0 when no chain was built.
     */
    fun prepare(levelsNeeded: Int): Int {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        val need = levelsNeeded.coerceIn(1, levels)
        var built = 0
        if (com.hayeco.flipspaces.core.spatial.PanelPrepare.buildChain(need, builtLevels)) {
            setMaxLevel(need - 1)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            builtLevels = need
            built = need
        }
        // Sampled down to the levels that are valid (and needed).
        if (need > 1) setMaxLevel(need - 1)
        val filter = if (need > 1) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
        if (filter != minFilter) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            minFilter = filter
        }
        return built
    }

    /** 0.5.17: the levels of the chain valid since the last copy (1: level 0 only). */
    private var builtLevels = 1
    private var maxLevel = -1

    private fun setMaxLevel(l: Int) {
        if (l == maxLevel) return
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, l)
        maxLevel = l
    }

    /**
     * The brightest pixel (luma 0..1) of a small mip level, at most 16 pixels across: enough
     * to tell a black panel from one with content, cheap to read back once a second. 0.5.17:
     * returns with the levels built for it (0 when the chain was already there) in [lumaChainLevels].
     */
    var lumaChainLevels = 0
        private set

    fun maxLuma(): Float {
        if (id == 0) return 1f
        lumaChainLevels = prepare(levels)
        var level = 0
        while (level < levels - 1 && (width shr level) > 16) level++
        val lw = (width shr level).coerceAtLeast(1)
        val lh = (height shr level).coerceAtLeast(1)
        if (readFbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            readFbo = ids[0]
        }
        val buf = readBuffer?.takeIf { it.capacity() >= lw * lh * 4 }
            ?: ByteBuffer.allocateDirect(lw * lh * 4).order(ByteOrder.nativeOrder()).also { readBuffer = it }
        buf.clear()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, id, level)
        GLES30.glReadPixels(0, 0, lw, lh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        var max = 0f
        for (i in 0 until lw * lh) {
            val r = buf.get(i * 4).toInt() and 0xff
            val g = buf.get(i * 4 + 1).toInt() and 0xff
            val b = buf.get(i * 4 + 2).toInt() and 0xff
            max = maxOf(max, (0.299f * r + 0.587f * g + 0.114f * b) / 255f)
        }
        return max
    }

    fun release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        GlKit.deleteTexture(id)
        id = 0
        fbo = 0
        readFbo = 0
        width = 0
        height = 0
    }
}
