package com.hayeco.flipspaces.core

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 1.1.14 (a tester asked how to get his logs to Mike; there was a Share on the head tracking screen, but no way to send
 * the log files themselves without adb): personal details taken out of a log line before it leaves the phone.
 *
 * Email addresses (most Android account names are one), account names, Wi-Fi names, MAC and Bluetooth addresses, and IP
 * addresses are replaced with a marker. The glasses' own link stays: 169.254.x.x and fe80:: link-local addresses, and
 * 127.x and 0.0.0.0, which say nothing about where the phone is. [secrets] are extra words to take out wherever they
 * appear (the Wi-Fi names the phone knows).
 *
 * Bluetooth device names too ("Jordan's Buds" says whose earbuds they are) become <bt-device-N> ([BtNames]).
 */
object LogScrub {
    const val EMAIL_MARK = "<email>"
    const val ACCOUNT_MARK = "<account>"
    const val WIFI_MARK = "<wifi>"
    const val IP_MARK = "<ip>"
    const val MAC_MARK = "<mac>"

    /** Shorter words are not taken out as [secrets]: "Home" would take whole log lines with it. */
    const val MIN_SECRET = 4

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val ACCOUNT_BRACE = Regex("(Account \\{name=)([^,}]*)")
    private val ACCOUNT = Regex("(?i)(\\b(?:account|accountName|account name|user ?name)\\s*[:=]\\s*)(\"[^\"]*\"|[^,;\\s}\\]]+)")
    private val WIFI_CONNECTED = Regex("(Wifi is connected to )\"[^\"]*\"")
    // Not BSSID: that is the router's MAC address, taken out as one.
    private val WIFI_KEY = Regex("(?i)(\\bSSID\\b\"?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^,;\\s}\\]]+)")
    private val WIFI_FORGET = Regex("(Forget )(.+?)( done| failed:|$)")
    private val MAC = Regex("(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])")
    private val IPV4 = Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\.?\\d)")
    private val IPV6 = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[\\w.]+)?(?![0-9A-Fa-f:])")

    /**
     * [bt]: the Bluetooth device names taken out so far ([BtNames]); pass the same one for every line of a zip, so a
     * device is the same marker in every file.
     */
    fun line(text: String, secrets: Collection<String> = emptyList(), bt: BtNames = BtNames()): String {
        var s = text
        for (w in secrets) if (w.length >= MIN_SECRET && s.contains(w)) s = s.replace(w, WIFI_MARK)
        if ('@' in s) s = EMAIL.replace(s, EMAIL_MARK)
        if (s.contains("ccount", ignoreCase = true) || s.contains("name", ignoreCase = true)) {
            s = ACCOUNT_BRACE.replace(s) { it.groupValues[1] + ACCOUNT_MARK }
            s = ACCOUNT.replace(s) { m -> if (m.groupValues[2].contains('<')) m.value else m.groupValues[1] + ACCOUNT_MARK }
        }
        if (s.contains("SSID", ignoreCase = true) || s.contains("Wifi") || s.contains("Forget ")) {
            s = WIFI_CONNECTED.replace(s) { it.groupValues[1] + "\"$WIFI_MARK\"" }
            s = WIFI_KEY.replace(s) { it.groupValues[1] + WIFI_MARK }
            s = WIFI_FORGET.replace(s) { it.groupValues[1] + WIFI_MARK + it.groupValues[3] }
        }
        // After the Wi-Fi names (so "Wifi is connected to" stays Wi-Fi) and before the MACs (a device list line is
        // known by its address at the start).
        s = bt.line(s)
        if (':' in s || '-' in s) s = MAC.replace(s, MAC_MARK)
        if ('.' in s) s = IPV4.replace(s) { m -> if (keepIpv4(m.groupValues.drop(1).map { it.toInt() })) m.value else IP_MARK }
        if (':' in s) s = IPV6.replace(s) { m -> if (keepIpv6(m.value)) m.value else IP_MARK }
        return s
    }

    /** The whole text, line by line. */
    fun text(text: String, secrets: Collection<String> = emptyList(), bt: BtNames = BtNames()): String =
        text.lineSequence().joinToString("\n") { line(it, secrets, bt) }

    internal fun isMac(s: String) = MAC.matches(s)

    private fun keepIpv4(o: List<Int>): Boolean = when {
        o.any { it > 255 } -> true // not an address (a version or a count)
        o[0] == 169 && o[1] == 254 -> true // the glasses' link-local network
        o[0] == 127 -> true
        o.all { it == 0 } -> true
        o[0] == 255 -> true // a netmask
        else -> false
    }

    private fun keepIpv6(a: String): Boolean {
        val colons = a.count { it == ':' }
        // A clock time ("12:34:56") or a Kotlin "::" is not an address: an address has "::" or all eight groups.
        if (!a.contains("::") && colons < 7) return true
        val bare = a.substringBefore('%').lowercase()
        return bare.trim(':').isEmpty() || bare == "::1" || bare.startsWith("fe80:")
    }
}

/**
 * 1.1.16: Bluetooth device names (paired and connected devices, as BluetoothDevice.getName and getAlias give them, the
 * Control Center's device list, dumpsys bluetooth_manager's device lines) replaced with <bt-device-1>, <bt-device-2>,
 * numbered as they are first met. One per zip, so a device is the same marker in every file of it and Mike can still
 * tell "the same headset dropped twice". [known]: the paired names the phone knows, taken out wherever they appear.
 */
class BtNames(known: Collection<String> = emptyList()) {
    private val marks = LinkedHashMap<String, String>()
    private val known: List<Pair<String, Regex>> = known.map { it.trim() }.filter { plausible(it) && it.length >= MIN_NAME }
        .distinct().sortedByDescending { it.length }
        .map { it to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") }

    /** The names taken out so far, each with its marker, in the order they were met. */
    val names: Map<String, String> get() = LinkedHashMap(marks)

    /** The marker for [name], the same each time. */
    fun mark(name: String): String {
        val n = name.trim()
        return marks.getOrPut(n) { "<bt-device-${marks.size + 1}>" }
    }

    fun line(text: String): String {
        // On the line as it came: a name already marked does not make the line one about Bluetooth.
        val btLine = BT_CONTEXT.containsMatchIn(text)
        var s = text
        for ((name, re) in known) if (s.contains(name)) s = re.replace(s) { mark(name) }
        s = DATA.replace(s) { m -> m.groupValues[1] + markValue(m.groupValues[2]) }
        s = KEY.replace(s) { m -> m.groupValues[1] + markValue(m.groupValues[2]) }
        s = LINE_NAME.replace(s) { m -> m.groupValues[1] + markValue(m.groupValues[2]) }
        s = DEVICE_LIST.replace(s) { m -> m.groupValues[1] + markValue(m.groupValues[2]) }
        if (btLine) s = NAME.replace(s) { m -> m.groupValues[1] + markValue(m.groupValues[2]) }
        s = LINKED.replace(s) { m ->
            val v = m.groupValues[2]
            if (v.startsWith('"') || btLine) m.groupValues[1] + markValue(v) else m.value
        }
        // Any other quoted name on a Bluetooth line.
        if (btLine) s = QUOTED.replace(s) { m -> markValue(m.value) }
        return s
    }

    /** A name as it came (quoted or not, maybe with spaces or a next key= after it) with the name itself marked. */
    private fun markValue(v: String): String {
        val quote = v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]
        if (quote) {
            val inner = v.substring(1, v.length - 1)
            return if (plausible(inner.trim())) v[0] + mark(inner) + v[0] else v
        }
        // "alias: Jordan's Buds address=00:11:..." keeps the address for the MAC scrub.
        val inner = NEXT_KEY.find(v)?.let { v.substring(0, it.range.first) } ?: v.trimEnd()
        if (!plausible(inner.trim())) return v
        return mark(inner) + v.substring(inner.length)
    }

    companion object {
        /** Shorter paired names are not taken out wherever they appear ("TV" would take words with it). */
        const val MIN_NAME = 3

        // Unquoted: up to a comma or bracket, but with a name's own "(R)" in it; "(AA:BB:...)" after a name is not.
        private const val BARE = "(?:[^,;(){}\\[\\]\\r\\n]|\\((?![^)]*:)[^)\\r\\n]*\\))+"
        private const val VALUE = "(\"[^\"]*\"|'[^']*'|$BARE)"
        // BtDevice(address=..., name=..., connected=...) and ControlItem.BtDeviceRow(...), as Kotlin prints them.
        private val DATA = Regex("(\\bBtDevice(?:Row)?\\([^)]*?\\bname=)([^,)]*)")
        private val KEY = Regex("(?i)(\\b(?:device ?name|bt ?name|bluetooth ?name|remote ?name|friendly ?name|alias|getName\\(\\)|getAlias\\(\\))\\s*[:=]\\s*)$VALUE")
        // dumpsys bluetooth_manager: "  Name: Galaxy Buds2" on a line of its own.
        private val LINE_NAME = Regex("^(\\s*Name\\s*[:=]\\s*)(.+)$")
        // dumpsys bluetooth_manager's bonded list: "  AA:BB:CC:DD:EE:FF [ DUAL ][ 0x240404 ] Galaxy Buds2".
        private val DEVICE_LIST = Regex("^(\\s*(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\s+(?:\\[[^\\]]*\\]\\s*)*)(\\S.*)$")
        private val BT_CONTEXT = Regex("(?i)bluetooth|btdevice|\\bbt\\b|a2dp|\\bhfp\\b|headset|\\ble ?audio\\b|\\bbond|\\bpair|joy-?con|\\bhid\\b")
        private val NAME = Regex("(?i)((?<!user )(?<!user)(?<!account )(?<!package )\\bname\\s*[:=]\\s*)$VALUE")
        // "connected to <name>": a quoted name on any line, an unquoted one on a Bluetooth line (up to a comma or bracket).
        private val LINKED = Regex("(?i)(\\b(?:connected to|disconnected from|connecting to|paired with|pairing with|bonded with|unpaired|forgot)\\s+)(\"[^\"]*\"|$BARE)")
        private val QUOTED = Regex("\"[^\"<]+\"")
        private val NEXT_KEY = Regex("\\s+\\w+\\s*[:=]")

        /** Not a name: blank, already a marker, an address or a number. */
        private fun plausible(n: String) = n.isNotBlank() && '<' !in n && !LogScrub.isMac(n) && !n.all { it.isDigit() || it == '.' || it == ':' }
    }
}

/**
 * 1.1.14: "Send logs to Mike" ([com.hayeco.flipspaces.SendLogs] in the app). One zip: Pocket XR's own rotating log
 * files ([RotatingLogFile]), the head tracking diagnostics, and an about.txt with the app version and versionCode, the
 * phone model, the Android version and the glasses, every line through [LogScrub]. The app puts it on an email to
 * [EMAIL] for the user to read, add to and send. Pure Kotlin, so it is tested on the JVM.
 */
object LogBundle {
    const val EMAIL = CrashReport.EMAIL
    const val BUTTON = "Send logs to Mike"

    /** The one line under the button: what goes, and that the user sends it. */
    const val WHAT_IS_SENT =
        "Emails Mike one zip of Pocket XR's logs and head tracking diagnostics, with your app version, phone, Android version " +
            "and glasses. Emails, account, Wi-Fi and Bluetooth device names and IP addresses are taken out. Nothing goes until " +
            "you tap send."

    /** The zip's size at most, so it fits an email (Gmail takes 25 MB); older log files are left out past it. */
    const val MAX_ZIP_BYTES = 18L * 1024 * 1024

    data class Info(
        val appVersion: String,
        val versionCode: Long,
        val phone: String,
        val android: String,
        val glasses: String,
        val timeUtc: String,
    )

    data class Written(val included: List<String>, val leftOut: List<String>, val bytes: Long)

    fun subject(info: Info): String = "Pocket XR logs ${info.appVersion} ${info.phone}"

    /** The email's text: room for the user's own words first, then the facts. */
    fun body(info: Info): String = buildString {
        appendLine("Hi Mike,")
        appendLine()
        appendLine("What happened (and roughly when):")
        appendLine()
        appendLine()
        appendLine("----")
        append(facts(info))
        appendLine("The logs are in the attached zip.")
    }

    private fun facts(info: Info): String = buildString {
        appendLine("Pocket XR ${info.appVersion} (versionCode ${info.versionCode})")
        appendLine("Phone: ${info.phone}")
        appendLine("Android: ${info.android}")
        appendLine("Glasses: ${info.glasses}")
        appendLine("Made: ${info.timeUtc} UTC")
    }

    /** The log files in [dir], newest first, as [RotatingLogFile] names them; only those that exist. */
    fun logFiles(dir: File?): List<File> {
        if (dir == null) return emptyList()
        val names = RotatingLogFile(dir)
        return (0 until RotatingLogFile.FILES).map { names.file(it) }.filter { it.isFile }
    }

    /**
     * Writes the zip to [out] (closed at the end): head-tracking.txt, logs/ newest first while the zip is under
     * [maxBytes], and about.txt, which lists what is in it and what was left out.
     */
    fun write(
        out: OutputStream,
        info: Info,
        diagnostics: String,
        logs: List<File>,
        secrets: Collection<String> = emptyList(),
        maxBytes: Long = MAX_ZIP_BYTES,
        btNames: Collection<String> = emptyList(),
        /**
         * 1.2.8 (Discussion #24): more text files by name, scrubbed like the rest: the Glasses report (every USB device, its
         * interfaces and the display modes) and the newest motion recording.
         */
        extras: List<Pair<String, String>> = emptyList(),
    ): Written {
        // One for the whole zip: a device is the same <bt-device-N> in every file.
        val bt = BtNames(btNames)
        val counted = Counting(out)
        val included = mutableListOf<String>()
        val leftOut = mutableListOf<String>()
        ZipOutputStream(counted).use { zip ->
            val w = zip.writer(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("head-tracking.txt"))
            w.write(LogScrub.text(diagnostics, secrets, bt))
            w.flush()
            zip.closeEntry()
            for ((name, text) in extras) {
                zip.putNextEntry(ZipEntry(name))
                w.write(LogScrub.text(text, secrets, bt))
                w.flush()
                zip.closeEntry()
            }
            for (f in logs) {
                if (counted.count >= maxBytes) {
                    leftOut += f.name
                    continue
                }
                val ok = runCatching {
                    zip.putNextEntry(ZipEntry("logs/" + f.name))
                    f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (l in lines) {
                            w.write(LogScrub.line(l, secrets, bt))
                            w.write("\n")
                        }
                    }
                    w.flush()
                    zip.closeEntry()
                }.isSuccess
                if (ok) included += f.name else leftOut += f.name
            }
            zip.putNextEntry(ZipEntry("about.txt"))
            // The glasses line can carry a device's name too, so about.txt goes through the same scrub.
            w.write(LogScrub.text(about(info, included, leftOut, extras.map { it.first }), secrets, bt))
            w.flush()
            zip.closeEntry()
        }
        return Written(included, leftOut, counted.count)
    }

    fun about(info: Info, included: List<String>, leftOut: List<String>, extras: List<String> = emptyList()): String = buildString {
        appendLine("Pocket XR logs, sent with $BUTTON")
        append(facts(info))
        if (extras.isNotEmpty()) appendLine("Also: " + extras.joinToString(", "))
        appendLine("Log files: " + included.ifEmpty { listOf("none on the phone") }.joinToString(", "))
        if (leftOut.isNotEmpty()) appendLine("Left out to keep the email small (the oldest): " + leftOut.joinToString(", "))
        appendLine("Taken out of every line: email addresses, account names, Wi-Fi names, Bluetooth device names (as <bt-device-1> and on, the same in every file), MAC addresses, and IP addresses other than the glasses' own link.")
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
