package com.hayeco.flipspaces.core

/**
 * 1.2.1 (Mike: tell new users about the add-on during setup, with a one-tap install): the add-on step of the tutorial and
 * of the glasses setup check. It says what the add-on gives (the phone's notifications in the glasses, and the pointer)
 * and offers one button for the next thing to do: install it, allow Pocket XR to install apps, or switch on notification
 * access in the add-on's own setup. It can always be skipped and never blocks setup.
 *
 * The add-on comes from the add-on releases on GitHub (tags `addon-vX.Y`, asset `pocketxr-addon-X.Y.apk`, as the README
 * says): [latest] picks the newest one from GitHub's release list. Pure Kotlin, so it is tested on the JVM; the app
 * downloads the APK and opens Android's install prompt (AddonInstaller).
 */
object AddonSetup {
    /** GitHub's release list, newest first; the add-on's releases are among Pocket XR's. */
    const val RELEASES_API = "https://api.github.com/repos/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/releases?per_page=100"

    /** The add-on releases page, the README's link and the fallback when the install does not work. */
    const val RELEASES_PAGE = "https://github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/releases?q=addon&expanded=true"

    /** Only an APK from Pocket XR's own releases is downloaded. */
    const val DOWNLOAD_PREFIX = "https://github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/releases/download/"

    val TITLE: String get() = UiText.get("core_addon_title", "Phone notifications in your glasses")
    val WHAT: String get() = UiText.get("core_addon_what", "The free Pocket XR add-on shows your phone's notifications in the glasses, and the pointer outside the Space. It is optional.")

    /** The tutorial page's two lines over the add-on's button. */
    val ITEMS: List<String> get() = listOf(
        UiText.get("core_addon_item_notifications", "See your phone's notifications on the Space's Notifications page"),
        UiText.get("core_addon_item_pointer", "Use the pointer on the glasses outside the Space"),
    )
    val NOTE: String get() = UiText.get("core_addon_note", "Optional. Skip it if you like, and install it later from the add-on releases.")

    /** An add-on release: its version and its APK. */
    data class Release(val version: String, val apkUrl: String)

    /**
     * The newest add-on release in GitHub's release list [json]: the highest `pocketxr-addon-X.Y.apk` among the releases
     * that are not a draft or a pre-release, and only from Pocket XR's own releases ([DOWNLOAD_PREFIX]). Null when there
     * is none, or the answer cannot be read.
     */
    fun latest(json: String): Release? {
        var best: Release? = null
        // Each release object starts with its "url" and then "assets_url"; an asset has no "assets_url" of its own.
        for (chunk in json.split("\"assets_url\"").drop(1)) {
            if (bool(chunk, "draft") == true || bool(chunk, "prerelease") == true) continue
            for (m in APK.findAll(chunk)) {
                val url = m.groupValues[1].replace("\\/", "/")
                if (!url.startsWith(DOWNLOAD_PREFIX)) continue
                val version = m.groupValues[2]
                if (best == null || UpdateCheck.newer(version, best.version)) best = Release(version, url)
            }
        }
        return best
    }

    /** Where the download is. */
    sealed interface Download {
        /** Nothing asked yet (or the add-on was installed since). */
        data object Idle : Download

        /** Downloading: [percent] 0 to 100, or null while the size is not known yet. */
        data class Running(val percent: Int?) : Download

        /** It did not work: [reason] in plain words. */
        data class Failed(val reason: String) : Download

        /** Downloaded, and Android's install prompt was opened. */
        data object Prompted : Download
    }

    /** What a button does. */
    enum class Action {
        /** Download the newest add-on and open Android's install prompt. */
        INSTALL,

        /** Open Android's "Install unknown apps" setting for Pocket XR; the install goes on once it is allowed. */
        ALLOW_INSTALLS,

        /** Open the add-on's own setup (SetupActivity) for notification access. */
        OPEN_SETUP,

        /** Open the add-on releases page (or the add-on's Play page for a copy from Google Play). */
        OPEN_RELEASES,
    }

    data class Button(val label: String, val action: Action, val primary: Boolean = false)

    enum class State { NOT_INSTALLED, NEEDS_PERMISSION, DOWNLOADING, PROMPTED, FAILED, GET_FROM_STORE, ACCESS_OFF, ACCESS_ON }

    /** The step as shown: what is going on ([line]), the download's [progress] while it runs, and the buttons. */
    data class Step(val state: State, val line: String, val buttons: List<Button>, val progress: Int? = null) {
        /** The add-on is installed: the step is done, whatever access is. */
        val done: Boolean get() = state == State.ACCESS_OFF || state == State.ACCESS_ON
    }

    val INSTALL: String get() = UiText.get("core_addon_install", "Install add-on")
    val ALLOW: String get() = UiText.get("core_addon_allow", "Allow installs")
    val SETUP: String get() = UiText.get("core_addon_setup", "Open add-on setup")
    val TURN_ON: String get() = UiText.get("core_addon_turn_on", "Turn on notifications")
    val RELEASES: String get() = UiText.get("core_addon_releases", "Open releases page")
    val TRY_AGAIN: String get() = UiText.get("core_addon_try_again", "Try again")
    val GET: String get() = UiText.get("core_addon_get", "Get the add-on")

    /**
     * The step for: the add-on [installed], its notification access on ([accessOn]), the [download], whether Pocket XR
     * may install apps ([canInstall]), and whether this copy installs the add-on itself ([selfInstall]: not the Google
     * Play build, nor a copy Google Play installed, which get the add-on from Play).
     */
    fun step(installed: Boolean, accessOn: Boolean, download: Download, canInstall: Boolean, selfInstall: Boolean): Step = when {
        installed && accessOn -> Step(State.ACCESS_ON, UiText.get("core_addon_access_on", "Add-on installed. Notifications are on."), listOf(Button(SETUP, Action.OPEN_SETUP)))
        installed -> Step(
            State.ACCESS_OFF,
            UiText.get("core_addon_access_off", "Add-on installed. Turn on notification access to see them in the glasses."),
            listOf(Button(TURN_ON, Action.OPEN_SETUP, primary = true)),
        )
        !selfInstall -> Step(State.GET_FROM_STORE, UiText.get("core_addon_from_play", "Get the add-on from Google Play."), listOf(Button(GET, Action.OPEN_RELEASES, primary = true)))
        download is Download.Running -> Step(
            State.DOWNLOADING,
            download.percent?.let { UiText.format("core_addon_downloading_percent", "Downloading the add-on: %1\$d%%", it) }
                ?: UiText.get("core_addon_downloading", "Downloading the add-on"),
            emptyList(),
            progress = download.percent ?: 0,
        )
        download is Download.Failed -> Step(
            State.FAILED,
            UiText.format("core_addon_download_failed", "The download did not work: %1\$s. Try again, or get it from the releases page.", download.reason),
            listOf(Button(TRY_AGAIN, Action.INSTALL, primary = true), Button(RELEASES, Action.OPEN_RELEASES)),
        )
        !canInstall -> Step(
            State.NEEDS_PERMISSION,
            UiText.get("core_addon_allow_first", "First allow Pocket XR to install apps. Turn it on, then come back here."),
            listOf(Button(ALLOW, Action.ALLOW_INSTALLS, primary = true), Button(RELEASES, Action.OPEN_RELEASES)),
        )
        download is Download.Prompted -> Step(
            State.PROMPTED,
            UiText.format("core_addon_prompted", "Tap Install in Android's prompt. If it closed, tap %1\$s again.", INSTALL),
            listOf(Button(INSTALL, Action.INSTALL, primary = true), Button(RELEASES, Action.OPEN_RELEASES)),
        )
        else -> Step(State.NOT_INSTALLED, UiText.get("core_addon_not_installed", "Not installed yet."), listOf(Button(INSTALL, Action.INSTALL, primary = true)))
    }

    /** Whether the glasses check's result shows the step: until the add-on is installed with notification access on. */
    fun showAfterCheck(installed: Boolean, accessOn: Boolean): Boolean = !(installed && accessOn)

    /** A failed download's reason, in plain words, from GitHub's answer [code] (0: no answer at all). */
    fun reason(code: Int): String = when (code) {
        0 -> UiText.get("core_addon_reason_offline", "no connection to GitHub")
        403, 429 -> UiText.get("core_addon_reason_busy", "GitHub is busy, try again in a while")
        404 -> UiText.get("core_addon_reason_none", "no add-on release found")
        else -> UiText.format("core_addon_reason_code", "GitHub answered %1\$d", code)
    }

    private val APK = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*pocketxr-addon-([0-9][0-9.]*[0-9]|[0-9])\\.apk)\"")

    private fun bool(json: String, key: String): Boolean? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBoolean()
}
