package com.hayeco.flipspaces.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.hayeco.flipspaces.BuildConfig
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.UpdateChecker
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.UpdateCheck
import com.hayeco.flipspaces.phone.PhoneNotifications
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 1.2.1: the one-tap install of the Pocket XR add-on ([AddonSetup]). It asks GitHub's release list for the newest add-on
 * ([AddonSetup.latest]), downloads its APK into the cache with progress, and opens Android's install prompt on it (through
 * the FileProvider, so the APK is never world readable). Android's prompt does the install; [AddonLink] sees the add-on
 * when a Pocket XR screen comes back to the front.
 *
 * Installing needs "Install unknown apps" for Pocket XR (REQUEST_INSTALL_PACKAGES, declared only outside the Google
 * Play build). Without it, [allowInstalls] opens that setting, and the install goes on by itself when the user comes back
 * with it allowed ([resumed]).
 */
object AddonInstaller {
    private const val TAG = "FlipSpacesAddon"
    private const val TIMEOUT_MS = 15_000
    private const val FILE = "pocketxr-addon.apk"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var download: AddonSetup.Download = AddonSetup.Download.Idle
        private set

    /** Install was tapped while installs were not allowed: go on once they are. */
    @Volatile private var waitingForPermission = false

    /** Called on the main thread whenever [download] changes. */
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    /** Whether this copy installs the add-on itself: not the Google Play build, nor a copy Google Play installed. */
    fun selfInstall(context: Context): Boolean =
        !BuildConfig.PLAY_BUILD && !UpdateCheck.fromPlay(UpdateChecker.installer(context, context.packageName))

    /** Whether Android lets Pocket XR open the install prompt ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** The step as it is now. */
    fun step(context: Context): AddonSetup.Step {
        val installed = AddonLink.isInstalled(context)
        if (installed && download != AddonSetup.Download.Idle && download !is AddonSetup.Download.Running) set(AddonSetup.Download.Idle)
        return AddonSetup.step(installed, installed && PhoneNotifications.hasAccess(context), download, canInstall(context), selfInstall(context))
    }

    /** Runs a button's [action]. */
    fun run(context: Context, action: AddonSetup.Action) {
        Log.i(TAG, "add-on step: $action")
        when (action) {
            AddonSetup.Action.INSTALL -> install(context)
            AddonSetup.Action.ALLOW_INSTALLS -> allowInstalls(context)
            AddonSetup.Action.OPEN_SETUP -> openSetup(context)
            AddonSetup.Action.OPEN_RELEASES -> openReleases(context)
        }
    }

    /** A Pocket XR screen came back (or got the focus): the install goes on if installs were allowed meanwhile. */
    fun resumed(context: Context) {
        AddonLink.connect(context)
        if (waitingForPermission && canInstall(context)) {
            waitingForPermission = false
            Log.i(TAG, "installs allowed; going on with the add-on's install")
            install(context)
        }
    }

    /** Downloads the newest add-on and opens Android's install prompt. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (download is AddonSetup.Download.Running) return
        if (!canInstall(app)) {
            allowInstalls(app)
            return
        }
        set(AddonSetup.Download.Running(null))
        Thread({
            val failed = runCatching { fetchAndOpen(app) }.exceptionOrNull()
            if (failed != null) {
                val reason = (failed as? Failure)?.reason ?: AddonSetup.reason(0)
                Log.i(TAG, "the add-on download did not work: ${failed.javaClass.simpleName}: ${failed.message}")
                set(AddonSetup.Download.Failed(reason))
            }
        }, "addon-download").apply { isDaemon = true }.start()
    }

    private class Failure(val reason: String) : Exception(reason)

    private fun fetchAndOpen(app: Context) {
        val list = get(AddonSetup.RELEASES_API) { c -> c.inputStream.bufferedReader().use { it.readText() } }
        val release = AddonSetup.latest(list) ?: throw Failure(AddonSetup.reason(404))
        Log.i(TAG, "downloading the add-on ${release.version}: ${release.apkUrl}")
        val dir = File(app.cacheDir, "addon").apply { mkdirs() }
        val apk = File(dir, FILE)
        val part = File(dir, "$FILE.part")
        get(release.apkUrl) { c ->
            val total = c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
                        if (percent != null && percent != shown) {
                            shown = percent
                            set(AddonSetup.Download.Running(percent))
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw Failure(app.getString(R.string.sys_addon_download_stopped))
        }
        apk.delete()
        if (!part.renameTo(apk)) throw Failure(app.getString(R.string.sys_addon_file_not_saved))
        main.post { openPrompt(app, apk) }
    }

    /** A GET of [url] that answers 200, read by [read]; else a [Failure] with GitHub's code in plain words. */
    private fun <T> get(url: String, read: (HttpURLConnection) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
            c.setRequestProperty("User-Agent", "PocketXR-addon-install")
            val code = c.responseCode
            if (code != 200) throw Failure(AddonSetup.reason(code))
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    private fun openPrompt(app: Context, apk: File) {
        val opened = runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.reports", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }
        opened.onFailure { Log.i(TAG, "the install prompt did not open: ${it.javaClass.simpleName}: ${it.message}") }
        set(if (opened.isSuccess) AddonSetup.Download.Prompted else AddonSetup.Download.Failed(app.getString(R.string.sys_addon_prompt_not_opened)))
    }

    /** Opens Android's "Install unknown apps" setting for Pocket XR; the install goes on when the user comes back. */
    fun allowInstalls(context: Context) {
        waitingForPermission = true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Log.i(TAG, "the install unknown apps setting did not open: ${it.message}")
            waitingForPermission = false
            openReleases(context)
        }
    }

    /** The add-on's own setup for notification access; Android's notification access page for an add-on without it. */
    fun openSetup(context: Context) {
        if (AddonLink.openSetup(context, AddonLink.SETUP_NOTES)) return
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "the notification access page did not open: ${it.message}") }
    }

    /** The add-on releases page, or the add-on's Play page for a copy from Google Play. */
    fun openReleases(context: Context) {
        val url = if (selfInstall(context)) AddonSetup.RELEASES_PAGE else AddonLink.PLAY_URL
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "no browser for $url: ${it.message}") }
    }

    private fun set(d: AddonSetup.Download) {
        download = d
        main.post { for (l in listeners) l() }
    }

    /** For the tests. */
    internal fun resetForTest() {
        download = AddonSetup.Download.Idle
        waitingForPermission = false
    }
}
