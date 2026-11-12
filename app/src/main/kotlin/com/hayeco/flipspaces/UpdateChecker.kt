package com.hayeco.flipspaces

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.hayeco.flipspaces.core.UpdateCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.5.97 (free updates for everyone): the in-app update check. It asks GitHub Releases for the latest version
 * ([UpdateCheck]); a newer one shows with a tap to download. The check can be turned off in Pocket XR Settings. It sends
 * nothing of the app's own: a plain request to GitHub's public API.
 *
 * 1.1.15 (Mike: an update was only found by opening Menu, Pocket XR Settings and tapping Check now): the check ran only
 * when the settings screen came to the front, which the app's icon skips for the trackpad (0.5.37), and the card was only
 * on that screen. Now it runs at app start ([start]) and every 12 hours in the background ([UpdateJobService]); a newer
 * version shows as a banner at the top of the trackpad and of Settings ([banner]), as a line on the glasses' Control
 * Center, and as one notification per version. Later hides the banner until the next version. None of it in the Google
 * Play build, or on a copy Play or F-Droid installed ([UpdateCheck.runs]).
 */
object UpdateChecker {
    private const val TAG = "FlipSpaces/Update"
    private const val PREFS = "updates"
    private const val KEY_ON = "check_on"
    private const val KEY_LAST = "last_check_ms"
    private const val KEY_OFFER = "offer_json"
    private const val KEY_RESULT = "last_result"
    private const val KEY_LATER = "later_version"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_ASKED = "notifications_asked"
    private const val KEY_FAILED_AT = "failed_at_ms"
    private const val TIMEOUT_MS = 10_000

    /**
     * 1.1.15: the background check's job ([UpdateJobService]). A library brings WorkManager, which numbers its own jobs
     * from 0; Pocket XR gives it none, so this id is free.
     */
    const val JOB_ID = 11_150

    /** 1.1.15: the update notification's channel and id. */
    const val CHANNEL = "updates"
    const val NOTIFICATION_ID = 11_151

    /** 1.1.15: the notification's tap opens the trackpad with this extra, on the update banner. */
    const val EXTRA_UPDATE = "com.hayeco.flipspaces.extra.UPDATE"

    private val _available = MutableStateFlow<UpdateCheck.Release?>(null)

    /** The newer release, or null (none, not checked yet, or the check is off). */
    val available: StateFlow<UpdateCheck.Release?> = _available.asStateFlow()

    private val _banner = MutableStateFlow<UpdateCheck.Release?>(null)

    /** 1.1.15: the release the banner shows: [available] unless Later was tapped on it ([UpdateCheck.banner]). */
    val banner: StateFlow<UpdateCheck.Release?> = _banner.asStateFlow()

    @Volatile private var running = false

    /**
     * 0.5.101 (Mike: "i also want to test the Check for updates to make sure its working as it should"): what the last check
     * found, for the line under the switch, with when it ran. Null before any check.
     */
    data class Status(val result: Result, val atMs: Long)

    enum class Result { CHECKING, UP_TO_DATE, AVAILABLE, NO_RELEASES, FAILED }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** Debug builds only (DebugReceiver): another repository's latest release, to see the update card for real. */
    @Volatile var testUrl: String? = null

    /** The version installed, which a release is compared with. */
    val installed: String get() = BuildConfig.VERSION_NAME

    /**
     * Off when the user turned it off, and always on the Play build or a copy F-Droid or Google Play installed: each brings
     * its own updates (1.1.11: [UpdateCheck.selfUpdates]).
     */
    fun enabled(context: Context): Boolean = offered(context) && prefs(context).getBoolean(KEY_ON, true)

    /** Whether this copy offers GitHub's updates at all; Settings hides its switch when not. 1.1.15: never the Play build. */
    fun offered(context: Context): Boolean = UpdateCheck.runs(BuildConfig.PLAY_BUILD, installer(context, context.packageName), switchOn = true)

    /** The package that installed [pkg] (Google Play, F-Droid, a browser's installer), or null (adb, unknown). */
    fun installer(context: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(pkg)
        }
    }.getOrNull()

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        schedule(context)
        if (!on) {
            _available.value = null
            refreshBanner(context)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } else {
            checkIfDue(context, force = true)
        }
    }

    /** 1.1.15: the app started: the last answer's offer shown again, the background check scheduled, and a check if due. */
    fun start(context: Context) {
        val app = context.applicationContext
        schedule(app)
        checkIfDue(app)
    }

    /**
     * 1.1.15: the background check every 12 hours while it is on, any network; kept over a restart. Cancelled when the
     * check is off, and never scheduled in the Play build. Left as it is when already scheduled, so each app start does not
     * push it back.
     */
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(context)) {
            if (jobs.getPendingJob(JOB_ID) != null) Log.i(TAG, "background update check cancelled (the check is off)")
            jobs.cancel(JOB_ID)
            return
        }
        if (jobs.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setPeriodic(UpdateCheck.EVERY_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        val ok = runCatching { jobs.schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        Log.i(TAG, "background update check every 12 hours: ${if (ok) "scheduled" else "not scheduled"}")
    }

    /**
     * A screen came to the front, the app started or the background job ran: show what the last check found, and check
     * again if 12 hours have passed. [onDone] runs when a check it started ends; returns whether one started.
     */
    fun checkIfDue(context: Context, force: Boolean = false, onDone: (() -> Unit)? = null): Boolean {
        val app = context.applicationContext
        if (!enabled(app)) return false
        // What the last answer offered, until a new one comes (no network needed to show it again).
        if (_available.value == null) {
            prefs(app).getString(KEY_OFFER, null)?.let { _available.value = UpdateCheck.offer(it, installed) }
            refreshBanner(app)
        }
        if (_status.value == null) {
            val last = prefs(app).getLong(KEY_LAST, 0L)
            val result = prefs(app).getString(KEY_RESULT, null)?.let { r -> Result.entries.firstOrNull { it.name == r } }
            if (last > 0L && result != null) _status.value = Status(result, last)
        }
        val now = System.currentTimeMillis()
        // 1.1.15: offline, a failed check waits UpdateCheck.RETRY_MS before the next request (Check now still asks).
        if (running || (!force && !UpdateCheck.due(prefs(app).getLong(KEY_LAST, 0L), now, prefs(app).getLong(KEY_FAILED_AT, 0L)))) return false
        running = true
        _status.value = Status(Result.CHECKING, now)
        Thread({
            var result = Result.FAILED
            try {
                val answer = fetcher()
                result = answered(app, answer.code, answer.json, now)
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                prefs(app).edit().apply { if (result == Result.FAILED) putLong(KEY_FAILED_AT, now) else remove(KEY_FAILED_AT) }.apply()
                prefs(app).edit().putString(KEY_RESULT, result.name).apply()
                _status.value = Status(result, System.currentTimeMillis())
                running = false
                onDone?.invoke()
            }
        }, "update-check").apply { isDaemon = true }.start()
        return true
    }

    /** GitHub's answer ([code], and [json] when it answered 200) taken in: the offer, the banner and the notification. */
    internal fun answered(app: Context, code: Int, json: String?, now: Long): Result {
        if (!enabled(app)) return Result.FAILED
        val result = when {
            code == 404 -> {
                // No release published yet: nothing to offer.
                prefs(app).edit().remove(KEY_OFFER).apply()
                _available.value = null
                Log.i(TAG, "update check: no release published yet ($installed installed)")
                Result.NO_RELEASES
            }
            json != null -> {
                prefs(app).edit().putString(KEY_OFFER, json).apply()
                _available.value = UpdateCheck.offer(json, installed)
                Log.i(TAG, _available.value?.let { "Pocket XR ${it.version} is available (installed $installed)" } ?: "up to date ($installed)")
                if (_available.value != null) Result.AVAILABLE else Result.UP_TO_DATE
            }
            else -> {
                Log.i(TAG, "update check: GitHub answered $code")
                Result.FAILED
            }
        }
        // A failed check is tried again on the next start or job, not 12 hours later.
        if (result != Result.FAILED) prefs(app).edit().putLong(KEY_LAST, now).apply()
        refreshBanner(app)
        _banner.value?.let { notifyOnce(app, it) }
        return result
    }

    /** 1.1.15: Later on the banner: hidden, with its notification, until a newer version. */
    fun later(context: Context) {
        val v = _banner.value?.version ?: return
        prefs(context).edit().putString(KEY_LATER, v).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        refreshBanner(context)
        Log.i(TAG, "update banner put off until the version after $v")
    }

    /**
     * 1.1.15: whether to ask for POST_NOTIFICATIONS now, with an update on screen: once, on Android 13 and later
     * ([UpdateCheck.asksForNotifications]). Answering true records the ask, so the caller asks now.
     */
    fun takeNotificationAsk(context: Context): Boolean {
        if (_banner.value == null) return false
        val ask = UpdateCheck.asksForNotifications(Build.VERSION.SDK_INT, canNotify(context), prefs(context).getBoolean(KEY_ASKED, false))
        if (ask) prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
        return ask
    }

    /** Opens the release's APK (the browser downloads it), or its page when it has none. */
    fun open(context: Context, release: UpdateCheck.Release) {
        val url = release.apkUrl ?: release.pageUrl
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "no browser for $url: ${it.message}") }
    }

    private fun refreshBanner(context: Context) {
        _banner.value = if (enabled(context)) UpdateCheck.banner(_available.value, prefs(context).getString(KEY_LATER, null)) else null
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * 1.1.15: "Pocket XR <version> is ready", once per version; its tap opens the trackpad on the banner. Without the
     * notification permission nothing is posted (nor recorded), and the banner is all there is.
     */
    private fun notifyOnce(app: Context, release: UpdateCheck.Release) {
        val prefs = prefs(app)
        if (!UpdateCheck.notifies(release.version, prefs.getString(KEY_NOTIFIED, null))) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (!canNotify(app) || !manager.areNotificationsEnabled()) {
            Log.i(TAG, "Pocket XR ${release.version}: no notification (not allowed); the banner shows it")
            return
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.home_channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(app, TrackpadActivity::class.java)
            .putExtra(EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val tap = PendingIntent.getActivity(app, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UpdateCheck.notificationTitle(release.version))
            .setContentText(UpdateCheck.NOTIFICATION_TEXT)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, n) }
            .onSuccess {
                prefs.edit().putString(KEY_NOTIFIED, release.version).apply()
                Log.i(TAG, "notification posted: Pocket XR ${release.version} is ready")
            }
            .onFailure { Log.i(TAG, "update notification not posted: ${it.message}") }
    }

    /** For the tests: what this process remembers forgotten. */
    internal fun resetForTest() {
        _available.value = null
        _banner.value = null
        _status.value = null
        running = false
    }

    internal class Answer(val code: Int, val json: String?)

    /** 1.1.15: the request to GitHub ([fetch]); the tests count and fail it here instead of going to the network. */
    internal val realFetcher: () -> Answer = ::fetch
    @Volatile internal var fetcher: () -> Answer = realFetcher

    /** GitHub's answer: its code, and the release's JSON when it answered 200 (404: no release published yet). */
    private fun fetch(): Answer {
        val c = URL(testUrl ?: UpdateCheck.RELEASES_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "PocketXR-update-check")
            val code = c.responseCode
            return Answer(code, if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
