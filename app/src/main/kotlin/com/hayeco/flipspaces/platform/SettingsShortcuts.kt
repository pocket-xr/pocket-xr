package com.hayeco.flipspaces.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.SettingsTarget

/** Opens the screen that switches the glasses from mirror to extended (DeX) mode. */
object SettingsShortcuts {

    /** First target whose screen exists on this phone, in [SettingsTarget.forMirror] order. */
    fun best(context: Context): SettingsTarget? =
        SettingsTarget.forMirror(WindowingProbe.dexInstalled(context)).firstOrNull { resolves(context, it) }

    /** Tries the target, then the ones after it. Returns an error text or null. */
    fun open(context: Context, target: SettingsTarget): String? {
        val order = SettingsTarget.forMirror(WindowingProbe.dexInstalled(context)).dropWhile { it != target }
        var lastError: String? = null
        for (candidate in order.ifEmpty { listOf(target) }) {
            val intent = intentFor(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return null
            } catch (e: ActivityNotFoundException) {
                lastError = e.message
            } catch (e: SecurityException) {
                lastError = e.message
            }
        }
        return context.getString(R.string.sys_settings_open_failed, lastError.toString())
    }

    private fun resolves(context: Context, target: SettingsTarget): Boolean =
        context.packageManager.resolveActivity(intentFor(target), 0) != null

    /**
     * DeX: Samsung's settings activity in the DeX package (Verify on One UI 9). External display:
     * the AOSP Android 16 action, present only on builds that ship it. Settings home always exists.
     */
    private fun intentFor(target: SettingsTarget): Intent = when (target) {
        SettingsTarget.DEX -> Intent().setComponent(
            ComponentName(WindowingProbe.DEX_PACKAGE, "com.sec.android.desktopmode.uiservice.activity.settings.SettingsActivity"),
        )
        SettingsTarget.EXTERNAL_DISPLAY -> Intent("android.settings.EXTERNAL_DISPLAY_SETTINGS")
        SettingsTarget.SETTINGS_HOME -> Intent(Settings.ACTION_SETTINGS)
    }
}
