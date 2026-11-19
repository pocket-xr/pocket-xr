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
class PcScreenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pc_screen", Context.MODE_PRIVATE)

    val uniqueId: String
        get() = prefs.getString(KEY_ID, null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(KEY_ID, it).apply() }

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

    fun savePairing(address: String, httpsPort: Int, cert: X509Certificate) {
        prefs.edit()
            .putString(KEY_ADDRESS, address.trim())
            .putInt(KEY_HTTPS, httpsPort)
            .putString(KEY_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_HTTPS).remove(KEY_CERT).apply()
    }

    companion object {
        val CODECS = listOf("auto", "h264", "av1")
        private const val KEY_CODEC = "codec"
        private const val KEY_ID = "unique_id"
        private const val KEY_ADDRESS = "address"
        private const val KEY_HTTPS = "https_port"
        private const val KEY_CERT = "server_cert"
    }
}
