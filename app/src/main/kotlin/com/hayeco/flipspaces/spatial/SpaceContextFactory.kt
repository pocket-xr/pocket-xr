package com.hayeco.flipspaces.spatial

import android.opengl.GLSurfaceView
import com.hayeco.flipspaces.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * 0.5.19 (the eye passes' p99 reached 13.5 ms, against the 6.6 ms the late
 * draw needs for under 1 % missed frames, while the GPU also ran the hand model on 30 camera frames a
 * second and SurfaceFlinger's GPU composition): the Space's OpenGL ES 3 context asks the driver for
 * high priority (EGL_IMG_context_priority, which Adreno offers), so its few short passes go ahead of
 * MediaPipe's long ones instead of queueing behind them. Where the extension is missing, or the driver
 * refuses, it is an ordinary context, as before; which one it got is logged once per context.
 */
internal object SpaceContextFactory : GLSurfaceView.EGLContextFactory {
    private const val TAG = "FlipSpaces/Space"
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    private const val EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102
    private const val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103

    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        val plain = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
        val offered = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS)?.contains("EGL_IMG_context_priority") == true
        if (offered) {
            val high = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL10.EGL_NONE)
            val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, high)
            if (ctx != null && ctx != EGL10.EGL_NO_CONTEXT) {
                val got = IntArray(1)
                val asked = egl.eglQueryContext(display, ctx, EGL_CONTEXT_PRIORITY_LEVEL_IMG, got)
                Log.i(TAG, "GPU context priority: asked high, the driver gave ${if (asked) name(got[0]) else "an unknown level"} (EGL_IMG_context_priority)")
                return ctx
            }
            Log.i(TAG, "GPU context priority: the driver refused a high priority context (EGL error 0x${Integer.toHexString(egl.eglGetError())}); an ordinary one")
        } else {
            Log.i(TAG, "GPU context priority: EGL_IMG_context_priority not offered; an ordinary context")
        }
        return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, plain)
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        if (!egl.eglDestroyContext(display, context)) Log.w(TAG, "eglDestroyContext failed: 0x${Integer.toHexString(egl.eglGetError())}")
    }

    private fun name(level: Int): String = when (level) {
        EGL_CONTEXT_PRIORITY_HIGH_IMG -> "high"
        EGL_CONTEXT_PRIORITY_MEDIUM_IMG -> "medium"
        EGL_CONTEXT_PRIORITY_LOW_IMG -> "low"
        else -> "level 0x${Integer.toHexString(level)}"
    }
}
