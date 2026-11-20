package com.hayeco.flipspaces.pcscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hayeco.flipspaces.Log
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvHTTP

/**
 * PC screens: the clipboard shared both ways while a PC screen streams, through Apollo's clipboard requests
 * (`/actions/clipboard?type=text`, HTTPS with the phone's certificate, allowed only while the phone has a stream and has
 * the read and set clipboard permissions the PC helper gives). Text only. Sunshine has no such request: the first 404
 * stops it for that stream.
 *
 * - PC to phone: the PC's clipboard read every [POLL_MS]; new text goes on the phone's clipboard.
 * - Phone to PC: the phone's clipboard read when it changes and when this window gains focus (Android lets an app read
 *   it only then); new text goes on the PC's clipboard.
 * Each side's last text is remembered, so a copy is not sent straight back.
 */
class PcScreenClipboard(context: Context, private val http: () -> NvHTTP?) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var last: String? = null
    private var thread: Thread? = null

    private val changed = ClipboardManager.OnPrimaryClipChangedListener { phoneToPc() }

    fun start() {
        if (running || clipboard == null) return
        running = true
        clipboard.addPrimaryClipChangedListener(changed)
        thread = Thread({ poll() }, "PcScreen-clipboard").also { it.start() }
        Log.i(TAG, "clipboard: shared while streaming")
    }

    fun stop() {
        running = false
        runCatching { clipboard?.removePrimaryClipChangedListener(changed) }
        thread?.interrupt()
        thread = null
    }

    /** The phone's clipboard to the PC, if it holds new text (on a change, and when the window gains focus). */
    fun phoneToPc() {
        if (!running) return
        val text = runCatching { clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return
        if (text.isEmpty() || text == last) return
        last = text
        Thread({
            val r = runCatching { http()?.setClipboardText(text) }
            Log.i(TAG, "clipboard: phone to PC, ${text.length} characters: ${r.exceptionOrNull() ?: "done"}")
        }, "PcScreen-clip-set").start()
    }

    private fun poll() {
        while (running) {
            try {
                val text = http()?.getClipboardText()
                if (!text.isNullOrEmpty() && text != last) {
                    last = text
                    main.post { runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("PC", text)) } }
                    Log.i(TAG, "clipboard: PC to phone, ${text.length} characters")
                }
            } catch (e: java.io.FileNotFoundException) {
                Log.i(TAG, "clipboard: this host has no clipboard request (Sunshine): not shared")
                running = false
                return
            } catch (e: HostHttpResponseException) {
                // 403 while the stream is still starting, or with no permission: keep trying quietly.
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // Network blips: the next poll tries again.
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val TAG = "PcScreen"
        const val POLL_MS = 1500L
    }
}
