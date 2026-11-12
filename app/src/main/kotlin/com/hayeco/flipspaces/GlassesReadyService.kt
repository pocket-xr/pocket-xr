package com.hayeco.flipspaces

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.hayeco.flipspaces.core.GlassesReady
import com.hayeco.flipspaces.core.pointer.IntroPages

/**
 * Keeps Pocket XR awake for the glasses (0.4.4, [GlassesReady]): a connectedDevice foreground
 * service with a quiet, low importance notification. While it runs the process is not cached,
 * so Android delivers the glasses' display added event to [GlassesAutoOpen] even with the
 * pointer service off and nothing of Pocket XR in front, and the plug-in check opens the
 * Space with the shell through Shizuku. Started at boot, after an update and with the app.
 */
class GlassesReadyService : Service() {
    override fun onCreate() {
        super.onCreate()
        GlassesAutoOpen.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1.1.10: the notification's Exit Pocket XR.
        if (intent?.action == ACTION_EXIT) {
            AppExit.exit(this, "the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            Log.i(TAG, "glasses ready service in the foreground; display events reach the plug-in watcher")
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException, or a SecurityException for a missing permission.
            Log.w(TAG, "glasses ready service not in the foreground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.home_channel_glasses_ready), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(GlassesReady.NOTIFICATION_TEXT)
            .setContentIntent(open)
            // 1.1.10 (a tester: the app "stays active in the notification bar"): a way to quit from here.
            .addAction(Notification.Action.Builder(null, IntroPages.EXIT_LABEL, exitIntent(this)).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FlipSpaces/Mode"
        private const val CHANNEL = "glasses_ready"
        private const val NOTIFICATION_ID = 8
        private const val ACTION_EXIT = "com.hayeco.flipspaces.EXIT"

        /** 1.1.10: Exit Pocket XR, for a notification action ([AppExit]). */
        fun exitIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 2, Intent(context, GlassesReadyService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE,
        )

        /** Starts the service; from the background Android may refuse ([GlassesReady.mayBeRefused]), which is logged. */
        fun start(context: Context, from: GlassesReady.From) {
            try {
                context.startForegroundService(Intent(context, GlassesReadyService::class.java))
            } catch (e: RuntimeException) {
                val note = if (GlassesReady.mayBeRefused(from)) "; the next phone screen or boot starts it" else ""
                Log.w(TAG, "glasses ready service not started (${from.name}): ${e.javaClass.simpleName}: ${e.message}$note")
            }
        }
    }
}

/** Starts [GlassesReadyService] after boot and after Pocket XR is updated (0.4.4). */
class GlassesReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GlassesReady.startsOn(intent.action)) return
        val from = if (intent.action == GlassesReady.ACTION_BOOT_COMPLETED) GlassesReady.From.BOOT else GlassesReady.From.UPDATE
        GlassesReadyService.start(context, from)
    }
}
