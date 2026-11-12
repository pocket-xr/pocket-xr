package com.hayeco.flipspaces.core

/**
 * 0.5.97 (free updates for everyone): is a newer Pocket XR on GitHub Releases?
 *
 * The app asks GitHub's public API for the latest release ([RELEASES_URL]) at most every 12 hours ([due]). It sends no data
 * of its own: the request carries only what any web request does. The answer's tag (`v1.0.0`) is compared with the
 * installed version ([newer]), and a newer release is offered with its APK, or else its release page ([parse]). Pure
 * Kotlin, so it is tested on the JVM; the app does the networking.
 */
object UpdateCheck {
    const val OWNER = "pocket-xr"
    const val REPO = "pocket-xr"
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** At most one check every 12 hours (1.1.15: was a day, and only when the settings screen opened). */
    const val EVERY_MS = 12L * 60 * 60 * 1000

    /**
     * 1.1.15: whether this copy checks GitHub at all: not the Google Play build ([playBuild]), not a copy Play or F-Droid
     * installed ([selfUpdates]), and only while the user's "Check for updates" switch is on.
     */
    fun runs(playBuild: Boolean, installer: String?, switchOn: Boolean): Boolean = !playBuild && selfUpdates(installer) && switchOn

    /** The installer Google Play records for a copy it installed. */
    const val PLAY_INSTALLER = "com.android.vending"

    /** Whether Google Play installed this copy ([installer]: the package manager's installing package). */
    fun fromPlay(installer: String?): Boolean = installer == PLAY_INSTALLER

    /**
     * 1.1.11 (Google Play): whether this copy may offer GitHub's updates. Not a copy from Google Play (Play's Device and
     * Network Abuse policy: an app from Play is updated only through Play) or from F-Droid (F-Droid brings its own).
     */
    fun selfUpdates(installer: String?): Boolean = !fromPlay(installer) && installer?.startsWith("org.fdroid.") != true

    /** What the latest release offers. */
    data class Release(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String)

    /**
     * 1.1.15: after a failed check (offline, GitHub not answering) the next one waits this long, so a phone without a
     * network makes one failed request, not one per app start or screen.
     */
    const val RETRY_MS = 30L * 60 * 1000

    /**
     * Whether a check is due: never checked, 12 hours since the last answer, or the clock went back; and not within
     * [RETRY_MS] of a failed one ([failedAtMs], 0 when the last one did not fail).
     */
    fun due(lastCheckMs: Long, nowMs: Long, failedAtMs: Long = 0L): Boolean {
        if (failedAtMs > 0L && nowMs >= failedAtMs && nowMs - failedAtMs < RETRY_MS) return false
        return lastCheckMs <= 0L || nowMs - lastCheckMs >= EVERY_MS || nowMs < lastCheckMs
    }

    /**
     * A version's numbers: "v1.2.3", "1.2.3-launcher" and "0.5.96-launcher" give [1, 2, 3] and [0, 5, 96]. Anything
     * after the first character that is not a digit or a dot is left out; null when it starts with no number.
     */
    fun numbers(version: String): List<Int>? {
        val v = version.trim().removePrefix("v").removePrefix("V")
        val head = v.takeWhile { it.isDigit() || it == '.' }.trim('.')
        if (head.isEmpty()) return null
        return head.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Whether [latest] is a higher version than [installed]; false when either cannot be read. */
    fun newer(latest: String, installed: String): Boolean {
        val a = numbers(latest) ?: return false
        val b = numbers(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release in GitHub's JSON answer: `tag_name`, `html_url`, `body`, and the first asset whose name ends in
     * ".apk" (its `browser_download_url`). A draft or pre-release is not offered. Null when the answer has no tag.
     */
    fun parse(json: String): Release? {
        val tag = string(json, "tag_name") ?: return null
        if (bool(json, "draft") == true || bool(json, "prerelease") == true) return null
        val page = string(json, "html_url") ?: RELEASES_PAGE
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)?.groupValues?.get(1)?.let(::unescape)
        return Release(tag.removePrefix("v"), page, apk, string(json, "body").orEmpty())
    }

    /** The newer release in [json] for [installed], or null when there is none (or the answer cannot be read). */
    fun offer(json: String, installed: String): Release? = parse(json)?.takeIf { newer(it.version, installed) }

    /**
     * 1.1.15: the release the banner shows: [offer], unless the user tapped Later on that version or a newer one
     * ([dismissed]). Later hides it until the next version.
     */
    fun banner(offer: Release?, dismissed: String?): Release? =
        offer?.takeIf { dismissed == null || newer(it.version, dismissed) }

    /** 1.1.15: whether [version] gets its notification: one per new version, never again for one already posted ([posted]). */
    fun notifies(version: String, posted: String?): Boolean = posted == null || newer(version, posted)

    /**
     * 1.1.15: whether to ask for POST_NOTIFICATIONS now that an update is on screen: Android 13 (API 33) or later, not
     * granted, and never asked for updates before. Declined, the banner is all there is.
     */
    fun asksForNotifications(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdkInt >= 33 && !granted && !askedBefore

    /** 1.1.15: the banner's and the Control Center's line, and the notification's title. */
    fun bannerLine(version: String): String = UiText.format("core_update_banner", "Update available: %1\$s", version)
    fun notificationTitle(version: String): String = UiText.format("core_update_notification_title", "Pocket XR %1\$s is ready", version)
    val NOTIFICATION_TEXT: String get() = UiText.get("core_update_notification_text", "Tap to see the update.")

    private fun string(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.let(::unescape)

    private fun bool(json: String, key: String): Boolean? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBoolean()

    private fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\r", "").replace("\\t", "\t")
        .replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
}
