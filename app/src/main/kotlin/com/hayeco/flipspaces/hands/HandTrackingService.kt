package com.hayeco.flipspaces.hands

import android.app.Notification
import com.hayeco.flipspaces.GlassesAutoOpen
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.hayeco.flipspaces.HandsActivity
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.hands.ProviderStatus

/**
 * Foreground service (type camera) that keeps the glasses camera and hand tracking running
 * while Mike looks at the glasses instead of the phone. Its notification has a Stop button,
 * which since 0.5.0 means "until the glasses are plugged in again" ([HandInputManager.autoStart]).
 */
class HandTrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 0.4.2: a running foreground service keeps the plug-in watcher alive too.
        GlassesAutoOpen.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 0.5.0: Stop means "until the next plug-in", not "until Mike presses Start hands".
            HandInputManager.pausedThisPlug = true
            android.util.Log.i("FlipSpacesHands", "Hands stopped from the notification; they start again with the next plug-in")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: RuntimeException) {
            // SecurityException without the CAMERA permission; ForegroundServiceStartNotAllowedException from the background.
            GlassesCameraProvider.markFailed(getString(R.string.hands_android_refused, e.message.toString()))
            stopSelf()
            return START_NOT_STICKY
        }
        GlassesCameraProvider.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        GlassesCameraProvider.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.hands_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HandsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HandTrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.hands_notif_title))
            .setContentText(getString(R.string.hands_notif_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.hands_notif_stop), stop).build())
            // 1.1.10: quit Pocket XR altogether from here too.
            .addAction(Notification.Action.Builder(null, com.hayeco.flipspaces.core.pointer.IntroPages.EXIT_LABEL, com.hayeco.flipspaces.GlassesReadyService.exitIntent(this)).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hands"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.hayeco.flipspaces.STOP_HANDS"

        /** Call from a visible activity: Android only lets camera services start from the foreground. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, HandTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandTrackingService::class.java))
        }

        fun isRunning(): Boolean = GlassesCameraProvider.running || GlassesCameraProvider.status.value is ProviderStatus.Running
    }
}
