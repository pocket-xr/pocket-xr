package com.hayeco.flipspaces.spatial

import android.opengl.GLES30
import android.opengl.GLUtils
import com.hayeco.flipspaces.core.spatial.Mat4
import com.hayeco.flipspaces.core.spatial.Wallpaper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 1.6.37: draws the wallpaper ([Wallpaper]) behind everything in each eye's pass, first, as a sky at infinity: only the
 * head's turn moves it (not its position, and not the eye's offset, so both eyes see it at the same place, as far away as
 * it can be). It is one pass over the whole eye: each pixel's direction comes from the inverse of the turn and the
 * projection, so it needs no mesh and has no seams; the per-row pose blends a top and a bottom direction like every
 * other draw. Stars are points drawn after it ([Wallpaper.Painter.Stars]), sharp at the glasses' own resolution.
 *
 * GL thread only. [WallpaperLoader] paints or decodes off it; [draw] uploads what it made on the next frame.
 */
class WallpaperGl {
    private var sky = 0
    private var starsProg = 0
    private var texture = 0
    private var starVbo = 0
    private var starCount = 0

    // Sky uniforms.
    private var uInvTop = -1
    private var uInvBottom = -1
    private var uRowBlend = -1
    private var uTex = -1
    private var uMode = -1
    private var uGain = -1
    private var uHalf = -1
    private var uAmbient = -1

    // Star uniforms.
    private var sVp = -1
    private var sRowFix = -1
    private var sRowBlend = -1
    private var sTex = -1
    private var sGain = -1
    private var sSize = -1

    /** What is on the GPU now, and what the loader made last; uploaded when they differ. */
    private var shown: WallpaperLoader.Loaded? = null

    /** The wallpaper's line for the log, once a change is drawn. */
    var line: String? = null
        private set

    /** Call from onSurfaceCreated: a new context has none of the old one's programs or textures. */
    fun init() {
        sky = link(SKY_VS, SKY_FS)
        uInvTop = GLES30.glGetUniformLocation(sky, "uInvTop")
        uInvBottom = GLES30.glGetUniformLocation(sky, "uInvBottom")
        uRowBlend = GLES30.glGetUniformLocation(sky, "uRowBlend")
        uTex = GLES30.glGetUniformLocation(sky, "uTex")
        uMode = GLES30.glGetUniformLocation(sky, "uMode")
        uGain = GLES30.glGetUniformLocation(sky, "uGain")
        uHalf = GLES30.glGetUniformLocation(sky, "uHalf")
        uAmbient = GLES30.glGetUniformLocation(sky, "uAmbient")
        starsProg = link(STAR_VS, STAR_FS)
        sVp = GLES30.glGetUniformLocation(starsProg, "uVp")
        sRowFix = GLES30.glGetUniformLocation(starsProg, "uRowFix")
        sRowBlend = GLES30.glGetUniformLocation(starsProg, "uRowBlend")
        sTex = GLES30.glGetUniformLocation(starsProg, "uTex")
        sGain = GLES30.glGetUniformLocation(starsProg, "uGain")
        sSize = GLES30.glGetUniformLocation(starsProg, "uSize")
        texture = 0
        starVbo = 0
        starCount = 0
        shown = null
    }

    /**
     * One eye's wallpaper, before its windows. [projection] is the eye's (cropped as it is placed), [view] and
     * [viewBottom] the head's at the top and bottom row (null: one pose for the whole eye).
     */
    fun draw(projection: Mat4, view: Mat4, viewBottom: Mat4?) {
        val loaded = WallpaperLoader.loaded
        if (loaded !== shown) upload(loaded)
        val l = shown ?: return
        if (l is WallpaperLoader.Loaded.None || texture == 0) return
        val top = projection * turnOnly(view)
        val bottom = viewBottom?.let { projection * turnOnly(it) }
        GLES30.glUseProgram(sky)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniformMatrix4fv(uInvTop, 1, false, top.inverse().m, 0)
        GLES30.glUniformMatrix4fv(uInvBottom, 1, false, (bottom ?: top).inverse().m, 0)
        GLES30.glUniform1f(uRowBlend, if (bottom != null) 1f else 0f)
        GLES30.glUniform1f(uGain, l.gain)
        when (l) {
            is WallpaperLoader.Loaded.Painted -> GLES30.glUniform1i(uMode, MODE_PAINTED)
            is WallpaperLoader.Loaded.Picture -> {
                GLES30.glUniform1i(uMode, if (l.panorama) MODE_PANORAMA else MODE_FRAMED)
                GLES30.glUniform2f(uHalf, Math.toRadians(l.frame.widthDeg / 2.0).toFloat(), Math.toRadians(l.frame.heightDeg / 2.0).toFloat())
                GLES30.glUniform3f(uAmbient, l.ambient[0], l.ambient[1], l.ambient[2])
            }
            is WallpaperLoader.Loaded.None -> Unit
        }
        // Premultiplied blending everywhere: an opaque pixel (alpha 1) simply replaces what the clear left.
        GlKit.drawQuad()
        if (starCount > 0) {
            GLES30.glUseProgram(starsProg)
            GLES30.glUniformMatrix4fv(sVp, 1, false, top.m, 0)
            GLES30.glUniformMatrix4fv(sRowFix, 1, false, bottom?.let { com.hayeco.flipspaces.core.spatial.RowScan.fix(top, it).m } ?: GlKit.IDENTITY, 0)
            GLES30.glUniform1f(sRowBlend, if (bottom != null) 1f else 0f)
            GLES30.glUniform1i(sTex, 0)
            GLES30.glUniform1f(sGain, l.gain)
            GLES30.glUniform1f(sSize, STAR_POINT_PX)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
            // Alpha 0 under premultiplied blending: each star's light is added to the sky's.
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)
            GlKit.bindQuad()
        }
    }

    private fun upload(l: WallpaperLoader.Loaded?) {
        // Only the brightness or the picture's width changed (a slider moving): the same pixels stay on the GPU.
        if (texture != 0 && samePixels(shown, l)) {
            shown = l
            line = l?.line
            return
        }
        if (texture != 0) GlKit.deleteTexture(texture)
        texture = 0
        if (starVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(starVbo), 0)
        starVbo = 0
        starCount = 0
        shown = l
        if (l == null || l is WallpaperLoader.Loaded.None) {
            line = l?.line
            return
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        when (l) {
            is WallpaperLoader.Loaded.Painted -> {
                // Half floats: the scenes' dark gradients would show steps at 8 bits. Always enlarged on the glasses (5.7
                // texels a degree against their 44 pixels), so no mipmaps.
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, l.width, l.height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, l.pixels.also { it.position(0) })
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val pts = l.stars
                if (pts.isNotEmpty()) {
                    val buf = ByteBuffer.allocateDirect(pts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(pts).also { it.position(0) }
                    val b = IntArray(1)
                    GLES30.glGenBuffers(1, b, 0)
                    starVbo = b[0]
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pts.size * 4, buf, GLES30.GL_STATIC_DRAW)
                    starCount = pts.size / 4
                    GlKit.bindQuad()
                }
            }
            is WallpaperLoader.Loaded.Picture -> {
                // OpenGL ES 3 promises only 2048 pixels a side; most phones take 8192 or more.
                val max = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
                val big = maxOf(l.bitmap.width, l.bitmap.height)
                val bmp = if (max in 1 until big) {
                    val k = max.toFloat() / big
                    android.graphics.Bitmap.createScaledBitmap(l.bitmap, (l.bitmap.width * k).toInt(), (l.bitmap.height * k).toInt(), true)
                } else {
                    l.bitmap
                }
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                if (bmp !== l.bitmap) bmp.recycle()
                // A picture can be finer than the glasses' pixels (a narrow screen of a big photo): mipmaps, and
                // the panorama's seam taken care of in the shader.
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, if (l.panorama) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                if (GlKit.maxAnisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GlKit.TEXTURE_MAX_ANISOTROPY_EXT, minOf(4f, GlKit.maxAnisotropy))
            }
            is WallpaperLoader.Loaded.None -> Unit
        }
        val err = GLES30.glGetError()
        line = if (err == GLES30.GL_NO_ERROR) l.line else "${l.line}; not drawn: GL error 0x${Integer.toHexString(err)}"
        if (err != GLES30.GL_NO_ERROR) {
            GlKit.deleteTexture(texture)
            texture = 0
        }
    }

    private fun samePixels(a: WallpaperLoader.Loaded?, b: WallpaperLoader.Loaded?): Boolean = when {
        a is WallpaperLoader.Loaded.Painted && b is WallpaperLoader.Loaded.Painted -> a.pixels === b.pixels
        a is WallpaperLoader.Loaded.Picture && b is WallpaperLoader.Loaded.Picture -> a.bitmap === b.bitmap
        else -> false
    }

    /** [view] without its position: the wallpaper is at infinity, so only the head's turn moves it. */
    private fun turnOnly(view: Mat4): Mat4 = Mat4(view.m.copyOf().also { it[12] = 0f; it[13] = 0f; it[14] = 0f })

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "wallpaper program: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "wallpaper shader: ${GLES30.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        const val MODE_PAINTED = 0
        const val MODE_PANORAMA = 1
        const val MODE_FRAMED = 2

        /** A star's point, pixels: its soft dot fits inside with room to move between pixels. */
        const val STAR_POINT_PX = 5f

        /**
         * The quad (8 strips, -0.5 to 0.5) stretched over the whole eye. Each corner's direction through the inverse
         * turn and projection: it is linear across the screen (the projection's w is the same at every pixel for a
         * fixed depth), so the interpolated direction is exact.
         */
        private const val SKY_VS = """#version 300 es
uniform mat4 uInvTop;
uniform mat4 uInvBottom;
uniform float uRowBlend;
layout(location = 0) in vec2 aPos;
out vec3 vTop;
out vec3 vBottom;
out float vRow;
void main() {
    vec2 ndc = aPos * 2.0;
    vec4 a = uInvTop * vec4(ndc, 1.0, 1.0);
    vec4 b = uInvBottom * vec4(ndc, 1.0, 1.0);
    vTop = a.xyz / a.w;
    vBottom = b.xyz / b.w;
    vRow = clamp((1.0 - ndc.y) * 0.5, 0.0, 1.0) * uRowBlend;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""

        /**
         * Painted: the scene's picture (wrapping left to right). Panorama: the same with the photo, its mipmaps chosen
         * from derivatives that do not jump where the picture wraps. Framed: the photo as a curved screen ahead (part of a cylinder around you), its edge
         * smoothed over a few hundredths of a degree, and around it the photo's own colour, darker, glowing near the
         * edge so the picture seems to light the space. A little noise breaks the 8 bit steps of the glasses' panels.
         */
        private const val SKY_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform int uMode;
uniform float uGain;
uniform vec2 uHalf;
uniform vec3 uAmbient;
in vec3 vTop;
in vec3 vBottom;
in float vRow;
out vec4 fragColor;
const float PI = 3.14159265;
void main() {
    vec3 d = normalize(mix(vTop, vBottom, vRow));
    float yaw = atan(d.x, -d.z);
    float pitch = asin(clamp(d.y, -1.0, 1.0));
    vec3 c;
    if (uMode == 2) {
        // On a cylinder around you: across, the arc; up, the height where the direction meets it.
        float up = d.y / max(length(d.xz), 1e-4);
        vec2 uv = vec2(yaw / (2.0 * uHalf.x) + 0.5, 0.5 - up / (2.0 * tan(uHalf.y)));
        float e = max(abs(yaw) - uHalf.x, abs(pitch) - uHalf.y);
        vec3 pic = texture(uTex, clamp(uv, 0.0, 1.0)).rgb;
        float inside = 1.0 - smoothstep(-0.0015, 0.0015, e);
        vec3 around = uAmbient * (0.3 + 0.7 * exp(-max(e, 0.0) * 4.0));
        c = mix(around, pic, inside);
    } else {
        vec2 uv = vec2(yaw / (2.0 * PI) + 0.5, 0.5 - pitch / PI);
        if (uMode == 1) {
            vec2 uv2 = vec2(fract(uv.x + 0.5), uv.y);
            vec2 dx = dFdx(uv);
            vec2 dy = dFdy(uv);
            vec2 dx2 = dFdx(uv2);
            vec2 dy2 = dFdy(uv2);
            if (abs(dx2.x) < abs(dx.x)) dx.x = dx2.x;
            if (abs(dy2.x) < abs(dy.x)) dy.x = dy2.x;
            c = textureGrad(uTex, uv, dx, dy).rgb;
        } else {
            c = texture(uTex, uv).rgb;
        }
    }
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    fragColor = vec4(max(c * uGain + (n - 0.5) / 255.0, 0.0), 1.0);
}
"""

        /** Each star a point at its direction, 10 m out (inside the projection's far plane), hidden behind hills. */
        private const val STAR_VS = """#version 300 es
precision highp float;
uniform mat4 uVp;
uniform mat4 uRowFix;
uniform float uRowBlend;
uniform sampler2D uTex;
uniform float uGain;
uniform float uSize;
layout(location = 0) in vec4 aPos;
out float vLight;
const float PI = 3.14159265;
void main() {
    vec3 d = aPos.xyz;
    vec4 top = uVp * vec4(d * 10.0, 1.0);
    vec4 bottom = uRowFix * top;
    float w = clamp((1.0 - top.y / top.w) * 0.5, 0.0, 1.0) * uRowBlend;
    gl_Position = mix(top, bottom, w);
    vec2 uv = vec2(atan(d.x, -d.z) / (2.0 * PI) + 0.5, 0.5 - asin(clamp(d.y, -1.0, 1.0)) / PI);
    vLight = aPos.w * uGain * textureLod(uTex, uv, 0.0).a;
    gl_PointSize = uSize;
}
"""

        /** A soft dot (as the phone's preview draws it), its light added: premultiplied, alpha 0. */
        private const val STAR_FS = """#version 300 es
precision highp float;
uniform float uSize;
in float vLight;
out vec4 fragColor;
void main() {
    vec2 p = (gl_PointCoord - 0.5) * uSize;
    float l = vLight * exp(-dot(p, p) / 0.6);
    if (l < 0.001) discard;
    fragColor = vec4(l, l, l * 1.05, 0.0);
}
"""
    }
}
