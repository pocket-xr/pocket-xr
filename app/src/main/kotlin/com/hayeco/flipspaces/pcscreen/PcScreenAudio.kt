package com.hayeco.flipspaces.pcscreen

import android.content.Context
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * PC screens: one sound for all of them. Every Apollo instance on a PC sends the same PC sound, so two PC screens
 * played it twice, a little apart (Mike's phone, 19 Nov 2026). The first PC screen to stream takes a file lock shared by
 * the PC screen processes and plays the sound; the others play nothing until it lets go.
 */
object PcScreenAudio {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** True when this process now carries the sound (or already did). */
    @Synchronized
    fun claim(context: Context): Boolean {
        if (lock?.isValid == true) return true
        return runCatching {
            val ch = channel ?: RandomAccessFile(File(context.filesDir, "pc_screen_audio.lock"), "rw").channel.also { channel = it }
            lock = ch.tryLock()
            lock != null
        }.getOrDefault(false).also { Log.i("PcScreen", "sound: ${if (it) "this PC screen plays it" else "another PC screen plays it"}") }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.release() }
        lock = null
    }

    /** Takes the stream's sound and plays none of it. */
    class Silent : AudioRenderer {
        override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int) = 0
        override fun start() = Unit
        override fun stop() = Unit
        override fun playDecodedAudio(audioData: ShortArray) = Unit
        override fun cleanup() = Unit
    }
}
