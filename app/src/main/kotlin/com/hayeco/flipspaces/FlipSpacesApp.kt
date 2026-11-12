package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.hayeco.flipspaces.core.GlassesReady

/**
 * Starts the plug-in watcher with the process (0.4.3). Before, only a phone screen, the pointer
 * service or the hand tracking service started it, so a process Android or Shizuku started for
 * anything else (Shizuku hands its binder to the app through a content provider) watched no
 * displays: a background plug-in then opened nothing and logged nothing.
 *
 * 0.4.4: also starts [GlassesReadyService], which keeps the process awake so the glasses'
 * display event is delivered at all; Android may refuse it when the process starts in the
 * background, and then boot or the next phone screen starts it.
 */
class FlipSpacesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        started = this
        // Core's words in the phone's language: core names a string in strings.xml, read here at the time it is shown.
        com.hayeco.flipspaces.core.UiText.lookup = { key -> appString(key) }
        // 1.0.1: a crash is kept and offered the next time the app opens ([CrashCatcher]); first, so it catches the rest.
        CrashCatcher.install(this)
        // 1.1.10: the open screens, so Exit Pocket XR can finish each one.
        AppExit.track(this)
        // 0.5.28: the app's own rotating log file, before anything logs.
        AppLogFile.start(this)
        // 0.5.68: the bundled font, before the Space or the trackpad paints any text.
        com.hayeco.flipspaces.ui.LookType.init(this)
        GlassesAutoOpen.start(this)
        // 1.1.0: the add-on's pointer service and notification listener, when the add-on is installed.
        com.hayeco.flipspaces.platform.AddonLink.connect(this)
        GlassesReadyService.start(this, GlassesReady.From.APP_START)
        // 0.5.1: each plug-in's time, so the hand camera never asks over Android's plug-in prompt.
        com.hayeco.flipspaces.hands.UsbPrompt.register(this)
        // 1.2.10 (Discussion #24): VITURE glasses plugged in, unplugged, and the USB prompt's answer, for the head tracker.
        com.hayeco.flipspaces.spatial.HeadTracker.registerUsbWatch(this)
        // 0.5.9: settings a plain display session changed before the process stopped are put back.
        com.hayeco.flipspaces.spatial.PlainDisplaySession.restoreLeftOver(this)
        // 1.1.15: the GitHub update check at app start and every 12 hours, not only when Settings opens. Never on Play.
        UpdateChecker.start(this)
    }

    companion object {
        @Volatile private var started: Application? = null

        /**
         * The app, for text where no Context is at hand (a background object). Set first thing in [onCreate]; before
         * that, or where the process has another Application (the Robolectric tests), Android's current application.
         */
        val app: Application
            get() = started ?: currentApplication().also { started = it }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun currentApplication(): Application =
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application

        /** A string in the phone's language, for code with no Context of its own. */
        fun text(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

        /** A plural string in the phone's language, for code with no Context of its own. */
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = app.resources.getQuantityString(id, count, *args)

        /** The string named [key] in strings.xml, or null; for [com.hayeco.flipspaces.core.UiText]. */
        @SuppressLint("DiscouragedApi")
        private fun appString(key: String): String? {
            val res = app.resources
            val id = res.getIdentifier(key, "string", app.packageName)
            return if (id == 0) null else res.getString(id)
        }
    }
}
