package com.hayeco.flipspaces

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.LogBundle
import com.hayeco.flipspaces.core.RotatingLogFile
import com.hayeco.flipspaces.core.spatial.BtDevice
import com.hayeco.flipspaces.core.spatial.BtState
import com.hayeco.flipspaces.core.spatial.WifiNet
import com.hayeco.flipspaces.core.spatial.WifiState
import com.hayeco.flipspaces.phone.PhoneConnections
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 1.1.14: Send logs to Mike on the app side ([SendLogs]): the email it opens, the zip the email app can read through the
 * FileProvider, and nothing personal from the logs in it. A plain Application, so FlipSpacesApp's start up (the log
 * reader, Shizuku) does not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SendLogs1114Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        PhoneConnections.wifi = WifiState()
        PhoneConnections.bt = BtState()
    }

    @Test
    fun theEmailGoesToMikeWithTheZipAttachedAndReadPermission() {
        val (file, info) = SendLogs.build(app)
        val uri = Uri.parse("content://${app.packageName}.reports/reports/${file.name}")
        assertEmail(SendLogs.email(uri, file.name, info), uri, info)
    }

    @Test
    fun theZipIsWhereTheReportsFileProviderServesFrom() {
        logDir().let { RotatingLogFile(it).file(0).writeText("I FlipSpaces: a line\n") }
        val (file, _) = SendLogs.build(app)
        assertEquals(File(app.getExternalFilesDir(null), "reports").canonicalFile, file.parentFile!!.canonicalFile)
        // report_paths.xml serves exactly that folder.
        val roots = mutableListOf<String>()
        app.resources.getXml(R.xml.report_paths).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name.endsWith("-path")) {
                    roots += xml.name + ":" + xml.getAttributeValue(null, "name") + ":" + xml.getAttributeValue(null, "path")
                }
            }
        }
        assertTrue(roots.toString(), "external-files-path:reports:reports/" in roots)
        // The provider is in the manifest under that authority and may grant read access.
        val provider = app.packageManager.resolveContentProvider(app.packageName + ".reports", 0)
        assertNotNull("no reports FileProvider in the manifest", provider)
        assertTrue(provider!!.grantUriPermissions)
        assertFalse(provider.exported)

        val entries = unzip(file.readBytes())
        // 1.2.8 (Discussion #24): the Glasses report goes along, with every USB device and the display modes.
        assertEquals(listOf("head-tracking.txt", "glasses-report.txt", "logs/flipspaces.log", "about.txt"), entries.keys.toList())
        assertTrue(entries["glasses-report.txt"]!!.contains("USB devices (every one Android lists):"))
        assertTrue(entries["logs/flipspaces.log"]!!.contains("a line"))
        assertTrue(entries["about.txt"]!!.contains("Phone: "))
    }

    /**
     * The whole path as on the phone: [SendLogs.open] starts the email and the zip is read back through the FileProvider.
     * Not on a Windows host: androidx FileProvider matches its roots with "/", so it finds no root for a Windows path.
     */
    @Test
    fun theEmailAppCanOpenTheZipThroughTheProvider() {
        assumeTrue("FileProvider needs \"/\" paths", File.separatorChar == '/')
        val (file, info) = SendLogs.build(app)
        SendLogs.open(app, file, info)
        val sent = shadowOf(app).nextStartedActivity
        assertNotNull("no email was opened: ${ShadowToast.getTextOfLatestToast()}", sent)
        val uri = sent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEmail(sent, uri, info)
        // Email apps first.
        assertEquals(Intent.ACTION_SENDTO, sent.selector?.action)
        assertEquals("mailto:", sent.selector?.data.toString())
        val entries = unzip(app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals("about.txt", entries.keys.last())
    }

    private fun assertEmail(sent: Intent, uri: Uri, info: LogBundle.Info) {
        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals("application/zip", sent.type)
        assertArrayEquals(arrayOf("connect@mikehaye.com"), sent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(LogBundle.subject(info), sent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(sent.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("Pocket XR logs " + info.appVersion))
        assertTrue(sent.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith("Hi Mike,"))
        assertEquals(uri, sent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", uri.scheme)
        assertEquals(app.packageName + ".reports", uri.authority)
        assertTrue("no read permission granted", sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The grant goes with the clip, so the email app can open the attachment.
        assertEquals(uri, sent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun nothingPersonalIsInTheZip() {
        PhoneConnections.wifi = WifiState(
            available = true, enabled = true, connected = "Davis Family 5G",
            saved = listOf(WifiNet(3, "Grandma Lake House"), WifiNet(4, "Davis Family 5G")),
        )
        PhoneConnections.bt = BtState(true, true, listOf(BtDevice("4C:87:5D:01:02:03", "Jordan's Buds", true)))
        val rot = RotatingLogFile(logDir())
        rot.file(0).writeText(
            """
            11-05 10:00:01.000  123  456 I Sync: Account {name=jordan.davis@gmail.com, type=com.google}
            11-05 10:00:02.000  123  456 I Accounts: accountName=jordan_davis signed in
            11-05 10:00:03.000  123  456 I FlipSpaces: control center: Wifi is connected to "Davis Family 5G"
            11-05 10:00:04.000  123  456 I FlipSpaces: joined Grandma Lake House
            11-05 10:00:05.000  123  456 I wifi: SSID: "Cafe Corner", BSSID: a4:2b:b0:12:34:56
            11-05 10:00:06.000  123  456 I FlipSpaces: paired 00:1A:7D:DA:71:13
            11-05 10:00:07.000  123  456 I net: wlan0 192.168.1.23/24 gw 10.0.0.1 dns 2001:4860:4860::8888
            11-05 10:00:08.000  123  456 I FlipSpaces/HeadTracker: glasses at 169.254.2.1:52998 via fe80::1c2b:3cff:fe4d:5e6f%ncm0
            11-05 10:00:09.000  123  456 I FlipSpaces: audio now playing on Jordan's Buds
            11-05 10:00:10.000  123  456 I BluetoothAdapter: device name=Davis Kitchen Speaker, bonded
            """.trimIndent() + "\n",
        )
        rot.file(1).writeText("11-04 09:00:00.000  1  2 I net: public 73.15.200.4, mail from someone@example.org\n")

        val (file, _) = SendLogs.build(app)
        val all = unzip(file.readBytes()).values.joinToString("\n")
        for (personal in listOf(
            "jordan.davis@gmail.com", "jordan.davis", "jordan_davis", "gmail.com", "Davis Family 5G", "Grandma Lake House",
            "Cafe Corner", "a4:2b:b0:12:34:56", "00:1A:7D:DA:71:13", "192.168.1.23", "10.0.0.1", "2001:4860:4860::8888",
            "73.15.200.4", "someone@example.org", "Jordan's Buds", "Davis Kitchen Speaker",
        )) {
            assertFalse("\"$personal\" is still in the zip", all.contains(personal, ignoreCase = true))
        }
        // The glasses' own link stays, and the lines themselves are still there.
        assertTrue(all.contains("169.254.2.1:52998"))
        assertTrue(all.contains("fe80::1c2b:3cff:fe4d:5e6f%ncm0"))
        assertTrue(all.contains("type=com.google"))
        assertTrue(all.contains("joined <wifi>"))
        assertTrue(Regex("playing on <bt-device-\\d+>").containsMatchIn(all))
        assertTrue(Regex("device name=<bt-device-\\d+>, bonded").containsMatchIn(all))
    }

    /** The folder [SendLogs] reads when the app's own log writer has not started (as here). */
    private fun logDir(): File = (app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")).apply { mkdirs() }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return m
    }
}
