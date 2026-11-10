package com.hayeco.flipspaces.spatial

/**
 * 1.4.1: a line to say on the glasses from code that has no Space at hand (the display control's Steady mode). The Space's
 * renderer takes it on its next frame and shows it like its other notices; with no Space up it waits for the next one.
 */
object GlassesNotice {
    @Volatile private var pending: Pair<String, Long>? = null

    fun post(text: String, ms: Long = 8_000L) {
        pending = text to ms
    }

    fun take(): Pair<String, Long>? = pending?.also { pending = null }
}
