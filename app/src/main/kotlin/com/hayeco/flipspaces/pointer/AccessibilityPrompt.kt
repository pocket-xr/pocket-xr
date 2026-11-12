package com.hayeco.flipspaces.pointer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.platform.AddonLink

/**
 * 0.5.10: the one time phone prompt for the pointer accessibility service. DeX's full screen
 * button is in the desktop shell's caption, which only an accessibility service may press (no
 * app or shell call reaches it on One UI 8: 0.5.9's section 24.2). When the press finds the
 * service off, the phone shows this once (per install), with a button that opens the service's
 * own Accessibility page, else the Accessibility list.
 *
 * 1.1.0: the service is the Pocket XR add-on's ([AddonLink.POINTER]).
 *
 * The full screen task hold (0.5.7) needs no accessibility service and has no DeX taskbar; with
 * 0.5.10's check it is no longer undone by an "unknown" answer, so this prompt only matters when
 * the Space ends up in its DeX window.
 */
object AccessibilityPrompt {
    private const val TAG = "FlipSpaces/Mode"
    private const val CHANNEL = "accessibility_prompt"
    private const val NOTIFICATION_ID = 31
    private const val PREFS = "accessibility_prompt"
    private const val KEY_SHOWN = "shown"

    /** The hidden Settings action for one service's page (AOSP AccessibilityDetailsSettingsActivity). */
    private const val DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * The intent that opens the add-on's disclosure for the pointer service (1.1.11, Google Play: it goes on to the
     * service's Accessibility page once agreed), else that page, else the Accessibility list.
     */
    fun settingsIntent(context: Context): Intent {
        AddonLink.setupIntent(context, AddonLink.SETUP_POINTER)?.let { return it }
        val details = Intent(DETAILS_ACTION)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, AddonLink.POINTER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolves = runCatching { context.packageManager.resolveActivity(details, 0) != null }.getOrDefault(false)
        return if (resolves) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Shows the prompt unless it was shown before; [why] is what is missing without the service. */
    fun showOnce(context: Context, why: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sys_notif_channel_setup), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID, settingsIntent(context), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.sys_pointer_prompt_title))
                .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.sys_pointer_prompt_text, why)))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.sys_pointer_prompt_action), open).build())
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, n)
        }.onSuccess {
            Log.i(TAG, "phone prompt shown once: turn on the pointer accessibility service ($why)")
        }.onFailure {
            Log.w(TAG, "phone prompt for the pointer accessibility service not shown: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
