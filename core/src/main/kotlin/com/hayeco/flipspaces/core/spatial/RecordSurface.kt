package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/** One EGL config's sizes, read with eglGetConfigAttrib, and whether a video encoder's surface takes it (EGL_RECORDABLE_ANDROID). */
data class EglConfigSizes(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
    val depth: Int,
    val stencil: Int,
    val samples: Int,
    val recordable: Boolean,
) {
    /** The same buffers as [other]: a surface of one and a context of the other can be made current together. */
    fun sameBuffers(other: EglConfigSizes): Boolean =
        red == other.red && green == other.green && blue == other.blue && alpha == other.alpha &&
            depth == other.depth && stencil == other.stencil && samples == other.samples
}

/**
 * 1.2.11 (Mike's Flip 7, Xclipse 950, Android 17: Record lit for a tenth of a second and nothing was saved, every tap
 * since the logs began, "the encoder's surface could not be made current (EGL 0x3009)"): which config the recording's
 * encoder surface is made with, in the order to try. 1.1.5 took the first RGBA8888 recordable config, whatever its other
 * buffers; the Space's own context is RGB888 with a 16 bit depth buffer (GLSurfaceView's default), and a strict driver
 * refuses a surface whose config doesn't match the context's (EGL_BAD_MATCH). Adreno took it anyway, which is why it
 * worked there.
 *
 * Indexes are into the recordable configs the driver offered; [OWN] is the Space's own config. The own config first when
 * it is recordable itself; else recordable configs with the own config's buffers (a recordable twin, for GPUs that drop
 * frames on a config that isn't recordable, issue 26), then the own config (always compatible with the context), then any
 * other recordable config, as before. The recorder makes each one current once, before recording, and keeps the first
 * that works.
 */
object RecordSurface {
    const val OWN = -1

    fun order(own: EglConfigSizes, recordable: List<EglConfigSizes>): List<Int> {
        if (own.recordable) return listOf(OWN)
        val twins = recordable.indices.filter { recordable[it].sameBuffers(own) }
        val others = recordable.indices.filter { it !in twins }
        return twins + OWN + others
    }

    /**
     * The line the glasses show when a recording ends: saved, recorded but empty, or never started. 1.2.11: a recording
     * that couldn't start said nothing at all, so a tap on Record looked ignored.
     */
    fun notice(kept: Boolean, frames: Long, seconds: Double): String = when {
        kept -> "Saved to Movies/Pocket XR (%.0f s)".format(Locale.ROOT, seconds)
        frames > 0 -> "The recording didn't work on this phone. Nothing was saved."
        else -> "The recording couldn't start on this phone. Nothing was saved."
    }
}
