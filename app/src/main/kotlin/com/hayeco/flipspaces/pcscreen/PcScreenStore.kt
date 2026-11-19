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

    val serverCert: X509Certificate?
        get() = prefs.getString(KEY_CERT, null)?.let { encoded ->
            runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(Base64.decode(encoded, Base64.NO_WRAP))) as X509Certificate
            }.getOrNull()
        }

    val paired: Boolean get() = address.isNotEmpty() && httpsPort > 0 && serverCert != null

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

    /** This PC screen's PC forgotten: address, port, certificate and MAC. The phone's own id stays. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_HTTPS).remove(KEY_CERT).remove(KEY_MAC).commit()
    }

    companion object {
        fun prefsName(slot: Int) = if (slot == 0) "pc_screen" else "pc_screen_${slot + 1}"
        val CODECS = listOf("auto", "h264", "av1")
        private const val KEY_CODEC = "codec"
        private const val KEY_ID = "unique_id"
        private const val KEY_ADDRESS = "address"
        private const val KEY_HTTPS = "https_port"
        private const val KEY_CERT = "server_cert"
        private const val KEY_MAC = "mac"
    }
}
