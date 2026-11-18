package com.hayeco.flipspaces.spatial

import android.content.Context
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.GlassesControl
import com.hayeco.flipspaces.core.spatial.GlassesLogFormat
import com.hayeco.flipspaces.core.spatial.GlassesLogLines
import com.hayeco.flipspaces.core.spatial.GlassesLogRelay
import com.hayeco.flipspaces.core.spatial.HeadShellCommand
import com.hayeco.flipspaces.core.spatial.HeadShellHeader
import com.hayeco.flipspaces.platform.ShizukuBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 0.5.24: receives the glasses' own log while the warp has it on
 * (the net log switch, [GlassesControl.NET_LOG]). The glasses' modules connect to the phone at
 * 169.254.2.10:51989, one TCP connection each, and write text lines; nothing comes on the control link.
 *
 * It listens the way the control link reached the glasses: with the app's own socket when that worked, or through
 * the Shizuku shell helper ([com.hayeco.flipspaces.platform.HeadStreamShell] LISTEN) when the app's own socket is
 * refused on the glasses' network (when a VPN is on). Every line goes to the log under [TAG] with the phone's
 * time and the connection, so the fs.log recorder has it, and to a file of this warp's own in the app's files
 * folder, `files/glasses-log/glasses-log-<date>-<time>.txt`.
 */
object GlassesLogReceiver {
    const val TAG = "FlipSpaces/GlassesLog"
    private const val DIR = "glasses-log"

    /** Past this the session's file stops growing (the log carries on); 0.5.24's INFO level should stay well under it. */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    private val lock = Object()
    private var session: Session? = null

    /** Up: the listener is bound, so the glasses' modules find it when the net log on goes out. */
    val ready: Boolean get() = session?.ready == true

    val error: String? get() = session?.error

    fun summary(): String = session?.summary() ?: "no lines"

    /** Starts listening, on its own thread, by [route] (the control link's, or null for the app's own socket). */
    fun open(context: Context, route: GlassesNetwork.Route?) {
        synchronized(lock) {
            if (session != null) return
            session = Session(context.applicationContext, route).also { s -> Thread({ s.run() }, "glasses-log").apply { isDaemon = true; start() } }
        }
    }

    fun close(why: String) {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        s.close(why)
    }

    private class Session(private val context: Context, private val route: GlassesNetwork.Route?) {
        @Volatile var ready = false
        @Volatile var error: String? = null
        @Volatile private var closed = false
        private val startedMs = System.currentTimeMillis()
        private var server: ServerSocket? = null
        private var helper: Process? = null
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        private var file: File? = null
        private var writer: Writer? = null
        private var fileBytes = 0L
        private var lines = 0L
        private var connections = 0
        private var openNow = 0

        fun summary(): String = synchronized(this) { "$lines line${if (lines == 1L) "" else "s"} on $connections connection${if (connections == 1) "" else "s"} ($openNow open)${file?.let { ", in ${it.name}" } ?: ""}" }

        fun run() {
            openFile()
            try {
                if (route is GlassesNetwork.Route.Shell) shell(route) else own()
            } catch (e: Exception) {
                if (!closed) fail("${e.javaClass.simpleName}: ${e.message}")
            }
        }

        private fun fail(why: String) {
            error = why
            note("the listener for the glasses' own log could not start: $why; their log on (10280) is not sent")
        }

        /** The app's own socket, bound to the phone's address on the glasses' network. */
        private fun own() {
            val s = ServerSocket()
            server = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName(GlassesControl.NET_LOG_PHONE), GlassesControl.NET_LOG_PORT))
            if (closed) {
                runCatching { s.close() }
                return
            }
            ready = true
            note("listening for the glasses' own log on ${GlassesControl.NET_LOG_PHONE}:${GlassesControl.NET_LOG_PORT} with Pocket XR's own socket (the control link's route: ${route?.label ?: "unknown"})")
            var next = 0
            while (!closed) {
                val c = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                val id = ++next
                sockets += c
                opened(id, "${c.inetAddress.hostAddress}:${c.port}")
                Thread({
                    val split = GlassesLogLines()
                    val buf = ByteArray(8192)
                    var why = "the glasses closed it"
                    try {
                        val input = c.getInputStream()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            split.append(buf, n).forEach { line(id, it) }
                        }
                    } catch (e: Exception) {
                        why = if (closed) "Pocket XR closed it" else "${e.javaClass.simpleName}: ${e.message}"
                    }
                    split.finish()?.let { line(id, it) }
                    closedConn(id, why)
                    sockets -= c
                    runCatching { c.close() }
                }, "glasses-log-$id").apply { isDaemon = true; start() }
            }
        }

        /** The Shizuku shell helper listens and relays ([GlassesLogRelay]). */
        private fun shell(r: GlassesNetwork.Route.Shell) {
            val p = ShizukuBridge.headStreamHelper(context)
            helper = p
            if (closed) {
                runCatching { p.destroy() }
                return
            }
            Thread({ runCatching { p.errorStream.bufferedReader().forEachLine { Log.i(TAG, "glasses log: shell helper: $it") } } }, "glasses-log-err").apply { isDaemon = true; start() }
            p.outputStream.write((HeadShellCommand.listen(HeadShellCommand.Listen(r.net.networkHandle, r.iface, GlassesControl.NET_LOG_PORT)) + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
            val out = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            val head = out.readLine() ?: throw java.io.IOException("the Shizuku helper ended before answering")
            when (val h = HeadShellHeader.parse(head)) {
                is HeadShellHeader.Parsed.Ok -> {
                    ready = true
                    note("listening for the glasses' own log through the Shizuku shell helper (${r.label}): ${h.detail}")
                }
                is HeadShellHeader.Parsed.Failed -> {
                    fail("Shizuku helper: ${h.error}")
                    return
                }
                is HeadShellHeader.Parsed.Garbled -> {
                    fail("the Shizuku helper printed something else: ${h.line.take(200)}")
                    return
                }
            }
            while (!closed) {
                val l = out.readLine() ?: break
                when (val e = GlassesLogRelay.parse(l)) {
                    is GlassesLogRelay.Event.Opened -> opened(e.connection, e.remote)
                    is GlassesLogRelay.Event.Line -> line(e.connection, e.text)
                    is GlassesLogRelay.Event.Closed -> closedConn(e.connection, e.why)
                    null -> Log.i(TAG, "glasses log: shell helper said: ${l.take(300)}")
                }
            }
            if (!closed) note("the Shizuku helper for the glasses' own log ended")
        }

        private fun opened(id: Int, remote: String) {
            synchronized(this) {
                connections++
                openNow++
            }
            note("connection $id from $remote (a glasses module's log)")
        }

        private fun closedConn(id: Int, why: String) {
            synchronized(this) { openNow-- }
            note("connection $id ended: $why")
        }

        private fun line(id: Int, text: String) {
            val now = System.currentTimeMillis()
            val out = GlassesLogFormat.line(now, id, text)
            Log.i(TAG, out)
            // 0.5.25: the warp counts the glasses' own scene mode drops and recentres.
            GlassesWarp.glassesLogLine(text)
            synchronized(this) {
                lines++
                write(out)
            }
        }

        /** Pocket XR's own lines about the glasses' log, in logcat and the file alike. */
        private fun note(text: String) {
            val out = "${GlassesLogFormat.stamp(System.currentTimeMillis())} glasses log: $text"
            Log.i(TAG, out)
            synchronized(this) { write(out) }
        }

        private fun openFile() {
            try {
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                val f = File(dir, GlassesLogFormat.fileName(startedMs))
                synchronized(this) {
                    file = f
                    writer = f.bufferedWriter(Charsets.UTF_8)
                }
                note("this warp's copy of the glasses' own log: ${f.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "glasses log: no file for this warp (${e.javaClass.simpleName}: ${e.message}); the lines still go to the log")
            }
        }

        /** Under the lock. */
        private fun write(line: String) {
            val w = writer ?: return
            if (fileBytes > MAX_FILE_BYTES) return
            try {
                w.write(line)
                w.write("\n")
                w.flush()
                fileBytes += line.length + 1
                if (fileBytes > MAX_FILE_BYTES) {
                    w.write("${GlassesLogFormat.stamp(System.currentTimeMillis())} glasses log: this file reached ${MAX_FILE_BYTES / (1024 * 1024)} MB; the rest is in the log only\n")
                    w.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "glasses log: the file write failed (${e.javaClass.simpleName}: ${e.message}); the lines still go to the log")
                runCatching { w.close() }
                writer = null
            }
        }

        fun close(why: String) {
            closed = true
            note("listener closed ($why): ${summary()}")
            runCatching { server?.close() }
            helper?.let { p ->
                runCatching { p.outputStream.close() }
                runCatching { p.destroy() }
            }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            synchronized(this) {
                runCatching { writer?.close() }
                writer = null
            }
        }
    }
}
