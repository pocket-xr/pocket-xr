package com.hayeco.flipspaces.pcscreen

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * PC screens: the PC this phone streams from, saved after pairing.
 *
 * What is saved:
 * - the address;
 * - the host's HTTPS port;
 * - the host's certificate, which is checked on every later connection, as Moonlight does.
 *
 * The client id is random, made once, like Moonlight's. The client's own certificate and key are kept by
 * AndroidCryptoProvider in the app's files.
 */
class PcScreenStore(context: Context, val slot: Int = 0) {
    /** Each PC screen ([PcScreenSlots]) keeps its own PC: the first in "pc_screen", the others in "pc_screen_2" and on. */
    private val prefs = context.applicationContext.getSharedPreferences(prefsName(slot), Context.MODE_PRIVATE)

    /** The client id is the phone's, one for every PC screen, kept with the first's. */
    private val idPrefs = if (slot == 0) prefs else context.applicationContext.getSharedPreferences(prefsName(0), Context.MODE_PRIVATE)

    val uniqueId: String
        get() = idPrefs.getString(KEY_ID, null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { idPrefs.edit().putString(KEY_ID, it).commit() }

    var address: String
        get() = prefs.getString(KEY_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADDRESS, value.trim()).apply()

    val httpsPort: Int get() = prefs.getInt(KEY_HTTPS, 0)

    /** The video codec asked of the host: [CODECS], "auto" being HEVC where the phone decodes it, else H.264. */
    var codec: String
        get() = prefs.getString(KEY_CODEC, "auto")?.takeIf { it in CODECS } ?: "auto"
        set(value) = prefs.edit().putString(KEY_CODEC, value).apply()

    /** Frame rate asked of the host: [PcScreenQuality.FPS], "auto" being 120 where the phone decodes it. */
    var fps: String
        get() = prefs.getString(KEY_FPS, "auto")?.takeIf { it in PcScreenQuality.FPS } ?: "auto"
        set(value) = prefs.edit().putString(KEY_FPS, value).apply()

    /**
     * 1.6.14: the size the computer makes this PC screen at ([PcScreenShape.SIZES]): the window's own shape, or a fixed
     * size such as 1920 x 1200. Only where the computer makes a screen of its own (Pocket XR's PC side or Apollo);
     * otherwise the stream stays 1920 x 1080.
     */
    var screenSize: String
        get() = prefs.getString(KEY_SCREEN_SIZE, "window")?.takeIf { it in PcScreenShape.SIZES } ?: "window"
        set(value) = prefs.edit().putString(KEY_SCREEN_SIZE, value).apply()

    /** Where the PC's sound plays: [SOUNDS] (the glasses, the PC's own speakers, or not at all for this screen). */
    var sound: String
        get() = prefs.getString(KEY_SOUND, "glasses")?.takeIf { it in SOUNDS } ?: "glasses"
        set(value) = prefs.edit().putString(KEY_SOUND, value).apply()

    /** The mouse: [MOUSES] ("exact" sends positions, "moves" relative moves, "auto" relative only where needed). */
    var mouse: String
        get() = prefs.getString(KEY_MOUSE, "auto")?.takeIf { it in MOUSES } ?: "auto"
        set(value) = prefs.edit().putString(KEY_MOUSE, value).apply()

    /** Precise pointer: the PC's pointer moves at [PRECISE_FACTOR] of the hand's speed (relative moves). */
    var precise: Boolean
        get() = prefs.getBoolean(KEY_PRECISE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRECISE, value).apply()

    /** The clipboard shared with the PC while streaming (Apollo). */
    var clipboard: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    /** The stream's numbers on the screen (frame rate, network, decode). */
    var stats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS, value).apply()

    /** Picture quality: [PcScreenQuality.QUALITY], how many bits the stream gets. */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, "auto")?.takeIf { it in PcScreenQuality.QUALITY } ?: "auto"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    val serverCert: X509Certificate?
        get() = prefs.getString(KEY_CERT, null)?.let { encoded ->
            runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(Base64.decode(encoded, Base64.NO_WRAP))) as X509Certificate
            }.getOrNull()
        }

    val paired: Boolean get() = address.isNotEmpty() && httpsPort > 0 && serverCert != null

    /** The PC's Windows name, as found at home: its Tailscale name away from home ([PcScreenRemote]). */
    var pcName: String?
        get() = prefs.getString(KEY_PC_NAME, null)
        set(value) = prefs.edit().putString(KEY_PC_NAME, value).apply()

    /** The host's network card, for Wake-on-LAN ([PcScreenWake]), from its serverinfo; null if it gave none. */
    val mac: String? get() = prefs.getString(KEY_MAC, null)

    fun savePairing(address: String, httpsPort: Int, cert: X509Certificate, mac: String? = null) {
        prefs.edit()
            .putString(KEY_ADDRESS, address.trim())
            .putInt(KEY_HTTPS, httpsPort)
            .apply { if (PcScreenWake.macBytes(mac) != null) putString(KEY_MAC, mac) }
            .putString(KEY_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .commit()
    }

    /** The pairing only (port and certificate), the PC kept: for pairing again with the same PC. */
    fun unpair() {
        prefs.edit().remove(KEY_HTTPS).remove(KEY_CERT).commit()
    }

    /** This PC screen's PC forgotten: address, port, certificate and MAC. The phone's own id stays. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_HTTPS).remove(KEY_CERT).remove(KEY_MAC).remove(KEY_PC_NAME).commit()
    }

    companion object {
        fun prefsName(slot: Int) = if (slot == 0) "pc_screen" else "pc_screen_${slot + 1}"
        val CODECS = listOf("auto", "h264", "av1")
        val SOUNDS = listOf("glasses", "pc", "off")
        val MOUSES = listOf("auto", "exact", "moves")
        const val PRECISE_FACTOR = 0.4f
        private const val KEY_SOUND = "sound"
        private const val KEY_MOUSE = "mouse"
        private const val KEY_PRECISE = "precise"
        private const val KEY_CLIPBOARD = "clipboard"
        private const val KEY_STATS = "stats"
        private const val KEY_CODEC = "codec"
        private const val KEY_ID = "unique_id"
        private const val KEY_ADDRESS = "address"
        private const val KEY_HTTPS = "https_port"
        private const val KEY_CERT = "server_cert"
        private const val KEY_MAC = "mac"
        private const val KEY_FPS = "fps"
        private const val KEY_SCREEN_SIZE = "screen_size"
        private const val KEY_PC_NAME = "pc_name"
        private const val KEY_QUALITY = "quality"
    }
}
