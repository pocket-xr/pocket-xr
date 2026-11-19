package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.os.SystemClock
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.File

/**
 * PC screens: one sound for all of them.
 *
 * What Mike's phone showed (19 Nov 2026):
 * - two PC screens each playing the sound played it twice, a little apart;
 * - Apollo sends the PC's sound to its newest session only: when PC screen 2 connected, PC screen 1's sound stopped
 *   coming, so "the first PC screen plays it" went silent.
 *
 * So each PC screen notes, in a small file shared by the PC screen processes, when it started and when it last
 * received sound that is not silence. A PC screen plays what it receives unless another PC screen, started later, is
 * receiving sound too: then that one plays and this one feeds silence. Whichever screen Apollo sends the sound to is
 * heard, once.
 */
class PcScreenAudio(context: Context, private val slot: Int, private val inner: AudioRenderer) : AudioRenderer {
    private val dir = context.filesDir
    private val mine = file(dir, slot)
    private val startedMs = SystemClock.elapsedRealtime()
    private var lastLoudMs = 0L
    private var lastWriteMs = 0L
    private var lastCheckMs = 0L
    private var playing = true
    private var silence = ShortArray(0)

    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int {
        write(SystemClock.elapsedRealtime())
        return inner.setup(audioConfiguration, sampleRate, samplesPerFrame)
    }

    override fun start() = inner.start()

    override fun stop() {
        inner.stop()
        runCatching { mine.delete() }
    }

    override fun cleanup() {
        inner.cleanup()
        runCatching { mine.delete() }
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        val now = SystemClock.elapsedRealtime()
        var peak = 0
        for (s in audioData) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > peak) peak = a
        }
        if (peak > LOUD) lastLoudMs = now
        if (now - lastWriteMs >= 250) write(now)
        if (now - lastCheckMs >= 250) {
            lastCheckMs = now
            val next = shouldPlay(now)
            if (next != playing) {
                playing = next
                Log.i("PcScreen", "sound: PC screen ${slot + 1} ${if (next) "plays the sound" else "is quiet, a later PC screen has the sound"}")
            }
        }
        if (playing) {
            inner.playDecodedAudio(audioData)
        } else {
            // Silence keeps the audio track fed, so it is ready the moment this screen has the sound again.
            if (silence.size != audioData.size) silence = ShortArray(audioData.size)
            inner.playDecodedAudio(silence)
        }
    }

    private fun write(now: Long) {
        lastWriteMs = now
        runCatching { mine.writeText("$startedMs $lastLoudMs") }
    }

    /** Plays unless another PC screen, started later, has had sound in the last 3 s. */
    private fun shouldPlay(now: Long): Boolean {
        for (other in 0 until PcScreenSlots.COUNT) {
            if (other == slot) continue
            val parts = runCatching { file(dir, other).readText().trim().split(' ') }.getOrNull() ?: continue
            val started = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val loud = parts.getOrNull(1)?.toLongOrNull() ?: continue
            if (started > startedMs && now - loud < LOUD_WINDOW_MS) return false
        }
        return true
    }

    companion object {
        /** Peak above this (of 32767) is sound, not silence or noise. */
        const val LOUD = 300
        // 1 s let the sound hop between screens at every pause in it, two screens playing for a moment (phone log,
        // 19 Nov 2026); 3 s rides over pauses in music and speech.
        const val LOUD_WINDOW_MS = 3000L

        private fun file(dir: File, slot: Int) = File(dir, "pc_screen_audio_${slot + 1}")

        /**
         * How many other PC screens are streaming now: their sound file was written in the last 5 s (every 250 ms
         * while a stream's sound arrives; removed when it stops).
         */
        fun othersStreaming(context: Context, slot: Int): Int {
            val now = System.currentTimeMillis()
            return (0 until PcScreenSlots.COUNT).count { other ->
                other != slot && file(context.filesDir, other).let { it.exists() && now - it.lastModified() < 5000 }
            }
        }
    }
}
